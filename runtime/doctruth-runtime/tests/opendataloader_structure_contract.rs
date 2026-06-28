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
    assert_eq!(value["blocks"][0]["level"], 2);
    assert_eq!(
        value["blocks"][0]["source"],
        "OpenDataLoader HeadingProcessor/LevelProcessor"
    );
    assert_eq!(value["blocks"][1]["type"], "caption");
    assert_eq!(value["blocks"][1]["source"], "derived-caption-pattern");
    assert_eq!(value["blocks"][2]["type"], "paragraph");
}

#[test]
fn structure_probe_assigns_numbered_heading_levels() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_structure_probe",
                "lines": [
                    {"text": "1. Overview", "fontSize": 18.0},
                    {"text": "1.2 Method", "fontSize": 16.0},
                    {"text": "1.2.3 Detail", "fontSize": 14.0}
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
    assert_eq!(value["blocks"][1]["level"], 2);
    assert_eq!(value["blocks"][2]["level"], 3);
    assert_eq!(
        value["coverageGaps"][0],
        json!({"processor": "CaptionProcessor", "reason": "reference_not_vendored"})
    );
}

#[test]
fn structure_probe_merges_bare_numbered_heading_markers() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_structure_probe",
                "lines": [
                    {"text": "8", "fontSize": 18.0},
                    {"text": "Choosing between Observer Models and Rejecting Participants", "fontSize": 18.0},
                    {"text": "Two further reasonable questions one might ask are:", "fontSize": 10.0}
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
    assert_eq!(
        value["blocks"][0]["text"],
        "8 Choosing between Observer Models and Rejecting Participants"
    );
    assert_eq!(value["blocks"][0]["level"], 1);
    assert_eq!(value["blocks"][1]["type"], "paragraph");
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
fn structure_probe_recognizes_abbreviated_caption_markers() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_structure_probe",
                "lines": [
                    {"text": "Fig. 7: Pipeline overview", "fontSize": 10.0},
                    {"text": "Tab. 2 Results", "fontSize": 10.0},
                    {"text": "fig tree growth", "fontSize": 10.0},
                    {"text": "table stakes remain high", "fontSize": 10.0}
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
    assert_eq!(value["blocks"][0]["type"], "caption");
    assert_eq!(value["blocks"][1]["type"], "caption");
    assert_eq!(value["blocks"][2]["type"], "paragraph");
    assert_eq!(value["blocks"][3]["type"], "paragraph");
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
fn structure_probe_recognizes_sequential_numeric_list_items() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_structure_probe",
                "lines": [
                    {"text": "1) First item", "fontSize": 10.0},
                    {"text": "2) Second item", "fontSize": 10.0}
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
fn structure_probe_rejects_non_sequential_numeric_list_items() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_structure_probe",
                "lines": [
                    {"text": "1) First", "fontSize": 10.0},
                    {"text": "3) Third", "fontSize": 10.0}
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
    assert_eq!(value["blocks"][0]["text"], "1) First");
    assert_eq!(value["blocks"][1]["type"], "paragraph");
    assert_eq!(value["blocks"][1]["text"], "3) Third");
}

#[test]
fn structure_probe_recognizes_bullet_list_items() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_structure_probe",
                "lines": [
                    {"text": "- First item", "fontSize": 10.0},
                    {"text": "- Second item", "fontSize": 10.0}
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
    assert_eq!(value["blocks"][0]["items"][0], "First item");
    assert_eq!(value["blocks"][0]["items"][1], "Second item");
}

#[test]
fn structure_probe_merges_wrapped_list_continuations() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_structure_probe",
                "lines": [
                    {"text": "- First item starts here", "fontSize": 10.0},
                    {"text": "and continues on the next visual line", "fontSize": 10.0},
                    {"text": "- Second item", "fontSize": 10.0}
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
    assert_eq!(
        value["blocks"][0]["items"][0],
        "First item starts here and continues on the next visual line"
    );
    assert_eq!(value["blocks"][0]["items"][1], "Second item");
}

#[test]
fn structure_probe_does_not_swallow_non_continuation_after_list() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_structure_probe",
                "lines": [
                    {"text": "- First item", "fontSize": 10.0},
                    {"text": "- Second item", "fontSize": 10.0},
                    {"text": "Summary follows.", "fontSize": 10.0}
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
    assert_eq!(value["blocks"][1]["type"], "paragraph");
    assert_eq!(value["blocks"][1]["text"], "Summary follows.");
}

#[test]
fn structure_probe_preserves_nested_list_hierarchy_from_indent() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_structure_probe",
                "lines": [
                    {"text": "1) Parent item", "fontSize": 10.0, "x0": 48.0},
                    {"text": "- Child detail", "fontSize": 10.0, "x0": 72.0},
                    {"text": "- Another child", "fontSize": 10.0, "x0": 72.0},
                    {"text": "2) Next parent", "fontSize": 10.0, "x0": 48.0}
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
    assert_eq!(
        value["blocks"][0]["items"],
        json!([
            "Parent item",
            "Child detail",
            "Another child",
            "Next parent"
        ])
    );
    assert_eq!(value["blocks"][0]["listItems"][0]["level"], 1);
    assert_eq!(value["blocks"][0]["listItems"][1]["level"], 2);
    assert_eq!(value["blocks"][0]["listItems"][2]["level"], 2);
    assert_eq!(value["blocks"][0]["listItems"][3]["level"], 1);
}

#[test]
fn structure_probe_reports_remaining_unvendored_caption_reference() {
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
