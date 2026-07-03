use assert_cmd::Command;
use serde_json::{Value, json};
use std::collections::BTreeSet;
use std::fs;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

static TEMP_FILE_COUNTER: AtomicU64 = AtomicU64::new(1);

#[test]
fn prediction_command_writes_only_bench_expected_package_shape() {
    let root = temp_dir("doctruth-runtime-opendataloader-package-shape");
    let pdf_dir = root.join("pdfs");
    let prediction = root.join("prediction/doctruth-rust-package");
    fs::create_dir_all(&pdf_dir).unwrap();
    fs::write(
        pdf_dir.join("package-case.pdf"),
        minimal_pdf("Rust owns prediction packaging."),
    )
    .unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    cmd.write_stdin(
        json!({
            "command": "opendataloader_prediction",
            "bench_dir": root,
            "engine": "doctruth-rust-package",
            "limit": 1,
            "preset": "lite",
            "runtime_profile": "edge-fast",
            "output_dir": prediction
        })
        .to_string(),
    )
    .assert()
    .success();

    assert_eq!(
        root_entries(&prediction),
        BTreeSet::from([
            "cases".to_string(),
            "failures".to_string(),
            "markdown".to_string(),
            "reference-comparison.json".to_string(),
            "reference-comparison.md".to_string(),
            "resources.json".to_string(),
            "summary.json".to_string(),
        ])
    );
    assert!(prediction.join("markdown/package-case.md").is_file());
    assert!(prediction.join("cases/package-case.json").is_file());
    assert_eq!(
        fs::read_dir(prediction.join("failures")).unwrap().count(),
        0
    );

    let summary = read_json(prediction.join("summary.json"));
    assert_eq!(summary["runtime_contract"], "TrustDocument");
    assert_eq!(summary["document_count"], 1);
    assert_eq!(summary["parsed_count"], 1);
    assert_eq!(summary["failed_count"], 0);

    let resources = read_json(prediction.join("resources.json"));
    assert_eq!(resources["backend"], "rust-edge-fast");
    assert_eq!(resources["documentCount"], 1);

    let comparison = read_json(prediction.join("reference-comparison.json"));
    assert_eq!(comparison["status"], "not-run");
    assert!(
        fs::read_to_string(prediction.join("reference-comparison.md"))
            .unwrap()
            .contains("Reference comparison not run")
    );
}

#[test]
fn prediction_command_cleans_stale_package_artifacts_when_reusing_output_dir() {
    let root = temp_dir("doctruth-runtime-opendataloader-package-reuse");
    let pdf_dir = root.join("pdfs");
    let prediction = root.join("prediction/doctruth-rust-package");
    let sibling = root.join("prediction/unrelated-sibling");
    fs::create_dir_all(&pdf_dir).unwrap();
    fs::create_dir_all(prediction.join("markdown")).unwrap();
    fs::create_dir_all(prediction.join("cases")).unwrap();
    fs::create_dir_all(prediction.join("failures")).unwrap();
    fs::create_dir_all(&sibling).unwrap();
    fs::write(
        pdf_dir.join("fresh-case.pdf"),
        minimal_pdf("Fresh Rust prediction packaging."),
    )
    .unwrap();
    fs::write(prediction.join("errors.json"), r#"{"documents":["stale"]}"#).unwrap();
    fs::write(prediction.join("markdown/stale-case.md"), "stale").unwrap();
    fs::write(prediction.join("cases/stale-case.json"), "{}").unwrap();
    fs::write(prediction.join("failures/stale-case.json"), "{}").unwrap();
    fs::write(sibling.join("keep.txt"), "keep").unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    cmd.write_stdin(
        json!({
            "command": "opendataloader_prediction",
            "bench_dir": root,
            "engine": "doctruth-rust-package",
            "limit": 1,
            "preset": "lite",
            "runtime_profile": "edge-fast",
            "output_dir": prediction
        })
        .to_string(),
    )
    .assert()
    .success();

    assert!(!prediction.join("errors.json").exists());
    assert!(!prediction.join("markdown/stale-case.md").exists());
    assert!(!prediction.join("cases/stale-case.json").exists());
    assert!(!prediction.join("failures/stale-case.json").exists());
    assert!(prediction.join("markdown/fresh-case.md").is_file());
    assert!(prediction.join("cases/fresh-case.json").is_file());
    assert_eq!(
        fs::read_dir(prediction.join("failures")).unwrap().count(),
        0
    );
    assert!(sibling.join("keep.txt").is_file());
}

fn root_entries(dir: &PathBuf) -> BTreeSet<String> {
    fs::read_dir(dir)
        .unwrap()
        .map(|entry| entry.unwrap().file_name().to_string_lossy().into_owned())
        .collect()
}

fn read_json(path: PathBuf) -> Value {
    serde_json::from_str(&fs::read_to_string(path).unwrap()).unwrap()
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
    write_xref(&mut pdf, objects.len(), &offsets);
    pdf
}

fn write_xref(pdf: &mut Vec<u8>, object_count: usize, offsets: &[usize]) {
    let xref_offset = pdf.len();
    pdf.extend_from_slice(
        format!("xref\n0 {}\n0000000000 65535 f \n", object_count + 1).as_bytes(),
    );
    for offset in offsets {
        pdf.extend_from_slice(format!("{offset:010} 00000 n \n").as_bytes());
    }
    pdf.extend_from_slice(
        format!(
            "trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{}\n%%EOF\n",
            object_count + 1,
            xref_offset
        )
        .as_bytes(),
    );
}
