use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::env;
use std::fs;
use std::path::PathBuf;
use std::sync::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

static TEMP_FILE_COUNTER: AtomicU64 = AtomicU64::new(1);
static ENV_LOCK: Mutex<()> = Mutex::new(());

#[test]
fn library_api_reports_doctor_readiness_without_spawning_binary() {
    let _lock = ENV_LOCK.lock().unwrap();
    let _guard = EnvGuard::clear_many(&["DOCTRUTH_MODEL_CACHE", "DOCTRUTH_MODEL_MANIFEST"]);
    let doctor = doctruth_runtime::doctor_json();

    assert_eq!(doctor["runtime"], "doctruth-runtime");
    assert_eq!(doctor["protocol_version"], "1");
    assert_eq!(doctor["local_first"], true);
    assert_eq!(doctor["capabilities"]["parse_pdf"], true);
    assert_eq!(doctor["pdfBackend"]["target"], "pdf_oxide");
    assert_eq!(doctor["pdfBackend"]["current"], "pdf_oxide");
    assert_eq!(doctor["pdfBackend"]["status"], "DEFAULT");
    assert_eq!(doctor["models"]["cache"]["directory"], ".doctruth/models");
    assert_eq!(doctor["models"]["worker"]["configured"], false);
    assert_eq!(doctor["models"]["presets"]["lite"]["required"], false);
    assert_eq!(
        doctor["models"]["presets"]["standard"]["models"][0]["identity"],
        "layout-rtdetr:v2"
    );
    assert_eq!(
        doctor["models"]["presets"]["table-lite"]["models"][0]["identity"],
        "slanet-plus:v1"
    );
    assert_eq!(
        doctor["models"]["presets"]["table-server"]["models"][0]["identity"],
        "slanext-auto:v1"
    );
    assert_eq!(
        doctor["models"]["presets"]["ocr"]["models"][0]["identity"],
        "ppocr-v5-mobile-det:v0.1.3"
    );
    assert_eq!(
        doctor["models"]["presets"]["ocr"]["models"][1]["identity"],
        "ppocr-v5-mobile-rec:v0.1.3"
    );
    assert_eq!(doctor["capabilities"]["ocr"]["available"], false);
    assert_eq!(doctor["capabilities"]["tables"]["available"], false);
    assert_eq!(doctor["capabilities"]["layout"]["available"], false);
    assert_eq!(
        doctor["capabilities"]["document_structure"]["available"],
        true
    );
}

#[test]
fn library_api_doctor_verifies_model_manifest_cache_and_sha_status() {
    let _lock = ENV_LOCK.lock().unwrap();
    let cache_dir = temp_dir("doctruth-runtime-doctor-cache");
    fs::create_dir_all(&cache_dir).unwrap();
    let ready_bytes = b"ready model artifact";
    let mismatch_bytes = b"wrong model artifact";
    fs::write(cache_dir.join("layout-rtdetr-v2.bin"), ready_bytes).unwrap();
    fs::write(cache_dir.join("tatr-v1.bin"), mismatch_bytes).unwrap();
    let manifest = temp_path("doctruth-runtime-doctor-manifest", "json");
    fs::write(
        &manifest,
        json!({
            "presets": {
                "standard": [
                    {
                        "name": "layout-rtdetr",
                        "version": "v2",
                        "sha256": sha256(ready_bytes),
                        "sizeBytes": ready_bytes.len(),
                        "required": true,
                        "task": "layout-detection",
                        "backend": "mnn",
                        "format": "mnn"
                    },
                    {
                        "name": "tatr",
                        "version": "v1",
                        "sha256": "sha256:not-the-real-hash",
                        "sizeBytes": mismatch_bytes.len(),
                        "required": true,
                        "task": "table-structure-recognition",
                        "backend": "mnn",
                        "format": "mnn"
                    },
                    {
                        "name": "ppocr-v5-mobile-det",
                        "version": "v0.1.3",
                        "sha256": "sha256:missing",
                        "sizeBytes": 42,
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

    let _guard = EnvGuard::set_many(&[
        ("DOCTRUTH_MODEL_CACHE", cache_dir.to_str().unwrap()),
        ("DOCTRUTH_MODEL_MANIFEST", manifest.to_str().unwrap()),
    ]);
    let doctor = doctruth_runtime::doctor_json();
    let standard = doctor["models"]["presets"]["standard"]["models"]
        .as_array()
        .unwrap();

    assert_eq!(
        doctor["models"]["manifest"]["path"],
        manifest.to_str().unwrap()
    );
    assert_eq!(
        doctor["models"]["cache"]["directory"],
        cache_dir.to_str().unwrap()
    );
    assert_eq!(doctor["models"]["presets"]["standard"]["allReady"], false);
    assert_eq!(standard[0]["cacheStatus"], "READY");
    assert_eq!(standard[0]["actualSha256"], sha256(ready_bytes));
    assert_eq!(standard[0]["actualSizeBytes"], ready_bytes.len());
    assert_eq!(standard[1]["cacheStatus"], "SHA_MISMATCH");
    assert_eq!(standard[1]["actualSha256"], sha256(mismatch_bytes));
    assert_eq!(standard[2]["cacheStatus"], "MISSING");
    assert_eq!(doctor["capabilities"]["layout"]["available"], true);
    assert_eq!(doctor["capabilities"]["tables"]["available"], false);
    assert_eq!(doctor["capabilities"]["ocr"]["available"], false);
}

#[test]
fn library_api_doctor_separates_configured_worker_from_ready_worker() {
    let _lock = ENV_LOCK.lock().unwrap();
    let worker = temp_path("doctruth-runtime-unready-worker", "py");
    fs::write(
        &worker,
        r#"#!/usr/bin/env python3
import json
import sys

if "--doctor" in sys.argv:
    print(json.dumps({
        "ok": False,
        "code": "model_runtime_unavailable",
        "message": "onnxruntime missing",
        "rssMb": 12,
        "peakMemoryMb": 24,
        "loadedModels": []
    }))
    sys.exit(0)
sys.exit(0)
"#,
    )
    .unwrap();
    make_executable(&worker);

    let _guard =
        EnvGuard::set_many(&[("DOCTRUTH_RUNTIME_MODEL_COMMAND", worker.to_str().unwrap())]);
    let doctor = doctruth_runtime::doctor_json();
    let worker_doctor = &doctor["models"]["worker"];

    assert_eq!(doctor["model_execution"], "local-worker");
    assert_eq!(worker_doctor["configured"], true);
    assert_eq!(worker_doctor["available"], true);
    assert_eq!(worker_doctor["ready"], false);
    assert_eq!(worker_doctor["statusCode"], "model_runtime_unavailable");
    assert_eq!(worker_doctor["message"], "onnxruntime missing");
    assert_eq!(worker_doctor["rssMb"], 12);
    assert_eq!(worker_doctor["peakMemoryMb"], 24);
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

fn temp_dir(prefix: &str) -> PathBuf {
    let path = env::temp_dir().join(format!("{prefix}-{}", unique_id()));
    fs::create_dir_all(&path).unwrap();
    path
}

fn temp_path(prefix: &str, extension: &str) -> PathBuf {
    env::temp_dir().join(format!("{prefix}-{}.{}", unique_id(), extension))
}

fn unique_id() -> String {
    let counter = TEMP_FILE_COUNTER.fetch_add(1, Ordering::Relaxed);
    let millis = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_millis();
    format!("{millis}-{counter}")
}

fn sha256(bytes: &[u8]) -> String {
    let mut digest = Sha256::new();
    digest.update(bytes);
    format!("sha256:{:x}", digest.finalize())
}

#[cfg(unix)]
fn make_executable(path: &std::path::Path) {
    use std::os::unix::fs::PermissionsExt;

    let mut permissions = fs::metadata(path).unwrap().permissions();
    permissions.set_mode(0o755);
    fs::set_permissions(path, permissions).unwrap();
}

#[cfg(not(unix))]
fn make_executable(_path: &std::path::Path) {}

struct EnvGuard {
    previous: Vec<(&'static str, Option<String>)>,
}

impl EnvGuard {
    fn clear_many(keys: &[&'static str]) -> Self {
        let previous = keys
            .iter()
            .map(|key| {
                let old = env::var(key).ok();
                unsafe {
                    env::remove_var(key);
                }
                (*key, old)
            })
            .collect();
        Self { previous }
    }

    fn set_many(values: &[(&'static str, &str)]) -> Self {
        let previous = values
            .iter()
            .map(|(key, value)| {
                let old = env::var(key).ok();
                unsafe {
                    env::set_var(key, value);
                }
                (*key, old)
            })
            .collect();
        Self { previous }
    }
}

impl Drop for EnvGuard {
    fn drop(&mut self) {
        for (key, value) in self.previous.iter().rev() {
            unsafe {
                match value {
                    Some(old) => env::set_var(key, old),
                    None => env::remove_var(key),
                }
            }
        }
    }
}
