use serde_json::{Value, json};
use std::io::{self, Read};
use std::path::Path;
use std::time::Instant;

const PROTOCOL_VERSION: &str = "1";

fn main() {
    if std::env::args().any(|arg| arg == "--doctor") {
        print_json(doctor_json());
        return;
    }
    let started = Instant::now();
    let mut input = String::new();
    if let Err(error) = io::stdin().read_to_string(&mut input) {
        fail(
            "worker_protocol_error",
            &format!("failed to read stdin: {error}"),
        );
    }
    let request: Value = match serde_json::from_str(&input) {
        Ok(value) => value,
        Err(error) => fail(
            "worker_protocol_error",
            &format!("invalid request JSON: {error}"),
        ),
    };
    if request.get("command").and_then(Value::as_str) != Some("parse_pdf") {
        fail("worker_protocol_error", "unsupported worker command");
    }
    let model = ready_mnn_model(&request);
    if !stub_mode_enabled() {
        fail(
            "mnn_inference_unavailable",
            "Rust MNN worker protocol is ready, but real MNN inference is not wired yet",
        );
    }
    let document = trust_document(&request, &model);
    print_json(json!({
        "ok": true,
        "document": document,
        "metrics": {
            "runtime": "mnn",
            "inputSource": "rust_mnn_worker_stub",
            "stubMode": true,
            "coldStartMs": 0.0,
            "inferenceMs": elapsed_ms(started),
            "loadedModels": [model_identity(&model)],
            "unload": {
                "status": "completed",
                "policy": "idle-after-request"
            }
        }
    }));
}

fn doctor_json() -> Value {
    json!({
        "ok": true,
        "runtime": "mnn",
        "engine": "mnn",
        "code": "protocol_ready",
        "message": "Rust MNN model worker protocol ready; real inference backend not wired",
        "protocol_version": PROTOCOL_VERSION,
        "protocolReady": true,
        "inferenceReady": false,
        "stubMode": stub_mode_enabled(),
        "productionPythonResidency": false
    })
}

fn ready_mnn_model(request: &Value) -> Value {
    let Some(models) = request.get("models").and_then(Value::as_array) else {
        fail("model_unavailable", "request has no models");
    };
    for model in models {
        let backend = model.get("backend").and_then(Value::as_str);
        let format = model.get("format").and_then(Value::as_str);
        if backend != Some("mnn") || format != Some("mnn") {
            continue;
        }
        if model.get("cacheStatus").and_then(Value::as_str) != Some("READY") {
            fail("model_unavailable", "MNN model cache is not READY");
        }
        let Some(path) = model.get("cachePath").and_then(Value::as_str) else {
            fail("model_unavailable", "MNN model cachePath missing");
        };
        if !Path::new(path).is_file() {
            fail("model_unavailable", "MNN model cachePath does not exist");
        }
        return model.clone();
    }
    fail(
        "unsupported_model_runtime",
        "Rust worker accepts READY MNN artifacts only",
    )
}

fn trust_document(request: &Value, model: &Value) -> Value {
    let source_hash = request
        .get("source_hash")
        .or_else(|| request.get("sourceHash"))
        .and_then(Value::as_str)
        .unwrap_or("sha256:unknown");
    let source_path = request
        .get("source_path")
        .or_else(|| request.get("sourcePath"))
        .and_then(Value::as_str)
        .unwrap_or("document.pdf");
    let source_filename = request
        .get("sourceFilename")
        .and_then(Value::as_str)
        .or_else(|| {
            Path::new(source_path)
                .file_name()
                .and_then(|name| name.to_str())
        })
        .unwrap_or("document.pdf");
    let preset = request
        .get("preset")
        .and_then(Value::as_str)
        .unwrap_or("table-lite");
    let task = model.get("task").and_then(Value::as_str).unwrap_or("");
    let model_id = model_identity(model);
    let (kind, text, source_object) = if task == "ocr" {
        ("OCR_REGION", "Auto OCR evidence", "mnn-ocr-region-1")
    } else {
        ("TABLE_CELL", "Auto table MNN evidence", "mnn-table-cell-1")
    };
    json!({
        "docId": source_hash,
        "source": {
            "sourceFilename": source_filename,
            "sourceHash": source_hash,
            "metadata": {
                "sourceFilename": source_filename,
                "pageCount": 1
            }
        },
        "body": {
            "pages": [{
                "pageNumber": 1,
                "width": 612.0,
                "height": 792.0,
                "textLayerAvailable": task != "ocr",
                "imageHash": format!("sha256:{}", "0".repeat(64))
            }],
            "units": [{
                "unitId": "unit-mnn-0001",
                "kind": kind,
                "page": 1,
                "text": text,
                "evidenceSpanIds": ["span-mnn-0001"],
                "location": {
                    "page": 1,
                    "readingOrder": 1,
                    "boundingBox": {"x0": 0.0, "y0": 0.0, "x1": 1000.0, "y1": 1000.0}
                },
                "sourceObjectId": source_object,
                "confidence": {"score": 0.9, "rationale": "rust mnn worker protocol"},
                "warnings": []
            }],
            "tables": []
        },
        "parserRun": {
            "parserRunId": "parser-run-rust-mnn-worker",
            "parserVersion": "doctruth-mnn-model-worker",
            "preset": preset,
            "backend": "mnn-model-worker-stub",
            "workerBackend": "mnn-model-worker-stub",
            "models": [model_id],
            "warnings": [{
                "code": "mnn_worker_stub_output",
                "severity": "SEVERE",
                "message": "Rust MNN worker emitted explicit stub output; real MNN inference is not wired"
            }]
        },
        "auditGradeStatus": "NOT_AUDIT_GRADE"
    })
}

fn model_identity(model: &Value) -> String {
    let name = model
        .get("name")
        .and_then(Value::as_str)
        .unwrap_or("mnn-model");
    let version = model.get("version").and_then(Value::as_str).unwrap_or("v1");
    format!("{name}:{version}")
}

fn elapsed_ms(started: Instant) -> f64 {
    (started.elapsed().as_secs_f64() * 1000.0 * 1000.0).round() / 1000.0
}

fn print_json(value: Value) {
    println!("{}", serde_json::to_string(&value).unwrap());
}

fn stub_mode_enabled() -> bool {
    std::env::var("DOCTRUTH_MNN_WORKER_STUB")
        .ok()
        .map(|value| matches!(value.as_str(), "1" | "true" | "TRUE" | "yes" | "YES"))
        .unwrap_or(false)
}

fn fail(code: &str, message: &str) -> ! {
    eprintln!(
        "{}",
        serde_json::to_string(&json!({
            "ok": false,
            "runtime": "mnn",
            "error_code": code,
            "message": message
        }))
        .unwrap()
    );
    std::process::exit(2);
}
