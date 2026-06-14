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
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .env("DOCTRUTH_RUNTIME_MODEL_COMMAND", &worker)
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
fn parse_pdf_reports_configured_worker_bad_json_as_stable_error() {
    let pdf = write_pdf_fixture("Fallback text should not be used.");
    let worker = write_bad_model_worker();
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    cmd.env("DOCTRUTH_RUNTIME_MODEL_COMMAND", &worker)
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
    assert_eq!(json["parserRun"]["backend"], "rust-sidecar+model-worker");
    assert_eq!(
        json["body"]["units"][0]["text"],
        "Worker cache metadata evidence"
    );
}

#[test]
fn parse_pdf_accepts_worker_envelope_with_document_payload() {
    let pdf = write_pdf_fixture("Fallback text should not be used.");
    let worker = write_enveloped_model_worker();
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .env("DOCTRUTH_RUNTIME_MODEL_COMMAND", &worker)
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
    "metrics": {"inputSource": "synthetic_tensor"}
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
assert cache.exists()
assert model["name"] == "slanet-plus"
assert model["version"] == "v1"
assert model["cacheStatus"] == "READY"
assert pathlib.Path(model["cachePath"]).parent == cache
assert model["actualSha256"] == model["sha256"]
assert model["actualSizeBytes"] > 0
assert model["task"] == "table-structure-recognition"
assert model["backend"] == "onnxruntime"
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

fn write_fake_model_worker() -> PathBuf {
    write_worker_script(
        "doctruth-runtime-model-worker",
        r#"#!/usr/bin/env python3
import json
import sys

request = json.load(sys.stdin)
assert request["preset"] == "table-lite"
assert request["requiredModels"][0]["name"] == "slanet-plus"
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
    let mut permissions = fs::metadata(&path).unwrap().permissions();
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        permissions.set_mode(0o755);
        fs::set_permissions(&path, permissions).unwrap();
    }
    path
}

fn write_pdf_fixture(text: &str) -> PathBuf {
    let path = temp_path("doctruth-runtime-worker-fixture", "pdf");
    fs::write(&path, minimal_pdf(text)).unwrap();
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
