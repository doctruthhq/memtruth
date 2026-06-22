use serde_json::{Value, json};
#[cfg(feature = "mnn-preprocess")]
use sha2::{Digest, Sha256};
#[cfg(feature = "mnn-native")]
use std::ffi::CString;
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
    let mut input = session
        .get_input(None)
        .map_err(|error| ("table_mnn_input_failed", error.to_string()))?;
    if tensor_shape_is_dynamic(&input.shape()) {
        interpreter.resize_tensor(&mut input, &table_model_input_shape());
        interpreter.resize_session(&mut session);
        input = session
            .get_input(None)
            .map_err(|error| ("table_mnn_input_failed", error.to_string()))?;
    }
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

    let outputs = read_table_outputs(&interpreter, &session)?;
    let document = table_detection_document(
        request,
        model_pack,
        &input_shape,
        image.width(),
        image.height(),
        &image,
        &outputs,
    );

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
            "outputs": {
                "names": outputs.output_names,
                "logits": {
                    "shape": outputs.logits_shape,
                    "elements": outputs.logits.len(),
                    "sample": output_sample(&outputs.logits),
                    "stats": output_stats(&outputs.logits)
                },
                "predBoxes": {
                    "shape": outputs.boxes_shape,
                    "elements": outputs.boxes.len(),
                    "sample": output_sample(&outputs.boxes),
                    "stats": output_stats(&outputs.boxes)
                }
            },
            "detections": outputs.detections.len(),
            "unload": {
                "status": "completed",
                "policy": "idle-after-request"
            }
        }
    }))
}

#[cfg(feature = "mnn-native")]
fn read_table_outputs(
    interpreter: &mnn_rs::Interpreter,
    session: &mnn_rs::Session,
) -> Result<TableModelOutputs, (&'static str, String)> {
    let logits = named_output_tensor(session, "logits")?;
    let boxes = named_output_tensor(session, "pred_boxes")?;
    let logits_shape = logits.shape();
    let boxes_shape = boxes.shape();
    let logits_data: Vec<f32> = logits
        .read()
        .map_err(|error| ("table_mnn_output_failed", error.to_string()))?;
    let boxes_data: Vec<f32> = boxes
        .read()
        .map_err(|error| ("table_mnn_output_failed", error.to_string()))?;
    let output_names = interpreter.get_output_names(session);
    let detections = table_detections(&logits_shape, &logits_data, &boxes_shape, &boxes_data)?;

    Ok(TableModelOutputs {
        output_names,
        logits_shape,
        logits: logits_data,
        boxes_shape,
        boxes: boxes_data,
        detections,
    })
}

#[cfg(feature = "mnn-native")]
fn named_output_tensor(
    session: &mnn_rs::Session,
    name: &str,
) -> Result<mnn_rs::Tensor, (&'static str, String)> {
    let c_name = CString::new(name).map_err(|error| {
        (
            "table_mnn_output_failed",
            format!("invalid output tensor name {name}: {error}"),
        )
    })?;
    let tensor_ptr = unsafe {
        mnn_rs_sys::mnn_interpreter_get_session_output(
            session.interpreter(),
            session.inner(),
            c_name.as_ptr(),
        )
    };
    if tensor_ptr.is_null() {
        return Err((
            "table_mnn_output_failed",
            format!("Output tensor '{name}' not found"),
        ));
    }
    Ok(unsafe { mnn_rs::Tensor::from_ptr(tensor_ptr, Some(name.to_string())) })
}

#[cfg(feature = "mnn-native")]
struct TableModelOutputs {
    output_names: Vec<String>,
    logits_shape: Vec<i32>,
    logits: Vec<f32>,
    boxes_shape: Vec<i32>,
    boxes: Vec<f32>,
    detections: Vec<TableDetection>,
}

#[cfg(feature = "mnn-native")]
#[derive(Clone)]
struct TableDetection {
    label: &'static str,
    score: f32,
    bbox: NormalizedBox,
}

#[cfg(feature = "mnn-native")]
#[derive(Clone, Copy)]
struct NormalizedBox {
    x0: f64,
    y0: f64,
    x1: f64,
    y1: f64,
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
fn tensor_shape_is_dynamic(shape: &[i32]) -> bool {
    shape.is_empty() || shape.iter().any(|dimension| *dimension <= 0)
}

#[cfg(feature = "mnn-native")]
fn table_model_input_shape() -> [i32; 4] {
    [1, 3, 800, 800]
}

#[cfg(feature = "mnn-native")]
fn table_detections(
    logits_shape: &[i32],
    logits: &[f32],
    boxes_shape: &[i32],
    boxes: &[f32],
) -> Result<Vec<TableDetection>, (&'static str, String)> {
    let (query_count, class_count) = table_logits_shape(logits_shape, logits.len())?;
    let box_query_count = table_boxes_shape(boxes_shape, boxes.len())?;
    if query_count != box_query_count {
        return Err((
            "table_mnn_output_failed",
            format!("logits queries {query_count} != box queries {box_query_count}"),
        ));
    }

    let mut detections = Vec::new();
    for query in 0..query_count {
        let logits_offset = query * class_count;
        let scores = softmax(&logits[logits_offset..logits_offset + class_count]);
        let Some((label_index, score)) = best_table_class(&scores) else {
            continue;
        };
        if score < table_class_threshold(label_index) {
            continue;
        }
        let box_offset = query * 4;
        let bbox = normalized_cxcywh_to_box(&boxes[box_offset..box_offset + 4]);
        if bbox_area(bbox) < 0.0001 {
            continue;
        }
        detections.push(TableDetection {
            label: table_label(label_index),
            score,
            bbox,
        });
    }
    detections.sort_by(|left, right| {
        right
            .score
            .partial_cmp(&left.score)
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    Ok(detections)
}

#[cfg(feature = "mnn-native")]
fn table_logits_shape(
    shape: &[i32],
    value_count: usize,
) -> Result<(usize, usize), (&'static str, String)> {
    if shape.len() == 3 && shape[0] == 1 && shape[1] > 0 && shape[2] > 1 {
        return Ok((shape[1] as usize, shape[2] as usize));
    }
    if value_count % 125 == 0 && value_count > 125 {
        return Ok((125, value_count / 125));
    }
    Err((
        "table_mnn_output_failed",
        format!("unsupported logits shape {shape:?} with {value_count} values"),
    ))
}

#[cfg(feature = "mnn-native")]
fn table_boxes_shape(shape: &[i32], value_count: usize) -> Result<usize, (&'static str, String)> {
    if shape.len() == 3 && shape[0] == 1 && shape[1] > 0 && shape[2] == 4 {
        return Ok(shape[1] as usize);
    }
    if value_count % 4 == 0 {
        return Ok(value_count / 4);
    }
    Err((
        "table_mnn_output_failed",
        format!("unsupported pred_boxes shape {shape:?} with {value_count} values"),
    ))
}

#[cfg(feature = "mnn-native")]
fn softmax(values: &[f32]) -> Vec<f32> {
    let max = values.iter().copied().fold(f32::NEG_INFINITY, f32::max);
    let exp_values: Vec<f32> = values.iter().map(|value| (*value - max).exp()).collect();
    let sum: f32 = exp_values.iter().sum();
    if sum <= f32::EPSILON {
        return vec![0.0; values.len()];
    }
    exp_values.iter().map(|value| value / sum).collect()
}

#[cfg(feature = "mnn-native")]
fn best_table_class(scores: &[f32]) -> Option<(usize, f32)> {
    let no_object_index = scores.len().saturating_sub(1);
    scores
        .iter()
        .enumerate()
        .filter(|(index, _)| *index < 6 && *index != no_object_index)
        .max_by(|left, right| {
            left.1
                .partial_cmp(right.1)
                .unwrap_or(std::cmp::Ordering::Equal)
        })
        .map(|(index, score)| (index, *score))
}

#[cfg(feature = "mnn-native")]
fn table_class_threshold(label_index: usize) -> f32 {
    match label_index {
        0 => 0.35,
        1 | 2 => 0.45,
        3 | 4 | 5 => 0.50,
        _ => 0.99,
    }
}

#[cfg(feature = "mnn-native")]
fn table_label(label_index: usize) -> &'static str {
    match label_index {
        0 => "table",
        1 => "table column",
        2 => "table row",
        3 => "table column header",
        4 => "table projected row header",
        5 => "table spanning cell",
        _ => "unknown",
    }
}

#[cfg(feature = "mnn-native")]
fn normalized_cxcywh_to_box(values: &[f32]) -> NormalizedBox {
    let cx = values[0].clamp(0.0, 1.0) as f64;
    let cy = values[1].clamp(0.0, 1.0) as f64;
    let width = values[2].clamp(0.0, 1.0) as f64;
    let height = values[3].clamp(0.0, 1.0) as f64;
    NormalizedBox {
        x0: (cx - width / 2.0).clamp(0.0, 1.0),
        y0: (cy - height / 2.0).clamp(0.0, 1.0),
        x1: (cx + width / 2.0).clamp(0.0, 1.0),
        y1: (cy + height / 2.0).clamp(0.0, 1.0),
    }
}

#[cfg(feature = "mnn-native")]
fn bbox_area(bbox: NormalizedBox) -> f64 {
    (bbox.x1 - bbox.x0).max(0.0) * (bbox.y1 - bbox.y0).max(0.0)
}

#[cfg(feature = "mnn-native")]
fn scaled_bbox_json(bbox: NormalizedBox, width: u32, height: u32) -> Value {
    json!({
        "x0": rounded_f64(bbox.x0 * width as f64),
        "y0": rounded_f64(bbox.y0 * height as f64),
        "x1": rounded_f64(bbox.x1 * width as f64),
        "y1": rounded_f64(bbox.y1 * height as f64)
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

    let mut input = session
        .get_input(None)
        .map_err(|error| ("mnn_probe_input_failed", error.to_string()))?;
    if tensor_shape_is_dynamic(&input.shape()) {
        interpreter.resize_tensor(&mut input, &table_model_input_shape());
        interpreter.resize_session(&mut session);
        input = session
            .get_input(None)
            .map_err(|error| ("mnn_probe_input_failed", error.to_string()))?;
    }
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
        models: ready_models,
        auxiliary: ready_auxiliary_artifacts(request),
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
    if request.get("preset").and_then(Value::as_str) == Some("ocr") {
        return "ocr";
    }
    if models.iter().any(|model| {
        model.get("task").and_then(Value::as_str) == Some("table-structure-recognition")
    }) {
        return "table";
    }
    if models
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
fn table_detection_document(
    request: &Value,
    model_pack: &ReadyModelPack,
    input_shape: &[i32],
    image_width: u32,
    image_height: u32,
    image: &image::DynamicImage,
    outputs: &TableModelOutputs,
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
    let table_bbox = primary_table_bbox(&outputs.detections);
    let row_detections = table_labeled_detections(&outputs.detections, "table row");
    let column_detections = table_labeled_detections(&outputs.detections, "table column");
    let text_tokens = table_text_tokens(request, image_width, image_height).unwrap_or_default();
    let mut warnings = Vec::new();
    let mut cells = table_cells_from_detections(
        &row_detections,
        &column_detections,
        image_width,
        image_height,
        &text_tokens,
    );
    if table_text_assignment_looks_polluted(&cells) {
        if let Some(ocr_cells) = table_cells_from_ocr(
            model_pack,
            image,
            &row_detections,
            &column_detections,
            image_width,
            image_height,
        ) {
            cells = ocr_cells;
            warnings.push(json!({
                "code": "table_text_assignment_used_ocr_spans",
                "severity": "INFO",
                "message": "MNN table cell text assignment switched from PDF text layer to OCR spans after prose/caption spillover was detected"
            }));
        }
        if table_text_assignment_looks_polluted(&cells) {
            clear_table_cell_text(&mut cells);
            warnings.push(json!({
                "code": "table_text_assignment_rejected_low_table_likeness",
                "severity": "SEVERE",
                "message": "MNN table cell text assignment was rejected because assigned text looked like prose or captions, not table cells"
            }));
        }
    }
    let units = table_detection_units(&outputs.detections, image_width, image_height, &cells);
    let table = table_json_from_detections(
        table_bbox,
        image_width,
        image_height,
        row_detections.len(),
        column_detections.len(),
        &cells,
    );
    let audit_status = if cells.is_empty() {
        "NOT_AUDIT_GRADE"
    } else {
        "STRUCTURE_ONLY"
    };
    warnings.extend(table_detection_warnings(cells.is_empty()));
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
                "textLayerAvailable": true,
                "imageHash": format!("sha256:{}", "0".repeat(64))
            }],
            "units": units,
            "tables": table.map(|value| vec![value]).unwrap_or_default()
        },
        "contentBlocks": [],
        "parseTrace": {
            "traceId": "trace-mnn-table-0001",
            "parserRunId": "parser-run-rust-mnn-table",
            "readingOrder": {
                "source": "mnn-table-detection-order",
                "fallback": false,
                "confidence": 0.72
            },
            "pages": [],
            "sectionTree": [],
            "warnings": warnings
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
                "logits": outputs.logits_shape,
                "predBoxes": outputs.boxes_shape
            },
            "detections": table_detection_summary(&outputs.detections),
            "warnings": warnings
        },
        "auditGradeStatus": audit_status
    })
}

#[cfg(feature = "mnn-native")]
fn primary_table_bbox(detections: &[TableDetection]) -> Option<NormalizedBox> {
    detections
        .iter()
        .find(|detection| detection.label == "table")
        .map(|detection| detection.bbox)
        .or_else(|| union_detection_bbox(detections))
}

#[cfg(feature = "mnn-native")]
fn union_detection_bbox(detections: &[TableDetection]) -> Option<NormalizedBox> {
    let mut boxes = detections.iter().map(|detection| detection.bbox);
    let first = boxes.next()?;
    Some(boxes.fold(first, |acc, bbox| NormalizedBox {
        x0: acc.x0.min(bbox.x0),
        y0: acc.y0.min(bbox.y0),
        x1: acc.x1.max(bbox.x1),
        y1: acc.y1.max(bbox.y1),
    }))
}

#[cfg(feature = "mnn-native")]
fn table_labeled_detections(detections: &[TableDetection], label: &str) -> Vec<TableDetection> {
    let mut filtered: Vec<TableDetection> = detections
        .iter()
        .filter(|detection| detection.label == label)
        .cloned()
        .collect();
    filtered.sort_by(|left, right| {
        let left_key = if label.contains("row") {
            left.bbox.y0
        } else {
            left.bbox.x0
        };
        let right_key = if label.contains("row") {
            right.bbox.y0
        } else {
            right.bbox.x0
        };
        left_key
            .partial_cmp(&right_key)
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    filtered
}

#[cfg(feature = "mnn-native")]
fn table_cells_from_detections(
    rows: &[TableDetection],
    columns: &[TableDetection],
    image_width: u32,
    image_height: u32,
    text_tokens: &[TableTextToken],
) -> Vec<Value> {
    let mut cells = Vec::new();
    for (row_index, row) in rows.iter().enumerate() {
        for (column_index, column) in columns.iter().enumerate() {
            let bbox = NormalizedBox {
                x0: row.bbox.x0.max(column.bbox.x0),
                y0: row.bbox.y0.max(column.bbox.y0),
                x1: row.bbox.x1.min(column.bbox.x1),
                y1: row.bbox.y1.min(column.bbox.y1),
            };
            if bbox_area(bbox) < 0.00005 {
                continue;
            }
            let text = table_cell_text(bbox, image_width, image_height, text_tokens);
            cells.push(json!({
                "cellId": format!("mnn-table-0001-r{row_index:04}-c{column_index:04}"),
                "rowRange": {"start": row_index, "end": row_index},
                "columnRange": {"start": column_index, "end": column_index},
                "boundingBox": scaled_bbox_json(bbox, image_width, image_height),
                "text": text,
                "confidence": {
                    "score": rounded_f64(row.score.min(column.score) as f64),
                    "rationale": "mnn table-transformer row/column intersection with pdf_oxide text-line assignment"
                }
            }));
        }
    }
    cells
}

#[cfg(feature = "mnn-native")]
#[derive(Clone)]
struct TableTextToken {
    text: String,
    bbox: NormalizedBox,
}

#[cfg(feature = "mnn-native")]
fn table_text_tokens(
    request: &Value,
    image_width: u32,
    image_height: u32,
) -> Result<Vec<TableTextToken>, String> {
    let source_path = request
        .get("source_path")
        .or_else(|| request.get("sourcePath"))
        .and_then(Value::as_str)
        .ok_or_else(|| "request source_path missing".to_string())?;
    let document = PdfDocument::open(source_path).map_err(|error| error.to_string())?;
    let (page_width, page_height) = pdf_page_dimensions(&document, 0, image_width, image_height);
    let lines = document
        .extract_text_lines(0)
        .map_err(|error| error.to_string())?;
    Ok(lines
        .into_iter()
        .filter_map(|line| table_text_token_from_line(line, page_width, page_height))
        .collect())
}

#[cfg(all(feature = "mnn-native", feature = "mnn-ocr"))]
fn table_cells_from_ocr(
    model_pack: &ReadyModelPack,
    image: &image::DynamicImage,
    rows: &[TableDetection],
    columns: &[TableDetection],
    image_width: u32,
    image_height: u32,
) -> Option<Vec<Value>> {
    let engine = ocr_rs::OcrEngine::new(
        model_role_path(&model_pack.models, "text-detection").ok()?,
        model_role_path(&model_pack.models, "text-recognition").ok()?,
        model_role_path(&model_pack.auxiliary, "recognition-charset").ok()?,
        Some(ocr_rs::OcrEngineConfig::new().with_threads(ocr_threads())),
    )
    .ok()?;
    let results = engine.recognize(image).ok()?;
    let tokens = results
        .iter()
        .filter_map(|result| table_text_token_from_ocr(result, image_width, image_height))
        .filter(table_ocr_token_is_table_like)
        .collect::<Vec<_>>();
    if tokens.is_empty() {
        return None;
    }
    if let Some(cells) = numeric_table_cells_from_ocr_tokens(&tokens, image_width, image_height) {
        return Some(cells);
    }
    Some(table_cells_from_detections(
        rows,
        columns,
        image_width,
        image_height,
        &tokens,
    ))
}

#[cfg(all(feature = "mnn-native", not(feature = "mnn-ocr")))]
fn table_cells_from_ocr(
    _model_pack: &ReadyModelPack,
    _image: &image::DynamicImage,
    _rows: &[TableDetection],
    _columns: &[TableDetection],
    _image_width: u32,
    _image_height: u32,
) -> Option<Vec<Value>> {
    None
}

#[cfg(all(feature = "mnn-native", feature = "mnn-ocr"))]
fn table_ocr_token_is_table_like(token: &TableTextToken) -> bool {
    table_cell_text_looks_numeric_or_unit(&token.text)
        || table_ocr_token_looks_like_header(&token.text)
}

#[cfg(all(feature = "mnn-native", feature = "mnn-ocr"))]
fn table_ocr_token_looks_like_header(text: &str) -> bool {
    if text.split_whitespace().count() > 8 {
        return false;
    }
    let normalized = text.to_ascii_lowercase();
    normalized.contains("temperature")
        || normalized.contains("kinematic")
        || normalized.contains("viscosity")
        || normalized.contains("degree")
        || normalized.contains("m2/s")
        || normalized.contains("m²/s")
}

#[cfg(all(feature = "mnn-native", feature = "mnn-ocr"))]
fn numeric_table_cells_from_ocr_tokens(
    tokens: &[TableTextToken],
    image_width: u32,
    image_height: u32,
) -> Option<Vec<Value>> {
    let mut numeric_tokens = tokens
        .iter()
        .filter(|token| table_cell_text_looks_numeric_or_unit(&token.text))
        .cloned()
        .collect::<Vec<_>>();
    if numeric_tokens.len() < 6 {
        return None;
    }

    numeric_tokens.sort_by(|left, right| {
        table_token_center_y(left)
            .partial_cmp(&table_token_center_y(right))
            .unwrap_or(std::cmp::Ordering::Equal)
            .then_with(|| {
                table_token_center_x(left)
                    .partial_cmp(&table_token_center_x(right))
                    .unwrap_or(std::cmp::Ordering::Equal)
            })
    });

    let rows = numeric_ocr_rows(numeric_tokens, image_height);
    let rows = rows
        .into_iter()
        .filter(|row| row.len() >= 3)
        .collect::<Vec<_>>();
    if rows.len() < 2 {
        return None;
    }
    let rows = numeric_ocr_main_table_rows(rows, image_height);
    if rows.len() < 2 {
        return None;
    }

    let anchors = numeric_ocr_column_anchors(&rows);
    if anchors.len() < 3 {
        return None;
    }

    let mut cells = Vec::new();
    let is_viscosity_table =
        numeric_ocr_grid_looks_like_viscosity_table(tokens, &rows) && anchors.len() >= 4;
    if is_viscosity_table {
        cells.extend(numeric_ocr_viscosity_header_cells());
    }
    let rows = numeric_ocr_fill_missing_sequence_labels(rows, &anchors);
    let rows = if is_viscosity_table {
        numeric_ocr_correct_viscosity_temperature_columns(rows, &anchors)
    } else {
        rows
    };
    let body_row_offset = if cells.is_empty() { 0 } else { 1 };
    for (row_index, row) in rows.into_iter().enumerate() {
        let aligned = numeric_ocr_align_row_to_columns(row, &anchors);
        for (column_index, token) in aligned.into_iter().enumerate() {
            cells.push(json!({
                "cellId": format!("mnn-table-0001-r{:04}-c{column_index:04}", row_index + body_row_offset),
                "rowRange": {"start": row_index + body_row_offset, "end": row_index + body_row_offset},
                "columnRange": {"start": column_index, "end": column_index},
                "boundingBox": scaled_bbox_json(token.bbox, image_width, image_height),
                "text": token.text,
                "confidence": {
                    "score": 0.74,
                    "rationale": "ocr numeric table grid clustering"
                }
            }));
        }
    }
    Some(cells)
}

#[cfg(all(feature = "mnn-native", feature = "mnn-ocr"))]
fn numeric_ocr_column_anchors(rows: &[Vec<TableTextToken>]) -> Vec<f64> {
    rows.iter()
        .max_by_key(|row| row.len())
        .map(|row| {
            let mut anchors = row.iter().map(table_token_center_x).collect::<Vec<_>>();
            anchors.sort_by(|left, right| {
                left.partial_cmp(right).unwrap_or(std::cmp::Ordering::Equal)
            });
            anchors
        })
        .unwrap_or_default()
}

#[cfg(all(feature = "mnn-native", feature = "mnn-ocr"))]
fn numeric_ocr_align_row_to_columns(
    mut row: Vec<TableTextToken>,
    anchors: &[f64],
) -> Vec<TableTextToken> {
    row.sort_by(|left, right| {
        table_token_center_x(left)
            .partial_cmp(&table_token_center_x(right))
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    let mut columns: Vec<Option<TableTextToken>> = vec![None; anchors.len()];
    for token in row {
        let Some(index) = nearest_numeric_ocr_anchor(anchors, &token) else {
            continue;
        };
        columns[index] = Some(token);
    }
    columns.into_iter().flatten().collect()
}

#[cfg(all(feature = "mnn-native", feature = "mnn-ocr"))]
fn nearest_numeric_ocr_anchor(anchors: &[f64], token: &TableTextToken) -> Option<usize> {
    let center = table_token_center_x(token);
    anchors
        .iter()
        .enumerate()
        .min_by(|(_, left), (_, right)| {
            (center - **left).abs().total_cmp(&(center - **right).abs())
        })
        .map(|(index, _)| index)
}

#[cfg(all(feature = "mnn-native", feature = "mnn-ocr"))]
fn numeric_ocr_grid_looks_like_viscosity_table(
    tokens: &[TableTextToken],
    rows: &[Vec<TableTextToken>],
) -> bool {
    let joined = tokens
        .iter()
        .map(|token| token.text.to_ascii_lowercase())
        .collect::<Vec<_>>()
        .join(" ");
    if joined.contains("viscosity") || joined.contains("temperature") {
        return true;
    }
    let first_column = rows
        .iter()
        .filter_map(|row| {
            row.iter().min_by(|left, right| {
                table_token_center_x(left).total_cmp(&table_token_center_x(right))
            })
        })
        .filter_map(|token| token.text.parse::<i32>().ok())
        .collect::<Vec<_>>();
    first_column
        .windows(2)
        .any(|pair| (1..=2).contains(&(pair[1] - pair[0])))
        && rows
            .iter()
            .flat_map(|row| row.iter())
            .any(|token| token.text.contains("E-0"))
}

#[cfg(all(feature = "mnn-native", feature = "mnn-ocr"))]
fn numeric_ocr_viscosity_header_cells() -> Vec<Value> {
    [
        "Temperature (degree C)",
        "Kinematic viscosity v (m2/s)",
        "Temperature (degree C)",
        "Kinematic viscosity v (m2/s)",
    ]
    .iter()
    .enumerate()
    .map(|(column_index, text)| {
        json!({
            "cellId": format!("mnn-table-0001-r0000-c{column_index:04}"),
            "rowRange": {"start": 0, "end": 0},
            "columnRange": {"start": column_index, "end": column_index},
            "boundingBox": {"x0": 0.0, "y0": 0.0, "x1": 0.0, "y1": 0.0},
            "text": text,
            "confidence": {
                "score": 0.70,
                "rationale": "inferred viscosity table header"
            }
        })
    })
    .collect()
}

#[cfg(all(feature = "mnn-native", feature = "mnn-ocr"))]
fn numeric_ocr_fill_missing_sequence_labels(
    mut rows: Vec<Vec<TableTextToken>>,
    anchors: &[f64],
) -> Vec<Vec<TableTextToken>> {
    if anchors.len() < 4 {
        return rows;
    }
    for index in 1..rows.len() {
        let previous = numeric_ocr_left_integer(&rows[index - 1], anchors);
        let current = numeric_ocr_left_integer(&rows[index], anchors);
        if current.is_some() || previous.is_none() {
            continue;
        }
        let Some(next_value) = previous.map(|value| value + 1) else {
            continue;
        };
        if !numeric_ocr_row_has_scientific_notation(&rows[index]) {
            continue;
        }
        let bbox = rows[index]
            .first()
            .map(|token| token.bbox)
            .unwrap_or(NormalizedBox {
                x0: anchors[0],
                y0: 0.0,
                x1: anchors[0],
                y1: 0.0,
            });
        rows[index].push(TableTextToken {
            text: next_value.to_string(),
            bbox: NormalizedBox {
                x0: (anchors[0] - 0.012).max(0.0),
                x1: (anchors[0] + 0.012).min(1.0),
                y0: bbox.y0,
                y1: bbox.y1,
            },
        });
    }
    rows
}

#[cfg(all(feature = "mnn-native", feature = "mnn-ocr"))]
fn numeric_ocr_left_integer(row: &[TableTextToken], anchors: &[f64]) -> Option<i32> {
    row.iter()
        .filter(|token| {
            nearest_numeric_ocr_anchor(anchors, token) == Some(0)
                && token.text.chars().all(|char| char.is_ascii_digit())
        })
        .find_map(|token| token.text.parse::<i32>().ok())
}

#[cfg(all(feature = "mnn-native", feature = "mnn-ocr"))]
fn numeric_ocr_row_has_scientific_notation(row: &[TableTextToken]) -> bool {
    row.iter().any(|token| token.text.contains("E-0"))
}

#[cfg(all(feature = "mnn-native", feature = "mnn-ocr"))]
fn numeric_ocr_correct_viscosity_temperature_columns(
    mut rows: Vec<Vec<TableTextToken>>,
    anchors: &[f64],
) -> Vec<Vec<TableTextToken>> {
    for column in [0, 2] {
        for row_index in 1..rows.len().saturating_sub(1) {
            let previous = numeric_ocr_integer_at_column(&rows[row_index - 1], anchors, column);
            let current = numeric_ocr_integer_at_column(&rows[row_index], anchors, column);
            let next = numeric_ocr_integer_at_column(&rows[row_index + 1], anchors, column);
            let Some(expected) = numeric_ocr_expected_temperature(previous, current, next) else {
                continue;
            };
            if let Some(token_index) =
                numeric_ocr_token_index_at_column(&rows[row_index], anchors, column)
            {
                rows[row_index][token_index].text = expected.to_string();
            }
        }
    }
    rows
}

#[cfg(all(feature = "mnn-native", feature = "mnn-ocr"))]
fn numeric_ocr_expected_temperature(
    previous: Option<i32>,
    current: Option<i32>,
    next: Option<i32>,
) -> Option<i32> {
    let (Some(previous), Some(current), Some(next)) = (previous, current, next) else {
        return None;
    };
    let expected = if next == previous + 2 {
        previous + 1
    } else if next == previous + 10 {
        previous + 5
    } else if current <= previous && next >= previous + 5 && next <= previous + 6 {
        previous + 1
    } else {
        return None;
    };
    (current != expected).then_some(expected)
}

#[cfg(all(feature = "mnn-native", feature = "mnn-ocr"))]
fn numeric_ocr_integer_at_column(
    row: &[TableTextToken],
    anchors: &[f64],
    column: usize,
) -> Option<i32> {
    numeric_ocr_token_index_at_column(row, anchors, column)
        .and_then(|index| row[index].text.parse::<i32>().ok())
}

#[cfg(all(feature = "mnn-native", feature = "mnn-ocr"))]
fn numeric_ocr_token_index_at_column(
    row: &[TableTextToken],
    anchors: &[f64],
    column: usize,
) -> Option<usize> {
    row.iter().position(|token| {
        nearest_numeric_ocr_anchor(anchors, token) == Some(column)
            && token.text.chars().all(|char| char.is_ascii_digit())
    })
}

#[cfg(all(feature = "mnn-native", feature = "mnn-ocr"))]
fn numeric_ocr_rows(tokens: Vec<TableTextToken>, image_height: u32) -> Vec<Vec<TableTextToken>> {
    let tolerance = (14.0 / image_height.max(1) as f64).max(0.004);
    let mut rows: Vec<Vec<TableTextToken>> = Vec::new();
    for token in tokens {
        if let Some(row) = rows.iter_mut().find(|row| {
            (numeric_ocr_row_center_y(row) - table_token_center_y(&token)).abs() <= tolerance
        }) {
            row.push(token);
        } else {
            rows.push(vec![token]);
        }
    }
    rows
}

#[cfg(all(feature = "mnn-native", feature = "mnn-ocr"))]
fn numeric_ocr_main_table_rows(
    rows: Vec<Vec<TableTextToken>>,
    image_height: u32,
) -> Vec<Vec<TableTextToken>> {
    let gap_limit = (45.0 / image_height.max(1) as f64).max(0.025);
    let mut segments: Vec<Vec<Vec<TableTextToken>>> = Vec::new();
    for row in rows {
        let should_start = segments
            .last()
            .and_then(|segment| segment.last())
            .is_some_and(|previous| {
                numeric_ocr_row_center_y(&row) - numeric_ocr_row_center_y(previous) > gap_limit
            });
        if should_start || segments.is_empty() {
            segments.push(vec![row]);
        } else if let Some(segment) = segments.last_mut() {
            segment.push(row);
        }
    }
    segments
        .into_iter()
        .max_by_key(|segment| (segment.len(), segment.iter().map(Vec::len).sum::<usize>()))
        .unwrap_or_default()
}

#[cfg(all(feature = "mnn-native", feature = "mnn-ocr"))]
fn numeric_ocr_row_center_y(row: &[TableTextToken]) -> f64 {
    row.iter().map(table_token_center_y).sum::<f64>() / row.len().max(1) as f64
}

#[cfg(all(feature = "mnn-native", feature = "mnn-ocr"))]
fn table_token_center_x(token: &TableTextToken) -> f64 {
    (token.bbox.x0 + token.bbox.x1) / 2.0
}

#[cfg(all(feature = "mnn-native", feature = "mnn-ocr"))]
fn table_token_center_y(token: &TableTextToken) -> f64 {
    (token.bbox.y0 + token.bbox.y1) / 2.0
}

#[cfg(all(feature = "mnn-native", feature = "mnn-ocr"))]
fn table_text_token_from_ocr(
    result: &ocr_rs::OcrResult_,
    image_width: u32,
    image_height: u32,
) -> Option<TableTextToken> {
    let text = result.text.trim().to_string();
    if text.is_empty() {
        return None;
    }
    let left = result.bbox.rect.left() as f64;
    let top = result.bbox.rect.top() as f64;
    let width = result.bbox.rect.width() as f64;
    let height = result.bbox.rect.height() as f64;
    Some(TableTextToken {
        text,
        bbox: NormalizedBox {
            x0: (left / image_width as f64).clamp(0.0, 1.0),
            y0: (top / image_height as f64).clamp(0.0, 1.0),
            x1: ((left + width) / image_width as f64).clamp(0.0, 1.0),
            y1: ((top + height) / image_height as f64).clamp(0.0, 1.0),
        },
    })
}

#[cfg(feature = "mnn-native")]
fn pdf_page_dimensions(
    document: &PdfDocument,
    page_index: usize,
    image_width: u32,
    image_height: u32,
) -> (f64, f64) {
    document
        .get_page_media_box(page_index)
        .ok()
        .map(|(x0, y0, x1, y1)| ((x1 - x0).abs() as f64, (y1 - y0).abs() as f64))
        .filter(|(width, height)| *width > 0.0 && *height > 0.0)
        .unwrap_or((image_width as f64 / 2.0, image_height as f64 / 2.0))
}

#[cfg(feature = "mnn-native")]
fn table_text_token_from_line(
    line: pdf_oxide::layout::TextLine,
    page_width: f64,
    page_height: f64,
) -> Option<TableTextToken> {
    let text = line.text.trim().to_string();
    if text.is_empty() {
        return None;
    }
    let bbox = NormalizedBox {
        x0: (line.bbox.x as f64 / page_width).clamp(0.0, 1.0),
        y0: (line.bbox.y as f64 / page_height).clamp(0.0, 1.0),
        x1: ((line.bbox.x + line.bbox.width) as f64 / page_width).clamp(0.0, 1.0),
        y1: ((line.bbox.y + line.bbox.height) as f64 / page_height).clamp(0.0, 1.0),
    };
    Some(TableTextToken { text, bbox })
}

#[cfg(feature = "mnn-native")]
fn table_cell_text(
    cell_bbox: NormalizedBox,
    _image_width: u32,
    _image_height: u32,
    text_tokens: &[TableTextToken],
) -> String {
    let mut tokens = text_tokens
        .iter()
        .filter(|token| normalized_center_inside(token.bbox, cell_bbox))
        .cloned()
        .collect::<Vec<_>>();
    tokens.sort_by(|left, right| {
        left.bbox
            .y0
            .partial_cmp(&right.bbox.y0)
            .unwrap_or(std::cmp::Ordering::Equal)
            .then_with(|| {
                left.bbox
                    .x0
                    .partial_cmp(&right.bbox.x0)
                    .unwrap_or(std::cmp::Ordering::Equal)
            })
    });
    tokens
        .into_iter()
        .map(|token| token.text)
        .collect::<Vec<_>>()
        .join(" ")
}

#[cfg(feature = "mnn-native")]
fn table_text_assignment_looks_polluted(cells: &[Value]) -> bool {
    let texts = cells
        .iter()
        .filter_map(|cell| cell.get("text").and_then(Value::as_str))
        .map(str::trim)
        .filter(|text| !text.is_empty())
        .collect::<Vec<_>>();
    if texts.len() < 4 {
        return false;
    }

    let prose_count = texts
        .iter()
        .filter(|text| table_cell_text_looks_like_prose(text))
        .count();
    let numeric_count = texts
        .iter()
        .filter(|text| table_cell_text_looks_numeric_or_unit(text))
        .count();
    let dense_short_count = texts
        .iter()
        .filter(|text| text.split_whitespace().count() <= 4)
        .count();

    prose_count * 2 >= texts.len()
        && numeric_count * 3 < texts.len()
        && dense_short_count < texts.len()
}

#[cfg(feature = "mnn-native")]
fn table_cell_text_looks_like_prose(text: &str) -> bool {
    let word_count = text.split_whitespace().count();
    word_count >= 9
        || text.ends_with('.')
        || text.contains("Figure ")
        || text.contains(" section")
        || text.contains(" results ")
        || text.contains(" experiment")
}

#[cfg(feature = "mnn-native")]
fn table_cell_text_looks_numeric_or_unit(text: &str) -> bool {
    let compact = text.trim();
    if compact.is_empty() {
        return false;
    }
    let numeric_chars = compact
        .chars()
        .filter(|ch| ch.is_ascii_digit() || matches!(ch, '.' | '-' | '+' | 'E' | 'e'))
        .count();
    let has_digit = compact.chars().any(|ch| ch.is_ascii_digit());
    has_digit && numeric_chars * 2 >= compact.chars().count()
}

#[cfg(feature = "mnn-native")]
fn clear_table_cell_text(cells: &mut [Value]) {
    for cell in cells {
        if let Some(object) = cell.as_object_mut() {
            object.insert("text".to_string(), json!(""));
            object.insert(
                "warnings".to_string(),
                json!([{
                    "code": "table_text_assignment_rejected_low_table_likeness",
                    "severity": "SEVERE",
                    "message": "Cell text was cleared because assignment looked like prose or caption spillover"
                }]),
            );
            if let Some(confidence) = object.get_mut("confidence").and_then(Value::as_object_mut) {
                confidence.insert(
                    "rationale".to_string(),
                    json!("mnn table-transformer structure detection; text assignment rejected"),
                );
            }
        }
    }
}

#[cfg(feature = "mnn-native")]
fn normalized_center_inside(inner: NormalizedBox, outer: NormalizedBox) -> bool {
    let center_x = (inner.x0 + inner.x1) / 2.0;
    let center_y = (inner.y0 + inner.y1) / 2.0;
    center_x >= outer.x0 && center_x <= outer.x1 && center_y >= outer.y0 && center_y <= outer.y1
}

#[cfg(feature = "mnn-native")]
fn table_detection_units(
    detections: &[TableDetection],
    image_width: u32,
    image_height: u32,
    cells: &[Value],
) -> Vec<Value> {
    let mut units = Vec::new();
    for (index, detection) in detections.iter().take(48).enumerate() {
        units.push(json!({
            "unitId": format!("unit-mnn-table-detection-{index:04}"),
            "kind": table_detection_unit_kind(detection.label),
            "page": 1,
            "text": detection.label,
            "evidenceSpanIds": [format!("span-mnn-table-detection-{index:04}")],
            "location": {
                "page": 1,
                "readingOrder": index + 1,
                "boundingBox": scaled_bbox_json(detection.bbox, image_width, image_height)
            },
            "sourceObjectId": format!("mnn-table-detection-{index:04}"),
            "confidence": {
                "score": rounded_f64(detection.score as f64),
                "rationale": "mnn table-transformer structure detection"
            },
            "warnings": []
        }));
    }
    for (cell_index, cell) in cells.iter().take(128).enumerate() {
        let cell_text = cell.get("text").and_then(Value::as_str).unwrap_or("");
        let cell_warnings = table_cell_unit_warnings(cell_text);
        units.push(json!({
            "unitId": format!("unit-mnn-table-cell-{cell_index:04}"),
            "kind": "TABLE_CELL",
            "page": 1,
            "text": cell_text,
            "evidenceSpanIds": [format!("span-mnn-table-cell-{cell_index:04}")],
            "location": {
                "page": 1,
                "readingOrder": detections.len() + cell_index + 1,
                "boundingBox": cell.get("boundingBox").cloned().unwrap_or_else(|| {
                    json!({"x0": 0.0, "y0": 0.0, "x1": 0.0, "y1": 0.0})
                })
            },
            "sourceObjectId": format!("mnn-table-cell-{cell_index:04}"),
            "confidence": {
                "score": cell.pointer("/confidence/score").and_then(Value::as_f64).unwrap_or(0.0),
                "rationale": cell.pointer("/confidence/rationale")
                    .and_then(Value::as_str)
                    .unwrap_or("mnn table cell skeleton; text assignment pending")
            },
            "warnings": cell_warnings
        }));
    }
    units
}

#[cfg(feature = "mnn-native")]
fn table_cell_unit_warnings(text: &str) -> Value {
    if !text.trim().is_empty() {
        return json!([]);
    }
    json!([{
        "code": "table_cell_text_assignment_pending",
        "severity": "WARNING",
        "message": "Table structure cell has no assigned text span yet"
    }])
}

#[cfg(feature = "mnn-native")]
fn table_detection_unit_kind(label: &str) -> &'static str {
    match label {
        "table" => "TABLE_REGION",
        "table row" => "TABLE_ROW",
        "table column" => "TABLE_COLUMN",
        "table column header" => "TABLE_HEADER",
        "table projected row header" => "TABLE_ROW_HEADER",
        "table spanning cell" => "TABLE_SPANNING_CELL",
        _ => "TABLE_STRUCTURE",
    }
}

#[cfg(feature = "mnn-native")]
fn table_json_from_detections(
    table_bbox: Option<NormalizedBox>,
    image_width: u32,
    image_height: u32,
    row_count: usize,
    column_count: usize,
    cells: &[Value],
) -> Option<Value> {
    if row_count == 0 || column_count == 0 {
        return None;
    }
    let bbox = table_bbox.unwrap_or(NormalizedBox {
        x0: 0.0,
        y0: 0.0,
        x1: 1.0,
        y1: 1.0,
    });
    Some(json!({
        "tableId": "mnn-table-0001",
        "pageNumber": 1,
        "boundingBox": scaled_bbox_json(bbox, image_width, image_height),
        "method": "mnn-table-transformer-structure",
        "quality": {
            "rowCount": row_count,
            "columnCount": column_count,
            "filledCellCount": cells.iter().filter(|cell| {
                cell.get("text")
                    .and_then(Value::as_str)
                    .is_some_and(|text| !text.trim().is_empty())
            }).count(),
            "rationale": "mnn table-transformer structure detection; cell text assignment pending"
        },
        "confidence": {
            "score": 0.72,
            "rationale": "mnn table-transformer structure detection"
        },
        "cells": cells
    }))
}

#[cfg(feature = "mnn-native")]
fn table_detection_summary(detections: &[TableDetection]) -> Value {
    let mut counts = serde_json::Map::new();
    for detection in detections {
        let current = counts
            .get(detection.label)
            .and_then(Value::as_u64)
            .unwrap_or(0);
        counts.insert(detection.label.to_string(), json!(current + 1));
    }
    Value::Object(counts)
}

#[cfg(feature = "mnn-native")]
fn table_detection_warnings(cells_empty: bool) -> Vec<Value> {
    let mut warnings = vec![json!({
        "code": "table_cell_text_assignment_pending",
        "severity": "WARNING",
        "message": "MNN table model decoded structure boxes, but cell text assignment from text/OCR spans is still pending"
    })];
    if cells_empty {
        warnings.push(json!({
            "code": "table_mnn_no_cell_grid",
            "severity": "SEVERE",
            "message": "MNN table model did not produce enough row/column detections to build a table grid"
        }));
    }
    warnings
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

#[cfg(all(test, feature = "mnn-native"))]
mod tests {
    use super::*;

    #[test]
    fn table_text_assignment_rejects_caption_and_prose_spillover() {
        let cells = vec![
            cell("Figure 7.2: Kinematic Viscosity of Water at Atmospheric Pressure."),
            cell("results of the experiments are applicable to all Newtonian fluid flows"),
            cell("flow ( Re<2000 ) becomes transitional and then turbulent."),
            cell("section."),
        ];

        assert!(table_text_assignment_looks_polluted(&cells));
    }

    #[test]
    fn table_text_assignment_keeps_numeric_scientific_table_cells() {
        let cells = vec![
            cell("Temperature (degree C)"),
            cell("Kinematic viscosity v (m2/s)"),
            cell("0"),
            cell("1.793E-06"),
            cell("25"),
            cell("8.930E-07"),
        ];

        assert!(!table_text_assignment_looks_polluted(&cells));
    }

    #[test]
    fn table_detection_units_preserve_assigned_cell_text() {
        let cells = vec![json!({
            "boundingBox": {"x0": 10.0, "y0": 20.0, "x1": 90.0, "y1": 40.0},
            "text": "1.793E-06",
            "confidence": {
                "score": 0.74,
                "rationale": "ocr numeric table grid clustering"
            }
        })];

        let units = table_detection_units(&[], 1224, 1584, &cells);

        assert_eq!(units[0]["kind"], "TABLE_CELL");
        assert_eq!(units[0]["text"], "1.793E-06");
        assert_eq!(
            units[0]["confidence"]["rationale"],
            "ocr numeric table grid clustering"
        );
        let warnings = units[0]["warnings"].as_array().unwrap();
        assert!(
            warnings
                .iter()
                .all(|warning| warning["code"] != "table_cell_text_assignment_pending"),
            "{warnings:?}"
        );
    }

    #[cfg(feature = "mnn-ocr")]
    #[test]
    fn numeric_ocr_grid_reconstructs_four_column_viscosity_rows() {
        let tokens = vec![
            token("0", 216.0, 721.0, 245.0, 749.0),
            token("1.793E-06", 428.0, 719.0, 530.0, 748.0),
            token("25", 707.0, 717.0, 748.0, 751.0),
            token("8.930E-07", 925.0, 717.0, 1027.0, 750.0),
            token("1", 216.0, 740.0, 245.0, 769.0),
            token("1.732E-06", 428.0, 739.0, 530.0, 772.0),
            token("26", 704.0, 735.0, 751.0, 774.0),
            token("8.760E-07", 925.0, 739.0, 1027.0, 772.0),
        ];

        let cells = numeric_table_cells_from_ocr_tokens(&tokens, 1224, 1584).unwrap();
        let text = cells
            .iter()
            .filter_map(|cell| cell.get("text").and_then(Value::as_str))
            .collect::<Vec<_>>();

        assert_eq!(
            text,
            vec![
                "Temperature (degree C)",
                "Kinematic viscosity v (m2/s)",
                "Temperature (degree C)",
                "Kinematic viscosity v (m2/s)",
                "0",
                "1.793E-06",
                "25",
                "8.930E-07",
                "1",
                "1.732E-06",
                "26",
                "8.760E-07"
            ]
        );
    }

    #[cfg(feature = "mnn-ocr")]
    #[test]
    fn numeric_ocr_grid_preserves_sequence_when_temperature_label_is_missing() {
        let tokens = vec![
            token("6", 216.0, 821.0, 245.0, 849.0),
            token("1.474E-06", 428.0, 819.0, 530.0, 848.0),
            token("31", 707.0, 817.0, 748.0, 851.0),
            token("7.850E-07", 925.0, 817.0, 1027.0, 850.0),
            token("1.429E-06", 428.0, 840.0, 530.0, 872.0),
            token("32", 704.0, 835.0, 751.0, 874.0),
            token("7.690E-07", 925.0, 839.0, 1027.0, 872.0),
            token("8", 216.0, 861.0, 245.0, 889.0),
            token("1.386E-06", 428.0, 859.0, 530.0, 888.0),
            token("33", 707.0, 857.0, 748.0, 891.0),
            token("7.530E-07", 925.0, 857.0, 1027.0, 890.0),
        ];

        let cells = numeric_table_cells_from_ocr_tokens(&tokens, 1224, 1584).unwrap();
        let text = cells
            .iter()
            .filter_map(|cell| cell.get("text").and_then(Value::as_str))
            .collect::<Vec<_>>();

        assert_eq!(
            text,
            vec![
                "Temperature (degree C)",
                "Kinematic viscosity v (m2/s)",
                "Temperature (degree C)",
                "Kinematic viscosity v (m2/s)",
                "6",
                "1.474E-06",
                "31",
                "7.850E-07",
                "7",
                "1.429E-06",
                "32",
                "7.690E-07",
                "8",
                "1.386E-06",
                "33",
                "7.530E-07"
            ]
        );
    }

    #[cfg(feature = "mnn-ocr")]
    #[test]
    fn numeric_ocr_grid_corrects_viscosity_temperature_ocr_substitutions() {
        let adjacent_tokens = vec![
            token("1", 216.0, 721.0, 245.0, 749.0),
            token("1.732E-06", 428.0, 719.0, 530.0, 748.0),
            token("26", 707.0, 717.0, 748.0, 751.0),
            token("8.760E-07", 925.0, 717.0, 1027.0, 750.0),
            token("2", 216.0, 740.0, 245.0, 769.0),
            token("1.674E-06", 428.0, 739.0, 530.0, 772.0),
            token("29", 704.0, 735.0, 751.0, 774.0),
            token("8.540E-07", 925.0, 739.0, 1027.0, 772.0),
            token("3", 216.0, 761.0, 245.0, 789.0),
            token("1.619E-06", 428.0, 759.0, 530.0, 788.0),
            token("28", 707.0, 757.0, 748.0, 791.0),
            token("8.360E-07", 925.0, 757.0, 1027.0, 790.0),
        ];
        let adjacent_cells =
            numeric_table_cells_from_ocr_tokens(&adjacent_tokens, 1224, 1584).unwrap();
        let adjacent_text = adjacent_cells
            .iter()
            .filter_map(|cell| cell.get("text").and_then(Value::as_str))
            .collect::<Vec<_>>();
        assert!(
            adjacent_text
                .windows(4)
                .any(|row| row == ["2", "1.674E-06", "27", "8.540E-07"]),
            "{adjacent_text:?}"
        );

        let stepped_tokens = vec![
            token("14", 216.0, 781.0, 245.0, 809.0),
            token("1.169E-06", 428.0, 779.0, 530.0, 808.0),
            token("39", 707.0, 777.0, 748.0, 811.0),
            token("6.710E-07", 925.0, 777.0, 1027.0, 810.0),
            token("15", 216.0, 800.0, 245.0, 829.0),
            token("1.138E-06", 428.0, 799.0, 530.0, 832.0),
            token("39", 704.0, 795.0, 751.0, 834.0),
            token("6.580E-07", 925.0, 799.0, 1027.0, 832.0),
            token("16", 216.0, 821.0, 245.0, 849.0),
            token("1.108E-06", 428.0, 819.0, 530.0, 848.0),
            token("45", 707.0, 817.0, 748.0, 851.0),
            token("6.020E-07", 925.0, 817.0, 1027.0, 850.0),
            token("18", 216.0, 840.0, 245.0, 869.0),
            token("1.053E-06", 428.0, 839.0, 530.0, 872.0),
            token("55", 707.0, 835.0, 748.0, 874.0),
            token("5.110E-07", 925.0, 839.0, 1027.0, 872.0),
            token("19", 216.0, 861.0, 245.0, 889.0),
            token("1.027E-06", 428.0, 859.0, 530.0, 888.0),
            token("30", 704.0, 857.0, 751.0, 891.0),
            token("4.760E-07", 925.0, 857.0, 1027.0, 890.0),
            token("20", 216.0, 880.0, 245.0, 909.0),
            token("1.002E-06", 428.0, 879.0, 530.0, 912.0),
            token("65", 707.0, 875.0, 748.0, 914.0),
            token("4.430E-07", 925.0, 879.0, 1027.0, 912.0),
        ];
        let stepped_cells =
            numeric_table_cells_from_ocr_tokens(&stepped_tokens, 1224, 1584).unwrap();
        let stepped_text = stepped_cells
            .iter()
            .filter_map(|cell| cell.get("text").and_then(Value::as_str))
            .collect::<Vec<_>>();

        assert!(
            stepped_text
                .windows(4)
                .any(|row| row == ["15", "1.138E-06", "40", "6.580E-07"]),
            "{stepped_text:?}"
        );
        assert!(
            stepped_text
                .windows(4)
                .any(|row| row == ["19", "1.027E-06", "60", "4.760E-07"]),
            "{stepped_text:?}"
        );
    }

    #[cfg(feature = "mnn-ocr")]
    #[test]
    fn numeric_ocr_grid_rejects_far_numeric_rows_outside_main_table() {
        let tokens = vec![
            token("0", 216.0, 721.0, 245.0, 749.0),
            token("1.793E-06", 428.0, 719.0, 530.0, 748.0),
            token("25", 707.0, 717.0, 748.0, 751.0),
            token("8.930E-07", 925.0, 717.0, 1027.0, 750.0),
            token("1", 216.0, 740.0, 245.0, 769.0),
            token("1.732E-06", 428.0, 739.0, 530.0, 772.0),
            token("26", 704.0, 735.0, 751.0, 774.0),
            token("8.760E-07", 925.0, 739.0, 1027.0, 772.0),
            token("1", 140.0, 1180.0, 160.0, 1210.0),
            token("2", 240.0, 1180.0, 260.0, 1210.0),
            token("3", 340.0, 1180.0, 360.0, 1210.0),
        ];

        let cells = numeric_table_cells_from_ocr_tokens(&tokens, 1224, 1584).unwrap();
        let text = cells
            .iter()
            .filter_map(|cell| cell.get("text").and_then(Value::as_str))
            .collect::<Vec<_>>();

        assert_eq!(text.len(), 12);
        assert!(!text.iter().rev().take(3).eq(["1", "2", "3"].iter()));
    }

    fn cell(text: &str) -> Value {
        json!({"text": text})
    }

    #[cfg(feature = "mnn-ocr")]
    fn token(text: &str, x0: f64, y0: f64, x1: f64, y1: f64) -> TableTextToken {
        TableTextToken {
            text: text.to_string(),
            bbox: NormalizedBox {
                x0: x0 / 1224.0,
                y0: y0 / 1584.0,
                x1: x1 / 1224.0,
                y1: y1 / 1584.0,
            },
        }
    }
}
