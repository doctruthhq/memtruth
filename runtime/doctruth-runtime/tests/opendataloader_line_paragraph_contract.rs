use assert_cmd::Command;
use serde_json::json;

#[test]
fn line_processor_preserves_numeric_table_rows() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_line_paragraph_probe",
                "lines": [
                    {"text": "Year", "x0": 100, "y0": 100, "x1": 150, "y1": 120},
                    {"text": "Rate", "x0": 220, "y0": 100, "x1": 260, "y1": 120},
                    {"text": "2024", "x0": 100, "y0": 130, "x1": 150, "y1": 150},
                    {"text": "10%", "x0": 220, "y0": 130, "x1": 260, "y1": 150}
                ]
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let value: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(value["paragraphs"].as_array().unwrap().len(), 0);
    assert_eq!(value["joinedParagraphs"].as_array().unwrap().len(), 0);
    assert_eq!(value["tableLikeRows"].as_u64().unwrap(), 2);
    assert_eq!(
        value["source"],
        "OpenDataLoader TextLineProcessor/ParagraphProcessor"
    );
}

#[test]
fn paragraph_processor_emits_single_prose_line_as_paragraph() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_line_paragraph_probe",
                "lines": [
                    {"text": "This standalone prose line should remain a paragraph.", "x0": 80, "y0": 100, "x1": 430, "y1": 120}
                ]
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let value: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(
        value["paragraphs"][0],
        "This standalone prose line should remain a paragraph."
    );
    assert_eq!(value["joinedParagraphs"].as_array().unwrap().len(), 0);
    assert_eq!(value["tableLikeRows"].as_u64().unwrap(), 0);
}

#[test]
fn paragraph_processor_joins_wrapped_prose_lines() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_line_paragraph_probe",
                "lines": [
                    {"text": "This is a wrapped paragraph that should", "x0": 80, "y0": 100, "x1": 500, "y1": 120},
                    {"text": "continue on the next visual line.", "x0": 80, "y0": 124, "x1": 420, "y1": 144}
                ]
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let value: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(
        value["joinedParagraphs"][0],
        "This is a wrapped paragraph that should continue on the next visual line."
    );
    assert_eq!(
        value["paragraphs"][0],
        "This is a wrapped paragraph that should continue on the next visual line."
    );
    assert_eq!(value["tableLikeRows"].as_u64().unwrap(), 0);
}

#[test]
fn paragraph_processor_reports_right_alignment_before_two_line_heuristic() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_line_paragraph_probe",
                "lines": [
                    {"text": "short", "x0": 150, "y0": 100, "x1": 220, "y1": 112},
                    {"text": "longer line", "x0": 90, "y0": 114, "x1": 220, "y1": 126}
                ]
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let value: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(value["paragraphAlignments"][0]["alignment"], "right");
    assert_eq!(
        value["paragraphAlignments"][0]["reason"],
        "OpenDataLoader ParagraphProcessor right-alignment precedence"
    );
    assert_eq!(value["joinedParagraphs"][0], "short longer line");
}

#[test]
fn line_paragraph_probe_requires_lines() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(json!({"command": "opendataloader_line_paragraph_probe"}).to_string())
        .assert()
        .code(2)
        .get_output()
        .stderr
        .clone();
    let value: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(value["error_code"], "MISSING_LINES");
}

#[test]
fn line_paragraph_probe_rejects_invalid_line_boxes() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_line_paragraph_probe",
                "lines": [
                    {"text": "Missing coordinate", "x0": 80, "y0": 100, "x1": 500}
                ]
            })
            .to_string(),
        )
        .assert()
        .code(2)
        .get_output()
        .stderr
        .clone();
    let value: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(value["error_code"], "INVALID_LINE_BOX");
}

#[test]
fn line_paragraph_probe_rejects_inverted_line_geometry() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_line_paragraph_probe",
                "lines": [
                    {"text": "Invalid geometry", "x0": 80, "y0": 100, "x1": 80, "y1": 120}
                ]
            })
            .to_string(),
        )
        .assert()
        .code(2)
        .get_output()
        .stderr
        .clone();
    let value: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(value["error_code"], "INVALID_LINE_BOX");
}
