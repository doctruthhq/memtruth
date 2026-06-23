use assert_cmd::Command;
use predicates::prelude::*;
use serde_json::json;
use std::fs;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

static TEMP_FILE_COUNTER: AtomicU64 = AtomicU64::new(1);

#[test]
fn opendataloader_java_backend_rejects_python_command_in_default_path() {
    let root = temp_dir("doctruth-runtime-no-python-default");
    let pdf_dir = root.join("pdfs");
    let prediction = root.join("prediction");
    fs::create_dir_all(&pdf_dir).unwrap();
    fs::write(pdf_dir.join("doc-a.pdf"), b"%PDF-1.4\n%%EOF\n").unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    cmd.write_stdin(
        json!({
            "command": "opendataloader_prediction",
            "bench_dir": root,
            "engine": "doctruth-java-core",
            "backend": "opendataloader-java-core",
            "java_backend_command": ["python3", "scripts/doctruth_opendataloader_prediction.py"],
            "limit": 1,
            "preset": "lite",
            "runtime_profile": "edge-fast",
            "output_dir": prediction
        })
        .to_string(),
    )
    .assert()
    .failure()
    .stderr(predicate::str::contains("PYTHON_DEFAULT_BACKEND_FORBIDDEN"))
    .stderr(predicate::str::contains("oracle-only"));
}

#[test]
fn opendataloader_bench_script_defaults_to_java_backend_without_python_adapter() {
    let script =
        fs::read_to_string(repo_root().join("scripts/run-doctruth-opendataloader-bench.sh"))
            .unwrap();

    assert!(
        script.contains("DOCTRUTH_OPENDATALOADER_BACKEND:-opendataloader-java-core"),
        "benchmark runner should default to the Java/OpenDataLoader quality core"
    );
    assert!(
        script.contains("opendataloader-backend --stdio-jsonl"),
        "benchmark runner should start the warm Java stdio backend"
    );
    assert!(
        !script.contains("doctruth_opendataloader_prediction.py"),
        "default benchmark runner must not call the Python prediction adapter"
    );
    assert!(
        script.contains("DOCTRUTH_ALLOW_PYTHON_ORACLE"),
        "official Python evaluator must stay explicitly oracle-gated"
    );
}

fn repo_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .unwrap()
        .parent()
        .unwrap()
        .to_path_buf()
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
