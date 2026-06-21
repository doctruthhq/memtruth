use serde_json::{Value, json};
#[cfg(feature = "mnn-preprocess")]
use sha2::{Digest, Sha256};
use std::io::{self, Read};
use std::path::Path;
use std::time::Instant;

#[cfg(feature = "mnn-preprocess")]
use pdf_oxide::document::PdfDocument;
#[cfg(feature = "mnn-preprocess")]
use pdf_oxide::rendering::{RenderOptions, render_page};

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
    if let Some(source_path) = arg_value(&args, "--preprocess-page") {
        let decoder = arg_value(&args, "--decoder").unwrap_or("table");
        match preprocess_page_probe(source_path, decoder) {
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
    let model_pack = ready_mnn_model_pack(&request);
    if !stub_mode_enabled() {
        if let Some(response) = real_inference_response(&request, &model_pack, started) {
            print_json(response);
            clean_exit();
        }
        fail(
            "mnn_inference_unavailable",
            "Rust MNN worker protocol is ready, but real MNN inference is not wired yet",
        );
    }
    let document = trust_document(&request, &model_pack);
    print_json(json!({
        "ok": true,
        "document": document,
        "metrics": {
            "runtime": "mnn",
            "decoder": model_pack.decoder,
            "inputSource": "rust_mnn_worker_stub",
            "stubMode": true,
            "coldStartMs": 0.0,
            "preprocessing": preprocessing_contract_json(model_pack.decoder),
            "inferenceMs": elapsed_ms(started),
            "loadedModels": model_pack.model_identities(),
            "auxiliaryArtifacts": model_pack.auxiliary_identities(),
            "unload": {
                "status": "completed",
                "policy": "idle-after-request"
            }
        }
    }));
}

fn real_inference_response(
    request: &Value,
    model_pack: &ReadyModelPack,
    started: Instant,
) -> Option<Value> {
    match model_pack.decoder {
        "ocr" => real_ocr_inference_response(request, model_pack, started),
        "table" => real_table_inference_response(request, model_pack, started),
        _ => None,
    }
}

#[cfg(feature = "mnn-ocr")]
fn real_ocr_inference_response(
    request: &Value,
    model_pack: &ReadyModelPack,
    started: Instant,
) -> Option<Value> {
    match ocr_inference_response(request, model_pack, started) {
        Ok(response) => Some(response),
        Err((code, message)) => fail(code, &message),
    }
}

#[cfg(not(feature = "mnn-ocr"))]
fn real_ocr_inference_response(
    _request: &Value,
    _model_pack: &ReadyModelPack,
    _started: Instant,
) -> Option<Value> {
    None
}

#[cfg(feature = "mnn-native")]
fn real_table_inference_response(
    request: &Value,
    model_pack: &ReadyModelPack,
    started: Instant,
) -> Option<Value> {
    match table_inference_response(request, model_pack, started) {
        Ok(response) => Some(response),
        Err((code, message)) => fail(code, &message),
    }
}

#[cfg(not(feature = "mnn-native"))]
fn real_table_inference_response(
    _request: &Value,
    _model_pack: &ReadyModelPack,
    _started: Instant,
) -> Option<Value> {
    None
}

#[cfg(feature = "mnn-ocr")]
fn ocr_inference_response(
    request: &Value,
    model_pack: &ReadyModelPack,
    started: Instant,
) -> Result<Value, (&'static str, String)> {
    let load_started = Instant::now();
    let engine = ocr_rs::OcrEngine::new(
        model_role_path(&model_pack.models, "text-detection")?,
        model_role_path(&model_pack.models, "text-recognition")?,
        model_role_path(&model_pack.auxiliary, "recognition-charset")?,
        Some(ocr_rs::OcrEngineConfig::new().with_threads(ocr_threads())),
    )
    .map_err(|error| ("ocr_mnn_load_failed", error.to_string()))?;
    let load_ms = elapsed_ms(load_started);

    let render_started = Instant::now();
    let image = render_first_page_image(request)?;
    let render_ms = elapsed_ms(render_started);

    let inference_started = Instant::now();
    let results = engine
        .recognize(&image)
        .map_err(|error| ("ocr_mnn_inference_failed", error.to_string()))?;
    let inference_ms = elapsed_ms(inference_started);
    let document = ocr_trust_document(request, model_pack, image.width(), image.height(), &results);

    Ok(json!({
        "ok": true,
        "document": document,
        "metrics": {
            "runtime": "mnn",
            "decoder": "ocr",
            "inputSource": "pdf_oxide_rendered_page",
            "stubMode": false,
            "coldStartMs": load_ms,
            "renderMs": render_ms,
            "preprocessing": preprocessing_contract_json("ocr"),
            "inferenceMs": inference_ms,
            "totalMs": elapsed_ms(started),
            "loadedModels": model_pack.model_identities(),
            "auxiliaryArtifacts": model_pack.auxiliary_identities(),
            "ocrRegions": results.len(),
            "unload": {
                "status": "completed",
                "policy": "idle-after-request"
            }
        }
    }))
}

#[cfg(feature = "mnn-native")]
fn table_inference_response(
    request: &Value,
    model_pack: &ReadyModelPack,
    started: Instant,
) -> Result<Value, (&'static str, String)> {
    use mnn_rs::{BackendType, Interpreter, ScheduleConfig};

    let load_started = Instant::now();
    let interpreter = Interpreter::from_file(model_role_path(
        &model_pack.models,
        "table-structure-decoder",
    )?)
    .map_err(|error| ("table_mnn_load_failed", error.to_string()))?;
    let load_ms = elapsed_ms(load_started);

    let render_started = Instant::now();
    let image = render_first_page_image(request)?;
    let render_ms = elapsed_ms(render_started);

    let session_started = Instant::now();
    let config = ScheduleConfig::new()
        .backend(BackendType::CPU)
        .num_threads(native_probe_threads());
    let mut session = interpreter
        .create_session(config)
        .map_err(|error| ("table_mnn_session_failed", error.to_string()))?;
    let session_ms = elapsed_ms(session_started);

    let input_started = Instant::now();
    let input = session
        .get_input(None)
        .map_err(|error| ("table_mnn_input_failed", error.to_string()))?;
    let input_shape = input.shape();
    let input_data = table_input_tensor(&image, &input_shape)?;
    input
        .write(&input_data)
        .map_err(|error| ("table_mnn_input_failed", error.to_string()))?;
    let input_ms = elapsed_ms(input_started);

    let inference_started = Instant::now();
    session
        .run()
        .map_err(|error| ("table_mnn_inference_failed", error.to_string()))?;
    let inference_ms = elapsed_ms(inference_started);

    let output = session
        .get_output(None)
        .map_err(|error| ("table_mnn_output_failed", error.to_string()))?;
    let output_shape = output.shape();
    let output_data: Vec<f32> = output
        .read()
        .map_err(|error| ("table_mnn_output_failed", error.to_string()))?;
    let document = table_decoder_pending_document(request, model_pack, &input_shape, &output_shape);

    Ok(json!({
        "ok": true,
        "document": document,
        "metrics": {
            "runtime": "mnn",
            "decoder": "table",
            "inputSource": "pdf_oxide_rendered_page",
            "stubMode": false,
            "coldStartMs": load_ms,
            "renderMs": render_ms,
            "sessionMs": session_ms,
            "inputMs": input_ms,
            "preprocessing": preprocessing_contract_json("table"),
            "inferenceMs": inference_ms,
            "totalMs": elapsed_ms(started),
            "loadedModels": model_pack.model_identities(),
            "input": {
                "shape": input_shape,
                "elements": input_data.len()
            },
            "output": {
                "shape": output_shape,
                "elements": output_data.len(),
                "sample": output_sample(&output_data),
                "stats": output_stats(&output_data)
            },
            "unload": {
                "status": "completed",
                "policy": "idle-after-request"
            }
        }
    }))
}

fn probe_model_arg(args: &[String]) -> Option<&str> {
    args.windows(2)
        .find(|window| window[0] == "--probe-model")
        .map(|window| window[1].as_str())
}

fn arg_value<'a>(args: &'a [String], flag: &str) -> Option<&'a str> {
    args.windows(2)
        .find(|window| window[0] == flag)
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
        "decoders": decoder_json(),
        "stubMode": stub_mode_enabled(),
        "productionPythonResidency": false
    })
}

#[cfg(feature = "mnn-preprocess")]
fn preprocess_page_probe(
    source_path: &str,
    decoder: &str,
) -> Result<Value, (&'static str, String)> {
    let started = Instant::now();
    let render_started = Instant::now();
    let image = render_first_page_image_from_path(source_path)?;
    let render_ms = elapsed_ms(render_started);
    let tensor_started = Instant::now();
    let tensor = rgb_nchw_tensor_report(&image);
    let tensor_ms = elapsed_ms(tensor_started);

    Ok(json!({
        "ok": true,
        "runtime": "mnn",
        "engine": "mnn",
        "command": "preprocess_page",
        "protocol_version": PROTOCOL_VERSION,
        "sourcePath": source_path,
        "preprocessing": preprocessing_contract_json(decoder),
        "image": {
            "width": image.width(),
            "height": image.height(),
            "colorSpace": "RGB"
        },
        "tensor": tensor,
        "metrics": {
            "renderMs": render_ms,
            "tensorMs": tensor_ms,
            "totalMs": elapsed_ms(started)
        }
    }))
}

#[cfg(not(feature = "mnn-preprocess"))]
fn preprocess_page_probe(
    _source_path: &str,
    _decoder: &str,
) -> Result<Value, (&'static str, String)> {
    Err((
        "mnn_preprocess_feature_disabled",
        "build doctruth-mnn-model-worker with --features mnn-preprocess to run PDF page preprocessing"
            .to_string(),
    ))
}

#[cfg(feature = "mnn-preprocess")]
fn rgb_nchw_tensor_report(image: &image::DynamicImage) -> Value {
    let rgb = image.to_rgb8();
    let (width, height) = rgb.dimensions();
    let elements = width as u64 * height as u64 * 3;
    let mut hasher = Sha256::new();
    let mut first_values = Vec::new();

    for channel in 0..3_usize {
        for y in 0..height {
            for x in 0..width {
                let value = rgb.get_pixel(x, y).0[channel] as f32 / 255.0;
                hasher.update(value.to_le_bytes());
                if first_values.len() < 12 {
                    first_values.push(rounded_f64(value as f64));
                }
            }
        }
    }

    json!({
        "dtype": "f32",
        "layout": "NCHW",
        "shape": [1, 3, height, width],
        "elements": elements,
        "bytes": elements * 4,
        "sha256": format!("sha256:{:x}", hasher.finalize()),
        "firstValues": first_values
    })
}

#[cfg(feature = "mnn-native")]
fn table_input_tensor(
    image: &image::DynamicImage,
    shape: &[i32],
) -> Result<Vec<f32>, (&'static str, String)> {
    let layout = tensor_image_layout(shape)?;
    let resized = image
        .resize_exact(
            layout.width,
            layout.height,
            image::imageops::FilterType::Triangle,
        )
        .to_rgb8();
    let mean = [0.485_f32, 0.456_f32, 0.406_f32];
    let std = [0.229_f32, 0.224_f32, 0.225_f32];
    let mut tensor = Vec::with_capacity((layout.width * layout.height * 3) as usize);

    if layout.nchw {
        for channel in 0..3_usize {
            for y in 0..layout.height {
                for x in 0..layout.width {
                    let value = resized.get_pixel(x, y).0[channel] as f32 / 255.0;
                    tensor.push((value - mean[channel]) / std[channel]);
                }
            }
        }
    } else {
        for y in 0..layout.height {
            for x in 0..layout.width {
                let pixel = resized.get_pixel(x, y).0;
                for channel in 0..3_usize {
                    let value = pixel[channel] as f32 / 255.0;
                    tensor.push((value - mean[channel]) / std[channel]);
                }
            }
        }
    }

    Ok(tensor)
}

#[cfg(feature = "mnn-native")]
struct TensorImageLayout {
    width: u32,
    height: u32,
    nchw: bool,
}

#[cfg(feature = "mnn-native")]
fn tensor_image_layout(shape: &[i32]) -> Result<TensorImageLayout, (&'static str, String)> {
    if shape.len() != 4 || shape.iter().any(|dimension| *dimension <= 0) {
        return Err((
            "table_mnn_input_failed",
            format!("expected positive 4D image tensor shape, got {shape:?}"),
        ));
    }
    if shape[0] == 1 && shape[1] == 3 {
        return Ok(TensorImageLayout {
            height: shape[2] as u32,
            width: shape[3] as u32,
            nchw: true,
        });
    }
    if shape[0] == 1 && shape[3] == 3 {
        return Ok(TensorImageLayout {
            height: shape[1] as u32,
            width: shape[2] as u32,
            nchw: false,
        });
    }
    Err((
        "table_mnn_input_failed",
        format!("unsupported table image tensor shape {shape:?}"),
    ))
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

#[derive(Clone)]
struct ReadyModelPack {
    decoder: &'static str,
    models: Vec<Value>,
    auxiliary: Vec<Value>,
}

impl ReadyModelPack {
    fn model_identities(&self) -> Vec<String> {
        self.models.iter().map(model_identity).collect()
    }

    fn auxiliary_identities(&self) -> Vec<String> {
        self.auxiliary.iter().map(model_identity).collect()
    }
}

fn ready_mnn_model_pack(request: &Value) -> ReadyModelPack {
    let Some(models) = request.get("models").and_then(Value::as_array) else {
        fail("model_unavailable", "request has no models");
    };
    let ready_models = ready_mnn_models(models);
    if ready_models.is_empty() {
        fail(
            "unsupported_model_runtime",
            "Rust worker accepts READY MNN artifacts only",
        );
    }
    if requested_decoder(request, &ready_models) == "ocr" {
        return ready_ocr_model_pack(request, ready_models);
    }
    ReadyModelPack {
        decoder: requested_decoder(request, &ready_models),
        models: vec![ready_models[0].clone()],
        auxiliary: Vec::new(),
    }
}

fn ready_mnn_models(models: &[Value]) -> Vec<Value> {
    let mut ready = Vec::new();
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
        ready.push(model.clone());
    }
    ready
}

fn ready_ocr_model_pack(request: &Value, ready_models: Vec<Value>) -> ReadyModelPack {
    let detection = find_model_role(&ready_models, "text-detection");
    let recognition = find_model_role(&ready_models, "text-recognition");
    if detection.is_none() || recognition.is_none() {
        fail(
            "model_unavailable",
            "OCR MNN decoder requires text-detection and text-recognition models",
        );
    }
    let auxiliary = ready_auxiliary_artifacts(request);
    if find_model_role(&auxiliary, "recognition-charset").is_none() {
        fail(
            "model_unavailable",
            "OCR MNN decoder requires recognition-charset auxiliary artifact",
        );
    }
    ReadyModelPack {
        decoder: "ocr",
        models: vec![detection.unwrap().clone(), recognition.unwrap().clone()],
        auxiliary,
    }
}

fn ready_auxiliary_artifacts(request: &Value) -> Vec<Value> {
    let Some(artifacts) = request.get("auxiliaryArtifacts").and_then(Value::as_array) else {
        return Vec::new();
    };
    let mut ready = Vec::new();
    for artifact in artifacts {
        if artifact.get("cacheStatus").and_then(Value::as_str) != Some("READY") {
            continue;
        }
        let Some(path) = artifact.get("cachePath").and_then(Value::as_str) else {
            continue;
        };
        if Path::new(path).is_file() {
            ready.push(artifact.clone());
        }
    }
    ready
}

fn requested_decoder(request: &Value, models: &[Value]) -> &'static str {
    if request.get("preset").and_then(Value::as_str) == Some("ocr")
        || models
            .iter()
            .any(|model| model.get("task").and_then(Value::as_str) == Some("ocr"))
    {
        return "ocr";
    }
    if models
        .iter()
        .any(|model| model.get("task").and_then(Value::as_str) == Some("layout-detection"))
    {
        return "layout";
    }
    "table"
}

fn preprocessing_contract_json(decoder: &str) -> Value {
    let (mean, std, resize) = match decoder {
        "table" => (
            json!([0.485, 0.456, 0.406]),
            json!([0.229, 0.224, 0.225]),
            json!({
                "width": 800,
                "height": 800,
                "keepAspectRatio": false,
                "resample": "bilinear",
                "sourceOfTruth": "opendataloader-hybrid-models table-lite manifest"
            }),
        ),
        "layout" => (
            json!([0.485, 0.456, 0.406]),
            json!([0.229, 0.224, 0.225]),
            json!({
                "width": 640,
                "height": 640,
                "keepAspectRatio": false,
                "resample": "bilinear",
                "sourceOfTruth": "opendataloader-hybrid-models layout-server manifest"
            }),
        ),
        _ => (
            json!([0.0, 0.0, 0.0]),
            json!([1.0, 1.0, 1.0]),
            json!({
                "mode": "model-specific",
                "sourceOfTruth": "model manifest or decoder adapter"
            }),
        ),
    };
    json!({
        "decoder": decoder,
        "imageSource": "pdf_oxide_rendered_page",
        "dpi": 144,
        "colorSpace": "RGB",
        "channelOrder": "RGB",
        "tensorLayout": "NCHW",
        "valueType": "f32",
        "scale": 0.00392156862745098_f64,
        "mean": mean,
        "std": std,
        "resize": resize,
        "parity": {
            "required": true,
            "checks": [
                "input_shape",
                "first_tensor_values",
                "tensor_sha256",
                "python_reference_digest"
            ],
            "promotionBlockedWithoutTensorDigest": true
        }
    })
}

fn find_model_role<'a>(models: &'a [Value], role: &str) -> Option<&'a Value> {
    models
        .iter()
        .find(|model| model.get("role").and_then(Value::as_str) == Some(role))
}

fn trust_document(request: &Value, model_pack: &ReadyModelPack) -> Value {
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
    let model_ids = model_pack.model_identities();
    let (kind, text, source_object) = if model_pack.decoder == "ocr" {
        ("OCR_REGION", "Auto OCR evidence", "mnn-ocr-region-1")
    } else if model_pack.decoder == "layout" {
        (
            "TEXT_BLOCK",
            "Auto layout MNN evidence",
            "mnn-layout-region-1",
        )
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
                "textLayerAvailable": model_pack.decoder != "ocr",
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
            "models": model_ids,
            "warnings": [{
                "code": "mnn_worker_stub_output",
                "severity": "SEVERE",
                "message": "Rust MNN worker emitted explicit stub output; real MNN inference is not wired"
            }]
        },
        "auditGradeStatus": "NOT_AUDIT_GRADE"
    })
}

#[cfg(feature = "mnn-native")]
fn table_decoder_pending_document(
    request: &Value,
    model_pack: &ReadyModelPack,
    input_shape: &[i32],
    output_shape: &[i32],
) -> Value {
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
    let model_ids = model_pack.model_identities();
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
                "textLayerAvailable": true,
                "imageHash": format!("sha256:{}", "0".repeat(64))
            }],
            "units": [],
            "tables": []
        },
        "contentBlocks": [],
        "parseTrace": {
            "traceId": "trace-mnn-table-0001",
            "parserRunId": "parser-run-rust-mnn-table",
            "readingOrder": {
                "source": "mnn-table-detection-order",
                "fallback": true,
                "confidence": 0.0
            },
            "pages": [],
            "sectionTree": [],
            "warnings": [{
                "code": "table_mnn_decoder_pending",
                "severity": "SEVERE",
                "message": "MNN table model inference ran, but table-structure decoding into TrustTable cells is not implemented yet"
            }]
        },
        "parserRun": {
            "parserRunId": "parser-run-rust-mnn-table",
            "parserVersion": "doctruth-mnn-model-worker",
            "preset": "table-lite",
            "backend": "rust-sidecar+model-worker",
            "workerBackend": "mnn-table-rs",
            "models": model_ids,
            "modelShapes": {
                "input": input_shape,
                "output": output_shape
            },
            "warnings": [{
                "code": "table_mnn_decoder_pending",
                "severity": "SEVERE",
                "message": "MNN table model output is available but not decoded into audit-grade table cells"
            }]
        },
        "auditGradeStatus": "NOT_AUDIT_GRADE"
    })
}

#[cfg(feature = "mnn-ocr")]
fn ocr_trust_document(
    request: &Value,
    model_pack: &ReadyModelPack,
    image_width: u32,
    image_height: u32,
    results: &[ocr_rs::OcrResult_],
) -> Value {
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
    let model_ids = model_pack.model_identities();
    let units = results
        .iter()
        .enumerate()
        .map(|(index, result)| ocr_unit_json(index, result))
        .collect::<Vec<_>>();
    let text_spans = units
        .iter()
        .enumerate()
        .map(|(index, unit)| ocr_span_json(index, unit))
        .collect::<Vec<_>>();
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
                "width": image_width,
                "height": image_height,
                "textLayerAvailable": false,
                "imageHash": format!("sha256:{}", "0".repeat(64))
            }],
            "units": units,
            "tables": []
        },
        "contentBlocks": text_spans,
        "parseTrace": {
            "traceId": "trace-mnn-ocr-0001",
            "parserRunId": "parser-run-rust-mnn-ocr",
            "readingOrder": {
                "source": "mnn-ocr-detection-order",
                "fallback": false,
                "confidence": 0.8
            },
            "pages": [{
                "pageIndex": 0,
                "pageNumber": 1,
                "pageSize": {"width": image_width, "height": image_height},
                "preprocBlocks": [],
                "readingBlocks": text_spans,
                "discardedBlocks": [],
                "tables": [],
                "images": [],
                "equations": [],
                "textSpans": []
            }],
            "sectionTree": [],
            "warnings": []
        },
        "parserRun": {
            "parserRunId": "parser-run-rust-mnn-ocr",
            "parserVersion": "doctruth-mnn-model-worker",
            "preset": "ocr",
            "backend": "rust-sidecar+model-worker",
            "workerBackend": "mnn-ocr-rs",
            "models": model_ids,
            "warnings": []
        },
        "auditGradeStatus": if results.is_empty() { "NOT_AUDIT_GRADE" } else { "AUDIT_GRADE" }
    })
}

#[cfg(feature = "mnn-ocr")]
fn ocr_unit_json(index: usize, result: &ocr_rs::OcrResult_) -> Value {
    let unit_id = format!("unit-ocr-{index:04}");
    let span_id = format!("span-ocr-{index:04}");
    let trace_span_id = format!("trace-span-ocr-{index:04}");
    let source_object_id = format!("mnn-ocr-region-{index:04}");
    json!({
        "unitId": unit_id,
        "kind": "OCR_REGION",
        "page": 1,
        "text": result.text,
        "evidenceSpanIds": [span_id],
        "parseTraceSpanIds": [trace_span_id],
        "location": {
            "page": 1,
            "readingOrder": index + 1,
            "boundingBox": ocr_bbox_json(result)
        },
        "sourceObjectId": source_object_id,
        "confidence": {
            "score": rounded_f64(result.confidence as f64),
            "rationale": "ocr-rs mnn detection and recognition"
        },
        "warnings": []
    })
}

#[cfg(feature = "mnn-ocr")]
fn ocr_span_json(index: usize, unit: &Value) -> Value {
    json!({
        "blockId": format!("block-ocr-{index:04}"),
        "type": "text",
        "page": 1,
        "readingOrder": index + 1,
        "text": unit.get("text").cloned().unwrap_or(Value::Null),
        "normalizedText": unit.get("text").cloned().unwrap_or(Value::Null),
        "bbox": unit.pointer("/location/boundingBox").cloned().unwrap_or(Value::Null),
        "evidenceSpanIds": unit.get("evidenceSpanIds").cloned().unwrap_or_else(|| json!([])),
        "sourceUnitIds": [unit.get("unitId").cloned().unwrap_or(Value::Null)],
        "tableId": Value::Null,
        "textLevel": Value::Null,
        "sectionId": Value::Null,
        "parentSectionId": Value::Null,
        "sectionPath": [],
        "sectionTitlePath": [],
        "isSectionRoot": false,
        "warnings": []
    })
}

#[cfg(feature = "mnn-ocr")]
fn ocr_bbox_json(result: &ocr_rs::OcrResult_) -> Value {
    let left = result.bbox.rect.left() as f64;
    let top = result.bbox.rect.top() as f64;
    json!({
        "x0": left,
        "y0": top,
        "x1": left + result.bbox.rect.width() as f64,
        "y1": top + result.bbox.rect.height() as f64
    })
}

#[cfg(feature = "mnn-preprocess")]
fn render_first_page_image(request: &Value) -> Result<image::DynamicImage, (&'static str, String)> {
    let source_path = request
        .get("source_path")
        .or_else(|| request.get("sourcePath"))
        .and_then(Value::as_str)
        .ok_or_else(|| {
            (
                "pdf_page_preprocess_failed",
                "source_path is required".to_string(),
            )
        })?;
    render_first_page_image_from_path(source_path)
}

#[cfg(feature = "mnn-preprocess")]
fn render_first_page_image_from_path(
    source_path: &str,
) -> Result<image::DynamicImage, (&'static str, String)> {
    let document = PdfDocument::open(source_path)
        .map_err(|error| ("pdf_page_preprocess_failed", error.to_string()))?;
    let rendered = render_page(&document, 0, &RenderOptions::with_dpi(144))
        .map_err(|error| ("pdf_page_preprocess_failed", error.to_string()))?;
    image::load_from_memory(&rendered.data)
        .map_err(|error| ("pdf_page_preprocess_failed", error.to_string()))
}

#[cfg(any(feature = "mnn-ocr", feature = "mnn-native"))]
fn model_role_path<'a>(models: &'a [Value], role: &str) -> Result<&'a str, (&'static str, String)> {
    find_model_role(models, role)
        .and_then(|model| model.get("cachePath").and_then(Value::as_str))
        .ok_or_else(|| {
            (
                "model_unavailable",
                format!("required model role {role} has no cachePath"),
            )
        })
}

#[cfg(feature = "mnn-ocr")]
fn ocr_threads() -> i32 {
    std::env::var("DOCTRUTH_MNN_OCR_THREADS")
        .ok()
        .and_then(|value| value.parse::<i32>().ok())
        .filter(|threads| *threads > 0)
        .unwrap_or(4)
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

#[cfg(any(
    feature = "mnn-native",
    feature = "mnn-ocr",
    feature = "mnn-preprocess"
))]
fn rounded_f64(value: f64) -> f64 {
    (value * 1_000_000.0).round() / 1_000_000.0
}

fn print_json(value: Value) {
    println!("{}", serde_json::to_string(&value).unwrap());
}

fn clean_exit() -> ! {
    use std::io::Write;

    let _ = std::io::stdout().flush();
    #[cfg(unix)]
    unsafe {
        unsafe extern "C" {
            fn _exit(status: i32) -> !;
        }
        _exit(0);
    }
    #[cfg(not(unix))]
    std::process::exit(0);
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

#[cfg(feature = "mnn-ocr")]
fn decoder_json() -> Value {
    json!({
        "ocr": {
            "compiled": true,
            "backend": "ocr-rs",
            "modelFormat": "mnn",
            "binding": std::any::type_name::<ocr_rs::OcrEngine>()
        }
    })
}

#[cfg(not(feature = "mnn-ocr"))]
fn decoder_json() -> Value {
    json!({
        "ocr": {
            "compiled": false,
            "backend": Value::Null,
            "modelFormat": "mnn",
            "binding": Value::Null
        }
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
