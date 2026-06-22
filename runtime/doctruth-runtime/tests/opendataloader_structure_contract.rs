use assert_cmd::Command;
use serde_json::json;

#[test]
fn structure_probe_promotes_numbered_heading_and_keeps_figure_caption_plain() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_structure_probe",
                "lines": [
                    {"text": "2.1. Diesel and biodiesel use", "fontSize": 18.0},
                    {"text": "Figure 1 Results", "fontSize": 10.0},
                    {"text": "ordinary short phrase", "fontSize": 10.0}
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
    assert_eq!(value["blocks"][0]["type"], "heading");
    assert_eq!(value["blocks"][0]["level"], 1);
    assert_eq!(
        value["blocks"][0]["source"],
        "OpenDataLoader HeadingProcessor"
    );
    assert_eq!(value["blocks"][1]["type"], "caption");
    assert_eq!(value["blocks"][1]["source"], "derived-caption-pattern");
    assert_eq!(value["blocks"][2]["type"], "paragraph");
}

#[test]
fn structure_probe_rejects_empty_numbered_heading_segments() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_structure_probe",
                "lines": [
                    {"text": "1..2 Invalid heading marker", "fontSize": 18.0}
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
    assert_eq!(value["blocks"][0]["type"], "paragraph");
}

#[test]
fn structure_probe_requires_numeric_caption_marker() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_structure_probe",
                "lines": [
                    {"text": "Figure skating results", "fontSize": 10.0},
                    {"text": "Table stakes are high", "fontSize": 10.0},
                    {"text": "Figure 1. Results", "fontSize": 10.0},
                    {"text": "Table 2 Results", "fontSize": 10.0}
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
    assert_eq!(value["blocks"][0]["type"], "paragraph");
    assert_eq!(value["blocks"][1]["type"], "paragraph");
    assert_eq!(value["blocks"][2]["type"], "caption");
    assert_eq!(value["blocks"][3]["type"], "caption");
}

#[test]
fn structure_probe_recognizes_localized_letter_list_items() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_structure_probe",
                "lines": [
                    {"text": "a) First item", "fontSize": 10.0},
                    {"text": "b) Second item", "fontSize": 10.0}
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
    assert_eq!(value["blocks"][0]["type"], "list");
    assert_eq!(value["blocks"][0]["items"].as_array().unwrap().len(), 2);
    assert_eq!(value["blocks"][0]["source"], "OpenDataLoader ListProcessor");
}

#[test]
fn structure_probe_rejects_non_sequential_letter_list_items() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_structure_probe",
                "lines": [
                    {"text": "a) First", "fontSize": 10.0},
                    {"text": "c) Third", "fontSize": 10.0}
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
    assert_eq!(value["blocks"][0]["type"], "paragraph");
    assert_eq!(value["blocks"][0]["text"], "a) First");
    assert_eq!(value["blocks"][1]["type"], "paragraph");
    assert_eq!(value["blocks"][1]["text"], "c) Third");
}

#[test]
fn structure_probe_recognizes_sequential_uppercase_letter_list_items() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_structure_probe",
                "lines": [
                    {"text": "A) First item", "fontSize": 10.0},
                    {"text": "B) Second item", "fontSize": 10.0}
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
    assert_eq!(value["blocks"][0]["type"], "list");
    assert_eq!(value["blocks"][0]["items"].as_array().unwrap().len(), 2);
}

#[test]
fn structure_probe_reports_unvendored_level_and_caption_references() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_structure_probe",
                "lines": [
                    {"text": "Overview", "fontSize": 18.0}
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
        value["coverageGaps"][0],
        json!({"processor": "LevelProcessor", "reason": "reference_not_vendored"})
    );
    assert_eq!(
        value["coverageGaps"][1],
        json!({"processor": "CaptionProcessor", "reason": "reference_not_vendored"})
    );
    let references = value["references"].as_array().unwrap();
    assert!(references.iter().all(|reference| {
        reference
            .as_str()
            .unwrap()
            .starts_with("third_party/opendataloader-pdf-reference/")
    }));
    assert!(
        !references
            .iter()
            .any(|reference| reference.as_str().unwrap().contains("LevelProcessor"))
    );
    assert!(
        !references
            .iter()
            .any(|reference| reference.as_str().unwrap().contains("CaptionProcessor"))
    );
}

#[test]
fn structure_probe_requires_lines() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(json!({"command": "opendataloader_structure_probe"}).to_string())
        .assert()
        .code(2)
        .get_output()
        .stderr
        .clone();
    let value: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(value["error_code"], "MISSING_LINES");
}

#[test]
fn structure_probe_rejects_invalid_font_size() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_structure_probe",
                "lines": [
                    {"text": "Invalid font size", "fontSize": "large"}
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
    assert_eq!(value["error_code"], "INVALID_STRUCTURE_LINE");
}
