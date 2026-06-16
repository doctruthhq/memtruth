use assert_cmd::Command;
use predicates::prelude::*;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::fs;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

static TEMP_FILE_COUNTER: AtomicU64 = AtomicU64::new(1);

#[test]
fn benchmark_corpus_runs_labeled_manifest_and_reports_metrics() {
    let root = temp_dir("doctruth-runtime-corpus");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    write_opendataloader_evaluation(&root);
    fs::write(&manifest, benchmark_manifest_with_external()).unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "benchmark_corpus",
                "manifest_path": manifest,
                "offline": true
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let report: Value = serde_json::from_slice(&output).unwrap();

    assert_eq!(report["runtime"], "doctruth-runtime");
    assert_eq!(report["corpus"], "rust-parser-accuracy-seed");
    assert_eq!(report["kind"], "human-labeled");
    assert_eq!(report["qualityProfile"], "parser-accuracy");
    assert_eq!(report["reviewType"], "generated-seed");
    assert_eq!(report["passed"], true);
    assert_eq!(report["metrics"]["reading_order_f1"], 1.0);
    assert_eq!(report["metrics"]["quote_anchor_accuracy"], 1.0);
    assert_eq!(report["metrics"]["bbox_coverage"], 1.0);
    assert_eq!(report["cases"][0]["labelId"], "rust-seed-v1-0001");
    assert_eq!(report["cases"][0]["tags"], json!(["multi-layout"]));
    assert_eq!(report["cases"][0]["metrics"]["reading_order_f1"], 1.0);
}

#[test]
fn benchmark_corpus_writes_recorded_report_artifact() {
    let root = temp_dir("doctruth-runtime-corpus-report");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let report_path = root.join("reports/parser-accuracy-report.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    write_opendataloader_evaluation(&root);
    fs::write(&manifest, benchmark_manifest_with_external()).unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "benchmark_corpus",
                "manifest_path": manifest,
                "offline": true,
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let stdout_report: Value = serde_json::from_slice(&output).unwrap();
    let recorded: Value = serde_json::from_str(&fs::read_to_string(&report_path).unwrap()).unwrap();
    assert_eq!(
        recorded["reportFormat"],
        "doctruth.parser-benchmark.report.v1"
    );
    assert!(
        recorded["manifest"]
            .as_str()
            .unwrap()
            .ends_with("corpus.json")
    );
    assert!(
        recorded["manifestSha256"]
            .as_str()
            .unwrap()
            .starts_with("sha256:")
    );
    assert_eq!(recorded["caseCount"], 1);
    assert_eq!(recorded["casesPerTag"]["multi-layout"], 1);
    assert_eq!(recorded["minCasesPerTag"]["multi-layout"], 1);
    assert_eq!(recorded["casesPerFixtureType"]["two-column"], 1);
    assert_eq!(recorded["fixtureCoverageRequired"]["scanned-ocr"], 1);
    assert_eq!(recorded["fixtureCoverageSatisfied"]["invoice"], true);
    assert_eq!(recorded["casesPerBehavior"]["xy-cut-edge"], 1);
    assert_eq!(
        recorded["behaviorCoverageRequired"]["structure-tree-preference"],
        1
    );
    assert_eq!(
        recorded["behaviorCoverageSatisfied"]["table-cluster-heuristics"],
        true
    );
    assert_eq!(recorded["coverageRequired"]["multi-layout"], 1);
    assert_eq!(recorded["coverageSatisfied"]["multi-layout"], true);
    assert_eq!(recorded["validityInputs"]["sourceHashes"], true);
    assert_eq!(recorded["validityInputs"]["manifestHash"], true);
    assert_eq!(recorded["validityInputs"]["parserConfig"], "TrustDocument");
    assert_eq!(
        recorded["validityInputs"]["modelCacheManifest"],
        "not-required"
    );
    assert_eq!(recorded["validityInputs"]["thresholds"], true);
    assert_eq!(recorded["validityInputs"]["expectedLabels"], true);
    assert_eq!(recorded["validityInputs"]["actualTrustDocument"], true);
    assert_eq!(recorded["minimums"]["reading_order_f1"], 1.0);
    assert!(recorded["maximums"].is_object());
    assert_eq!(recorded["metrics"]["opendataloader_nid"], 0.91);
    assert_eq!(recorded["metrics"]["opendataloader_teds"], 0.52);
    assert_eq!(recorded["metrics"]["opendataloader_mhs"], 0.76);
    assert_eq!(recorded["metrics"]["opendataloader_speed"], 0.015);
    assert!(
        recorded["externalMetrics"]["opendataloader"]["evaluationSha256"]
            .as_str()
            .unwrap()
            .starts_with("sha256:")
    );
    assert_eq!(recorded["runtime"], "doctruth-runtime");
    assert_eq!(recorded["corpus"], stdout_report["corpus"]);
    assert_eq!(recorded["qualityProfile"], "parser-accuracy");
    assert_eq!(recorded["reviewType"], "generated-seed");
    assert_eq!(recorded["cases"][0]["labelId"], "rust-seed-v1-0001");
    assert!(
        recorded["cases"][0]["sourceSha256"]
            .as_str()
            .unwrap()
            .starts_with("sha256:")
    );
    assert_eq!(recorded["cases"][0]["replay"]["sourceRefReplayable"], true);
    assert!(recorded["cases"][0]["actualTrustDocument"].is_object());
    assert!(
        recorded["cases"][0]["actualTrustDocumentSha256"]
            .as_str()
            .unwrap()
            .starts_with("sha256:")
    );
    assert_eq!(
        recorded["cases"][0]["fixtureTypes"],
        json!([
            "simple-single-column",
            "two-column",
            "sidebar-resume",
            "table",
            "borderless-table",
            "scanned-ocr",
            "invoice",
            "mixed-layout"
        ])
    );
    assert_eq!(
        recorded["cases"][0]["behaviors"],
        json!([
            "xy-cut-edge",
            "safety-filter",
            "structure-tree-preference",
            "table-cluster-heuristics"
        ])
    );
    assert_eq!(recorded["cases"][0]["replay"]["quoteReplayable"], true);
    assert_eq!(
        recorded["cases"][0]["replay"]["evidenceSpanReplayable"],
        true
    );
}

#[test]
fn verify_benchmark_report_accepts_recorded_report_artifact() {
    let root = temp_dir("doctruth-runtime-report-verify");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let report_path = root.join("reports/parser-accuracy-report.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    fs::write(&manifest, benchmark_manifest()).unwrap();

    let mut writer = Command::cargo_bin("doctruth-runtime").unwrap();
    writer
        .write_stdin(
            json!({
                "command": "benchmark_corpus",
                "manifest_path": manifest,
                "offline": true,
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .success();

    let mut verifier = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = verifier
        .write_stdin(
            json!({
                "command": "verify_benchmark_report",
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let verified: Value = serde_json::from_slice(&output).unwrap();

    assert_eq!(verified["verified"], true);
    assert_eq!(
        verified["reportFormat"],
        "doctruth.parser-benchmark.report.v1"
    );
    assert_eq!(verified["caseCount"], 1);
}

#[test]
fn benchmark_corpus_exports_opendataloader_prediction_artifacts() {
    let root = temp_dir("doctruth-runtime-opendataloader-prediction");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let prediction = root.join("prediction/doctruth");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    fs::write(&manifest, benchmark_manifest()).unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "benchmark_corpus",
                "manifest_path": manifest,
                "offline": true,
                "opendataloader_prediction_dir": prediction
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let report: Value = serde_json::from_slice(&output).unwrap();

    let markdown = prediction.join("markdown/rust-seed-v1-0001.md");
    assert!(markdown.is_file());
    assert!(
        fs::read_to_string(markdown)
            .unwrap()
            .contains("Rust corpus evidence.")
    );
    let summary: Value =
        serde_json::from_str(&fs::read_to_string(prediction.join("summary.json")).unwrap())
            .unwrap();
    assert_eq!(summary["engine_name"], "doctruth");
    assert_eq!(summary["document_count"], 1);
    assert_eq!(
        report["externalArtifacts"]["opendataloaderPrediction"]["engine"],
        "doctruth"
    );
}

#[test]
fn verify_benchmark_report_rejects_tampered_coverage_thresholds() {
    let root = temp_dir("doctruth-runtime-report-verify-tampered");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let report_path = root.join("reports/parser-accuracy-report.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    fs::write(&manifest, benchmark_manifest()).unwrap();

    let mut writer = Command::cargo_bin("doctruth-runtime").unwrap();
    writer
        .write_stdin(
            json!({
                "command": "benchmark_corpus",
                "manifest_path": manifest,
                "offline": true,
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .success();
    let mut recorded: Value =
        serde_json::from_str(&fs::read_to_string(&report_path).unwrap()).unwrap();
    recorded["minCasesPerTag"]["multi-layout"] = json!(2);
    fs::write(&report_path, serde_json::to_string(&recorded).unwrap()).unwrap();

    let mut verifier = Command::cargo_bin("doctruth-runtime").unwrap();
    verifier
        .write_stdin(
            json!({
                "command": "verify_benchmark_report",
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .failure()
        .stderr(predicate::str::contains("minCasesPerTag mismatch"));
}

#[test]
fn verify_benchmark_report_rejects_tampered_coverage_satisfaction() {
    let root = temp_dir("doctruth-runtime-report-coverage-satisfaction");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let report_path = root.join("reports/parser-accuracy-report.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    fs::write(&manifest, benchmark_manifest()).unwrap();

    write_recorded_report(&manifest, &report_path);
    let mut recorded: Value =
        serde_json::from_str(&fs::read_to_string(&report_path).unwrap()).unwrap();
    recorded["coverageSatisfied"]["multi-layout"] = json!(false);
    fs::write(&report_path, serde_json::to_string(&recorded).unwrap()).unwrap();

    let mut verifier = Command::cargo_bin("doctruth-runtime").unwrap();
    verifier
        .write_stdin(
            json!({
                "command": "verify_benchmark_report",
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .failure()
        .stderr(predicate::str::contains("coverageSatisfied mismatch"));
}

#[test]
fn verify_benchmark_report_rejects_tampered_fixture_coverage() {
    let root = temp_dir("doctruth-runtime-report-fixture-coverage");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let report_path = root.join("reports/parser-accuracy-report.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    fs::write(&manifest, benchmark_manifest()).unwrap();

    write_recorded_report(&manifest, &report_path);
    let mut recorded: Value =
        serde_json::from_str(&fs::read_to_string(&report_path).unwrap()).unwrap();
    recorded["fixtureCoverageSatisfied"]["invoice"] = json!(false);
    fs::write(&report_path, serde_json::to_string(&recorded).unwrap()).unwrap();

    let mut verifier = Command::cargo_bin("doctruth-runtime").unwrap();
    verifier
        .write_stdin(
            json!({
                "command": "verify_benchmark_report",
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .failure()
        .stderr(predicate::str::contains(
            "fixtureCoverageSatisfied mismatch",
        ));
}

#[test]
fn verify_benchmark_report_rejects_tampered_behavior_coverage() {
    let root = temp_dir("doctruth-runtime-report-behavior-coverage");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let report_path = root.join("reports/parser-accuracy-report.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    fs::write(&manifest, benchmark_manifest()).unwrap();

    write_recorded_report(&manifest, &report_path);
    let mut recorded: Value =
        serde_json::from_str(&fs::read_to_string(&report_path).unwrap()).unwrap();
    recorded["behaviorCoverageSatisfied"]["xy-cut-edge"] = json!(false);
    fs::write(&report_path, serde_json::to_string(&recorded).unwrap()).unwrap();

    let mut verifier = Command::cargo_bin("doctruth-runtime").unwrap();
    verifier
        .write_stdin(
            json!({
                "command": "verify_benchmark_report",
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .failure()
        .stderr(predicate::str::contains(
            "behaviorCoverageSatisfied mismatch",
        ));
}

#[test]
fn verify_benchmark_report_rejects_tampered_validity_inputs() {
    let root = temp_dir("doctruth-runtime-report-validity-inputs");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let report_path = root.join("reports/parser-accuracy-report.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    fs::write(&manifest, benchmark_manifest()).unwrap();

    write_recorded_report(&manifest, &report_path);
    let mut recorded: Value =
        serde_json::from_str(&fs::read_to_string(&report_path).unwrap()).unwrap();
    recorded["validityInputs"]["actualTrustDocument"] = json!(false);
    fs::write(&report_path, serde_json::to_string(&recorded).unwrap()).unwrap();

    let mut verifier = Command::cargo_bin("doctruth-runtime").unwrap();
    verifier
        .write_stdin(
            json!({
                "command": "verify_benchmark_report",
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .failure()
        .stderr(predicate::str::contains("validityInputs mismatch"));
}

#[test]
fn verify_benchmark_report_rejects_tampered_actual_trust_document_hash() {
    let root = temp_dir("doctruth-runtime-report-actual-document-hash");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let report_path = root.join("reports/parser-accuracy-report.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    fs::write(&manifest, benchmark_manifest()).unwrap();

    write_recorded_report(&manifest, &report_path);
    let mut recorded: Value =
        serde_json::from_str(&fs::read_to_string(&report_path).unwrap()).unwrap();
    recorded["cases"][0]["actualTrustDocumentSha256"] = json!("sha256:tampered");
    fs::write(&report_path, serde_json::to_string(&recorded).unwrap()).unwrap();

    let mut verifier = Command::cargo_bin("doctruth-runtime").unwrap();
    verifier
        .write_stdin(
            json!({
                "command": "verify_benchmark_report",
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .failure()
        .stderr(predicate::str::contains("actualTrustDocumentSha256"));
}

#[test]
fn verify_benchmark_report_rejects_tampered_case_replay() {
    let root = temp_dir("doctruth-runtime-report-case-replay");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let report_path = root.join("reports/parser-accuracy-report.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    fs::write(&manifest, benchmark_manifest()).unwrap();

    write_recorded_report(&manifest, &report_path);
    let mut recorded: Value =
        serde_json::from_str(&fs::read_to_string(&report_path).unwrap()).unwrap();
    recorded["cases"][0]["replay"]["evidenceSpanReplayable"] = json!(false);
    fs::write(&report_path, serde_json::to_string(&recorded).unwrap()).unwrap();

    let mut verifier = Command::cargo_bin("doctruth-runtime").unwrap();
    verifier
        .write_stdin(
            json!({
                "command": "verify_benchmark_report",
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .failure()
        .stderr(predicate::str::contains("case replay mismatch"))
        .stderr(predicate::str::contains("evidenceSpanReplayable"));
}

#[test]
fn verify_benchmark_report_rejects_tampered_metrics_below_minimum() {
    let root = temp_dir("doctruth-runtime-report-verify-metric-tampered");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let report_path = root.join("reports/parser-accuracy-report.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    fs::write(&manifest, benchmark_manifest()).unwrap();

    let mut writer = Command::cargo_bin("doctruth-runtime").unwrap();
    writer
        .write_stdin(
            json!({
                "command": "benchmark_corpus",
                "manifest_path": manifest,
                "offline": true,
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .success();
    let mut recorded: Value =
        serde_json::from_str(&fs::read_to_string(&report_path).unwrap()).unwrap();
    recorded["metrics"]["reading_order_f1"] = json!(0.0);
    fs::write(&report_path, serde_json::to_string(&recorded).unwrap()).unwrap();

    let mut verifier = Command::cargo_bin("doctruth-runtime").unwrap();
    verifier
        .write_stdin(
            json!({
                "command": "verify_benchmark_report",
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .failure()
        .stderr(predicate::str::contains("aggregate metric mismatch"))
        .stderr(predicate::str::contains("reading_order_f1"));
}

#[test]
fn verify_benchmark_report_rejects_tampered_external_metrics() {
    let root = temp_dir("doctruth-runtime-report-verify-external-tampered");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let report_path = root.join("reports/parser-accuracy-report.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    write_opendataloader_evaluation(&root);
    fs::write(&manifest, benchmark_manifest_with_external()).unwrap();

    let mut writer = Command::cargo_bin("doctruth-runtime").unwrap();
    writer
        .write_stdin(
            json!({
                "command": "benchmark_corpus",
                "manifest_path": manifest,
                "offline": true,
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .success();
    let mut recorded: Value =
        serde_json::from_str(&fs::read_to_string(&report_path).unwrap()).unwrap();
    recorded["metrics"]["opendataloader_nid"] = json!(0.0);
    fs::write(&report_path, serde_json::to_string(&recorded).unwrap()).unwrap();

    let mut verifier = Command::cargo_bin("doctruth-runtime").unwrap();
    verifier
        .write_stdin(
            json!({
                "command": "verify_benchmark_report",
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .failure()
        .stderr(predicate::str::contains("external metrics mismatch"))
        .stderr(predicate::str::contains("opendataloader_nid"));
}

#[test]
fn verify_benchmark_report_accepts_case_metric_threshold_fallback() {
    let root = temp_dir("doctruth-runtime-report-verify-case-metric");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let report_path = root.join("reports/parser-accuracy-report.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    fs::write(&manifest, benchmark_manifest()).unwrap();

    let mut writer = Command::cargo_bin("doctruth-runtime").unwrap();
    writer
        .write_stdin(
            json!({
                "command": "benchmark_corpus",
                "manifest_path": manifest,
                "offline": true,
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .success();
    let mut recorded: Value =
        serde_json::from_str(&fs::read_to_string(&report_path).unwrap()).unwrap();
    recorded["metrics"]
        .as_object_mut()
        .unwrap()
        .remove("quote_anchor_accuracy");
    fs::write(&report_path, serde_json::to_string(&recorded).unwrap()).unwrap();

    let mut verifier = Command::cargo_bin("doctruth-runtime").unwrap();
    verifier
        .write_stdin(
            json!({
                "command": "verify_benchmark_report",
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .success();
}

#[test]
fn verify_benchmark_report_rejects_tampered_case_metric_aggregate_mismatch() {
    let root = temp_dir("doctruth-runtime-report-verify-aggregate-mismatch");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let report_path = root.join("reports/parser-accuracy-report.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    fs::write(&manifest, benchmark_manifest()).unwrap();

    let mut writer = Command::cargo_bin("doctruth-runtime").unwrap();
    writer
        .write_stdin(
            json!({
                "command": "benchmark_corpus",
                "manifest_path": manifest,
                "offline": true,
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .success();
    let mut recorded: Value =
        serde_json::from_str(&fs::read_to_string(&report_path).unwrap()).unwrap();
    recorded["cases"][0]["metrics"]["reading_order_f1"] = json!(0.5);
    fs::write(&report_path, serde_json::to_string(&recorded).unwrap()).unwrap();

    let mut verifier = Command::cargo_bin("doctruth-runtime").unwrap();
    verifier
        .write_stdin(
            json!({
                "command": "verify_benchmark_report",
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .failure()
        .stderr(predicate::str::contains("aggregate metric mismatch"))
        .stderr(predicate::str::contains("reading_order_f1"));
}

#[test]
fn benchmark_corpus_rejects_parser_accuracy_manifest_without_review_type() {
    let root = temp_dir("doctruth-runtime-bad-corpus");
    fs::create_dir_all(&root).unwrap();
    let manifest = root.join("corpus.json");
    fs::write(
        &manifest,
        json!({
            "name": "bad-corpus",
            "kind": "human-labeled",
            "qualityProfile": "parser-accuracy",
            "labeling": {
                "labelSetVersion": "seed-v1",
                "reviewedAt": "2026-06-13",
                "reviewer": "rust-runtime-test",
                "requiredMetrics": ["reading_order_f1"],
                "requiredTags": ["multi-layout"],
                "minCasesPerTag": 1
            },
            "minimums": {"reading_order_f1": 1.0},
            "cases": []
        })
        .to_string(),
    )
    .unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    cmd.write_stdin(
        json!({
            "command": "benchmark_corpus",
            "manifest_path": manifest,
            "offline": true
        })
        .to_string(),
    )
    .assert()
    .failure()
    .stderr(predicate::str::contains("PARSER_ACCURACY_LABELING_INVALID"))
    .stderr(predicate::str::contains("reviewType"));
}

#[test]
fn benchmark_corpus_rejects_human_reviewed_parser_accuracy_without_min_total_cases() {
    let root = temp_dir("doctruth-runtime-min-total-corpus");
    fs::create_dir_all(&root).unwrap();
    let manifest = root.join("corpus.json");
    let mut manifest_json: Value = serde_json::from_str(&benchmark_manifest()).unwrap();
    manifest_json["labeling"]["reviewType"] = json!("human-reviewed");
    fs::write(&manifest, manifest_json.to_string()).unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    cmd.write_stdin(
        json!({
            "command": "benchmark_corpus",
            "manifest_path": manifest,
            "offline": true
        })
        .to_string(),
    )
    .assert()
    .failure()
    .stderr(predicate::str::contains("PARSER_ACCURACY_LABELING_INVALID"))
    .stderr(predicate::str::contains("minTotalCases"));
}

#[test]
fn benchmark_corpus_rejects_human_reviewed_parser_accuracy_without_source_sha() {
    let root = temp_dir("doctruth-runtime-source-pin-corpus");
    fs::create_dir_all(&root).unwrap();
    let manifest = root.join("corpus.json");
    let mut manifest_json: Value = serde_json::from_str(&benchmark_manifest()).unwrap();
    manifest_json["labeling"]["reviewType"] = json!("human-reviewed");
    manifest_json["labeling"]["minTotalCases"] = json!(1);
    fs::write(&manifest, manifest_json.to_string()).unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    cmd.write_stdin(
        json!({
            "command": "benchmark_corpus",
            "manifest_path": manifest,
            "offline": true
        })
        .to_string(),
    )
    .assert()
    .failure()
    .stderr(predicate::str::contains("PARSER_ACCURACY_LABELING_INVALID"))
    .stderr(predicate::str::contains("sourceSha256"))
    .stderr(predicate::str::contains("rust-multi-layout"));
}

#[test]
fn benchmark_corpus_rejects_human_reviewed_parser_accuracy_without_core_metrics() {
    let root = temp_dir("doctruth-runtime-core-metrics-corpus");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(&expected_document, "{}").unwrap();
    let mut manifest_json: Value = serde_json::from_str(&benchmark_manifest()).unwrap();
    manifest_json["labeling"]["reviewType"] = json!("human-reviewed");
    manifest_json["labeling"]["minTotalCases"] = json!(1);
    manifest_json["cases"][0]["sourceSha256"] = json!(sha256_bytes(&fs::read(&pdf).unwrap()));
    fs::write(&manifest, manifest_json.to_string()).unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    cmd.write_stdin(
        json!({
            "command": "benchmark_corpus",
            "manifest_path": manifest,
            "offline": true
        })
        .to_string(),
    )
    .assert()
    .failure()
    .stderr(predicate::str::contains("PARSER_ACCURACY_LABELING_INVALID"))
    .stderr(predicate::str::contains("requiredMetrics"))
    .stderr(predicate::str::contains("bbox_iou"))
    .stderr(predicate::str::contains("ocr_text_accuracy"));
}

#[test]
fn benchmark_corpus_rejects_human_reviewed_parser_accuracy_without_core_tags() {
    let root = temp_dir("doctruth-runtime-core-tags-corpus");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(&expected_document, "{}").unwrap();
    let mut manifest_json: Value = serde_json::from_str(&benchmark_manifest()).unwrap();
    manifest_json["labeling"]["reviewType"] = json!("human-reviewed");
    manifest_json["labeling"]["minTotalCases"] = json!(1);
    manifest_json["labeling"]["requiredMetrics"] = json!([
        "reading_order_f1",
        "quote_anchor_accuracy",
        "bbox_coverage",
        "bbox_iou",
        "evidence_span_accuracy",
        "table_cell_f1",
        "ocr_text_accuracy"
    ]);
    manifest_json["minimums"] = json!({
        "reading_order_f1": 1.0,
        "quote_anchor_accuracy": 1.0,
        "bbox_coverage": 1.0,
        "bbox_iou": 1.0,
        "evidence_span_accuracy": 1.0,
        "table_cell_f1": 1.0,
        "ocr_text_accuracy": 1.0
    });
    manifest_json["cases"][0]["sourceSha256"] = json!(sha256_bytes(&fs::read(&pdf).unwrap()));
    fs::write(&manifest, manifest_json.to_string()).unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    cmd.write_stdin(
        json!({
            "command": "benchmark_corpus",
            "manifest_path": manifest,
            "offline": true
        })
        .to_string(),
    )
    .assert()
    .failure()
    .stderr(predicate::str::contains("PARSER_ACCURACY_LABELING_INVALID"))
    .stderr(predicate::str::contains("requiredTags"))
    .stderr(predicate::str::contains("table"))
    .stderr(predicate::str::contains("source-map"));
}

#[test]
fn benchmark_corpus_rejects_source_sha_mismatch() {
    let root = temp_dir("doctruth-runtime-sha-corpus");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(&expected_document, "{}").unwrap();
    let mut manifest_json: Value = serde_json::from_str(&benchmark_manifest()).unwrap();
    manifest_json["cases"][0]["sourceSha256"] = json!("sha256:not-the-real-hash");
    fs::write(&manifest, manifest_json.to_string()).unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    cmd.write_stdin(
        json!({
            "command": "benchmark_corpus",
            "manifest_path": manifest,
            "offline": true
        })
        .to_string(),
    )
    .assert()
    .failure()
    .stderr(predicate::str::contains("SOURCE_SHA256_MISMATCH"))
    .stderr(predicate::str::contains("fixture.pdf"));
}

#[test]
fn benchmark_corpus_rejects_maximum_threshold_failures() {
    let root = temp_dir("doctruth-runtime-maximum-corpus");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    let mut manifest_json: Value = serde_json::from_str(&benchmark_manifest()).unwrap();
    manifest_json["maximums"] = json!({"reading_order_f1": 0.0});
    fs::write(&manifest, manifest_json.to_string()).unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    cmd.write_stdin(
        json!({
            "command": "benchmark_corpus",
            "manifest_path": manifest,
            "offline": true
        })
        .to_string(),
    )
    .assert()
    .failure()
    .stderr(predicate::str::contains("BENCHMARK_THRESHOLDS_FAILED"))
    .stderr(predicate::str::contains("reading_order_f1"))
    .stderr(predicate::str::contains("above allowed maximum"));
}

#[test]
fn benchmark_corpus_uses_case_preset_for_model_worker_cases() {
    let root = temp_dir("doctruth-runtime-model-corpus");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let worker = write_fake_model_worker();
    fs::write(&pdf, minimal_pdf("Fallback corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Worker corpus evidence.\n").unwrap();
    fs::write(&expected_document, "{}").unwrap();
    let mut manifest_json: Value = serde_json::from_str(&benchmark_manifest()).unwrap();
    manifest_json["cases"].as_array_mut().unwrap()[0]["preset"] = json!("table-lite");
    fs::write(&manifest, manifest_json.to_string()).unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .env("DOCTRUTH_RUNTIME_MODEL_COMMAND", worker)
        .write_stdin(
            json!({
                "command": "benchmark_corpus",
                "manifest_path": manifest,
                "offline": true
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let report: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(report["passed"], true);
    assert_eq!(report["cases"][0]["preset"], "table-lite");
    assert_eq!(report["cases"][0]["metrics"]["reading_order_f1"], 1.0);
}

#[test]
fn benchmark_corpus_scores_expected_document_quality_metrics() {
    let root = temp_dir("doctruth-runtime-quality-corpus");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    fs::write(&pdf, minimal_pdf("Invoice Total 123.")).unwrap();
    fs::write(&expected_markdown, "Invoice Total 123.\n").unwrap();
    fs::write(
        &expected_document,
        json!({
            "docId": "expected",
            "body": {
                "units": [{
                    "unitId": "expected-unit-0001",
                    "kind": "LINE_SPAN",
                    "page": 1,
                    "text": "Invoice Total 123.",
                    "evidenceSpanIds": ["expected-span-0001"],
                    "location": {
                        "page": 1,
                        "readingOrder": 1,
                        "boundingBox": {
                            "x0": 117.6470588235294,
                            "y0": 95.95959595959596,
                            "x1": 324.0000406901042,
                            "y1": 116.16161616161617
                        }
                    },
                    "sourceObjectId": "expected-line-1",
                    "confidence": {"score": 1.0, "rationale": "test label"},
                    "warnings": []
                }]
            }
        })
        .to_string(),
    )
    .unwrap();
    let mut manifest_json: Value = serde_json::from_str(&benchmark_manifest()).unwrap();
    manifest_json["labeling"]["requiredMetrics"] = json!([
        "reading_order_f1",
        "bbox_iou",
        "evidence_span_accuracy",
        "table_cell_f1",
        "ocr_text_accuracy"
    ]);
    manifest_json["minimums"] = json!({
        "reading_order_f1": 1.0,
        "bbox_iou": 1.0,
        "evidence_span_accuracy": 1.0,
        "table_cell_f1": 1.0,
        "ocr_text_accuracy": 1.0
    });
    fs::write(&manifest, manifest_json.to_string()).unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(
            json!({
                "command": "benchmark_corpus",
                "manifest_path": manifest,
                "offline": true
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let report: Value = serde_json::from_slice(&output).unwrap();
    let metrics = &report["cases"][0]["metrics"];
    assert_eq!(metrics["bbox_iou"], 1.0);
    assert_eq!(metrics["evidence_span_accuracy"], 1.0);
    assert_eq!(metrics["table_cell_f1"], 1.0);
    assert_eq!(metrics["ocr_text_accuracy"], 1.0);
}

fn benchmark_manifest() -> String {
    json!({
        "name": "rust-parser-accuracy-seed",
        "kind": "human-labeled",
        "qualityProfile": "parser-accuracy",
        "labeling": {
            "labelSetVersion": "rust-seed-v1",
            "reviewedAt": "2026-06-13",
            "reviewer": "rust-runtime-test",
            "reviewType": "generated-seed",
            "requiredMetrics": [
                "reading_order_f1",
                "quote_anchor_accuracy",
                "bbox_coverage"
            ],
            "requiredTags": ["multi-layout"],
            "minCasesPerTag": 1,
            "requiredFixtureTypes": [
                "simple-single-column",
                "two-column",
                "sidebar-resume",
                "table",
                "borderless-table",
                "scanned-ocr",
                "invoice",
                "mixed-layout"
            ],
            "minCasesPerFixtureType": 1,
            "requiredBehaviors": [
                "xy-cut-edge",
                "safety-filter",
                "structure-tree-preference",
                "table-cluster-heuristics"
            ],
            "minCasesPerBehavior": 1
        },
        "minimums": {
            "reading_order_f1": 1.0,
            "quote_anchor_accuracy": 1.0,
            "bbox_coverage": 1.0
        },
        "cases": [
            {
                "name": "rust-multi-layout",
                "labelId": "rust-seed-v1-0001",
                "tags": ["multi-layout"],
                "fixtureTypes": [
                    "simple-single-column",
                    "two-column",
                    "sidebar-resume",
                    "table",
                    "borderless-table",
                    "scanned-ocr",
                    "invoice",
                    "mixed-layout"
                ],
                "behaviors": [
                    "xy-cut-edge",
                    "safety-filter",
                    "structure-tree-preference",
                    "table-cluster-heuristics"
                ],
                "source": "fixture.pdf",
                "expectedMarkdown": "expected.md",
                "expectedDocument": "expected.json"
            }
        ]
    })
    .to_string()
}

fn benchmark_manifest_with_external() -> String {
    let mut manifest: Value = serde_json::from_str(&benchmark_manifest()).unwrap();
    manifest["minimums"]["opendataloader_nid"] = json!(0.90);
    manifest["minimums"]["opendataloader_teds"] = json!(0.50);
    manifest["minimums"]["opendataloader_mhs"] = json!(0.74);
    manifest["maximums"] = json!({"opendataloader_speed": 0.02});
    manifest["externalEvaluations"] = json!({"opendataloader": "opendataloader-evaluation.json"});
    manifest.to_string()
}

fn write_opendataloader_evaluation(root: &std::path::Path) {
    fs::write(
        root.join("opendataloader-evaluation.json"),
        json!({
            "summary": {
                "engine_name": "doctruth-runtime",
                "engine_version": "test",
                "document_count": 1,
                "elapsed_per_doc": 0.015
            },
            "metrics": {
                "score": {
                    "nid_mean": 0.91,
                    "teds_mean": 0.52,
                    "mhs_mean": 0.76
                }
            }
        })
        .to_string(),
    )
    .unwrap();
}

fn write_fake_model_worker() -> PathBuf {
    let path = temp_dir("doctruth-runtime-corpus-worker").with_extension("py");
    fs::write(
        &path,
        r#"#!/usr/bin/env python3
import json
import sys

request = json.load(sys.stdin)
assert request["preset"] == "table-lite"
print(json.dumps({
    "docId": request["source_hash"],
    "source": {
        "sourceFilename": "worker.pdf",
        "sourceHash": request["source_hash"],
        "metadata": {"sourceFilename": "worker.pdf", "pageCount": 1}
    },
    "body": {
        "pages": [{
            "pageNumber": 1,
            "width": 612.0,
            "height": 792.0,
            "textLayerAvailable": True,
            "imageHash": "sha256:" + "0" * 64
        }],
        "units": [{
            "unitId": "unit-0001",
            "kind": "TABLE_CELL",
            "page": 1,
            "text": "Worker corpus evidence.",
            "evidenceSpanIds": ["span-0001"],
            "location": {
                "page": 1,
                "readingOrder": 1,
                "boundingBox": {"x0": 0.0, "y0": 0.0, "x1": 1000.0, "y1": 1000.0}
            },
            "sourceObjectId": "worker-cell-1",
            "confidence": {"score": 0.93, "rationale": "fake model worker"},
            "warnings": []
        }],
        "tables": []
    },
    "parserRun": {
        "parserVersion": "test-worker",
        "preset": request["preset"],
        "backend": "rust-sidecar+model-worker",
        "models": ["slanet-plus:v1"],
        "warnings": []
    },
    "auditGradeStatus": "AUDIT_GRADE"
}))
"#,
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

fn temp_dir(prefix: &str) -> PathBuf {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let sequence = TEMP_FILE_COUNTER.fetch_add(1, Ordering::Relaxed);
    std::env::temp_dir().join(format!(
        "{prefix}-{}-{nanos}-{sequence}",
        std::process::id()
    ))
}

fn write_recorded_report(manifest: &PathBuf, report_path: &PathBuf) {
    let mut writer = Command::cargo_bin("doctruth-runtime").unwrap();
    writer
        .write_stdin(
            json!({
                "command": "benchmark_corpus",
                "manifest_path": manifest,
                "offline": true,
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .success();
}

fn sha256_bytes(bytes: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(bytes);
    format!("sha256:{}", hex_lower(&hasher.finalize()))
}

fn hex_lower(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn minimal_pdf(text: &str) -> Vec<u8> {
    let escaped = text
        .replace('\\', r"\\")
        .replace('(', r"\(")
        .replace(')', r"\)");
    let stream = format!("BT\n/F1 16 Tf\n72 700 Td\n({escaped}) Tj\nET\n");
    let objects = [
        "<< /Type /Catalog /Pages 2 0 R >>".to_string(),
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_string(),
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>".to_string(),
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_string(),
        format!("<< /Length {} >>\nstream\n{}endstream", stream.len(), stream),
    ];
    write_pdf_objects(&objects)
}

fn write_pdf_objects(objects: &[String]) -> Vec<u8> {
    let mut pdf = b"%PDF-1.4\n".to_vec();
    let mut offsets = Vec::new();
    for (index, object) in objects.iter().enumerate() {
        offsets.push(pdf.len());
        pdf.extend_from_slice(format!("{} 0 obj\n{}\nendobj\n", index + 1, object).as_bytes());
    }
    write_xref(&mut pdf, objects.len(), &offsets);
    pdf
}

fn write_xref(pdf: &mut Vec<u8>, object_count: usize, offsets: &[usize]) {
    let xref_offset = pdf.len();
    pdf.extend_from_slice(
        format!("xref\n0 {}\n0000000000 65535 f \n", object_count + 1).as_bytes(),
    );
    for offset in offsets {
        pdf.extend_from_slice(format!("{offset:010} 00000 n \n").as_bytes());
    }
    pdf.extend_from_slice(
        format!(
            "trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{}\n%%EOF\n",
            object_count + 1,
            xref_offset
        )
        .as_bytes(),
    );
}
