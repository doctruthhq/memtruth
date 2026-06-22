use assert_cmd::Command;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::fs;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

static TEMP_COUNTER: AtomicU64 = AtomicU64::new(1);

#[test]
fn model_manifest_lists_required_opendataloader_roles() {
    let manifest = read_manifest();
    let models = preset_models(&manifest);

    let layout = models.iter().any(|model| {
        string_field(model, "task") == Some("layout-detection")
            || string_field(model, "role").is_some_and(|role| role.contains("layout"))
    });
    let table_models = models
        .iter()
        .filter(|model| {
            string_field(model, "task") == Some("table-structure-recognition")
                || string_field(model, "role").is_some_and(|role| role.contains("table"))
        })
        .collect::<Vec<_>>();
    let ocr_detection = models
        .iter()
        .any(|model| string_field(model, "role") == Some("text-detection"));
    let ocr_recognition = models
        .iter()
        .any(|model| string_field(model, "role") == Some("text-recognition"));

    assert!(
        layout,
        "manifest must include a layout capability: {manifest}"
    );
    assert!(
        !table_models.is_empty(),
        "manifest must include a table capability: {manifest}"
    );
    assert!(
        ocr_detection,
        "manifest must include OCR detection via role=text-detection or task=ocr with an OCR detection role marker: {manifest}"
    );
    assert!(
        ocr_recognition,
        "manifest must include OCR recognition via role=text-recognition or task=ocr with an OCR recognition role marker: {manifest}"
    );
    for model in table_models {
        assert_eq!(
            string_field(model, "format"),
            Some("mnn"),
            "table runtime must stay on MNN: {model}"
        );
    }
    for model in models.iter().filter(|model| {
        matches!(
            string_field(model, "role"),
            Some("text-detection" | "text-recognition")
        )
    }) {
        assert_eq!(
            string_field(model, "format"),
            Some("mnn"),
            "OCR runtime must stay on MNN: {model}"
        );
    }
}

#[test]
fn doctor_rejects_ready_ocr_charset_as_ocr_capability() {
    let (cache_dir, manifest) = manifest_with_cached_models(
        "doctruth-runtime-ocr-charset",
        vec![cached_model(
            "ppocr-v5-mobile-charset",
            "v0.1.3",
            "ocr",
            "recognition-charset",
            b"charset metadata",
        )],
    );

    let doctor = doctor_with_manifest(&cache_dir, &manifest);
    let ocr = &doctor["capabilities"]["ocr"];

    assert_eq!(doctor["models"]["presets"]["ocr"]["allReady"], true);
    assert_eq!(ocr["available"], false);
    assert_eq!(ocr["textDetection"]["available"], false);
    assert_eq!(ocr["textRecognition"]["available"], false);
    assert_eq!(ocr["models"][0]["cacheStatus"], "READY");
    assert_eq!(ocr["models"][0]["role"], "recognition-charset");
}

#[test]
fn doctor_rejects_ready_ocr_recognition_charset_as_ocr_capability() {
    let (cache_dir, manifest) = manifest_with_cached_models(
        "doctruth-runtime-ocr-recognition-charset",
        vec![cached_model(
            "ppocr-v5-mobile-recognition-charset",
            "v0.1.3",
            "ocr",
            "ocr-recognition-charset",
            b"charset metadata",
        )],
    );

    let doctor = doctor_with_manifest(&cache_dir, &manifest);
    let ocr = &doctor["capabilities"]["ocr"];

    assert_eq!(doctor["models"]["presets"]["ocr"]["allReady"], true);
    assert_eq!(ocr["available"], false);
    assert_eq!(ocr["textDetection"]["available"], false);
    assert_eq!(ocr["textRecognition"]["available"], false);
    assert_eq!(ocr["models"][0]["cacheStatus"], "READY");
    assert_eq!(ocr["models"][0]["role"], "ocr-recognition-charset");
    assert_eq!(ocr["textRecognition"]["models"], json!([]));
}

#[test]
fn doctor_reports_ocr_available_only_when_detection_and_recognition_are_ready() {
    let (cache_dir, manifest) = manifest_with_cached_models(
        "doctruth-runtime-ocr-det-rec",
        vec![
            cached_model(
                "ppocr-v5-mobile-det",
                "v0.1.3",
                "ocr",
                "text-detection",
                b"detector model",
            ),
            cached_model(
                "ppocr-v5-mobile-rec",
                "v0.1.3",
                "ocr",
                "text-recognition",
                b"recognizer model",
            ),
        ],
    );

    let doctor = doctor_with_manifest(&cache_dir, &manifest);
    let ocr = &doctor["capabilities"]["ocr"];

    assert_eq!(doctor["models"]["presets"]["ocr"]["allReady"], true);
    assert_eq!(ocr["available"], true);
    assert_eq!(ocr["textDetection"]["available"], true);
    assert_eq!(ocr["textRecognition"]["available"], true);
    assert_eq!(ocr["textDetection"]["models"][0]["role"], "text-detection");
    assert_eq!(
        ocr["textRecognition"]["models"][0]["role"],
        "text-recognition"
    );
}

#[test]
fn doctor_reports_placeholder_sha_as_blocked_cache_status() {
    let cache_dir = temp_dir("doctruth-runtime-placeholder-sha-cache");
    fs::create_dir_all(&cache_dir).unwrap();
    let bytes = b"placeholder model bytes";
    fs::write(cache_dir.join("ppocr-v5-mobile-det-v0.1.3.bin"), bytes).unwrap();
    let manifest = temp_path("doctruth-runtime-placeholder-sha-manifest", "json");
    fs::write(
        &manifest,
        json!({
            "presets": {
                "ocr": [
                    {
                        "name": "ppocr-v5-mobile-det",
                        "version": "v0.1.3",
                        "sha256": " SHA256:Pending ",
                        "sizeBytes": bytes.len(),
                        "required": true,
                        "task": "ocr",
                        "role": "text-detection",
                        "backend": "mnn",
                        "format": "mnn"
                    }
                ]
            }
        })
        .to_string(),
    )
    .unwrap();

    let doctor = doctor_with_manifest(&cache_dir, &manifest);
    let model = &doctor["models"]["presets"]["ocr"]["models"][0];

    assert_eq!(doctor["models"]["presets"]["ocr"]["allReady"], false);
    assert_eq!(model["cacheStatus"], "PLACEHOLDER_SHA");
    assert_eq!(model["actualSha256"], sha256(bytes));
    assert_eq!(doctor["capabilities"]["ocr"]["available"], false);
    assert_eq!(
        doctor["capabilities"]["ocr"]["textDetection"]["available"],
        false
    );
}

#[test]
fn doctor_with_real_manifest_does_not_synthesize_missing_preset_models() {
    let cache_dir = temp_dir("doctruth-runtime-real-manifest-empty-cache");
    fs::create_dir_all(&cache_dir).unwrap();

    let doctor = doctor_with_manifest(&cache_dir, &manifest_path());
    let standard_models = doctor["models"]["presets"]["standard"]["models"]
        .as_array()
        .unwrap();
    let table_server_models = doctor["models"]["presets"]["table-server"]["models"]
        .as_array()
        .unwrap();
    let layout = &doctor["capabilities"]["layout"];

    assert!(
        standard_models.is_empty(),
        "real manifest has no standard preset; doctor must not synthesize placeholder models: {standard_models:?}"
    );
    assert!(
        table_server_models.is_empty(),
        "real manifest has no table-server preset; doctor must not synthesize placeholder models: {table_server_models:?}"
    );
    assert_eq!(layout["preset"], "layout-server");
    assert_eq!(layout["task"], "layout-detection");
    assert!(
        layout["models"]
            .as_array()
            .is_some_and(|models| models.iter().any(|model| {
                string_field(model, "name") == Some("kreuzberg-rtdetr-layout")
                    && string_field(model, "task") == Some("layout-detection")
            })),
        "layout capability must come from real layout-server manifest models: {layout}"
    );
}

#[test]
fn doctor_reports_runtime_pending_sha_forms_as_placeholder_status() {
    let cache_dir = temp_dir("doctruth-runtime-placeholder-layout-sha-cache");
    fs::create_dir_all(&cache_dir).unwrap();
    let bytes = b"layout placeholder model bytes";
    fs::write(cache_dir.join("layout-rtdetr-v2.bin"), bytes).unwrap();
    let manifest = temp_path("doctruth-runtime-placeholder-layout-sha-manifest", "json");
    fs::write(
        &manifest,
        json!({
            "presets": {
                "layout-server": [
                    {
                        "name": "layout-rtdetr",
                        "version": "v2",
                        "sha256": "sha256:pending-layout-rtdetr-v2",
                        "sizeBytes": bytes.len(),
                        "required": true,
                        "task": "layout-detection",
                        "role": "document-layout-detection",
                        "backend": "mnn",
                        "format": "mnn"
                    }
                ]
            }
        })
        .to_string(),
    )
    .unwrap();

    let doctor = doctor_with_manifest(&cache_dir, &manifest);
    let model = &doctor["models"]["presets"]["layout-server"]["models"][0];

    assert_eq!(
        doctor["models"]["presets"]["layout-server"]["allReady"],
        false
    );
    assert_eq!(model["cacheStatus"], "PLACEHOLDER_SHA");
    assert_eq!(model["actualSha256"], sha256(bytes));
    assert_eq!(doctor["capabilities"]["layout"]["available"], false);
}

#[test]
fn static_placeholder_checksum_helper_detects_pending_suffixes() {
    assert!(placeholder_checksum("sha256:pending-layout-rtdetr-v2"));
}

#[test]
fn parse_pdf_table_server_edge_model_offline_missing_cache_records_blocked_model_runtime() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "parse_pdf",
                "source_path": repo_root().join("third_party/opendataloader-bench/pdfs/01030000000110.pdf"),
                "preset": "table-server",
                "runtime_profile": "edge-model",
                "offline_mode": true,
                "allow_model_downloads": false,
                "model_manifest": manifest_path(),
                "model_cache": "/tmp/nonexistent-doctruth-model-cache"
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let json: Value = serde_json::from_slice(&output).unwrap();
    let routing = &json["parserRun"]["modelRouting"];

    assert_eq!(json["parserRun"]["profile"], "edge-model");
    assert_eq!(json["parserRun"]["preset"], "table-server");
    assert_eq!(routing["mode"], "explicit-preset");
    assert_eq!(routing["requiresModelRuntime"], true);
    assert_eq!(routing["startedModelRuntime"], false);
    assert_eq!(routing["candidateRoutedPages"], json!([1]));
    assert_eq!(routing["routedPages"], json!([]));
    assert_eq!(routing["blockedReason"], "model-runtime-unavailable");
    assert_eq!(
        routing["models"],
        json!([]),
        "configured manifest has no table-server preset; parse routing must not synthesize static required model identities: {json}"
    );
    assert_eq!(
        json["parserRun"]["models"],
        json!([]),
        "configured manifest has no table-server preset; parserRun.models must stay empty instead of leaking static required model identities: {json}"
    );
    assert!(
        !json.to_string().contains("slanext-auto:v1"),
        "parse output must not leak static RequiredModel fake identities when configured manifest lacks table-server: {json}"
    );
    assert!(
        !json.to_string().contains("pending"),
        "parse routing/model output must not leak placeholder checksum text: {json}"
    );
    assert_eq!(json["auditGradeStatus"], "NOT_AUDIT_GRADE");

    let warnings = json["parserRun"]["warnings"].as_array().unwrap();
    assert!(
        warnings.iter().any(|warning| {
            warning["code"] == "model_unavailable_fallback"
                && warning["severity"] == "SEVERE"
                && warning["message"]
                    .as_str()
                    .is_some_and(|message| message.contains("table-server"))
        }),
        "expected blocked model runtime warning for table-server edge-model route: {json}"
    );
}

#[test]
fn parse_pdf_auto_simple_text_with_configured_manifest_stays_audit_grade() {
    let pdf = write_pdf_fixture("Simple OpenDataLoader text stays deterministic.");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "parse_pdf",
                "source_path": pdf,
                "preset": "auto",
                "runtime_profile": "edge-model",
                "offline_mode": true,
                "allow_model_downloads": false,
                "model_manifest": manifest_path(),
                "model_cache": "/tmp/nonexistent-doctruth-model-cache"
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let json: Value = serde_json::from_slice(&output).unwrap();
    let routing = &json["parserRun"]["modelRouting"];
    let warnings = json["parserRun"]["warnings"].as_array().unwrap();

    assert_eq!(json["parserRun"]["preset"], "auto");
    assert_eq!(routing["mode"], "auto");
    assert_eq!(routing["decision"], "deterministic-only");
    assert_eq!(routing["requiresModelRuntime"], false);
    assert_eq!(routing["startedModelRuntime"], false);
    assert_eq!(routing["routedPages"], json!([]));
    assert_eq!(json["auditGradeStatus"], "AUDIT_GRADE");
    assert!(
        !warnings
            .iter()
            .any(|warning| warning["code"] == "model_unavailable_fallback"),
        "deterministic-only auto parse must not emit model fallback warnings: {json}"
    );
}

#[test]
fn parse_pdf_with_explicit_malformed_manifest_returns_model_manifest_invalid() {
    let pdf = write_pdf_fixture("Invalid manifest must not fall back.");
    let manifest = temp_path("doctruth-runtime-malformed-model-manifest", "json");
    fs::write(&manifest, "{not json").unwrap();
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(
            json!({
                "command": "parse_pdf",
                "source_path": pdf,
                "preset": "auto",
                "runtime_profile": "edge-model",
                "offline_mode": true,
                "allow_model_downloads": false,
                "model_manifest": manifest,
                "model_cache": "/tmp/nonexistent-doctruth-model-cache"
            })
            .to_string(),
        )
        .assert()
        .failure()
        .get_output()
        .stderr
        .clone();
    let error: Value = serde_json::from_slice(&output).unwrap();

    assert_eq!(error["error_code"], "MODEL_MANIFEST_INVALID");
    assert!(
        error["message"]
            .as_str()
            .is_some_and(|message| message.contains("malformed-model-manifest")),
        "{error}"
    );
}

#[test]
fn model_manifest_does_not_contain_placeholder_checksums() {
    let manifest = read_manifest();
    let placeholders = checksum_placeholders(&manifest, String::new());

    assert!(
        placeholders.is_empty(),
        "manifest must not contain placeholder checksum values: {placeholders:?}"
    );
}

fn read_manifest() -> Value {
    let manifest = fs::read_to_string(manifest_path()).unwrap();
    serde_json::from_str(&manifest).unwrap()
}

fn doctor_with_manifest(cache_dir: &PathBuf, manifest: &PathBuf) -> Value {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .arg("--doctor")
        .env("DOCTRUTH_MODEL_CACHE", cache_dir)
        .env("DOCTRUTH_MODEL_MANIFEST", manifest)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    serde_json::from_slice(&output).unwrap()
}

fn manifest_with_cached_models(name: &str, models: Vec<Value>) -> (PathBuf, PathBuf) {
    let cache_dir = temp_dir(&format!("{name}-cache"));
    fs::create_dir_all(&cache_dir).unwrap();
    for model in &models {
        let cache_filename = model["cacheFilename"].as_str().unwrap();
        let bytes = model["testBytes"].as_str().unwrap().as_bytes();
        fs::write(cache_dir.join(cache_filename), bytes).unwrap();
    }
    let manifest_models = models
        .into_iter()
        .map(|mut model| {
            model.as_object_mut().unwrap().remove("testBytes");
            model
        })
        .collect::<Vec<_>>();
    let manifest = temp_path(&format!("{name}-manifest"), "json");
    fs::write(
        &manifest,
        json!({
            "presets": {
                "ocr": manifest_models
            }
        })
        .to_string(),
    )
    .unwrap();
    (cache_dir, manifest)
}

fn cached_model(name: &str, version: &str, task: &str, role: &str, bytes: &[u8]) -> Value {
    let cache_filename = format!("{name}-{version}.bin");
    json!({
        "name": name,
        "version": version,
        "sha256": sha256(bytes),
        "sizeBytes": bytes.len(),
        "required": true,
        "task": task,
        "role": role,
        "backend": "mnn",
        "format": "mnn",
        "cacheFilename": cache_filename,
        "testBytes": String::from_utf8(bytes.to_vec()).unwrap()
    })
}

fn write_pdf_fixture(text: &str) -> PathBuf {
    let path = temp_path("doctruth-runtime-opendataloader-fixture", "pdf");
    fs::write(&path, minimal_pdf(text)).unwrap();
    path
}

fn temp_dir(prefix: &str) -> PathBuf {
    std::env::temp_dir().join(unique_name(prefix))
}

fn temp_path(prefix: &str, extension: &str) -> PathBuf {
    std::env::temp_dir().join(format!("{}.{}", unique_name(prefix), extension))
}

fn unique_name(prefix: &str) -> String {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let counter = TEMP_COUNTER.fetch_add(1, Ordering::SeqCst);
    format!("{prefix}-{nanos}-{counter}")
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

fn preset_models(manifest: &Value) -> Vec<&Value> {
    manifest
        .pointer("/presets")
        .and_then(Value::as_object)
        .into_iter()
        .flat_map(|presets| presets.values())
        .flat_map(|preset| preset.as_array().into_iter().flatten())
        .collect()
}

fn checksum_placeholders(value: &Value, path: String) -> Vec<String> {
    match value {
        Value::Object(object) => object
            .iter()
            .flat_map(|(key, value)| {
                checksum_placeholders(value, format!("{path}/{key}")).into_iter()
            })
            .collect(),
        Value::Array(values) => values
            .iter()
            .enumerate()
            .flat_map(|(index, value)| {
                checksum_placeholders(value, format!("{path}/{index}")).into_iter()
            })
            .collect(),
        Value::String(text) if placeholder_checksum(text) => vec![format!("{path}={text}")],
        _ => Vec::new(),
    }
}

fn placeholder_checksum(value: &str) -> bool {
    let normalized = value.trim().to_ascii_lowercase().replace([' ', '_'], "-");
    normalized == "pending"
        || normalized.starts_with("pending-")
        || normalized == "sha256:pending"
        || normalized.starts_with("sha256:pending-")
}

fn string_field<'a>(value: &'a Value, key: &str) -> Option<&'a str> {
    value.get(key).and_then(Value::as_str)
}

fn manifest_path() -> PathBuf {
    repo_root().join("model-packs/opendataloader-hybrid-models.json")
}

fn repo_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..")
}
