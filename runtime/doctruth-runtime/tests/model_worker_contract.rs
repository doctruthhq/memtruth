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
        serde_json::json!(["xenova-table-transformer-structure-recognition:model-main-2026-06-30"])
    );
    assert_eq!(json["auditGradeStatus"], "AUDIT_GRADE");
    assert_eq!(json["body"]["units"][0]["kind"], "TABLE_CELL");
    assert_eq!(json["body"]["units"][0]["text"], "Worker model evidence");
}

#[test]
fn runtime_jsonl_batch_keeps_model_worker_alive_until_all_jobs_complete() {
    let first_pdf = write_pdf_fixture("First table job should use the warm worker.");
    let second_pdf = write_pdf_fixture("Second table job should reuse the warm worker.");
    let worker_start_log = temp_path("doctruth-runtime-worker-starts", "log");
    let worker = write_jsonl_persistent_model_worker();
    let (cache_dir, manifest) = ready_mnn_model_manifest("doctruth-runtime-jsonl-worker-cache");
    let input = format!(
        "{}\n{}\n",
        parse_request(&first_pdf, "table-lite"),
        parse_request(&second_pdf, "table-lite")
    );
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .env("DOCTRUTH_RUNTIME_MODEL_COMMAND", &worker)
        .env("DOCTRUTH_MODEL_CACHE", &cache_dir)
        .env("DOCTRUTH_MODEL_MANIFEST", &manifest)
        .env("DOCTRUTH_TEST_WORKER_START_LOG", &worker_start_log)
        .write_stdin(input)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let lines = String::from_utf8(output).unwrap();
    let documents = lines
        .lines()
        .map(|line| serde_json::from_str::<Value>(line).unwrap())
        .collect::<Vec<_>>();
    assert_eq!(documents.len(), 2, "{lines}");
    assert_eq!(fs::read_to_string(&worker_start_log).unwrap(), "started\n");
    for document in documents {
        assert_eq!(
            document["parserRun"]["backend"],
            "rust-sidecar+model-worker"
        );
        assert_eq!(
            document["parserRun"]["modelRuntime"]["unloadPolicy"],
            "after-job-batch"
        );
        assert_eq!(
            document["parserRun"]["modelRuntime"]["unload"]["status"],
            "deferred"
        );
    }
}

#[test]
fn runtime_jsonl_batch_keeps_rapidocr_worker_alive_for_all_ocr_jobs() {
    let fake_python_path = write_fake_rapidocr_pythonpath();
    let first_image = write_fake_png("doctruth-runtime-rapidocr-runtime-first");
    let second_image = write_fake_png("doctruth-runtime-rapidocr-runtime-second");
    let worker_start_log = temp_path("doctruth-runtime-rapidocr-worker-starts", "log");
    let worker = write_rapidocr_worker_wrapper(&worker_start_log);
    let (cache_dir, manifest) =
        ready_mnn_ocr_model_pack_manifest("doctruth-runtime-rapidocr-jsonl-cache");
    let input = format!(
        "{}\n{}\n",
        json!({
            "command": "parse_pdf",
            "source_path": first_image,
            "source_hash": "sha256:rapidocr-first",
            "preset": "ocr",
            "offline_mode": true,
            "allow_model_downloads": false
        }),
        json!({
            "command": "parse_pdf",
            "source_path": second_image,
            "source_hash": "sha256:rapidocr-second",
            "preset": "ocr",
            "offline_mode": true,
            "allow_model_downloads": false
        })
    );
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .env("DOCTRUTH_RUNTIME_MODEL_COMMAND", &worker)
        .env("DOCTRUTH_MODEL_CACHE", &cache_dir)
        .env("DOCTRUTH_MODEL_MANIFEST", &manifest)
        .env("DOCTRUTH_ALLOW_PYTHON_ORACLE", "1")
        .env("PYTHONPATH", fake_python_path)
        .write_stdin(input)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let lines = String::from_utf8(output).unwrap();
    let documents = lines
        .lines()
        .map(|line| serde_json::from_str::<Value>(line).unwrap())
        .collect::<Vec<_>>();
    assert_eq!(documents.len(), 2, "{lines}");
    assert_eq!(fs::read_to_string(&worker_start_log).unwrap(), "started\n");
    assert_eq!(documents[0]["docId"], "sha256:rapidocr-first");
    assert_eq!(documents[1]["docId"], "sha256:rapidocr-second");
    for document in documents {
        assert_eq!(
            document["parserRun"]["backend"],
            "rust-sidecar+model-worker"
        );
        assert_eq!(document["parserRun"]["workerBackend"], "rapidocr-worker");
        assert_eq!(document["parserRun"]["preset"], "ocr");
        assert_eq!(
            document["parserRun"]["modelRuntime"]["unloadPolicy"],
            "after-job-batch"
        );
        assert_eq!(document["body"]["units"][0]["kind"], "OCR_REGION");
        assert_eq!(
            document["body"]["units"][0]["text"],
            "RapidOCR batch evidence"
        );
    }
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
        json!(["xenova-table-transformer-structure-recognition:model-main-2026-06-30"])
    );
    assert_eq!(json["body"]["units"][0]["kind"], "TABLE_CELL");
    assert_eq!(
        json["body"]["units"][0]["text"],
        "Auto table model evidence"
    );
}

#[test]
fn parse_pdf_auto_preset_table_heavy_without_worker_records_blocked_model_route() {
    let pdf = write_pdf_fixture("Item Qty Price\nA 2 10\nB 4 20\nTotal 6 30");
    let (cache_dir, manifest) = ready_mnn_model_manifest("doctruth-runtime-auto-table-blocked");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
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
    assert_eq!(json["parserRun"]["modelRouting"]["route"], "table-model");
    assert_eq!(
        json["parserRun"]["modelRouting"]["requiresModelRuntime"],
        true
    );
    assert_eq!(
        json["parserRun"]["modelRouting"]["startedModelRuntime"],
        false
    );
    assert_eq!(
        json["parserRun"]["modelRouting"]["candidateRoutedPages"],
        json!([1])
    );
    assert_eq!(json["parserRun"]["modelRouting"]["routedPages"], json!([]));
    assert_eq!(
        json["parserRun"]["modelRouting"]["blockedReason"],
        "model-runtime-unavailable"
    );
    assert_eq!(json["auditGradeStatus"], "NOT_AUDIT_GRADE");
}

#[test]
fn parse_pdf_auto_preset_scanned_pdf_routes_to_ocr_mnn_worker() {
    let pdf = write_empty_text_layer_pdf();
    let worker = write_auto_ocr_model_worker();
    let (cache_dir, manifest) =
        ready_mnn_ocr_model_pack_manifest("doctruth-runtime-auto-ocr-cache");
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
        json!(["ppocr-v5-mobile-det:v0.1.3", "ppocr-v5-mobile-rec:v0.1.3"])
    );
    assert_eq!(json["body"]["units"][0]["kind"], "OCR_REGION");
    assert_eq!(json["body"]["units"][0]["text"], "Auto OCR evidence");
}

#[test]
fn rapidocr_mnn_worker_accepts_jsonl_batch_until_stdin_closes() {
    let fake_python_path = write_fake_rapidocr_pythonpath();
    let first_image = write_fake_png("doctruth-runtime-rapidocr-jsonl-first");
    let second_image = write_fake_png("doctruth-runtime-rapidocr-jsonl-second");
    let worker = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .join("scripts/doctruth-rapidocr-mnn-worker");
    let input = format!(
        "{}\n{}\n",
        json!({
            "command": "parse_pdf",
            "source_path": first_image,
            "source_hash": "sha256:first",
            "preset": "ocr",
            "models": [{"name": "ocr-router", "version": "v1"}]
        }),
        json!({
            "command": "parse_pdf",
            "source_path": second_image,
            "source_hash": "sha256:second",
            "preset": "ocr",
            "models": [{"name": "ocr-router", "version": "v1"}]
        })
    );
    let mut cmd = Command::new(worker);

    let output = cmd
        .env("DOCTRUTH_ALLOW_PYTHON_ORACLE", "1")
        .env("PYTHONPATH", fake_python_path)
        .write_stdin(input)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let lines = String::from_utf8(output).unwrap();
    let responses = lines
        .lines()
        .map(|line| serde_json::from_str::<Value>(line).unwrap())
        .collect::<Vec<_>>();

    assert_eq!(responses.len(), 2, "{lines}");
    assert_eq!(responses[0]["ok"], true);
    assert_eq!(responses[1]["ok"], true);
    assert_eq!(responses[0]["document"]["docId"], "sha256:first");
    assert_eq!(responses[1]["document"]["docId"], "sha256:second");
    assert_eq!(
        responses[0]["document"]["body"]["units"][0]["kind"],
        "OCR_REGION"
    );
    assert_eq!(
        responses[1]["document"]["body"]["units"][0]["text"],
        "RapidOCR batch evidence"
    );
}

#[test]
fn parse_pdf_auto_routes_sparse_visual_infographic_to_ocr_mnn_worker() {
    let pdf = opendataloader_worker_fixture("01030000000141.pdf");
    let worker = write_auto_ocr_model_worker();
    let (cache_dir, manifest) =
        ready_mnn_ocr_model_pack_manifest("doctruth-runtime-auto-infographic-ocr-cache");
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
    assert_eq!(json["parserRun"]["modelRouting"]["route"], "ocr-model");
    assert_eq!(
        json["parserRun"]["modelRouting"]["candidateRoutedPages"],
        json!([1])
    );
    assert_eq!(json["body"]["units"][0]["kind"], "OCR_REGION");
}

#[test]
fn parse_pdf_auto_keeps_readable_toc_page_deterministic() {
    let pdf = opendataloader_worker_fixture("01030000000198.pdf");
    let worker = write_failing_model_worker();
    let (cache_dir, manifest) = ready_mnn_model_manifest("doctruth-runtime-auto-toc-cache");
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
    assert_eq!(json["parserRun"]["preset"], "auto");
    assert_eq!(
        json["parserRun"]["modelRouting"]["route"],
        "deterministic-only"
    );
    assert_eq!(
        json["parserRun"]["modelRouting"]["startedModelRuntime"],
        false
    );
    assert!(
        json["contentBlocks"]
            .as_array()
            .unwrap()
            .iter()
            .any(|block| block["text"] == "1. Overview of OCR Pack"),
        "{json}"
    );
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

#[cfg(not(feature = "mnn-preprocess"))]
#[test]
fn rust_mnn_model_worker_preprocess_probe_fails_without_feature() {
    let pdf = write_pdf_fixture("Preprocess feature disabled.");
    let mut cmd = Command::cargo_bin("doctruth-mnn-model-worker").unwrap();

    cmd.arg("--preprocess-page")
        .arg(&pdf)
        .arg("--decoder")
        .arg("table")
        .assert()
        .failure()
        .stderr(predicate::str::contains("mnn_preprocess_feature_disabled"));
}

#[cfg(feature = "mnn-preprocess")]
#[test]
fn rust_mnn_model_worker_preprocess_probe_emits_stable_rgb_nchw_tensor_digest() {
    let pdf = write_pdf_fixture("Preprocess feature enabled.");
    let mut first = Command::cargo_bin("doctruth-mnn-model-worker").unwrap();
    let first_output = first
        .arg("--preprocess-page")
        .arg(&pdf)
        .arg("--decoder")
        .arg("table")
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let mut second = Command::cargo_bin("doctruth-mnn-model-worker").unwrap();
    let second_output = second
        .arg("--preprocess-page")
        .arg(&pdf)
        .arg("--decoder")
        .arg("table")
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let first_json: Value = serde_json::from_slice(&first_output).unwrap();
    let second_json: Value = serde_json::from_slice(&second_output).unwrap();

    assert_eq!(first_json["ok"], true);
    assert_eq!(first_json["preprocessing"]["decoder"], "table");
    assert_eq!(first_json["preprocessing"]["channelOrder"], "RGB");
    assert_eq!(first_json["preprocessing"]["tensorLayout"], "NCHW");
    assert_eq!(first_json["tensor"]["shape"][0], 1);
    assert_eq!(first_json["tensor"]["shape"][1], 3);
    assert!(first_json["tensor"]["shape"][2].as_u64().unwrap() > 0);
    assert!(first_json["tensor"]["shape"][3].as_u64().unwrap() > 0);
    assert_eq!(
        first_json["tensor"]["sha256"],
        second_json["tensor"]["sha256"]
    );
    assert_eq!(
        first_json["tensor"]["firstValues"],
        second_json["tensor"]["firstValues"]
    );
    assert!(
        first_json["tensor"]["sha256"]
            .as_str()
            .is_some_and(|digest| digest.starts_with("sha256:") && digest.len() == 71)
    );
}

#[cfg(feature = "mnn-ocr")]
#[test]
fn rust_mnn_model_worker_doctor_reports_ocr_rs_decoder_when_feature_enabled() {
    let mut cmd = Command::cargo_bin("doctruth-mnn-model-worker").unwrap();

    let output = cmd
        .arg("--doctor")
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(json["decoders"]["ocr"]["compiled"], true);
    assert_eq!(json["decoders"]["ocr"]["backend"], "ocr-rs");
    assert_eq!(json["decoders"]["ocr"]["modelFormat"], "mnn");
}

#[cfg(feature = "mnn-ocr")]
#[test]
fn rust_mnn_model_worker_attempts_real_ocr_engine_when_feature_enabled() {
    let cache_dir = temp_dir("doctruth-runtime-worker-real-ocr-invalid-pack");
    fs::create_dir_all(&cache_dir).unwrap();
    let det_path = cache_dir.join("ppocr-v5-mobile-det-v0.1.3.bin");
    let rec_path = cache_dir.join("ppocr-v5-mobile-rec-v0.1.3.bin");
    let keys_path = cache_dir.join("ppocr-keys-v5-v0.1.3.bin");
    fs::write(&det_path, b"invalid det mnn").unwrap();
    fs::write(&rec_path, b"invalid rec mnn").unwrap();
    fs::write(&keys_path, b"a\nb\nc\n").unwrap();
    let pdf = write_empty_text_layer_pdf();
    let mut cmd = Command::cargo_bin("doctruth-mnn-model-worker").unwrap();

    cmd.write_stdin(
        json!({
            "command": "parse_pdf",
            "source_path": pdf,
            "source_hash": "sha256:model-worker",
            "preset": "ocr",
            "models": [
                {
                    "name": "ppocr-v5-mobile-det",
                    "version": "v0.1.3",
                    "role": "text-detection",
                    "task": "ocr",
                    "backend": "mnn",
                    "format": "mnn",
                    "cacheStatus": "READY",
                    "cachePath": det_path
                },
                {
                    "name": "ppocr-v5-mobile-rec",
                    "version": "v0.1.3",
                    "role": "text-recognition",
                    "task": "ocr",
                    "backend": "mnn",
                    "format": "mnn",
                    "cacheStatus": "READY",
                    "cachePath": rec_path
                }
            ],
            "auxiliaryArtifacts": [
                {
                    "name": "ppocr-keys-v5",
                    "version": "v0.1.3",
                    "role": "recognition-charset",
                    "cacheStatus": "READY",
                    "cachePath": keys_path
                }
            ]
        })
        .to_string(),
    )
    .assert()
    .failure()
    .stderr(predicate::str::contains("ocr_mnn_load_failed"));
}

#[cfg(feature = "mnn-native")]
#[test]
fn rust_mnn_model_worker_attempts_real_table_engine_when_native_feature_enabled() {
    let model_path = temp_path("doctruth-runtime-worker-real-table-invalid-pack", "mnn");
    fs::write(&model_path, b"invalid table mnn").unwrap();
    let pdf = write_pdf_fixture("Item Qty Price\nA 2 10\nB 4 20\nTotal 6 30");
    let mut cmd = Command::cargo_bin("doctruth-mnn-model-worker").unwrap();

    cmd.write_stdin(
        json!({
            "command": "parse_pdf",
            "source_path": pdf,
            "source_hash": "sha256:model-worker",
            "preset": "table-lite",
            "models": [{
                "name": "xenova-table-transformer-structure-recognition",
                "version": "model_quantized-main-2026-06-19",
                "role": "table-structure-decoder",
                "task": "table-structure-recognition",
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
    .stderr(predicate::str::contains("table_mnn_load_failed"));
}

#[test]
fn rust_mnn_model_worker_probe_fails_without_native_feature() {
    let model_path = temp_path("doctruth-runtime-worker-probe", "mnn");
    fs::write(&model_path, b"mnn").unwrap();
    let mut cmd = Command::cargo_bin("doctruth-mnn-model-worker").unwrap();

    cmd.arg("--probe-model")
        .arg(&model_path)
        .assert()
        .failure()
        .stderr(predicate::str::contains("mnn_native_feature_disabled"));
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
    assert_eq!(json["metrics"]["preprocessing"]["decoder"], "table");
    assert_eq!(json["metrics"]["preprocessing"]["channelOrder"], "RGB");
    assert_eq!(json["metrics"]["preprocessing"]["tensorLayout"], "NCHW");
    assert_eq!(json["metrics"]["preprocessing"]["parity"]["required"], true);
    assert_eq!(
        json["document"]["parserRun"]["workerBackend"],
        "mnn-model-worker-stub"
    );
    assert_eq!(json["document"]["auditGradeStatus"], "NOT_AUDIT_GRADE");
}

#[test]
fn rust_mnn_model_worker_stub_mode_accepts_jsonl_batch_until_stdin_closes() {
    let first_model = temp_path("doctruth-runtime-worker-mnn-jsonl-first", "mnn");
    let second_model = temp_path("doctruth-runtime-worker-mnn-jsonl-second", "mnn");
    fs::write(&first_model, b"first mnn").unwrap();
    fs::write(&second_model, b"second mnn").unwrap();
    let request = |path: &Path| {
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
                "cachePath": path
            }]
        })
        .to_string()
    };
    let mut cmd = Command::cargo_bin("doctruth-mnn-model-worker").unwrap();

    let output = cmd
        .env("DOCTRUTH_MNN_WORKER_STUB", "1")
        .write_stdin(format!(
            "{}\n{}\n",
            request(&first_model),
            request(&second_model)
        ))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let lines = String::from_utf8(output).unwrap();
    let responses = lines
        .lines()
        .map(|line| serde_json::from_str::<Value>(line).unwrap())
        .collect::<Vec<_>>();
    assert_eq!(responses.len(), 2, "{lines}");
    assert!(responses.iter().all(|response| response["ok"] == true));
    assert!(
        responses
            .iter()
            .all(|response| response["metrics"]["runtime"] == "mnn")
    );
}

#[test]
fn rust_mnn_model_worker_stub_mode_reports_complete_ocr_pack_readiness() {
    let cache_dir = temp_dir("doctruth-runtime-worker-ocr-pack");
    fs::create_dir_all(&cache_dir).unwrap();
    let det_path = cache_dir.join("ppocr-v5-mobile-det-v0.1.3.bin");
    let rec_path = cache_dir.join("ppocr-v5-mobile-rec-v0.1.3.bin");
    let keys_path = cache_dir.join("ppocr-keys-v5-v0.1.3.bin");
    fs::write(&det_path, b"det").unwrap();
    fs::write(&rec_path, b"rec").unwrap();
    fs::write(&keys_path, b"abc\n").unwrap();
    let mut cmd = Command::cargo_bin("doctruth-mnn-model-worker").unwrap();

    let output = cmd
        .env("DOCTRUTH_MNN_WORKER_STUB", "1")
        .write_stdin(
            json!({
                "command": "parse_pdf",
                "source_path": "document.pdf",
                "source_hash": "sha256:model-worker",
                "preset": "ocr",
                "models": [
                    {
                        "name": "ppocr-v5-mobile-det",
                        "version": "v0.1.3",
                        "role": "text-detection",
                        "task": "ocr",
                        "backend": "mnn",
                        "format": "mnn",
                        "cacheStatus": "READY",
                        "cachePath": det_path
                    },
                    {
                        "name": "ppocr-v5-mobile-rec",
                        "version": "v0.1.3",
                        "role": "text-recognition",
                        "task": "ocr",
                        "backend": "mnn",
                        "format": "mnn",
                        "cacheStatus": "READY",
                        "cachePath": rec_path
                    }
                ],
                "auxiliaryArtifacts": [
                    {
                        "name": "ppocr-keys-v5",
                        "version": "v0.1.3",
                        "role": "recognition-charset",
                        "cacheStatus": "READY",
                        "cachePath": keys_path
                    }
                ]
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(json["metrics"]["decoder"], "ocr");
    assert_eq!(json["metrics"]["preprocessing"]["decoder"], "ocr");
    assert_eq!(json["metrics"]["preprocessing"]["channelOrder"], "RGB");
    assert_eq!(json["metrics"]["preprocessing"]["tensorLayout"], "NCHW");
    assert_eq!(
        json["metrics"]["loadedModels"],
        json!(["ppocr-v5-mobile-det:v0.1.3", "ppocr-v5-mobile-rec:v0.1.3"])
    );
    assert_eq!(
        json["metrics"]["auxiliaryArtifacts"],
        json!(["ppocr-keys-v5:v0.1.3"])
    );
    assert_eq!(
        json["document"]["parserRun"]["models"],
        json!(["ppocr-v5-mobile-det:v0.1.3", "ppocr-v5-mobile-rec:v0.1.3"])
    );
    assert_eq!(json["document"]["body"]["units"][0]["kind"], "OCR_REGION");
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
    assert_eq!(
        json["parserRun"]["modelRuntime"]["preprocessing"]["decoder"],
        "table"
    );
    assert_eq!(
        json["parserRun"]["modelRuntime"]["preprocessing"]["imageSource"],
        "pdf_oxide_rendered_page"
    );
    assert_eq!(
        json["parserRun"]["modelRuntime"]["preprocessing"]["parity"]["required"],
        true
    );
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
        ready_mnn_ocr_model_pack_manifest("doctruth-runtime-auto-ocr-path-cache");
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
fn parse_pdf_auto_table_route_discovers_packaged_rust_mnn_worker() {
    let pdf = write_pdf_fixture("Item Qty Price\nA 2 10\nB 4 20\nTotal 6 30");
    let bin_dir = temp_dir("doctruth-runtime-packaged-table-bin");
    fs::create_dir_all(&bin_dir).unwrap();
    let source_worker = assert_cmd::cargo::cargo_bin("doctruth-mnn-model-worker");
    let worker = bin_dir.join("doctruth-mnn-model-worker");
    fs::copy(&source_worker, &worker).unwrap();
    make_executable(&worker);
    let (cache_dir, manifest) = ready_mnn_model_manifest("doctruth-runtime-auto-table-path-cache");
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
    assert_eq!(json["parserRun"]["preset"], "table-lite");
    assert_eq!(json["parserRun"]["modelRouting"]["route"], "table-model");
    assert_eq!(json["body"]["units"][0]["kind"], "TABLE_CELL");
    assert_eq!(json["body"]["units"][0]["text"], "Auto table MNN evidence");
}

#[test]
fn parse_pdf_auto_routes_opendataloader_image_backed_table_case_to_mnn_worker() {
    let pdf = opendataloader_worker_fixture("01030000000110.pdf");
    let bin_dir = temp_dir("doctruth-runtime-packaged-odl-table-bin");
    fs::create_dir_all(&bin_dir).unwrap();
    let source_worker = assert_cmd::cargo::cargo_bin("doctruth-mnn-model-worker");
    let worker = bin_dir.join("doctruth-mnn-model-worker");
    fs::copy(&source_worker, &worker).unwrap();
    make_executable(&worker);
    let (cache_dir, manifest) = ready_mnn_model_manifest("doctruth-runtime-odl-table-cache");
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
    assert_eq!(json["parserRun"]["preset"], "table-lite");
    assert_eq!(json["parserRun"]["modelRouting"]["route"], "table-model");
    assert_eq!(
        json["parserRun"]["modelRouting"]["candidateRoutedPages"],
        json!([1])
    );
    assert_eq!(json["body"]["units"][0]["kind"], "TABLE_CELL");
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
    assert_eq!(
        json["parserRun"]["modelRuntime"]["preprocessing"]["decoder"],
        "table"
    );
    assert_eq!(
        json["parserRun"]["modelRuntime"]["preprocessing"]["channelOrder"],
        "RGB"
    );
    assert_eq!(
        json["parserRun"]["modelRuntime"]["preprocessing"]["tensorLayout"],
        "NCHW"
    );
    assert_eq!(
        json["parserRun"]["modelRuntime"]["preprocessing"]["parity"]["promotionBlockedWithoutTensorDigest"],
        true
    );
}

#[test]
fn parse_pdf_sends_ocr_model_pack_auxiliary_artifacts_to_worker() {
    let pdf = write_empty_text_layer_pdf();
    let worker = write_ocr_pack_asserting_model_worker();
    let (cache_dir, manifest) =
        ready_mnn_ocr_model_pack_manifest("doctruth-runtime-ocr-pack-cache");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .env("DOCTRUTH_RUNTIME_MODEL_COMMAND", &worker)
        .env("DOCTRUTH_MODEL_CACHE", &cache_dir)
        .env("DOCTRUTH_MODEL_MANIFEST", &manifest)
        .write_stdin(parse_request(&pdf, "ocr"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(json["parserRun"]["backend"], "rust-sidecar+model-worker");
    assert_eq!(
        json["parserRun"]["models"],
        json!(["ppocr-v5-mobile-det:v0.1.3", "ppocr-v5-mobile-rec:v0.1.3"])
    );
    assert_eq!(json["body"]["units"][0]["kind"], "OCR_REGION");
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
fn parse_pdf_benchmark_oracle_routes_ready_onnx_reference_manifest_to_worker() {
    let pdf = write_pdf_fixture("Benchmark oracle should use reference model.");
    let worker = write_onnx_reference_model_worker();
    let cache_dir = temp_dir("doctruth-runtime-onnx-reference-cache");
    fs::create_dir_all(&cache_dir).unwrap();
    let artifact = b"real onnx reference artifact";
    let artifact_sha = sha256(artifact);
    let artifact_path = cache_dir.join("table-reference.onnx");
    fs::write(&artifact_path, artifact).unwrap();
    let manifest = temp_path("doctruth-runtime-onnx-reference-manifest", "json");
    fs::write(
        &manifest,
        json!({
            "presets": {
                "table-lite": [
                    {
                        "name": "xenova-table-transformer-structure-recognition",
                        "version": "model_quantized-main-2026-06-19",
                        "cacheFilename": "table-reference.onnx",
                        "sha256": artifact_sha,
                        "sizeBytes": artifact.len(),
                        "required": true,
                        "task": "table-structure-recognition",
                        "backend": "onnxruntime",
                        "format": "onnx",
                        "precision": "quantized",
                        "license": "Apache-2.0",
                        "preprocessing": {
                            "inputLayout": "NCHW",
                            "dtype": "float32",
                            "colorSpace": "sRGB",
                            "channelOrder": "RGB",
                            "resize": {"width": 800, "height": 800, "keepAspectRatio": false},
                            "resample": "bilinear",
                            "scale": 0.00392156862745098,
                            "mean": [0.485, 0.456, 0.406],
                            "std": [0.229, 0.224, 0.225]
                        },
                        "parity": {
                            "referenceEngine": "python-onnxruntime",
                            "candidateEngine": "rust-mnn",
                            "tensorDumpRequired": true,
                            "firstTensorValuesRequired": true,
                            "maxAbsDiff": 0.000001
                        }
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
        .write_stdin(parse_request_with_runtime_profile(
            &pdf,
            "table-lite",
            "benchmark-oracle",
        ))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(json["parserRun"]["backend"], "rust-sidecar+model-worker");
    assert_eq!(json["parserRun"]["profile"], "benchmark-oracle");
    assert_eq!(
        json["parserRun"]["modelRouting"]["decision"],
        "model-runtime"
    );
    assert_eq!(json["parserRun"]["modelRouting"]["route"], "model-runtime");
    assert_eq!(json["parserRun"]["modelRuntime"]["runtime"], "onnxruntime");
    assert_eq!(json["parserRun"]["modelRuntime"]["referenceOnly"], true);
    assert_eq!(
        json["parserRun"]["modelRuntime"]["preprocessing"]["resize"]["width"],
        800
    );
    assert_eq!(
        json["parserRun"]["modelRuntime"]["preprocessing"]["mean"][0],
        0.485
    );
    assert_eq!(
        json["parserRun"]["modelRuntime"]["preprocessing"]["parity"]["referenceEngine"],
        "python-onnxruntime"
    );
    assert_eq!(
        json["body"]["units"][0]["text"],
        "ONNX reference worker evidence"
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
    assert_eq!(json["parserRun"]["modelRuntime"]["decoder"], "table");
    assert_eq!(
        json["parserRun"]["modelRuntime"]["inputSource"],
        "synthetic_tensor"
    );
    assert_eq!(json["parserRun"]["modelRuntime"]["coldStartMs"], 12.5);
    assert_eq!(json["parserRun"]["modelRuntime"]["renderMs"], 4.5);
    assert_eq!(json["parserRun"]["modelRuntime"]["inferenceMs"], 3.25);
    assert_eq!(json["parserRun"]["modelRuntime"]["totalMs"], 20.25);
    assert_eq!(json["parserRun"]["modelRuntime"]["rssMb"], 188);
    assert_eq!(json["parserRun"]["modelRuntime"]["peakMemoryMb"], 221);
    assert_eq!(json["parserRun"]["modelRuntime"]["ocrRegions"], 0);
    assert_eq!(
        json["parserRun"]["modelRuntime"]["loadedModels"],
        json!(["xenova-table-transformer-structure-recognition:model-main-2026-06-30"])
    );
    assert_eq!(
        json["parserRun"]["modelRuntime"]["auxiliaryArtifacts"],
        json!(["table-charset:v1"])
    );
    assert!(
        json["parserRun"]["modelRuntime"]["manifestPath"]
            .as_str()
            .is_some_and(|path| path.ends_with(".json")),
        "{}",
        json["parserRun"]["modelRuntime"]
    );
    assert_eq!(
        json["parserRun"]["modelRuntime"]["modelArtifacts"][0]["name"],
        "xenova-table-transformer-structure-recognition"
    );
    assert_eq!(
        json["parserRun"]["modelRuntime"]["modelArtifacts"][0]["backend"],
        "mnn"
    );
    assert!(
        json["parserRun"]["modelRuntime"]["modelArtifacts"][0]["actualSha256"]
            .as_str()
            .is_some_and(|sha| sha.starts_with("sha256:")),
        "{}",
        json["parserRun"]["modelRuntime"]
    );
    assert!(
        json["parserRun"]["modelRuntime"]["auxiliaryArtifactDetails"].is_array(),
        "{}",
        json["parserRun"]["modelRuntime"]
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
    let artifact_path =
        cache_dir.join("xenova-table-transformer-structure-recognition-model-main-2026-06-30.mnn");
    fs::write(&artifact_path, artifact).unwrap();
    let manifest = temp_path(&format!("{prefix}-manifest"), "json");
    fs::write(
        &manifest,
        json!({
            "presets": {
                "table-lite": [
                    {
                        "name": "xenova-table-transformer-structure-recognition",
                        "version": "model-main-2026-06-30",
                        "sha256": artifact_sha,
                        "sizeBytes": artifact.len(),
                        "cacheFilename": "xenova-table-transformer-structure-recognition-model-main-2026-06-30.mnn",
                        "required": true,
                        "task": "table-structure-recognition",
                        "role": "table-structure-decoder",
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

fn ready_mnn_ocr_model_pack_manifest(prefix: &str) -> (PathBuf, PathBuf) {
    let cache_dir = temp_dir(prefix);
    fs::create_dir_all(&cache_dir).unwrap();
    let det = b"ready mnn ppocr det";
    let rec = b"ready mnn ppocr rec";
    let keys = b"abc\n";
    fs::write(cache_dir.join("ppocr-v5-mobile-det-v0.1.3.bin"), det).unwrap();
    fs::write(cache_dir.join("ppocr-v5-mobile-rec-v0.1.3.bin"), rec).unwrap();
    fs::write(cache_dir.join("ppocr-keys-v5-v0.1.3.bin"), keys).unwrap();
    let manifest = temp_path(&format!("{prefix}-manifest"), "json");
    fs::write(
        &manifest,
        json!({
            "presets": {
                "ocr": [
                    {
                        "name": "ppocr-v5-mobile-det",
                        "version": "v0.1.3",
                        "sha256": sha256(det),
                        "sizeBytes": det.len(),
                        "required": true,
                        "task": "ocr",
                        "role": "text-detection",
                        "backend": "mnn",
                        "format": "mnn",
                        "precision": "fp32",
                        "license": "test"
                    },
                    {
                        "name": "ppocr-v5-mobile-rec",
                        "version": "v0.1.3",
                        "sha256": sha256(rec),
                        "sizeBytes": rec.len(),
                        "required": true,
                        "task": "ocr",
                        "role": "text-recognition",
                        "backend": "mnn",
                        "format": "mnn",
                        "precision": "fp32",
                        "license": "test"
                    }
                ]
            },
            "auxiliary": [
                {
                    "name": "ppocr-keys-v5",
                    "version": "v0.1.3",
                    "sha256": sha256(keys),
                    "sizeBytes": keys.len(),
                    "role": "recognition-charset",
                    "license": "test"
                }
            ]
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

fn write_jsonl_persistent_model_worker() -> PathBuf {
    write_worker_script(
        "doctruth-runtime-jsonl-persistent-model-worker",
        r#"#!/usr/bin/env python3
import json
import os
import sys

start_log = os.environ["DOCTRUTH_TEST_WORKER_START_LOG"]
with open(start_log, "a", encoding="utf-8") as handle:
    handle.write("started\n")

for line in sys.stdin:
    if not line.strip():
        continue
    request = json.loads(line)
    assert request["preset"] == "table-lite"
    assert request["modelRuntime"]["runtime"] == "mnn"
    assert request["modelRuntime"]["loadPolicy"] == "lazy"
    assert request["modelRuntime"]["unloadPolicy"] == "after-job-batch"
    print(json.dumps({
        "docId": request["source_hash"],
        "source": {
            "sourceFilename": request["sourceFilename"],
            "sourceHash": request["source_hash"],
            "metadata": {"sourceFilename": request["sourceFilename"], "pageCount": 1}
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
                "text": "Warm worker model evidence",
                "evidenceSpanIds": ["span-0001"],
                "location": {
                    "page": 1,
                    "readingOrder": 1,
                    "boundingBox": {"x0": 0.0, "y0": 0.0, "x1": 1000.0, "y1": 1000.0}
                },
                "sourceObjectId": "warm-worker-cell-1",
                "confidence": {"score": 0.93, "rationale": "fake persistent model worker"},
                "warnings": []
            }],
            "tables": []
        },
        "parserRun": {
            "parserVersion": "test-worker",
            "preset": request["preset"],
            "backend": "rust-sidecar+model-worker",
            "models": ["xenova-table-transformer-structure-recognition:model-main-2026-06-30"],
            "warnings": []
        },
        "auditGradeStatus": "AUDIT_GRADE",
        "metrics": {
            "runtime": "mnn",
            "coldStartMs": 9.0,
            "inferenceMs": 2.0,
            "loadedModels": ["xenova-table-transformer-structure-recognition:model-main-2026-06-30"],
            "unload": {"status": "deferred", "policy": "after-job-batch"}
        }
    }), flush=True)
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
            "models": ["xenova-table-transformer-structure-recognition:model-main-2026-06-30"],
            "warnings": []
        },
        "auditGradeStatus": "AUDIT_GRADE"
    },
    "metrics": {
        "decoder": "table",
        "inputSource": "synthetic_tensor",
        "runtime": "mnn",
        "coldStartMs": 12.5,
        "renderMs": 4.5,
        "inferenceMs": 3.25,
        "totalMs": 20.25,
        "rssMb": 188,
        "peakMemoryMb": 221,
        "ocrRegions": 0,
        "loadedModels": ["xenova-table-transformer-structure-recognition:model-main-2026-06-30"],
        "auxiliaryArtifacts": ["table-charset:v1"],
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
assert request["modelRuntime"]["preprocessing"]["decoder"] == "table"
assert request["modelRuntime"]["preprocessing"]["imageSource"] == "pdf_oxide_rendered_page"
assert request["modelRuntime"]["preprocessing"]["channelOrder"] == "RGB"
assert request["modelRuntime"]["preprocessing"]["tensorLayout"] == "NCHW"
assert request["modelRuntime"]["preprocessing"]["parity"]["required"] is True
assert request["modelRuntime"]["preprocessing"]["parity"]["promotionBlockedWithoutTensorDigest"] is True
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
assert request["modelRuntime"]["preprocessing"]["decoder"] == "table"
assert request["modelRuntime"]["preprocessing"]["imageSource"] == "pdf_oxide_rendered_page"
assert request["modelRuntime"]["preprocessing"]["tensorLayout"] == "NCHW"
assert request["modelRouting"]["models"] == ["xenova-table-transformer-structure-recognition:model-main-2026-06-30"]
assert request["requiredModels"][0]["name"] == request["models"][0]["name"]
assert request["requiredModels"][0]["version"] == request["models"][0]["version"]
assert request["requiredModels"][0]["identity"] == "xenova-table-transformer-structure-recognition:model-main-2026-06-30"
assert request["models"][0]["name"] == "xenova-table-transformer-structure-recognition"
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
        "models": ["xenova-table-transformer-structure-recognition:model-main-2026-06-30"],
        "warnings": []
    },
    "auditGradeStatus": "AUDIT_GRADE",
    "metrics": {
        "runtime": "mnn",
        "coldStartMs": 9.0,
        "inferenceMs": 2.0,
        "loadedModels": ["xenova-table-transformer-structure-recognition:model-main-2026-06-30"],
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

fn write_ocr_pack_asserting_model_worker() -> PathBuf {
    write_worker_script(
        "doctruth-runtime-ocr-pack-model-worker",
        r#"#!/usr/bin/env python3
import json
import pathlib
import sys

request = json.load(sys.stdin)
models = request["models"]
auxiliary = request["auxiliaryArtifacts"]
assert request["preset"] == "ocr"
assert request["modelRuntime"]["preprocessing"]["decoder"] == "ocr"
assert request["modelRuntime"]["preprocessing"]["channelOrder"] == "RGB"
assert request["modelRuntime"]["preprocessing"]["tensorLayout"] == "NCHW"
assert [model["role"] for model in models] == ["text-detection", "text-recognition"], models
assert all(model["backend"] == "mnn" and model["format"] == "mnn" for model in models), models
assert all(model["cacheStatus"] == "READY" for model in models), models
assert all(pathlib.Path(model["cachePath"]).is_file() for model in models), models
assert len(auxiliary) == 1, auxiliary
assert auxiliary[0]["role"] == "recognition-charset", auxiliary
assert auxiliary[0]["cacheStatus"] == "READY", auxiliary
assert pathlib.Path(auxiliary[0]["cachePath"]).is_file(), auxiliary
print(json.dumps({
    "docId": request["source_hash"],
    "source": {
        "sourceFilename": "ocr-pack-worker.pdf",
        "sourceHash": request["source_hash"],
        "metadata": {"sourceFilename": "ocr-pack-worker.pdf", "pageCount": 1}
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
            "text": "OCR pack evidence",
            "evidenceSpanIds": ["span-0001"],
            "location": {
                "page": 1,
                "readingOrder": 1,
                "boundingBox": {"x0": 20.0, "y0": 20.0, "x1": 200.0, "y1": 80.0}
            },
            "sourceObjectId": "ocr-pack-region-1",
            "confidence": {"score": 0.91, "rationale": "fake ocr pack worker"},
            "warnings": []
        }],
        "tables": []
    },
    "parserRun": {
        "parserVersion": "test-worker",
        "preset": request["preset"],
        "backend": "rust-sidecar+model-worker",
        "models": ["ppocr-v5-mobile-det:v0.1.3", "ppocr-v5-mobile-rec:v0.1.3"],
        "warnings": []
    },
    "auditGradeStatus": "AUDIT_GRADE"
}))
"#,
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
assert request["modelRuntime"]["preprocessing"]["decoder"] == "ocr"
assert request["modelRuntime"]["preprocessing"]["imageSource"] == "pdf_oxide_rendered_page"
assert request["modelRuntime"]["preprocessing"]["parity"]["promotionBlockedWithoutTensorDigest"] is True
models = request["models"]
auxiliary = request["auxiliaryArtifacts"]
assert [model["role"] for model in models] == ["text-detection", "text-recognition"], models
assert all(model["backend"] == "mnn" and model["format"] == "mnn" for model in models), models
assert len(auxiliary) == 1, auxiliary
assert auxiliary[0]["role"] == "recognition-charset", auxiliary
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
        "models": ["ppocr-v5-mobile-det:v0.1.3", "ppocr-v5-mobile-rec:v0.1.3"],
        "warnings": []
    },
    "auditGradeStatus": "AUDIT_GRADE",
    "metrics": {
        "runtime": "mnn",
        "coldStartMs": 10.0,
        "inferenceMs": 5.0,
        "loadedModels": ["ppocr-v5-mobile-det:v0.1.3", "ppocr-v5-mobile-rec:v0.1.3"],
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
assert request["requiredModels"][0]["name"] == "xenova-table-transformer-structure-recognition"
assert request["requiredModels"][0]["version"] == "model-main-2026-06-30"
assert request["requiredModels"][0]["identity"] == "xenova-table-transformer-structure-recognition:model-main-2026-06-30"
assert request["models"][0]["backend"] == "mnn"
assert request["models"][0]["format"] == "mnn"
assert request["models"][0]["cacheStatus"] == "READY"
assert request["modelRuntime"]["preprocessing"]["decoder"] == "table"
assert request["modelRuntime"]["preprocessing"]["channelOrder"] == "RGB"
assert request["modelRuntime"]["preprocessing"]["tensorLayout"] == "NCHW"
assert request["modelRuntime"]["preprocessing"]["parity"]["required"] is True
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
        "models": ["xenova-table-transformer-structure-recognition:model-main-2026-06-30"],
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

fn write_onnx_reference_model_worker() -> PathBuf {
    write_worker_script(
        "doctruth-runtime-onnx-reference-model-worker",
        r#"#!/usr/bin/env python3
import json
import pathlib
import sys

request = json.load(sys.stdin)
model = request["models"][0]
assert request["profile"] == "benchmark-oracle"
assert request["runtime_profile"] == "benchmark-oracle"
assert request["modelRuntime"]["runtime"] == "onnxruntime"
assert request["modelRuntime"]["referenceOnly"] is True
assert request["modelRuntime"]["preprocessing"]["resize"]["width"] == 800
assert request["modelRuntime"]["preprocessing"]["resize"]["height"] == 800
assert request["modelRuntime"]["preprocessing"]["mean"] == [0.485, 0.456, 0.406]
assert request["modelRuntime"]["preprocessing"]["std"] == [0.229, 0.224, 0.225]
assert request["modelRuntime"]["preprocessing"]["parity"]["referenceEngine"] == "python-onnxruntime"
assert model["name"] == "xenova-table-transformer-structure-recognition"
assert model["backend"] == "onnxruntime"
assert model["format"] == "onnx"
assert model["cacheStatus"] == "READY"
assert pathlib.Path(model["cachePath"]).name == "table-reference.onnx"
assert pathlib.Path(model["cachePath"]).is_file()
print(json.dumps({
    "docId": request["source_hash"],
    "source": {
        "sourceFilename": "onnx-reference-worker.pdf",
        "sourceHash": request["source_hash"],
        "metadata": {"sourceFilename": "onnx-reference-worker.pdf", "pageCount": 1}
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
            "unitId": "unit-onnx-0001",
            "kind": "TABLE_CELL",
            "page": 1,
            "text": "ONNX reference worker evidence",
            "evidenceSpanIds": ["span-onnx-0001"],
            "location": {
                "page": 1,
                "readingOrder": 1,
                "boundingBox": {"x0": 0.0, "y0": 0.0, "x1": 1000.0, "y1": 1000.0}
            },
            "sourceObjectId": "onnx-reference-cell-1",
            "confidence": {"score": 0.97, "rationale": "real onnx reference worker"},
            "warnings": []
        }],
        "tables": []
    },
    "parserRun": {
        "parserVersion": "test-onnx-reference-worker",
        "preset": request["preset"],
        "backend": "rust-sidecar+model-worker",
        "profile": request["profile"],
        "models": ["xenova-table-transformer-structure-recognition:model_quantized-main-2026-06-19"],
        "warnings": []
    },
    "auditGradeStatus": "AUDIT_GRADE",
    "metrics": {
        "runtime": "onnxruntime",
        "loadedModels": ["xenova-table-transformer-structure-recognition:model_quantized-main-2026-06-19"]
    }
}))
"#,
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

fn write_fake_png(prefix: &str) -> PathBuf {
    let path = temp_path(prefix, "png");
    fs::write(&path, b"fake png").unwrap();
    path
}

fn write_fake_rapidocr_pythonpath() -> PathBuf {
    let python_path = temp_dir("doctruth-runtime-fake-rapidocr-pythonpath");
    fs::create_dir_all(&python_path).unwrap();
    fs::write(
        python_path.join("rapidocr.py"),
        r#"
class Result:
    boxes = [[[10, 20], [120, 20], [120, 48], [10, 48]]]
    txts = ["RapidOCR batch evidence"]
    scores = [0.94]

class RapidOCR:
    def __call__(self, image_path):
        return Result()
"#,
    )
    .unwrap();
    python_path
}

fn write_rapidocr_worker_wrapper(start_log: &Path) -> PathBuf {
    let worker = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .join("scripts/doctruth-rapidocr-mnn-worker");
    write_worker_script(
        "doctruth-runtime-rapidocr-worker-wrapper",
        &format!(
            r#"#!/usr/bin/env sh
set -eu
printf 'started\n' >> '{}'
exec '{}'
"#,
            start_log.display(),
            worker.display()
        ),
    )
}

fn opendataloader_worker_fixture(name: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../third_party/opendataloader-bench/pdfs")
        .join(name)
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
