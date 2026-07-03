use assert_cmd::Command;
use serde_json::{Value, json};

#[test]
fn triage_probe_routes_replacement_ratio_to_backend() {
    let value = triage(json!({
        "command": "opendataloader_triage_probe",
        "replacementRatio": 0.3,
        "lines": [
            {"text": "broken text", "x0": 10, "y0": 100, "x1": 90, "y1": 120}
        ]
    }));

    assert_eq!(value["route"], "backend");
    assert_eq!(value["confidence"], 1.0);
    assert_eq!(value["signals"]["replacementRatio"], 0.3);
    assert_eq!(value["source"], "OpenDataLoader TriageProcessor");
}

#[test]
fn triage_probe_routes_vector_line_ratio_to_backend() {
    let value = triage(json!({
        "command": "opendataloader_triage_probe",
        "lineRatioThreshold": 0.3,
        "lines": [
            {"text": "Header", "x0": 10, "y0": 100, "x1": 80, "y1": 120}
        ],
        "segments": [
            {"x0": 10, "y0": 90, "x1": 200, "y1": 90},
            {"x0": 10, "y0": 80, "x1": 200, "y1": 80},
            {"x0": 10, "y0": 70, "x1": 200, "y1": 70}
        ]
    }));

    assert_eq!(value["route"], "backend");
    assert_eq!(value["confidence"], 0.95);
    assert_eq!(value["signals"]["hasVectorTableSignal"], true);
    assert_eq!(value["signals"]["horizontalLineCount"], 3);
    assert!(value["signals"]["lineToTextRatio"].as_f64().unwrap() > 0.3);
}

#[test]
fn triage_probe_honors_custom_line_ratio_threshold() {
    let value = triage(json!({
        "command": "opendataloader_triage_probe",
        "lineRatioThreshold": 0.5,
        "lines": [
            {"text": "Text1", "x0": 10, "y0": 100, "x1": 80, "y1": 120},
            {"text": "Text2", "x0": 10, "y0": 80, "x1": 80, "y1": 100}
        ],
        "segments": [
            {"x0": 10, "y0": 70, "x1": 200, "y1": 70}
        ]
    }));

    assert_eq!(value["route"], "deterministic");
    assert!(value["signals"]["lineToTextRatio"].as_f64().unwrap() > 0.3);
}

#[test]
fn triage_probe_reports_suspicious_gap_without_backend_route() {
    let value = triage(json!({
        "command": "opendataloader_triage_probe",
        "lines": [
            {"text": "Col1", "x0": 10, "y0": 100, "x1": 50, "y1": 120},
            {"text": "Col2", "x0": 200, "y0": 100, "x1": 250, "y1": 120}
        ]
    }));

    assert_eq!(value["route"], "deterministic");
    assert_eq!(value["signals"]["hasSuspiciousPattern"], true);
    assert_eq!(value["signals"]["alignedLineGroups"], 1);
}

#[test]
fn triage_probe_routes_large_wide_image_to_backend() {
    let value = triage(json!({
        "command": "opendataloader_triage_probe",
        "pageBox": {"x0": 0, "y0": 0, "x1": 1000, "y1": 500},
        "imageBoxes": [
            {"x0": 10, "y0": 10, "x1": 510, "y1": 130}
        ]
    }));

    assert_eq!(value["route"], "backend");
    assert_eq!(value["confidence"], 0.85);
    assert_eq!(value["signals"]["hasLargeImage"], true);
    assert!(value["signals"]["largeImageRatio"].as_f64().unwrap() >= 0.11);
}

#[test]
fn triage_probe_requires_valid_segments() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_triage_probe",
                "segments": [
                    {"x0": 10, "y0": 10, "x1": 10, "y1": 10}
                ]
            })
            .to_string(),
        )
        .assert()
        .code(2)
        .get_output()
        .stderr
        .clone();
    let value: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(value["error_code"], "INVALID_SEGMENT");
}

fn triage(request: Value) -> Value {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(request.to_string())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    serde_json::from_slice(&output).unwrap()
}
