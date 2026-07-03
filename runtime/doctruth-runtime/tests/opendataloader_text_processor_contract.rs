use assert_cmd::Command;
use serde_json::json;

#[test]
fn text_processor_contract_replaces_undefined_characters_when_requested() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_text_processor_probe",
                "text": "A\u{fffd}B",
                "undefined_character_replacement": " "
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let value: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(value["text"], "A B");
    assert!(value["replacementRatio"].as_f64().unwrap() > 0.0);
    assert_eq!(value["replacementCount"], 1);
    assert_eq!(value["source"], "OpenDataLoader TextProcessor");
}

#[test]
fn text_processor_contract_preserves_text_when_replacement_is_disabled() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_text_processor_probe",
                "text": "A\u{fffd}B"
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let value: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(value["text"], "A\u{fffd}B");
    assert!(value["replacementRatio"].as_f64().unwrap() > 0.0);
    assert_eq!(value["replacementCount"], 1);
}

#[test]
fn text_processor_contract_preserves_text_when_replacement_is_replacement_character() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_text_processor_probe",
                "text": "A\u{fffd}B",
                "undefined_character_replacement": "\u{fffd}"
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let value: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(value["text"], "A\u{fffd}B");
    assert!(value["replacementRatio"].as_f64().unwrap() > 0.0);
    assert_eq!(value["replacementCount"], 1);
}

#[test]
fn text_processor_contract_counts_replacement_ratio_with_java_utf16_units() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_text_processor_probe",
                "text": "😀\u{fffd}"
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let value: serde_json::Value = serde_json::from_slice(&output).unwrap();
    let replacement_ratio = value["replacementRatio"].as_f64().unwrap();
    assert_eq!(value["text"], "😀\u{fffd}");
    assert_eq!(value["replacementCount"], 1);
    assert!((replacement_ratio - (1.0 / 3.0)).abs() < f64::EPSILON);
}

#[test]
fn text_processor_contract_accepts_camel_case_replacement_alias() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_text_processor_probe",
                "text": "A\u{fffd}B",
                "undefinedCharacterReplacement": "_"
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let value: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(value["text"], "A_B");
    assert_eq!(value["replacementCount"], 1);
}

#[test]
fn text_processor_contract_prefers_snake_case_replacement_over_camel_case_alias() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_text_processor_probe",
                "text": "A\u{fffd}B",
                "undefined_character_replacement": " ",
                "undefinedCharacterReplacement": "_"
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let value: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(value["text"], "A B");
    assert_eq!(value["replacementCount"], 1);
}

#[test]
fn text_processor_contract_reports_zero_ratio_for_empty_text() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_text_processor_probe",
                "text": "",
                "undefined_character_replacement": " "
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let value: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(value["text"], "");
    assert_eq!(value["replacementRatio"], 0.0);
    assert_eq!(value["replacementCount"], 0);
}

#[test]
fn content_filter_probe_reports_hidden_off_page_tiny_and_duplicate_lines() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_content_filter_probe",
                "hiddenTexts": ["Hidden Text"],
                "lines": [
                    {"text": "Visible Text", "x0": 10.0, "y0": 10.0, "x1": 120.0, "y1": 24.0},
                    {"text": "Hidden Text", "x0": 10.0, "y0": 30.0, "x1": 120.0, "y1": 44.0},
                    {"text": "Off page", "x0": -30.0, "y0": 10.0, "x1": -10.0, "y1": 24.0},
                    {"text": "Tiny Text", "x0": 10.0, "y0": 50.0, "x1": 11.0, "y1": 51.0},
                    {"text": "Visible Text", "x0": 10.0, "y0": 10.0, "x1": 120.0, "y1": 24.0}
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
        value["source"],
        "OpenDataLoader ContentFilterProcessor/HiddenTextProcessor"
    );
    assert_eq!(value["keptLines"], json!(["Visible Text"]));
    assert_eq!(
        value["filteredCodes"],
        json!([
            "hidden_text_filtered",
            "off_page_text_filtered",
            "tiny_text_filtered",
            "duplicate_text_filtered"
        ])
    );
}

#[test]
fn text_processor_contract_requires_text() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(json!({"command": "opendataloader_text_processor_probe"}).to_string())
        .assert()
        .failure()
        .code(2)
        .get_output()
        .stderr
        .clone();
    let value: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(value["error_code"], "MISSING_TEXT");
}
