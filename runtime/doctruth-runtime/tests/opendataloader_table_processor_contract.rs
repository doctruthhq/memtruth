use assert_cmd::Command;
use serde_json::json;
use std::fs;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

static TEMP_FILE_COUNTER: AtomicU64 = AtomicU64::new(0);

fn run_doc(doc_id: &str) -> String {
    let output_dir = std::env::temp_dir().join(format!(
        "doctruth-table-contract-{doc_id}-{}",
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_prediction",
                "bench_dir": "../../third_party/opendataloader-bench",
                "output_dir": output_dir,
                "engine": "doctruth-table-contract",
                "doc_id": doc_id,
                "preset": "edge-fast",
                "profile": "edge-fast",
                "timeout_seconds": 30
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let value: serde_json::Value = serde_json::from_slice(&output).unwrap();
    let markdown_dir = PathBuf::from(value["prediction"]["markdownPath"].as_str().unwrap());
    let markdown = fs::read_to_string(markdown_dir.join(format!("{doc_id}.md"))).unwrap();
    let _ = fs::remove_dir_all(output_dir);
    markdown
}

#[test]
fn table_processor_preserves_regular_bordered_table_case_00083() {
    let markdown = run_doc("01030000000083");
    assert!(
        markdown.contains("|Category|Number of clauses in Union laws|"),
        "{markdown}"
    );
}

#[test]
fn table_processor_preserves_matrix_table_case_00189() {
    let markdown = run_doc("01030000000189");
    assert!(
        markdown.contains("|Model|Alpaca-GPT4|OpenOrca|"),
        "{markdown}"
    );
}

#[test]
fn table_processor_preserves_column_major_numeric_table_case_00127() {
    let markdown = run_doc("01030000000127");
    assert!(
        markdown.contains("|Year|3-Year|5-Year|7-Year|"),
        "{markdown}"
    );
}

#[test]
fn table_processor_does_not_promote_union_state_header_without_numeric_body() {
    let root = temp_dir("doctruth-table-contract-near-union-state");
    let pdf_dir = root.join("pdfs");
    let prediction = root.join("prediction/doctruth-table-contract");
    fs::create_dir_all(&pdf_dir).unwrap();
    fs::write(
        pdf_dir.join("near-match.pdf"),
        minimal_pdf(
            "|Category|Union laws|State laws|Number of|\n\
             |---|---|---|---|\n\
             |Overview|Union laws apply here|State laws apply here|Number of examples|",
        ),
    )
    .unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    cmd.write_stdin(
        json!({
            "command": "opendataloader_prediction",
            "bench_dir": root,
            "engine": "doctruth-table-contract",
            "output_dir": prediction,
            "preset": "lite",
            "profile": "edge-fast",
            "limit": 1,
            "timeout_seconds": 10
        })
        .to_string(),
    )
    .assert()
    .success();

    let markdown = fs::read_to_string(prediction.join("markdown/near-match.md")).unwrap();
    assert!(
        !markdown.contains("|Category|Number of clauses in Union laws|In percent|Number of clauses in State laws|In percent|"),
        "{markdown}"
    );
    let _ = fs::remove_dir_all(root);
}

#[test]
fn table_processor_contract_records_absent_special_table_reference() {
    const ABSENT_SPECIAL_TABLE_PROCESSOR_REFERENCE: &str = "third_party/opendataloader-pdf-reference/java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/SpecialTableProcessor.java";

    assert!(
        !repo_root()
            .join(ABSENT_SPECIAL_TABLE_PROCESSOR_REFERENCE)
            .exists(),
        "SpecialTableProcessor is not present in the vendored OpenDataLoader reference; do not claim direct parity"
    );
}

#[test]
fn table_border_probe_covers_split_neighbor_and_depth_contracts() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_table_border_probe",
                "textChunk": {"text": "test", "x0": 10.0, "x1": 30.0},
                "cells": [
                    {"left": 10.0, "right": 20.0},
                    {"left": 20.0, "right": 30.0}
                ],
                "neighborTables": [
                    {"columns": [10.0, 10.0], "width": 20.0},
                    {"columns": [10.5, 9.5], "width": 20.0},
                    {"columns": [10.0, 30.0], "width": 40.0}
                ],
                "depths": [9, 10]
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let value: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(value["source"], "OpenDataLoader TableBorderProcessor");
    assert_eq!(value["cellTextParts"], json!(["te", "st"]));
    assert_eq!(value["neighborLinks"], json!([true, false]));
    assert_eq!(value["depthAllowed"], json!([true, false]));
    assert!(
        value["reference"]
            .as_str()
            .unwrap()
            .ends_with("TableBorderProcessor.java")
    );
}

fn repo_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(|path| path.parent())
        .expect("runtime crate lives under runtime/doctruth-runtime")
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
