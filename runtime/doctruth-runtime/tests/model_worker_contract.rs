use assert_cmd::Command;
use predicates::prelude::*;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

static TEMP_FILE_COUNTER: AtomicU64 = AtomicU64::new(1);

#[test]
fn parse_pdf_routes_model_assisted_preset_to_configured_worker() {
    let pdf = write_pdf_fixture("Fallback text should not be used.");
    let worker = write_fake_model_worker();
    let (cache_dir, manifest) = ready_mnn_model_manifest("doctruth-runtime-route-model-cache");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .env("DOCTRUTH_RUNTIME_MODEL_COMMAND", &worker)
        .env("DOCTRUTH_MODEL_CACHE", &cache_dir)
        .env("DOCTRUTH_MODEL_MANIFEST", &manifest)
        .write_stdin(parse_request(&pdf, "table-lite"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(json["docId"], "sha256:model-worker");
    assert_eq!(json["parserRun"]["backend"], "rust-sidecar+model-worker");
    assert_eq!(json["parserRun"]["preset"], "table-lite");
    assert_eq!(
        json["parserRun"]["models"],
        serde_json::json!(["slanet-plus:v1"])
    );
    assert_eq!(json["auditGradeStatus"], "AUDIT_GRADE");
    assert_eq!(json["body"]["units"][0]["kind"], "TABLE_CELL");
    assert_eq!(json["body"]["units"][0]["text"], "Worker model evidence");
}

#[test]
fn parse_pdf_edge_fast_profile_does_not_start_configured_worker() {
    let pdf = write_pdf_fixture("Edge fast deterministic evidence.");
    let worker = write_failing_model_worker();
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .env("DOCTRUTH_RUNTIME_MODEL_COMMAND", &worker)
        .write_stdin(parse_request_with_runtime_profile(
            &pdf,
            "table-lite",
            "edge-fast",
        ))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let warnings = json["parserRun"]["warnings"].as_array().unwrap();

    assert_eq!(json["parserRun"]["backend"], "rust-sidecar");
    assert_eq!(json["parserRun"]["profile"], "edge-fast");
    assert_eq!(json["parserRun"]["preset"], "table-lite");
    assert_eq!(json["auditGradeStatus"], "NOT_AUDIT_GRADE");
    assert_eq!(
        json["body"]["units"][0]["text"],
        "Edge fast deterministic evidence."
    );
    assert!(
        warnings.iter().any(|warning| {
            warning["code"] == "model_unavailable_fallback"
                && warning["severity"] == "SEVERE"
                && warning["message"]
                    .as_str()
                    .is_some_and(|message| message.contains("edge-fast"))
        }),
        "expected edge-fast warning to explain model startup was disabled, got {warnings:?}"
    );
}

#[test]
fn parse_pdf_auto_preset_simple_text_does_not_start_mnn_worker() {
    let pdf = write_pdf_fixture("Simple text should stay deterministic.");
    let worker = write_failing_model_worker();
    let (cache_dir, manifest) = ready_mnn_model_manifest("doctruth-runtime-auto-simple-cache");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .env("DOCTRUTH_RUNTIME_MODEL_COMMAND", &worker)
        .env("DOCTRUTH_MODEL_CACHE", &cache_dir)
        .env("DOCTRUTH_MODEL_MANIFEST", &manifest)
        .write_stdin(parse_request(&pdf, "auto"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();

    assert_eq!(json["parserRun"]["backend"], "rust-sidecar");
    assert_eq!(json["parserRun"]["profile"], "edge-model");
    assert_eq!(json["parserRun"]["preset"], "auto");
    assert_eq!(json["parserRun"]["modelRouting"]["mode"], "auto");
    assert_eq!(
        json["parserRun"]["modelRouting"]["decision"],
        "deterministic-only"
    );
    assert_eq!(
        json["parserRun"]["modelRouting"]["startedModelRuntime"],
        false
    );
    assert_eq!(json["parserRun"]["modelRouting"]["routedPages"], json!([]));
    assert_eq!(json["auditGradeStatus"], "AUDIT_GRADE");
    assert_eq!(
        json["body"]["units"][0]["text"],
        "Simple text should stay deterministic."
    );
}

#[test]
fn parse_pdf_auto_preset_table_heavy_routes_to_table_mnn_worker() {
    let pdf = write_pdf_fixture("Item Qty Price\nA 2 10\nB 4 20\nTotal 6 30");
    let worker = write_auto_table_model_worker();
    let (cache_dir, manifest) = ready_mnn_model_manifest("doctruth-runtime-auto-table-cache");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .env("DOCTRUTH_RUNTIME_MODEL_COMMAND", &worker)
        .env("DOCTRUTH_MODEL_CACHE", &cache_dir)
        .env("DOCTRUTH_MODEL_MANIFEST", &manifest)
        .write_stdin(parse_request(&pdf, "auto"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();

    assert_eq!(json["parserRun"]["backend"], "rust-sidecar+model-worker");
    assert_eq!(json["parserRun"]["preset"], "table-lite");
    assert_eq!(json["parserRun"]["profile"], "edge-model");
    assert_eq!(json["parserRun"]["modelRouting"]["mode"], "auto");
    assert_eq!(
        json["parserRun"]["modelRouting"]["decision"],
        "model-runtime"
    );
    assert_eq!(
        json["parserRun"]["modelRouting"]["startedModelRuntime"],
        true
    );
    assert_eq!(json["parserRun"]["modelRouting"]["routedPages"], json!([1]));
    assert_eq!(
        json["parserRun"]["modelRouting"]["models"],
        json!(["slanet-plus:v1"])
    );
    assert_eq!(json["body"]["units"][0]["kind"], "TABLE_CELL");
    assert_eq!(
        json["body"]["units"][0]["text"],
        "Auto table model evidence"
    );
}

#[test]
fn parse_pdf_auto_preset_scanned_pdf_routes_to_ocr_mnn_worker() {
    let pdf = write_empty_text_layer_pdf();
    let worker = write_auto_ocr_model_worker();
    let (cache_dir, manifest) = ready_mnn_ocr_model_manifest("doctruth-runtime-auto-ocr-cache");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .env("DOCTRUTH_RUNTIME_MODEL_COMMAND", &worker)
        .env("DOCTRUTH_MODEL_CACHE", &cache_dir)
        .env("DOCTRUTH_MODEL_MANIFEST", &manifest)
        .write_stdin(parse_request(&pdf, "auto"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();

    assert_eq!(json["parserRun"]["backend"], "rust-sidecar+model-worker");
    assert_eq!(json["parserRun"]["preset"], "ocr");
    assert_eq!(json["parserRun"]["profile"], "edge-model");
    assert_eq!(json["parserRun"]["modelRouting"]["mode"], "auto");
    assert_eq!(
        json["parserRun"]["modelRouting"]["decision"],
        "model-runtime"
    );
    assert_eq!(json["parserRun"]["modelRouting"]["route"], "ocr-model");
    assert_eq!(
        json["parserRun"]["modelRouting"]["startedModelRuntime"],
        true
    );
    assert_eq!(json["parserRun"]["modelRouting"]["routedPages"], json!([1]));
    assert_eq!(
        json["parserRun"]["modelRouting"]["models"],
        json!(["ocr-router:v1"])
    );
    assert_eq!(json["body"]["units"][0]["kind"], "OCR_REGION");
    assert_eq!(json["body"]["units"][0]["text"], "Auto OCR evidence");
}

#[test]
fn rust_mnn_model_worker_doctor_is_python_free() {
    let mut cmd = Command::cargo_bin("doctruth-mnn-model-worker").unwrap();

    let output = cmd
        .arg("--doctor")
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(json["ok"], true);
    assert_eq!(json["runtime"], "mnn");
    assert_eq!(json["engine"], "mnn");
    assert_eq!(json["code"], "protocol_ready");
    assert_eq!(json["protocolReady"], true);
    assert_eq!(json["inferenceReady"], false);
    assert_eq!(json["nativeBackend"]["compiled"], false);
    assert_eq!(json["nativeBackend"]["crate"], "mnn-rs");
    assert_eq!(json["stubMode"], false);
    assert_eq!(json["productionPythonResidency"], false);
}

#[test]
fn rust_mnn_model_worker_rejects_non_mnn_artifacts() {
    let model_path = temp_path("doctruth-runtime-worker-onnx", "onnx");
    fs::write(&model_path, b"onnx").unwrap();
    let mut cmd = Command::cargo_bin("doctruth-mnn-model-worker").unwrap();

    cmd.write_stdin(
        json!({
            "command": "parse_pdf",
            "source_path": "document.pdf",
            "source_hash": "sha256:model-worker",
            "preset": "table-lite",
            "models": [{
                "name": "slanet-plus",
                "version": "v1",
                "backend": "onnxruntime",
                "format": "onnx",
                "cacheStatus": "READY",
                "cachePath": model_path
            }]
        })
        .to_string(),
    )
    .assert()
    .failure()
    .stderr(predicate::str::contains("unsupported_model_runtime"));
}

#[test]
fn rust_mnn_model_worker_rejects_inference_without_stub_or_backend() {
    let model_path = temp_path("doctruth-runtime-worker-mnn", "mnn");
    fs::write(&model_path, b"mnn").unwrap();
    let mut cmd = Command::cargo_bin("doctruth-mnn-model-worker").unwrap();

    cmd.write_stdin(
        json!({
            "command": "parse_pdf",
            "source_path": "document.pdf",
            "source_hash": "sha256:model-worker",
            "preset": "table-lite",
            "models": [{
                "name": "slanet-plus",
                "version": "v1",
                "backend": "mnn",
                "format": "mnn",
                "cacheStatus": "READY",
                "cachePath": model_path
            }]
        })
        .to_string(),
    )
    .assert()
    .failure()
    .stderr(predicate::str::contains("mnn_inference_unavailable"));
}

#[test]
fn rust_mnn_model_worker_stub_mode_is_explicit() {
    let model_path = temp_path("doctruth-runtime-worker-mnn-stub", "mnn");
    fs::write(&model_path, b"mnn").unwrap();
    let mut cmd = Command::cargo_bin("doctruth-mnn-model-worker").unwrap();

    let output = cmd
        .env("DOCTRUTH_MNN_WORKER_STUB", "1")
        .write_stdin(
            json!({
                "command": "parse_pdf",
                "source_path": "document.pdf",
                "source_hash": "sha256:model-worker",
                "preset": "table-lite",
                "models": [{
                    "name": "slanet-plus",
                    "version": "v1",
                    "backend": "mnn",
                    "format": "mnn",
                    "cacheStatus": "READY",
                    "cachePath": model_path
                }]
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(json["metrics"]["stubMode"], true);
    assert_eq!(
        json["document"]["parserRun"]["workerBackend"],
        "mnn-model-worker-stub"
    );
    assert_eq!(json["document"]["auditGradeStatus"], "NOT_AUDIT_GRADE");
}

#[test]
fn parse_pdf_routes_to_rust_mnn_model_worker_binary() {
    let pdf = write_pdf_fixture("Fallback text should not be used.");
    let worker = assert_cmd::cargo::cargo_bin("doctruth-mnn-model-worker");
    let (cache_dir, manifest) = ready_mnn_model_manifest("doctruth-runtime-rust-mnn-cache");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .env("DOCTRUTH_MNN_WORKER_STUB", "1")
        .env("DOCTRUTH_RUNTIME_MODEL_COMMAND", &worker)
        .env("DOCTRUTH_MODEL_CACHE", &cache_dir)
        .env("DOCTRUTH_MODEL_MANIFEST", &manifest)
        .write_stdin(parse_request(&pdf, "table-lite"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(json["parserRun"]["backend"], "rust-sidecar+model-worker");
    assert_eq!(json["parserRun"]["workerBackend"], "mnn-model-worker-stub");
    assert_eq!(json["parserRun"]["modelRuntime"]["runtime"], "mnn");
    assert_eq!(json["body"]["units"][0]["kind"], "TABLE_CELL");
    assert_eq!(json["body"]["units"][0]["text"], "Auto table MNN evidence");
}

#[test]
fn parse_pdf_auto_ocr_route_discovers_packaged_rust_mnn_worker() {
    let pdf = write_empty_text_layer_pdf();
    let bin_dir = temp_dir("doctruth-runtime-packaged-ocr-bin");
    fs::create_dir_all(&bin_dir).unwrap();
    let source_worker = assert_cmd::cargo::cargo_bin("doctruth-mnn-model-worker");
    let worker = bin_dir.join("doctruth-mnn-model-worker");
    fs::copy(&source_worker, &worker).unwrap();
    make_executable(&worker);
    let (cache_dir, manifest) =
        ready_mnn_ocr_model_manifest("doctruth-runtime-auto-ocr-path-cache");
    let path = prepend_path(&bin_dir);
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .env("DOCTRUTH_MNN_WORKER_STUB", "1")
        .env("PATH", path)
        .env("DOCTRUTH_MODEL_CACHE", &cache_dir)
        .env("DOCTRUTH_MODEL_MANIFEST", &manifest)
        .write_stdin(parse_request(&pdf, "auto"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();

    assert_eq!(json["parserRun"]["backend"], "rust-sidecar+model-worker");
    assert_eq!(json["parserRun"]["workerBackend"], "mnn-model-worker-stub");
    assert_eq!(json["parserRun"]["preset"], "ocr");
    assert_eq!(json["parserRun"]["modelRouting"]["route"], "ocr-model");
    assert_eq!(json["body"]["units"][0]["kind"], "OCR_REGION");
    assert_eq!(json["body"]["units"][0]["text"], "Auto OCR evidence");
}

#[test]
fn parse_pdf_reports_configured_worker_bad_json_as_stable_error() {
    let pdf = write_pdf_fixture("Fallback text should not be used.");
    let worker = write_bad_model_worker();
    let (cache_dir, manifest) = ready_mnn_model_manifest("doctruth-runtime-bad-json-cache");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    cmd.env("DOCTRUTH_RUNTIME_MODEL_COMMAND", &worker)
        .env("DOCTRUTH_MODEL_CACHE", &cache_dir)
        .env("DOCTRUTH_MODEL_MANIFEST", &manifest)
        .write_stdin(parse_request(&pdf, "table-lite"))
        .assert()
        .failure()
        .stderr(predicate::str::contains("MODEL_WORKER_FAILED"))
        .stderr(predicate::str::contains("invalid JSON"));
}

#[test]
fn parse_pdf_sends_manifest_cache_metadata_to_configured_worker() {
    let pdf = write_pdf_fixture("Fallback text should not be used.");
    let worker = write_cache_asserting_model_worker();
    let cache_dir = temp_dir("doctruth-runtime-model-cache");
    fs::create_dir_all(&cache_dir).unwrap();
    let artifact = b"ready model artifact";
    let artifact_sha = sha256(artifact);
    let artifact_path = cache_dir.join("slanet-plus-v1.bin");
    fs::write(&artifact_path, artifact).unwrap();
    let manifest = temp_path("doctruth-runtime-model-manifest", "json");
    fs::write(
        &manifest,
        json!({
            "presets": {
                "table-lite": [
                    {
                        "name": "slanet-plus",
                        "version": "v1",
                        "sha256": artifact_sha,
                        "sizeBytes": artifact.len(),
                        "required": true,
                        "task": "table-structure-recognition",
                        "backend": "mnn",
                        "format": "mnn",
                        "precision": "fp32",
                        "license": "test"
                    }
                ]
            }
        })
        .to_string(),
    )
    .unwrap();
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .env("DOCTRUTH_RUNTIME_MODEL_COMMAND", &worker)
        .env("DOCTRUTH_MODEL_CACHE", &cache_dir)
        .env("DOCTRUTH_MODEL_MANIFEST", &manifest)
        .write_stdin(parse_request(&pdf, "table-lite"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(json["parserRun"]["backend"], "rust-sidecar+model-worker");
    assert_eq!(
        json["body"]["units"][0]["text"],
        "Worker cache metadata evidence"
    );
    assert_eq!(json["parserRun"]["modelRuntime"]["runtime"], "mnn");
    assert_eq!(json["parserRun"]["modelRuntime"]["loadPolicy"], "lazy");
    assert_eq!(
        json["parserRun"]["modelRuntime"]["unloadPolicy"],
        "idle-after-request"
    );
}

#[test]
fn parse_pdf_edge_model_rejects_onnx_manifest_and_does_not_start_worker() {
    let pdf = write_pdf_fixture("Unsupported ONNX manifest fallback evidence.");
    let worker = write_failing_model_worker();
    let cache_dir = temp_dir("doctruth-runtime-onnx-model-cache");
    fs::create_dir_all(&cache_dir).unwrap();
    let artifact = b"onnx artifact should not be production";
    let artifact_sha = sha256(artifact);
    let artifact_path = cache_dir.join("slanet-plus-v1.bin");
    fs::write(&artifact_path, artifact).unwrap();
    let manifest = temp_path("doctruth-runtime-onnx-model-manifest", "json");
    fs::write(
        &manifest,
        json!({
            "presets": {
                "table-lite": [
                    {
                        "name": "slanet-plus",
                        "version": "v1",
                        "sha256": artifact_sha,
                        "sizeBytes": artifact.len(),
                        "required": true,
                        "task": "table-structure-recognition",
                        "backend": "onnxruntime",
                        "format": "onnx",
                        "precision": "int8",
                        "license": "test"
                    }
                ]
            }
        })
        .to_string(),
    )
    .unwrap();
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .env("DOCTRUTH_RUNTIME_MODEL_COMMAND", &worker)
        .env("DOCTRUTH_MODEL_CACHE", &cache_dir)
        .env("DOCTRUTH_MODEL_MANIFEST", &manifest)
        .write_stdin(parse_request(&pdf, "table-lite"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let warnings = json["parserRun"]["warnings"].as_array().unwrap();

    assert_eq!(json["parserRun"]["profile"], "edge-model");
    assert_eq!(json["parserRun"]["backend"], "rust-sidecar");
    assert_eq!(json["auditGradeStatus"], "NOT_AUDIT_GRADE");
    assert!(
        warnings.iter().any(|warning| {
            warning["code"] == "model_unavailable_fallback"
                && warning["severity"] == "SEVERE"
                && warning["message"]
                    .as_str()
                    .is_some_and(|message| message.contains("unsupported model runtime"))
        }),
        "expected unsupported model runtime warning, got {warnings:?}"
    );
}

#[test]
fn parse_pdf_accepts_worker_envelope_with_document_payload() {
    let pdf = write_pdf_fixture("Fallback text should not be used.");
    let worker = write_enveloped_model_worker();
    let (cache_dir, manifest) = ready_mnn_model_manifest("doctruth-runtime-envelope-cache");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .env("DOCTRUTH_RUNTIME_MODEL_COMMAND", &worker)
        .env("DOCTRUTH_MODEL_CACHE", &cache_dir)
        .env("DOCTRUTH_MODEL_MANIFEST", &manifest)
        .write_stdin(parse_request(&pdf, "table-lite"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(json["docId"], "sha256:model-worker");
    assert_eq!(json["parserRun"]["backend"], "rust-sidecar+model-worker");
    assert_eq!(json["parserRun"]["workerBackend"], "pdfbox+model-worker");
    assert_eq!(json["parserRun"]["modelRuntime"]["runtime"], "mnn");
    assert_eq!(json["parserRun"]["modelRuntime"]["coldStartMs"], 12.5);
    assert_eq!(json["parserRun"]["modelRuntime"]["inferenceMs"], 3.25);
    assert_eq!(json["parserRun"]["modelRuntime"]["rssMb"], 188);
    assert_eq!(json["parserRun"]["modelRuntime"]["peakMemoryMb"], 221);
    assert_eq!(
        json["parserRun"]["modelRuntime"]["loadedModels"],
        json!(["slanet-plus:v1"])
    );
    assert_eq!(
        json["parserRun"]["modelRuntime"]["unload"]["status"],
        "scheduled"
    );
    assert_eq!(json["body"]["units"][0]["text"], "Worker envelope evidence");
    assert!(json.get("ok").is_none(), "{json}");
}

fn parse_request(source_path: &Path, preset: &str) -> String {
    format!(
        r#"{{"command":"parse_pdf","source_path":"{}","source_hash":"sha256:model-worker","preset":"{}","offline_mode":true,"allow_model_downloads":false}}"#,
        source_path.display(),
        preset
    )
}

fn parse_request_with_runtime_profile(source_path: &Path, preset: &str, profile: &str) -> String {
    format!(
        r#"{{"command":"parse_pdf","source_path":"{}","source_hash":"sha256:model-worker","preset":"{}","profile":"{}","offline_mode":true,"allow_model_downloads":false}}"#,
        source_path.display(),
        preset,
        profile
    )
}

fn ready_mnn_model_manifest(prefix: &str) -> (PathBuf, PathBuf) {
    let cache_dir = temp_dir(prefix);
    fs::create_dir_all(&cache_dir).unwrap();
    let artifact = b"ready mnn model artifact";
    let artifact_sha = sha256(artifact);
    let artifact_path = cache_dir.join("slanet-plus-v1.bin");
    fs::write(&artifact_path, artifact).unwrap();
    let manifest = temp_path(&format!("{prefix}-manifest"), "json");
    fs::write(
        &manifest,
        json!({
            "presets": {
                "table-lite": [
                    {
                        "name": "slanet-plus",
                        "version": "v1",
                        "sha256": artifact_sha,
                        "sizeBytes": artifact.len(),
                        "required": true,
                        "task": "table-structure-recognition",
                        "backend": "mnn",
                        "format": "mnn",
                        "precision": "fp32",
                        "license": "test"
                    }
                ]
            }
        })
        .to_string(),
    )
    .unwrap();
    (cache_dir, manifest)
}

fn ready_mnn_ocr_model_manifest(prefix: &str) -> (PathBuf, PathBuf) {
    let cache_dir = temp_dir(prefix);
    fs::create_dir_all(&cache_dir).unwrap();
    let artifact = b"ready mnn ocr model artifact";
    let artifact_sha = sha256(artifact);
    let artifact_path = cache_dir.join("ocr-router-v1.bin");
    fs::write(&artifact_path, artifact).unwrap();
    let manifest = temp_path(&format!("{prefix}-manifest"), "json");
    fs::write(
        &manifest,
        json!({
            "presets": {
                "ocr": [
                    {
                        "name": "ocr-router",
                        "version": "v1",
                        "sha256": artifact_sha,
                        "sizeBytes": artifact.len(),
                        "required": true,
                        "task": "ocr",
                        "backend": "mnn",
                        "format": "mnn",
                        "precision": "fp32",
                        "license": "test"
                    }
                ]
            }
        })
        .to_string(),
    )
    .unwrap();
    (cache_dir, manifest)
}

fn write_failing_model_worker() -> PathBuf {
    write_worker_script(
        "doctruth-runtime-failing-model-worker",
        r#"#!/usr/bin/env python3
import sys

sys.stderr.write("edge-fast must not start this worker\n")
sys.exit(17)
"#,
    )
}

fn write_enveloped_model_worker() -> PathBuf {
    write_worker_script(
        "doctruth-runtime-enveloped-model-worker",
        r#"#!/usr/bin/env python3
import json
import sys

request = json.load(sys.stdin)
print(json.dumps({
    "ok": True,
    "document": {
        "docId": request["source_hash"],
        "source": {
            "sourceFilename": "worker-envelope.pdf",
            "sourceHash": request["source_hash"],
            "metadata": {"sourceFilename": "worker-envelope.pdf", "pageCount": 1}
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
                "text": "Worker envelope evidence",
                "evidenceSpanIds": ["span-0001"],
                "location": {
                    "page": 1,
                    "readingOrder": 1,
                    "boundingBox": {"x0": 0.0, "y0": 0.0, "x1": 1000.0, "y1": 1000.0}
                },
                "sourceObjectId": "worker-envelope-cell-1",
                "confidence": {"score": 0.95, "rationale": "fake enveloped worker"},
                "warnings": []
            }],
            "tables": []
        },
        "parserRun": {
            "parserRunId": "parser-run-worker-envelope",
            "parserVersion": "test-worker",
            "preset": request["preset"],
            "backend": "pdfbox+model-worker",
            "models": ["slanet-plus:v1"],
            "warnings": []
        },
        "auditGradeStatus": "AUDIT_GRADE"
    },
    "metrics": {
        "inputSource": "synthetic_tensor",
        "runtime": "mnn",
        "coldStartMs": 12.5,
        "inferenceMs": 3.25,
        "rssMb": 188,
        "peakMemoryMb": 221,
        "loadedModels": ["slanet-plus:v1"],
        "unload": {"status": "scheduled", "policy": "idle-after-request"}
    }
}))
"#,
    )
}

fn write_cache_asserting_model_worker() -> PathBuf {
    write_worker_script(
        "doctruth-runtime-cache-model-worker",
        r#"#!/usr/bin/env python3
import json
import pathlib
import sys

request = json.load(sys.stdin)
cache = pathlib.Path(request["modelCacheDirectory"])
model = request["models"][0]
assert request["modelRuntime"]["runtime"] == "mnn"
assert request["modelRuntime"]["loadPolicy"] == "lazy"
assert request["modelRuntime"]["unloadPolicy"] == "idle-after-request"
assert cache.exists()
assert model["name"] == "slanet-plus"
assert model["version"] == "v1"
assert model["cacheStatus"] == "READY"
assert pathlib.Path(model["cachePath"]).parent == cache
assert model["actualSha256"] == model["sha256"]
assert model["actualSizeBytes"] > 0
assert model["task"] == "table-structure-recognition"
assert model["backend"] == "mnn"
assert model["format"] == "mnn"
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
            "text": "Worker cache metadata evidence",
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
}

fn write_auto_table_model_worker() -> PathBuf {
    write_worker_script(
        "doctruth-runtime-auto-table-model-worker",
        r#"#!/usr/bin/env python3
import json
import sys

request = json.load(sys.stdin)
assert request["preset"] == "table-lite"
assert request["modelRouting"]["mode"] == "auto"
assert request["modelRouting"]["decision"] == "model-runtime"
assert request["modelRouting"]["route"] == "table-model"
assert request["models"][0]["name"] == "slanet-plus"
assert request["models"][0]["backend"] == "mnn"
assert request["models"][0]["format"] == "mnn"
print(json.dumps({
    "docId": request["source_hash"],
    "source": {
        "sourceFilename": "auto-table-worker.pdf",
        "sourceHash": request["source_hash"],
        "metadata": {"sourceFilename": "auto-table-worker.pdf", "pageCount": 1}
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
            "text": "Auto table model evidence",
            "evidenceSpanIds": ["span-0001"],
            "location": {
                "page": 1,
                "readingOrder": 1,
                "boundingBox": {"x0": 0.0, "y0": 0.0, "x1": 1000.0, "y1": 1000.0}
            },
            "sourceObjectId": "auto-table-worker-cell-1",
            "confidence": {"score": 0.95, "rationale": "fake auto table worker"},
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
    "auditGradeStatus": "AUDIT_GRADE",
    "metrics": {
        "runtime": "mnn",
        "coldStartMs": 9.0,
        "inferenceMs": 2.0,
        "loadedModels": ["slanet-plus:v1"],
        "unload": {"status": "scheduled", "policy": "idle-after-request"}
    }
}))
"#,
    )
}

fn write_auto_ocr_model_worker() -> PathBuf {
    write_worker_script(
        "doctruth-runtime-auto-ocr-model-worker",
        auto_ocr_worker_script_body(),
    )
}

fn auto_ocr_worker_script_body() -> &'static str {
    r#"#!/usr/bin/env python3
import json
import sys

request = json.load(sys.stdin)
assert request["preset"] == "ocr"
assert request["modelRouting"]["mode"] == "auto"
assert request["modelRouting"]["decision"] == "model-runtime"
assert request["modelRouting"]["route"] == "ocr-model"
assert request["models"][0]["name"] == "ocr-router"
assert request["models"][0]["backend"] == "mnn"
assert request["models"][0]["format"] == "mnn"
print(json.dumps({
    "docId": request["source_hash"],
    "source": {
        "sourceFilename": "auto-ocr-worker.pdf",
        "sourceHash": request["source_hash"],
        "metadata": {"sourceFilename": "auto-ocr-worker.pdf", "pageCount": 1}
    },
    "body": {
        "pages": [{
            "pageNumber": 1,
            "width": 612.0,
            "height": 792.0,
            "textLayerAvailable": False,
            "imageHash": "sha256:" + "0" * 64
        }],
        "units": [{
            "unitId": "unit-0001",
            "kind": "OCR_REGION",
            "page": 1,
            "text": "Auto OCR evidence",
            "evidenceSpanIds": ["span-0001"],
            "location": {
                "page": 1,
                "readingOrder": 1,
                "boundingBox": {"x0": 20.0, "y0": 20.0, "x1": 200.0, "y1": 80.0}
            },
            "sourceObjectId": "auto-ocr-worker-region-1",
            "confidence": {"score": 0.91, "rationale": "fake auto ocr worker"},
            "warnings": []
        }],
        "tables": []
    },
    "parserRun": {
        "parserVersion": "test-worker",
        "preset": request["preset"],
        "backend": "rapidocr-worker",
        "models": ["ocr-router:v1"],
        "warnings": []
    },
    "auditGradeStatus": "AUDIT_GRADE",
    "metrics": {
        "runtime": "mnn",
        "coldStartMs": 10.0,
        "inferenceMs": 5.0,
        "loadedModels": ["ocr-router:v1"],
        "unload": {"status": "scheduled", "policy": "idle-after-request"}
    }
}))
"#
}

fn prepend_path(bin_dir: &Path) -> String {
    let existing = std::env::var("PATH").unwrap_or_default();
    format!("{}:{}", bin_dir.display(), existing)
}

fn make_executable(path: &Path) {
    let mut permissions = fs::metadata(path).unwrap().permissions();
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        permissions.set_mode(0o755);
        fs::set_permissions(path, permissions).unwrap();
    }
}

fn write_fake_model_worker() -> PathBuf {
    write_worker_script(
        "doctruth-runtime-model-worker",
        r#"#!/usr/bin/env python3
import json
import sys

request = json.load(sys.stdin)
assert request["preset"] == "table-lite"
assert request["requiredModels"][0]["name"] == "slanet-plus"
assert request["models"][0]["backend"] == "mnn"
assert request["models"][0]["format"] == "mnn"
assert request["models"][0]["cacheStatus"] == "READY"
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
            "text": "Worker model evidence",
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
}

fn write_bad_model_worker() -> PathBuf {
    write_worker_script(
        "doctruth-runtime-bad-model-worker",
        "#!/usr/bin/env python3\nprint('not json')\n",
    )
}

fn write_worker_script(prefix: &str, body: &str) -> PathBuf {
    let path = temp_path(prefix, "py");
    fs::write(&path, body).unwrap();
    make_executable(&path);
    path
}

fn write_pdf_fixture(text: &str) -> PathBuf {
    let path = temp_path("doctruth-runtime-worker-fixture", "pdf");
    fs::write(&path, minimal_pdf(text)).unwrap();
    path
}

fn write_empty_text_layer_pdf() -> PathBuf {
    let path = temp_path("doctruth-runtime-worker-empty-text-layer", "pdf");
    fs::write(&path, minimal_empty_text_layer_pdf()).unwrap();
    path
}

fn temp_path(prefix: &str, extension: &str) -> PathBuf {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let sequence = TEMP_FILE_COUNTER.fetch_add(1, Ordering::Relaxed);
    std::env::temp_dir().join(format!(
        "{prefix}-{}-{nanos}-{sequence}.{extension}",
        std::process::id()
    ))
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

fn sha256(bytes: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(bytes);
    format!("sha256:{:x}", hasher.finalize())
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

fn minimal_empty_text_layer_pdf() -> Vec<u8> {
    let stream = "q\n0.95 0.95 0.95 rg\n72 600 120 60 re\nf\nQ\n";
    let objects = [
        "<< /Type /Catalog /Pages 2 0 R >>".to_string(),
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_string(),
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> /Contents 4 0 R >>"
            .to_string(),
        format!(
            "<< /Length {} >>\nstream\n{}endstream",
            stream.len(),
            stream
        ),
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
