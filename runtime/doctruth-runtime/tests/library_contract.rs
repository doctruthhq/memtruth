use serde_json::{Value, json};

#[test]
fn library_api_reports_doctor_readiness_without_spawning_binary() {
    let doctor = doctruth_runtime::doctor_json();

    assert_eq!(doctor["runtime"], "doctruth-runtime");
    assert_eq!(doctor["protocol_version"], "1");
    assert_eq!(doctor["local_first"], true);
    assert_eq!(doctor["capabilities"]["parse_pdf"], true);
    assert_eq!(doctor["pdfBackend"]["target"], "pdf_oxide");
    assert_eq!(doctor["pdfBackend"]["current"], "pdf_oxide+lopdf");
    assert_eq!(doctor["pdfBackend"]["status"], "PARTIAL");
}

#[test]
fn library_api_maps_unknown_command_to_protocol_error_json() {
    let input = json!({"command": "unknown"}).to_string();

    let error = doctruth_runtime::run_with_args_and_input(&[], &input).unwrap_err();
    let json: Value = serde_json::from_str(&error).unwrap();

    assert_eq!(json["error_code"], "UNKNOWN_COMMAND");
}

#[test]
fn library_api_keeps_cli_argument_validation_outside_parser_core() {
    let args = vec!["--unexpected".to_string()];

    let error = doctruth_runtime::run_with_args_and_input(&args, "").unwrap_err();
    let json: Value = serde_json::from_str(&error).unwrap();

    assert_eq!(json["error_code"], "UNKNOWN_ARGUMENT");
}
