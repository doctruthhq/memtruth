use assert_cmd::Command;
use doctruth_runtime::opendataloader_parity_matrix_json;
use serde_json::json;
use std::collections::HashSet;
use std::fs;
use std::path::PathBuf;

const REQUIRED_PROCESSORS: &[&str] = &[
    "DocumentProcessor",
    "TaggedDocumentProcessor",
    "TextProcessor",
    "TextLineProcessor",
    "ParagraphProcessor",
    "HeadingProcessor",
    "ListProcessor",
    "CaptionProcessor",
    "LevelProcessor",
    "HeaderFooterProcessor",
    "ContentFilterProcessor",
    "TextDecorationProcessor",
    "TableBorderProcessor",
    "ClusterTableProcessor",
    "SpecialTableProcessor",
    "TableStructureNormalizer",
    "HiddenTextProcessor",
    "HybridDocumentProcessor",
    "TriageProcessor",
    "DoclingSchemaTransformer",
    "OcrStrategy",
];

#[test]
fn opendataloader_parity_matrix_lists_required_processors() {
    let matrix = opendataloader_parity_matrix_json();
    let processors = matrix["processors"].as_array().expect("processors array");
    let names = processors
        .iter()
        .filter_map(|entry| entry["upstream"].as_str())
        .collect::<Vec<_>>();

    for expected in REQUIRED_PROCESSORS {
        assert!(names.contains(&expected), "missing processor {expected}");
    }
}

#[test]
fn opendataloader_parity_matrix_command_returns_json() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(json!({"command": "opendataloader_parity_matrix"}).to_string())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let json: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(json["source"]["name"], "OpenDataLoader PDF");
    assert_eq!(
        json["source"]["path"],
        "third_party/opendataloader-pdf-reference"
    );
    assert!(json["processors"].as_array().unwrap().len() >= 20);
}

#[test]
fn opendataloader_parity_matrix_source_points_to_tracked_reference() {
    let matrix = opendataloader_parity_matrix_json();
    let source = &matrix["source"];

    assert_eq!(source["name"].as_str(), Some("OpenDataLoader PDF"));
    assert_eq!(
        source["path"].as_str(),
        Some("third_party/opendataloader-pdf-reference")
    );
    assert_eq!(source["license"].as_str(), Some("Apache-2.0"));
}

#[test]
fn opendataloader_parity_matrix_has_unique_upstream_processor_names() {
    let matrix = opendataloader_parity_matrix_json();
    let mut names = HashSet::new();

    for entry in matrix["processors"].as_array().expect("processors array") {
        let upstream = entry["upstream"].as_str().expect("upstream");
        assert!(names.insert(upstream), "duplicate processor {upstream}");
    }
}

#[test]
fn opendataloader_parity_matrix_has_status_and_owner_for_every_processor() {
    let matrix = opendataloader_parity_matrix_json();
    let processors = matrix["processors"].as_array().expect("processors array");

    assert!(!processors.is_empty());
    for entry in processors {
        assert!(entry["upstream"].as_str().is_some(), "missing upstream");
        assert!(
            entry["status"].as_str().is_some(),
            "missing status for {entry:?}"
        );
        assert!(
            entry["doc_truth_owner"].as_str().is_some(),
            "missing owner for {entry:?}"
        );
        assert!(
            entry["focused_test"].as_str().is_some(),
            "missing focused test for {entry:?}"
        );
    }
}

#[test]
fn opendataloader_pipeline_stage_order_is_explicit() {
    let matrix = opendataloader_parity_matrix_json();
    let stages = matrix["pipeline_stages"]
        .as_array()
        .expect("pipeline stages");
    let names = stages
        .iter()
        .filter_map(|stage| stage["name"].as_str())
        .collect::<Vec<_>>();

    assert_eq!(
        names,
        vec![
            "pdf_text_extraction",
            "text_normalization",
            "content_filtering",
            "line_grouping",
            "paragraph_merge",
            "heading_hierarchy",
            "list_grouping",
            "caption_binding",
            "table_border_detection",
            "borderless_table_clustering",
            "table_structure_normalization",
            "chart_table_gate",
            "ocr_table_model_routing",
            "reading_order",
            "trust_document_export",
        ]
    );

    for stage in stages {
        assert!(stage["owner"].as_str().is_some(), "missing owner");
        assert!(
            stage["canonical_output"].as_str().is_some(),
            "missing canonical output"
        );
    }
}

#[test]
fn opendataloader_parity_matrix_has_no_unknown_statuses() {
    let matrix = opendataloader_parity_matrix_json();
    for entry in matrix["processors"].as_array().expect("processors array") {
        let status = entry["status"].as_str().expect("status");
        assert!(
            matches!(
                status,
                "ported" | "partial" | "not_ported" | "oracle_only" | "intentionally_skipped"
            ),
            "unexpected status {status} in {entry:?}"
        );
        assert!(
            entry["doc"]
                .as_str()
                .unwrap_or_default()
                .starts_with("docs/parser/opendataloader-parity-matrix.md#")
        );
    }
}

#[test]
fn opendataloader_parity_matrix_doc_links_match_markdown_headings() {
    let matrix = opendataloader_parity_matrix_json();
    let doc_path = repo_root().join("docs/parser/opendataloader-parity-matrix.md");
    let markdown = fs::read_to_string(&doc_path).expect("parity matrix markdown exists");

    for entry in matrix["processors"].as_array().expect("processors array") {
        let upstream = entry["upstream"].as_str().expect("upstream");
        let expected_doc = format!(
            "docs/parser/opendataloader-parity-matrix.md#{}",
            upstream.to_ascii_lowercase()
        );
        assert_eq!(entry["doc"].as_str(), Some(expected_doc.as_str()));

        let has_heading = markdown.lines().any(|line| {
            let heading = line.trim_start_matches('#').trim();
            line.starts_with('#') && heading == upstream
        });
        assert!(has_heading, "missing markdown heading for {upstream}");
    }
}

#[test]
fn opendataloader_source_pin_and_notice_are_recorded() {
    let repo = repo_root();
    let source =
        fs::read_to_string(repo.join("third_party/opendataloader-pdf-reference/SOURCE.md"))
            .expect("SOURCE.md");
    assert!(
        source.contains("Repository: https://github.com/opendataloader-project/opendataloader-pdf")
    );
    assert!(source.contains("License: Apache-2.0"));
    assert!(
        source.contains("Reference commit: d1845179a1286bbb76f9618e8b6c8f51509a52f4")
            || source.contains("Pinned commit: d1845179a1286bbb76f9618e8b6c8f51509a52f4")
            || source.contains("Commit: d1845179a1286bbb76f9618e8b6c8f51509a52f4")
    );
    assert!(source.contains("third_party/opendataloader-pdf-reference"));
    assert!(source.contains("not compiled into DocTruth"));
    assert!(source.contains("not a production parser fallback"));

    let notice = fs::read_to_string(repo.join("NOTICE")).expect("NOTICE");
    assert!(notice.contains("OpenDataLoader PDF"));
    assert!(notice.contains("https://github.com/opendataloader-project/opendataloader-pdf"));
    assert!(notice.contains("Apache License 2.0"));
    assert!(notice.contains("d1845179a1286bbb76f9618e8b6c8f51509a52f4"));
    assert!(notice.contains("third_party/opendataloader-pdf-reference"));
}

#[test]
fn opendataloader_parity_docs_record_source_pin() {
    let markdown =
        fs::read_to_string(repo_root().join("docs/parser/opendataloader-parity-matrix.md"))
            .expect("parity matrix markdown exists");
    assert!(markdown.contains("https://github.com/opendataloader-project/opendataloader-pdf"));
    assert!(markdown.contains("third_party/opendataloader-pdf-reference"));
    assert!(markdown.contains("d1845179a1286bbb76f9618e8b6c8f51509a52f4"));
    assert!(markdown.contains("Apache-2.0") || markdown.contains("Apache License 2.0"));
}

#[test]
fn docs_define_opendataloader_parity_as_measured_not_asserted() {
    for path in [
        "docs/pdf-parser-runtime-prd.md",
        "docs/parser-capability-matrix.md",
        "AGENTS.md",
    ] {
        let text = fs::read_to_string(repo_root().join(path)).expect(path);
        let normalized = text.split_whitespace().collect::<Vec<_>>().join(" ");
        assert!(
            !text.contains("OpenDataLoader parity complete"),
            "{path} must not claim full parity without the full200 gate"
        );
        assert!(
            normalized.contains("OpenDataLoader parity is measured, not asserted"),
            "{path} must define OpenDataLoader parity done criteria"
        );
        for phrase in [
            "Rust contract test",
            "upstream source reference",
            "focused OpenDataLoader Bench case or a full200 report",
            "Until full200 reaches the accepted baseline",
            "not OpenDataLoader-equivalent",
        ] {
            assert!(
                normalized.contains(phrase),
                "{path} must preserve OpenDataLoader parity gate phrase: {phrase}"
            );
        }
    }
}

fn repo_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(|path| path.parent())
        .expect("runtime crate lives under runtime/doctruth-runtime")
        .to_path_buf()
}
