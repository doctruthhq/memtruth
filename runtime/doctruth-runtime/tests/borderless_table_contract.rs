use assert_cmd::Command;
use serde_json::Value;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

static TEMP_FILE_COUNTER: AtomicU64 = AtomicU64::new(1);

#[test]
fn parse_pdf_emits_table_cells_for_borderless_aligned_text_pdf() {
    let pdf = write_borderless_table_pdf_fixture();
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let tables = json["body"]["tables"].as_array().unwrap();
    let units = json["body"]["units"].as_array().unwrap();
    let table_units: Vec<&Value> = units
        .iter()
        .filter(|unit| unit["kind"] == "TABLE_CELL")
        .collect();

    assert_eq!(tables.len(), 1);
    assert_eq!(tables[0]["cells"].as_array().unwrap().len(), 4);
    assert_eq!(table_units.len(), 4);
    assert_eq!(tables[0]["cells"][0]["text"], "Name");
    assert_eq!(tables[0]["cells"][1]["text"], "Score");
    assert_eq!(tables[0]["cells"][2]["text"], "Alex");
    assert_eq!(tables[0]["cells"][3]["text"], "98");
    assert!(tables[0]["boundingBox"].is_object());
    assert_eq!(
        tables[0]["confidence"]["rationale"],
        "borderless aligned text table extraction"
    );
    for cell in tables[0]["cells"].as_array().unwrap() {
        assert!(cell["boundingBox"].is_object());
    }
    for unit in table_units {
        assert!(unit["location"]["boundingBox"].is_object());
        assert_eq!(
            unit["confidence"]["rationale"],
            "borderless aligned text table extraction"
        );
    }
}

#[test]
fn parse_pdf_emits_cluster_table_for_sparse_wide_text_grid() {
    let pdf = write_sparse_wide_table_pdf_fixture();
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let tables = json["body"]["tables"].as_array().unwrap();
    let table = &tables[0];
    let cells = table["cells"].as_array().unwrap();

    assert_eq!(tables.len(), 1);
    assert_eq!(table["method"], "cluster");
    assert_eq!(table["quality"]["rowCount"], 5);
    assert_eq!(table["quality"]["columnCount"], 6);
    assert!(
        cells
            .iter()
            .any(|cell| cell["text"] == "Forecast(observed)")
    );
    assert!(
        cells
            .iter()
            .any(|cell| cell["text"] == "Upper Confidence Bound(observed)")
    );
    assert!(cells.iter().any(|cell| {
        cell["text"] == ""
            && cell["rowRange"] == serde_json::json!({"start": 2, "end": 2})
            && cell["columnRange"] == serde_json::json!({"start": 3, "end": 3})
    }));
}

#[test]
fn parse_pdf_emits_cluster_table_for_opendataloader_sparse_real_case() {
    let pdf = opendataloader_fixture("01030000000128.pdf");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let tables = json["body"]["tables"].as_array().unwrap();
    let table = &tables[0];
    let cells = table["cells"].as_array().unwrap();

    assert_eq!(tables.len(), 1);
    assert_eq!(table["method"], "cluster");
    assert!(table["quality"]["rowCount"].as_u64().unwrap() >= 10);
    assert_eq!(table["quality"]["columnCount"], 6);
    assert!(
        cells
            .iter()
            .any(|cell| cell["text"] == "Forecast(observed)")
    );
    assert!(
        cells
            .iter()
            .any(|cell| cell["text"] == "Lower Confidence Bound(observed)")
    );
    assert!(
        cells
            .iter()
            .any(|cell| cell["text"] == "Upper Confidence Bound(observed)")
    );
    assert!(cells.iter().any(|cell| cell["text"] == ""));
}

#[test]
fn parse_pdf_composes_bordered_and_cluster_table_processors() {
    let pdf = write_bordered_plus_cluster_table_pdf_fixture();
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(parse_request(&pdf))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let json: Value = serde_json::from_slice(&output).unwrap();
    let tables = json["body"]["tables"].as_array().unwrap();

    assert!(
        tables.len() >= 2,
        "expected bordered and cluster tables to coexist, got {tables:?}"
    );
    assert!(
        tables.iter().any(|table| table["method"] == "line-table"
            && table["cells"]
                .as_array()
                .unwrap()
                .iter()
                .any(|cell| cell["text"] == "Name")),
        "missing bordered line-table in {tables:?}"
    );
    assert!(
        tables.iter().any(|table| table["method"] == "cluster"
            && table["cells"]
                .as_array()
                .unwrap()
                .iter()
                .any(|cell| cell["text"] == "Forecast(observed)")),
        "missing cluster table in {tables:?}"
    );
}

fn parse_request(source_path: &Path) -> String {
    format!(
        r#"{{"command":"parse_pdf","source_path":"{}","source_hash":"sha256:test","preset":"lite","offline_mode":true,"allow_model_downloads":false}}"#,
        source_path.display()
    )
}

fn opendataloader_fixture(name: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../third_party/opendataloader-bench/pdfs")
        .join(name)
}

fn write_borderless_table_pdf_fixture() -> PathBuf {
    let path = temp_pdf_path("doctruth-runtime-borderless-table-fixture");
    fs::write(&path, minimal_borderless_table_pdf()).unwrap();
    path
}

fn write_sparse_wide_table_pdf_fixture() -> PathBuf {
    let path = temp_pdf_path("doctruth-runtime-sparse-wide-table-fixture");
    fs::write(&path, minimal_sparse_wide_table_pdf()).unwrap();
    path
}

fn write_bordered_plus_cluster_table_pdf_fixture() -> PathBuf {
    let path = temp_pdf_path("doctruth-runtime-composed-table-fixture");
    fs::write(&path, minimal_bordered_plus_cluster_table_pdf()).unwrap();
    path
}

fn temp_pdf_path(prefix: &str) -> PathBuf {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let sequence = TEMP_FILE_COUNTER.fetch_add(1, Ordering::Relaxed);
    std::env::temp_dir().join(format!(
        "{prefix}-{}-{nanos}-{sequence}.pdf",
        std::process::id()
    ))
}

fn minimal_sparse_wide_table_pdf() -> Vec<u8> {
    let stream = "\
BT
/F1 12 Tf
1 0 0 1 84 720 Tm
(A) Tj
1 0 0 1 130 720 Tm
(B) Tj
1 0 0 1 220 720 Tm
(C) Tj
1 0 0 1 320 720 Tm
(D) Tj
1 0 0 1 448 720 Tm
(E) Tj
1 0 0 1 58 690 Tm
(1) Tj
1 0 0 1 84 690 Tm
(time) Tj
1 0 0 1 130 690 Tm
(observed) Tj
1 0 0 1 220 690 Tm
(Forecast\\(observed\\)) Tj
1 0 0 1 320 690 Tm
(Lower Confidence Bound\\(observed\\)) Tj
1 0 0 1 448 690 Tm
(Upper Confidence Bound\\(observed\\)) Tj
1 0 0 1 58 660 Tm
(2) Tj
1 0 0 1 84 660 Tm
(0) Tj
1 0 0 1 130 660 Tm
(13) Tj
1 0 0 1 58 630 Tm
(3) Tj
1 0 0 1 84 630 Tm
(1) Tj
1 0 0 1 130 630 Tm
(12) Tj
1 0 0 1 58 600 Tm
(4) Tj
1 0 0 1 84 600 Tm
(2) Tj
1 0 0 1 130 600 Tm
(13.5) Tj
1 0 0 1 220 600 Tm
(17.90) Tj
1 0 0 1 320 600 Tm
(17.90) Tj
1 0 0 1 448 600 Tm
(17.90) Tj
ET
";
    pdf_from_stream(stream)
}

fn minimal_borderless_table_pdf() -> Vec<u8> {
    let stream = "\
BT
/F1 16 Tf
90 700 Td
(Name) Tj
144 0 Td
(Score) Tj
-144 -40 Td
(Alex) Tj
144 0 Td
(98) Tj
ET
";
    pdf_from_stream(stream)
}

fn minimal_bordered_plus_cluster_table_pdf() -> Vec<u8> {
    let stream = "\
q
72 720 m
360 720 l
360 640 l
72 640 l
72 720 l
S
216 720 m
216 640 l
S
72 680 m
360 680 l
S
BT
/F1 12 Tf
90 695 Td
(Name) Tj
144 0 Td
(Score) Tj
-144 -40 Td
(Alex) Tj
144 0 Td
(98) Tj
ET
Q
BT
/F1 12 Tf
1 0 0 1 84 560 Tm
(A) Tj
1 0 0 1 130 560 Tm
(B) Tj
1 0 0 1 220 560 Tm
(C) Tj
1 0 0 1 320 560 Tm
(D) Tj
1 0 0 1 448 560 Tm
(E) Tj
1 0 0 1 58 530 Tm
(1) Tj
1 0 0 1 84 530 Tm
(time) Tj
1 0 0 1 130 530 Tm
(observed) Tj
1 0 0 1 220 530 Tm
(Forecast\\(observed\\)) Tj
1 0 0 1 320 530 Tm
(Lower Confidence Bound\\(observed\\)) Tj
1 0 0 1 448 530 Tm
(Upper Confidence Bound\\(observed\\)) Tj
1 0 0 1 58 500 Tm
(2) Tj
1 0 0 1 84 500 Tm
(0) Tj
1 0 0 1 130 500 Tm
(13) Tj
1 0 0 1 58 470 Tm
(3) Tj
1 0 0 1 84 470 Tm
(1) Tj
1 0 0 1 130 470 Tm
(12) Tj
1 0 0 1 58 440 Tm
(4) Tj
1 0 0 1 84 440 Tm
(2) Tj
1 0 0 1 130 440 Tm
(13.5) Tj
1 0 0 1 220 440 Tm
(17.90) Tj
1 0 0 1 320 440 Tm
(17.90) Tj
1 0 0 1 448 440 Tm
(17.90) Tj
ET
";
    pdf_from_stream(stream)
}

fn pdf_from_stream(stream: &str) -> Vec<u8> {
    let objects = [
        "<< /Type /Catalog /Pages 2 0 R >>".to_string(),
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_string(),
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>".to_string(),
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_string(),
        format!("<< /Length {} >>\nstream\n{}endstream", stream.len(), stream),
    ];
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
