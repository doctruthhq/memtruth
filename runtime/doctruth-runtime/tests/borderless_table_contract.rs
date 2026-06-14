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

fn parse_request(source_path: &Path) -> String {
    format!(
        r#"{{"command":"parse_pdf","source_path":"{}","source_hash":"sha256:test","preset":"lite","offline_mode":true,"allow_model_downloads":false}}"#,
        source_path.display()
    )
}

fn write_borderless_table_pdf_fixture() -> PathBuf {
    let path = temp_pdf_path("doctruth-runtime-borderless-table-fixture");
    fs::write(&path, minimal_borderless_table_pdf()).unwrap();
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
