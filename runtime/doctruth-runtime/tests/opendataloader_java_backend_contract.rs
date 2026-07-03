use doctruth_runtime::opendataloader_java_backend::OpenDataLoaderJavaBackendClient;
use serde_json::json;
use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

static TEMP_FILE_COUNTER: AtomicU64 = AtomicU64::new(1);

#[test]
fn warm_client_sends_multiple_requests_to_one_process() {
    let dir = temp_dir("doctruth-runtime-java-backend-warm");
    let starts = dir.join("starts.txt");
    let worker = write_worker(
        &dir,
        "warm-worker",
        r#"
echo start >> "$1"
count=0
while IFS= read -r line; do
  count=$((count + 1))
  printf '{"ok":true,"count":%s,"request":%s}\n' "$count" "$line"
done
"#,
    );
    let argv = vec![worker.display().to_string(), starts.display().to_string()];
    let mut client = OpenDataLoaderJavaBackendClient::spawn(&argv).unwrap();
    let child_id = client.child_id();

    let first = client.send(&json!({"document":"first.pdf"})).unwrap();
    let second = client.send(&json!({"document":"second.pdf"})).unwrap();

    assert!(child_id > 0);
    assert_eq!(first["count"], 1);
    assert_eq!(first["request"]["document"], "first.pdf");
    assert_eq!(second["count"], 2);
    assert_eq!(second["request"]["document"], "second.pdf");
    assert_eq!(fs::read_to_string(starts).unwrap().lines().count(), 1);
}

#[test]
fn invalid_worker_json_fails_closed_without_restarting_process() {
    let dir = temp_dir("doctruth-runtime-java-backend-invalid");
    let starts = dir.join("starts.txt");
    let worker = write_worker(
        &dir,
        "invalid-worker",
        r#"
echo start >> "$1"
while IFS= read -r line; do
  case "$line" in
    *invalid*) printf '{not-json\n' ;;
    *) printf '{"ok":true,"request":%s}\n' "$line" ;;
  esac
done
"#,
    );
    let argv = vec![worker.display().to_string(), starts.display().to_string()];
    let mut client = OpenDataLoaderJavaBackendClient::spawn(&argv).unwrap();

    let error = client.send(&json!({"document":"invalid.pdf"})).unwrap_err();
    let next = client.send(&json!({"document":"valid.pdf"})).unwrap();

    assert!(error.contains("invalid JSON"));
    assert_eq!(next["ok"], true);
    assert_eq!(next["request"]["document"], "valid.pdf");
    assert_eq!(fs::read_to_string(starts).unwrap().lines().count(), 1);
}

#[test]
fn empty_command_is_rejected() {
    let error = match OpenDataLoaderJavaBackendClient::spawn(&[]) {
        Ok(_) => panic!("empty command should fail"),
        Err(error) => error,
    };

    assert!(error.contains("command is required"));
}

fn write_worker(dir: &std::path::Path, name: &str, body: &str) -> PathBuf {
    let path = dir.join(name);
    fs::write(&path, format!("#!/bin/sh\n{body}\n")).unwrap();
    let mut permissions = fs::metadata(&path).unwrap().permissions();
    permissions.set_mode(0o755);
    fs::set_permissions(&path, permissions).unwrap();
    path
}

fn temp_dir(name: &str) -> PathBuf {
    let nonce = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let count = TEMP_FILE_COUNTER.fetch_add(1, Ordering::Relaxed);
    let dir = std::env::temp_dir().join(format!("{name}-{nonce}-{count}"));
    fs::create_dir_all(&dir).unwrap();
    dir
}
