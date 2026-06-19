use serde_json::{Value, json};
use std::io::{self, Read};
use std::path::Path;
use std::time::Instant;

const PROTOCOL_VERSION: &str = "1";

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.iter().any(|arg| arg == "--doctor") {
        print_json(doctor_json());
        return;
    }
    if let Some(model_path) = probe_model_arg(&args) {
        match probe_model(model_path) {
            Ok(report) => print_json(report),
            Err((code, message)) => fail(code, &message),
        }
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

fn probe_model_arg(args: &[String]) -> Option<&str> {
    args.windows(2)
        .find(|window| window[0] == "--probe-model")
        .map(|window| window[1].as_str())
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
        "nativeBackend": native_backend_json(),
        "stubMode": stub_mode_enabled(),
        "productionPythonResidency": false
    })
}

#[cfg(feature = "mnn-native")]
fn probe_model(model_path: &str) -> Result<Value, (&'static str, String)> {
    use mnn_rs::{BackendType, Interpreter, ScheduleConfig};

    let started = Instant::now();
    let load_started = Instant::now();
    let interpreter = Interpreter::from_file(model_path)
        .map_err(|error| ("mnn_probe_load_failed", error.to_string()))?;
    let load_ms = elapsed_ms(load_started);

    let session_started = Instant::now();
    let config = ScheduleConfig::new()
        .backend(BackendType::CPU)
        .num_threads(native_probe_threads());
    let mut session = interpreter
        .create_session(config)
        .map_err(|error| ("mnn_probe_session_failed", error.to_string()))?;
    let session_ms = elapsed_ms(session_started);
    let resize_started = Instant::now();
    interpreter.resize_session(&mut session);
    let resize_ms = elapsed_ms(resize_started);

    let input = session
        .get_input(None)
        .map_err(|error| ("mnn_probe_input_failed", error.to_string()))?;
    let input_shape = input.shape();
    let input_elements = checked_element_count(input.element_count())?;
    let input_data: Vec<f32> = (0..input_elements)
        .map(|index| (index % 256) as f32 / 255.0)
        .collect();
    let input_write = input.write(&input_data);

    let inference_started = Instant::now();
    session
        .run()
        .map_err(|error| ("mnn_probe_inference_failed", error.to_string()))?;
    let inference_ms = elapsed_ms(inference_started);

    let output = session
        .get_output(None)
        .map_err(|error| ("mnn_probe_output_failed", error.to_string()))?;
    let output_shape = output.shape();
    let output_elements = checked_element_count(output.element_count())?;
    let output_read: Result<Vec<f32>, String> = output.read().map_err(|error| error.to_string());
    let output_read_ready = output_read.is_ok();
    let host_tensor_io_ready = input_write.is_ok() && output_read_ready;
    let output_data = output_read.unwrap_or_default();

    Ok(json!({
        "ok": true,
        "runtime": "mnn",
        "engine": "mnn",
        "command": "probe_model",
        "protocol_version": PROTOCOL_VERSION,
        "nativeBackend": native_backend_json(),
        "mnnSessionReady": true,
        "inferenceRan": true,
        "modelPath": model_path,
        "modelBytes": model_size(model_path),
        "input": {
            "shape": input_shape,
            "elements": input_elements,
            "hostWriteReady": input_write.is_ok(),
            "hostWriteError": input_write.err().map(|error| error.to_string())
        },
        "output": {
            "shape": output_shape,
            "elements": output_elements,
            "hostReadReady": output_read_ready,
            "sample": output_sample(&output_data),
            "stats": output_stats(&output_data)
        },
        "hostTensorIoReady": host_tensor_io_ready,
        "metrics": {
            "loadMs": load_ms,
            "sessionMs": session_ms,
            "resizeMs": resize_ms,
            "inferenceMs": inference_ms,
            "totalMs": elapsed_ms(started),
            "memoryBytes": session.memory_usage(),
            "flops": session.flops()
        }
    }))
}

#[cfg(not(feature = "mnn-native"))]
fn probe_model(_model_path: &str) -> Result<Value, (&'static str, String)> {
    Err((
        "mnn_native_feature_disabled",
        "build doctruth-mnn-model-worker with --features mnn-native to probe real MNN inference"
            .to_string(),
    ))
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

#[cfg(feature = "mnn-native")]
fn checked_element_count(count: i32) -> Result<usize, (&'static str, String)> {
    usize::try_from(count).map_err(|_| {
        (
            "mnn_probe_invalid_tensor",
            format!("negative tensor element count: {count}"),
        )
    })
}

#[cfg(feature = "mnn-native")]
fn native_probe_threads() -> u32 {
    std::env::var("DOCTRUTH_MNN_NATIVE_THREADS")
        .ok()
        .and_then(|value| value.parse::<u32>().ok())
        .filter(|threads| *threads > 0)
        .unwrap_or(4)
}

#[cfg(feature = "mnn-native")]
fn model_size(model_path: &str) -> u64 {
    std::fs::metadata(model_path)
        .map(|metadata| metadata.len())
        .unwrap_or(0)
}

#[cfg(feature = "mnn-native")]
fn output_sample(values: &[f32]) -> Vec<f64> {
    values
        .iter()
        .take(8)
        .map(|value| rounded_f64(*value as f64))
        .collect()
}

#[cfg(feature = "mnn-native")]
fn output_stats(values: &[f32]) -> Value {
    if values.is_empty() {
        return json!({"min": Value::Null, "max": Value::Null, "mean": Value::Null});
    }
    let mut min = f32::INFINITY;
    let mut max = f32::NEG_INFINITY;
    let mut sum = 0.0_f64;
    for value in values {
        min = min.min(*value);
        max = max.max(*value);
        sum += *value as f64;
    }
    json!({
        "min": rounded_f64(min as f64),
        "max": rounded_f64(max as f64),
        "mean": rounded_f64(sum / values.len() as f64)
    })
}

#[cfg(feature = "mnn-native")]
fn rounded_f64(value: f64) -> f64 {
    (value * 1_000_000.0).round() / 1_000_000.0
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

#[cfg(feature = "mnn-native")]
fn native_backend_json() -> Value {
    json!({
        "compiled": true,
        "crate": "mnn-rs",
        "binding": std::any::type_name::<mnn_rs::Interpreter>(),
        "mode": "native-mnn-feature"
    })
}

#[cfg(not(feature = "mnn-native"))]
fn native_backend_json() -> Value {
    json!({
        "compiled": false,
        "crate": "mnn-rs",
        "binding": Value::Null,
        "mode": "feature-disabled"
    })
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
