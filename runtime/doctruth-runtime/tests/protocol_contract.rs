use assert_cmd::Command;
use pdf_oxide::document::PdfDocument;
use pdf_oxide::rendering::{RenderOptions, render_page};
use predicates::prelude::*;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

static TEMP_FILE_COUNTER: AtomicU64 = AtomicU64::new(1);

fn parse_request(source_path: &Path) -> String {
    parse_request_with_hash(source_path, "sha256:test")
}

fn parse_request_with_hash(source_path: &Path, source_hash: &str) -> String {
    parse_request_with_hash_and_preset(source_path, source_hash, "lite")
}

fn parse_request_with_hash_and_preset(
    source_path: &Path,
    source_hash: &str,
    preset: &str,
) -> String {
    format!(
        r#"{{"command":"parse_pdf","source_path":"{}","source_hash":"{}","preset":"{}","offline_mode":true,"allow_model_downloads":false}}"#,
        source_path.display(),
        source_hash,
        preset
    )
}

fn vendored_opendataloader_pdf(name: &str) -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../third_party/opendataloader-bench/pdfs")
        .join(name)
}

fn looks_like_noisy_full_page_table(table: &Value) -> bool {
    let cells = table["cells"].as_array().cloned().unwrap_or_default();
    let noisy_text = cells.iter().any(|cell| {
        cell["text"]
            .as_str()
            .map(text_has_invalid_encoding_noise)
            .unwrap_or(false)
    });
    let large_span = cells.iter().any(|cell| {
        let row = &cell["rowRange"];
        let col = &cell["columnRange"];
        range_span(row) > 10 || range_span(col) > 10
    });
    noisy_text && large_span
}

fn looks_like_noisy_borderless_table(table: &Value) -> bool {
    let cells = table["cells"].as_array().cloned().unwrap_or_default();
    if cells.len() < 8 {
        return false;
    }
    let noisy = cells
        .iter()
        .filter(|cell| {
            cell["text"]
                .as_str()
                .map(text_has_invalid_encoding_noise)
                .unwrap_or(false)
        })
        .count();
    noisy * 2 >= cells.len()
}

fn range_span(range: &Value) -> u64 {
    let start = range["start"].as_u64().unwrap_or(0);
    let end = range["end"].as_u64().unwrap_or(start);
    end.saturating_sub(start) + 1
}

fn text_has_invalid_encoding_noise(text: &str) -> bool {
    text.chars()
        .any(|ch| ch == '\u{fffd}' || (ch.is_control() && !ch.is_whitespace()))
}

#[test]
fn doctor_reports_local_runtime_readiness() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    cmd.arg("--doctor")
        .assert()
        .success()
        .stdout(predicate::str::contains("\"runtime\":\"doctruth-runtime\""))
        .stdout(predicate::str::contains("\"local_first\":true"))
        .stdout(predicate::str::contains("\"protocol_version\":\"1\""))
        .stdout(predicate::str::contains("\"rssMb\":"))
        .stdout(predicate::str::contains("\"peakMemoryMb\":"))
        .stdout(predicate::str::contains("\"target\":\"pdf_oxide\""))
        .stdout(predicate::str::contains("\"status\":\"DEFAULT\""));
}

#[test]
fn doctor_reports_runtime_profiles_and_resource_gate_contract() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .arg("--doctor")
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let profiles = &json["profiles"];

    assert_eq!(profiles["recommendedProductionProfile"], "edge-fast");
    assert_eq!(profiles["defaultProtocolProfile"], "edge-model");
    assert_eq!(profiles["active"], "edge-model");
    assert_eq!(profiles["available"]["edge-fast"]["production"], true);
    assert_eq!(profiles["available"]["edge-fast"]["modelStartup"], false);
    assert_eq!(
        profiles["available"]["edge-fast"]["fallbackChains"],
        json!([])
    );
    assert_eq!(profiles["available"]["edge-model"]["modelRuntime"], "mnn");
    assert_eq!(
        profiles["available"]["edge-model"]["lazyModelStartup"],
        true
    );
    assert_eq!(
        profiles["available"]["edge-model"]["forbiddenResidency"],
        json!(["python", "torch", "docling"])
    );
    assert_eq!(
        profiles["available"]["benchmark-oracle"]["production"],
        false
    );
    assert_eq!(
        profiles["available"]["benchmark-oracle"]["requiresExplicitCommand"],
        true
    );
}

#[test]
fn doctor_reports_opendataloader_reference_stages_owned_by_rust() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .arg("--doctor")
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let stages = json["pdfBackend"]["referenceStages"]
        .as_array()
        .expect("reference stages should be listed");
    for expected in [
        "content-filter",
        "text-line",
        "xy-cut-plus-plus",
        "cluster-table",
        "table-structure-normalizer",
        "heading",
    ] {
        assert!(
            stages.iter().any(|stage| stage == expected),
            "missing OpenDataLoader reference stage {expected}: {stages:?}"
        );
    }
    assert_eq!(json["pdfBackend"]["canonicalOutput"], "TrustDocument");
}

#[test]
fn parse_pdf_rejects_benchmark_oracle_as_production_runtime_profile() {
    let pdf = write_pdf_fixture("Benchmark oracle is not production parse.");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    cmd.write_stdin(format!(
        r#"{{"command":"parse_pdf","source_path":"{}","source_hash":"sha256:benchmark-oracle-profile","preset":"lite","profile":"benchmark-oracle","offline_mode":true}}"#,
        pdf.display()
    ))
    .assert()
    .failure()
    .stderr(predicate::str::contains("PROFILE_NOT_SUPPORTED"))
    .stderr(predicate::str::contains("benchmark-oracle"));
}

#[test]
fn parse_pdf_reads_stdin_and_writes_trust_document_json() {
    let pdf = write_pdf_fixture("Rust sidecar extraction works.");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(json["docId"], "sha256:test");
    assert!(
        json["source"]["sourceFilename"]
            .as_str()
            .unwrap()
            .starts_with("doctruth-runtime-fixture-")
    );
    assert_eq!(json["source"]["sourceHash"], "sha256:test");
    assert_eq!(json["parserRun"]["backend"], "rust-sidecar");
    assert_eq!(json["parserRun"]["pdfBackend"]["target"], "pdf_oxide");
    assert_eq!(json["parserRun"]["pdfBackend"]["current"], "pdf_oxide");
    assert_eq!(json["parserRun"]["pdfBackend"]["status"], "DEFAULT");
    assert_eq!(
        json["parserRun"]["pdfBackend"]["canonicalOutput"],
        "TrustDocument"
    );
    assert_eq!(json["parserRun"]["preset"], "lite");
    assert_eq!(json["parserRun"]["profile"], "edge-model");
    assert_eq!(json["auditGradeStatus"], "AUDIT_GRADE");
    assert_eq!(json["body"]["pages"][0]["pageNumber"], 1);
    assert_eq!(json["body"]["pages"][0]["textLayerAvailable"], true);
    assert_eq!(json["body"]["units"][0]["kind"], "LINE_SPAN");
    assert_eq!(json["body"]["units"][0]["page"], 1);
    assert_eq!(json["body"]["units"][0]["location"]["readingOrder"], 1);
    assert_eq!(
        json["body"]["units"][0]["text"],
        "Rust sidecar extraction works."
    );
    assert_eq!(json["body"]["units"][0]["evidenceSpanIds"][0], "span-0001");
    assert!(json["body"]["units"][0]["location"]["boundingBox"].is_object());

    let warnings = json["parserRun"]["warnings"].as_array().unwrap();
    assert!(
        !warnings
            .iter()
            .any(|warning| warning["severity"] == "SEVERE")
    );
}

#[test]
fn parse_pdf_marks_model_assisted_preset_fallback_as_not_audit_grade() {
    let pdf = write_pdf_fixture("Model fallback evidence.");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request_with_hash_and_preset(
            &pdf,
            "sha256:model-fallback",
            "table-lite",
        ))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let warnings = json["parserRun"]["warnings"].as_array().unwrap();

    assert_eq!(json["parserRun"]["preset"], "table-lite");
    assert_eq!(json["auditGradeStatus"], "NOT_AUDIT_GRADE");
    assert_eq!(json["body"]["units"][0]["text"], "Model fallback evidence.");
    assert_eq!(json["parserRun"]["models"], json!(["slanet-plus:v1"]));
    assert!(
        warnings.iter().any(|warning| {
            warning["code"] == "model_unavailable_fallback"
                && warning["severity"] == "SEVERE"
                && warning["message"]
                    .as_str()
                    .is_some_and(|message| message.contains("slanet-plus:v1"))
        }),
        "expected severe model_unavailable_fallback warning with model identity, got {warnings:?}"
    );
}

#[test]
fn parse_pdf_filters_full_page_line_table_false_positive() {
    let pdf = vendored_opendataloader_pdf("01030000000146.pdf");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request_with_hash(
            &pdf,
            "sha256:invalid-text-encoding",
        ))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let warnings = json["parserRun"]["warnings"].as_array().unwrap();
    assert_eq!(json["auditGradeStatus"], "NOT_AUDIT_GRADE");
    assert!(
        warnings.iter().any(
            |warning| warning["code"] == "full_page_table_false_positive_filtered"
                && warning["severity"] == "SEVERE"
        ),
        "{warnings:?}"
    );

    let tables = json["body"]["tables"].as_array().unwrap();
    assert!(
        tables
            .iter()
            .all(|table| !looks_like_noisy_full_page_table(table)),
        "{tables:?}"
    );
}

#[test]
fn parse_pdf_filters_noisy_borderless_table_false_positive() {
    let pdf = vendored_opendataloader_pdf("01030000000101.pdf");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request_with_hash(
            &pdf,
            "sha256:noisy-borderless-table",
        ))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let warnings = json["parserRun"]["warnings"].as_array().unwrap();
    assert_eq!(json["auditGradeStatus"], "NOT_AUDIT_GRADE");
    assert!(
        warnings.iter().any(
            |warning| warning["code"] == "invalid_text_encoding_detected"
                && warning["severity"] == "SEVERE"
        ),
        "{warnings:?}"
    );

    let markdown = json["body"]["tables"].as_array().unwrap();
    assert!(
        markdown
            .iter()
            .all(|table| !looks_like_noisy_borderless_table(table)),
        "{markdown:?}"
    );
}

#[test]
fn parse_pdf_gracefully_falls_back_for_missing_layout_table_and_ocr_models() {
    for (preset, model_identities) in [
        ("standard", vec!["layout-rtdetr:v2"]),
        ("table-server", vec!["slanext-auto:v1"]),
        (
            "ocr",
            vec!["ppocr-v5-mobile-det:v0.1.3", "ppocr-v5-mobile-rec:v0.1.3"],
        ),
    ] {
        let pdf = write_pdf_fixture(&format!("Missing {preset} model fallback evidence."));
        let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

        let output = cmd
            .write_stdin(parse_request_with_hash_and_preset(
                &pdf,
                "sha256:model-missing",
                preset,
            ))
            .assert()
            .success()
            .get_output()
            .stdout
            .clone();

        let json: Value = serde_json::from_slice(&output).unwrap();
        let warnings = json["parserRun"]["warnings"].as_array().unwrap();

        assert_eq!(json["parserRun"]["preset"], preset);
        assert_eq!(json["auditGradeStatus"], "NOT_AUDIT_GRADE");
        assert!(
            json["body"]["units"][0]["text"]
                .as_str()
                .unwrap()
                .contains(preset)
        );
        for model_identity in model_identities {
            assert!(
                json["parserRun"]["models"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .any(|model| model == model_identity),
                "expected {model_identity} in parserRun.models for {preset}: {json}"
            );
            assert!(
                warnings.iter().any(|warning| {
                    warning["code"] == "model_unavailable_fallback"
                        && warning["severity"] == "SEVERE"
                        && warning["message"]
                            .as_str()
                            .is_some_and(|message| message.contains(model_identity))
                }),
                "expected severe missing-model warning for {preset}/{model_identity}, got {warnings:?}"
            );
        }
    }
}

#[test]
fn parse_pdf_keeps_page_level_units_for_multi_page_text_layer_pdf() {
    let pdf = write_pdf_fixture_with_pages(&["First page evidence.", "Second page evidence."]);
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(json["source"]["metadata"]["pageCount"], 2);
    assert_eq!(json["body"]["pages"].as_array().unwrap().len(), 2);
    assert_eq!(json["body"]["units"].as_array().unwrap().len(), 2);
    assert_eq!(json["body"]["units"][0]["page"], 1);
    assert_eq!(json["body"]["units"][0]["location"]["readingOrder"], 1);
    assert_eq!(json["body"]["units"][0]["text"], "First page evidence.");
    assert_eq!(json["body"]["units"][1]["page"], 2);
    assert_eq!(json["body"]["units"][1]["location"]["readingOrder"], 2);
    assert_eq!(json["body"]["units"][1]["text"], "Second page evidence.");
}

#[test]
fn parse_pdf_emits_line_level_units_for_single_page_text_layer_pdf() {
    let pdf = write_pdf_fixture_with_lines(&["First citeable line.", "Second citeable line."]);
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let units = json["body"]["units"].as_array().unwrap();
    assert_eq!(units.len(), 2);
    assert_eq!(units[0]["kind"], "LINE_SPAN");
    assert_eq!(units[0]["page"], 1);
    assert_eq!(units[0]["location"]["readingOrder"], 1);
    assert_eq!(units[0]["text"], "First citeable line.");
    assert_eq!(
        units[0]["sourceObjectId"],
        "runtime-text-layer-page-1-line-1"
    );
    assert_eq!(units[1]["kind"], "LINE_SPAN");
    assert_eq!(units[1]["page"], 1);
    assert_eq!(units[1]["location"]["readingOrder"], 2);
    assert_eq!(units[1]["text"], "Second citeable line.");
    assert_eq!(
        units[1]["sourceObjectId"],
        "runtime-text-layer-page-1-line-2"
    );
}

#[test]
fn parse_pdf_emits_flat_content_blocks_in_reading_order() {
    let pdf = write_pdf_fixture_with_lines(&["PROFILE", "Evidence body line."]);
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let blocks = json["contentBlocks"].as_array().unwrap();

    assert_eq!(blocks.len(), 2);
    assert_eq!(blocks[0]["blockId"], "block-0001");
    assert_eq!(blocks[0]["type"], "heading");
    assert_eq!(blocks[0]["textLevel"], 2);
    assert_eq!(blocks[0]["page"], 1);
    assert_eq!(blocks[0]["readingOrder"], 1);
    assert_eq!(blocks[0]["text"], "PROFILE");
    assert_eq!(blocks[0]["normalizedText"], "PROFILE");
    assert_eq!(blocks[0]["sourceUnitIds"], json!(["unit-0001"]));
    assert_eq!(blocks[0]["evidenceSpanIds"], json!(["span-0001"]));
    assert!(blocks[0]["bbox"].is_object());
    assert_eq!(blocks[1]["blockId"], "block-0002");
    assert_eq!(blocks[1]["type"], "text");
    assert_eq!(blocks[1]["textLevel"], Value::Null);
    assert_eq!(blocks[1]["readingOrder"], 2);
    assert_eq!(blocks[1]["text"], "Evidence body line.");
    assert_eq!(blocks[1]["normalizedText"], "Evidence body line.");
    assert_eq!(blocks[1]["sourceUnitIds"], json!(["unit-0002"]));
    assert_eq!(blocks[1]["evidenceSpanIds"], json!(["span-0002"]));
}

#[test]
fn parse_pdf_classifies_list_items_before_heading_rules() {
    let pdf = write_pdf_fixture_with_lines(&["SKILLS", "- Rust parser core", "1. Evidence replay"]);
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let blocks = json["contentBlocks"].as_array().unwrap();
    let trace_blocks = json["parseTrace"]["pages"][0]["readingBlocks"]
        .as_array()
        .unwrap();

    assert_eq!(blocks[0]["type"], "heading");
    assert_eq!(blocks[0]["textLevel"], 2);
    assert_eq!(blocks[1]["type"], "list");
    assert_eq!(blocks[1]["textLevel"], Value::Null);
    assert_eq!(blocks[2]["type"], "list");
    assert_eq!(blocks[2]["textLevel"], Value::Null);
    assert_eq!(trace_blocks[1]["type"], "list");
    assert_eq!(trace_blocks[2]["type"], "list");
}

#[test]
fn parse_pdf_does_not_promote_year_lead_sentence_to_heading() {
    let pdf = write_pdf_fixture_with_lines(&[
        "Filipino Women in Electoral Politics",
        "1935 Constitution. The reluctance was expected because only 21-year-",
        "old Filipino men had been allowed to vote during the time.",
    ]);
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let blocks = json["contentBlocks"].as_array().unwrap();

    assert_eq!(blocks[0]["type"], "heading");
    assert_eq!(blocks[1]["type"], "text");
    assert_eq!(blocks[1]["textLevel"], Value::Null);
    assert_eq!(blocks[1]["sectionId"], blocks[0]["sectionId"]);
    assert_eq!(blocks[2]["sectionId"], blocks[0]["sectionId"]);
}

#[test]
fn parse_pdf_does_not_promote_single_titlecase_entity_to_heading() {
    let pdf = write_pdf_fixture_with_lines(&[
        "I. Introduction",
        "Belgium, France, Germany, Ireland, Japan, the Netherlands.",
        "Germany",
    ]);
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let blocks = json["contentBlocks"].as_array().unwrap();

    assert_eq!(blocks[0]["type"], "heading");
    assert_eq!(blocks[1]["type"], "text");
    assert_eq!(blocks[2]["type"], "text");
    assert_eq!(blocks[2]["textLevel"], Value::Null);
    assert_eq!(blocks[2]["sectionId"], blocks[0]["sectionId"]);
}

#[test]
fn parse_pdf_promotes_common_single_word_section_heading() {
    let pdf = write_pdf_fixture_with_lines(&["Contents", "1. Front Matter 1"]);
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let blocks = json["contentBlocks"].as_array().unwrap();

    assert_eq!(blocks[0]["type"], "heading");
    assert_eq!(blocks[0]["textLevel"], 3);
    assert_eq!(blocks[0]["text"], "Contents");
    assert_eq!(blocks[1]["sectionId"], blocks[0]["sectionId"]);
}

#[test]
fn parse_pdf_does_not_promote_opendataloader_bullet_fragments_to_headings() {
    let pdf = opendataloader_fixture("01030000000195.pdf");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let heading_texts: Vec<&str> = json["contentBlocks"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|block| block["type"] == "heading")
        .filter_map(|block| block["text"].as_str())
        .collect();

    for fragment in [
        "•",
        "Introduction",
        "SOLAR",
        "Billion-",
        ": We",
        "Instruction-Following",
        "Ca-",
        "and Wonsung",
        "with Dahyun Kim, Wonho",
        "Evaluation (Data-Centric LLM) part, with Yungi",
    ] {
        assert!(
            !heading_texts.contains(&fragment),
            "unexpected heading fragment {fragment:?} in {heading_texts:?}"
        );
    }
    assert!(
        heading_texts
            .iter()
            .any(|text| text.starts_with("B.1 ") || text.starts_with("B.2 ")),
        "expected real numbered section heading in {heading_texts:?}"
    );
}

#[test]
fn parse_pdf_merges_opendataloader_split_heading_lines() {
    let pdf = opendataloader_fixture("01030000000195.pdf");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let heading_texts: Vec<&str> = json["contentBlocks"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|block| block["type"] == "heading")
        .filter_map(|block| block["text"].as_str())
        .collect();

    for expected in [
        "B Related Works and Background",
        "B.1 Large Language Models",
        "B.2 Mixture of Experts",
    ] {
        assert!(
            heading_texts.contains(&expected),
            "expected merged heading {expected:?} in {heading_texts:?}"
        );
    }
    for fragment in ["B", "B.1", "B.2"] {
        assert!(
            !heading_texts.contains(&fragment),
            "unexpected standalone heading marker {fragment:?} in {heading_texts:?}"
        );
    }
}

#[test]
fn parse_pdf_merges_numeric_opendataloader_heading_lines() {
    let pdf = opendataloader_fixture("01030000000001.pdf");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let heading_texts: Vec<&str> = json["contentBlocks"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|block| block["type"] == "heading")
        .filter_map(|block| block["text"].as_str())
        .collect();

    assert!(
        heading_texts.contains(&"7 Variants of sj Observer Models"),
        "expected merged numeric section heading in {heading_texts:?}"
    );
    assert!(
        !heading_texts.contains(&"\u{00ad}"),
        "soft hyphen must not become a heading in {heading_texts:?}"
    );
}

#[test]
fn parse_pdf_promotes_opendataloader_numbered_section_headings() {
    let cases = [
        ("01030000000036.pdf", "2. General Profile of MSMEs"),
        (
            "01030000000038.pdf",
            "6.2. Expectations for Re-Hiring Employees",
        ),
    ];

    for (fixture, expected_heading) in cases {
        let pdf = opendataloader_fixture(fixture);
        let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

        let output = cmd
            .write_stdin(parse_request(&pdf))
            .assert()
            .success()
            .get_output()
            .stdout
            .clone();

        let json: Value = serde_json::from_slice(&output).unwrap();
        let heading_texts: Vec<&str> = json["contentBlocks"]
            .as_array()
            .unwrap()
            .iter()
            .filter(|block| block["type"] == "heading")
            .filter_map(|block| block["text"].as_str())
            .collect();

        assert!(
            heading_texts.contains(&expected_heading),
            "expected numbered section heading {expected_heading:?} in {heading_texts:?}"
        );
    }
}

#[test]
fn parse_pdf_does_not_emit_full_page_single_cell_line_table() {
    let pdf = opendataloader_fixture("01030000000029.pdf");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let tables = json["body"]["tables"].as_array().unwrap();
    for table in tables {
        let cells = table["cells"].as_array().unwrap();
        let method = table["method"].as_str().unwrap_or_default();
        let text = cells
            .iter()
            .filter_map(|cell| cell["text"].as_str())
            .collect::<Vec<_>>()
            .join(" ");
        assert!(
            !(method == "line-table" && cells.len() == 1 && text.contains("5.Thedynamics")),
            "full-page prose must not leak as a single line-table cell: {table:?}"
        );
    }
}

#[test]
fn parse_pdf_merges_dotted_numeric_opendataloader_heading_lines() {
    let pdf = opendataloader_fixture("01030000000029.pdf");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let heading_texts: Vec<&str> = json["contentBlocks"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|block| block["type"] == "heading")
        .filter_map(|block| block["text"].as_str())
        .collect();

    for expected in ["5. The dynamics", "6. Modeling the dynamics"] {
        assert!(
            heading_texts.contains(&expected),
            "expected dotted numeric heading {expected:?} in {heading_texts:?}"
        );
    }
}

#[test]
fn parse_pdf_promotes_centered_chapter_number_and_title_headings() {
    let pdf = opendataloader_fixture("01030000000021.pdf");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let blocks = json["contentBlocks"].as_array().unwrap();

    assert_eq!(blocks[0]["text"], "2");
    assert_eq!(blocks[0]["type"], "heading");
    assert_eq!(blocks[0]["textLevel"], 1);
    assert_eq!(blocks[1]["text"], "The Lost Homeland");
    assert_eq!(blocks[1]["type"], "heading");
    assert_eq!(blocks[1]["textLevel"], 1);
    assert_eq!(blocks[2]["type"], "text");
}

#[test]
fn parse_pdf_emits_opendataloader_party_registration_table() {
    let pdf = opendataloader_fixture("01030000000047.pdf");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let tables = json["body"]["tables"].as_array().unwrap();
    let table = tables
        .iter()
        .find(|table| {
            table["cells"].as_array().is_some_and(|cells| {
                cells
                    .iter()
                    .any(|cell| cell["text"] == "Khmer United Party")
            })
        })
        .unwrap_or_else(|| panic!("expected party registration table in {tables:?}"));
    let cells = table["cells"].as_array().unwrap();
    assert_eq!(table["quality"]["columnCount"], 7);
    assert!(
        table["boundingBox"]["y0"].as_f64().unwrap() < 205.0,
        "party table bbox should cover the header rows: {table:?}"
    );
    for expected in [
        "No.",
        "Political party",
        "Provisional registration result on 7 March",
        "Official registration result on 29 April",
        "Difference in the number of candidates",
        "Khmer United Party",
        "35",
        "498",
        "30",
        "457",
        "-41",
        "Total",
        "84,208",
        "86,092",
        "+1,884",
    ] {
        assert!(
            cells.iter().any(|cell| cell["text"] == expected),
            "expected table cell {expected:?} in {cells:?}"
        );
    }
    let total_cells = cells
        .iter()
        .filter(|cell| cell["rowRange"]["start"].as_u64() == Some(9))
        .collect::<Vec<_>>();
    assert_eq!(
        total_cells.len(),
        7,
        "expected total row to preserve empty cells"
    );
    assert!(
        total_cells
            .iter()
            .any(|cell| cell["columnRange"]["start"].as_u64() == Some(0) && cell["text"] == ""),
        "expected empty first total-row cell in {total_cells:?}"
    );
}

#[test]
fn parse_pdf_keeps_opendataloader_party_registration_continuation_rows() {
    let pdf = opendataloader_fixture("01030000000046.pdf");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let table = json["body"]["tables"]
        .as_array()
        .unwrap()
        .iter()
        .find(|table| {
            table["cells"].as_array().is_some_and(|cells| {
                cells
                    .iter()
                    .any(|cell| cell["text"] == "Cambodian People’s Party")
            })
        })
        .unwrap_or_else(|| panic!("expected party table in {:?}", json["body"]["tables"]));
    let cells = table["cells"].as_array().unwrap();

    assert_eq!(table["quality"]["columnCount"], 7);
    assert_eq!(table["quality"]["rowCount"], 12);
    for expected in [
        "Khmer Will Party",
        "67",
        "1,000",
        "58",
        "1,050",
        "+50",
        "Cambodian Reform Party",
        "Kampucheaniyum Party",
        "+16",
    ] {
        assert!(
            cells.iter().any(|cell| cell["text"] == expected),
            "expected continuation cell {expected:?} in {cells:?}"
        );
    }
}

#[test]
fn parse_pdf_does_not_emit_full_page_spanned_line_table_cell() {
    let pdf = opendataloader_fixture("01030000000041.pdf");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let units = json["body"]["units"].as_array().unwrap();
    let line_text = units
        .iter()
        .filter(|unit| unit["kind"] == "LINE_SPAN")
        .filter_map(|unit| unit["text"].as_str())
        .collect::<Vec<_>>()
        .join(" ");

    assert!(
        line_text.contains("tweets, videos) inciting violence"),
        "normal text lines should remain available: {line_text}"
    );

    for unit in units.iter().filter(|unit| unit["kind"] == "TABLE_CELL") {
        let bbox = &unit["location"]["boundingBox"];
        let text = unit["text"].as_str().unwrap_or_default();
        let row_range = &unit["rowRange"];
        let column_range = &unit["columnRange"];
        let full_page =
            bbox["x0"] == 0.0 && bbox["y0"] == 0.0 && bbox["x1"] == 1000.0 && bbox["y1"] == 1000.0;
        let spanned = row_range["end"].as_u64().unwrap_or(0)
            > row_range["start"].as_u64().unwrap_or(0)
            || column_range["end"].as_u64().unwrap_or(0)
                > column_range["start"].as_u64().unwrap_or(0);

        assert!(
            !(full_page && spanned && text.contains("Figure 3: Frequency")),
            "full-page prose/chart text must not leak as a spanned line-table cell: {unit:?}"
        );
    }
}

#[test]
fn parse_pdf_orders_opendataloader_two_column_body_by_column_regions() {
    let pdf = opendataloader_fixture("01030000000037.pdf");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let texts = json["body"]["units"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|unit| unit["kind"] == "LINE_SPAN")
        .filter_map(|unit| unit["text"].as_str())
        .collect::<Vec<_>>();
    let pos = |needle: &str| {
        texts
            .iter()
            .position(|text| text.contains(needle))
            .unwrap_or_else(|| panic!("missing {needle:?} in {texts:?}"))
    };

    assert!(
        pos("3.1. Status of Business Operations") < pos("course of the research period"),
        "left-column subsection should appear before right-column continuation: {texts:?}"
    );
    assert!(
        pos("“working as usual” gradually increased over the")
            < pos("course of the research period"),
        "left-column paragraph should not be row-interleaved with right column: {texts:?}"
    );
}

#[test]
fn parse_pdf_merges_vertical_numbered_heading_fragments() {
    let pdf = opendataloader_fixture("01030000000003.pdf");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let blocks = json["contentBlocks"].as_array().unwrap();
    let headings = blocks
        .iter()
        .filter(|block| block["type"] == "heading")
        .map(|block| block["text"].as_str().unwrap_or(""))
        .collect::<Vec<_>>();

    assert!(
        headings.contains(&"11 Dual-Presentation SJ Data"),
        "expected vertically split numbered heading in {headings:?}"
    );
    for fragment in ["11", "Dual-Presentation", "sj", "Data", "Arnold, 2011"] {
        assert!(
            !headings.contains(&fragment),
            "heading fragment {fragment:?} should be merged: {headings:?}"
        );
    }
}

#[test]
fn parse_pdf_merges_same_line_number_marker_heading() {
    let pdf = opendataloader_fixture("01030000000028.pdf");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let blocks = json["contentBlocks"].as_array().unwrap();
    let headings = blocks
        .iter()
        .filter(|block| block["type"] == "heading")
        .map(|block| block["text"].as_str().unwrap_or(""))
        .collect::<Vec<_>>();

    assert!(
        headings.contains(&"4. Entropy"),
        "expected same-line numeric marker heading in {headings:?}"
    );
    for fragment in ["4.", "Entropy", "1. A", "2. A"] {
        assert!(
            !headings.contains(&fragment),
            "unexpected heading fragment {fragment:?}: {headings:?}"
        );
    }
}

#[test]
fn parse_pdf_does_not_promote_page_header_number_as_heading() {
    let pdf = opendataloader_fixture("01030000000048.pdf");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let blocks = json["contentBlocks"].as_array().unwrap();
    let headings = blocks
        .iter()
        .filter(|block| block["type"] == "heading")
        .map(|block| block["text"].as_str().unwrap_or(""))
        .collect::<Vec<_>>();

    assert!(
        !headings.contains(&"8 Encinas Franco and Laguna"),
        "page header must not become a section heading: {headings:?}"
    );
    assert!(
        headings.contains(&"Filipino Women in Electoral Politics"),
        "main title should remain a heading: {headings:?}"
    );
}

#[test]
fn parse_pdf_emits_table_of_contents_rows_for_split_page_numbers() {
    let pdf = opendataloader_fixture("01030000000016.pdf");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let tables = json["body"]["tables"].as_array().unwrap();
    let table = tables
        .iter()
        .find(|table| {
            table["cells"]
                .as_array()
                .is_some_and(|cells| cells.iter().any(|cell| cell["text"] == "Table of Contents"))
        })
        .unwrap_or_else(|| panic!("expected TOC table in {tables:?}"));
    let cells = table["cells"].as_array().unwrap();

    assert_eq!(table["quality"]["columnCount"], 2);
    assert!(table["quality"]["rowCount"].as_u64().unwrap() >= 18);
    for expected in [
        "Introduction",
        "7",
        "1. Changing Practices, Shifting Sites",
        "7",
        "12. A 21st-century Dollhouse: The Sims",
        "83",
        "13. Unwanted Play Practices in The Sims Online",
        "94",
        "Index",
        "153",
    ] {
        assert!(
            cells.iter().any(|cell| cell["text"] == expected),
            "expected TOC cell {expected:?} in {cells:?}"
        );
    }
}

#[test]
fn parse_pdf_merges_split_title_line_and_rejects_body_fragments_as_headings() {
    let pdf = opendataloader_fixture("01030000000033.pdf");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let blocks = json["contentBlocks"].as_array().unwrap();
    assert!(
        blocks.iter().any(|block| {
            block["type"] == "heading"
                && block["text"] == "Functional Abstraction"
                && block["textLevel"] == 3
        }),
        "expected split title line to become one heading block: {blocks:?}"
    );
    assert!(
        !blocks
            .iter()
            .any(|block| { block["type"] == "heading" && block["text"] == "Nothing would" }),
        "body fragment should not be promoted as heading: {blocks:?}"
    );
}

#[test]
fn parse_pdf_does_not_promote_inline_math_fragments_to_headings() {
    let pdf = opendataloader_fixture("01030000000031.pdf");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let blocks = json["contentBlocks"].as_array().unwrap();
    let headings = blocks
        .iter()
        .filter(|block| block["type"] == "heading")
        .map(|block| block["text"].as_str().unwrap_or(""))
        .collect::<Vec<_>>();

    assert!(
        headings.contains(&"8. Numerical computations in the combinatorial multiverse"),
        "expected real numbered section heading in {headings:?}"
    );
    for fragment in [
        "P",
        "P þP",
        "W and",
        "P , P and P",
        "A , we can compute the",
        "S ¼",
        "W",
        ". Although the picture clearly supports the claim that",
    ] {
        assert!(
            !headings.contains(&fragment),
            "math/body fragment {fragment:?} should not be a heading: {headings:?}"
        );
    }
}

#[test]
fn parse_pdf_merges_multiline_headings_and_rejects_parenthetical_body_fragments() {
    for (fixture, expected_heading, rejected_heading) in [
        (
            "01030000000019.pdf",
            "Author’s Note to the 2021 Edition",
            "(edited by Emily Turner-Graham and Christine Winter, Peter",
        ),
        (
            "01030000000039.pdf",
            "9.5. Adapting to the New Normal: Changing Business Models",
            "Business Models",
        ),
    ] {
        let pdf = opendataloader_fixture(fixture);
        let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

        let output = cmd
            .write_stdin(parse_request(&pdf))
            .assert()
            .success()
            .get_output()
            .stdout
            .clone();

        let json: Value = serde_json::from_slice(&output).unwrap();
        let headings = json["contentBlocks"]
            .as_array()
            .unwrap()
            .iter()
            .filter(|block| block["type"] == "heading")
            .map(|block| block["text"].as_str().unwrap_or(""))
            .collect::<Vec<_>>();

        assert!(
            headings.contains(&expected_heading),
            "expected merged heading {expected_heading:?} in {headings:?}"
        );
        assert!(
            !headings.contains(&rejected_heading),
            "unexpected standalone/false heading {rejected_heading:?} in {headings:?}"
        );
    }
}

#[test]
fn parse_pdf_does_not_promote_footnote_and_hyphen_continuations_to_headings() {
    let pdf = opendataloader_fixture("01030000000013.pdf");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let headings = json["contentBlocks"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|block| block["type"] == "heading")
        .map(|block| block["text"].as_str().unwrap_or(""))
        .collect::<Vec<_>>();

    assert!(
        headings.contains(&"4 Al-Sadu Symbols and Social Significance"),
        "expected real chapter heading in {headings:?}"
    );
    for fragment in [
        "24 Quite",
        "graphic Codes",
        "nical Values",
        "International Design Journal",
    ] {
        assert!(
            !headings.iter().any(|heading| heading.contains(fragment)),
            "footnote/hyphen continuation should not be a heading: {fragment:?} in {headings:?}"
        );
    }
}

#[test]
fn parse_pdf_emits_section_hierarchy_for_heading_blocks() {
    let pdf = write_pdf_fixture_with_lines(&[
        "PROFILE",
        "Career Summary",
        "Evidence body line.",
        "SKILLS",
        "- Rust parser core",
    ]);
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let blocks = json["contentBlocks"].as_array().unwrap();
    let section_tree = json["parseTrace"]["sectionTree"].as_array().unwrap();
    let trace_blocks = json["parseTrace"]["pages"][0]["readingBlocks"]
        .as_array()
        .unwrap();

    assert_eq!(section_tree.len(), 2);
    assert_eq!(section_tree[0]["sectionId"], "section-0001");
    assert_eq!(section_tree[0]["title"], "PROFILE");
    assert_eq!(section_tree[0]["textLevel"], 2);
    assert_eq!(section_tree[0]["blockId"], "block-0001");
    assert_eq!(section_tree[0]["children"][0]["sectionId"], "section-0002");
    assert_eq!(section_tree[0]["children"][0]["title"], "Career Summary");
    assert_eq!(section_tree[1]["sectionId"], "section-0003");
    assert_eq!(section_tree[1]["title"], "SKILLS");

    assert_eq!(blocks[0]["type"], "heading");
    assert_eq!(blocks[0]["sectionId"], "section-0001");
    assert_eq!(blocks[0]["parentSectionId"], Value::Null);
    assert_eq!(blocks[0]["sectionPath"], json!(["section-0001"]));
    assert_eq!(blocks[0]["sectionTitlePath"], json!(["PROFILE"]));
    assert_eq!(blocks[0]["isSectionRoot"], true);

    assert_eq!(blocks[1]["type"], "heading");
    assert_eq!(blocks[1]["textLevel"], 3);
    assert_eq!(blocks[1]["sectionId"], "section-0002");
    assert_eq!(blocks[1]["parentSectionId"], "section-0001");
    assert_eq!(
        blocks[1]["sectionPath"],
        json!(["section-0001", "section-0002"])
    );
    assert_eq!(
        blocks[1]["sectionTitlePath"],
        json!(["PROFILE", "Career Summary"])
    );

    assert_eq!(blocks[2]["type"], "text");
    assert_eq!(blocks[2]["sectionId"], "section-0002");
    assert_eq!(
        blocks[2]["sectionPath"],
        json!(["section-0001", "section-0002"])
    );
    assert_eq!(blocks[2]["isSectionRoot"], false);

    assert_eq!(blocks[3]["type"], "heading");
    assert_eq!(blocks[3]["sectionId"], "section-0003");
    assert_eq!(blocks[3]["parentSectionId"], Value::Null);
    assert_eq!(blocks[3]["sectionPath"], json!(["section-0003"]));
    assert_eq!(blocks[4]["type"], "list");
    assert_eq!(blocks[4]["sectionId"], "section-0003");
    assert_eq!(trace_blocks[1]["sectionPath"], blocks[1]["sectionPath"]);
    assert_eq!(trace_blocks[2]["sectionId"], "section-0002");
}

#[test]
fn parse_pdf_exposes_core_table_quality_and_cell_ranges() {
    let pdf = write_bordered_table_pdf_fixture();
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let table = &json["body"]["tables"][0];
    let table_units: Vec<&Value> = json["body"]["units"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|unit| unit["kind"] == "TABLE_CELL")
        .collect();

    assert_eq!(table["method"], "line-table");
    assert_eq!(table["quality"]["rowCount"], 2);
    assert_eq!(table["quality"]["columnCount"], 2);
    assert_eq!(table["quality"]["filledCellCount"], 4);
    assert_eq!(table_units[0]["tableId"], table["tableId"]);
    assert_eq!(table_units[0]["rowRange"], json!({"start": 0, "end": 0}));
    assert_eq!(table_units[0]["columnRange"], json!({"start": 0, "end": 0}));
}

#[test]
fn parse_pdf_emits_parse_trace_with_block_line_span_links() {
    let pdf = write_pdf_fixture_with_lines(&["Trace first line.", "Trace second line."]);
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let trace = &json["parseTrace"];
    let page = &trace["pages"][0];
    let blocks = page["readingBlocks"].as_array().unwrap();

    assert_eq!(trace["parserRunId"], json["parserRun"]["parserRunId"]);
    assert_eq!(page["pageIndex"], 0);
    assert_eq!(page["pageNumber"], 1);
    assert_eq!(page["pageSize"], json!({"width": 612.0, "height": 792.0}));
    assert!(page["preprocBlocks"].as_array().unwrap().is_empty());
    assert!(page["discardedBlocks"].as_array().unwrap().is_empty());
    assert_eq!(blocks.len(), 2);
    assert_eq!(blocks[0]["blockId"], "block-0001");
    assert_eq!(blocks[0]["sourceUnitIds"], json!(["unit-0001"]));
    assert_eq!(blocks[0]["evidenceSpanIds"], json!(["span-0001"]));
    assert_eq!(blocks[0]["lines"][0]["lineId"], "line-0001");
    assert_eq!(blocks[0]["lines"][0]["text"], "Trace first line.");
    assert_eq!(
        blocks[0]["lines"][0]["spans"][0]["spanId"],
        "trace-span-0001"
    );
    assert_eq!(
        blocks[0]["lines"][0]["spans"][0]["content"],
        "Trace first line."
    );
    assert_eq!(
        blocks[0]["lines"][0]["spans"][0]["sourceObjectId"],
        "runtime-text-layer-page-1-line-1"
    );
    assert_eq!(
        blocks[0]["lines"][0]["spans"][0]["evidenceSpanId"],
        "span-0001"
    );
    assert!(blocks[0]["lines"][0]["spans"][0]["bbox"].is_object());
    assert_eq!(blocks[1]["blockId"], "block-0002");
    assert_eq!(blocks[1]["lines"][0]["lineId"], "line-0002");
    assert_eq!(
        blocks[1]["lines"][0]["spans"][0]["evidenceSpanId"],
        "span-0002"
    );
}

#[test]
fn parse_pdf_emits_page_text_spans_for_geometry_algorithms() {
    let pdf = write_pdf_fixture_with_lines(&["Geometry first line.", "Geometry second line."]);
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let page = &json["parseTrace"]["pages"][0];
    let spans = page["textSpans"].as_array().unwrap();
    let units = json["body"]["units"].as_array().unwrap();

    assert_eq!(spans.len(), 2);
    assert_eq!(spans[0]["spanId"], "trace-span-0001");
    assert_eq!(spans[0]["content"], "Geometry first line.");
    assert_eq!(spans[0]["type"], "text");
    assert_eq!(spans[0]["page"], 1);
    assert_eq!(spans[0]["readingOrder"], 1);
    assert_eq!(
        spans[0]["sourceObjectId"],
        "runtime-text-layer-page-1-line-1"
    );
    assert_eq!(spans[0]["evidenceSpanId"], "span-0001");
    assert!(spans[0]["bbox"]["x0"].as_f64().unwrap() > 0.0);
    assert!(spans[0]["bbox"]["x1"].as_f64().unwrap() > spans[0]["bbox"]["x0"].as_f64().unwrap());
    assert_eq!(spans[1]["spanId"], "trace-span-0002");
    assert_eq!(spans[1]["content"], "Geometry second line.");
    assert_eq!(units[0]["parseTraceSpanIds"], json!(["trace-span-0001"]));
    assert_eq!(units[1]["parseTraceSpanIds"], json!(["trace-span-0002"]));
}

#[test]
fn parse_pdf_emits_positioned_text_bboxes_when_content_stream_positions_are_available() {
    let pdf = write_pdf_fixture("Positioned text evidence.");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let unit = &json["body"]["units"][0];
    let bbox = &unit["location"]["boundingBox"];
    let warnings = unit["warnings"].as_array().unwrap();

    assert_eq!(unit["kind"], "LINE_SPAN");
    assert_eq!(unit["text"], "Positioned text evidence.");
    assert!(bbox["x0"].as_f64().unwrap() > 0.0);
    assert!(bbox["y0"].as_f64().unwrap() > 0.0);
    assert!(bbox["x1"].as_f64().unwrap() < 1000.0);
    assert!(bbox["y1"].as_f64().unwrap() < 1000.0);
    assert!(
        !warnings
            .iter()
            .any(|warning| warning["code"] == "runtime_bbox_page_fallback")
    );
}

#[test]
fn parse_pdf_uses_media_box_page_dimensions_and_stable_page_hash() {
    let pdf = write_custom_media_box_pdf_fixture();
    let mut first_cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let mut second_cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let first_output = first_cmd
        .write_stdin(parse_request_with_hash(&pdf, "sha256:first-source"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let second_output = second_cmd
        .write_stdin(parse_request_with_hash(&pdf, "sha256:second-source"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let first_json: Value = serde_json::from_slice(&first_output).unwrap();
    let second_json: Value = serde_json::from_slice(&second_output).unwrap();
    let first_page = &first_json["body"]["pages"][0];
    let second_page = &second_json["body"]["pages"][0];

    assert_eq!(first_page["width"].as_f64().unwrap(), 300.0);
    assert_eq!(first_page["height"].as_f64().unwrap(), 400.0);
    assert_eq!(first_page["imageHash"], second_page["imageHash"]);
    let image_hash = first_page["imageHash"].as_str().unwrap();
    assert!(image_hash.starts_with("sha256:"));
    assert_eq!(image_hash.len(), "sha256:".len() + 64);
    assert!(!image_hash.contains("first-source"));
    assert!(!image_hash.contains("second-source"));
}

#[test]
fn parse_pdf_uses_pdf_oxide_rendered_png_hash_by_default() {
    let pdf = write_pdf_fixture("pdf oxide render hash evidence.");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(
        json["body"]["pages"][0]["imageHash"],
        pdf_oxide_rendered_hash(&pdf, 0)
    );
}

#[test]
fn parse_pdf_uses_configured_rendered_png_hash_for_page_image_metadata() {
    let pdf = write_pdf_fixture("Rendered PNG hash evidence.");
    let renderer = write_fake_page_renderer();
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .env("DOCTRUTH_RUNTIME_PAGE_RENDERER", &renderer)
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(
        json["body"]["pages"][0]["imageHash"],
        rendered_fixture_hash(1)
    );
}

#[test]
fn parse_pdf_orders_two_column_positioned_text_by_visual_columns() {
    let pdf = write_two_column_pdf_fixture();
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let units = json["body"]["units"].as_array().unwrap();
    let texts: Vec<&str> = units
        .iter()
        .filter(|unit| unit["kind"] == "LINE_SPAN")
        .map(|unit| unit["text"].as_str().unwrap())
        .collect();
    let orders: Vec<i64> = units
        .iter()
        .filter(|unit| unit["kind"] == "LINE_SPAN")
        .map(|unit| unit["location"]["readingOrder"].as_i64().unwrap())
        .collect();

    assert_eq!(
        texts,
        vec![
            "LEFT PROFILE",
            "Left column evidence.",
            "RIGHT EXPERIENCE",
            "Right column evidence."
        ]
    );
    assert_eq!(orders, vec![1, 2, 3, 4]);
    assert!(
        units[0]["location"]["boundingBox"]["x0"].as_f64().unwrap()
            < units[2]["location"]["boundingBox"]["x0"].as_f64().unwrap()
    );
}

#[test]
fn parse_pdf_filters_duplicate_positioned_text_and_marks_not_audit_grade() {
    let pdf = write_duplicate_text_pdf_fixture();
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let line_units: Vec<&Value> = json["body"]["units"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|unit| unit["kind"] == "LINE_SPAN")
        .collect();
    let duplicate_count = line_units
        .iter()
        .filter(|unit| unit["text"] == "Duplicate overlay evidence.")
        .count();
    let warnings = json["parserRun"]["warnings"].as_array().unwrap();

    assert_eq!(duplicate_count, 1);
    assert_eq!(json["auditGradeStatus"], "NOT_AUDIT_GRADE");
    assert!(warnings.iter().any(|warning| {
        warning["code"] == "duplicate_text_filtered"
            && warning["severity"] == "SEVERE"
            && warning["message"]
                .as_str()
                .unwrap()
                .contains("Duplicate overlay evidence.")
    }));
}

#[test]
fn parse_pdf_filters_off_page_tiny_whitespace_and_background_text() {
    let pdf = write_safety_filter_pdf_fixture();
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let units = json["body"]["units"].as_array().unwrap();
    let texts: Vec<&str> = units
        .iter()
        .filter(|unit| unit["kind"] == "LINE_SPAN")
        .map(|unit| unit["text"].as_str().unwrap())
        .collect();
    let warnings = json["parserRun"]["warnings"].as_array().unwrap();

    assert_eq!(texts, vec!["Visible evidence."]);
    assert_eq!(json["auditGradeStatus"], "NOT_AUDIT_GRADE");
    assert_warning(warnings, "off_page_text_filtered", "Off page evidence");
    assert_warning(warnings, "tiny_text_filtered", "Tiny evidence");
    assert_warning(warnings, "background_text_filtered", "Background evidence");
    assert_warning(warnings, "whitespace_text_filtered", "whitespace-only");
}

#[test]
fn parse_pdf_prefers_trustworthy_tagged_structure_tree_order() {
    let pdf = write_tagged_structure_order_pdf_fixture();
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let texts: Vec<&str> = json["body"]["units"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|unit| unit["kind"] == "LINE_SPAN")
        .map(|unit| unit["text"].as_str().unwrap())
        .collect();

    assert_eq!(texts, vec!["Logical first.", "Logical second."]);
    assert_eq!(
        json["parserRun"]["readingOrder"]["source"],
        "structure-tree"
    );
    assert_eq!(json["parserRun"]["readingOrder"]["fallback"], false);
    assert_eq!(
        json["parseTrace"]["readingOrder"]["source"],
        "structure-tree"
    );
    assert_eq!(json["auditGradeStatus"], "AUDIT_GRADE");
}

#[test]
fn parse_pdf_falls_back_when_tagged_structure_tree_is_suspect() {
    let pdf = write_suspect_tagged_structure_order_pdf_fixture();
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let texts: Vec<&str> = json["body"]["units"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|unit| unit["kind"] == "LINE_SPAN")
        .map(|unit| unit["text"].as_str().unwrap())
        .collect();
    let warnings = json["parserRun"]["warnings"].as_array().unwrap();

    assert_eq!(texts, vec!["Logical second.", "Logical first."]);
    assert_eq!(json["parserRun"]["readingOrder"]["source"], "xy-cut");
    assert_eq!(json["parserRun"]["readingOrder"]["fallback"], true);
    assert_warning_with_severity(
        warnings,
        "structure_tree_suspect_fallback",
        "Suspects true",
        "WARNING",
    );
    assert_eq!(json["auditGradeStatus"], "AUDIT_GRADE");
}

#[test]
fn parse_pdf_emits_table_cells_for_bordered_grid_pdf() {
    let pdf = write_bordered_table_pdf_fixture();
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let tables = json["body"]["tables"].as_array().unwrap();
    let units = json["body"]["units"].as_array().unwrap();
    let table_units: Vec<&Value> = units
        .iter()
        .filter(|unit| unit["kind"] == "TABLE_CELL")
        .collect();

    assert_eq!(tables.len(), 1);
    assert_eq!(tables[0]["cells"].as_array().unwrap().len(), 4);
    assert_eq!(table_units.len(), 4);
    assert_eq!(tables[0]["cells"][0]["text"], "Name");
    assert_eq!(tables[0]["cells"][1]["text"], "Score");
    assert_eq!(tables[0]["cells"][2]["text"], "Alex");
    assert_eq!(tables[0]["cells"][3]["text"], "98");
    assert!(tables[0]["boundingBox"].is_object());
    for cell in tables[0]["cells"].as_array().unwrap() {
        assert!(cell["boundingBox"].is_object());
    }
    for unit in table_units {
        assert!(unit["location"]["boundingBox"].is_object());
        assert_eq!(
            unit["confidence"]["rationale"],
            "pdf_oxide line-table extraction"
        );
    }
}

#[test]
fn parse_pdf_filters_invisible_render_mode_text() {
    let pdf = write_invisible_text_pdf_fixture();
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let line_texts: Vec<&str> = json["body"]["units"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|unit| unit["kind"] == "LINE_SPAN")
        .map(|unit| unit["text"].as_str().unwrap())
        .collect();
    let warnings = json["parserRun"]["warnings"].as_array().unwrap();

    assert_eq!(line_texts, vec!["Visible evidence."]);
    assert_warning_with_severity(
        warnings,
        "hidden_text_filtered",
        "Invisible evidence",
        "SEVERE",
    );
    assert_eq!(json["auditGradeStatus"], "NOT_AUDIT_GRADE");
}

#[test]
fn parse_pdf_uses_pdf_oxide_text_spatial_table_detection_for_borderless_table() {
    let pdf = write_borderless_table_pdf_fixture();
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let tables = json["body"]["tables"].as_array().unwrap();
    let cells = tables[0]["cells"].as_array().unwrap();

    assert_eq!(tables.len(), 1);
    assert_eq!(tables[0]["method"], "cluster");
    assert_eq!(
        tables[0]["confidence"]["rationale"],
        "pdf_oxide text-spatial table extraction"
    );
    assert!(cells.iter().any(|cell| cell["text"] == "Name"));
    assert!(cells.iter().any(|cell| cell["text"] == "Score"));
    assert!(cells.iter().any(|cell| cell["text"] == "Alex"));
    assert!(cells.iter().any(|cell| cell["text"] == "98"));
}

#[test]
fn parse_pdf_does_not_emit_figure_caption_page_as_spatial_table() {
    let pdf = opendataloader_fixture("01030000000027.pdf");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let tables = json["body"]["tables"].as_array().unwrap();
    let table_units = json["body"]["units"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|unit| unit["kind"] == "TABLE_CELL")
        .count();
    let line_texts = json["body"]["units"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|unit| unit["kind"] == "LINE_SPAN")
        .map(|unit| unit["text"].as_str().unwrap_or(""))
        .collect::<Vec<_>>();

    assert!(
        tables.is_empty(),
        "figure/chart captions must not be emitted as a TrustTable: {tables:?}"
    );
    assert_eq!(table_units, 0);
    assert!(line_texts.contains(&"Figure"));
    assert!(line_texts.contains(&"Estimated cumulative damage for impeller blades."));
}

#[test]
fn parse_pdf_merges_figure_caption_fragments() {
    let pdf = opendataloader_fixture("01030000000027.pdf");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let blocks = json["contentBlocks"].as_array().unwrap();
    let texts = blocks
        .iter()
        .map(|block| block["text"].as_str().unwrap_or(""))
        .collect::<Vec<_>>();

    for expected in [
        "Figure 7. Estimated cumulative damage for impeller blades.",
        "Figure 8. Estimated residual life of impeller blades by the criterion of cracking.",
        "Figure 9. Estimated residual life of impeller blades at the stage of crack development.",
    ] {
        assert!(
            texts.contains(&expected),
            "expected merged figure caption {expected:?} in {texts:?}"
        );
    }
    for fragment in ["Figure", "7.", "8.", "9."] {
        assert!(
            !texts.contains(&fragment),
            "figure caption fragment {fragment:?} should be merged: {texts:?}"
        );
    }
}

#[test]
fn parse_pdf_preserves_horizontal_merged_cell_column_span() {
    let pdf = write_merged_table_pdf_fixture();
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let tables = json["body"]["tables"].as_array().unwrap();
    let units = json["body"]["units"].as_array().unwrap();
    let table_units: Vec<&Value> = units
        .iter()
        .filter(|unit| unit["kind"] == "TABLE_CELL")
        .collect();

    assert_eq!(tables.len(), 1);
    assert_eq!(tables[0]["cells"].as_array().unwrap().len(), 3);
    assert_eq!(table_units.len(), 3);
    assert_eq!(tables[0]["cells"][0]["text"], "Header");
    assert_eq!(
        tables[0]["cells"][0]["rowRange"],
        json!({"start": 0, "end": 0})
    );
    assert_eq!(
        tables[0]["cells"][0]["columnRange"],
        json!({"start": 0, "end": 1})
    );
    assert_eq!(tables[0]["cells"][1]["text"], "A");
    assert_eq!(
        tables[0]["cells"][1]["columnRange"],
        json!({"start": 0, "end": 0})
    );
    assert_eq!(tables[0]["cells"][2]["text"], "B");
    assert_eq!(
        tables[0]["cells"][2]["columnRange"],
        json!({"start": 1, "end": 1})
    );
    for cell in tables[0]["cells"].as_array().unwrap() {
        assert!(cell["boundingBox"].is_object());
    }
    for unit in table_units {
        assert!(unit["location"]["boundingBox"].is_object());
    }
}

#[test]
fn parse_pdf_preserves_vertical_merged_cell_row_span() {
    let pdf = write_row_span_table_pdf_fixture();
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let tables = json["body"]["tables"].as_array().unwrap();
    let units = json["body"]["units"].as_array().unwrap();
    let table_units: Vec<&Value> = units
        .iter()
        .filter(|unit| unit["kind"] == "TABLE_CELL")
        .collect();

    assert_eq!(tables.len(), 1);
    assert_eq!(tables[0]["cells"].as_array().unwrap().len(), 3);
    assert_eq!(table_units.len(), 3);
    assert_eq!(tables[0]["cells"][0]["text"], "Role");
    assert_eq!(
        tables[0]["cells"][0]["rowRange"],
        json!({"start": 0, "end": 1})
    );
    assert_eq!(
        tables[0]["cells"][0]["columnRange"],
        json!({"start": 0, "end": 0})
    );
    assert_eq!(tables[0]["cells"][1]["text"], "Top");
    assert_eq!(
        tables[0]["cells"][1]["rowRange"],
        json!({"start": 0, "end": 0})
    );
    assert_eq!(
        tables[0]["cells"][1]["columnRange"],
        json!({"start": 1, "end": 1})
    );
    assert_eq!(tables[0]["cells"][2]["text"], "Bottom");
    assert_eq!(
        tables[0]["cells"][2]["rowRange"],
        json!({"start": 1, "end": 1})
    );
    assert_eq!(
        tables[0]["cells"][2]["columnRange"],
        json!({"start": 1, "end": 1})
    );
    for cell in tables[0]["cells"].as_array().unwrap() {
        assert!(cell["boundingBox"].is_object());
    }
    for unit in table_units {
        assert!(unit["location"]["boundingBox"].is_object());
    }
}

#[test]
fn parse_pdf_merges_multi_page_table_continuation_with_repeated_header() {
    let pdf = write_continued_table_pdf_fixture();
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let tables = json["body"]["tables"].as_array().unwrap();
    let units = json["body"]["units"].as_array().unwrap();
    let table_units: Vec<&Value> = units
        .iter()
        .filter(|unit| unit["kind"] == "TABLE_CELL")
        .collect();

    assert_eq!(tables.len(), 1);
    assert_eq!(tables[0]["pageNumber"], 1);
    assert_eq!(
        tables[0]["cells"]
            .as_array()
            .unwrap()
            .iter()
            .map(|cell| cell["text"].as_str().unwrap())
            .collect::<Vec<_>>(),
        vec!["Name", "Score", "Alex", "98", "Bea", "97"]
    );
    assert_eq!(table_units.len(), 6);
    assert_eq!(table_units[4]["text"], "Bea");
    assert_eq!(table_units[4]["location"]["page"], 2);
    assert_eq!(table_units[5]["text"], "97");
    assert_eq!(table_units[5]["location"]["page"], 2);
}

#[test]
fn unknown_command_fails_with_stable_error_json() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(r#"{"command":"unknown"}"#)
        .assert()
        .failure()
        .code(2)
        .get_output()
        .stderr
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(json["error_code"], "UNKNOWN_COMMAND");
    assert_eq!(json["runtime"], "doctruth-runtime");
}

#[test]
fn invalid_json_fails_with_stable_error_json() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin("not-json")
        .assert()
        .failure()
        .code(2)
        .get_output()
        .stderr
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(json["error_code"], "INVALID_REQUEST_JSON");
    assert_eq!(json["runtime"], "doctruth-runtime");
}

#[test]
fn missing_pdf_fails_with_stable_error_json() {
    let missing = std::env::temp_dir().join("doctruth-runtime-missing.pdf");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&missing))
        .assert()
        .failure()
        .code(2)
        .get_output()
        .stderr
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(json["error_code"], "PDF_EXTRACTION_FAILED");
    assert_eq!(json["runtime"], "doctruth-runtime");
}

fn write_pdf_fixture(text: &str) -> PathBuf {
    let path = temp_pdf_path("doctruth-runtime-fixture");
    fs::write(&path, minimal_pdf(&[text])).unwrap();
    path
}

fn write_pdf_fixture_with_pages(pages: &[&str]) -> PathBuf {
    let path = temp_pdf_path("doctruth-runtime-multipage-fixture");
    fs::write(&path, minimal_pdf(pages)).unwrap();
    path
}

fn write_pdf_fixture_with_lines(lines: &[&str]) -> PathBuf {
    let path = temp_pdf_path("doctruth-runtime-lines-fixture");
    fs::write(&path, minimal_pdf_with_lines(lines)).unwrap();
    path
}

fn write_custom_media_box_pdf_fixture() -> PathBuf {
    let path = temp_pdf_path("doctruth-runtime-custom-media-box-fixture");
    fs::write(&path, minimal_custom_media_box_pdf()).unwrap();
    path
}

fn write_bordered_table_pdf_fixture() -> PathBuf {
    let path = temp_pdf_path("doctruth-runtime-bordered-table-fixture");
    fs::write(&path, minimal_bordered_table_pdf()).unwrap();
    path
}

fn write_merged_table_pdf_fixture() -> PathBuf {
    let path = temp_pdf_path("doctruth-runtime-merged-table-fixture");
    fs::write(&path, minimal_merged_table_pdf()).unwrap();
    path
}

fn write_row_span_table_pdf_fixture() -> PathBuf {
    let path = temp_pdf_path("doctruth-runtime-row-span-table-fixture");
    fs::write(&path, minimal_row_span_table_pdf()).unwrap();
    path
}

fn write_continued_table_pdf_fixture() -> PathBuf {
    let path = temp_pdf_path("doctruth-runtime-continued-table-fixture");
    fs::write(&path, minimal_continued_table_pdf()).unwrap();
    path
}

fn write_two_column_pdf_fixture() -> PathBuf {
    let path = temp_pdf_path("doctruth-runtime-two-column-fixture");
    fs::write(&path, minimal_two_column_pdf()).unwrap();
    path
}

fn opendataloader_fixture(name: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../third_party/opendataloader-bench/pdfs")
        .join(name)
}

fn write_duplicate_text_pdf_fixture() -> PathBuf {
    let path = temp_pdf_path("doctruth-runtime-duplicate-text-fixture");
    fs::write(&path, minimal_duplicate_text_pdf()).unwrap();
    path
}

fn write_invisible_text_pdf_fixture() -> PathBuf {
    let path = temp_pdf_path("doctruth-runtime-invisible-text-fixture");
    fs::write(&path, minimal_invisible_text_pdf()).unwrap();
    path
}

fn write_safety_filter_pdf_fixture() -> PathBuf {
    let path = temp_pdf_path("doctruth-runtime-safety-filter-fixture");
    fs::write(&path, minimal_safety_filter_pdf()).unwrap();
    path
}

fn write_borderless_table_pdf_fixture() -> PathBuf {
    let path = temp_pdf_path("doctruth-runtime-borderless-table-fixture");
    fs::write(&path, minimal_borderless_table_pdf()).unwrap();
    path
}

fn write_tagged_structure_order_pdf_fixture() -> PathBuf {
    let path = temp_pdf_path("doctruth-runtime-tagged-structure-order-fixture");
    fs::write(&path, minimal_tagged_structure_order_pdf(false)).unwrap();
    path
}

fn write_suspect_tagged_structure_order_pdf_fixture() -> PathBuf {
    let path = temp_pdf_path("doctruth-runtime-suspect-tagged-structure-fixture");
    fs::write(&path, minimal_tagged_structure_order_pdf(true)).unwrap();
    path
}

fn assert_warning(warnings: &[Value], code: &str, message_part: &str) {
    assert_warning_with_severity(warnings, code, message_part, "SEVERE");
}

fn assert_warning_with_severity(
    warnings: &[Value],
    code: &str,
    message_part: &str,
    severity: &str,
) {
    assert!(
        warnings.iter().any(|warning| {
            warning["code"] == code
                && warning["severity"] == severity
                && warning["message"].as_str().unwrap().contains(message_part)
        }),
        "missing {severity} warning {code} containing {message_part}; got {warnings:?}"
    );
}

fn write_fake_page_renderer() -> PathBuf {
    let path = temp_pdf_path("doctruth-runtime-fake-page-renderer").with_extension("sh");
    fs::write(
        &path,
        "#!/usr/bin/env sh\nset -eu\nprintf '\\211PNG\\r\\n\\032\\nfake-page-%s' \"$2\" > \"$3\"\n",
    )
    .unwrap();
    let mut permissions = fs::metadata(&path).unwrap().permissions();
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        permissions.set_mode(0o755);
        fs::set_permissions(&path, permissions).unwrap();
    }
    path
}

fn rendered_fixture_hash(page_number: usize) -> String {
    let mut bytes = b"\x89PNG\r\n\x1a\nfake-page-".to_vec();
    bytes.extend_from_slice(page_number.to_string().as_bytes());
    format!("sha256:{:x}", Sha256::digest(&bytes))
}

fn pdf_oxide_rendered_hash(path: &Path, page_index: usize) -> String {
    let document = PdfDocument::open(path).unwrap();
    let image = render_page(&document, page_index, &RenderOptions::with_dpi(72)).unwrap();
    assert!(image.data.starts_with(b"\x89PNG\r\n\x1a\n"));
    format!("sha256:{:x}", Sha256::digest(&image.data))
}

fn temp_pdf_path(prefix: &str) -> PathBuf {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let sequence = TEMP_FILE_COUNTER.fetch_add(1, Ordering::Relaxed);
    std::env::temp_dir().join(format!(
        "{prefix}-{}-{nanos}-{sequence}.pdf",
        std::process::id()
    ))
}

fn minimal_pdf(pages: &[&str]) -> Vec<u8> {
    let mut objects = vec![
        "<< /Type /Catalog /Pages 2 0 R >>".to_string(),
        String::new(),
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_string(),
    ];
    let page_tree_index = 1;

    let mut page_refs = Vec::new();
    for text in pages {
        let page_object_number = objects.len() + 1;
        let stream_object_number = objects.len() + 2;
        page_refs.push(format!("{page_object_number} 0 R"));
        objects.push(format!(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents {stream_object_number} 0 R >>"
        ));
        let escaped = text
            .replace('\\', "\\\\")
            .replace('(', "\\(")
            .replace(')', "\\)");
        let stream = format!("BT\n/F1 24 Tf\n72 720 Td\n({escaped}) Tj\nET\n");
        objects.push(format!(
            "<< /Length {} >>\nstream\n{}endstream",
            stream.len(),
            stream
        ));
    }
    objects[page_tree_index] = format!(
        "<< /Type /Pages /Kids [{}] /Count {} >>",
        page_refs.join(" "),
        pages.len()
    );

    let mut pdf = b"%PDF-1.4\n".to_vec();
    let mut offsets = Vec::new();
    for (index, object) in objects.iter().enumerate() {
        offsets.push(pdf.len());
        pdf.extend_from_slice(format!("{} 0 obj\n{}\nendobj\n", index + 1, object).as_bytes());
    }
    let xref_offset = pdf.len();
    pdf.extend_from_slice(
        format!("xref\n0 {}\n0000000000 65535 f \n", objects.len() + 1).as_bytes(),
    );
    for offset in offsets {
        pdf.extend_from_slice(format!("{offset:010} 00000 n \n").as_bytes());
    }
    pdf.extend_from_slice(
        format!(
            "trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{}\n%%EOF\n",
            objects.len() + 1,
            xref_offset
        )
        .as_bytes(),
    );
    pdf
}

fn minimal_pdf_with_lines(lines: &[&str]) -> Vec<u8> {
    let mut stream = "BT\n/F1 24 Tf\n72 720 Td\n".to_string();
    for (index, line) in lines.iter().enumerate() {
        if index > 0 {
            stream.push_str("0 -30 Td\n");
        }
        let escaped = line
            .replace('\\', "\\\\")
            .replace('(', "\\(")
            .replace(')', "\\)");
        stream.push_str(&format!("({escaped}) Tj\n"));
    }
    stream.push_str("ET\n");

    let objects = [
        "<< /Type /Catalog /Pages 2 0 R >>".to_string(),
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_string(),
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>".to_string(),
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_string(),
        format!("<< /Length {} >>\nstream\n{}endstream", stream.len(), stream),
    ];
    let mut pdf = b"%PDF-1.4\n".to_vec();
    let mut offsets = Vec::new();
    for (index, object) in objects.iter().enumerate() {
        offsets.push(pdf.len());
        pdf.extend_from_slice(format!("{} 0 obj\n{}\nendobj\n", index + 1, object).as_bytes());
    }
    let xref_offset = pdf.len();
    pdf.extend_from_slice(
        format!("xref\n0 {}\n0000000000 65535 f \n", objects.len() + 1).as_bytes(),
    );
    for offset in offsets {
        pdf.extend_from_slice(format!("{offset:010} 00000 n \n").as_bytes());
    }
    pdf.extend_from_slice(
        format!(
            "trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{}\n%%EOF\n",
            objects.len() + 1,
            xref_offset
        )
        .as_bytes(),
    );
    pdf
}

fn minimal_custom_media_box_pdf() -> Vec<u8> {
    let stream = "BT\n/F1 18 Tf\n40 340 Td\n(Custom page size evidence.) Tj\nET\n";
    let objects = [
        "<< /Type /Catalog /Pages 2 0 R >>".to_string(),
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_string(),
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>".to_string(),
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_string(),
        format!("<< /Length {} >>\nstream\n{}endstream", stream.len(), stream),
    ];
    let mut pdf = b"%PDF-1.4\n".to_vec();
    let mut offsets = Vec::new();
    for (index, object) in objects.iter().enumerate() {
        offsets.push(pdf.len());
        pdf.extend_from_slice(format!("{} 0 obj\n{}\nendobj\n", index + 1, object).as_bytes());
    }
    let xref_offset = pdf.len();
    pdf.extend_from_slice(
        format!("xref\n0 {}\n0000000000 65535 f \n", objects.len() + 1).as_bytes(),
    );
    for offset in offsets {
        pdf.extend_from_slice(format!("{offset:010} 00000 n \n").as_bytes());
    }
    pdf.extend_from_slice(
        format!(
            "trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{}\n%%EOF\n",
            objects.len() + 1,
            xref_offset
        )
        .as_bytes(),
    );
    pdf
}

fn minimal_bordered_table_pdf() -> Vec<u8> {
    let stream = "\
q
72 720 m
360 720 l
360 640 l
72 640 l
72 720 l
S
216 720 m
216 640 l
S
72 680 m
360 680 l
S
BT
/F1 16 Tf
90 695 Td
(Name) Tj
144 0 Td
(Score) Tj
-144 -40 Td
(Alex) Tj
144 0 Td
(98) Tj
ET
Q
";
    let objects = [
        "<< /Type /Catalog /Pages 2 0 R >>".to_string(),
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_string(),
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>".to_string(),
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_string(),
        format!("<< /Length {} >>\nstream\n{}endstream", stream.len(), stream),
    ];
    let mut pdf = b"%PDF-1.4\n".to_vec();
    let mut offsets = Vec::new();
    for (index, object) in objects.iter().enumerate() {
        offsets.push(pdf.len());
        pdf.extend_from_slice(format!("{} 0 obj\n{}\nendobj\n", index + 1, object).as_bytes());
    }
    let xref_offset = pdf.len();
    pdf.extend_from_slice(
        format!("xref\n0 {}\n0000000000 65535 f \n", objects.len() + 1).as_bytes(),
    );
    for offset in offsets {
        pdf.extend_from_slice(format!("{offset:010} 00000 n \n").as_bytes());
    }
    pdf.extend_from_slice(
        format!(
            "trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{}\n%%EOF\n",
            objects.len() + 1,
            xref_offset
        )
        .as_bytes(),
    );
    pdf
}

fn minimal_borderless_table_pdf() -> Vec<u8> {
    let stream = "\
BT
/F1 16 Tf
72 720 Td
(Name) Tj
120 0 Td
(Score) Tj
120 0 Td
(Team) Tj
-240 -40 Td
(Alex) Tj
120 0 Td
(98) Tj
120 0 Td
(Red) Tj
-240 -40 Td
(Blair) Tj
120 0 Td
(87) Tj
120 0 Td
(Blue) Tj
ET
";
    minimal_single_stream_pdf(stream)
}

fn minimal_merged_table_pdf() -> Vec<u8> {
    let stream = "\
q
72 720 m
360 720 l
360 640 l
72 640 l
72 720 l
S
72 680 m
360 680 l
S
216 680 m
216 640 l
S
BT
/F1 16 Tf
155 695 Td
(Header) Tj
-35 -40 Td
(A) Tj
145 0 Td
(B) Tj
ET
Q
";
    let objects = [
        "<< /Type /Catalog /Pages 2 0 R >>".to_string(),
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_string(),
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>".to_string(),
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_string(),
        format!("<< /Length {} >>\nstream\n{}endstream", stream.len(), stream),
    ];
    let mut pdf = b"%PDF-1.4\n".to_vec();
    let mut offsets = Vec::new();
    for (index, object) in objects.iter().enumerate() {
        offsets.push(pdf.len());
        pdf.extend_from_slice(format!("{} 0 obj\n{}\nendobj\n", index + 1, object).as_bytes());
    }
    let xref_offset = pdf.len();
    pdf.extend_from_slice(
        format!("xref\n0 {}\n0000000000 65535 f \n", objects.len() + 1).as_bytes(),
    );
    for offset in offsets {
        pdf.extend_from_slice(format!("{offset:010} 00000 n \n").as_bytes());
    }
    pdf.extend_from_slice(
        format!(
            "trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{}\n%%EOF\n",
            objects.len() + 1,
            xref_offset
        )
        .as_bytes(),
    );
    pdf
}

fn minimal_row_span_table_pdf() -> Vec<u8> {
    let stream = "\
q
72 720 m
360 720 l
360 640 l
72 640 l
72 720 l
S
216 720 m
216 640 l
S
216 680 m
360 680 l
S
BT
/F1 16 Tf
120 675 Td
(Role) Tj
145 20 Td
(Top) Tj
-10 -40 Td
(Bottom) Tj
ET
Q
";
    let objects = [
        "<< /Type /Catalog /Pages 2 0 R >>".to_string(),
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_string(),
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>".to_string(),
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_string(),
        format!("<< /Length {} >>\nstream\n{}endstream", stream.len(), stream),
    ];
    let mut pdf = b"%PDF-1.4\n".to_vec();
    let mut offsets = Vec::new();
    for (index, object) in objects.iter().enumerate() {
        offsets.push(pdf.len());
        pdf.extend_from_slice(format!("{} 0 obj\n{}\nendobj\n", index + 1, object).as_bytes());
    }
    let xref_offset = pdf.len();
    pdf.extend_from_slice(
        format!("xref\n0 {}\n0000000000 65535 f \n", objects.len() + 1).as_bytes(),
    );
    for offset in offsets {
        pdf.extend_from_slice(format!("{offset:010} 00000 n \n").as_bytes());
    }
    pdf.extend_from_slice(
        format!(
            "trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{}\n%%EOF\n",
            objects.len() + 1,
            xref_offset
        )
        .as_bytes(),
    );
    pdf
}

fn minimal_continued_table_pdf() -> Vec<u8> {
    let page_one = bordered_table_stream("Alex", "98");
    let page_two = bordered_table_stream("Bea", "97");
    let mut objects = vec![
        "<< /Type /Catalog /Pages 2 0 R >>".to_string(),
        String::new(),
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_string(),
    ];
    let mut page_refs = Vec::new();
    for stream in [page_one, page_two] {
        let page_object_number = objects.len() + 1;
        let stream_object_number = objects.len() + 2;
        page_refs.push(format!("{page_object_number} 0 R"));
        objects.push(format!(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents {stream_object_number} 0 R >>"
        ));
        objects.push(format!(
            "<< /Length {} >>\nstream\n{}endstream",
            stream.len(),
            stream
        ));
    }
    objects[1] = format!(
        "<< /Type /Pages /Kids [{}] /Count 2 >>",
        page_refs.join(" ")
    );
    let mut pdf = b"%PDF-1.4\n".to_vec();
    let mut offsets = Vec::new();
    for (index, object) in objects.iter().enumerate() {
        offsets.push(pdf.len());
        pdf.extend_from_slice(format!("{} 0 obj\n{}\nendobj\n", index + 1, object).as_bytes());
    }
    let xref_offset = pdf.len();
    pdf.extend_from_slice(
        format!("xref\n0 {}\n0000000000 65535 f \n", objects.len() + 1).as_bytes(),
    );
    for offset in offsets {
        pdf.extend_from_slice(format!("{offset:010} 00000 n \n").as_bytes());
    }
    pdf.extend_from_slice(
        format!(
            "trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{}\n%%EOF\n",
            objects.len() + 1,
            xref_offset
        )
        .as_bytes(),
    );
    pdf
}

fn bordered_table_stream(name: &str, score: &str) -> String {
    format!(
        "\
q
72 720 m
360 720 l
360 640 l
72 640 l
72 720 l
S
216 720 m
216 640 l
S
72 680 m
360 680 l
S
BT
/F1 16 Tf
90 695 Td
(Name) Tj
144 0 Td
(Score) Tj
-144 -40 Td
({name}) Tj
144 0 Td
({score}) Tj
ET
Q
"
    )
}

fn minimal_two_column_pdf() -> Vec<u8> {
    let stream = "\
BT
/F1 16 Tf
72 720 Td
(LEFT PROFILE) Tj
260 0 Td
(RIGHT EXPERIENCE) Tj
-260 -30 Td
(Left column evidence.) Tj
260 0 Td
(Right column evidence.) Tj
ET
";
    let objects = [
        "<< /Type /Catalog /Pages 2 0 R >>".to_string(),
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_string(),
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>".to_string(),
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_string(),
        format!("<< /Length {} >>\nstream\n{}endstream", stream.len(), stream),
    ];
    let mut pdf = b"%PDF-1.4\n".to_vec();
    let mut offsets = Vec::new();
    for (index, object) in objects.iter().enumerate() {
        offsets.push(pdf.len());
        pdf.extend_from_slice(format!("{} 0 obj\n{}\nendobj\n", index + 1, object).as_bytes());
    }
    let xref_offset = pdf.len();
    pdf.extend_from_slice(
        format!("xref\n0 {}\n0000000000 65535 f \n", objects.len() + 1).as_bytes(),
    );
    for offset in offsets {
        pdf.extend_from_slice(format!("{offset:010} 00000 n \n").as_bytes());
    }
    pdf.extend_from_slice(
        format!(
            "trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{}\n%%EOF\n",
            objects.len() + 1,
            xref_offset
        )
        .as_bytes(),
    );
    pdf
}

fn minimal_duplicate_text_pdf() -> Vec<u8> {
    let stream = "\
BT
/F1 16 Tf
72 720 Td
(Duplicate overlay evidence.) Tj
0 -10 Td
(Duplicate overlay evidence.) Tj
0 -20 Td
(Unique evidence.) Tj
ET
";
    let objects = [
        "<< /Type /Catalog /Pages 2 0 R >>".to_string(),
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_string(),
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>".to_string(),
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_string(),
        format!("<< /Length {} >>\nstream\n{}endstream", stream.len(), stream),
    ];
    let mut pdf = b"%PDF-1.4\n".to_vec();
    let mut offsets = Vec::new();
    for (index, object) in objects.iter().enumerate() {
        offsets.push(pdf.len());
        pdf.extend_from_slice(format!("{} 0 obj\n{}\nendobj\n", index + 1, object).as_bytes());
    }
    let xref_offset = pdf.len();
    pdf.extend_from_slice(
        format!("xref\n0 {}\n0000000000 65535 f \n", objects.len() + 1).as_bytes(),
    );
    for offset in offsets {
        pdf.extend_from_slice(format!("{offset:010} 00000 n \n").as_bytes());
    }
    pdf.extend_from_slice(
        format!(
            "trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{}\n%%EOF\n",
            objects.len() + 1,
            xref_offset
        )
        .as_bytes(),
    );
    pdf
}

fn minimal_invisible_text_pdf() -> Vec<u8> {
    let stream = "\
BT
/F1 16 Tf
72 720 Td
(Visible evidence.) Tj
3 Tr
0 -30 Td
(Invisible evidence.) Tj
ET
";
    minimal_single_stream_pdf(stream)
}

fn minimal_safety_filter_pdf() -> Vec<u8> {
    let stream = "\
BT
/F1 16 Tf
72 720 Td
(Visible evidence.) Tj
-500 0 Td
(Off page evidence.) Tj
500 -30 Td
/F1 1 Tf
(Tiny evidence.) Tj
/F1 16 Tf
0 -30 Td
1 1 1 rg
(Background evidence.) Tj
0 0 0 rg
0 -30 Td
(      ) Tj
ET
";
    minimal_single_stream_pdf(stream)
}

fn minimal_tagged_structure_order_pdf(suspect: bool) -> Vec<u8> {
    let stream = "\
BT
/F1 16 Tf
/P <</MCID 1>> BDC
72 720 Td
(Logical second.) Tj
EMC
/P <</MCID 0>> BDC
0 -80 Td
(Logical first.) Tj
EMC
ET
";
    let suspects = if suspect { "true" } else { "false" };
    let objects = [
        format!(
            "<< /Type /Catalog /Pages 2 0 R /MarkInfo << /Marked true /Suspects {suspects} >> /StructTreeRoot 6 0 R >>"
        ),
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_string(),
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /StructParents 0 /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>".to_string(),
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_string(),
        format!("<< /Length {} >>\nstream\n{}endstream", stream.len(), stream),
        "<< /Type /StructTreeRoot /K [7 0 R 8 0 R] >>".to_string(),
        "<< /Type /StructElem /S /P /P 6 0 R /Pg 3 0 R /K 0 >>".to_string(),
        "<< /Type /StructElem /S /P /P 6 0 R /Pg 3 0 R /K 1 >>".to_string(),
    ];
    pdf_from_objects(&objects)
}

fn minimal_single_stream_pdf(stream: &str) -> Vec<u8> {
    let objects = [
        "<< /Type /Catalog /Pages 2 0 R >>".to_string(),
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_string(),
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>".to_string(),
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_string(),
        format!("<< /Length {} >>\nstream\n{}endstream", stream.len(), stream),
    ];
    pdf_from_objects(&objects)
}

fn pdf_from_objects(objects: &[String]) -> Vec<u8> {
    let mut pdf = b"%PDF-1.4\n".to_vec();
    let mut offsets = Vec::new();
    for (index, object) in objects.iter().enumerate() {
        offsets.push(pdf.len());
        pdf.extend_from_slice(format!("{} 0 obj\n{}\nendobj\n", index + 1, object).as_bytes());
    }
    let xref_offset = pdf.len();
    pdf.extend_from_slice(
        format!("xref\n0 {}\n0000000000 65535 f \n", objects.len() + 1).as_bytes(),
    );
    for offset in offsets {
        pdf.extend_from_slice(format!("{offset:010} 00000 n \n").as_bytes());
    }
    pdf.extend_from_slice(
        format!(
            "trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{}\n%%EOF\n",
            objects.len() + 1,
            xref_offset
        )
        .as_bytes(),
    );
    pdf
}
