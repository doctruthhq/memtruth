use std::fs;
use std::process::Command;
use std::time::{SystemTime, UNIX_EPOCH};

#[test]
fn concise_preflight_cli_checks_sections_schema_and_query() {
    let temp = unique_temp_dir("memtruth-vespa-preflight-cli");
    fs::create_dir_all(&temp).unwrap();
    let sections = temp.join("sections.jsonl");
    let query = temp.join("query.json");
    let report = temp.join("report.json");
    let sd_path =
        std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/testdata/legal_doc.sd");

    fs::write(
        &sections,
        r#"{"doc_id":"federal_register_of_legislation:C2026C00227:section:0056-14","parent_doc_id":"federal_register_of_legislation:C2026C00227","version_id":"C2026C00227","section_ordinal":56,"section_number":"14","section_heading":"Australian Privacy Principles","part_label":"Part III-Information privacy","division_label":"Division 2-Australian Privacy Principles","type":"act","jurisdiction":"commonwealth","source":"federal_register_of_legislation","citation":"Privacy Act 1988 (Cth) C2026C00227 s 14","title":"Privacy Act 1988 s 14 - Australian Privacy Principles","date":"2026-06-04","url":"https://www.legislation.gov.au/C2004A03712/latest","text":"14 Australian Privacy Principles\nThe Australian Privacy Principles are set out in Schedule 1."}"#,
    )
    .unwrap();
    fs::write(
        &query,
        r#"{"ranking":"bm25_recall","filters":[{"field":"jurisdiction","operator":"equals"}],"lanes":["bm25"],"total_target_hits":50}"#,
    )
    .unwrap();

    let binary = std::env::var("CARGO_BIN_EXE_memtruth-vespa-preflight").unwrap();
    let output = Command::new(binary)
        .arg("--sections")
        .arg(&sections)
        .arg("--sd")
        .arg(&sd_path)
        .arg("--query")
        .arg(&query)
        .arg("--report")
        .arg(&report)
        .output()
        .unwrap();

    assert!(
        output.status.success(),
        "stdout={}\nstderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );

    let report_json: serde_json::Value =
        serde_json::from_str(&fs::read_to_string(&report).unwrap()).unwrap();
    assert_eq!(report_json["has_errors"], false);
    assert_eq!(report_json["diagnostics"], serde_json::json!([]));
}

#[test]
fn cli_reports_malformed_jsonl_as_structured_diagnostic() {
    let temp = unique_temp_dir("memtruth-vespa-preflight-bad-jsonl");
    fs::create_dir_all(&temp).unwrap();
    let sections = temp.join("sections.jsonl");
    let report = temp.join("report.json");

    fs::write(&sections, "{not-json}\n").unwrap();

    let binary = std::env::var("CARGO_BIN_EXE_memtruth-vespa-preflight").unwrap();
    let output = Command::new(binary)
        .arg("--sections")
        .arg(&sections)
        .arg("--report")
        .arg(&report)
        .output()
        .unwrap();

    assert_eq!(output.status.code(), Some(1));
    let report_json: serde_json::Value =
        serde_json::from_str(&fs::read_to_string(&report).unwrap()).unwrap();
    assert_eq!(report_json["has_errors"], true);
    assert_eq!(
        report_json["parser_scope"],
        "memtruth-supported Vespa contract surface"
    );
    assert!(
        report_json["diagnostics"]
            .as_array()
            .unwrap()
            .iter()
            .any(|diagnostic| {
                diagnostic["code"] == "E_JSONL_PARSE_FAILED"
                    && diagnostic["path"] == "sections[0]"
                    && diagnostic["fix"]
                        .as_str()
                        .unwrap()
                        .contains("valid JSON object")
            })
    );
}

#[test]
fn cli_reports_missing_section_fields_with_precise_paths() {
    let temp = unique_temp_dir("memtruth-vespa-preflight-missing-field");
    fs::create_dir_all(&temp).unwrap();
    let sections = temp.join("sections.jsonl");
    let report = temp.join("report.json");

    fs::write(
        &sections,
        r#"{"doc_id":"missing-body","parent_doc_id":"parent","version_id":"v1","section_ordinal":1,"section_number":"1","section_heading":"Heading","citation":"Citation","title":"Title","jurisdiction":"commonwealth","source":"test","type":"act"}"#,
    )
    .unwrap();

    let binary = std::env::var("CARGO_BIN_EXE_memtruth-vespa-preflight").unwrap();
    let output = Command::new(binary)
        .arg("--sections")
        .arg(&sections)
        .arg("--report")
        .arg(&report)
        .output()
        .unwrap();

    assert_eq!(output.status.code(), Some(1));
    let report_json: serde_json::Value =
        serde_json::from_str(&fs::read_to_string(&report).unwrap()).unwrap();
    assert!(
        report_json["diagnostics"]
            .as_array()
            .unwrap()
            .iter()
            .any(|diagnostic| {
                diagnostic["code"] == "E_REQUIRED_FIELD_EMPTY"
                    && diagnostic["path"] == "sections[0].body_text"
            })
    );
}

#[test]
fn legal_section_feed_cli_rejects_malformed_input_without_writing_feed() {
    let temp = unique_temp_dir("memtruth-legal-section-feed-bad-jsonl");
    fs::create_dir_all(&temp).unwrap();
    let sections = temp.join("sections.jsonl");
    let output_path = temp.join("feed.jsonl");

    fs::write(&sections, "{not-json}\n").unwrap();

    let binary = std::env::var("CARGO_BIN_EXE_memtruth-legal-section-feed").unwrap();
    let output = Command::new(binary)
        .arg("--in")
        .arg(&sections)
        .arg("--out")
        .arg(&output_path)
        .output()
        .unwrap();

    assert_eq!(output.status.code(), Some(1));
    assert!(
        String::from_utf8_lossy(&output.stderr).contains("malformed=1"),
        "stderr={}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert!(!output_path.exists());
}

fn unique_temp_dir(prefix: &str) -> std::path::PathBuf {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    std::env::temp_dir().join(format!("{prefix}-{nanos}"))
}
