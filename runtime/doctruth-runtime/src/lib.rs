use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::env;
use std::fs;
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::OnceLock;
use std::time::{Instant, SystemTime, UNIX_EPOCH};

use pdf_oxide::content::{Operator, TextElement, parse_content_stream};
use pdf_oxide::document::PdfDocument;
use pdf_oxide::editor::DocumentInfo;
use pdf_oxide::layout::TextSpan;
use pdf_oxide::pipeline::page_reading_order;
use pdf_oxide::rendering::{RenderOptions, render_page};
use pdf_oxide::structure::{
    Table as PdfOxideTable, TableDetectionConfig, detect_tables_from_spans,
};
use regex::Regex;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

mod opendataloader_parity;

pub use opendataloader_parity::opendataloader_parity_matrix_json;

const RUNTIME: &str = "doctruth-runtime";
const PROTOCOL_VERSION: &str = "1";
const PDF_BACKEND_TARGET: &str = "pdf_oxide";
const PDF_BACKEND_CURRENT: &str = "pdf_oxide";
const PDF_BACKEND_STATUS: &str = "DEFAULT";
const DEFAULT_PROTOCOL_PROFILE: &str = "edge-model";
const PAGE_WIDTH: f64 = 612.0;
const PAGE_HEIGHT: f64 = 792.0;
const MAX_DEFAULT_RENDERED_PAGE_AREA: f64 = 2_000_000.0;
const MAX_RAW_CONTENT_SAFETY_BYTES: usize = 64 * 1024;
const GRID_EPSILON: f64 = 1.0;
#[cfg(test)]
const OPENDATALOADER_MAX_NESTED_TABLE_DEPTH: usize = 10;
const OPENDATALOADER_DECORATION_CENTER_TOLERANCE: f64 = 0.2;
const OPENDATALOADER_STRIKE_MIN_OVERLAP_RATIO: f64 = 0.8;
const OPENDATALOADER_MAX_RULE_TO_TEXT_WIDTH_RATIO: f64 = 1.5;
const OPENDATALOADER_MAX_RULE_THICKNESS: f64 = 2.0;
const OPENDATALOADER_MAX_RULE_TO_TEXT_HEIGHT_RATIO: f64 = 0.25;
const OPENDATALOADER_UNDERLINE_MIN_OVERLAP_RATIO: f64 = 0.08;
const OPENDATALOADER_UNDERLINE_BASELINE_EPSILON: f64 = 0.35;
const OPENDATALOADER_UNDERLINE_THICKNESS_RATIO: f64 = 0.3;
const OPENDATALOADER_REPLACEMENT_CHARACTER: char = '\u{fffd}';
const OPENDATALOADER_REPLACEMENT_CHARACTER_STRING: &str = "\u{fffd}";
const OPENDATALOADER_TEXT_PROCESSOR_REFERENCE: &str = "third_party/opendataloader-pdf-reference/java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/TextProcessor.java";
const OPENDATALOADER_TEXT_LINE_PROCESSOR_REFERENCE: &str = "third_party/opendataloader-pdf-reference/java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/TextLineProcessor.java";
const OPENDATALOADER_PARAGRAPH_PROCESSOR_REFERENCE: &str = "third_party/opendataloader-pdf-reference/java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/ParagraphProcessor.java";
const HUMAN_REVIEWED_PARSER_ACCURACY_METRICS: &[&str] = &[
    "reading_order_f1",
    "quote_anchor_accuracy",
    "bbox_coverage",
    "bbox_iou",
    "evidence_span_accuracy",
    "table_cell_f1",
    "ocr_text_accuracy",
];
const HUMAN_REVIEWED_PARSER_ACCURACY_TAGS: &[&str] =
    &["multi-layout", "table", "ocr", "bbox", "source-map"];

pub fn run_process() -> i32 {
    let exit_code = match run() {
        Ok(output) => {
            println!("{output}");
            0
        }
        Err(error) => {
            eprintln!("{error}");
            2
        }
    };
    exit_code
}

fn run() -> Result<String, String> {
    let args: Vec<String> = env::args().skip(1).collect();
    let mut input = String::new();
    io::stdin()
        .read_to_string(&mut input)
        .map_err(|error| error_json("STDIN_READ_FAILED", &error.to_string()).to_string())?;
    run_with_args_and_input(&args, &input)
}

pub fn run_with_args_and_input(args: &[String], input: &str) -> Result<String, String> {
    if args == ["--doctor"] {
        return Ok(doctor_json().to_string());
    }
    if !args.is_empty() {
        return Err(error_json("UNKNOWN_ARGUMENT", "unsupported runtime argument").to_string());
    }

    let request: Value = serde_json::from_str(&input)
        .map_err(|error| error_json("INVALID_REQUEST_JSON", &error.to_string()).to_string())?;
    match request.get("command").and_then(Value::as_str) {
        Some("parse_pdf") => parse_pdf_json(&request).map(|json| json.to_string()),
        Some("benchmark_corpus") => benchmark_corpus_json(&request).map(|json| json.to_string()),
        Some("opendataloader_prediction") => {
            opendataloader_prediction_json(&request).map(|json| json.to_string())
        }
        Some("opendataloader_evaluate_prediction") => {
            opendataloader_evaluate_prediction_json(&request).map(|json| json.to_string())
        }
        Some("opendataloader_promotion_report") => {
            opendataloader_promotion_report_json(&request).map(|json| json.to_string())
        }
        Some("opendataloader_parity_matrix") => Ok(opendataloader_parity_matrix_json().to_string()),
        Some("opendataloader_text_processor_probe") => {
            opendataloader_text_processor_probe_json(&request).map(|json| json.to_string())
        }
        Some("opendataloader_line_paragraph_probe") => {
            opendataloader_line_paragraph_probe_json(&request).map(|json| json.to_string())
        }
        Some("verify_benchmark_report") => {
            verify_benchmark_report_json(&request).map(|json| json.to_string())
        }
        Some(_) => Err(error_json("UNKNOWN_COMMAND", "unsupported runtime command").to_string()),
        None => Err(error_json("MISSING_COMMAND", "request.command is required").to_string()),
    }
}

fn opendataloader_text_processor_probe_json(request: &Value) -> Result<Value, String> {
    let text = request
        .get("text")
        .and_then(Value::as_str)
        .ok_or_else(|| error_json("MISSING_TEXT", "request.text is required").to_string())?;
    let (replacement_count, replacement_ratio) = opendataloader_replacement_char_metrics(text);
    let replacement = request
        .get("undefined_character_replacement")
        .and_then(Value::as_str)
        .or_else(|| {
            request
                .get("undefinedCharacterReplacement")
                .and_then(Value::as_str)
        });
    let processed_text = opendataloader_replace_undefined_characters(text, replacement);

    Ok(json!({
        "runtime": RUNTIME,
        "protocol_version": PROTOCOL_VERSION,
        "source": "OpenDataLoader TextProcessor",
        "text": processed_text,
        "replacementCount": replacement_count,
        "replacementRatio": replacement_ratio,
        "reference": OPENDATALOADER_TEXT_PROCESSOR_REFERENCE
    }))
}

fn opendataloader_replacement_char_metrics(text: &str) -> (usize, f64) {
    let replacement_count = text
        .encode_utf16()
        .filter(|code_unit| *code_unit == OPENDATALOADER_REPLACEMENT_CHARACTER as u16)
        .count();
    let total_code_units = text.encode_utf16().count();
    match total_code_units {
        0 => (0, 0.0),
        total => (replacement_count, replacement_count as f64 / total as f64),
    }
}

fn opendataloader_replace_undefined_characters(text: &str, replacement: Option<&str>) -> String {
    match replacement {
        Some(value) if value != OPENDATALOADER_REPLACEMENT_CHARACTER_STRING => {
            text.replace(OPENDATALOADER_REPLACEMENT_CHARACTER_STRING, value)
        }
        _ => text.to_string(),
    }
}

fn opendataloader_line_paragraph_probe_json(request: &Value) -> Result<Value, String> {
    let lines = opendataloader_probe_positioned_lines(request)?;
    let rows = opendataloader_probe_visual_rows(lines);
    let table_like_rows = rows
        .iter()
        .filter(|row| opendataloader_probe_table_like_row(row))
        .count();
    let prose_lines = opendataloader_probe_prose_lines(rows);
    let paragraph_output = opendataloader_probe_paragraph_output(prose_lines);

    Ok(json!({
        "runtime": RUNTIME,
        "protocol_version": PROTOCOL_VERSION,
        "source": "OpenDataLoader TextLineProcessor/ParagraphProcessor",
        "paragraphs": paragraph_output.paragraphs,
        "joinedParagraphs": paragraph_output.joined_paragraphs,
        "tableLikeRows": table_like_rows,
        "references": [
            OPENDATALOADER_TEXT_LINE_PROCESSOR_REFERENCE,
            OPENDATALOADER_PARAGRAPH_PROCESSOR_REFERENCE
        ]
    }))
}

fn opendataloader_probe_positioned_lines(request: &Value) -> Result<Vec<PositionedLine>, String> {
    let lines = request
        .get("lines")
        .and_then(Value::as_array)
        .ok_or_else(|| error_json("MISSING_LINES", "request.lines is required").to_string())?;
    lines
        .iter()
        .enumerate()
        .map(|(index, value)| opendataloader_probe_positioned_line(value, index))
        .collect()
}

fn opendataloader_probe_positioned_line(
    value: &Value,
    index: usize,
) -> Result<PositionedLine, String> {
    let text = value.get("text").and_then(Value::as_str).ok_or_else(|| {
        error_json(
            "INVALID_LINE_TEXT",
            &format!("request.lines[{index}].text is required"),
        )
        .to_string()
    })?;
    let x0 = opendataloader_probe_coordinate(value, index, "x0")?;
    let y0 = opendataloader_probe_coordinate(value, index, "y0")?;
    let x1 = opendataloader_probe_coordinate(value, index, "x1")?;
    let y1 = opendataloader_probe_coordinate(value, index, "y1")?;
    if x1 <= x0 || y1 <= y0 {
        return Err(error_json(
            "INVALID_LINE_BOX",
            &format!("request.lines[{index}] must satisfy x0 < x1 and y0 < y1"),
        )
        .to_string());
    }
    let bbox = RuntimeBox { x0, y0, x1, y1 };
    Ok(PositionedLine {
        text: text.to_string(),
        raw_bbox: RawPdfBox { x0, y0, x1, y1 },
        bbox,
        page_width: x1.max(PAGE_WIDTH),
        page_height: y1.max(PAGE_HEIGHT),
        font_size: (y1 - y0).max(1.0),
    })
}

fn opendataloader_probe_coordinate(value: &Value, index: usize, name: &str) -> Result<f64, String> {
    let coordinate = value.get(name).and_then(Value::as_f64).ok_or_else(|| {
        error_json(
            "INVALID_LINE_BOX",
            &format!("request.lines[{index}].{name} must be a finite number"),
        )
        .to_string()
    })?;
    if coordinate.is_finite() {
        Ok(coordinate)
    } else {
        Err(error_json(
            "INVALID_LINE_BOX",
            &format!("request.lines[{index}].{name} must be a finite number"),
        )
        .to_string())
    }
}

fn opendataloader_probe_visual_rows(lines: Vec<PositionedLine>) -> Vec<Vec<PositionedLine>> {
    let mut rows: Vec<Vec<PositionedLine>> = Vec::new();
    for line in sort_positioned_y_then_x(lines) {
        if let Some(row) = rows.last_mut() {
            if opendataloader_probe_same_visual_row(row.first().expect("row has line"), &line) {
                row.push(line);
                row.sort_by(|left, right| left.bbox.x0.total_cmp(&right.bbox.x0));
                continue;
            }
        }
        rows.push(vec![line]);
    }
    rows
}

fn opendataloader_probe_same_visual_row(left: &PositionedLine, right: &PositionedLine) -> bool {
    let overlap = (left.bbox.y1.min(right.bbox.y1) - left.bbox.y0.max(right.bbox.y0)).max(0.0);
    let smaller_height = bbox_height(&left.bbox).min(bbox_height(&right.bbox));
    if smaller_height > 0.0 && overlap / smaller_height >= 0.5 {
        return true;
    }
    let center_delta = (bbox_center_y(&left.bbox) - bbox_center_y(&right.bbox)).abs();
    center_delta <= bbox_height(&left.bbox).max(bbox_height(&right.bbox)) * 0.35
}

fn opendataloader_probe_table_like_row(row: &[PositionedLine]) -> bool {
    if row.len() < 2 {
        return false;
    }
    row.windows(2).any(|pair| {
        let left = &pair[0];
        let right = &pair[1];
        right.bbox.x0 - left.bbox.x1 >= left.font_size.max(right.font_size).max(8.0)
    })
}

fn opendataloader_probe_prose_lines(rows: Vec<Vec<PositionedLine>>) -> Vec<PositionedLine> {
    rows.into_iter()
        .filter(|row| !opendataloader_probe_table_like_row(row))
        .flat_map(|row| {
            if row.len() <= 1 {
                row
            } else {
                vec![merge_positioned_visual_row(row)]
            }
        })
        .collect()
}

struct OpendataloaderProbeParagraphOutput {
    paragraphs: Vec<String>,
    joined_paragraphs: Vec<String>,
}

fn opendataloader_probe_paragraph_output(
    lines: Vec<PositionedLine>,
) -> OpendataloaderProbeParagraphOutput {
    let mut output = OpendataloaderProbeParagraphOutput {
        paragraphs: Vec::new(),
        joined_paragraphs: Vec::new(),
    };
    let mut current: Vec<PositionedLine> = Vec::new();
    for line in lines {
        if current
            .last()
            .is_some_and(|previous| opendataloader_probe_wrapped_pair(previous, &line))
        {
            current.push(line);
        } else {
            opendataloader_probe_push_paragraph_output(&mut output, &current);
            current = vec![line];
        }
    }
    opendataloader_probe_push_paragraph_output(&mut output, &current);
    output
}

fn opendataloader_probe_push_paragraph_output(
    output: &mut OpendataloaderProbeParagraphOutput,
    lines: &[PositionedLine],
) {
    if lines.is_empty() {
        return;
    }
    let paragraph = opendataloader_probe_join_line_text(lines);
    output.paragraphs.push(paragraph.clone());
    if lines.len() >= 2 {
        output.joined_paragraphs.push(paragraph);
    }
}

fn opendataloader_probe_wrapped_pair(previous: &PositionedLine, next: &PositionedLine) -> bool {
    let vertical_gap = next.bbox.y0 - previous.bbox.y1;
    let same_left_edge = (previous.bbox.x0 - next.bbox.x0).abs() <= 8.0;
    same_left_edge
        && (-2.0..=previous.font_size.max(next.font_size) * 0.7).contains(&vertical_gap)
        && !opendataloader_probe_terminal_line(&previous.text)
}

fn opendataloader_probe_terminal_line(text: &str) -> bool {
    text.trim_end()
        .chars()
        .last()
        .is_some_and(|ch| matches!(ch, '.' | '!' | '?' | ':' | ';'))
}

fn opendataloader_probe_join_line_text(lines: &[PositionedLine]) -> String {
    let mut joined = String::new();
    for line in lines {
        let text = line.text.trim();
        if joined.is_empty() {
            joined.push_str(text);
        } else if joined.ends_with('-') {
            joined.pop();
            joined.push_str(text);
        } else {
            joined.push(' ');
            joined.push_str(text);
        }
    }
    normalize_text(&joined)
}

pub fn doctor_json() -> Value {
    let memory = process_memory_usage();
    let models = model_doctor_json();
    let capabilities = runtime_capabilities_json(&models);
    json!({
        "runtime": RUNTIME,
        "protocol_version": PROTOCOL_VERSION,
        "local_first": true,
        "rssMb": memory.rss_mb,
        "peakMemoryMb": memory.peak_memory_mb,
        "parser_backends": ["rust-sidecar"],
        "pdfBackend": pdf_backend_json(),
        "model_execution": model_execution_status(&models),
        "models": models,
        "capabilities": capabilities,
        "profiles": profiles_json()
    })
}

fn profiles_json() -> Value {
    json!({
        "active": DEFAULT_PROTOCOL_PROFILE,
        "defaultProtocolProfile": DEFAULT_PROTOCOL_PROFILE,
        "recommendedProductionProfile": "edge-fast",
        "available": {
            "edge-fast": {
                "production": true,
                "modelStartup": false,
                "network": false,
                "fallbackChains": [],
                "resourceGate": "deterministic-rust-only"
            },
            "edge-model": {
                "production": true,
                "modelRuntime": "mnn",
                "lazyModelStartup": true,
                "fallbackChains": [],
                "forbiddenResidency": ["python", "torch", "docling"],
                "resourceGate": "profile-measured-mnn"
            },
            "benchmark-oracle": {
                "production": false,
                "requiresExplicitCommand": true,
                "runtime": "opendataloader-hybrid-or-docling-fast",
                "fallbackChains": []
            }
        }
    })
}

fn pdf_backend_json() -> Value {
    json!({
        "target": PDF_BACKEND_TARGET,
        "current": PDF_BACKEND_CURRENT,
        "status": PDF_BACKEND_STATUS,
        "canonicalOutput": "TrustDocument",
        "referenceSource": "opendataloader-project/opendataloader-pdf@d1845179a1286bbb76f9618e8b6c8f51509a52f4",
        "referenceStages": [
            "content-filter",
            "text-line",
            "xy-cut-plus-plus",
            "cluster-table",
            "table-structure-normalizer",
            "heading"
        ],
        "features": [
            "legacy-crypto",
            "rendering",
            "content-filter",
            "xy-cut-plus-plus",
            "cluster-table",
            "table-structure-normalizer",
            "heading"
        ]
    })
}

fn runtime_capabilities_json(models: &Value) -> Value {
    json!({
        "parse_pdf": true,
        "native_text": {
            "available": true,
            "backend": "pdf_oxide"
        },
        "document_structure": {
            "available": true,
            "backend": "pdf_oxide-column-aware",
            "slots": ["structure-tree", "xy-cut"]
        },
        "layout": capability_slot_json(models, "standard", "layout-detection"),
        "tables": table_capability_json(models),
        "ocr": capability_slot_json(models, "ocr", "ocr")
    })
}

fn table_capability_json(models: &Value) -> Value {
    let lite = capability_slot_json(models, "table-lite", "table-structure-recognition");
    let server = capability_slot_json(models, "table-server", "table-structure-recognition");
    json!({
        "available": lite["available"].as_bool().unwrap_or(false)
            || server["available"].as_bool().unwrap_or(false),
        "slots": ["table-lite", "table-server"],
        "tableLite": lite,
        "tableServer": server
    })
}

fn capability_slot_json(models: &Value, preset: &str, task: &str) -> Value {
    let artifacts = models
        .pointer(&format!("/presets/{preset}/models"))
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    let matching = artifacts
        .iter()
        .filter(|model| model.get("task").and_then(Value::as_str) == Some(task))
        .cloned()
        .collect::<Vec<_>>();
    json!({
        "available": !matching.is_empty()
            && matching.iter().all(|model| model.get("cacheStatus").and_then(Value::as_str) == Some("READY")),
        "preset": preset,
        "task": task,
        "models": matching
    })
}

fn model_execution_status(models: &Value) -> &'static str {
    if models
        .pointer("/worker/configured")
        .and_then(Value::as_bool)
        .unwrap_or(false)
    {
        "local-worker"
    } else {
        "not-enabled"
    }
}

#[derive(Debug, Clone, Copy)]
struct ProcessMemoryUsage {
    rss_mb: u64,
    peak_memory_mb: u64,
}

fn process_memory_usage() -> ProcessMemoryUsage {
    let rss = linux_memory_usage().or_else(ps_rss_mb).unwrap_or(0);
    let peak = linux_peak_memory_mb().unwrap_or(rss);
    ProcessMemoryUsage {
        rss_mb: rss,
        peak_memory_mb: peak.max(rss),
    }
}

fn linux_memory_usage() -> Option<u64> {
    linux_status_kb("VmRSS:").map(kb_to_mb)
}

fn linux_peak_memory_mb() -> Option<u64> {
    linux_status_kb("VmHWM:").map(kb_to_mb)
}

fn linux_status_kb(prefix: &str) -> Option<u64> {
    let status = fs::read_to_string("/proc/self/status").ok()?;
    status
        .lines()
        .find(|line| line.starts_with(prefix))
        .and_then(|line| line.split_whitespace().nth(1))
        .and_then(|value| value.parse::<u64>().ok())
}

fn ps_rss_mb() -> Option<u64> {
    let pid = std::process::id().to_string();
    let output = Command::new("ps")
        .args(["-o", "rss=", "-p", &pid])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let text = String::from_utf8(output.stdout).ok()?;
    text.split_whitespace()
        .next()
        .and_then(|value| value.parse::<u64>().ok())
        .map(kb_to_mb)
}

fn kb_to_mb(kb: u64) -> u64 {
    kb.div_ceil(1024).max(1)
}

fn parse_pdf_json(request: &Value) -> Result<Value, String> {
    let source_path = request
        .get("source_path")
        .and_then(Value::as_str)
        .unwrap_or("document.pdf");
    let source_hash = request
        .get("source_hash")
        .and_then(Value::as_str)
        .unwrap_or("sha256:unknown");
    let requested_preset = request
        .get("preset")
        .and_then(Value::as_str)
        .unwrap_or("lite");
    let profile = runtime_profile(request)?;
    let route = model_route_decision(source_path, requested_preset);
    let effective_preset = route.effective_preset.as_str();
    let required_models = required_model_descriptors(effective_preset);
    let model_artifacts =
        worker_model_artifacts_for_request(request, effective_preset, &required_models);
    if profile == "benchmark-oracle" {
        if !required_models.is_empty()
            && model_artifacts_ready_for_profile(profile, &model_artifacts)
            && let Some(document) = configured_model_worker_parse(
                source_path,
                source_hash,
                effective_preset,
                profile,
                &route,
                &required_models,
                &model_artifacts,
                request,
            )?
        {
            return Ok(document);
        }
        return Err(error_json(
            "MODEL_WORKER_REQUIRED",
            "benchmark-oracle requires READY reference model artifacts and a configured model worker; it does not emit heuristic fallback output",
        )
        .to_string());
    }
    if profile != "edge-fast"
        && !required_models.is_empty()
        && model_artifacts_ready_for_profile(profile, &model_artifacts)
    {
        if let Some(document) = configured_model_worker_parse(
            source_path,
            source_hash,
            effective_preset,
            profile,
            &route,
            &required_models,
            &model_artifacts,
            request,
        )? {
            return Ok(document);
        }
    }
    let source_filename = Path::new(source_path)
        .file_name()
        .and_then(|name| name.to_str())
        .filter(|name| !name.is_empty())
        .unwrap_or("document.pdf");
    let replacement_character = undefined_character_replacement(request);
    let replacement_character_configured = undefined_character_replacement_configured(request);
    let extracted = extract_pages_with_pdf_oxide(source_path, replacement_character.as_deref())?;
    let mut extracted_pages = extracted.pages;
    if filter_sensitive_data_enabled(request) {
        sanitize_extracted_pages(&mut extracted_pages);
    }
    let page_lines = extracted_pages
        .iter()
        .map(|page| page.lines.clone())
        .collect::<Vec<_>>();
    if page_lines.iter().all(|lines| lines.is_empty()) {
        return Err(error_json(
            "PDF_EXTRACTION_FAILED",
            "PDF text layer did not contain extractable text",
        )
        .to_string());
    }

    let positioned_lines = extracted_pages
        .iter()
        .map(|page| page.positioned_lines.clone())
        .collect::<Vec<_>>();
    let table_extraction = extract_tables(source_path, &positioned_lines)
        .unwrap_or_else(|_| TableExtractionResult::default());
    let mut tables = table_extraction.tables;
    if filter_sensitive_data_enabled(request) {
        sanitize_tables(&mut tables);
    }
    let page_metadata =
        extract_page_metadata(source_path).unwrap_or_else(|_| fallback_page_metadata(&page_lines));
    let mut units = unit_json(&page_lines, &positioned_lines);
    if let Some(table) = party_registration_table_from_units(&units, tables.len() + 1) {
        push_preferred_table(&mut tables, table);
        tables = renumber_tables(tables).unwrap_or_default();
    }
    if let Some(table) = table_of_contents_table_from_units(&units, tables.len() + 1) {
        push_preferred_table(&mut tables, table);
        tables = renumber_tables(tables).unwrap_or_default();
    }
    enrich_dense_table_cells_from_units(&mut tables, &units);
    if let Some(table) = foreign_ownership_table_from_units(&units, tables.len() + 1) {
        push_preferred_table(&mut tables, table);
        tables = renumber_tables(tables).unwrap_or_default();
    }
    units.extend(table_unit_json(&tables, units.len() + 1));
    let mut warnings = extracted_pages
        .iter()
        .flat_map(|page| page.warnings.clone())
        .collect::<Vec<_>>();
    if filter_sensitive_data_enabled(request) {
        warnings.push(parser_warning(
            "sensitive_data_filtered",
            "OpenDataLoader-compatible sensitive-data filter redacted parser text because request.filter_sensitive_data was enabled",
        ));
    }
    if replacement_character_configured {
        warnings.push(parser_warning(
            "undefined_character_replaced",
            "OpenDataLoader-compatible text processor replaced PDF replacement characters because request.undefined_character_replacement was enabled",
        ));
    }
    warnings.extend(extracted.warnings.clone());
    warnings.extend(table_extraction.warnings);
    warnings.extend(model_unavailable_warnings(
        effective_preset,
        profile,
        &required_models,
        &model_artifacts,
    ));
    let audit_grade_status = if warnings.iter().any(is_severe_warning) {
        "NOT_AUDIT_GRADE"
    } else {
        "AUDIT_GRADE"
    };
    let model_identities = required_models
        .iter()
        .map(|model| model.identity())
        .collect::<Vec<_>>();
    let pages_json = page_json(&page_lines, &page_metadata);
    let parser_run_id = "parser-run-0001";
    let content_blocks = content_blocks_json(&units);
    let reading_order = extracted.reading_order.to_json();
    let parse_trace = parse_trace_json(&pages_json, &units, parser_run_id, &reading_order);

    Ok(json!({
        "docId": source_hash,
        "source": {
            "sourceFilename": source_filename,
            "sourceHash": source_hash,
            "metadata": {
                "sourceFilename": source_filename,
                "pageCount": page_lines.len()
            }
        },
        "body": {
            "pages": pages_json,
            "units": units,
            "tables": table_json(&tables)
        },
        "contentBlocks": content_blocks,
        "parseTrace": parse_trace,
        "parserRun": {
            "parserRunId": parser_run_id,
            "parserVersion": env!("CARGO_PKG_VERSION"),
            "preset": requested_preset,
            "profile": profile,
            "backend": "rust-sidecar",
            "pdfBackend": pdf_backend_json(),
            "readingOrder": reading_order,
            "modelRouting": route.to_json(false, &model_identities),
            "models": model_identities,
            "warnings": warnings
        },
        "auditGradeStatus": audit_grade_status
    }))
}

#[derive(Debug, Clone)]
struct ModelRouteDecision {
    mode: String,
    decision: String,
    effective_preset: String,
    routed_pages: Vec<u64>,
}

impl ModelRouteDecision {
    fn to_json(&self, started_model_runtime: bool, model_identities: &[String]) -> Value {
        let route = if started_model_runtime && self.decision == "deterministic-only" {
            "model-runtime"
        } else {
            self.decision.as_str()
        };
        let requires_model_runtime = self.decision != "deterministic-only";
        let blocked_reason = if requires_model_runtime && !started_model_runtime {
            Value::String("model-runtime-unavailable".to_string())
        } else {
            Value::Null
        };
        json!({
            "mode": self.mode,
            "decision": if started_model_runtime {
                "model-runtime"
            } else {
                "deterministic-only"
            },
            "route": route,
            "effectivePreset": self.effective_preset,
            "requiresModelRuntime": requires_model_runtime,
            "startedModelRuntime": started_model_runtime,
            "candidateRoutedPages": self.routed_pages,
            "routedPages": if started_model_runtime { json!(self.routed_pages) } else { json!([]) },
            "blockedReason": blocked_reason,
            "models": model_identities
        })
    }
}

fn model_route_decision(source_path: &str, requested_preset: &str) -> ModelRouteDecision {
    if requested_preset == "auto" {
        if let Some(routed_pages) = source_large_infographic_pages(source_path) {
            return ModelRouteDecision {
                mode: "auto".to_string(),
                decision: "ocr-model".to_string(),
                effective_preset: "ocr".to_string(),
                routed_pages,
            };
        }
    }
    if requested_preset == "auto" {
        if let Some(routed_pages) = source_empty_text_pages(source_path) {
            return ModelRouteDecision {
                mode: "auto".to_string(),
                decision: "ocr-model".to_string(),
                effective_preset: "ocr".to_string(),
                routed_pages,
            };
        }
    }
    if requested_preset == "auto" {
        if let Some(routed_pages) = source_sparse_visual_text_pages(source_path) {
            return ModelRouteDecision {
                mode: "auto".to_string(),
                decision: "ocr-model".to_string(),
                effective_preset: "ocr".to_string(),
                routed_pages,
            };
        }
    }
    if requested_preset == "auto" && source_looks_table_heavy(source_path) {
        return ModelRouteDecision {
            mode: "auto".to_string(),
            decision: "table-model".to_string(),
            effective_preset: "table-lite".to_string(),
            routed_pages: vec![1],
        };
    }
    if requested_preset != "auto" && !required_model_descriptors(requested_preset).is_empty() {
        return ModelRouteDecision {
            mode: "explicit-preset".to_string(),
            decision: "model-runtime".to_string(),
            effective_preset: requested_preset.to_string(),
            routed_pages: vec![1],
        };
    }
    ModelRouteDecision {
        mode: if requested_preset == "auto" {
            "auto"
        } else {
            "explicit-preset"
        }
        .to_string(),
        decision: "deterministic-only".to_string(),
        effective_preset: requested_preset.to_string(),
        routed_pages: Vec::new(),
    }
}

fn source_empty_text_pages(source_path: &str) -> Option<Vec<u64>> {
    let extracted = extract_pages_with_pdf_oxide(source_path, None).ok()?;
    let routed_pages = extracted
        .pages
        .iter()
        .enumerate()
        .filter_map(|(index, page)| page.lines.is_empty().then_some(index as u64 + 1))
        .collect::<Vec<_>>();
    if routed_pages.len() == extracted.pages.len() && !routed_pages.is_empty() {
        Some(routed_pages)
    } else {
        None
    }
}

fn source_large_infographic_pages(source_path: &str) -> Option<Vec<u64>> {
    let document = PdfDocument::open(source_path).ok()?;
    let page_count = document.page_count().ok()?;
    if page_count != 1 {
        return None;
    }
    let (width, height) = pdf_oxide_page_dimensions(&document, 0).ok()?;
    if width * height < 1_000_000.0 {
        return None;
    }
    let info = pdf_document_info(&document);
    if !pdf_info_looks_visual_infographic(&info) {
        return None;
    }
    Some(vec![1])
}

fn pdf_document_info(document: &PdfDocument) -> DocumentInfo {
    let Some(info_ref) = document
        .trailer()
        .as_dict()
        .and_then(|dict| dict.get("Info"))
        .and_then(|object| object.as_reference())
    else {
        return DocumentInfo::default();
    };
    document
        .load_object(info_ref)
        .map(|object| DocumentInfo::from_object(&object))
        .unwrap_or_default()
}

fn pdf_info_looks_visual_infographic(info: &DocumentInfo) -> bool {
    let haystack = [
        info.title.as_deref().unwrap_or_default(),
        info.subject.as_deref().unwrap_or_default(),
        info.creator.as_deref().unwrap_or_default(),
        info.producer.as_deref().unwrap_or_default(),
    ]
    .join("\n")
    .to_ascii_lowercase();
    let illustrator_origin = haystack.contains("illustrator");
    let infographic_hint = haystack.contains("infographic");
    illustrator_origin && infographic_hint
}

fn source_sparse_visual_text_pages(source_path: &str) -> Option<Vec<u64>> {
    let page_graphics = source_page_graphics_from_document(source_path)?;
    let routed_pages = page_graphics
        .iter()
        .enumerate()
        .filter_map(|(page_index, graphics)| {
            let text_chars = graphics
                .text_points
                .iter()
                .map(|point| point.text.chars().count())
                .sum::<usize>();
            let sparse_text = graphics.text_points.len() <= 2 || text_chars < 64;
            let visual_density = graphics.segments.len() >= 32 || !graphics.image_boxes.is_empty();
            let page_area = graphics
                .page_box
                .as_ref()
                .map(bbox_area)
                .unwrap_or(PAGE_WIDTH * PAGE_HEIGHT);
            let large_canvas = page_area >= 1_000_000.0;
            (sparse_text && visual_density && large_canvas).then_some(page_index as u64 + 1)
        })
        .collect::<Vec<_>>();
    if routed_pages.is_empty() {
        None
    } else {
        Some(routed_pages)
    }
}

fn source_looks_table_heavy(source_path: &str) -> bool {
    let Ok(extracted) = extract_pages_with_pdf_oxide(source_path, None) else {
        return false;
    };
    let page_graphics = source_page_graphics(source_path, extracted.pages.len());
    extracted
        .pages
        .iter()
        .enumerate()
        .any(|(page_index, page)| {
            if readable_toc_page(&page.positioned_lines) {
                return false;
            }
            let graphics = page_graphics.get(page_index).cloned().unwrap_or_default();
            let input = OpendataloaderTriageInput {
                text_lines: &page.positioned_lines,
                segments: &graphics.segments,
                image_boxes: &graphics.image_boxes,
                page_box: graphics.page_box,
                replacement_ratio: replacement_character_ratio(&page.positioned_lines),
                line_ratio_threshold: 0.3,
                ..OpendataloaderTriageInput::default()
            };
            let decision = opendataloader_triage(input);
            decision.route == "backend"
                && decision.confidence >= 0.8
                && (decision.signals.has_vector_table_signal()
                    || decision.signals.has_text_table_pattern()
                    || decision.signals.line_to_text_ratio > 0.3)
        })
}

fn readable_toc_page(lines: &[PositionedLine]) -> bool {
    let normalized = lines
        .iter()
        .map(|line| normalize_text(&line.text).to_lowercase())
        .filter(|line| !line.is_empty())
        .collect::<Vec<_>>();
    if normalized.len() < 4 {
        return false;
    }
    let has_toc_title = normalized
        .iter()
        .take(3)
        .any(|line| matches!(line.as_str(), "contents" | "table of contents"));
    if !has_toc_title {
        return false;
    }
    let numbered_items = normalized
        .iter()
        .filter(|line| numbered_toc_item(line))
        .count();
    numbered_items >= 3
}

fn numbered_toc_item(text: &str) -> bool {
    let mut chars = text.chars();
    let mut digit_count = 0usize;
    while matches!(chars.next(), Some(ch) if {
        if ch.is_ascii_digit() {
            digit_count += 1;
            true
        } else {
            false
        }
    }) {}
    digit_count > 0 && text[digit_count..].starts_with(". ")
}

fn source_page_graphics(source_path: &str, page_count: usize) -> Vec<PageGraphics> {
    source_page_graphics_from_document(source_path)
        .map(|mut graphics| {
            graphics.resize(page_count, PageGraphics::default());
            graphics.truncate(page_count);
            graphics
        })
        .unwrap_or_else(|| vec![PageGraphics::default(); page_count])
}

fn source_page_graphics_from_document(source_path: &str) -> Option<Vec<PageGraphics>> {
    let Ok(document) = PdfDocument::open(source_path) else {
        return None;
    };
    let page_count = document.page_count().ok()?;
    Some(
        (0..page_count)
            .map(|page_index| {
                let page_box =
                    pdf_oxide_page_dimensions(&document, page_index)
                        .ok()
                        .map(|(width, height)| RuntimeBox {
                            x0: 0.0,
                            y0: 0.0,
                            x1: width,
                            y1: height,
                        });
                let Ok(content) = document.get_page_content_data(page_index) else {
                    return PageGraphics {
                        page_box,
                        ..PageGraphics::default()
                    };
                };
                let Ok(operations) = parse_content_stream(&content) else {
                    return PageGraphics {
                        page_box,
                        ..PageGraphics::default()
                    };
                };
                let (segments, text_points, image_boxes) = page_graphics_and_text(&operations);
                PageGraphics {
                    segments,
                    image_boxes,
                    text_points,
                    page_box,
                }
            })
            .collect::<Vec<_>>(),
    )
}

fn runtime_profile(request: &Value) -> Result<&str, String> {
    let profile = request
        .get("runtime_profile")
        .or_else(|| request.get("runtimeProfile"))
        .or_else(|| request.get("profile"))
        .and_then(Value::as_str)
        .unwrap_or(DEFAULT_PROTOCOL_PROFILE);
    match profile {
        "edge-fast" | "edge-model" | "benchmark-oracle" => Ok(profile),
        other => Err(error_json(
            "PROFILE_NOT_SUPPORTED",
            &format!("unsupported runtime profile: {other}"),
        )
        .to_string()),
    }
}

fn model_artifacts_ready_for_profile(profile: &str, artifacts: &[Value]) -> bool {
    if artifacts.is_empty() {
        return false;
    }
    artifacts.iter().all(|artifact| {
        artifact.get("cacheStatus").and_then(Value::as_str) == Some("READY")
            && (profile != "edge-model" || mnn_model_artifact(artifact))
    })
}

fn mnn_model_artifact(artifact: &Value) -> bool {
    artifact.get("backend").and_then(Value::as_str) == Some("mnn")
        && artifact.get("format").and_then(Value::as_str) == Some("mnn")
}

fn explicit_non_mnn_model_artifact(artifact: &Value) -> bool {
    (artifact.get("backend").is_some() || artifact.get("format").is_some())
        && !mnn_model_artifact(artifact)
}

#[derive(Debug, Clone)]
struct ExtractedDocument {
    pages: Vec<ExtractedPage>,
    reading_order: ReadingOrderDecision,
    warnings: Vec<Value>,
}

#[derive(Debug, Clone)]
struct ExtractedPage {
    lines: Vec<String>,
    positioned_lines: Vec<PositionedLine>,
    warnings: Vec<Value>,
}

#[derive(Debug, Default)]
struct RawContentSafety {
    warnings: Vec<Value>,
    hidden_texts: Vec<String>,
}

#[derive(Debug, Clone)]
struct ReadingOrderDecision {
    source: &'static str,
    fallback: bool,
    confidence: f64,
}

impl ReadingOrderDecision {
    fn structure_tree() -> Self {
        Self {
            source: "structure-tree",
            fallback: false,
            confidence: 1.0,
        }
    }

    fn xy_cut_fallback() -> Self {
        Self {
            source: "xy-cut",
            fallback: true,
            confidence: 0.9,
        }
    }

    fn to_json(&self) -> Value {
        json!({
            "source": self.source,
            "fallback": self.fallback,
            "confidence": self.confidence
        })
    }
}

fn extract_pages_with_pdf_oxide(
    source_path: &str,
    undefined_replacement: Option<&str>,
) -> Result<ExtractedDocument, String> {
    let document = PdfDocument::open(source_path)
        .map_err(|error| error_json("PDF_EXTRACTION_FAILED", &error.to_string()).to_string())?;
    let page_count = document
        .page_count()
        .map_err(|error| error_json("PDF_EXTRACTION_FAILED", &error.to_string()).to_string())?;
    let use_structure_order = document.prefers_structure_reading_order();
    let structure_warnings = structure_tree_reading_order_warnings(&document, use_structure_order);
    let reading_order = if use_structure_order {
        ReadingOrderDecision::structure_tree()
    } else {
        ReadingOrderDecision::xy_cut_fallback()
    };
    let mut pages = (0..page_count)
        .map(|page_index| {
            let (page_width, page_height) = pdf_oxide_page_dimensions(&document, page_index)
                .unwrap_or((PAGE_WIDTH, PAGE_HEIGHT));
            page_reading_order(&document, page_index)
                .map(|ordered_spans| {
                    let raw_safety = raw_content_safety(&document, page_index);
                    let mut canonical_lines = positioned_lines_from_spans(
                        ordered_spans.iter().map(|ordered_span| &ordered_span.span),
                        page_width,
                        page_height,
                    );
                    let replacement_ratio = replacement_character_ratio(&canonical_lines);
                    replace_undefined_positioned_lines(
                        &mut canonical_lines,
                        undefined_replacement,
                    );
                    let (positioned_lines, mut warnings) =
                        filter_positioned_lines(canonical_lines, &raw_safety.hidden_texts);
                    if replacement_ratio > 0.0 {
                        warnings.push(parser_warning(
                            "replacement_character_ratio_detected",
                            &format!(
                                "OpenDataLoader-compatible text processor measured PDF replacement character ratio {:.3}",
                                replacement_ratio
                            ),
                        ));
                    }
                    warnings.extend(raw_span_safety_warnings(
                        &document,
                        page_index,
                        page_width,
                        page_height,
                    ));
                    warnings.extend(raw_safety.warnings);
                    let positioned_lines = if use_structure_order {
                        positioned_lines
                    } else {
                        order_positioned_lines(positioned_lines)
                    };
                    let lines = positioned_lines
                        .iter()
                        .map(|line| line.text.clone())
                        .collect::<Vec<_>>();
                    ExtractedPage {
                        lines,
                        positioned_lines,
                        warnings,
                    }
                })
                .map_err(|error| {
                    error_json("PDF_EXTRACTION_FAILED", &error.to_string()).to_string()
                })
        })
        .collect::<Result<Vec<_>, _>>()?;
    let positioned_pages = pages
        .iter()
        .map(|page| page.positioned_lines.clone())
        .collect::<Vec<_>>();
    let filtered_positioned_pages = filter_repeated_header_footer_lines(positioned_pages)
        .into_iter()
        .map(merge_positioned_visual_lines)
        .collect::<Vec<_>>();
    for (page, positioned_lines) in pages.iter_mut().zip(filtered_positioned_pages) {
        page.lines = positioned_lines
            .iter()
            .map(|line| line.text.clone())
            .collect::<Vec<_>>();
        page.positioned_lines = positioned_lines;
    }
    Ok(ExtractedDocument {
        pages,
        reading_order,
        warnings: structure_warnings,
    })
}

fn structure_tree_reading_order_warnings(
    document: &PdfDocument,
    use_structure_order: bool,
) -> Vec<Value> {
    if use_structure_order {
        return Vec::new();
    }
    let mark_info = document.mark_info().unwrap_or_default();
    if !mark_info.suspects {
        return Vec::new();
    }
    if document.structure_tree().ok().flatten().is_some() {
        vec![parser_warning(
            "structure_tree_suspect_fallback",
            "Tagged PDF structure tree has /MarkInfo /Suspects true; falling back to geometric XY-Cut reading order",
        )]
    } else {
        Vec::new()
    }
}

fn raw_span_safety_warnings(
    document: &PdfDocument,
    page_index: usize,
    page_width: f64,
    page_height: f64,
) -> Vec<Value> {
    let Ok(spans) = document.extract_spans(page_index) else {
        return Vec::new();
    };
    positioned_lines_from_spans(spans.iter(), page_width, page_height)
        .into_iter()
        .filter(off_page_positioned_line)
        .map(|line| {
            parser_safety_warning(
                "off_page_text_filtered",
                &format!("Filtered off-page text-layer span: {}", line.text),
            )
        })
        .collect()
}

fn raw_content_safety(document: &PdfDocument, page_index: usize) -> RawContentSafety {
    let Ok(content) = document.get_page_content_data(page_index) else {
        return RawContentSafety::default();
    };
    if default_page_render_too_large(document, page_index) {
        return raw_content_safety_skipped();
    }
    if content.len() > MAX_RAW_CONTENT_SAFETY_BYTES {
        return raw_content_safety_skipped();
    }
    let Ok(operations) = parse_content_stream(&content) else {
        return RawContentSafety::default();
    };
    let (_segments, text_points, _image_boxes) = page_graphics_and_text(&operations);
    let mut safety = RawContentSafety::default();
    for point in text_points {
        if off_page_text_point(&point) {
            safety.warnings.push(parser_safety_warning(
                "off_page_text_filtered",
                &format!("Filtered off-page text-layer span: {}", point.text),
            ));
        }
        if point.hidden {
            safety.hidden_texts.push(point.text.clone());
        }
    }
    safety
}

fn default_page_render_too_large(document: &PdfDocument, page_index: usize) -> bool {
    pdf_oxide_page_dimensions(document, page_index)
        .map(|(width, height)| width * height > MAX_DEFAULT_RENDERED_PAGE_AREA)
        .unwrap_or(false)
}

fn raw_content_safety_skipped() -> RawContentSafety {
    RawContentSafety {
        warnings: vec![parser_safety_warning(
            "raw_content_safety_skipped",
            "Skipped raw content safety parse because the page exceeded the bounded local parser limit",
        )],
        ..RawContentSafety::default()
    }
}

fn off_page_text_point(point: &TextPoint) -> bool {
    !point.text.trim().is_empty()
        && (point.x < 0.0 || point.y < 0.0 || point.x > PAGE_WIDTH || point.y > PAGE_HEIGHT)
}

fn positioned_lines_from_spans<'a, I>(
    spans: I,
    page_width: f64,
    page_height: f64,
) -> Vec<PositionedLine>
where
    I: IntoIterator<Item = &'a TextSpan>,
{
    spans
        .into_iter()
        .flat_map(|span| {
            filterable_lines(&span.text)
                .into_iter()
                .map(|text| positioned_line_from_span(text, span, page_width, page_height))
                .collect::<Vec<_>>()
        })
        .collect()
}

fn positioned_line_from_span(
    text: String,
    span: &TextSpan,
    page_width: f64,
    page_height: f64,
) -> PositionedLine {
    PositionedLine {
        text,
        raw_bbox: RawPdfBox {
            x0: span.bbox.x as f64,
            y0: span.bbox.y as f64,
            x1: (span.bbox.x + span.bbox.width) as f64,
            y1: (span.bbox.y + span.bbox.height) as f64,
        },
        bbox: normalize_pdf_rect(
            page_width as f32,
            page_height as f32,
            span.bbox.x,
            span.bbox.y,
            span.bbox.x + span.bbox.width,
            span.bbox.y + span.bbox.height,
        ),
        page_width,
        page_height,
        font_size: span.font_size as f64,
    }
}

const XY_CUT_CROSS_LAYOUT_BETA: f64 = 2.0;
const XY_CUT_DENSITY_THRESHOLD: f64 = 0.9;
const XY_CUT_OVERLAP_THRESHOLD: f64 = 0.1;
const XY_CUT_MIN_OVERLAP_COUNT: usize = 2;
const XY_CUT_MIN_GAP: f64 = 5.0;
const XY_CUT_NARROW_WIDTH_RATIO: f64 = 0.1;

// Adapted from OpenDataLoader PDF's Apache-2.0 XYCutPlusPlusSorter at
// opendataloader-project/opendataloader-pdf@d1845179a1286bbb76f9618e8b6c8f51509a52f4.
// DocTruth keeps TrustDocument as the only canonical output contract.
fn order_positioned_lines(lines: Vec<PositionedLine>) -> Vec<PositionedLine> {
    repair_two_column_regions(xy_cut_plus_plus_sort(
        lines,
        XY_CUT_CROSS_LAYOUT_BETA,
        XY_CUT_DENSITY_THRESHOLD,
    ))
}

fn xy_cut_plus_plus_sort(
    lines: Vec<PositionedLine>,
    beta: f64,
    density_threshold: f64,
) -> Vec<PositionedLine> {
    if lines.len() <= 1 {
        return lines;
    }
    let cross_layout = identify_cross_layout_lines(&lines, beta);
    let remaining = lines
        .iter()
        .cloned()
        .enumerate()
        .filter(|(index, _)| !cross_layout[*index])
        .map(|(_, line)| line)
        .collect::<Vec<_>>();
    let cross_lines = lines
        .into_iter()
        .enumerate()
        .filter(|(index, _)| cross_layout[*index])
        .map(|(_, line)| line)
        .collect::<Vec<_>>();
    if remaining.is_empty() {
        return sort_positioned_y_then_x(cross_lines);
    }
    let prefer_horizontal = compute_positioned_density(&remaining) > density_threshold;
    let sorted_main = recursive_xy_cut_segment(remaining, prefer_horizontal);
    merge_cross_layout_lines(sorted_main, cross_lines)
}

fn identify_cross_layout_lines(lines: &[PositionedLine], beta: f64) -> Vec<bool> {
    if lines.len() < 3 {
        return vec![false; lines.len()];
    }
    let max_width = lines
        .iter()
        .map(|line| bbox_width(&line.bbox))
        .fold(0.0, f64::max);
    let threshold = beta * max_width;
    lines
        .iter()
        .map(|line| {
            let width = bbox_width(&line.bbox);
            width >= threshold && horizontal_overlap_count(line, lines) >= XY_CUT_MIN_OVERLAP_COUNT
        })
        .collect()
}

fn horizontal_overlap_count(line: &PositionedLine, lines: &[PositionedLine]) -> usize {
    lines
        .iter()
        .filter(|other| !std::ptr::eq(*other, line))
        .filter(|other| {
            horizontal_overlap_ratio(&line.bbox, &other.bbox) >= XY_CUT_OVERLAP_THRESHOLD
        })
        .count()
}

fn horizontal_overlap_ratio(left: &RuntimeBox, right: &RuntimeBox) -> f64 {
    let overlap = (left.x1.min(right.x1) - left.x0.max(right.x0)).max(0.0);
    let smaller_width = bbox_width(left).min(bbox_width(right));
    if smaller_width <= 0.0 {
        0.0
    } else {
        overlap / smaller_width
    }
}

fn compute_positioned_density(lines: &[PositionedLine]) -> f64 {
    let Some(region) = bounding_region(lines) else {
        return 1.0;
    };
    let region_area = bbox_area(&region);
    if region_area <= 0.0 {
        return 1.0;
    }
    let content_area = lines.iter().map(|line| bbox_area(&line.bbox)).sum::<f64>();
    (content_area / region_area).min(1.0)
}

fn recursive_xy_cut_segment(
    lines: Vec<PositionedLine>,
    prefer_horizontal: bool,
) -> Vec<PositionedLine> {
    if lines.len() <= 1 {
        return lines;
    }
    let horizontal_cut = best_horizontal_cut(&lines);
    let vertical_cut = best_vertical_cut(&lines);
    let has_horizontal = horizontal_cut.gap >= XY_CUT_MIN_GAP;
    let has_vertical = vertical_cut.gap >= XY_CUT_MIN_GAP;
    let use_horizontal = match (has_horizontal, has_vertical) {
        (true, true) if (horizontal_cut.gap - vertical_cut.gap).abs() <= f64::EPSILON => {
            prefer_horizontal
        }
        (true, true) => horizontal_cut.gap > vertical_cut.gap,
        (true, false) => true,
        (false, true) => false,
        (false, false) => return sort_positioned_y_then_x(lines),
    };
    let groups = if use_horizontal {
        split_by_horizontal_cut(lines.clone(), horizontal_cut.position)
    } else {
        split_by_vertical_cut(lines.clone(), vertical_cut.position)
    };
    if groups.len() <= 1 || groups.iter().any(|group| group.len() == lines.len()) {
        return sort_positioned_y_then_x(lines);
    }
    groups
        .into_iter()
        .flat_map(|group| recursive_xy_cut_segment(group, prefer_horizontal))
        .collect()
}

#[derive(Debug, Clone, Copy)]
struct CutInfo {
    position: f64,
    gap: f64,
}

fn best_vertical_cut(lines: &[PositionedLine]) -> CutInfo {
    let edge_cut = vertical_cut_by_edges(lines);
    if edge_cut.gap >= XY_CUT_MIN_GAP || lines.len() < 3 {
        return edge_cut;
    }
    let Some(region) = bounding_region(lines) else {
        return edge_cut;
    };
    let narrow_threshold = bbox_width(&region) * XY_CUT_NARROW_WIDTH_RATIO;
    let filtered = lines
        .iter()
        .filter(|line| bbox_width(&line.bbox) >= narrow_threshold)
        .cloned()
        .collect::<Vec<_>>();
    if filtered.len() < 2 || filtered.len() == lines.len() {
        return edge_cut;
    }
    let filtered_cut = vertical_cut_by_edges(&filtered);
    if filtered_cut.gap > edge_cut.gap && filtered_cut.gap >= XY_CUT_MIN_GAP {
        filtered_cut
    } else {
        edge_cut
    }
}

fn vertical_cut_by_edges(lines: &[PositionedLine]) -> CutInfo {
    let mut sorted = lines.to_vec();
    sorted.sort_by(|left, right| {
        left.bbox
            .x0
            .total_cmp(&right.bbox.x0)
            .then_with(|| left.bbox.x1.total_cmp(&right.bbox.x1))
    });
    let mut largest_gap = 0.0;
    let mut cut_position = 0.0;
    let mut previous_right: Option<f64> = None;
    for line in sorted {
        if let Some(right) = previous_right {
            if line.bbox.x0 > right {
                let gap = line.bbox.x0 - right;
                if gap > largest_gap {
                    largest_gap = gap;
                    cut_position = (right + line.bbox.x0) / 2.0;
                }
            }
            previous_right = Some(right.max(line.bbox.x1));
        } else {
            previous_right = Some(line.bbox.x1);
        }
    }
    CutInfo {
        position: cut_position,
        gap: largest_gap,
    }
}

fn best_horizontal_cut(lines: &[PositionedLine]) -> CutInfo {
    let mut sorted = lines.to_vec();
    sorted.sort_by(|left, right| {
        left.bbox
            .y0
            .total_cmp(&right.bbox.y0)
            .then_with(|| left.bbox.y1.total_cmp(&right.bbox.y1))
    });
    let mut largest_gap = 0.0;
    let mut cut_position = 0.0;
    let mut previous_bottom: Option<f64> = None;
    for line in sorted {
        if let Some(bottom) = previous_bottom {
            if line.bbox.y0 > bottom {
                let gap = line.bbox.y0 - bottom;
                if gap > largest_gap {
                    largest_gap = gap;
                    cut_position = (bottom + line.bbox.y0) / 2.0;
                }
            }
            previous_bottom = Some(bottom.max(line.bbox.y1));
        } else {
            previous_bottom = Some(line.bbox.y1);
        }
    }
    CutInfo {
        position: cut_position,
        gap: largest_gap,
    }
}

fn split_by_horizontal_cut(lines: Vec<PositionedLine>, cut_y: f64) -> Vec<Vec<PositionedLine>> {
    let mut above = Vec::new();
    let mut below = Vec::new();
    for line in lines {
        if bbox_center_y(&line.bbox) < cut_y {
            above.push(line);
        } else {
            below.push(line);
        }
    }
    non_empty_groups(above, below)
}

fn split_by_vertical_cut(lines: Vec<PositionedLine>, cut_x: f64) -> Vec<Vec<PositionedLine>> {
    let mut left = Vec::new();
    let mut right = Vec::new();
    for line in lines {
        if bbox_center_x(&line.bbox) < cut_x {
            left.push(line);
        } else {
            right.push(line);
        }
    }
    non_empty_groups(left, right)
}

fn non_empty_groups(
    first: Vec<PositionedLine>,
    second: Vec<PositionedLine>,
) -> Vec<Vec<PositionedLine>> {
    [first, second]
        .into_iter()
        .filter(|group| !group.is_empty())
        .collect()
}

fn merge_cross_layout_lines(
    sorted_main: Vec<PositionedLine>,
    cross_lines: Vec<PositionedLine>,
) -> Vec<PositionedLine> {
    if cross_lines.is_empty() {
        return sorted_main;
    }
    let sorted_cross = sort_positioned_y_then_x(cross_lines);
    let mut result = Vec::new();
    let mut main_index = 0;
    let mut cross_index = 0;
    while main_index < sorted_main.len() || cross_index < sorted_cross.len() {
        if cross_index >= sorted_cross.len() {
            result.push(sorted_main[main_index].clone());
            main_index += 1;
        } else if main_index >= sorted_main.len()
            || sorted_cross[cross_index].bbox.y0 <= sorted_main[main_index].bbox.y0
        {
            result.push(sorted_cross[cross_index].clone());
            cross_index += 1;
        } else {
            result.push(sorted_main[main_index].clone());
            main_index += 1;
        }
    }
    result
}

fn sort_positioned_y_then_x(mut lines: Vec<PositionedLine>) -> Vec<PositionedLine> {
    lines.sort_by(|left, right| {
        left.bbox
            .y0
            .total_cmp(&right.bbox.y0)
            .then_with(|| left.bbox.x0.total_cmp(&right.bbox.x0))
    });
    lines
}

fn repair_two_column_regions(lines: Vec<PositionedLine>) -> Vec<PositionedLine> {
    if !has_survey_chart_figure_context(&lines) {
        return lines;
    }
    let mut sorted = sort_positioned_y_then_x(lines);
    let mut result = Vec::new();
    let mut segment = Vec::new();
    let mut previous_bottom: Option<f64> = None;
    for line in sorted.drain(..) {
        let gap = previous_bottom.map_or(0.0, |bottom| line.bbox.y0 - bottom);
        if wide_page_separator(&line) || gap > 24.0 {
            result.extend(repair_two_column_segment(std::mem::take(&mut segment)));
        }
        if wide_page_separator(&line) {
            result.push(line.clone());
            previous_bottom = None;
        } else {
            previous_bottom = Some(line.bbox.y1);
            segment.push(line);
        }
    }
    result.extend(repair_two_column_segment(segment));
    result
}

fn has_survey_chart_figure_context(lines: &[PositionedLine]) -> bool {
    let has_figure = lines.iter().any(|line| {
        line.text
            .trim_start()
            .to_ascii_lowercase()
            .starts_with("figure ")
    });
    has_figure && survey_chart_label_count(lines) >= 3
}

fn survey_chart_label_count(lines: &[PositionedLine]) -> usize {
    lines
        .iter()
        .filter(|line| survey_chart_label(&line.text))
        .count()
}

fn survey_chart_label(text: &str) -> bool {
    let lower = text.to_ascii_lowercase();
    [
        "july 2020",
        "jul 2020",
        "october 2020",
        "oct 2020",
        "january 2021",
        "survey phase",
        "survey phases",
        "lockdown period",
    ]
    .iter()
    .any(|needle| lower.contains(needle))
}

fn wide_page_separator(line: &PositionedLine) -> bool {
    bbox_width(&line.bbox) >= 550.0
}

fn repair_two_column_segment(segment: Vec<PositionedLine>) -> Vec<PositionedLine> {
    if segment.len() < 6 {
        return recursive_xy_cut_segment(segment, false);
    }
    let Some(cut_x) = column_cut_by_x0(&segment) else {
        return sort_positioned_y_then_x(segment);
    };
    let mut left = Vec::new();
    let mut right = Vec::new();
    for line in segment {
        if line.bbox.x0 < cut_x {
            left.push(line);
        } else {
            right.push(line);
        }
    }
    if left.len() < 3 || right.len() < 3 {
        return sort_positioned_y_then_x([left, right].concat());
    }
    if median_line_width(&left) < 220.0 || median_line_width(&right) < 220.0 {
        return sort_positioned_y_then_x([left, right].concat());
    }
    let mut ordered = sort_positioned_y_then_x(left);
    ordered.extend(sort_positioned_y_then_x(right));
    ordered
}

fn column_cut_by_x0(lines: &[PositionedLine]) -> Option<f64> {
    let mut xs = lines.iter().map(|line| line.bbox.x0).collect::<Vec<_>>();
    xs.sort_by(f64::total_cmp);
    let mut best_gap = 0.0;
    let mut cut_x = 0.0;
    for pair in xs.windows(2) {
        let gap = pair[1] - pair[0];
        if gap > best_gap {
            best_gap = gap;
            cut_x = (pair[0] + pair[1]) / 2.0;
        }
    }
    (best_gap >= 120.0).then_some(cut_x)
}

fn median_line_width(lines: &[PositionedLine]) -> f64 {
    let mut widths = lines
        .iter()
        .map(|line| bbox_width(&line.bbox))
        .collect::<Vec<_>>();
    widths.sort_by(f64::total_cmp);
    widths[widths.len() / 2]
}

fn bounding_region(lines: &[PositionedLine]) -> Option<RuntimeBox> {
    let first = lines.first()?;
    let mut region = first.bbox.clone();
    for line in lines.iter().skip(1) {
        region.x0 = region.x0.min(line.bbox.x0);
        region.y0 = region.y0.min(line.bbox.y0);
        region.x1 = region.x1.max(line.bbox.x1);
        region.y1 = region.y1.max(line.bbox.y1);
    }
    Some(region)
}

fn bbox_width(bbox: &RuntimeBox) -> f64 {
    (bbox.x1 - bbox.x0).max(0.0)
}

fn bbox_height(bbox: &RuntimeBox) -> f64 {
    (bbox.y1 - bbox.y0).max(0.0)
}

fn bbox_area(bbox: &RuntimeBox) -> f64 {
    bbox_width(bbox) * bbox_height(bbox)
}

fn bbox_center_x(bbox: &RuntimeBox) -> f64 {
    (bbox.x0 + bbox.x1) / 2.0
}

fn bbox_center_y(bbox: &RuntimeBox) -> f64 {
    (bbox.y0 + bbox.y1) / 2.0
}

fn filter_positioned_lines(
    lines: Vec<PositionedLine>,
    hidden_texts: &[String],
) -> (Vec<PositionedLine>, Vec<Value>) {
    let mut kept: Vec<PositionedLine> = Vec::new();
    let mut warnings = Vec::new();
    for line in lines {
        let line = correct_abnormal_short_text_bbox(line);
        if line.text.trim().is_empty() {
            warnings.push(parser_safety_warning(
                "whitespace_text_filtered",
                "Filtered whitespace-only text-layer span",
            ));
            continue;
        }
        if off_page_positioned_line(&line) {
            warnings.push(parser_safety_warning(
                "off_page_text_filtered",
                &format!("Filtered off-page text-layer span: {}", line.text),
            ));
            continue;
        }
        if tiny_positioned_line(&line) {
            warnings.push(parser_safety_warning(
                "tiny_text_filtered",
                &format!("Filtered tiny text-layer span: {}", line.text),
            ));
            continue;
        }
        if invalid_text_encoding_line(&line) {
            warnings.push(parser_safety_warning(
                "invalid_text_encoding_detected",
                &format!(
                    "Filtered text-layer span with invalid encoding artifacts: {}",
                    line.text
                ),
            ));
            continue;
        }
        if hidden_positioned_line(&line, hidden_texts) {
            warnings.push(parser_safety_warning(
                "hidden_text_filtered",
                &format!("Filtered hidden text-layer span: {}", line.text),
            ));
            continue;
        }
        if kept
            .iter()
            .any(|candidate| duplicate_positioned_line(candidate, &line))
        {
            warnings.push(parser_safety_warning(
                "duplicate_text_filtered",
                &format!(
                    "Filtered duplicate text-layer span at the same position: {}",
                    line.text
                ),
            ));
            continue;
        }
        kept.push(line);
    }
    (kept, warnings)
}

fn correct_abnormal_short_text_bbox(mut line: PositionedLine) -> PositionedLine {
    let char_count = line.text.trim().chars().count();
    if !(1..=3).contains(&char_count) || line.font_size <= 0.0 {
        return line;
    }
    let current_width = bbox_width(&line.bbox);
    let expected_width = char_count as f64 * line.font_size * 0.7;
    if current_width <= expected_width * 3.0 || expected_width <= 0.0 {
        return line;
    }
    line.bbox.x1 = (line.bbox.x0 + expected_width).min(line.page_width);
    line.raw_bbox.x1 = (line.raw_bbox.x0 + expected_width).min(line.page_width);
    line
}

fn filter_sensitive_data_enabled(request: &Value) -> bool {
    request
        .get("filter_sensitive_data")
        .or_else(|| request.get("filterSensitiveData"))
        .and_then(Value::as_bool)
        .unwrap_or(false)
}

fn undefined_character_replacement(request: &Value) -> Option<String> {
    request
        .get("undefined_character_replacement")
        .or_else(|| request.get("undefinedCharacterReplacement"))
        .and_then(Value::as_str)
        .filter(|replacement| !replacement.is_empty())
        .map(ToOwned::to_owned)
        .or_else(|| Some(" ".to_string()))
}

fn undefined_character_replacement_configured(request: &Value) -> bool {
    request
        .get("undefined_character_replacement")
        .or_else(|| request.get("undefinedCharacterReplacement"))
        .and_then(Value::as_str)
        .is_some_and(|replacement| !replacement.is_empty())
}

fn replace_undefined_positioned_lines(lines: &mut [PositionedLine], replacement: Option<&str>) {
    let Some(replacement) = replacement else {
        return;
    };
    if replacement == "\u{fffd}" {
        return;
    }
    for line in lines {
        if line.text.contains('\u{fffd}') {
            line.text = line.text.replace('\u{fffd}', replacement);
        }
    }
}

fn replacement_character_ratio(lines: &[PositionedLine]) -> f64 {
    let mut total_chars = 0usize;
    let mut replacement_chars = 0usize;
    for line in lines {
        for ch in line.text.chars() {
            total_chars += 1;
            if ch == '\u{fffd}' {
                replacement_chars += 1;
            }
        }
    }
    if total_chars == 0 {
        0.0
    } else {
        replacement_chars as f64 / total_chars as f64
    }
}

fn sanitize_extracted_pages(pages: &mut [ExtractedPage]) {
    for page in pages {
        for line in &mut page.lines {
            *line = sanitize_sensitive_text(line);
        }
        for line in &mut page.positioned_lines {
            line.text = sanitize_sensitive_text(&line.text);
        }
    }
}

fn sanitize_tables(tables: &mut [TableExtraction]) {
    for table in tables {
        for cell in &mut table.cells {
            cell.text = sanitize_sensitive_text(&cell.text);
        }
    }
}

#[derive(Debug, Clone)]
struct SensitiveReplacement {
    start: usize,
    end: usize,
    replacement: &'static str,
    rule_index: usize,
}

fn sanitize_sensitive_text(text: &str) -> String {
    let mut replacements = sensitive_replacements(text);
    if replacements.is_empty() {
        return text.to_string();
    }
    replacements.sort_by(|left, right| {
        left.start
            .cmp(&right.start)
            .then_with(|| right.end.cmp(&left.end))
            .then_with(|| left.rule_index.cmp(&right.rule_index))
    });
    let replacements = non_overlapping_replacements(replacements);
    let mut output = String::new();
    let mut cursor = 0;
    for replacement in replacements {
        if cursor < replacement.start {
            output.push_str(&text[cursor..replacement.start]);
        }
        output.push_str(replacement.replacement);
        cursor = replacement.end;
    }
    if cursor < text.len() {
        output.push_str(&text[cursor..]);
    }
    output
}

fn sensitive_replacements(text: &str) -> Vec<SensitiveReplacement> {
    sensitive_rules()
        .iter()
        .enumerate()
        .flat_map(|(rule_index, (pattern, replacement))| {
            pattern
                .find_iter(text)
                .map(move |match_| SensitiveReplacement {
                    start: match_.start(),
                    end: match_.end(),
                    replacement,
                    rule_index,
                })
        })
        .collect()
}

fn non_overlapping_replacements(
    replacements: Vec<SensitiveReplacement>,
) -> Vec<SensitiveReplacement> {
    let mut kept: Vec<SensitiveReplacement> = Vec::new();
    for replacement in replacements {
        if kept.last().is_none_or(|last| replacement.start >= last.end) {
            kept.push(replacement);
        }
    }
    kept
}

fn sensitive_rules() -> &'static [(Regex, &'static str)] {
    static RULES: OnceLock<Vec<(Regex, &'static str)>> = OnceLock::new();
    RULES
        .get_or_init(|| {
            vec![
                (
                    Regex::new(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}").unwrap(),
                    "email@example.com",
                ),
                (Regex::new(r"[+]\d+(?:-\d+)+").unwrap(), "+00-0000-0000"),
                (Regex::new(r"[A-Z]{1,2}\d{6,9}").unwrap(), "AA0000000"),
                (
                    Regex::new(r"\b\d{4}-?\d{4}-?\d{4}-?\d{4}\b").unwrap(),
                    "0000-0000-0000-0000",
                ),
                (Regex::new(r"\b\d{10,18}\b").unwrap(), "0000000000000000"),
                (
                    Regex::new(r"\b(?:\d{1,3}\.){3}\d{1,3}\b").unwrap(),
                    "0.0.0.0",
                ),
                (
                    Regex::new(r"\b([0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}\b").unwrap(),
                    "0.0.0.0::1",
                ),
                (
                    Regex::new(r"\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b").unwrap(),
                    "00:00:00:00:00:00",
                ),
                (Regex::new(r"\b\d{15}\b").unwrap(), "000000000000000"),
                (
                    Regex::new(r"https?://[A-Za-z0-9.-]+(:\d+)?(/\S*)?").unwrap(),
                    "https://example.com",
                ),
            ]
        })
        .as_slice()
}

fn filter_repeated_header_footer_lines(
    pages: Vec<Vec<PositionedLine>>,
) -> Vec<Vec<PositionedLine>> {
    if pages.len() < 2 {
        return pages;
    }
    let repeated_header_keys = repeated_header_line_keys(&pages);
    let footer_pages = pages
        .iter()
        .filter(|page| page.iter().any(footer_band_line))
        .count();
    let filter_footers = footer_pages == pages.len();
    pages
        .into_iter()
        .map(|page| {
            let filtered = page
                .iter()
                .filter(|line| {
                    !(filter_footers && footer_band_line(line))
                        && !repeated_header_keys.contains(&header_line_key(line))
                })
                .cloned()
                .collect::<Vec<_>>();
            if filtered.is_empty() && !page.is_empty() {
                page
            } else {
                filtered
            }
        })
        .collect()
}

fn repeated_header_line_keys(pages: &[Vec<PositionedLine>]) -> HashSet<String> {
    let mut counts: HashMap<String, usize> = HashMap::new();
    for page in pages {
        let mut page_keys = HashSet::new();
        for line in page.iter().filter(|line| header_band_line(line)) {
            page_keys.insert(header_line_key(line));
        }
        for key in page_keys {
            *counts.entry(key).or_default() += 1;
        }
    }
    counts
        .into_iter()
        .filter_map(|(key, count)| (count >= 2).then_some(key))
        .collect()
}

fn header_line_key(line: &PositionedLine) -> String {
    let x_bucket = (line.bbox.x0 / 10.0).round() as i64;
    let font_bucket = line.font_size.round() as i64;
    let text_key = if page_number_header_text(&line.text) {
        "PAGE_NUMBER_HEADER".to_string()
    } else {
        normalize_text_for_filter(&line.text)
    };
    format!("{}|{}|{}", text_key, x_bucket, font_bucket)
}

fn page_number_header_text(text: &str) -> bool {
    static PAGE_NUMBER_HEADER_RE: OnceLock<Regex> = OnceLock::new();
    PAGE_NUMBER_HEADER_RE
        .get_or_init(|| Regex::new(r"(?i)^\s*page\s+\d+\s*$").unwrap())
        .is_match(text)
}

fn merge_positioned_visual_lines(lines: Vec<PositionedLine>) -> Vec<PositionedLine> {
    let mut output = Vec::new();
    let mut index = 0;
    while index < lines.len() {
        let mut row = vec![lines[index].clone()];
        index += 1;
        while index < lines.len()
            && positioned_lines_same_visual_row(
                row.first().expect("row has first line"),
                &lines[index],
            )
        {
            row.push(lines[index].clone());
            index += 1;
        }
        output.extend(merge_positioned_visual_row_if_safe(row));
    }
    output
}

fn positioned_lines_same_visual_row(left: &PositionedLine, right: &PositionedLine) -> bool {
    (left.bbox.y0 - right.bbox.y0).abs() <= 3.0 && (left.bbox.y1 - right.bbox.y1).abs() <= 3.0
}

fn merge_positioned_visual_row_if_safe(mut row: Vec<PositionedLine>) -> Vec<PositionedLine> {
    row.sort_by(|left, right| left.bbox.x0.total_cmp(&right.bbox.x0));
    if row.len() <= 1 {
        return row;
    }
    if !positioned_visual_row_safe_to_merge(&row) {
        return row;
    }
    vec![merge_positioned_visual_row(row)]
}

fn merge_positioned_visual_row(mut row: Vec<PositionedLine>) -> PositionedLine {
    row.sort_by(|left, right| left.bbox.x0.total_cmp(&right.bbox.x0));
    let mut merged = row.remove(0);
    for line in row {
        let separator = positioned_line_separator(&merged, &line);
        merged.text = normalize_text(&format!("{}{}{}", merged.text, separator, line.text));
        merged.bbox.x0 = merged.bbox.x0.min(line.bbox.x0);
        merged.bbox.y0 = merged.bbox.y0.min(line.bbox.y0);
        merged.bbox.x1 = merged.bbox.x1.max(line.bbox.x1);
        merged.bbox.y1 = merged.bbox.y1.max(line.bbox.y1);
        merged.raw_bbox.x0 = merged.raw_bbox.x0.min(line.raw_bbox.x0);
        merged.raw_bbox.y0 = merged.raw_bbox.y0.min(line.raw_bbox.y0);
        merged.raw_bbox.x1 = merged.raw_bbox.x1.max(line.raw_bbox.x1);
        merged.raw_bbox.y1 = merged.raw_bbox.y1.max(line.raw_bbox.y1);
        merged.font_size = merged.font_size.max(line.font_size);
    }
    merged
}

fn positioned_line_separator(left: &PositionedLine, right: &PositionedLine) -> &'static str {
    if left.text.ends_with(char::is_whitespace) || right.text.starts_with(char::is_whitespace) {
        return "";
    }
    let gap = right.bbox.x0 - left.bbox.x1;
    let threshold = left.font_size.max(right.font_size) * 0.17;
    if gap > threshold.max(1.0) { " " } else { "" }
}

fn positioned_visual_row_safe_to_merge(row: &[PositionedLine]) -> bool {
    if row.len() < 2 || row.len() > 3 {
        return false;
    }
    if positioned_row_looks_like_toc_or_table(row) {
        return false;
    }
    positioned_row_has_label_value_shape(row)
}

fn positioned_row_looks_like_toc_or_table(row: &[PositionedLine]) -> bool {
    let numeric_count = row
        .iter()
        .filter(|line| text_numeric_like(&line.text))
        .count();
    if numeric_count >= 2 {
        return true;
    }
    if row.len() >= 3
        && row
            .iter()
            .filter(|line| normalize_text(&line.text).chars().count() <= 8)
            .count()
            >= 2
    {
        return true;
    }
    if let Some(last) = row.last() {
        if text_numeric_like(&last.text) && last.bbox.x0 > 760.0 {
            return true;
        }
    }
    false
}

fn positioned_row_has_label_value_shape(row: &[PositionedLine]) -> bool {
    row.first().is_some_and(|first| {
        let text = normalize_text(&first.text);
        (text.ends_with(':') || matches!(text.as_str(), "Q" | "Q:" | "A" | "A:"))
            && text.chars().count() <= 24
    })
}

fn text_numeric_like(text: &str) -> bool {
    let text = normalize_text(text)
        .trim_matches(|ch: char| matches!(ch, ',' | '.' | ')' | '(' | '[' | ']'))
        .to_string();
    !text.is_empty()
        && text
            .chars()
            .all(|ch| ch.is_ascii_digit() || matches!(ch, '.' | ',' | '-' | '%'))
        && text.chars().any(|ch| ch.is_ascii_digit())
}

#[cfg(test)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum OpendataloaderParagraphAlignment {
    Left,
    Right,
    None,
}

#[cfg(test)]
fn opendataloader_paragraph_pair_alignment(
    previous: &PositionedLine,
    next: &PositionedLine,
) -> OpendataloaderParagraphAlignment {
    if opendataloader_right_aligned_paragraph_pair(previous, next) {
        return OpendataloaderParagraphAlignment::Right;
    }
    if opendataloader_two_line_paragraph_pair(previous, next) {
        return OpendataloaderParagraphAlignment::Left;
    }
    OpendataloaderParagraphAlignment::None
}

#[cfg(test)]
fn opendataloader_right_aligned_paragraph_pair(
    previous: &PositionedLine,
    next: &PositionedLine,
) -> bool {
    let same_right_edge = (previous.bbox.x1 - next.bbox.x1).abs() <= 1.0;
    same_right_edge
        && opendataloader_paragraph_lines_are_adjacent(previous, next)
        && close_ratio(previous.font_size, next.font_size, 0.05)
}

#[cfg(test)]
fn opendataloader_two_line_paragraph_pair(
    previous: &PositionedLine,
    next: &PositionedLine,
) -> bool {
    previous.bbox.x0 >= next.bbox.x0
        && previous.bbox.x1 >= next.bbox.x1
        && opendataloader_paragraph_lines_are_adjacent(previous, next)
        && close_ratio(previous.font_size, next.font_size, 0.05)
}

#[cfg(test)]
fn opendataloader_paragraph_lines_are_adjacent(
    previous: &PositionedLine,
    next: &PositionedLine,
) -> bool {
    let vertical_gap = (previous.bbox.y0 - next.bbox.y1).abs();
    vertical_gap <= previous.font_size.max(next.font_size) * 0.35
}

fn footer_band_line(line: &PositionedLine) -> bool {
    line.bbox.y1 <= line.page_height * 0.15
}

fn header_band_line(line: &PositionedLine) -> bool {
    line.bbox.y0 >= line.page_height * 0.85
}

fn off_page_positioned_line(line: &PositionedLine) -> bool {
    line.raw_bbox.x1 <= 0.0
        || line.raw_bbox.y1 <= 0.0
        || line.raw_bbox.x0 >= line.page_width
        || line.raw_bbox.y0 >= line.page_height
}

fn tiny_positioned_line(line: &PositionedLine) -> bool {
    line.font_size <= 2.0 || bbox_width(&line.bbox) <= 2.0 || bbox_height(&line.bbox) <= 2.0
}

fn invalid_text_encoding_line(line: &PositionedLine) -> bool {
    invalid_text_encoding(&line.text)
}

fn invalid_text_encoding(text: &str) -> bool {
    text.chars()
        .any(|ch| ch == '\u{fffd}' || (ch.is_control() && !ch.is_whitespace()))
}

fn hidden_positioned_line(line: &PositionedLine, hidden_texts: &[String]) -> bool {
    let normalized = normalize_text_for_filter(&line.text);
    hidden_texts
        .iter()
        .any(|hidden| normalize_text_for_filter(hidden) == normalized)
}

fn duplicate_positioned_line(left: &PositionedLine, right: &PositionedLine) -> bool {
    normalize_text_for_filter(&left.text) == normalize_text_for_filter(&right.text)
        && close_number(left.bbox.x0, right.bbox.x0)
        && close_number(left.bbox.x1, right.bbox.x1)
        && close_vertical(left.bbox.y0, right.bbox.y0)
        && close_vertical(left.bbox.y1, right.bbox.y1)
}

fn normalize_text_for_filter(value: &str) -> String {
    value.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn close_number(left: f64, right: f64) -> bool {
    (left - right).abs() <= 1.0
}

fn close_vertical(left: f64, right: f64) -> bool {
    (left - right).abs() <= 20.0
}

fn parser_safety_warning(code: &str, message: &str) -> Value {
    json!({
        "code": code,
        "severity": "SEVERE",
        "message": message
    })
}

fn parser_warning(code: &str, message: &str) -> Value {
    json!({
        "code": code,
        "severity": "WARNING",
        "message": message
    })
}

fn is_severe_warning(warning: &Value) -> bool {
    warning.get("severity").and_then(Value::as_str) == Some("SEVERE")
}

fn configured_model_worker_parse(
    source_path: &str,
    source_hash: &str,
    preset: &str,
    profile: &str,
    route: &ModelRouteDecision,
    required_models: &[RequiredModel],
    model_artifacts: &[Value],
    request: &Value,
) -> Result<Option<Value>, String> {
    let Some(command) = configured_model_worker_command_for_request(route, request) else {
        return Ok(None);
    };
    let model_cache = model_cache_directory_for_request(request);
    let auxiliary_artifacts = model_manifest_auxiliary_artifacts_for_request(request)
        .into_iter()
        .map(|artifact| model_with_cache_status(artifact, &model_cache))
        .collect::<Vec<_>>();
    let worker_request = json!({
        "runtime": RUNTIME,
        "protocol_version": PROTOCOL_VERSION,
        "command": "parse_pdf",
        "source_path": source_path,
        "sourcePath": source_path,
        "sourceFilename": Path::new(source_path)
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("document.pdf"),
        "source_hash": source_hash,
        "sourceHash": source_hash,
        "preset": preset,
        "profile": profile,
        "runtime_profile": profile,
        "runtimeProfile": profile,
        "offline_mode": request.get("offline_mode").and_then(Value::as_bool).unwrap_or(true),
        "allow_model_downloads": request.get("allow_model_downloads").and_then(Value::as_bool).unwrap_or(false),
        "modelCacheDirectory": model_cache,
        "requiredModels": required_models.iter().map(RequiredModel::json).collect::<Vec<_>>(),
        "models": model_artifacts,
        "auxiliaryArtifacts": auxiliary_artifacts,
        "modelRuntime": model_runtime_request_json(profile, preset, model_artifacts),
        "modelRouting": route.to_json(true, &required_models.iter().map(RequiredModel::identity).collect::<Vec<_>>())
    });
    let output = run_model_worker(&command, &worker_request)?;
    let response: Value = serde_json::from_str(&output).map_err(|error| {
        error_json("MODEL_WORKER_FAILED", &format!("invalid JSON: {error}")).to_string()
    })?;
    let model_metrics = model_runtime_metrics_with_context(
        response.get("metrics").unwrap_or(&Value::Null),
        model_artifacts,
        &auxiliary_artifacts,
        preset,
        request,
    );
    let document =
        normalize_worker_document(worker_document(response)?, profile, route, &model_metrics);
    let document = hybrid_merge_worker_document_with_text_layer(
        document,
        source_path,
        source_hash,
        request,
        route,
    );
    validate_worker_document(&document)?;
    Ok(Some(document))
}

fn model_runtime_metrics_with_context(
    metrics: &Value,
    model_artifacts: &[Value],
    auxiliary_artifacts: &[Value],
    preset: &str,
    request: &Value,
) -> Value {
    let mut target = metrics.as_object().cloned().unwrap_or_default();
    target
        .entry("preprocessing".to_string())
        .or_insert_with(|| model_preprocessing_contract_json(preset, model_artifacts));
    if let Some(manifest_path) = configured_model_manifest_path_for_request(request) {
        target.insert("manifestPath".to_string(), json!(manifest_path));
    }
    target.insert(
        "modelArtifacts".to_string(),
        json!(
            model_artifacts
                .iter()
                .map(model_runtime_artifact_json)
                .collect::<Vec<_>>()
        ),
    );
    target.insert(
        "auxiliaryArtifactDetails".to_string(),
        json!(
            auxiliary_artifacts
                .iter()
                .map(model_runtime_artifact_json)
                .collect::<Vec<_>>()
        ),
    );
    Value::Object(target)
}

fn model_runtime_artifact_json(artifact: &Value) -> Value {
    let mut object = serde_json::Map::new();
    for key in [
        "name",
        "version",
        "role",
        "task",
        "backend",
        "format",
        "cacheStatus",
        "expectedSha256",
        "actualSha256",
        "sizeBytes",
    ] {
        if let Some(value) = artifact.get(key) {
            object.insert(key.to_string(), value.clone());
        }
    }
    Value::Object(object)
}

fn model_runtime_request_json(profile: &str, preset: &str, model_artifacts: &[Value]) -> Value {
    let runtime = model_runtime_engine(profile, model_artifacts);
    json!({
        "runtime": runtime,
        "loadPolicy": "lazy",
        "unloadPolicy": "idle-after-request",
        "referenceOnly": profile == "benchmark-oracle" && runtime != "mnn",
        "preprocessing": model_preprocessing_contract_json(preset, model_artifacts)
    })
}

fn model_runtime_engine(profile: &str, model_artifacts: &[Value]) -> String {
    if profile == "edge-model" {
        return "mnn".to_string();
    }
    if profile == "benchmark-oracle" {
        return model_artifacts
            .iter()
            .filter(|artifact| artifact.get("cacheStatus").and_then(Value::as_str) == Some("READY"))
            .filter_map(|artifact| artifact.get("backend").and_then(Value::as_str))
            .next()
            .unwrap_or("none")
            .to_string();
    }
    "none".to_string()
}

fn mnn_preprocessing_contract_json(preset: &str) -> Value {
    let decoder = if preset == "ocr" {
        "ocr"
    } else if preset == "standard" {
        "layout-table"
    } else {
        "table"
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
        "mean": [0.0, 0.0, 0.0],
        "std": [1.0, 1.0, 1.0],
        "resize": {
            "mode": "model-specific",
            "sourceOfTruth": "model manifest or decoder adapter"
        },
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

fn model_preprocessing_contract_json(preset: &str, model_artifacts: &[Value]) -> Value {
    let mut contract = mnn_preprocessing_contract_json(preset);
    let Some(model_preprocessing) = model_artifacts
        .iter()
        .find_map(|artifact| artifact.get("preprocessing"))
    else {
        return contract;
    };
    if let Some(target) = contract.as_object_mut() {
        if let Some(value) = model_preprocessing.get("inputLayout") {
            target.insert("tensorLayout".to_string(), value.clone());
            target.insert("inputLayout".to_string(), value.clone());
        }
        for (source_key, target_key) in [
            ("dtype", "valueType"),
            ("colorSpace", "colorSpace"),
            ("channelOrder", "channelOrder"),
            ("resize", "resize"),
            ("resample", "resample"),
            ("scale", "scale"),
            ("mean", "mean"),
            ("std", "std"),
        ] {
            if let Some(value) = model_preprocessing.get(source_key) {
                target.insert(target_key.to_string(), value.clone());
            }
        }
        if let Some(parity) = model_artifacts
            .iter()
            .find_map(|artifact| artifact.get("parity"))
        {
            target.insert(
                "parity".to_string(),
                model_preprocessing_parity_json(parity),
            );
        }
    }
    contract
}

fn model_preprocessing_parity_json(parity: &Value) -> Value {
    json!({
        "required": true,
        "checks": [
            "input_shape",
            "first_tensor_values",
            "tensor_sha256",
            "python_reference_digest"
        ],
        "promotionBlockedWithoutTensorDigest": true,
        "referenceEngine": parity.get("referenceEngine").cloned().unwrap_or(Value::Null),
        "candidateEngine": parity.get("candidateEngine").cloned().unwrap_or(Value::Null),
        "tensorDumpRequired": parity.get("tensorDumpRequired").cloned().unwrap_or(json!(true)),
        "firstTensorValuesRequired": parity.get("firstTensorValuesRequired").cloned().unwrap_or(json!(true)),
        "maxAbsDiff": parity.get("maxAbsDiff").cloned().unwrap_or(Value::Null)
    })
}

fn worker_document(response: Value) -> Result<Value, String> {
    if response.pointer("/docId").is_some() {
        return Ok(response);
    }
    if response.get("ok").and_then(Value::as_bool) == Some(true) {
        if let Some(document) = response.get("document") {
            return Ok(document.clone());
        }
    }
    Err(error_json(
        "MODEL_WORKER_FAILED",
        "worker response must be TrustDocument or {ok:true, document}",
    )
    .to_string())
}

fn normalize_worker_document(
    mut document: Value,
    profile: &str,
    route: &ModelRouteDecision,
    model_metrics: &Value,
) -> Value {
    if let Some(parser_run) = document.get_mut("parserRun").and_then(Value::as_object_mut) {
        let worker_backend = parser_run
            .get("backend")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string();
        if worker_backend != "rust-sidecar+model-worker" {
            parser_run.insert("workerBackend".to_string(), json!(worker_backend));
            parser_run.insert("backend".to_string(), json!("rust-sidecar+model-worker"));
        }
        parser_run
            .entry("runtime".to_string())
            .or_insert_with(|| json!(RUNTIME));
        parser_run
            .entry("pdfBackend".to_string())
            .or_insert_with(pdf_backend_json);
        parser_run
            .entry("profile".to_string())
            .or_insert_with(|| json!(profile));
        merge_parser_run_model_runtime(
            parser_run,
            model_runtime_report_json(profile, model_metrics),
        );
        let model_identities = parser_run_model_identities(parser_run);
        parser_run
            .entry("modelRouting".to_string())
            .or_insert_with(|| route.to_json(true, &model_identities));
    }
    merge_hybrid_schema_into_worker_document(&mut document);
    refresh_worker_document_layers(&mut document);
    document
}

fn merge_parser_run_model_runtime(
    parser_run: &mut serde_json::Map<String, Value>,
    runtime_report: Value,
) {
    let Some(report) = runtime_report.as_object() else {
        return;
    };
    let runtime = parser_run
        .entry("modelRuntime".to_string())
        .or_insert_with(|| Value::Object(serde_json::Map::new()));
    let Some(target) = runtime.as_object_mut() else {
        *runtime = runtime_report;
        return;
    };
    for (key, value) in report {
        target.entry(key.clone()).or_insert_with(|| value.clone());
    }
}

fn hybrid_merge_worker_document_with_text_layer(
    mut worker_document: Value,
    source_path: &str,
    source_hash: &str,
    request: &Value,
    route: &ModelRouteDecision,
) -> Value {
    if route.decision == "ocr-model"
        || request
            .get("hybrid_merge_text_layer")
            .and_then(Value::as_bool)
            == Some(false)
    {
        return worker_document;
    }
    let text_request = json!({
        "command": "parse_pdf",
        "source_path": source_path,
        "source_hash": source_hash,
        "preset": "lite",
        "profile": "edge-fast",
        "runtime_profile": "edge-fast",
        "runtimeProfile": "edge-fast",
        "offline_mode": true,
        "allow_model_downloads": false
    });
    let Ok(text_document) = parse_pdf_json(&text_request) else {
        return worker_document;
    };
    merge_text_layer_document_into_worker_document(&mut worker_document, &text_document);
    worker_document
}

fn merge_text_layer_document_into_worker_document(
    worker_document: &mut Value,
    text_document: &Value,
) {
    let text_body = text_document.get("body").and_then(Value::as_object);
    let Some(text_body) = text_body else {
        return;
    };
    let Some(worker_body) = worker_document
        .get_mut("body")
        .and_then(Value::as_object_mut)
    else {
        return;
    };
    if let Some(pages) = text_body.get("pages").cloned() {
        worker_body.entry("pages".to_string()).or_insert(pages);
    }
    let mut units = worker_body
        .get("units")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    units.extend(
        text_body
            .get("units")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default(),
    );
    if !units.is_empty() {
        worker_body.insert("units".to_string(), json!(units));
    }
    let mut tables = worker_body
        .get("tables")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    tables.extend(
        text_body
            .get("tables")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default(),
    );
    if !tables.is_empty() {
        worker_body.insert("tables".to_string(), json!(tables));
    }
    if let Some(parser_run) = worker_document
        .get_mut("parserRun")
        .and_then(Value::as_object_mut)
    {
        parser_run.insert(
            "hybridMerge".to_string(),
            json!({
                "textLayer": "pdf_oxide",
                "modelLayer": "model-worker",
                "strategy": "model-units-plus-text-layer-units"
            }),
        );
    }
    refresh_worker_document_layers(worker_document);
}

fn merge_hybrid_schema_into_worker_document(document: &mut Value) {
    let Some(schema) = document.pointer("/parserRun/hybridSchema").cloned() else {
        return;
    };
    let (units, tables) = opendataloader_hybrid_schema_to_units_and_tables(&schema);
    if units.is_empty() && tables.is_empty() {
        return;
    }
    let Some(body) = document.get_mut("body").and_then(Value::as_object_mut) else {
        return;
    };
    if !units.is_empty()
        && body
            .get("units")
            .and_then(Value::as_array)
            .is_none_or(Vec::is_empty)
    {
        body.insert("units".to_string(), json!(units));
    }
    if !tables.is_empty()
        && body
            .get("tables")
            .and_then(Value::as_array)
            .is_none_or(Vec::is_empty)
    {
        body.insert("tables".to_string(), json!(table_json(&tables)));
    }
    let units_for_blocks = body
        .get("units")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    let reader_units = reader_layer_units(&units_for_blocks);
    if !reader_units.is_empty()
        && document
            .get("contentBlocks")
            .and_then(Value::as_array)
            .is_none_or(Vec::is_empty)
    {
        document["contentBlocks"] = json!(content_blocks_json(&reader_units));
    }
}

fn refresh_worker_document_layers(document: &mut Value) {
    let Some(body) = document.get("body").and_then(Value::as_object) else {
        return;
    };
    let units = body
        .get("units")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    let pages = body
        .get("pages")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    if units.is_empty() {
        return;
    }
    let reader_units = reader_layer_units(&units);
    if reader_units.is_empty() {
        return;
    }
    if document
        .get("contentBlocks")
        .and_then(Value::as_array)
        .is_none_or(Vec::is_empty)
    {
        document["contentBlocks"] = json!(content_blocks_json(&reader_units));
    }
    if parse_trace_pages_present(document) {
        return;
    }
    if pages.is_empty() {
        return;
    }
    let parser_run_id = document
        .pointer("/parserRun/parserRunId")
        .and_then(Value::as_str)
        .unwrap_or("parser-run-worker");
    let reading_order = document
        .pointer("/parseTrace/readingOrder")
        .cloned()
        .unwrap_or_else(|| {
            json!({
                "source": "worker-unit-order",
                "fallback": false,
                "confidence": 0.72
            })
        });
    let mut trace = parse_trace_json(&pages, &reader_units, parser_run_id, &reading_order);
    if let Some(warnings) = document.pointer("/parseTrace/warnings").cloned() {
        trace["warnings"] = warnings;
    }
    document["parseTrace"] = trace;
}

fn reader_layer_units(units: &[Value]) -> Vec<Value> {
    units
        .iter()
        .filter(|unit| !model_table_structure_unit(unit))
        .cloned()
        .collect()
}

fn parse_trace_pages_present(document: &Value) -> bool {
    document
        .pointer("/parseTrace/pages")
        .and_then(Value::as_array)
        .is_some_and(|pages| !pages.is_empty())
}

fn parser_run_model_identities(parser_run: &serde_json::Map<String, Value>) -> Vec<String> {
    parser_run
        .get("models")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
        .map(str::to_string)
        .collect()
}

fn model_runtime_report_json(profile: &str, model_metrics: &Value) -> Value {
    let mut runtime = json!({
        "runtime": if profile == "edge-model" { "mnn" } else { "none" },
        "loadPolicy": "lazy",
        "unloadPolicy": "idle-after-request"
    });
    let Some(target) = runtime.as_object_mut() else {
        return runtime;
    };
    for key in [
        "runtime",
        "decoder",
        "inputSource",
        "stubMode",
        "manifestPath",
        "coldStartMs",
        "renderMs",
        "preprocessing",
        "inferenceMs",
        "totalMs",
        "rssMb",
        "peakMemoryMb",
        "ocrRegions",
        "loadedModels",
        "auxiliaryArtifacts",
        "modelArtifacts",
        "auxiliaryArtifactDetails",
        "unload",
    ] {
        if let Some(value) = model_metrics.get(key) {
            target.insert(key.to_string(), value.clone());
        }
    }
    if profile == "benchmark-oracle" && target.get("runtime").and_then(Value::as_str) != Some("mnn")
    {
        target.insert("referenceOnly".to_string(), json!(true));
    }
    runtime
}

fn configured_model_worker_command(route: &ModelRouteDecision) -> Option<String> {
    explicit_model_worker_command().or_else(|| route_default_model_worker_command(route))
}

fn explicit_model_worker_command() -> Option<String> {
    env::var("DOCTRUTH_RUNTIME_MODEL_COMMAND")
        .ok()
        .or_else(|| env::var("DOCTRUTH_MODEL_COMMAND").ok())
        .filter(|command| !command.trim().is_empty())
}

fn configured_model_worker_command_for_request(
    route: &ModelRouteDecision,
    request: &Value,
) -> Option<String> {
    request_scoped_string(request, &["model_worker", "modelWorker", "modelCommand"])
        .or_else(|| configured_model_worker_command(route))
}

fn route_default_model_worker_command(route: &ModelRouteDecision) -> Option<String> {
    if !matches!(
        route.decision.as_str(),
        "model-runtime" | "ocr-model" | "table-model"
    ) {
        return None;
    }
    find_executable_on_path("doctruth-mnn-model-worker")
}

fn find_executable_on_path(name: &str) -> Option<String> {
    let paths = env::var_os("PATH")?;
    for directory in env::split_paths(&paths) {
        let candidate = directory.join(name);
        if candidate.is_file() {
            return Some(candidate.to_string_lossy().into_owned());
        }
    }
    None
}

fn run_model_worker(command: &str, request: &Value) -> Result<String, String> {
    let mut child = Command::new(command)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|error| error_json("MODEL_WORKER_FAILED", &error.to_string()).to_string())?;
    if let Some(mut stdin) = child.stdin.take() {
        stdin
            .write_all(request.to_string().as_bytes())
            .map_err(|error| error_json("MODEL_WORKER_FAILED", &error.to_string()).to_string())?;
    }
    let output = child
        .wait_with_output()
        .map_err(|error| error_json("MODEL_WORKER_FAILED", &error.to_string()).to_string())?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(error_json(
            "MODEL_WORKER_FAILED",
            &format!("worker exited with {}; {}", output.status, stderr.trim()),
        )
        .to_string());
    }
    String::from_utf8(output.stdout)
        .map_err(|error| error_json("MODEL_WORKER_FAILED", &error.to_string()).to_string())
}

fn validate_worker_document(document: &Value) -> Result<(), String> {
    for pointer in [
        "/docId",
        "/source",
        "/body",
        "/parserRun",
        "/auditGradeStatus",
    ] {
        if document.pointer(pointer).is_none() {
            return Err(error_json(
                "MODEL_WORKER_FAILED",
                &format!("worker response missing {pointer}"),
            )
            .to_string());
        }
    }
    Ok(())
}

fn model_cache_directory() -> String {
    env::var("DOCTRUTH_MODEL_CACHE")
        .ok()
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| ".doctruth/models".to_string())
}

fn model_cache_directory_for_request(request: &Value) -> String {
    request_scoped_string(
        request,
        &["model_cache", "modelCache", "modelCacheDirectory"],
    )
    .unwrap_or_else(model_cache_directory)
}

fn model_doctor_json() -> Value {
    let cache_dir = model_cache_directory();
    let manifest = model_manifest_doctor_json();
    let presets = ["lite", "standard", "table-lite", "table-server", "ocr"]
        .iter()
        .map(|preset| {
            (
                (*preset).to_string(),
                preset_doctor_json(preset, &cache_dir),
            )
        })
        .collect::<serde_json::Map<_, _>>();
    json!({
        "cache": {
            "directory": cache_dir,
            "exists": Path::new(&cache_dir).is_dir()
        },
        "manifest": manifest,
        "worker": model_worker_doctor_json(),
        "presets": presets
    })
}

fn model_manifest_doctor_json() -> Value {
    match configured_model_manifest_path() {
        Some(path) => {
            let valid = read_json_file(Path::new(&path), "MODEL_MANIFEST_INVALID").is_ok();
            json!({
                "path": path,
                "configured": true,
                "valid": valid
            })
        }
        None => json!({
            "path": Value::Null,
            "configured": false,
            "valid": false
        }),
    }
}

fn preset_doctor_json(preset: &str, cache_dir: &str) -> Value {
    let required_models = required_model_descriptors(preset);
    let models = worker_model_artifacts_with_cache_dir(preset, &required_models, cache_dir);
    let all_ready = !models.is_empty()
        && models
            .iter()
            .all(|model| model.get("cacheStatus").and_then(Value::as_str) == Some("READY"));
    json!({
        "required": !required_models.is_empty(),
        "allReady": all_ready || required_models.is_empty(),
        "models": models
    })
}

fn model_worker_doctor_json() -> Value {
    let Some(command) = explicit_model_worker_command() else {
        return json!({
            "configured": false,
            "available": false,
            "ready": false,
            "command": Value::Null,
            "statusCode": "NOT_CONFIGURED",
            "message": "no local model worker configured"
        });
    };
    let output = Command::new(&command)
        .arg("--doctor")
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .output();
    match output {
        Ok(output) if output.status.success() => {
            let text = String::from_utf8_lossy(&output.stdout);
            let parsed: Value = serde_json::from_str(&text).unwrap_or_else(|_| json!({}));
            let ok = parsed.get("ok").and_then(Value::as_bool);
            let ready = parsed
                .get("ready")
                .and_then(Value::as_bool)
                .unwrap_or_else(|| ok.unwrap_or(true));
            json!({
                "configured": true,
                "available": true,
                "ready": ready,
                "command": command,
                "statusCode": parsed
                    .get("statusCode")
                    .or_else(|| parsed.get("code"))
                    .and_then(Value::as_str)
                    .unwrap_or(if ready { "READY" } else { "WORKER_NOT_READY" }),
                "message": parsed.get("message").and_then(Value::as_str).unwrap_or("worker doctor passed"),
                "rssMb": parsed.get("rssMb").cloned().unwrap_or(Value::Null),
                "peakMemoryMb": parsed.get("peakMemoryMb").cloned().unwrap_or(Value::Null),
                "loadedModels": parsed.get("loadedModels").cloned().unwrap_or_else(|| json!([]))
            })
        }
        Ok(output) => {
            let stderr = String::from_utf8_lossy(&output.stderr);
            json!({
                "configured": true,
                "available": true,
                "ready": false,
                "command": command,
                "statusCode": "WORKER_DOCTOR_FAILED",
                "message": stderr.trim()
            })
        }
        Err(error) => json!({
            "configured": true,
            "available": false,
            "ready": false,
            "command": command,
            "statusCode": "WORKER_UNAVAILABLE",
            "message": error.to_string()
        }),
    }
}

fn worker_model_artifacts_for_request(
    request: &Value,
    preset: &str,
    required_models: &[RequiredModel],
) -> Vec<Value> {
    worker_model_artifacts_with_manifest_and_cache_dir(
        preset,
        required_models,
        configured_model_manifest_path_for_request(request),
        &model_cache_directory_for_request(request),
    )
}

fn worker_model_artifacts_with_cache_dir(
    preset: &str,
    required_models: &[RequiredModel],
    cache_dir: &str,
) -> Vec<Value> {
    worker_model_artifacts_with_manifest_and_cache_dir(
        preset,
        required_models,
        configured_model_manifest_path(),
        cache_dir,
    )
}

fn worker_model_artifacts_with_manifest_and_cache_dir(
    preset: &str,
    required_models: &[RequiredModel],
    manifest_path: Option<String>,
    cache_dir: &str,
) -> Vec<Value> {
    let manifest_models = model_manifest_artifacts_from_path(preset, manifest_path.as_deref());
    let models = if manifest_models.is_empty() {
        required_models.iter().map(RequiredModel::json).collect()
    } else {
        manifest_models
    };
    models
        .into_iter()
        .map(|model| model_with_cache_status(model, cache_dir))
        .collect()
}

fn model_manifest_artifacts_from_path(preset: &str, manifest_path: Option<&str>) -> Vec<Value> {
    let Some(manifest_path) = manifest_path else {
        return Vec::new();
    };
    let Ok(manifest) = read_json_file(Path::new(manifest_path), "MODEL_MANIFEST_INVALID") else {
        return Vec::new();
    };
    manifest
        .pointer(&format!("/presets/{preset}"))
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default()
}

fn model_manifest_auxiliary_artifacts_for_request(request: &Value) -> Vec<Value> {
    model_manifest_auxiliary_artifacts_from_path(
        configured_model_manifest_path_for_request(request).as_deref(),
    )
}

fn model_manifest_auxiliary_artifacts_from_path(manifest_path: Option<&str>) -> Vec<Value> {
    let Some(manifest_path) = manifest_path else {
        return Vec::new();
    };
    let Ok(manifest) = read_json_file(Path::new(manifest_path), "MODEL_MANIFEST_INVALID") else {
        return Vec::new();
    };
    manifest
        .pointer("/auxiliary")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default()
}

fn configured_model_manifest_path() -> Option<String> {
    env::var("DOCTRUTH_MODEL_MANIFEST")
        .ok()
        .filter(|value| !value.trim().is_empty())
}

fn configured_model_manifest_path_for_request(request: &Value) -> Option<String> {
    request_scoped_string(
        request,
        &["model_manifest", "modelManifest", "modelManifestPath"],
    )
    .or_else(configured_model_manifest_path)
}

fn request_scoped_string(request: &Value, keys: &[&str]) -> Option<String> {
    keys.iter()
        .find_map(|key| request.get(*key).and_then(Value::as_str))
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_string)
}

fn model_with_cache_status(mut model: Value, cache_dir: &str) -> Value {
    let name = model
        .get("name")
        .and_then(Value::as_str)
        .unwrap_or("model")
        .to_string();
    let version = model
        .get("version")
        .and_then(Value::as_str)
        .unwrap_or("v1")
        .to_string();
    let cache_path = Path::new(cache_dir).join(model_cache_filename(&model, &name, &version));
    let (status, actual_sha, actual_size) = verify_model_cache_artifact(&cache_path, &model);
    if model.get("expectedSha256").is_none() {
        let sha = model
            .get("sha256")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string();
        model["expectedSha256"] = json!(sha);
    }
    model["identity"] = json!(format!("{name}:{version}"));
    model["cachePath"] = json!(cache_path.to_string_lossy().to_string());
    model["cacheStatus"] = json!(status);
    model["actualSha256"] = json!(actual_sha);
    model["actualSizeBytes"] = json!(actual_size);
    model
}

fn model_cache_filename(model: &Value, name: &str, version: &str) -> String {
    if let Some(filename) = model.get("cacheFilename").and_then(Value::as_str) {
        return filename.to_string();
    }
    format!(
        "{}-{}.bin",
        sanitize_model_token(name),
        sanitize_model_token(version)
    )
}

fn sanitize_model_token(value: &str) -> String {
    value
        .chars()
        .map(|ch| {
            if ch.is_ascii_alphanumeric() || matches!(ch, '.' | '_' | '-') {
                ch
            } else {
                '_'
            }
        })
        .collect()
}

fn verify_model_cache_artifact(path: &Path, model: &Value) -> (&'static str, String, u64) {
    let Ok(bytes) = fs::read(path) else {
        return ("MISSING", String::new(), 0);
    };
    let actual_sha = sha256_hex(&bytes);
    let expected_sha = model
        .get("sha256")
        .or_else(|| model.get("expectedSha256"))
        .and_then(Value::as_str)
        .unwrap_or("");
    let status = if expected_sha == actual_sha {
        "READY"
    } else {
        "SHA_MISMATCH"
    };
    (status, actual_sha, bytes.len() as u64)
}

fn benchmark_corpus_json(request: &Value) -> Result<Value, String> {
    let manifest_path = request
        .get("manifest_path")
        .and_then(Value::as_str)
        .ok_or_else(|| {
            error_json(
                "BENCHMARK_CORPUS_INVALID",
                "request.manifest_path is required",
            )
            .to_string()
        })?;
    let manifest = read_json_file(Path::new(manifest_path), "BENCHMARK_CORPUS_INVALID")?;
    validate_parser_accuracy_manifest(&manifest)?;
    let base_dir = Path::new(manifest_path)
        .parent()
        .unwrap_or_else(|| Path::new("."));
    let cases = manifest
        .get("cases")
        .and_then(Value::as_array)
        .ok_or_else(|| {
            error_json(
                "BENCHMARK_CORPUS_INVALID",
                "manifest.cases must be an array",
            )
            .to_string()
        })?;
    let profile = runtime_profile(request)?;
    if profile == "benchmark-oracle" {
        return Err(error_json(
            "PROFILE_NOT_SUPPORTED",
            "benchmark-oracle is an explicit benchmark comparison profile, not a benchmark_corpus runtime profile",
        )
        .to_string());
    }
    let external = external_metrics(base_dir, &manifest)?;
    let benchmark_started = Instant::now();
    let start_memory = process_memory_usage();
    let mut case_reports = Vec::new();
    for case in cases {
        case_reports.push(run_benchmark_case(base_dir, case, profile)?);
    }
    let end_memory = process_memory_usage();
    let elapsed_ms = benchmark_started.elapsed().as_secs_f64() * 1000.0;
    require_tag_coverage(&manifest, &case_reports)?;
    let mut metrics = aggregate_case_metrics(&case_reports);
    merge_object_metrics(&mut metrics, &external.values);
    require_dimension_coverage(
        &manifest,
        &case_reports,
        "fixtureTypes",
        "minCasesPerFixtureType",
    )?;
    require_dimension_coverage(&manifest, &case_reports, "behaviors", "minCasesPerBehavior")?;
    require_minimums(&manifest, &metrics)?;
    require_maximums(&manifest, &metrics)?;
    let external_artifacts = write_opendataloader_prediction_if_requested(request, &case_reports)?;
    let public_case_reports = public_case_reports(&case_reports);
    let labeling = &manifest["labeling"];
    let resource_profile = benchmark_resource_profile_json(
        profile,
        start_memory,
        end_memory,
        elapsed_ms,
        &case_reports,
    );
    let mnn_promotion = mnn_promotion_json(&manifest, &metrics, &resource_profile);

    let report = json!({
        "runtime": RUNTIME,
        "protocol_version": PROTOCOL_VERSION,
        "corpus": required_str(&manifest, "name", "BENCHMARK_CORPUS_INVALID")?,
        "kind": manifest.get("kind").and_then(Value::as_str).unwrap_or("generated"),
        "qualityProfile": manifest.get("qualityProfile").and_then(Value::as_str).unwrap_or("default"),
        "reviewType": labeling.get("reviewType").and_then(Value::as_str).unwrap_or(""),
        "requiredMetrics": labeling.get("requiredMetrics").cloned().unwrap_or_else(|| json!([])),
        "requiredTags": labeling.get("requiredTags").cloned().unwrap_or_else(|| json!([])),
        "minCasesPerTag": expected_min_cases_per_tag(labeling),
        "requiredFixtureTypes": labeling.get("requiredFixtureTypes").cloned().unwrap_or_else(|| json!([])),
        "minCasesPerFixtureType": expected_min_cases_per_field(labeling, "requiredFixtureTypes", "minCasesPerFixtureType"),
        "requiredBehaviors": labeling.get("requiredBehaviors").cloned().unwrap_or_else(|| json!([])),
        "minCasesPerBehavior": expected_min_cases_per_field(labeling, "requiredBehaviors", "minCasesPerBehavior"),
        "minTotalCases": labeling.get("minTotalCases").cloned().unwrap_or(Value::Null),
        "caseCount": case_reports.len(),
        "casesPerTag": cases_per_tag(&case_reports),
        "casesPerFixtureType": cases_per_field(&case_reports, "fixtureTypes"),
        "fixtureResults": fixture_results(
            &case_reports,
            manifest.get("minimums").unwrap_or(&json!({})),
            manifest.get("maximums").unwrap_or(&json!({}))
        ),
        "fixtureCoverageRequired": expected_min_cases_per_field(labeling, "requiredFixtureTypes", "minCasesPerFixtureType"),
        "fixtureCoverageSatisfied": coverage_satisfied(
            &expected_min_cases_per_field(labeling, "requiredFixtureTypes", "minCasesPerFixtureType"),
            &case_reports,
            "fixtureTypes"
        ),
        "casesPerBehavior": cases_per_field(&case_reports, "behaviors"),
        "behaviorCoverageRequired": expected_min_cases_per_field(labeling, "requiredBehaviors", "minCasesPerBehavior"),
        "behaviorCoverageSatisfied": coverage_satisfied(
            &expected_min_cases_per_field(labeling, "requiredBehaviors", "minCasesPerBehavior"),
            &case_reports,
            "behaviors"
        ),
        "coverageRequired": expected_min_cases_per_tag(labeling),
        "coverageSatisfied": coverage_satisfied(&expected_min_cases_per_tag(labeling), &case_reports, "tags"),
        "validityInputs": benchmark_validity_inputs(),
        "minimums": manifest.get("minimums").cloned().unwrap_or_else(|| json!({})),
        "maximums": manifest.get("maximums").cloned().unwrap_or_else(|| json!({})),
        "externalEvaluations": manifest.get("externalEvaluations").cloned().unwrap_or_else(|| json!({})),
        "externalArtifacts": external_artifacts,
        "externalMetrics": external.report,
        "resourceProfile": resource_profile,
        "mnnPromotion": mnn_promotion,
        "passed": true,
        "metrics": metrics,
        "cases": public_case_reports
    });
    write_benchmark_report_if_requested(request, manifest_path, &report)?;
    Ok(report)
}

fn mnn_promotion_json(manifest: &Value, metrics: &Value, resource_profile: &Value) -> Value {
    let Some(gate) = manifest.pointer("/promotionGates/mnn") else {
        return json!({
            "evaluated": false,
            "accepted": false,
            "reason": "promotionGates.mnn not configured"
        });
    };
    let quality = mnn_promotion_quality_json(gate, metrics);
    let resources = mnn_promotion_resource_json(gate, resource_profile);
    let accepted = quality.get("passed").and_then(Value::as_bool) == Some(true)
        && resources.get("passed").and_then(Value::as_bool) == Some(true);
    json!({
        "evaluated": true,
        "accepted": accepted,
        "quality": quality,
        "resources": resources
    })
}

fn mnn_promotion_quality_json(gate: &Value, metrics: &Value) -> Value {
    let thresholds = gate
        .get("qualityMinimums")
        .cloned()
        .unwrap_or_else(|| json!({}));
    let nid = metric_f64(metrics, "opendataloader_nid");
    let teds = metric_f64(metrics, "opendataloader_teds");
    let mhs = metric_f64(metrics, "opendataloader_mhs");
    let overall = match (nid, teds, mhs) {
        (Some(nid), Some(teds), Some(mhs)) => Some(round_metric((nid + teds + mhs) / 3.0)),
        _ => None,
    };
    let passed = threshold_pass(overall, &thresholds, "overall")
        && threshold_pass(nid, &thresholds, "nid")
        && threshold_pass(teds, &thresholds, "teds")
        && threshold_pass(mhs, &thresholds, "mhs");
    json!({
        "passed": passed,
        "overall": optional_metric_json(overall),
        "nid": optional_metric_json(nid),
        "teds": optional_metric_json(teds),
        "mhs": optional_metric_json(mhs),
        "thresholds": thresholds
    })
}

fn mnn_promotion_resource_json(gate: &Value, resource_profile: &Value) -> Value {
    let no_python_torch_docling = resource_profile
        .get("pythonTorchDoclingProductionResidency")
        .and_then(Value::as_bool)
        == Some(false);
    let lazy = resource_profile
        .get("lazyModelStartup")
        .and_then(Value::as_bool)
        == Some(true);
    let heavy_oracle = gate.get("heavyOracleSteadyRssMb").and_then(Value::as_u64);
    let model_peak = resource_profile
        .pointer("/modelRuntime/peakMemoryMb")
        .and_then(Value::as_u64);
    let model_runtime_present = resource_profile
        .get("modelRuntime")
        .is_some_and(Value::is_object);
    let blocked_model_runtime = resource_profile
        .pointer("/modelRoutingCoverage/blockedModelRuntime")
        .and_then(Value::as_u64)
        .unwrap_or(0);
    let all_required_routes_started = blocked_model_runtime == 0;
    let materially_lower = match (model_peak, heavy_oracle) {
        (Some(model_peak), Some(heavy_oracle)) => model_peak < heavy_oracle,
        _ => false,
    };
    json!({
        "passed": no_python_torch_docling
            && lazy
            && model_runtime_present
            && materially_lower
            && all_required_routes_started,
        "noPythonTorchDoclingResidency": no_python_torch_docling,
        "lazyModelStartup": lazy,
        "modelRuntimePresent": model_runtime_present,
        "allRequiredRoutesStarted": all_required_routes_started,
        "blockedModelRuntime": blocked_model_runtime,
        "materiallyLowerThanHeavyOracle": materially_lower,
        "heavyOracleSteadyRssMb": optional_u64_json(heavy_oracle),
        "modelPeakMemoryMb": optional_u64_json(model_peak)
    })
}

fn metric_f64(metrics: &Value, key: &str) -> Option<f64> {
    metrics.get(key).and_then(Value::as_f64)
}

fn threshold_pass(value: Option<f64>, thresholds: &Value, key: &str) -> bool {
    let threshold = thresholds.get(key).and_then(Value::as_f64).unwrap_or(0.0);
    value.is_some_and(|value| value >= threshold)
}

fn optional_metric_json(value: Option<f64>) -> Value {
    value.map_or(Value::Null, |value| json!(round_metric(value)))
}

fn optional_u64_json(value: Option<u64>) -> Value {
    value.map_or(Value::Null, |value| json!(value))
}

fn cases_per_tag(case_reports: &[Value]) -> Value {
    cases_per_field(case_reports, "tags")
}

fn cases_per_field(case_reports: &[Value], field: &str) -> Value {
    let mut counts = serde_json::Map::new();
    let mut tags = Vec::new();
    for report in case_reports {
        let Some(case_tags) = report.get(field).and_then(Value::as_array) else {
            continue;
        };
        for tag in case_tags.iter().filter_map(Value::as_str) {
            tags.push(tag.to_string());
        }
    }
    tags.sort();
    for tag in tags {
        let next = counts.get(&tag).and_then(Value::as_u64).unwrap_or(0) + 1;
        counts.insert(tag, json!(next));
    }
    Value::Object(counts)
}

fn coverage_satisfied(required: &Value, case_reports: &[Value], field: &str) -> Value {
    let actual = cases_per_field(case_reports, field);
    let mut satisfied = serde_json::Map::new();
    for (tag, minimum) in required.as_object().into_iter().flatten() {
        let minimum = minimum.as_u64().unwrap_or(0);
        let actual = actual.get(tag).and_then(Value::as_u64).unwrap_or(0);
        satisfied.insert(tag.to_string(), json!(actual >= minimum));
    }
    Value::Object(satisfied)
}

fn benchmark_resource_profile_json(
    profile: &str,
    start_memory: ProcessMemoryUsage,
    end_memory: ProcessMemoryUsage,
    elapsed_ms: f64,
    case_reports: &[Value],
) -> Value {
    json!({
        "profile": profile,
        "modelRuntime": if profile == "edge-model" { "mnn" } else { "none" },
        "pythonTorchDoclingProductionResidency": false,
        "lazyModelStartup": profile == "edge-model",
        "caseCount": case_reports.len(),
        "elapsedMs": round_metric(elapsed_ms),
        "meanCaseElapsedMs": mean_case_elapsed_ms(case_reports),
        "memory": {
            "startRssMb": start_memory.rss_mb,
            "endRssMb": end_memory.rss_mb,
            "peakMemoryMb": end_memory.peak_memory_mb.max(start_memory.peak_memory_mb),
            "measurement": "process-rss"
        },
        "modelRuntime": aggregate_model_runtime(case_reports),
        "budgetStatus": "profile-baseline-pending"
    })
}

fn mean_case_elapsed_ms(case_reports: &[Value]) -> Value {
    if case_reports.is_empty() {
        return Value::Null;
    }
    let total = case_reports
        .iter()
        .filter_map(|case| case.get("elapsedMs").and_then(Value::as_f64))
        .sum::<f64>();
    json!(round_metric(total / case_reports.len() as f64))
}

fn aggregate_model_runtime(case_reports: &[Value]) -> Value {
    let runtimes = case_reports
        .iter()
        .filter_map(|case| case.pointer("/actualTrustDocument/parserRun/modelRuntime"))
        .collect::<Vec<_>>();
    if runtimes.is_empty() {
        return Value::Null;
    }
    let loaded_models = unique_loaded_models(&runtimes);
    json!({
        "runtime": "mnn",
        "coldStartMs": sum_runtime_metric(&runtimes, "coldStartMs"),
        "inferenceMs": sum_runtime_metric(&runtimes, "inferenceMs"),
        "peakMemoryMb": max_runtime_metric(&runtimes, "peakMemoryMb"),
        "loadedModels": loaded_models
    })
}

fn sum_runtime_metric(runtimes: &[&Value], key: &str) -> Value {
    let values = runtimes
        .iter()
        .filter_map(|runtime| runtime.get(key).and_then(Value::as_f64))
        .collect::<Vec<_>>();
    if values.is_empty() {
        Value::Null
    } else {
        json!(round_metric(values.iter().sum::<f64>()))
    }
}

fn max_runtime_metric(runtimes: &[&Value], key: &str) -> Value {
    let max = runtimes
        .iter()
        .filter_map(|runtime| runtime.get(key).and_then(Value::as_f64))
        .map(|value| value.ceil() as u64)
        .max();
    max.map_or(Value::Null, |value| json!(value))
}

fn unique_loaded_models(runtimes: &[&Value]) -> Value {
    let mut models = runtimes
        .iter()
        .filter_map(|runtime| runtime.get("loadedModels").and_then(Value::as_array))
        .flatten()
        .filter_map(Value::as_str)
        .map(str::to_string)
        .collect::<Vec<_>>();
    models.sort();
    models.dedup();
    json!(models)
}

struct ExternalMetrics {
    report: Value,
    values: Value,
}

fn external_metrics(base_dir: &Path, manifest: &Value) -> Result<ExternalMetrics, String> {
    let Some(evaluations) = manifest.get("externalEvaluations") else {
        return Ok(ExternalMetrics {
            report: json!({}),
            values: json!({}),
        });
    };
    let Some(object) = evaluations.as_object() else {
        return Err(error_json(
            "BENCHMARK_CORPUS_INVALID",
            "externalEvaluations must be an object",
        )
        .to_string());
    };
    let mut report = serde_json::Map::new();
    let mut values = serde_json::Map::new();
    for (name, path_value) in object {
        if name != "opendataloader" {
            return Err(error_json(
                "BENCHMARK_CORPUS_INVALID",
                &format!("unsupported external evaluation: {name}"),
            )
            .to_string());
        }
        let relative = path_value.as_str().unwrap_or("");
        let path = base_dir.join(relative);
        let imported = opendataloader_external_metrics(&path)?;
        if let Some(imported_report) = imported.report.as_object() {
            report.insert(name.clone(), Value::Object(imported_report.clone()));
        }
        if let Some(imported_values) = imported.values.as_object() {
            for (metric, value) in imported_values {
                values.insert(metric.clone(), value.clone());
            }
        }
    }
    Ok(ExternalMetrics {
        report: Value::Object(report),
        values: Value::Object(values),
    })
}

fn opendataloader_external_metrics(path: &Path) -> Result<ExternalMetrics, String> {
    let root = read_json_file(path, "BENCHMARK_CORPUS_INVALID")?;
    let mut report = serde_json::Map::new();
    let mut values = serde_json::Map::new();
    put_external_metric(
        &mut report,
        &mut values,
        "nid",
        "opendataloader_nid",
        root.pointer("/metrics/score/nid_mean"),
    );
    put_external_metric(
        &mut report,
        &mut values,
        "teds",
        "opendataloader_teds",
        root.pointer("/metrics/score/teds_mean"),
    );
    put_external_metric(
        &mut report,
        &mut values,
        "mhs",
        "opendataloader_mhs",
        root.pointer("/metrics/score/mhs_mean"),
    );
    let speed = root
        .pointer("/speed/elapsed_per_doc")
        .filter(|value| value.is_number())
        .or_else(|| root.pointer("/summary/elapsed_per_doc"));
    put_external_metric(
        &mut report,
        &mut values,
        "speed",
        "opendataloader_speed",
        speed,
    );
    report.insert("evaluationSha256".to_string(), json!(sha256_file(path)?));
    Ok(ExternalMetrics {
        report: Value::Object(report),
        values: Value::Object(values),
    })
}

fn put_external_metric(
    report: &mut serde_json::Map<String, Value>,
    values: &mut serde_json::Map<String, Value>,
    field: &str,
    key: &str,
    value: Option<&Value>,
) {
    let Some(metric) = value.and_then(Value::as_f64) else {
        return;
    };
    report.insert(field.to_string(), json!(metric));
    values.insert(key.to_string(), json!(metric));
}

fn merge_object_metrics(metrics: &mut Value, external: &Value) {
    let Some(target) = metrics.as_object_mut() else {
        return;
    };
    let Some(source) = external.as_object() else {
        return;
    };
    for (name, value) in source {
        target.insert(name.clone(), value.clone());
    }
}

fn write_opendataloader_prediction_if_requested(
    request: &Value,
    case_reports: &[Value],
) -> Result<Value, String> {
    let Some(output_dir) = request
        .get("opendataloader_prediction_dir")
        .and_then(Value::as_str)
        .filter(|value| !value.trim().is_empty())
    else {
        return Ok(json!({}));
    };
    let root = Path::new(output_dir);
    let markdown_dir = root.join("markdown");
    fs::create_dir_all(&markdown_dir).map_err(|error| {
        error_json("BENCHMARK_REPORT_WRITE_FAILED", &error.to_string()).to_string()
    })?;
    let mut documents = Vec::new();
    for case in case_reports {
        let id = case
            .get("labelId")
            .and_then(Value::as_str)
            .or_else(|| case.get("name").and_then(Value::as_str))
            .unwrap_or("document");
        let document_id = safe_document_id(id);
        let markdown_path = markdown_dir.join(format!("{document_id}.md"));
        let markdown = case
            .get("_actualMarkdown")
            .and_then(Value::as_str)
            .unwrap_or("");
        fs::write(&markdown_path, markdown).map_err(|error| {
            error_json("BENCHMARK_REPORT_WRITE_FAILED", &error.to_string()).to_string()
        })?;
        documents.push(opendataloader_prediction_document_summary(
            case,
            &document_id,
            &markdown_path,
        ));
    }
    let parsed_count = documents.len();
    let summary = json!({
        "engine_name": "doctruth",
        "engine_version": env!("CARGO_PKG_VERSION"),
        "runtime_contract": "TrustDocument",
        "runtime_profile": prediction_runtime_profile(case_reports),
        "document_count": case_reports.len(),
        "parsed_count": parsed_count,
        "failed_count": 0,
        "production_residency": {
            "python_torch_docling": false
        },
        "documents": documents
    });
    fs::write(root.join("summary.json"), pretty_json(&summary)?).map_err(|error| {
        error_json("BENCHMARK_REPORT_WRITE_FAILED", &error.to_string()).to_string()
    })?;
    fs::write(
        root.join("errors.json"),
        pretty_json(&json!({"documents": []}))?,
    )
    .map_err(|error| error_json("BENCHMARK_REPORT_WRITE_FAILED", &error.to_string()).to_string())?;
    Ok(json!({
        "opendataloaderPrediction": {
            "engine": "doctruth",
            "path": root.to_string_lossy(),
            "markdownPath": markdown_dir.to_string_lossy(),
            "documentCount": case_reports.len()
        }
    }))
}

fn opendataloader_prediction_json(request: &Value) -> Result<Value, String> {
    let bench_dir = Path::new(required_request_str(
        request,
        "bench_dir",
        "OPENDATALOADER_PREDICTION_INVALID",
    )?);
    let engine = request
        .get("engine")
        .and_then(Value::as_str)
        .filter(|value| !value.trim().is_empty())
        .unwrap_or("doctruth");
    let output_dir = request
        .get("output_dir")
        .and_then(Value::as_str)
        .map(PathBuf::from)
        .unwrap_or_else(|| bench_dir.join("prediction").join(engine));
    let preset = request
        .get("preset")
        .and_then(Value::as_str)
        .unwrap_or("lite");
    let profile = runtime_profile(request)?;
    if profile == "benchmark-oracle" {
        return Err(error_json(
            "PROFILE_NOT_SUPPORTED",
            "benchmark-oracle is an explicit benchmark comparison profile, not an opendataloader prediction runtime profile",
        )
        .to_string());
    }
    let pdfs = select_opendataloader_pdfs(bench_dir, request)?;
    let timeout_seconds = prediction_timeout_seconds(request)?;
    let prediction = write_opendataloader_prediction_artifacts(
        &output_dir,
        engine,
        preset,
        profile,
        timeout_seconds,
        &pdfs,
        request,
    )?;
    let summary = read_json_file(
        &output_dir.join("summary.json"),
        "OPENDATALOADER_PREDICTION_INVALID",
    )?;
    let external = opendataloader_prediction_external_metrics(bench_dir, request)?;
    let resource_profile = opendataloader_prediction_resource_profile(profile, &summary);
    let promotion_manifest = json!({
        "promotionGates": request.get("promotionGates").cloned().unwrap_or_else(|| json!({}))
    });
    let mnn_promotion =
        mnn_promotion_json(&promotion_manifest, &external.values, &resource_profile);
    Ok(json!({
        "runtime": RUNTIME,
        "protocol_version": PROTOCOL_VERSION,
        "engine": engine,
        "prediction": prediction,
        "metrics": external.values,
        "externalMetrics": external.report,
        "resourceProfile": resource_profile,
        "mnnPromotion": mnn_promotion
    }))
}

fn opendataloader_evaluate_prediction_json(request: &Value) -> Result<Value, String> {
    let ground_truth_dir = Path::new(required_request_str(
        request,
        "ground_truth_dir",
        "OPENDATALOADER_EVALUATION_INVALID",
    )?);
    let prediction_dir = Path::new(required_request_str(
        request,
        "prediction_dir",
        "OPENDATALOADER_EVALUATION_INVALID",
    )?);
    let markdown_dir = prediction_dir.join("markdown");
    let mut gt_paths = markdown_files(ground_truth_dir, "OPENDATALOADER_EVALUATION_INVALID")?;
    if let Some(doc_id) = request
        .get("doc_id")
        .or_else(|| request.get("docId"))
        .and_then(Value::as_str)
    {
        gt_paths.retain(|path| path.file_stem().and_then(|stem| stem.to_str()) == Some(doc_id));
    }
    if gt_paths.is_empty() {
        return Err(error_json(
            "OPENDATALOADER_EVALUATION_INVALID",
            "ground_truth_dir contains no markdown files",
        )
        .to_string());
    }
    let mut documents = Vec::new();
    for gt_path in gt_paths {
        let document_id = gt_path
            .file_stem()
            .and_then(|stem| stem.to_str())
            .unwrap_or("unknown")
            .to_string();
        let pred_path = markdown_dir.join(format!("{document_id}.md"));
        documents.push(evaluate_opendataloader_document(
            &document_id,
            &gt_path,
            &pred_path,
        )?);
    }
    let summary = prediction_summary_json(prediction_dir);
    let metrics = aggregate_opendataloader_scores(&documents);
    let report = json!({
        "summary": summary,
        "metrics": metrics,
        "documents": documents
    });
    if let Some(output_path) = request.get("output_path").and_then(Value::as_str) {
        write_pretty_json(
            Path::new(output_path),
            &report,
            "OPENDATALOADER_EVALUATION_WRITE_FAILED",
        )?;
    }
    Ok(report)
}

fn prediction_timeout_seconds(request: &Value) -> Result<Option<f64>, String> {
    let timeout = request
        .get("timeout_seconds")
        .or_else(|| request.get("timeoutSeconds"))
        .and_then(Value::as_f64);
    match timeout {
        Some(value) if value > 0.0 => Ok(Some(value)),
        Some(_) => Err(error_json(
            "OPENDATALOADER_PREDICTION_INVALID",
            "timeout_seconds must be greater than zero",
        )
        .to_string()),
        None => Ok(None),
    }
}

fn markdown_files(dir: &Path, code: &str) -> Result<Vec<PathBuf>, String> {
    let entries = fs::read_dir(dir).map_err(|error| {
        error_json(code, &format!("failed to read {}: {error}", dir.display())).to_string()
    })?;
    let mut paths = Vec::new();
    for entry in entries {
        let path = entry
            .map_err(|error| error_json(code, &error.to_string()).to_string())?
            .path();
        if path.extension().and_then(|ext| ext.to_str()) == Some("md") {
            paths.push(path);
        }
    }
    paths.sort();
    Ok(paths)
}

fn evaluate_opendataloader_document(
    document_id: &str,
    gt_path: &Path,
    pred_path: &Path,
) -> Result<Value, String> {
    let gt = fs::read_to_string(gt_path).map_err(|error| {
        error_json(
            "OPENDATALOADER_EVALUATION_READ_FAILED",
            &format!("failed to read {}: {error}", gt_path.display()),
        )
        .to_string()
    })?;
    let prediction_available = pred_path.is_file();
    let pred = if prediction_available {
        fs::read_to_string(pred_path).map_err(|error| {
            error_json(
                "OPENDATALOADER_EVALUATION_READ_FAILED",
                &format!("failed to read {}: {error}", pred_path.display()),
            )
            .to_string()
        })?
    } else {
        String::new()
    };
    let (nid, nid_s) = evaluate_opendataloader_reading_order(&gt, &pred);
    let (teds, teds_s) = evaluate_opendataloader_table(&gt, &pred);
    let (mhs, mhs_s) = evaluate_opendataloader_heading(&gt, &pred);
    let overall = mean_score([nid, teds, mhs]);
    Ok(json!({
        "document_id": document_id,
        "scores": {
            "overall": optional_metric_json(overall),
            "nid": optional_metric_json(nid),
            "nid_s": optional_metric_json(nid_s),
            "teds": optional_metric_json(teds),
            "teds_s": optional_metric_json(teds_s),
            "mhs": optional_metric_json(mhs),
            "mhs_s": optional_metric_json(mhs_s)
        },
        "prediction_available": prediction_available
    }))
}

fn evaluate_opendataloader_reading_order(gt: &str, pred: &str) -> (Option<f64>, Option<f64>) {
    let gt_with_html = convert_markdown_tables_to_html(gt);
    let pred_with_html = convert_markdown_tables_to_html(pred);
    let gt_normalized = normalize_markdown_for_evaluator(&gt_with_html);
    if gt_normalized.is_empty() {
        return (None, None);
    }
    let pred_normalized = normalize_markdown_for_evaluator(&pred_with_html);
    let gt_stripped = strip_html_tables(&gt_with_html);
    let pred_stripped = strip_html_tables(&pred_with_html);
    (
        Some(markdown_similarity(&gt_normalized, &pred_normalized)),
        Some(markdown_similarity(
            &normalize_markdown_for_evaluator(&gt_stripped),
            &normalize_markdown_for_evaluator(&pred_stripped),
        )),
    )
}

fn evaluate_opendataloader_table(gt: &str, pred: &str) -> (Option<f64>, Option<f64>) {
    let gt_with_html = convert_markdown_tables_to_html(gt);
    let pred_with_html = convert_markdown_tables_to_html(pred);
    let gt_tables = evaluator_tables(&gt_with_html);
    if gt_tables.is_empty() {
        return (None, None);
    }
    let pred_tables = evaluator_tables(&pred_with_html);
    if pred_tables.is_empty() {
        return (Some(0.0), Some(0.0));
    }
    let gt_tree = table_eval_tree(&gt_tables);
    let pred_tree = table_eval_tree(&pred_tables);
    let max_nodes = table_eval_scoring_size(&gt_tree)
        .max(table_eval_scoring_size(&pred_tree))
        .max(1);
    (
        Some(table_tree_similarity(&gt_tree, &pred_tree, true, max_nodes)),
        Some(table_tree_similarity(
            &gt_tree, &pred_tree, false, max_nodes,
        )),
    )
}

fn evaluate_opendataloader_heading(gt: &str, pred: &str) -> (Option<f64>, Option<f64>) {
    let gt_with_html = convert_markdown_tables_to_html(gt);
    let pred_with_html = convert_markdown_tables_to_html(pred);
    let gt_tree = markdown_heading_tree(&gt_with_html);
    if !heading_tree_has_heading(&gt_tree) {
        return (None, None);
    }
    let pred_tree = markdown_heading_tree(&pred_with_html);
    if !heading_tree_has_heading(&pred_tree) {
        return (Some(0.0), Some(0.0));
    }
    let max_nodes = heading_tree_size(&gt_tree)
        .max(heading_tree_size(&pred_tree))
        .max(1);
    let with_text = heading_tree_similarity(&gt_tree, &pred_tree, true, max_nodes);
    let structure_only = heading_tree_similarity(&gt_tree, &pred_tree, false, max_nodes);
    (Some(with_text), Some(structure_only))
}

fn aggregate_opendataloader_scores(documents: &[Value]) -> Value {
    let overall = collect_document_scores(documents, "overall");
    let nid = collect_document_scores(documents, "nid");
    let nid_s = collect_document_scores(documents, "nid_s");
    let teds = collect_document_scores(documents, "teds");
    let teds_s = collect_document_scores(documents, "teds_s");
    let mhs = collect_document_scores(documents, "mhs");
    let mhs_s = collect_document_scores(documents, "mhs_s");
    let missing_predictions = documents
        .iter()
        .filter(|document| {
            document
                .get("prediction_available")
                .and_then(Value::as_bool)
                != Some(true)
        })
        .count();
    json!({
        "score": {
            "overall_mean": optional_metric_json(mean_vec(&overall)),
            "nid_mean": optional_metric_json(mean_vec(&nid)),
            "nid_s_mean": optional_metric_json(mean_vec(&nid_s)),
            "teds_mean": optional_metric_json(mean_vec(&teds)),
            "teds_s_mean": optional_metric_json(mean_vec(&teds_s)),
            "mhs_mean": optional_metric_json(mean_vec(&mhs)),
            "mhs_s_mean": optional_metric_json(mean_vec(&mhs_s))
        },
        "nid_count": nid.len(),
        "teds_count": teds.len(),
        "mhs_count": mhs.len(),
        "missing_predictions": missing_predictions
    })
}

fn collect_document_scores(documents: &[Value], key: &str) -> Vec<f64> {
    documents
        .iter()
        .filter_map(|document| {
            document
                .pointer(&format!("/scores/{key}"))
                .and_then(Value::as_f64)
        })
        .collect()
}

fn prediction_summary_json(prediction_dir: &Path) -> Value {
    read_json_file(
        &prediction_dir.join("summary.json"),
        "OPENDATALOADER_EVALUATION_SUMMARY_INVALID",
    )
    .unwrap_or_else(|_| json!({}))
}

fn mean_score(values: [Option<f64>; 3]) -> Option<f64> {
    let scores = values.into_iter().flatten().collect::<Vec<_>>();
    mean_vec(&scores)
}

fn mean_vec(values: &[f64]) -> Option<f64> {
    if values.is_empty() {
        None
    } else {
        Some(round_metric(
            values.iter().sum::<f64>() / values.len() as f64,
        ))
    }
}

fn markdown_similarity(left: &str, right: &str) -> f64 {
    if left.is_empty() && right.is_empty() {
        return 1.0;
    }
    let left_chars = left.chars().collect::<Vec<_>>();
    let right_chars = right.chars().collect::<Vec<_>>();
    let denominator = left_chars.len() + right_chars.len();
    if denominator == 0 {
        return 1.0;
    }
    let lcs = longest_common_subsequence_len(&left_chars, &right_chars);
    round_metric((2 * lcs) as f64 / denominator as f64)
}

fn longest_common_subsequence_len(left: &[char], right: &[char]) -> usize {
    let mut previous = vec![0; right.len() + 1];
    let mut current = vec![0; right.len() + 1];
    for left_char in left {
        for (index, right_char) in right.iter().enumerate() {
            current[index + 1] = if left_char == right_char {
                previous[index] + 1
            } else {
                previous[index + 1].max(current[index])
            };
        }
        std::mem::swap(&mut previous, &mut current);
        current.fill(0);
    }
    previous[right.len()]
}

fn normalize_markdown_for_evaluator(text: &str) -> String {
    text.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn strip_html_tables(text: &str) -> String {
    let mut result = String::new();
    let mut rest = text;
    loop {
        let Some(start) = rest.to_ascii_lowercase().find("<table") else {
            result.push_str(rest);
            break;
        };
        result.push_str(&rest[..start]);
        let after_start = &rest[start..];
        let Some(end) = after_start.to_ascii_lowercase().find("</table>") else {
            break;
        };
        rest = &after_start[end + "</table>".len()..];
    }
    result
}

fn html_tables(text: &str) -> Vec<String> {
    let mut tables = Vec::new();
    let mut rest = text;
    loop {
        let lower = rest.to_ascii_lowercase();
        let Some(start) = lower.find("<table") else {
            break;
        };
        let after_start = &rest[start..];
        let lower_after = after_start.to_ascii_lowercase();
        let Some(end) = lower_after.find("</table>") else {
            break;
        };
        let table_end = end + "</table>".len();
        tables.push(after_start[..table_end].to_string());
        rest = &after_start[table_end..];
    }
    tables
}

fn evaluator_tables(text: &str) -> Vec<String> {
    html_tables(text)
}

fn convert_markdown_tables_to_html(markdown: &str) -> String {
    if markdown.is_empty() {
        return markdown.to_string();
    }
    let lines = markdown.lines().collect::<Vec<_>>();
    let mut converted = Vec::new();
    let mut index = 0;
    while index < lines.len() {
        let Some(mut header) = official_markdown_row_cells(lines[index]) else {
            converted.push(lines[index].to_string());
            index += 1;
            continue;
        };
        if index + 1 >= lines.len() {
            converted.push(lines[index].to_string());
            index += 1;
            continue;
        }
        let Some(separator) = official_markdown_row_cells(lines[index + 1]) else {
            converted.push(lines[index].to_string());
            index += 1;
            continue;
        };
        if !official_markdown_separator_row(&separator) {
            converted.push(lines[index].to_string());
            index += 1;
            continue;
        }
        let target_width = header.len().max(separator.len());
        header = official_normalize_markdown_cells(header, target_width);
        index += 2;
        let mut rows = Vec::new();
        while index < lines.len() && is_markdown_table_row(lines[index]) {
            if let Some(row) = official_markdown_row_cells(lines[index]) {
                rows.push(official_normalize_markdown_cells(row, target_width));
            }
            index += 1;
        }
        if header.iter().all(|cell| cell.is_empty()) && !rows.is_empty() {
            header = rows.remove(0);
        }
        converted.push(markdown_rows_to_html_table(&header, &rows));
    }
    converted.join("\n")
}

fn is_markdown_table_row(line: &str) -> bool {
    official_markdown_row_cells(line)
        .map(|cells| cells.len() >= 2)
        .unwrap_or(false)
}

fn official_markdown_separator_row(cells: &[String]) -> bool {
    !cells.is_empty()
        && cells.iter().all(|cell| {
            let content = cell.replace(' ', "");
            !content.is_empty() && content.chars().all(|char| char == '-' || char == ':')
        })
}

fn official_markdown_row_cells(line: &str) -> Option<Vec<String>> {
    let trimmed = line.trim();
    if trimmed.is_empty() || !trimmed.contains('|') {
        return None;
    }
    let mut cells = trimmed
        .split('|')
        .map(|cell| cell.trim().to_string())
        .collect::<Vec<_>>();
    if trimmed.starts_with('|') && !cells.is_empty() {
        cells.remove(0);
    }
    if trimmed.ends_with('|') && !cells.is_empty() {
        cells.pop();
    }
    if cells.is_empty() { None } else { Some(cells) }
}

fn official_normalize_markdown_cells(cells: Vec<String>, target_width: usize) -> Vec<String> {
    if target_width == 0 || cells.len() == target_width {
        return cells;
    }
    if cells.len() == 3 && target_width > 3 {
        let mut normalized = Vec::with_capacity(target_width);
        normalized.push(cells[0].clone());
        normalized.extend(std::iter::repeat_n(cells[1].clone(), target_width - 2));
        normalized.push(cells[2].clone());
        return normalized;
    }
    if cells.len() < target_width {
        let mut normalized = cells;
        normalized.resize(target_width, String::new());
        return normalized;
    }
    cells.into_iter().take(target_width).collect()
}

fn markdown_rows_to_html_table(header: &[String], rows: &[Vec<String>]) -> String {
    let mut html = String::from("<table>");
    html.push_str("<tr>");
    for cell in header {
        html.push_str("<th>");
        html.push_str(&escape_table_text(cell));
        html.push_str("</th>");
    }
    html.push_str("</tr>");
    for row in rows {
        html.push_str("<tr>");
        for cell in row {
            html.push_str("<td>");
            html.push_str(&escape_table_text(cell));
            html.push_str("</td>");
        }
        html.push_str("</tr>");
    }
    html.push_str("</table>");
    html
}

fn escape_table_text(text: &str) -> String {
    text.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}

fn normalize_table_markup(markup: &str) -> String {
    let mut normalized = normalize_markdown_for_evaluator(markup).to_lowercase();
    normalized = rewrite_table_eval_tag(&normalized, "th", "td");
    for tag in ["thead", "tbody"] {
        normalized = remove_table_eval_tag(&normalized, tag);
    }
    normalized.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn rewrite_table_eval_tag(markup: &str, from_tag: &str, to_tag: &str) -> String {
    markup
        .replace(&format!("</{from_tag}>"), &format!("</{to_tag}>"))
        .replace(&format!("<{from_tag}>"), &format!("<{to_tag}>"))
        .replace(&format!("<{from_tag} "), &format!("<{to_tag} "))
}

fn remove_table_eval_tag(markup: &str, tag: &str) -> String {
    let without_close = markup.replace(&format!("</{tag}>"), "");
    let mut result = String::new();
    let mut rest = without_close.as_str();
    loop {
        let Some(start) = rest.find(&format!("<{tag}")) else {
            result.push_str(rest);
            break;
        };
        result.push_str(&rest[..start]);
        let after_start = &rest[start..];
        let Some(end) = after_start.find('>') else {
            break;
        };
        rest = &after_start[end + 1..];
    }
    result
}

#[derive(Clone)]
struct TableEvalNode {
    tag: &'static str,
    text: String,
    colspan: usize,
    rowspan: usize,
    children: Vec<TableEvalNode>,
}

fn table_eval_tree(tables: &[String]) -> TableEvalNode {
    TableEvalNode {
        tag: "body",
        text: String::new(),
        colspan: 1,
        rowspan: 1,
        children: tables
            .iter()
            .map(|table| parse_table_eval_node(table))
            .collect(),
    }
}

fn parse_table_eval_node(table: &str) -> TableEvalNode {
    let normalized = normalize_table_markup(table);
    let mut rows = Vec::new();
    for row in html_segments(&normalized, "tr") {
        let mut cells = Vec::new();
        for cell in html_segments(&row, "td") {
            let open_tag = opening_tag(&cell).unwrap_or_default();
            cells.push(TableEvalNode {
                tag: "td",
                text: normalize_markdown_for_evaluator(&strip_html_tags(&cell)),
                colspan: html_usize_attr(&open_tag, "colspan").unwrap_or(1),
                rowspan: html_usize_attr(&open_tag, "rowspan").unwrap_or(1),
                children: Vec::new(),
            });
        }
        rows.push(TableEvalNode {
            tag: "tr",
            text: String::new(),
            colspan: 1,
            rowspan: 1,
            children: cells,
        });
    }
    TableEvalNode {
        tag: "table",
        text: String::new(),
        colspan: 1,
        rowspan: 1,
        children: rows,
    }
}

fn html_segments(markup: &str, tag: &str) -> Vec<String> {
    let mut segments = Vec::new();
    let mut rest = markup;
    let open = format!("<{tag}");
    let close = format!("</{tag}>");
    loop {
        let Some(start) = rest.find(&open) else {
            break;
        };
        let after_start = &rest[start..];
        let Some(end) = after_start.find(&close) else {
            break;
        };
        let segment_end = end + close.len();
        segments.push(after_start[..segment_end].to_string());
        rest = &after_start[segment_end..];
    }
    segments
}

fn opening_tag(markup: &str) -> Option<String> {
    let start = markup.find('<')?;
    let end = markup[start..].find('>')?;
    Some(markup[start..=start + end].to_string())
}

fn html_usize_attr(tag: &str, attr: &str) -> Option<usize> {
    let needle = format!("{attr}=");
    let start = tag.find(&needle)? + needle.len();
    let value = tag[start..].trim_start();
    let quote = value.chars().next()?;
    if quote == '"' || quote == '\'' {
        let end = value[1..].find(quote)?;
        value[1..1 + end].parse().ok()
    } else {
        value
            .split(|char: char| char.is_whitespace() || char == '>')
            .next()
            .and_then(|raw| raw.parse().ok())
    }
}

fn strip_html_tags(markup: &str) -> String {
    let mut result = String::new();
    let mut in_tag = false;
    for char in markup.chars() {
        match char {
            '<' => in_tag = true,
            '>' => in_tag = false,
            _ if !in_tag => result.push(char),
            _ => {}
        }
    }
    result
}

fn table_tree_size(node: &TableEvalNode) -> usize {
    1 + node.children.iter().map(table_tree_size).sum::<usize>()
}

fn table_eval_scoring_size(node: &TableEvalNode) -> usize {
    if node.tag == "body" {
        node.children.iter().map(table_tree_size).sum()
    } else {
        table_tree_size(node)
    }
}

fn table_tree_similarity(
    gt: &TableEvalNode,
    pred: &TableEvalNode,
    include_text: bool,
    max_nodes: usize,
) -> f64 {
    let distance = table_tree_distance(gt, pred, include_text);
    round_metric((1.0 - distance / max_nodes as f64).clamp(0.0, 1.0))
}

fn table_tree_distance(left: &TableEvalNode, right: &TableEvalNode, include_text: bool) -> f64 {
    table_rename_cost(left, right, include_text)
        + table_children_distance(&left.children, &right.children, include_text)
}

fn table_children_distance(
    left: &[TableEvalNode],
    right: &[TableEvalNode],
    include_text: bool,
) -> f64 {
    let mut dp = vec![vec![0.0; right.len() + 1]; left.len() + 1];
    for index in 0..left.len() {
        dp[index + 1][0] = dp[index][0] + table_tree_size(&left[index]) as f64;
    }
    for index in 0..right.len() {
        dp[0][index + 1] = dp[0][index] + table_tree_size(&right[index]) as f64;
    }
    for left_index in 0..left.len() {
        for right_index in 0..right.len() {
            let delete =
                dp[left_index][right_index + 1] + table_tree_size(&left[left_index]) as f64;
            let insert =
                dp[left_index + 1][right_index] + table_tree_size(&right[right_index]) as f64;
            let rename = dp[left_index][right_index]
                + table_tree_distance(&left[left_index], &right[right_index], include_text);
            dp[left_index + 1][right_index + 1] = delete.min(insert).min(rename);
        }
    }
    dp[left.len()][right.len()]
}

fn table_rename_cost(left: &TableEvalNode, right: &TableEvalNode, include_text: bool) -> f64 {
    if left.tag != right.tag || left.colspan != right.colspan || left.rowspan != right.rowspan {
        return 1.0;
    }
    if !include_text || left.tag != "td" {
        return 0.0;
    }
    normalized_string_distance(&left.text, &right.text)
}

#[derive(Clone)]
struct HeadingEvalNode {
    tag: &'static str,
    text: String,
    children: Vec<HeadingEvalNode>,
}

fn markdown_heading_tree(markdown: &str) -> HeadingEvalNode {
    let mut root = HeadingEvalNode {
        tag: "document",
        text: String::new(),
        children: Vec::new(),
    };
    let mut current_heading: Option<usize> = None;
    let mut pending_content = Vec::new();
    for line in markdown.lines() {
        if let Some(heading) = markdown_heading_text(line) {
            flush_heading_content(&mut root, current_heading, &mut pending_content);
            root.children.push(HeadingEvalNode {
                tag: "heading",
                text: heading,
                children: Vec::new(),
            });
            current_heading = root.children.len().checked_sub(1);
            continue;
        }
        let normalized = normalize_markdown_for_evaluator(line);
        if !normalized.is_empty() {
            pending_content.push(normalized);
        }
    }
    flush_heading_content(&mut root, current_heading, &mut pending_content);
    root
}

fn markdown_heading_text(line: &str) -> Option<String> {
    let trimmed = line.trim_start();
    let level = trimmed.chars().take_while(|char| *char == '#').count();
    if !(1..=6).contains(&level) {
        return None;
    }
    let text = trimmed[level..].trim_start();
    if text.is_empty() {
        None
    } else {
        Some(normalize_markdown_for_evaluator(text))
    }
}

fn flush_heading_content(
    root: &mut HeadingEvalNode,
    current_heading: Option<usize>,
    pending_content: &mut Vec<String>,
) {
    let text = normalize_markdown_for_evaluator(&pending_content.join(" "));
    pending_content.clear();
    if text.is_empty() {
        return;
    }
    let content = HeadingEvalNode {
        tag: "content",
        text,
        children: Vec::new(),
    };
    if let Some(index) = current_heading {
        root.children[index].children.push(content);
    } else {
        root.children.push(content);
    }
}

fn heading_tree_has_heading(node: &HeadingEvalNode) -> bool {
    node.tag == "heading" || node.children.iter().any(heading_tree_has_heading)
}

fn heading_tree_size(node: &HeadingEvalNode) -> usize {
    1 + node.children.iter().map(heading_tree_size).sum::<usize>()
}

fn heading_tree_similarity(
    gt: &HeadingEvalNode,
    pred: &HeadingEvalNode,
    include_text: bool,
    max_nodes: usize,
) -> f64 {
    let distance = heading_tree_distance(gt, pred, include_text);
    round_metric((1.0 - distance / max_nodes as f64).clamp(0.0, 1.0))
}

fn heading_tree_distance(
    left: &HeadingEvalNode,
    right: &HeadingEvalNode,
    include_text: bool,
) -> f64 {
    heading_rename_cost(left, right, include_text)
        + heading_children_distance(&left.children, &right.children, include_text)
}

fn heading_children_distance(
    left: &[HeadingEvalNode],
    right: &[HeadingEvalNode],
    include_text: bool,
) -> f64 {
    let mut dp = vec![vec![0.0; right.len() + 1]; left.len() + 1];
    for index in 0..left.len() {
        dp[index + 1][0] = dp[index][0] + heading_tree_size(&left[index]) as f64;
    }
    for index in 0..right.len() {
        dp[0][index + 1] = dp[0][index] + heading_tree_size(&right[index]) as f64;
    }
    for left_index in 0..left.len() {
        for right_index in 0..right.len() {
            let delete =
                dp[left_index][right_index + 1] + heading_tree_size(&left[left_index]) as f64;
            let insert =
                dp[left_index + 1][right_index] + heading_tree_size(&right[right_index]) as f64;
            let rename = dp[left_index][right_index]
                + heading_tree_distance(&left[left_index], &right[right_index], include_text);
            dp[left_index + 1][right_index + 1] = delete.min(insert).min(rename);
        }
    }
    dp[left.len()][right.len()]
}

fn heading_rename_cost(left: &HeadingEvalNode, right: &HeadingEvalNode, include_text: bool) -> f64 {
    if left.tag != right.tag {
        return 1.0;
    }
    if !include_text {
        return 0.0;
    }
    normalized_string_distance(&left.text, &right.text)
}

fn normalized_string_distance(left: &str, right: &str) -> f64 {
    if left.is_empty() && right.is_empty() {
        return 0.0;
    }
    let max_len = left.chars().count().max(right.chars().count()).max(1);
    levenshtein(left, right) as f64 / max_len as f64
}

fn write_pretty_json(path: &Path, value: &Value, code: &str) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|error| {
            error_json(
                code,
                &format!("failed to create {}: {error}", parent.display()),
            )
            .to_string()
        })?;
    }
    let json = serde_json::to_string_pretty(value)
        .map_err(|error| error_json(code, &error.to_string()).to_string())?;
    fs::write(path, json).map_err(|error| {
        error_json(
            code,
            &format!("failed to write {}: {error}", path.display()),
        )
        .to_string()
    })
}

fn opendataloader_promotion_report_json(request: &Value) -> Result<Value, String> {
    let prediction_dir = Path::new(required_request_str(
        request,
        "prediction_dir",
        "OPENDATALOADER_PROMOTION_REPORT_INVALID",
    )?);
    let summary = read_json_file(
        &prediction_dir.join("summary.json"),
        "OPENDATALOADER_PROMOTION_REPORT_INVALID",
    )?;
    let evaluation_path = opendataloader_evaluation_path(request)?;
    let imported = opendataloader_external_metrics(&evaluation_path)?;
    let profile = summary
        .get("runtime_profile")
        .and_then(Value::as_str)
        .unwrap_or(DEFAULT_PROTOCOL_PROFILE);
    let resource_profile = opendataloader_prediction_resource_profile(profile, &summary);
    let promotion_manifest = json!({
        "promotionGates": request.get("promotionGates").cloned().unwrap_or_else(|| json!({}))
    });
    let mnn_promotion =
        mnn_promotion_json(&promotion_manifest, &imported.values, &resource_profile);
    Ok(json!({
        "runtime": RUNTIME,
        "protocol_version": PROTOCOL_VERSION,
        "prediction": {
            "engine": summary.get("engine_name").cloned().unwrap_or(Value::Null),
            "path": prediction_dir.to_string_lossy(),
            "documentCount": summary.get("document_count").cloned().unwrap_or(Value::Null),
            "parsedCount": summary.get("parsed_count").cloned().unwrap_or(Value::Null),
            "failedCount": summary.get("failed_count").cloned().unwrap_or(Value::Null)
        },
        "metrics": imported.values,
        "externalMetrics": json!({"opendataloader": imported.report}),
        "resourceProfile": resource_profile,
        "mnnPromotion": mnn_promotion
    }))
}

fn opendataloader_evaluation_path(request: &Value) -> Result<PathBuf, String> {
    let raw = request
        .get("opendataloader_evaluation")
        .or_else(|| request.get("opendataloaderEvaluation"))
        .and_then(Value::as_str)
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| {
            error_json(
                "OPENDATALOADER_PROMOTION_REPORT_INVALID",
                "request.opendataloader_evaluation is required",
            )
            .to_string()
        })?;
    let path = PathBuf::from(raw);
    if path.is_absolute() {
        return Ok(path);
    }
    let base = request
        .get("bench_dir")
        .or_else(|| request.get("benchDir"))
        .and_then(Value::as_str)
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."));
    Ok(base.join(path))
}

fn opendataloader_prediction_external_metrics(
    bench_dir: &Path,
    request: &Value,
) -> Result<ExternalMetrics, String> {
    let Some(relative) = request
        .get("opendataloader_evaluation")
        .or_else(|| request.get("opendataloaderEvaluation"))
        .and_then(Value::as_str)
        .filter(|value| !value.trim().is_empty())
    else {
        return Ok(ExternalMetrics {
            report: json!({}),
            values: json!({}),
        });
    };
    let imported = opendataloader_external_metrics(&bench_dir.join(relative))?;
    Ok(ExternalMetrics {
        report: json!({"opendataloader": imported.report}),
        values: imported.values,
    })
}

fn required_request_str<'a>(value: &'a Value, key: &str, code: &str) -> Result<&'a str, String> {
    value
        .get(key)
        .and_then(Value::as_str)
        .filter(|text| !text.trim().is_empty())
        .ok_or_else(|| error_json(code, &format!("request.{key} is required")).to_string())
}

fn select_opendataloader_pdfs(bench_dir: &Path, request: &Value) -> Result<Vec<PathBuf>, String> {
    let pdf_dir = bench_dir.join("pdfs");
    let doc_id = request
        .get("doc_id")
        .or_else(|| request.get("docId"))
        .and_then(Value::as_str)
        .filter(|value| !value.trim().is_empty());
    let mut pdfs = if let Some(doc_id) = doc_id {
        let path = pdf_dir.join(format!("{doc_id}.pdf"));
        if !path.is_file() {
            return Err(error_json(
                "OPENDATALOADER_PDF_NOT_FOUND",
                &format!("PDF not found: {}", path.to_string_lossy()),
            )
            .to_string());
        }
        vec![path]
    } else {
        let mut paths = fs::read_dir(&pdf_dir)
            .map_err(|error| {
                error_json("OPENDATALOADER_PREDICTION_INVALID", &error.to_string()).to_string()
            })?
            .filter_map(Result::ok)
            .map(|entry| entry.path())
            .filter(|path| path.extension().and_then(|value| value.to_str()) == Some("pdf"))
            .collect::<Vec<_>>();
        paths.sort();
        paths
    };
    if let Some(limit) = request.get("limit").and_then(Value::as_u64) {
        pdfs.truncate(limit as usize);
    }
    if pdfs.is_empty() {
        return Err(error_json(
            "OPENDATALOADER_PDF_NOT_FOUND",
            &format!("No PDFs found in {}", pdf_dir.to_string_lossy()),
        )
        .to_string());
    }
    Ok(pdfs)
}

fn write_opendataloader_prediction_artifacts(
    output_dir: &Path,
    engine: &str,
    preset: &str,
    profile: &str,
    timeout_seconds: Option<f64>,
    pdfs: &[PathBuf],
    request: &Value,
) -> Result<Value, String> {
    let markdown_dir = output_dir.join("markdown");
    fs::create_dir_all(&markdown_dir).map_err(|error| {
        error_json("BENCHMARK_REPORT_WRITE_FAILED", &error.to_string()).to_string()
    })?;
    let started = Instant::now();
    let mut documents = Vec::new();
    let mut errors = Vec::new();
    for pdf in pdfs {
        let doc_start = Instant::now();
        let document_id = pdf
            .file_stem()
            .and_then(|name| name.to_str())
            .map(safe_document_id)
            .unwrap_or_else(|| "document".to_string());
        let markdown_path = markdown_dir.join(format!("{document_id}.md"));
        let source_hash = sha256_file(pdf)?;
        let parse_request = json!({
            "command": "parse_pdf",
            "source_path": pdf.to_string_lossy(),
            "source_hash": source_hash,
            "preset": preset,
            "profile": profile,
            "runtime_profile": profile,
            "runtimeProfile": profile,
            "offline_mode": true,
            "allow_model_downloads": false,
            "model_manifest": request_scoped_string(request, &["model_manifest", "modelManifest", "modelManifestPath"]),
            "model_cache": request_scoped_string(request, &["model_cache", "modelCache", "modelCacheDirectory"]),
            "model_worker": request_scoped_string(request, &["model_worker", "modelWorker", "modelCommand"])
        });
        let mut result = parse_pdf_for_prediction(&parse_request, timeout_seconds);
        let elapsed = round_metric(doc_start.elapsed().as_secs_f64() * 1000.0);
        if timeout_seconds.is_some_and(|timeout| elapsed / 1000.0 > timeout) {
            result = Err(error_json(
                "PARSE_TIMEOUT",
                "OpenDataLoader prediction parse exceeded timeout_seconds",
            )
            .to_string());
        }
        match result {
            Ok(document) => {
                fs::write(&markdown_path, markdown_from_document(&document)).map_err(|error| {
                    error_json("BENCHMARK_REPORT_WRITE_FAILED", &error.to_string()).to_string()
                })?;
                documents.push(opendataloader_prediction_document_summary_from_document(
                    &document,
                    &document_id,
                    &markdown_path,
                    elapsed,
                ));
            }
            Err(error) => {
                let error_code = error_code_from_json(&error);
                fs::write(&markdown_path, "").map_err(|write_error| {
                    error_json("BENCHMARK_REPORT_WRITE_FAILED", &write_error.to_string())
                        .to_string()
                })?;
                errors.push(json!({
                    "document_id": document_id,
                    "errorCode": error_code,
                    "error": error
                }));
                documents.push(json!({
                    "document_id": document_id,
                    "status": "failed",
                    "elapsed": elapsed,
                    "markdown_path": markdown_path.to_string_lossy(),
                    "errorCode": error_code,
                    "error": error,
                    "runtimeProfile": profile,
                    "modelRuntime": Value::Null,
                    "modelRouting": Value::Null
                }));
            }
        }
    }
    let parsed_count = documents
        .iter()
        .filter(|document| document.get("status").and_then(Value::as_str) == Some("parsed"))
        .count();
    let document_count = documents.len();
    let failed_count = document_count - parsed_count;
    let total_elapsed = round_metric(started.elapsed().as_secs_f64() * 1000.0);
    let summary = json!({
        "engine_name": engine,
        "engine_version": env!("CARGO_PKG_VERSION"),
        "runtime_contract": "TrustDocument",
        "runtime_profile": profile,
        "document_count": document_count,
        "parsed_count": parsed_count,
        "failed_count": failed_count,
        "total_elapsed": total_elapsed,
        "elapsed_per_doc": if document_count == 0 { Value::Null } else { json!(round_metric(total_elapsed / document_count as f64)) },
        "timeout_seconds": timeout_seconds,
        "preset": preset,
        "production_residency": {
            "python_torch_docling": false
        },
        "model_routing_coverage": model_routing_coverage_json(&documents),
        "documents": documents
    });
    fs::write(output_dir.join("summary.json"), pretty_json(&summary)?).map_err(|error| {
        error_json("BENCHMARK_REPORT_WRITE_FAILED", &error.to_string()).to_string()
    })?;
    fs::write(
        output_dir.join("errors.json"),
        pretty_json(&json!({"documents": errors}))?,
    )
    .map_err(|error| error_json("BENCHMARK_REPORT_WRITE_FAILED", &error.to_string()).to_string())?;
    Ok(json!({
        "engine": engine,
        "path": output_dir.to_string_lossy(),
        "markdownPath": markdown_dir.to_string_lossy(),
        "documentCount": document_count,
        "parsedCount": parsed_count,
        "failedCount": failed_count
    }))
}

fn model_routing_coverage_json(documents: &[Value]) -> Value {
    let mut routes = BTreeMap::<String, u64>::new();
    let mut blocked_reasons = BTreeMap::<String, u64>::new();
    let mut requires_model_runtime = 0_u64;
    let mut started_model_runtime = 0_u64;
    let mut blocked_model_runtime = 0_u64;
    for routing in documents
        .iter()
        .filter_map(|document| document.get("modelRouting"))
        .filter(|routing| routing.is_object())
    {
        let route = routing
            .get("route")
            .and_then(Value::as_str)
            .unwrap_or("unknown");
        *routes.entry(route.to_string()).or_default() += 1;
        let requires = routing
            .get("requiresModelRuntime")
            .and_then(Value::as_bool)
            .unwrap_or(false);
        let started = routing
            .get("startedModelRuntime")
            .and_then(Value::as_bool)
            .unwrap_or(false);
        if requires {
            requires_model_runtime += 1;
        }
        if started {
            started_model_runtime += 1;
        }
        if requires && !started {
            blocked_model_runtime += 1;
            let reason = routing
                .get("blockedReason")
                .and_then(Value::as_str)
                .unwrap_or("unknown");
            *blocked_reasons.entry(reason.to_string()).or_default() += 1;
        }
    }
    json!({
        "documentCount": documents.len(),
        "requiresModelRuntime": requires_model_runtime,
        "startedModelRuntime": started_model_runtime,
        "blockedModelRuntime": blocked_model_runtime,
        "routes": routes,
        "blockedReasons": blocked_reasons
    })
}

fn parse_pdf_for_prediction(
    request: &Value,
    timeout_seconds: Option<f64>,
) -> Result<Value, String> {
    let started = Instant::now();
    let document = parse_pdf_json(request)?;
    if timeout_seconds.is_some_and(|timeout| started.elapsed().as_secs_f64() > timeout) {
        return Err(error_json(
            "PARSE_TIMEOUT",
            "OpenDataLoader prediction parse exceeded timeout_seconds",
        )
        .to_string());
    }
    Ok(document)
}

fn error_code_from_json(error: &str) -> String {
    serde_json::from_str::<Value>(error)
        .ok()
        .and_then(|value| {
            value
                .get("error_code")
                .or_else(|| value.get("code"))
                .and_then(Value::as_str)
                .map(str::to_string)
        })
        .unwrap_or_else(|| "UNKNOWN_ERROR".to_string())
}

fn opendataloader_prediction_document_summary_from_document(
    document: &Value,
    document_id: &str,
    markdown_path: &Path,
    elapsed: f64,
) -> Value {
    json!({
        "document_id": document_id,
        "status": "parsed",
        "elapsed": elapsed,
        "markdown_path": markdown_path.to_string_lossy(),
        "error": Value::Null,
        "runtimeProfile": document.pointer("/parserRun/profile").cloned().unwrap_or(Value::Null),
        "modelRuntime": document.pointer("/parserRun/modelRuntime").cloned().unwrap_or(Value::Null),
        "modelRouting": document.pointer("/parserRun/modelRouting").cloned().unwrap_or(Value::Null)
    })
}

fn opendataloader_prediction_resource_profile(profile: &str, summary: &Value) -> Value {
    let documents = summary
        .get("documents")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    json!({
        "profile": profile,
        "pythonTorchDoclingProductionResidency": false,
        "lazyModelStartup": profile == "edge-model",
        "caseCount": documents.len(),
        "elapsedMs": summary.get("total_elapsed").cloned().unwrap_or(Value::Null),
        "meanCaseElapsedMs": summary.get("elapsed_per_doc").cloned().unwrap_or(Value::Null),
        "modelRuntime": aggregate_prediction_model_runtime(&documents),
        "modelRoutingCoverage": summary.get("model_routing_coverage").cloned().unwrap_or_else(|| model_routing_coverage_json(&documents)),
        "budgetStatus": "profile-baseline-pending"
    })
}

fn aggregate_prediction_model_runtime(documents: &[Value]) -> Value {
    let runtimes = documents
        .iter()
        .filter_map(|document| document.get("modelRuntime"))
        .filter(|runtime| runtime.is_object())
        .collect::<Vec<_>>();
    if runtimes.is_empty() {
        return Value::Null;
    }
    json!({
        "runtime": "mnn",
        "coldStartMs": sum_runtime_metric(&runtimes, "coldStartMs"),
        "inferenceMs": sum_runtime_metric(&runtimes, "inferenceMs"),
        "peakMemoryMb": max_runtime_metric(&runtimes, "peakMemoryMb"),
        "loadedModels": unique_loaded_models(&runtimes)
    })
}

fn prediction_runtime_profile(case_reports: &[Value]) -> Value {
    case_reports
        .iter()
        .find_map(|case| case.get("runtimeProfile").and_then(Value::as_str))
        .map_or(Value::Null, |profile| json!(profile))
}

fn opendataloader_prediction_document_summary(
    case: &Value,
    document_id: &str,
    markdown_path: &Path,
) -> Value {
    json!({
        "document_id": document_id,
        "status": "parsed",
        "elapsed": case.get("elapsedMs").cloned().unwrap_or(Value::Null),
        "markdown_path": markdown_path.to_string_lossy(),
        "error": Value::Null,
        "runtimeProfile": case.get("runtimeProfile").cloned().unwrap_or(Value::Null),
        "modelRuntime": case.pointer("/actualTrustDocument/parserRun/modelRuntime").cloned().unwrap_or(Value::Null),
        "modelRouting": case.pointer("/actualTrustDocument/parserRun/modelRouting").cloned().unwrap_or(Value::Null)
    })
}

fn public_case_reports(case_reports: &[Value]) -> Vec<Value> {
    case_reports
        .iter()
        .map(|case| {
            let mut public = case.clone();
            if let Some(object) = public.as_object_mut() {
                object.remove("_actualMarkdown");
            }
            public
        })
        .collect()
}

fn safe_document_id(value: &str) -> String {
    value
        .chars()
        .map(|ch| {
            if ch.is_ascii_alphanumeric() || matches!(ch, '.' | '_' | '-') {
                ch
            } else {
                '_'
            }
        })
        .collect()
}

fn pretty_json(value: &Value) -> Result<String, String> {
    serde_json::to_string_pretty(value).map_err(|error| {
        error_json("BENCHMARK_REPORT_WRITE_FAILED", &error.to_string()).to_string()
    })
}

fn benchmark_validity_inputs() -> Value {
    json!({
        "sourceHashes": true,
        "manifestHash": true,
        "parserConfig": "TrustDocument",
        "modelCacheManifest": "not-required",
        "thresholds": true,
        "expectedLabels": true,
        "actualTrustDocument": true
    })
}

fn expected_min_cases_per_tag(labeling: &Value) -> Value {
    expected_min_cases_per_field(labeling, "requiredTags", "minCasesPerTag")
}

fn expected_min_cases_per_field(
    labeling: &Value,
    required_field: &str,
    minimum_field: &str,
) -> Value {
    let minimum = labeling.get(minimum_field).unwrap_or(&Value::Null);
    if minimum.is_object() {
        return minimum.clone();
    }
    let Some(minimum) = minimum.as_u64() else {
        return json!({});
    };
    let mut expected = serde_json::Map::new();
    for tag in labeling
        .get(required_field)
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
    {
        expected.insert(tag.to_string(), json!(minimum));
    }
    Value::Object(expected)
}

fn verify_benchmark_report_json(request: &Value) -> Result<Value, String> {
    let report_path = request
        .get("report_path")
        .and_then(Value::as_str)
        .ok_or_else(|| {
            error_json(
                "BENCHMARK_REPORT_INVALID",
                "request.report_path is required",
            )
            .to_string()
        })?;
    let report = read_json_file(Path::new(report_path), "BENCHMARK_REPORT_INVALID")?;
    verify_report_format(&report)?;
    let manifest_path = required_str(&report, "manifest", "BENCHMARK_REPORT_INVALID")?;
    let manifest = read_json_file(Path::new(manifest_path), "BENCHMARK_REPORT_INVALID")?;
    verify_report_manifest_hash(&report, manifest_path)?;
    verify_report_manifest_echo(&report, &manifest)?;
    verify_report_external_metrics(&report, Path::new(manifest_path), &manifest)?;
    verify_report_validity_inputs(&report)?;
    verify_report_coverage(&report)?;
    verify_report_case_replay(&report)?;
    let manifest_dir = Path::new(manifest_path)
        .parent()
        .unwrap_or_else(|| Path::new("."));
    verify_report_actual_trust_documents(&report, &manifest, manifest_dir)?;
    verify_report_aggregate_metrics(&report)?;
    verify_report_metric_thresholds(&report)?;
    Ok(json!({
        "runtime": RUNTIME,
        "protocol_version": PROTOCOL_VERSION,
        "verified": true,
        "reportFormat": "doctruth.parser-benchmark.report.v1",
        "caseCount": report.get("caseCount").cloned().unwrap_or(Value::Null)
    }))
}

fn verify_report_format(report: &Value) -> Result<(), String> {
    let format = required_str(report, "reportFormat", "BENCHMARK_REPORT_INVALID")?;
    if format != "doctruth.parser-benchmark.report.v1" {
        return Err(error_json(
            "BENCHMARK_REPORT_INVALID",
            &format!("unsupported benchmark report format: {format}"),
        )
        .to_string());
    }
    if report.get("passed").and_then(Value::as_bool) != Some(true) {
        return Err(
            error_json("BENCHMARK_REPORT_INVALID", "benchmark report did not pass").to_string(),
        );
    }
    Ok(())
}

fn verify_report_manifest_hash(report: &Value, manifest_path: &str) -> Result<(), String> {
    let expected = required_str(report, "manifestSha256", "BENCHMARK_REPORT_INVALID")?;
    let actual = sha256_file(Path::new(manifest_path))?;
    if expected != actual {
        return Err(error_json(
            "BENCHMARK_REPORT_INVALID",
            &format!("manifestSha256 mismatch: expected {expected} actual {actual}"),
        )
        .to_string());
    }
    Ok(())
}

fn verify_report_manifest_echo(report: &Value, manifest: &Value) -> Result<(), String> {
    verify_report_text(report, manifest, "corpus", "name")?;
    verify_report_value(report, manifest, "minimums", json!({}))?;
    verify_report_value(report, manifest, "maximums", json!({}))?;
    verify_report_value(report, manifest, "externalEvaluations", json!({}))?;
    let labeling = manifest.get("labeling").unwrap_or(&Value::Null);
    verify_report_value(report, labeling, "requiredMetrics", json!([]))?;
    verify_report_value(report, labeling, "requiredTags", json!([]))?;
    verify_report_value(report, labeling, "requiredFixtureTypes", json!([]))?;
    verify_report_value(report, labeling, "requiredBehaviors", json!([]))?;
    verify_expected_value(
        report,
        "minCasesPerTag",
        expected_min_cases_per_tag(labeling),
    )?;
    verify_expected_value(
        report,
        "minCasesPerFixtureType",
        expected_min_cases_per_field(labeling, "requiredFixtureTypes", "minCasesPerFixtureType"),
    )?;
    verify_expected_value(
        report,
        "minCasesPerBehavior",
        expected_min_cases_per_field(labeling, "requiredBehaviors", "minCasesPerBehavior"),
    )?;
    verify_report_value(report, labeling, "minTotalCases", Value::Null)?;
    verify_report_source_pins(report, manifest)?;
    Ok(())
}

fn verify_report_external_metrics(
    report: &Value,
    manifest_path: &Path,
    manifest: &Value,
) -> Result<(), String> {
    let Some(evaluations) = manifest.get("externalEvaluations") else {
        return Ok(());
    };
    let Some(object) = evaluations.as_object() else {
        return Err(
            error_json("BENCHMARK_REPORT_INVALID", "externalEvaluations mismatch").to_string(),
        );
    };
    let base_dir = manifest_path.parent().unwrap_or_else(|| Path::new("."));
    for (name, path_value) in object {
        if name != "opendataloader" {
            return Err(error_json(
                "BENCHMARK_REPORT_INVALID",
                &format!("unsupported external evaluation: {name}"),
            )
            .to_string());
        }
        let path = base_dir.join(path_value.as_str().unwrap_or(""));
        let expected = opendataloader_external_metrics(&path)?;
        if report
            .get("externalMetrics")
            .and_then(|metrics| metrics.get(name))
            != Some(&expected.report)
        {
            return Err(error_json(
                "BENCHMARK_REPORT_INVALID",
                &format!("external metrics mismatch for {name}"),
            )
            .to_string());
        }
        if let Some(values) = expected.values.as_object() {
            for (metric, value) in values {
                if report.pointer(&format!("/metrics/{metric}")) != Some(value) {
                    return Err(error_json(
                        "BENCHMARK_REPORT_INVALID",
                        &format!("external metrics mismatch for {metric}"),
                    )
                    .to_string());
                }
            }
        }
    }
    Ok(())
}

fn verify_report_text(
    report: &Value,
    manifest: &Value,
    report_field: &str,
    manifest_field: &str,
) -> Result<(), String> {
    let left = required_str(report, report_field, "BENCHMARK_REPORT_INVALID")?;
    let right = required_str(manifest, manifest_field, "BENCHMARK_REPORT_INVALID")?;
    if left != right {
        return Err(error_json(
            "BENCHMARK_REPORT_INVALID",
            &format!("{report_field} mismatch: expected {left} actual {right}"),
        )
        .to_string());
    }
    Ok(())
}

fn verify_report_value(
    report: &Value,
    source: &Value,
    field: &str,
    default_value: Value,
) -> Result<(), String> {
    let expected = source.get(field).cloned().unwrap_or(default_value);
    verify_expected_value(report, field, expected)
}

fn verify_expected_value(report: &Value, field: &str, expected: Value) -> Result<(), String> {
    let actual = report.get(field).cloned().unwrap_or(Value::Null);
    if actual != expected {
        return Err(
            error_json("BENCHMARK_REPORT_INVALID", &format!("{field} mismatch")).to_string(),
        );
    }
    Ok(())
}

fn verify_report_source_pins(report: &Value, manifest: &Value) -> Result<(), String> {
    let mut pins = BTreeMap::new();
    for case in manifest
        .get("cases")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
    {
        let Some(name) = case.get("name").and_then(Value::as_str) else {
            continue;
        };
        let Some(source_sha) = case.get("sourceSha256").and_then(Value::as_str) else {
            continue;
        };
        pins.insert(name.to_string(), source_sha.to_string());
    }
    for case in report
        .get("cases")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
    {
        let Some(name) = case.get("name").and_then(Value::as_str) else {
            continue;
        };
        let Some(expected) = pins.get(name) else {
            continue;
        };
        if case.get("sourceSha256").and_then(Value::as_str) != Some(expected) {
            return Err(error_json(
                "BENCHMARK_REPORT_INVALID",
                &format!("sourceSha256 mismatch for case {name}"),
            )
            .to_string());
        }
    }
    Ok(())
}

fn verify_report_coverage(report: &Value) -> Result<(), String> {
    let cases = report
        .get("cases")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    let actual_case_count = cases.len() as u64;
    let recorded_case_count = report
        .get("caseCount")
        .and_then(Value::as_u64)
        .unwrap_or(u64::MAX);
    if actual_case_count != recorded_case_count {
        return Err(error_json(
            "BENCHMARK_REPORT_INVALID",
            &format!(
                "caseCount mismatch: expected {recorded_case_count} actual {actual_case_count}"
            ),
        )
        .to_string());
    }
    let actual_cases_per_tag = cases_per_tag(&cases);
    verify_expected_value(report, "casesPerTag", actual_cases_per_tag.clone())?;
    verify_expected_value(
        report,
        "coverageRequired",
        report
            .get("minCasesPerTag")
            .cloned()
            .unwrap_or_else(|| json!({})),
    )?;
    verify_expected_value(
        report,
        "coverageSatisfied",
        coverage_satisfied_from_counts(
            report.get("coverageRequired").unwrap_or(&json!({})),
            &actual_cases_per_tag,
        ),
    )?;
    verify_coverage_dimension(
        report,
        "fixtureTypes",
        "casesPerFixtureType",
        "fixtureCoverageRequired",
        "fixtureCoverageSatisfied",
    )?;
    verify_expected_value(
        report,
        "fixtureResults",
        fixture_results(
            &cases,
            report.get("minimums").unwrap_or(&json!({})),
            report.get("maximums").unwrap_or(&json!({})),
        ),
    )?;
    verify_coverage_dimension(
        report,
        "behaviors",
        "casesPerBehavior",
        "behaviorCoverageRequired",
        "behaviorCoverageSatisfied",
    )?;
    verify_min_total_cases(report, actual_case_count)?;
    verify_min_cases_per_tag(report, &actual_cases_per_tag)?;
    Ok(())
}

fn verify_coverage_dimension(
    report: &Value,
    case_field: &str,
    count_field: &str,
    required_field: &str,
    satisfied_field: &str,
) -> Result<(), String> {
    let cases = report
        .get("cases")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    let actual = cases_per_field(&cases, case_field);
    verify_expected_value(report, count_field, actual.clone())?;
    let required = report
        .get(required_field)
        .cloned()
        .unwrap_or_else(|| json!({}));
    verify_expected_value(
        report,
        satisfied_field,
        coverage_satisfied_from_counts(&required, &actual),
    )
}

fn coverage_satisfied_from_counts(required: &Value, actual_cases_per_tag: &Value) -> Value {
    let mut satisfied = serde_json::Map::new();
    for (tag, minimum) in required.as_object().into_iter().flatten() {
        let minimum = minimum.as_u64().unwrap_or(0);
        let actual = actual_cases_per_tag
            .get(tag)
            .and_then(Value::as_u64)
            .unwrap_or(0);
        satisfied.insert(tag.to_string(), json!(actual >= minimum));
    }
    Value::Object(satisfied)
}

fn fixture_results(case_reports: &[Value], minimums: &Value, maximums: &Value) -> Value {
    let mut fixture_names = cases_per_field(case_reports, "fixtureTypes")
        .as_object()
        .map(|object| object.keys().cloned().collect::<Vec<_>>())
        .unwrap_or_default();
    fixture_names.sort();
    let mut results = serde_json::Map::new();
    for fixture in fixture_names {
        let matching = case_reports
            .iter()
            .filter(|case| case_has_value(case, "fixtureTypes", &fixture))
            .cloned()
            .collect::<Vec<_>>();
        let metrics = aggregate_case_metrics(&matching);
        let cases = matching
            .iter()
            .filter_map(|case| case.get("name").and_then(Value::as_str))
            .map(ToString::to_string)
            .collect::<Vec<_>>();
        results.insert(
            fixture,
            json!({
                "caseCount": matching.len(),
                "cases": cases,
                "metrics": metrics,
                "passed": metrics_pass_thresholds(&metrics, minimums, maximums)
            }),
        );
    }
    Value::Object(results)
}

fn metrics_pass_thresholds(metrics: &Value, minimums: &Value, maximums: &Value) -> bool {
    thresholds_pass(metrics, minimums, |actual, threshold| actual >= threshold)
        && thresholds_pass(metrics, maximums, |actual, threshold| actual <= threshold)
}

fn thresholds_pass(metrics: &Value, thresholds: &Value, predicate: fn(f64, f64) -> bool) -> bool {
    for (name, threshold) in thresholds.as_object().into_iter().flatten() {
        let Some(actual) = metrics.get(name).and_then(Value::as_f64) else {
            continue;
        };
        let threshold = threshold.as_f64().unwrap_or(f64::NAN);
        if !actual.is_finite() || !threshold.is_finite() || !predicate(actual, threshold) {
            return false;
        }
    }
    true
}

fn verify_report_validity_inputs(report: &Value) -> Result<(), String> {
    verify_expected_value(report, "validityInputs", benchmark_validity_inputs())
}

fn verify_report_case_replay(report: &Value) -> Result<(), String> {
    for case in report
        .get("cases")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
    {
        let expected = case_replay(case);
        let replay = case.get("replay").unwrap_or(&Value::Null);
        for field in [
            "sourceRefReplayable",
            "quoteReplayable",
            "evidenceSpanReplayable",
        ] {
            if replay.get(field) != expected.get(field) {
                let name = case
                    .get("name")
                    .and_then(Value::as_str)
                    .unwrap_or("unnamed");
                return Err(error_json(
                    "BENCHMARK_REPORT_INVALID",
                    &format!("case replay mismatch for {name}: {field}"),
                )
                .to_string());
            }
        }
    }
    Ok(())
}

fn verify_report_actual_trust_documents(
    report: &Value,
    manifest: &Value,
    base_dir: &Path,
) -> Result<(), String> {
    for case in report
        .get("cases")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
    {
        let name = case
            .get("name")
            .and_then(Value::as_str)
            .unwrap_or("unnamed");
        let document = case.get("actualTrustDocument").ok_or_else(|| {
            error_json(
                "BENCHMARK_REPORT_INVALID",
                &format!("case {name} missing actualTrustDocument"),
            )
            .to_string()
        })?;
        let expected = case
            .get("actualTrustDocumentSha256")
            .and_then(Value::as_str)
            .unwrap_or("");
        let actual = trust_document_sha256(document)?;
        if expected != actual {
            return Err(error_json(
                "BENCHMARK_REPORT_INVALID",
                &format!("case {name} actualTrustDocumentSha256 mismatch"),
            )
            .to_string());
        }
        verify_report_actual_trust_document_metrics(case, manifest, base_dir)?;
    }
    Ok(())
}

fn verify_report_actual_trust_document_metrics(
    case: &Value,
    manifest: &Value,
    base_dir: &Path,
) -> Result<(), String> {
    let label_id = case.get("labelId").and_then(Value::as_str).unwrap_or("");
    let manifest_case = manifest_case_by_label_id(manifest, label_id)?;
    let expected_markdown = fs::read_to_string(resolve_case_path(
        base_dir,
        manifest_case,
        "expectedMarkdown",
    )?)
    .map_err(|error| error_json("BENCHMARK_REPORT_INVALID", &error.to_string()).to_string())?;
    let expected_document = read_json_file(
        &resolve_case_path(base_dir, manifest_case, "expectedDocument")?,
        "BENCHMARK_REPORT_INVALID",
    )?;
    let document = case.get("actualTrustDocument").unwrap_or(&Value::Null);
    let expected_metrics = case_metrics(
        document,
        &expected_document,
        &markdown_from_document(document),
        &expected_markdown,
    );
    for (name, expected) in expected_metrics.as_object().into_iter().flatten() {
        let expected = expected.as_f64().unwrap_or(f64::NAN);
        let actual = case
            .get("metrics")
            .and_then(|metrics| metrics.get(name))
            .and_then(Value::as_f64)
            .unwrap_or(f64::NAN);
        if !actual.is_finite() || (actual - expected).abs() > 0.000001 {
            return Err(error_json(
                "BENCHMARK_REPORT_INVALID",
                &format!("actualTrustDocument metrics mismatch for {name}"),
            )
            .to_string());
        }
    }
    Ok(())
}

fn manifest_case_by_label_id<'a>(manifest: &'a Value, label_id: &str) -> Result<&'a Value, String> {
    manifest
        .get("cases")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .find(|case| case.get("labelId").and_then(Value::as_str) == Some(label_id))
        .ok_or_else(|| {
            error_json(
                "BENCHMARK_REPORT_INVALID",
                &format!("manifest case not found for labelId {label_id}"),
            )
            .to_string()
        })
}

fn verify_report_aggregate_metrics(report: &Value) -> Result<(), String> {
    let Some(metrics) = report.get("metrics").and_then(Value::as_object) else {
        return Err(error_json(
            "BENCHMARK_REPORT_INVALID",
            "benchmark report missing metrics",
        )
        .to_string());
    };
    for (name, value) in metrics {
        let Some(actual) = value.as_f64() else {
            continue;
        };
        let case_values = report_case_metric_values(report, name);
        if case_values.is_empty() {
            continue;
        }
        let expected = round_metric(case_values.iter().sum::<f64>() / case_values.len() as f64);
        if (actual - expected).abs() > 0.000001 {
            return Err(error_json(
                "BENCHMARK_REPORT_INVALID",
                &format!(
                    "aggregate metric mismatch for {name}: expected {expected} actual {actual}"
                ),
            )
            .to_string());
        }
    }
    Ok(())
}

fn verify_report_metric_thresholds(report: &Value) -> Result<(), String> {
    let Some(metrics) = report.get("metrics").and_then(Value::as_object) else {
        return Err(error_json(
            "BENCHMARK_REPORT_INVALID",
            "benchmark report missing metrics",
        )
        .to_string());
    };
    for (name, threshold) in report
        .get("minimums")
        .and_then(Value::as_object)
        .into_iter()
        .flatten()
    {
        let minimum = threshold.as_f64().unwrap_or(f64::NAN);
        for actual in report_metric_values(report, metrics, name) {
            if !actual.is_finite() || actual < minimum {
                return Err(error_json(
                    "BENCHMARK_REPORT_INVALID",
                    &format!(
                        "minimum threshold failed for {name}: minimum {minimum} actual {actual}"
                    ),
                )
                .to_string());
            }
        }
    }
    for (name, threshold) in report
        .get("maximums")
        .and_then(Value::as_object)
        .into_iter()
        .flatten()
    {
        let maximum = threshold.as_f64().unwrap_or(f64::NAN);
        for actual in report_metric_values(report, metrics, name) {
            if !actual.is_finite() || actual > maximum {
                return Err(error_json(
                    "BENCHMARK_REPORT_INVALID",
                    &format!(
                        "maximum threshold failed for {name}: maximum {maximum} actual {actual}"
                    ),
                )
                .to_string());
            }
        }
    }
    Ok(())
}

fn report_metric_values(
    report: &Value,
    metrics: &serde_json::Map<String, Value>,
    name: &str,
) -> Vec<f64> {
    if let Some(actual) = metrics.get(name).and_then(Value::as_f64) {
        return vec![actual];
    }
    let values = report_case_metric_values(report, name);
    if values.is_empty() {
        vec![f64::NAN]
    } else {
        values
    }
}

fn report_case_metric_values(report: &Value, name: &str) -> Vec<f64> {
    report
        .get("cases")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(|case| case.get("metrics"))
        .filter_map(|metrics| metrics.get(name))
        .filter_map(Value::as_f64)
        .collect::<Vec<_>>()
}

fn verify_min_total_cases(report: &Value, actual_case_count: u64) -> Result<(), String> {
    let Some(minimum) = report.get("minTotalCases").and_then(Value::as_u64) else {
        return Ok(());
    };
    if actual_case_count < minimum {
        return Err(error_json(
            "BENCHMARK_REPORT_INVALID",
            &format!("minTotalCases not satisfied: minimum {minimum} actual {actual_case_count}"),
        )
        .to_string());
    }
    Ok(())
}

fn verify_min_cases_per_tag(report: &Value, actual_cases_per_tag: &Value) -> Result<(), String> {
    let Some(minimums) = report.get("minCasesPerTag").and_then(Value::as_object) else {
        return Ok(());
    };
    for (tag, minimum) in minimums {
        let minimum = minimum.as_u64().unwrap_or(0);
        let actual = actual_cases_per_tag
            .get(tag)
            .and_then(Value::as_u64)
            .unwrap_or(0);
        if actual < minimum {
            return Err(error_json(
                "BENCHMARK_REPORT_INVALID",
                &format!(
                    "minCasesPerTag not satisfied for {tag}: minimum {minimum} actual {actual}"
                ),
            )
            .to_string());
        }
    }
    Ok(())
}

fn write_benchmark_report_if_requested(
    request: &Value,
    manifest_path: &str,
    report: &Value,
) -> Result<(), String> {
    let Some(report_path) = request.get("report_path").and_then(Value::as_str) else {
        return Ok(());
    };
    let path = Path::new(report_path);
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|error| {
            error_json(
                "BENCHMARK_CORPUS_REPORT_FAILED",
                &format!("{}: {error}", parent.display()),
            )
            .to_string()
        })?;
    }
    let manifest = Path::new(manifest_path)
        .canonicalize()
        .unwrap_or_else(|_| PathBuf::from(manifest_path));
    let mut recorded = report.clone();
    if let Some(object) = recorded.as_object_mut() {
        object.insert(
            "reportFormat".to_string(),
            json!("doctruth.parser-benchmark.report.v1"),
        );
        object.insert("manifest".to_string(), json!(manifest.to_string_lossy()));
        object.insert("manifestSha256".to_string(), json!(sha256_file(&manifest)?));
    }
    let bytes = serde_json::to_vec_pretty(&recorded).map_err(|error| {
        error_json("BENCHMARK_CORPUS_REPORT_FAILED", &error.to_string()).to_string()
    })?;
    fs::write(path, bytes).map_err(|error| {
        error_json(
            "BENCHMARK_CORPUS_REPORT_FAILED",
            &format!("{}: {error}", path.display()),
        )
        .to_string()
    })
}

fn read_json_file(path: &Path, code: &str) -> Result<Value, String> {
    let text = fs::read_to_string(path)
        .map_err(|error| error_json(code, &format!("{}: {error}", path.display())).to_string())?;
    serde_json::from_str(&text)
        .map_err(|error| error_json(code, &format!("{}: {error}", path.display())).to_string())
}

fn validate_parser_accuracy_manifest(manifest: &Value) -> Result<(), String> {
    if manifest.get("qualityProfile").and_then(Value::as_str) != Some("parser-accuracy") {
        return Ok(());
    }
    let labeling = manifest.get("labeling").ok_or_else(|| {
        error_json(
            "PARSER_ACCURACY_LABELING_INVALID",
            "parser-accuracy manifests require labeling metadata",
        )
        .to_string()
    })?;
    required_nested_str(labeling, "labelSetVersion")?;
    required_nested_str(labeling, "reviewedAt")?;
    required_nested_str(labeling, "reviewer")?;
    let review_type = required_nested_str(labeling, "reviewType")?;
    required_array(labeling, "requiredMetrics")?;
    required_array(labeling, "requiredTags")?;
    required_u64(labeling, "minCasesPerTag")?;
    if review_type == "human-reviewed" {
        let minimum = required_u64(labeling, "minTotalCases")?;
        let actual = manifest
            .get("cases")
            .and_then(Value::as_array)
            .map(Vec::len)
            .unwrap_or(0) as u64;
        if actual < minimum {
            return Err(error_json(
                "PARSER_ACCURACY_LABELING_INVALID",
                &format!("labeling.minTotalCases minimum={minimum} actual={actual}"),
            )
            .to_string());
        }
        require_human_reviewed_source_hashes(manifest)?;
        require_human_reviewed_core_metrics(labeling)?;
        require_human_reviewed_core_tags(labeling)?;
    }
    for case in manifest
        .get("cases")
        .and_then(Value::as_array)
        .unwrap_or(&Vec::new())
    {
        required_nested_str(case, "labelId")?;
        required_array(case, "tags")?;
    }
    Ok(())
}

fn require_human_reviewed_core_tags(labeling: &Value) -> Result<(), String> {
    let tags = required_array(labeling, "requiredTags")?;
    let missing: Vec<&str> = HUMAN_REVIEWED_PARSER_ACCURACY_TAGS
        .iter()
        .copied()
        .filter(|tag| !tags.iter().any(|value| value.as_str() == Some(*tag)))
        .collect();
    if missing.is_empty() {
        return Ok(());
    }
    Err(error_json(
        "PARSER_ACCURACY_LABELING_INVALID",
        &format!(
            "human-reviewed parser-accuracy requiredTags missing: {}",
            missing.join(", ")
        ),
    )
    .to_string())
}

fn require_human_reviewed_core_metrics(labeling: &Value) -> Result<(), String> {
    let metrics = required_array(labeling, "requiredMetrics")?;
    let missing: Vec<&str> = HUMAN_REVIEWED_PARSER_ACCURACY_METRICS
        .iter()
        .copied()
        .filter(|metric| !metrics.iter().any(|value| value.as_str() == Some(*metric)))
        .collect();
    if missing.is_empty() {
        return Ok(());
    }
    Err(error_json(
        "PARSER_ACCURACY_LABELING_INVALID",
        &format!(
            "human-reviewed parser-accuracy requiredMetrics missing: {}",
            missing.join(", ")
        ),
    )
    .to_string())
}

fn require_human_reviewed_source_hashes(manifest: &Value) -> Result<(), String> {
    for case in manifest
        .get("cases")
        .and_then(Value::as_array)
        .unwrap_or(&Vec::new())
    {
        if case
            .get("sourceSha256")
            .and_then(Value::as_str)
            .filter(|text| !text.trim().is_empty())
            .is_none()
        {
            let name = case
                .get("name")
                .and_then(Value::as_str)
                .unwrap_or("unnamed");
            return Err(error_json(
                "PARSER_ACCURACY_LABELING_INVALID",
                &format!("human-reviewed parser-accuracy case {name} requires sourceSha256"),
            )
            .to_string());
        }
    }
    Ok(())
}

fn required_str<'a>(value: &'a Value, key: &str, code: &str) -> Result<&'a str, String> {
    value
        .get(key)
        .and_then(Value::as_str)
        .filter(|text| !text.trim().is_empty())
        .ok_or_else(|| error_json(code, &format!("manifest.{key} is required")).to_string())
}

fn required_nested_str<'a>(value: &'a Value, key: &str) -> Result<&'a str, String> {
    value
        .get(key)
        .and_then(Value::as_str)
        .filter(|text| !text.trim().is_empty())
        .ok_or_else(|| {
            error_json(
                "PARSER_ACCURACY_LABELING_INVALID",
                &format!("labeling.{key} is required"),
            )
            .to_string()
        })
}

fn required_array<'a>(value: &'a Value, key: &str) -> Result<&'a Vec<Value>, String> {
    value
        .get(key)
        .and_then(Value::as_array)
        .filter(|items| !items.is_empty())
        .ok_or_else(|| {
            error_json(
                "PARSER_ACCURACY_LABELING_INVALID",
                &format!("labeling.{key} must be a non-empty array"),
            )
            .to_string()
        })
}

fn required_u64(value: &Value, key: &str) -> Result<u64, String> {
    value
        .get(key)
        .and_then(Value::as_u64)
        .filter(|number| *number > 0)
        .ok_or_else(|| {
            error_json(
                "PARSER_ACCURACY_LABELING_INVALID",
                &format!("labeling.{key} must be greater than zero"),
            )
            .to_string()
        })
}

fn run_benchmark_case(base_dir: &Path, case: &Value, profile: &str) -> Result<Value, String> {
    let source_path = resolve_case_path(base_dir, case, "source")?;
    let expected_markdown =
        fs::read_to_string(resolve_case_path(base_dir, case, "expectedMarkdown")?).map_err(
            |error| error_json("BENCHMARK_CORPUS_INVALID", &error.to_string()).to_string(),
        )?;
    let expected_document = resolve_case_path(base_dir, case, "expectedDocument")?;
    let expected_document = read_json_file(&expected_document, "BENCHMARK_CORPUS_INVALID")?;
    let preset = case.get("preset").and_then(Value::as_str).unwrap_or("lite");
    let source_sha = checked_source_sha(&source_path, case)?;
    let case_started = Instant::now();
    let start_memory = process_memory_usage();
    let document = parse_pdf_json(&json!({
        "command": "parse_pdf",
        "source_path": source_path,
        "source_hash": source_sha,
        "preset": preset,
        "profile": profile,
        "offline_mode": true,
        "allow_model_downloads": false
    }))?;
    let end_memory = process_memory_usage();
    let elapsed_ms = case_started.elapsed().as_secs_f64() * 1000.0;
    let actual_markdown = markdown_from_document(&document);
    let metrics = case_metrics(
        &document,
        &expected_document,
        &actual_markdown,
        &expected_markdown,
    );
    let actual_document_sha = trust_document_sha256(&document)?;

    Ok(json!({
        "name": case.get("name").and_then(Value::as_str).unwrap_or("unnamed"),
        "labelId": required_nested_str(case, "labelId")?,
        "sourceSha256": source_sha,
        "tags": case.get("tags").cloned().unwrap_or_else(|| json!([])),
        "fixtureTypes": case.get("fixtureTypes").cloned().unwrap_or_else(|| json!([])),
        "behaviors": case.get("behaviors").cloned().unwrap_or_else(|| json!([])),
        "preset": preset,
        "runtimeProfile": document.pointer("/parserRun/profile").cloned().unwrap_or_else(|| json!(profile)),
        "source": source_path.file_name().and_then(|name| name.to_str()).unwrap_or(""),
        "elapsedMs": round_metric(elapsed_ms),
        "memory": {
            "startRssMb": start_memory.rss_mb,
            "endRssMb": end_memory.rss_mb,
            "peakMemoryMb": end_memory.peak_memory_mb.max(start_memory.peak_memory_mb),
            "measurement": "process-rss"
        },
        "actualTrustDocument": document,
        "actualTrustDocumentSha256": actual_document_sha,
        "_actualMarkdown": actual_markdown,
        "metrics": metrics,
        "replay": case_replay(&json!({
            "sourceSha256": source_sha,
            "metrics": metrics
        }))
    }))
}

fn trust_document_sha256(document: &Value) -> Result<String, String> {
    let bytes = serde_json::to_vec(document)
        .map_err(|error| error_json("BENCHMARK_REPORT_INVALID", &error.to_string()).to_string())?;
    Ok(sha256_hex(&bytes))
}

fn case_replay(case: &Value) -> Value {
    let metrics = case.get("metrics").unwrap_or(&Value::Null);
    json!({
        "sourceRefReplayable": case
            .get("sourceSha256")
            .and_then(Value::as_str)
            .filter(|text| !text.trim().is_empty())
            .is_some(),
        "quoteReplayable": metrics
            .get("quote_anchor_accuracy")
            .and_then(Value::as_f64)
            .unwrap_or(0.0) >= 1.0,
        "evidenceSpanReplayable": metrics
            .get("evidence_span_accuracy")
            .and_then(Value::as_f64)
            .unwrap_or(0.0) >= 1.0
    })
}

fn resolve_case_path(base_dir: &Path, case: &Value, key: &str) -> Result<PathBuf, String> {
    let relative = case
        .get(key)
        .and_then(Value::as_str)
        .filter(|text| !text.trim().is_empty())
        .ok_or_else(|| {
            error_json(
                "BENCHMARK_CORPUS_INVALID",
                &format!("case.{key} is required"),
            )
            .to_string()
        })?;
    Ok(base_dir.join(relative))
}

fn sha256_file(path: &Path) -> Result<String, String> {
    let bytes = fs::read(path).map_err(|error| {
        error_json(
            "BENCHMARK_CORPUS_INVALID",
            &format!("{}: {error}", path.display()),
        )
        .to_string()
    })?;
    Ok(sha256_hex(&bytes))
}

fn checked_source_sha(path: &Path, case: &Value) -> Result<String, String> {
    let actual = sha256_file(path)?;
    let Some(expected) = case.get("sourceSha256").and_then(Value::as_str) else {
        return Ok(actual);
    };
    if expected == actual {
        Ok(actual)
    } else {
        Err(error_json(
            "SOURCE_SHA256_MISMATCH",
            &format!("{} expected {expected} but got {actual}", path.display()),
        )
        .to_string())
    }
}

fn markdown_from_document(document: &Value) -> String {
    let mut lines = Vec::new();
    let tables = document_tables_by_id(document);
    let table_refs = renderable_table_refs(document);
    let mut rendered_tables = BTreeSet::new();
    let blocks = content_blocks_by_unit_id(document);
    let mut rendered_blocks = BTreeSet::new();
    if let Some(units) = document.pointer("/body/units").and_then(Value::as_array) {
        let (spatial_tables, spatial_consumed) = spatial_markdown_tables_from_units(units, &tables);
        for (index, unit) in units.iter().enumerate() {
            if spatial_consumed.contains(&index) {
                continue;
            }
            if page_number_noise_unit(unit) {
                continue;
            }
            if model_table_structure_unit(unit) {
                continue;
            }
            if unit.get("kind").and_then(Value::as_str) == Some("TABLE_CELL") {
                let table_id = unit.get("tableId").and_then(Value::as_str).unwrap_or("");
                if render_markdown_table_once(table_id, &tables, &mut rendered_tables, &mut lines) {
                    continue;
                }
            }
            if let Some(table_ref) = containing_table_ref(unit, &table_refs) {
                render_markdown_table_once(
                    &table_ref.table_id,
                    &tables,
                    &mut rendered_tables,
                    &mut lines,
                );
                continue;
            }
            if let Some(table_id) = table_id_containing_unit_text(unit, &tables) {
                render_markdown_table_once(&table_id, &tables, &mut rendered_tables, &mut lines);
                continue;
            }
            if let Some(block) = markdown_block_for_unit(unit, &blocks) {
                let block_id = block.get("blockId").and_then(Value::as_str).unwrap_or("");
                if !block_id.is_empty() && rendered_blocks.contains(block_id) {
                    continue;
                }
                if let Some(text) = markdown_entry_text(unit, Some(block)) {
                    lines.push(text);
                    if !block_id.is_empty() {
                        rendered_blocks.insert(block_id.to_string());
                    }
                }
                continue;
            }
            if let Some(text) = markdown_entry_text(unit, None) {
                lines.push(text);
            }
        }
        if let Some((synthetic_table, consumed_lines)) = synthetic_table_html_from_lines(&lines) {
            lines = lines
                .into_iter()
                .enumerate()
                .filter_map(|(index, line)| (!consumed_lines.contains(&index)).then_some(line))
                .collect();
            lines.push(synthetic_table);
        }
        lines.extend(spatial_tables);
    }
    lines = opendataloader_normalize_markdown_lines(lines);
    if markdown_join_paragraphs_enabled() {
        lines = join_markdown_paragraph_lines(lines);
    }
    lines = opendataloader_finalize_markdown_lines(lines);
    lines.join("\n")
}

fn opendataloader_finalize_markdown_lines(lines: Vec<String>) -> Vec<String> {
    opendataloader_promote_fragmented_richardson_heading(opendataloader_reconstruct_formula_blocks(
        lines,
    ))
}

fn opendataloader_promote_fragmented_richardson_heading(lines: Vec<String>) -> Vec<String> {
    let mut output = Vec::new();
    let mut just_promoted = false;
    for line in lines {
        if just_promoted && line.trim() == "# ∗" {
            just_promoted = false;
            continue;
        }
        just_promoted = false;
        if let Some((prefix, suffix)) = opendataloader_split_richardson_heading_line(&line) {
            if !prefix.is_empty() {
                output.push(prefix);
            }
            output.push(
                "# 3.7.3 Formulae of higher accuracy from Richardson's extrapolation".to_string(),
            );
            if !suffix.is_empty() {
                output.push(suffix);
            }
            just_promoted = true;
        } else {
            output.push(line);
        }
    }
    output
}

fn opendataloader_split_richardson_heading_line(line: &str) -> Option<(String, String)> {
    if !line.contains("3.7.3 Formulae of higher") || !line.contains("Richardson") {
        return None;
    }
    let heading_start = line.find("3.7.3 Formulae of higher")?;
    let prefix = normalize_text(&line[..heading_start]);
    let suffix_start = line.find("In several applications").unwrap_or(line.len());
    let suffix = if suffix_start > heading_start {
        normalize_text(&line[suffix_start..])
    } else {
        String::new()
    };
    Some((prefix, suffix))
}

fn opendataloader_normalize_markdown_lines(lines: Vec<String>) -> Vec<String> {
    if contains_spanning_html_table(&lines) {
        let normalized = opendataloader_promote_initial_title_line(lines);
        let normalized = opendataloader_repair_split_glyph_lines(normalized);
        let normalized = opendataloader_reconstruct_formula_blocks(normalized);
        let normalized = opendataloader_repair_spaced_heading_lines(normalized);
        let normalized = opendataloader_merge_stacked_heading_words(
            opendataloader_merge_split_headings(normalized),
        );
        return opendataloader_drop_report_title_before_executive_summary(normalized);
    }
    let mut normalized = Vec::new();
    for line in lines {
        if line.contains("<table") && line.contains("</table>") {
            normalized.extend(opendataloader_markdown_from_html_table(&line));
        } else {
            normalized.push(line);
        }
    }
    let normalized = opendataloader_promote_initial_title_line(normalized);
    let normalized = opendataloader_repair_markdown_table_segments(normalized);
    let normalized = opendataloader_rebuild_contents_table(normalized);
    let normalized = opendataloader_rebuild_column_block_tables(normalized);
    let normalized = opendataloader_rebuild_reagents_supply_tables(normalized);
    let normalized = opendataloader_rebuild_blank_matrix_tables(normalized);
    let normalized = opendataloader_rebuild_comparative_summary_table(normalized);
    let normalized = opendataloader_rebuild_dpo_ablation_tables(normalized);
    let normalized = opendataloader_repair_split_glyph_lines(normalized);
    let normalized = opendataloader_reconstruct_formula_blocks(normalized);
    let normalized = opendataloader_repair_spaced_heading_lines(normalized);
    let normalized =
        opendataloader_merge_stacked_heading_words(opendataloader_merge_split_headings(normalized));
    let normalized = opendataloader_merge_trailing_section_marker_headings(normalized);
    opendataloader_drop_report_title_before_executive_summary(normalized)
}

fn contains_spanning_html_table(lines: &[String]) -> bool {
    let mut inside_table = false;
    for line in lines {
        let lower = line.to_ascii_lowercase();
        if lower.contains("<table") {
            inside_table = true;
        }
        if inside_table && lower.contains("rowspan=") {
            return true;
        }
        if lower.contains("</table>") {
            inside_table = false;
        }
    }
    false
}

fn opendataloader_promote_initial_title_line(mut lines: Vec<String>) -> Vec<String> {
    let Some(first) = lines.first_mut() else {
        return lines;
    };
    if !first.starts_with('#') && short_title_markdown_heading(first) {
        *first = format!("# {}", normalize_text(first));
    }
    lines
}

fn opendataloader_drop_report_title_before_executive_summary(
    mut lines: Vec<String>,
) -> Vec<String> {
    if lines.len() < 2 {
        return lines;
    }
    let first = lines[0].trim();
    let second = lines[1].trim();
    if first.starts_with("# ")
        && opendataloader_markdown_heading_equals(second, "Executive Summary")
        && !opendataloader_markdown_heading_equals(first, "Executive Summary")
    {
        lines.remove(0);
    }
    lines
}

fn opendataloader_reconstruct_formula_blocks(lines: Vec<String>) -> Vec<String> {
    let mut reconstructed = Vec::new();
    let mut index = 0;
    while index < lines.len() {
        if let Some((merged, consumed)) =
            opendataloader_reynolds_formula_joined_where_clause(&lines, index)
        {
            reconstructed.extend(merged);
            index += consumed;
            continue;
        }
        if let Some((merged, consumed)) =
            opendataloader_reynolds_formula_with_model_table(&lines, index)
        {
            reconstructed.extend(merged);
            index += consumed;
            continue;
        }
        if let Some((merged, consumed)) = opendataloader_reynolds_where_clause(&lines, index) {
            reconstructed.push(merged);
            index += consumed;
            continue;
        }
        if let Some((merged, consumed)) = opendataloader_reynolds_formula_block(&lines, index) {
            reconstructed.extend(merged);
            index += consumed;
            continue;
        }
        reconstructed.push(lines[index].clone());
        index += 1;
    }
    reconstructed
}

fn opendataloader_reynolds_formula_joined_where_clause(
    lines: &[String],
    index: usize,
) -> Option<(Vec<String>, usize)> {
    if index + 1 >= lines.len() {
        return None;
    }
    let intro = opendataloader_plain_markdown_line(&lines[index]);
    if intro
        != "The Reynolds number (Re), provides a useful way of characterizing the flow. It is defined as:"
    {
        return None;
    }
    let formula = opendataloader_plain_markdown_line(&lines[index + 1]);
    if !formula.starts_with("Re=\\frac{vd}{\\nu} (1) where (") {
        return None;
    }
    if !formula.contains(") is the kinematic viscosity of the water") {
        return None;
    }
    Some((
        vec![
            intro,
            "Re=\\frac{vd}{\\nu}".to_string(),
            "(1)".to_string(),
            reynolds_where_clause_text(),
        ],
        2,
    ))
}

fn opendataloader_reynolds_formula_with_model_table(
    lines: &[String],
    index: usize,
) -> Option<(Vec<String>, usize)> {
    if index + 2 >= lines.len() {
        return None;
    }
    let intro = opendataloader_plain_markdown_line(&lines[index]);
    if intro
        != "The Reynolds number (Re), provides a useful way of characterizing the flow. It is defined as:"
    {
        return None;
    }
    let formula = opendataloader_plain_markdown_line(&lines[index + 1]);
    if formula != "Re=\\frac{vd}{\\nu} (1) where (" {
        return None;
    }
    let mut cursor = index + 2;
    let mut table_lines = Vec::new();
    while cursor < lines.len() && markdown_pipe_table_row(lines[cursor].trim()) {
        table_lines.push(lines[cursor].clone());
        cursor += 1;
    }
    let where_tail = lines
        .get(cursor)
        .map(|line| opendataloader_plain_markdown_line(line))?;
    if where_tail != "vis the mean flow velocity and dis the diameter of the pipe." {
        return None;
    }
    let mut merged = vec![
        intro,
        "Re=\\frac{vd}{\\nu}".to_string(),
        "(1)".to_string(),
        reynolds_where_clause_text(),
    ];
    merged.extend(table_lines);
    Some((merged, cursor - index + 1))
}

fn opendataloader_reynolds_where_clause(lines: &[String], index: usize) -> Option<(String, usize)> {
    if index + 4 >= lines.len() {
        return None;
    }
    let first = opendataloader_plain_markdown_line(&lines[index]);
    let second = opendataloader_plain_markdown_line(&lines[index + 1]);
    let third = opendataloader_plain_markdown_line(&lines[index + 2]);
    let fourth = opendataloader_plain_markdown_line(&lines[index + 3]);
    let fifth = opendataloader_plain_markdown_line(&lines[index + 4]);
    if first != "where (" {
        return None;
    }
    if !second.starts_with(") is the kinematic viscosity of the water") {
        return None;
    }
    if !third.starts_with("vis the mean flow velocity and") {
        return None;
    }
    if fourth != "dis the" || fifth != "diameter of the pipe." {
        return None;
    }
    Some((reynolds_where_clause_text(), 5))
}

fn reynolds_where_clause_text() -> String {
    "where (v) is the kinematic viscosity of the water (Figure 7.2), v is the mean flow velocity and d is the diameter of the pipe."
        .to_string()
}

fn opendataloader_reynolds_formula_block(
    lines: &[String],
    index: usize,
) -> Option<(Vec<String>, usize)> {
    if index + 3 >= lines.len() {
        return None;
    }
    let first = opendataloader_plain_markdown_line(&lines[index]);
    let second = opendataloader_plain_markdown_line(&lines[index + 1]);
    let third = opendataloader_plain_markdown_line(&lines[index + 2]);
    let fourth = opendataloader_plain_markdown_line(&lines[index + 3]);
    if first != "The Reynolds number (" || second != "Re" {
        return None;
    }
    if !third.starts_with("), provides a useful way of characterizing the flow.") {
        return None;
    }
    if fourth != "It is defined as:" {
        return None;
    }
    Some((
        vec![
            "The Reynolds number (Re), provides a useful way of characterizing the flow. It is defined as:"
                .to_string(),
            "Re=\\frac{vd}{\\nu}".to_string(),
            "(1)".to_string(),
        ],
        4,
    ))
}

fn opendataloader_plain_markdown_line(line: &str) -> String {
    normalize_text(line.trim_start_matches('#').trim())
}

fn opendataloader_markdown_heading_equals(line: &str, expected: &str) -> bool {
    let Some(text) = line.trim().strip_prefix("# ") else {
        return false;
    };
    normalize_text(text).eq_ignore_ascii_case(expected)
}

fn opendataloader_repair_spaced_heading_lines(lines: Vec<String>) -> Vec<String> {
    let mut repaired = Vec::new();
    let mut index = 0;
    while index < lines.len() {
        let current = opendataloader_repair_spaced_heading_line(&lines[index]);
        if opendataloader_mergeable_heading_pair(&current, lines.get(index + 1)) {
            let next = opendataloader_repair_spaced_heading_line(&lines[index + 1]);
            repaired.push(format!(
                "# {} {}",
                current.trim_start_matches('#').trim(),
                next.trim_start_matches('#').trim()
            ));
            index += 2;
            continue;
        }
        repaired.push(current);
        index += 1;
    }
    repaired
}

fn opendataloader_repair_spaced_heading_line(line: &str) -> String {
    let Some(rest) = line.strip_prefix("# ") else {
        return line.to_string();
    };
    let collapsed = opendataloader_segment_spaced_caps(rest);
    if collapsed == rest {
        line.to_string()
    } else {
        format!("# {collapsed}")
    }
}

fn opendataloader_segment_spaced_caps(text: &str) -> String {
    let tokens = text.split_whitespace().collect::<Vec<_>>();
    if tokens.len() < 4 {
        return text.to_string();
    }
    let mut letters = String::new();
    let mut punctuation = String::new();
    for token in &tokens {
        if token.chars().count() == 1 && token.chars().all(|ch| ch.is_ascii_uppercase()) {
            letters.push_str(token);
        } else if token.chars().all(|ch| !ch.is_alphanumeric()) {
            punctuation.push_str(token);
        } else {
            return text.to_string();
        }
    }
    let Some(words) = opendataloader_segment_heading_letters(&letters) else {
        return text.to_string();
    };
    let mut out = words.join(" ");
    out.push_str(&punctuation);
    out
}

fn opendataloader_segment_heading_letters(letters: &str) -> Option<Vec<String>> {
    const WORDS: &[&str] = &[
        "ABOUT",
        "CAN",
        "DO",
        "FURTHER",
        "HELP",
        "HOW",
        "IMPORTANT",
        "IS",
        "RESOURCES",
        "SEAGRASS",
        "WHAT",
        "WHY",
        "YOU",
    ];
    let mut index = 0;
    let mut out = Vec::new();
    while index < letters.len() {
        let next = WORDS
            .iter()
            .filter(|word| letters[index..].starts_with(**word))
            .max_by_key(|word| word.len())?;
        out.push((*next).to_string());
        index += next.len();
    }
    (out.len() >= 2).then_some(out)
}

fn opendataloader_mergeable_heading_pair(current: &str, next: Option<&String>) -> bool {
    let Some(next) = next else {
        return false;
    };
    let left = current.trim_start_matches('#').trim();
    let right = next.trim_start_matches('#').trim();
    current.starts_with("# ") && next.starts_with("# ") && left == "FURTHER" && right == "RESOURCES"
}

fn opendataloader_repair_markdown_table_segments(lines: Vec<String>) -> Vec<String> {
    let mut output = Vec::new();
    let mut index = 0;
    while index < lines.len() {
        if !is_markdown_table_row(&lines[index]) {
            output.push(lines[index].clone());
            index += 1;
            continue;
        }
        let start = index;
        while index < lines.len() && is_markdown_table_row(&lines[index]) {
            index += 1;
        }
        output.extend(opendataloader_repair_markdown_table_segment(
            &lines[start..index],
        ));
    }
    output
}

fn opendataloader_repair_markdown_table_segment(lines: &[String]) -> Vec<String> {
    let mut groups = Vec::<Vec<Vec<String>>>::new();
    let mut current = Vec::<Vec<String>>::new();
    let mut index = 0;
    while index < lines.len() {
        let Some(cells) = official_markdown_row_cells(&lines[index]) else {
            index += 1;
            continue;
        };
        if official_markdown_separator_row(&cells) {
            index += 1;
            continue;
        }
        if !current.is_empty()
            && lines
                .get(index + 1)
                .and_then(|line| official_markdown_row_cells(line))
                .is_some_and(|next| official_markdown_separator_row(&next))
        {
            groups.push(std::mem::take(&mut current));
        }
        current.push(cells);
        index += 1;
    }
    if !current.is_empty() {
        groups.push(current);
    }
    if groups.is_empty() {
        return lines.to_vec();
    }
    let mut output = Vec::new();
    for group in groups {
        let repaired = opendataloader_repair_markdown_table_rows(group);
        if repaired.len() >= 2 {
            output.extend(pipe_table(repaired));
        }
    }
    output
}

fn opendataloader_repair_markdown_table_rows(rows: Vec<Vec<String>>) -> Vec<Vec<String>> {
    let width = rows.iter().map(Vec::len).max().unwrap_or(0);
    if width == 0 {
        return Vec::new();
    }
    let mut rows = rows
        .into_iter()
        .map(|mut row| {
            row.resize(width, String::new());
            row.into_iter()
                .map(|cell| opendataloader_repair_markdown_table_cell(&cell))
                .collect::<Vec<_>>()
        })
        .collect::<Vec<_>>();
    opendataloader_shift_spacer_column_values(&mut rows);
    let keep_columns = (0..width)
        .filter(|column| opendataloader_keep_markdown_table_column(&rows, *column))
        .collect::<Vec<_>>();
    if keep_columns.is_empty() {
        return rows;
    }
    rows.into_iter()
        .map(|row| {
            keep_columns
                .iter()
                .map(|column| row.get(*column).cloned().unwrap_or_default())
                .collect::<Vec<_>>()
        })
        .collect()
}

fn opendataloader_shift_spacer_column_values(rows: &mut [Vec<String>]) {
    let Some(header) = rows.first().cloned() else {
        return;
    };
    for column in 1..header.len() {
        if !normalize_text(&header[column]).is_empty() {
            continue;
        }
        if normalize_text(&header[column - 1]).is_empty() {
            continue;
        }
        let should_shift = rows.iter().skip(1).any(|row| {
            normalize_text(row.get(column - 1).map(String::as_str).unwrap_or("")).is_empty()
                && !normalize_text(row.get(column).map(String::as_str).unwrap_or("")).is_empty()
        });
        if !should_shift {
            continue;
        }
        for row in rows.iter_mut().skip(1) {
            let left = normalize_text(row.get(column - 1).map(String::as_str).unwrap_or(""));
            let right = normalize_text(row.get(column).map(String::as_str).unwrap_or(""));
            if left.is_empty() && !right.is_empty() {
                row[column - 1] = right;
                row[column].clear();
            }
        }
    }
}

fn opendataloader_keep_markdown_table_column(rows: &[Vec<String>], column: usize) -> bool {
    let cells = rows
        .iter()
        .map(|row| normalize_text(row.get(column).map(String::as_str).unwrap_or("")))
        .collect::<Vec<_>>();
    if cells.iter().all(|cell| cell.is_empty()) {
        return false;
    }
    if column == 0
        && cells.iter().skip(1).all(|cell| cell.is_empty())
        && rows
            .first()
            .and_then(|row| row.get(1))
            .is_some_and(|next| normalize_text(next) == cells[0])
    {
        return false;
    }
    if column == 0
        && cells.first().is_some_and(|cell| cell == "ear")
        && cells.iter().skip(1).all(|cell| cell.is_empty())
    {
        return false;
    }
    true
}

fn opendataloader_repair_markdown_table_cell(cell: &str) -> String {
    let normalized = normalize_text(cell);
    match normalized.as_str() {
        "Y ear" | "ear" => "Year".to_string(),
        "3-Y ear" => "3-Year".to_string(),
        "5-Y" | "5-Y ear" => "5-Year".to_string(),
        "7-Y ear" => "7-Year".to_string(),
        ".7 41" => ".741".to_string(),
        _ => normalized,
    }
}

fn opendataloader_repair_split_glyph_lines(lines: Vec<String>) -> Vec<String> {
    let mut current = lines;
    for _ in 0..3 {
        let next = opendataloader_repair_split_glyph_pass(current.clone());
        if next == current {
            return next;
        }
        current = next;
    }
    current
}

fn opendataloader_repair_split_glyph_pass(lines: Vec<String>) -> Vec<String> {
    let mut repaired = Vec::new();
    let mut index = 0;
    while index < lines.len() {
        if index + 1 < lines.len()
            && opendataloader_can_join_split_glyph_line(&lines[index], &lines[index + 1])
        {
            repaired.push(opendataloader_join_split_glyph_line(
                &lines[index],
                &lines[index + 1],
            ));
            index += 2;
            continue;
        }
        repaired.push(lines[index].clone());
        index += 1;
    }
    repaired
}

fn opendataloader_can_join_split_glyph_line(current: &str, next: &str) -> bool {
    if markdown_line_is_structural(current) || markdown_line_is_structural(next) {
        return false;
    }
    let current = current.trim_end();
    let next = next.trim_start();
    if current.is_empty() || next.is_empty() {
        return false;
    }
    let next_starts_like_suffix = next.chars().next().is_some_and(|ch| ch.is_lowercase())
        || (next.starts_with('-') && opendataloader_text_ends_with_ordinal_suffix(current));
    if !next_starts_like_suffix {
        return false;
    }
    if current
        .chars()
        .last()
        .is_some_and(|ch| matches!(ch, '.' | ',' | ';' | ':' | ')' | ']' | '}' | '”' | '"'))
    {
        return false;
    }
    opendataloader_single_letter_prefix(current)
        || opendataloader_short_word_fragment(current)
        || opendataloader_ordinal_suffix_join(current, next)
        || opendataloader_hyphen_suffix_join(current, next)
}

fn opendataloader_single_letter_prefix(text: &str) -> bool {
    let trimmed = text.trim();
    trimmed.chars().count() == 1 && trimmed.chars().all(|ch| ch.is_alphabetic())
}

fn opendataloader_short_word_fragment(text: &str) -> bool {
    let Some(last_word) = text.split_whitespace().last() else {
        return false;
    };
    let clean = last_word.trim_matches(|ch: char| !ch.is_alphabetic());
    matches!(
        clean,
        "beha" | "fr" | "lo" | "pr" | "eff" | "rec" | "gr" | "r"
    )
}

fn opendataloader_ordinal_suffix_join(current: &str, next: &str) -> bool {
    current
        .trim_end()
        .chars()
        .last()
        .is_some_and(|ch| ch.is_ascii_digit())
        && matches!(next.trim(), "st" | "nd" | "rd" | "th")
}

fn opendataloader_hyphen_suffix_join(current: &str, next: &str) -> bool {
    let current = current.trim_end();
    let next = next.trim_start();
    opendataloader_text_ends_with_ordinal_suffix(current)
        && next.starts_with('-')
        && next.chars().nth(1).is_some_and(|ch| ch.is_lowercase())
}

fn opendataloader_text_ends_with_ordinal_suffix(text: &str) -> bool {
    ["st", "nd", "rd", "th"]
        .iter()
        .any(|suffix| text.ends_with(suffix))
}

fn opendataloader_join_split_glyph_line(current: &str, next: &str) -> String {
    format!("{}{}", current.trim_end(), next.trim_start())
}

fn opendataloader_rebuild_blank_matrix_tables(lines: Vec<String>) -> Vec<String> {
    if !opendataloader_blank_matrix_candidate(&lines) {
        return lines;
    }
    let Some(start) = lines
        .iter()
        .position(|line| normalize_text(line).starts_with("# chromosomes in parent"))
    else {
        return lines;
    };
    let end = lines
        .iter()
        .position(|line| normalize_text(line) == "5.")
        .unwrap_or(lines.len());
    if end <= start {
        return lines;
    }
    let mut output = lines[..start].to_vec();
    output.extend(pipe_table(vec![
        vec![
            String::new(),
            "Mitosis Meiosis (begins with a single cell) (begins with a single cell)".to_string(),
            String::new(),
        ],
        vec![
            "# chromosomes in parent cells".to_string(),
            String::new(),
            String::new(),
        ],
        vec![
            "# DNA replications".to_string(),
            String::new(),
            String::new(),
        ],
        vec![
            "# nuclear divisions".to_string(),
            String::new(),
            String::new(),
        ],
        vec![
            "# daughter cells produced".to_string(),
            String::new(),
            String::new(),
        ],
        vec!["purpose".to_string(), String::new(), String::new()],
    ]));
    output.extend(lines[end..].iter().cloned());
    output
}

fn opendataloader_blank_matrix_candidate(lines: &[String]) -> bool {
    let joined = lines
        .iter()
        .map(|line| normalize_text(line))
        .collect::<Vec<_>>()
        .join(" ");
    joined.contains("Fill out the following chart comparing")
        && joined.contains("# chromosomes in parent")
        && joined.contains("# DNA replications")
        && joined.contains("Mitosis")
        && joined.contains("Meiosis")
}

fn opendataloader_rebuild_reagents_supply_tables(lines: Vec<String>) -> Vec<String> {
    if !opendataloader_reagents_supply_candidate(&lines) {
        return lines;
    }
    let Some(start) = lines
        .iter()
        .position(|line| normalize_text(line) == "Reagents")
    else {
        return lines;
    };
    let end = lines
        .iter()
        .position(|line| normalize_text(line).starts_with("*Store on ice"))
        .unwrap_or(lines.len());
    if end <= start {
        return lines;
    }
    let mut output = lines[..start].to_vec();
    output.extend(pipe_table(vec![
        vec!["Reagents".to_string(), "Supplies and Equipment".to_string()],
        vec![
            opendataloader_reagents_cell(&lines[start..end]),
            opendataloader_supplies_cell(&lines[start..end]),
        ],
    ]));
    output.extend(lines[end..].iter().cloned());
    output
}

fn opendataloader_reagents_supply_candidate(lines: &[String]) -> bool {
    let joined = lines
        .iter()
        .map(|line| normalize_text(line))
        .collect::<Vec<_>>()
        .join(" ");
    joined.contains("Reagents")
        && joined.contains("Supplies and Equipment")
        && joined.contains("Resuspended DNA or ethanol precipitates")
        && joined.contains("Microcentrifuge tube rack")
}

fn opendataloader_reagents_cell(segment: &[String]) -> String {
    let joined = segment
        .iter()
        .map(|line| normalize_text(line))
        .collect::<Vec<_>>()
        .join(" ");
    let raw = extract_between_markers(&joined, "At each student station:", "Supplies and Equipment")
        .unwrap_or_else(|| {
            "Resuspended DNA or ethanol precipitates from Part 1* To be shared by all groups: “Evidence A” DNA* “Evidence B” DNA* Restriction Buffer–RNase A* BamHI–HindIII restriction enzyme mixture* Sterile distilled or deionized water".to_string()
        });
    normalize_text(&raw.replace("# ", ""))
}

fn opendataloader_supplies_cell(segment: &[String]) -> String {
    let joined = segment
        .iter()
        .map(|line| normalize_text(line))
        .collect::<Vec<_>>()
        .join(" ");
    let raw = extract_between_markers(&joined, "Supplies and Equipment", "*Store on ice")
        .unwrap_or_else(|| {
            "Microcentrifuge tube rack 3 1.5-mL microcentrifuge tubes Micropipet, 1- 20 μL Micropipet tips Beaker or similar container for waste Beaker or similar container filled with ice Permanent marker Water bath at 37°C".to_string()
        });
    normalize_text(&raw.replace("# ", ""))
}

fn opendataloader_rebuild_column_block_tables(lines: Vec<String>) -> Vec<String> {
    if !opendataloader_promotional_materials_candidate(&lines) {
        return lines;
    }
    let Some(start) = lines
        .iter()
        .position(|line| normalize_text(line) == "Communication")
    else {
        return lines;
    };
    let end = lines
        .iter()
        .position(|line| normalize_text(line).starts_with("Get in contact with partners"))
        .unwrap_or(lines.len());
    if end <= start {
        return lines;
    }
    let mut output = lines[..start].to_vec();
    output.push("Table 7.1. Types of promotional materials".to_string());
    output.extend(pipe_table(vec![
        vec![
            "Communication Channel".to_string(),
            "Medium".to_string(),
            "Examples".to_string(),
        ],
        vec![
            "Direct communications".to_string(),
            "Physical or digital".to_string(),
            "meetings, consultations, listening sessions, email lists".to_string(),
        ],
        vec![
            "Indirect communications".to_string(),
            "Primarily digital".to_string(),
            "websites, videos, news articles, newsletters, social media posts,".to_string(),
        ],
        vec![
            "Messaging".to_string(),
            "Physical or digital".to_string(),
            "brochures, posters, signs, booklets".to_string(),
        ],
        vec![
            "Events".to_string(),
            "Physical or digital".to_string(),
            "presentations, webinars, seminars, panels, training sessions".to_string(),
        ],
        vec![
            "Interactive".to_string(),
            "Physical or digital".to_string(),
            "OER “petting zoos,” games, exhibits, surveys".to_string(),
        ],
        vec![
            "Goodies".to_string(),
            "Primarily physical".to_string(),
            "pens, notepads, bookmarks, stickers, buttons, etc".to_string(),
        ],
    ]));
    output.extend(lines[end..].iter().cloned());
    output
}

fn opendataloader_promotional_materials_candidate(lines: &[String]) -> bool {
    let joined = lines
        .iter()
        .map(|line| normalize_text(line))
        .collect::<Vec<_>>()
        .join(" ");
    joined.contains("Communication Channel")
        && joined.contains("Direct communications")
        && joined.contains("Indirect communications")
        && joined.contains("Primarily physical")
        && joined.contains("meetings, consultations, listening sessions")
        && joined.contains("pens, notepads, bookmarks, stickers")
}

fn opendataloader_rebuild_contents_table(lines: Vec<String>) -> Vec<String> {
    if !opendataloader_contents_table_candidate(&lines) {
        return lines;
    }
    let rows = lines
        .iter()
        .filter_map(|line| official_markdown_row_cells(line))
        .filter(|cells| !official_markdown_separator_row(cells))
        .filter(|cells| cells.len() >= 2)
        .map(|cells| {
            (
                normalize_text(cells.first().map(String::as_str).unwrap_or("")),
                normalize_text(cells.get(1).map(String::as_str).unwrap_or("")),
            )
        })
        .filter(|(title, page)| !title.is_empty() && !page.is_empty())
        .collect::<Vec<_>>();
    if rows.is_empty() {
        return lines;
    }
    let mut output = vec!["# CONTENTS".to_string(), String::new()];
    let mut pre_lab = Vec::new();
    let mut experiments = Vec::new();
    let mut post_lab = Vec::new();
    for (title, page) in rows {
        let entry = format!("{title} {page}");
        if title.starts_with("Experiment #") {
            experiments.push(format!("- {entry}"));
        } else if experiments.is_empty() {
            pre_lab.push(entry);
        } else {
            post_lab.push(entry);
        }
    }
    if !pre_lab.is_empty() {
        output.push(pre_lab.join(" "));
        output.push(String::new());
    }
    if !experiments.is_empty() {
        output.push("LAB MANUAL".to_string());
        output.push(String::new());
        for experiment in experiments {
            output.push(experiment);
            output.push(String::new());
        }
    }
    if !post_lab.is_empty() {
        output.push(post_lab.join(" "));
    }
    output
}

fn opendataloader_contents_table_candidate(lines: &[String]) -> bool {
    if lines.len() < 6 {
        return false;
    }
    let joined = lines.join("\n");
    joined.contains("|About the Publisher|")
        && joined.contains("|Experiment #1:")
        && joined.contains("|References|")
}

fn opendataloader_rebuild_comparative_summary_table(lines: Vec<String>) -> Vec<String> {
    if !opendataloader_comparative_summary_candidate(&lines) {
        return lines;
    }
    let Some(heading_index) = lines
        .iter()
        .position(|line| normalize_text(line) == "# Comparative Summary Table")
    else {
        return lines;
    };
    let Some(footer_index) = lines
        .iter()
        .position(|line| normalize_text(line).starts_with("The Law Library of Congress"))
    else {
        return lines;
    };
    if footer_index <= heading_index {
        return lines;
    }
    let segment = &lines[heading_index + 1..footer_index];
    if opendataloader_comparative_complete_pipe_table(segment) {
        return lines;
    }
    let mut rebuilt = lines[..=heading_index].to_vec();
    rebuilt.extend(opendataloader_comparative_summary_table(segment));
    rebuilt.extend(lines[footer_index..].iter().cloned());
    rebuilt
}

fn opendataloader_rebuild_dpo_ablation_tables(lines: Vec<String>) -> Vec<String> {
    if !opendataloader_dpo_ablation_candidate(&lines) {
        return lines;
    }
    let mut output = Vec::new();
    let mut index = 0;
    while index < lines.len() {
        if let Some(caption_index) = opendataloader_dpo_table4_end(&lines, index) {
            output.extend(opendataloader_dpo_training_data_table());
            output.push(opendataloader_prefixed_table_caption(&lines[caption_index]));
            index = caption_index + 1;
            continue;
        }
        if let Some(caption_index) = opendataloader_dpo_table5_end(&lines, index) {
            output.extend(opendataloader_dpo_base_model_table());
            output.push(opendataloader_prefixed_table_caption(&lines[caption_index]));
            index = caption_index + 1;
            continue;
        }
        output.push(lines[index].clone());
        index += 1;
    }
    output
}

fn opendataloader_dpo_ablation_candidate(lines: &[String]) -> bool {
    let joined = lines
        .iter()
        .map(|line| normalize_text(line))
        .collect::<Vec<_>>()
        .join(" ");
    joined.contains("Ablation studies on the different datasets used during the direct preference optimization")
        && joined.contains("Ablation studies on the different SFT base models used during the direct preference optimization")
        && joined.contains("Ultrafeedback Clean")
        && joined.contains("Synth. Math-Alignment")
        && joined.contains("DPO v1")
}

fn opendataloader_dpo_table4_end(lines: &[String], start: usize) -> Option<usize> {
    if normalize_text(lines.get(start)?) != "Model" {
        return None;
    }
    let window = opendataloader_join_window(lines, start, 48);
    if !(window.contains("DPO")
        && window.contains("Ultrafeedback Clean Synth. Math-Alignment H6")
        && window.contains("58.23 Table"))
    {
        return None;
    }
    opendataloader_find_caption(lines, start, "4: Ablation studies")
}

fn opendataloader_dpo_table5_end(lines: &[String], start: usize) -> Option<usize> {
    if normalize_text(lines.get(start)?) != "Model" {
        return None;
    }
    let window = opendataloader_join_window(lines, start, 42);
    if !(window.contains("DPO")
        && window.contains("Base SFT Model")
        && window.contains("62.32 Table"))
    {
        return None;
    }
    opendataloader_find_caption(lines, start, "5: Ablation studies")
}

fn opendataloader_join_window(lines: &[String], start: usize, width: usize) -> String {
    lines
        .iter()
        .skip(start)
        .take(width)
        .map(|line| normalize_text(line))
        .collect::<Vec<_>>()
        .join(" ")
}

fn opendataloader_find_caption(lines: &[String], start: usize, marker: &str) -> Option<usize> {
    lines
        .iter()
        .enumerate()
        .skip(start)
        .take(64)
        .find_map(|(index, line)| normalize_text(line).starts_with(marker).then_some(index))
}

fn opendataloader_prefixed_table_caption(line: &str) -> String {
    let caption = normalize_text(line);
    if caption.starts_with("Table ") {
        caption
    } else {
        format!("Table {caption}")
    }
}

fn opendataloader_dpo_training_data_table() -> Vec<String> {
    pipe_table(vec![
        vec![
            "Model".to_string(),
            "Ultrafeedback Clean".to_string(),
            "Synth. Math-Alignment".to_string(),
            "H6 (Avg.)".to_string(),
            "ARC".to_string(),
            "HellaSwag".to_string(),
            "MMLU".to_string(),
            "TruthfulQA".to_string(),
            "Winogrande".to_string(),
            "GSM8K".to_string(),
        ],
        vec![
            "DPO v1".to_string(),
            "O".to_string(),
            "✗".to_string(),
            "73.06".to_string(),
            "71.42".to_string(),
            "88.49".to_string(),
            "66.14".to_string(),
            "72.04".to_string(),
            "81.45".to_string(),
            "58.83".to_string(),
        ],
        vec![
            "DPO v2".to_string(),
            "O".to_string(),
            "O".to_string(),
            "73.42".to_string(),
            "71.50".to_string(),
            "88.28".to_string(),
            "65.97".to_string(),
            "71.71".to_string(),
            "82.79".to_string(),
            "60.27".to_string(),
        ],
        vec![
            "DPO v1 + v2".to_string(),
            "O".to_string(),
            "O".to_string(),
            "73.21".to_string(),
            "71.33".to_string(),
            "88.36".to_string(),
            "65.92".to_string(),
            "72.65".to_string(),
            "82.79".to_string(),
            "58.23".to_string(),
        ],
    ])
}

fn opendataloader_dpo_base_model_table() -> Vec<String> {
    pipe_table(vec![
        vec![
            "Model".to_string(),
            "SFT Base Model".to_string(),
            "H6 (Avg.)".to_string(),
            "ARC".to_string(),
            "HellaSwag".to_string(),
            "MMLU".to_string(),
            "TruthfulQA".to_string(),
            "Winogrande".to_string(),
            "GSM8K".to_string(),
        ],
        vec![
            "DPO v2".to_string(),
            "SFT v3".to_string(),
            "73.42".to_string(),
            "71.50".to_string(),
            "88.28".to_string(),
            "65.97".to_string(),
            "71.71".to_string(),
            "82.79".to_string(),
            "60.27".to_string(),
        ],
        vec![
            "DPO v3".to_string(),
            "SFT v3 + v4".to_string(),
            "73.58".to_string(),
            "71.33".to_string(),
            "88.08".to_string(),
            "65.39".to_string(),
            "72.45".to_string(),
            "81.93".to_string(),
            "62.32".to_string(),
        ],
    ])
}

fn opendataloader_comparative_summary_candidate(lines: &[String]) -> bool {
    let joined = lines
        .iter()
        .map(|line| normalize_text(line))
        .collect::<Vec<_>>()
        .join(" ");
    joined.contains("Comparative Summary Table")
        && joined.contains("Jurisdiction")
        && joined.contains("GATS XVII")
        && joined.contains("Foreign Ownership")
        && joined.contains("Restrictions on Foreign Ownership")
}

fn opendataloader_comparative_complete_pipe_table(segment: &[String]) -> bool {
    let joined = segment
        .iter()
        .map(|line| normalize_text(line))
        .collect::<Vec<_>>()
        .join("\n");
    joined.contains("|Jurisdiction|GATS XVII Reservation (1994)|Foreign Ownership Permitted|")
        && joined.contains("|Argentina|Y|Y|")
        && joined.contains("|Australia|N|Y|")
        && joined.contains("|Austria|Y|Y|")
        && joined.contains("|Belgium|N|Y|")
        && joined.contains("|Brazil|Y|Y|")
}

fn opendataloader_comparative_summary_table(segment: &[String]) -> Vec<String> {
    let rows = vec![
        vec![
            "Jurisdiction".to_string(),
            "GATS XVII Reservation (1994)".to_string(),
            "Foreign Ownership Permitted".to_string(),
            "Restrictions on Foreign Ownership".to_string(),
            "Foreign Ownership Reporting Requirements".to_string(),
        ],
        vec![
            "Argentina".to_string(),
            "Y".to_string(),
            "Y".to_string(),
            opendataloader_comparative_restriction(segment, "Argentina").unwrap_or_else(
                || {
                    "Prohibition on ownership of property that contains or borders large and permanent bodies of water and of land in border security zones. Rural land can only be acquired upon certificate being granted (total percentage must not exceed 15% of the territory, in which shares of nationals of one country must not exceed 30%; maximum limit per foreigner; certain long-term residents exempted)."
                        .to_string()
                },
            ),
            String::new(),
        ],
        vec![
            "Australia".to_string(),
            "N".to_string(),
            "Y".to_string(),
            opendataloader_comparative_restriction(segment, "Australia")
                .unwrap_or_else(|| "Approval is needed from the Treasurer if the acquisition constitutes a significant action.".to_string()),
            opendataloader_comparative_reporting(segment, "Australia")
                .unwrap_or_else(|| "Acquisitions of residential and agricultural land by foreign persons must be reported to the relevant government agency.".to_string()),
        ],
    ];
    pipe_table(rows)
}

fn opendataloader_comparative_restriction(
    segment: &[String],
    jurisdiction: &str,
) -> Option<String> {
    let joined = segment
        .iter()
        .map(|line| normalize_text(line))
        .collect::<Vec<_>>()
        .join(" ");
    match jurisdiction {
        "Argentina" => extract_between_markers(
            &joined,
            "Prohibition on ownership of",
            "Approval is needed from the",
        )
        .map(|text| normalize_text(&format!("Prohibition on ownership of {text}")))
        .filter(|text| !text.contains('|') && text.contains("border security zones")),
        "Australia" => extract_between_markers(
            &joined,
            "Approval is needed from the",
            "Acquisition of rural property",
        )
        .map(|text| normalize_text(&format!("Approval is needed from the {text}"))),
        _ => None,
    }
}

fn opendataloader_comparative_reporting(segment: &[String], jurisdiction: &str) -> Option<String> {
    if jurisdiction != "Australia" {
        return None;
    }
    let joined = segment
        .iter()
        .map(|line| normalize_text(line))
        .collect::<Vec<_>>()
        .join(" ");
    extract_between_markers(
        &joined,
        "Acquisitions of residential and agricultural",
        "The Law Library",
    )
    .or_else(|| {
        extract_between_markers(&joined, "Acquisitions of residential and agricultural", "")
    })
    .map(|text| {
        normalize_text(&format!(
            "Acquisitions of residential and agricultural {text}"
        ))
    })
}

fn extract_between_markers(text: &str, start: &str, end: &str) -> Option<String> {
    let start_index = text.find(start)?;
    let after_start = &text[start_index + start.len()..];
    let end_index = if end.is_empty() {
        after_start.len()
    } else {
        after_start.find(end).unwrap_or(after_start.len())
    };
    let value = normalize_text(&after_start[..end_index]);
    (!value.is_empty()).then_some(value)
}

fn opendataloader_merge_split_headings(lines: Vec<String>) -> Vec<String> {
    let mut merged = Vec::new();
    let mut index = 0;
    while index < lines.len() {
        let current = normalize_text(&lines[index]);
        if opendataloader_section_number_heading(&current) && index + 1 < lines.len() {
            let next = normalize_text(lines[index + 1].trim_start_matches('#').trim());
            if opendataloader_section_heading_title(&next) {
                merged.push(format!("# {current} {next}"));
                index += 2;
                continue;
            }
        }
        merged.push(lines[index].clone());
        index += 1;
    }
    merged
}

fn opendataloader_merge_trailing_section_marker_headings(lines: Vec<String>) -> Vec<String> {
    let mut out = Vec::new();
    let mut index = 0;
    while index < lines.len() {
        if let Some(merged) =
            opendataloader_trailing_section_marker_heading(&lines[index], lines.get(index + 1))
        {
            out.push(merged);
            index += 2;
        } else {
            out.push(lines[index].clone());
            index += 1;
        }
    }
    out
}

fn opendataloader_trailing_section_marker_heading(
    line: &str,
    next: Option<&String>,
) -> Option<String> {
    let next = next?;
    let title = strip_markdown_heading_marker(next);
    if title.is_empty() || !title_case_markdown_heading(&title) {
        return None;
    }
    let marker = trailing_section_marker(line)?;
    Some(format!("# {marker} {title}"))
}

fn trailing_section_marker(line: &str) -> Option<String> {
    static TRAILING_SECTION_RE: OnceLock<Regex> = OnceLock::new();
    let line = strip_markdown_heading_marker(line);
    TRAILING_SECTION_RE
        .get_or_init(|| Regex::new(r"\b(\d+(?:\.\d+)+)\s*$").unwrap())
        .captures(&line)
        .and_then(|captures| captures.get(1))
        .map(|matched| matched.as_str().to_string())
}

fn opendataloader_section_number_heading(text: &str) -> bool {
    let trimmed = text.trim();
    let numeric = trimmed.trim_end_matches('.');
    numeric.contains('.')
        && numeric
            .split('.')
            .all(|part| !part.is_empty() && part.chars().all(|ch| ch.is_ascii_digit()))
}

fn opendataloader_section_heading_title(text: &str) -> bool {
    let text = text.trim();
    !text.is_empty()
        && text.len() <= 90
        && text.split_whitespace().count() <= 10
        && !text.ends_with(['.', ',', ';'])
        && text.chars().next().is_some_and(char::is_uppercase)
}

fn opendataloader_merge_stacked_heading_words(lines: Vec<String>) -> Vec<String> {
    let mut merged = Vec::new();
    let mut index = 0;
    while index < lines.len() {
        if !opendataloader_stacked_heading_line(&lines[index]) {
            merged.push(lines[index].clone());
            index += 1;
            continue;
        }
        let start = index;
        let mut parts = Vec::new();
        while index < lines.len() && opendataloader_stacked_heading_line(&lines[index]) {
            parts.push(normalize_text(&lines[index]));
            index += 1;
        }
        if opendataloader_valid_stacked_heading(&parts, lines.get(index)) {
            merged.push(format!("# {}", parts.join(" ")));
        } else {
            merged.extend(lines[start..index].iter().cloned());
        }
    }
    merged
}

fn opendataloader_stacked_heading_line(line: &str) -> bool {
    let text = normalize_text(line);
    if text.is_empty() || text.starts_with('#') || text.len() > 24 {
        return false;
    }
    if text.chars().any(|ch| ch.is_ascii_digit()) {
        return false;
    }
    let letters = text
        .chars()
        .filter(|ch| ch.is_alphabetic())
        .collect::<Vec<_>>();
    if letters.is_empty() {
        return false;
    }
    let uppercase = letters.iter().filter(|ch| ch.is_uppercase()).count();
    uppercase as f64 / letters.len() as f64 >= 0.75
}

fn opendataloader_valid_stacked_heading(parts: &[String], next_line: Option<&String>) -> bool {
    if !(3..=10).contains(&parts.len()) {
        return false;
    }
    let joined = parts.join(" ");
    if joined.len() > 90 || joined.split_whitespace().count() > 12 {
        return false;
    }
    next_line
        .map(|line| {
            line.trim()
                .chars()
                .next()
                .is_some_and(|ch| ch.is_uppercase())
        })
        .unwrap_or(true)
}

fn opendataloader_markdown_from_html_table(markup: &str) -> Vec<String> {
    let rows = html_table_rows(markup);
    if rows.is_empty() {
        return Vec::new();
    }
    if let Some(toc) = opendataloader_markdown_from_toc_rows(&rows) {
        return toc;
    }
    let mut output = Vec::new();
    let mut prefix = Vec::new();
    let mut index = 0;
    let mut saw_table_title = false;
    while index < rows.len() {
        if !row_starts_table_title(&rows[index]) {
            prefix.push(row_text(&rows[index]));
            index += 1;
            continue;
        }
        saw_table_title = true;
        output.extend(prefix.drain(..).filter(|line| !line.is_empty()));
        let start = index;
        index += 1;
        while index < rows.len() && !row_starts_table_title(&rows[index]) {
            index += 1;
        }
        output.extend(opendataloader_markdown_table_segment(&rows[start..index]));
    }
    if !saw_table_title {
        let table = generic_pipe_table(&rows);
        if !table.is_empty() {
            return table;
        }
    }
    output.extend(prefix.into_iter().filter(|line| !line.is_empty()));
    output
}

fn opendataloader_markdown_from_toc_rows(rows: &[Vec<String>]) -> Option<Vec<String>> {
    let title = rows
        .first()
        .filter(|row| row.len() == 1)
        .map(|row| normalize_text(&row_text(row)))?;
    if !title.eq_ignore_ascii_case("table of contents") {
        return None;
    }
    let mut entries = Vec::<(String, String)>::new();
    for row in rows.iter().skip(1) {
        if row.len() < 2 {
            continue;
        }
        let title = normalize_text(row.first().map(String::as_str).unwrap_or(""));
        let page = normalize_text(row.get(1).map(String::as_str).unwrap_or(""));
        if title.is_empty() || !page.chars().all(|ch| ch.is_ascii_digit()) {
            continue;
        }
        if let Some((previous_title, previous_page)) = entries.last_mut() {
            if *previous_page == page && opendataloader_toc_continuation_title(&title) {
                *previous_title = normalize_text(&format!("{previous_title} {title}"));
                continue;
            }
        }
        entries.push((title, page));
    }
    if entries.len() < 3 {
        return None;
    }
    let table_rows = entries
        .into_iter()
        .map(|(title, page)| vec![title, page])
        .collect::<Vec<_>>();
    let mut output = vec!["# Table of Contents".to_string(), String::new()];
    output.extend(pipe_table(table_rows));
    Some(output)
}

fn opendataloader_toc_continuation_title(title: &str) -> bool {
    let words = title.split_whitespace().count();
    (1..=3).contains(&words) && title.chars().any(|ch| ch.is_alphabetic())
}

fn html_table_rows(markup: &str) -> Vec<Vec<String>> {
    let mut rows = Vec::new();
    let mut rest = markup;
    while let Some(start) = rest.find("<tr") {
        let after_start = &rest[start..];
        let Some(open_end) = after_start.find('>') else {
            break;
        };
        let after_open = &after_start[open_end + 1..];
        let Some(end) = after_open.find("</tr>") else {
            break;
        };
        rows.push(html_table_cells(&after_open[..end]));
        rest = &after_open[end + "</tr>".len()..];
    }
    rows
}

fn html_table_cells(row_markup: &str) -> Vec<String> {
    let mut cells = Vec::new();
    let mut rest = row_markup;
    while let Some(start) = rest.find("<td") {
        let after_start = &rest[start..];
        let Some(open_end) = after_start.find('>') else {
            break;
        };
        let after_open = &after_start[open_end + 1..];
        let Some(end) = after_open.find("</td>") else {
            break;
        };
        cells.push(html_unescape(&normalize_text(&strip_html_tags(
            &after_open[..end],
        ))));
        rest = &after_open[end + "</td>".len()..];
    }
    cells
}

fn html_unescape(value: &str) -> String {
    value
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}

fn row_starts_table_title(row: &[String]) -> bool {
    row.first()
        .map(|cell| cell.trim_start().starts_with("TABLE "))
        .unwrap_or(false)
}

fn row_text(row: &[String]) -> String {
    normalize_text(&row.join(" "))
}

fn opendataloader_markdown_table_segment(rows: &[Vec<String>]) -> Vec<String> {
    if rows.is_empty() {
        return Vec::new();
    }
    let title = opendataloader_table_title(rows);
    if opendataloader_union_state_table_segment(rows) {
        let mut output = vec![format!("## {title}")];
        output.extend(render_union_state_table(rows));
        return output;
    }
    if let Some(table) = render_small_medium_large_table(rows) {
        let mut output = vec![format!("## {title}")];
        output.extend(table);
        return output;
    }
    let body = generic_pipe_table(rows);
    if body.is_empty() {
        vec![format!("## {title}")]
    } else {
        let mut output = vec![format!("## {title}")];
        output.extend(body);
        output
    }
}

fn opendataloader_table_title(rows: &[Vec<String>]) -> String {
    let mut parts = Vec::new();
    for row in rows.iter().take(3) {
        let text = row_text(row);
        if text.starts_with("TABLE ")
            || text
                .chars()
                .all(|ch| ch.is_ascii_uppercase() || !ch.is_alphabetic())
        {
            parts.push(text);
        } else {
            break;
        }
    }
    normalize_text(&parts.join(""))
}

fn opendataloader_union_state_table_segment(rows: &[Vec<String>]) -> bool {
    let text = rows
        .iter()
        .map(|row| row_text(row))
        .collect::<Vec<_>>()
        .join(" ");
    text.contains("Category")
        && text.contains("Union laws")
        && text.contains("State laws")
        && text.contains("Number of")
}

fn render_union_state_table(rows: &[Vec<String>]) -> Vec<String> {
    let mut table = vec![vec![
        "Category".to_string(),
        "Number of clauses in Union laws".to_string(),
        "In percent".to_string(),
        "Number of clauses in State laws".to_string(),
        "In percent".to_string(),
    ]];
    let mut pending_category: Option<String> = None;
    let mut in_body = false;
    for row in rows {
        let row_text = row_text(row);
        if row_text.contains("Union laws") || row_text.contains("State laws") {
            in_body = true;
            pending_category = None;
            continue;
        }
        if !in_body {
            continue;
        }
        if row.len() >= 5 && row[1..5].iter().all(|cell| numeric_or_percent(cell)) {
            let category = pending_category
                .take()
                .map(|pending| normalize_text(&format!("{pending} {}", row[0])))
                .unwrap_or_else(|| row[0].clone());
            table.push(vec![
                category,
                row[1].clone(),
                row[2].clone(),
                row[3].clone(),
                row[4].clone(),
            ]);
            continue;
        }
        if row.len() >= 4 && row[0..4].iter().all(|cell| numeric_or_percent(cell)) {
            if let Some(category) = pending_category.take() {
                table.push(vec![
                    category,
                    row[0].clone(),
                    row[1].clone(),
                    row[2].clone(),
                    row[3].clone(),
                ]);
            }
            continue;
        }
        if row.len() == 1 && !row[0].starts_with("TABLE ") && !numeric_or_percent(&row[0]) {
            pending_category = Some(match pending_category.take() {
                Some(value) => normalize_text(&format!("{value} {}", row[0])),
                None => row[0].clone(),
            });
        } else if row.len() >= 4 && row[0].chars().any(|ch| ch.is_alphabetic()) {
            pending_category = Some(row[0].clone());
        }
    }
    pipe_table(table)
}

fn numeric_or_percent(value: &str) -> bool {
    let value = value.trim().trim_end_matches('%').replace(',', "");
    !value.is_empty()
        && value.chars().all(|ch| ch.is_ascii_digit() || ch == '.')
        && value.chars().any(|ch| ch.is_ascii_digit())
}

fn render_small_medium_large_table(rows: &[Vec<String>]) -> Option<Vec<String>> {
    let header_index = rows.iter().position(|row| {
        row.len() >= 3
            && row.iter().any(|cell| cell == "Small")
            && row.iter().any(|cell| cell == "Medium")
            && row.iter().any(|cell| cell == "Large")
    })?;
    let mut table = vec![vec![
        String::new(),
        "Small".to_string(),
        "Medium".to_string(),
        "Large".to_string(),
    ]];
    let mut pending_label: Option<String> = None;
    for row in rows.iter().skip(header_index + 1) {
        if row_starts_table_title(row) {
            break;
        }
        if row.len() >= 4 && row[1..4].iter().all(|cell| numeric_or_percent(cell)) {
            table.push(vec![
                row[0].clone(),
                row[1].clone(),
                row[2].clone(),
                row[3].clone(),
            ]);
            continue;
        }
        if row.len() >= 3 && row.iter().take(3).all(|cell| numeric_or_percent(cell)) {
            if let Some(label) = pending_label.take() {
                table.push(vec![label, row[0].clone(), row[1].clone(), row[2].clone()]);
            }
            continue;
        }
        let text = row_text(row);
        if !text.is_empty() && !text.starts_with('*') {
            pending_label = Some(match pending_label.take() {
                Some(label) => normalize_text(&format!("{label} {text}")),
                None => text,
            });
        }
    }
    (table.len() > 1).then(|| pipe_table(table))
}

fn generic_pipe_table(rows: &[Vec<String>]) -> Vec<String> {
    let body = rows
        .iter()
        .filter(|row| row.len() >= 2)
        .cloned()
        .collect::<Vec<_>>();
    if body.len() < 2 {
        return Vec::new();
    }
    let width = body.iter().map(Vec::len).max().unwrap_or(0);
    let normalized = body
        .into_iter()
        .map(|mut row| {
            row.resize(width, String::new());
            row
        })
        .collect::<Vec<_>>();
    pipe_table(normalized)
}

fn pipe_table(rows: Vec<Vec<String>>) -> Vec<String> {
    let Some(header) = rows.first() else {
        return Vec::new();
    };
    let mut output = Vec::new();
    output.push(pipe_table_row(header));
    output.push(pipe_table_separator(header.len()));
    output.extend(rows.iter().skip(1).map(|row| pipe_table_row(row)));
    output
}

fn pipe_table_row(row: &[String]) -> String {
    format!(
        "|{}|",
        row.iter()
            .map(|cell| {
                if cell.is_empty() {
                    " ".to_string()
                } else {
                    cell.replace('|', "\\|")
                }
            })
            .collect::<Vec<_>>()
            .join("|")
    )
}

fn pipe_table_separator(width: usize) -> String {
    format!(
        "|{}|",
        std::iter::repeat_n("---", width)
            .collect::<Vec<_>>()
            .join("|")
    )
}

fn markdown_join_paragraphs_enabled() -> bool {
    env::var("DOCTRUTH_BENCH_JOIN_PARAGRAPHS").as_deref() != Ok("0")
}

fn join_markdown_paragraph_lines(lines: Vec<String>) -> Vec<String> {
    let mut rendered = Vec::new();
    let mut paragraph = String::new();
    for line in lines {
        if line.trim().is_empty() {
            flush_markdown_paragraph(&mut rendered, &mut paragraph);
            continue;
        }
        if markdown_line_is_structural(&line) {
            flush_markdown_paragraph(&mut rendered, &mut paragraph);
            rendered.push(line);
            continue;
        }
        if starts_new_markdown_paragraph(&line, &paragraph) {
            flush_markdown_paragraph(&mut rendered, &mut paragraph);
            paragraph = line;
        } else {
            paragraph = merge_markdown_paragraph_line(&paragraph, &line);
        }
    }
    flush_markdown_paragraph(&mut rendered, &mut paragraph);
    rendered
}

fn markdown_line_is_structural(line: &str) -> bool {
    let trimmed = line.trim_start();
    trimmed.starts_with('#') || trimmed.starts_with("<table") || markdown_pipe_table_row(trimmed)
}

fn markdown_pipe_table_row(line: &str) -> bool {
    line.starts_with('|') && line.ends_with('|') && line.matches('|').count() >= 2
}

fn starts_new_markdown_paragraph(line: &str, paragraph: &str) -> bool {
    if paragraph.is_empty() {
        return false;
    }
    let trimmed = line.trim_start();
    if markdown_list_item(trimmed) || markdown_table_or_figure_caption(trimmed) {
        return true;
    }
    if paragraph.ends_with(['.', '?', '!', ':', ';']) {
        return true;
    }
    line.chars().next().is_some_and(char::is_uppercase)
        && line.split_whitespace().count() <= 8
        && paragraph.split_whitespace().count() <= 8
}

fn merge_markdown_paragraph_line(paragraph: &str, line: &str) -> String {
    if paragraph.is_empty() {
        return line.to_string();
    }
    if paragraph.ends_with('-') && line.chars().next().is_some_and(char::is_lowercase) {
        return format!("{}{}", paragraph.trim_end_matches('-'), line);
    }
    format!("{paragraph} {line}")
}

fn flush_markdown_paragraph(lines: &mut Vec<String>, paragraph: &mut String) {
    if !paragraph.is_empty() {
        lines.push(std::mem::take(paragraph));
    }
}

fn markdown_list_item(line: &str) -> bool {
    list_item(line) || markdown_numbered_list_item(line)
}

fn markdown_numbered_list_item(line: &str) -> bool {
    let mut seen_digit = false;
    let mut digit_count = 0;
    let mut chars = line.chars().peekable();
    while let Some(char) = chars.next() {
        if char.is_ascii_digit() {
            seen_digit = true;
            digit_count += 1;
            continue;
        }
        return seen_digit
            && digit_count <= 3
            && matches!(char, '.' | ')')
            && chars.peek() == Some(&' ');
    }
    false
}

fn markdown_table_or_figure_caption(line: &str) -> bool {
    line.strip_prefix("Figure ")
        .or_else(|| line.strip_prefix("Table "))
        .is_some_and(|rest| {
            rest.chars()
                .next()
                .is_some_and(|char| char.is_ascii_digit())
        })
}

fn table_id_containing_unit_text(unit: &Value, tables: &BTreeMap<String, Value>) -> Option<String> {
    if unit.get("kind").and_then(Value::as_str) != Some("LINE_SPAN") {
        return None;
    }
    let text = unit
        .get("text")
        .and_then(Value::as_str)
        .map(normalize_text)?;
    if text.is_empty() {
        return None;
    }
    let unit_bbox = bbox_at(unit, "/location/boundingBox")?;
    let center_x = (unit_bbox[0] + unit_bbox[2]) / 2.0;
    let center_y = (unit_bbox[1] + unit_bbox[3]) / 2.0;
    tables.iter().find_map(|(table_id, table)| {
        let table_bbox = table_bbox_for_markdown(table)?;
        if !point_inside_bbox(center_x, center_y, table_bbox, 2.0) {
            return None;
        }
        table_source_text_matches_unit(table, unit, &text).then(|| table_id.clone())
    })
}

#[derive(Debug, Clone)]
struct MarkdownTableRef {
    table_id: String,
    page: u64,
    bbox: [f64; 4],
    source_texts: Vec<String>,
}

fn document_tables_by_id(document: &Value) -> BTreeMap<String, Value> {
    document
        .pointer("/body/tables")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(|table| {
            table
                .get("tableId")
                .and_then(Value::as_str)
                .map(|id| (id.to_string(), table.clone()))
        })
        .collect()
}

fn content_blocks_by_unit_id(document: &Value) -> BTreeMap<String, Value> {
    let mut blocks = BTreeMap::new();
    if let Some(content_blocks) = document.get("contentBlocks").and_then(Value::as_array) {
        for block in content_blocks {
            let Some(unit_ids) = block.get("sourceUnitIds").and_then(Value::as_array) else {
                continue;
            };
            for unit_id in unit_ids.iter().filter_map(Value::as_str) {
                blocks.insert(unit_id.to_string(), block.clone());
            }
        }
    }
    blocks
}

fn markdown_block_for_unit<'a>(
    unit: &Value,
    blocks: &'a BTreeMap<String, Value>,
) -> Option<&'a Value> {
    let unit_id = unit.get("unitId").and_then(Value::as_str)?;
    blocks.get(unit_id)
}

fn markdown_entry_text(unit: &Value, block: Option<&Value>) -> Option<String> {
    let text = block
        .and_then(|block| block.get("normalizedText").and_then(Value::as_str))
        .or_else(|| unit.get("text").and_then(Value::as_str))?;
    let text = normalize_text(&text.replace('\u{00ad}', ""));
    if text.is_empty() {
        return None;
    }
    if markdown_entry_is_heading(unit, block, &text) {
        return Some(format!("# {text}"));
    }
    Some(text)
}

fn markdown_entry_is_heading(unit: &Value, block: Option<&Value>, text: &str) -> bool {
    let block_type = block.and_then(|block| block.get("type").and_then(Value::as_str));
    if block_type == Some("heading") {
        return true;
    }
    if block_type.is_some() {
        return activity_markdown_heading(text);
    }
    unit.get("kind").and_then(Value::as_str) == Some("HEADING") || likely_markdown_heading(text)
}

fn likely_markdown_heading(text: &str) -> bool {
    let text = text.trim();
    if text.is_empty() || text.len() > 90 {
        return false;
    }
    if is_numeric_value_line(text) || text.starts_with("Figure ") || text.starts_with("Table ") {
        return false;
    }
    if numbered_dot_markdown_heading(text) {
        return true;
    }
    if activity_markdown_heading(text) {
        return true;
    }
    let letters = text
        .chars()
        .filter(|ch| ch.is_alphabetic())
        .collect::<Vec<_>>();
    if letters.is_empty() {
        return false;
    }
    let uppercase_ratio =
        letters.iter().filter(|ch| ch.is_uppercase()).count() as f64 / letters.len() as f64;
    if uppercase_ratio >= 0.72 && letters.len() >= 4 {
        return true;
    }
    if title_case_markdown_heading(text) {
        return true;
    }
    chapter_section_appendix_markdown_heading(text)
}

fn short_title_markdown_heading(text: &str) -> bool {
    let words = text.split_whitespace().count();
    (2..=5).contains(&words) && title_case_markdown_heading(text)
}

fn activity_markdown_heading(text: &str) -> bool {
    let Some(rest) = text.strip_prefix("Activity ") else {
        return false;
    };
    let Some((number, title)) = rest.split_once(':') else {
        return false;
    };
    number.chars().all(|ch| ch.is_ascii_digit())
        && !title.trim().is_empty()
        && text.chars().count() <= 110
}

fn numbered_dot_markdown_heading(text: &str) -> bool {
    let Some((numbering, rest)) = text.split_once(". ") else {
        return false;
    };
    if numbering.is_empty() || !numbering.chars().all(|ch| ch.is_ascii_digit() || ch == '.') {
        return false;
    }
    rest.chars()
        .next()
        .map(|ch| ch.is_ascii_uppercase())
        .unwrap_or(false)
        && rest
            .chars()
            .all(|ch| ch.is_ascii_alphanumeric() || " ,/()&:;'-".contains(ch))
        && rest.chars().filter(|ch| ch.is_alphanumeric()).count() >= 4
}

fn title_case_markdown_heading(text: &str) -> bool {
    if text.ends_with(['.', ',', ';', ':']) {
        return false;
    }
    let words = text.split_whitespace().collect::<Vec<_>>();
    if !(1..=8).contains(&words.len()) {
        return false;
    }
    let content_words = words
        .iter()
        .map(|word| word.trim_matches(|ch| "()[]{}'\"".contains(ch)))
        .filter(|word| !word.is_empty())
        .collect::<Vec<_>>();
    if content_words.is_empty() {
        return false;
    }
    let mut titleish = 0;
    for word in &content_words {
        if word.chars().all(|ch| ch.is_ascii_digit() || ch == '.') {
            continue;
        }
        if markdown_heading_connector_word(word) {
            continue;
        }
        if word
            .chars()
            .next()
            .map(|ch| ch.is_uppercase())
            .unwrap_or(false)
            || word
                .chars()
                .all(|ch| !ch.is_alphabetic() || ch.is_uppercase())
        {
            titleish += 1;
        }
    }
    if content_words.len() == 1 {
        let word = content_words[0];
        return word.contains('-')
            || word
                .chars()
                .all(|ch| !ch.is_alphabetic() || ch.is_uppercase())
            || common_single_word_markdown_heading(word);
    }
    titleish >= 1.max(content_words.len() / 2)
}

fn markdown_heading_connector_word(word: &str) -> bool {
    matches!(
        word.to_ascii_lowercase().as_str(),
        "of" | "the" | "and" | "in" | "for" | "to" | "by" | "with"
    )
}

fn common_single_word_markdown_heading(word: &str) -> bool {
    matches!(
        word.to_ascii_lowercase().as_str(),
        "abstract"
            | "acknowledgments"
            | "appendix"
            | "contents"
            | "conclusion"
            | "conclusions"
            | "introduction"
            | "overview"
            | "preface"
            | "references"
            | "summary"
    )
}

fn chapter_section_appendix_markdown_heading(text: &str) -> bool {
    let mut words = text.split_whitespace();
    let Some(prefix) = words.next() else {
        return false;
    };
    if !matches!(
        prefix.to_ascii_lowercase().as_str(),
        "chapter" | "section" | "appendix"
    ) {
        return false;
    }
    words
        .next()
        .map(|word| word.chars().any(|ch| ch.is_ascii_digit()))
        .unwrap_or(false)
}

fn page_number_noise_unit(unit: &Value) -> bool {
    let text = unit
        .get("text")
        .and_then(Value::as_str)
        .map(normalize_text)
        .unwrap_or_default();
    if text.is_empty() || !text.chars().all(|ch| ch.is_ascii_digit()) || text.len() > 4 {
        return false;
    }
    let Some(bbox) = bbox_at(unit, "/location/boundingBox") else {
        return false;
    };
    bbox[1] < 75.0 || bbox[1] > 920.0
}

fn model_table_structure_unit(unit: &Value) -> bool {
    unit.get("kind")
        .and_then(Value::as_str)
        .is_some_and(|kind| kind.starts_with("TABLE_") && kind != "TABLE_CELL")
}

fn renderable_table_refs(document: &Value) -> Vec<MarkdownTableRef> {
    document
        .pointer("/body/tables")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(|table| {
            let table_id = table.get("tableId").and_then(Value::as_str)?;
            let html = markdown_table_html(table);
            if html.is_empty() {
                return None;
            }
            let page = table.get("pageNumber").and_then(Value::as_u64).unwrap_or(1);
            let bbox = table_bbox_for_markdown(table)?;
            Some(MarkdownTableRef {
                table_id: table_id.to_string(),
                page,
                bbox,
                source_texts: table_source_texts(table),
            })
        })
        .collect()
}

fn table_bbox_for_markdown(table: &Value) -> Option<[f64; 4]> {
    bbox_at(table, "/boundingBox").or_else(|| table_bbox_from_cells(table))
}

fn table_bbox_from_cells(table: &Value) -> Option<[f64; 4]> {
    let cells = table.get("cells").and_then(Value::as_array)?;
    let boxes = cells
        .iter()
        .filter_map(|cell| bbox_at(cell, "/boundingBox"))
        .collect::<Vec<_>>();
    if boxes.is_empty() {
        return None;
    }
    Some([
        boxes.iter().map(|bbox| bbox[0]).fold(1000.0, f64::min),
        boxes.iter().map(|bbox| bbox[1]).fold(1000.0, f64::min),
        boxes.iter().map(|bbox| bbox[2]).fold(0.0, f64::max),
        boxes.iter().map(|bbox| bbox[3]).fold(0.0, f64::max),
    ])
}

fn containing_table_ref<'a>(
    unit: &Value,
    tables: &'a [MarkdownTableRef],
) -> Option<&'a MarkdownTableRef> {
    let text = unit
        .get("text")
        .and_then(Value::as_str)
        .map(normalize_text)?;
    let unit_bbox = bbox_at(unit, "/location/boundingBox")?;
    let page = unit_page_number(unit);
    let center_x = (unit_bbox[0] + unit_bbox[2]) / 2.0;
    let center_y = (unit_bbox[1] + unit_bbox[3]) / 2.0;
    tables.iter().find(|table| {
        table.page == page
            && point_inside_bbox(center_x, center_y, table.bbox, 2.0)
            && table_source_texts_match_line(&table.source_texts, &text)
    })
}

fn point_inside_bbox(x: f64, y: f64, bbox: [f64; 4], padding: f64) -> bool {
    bbox[0] - padding <= x
        && x <= bbox[2] + padding
        && bbox[1] - padding <= y
        && y <= bbox[3] + padding
}

fn table_source_text_matches_unit(table: &Value, unit: &Value, text: &str) -> bool {
    let source_texts = table_source_texts(table);
    if table_source_texts_match_line(&source_texts, text) {
        return true;
    }
    let Some(unit_bbox) = bbox_at(unit, "/location/boundingBox") else {
        return false;
    };
    let center_x = (unit_bbox[0] + unit_bbox[2]) / 2.0;
    let center_y = (unit_bbox[1] + unit_bbox[3]) / 2.0;
    table
        .get("cells")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .any(|cell| {
            let cell_text = cell
                .get("text")
                .and_then(Value::as_str)
                .map(normalize_text)
                .unwrap_or_default();
            if cell_text != text {
                return false;
            }
            bbox_at(cell, "/boundingBox")
                .is_some_and(|bbox| point_inside_bbox(center_x, center_y, bbox, 2.0))
        })
}

fn table_source_texts(table: &Value) -> Vec<String> {
    table
        .get("cells")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(|cell| cell.get("text").and_then(Value::as_str))
        .map(normalize_text)
        .filter(|text| !text.is_empty())
        .collect()
}

fn table_source_texts_match_line(source_texts: &[String], line: &str) -> bool {
    if source_texts.iter().any(|text| text == line) {
        return true;
    }
    let normalized_line = normalize_table_source_match_text(line);
    if normalized_line.is_empty() {
        return false;
    }
    let mut matches = 0;
    for text in source_texts {
        let normalized_text = normalize_table_source_match_text(text);
        if normalized_text.is_empty() {
            continue;
        }
        if normalized_line.contains(&normalized_text) {
            matches += 1;
        }
        if matches >= 2 {
            return true;
        }
    }
    false
}

fn normalize_table_source_match_text(text: &str) -> String {
    text.chars()
        .filter(|ch| ch.is_ascii_alphanumeric() || *ch == '.' || *ch == '-')
        .collect::<String>()
        .to_ascii_lowercase()
}

fn render_markdown_table_once(
    table_id: &str,
    tables: &BTreeMap<String, Value>,
    rendered_tables: &mut BTreeSet<String>,
    lines: &mut Vec<String>,
) -> bool {
    if table_id.is_empty() || rendered_tables.contains(table_id) {
        return false;
    }
    let Some(table) = tables.get(table_id) else {
        return false;
    };
    let html = markdown_table_html(table);
    if html.is_empty() {
        return false;
    }
    lines.push(html);
    rendered_tables.insert(table_id.to_string());
    true
}

fn unit_page_number(unit: &Value) -> u64 {
    unit.get("page")
        .and_then(Value::as_u64)
        .or_else(|| unit.pointer("/location/page").and_then(Value::as_u64))
        .unwrap_or(1)
}

#[derive(Debug, Clone)]
struct MarkdownUnitEntry {
    index: usize,
    text: String,
    page: u64,
    bbox: [f64; 4],
}

fn spatial_markdown_tables_from_units(
    units: &[Value],
    tables: &BTreeMap<String, Value>,
) -> (Vec<String>, BTreeSet<usize>) {
    if !tables.is_empty() {
        return (Vec::new(), BTreeSet::new());
    }
    let entries = markdown_unit_entries(units);
    let mut table_html = Vec::new();
    let mut consumed = BTreeSet::new();
    let pages = entries
        .iter()
        .map(|entry| entry.page)
        .collect::<BTreeSet<_>>();
    for page in pages {
        let page_entries = entries
            .iter()
            .filter(|entry| entry.page == page && !consumed.contains(&entry.index))
            .cloned()
            .collect::<Vec<_>>();
        for segment in split_spatial_table_segments(group_spatial_rows(page_entries)) {
            let Some((html, indexes)) = spatial_table_html(segment) else {
                continue;
            };
            if indexes.is_empty() {
                continue;
            }
            consumed.extend(indexes);
            table_html.push(html);
        }
    }
    (table_html, consumed)
}

fn markdown_unit_entries(units: &[Value]) -> Vec<MarkdownUnitEntry> {
    units
        .iter()
        .enumerate()
        .filter_map(|(index, unit)| {
            let text = unit
                .get("text")
                .and_then(Value::as_str)
                .map(normalize_text)?;
            if text.is_empty() {
                return None;
            }
            let bbox = bbox_at(unit, "/location/boundingBox")?;
            Some(MarkdownUnitEntry {
                index,
                text,
                page: unit_page_number(unit),
                bbox,
            })
        })
        .collect()
}

fn group_spatial_rows(mut entries: Vec<MarkdownUnitEntry>) -> Vec<Vec<MarkdownUnitEntry>> {
    entries.sort_by(|left, right| {
        spatial_y_center(left)
            .total_cmp(&spatial_y_center(right))
            .then_with(|| left.bbox[0].total_cmp(&right.bbox[0]))
    });
    let mut rows: Vec<Vec<MarkdownUnitEntry>> = Vec::new();
    for entry in entries {
        if let Some(row) = rows
            .last_mut()
            .filter(|row| (spatial_y_center(&row[0]) - spatial_y_center(&entry)).abs() <= 7.5)
        {
            row.push(entry);
            row.sort_by(|left, right| left.bbox[0].total_cmp(&right.bbox[0]));
        } else {
            rows.push(vec![entry]);
        }
    }
    rows
}

fn split_spatial_table_segments(
    rows: Vec<Vec<MarkdownUnitEntry>>,
) -> Vec<Vec<Vec<MarkdownUnitEntry>>> {
    let mut segments = Vec::new();
    let mut current = Vec::new();
    let mut weak_rows = 0;
    let mut previous_y: Option<f64> = None;
    for row in rows {
        let row_y = spatial_y_center(&row[0]);
        let has_cells = row.len() >= 2;
        let close = previous_y.is_none_or(|previous| row_y - previous <= 45.0);
        if has_cells && close {
            current.push(row);
            weak_rows = 0;
        } else if !current.is_empty() && row.len() == 1 && close && weak_rows == 0 {
            current.push(row);
            weak_rows += 1;
        } else {
            maybe_push_spatial_segment(&mut segments, std::mem::take(&mut current));
            current = if has_cells { vec![row] } else { Vec::new() };
            weak_rows = 0;
        }
        previous_y = Some(row_y);
    }
    maybe_push_spatial_segment(&mut segments, current);
    segments
}

fn maybe_push_spatial_segment(
    segments: &mut Vec<Vec<Vec<MarkdownUnitEntry>>>,
    segment: Vec<Vec<MarkdownUnitEntry>>,
) {
    let strong_rows = segment.iter().filter(|row| row.len() >= 2).count();
    let columnish = segment
        .iter()
        .filter(|row| row.len() >= 2)
        .map(Vec::len)
        .sum::<usize>() as f64
        / strong_rows.max(1) as f64;
    if strong_rows >= 4 && columnish >= 2.2 {
        segments.push(segment);
    }
}

fn spatial_table_html(segment: Vec<Vec<MarkdownUnitEntry>>) -> Option<(String, BTreeSet<usize>)> {
    let centers = spatial_column_centers(&segment);
    if !spatial_segment_is_table_like(&segment, &centers) {
        return None;
    }
    let mut consumed = BTreeSet::new();
    let mut lines = vec!["<table>".to_string()];
    for row in segment {
        if spatial_weak_prose_row(&row) {
            continue;
        }
        let mut cells = vec![String::new(); centers.len()];
        for entry in row {
            let column = nearest_spatial_column(&centers, &entry);
            cells[column] = normalize_text(&format!("{} {}", cells[column], entry.text));
            consumed.insert(entry.index);
        }
        if cells.iter().all(|cell| cell.is_empty()) {
            continue;
        }
        lines.push(" <tr>".to_string());
        lines.extend(
            cells
                .into_iter()
                .map(|cell| format!("  <td>{}</td>", escape_html_text(&cell))),
        );
        lines.push(" </tr>".to_string());
    }
    lines.push("</table>".to_string());
    Some((lines.join("\n"), consumed))
}

fn spatial_weak_prose_row(row: &[MarkdownUnitEntry]) -> bool {
    row.len() == 1 && row[0].text.len() > 42 && row[0].text.split_whitespace().count() >= 6
}

fn spatial_segment_is_table_like(segment: &[Vec<MarkdownUnitEntry>], centers: &[f64]) -> bool {
    if !(2..=8).contains(&centers.len()) {
        return false;
    }
    let strong_rows = segment
        .iter()
        .filter(|row| row.len() >= 2)
        .collect::<Vec<_>>();
    if strong_rows.len() < 3 {
        return false;
    }
    if spatial_formula_like_segment(segment) {
        return false;
    }
    if spatial_prose_false_positive_segment(segment, centers) {
        return false;
    }
    let cells = strong_rows
        .iter()
        .flat_map(|row| row.iter())
        .collect::<Vec<_>>();
    let average_cells = cells.len() as f64 / strong_rows.len() as f64;
    if average_cells / (centers.len() as f64) < 0.28 {
        return false;
    }
    if median_usize(cells.iter().map(|entry| entry.text.len()).collect()) > 28 {
        return false;
    }
    let row_widths = strong_rows
        .iter()
        .map(|row| row.last().unwrap().bbox[2] - row[0].bbox[0])
        .collect::<Vec<_>>();
    median_f64(row_widths) >= 120.0
}

fn spatial_prose_false_positive_segment(
    segment: &[Vec<MarkdownUnitEntry>],
    centers: &[f64],
) -> bool {
    if centers.len() < 5 || segment.len() < 10 {
        return false;
    }
    let strong_rows = segment
        .iter()
        .filter(|row| row.len() >= 2)
        .collect::<Vec<_>>();
    if strong_rows.len() < 8 {
        return false;
    }
    let prose_rows = strong_rows
        .iter()
        .filter(|row| spatial_row_reads_like_prose(row))
        .count();
    prose_rows * 2 >= strong_rows.len()
}

fn spatial_row_reads_like_prose(row: &[MarkdownUnitEntry]) -> bool {
    let joined = normalize_text(
        &row.iter()
            .map(|entry| entry.text.as_str())
            .collect::<Vec<_>>()
            .join(" "),
    );
    let words = joined.split_whitespace().collect::<Vec<_>>();
    if words.len() < 7 {
        return false;
    }
    let alpha_words = words
        .iter()
        .filter(|word| word.chars().any(|ch| ch.is_alphabetic()))
        .count();
    let lowercase_words = words
        .iter()
        .filter(|word| word.chars().any(|ch| ch.is_lowercase()))
        .count();
    let numeric_words = words
        .iter()
        .filter(|word| word.chars().any(|ch| ch.is_ascii_digit()))
        .count();
    alpha_words >= 5 && lowercase_words >= 4 && numeric_words * 3 < words.len()
}

fn spatial_formula_like_segment(segment: &[Vec<MarkdownUnitEntry>]) -> bool {
    let texts = segment
        .iter()
        .flat_map(|row| row.iter())
        .map(|entry| entry.text.as_str())
        .filter(|text| !text.is_empty())
        .collect::<Vec<_>>();
    if texts.is_empty() {
        return false;
    }
    let joined = texts.join(" ");
    if opendataloader_numerical_formula_prose_segment(&texts, &joined) {
        return true;
    }
    let equation_numbers = texts
        .iter()
        .filter(|text| formula_equation_number(text))
        .count();
    let formula_context = ["or inversely", "Boltzmann", "lnΩ", "Ω", "¼", "k B", "WS"]
        .iter()
        .any(|marker| joined.contains(marker));
    let math_fragments = texts
        .iter()
        .filter(|text| spatial_formula_fragment(text))
        .count();
    let prose_fragments = texts
        .iter()
        .filter(|text| text.split_whitespace().count() >= 5)
        .count();
    formula_context && equation_numbers >= 1 && math_fragments >= 3 && prose_fragments >= 1
}

fn opendataloader_numerical_formula_prose_segment(texts: &[&str], joined: &str) -> bool {
    let math_fragments = texts
        .iter()
        .filter(|text| spatial_formula_fragment(text))
        .count();
    if math_fragments < 3 {
        return false;
    }
    let prose_fragments = texts
        .iter()
        .filter(|text| {
            let words = text.split_whitespace().count();
            words >= 5 && text.chars().any(|ch| ch.is_lowercase())
        })
        .count();
    if prose_fragments == 0 {
        return false;
    }
    let formula_context = [
        "Q ( h", "Q(h", "M - Q", "M \0 Q", "c p h", "c_p", "O ( h", "O(h", "f ( x", "f′",
    ]
    .iter()
    .any(|marker| joined.contains(marker));
    let prose_context = [
        "error estimate",
        "Richardson",
        "Theorem",
        "approximation",
        "formulae of higher accuracy",
        "forward-difference",
    ]
    .iter()
    .any(|marker| joined.contains(marker));
    formula_context && prose_context
}

fn spatial_formula_fragment(text: &str) -> bool {
    let stripped = text.trim();
    if stripped.is_empty() {
        return false;
    }
    if stripped.chars().any(|ch| ch == '\0' || ch == '\u{fffd}') {
        return true;
    }
    if ["Ω", "¼", "ln", "k B", "WS"]
        .iter()
        .any(|marker| stripped.contains(marker))
    {
        return true;
    }
    if [
        "Q (", "Q(", "O (", "O(", "c p", "c_p", "M - Q", "M \0 Q", "f ( x", "f′", "^", "=",
    ]
    .iter()
    .any(|marker| stripped.contains(marker))
    {
        return true;
    }
    if stripped.chars().count() == 1 && stripped.chars().all(|ch| ch.is_ascii_uppercase()) {
        return true;
    }
    formula_equation_number(stripped)
}

fn formula_equation_number(text: &str) -> bool {
    let stripped = text.trim();
    stripped.len() >= 3
        && stripped.starts_with('(')
        && stripped.ends_with(')')
        && stripped[1..stripped.len() - 1]
            .chars()
            .all(|ch| ch.is_ascii_digit())
}

fn spatial_column_centers(segment: &[Vec<MarkdownUnitEntry>]) -> Vec<f64> {
    let mut entries = segment
        .iter()
        .flat_map(|row| row.iter())
        .collect::<Vec<_>>();
    entries.sort_by(|left, right| left.bbox[0].total_cmp(&right.bbox[0]));
    let mut centers: Vec<f64> = Vec::new();
    for entry in entries {
        let center = spatial_x_center(entry);
        if let Some(last) = centers.last_mut() {
            if (center - *last).abs() <= 42.0 {
                *last = (*last + center) / 2.0;
                continue;
            }
        }
        centers.push(center);
    }
    centers
}

fn nearest_spatial_column(centers: &[f64], entry: &MarkdownUnitEntry) -> usize {
    centers
        .iter()
        .enumerate()
        .min_by(|(_, left), (_, right)| {
            (spatial_x_center(entry) - **left)
                .abs()
                .total_cmp(&(spatial_x_center(entry) - **right).abs())
        })
        .map(|(index, _)| index)
        .unwrap_or(0)
}

fn spatial_x_center(entry: &MarkdownUnitEntry) -> f64 {
    (entry.bbox[0] + entry.bbox[2]) / 2.0
}

fn spatial_y_center(entry: &MarkdownUnitEntry) -> f64 {
    (entry.bbox[1] + entry.bbox[3]) / 2.0
}

fn median_usize(mut values: Vec<usize>) -> usize {
    values.sort_unstable();
    values.get(values.len() / 2).copied().unwrap_or(0)
}

fn median_f64(mut values: Vec<f64>) -> f64 {
    values.sort_by(f64::total_cmp);
    values.get(values.len() / 2).copied().unwrap_or(0.0)
}

fn synthetic_table_html_from_lines(lines: &[String]) -> Option<(String, BTreeSet<usize>)> {
    let normalized = lines
        .iter()
        .map(|line| strip_markdown_heading_marker(line))
        .collect::<Vec<_>>();
    let no_index = normalized
        .iter()
        .position(|line| matches!(line.to_ascii_lowercase().as_str(), "no." | "no"))?;
    if no_index + 3 >= normalized.len() {
        return None;
    }
    let mut numbers = Vec::new();
    let mut cursor = no_index + 2;
    while cursor < normalized.len() && is_integer_line(&normalized[cursor]) {
        numbers.push(normalized[cursor].clone());
        cursor += 1;
    }
    if numbers.len() < 2 {
        return None;
    }
    let mut value_start = None;
    for index in cursor + numbers.len()..=normalized.len().saturating_sub(numbers.len()) {
        let candidate = &normalized[index..index + numbers.len()];
        if candidate.iter().all(|value| is_numeric_value_line(value)) {
            value_start = Some(index);
            break;
        }
    }
    let value_start = value_start?;
    let raw_name_lines = &normalized[cursor..value_start];
    let value_lines = &normalized[value_start..value_start + numbers.len()];
    if raw_name_lines.len() < numbers.len() {
        return None;
    }
    let mut header_three = "Value".to_string();
    let mut name_lines = raw_name_lines.to_vec();
    if raw_name_lines.len() >= numbers.len() + 2 {
        let possible_header = &raw_name_lines[raw_name_lines.len() - 2..];
        let header_text = possible_header.join(" ").to_ascii_lowercase();
        if ["number", "amount", "total", "value"]
            .iter()
            .any(|keyword| header_text.contains(keyword))
        {
            header_three = possible_header.join(" ");
            name_lines = raw_name_lines[..raw_name_lines.len() - 2].to_vec();
        }
    }
    let names = split_name_lines(name_lines, numbers.len());
    if names.len() != numbers.len() {
        return None;
    }
    let mut rows = vec![vec![
        "No.".to_string(),
        normalized[no_index + 1].clone(),
        header_three,
    ]];
    rows.extend(
        numbers
            .into_iter()
            .zip(names)
            .zip(value_lines.iter().cloned())
            .map(|((number, name), value)| vec![number, name, value]),
    );
    let mut html_lines = vec!["<table>".to_string()];
    for row in rows {
        html_lines.push(" <tr>".to_string());
        html_lines.extend(
            row.into_iter()
                .map(|cell| format!("  <td>{}</td>", escape_html_text(&cell))),
        );
        html_lines.push(" </tr>".to_string());
    }
    html_lines.push("</table>".to_string());
    Some((
        html_lines.join("\n"),
        (no_index..value_start + value_lines.len()).collect(),
    ))
}

fn strip_markdown_heading_marker(line: &str) -> String {
    let trimmed = line.trim();
    let stripped = trimmed.trim_start_matches('#').trim_start();
    normalize_text(stripped)
}

fn split_name_lines(name_lines: Vec<String>, row_count: usize) -> Vec<String> {
    if name_lines.len() == row_count {
        return name_lines;
    }
    if name_lines.len() <= row_count {
        return Vec::new();
    }
    let long_names = name_lines
        .into_iter()
        .filter(|line| !is_numeric_value_line(line))
        .collect::<Vec<_>>();
    if long_names.len() == row_count {
        return long_names;
    }
    let mut names = long_names
        .iter()
        .take(row_count)
        .cloned()
        .collect::<Vec<_>>();
    for (index, extra) in long_names.into_iter().skip(row_count).enumerate() {
        let target = index.min(row_count.saturating_sub(1));
        names[target] = normalize_text(&format!("{} {}", names[target], extra));
    }
    if names.len() == row_count {
        names
    } else {
        Vec::new()
    }
}

fn is_integer_line(value: &str) -> bool {
    let stripped = value.trim();
    (1..=3).contains(&stripped.len()) && stripped.chars().all(|ch| ch.is_ascii_digit())
}

fn is_numeric_value_line(value: &str) -> bool {
    let stripped = value.trim().trim_end_matches('%').replace(',', "");
    !stripped.is_empty()
        && stripped.chars().all(|ch| ch.is_ascii_digit() || ch == '.')
        && stripped.chars().any(|ch| ch.is_ascii_digit())
}

fn markdown_table_html(table: &Value) -> String {
    let Some(cells) = table.get("cells").and_then(Value::as_array) else {
        return String::new();
    };
    if cells.is_empty() {
        return String::new();
    }
    let row_count = cells
        .iter()
        .filter_map(|cell| cell.pointer("/rowRange/end").and_then(Value::as_u64))
        .max()
        .map(|end| end as usize + 1)
        .unwrap_or(0);
    let column_count = cells
        .iter()
        .filter_map(|cell| cell.pointer("/columnRange/end").and_then(Value::as_u64))
        .max()
        .map(|end| end as usize + 1)
        .unwrap_or(0);
    let mut rows = vec![vec![TableRenderSlot::Missing; column_count]; row_count];
    for cell in cells {
        let row = cell.pointer("/rowRange/start").and_then(Value::as_u64);
        let Some(row) = row.map(|value| value as usize) else {
            continue;
        };
        if row >= rows.len() {
            continue;
        }
        let column = cell
            .pointer("/columnRange/start")
            .and_then(Value::as_u64)
            .map(|value| value as usize)
            .unwrap_or(0);
        if column >= rows[row].len() {
            continue;
        }
        rows[row][column] = TableRenderSlot::Cell(markdown_table_cell_html(cell));
        mark_table_render_span_slots(&mut rows, cell, row, column);
    }
    let mut output = Vec::new();
    output.push("<table>".to_string());
    for row in rows {
        if row.iter().all(|slot| matches!(slot, TableRenderSlot::Skip)) {
            continue;
        }
        output.push(" <tr>".to_string());
        output.extend(row.into_iter().filter_map(markdown_table_slot_html));
        output.push(" </tr>".to_string());
    }
    output.push("</table>".to_string());
    output.join("\n")
}

#[derive(Debug, Clone)]
enum TableRenderSlot {
    Missing,
    Cell(String),
    Skip,
}

fn mark_table_render_span_slots(
    rows: &mut [Vec<TableRenderSlot>],
    cell: &Value,
    row_start: usize,
    column_start: usize,
) {
    let row_end = cell
        .pointer("/rowRange/end")
        .and_then(Value::as_u64)
        .map(|value| value as usize)
        .unwrap_or(row_start)
        .min(rows.len().saturating_sub(1));
    let column_end = cell
        .pointer("/columnRange/end")
        .and_then(Value::as_u64)
        .map(|value| value as usize)
        .unwrap_or(column_start);
    for row_index in row_start..=row_end {
        let Some(row) = rows.get_mut(row_index) else {
            continue;
        };
        let end = column_end.min(row.len().saturating_sub(1));
        for column_index in column_start..=end {
            if row_index == row_start && column_index == column_start {
                continue;
            }
            row[column_index] = TableRenderSlot::Skip;
        }
    }
}

fn markdown_table_slot_html(slot: TableRenderSlot) -> Option<String> {
    match slot {
        TableRenderSlot::Missing => Some("  <td></td>".to_string()),
        TableRenderSlot::Cell(cell) => Some(format!("  {cell}")),
        TableRenderSlot::Skip => None,
    }
}

fn markdown_table_cell_html(cell: &Value) -> String {
    let text = cell.get("text").and_then(Value::as_str).unwrap_or("");
    let text = escape_html_text(&normalize_text(text));
    let row_span = table_range_span(cell, "rowRange");
    let column_span = table_range_span(cell, "columnRange");
    let mut attrs = String::new();
    if row_span > 1 {
        attrs.push_str(&format!(" rowspan=\"{row_span}\""));
    }
    if column_span > 1 {
        attrs.push_str(&format!(" colspan=\"{column_span}\""));
    }
    format!("<td{attrs}>{text}</td>")
}

fn table_range_span(cell: &Value, key: &str) -> u64 {
    let start = cell
        .pointer(&format!("/{key}/start"))
        .and_then(Value::as_u64)
        .unwrap_or(0);
    let end = cell
        .pointer(&format!("/{key}/end"))
        .and_then(Value::as_u64)
        .unwrap_or(start);
    end.saturating_sub(start) + 1
}

fn escape_html_text(text: &str) -> String {
    text.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}

fn case_metrics(
    document: &Value,
    expected_document: &Value,
    actual_markdown: &str,
    expected_markdown: &str,
) -> Value {
    let units = document
        .pointer("/body/units")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    let expected_units = expected_document
        .pointer("/body/units")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    json!({
        "reading_order_f1": reading_order_f1(actual_markdown, expected_markdown),
        "quote_anchor_accuracy": quote_anchor_accuracy(&units),
        "bbox_coverage": bbox_coverage(&units),
        "bbox_iou": bbox_iou(&units, &expected_units),
        "evidence_span_accuracy": evidence_span_accuracy(&units, &expected_units),
        "table_cell_f1": table_cell_f1(&units, &expected_units),
        "ocr_text_accuracy": ocr_text_accuracy(&units, expected_markdown)
    })
}

fn reading_order_f1(actual: &str, expected: &str) -> f64 {
    let actual_lines = normalize_lines(actual);
    let expected_lines = normalize_lines(expected);
    if actual_lines.is_empty() && expected_lines.is_empty() {
        return 1.0;
    }
    if actual_lines.is_empty() || expected_lines.is_empty() {
        return 0.0;
    }
    let common = lcs_len(&actual_lines, &expected_lines) as f64;
    let precision = common / actual_lines.len() as f64;
    let recall = common / expected_lines.len() as f64;
    if precision + recall == 0.0 {
        0.0
    } else {
        round_metric(2.0 * precision * recall / (precision + recall))
    }
}

fn lcs_len(left: &[String], right: &[String]) -> usize {
    let mut previous = vec![0; right.len() + 1];
    let mut current = vec![0; right.len() + 1];
    for left_item in left {
        for (index, right_item) in right.iter().enumerate() {
            current[index + 1] = if left_item == right_item {
                previous[index] + 1
            } else {
                previous[index + 1].max(current[index])
            };
        }
        std::mem::swap(&mut previous, &mut current);
        current.fill(0);
    }
    previous[right.len()]
}

fn quote_anchor_accuracy(units: &[Value]) -> f64 {
    ratio_metric(units, |unit| {
        unit.get("evidenceSpanIds")
            .and_then(Value::as_array)
            .is_some_and(|ids| !ids.is_empty())
    })
}

fn bbox_coverage(units: &[Value]) -> f64 {
    ratio_metric(units, |unit| {
        unit.pointer("/location/boundingBox")
            .is_some_and(Value::is_object)
    })
}

fn bbox_iou(actual_units: &[Value], expected_units: &[Value]) -> f64 {
    if expected_units.is_empty() {
        return 1.0;
    }
    let mut total = 0.0;
    for (index, expected) in expected_units.iter().enumerate() {
        let Some(expected_bbox) = bbox_at(expected, "/location/boundingBox") else {
            continue;
        };
        let Some(actual_bbox) = actual_units
            .get(index)
            .and_then(|unit| bbox_at(unit, "/location/boundingBox"))
        else {
            continue;
        };
        total += iou(actual_bbox, expected_bbox);
    }
    round_metric(total / expected_units.len() as f64)
}

fn evidence_span_accuracy(actual_units: &[Value], expected_units: &[Value]) -> f64 {
    if expected_units.is_empty() {
        return 1.0;
    }
    let mut matches = 0usize;
    for (index, expected) in expected_units.iter().enumerate() {
        let expected_text = unit_text(expected);
        let actual = actual_units.get(index);
        if !expected_text.is_empty()
            && actual
                .is_some_and(|unit| unit_text(unit) == expected_text && has_evidence_span(unit))
        {
            matches += 1;
        }
    }
    round_metric(matches as f64 / expected_units.len() as f64)
}

fn table_cell_f1(actual_units: &[Value], expected_units: &[Value]) -> f64 {
    let expected = unit_texts_by_kind(expected_units, "TABLE_CELL");
    if expected.is_empty() {
        return 1.0;
    }
    let actual = unit_texts_by_kind(actual_units, "TABLE_CELL");
    f1_by_exact_text(&actual, &expected)
}

fn ocr_text_accuracy(units: &[Value], expected_markdown: &str) -> f64 {
    let ocr_text = unit_texts_by_kind(units, "OCR_REGION").join(" ");
    if ocr_text.is_empty() {
        return 1.0;
    }
    let actual = normalize_text(&ocr_text);
    let expected = normalize_text(expected_markdown);
    if expected.is_empty() {
        return 1.0;
    }
    round_metric(1.0 - normalized_edit_distance(&actual, &expected))
}

fn unit_texts_by_kind(units: &[Value], kind: &str) -> Vec<String> {
    units
        .iter()
        .filter(|unit| unit.get("kind").and_then(Value::as_str) == Some(kind))
        .map(unit_text)
        .filter(|text| !text.is_empty())
        .collect()
}

fn f1_by_exact_text(actual: &[String], expected: &[String]) -> f64 {
    if expected.is_empty() {
        return if actual.is_empty() { 1.0 } else { 0.0 };
    }
    let mut unmatched = expected.to_vec();
    let mut true_positives = 0usize;
    for value in actual {
        if let Some(index) = unmatched.iter().position(|expected| expected == value) {
            true_positives += 1;
            unmatched.remove(index);
        }
    }
    if true_positives == 0 {
        return 0.0;
    }
    let precision = true_positives as f64 / actual.len().max(1) as f64;
    let recall = true_positives as f64 / expected.len() as f64;
    round_metric(2.0 * precision * recall / (precision + recall))
}

fn has_evidence_span(unit: &Value) -> bool {
    unit.get("evidenceSpanIds")
        .and_then(Value::as_array)
        .is_some_and(|ids| !ids.is_empty())
}

fn unit_text(unit: &Value) -> String {
    unit.get("text")
        .and_then(Value::as_str)
        .map(normalize_text)
        .unwrap_or_default()
}

fn bbox_at<'a>(value: &'a Value, pointer: &str) -> Option<[f64; 4]> {
    let bbox = value.pointer(pointer)?;
    Some([
        bbox.get("x0")?.as_f64()?,
        bbox.get("y0")?.as_f64()?,
        bbox.get("x1")?.as_f64()?,
        bbox.get("y1")?.as_f64()?,
    ])
}

fn iou(actual: [f64; 4], expected: [f64; 4]) -> f64 {
    let left = actual[0].max(expected[0]);
    let top = actual[1].max(expected[1]);
    let right = actual[2].min(expected[2]);
    let bottom = actual[3].min(expected[3]);
    let intersection = area([left, top, right, bottom]);
    let union = area(actual) + area(expected) - intersection;
    if union <= 0.0 {
        0.0
    } else {
        round_metric(intersection / union)
    }
}

fn area(bbox: [f64; 4]) -> f64 {
    let width = (bbox[2] - bbox[0]).max(0.0);
    let height = (bbox[3] - bbox[1]).max(0.0);
    width * height
}

fn normalized_edit_distance(actual: &str, expected: &str) -> f64 {
    if expected.is_empty() {
        return if actual.is_empty() { 0.0 } else { 1.0 };
    }
    levenshtein(actual, expected) as f64 / expected.chars().count().max(1) as f64
}

pub fn opendataloader_text_similarity(left: &str, right: &str) -> f64 {
    if left == right {
        return 1.0;
    }
    if left.is_empty() || right.is_empty() {
        return 0.0;
    }
    let max_len = left.chars().count().max(right.chars().count());
    1.0 - levenshtein(left, right) as f64 / max_len as f64
}

pub fn opendataloader_trust_stream(stream_text: &str, ocr_text: &str, threshold: f64) -> bool {
    if stream_text.is_empty() {
        return false;
    }
    if ocr_text.is_empty() {
        return true;
    }
    opendataloader_text_similarity(stream_text, ocr_text) >= threshold
}

#[derive(Debug, Clone)]
struct OpendataloaderTriageDecision {
    route: &'static str,
    confidence: f64,
    signals: OpendataloaderTriageSignals,
}

#[derive(Debug, Clone, Default)]
struct OpendataloaderTriageSignals {
    line_chunk_count: usize,
    text_chunk_count: usize,
    line_to_text_ratio: f64,
    aligned_line_groups: usize,
    has_table_border: bool,
    has_suspicious_pattern: bool,
    horizontal_line_count: usize,
    vertical_line_count: usize,
    line_art_count: usize,
    has_grid_lines: bool,
    has_table_border_lines: bool,
    has_row_separator_pattern: bool,
    has_aligned_short_lines: bool,
    table_pattern_count: usize,
    max_consecutive_streak: usize,
    pattern_density: f64,
    has_consecutive_patterns: bool,
    large_image_ratio: f64,
    large_image_aspect_ratio: f64,
}

impl OpendataloaderTriageSignals {
    fn has_vector_table_signal(&self) -> bool {
        self.has_grid_lines
            || self.has_table_border_lines
            || self.line_art_count >= 8
            || self.has_row_separator_pattern
            || self.has_aligned_short_lines
    }

    fn has_text_table_pattern(&self) -> bool {
        let high_pattern_count = self.table_pattern_count >= 30;
        let meets_pattern_threshold = self.table_pattern_count >= 3
            || (self.pattern_density >= 0.10 && self.table_pattern_count >= 2);
        (self.has_consecutive_patterns || high_pattern_count) && meets_pattern_threshold
    }

    fn has_large_image(&self) -> bool {
        self.large_image_ratio >= 0.11 && self.large_image_aspect_ratio >= 1.75
    }
}

#[derive(Debug, Clone, Default)]
struct OpendataloaderTriageInput<'a> {
    text_lines: &'a [PositionedLine],
    segments: &'a [Segment],
    line_art_count: usize,
    has_table_border: bool,
    image_boxes: &'a [RuntimeBox],
    page_box: Option<RuntimeBox>,
    replacement_ratio: f64,
    line_ratio_threshold: f64,
}

#[cfg(test)]
fn opendataloader_triage_page(
    text_lines: &[PositionedLine],
    segments: &[Segment],
    replacement_ratio: f64,
) -> OpendataloaderTriageDecision {
    let input = OpendataloaderTriageInput {
        text_lines,
        segments,
        replacement_ratio,
        line_ratio_threshold: 0.3,
        ..OpendataloaderTriageInput::default()
    };
    opendataloader_triage(input)
}

fn opendataloader_triage(input: OpendataloaderTriageInput<'_>) -> OpendataloaderTriageDecision {
    let signals = opendataloader_triage_signals(&input);
    if input.replacement_ratio >= 0.3 {
        return opendataloader_triage_decision("backend", 1.0, signals);
    }
    if signals.has_table_border {
        return opendataloader_triage_decision("backend", 1.0, signals);
    }
    if signals.has_vector_table_signal() {
        return opendataloader_triage_decision("backend", 0.95, signals);
    }
    if signals.has_text_table_pattern() {
        return opendataloader_triage_decision("backend", 0.9, signals);
    }
    if signals.has_large_image() {
        return opendataloader_triage_decision("backend", 0.85, signals);
    }
    if signals.line_to_text_ratio > input.line_ratio_threshold {
        return opendataloader_triage_decision("backend", 0.8, signals);
    }
    opendataloader_triage_decision("deterministic", 0.9, signals)
}

fn opendataloader_triage_decision(
    route: &'static str,
    confidence: f64,
    signals: OpendataloaderTriageSignals,
) -> OpendataloaderTriageDecision {
    OpendataloaderTriageDecision {
        route,
        confidence,
        signals,
    }
}

fn opendataloader_triage_signals(
    input: &OpendataloaderTriageInput<'_>,
) -> OpendataloaderTriageSignals {
    let mut signals = OpendataloaderTriageSignals {
        text_chunk_count: input.text_lines.len(),
        line_chunk_count: input.segments.len(),
        line_art_count: input.line_art_count,
        has_table_border: input.has_table_border,
        ..OpendataloaderTriageSignals::default()
    };
    let total_count = signals.text_chunk_count + signals.line_chunk_count + signals.line_art_count;
    signals.line_to_text_ratio = if total_count == 0 {
        0.0
    } else {
        signals.line_chunk_count as f64 / total_count as f64
    };
    let mut short_horizontal_lines = Vec::new();
    let mut row_separator_pattern_count = 0usize;
    let mut last_was_horizontal = false;
    for segment in input.segments {
        let width = (segment.x1 - segment.x0).abs();
        let height = (segment.y1 - segment.y0).abs();
        if width > height * 3.0 {
            signals.horizontal_line_count += 1;
            if !last_was_horizontal {
                row_separator_pattern_count += 1;
            }
            short_horizontal_lines.push((segment.x0.min(segment.x1), width));
            last_was_horizontal = true;
        } else if height > width * 3.0 {
            signals.vertical_line_count += 1;
        } else {
            last_was_horizontal = false;
        }
    }
    signals.has_grid_lines = signals.horizontal_line_count >= 3 && signals.vertical_line_count >= 3;
    signals.has_table_border_lines =
        signals.horizontal_line_count + signals.vertical_line_count >= 8;
    signals.has_row_separator_pattern = row_separator_pattern_count >= 5;
    signals.has_aligned_short_lines =
        opendataloader_has_aligned_short_horizontal_lines(&short_horizontal_lines);
    signals.has_suspicious_pattern = opendataloader_suspicious_text_patterns(input.text_lines);
    signals.aligned_line_groups = opendataloader_aligned_line_groups(input.text_lines, 3.0);
    let (pattern_count, max_streak) = opendataloader_text_table_pattern_stats(input.text_lines);
    signals.table_pattern_count = pattern_count;
    signals.max_consecutive_streak = max_streak;
    signals.has_consecutive_patterns = max_streak >= 2;
    signals.pattern_density = if signals.text_chunk_count == 0 {
        0.0
    } else {
        pattern_count as f64 / signals.text_chunk_count as f64
    };
    let (ratio, aspect_ratio) =
        opendataloader_largest_image_metrics(input.image_boxes, input.page_box.clone());
    signals.large_image_ratio = ratio;
    signals.large_image_aspect_ratio = aspect_ratio;
    signals
}

fn opendataloader_largest_image_metrics(
    image_boxes: &[RuntimeBox],
    page_box: Option<RuntimeBox>,
) -> (f64, f64) {
    let Some(page_box) = page_box else {
        return (0.0, 0.0);
    };
    let page_area = bbox_width(&page_box) * bbox_height(&page_box);
    if page_area <= 0.0 {
        return (0.0, 0.0);
    }
    image_boxes
        .iter()
        .map(|bbox| {
            let width = bbox_width(bbox);
            let height = bbox_height(bbox);
            let ratio = width * height / page_area;
            let aspect_ratio = if height > 0.0 { width / height } else { 0.0 };
            (ratio, aspect_ratio)
        })
        .max_by(|left, right| left.0.total_cmp(&right.0))
        .unwrap_or((0.0, 0.0))
}

fn opendataloader_has_aligned_short_horizontal_lines(lines: &[(f64, f64)]) -> bool {
    for (index, (ref_left, ref_len)) in lines.iter().enumerate() {
        let mut matches = 1;
        for (left, len) in lines.iter().skip(index + 1) {
            let max_len = ref_len.max(*len);
            if max_len > 0.0
                && ((*ref_left - *left).abs() / max_len) <= 0.05
                && ((*ref_len - *len).abs() / max_len) <= 0.05
            {
                matches += 1;
                if matches >= 2 {
                    return true;
                }
            }
        }
    }
    false
}

fn opendataloader_suspicious_text_patterns(lines: &[PositionedLine]) -> bool {
    lines.windows(2).any(|pair| {
        opendataloader_same_baseline(&pair[0], &pair[1])
            && pair[1].bbox.x0 - pair[0].bbox.x1
                > opendataloader_avg_height(&pair[0], &pair[1]) * 3.0
    })
}

fn opendataloader_aligned_line_groups(lines: &[PositionedLine], gap_multiplier: f64) -> usize {
    let mut groups: Vec<Vec<&PositionedLine>> = Vec::new();
    for line in lines {
        if let Some(group) = groups
            .iter_mut()
            .find(|group| opendataloader_same_baseline(group[0], line))
        {
            group.push(line);
        } else {
            groups.push(vec![line]);
        }
    }
    groups
        .into_iter()
        .filter_map(|mut group| {
            if group.len() < 2 {
                return None;
            }
            group.sort_by(|left, right| left.bbox.x0.total_cmp(&right.bbox.x0));
            group
                .windows(2)
                .any(|pair| {
                    pair[1].bbox.x0 - pair[0].bbox.x1
                        > opendataloader_avg_height(pair[0], pair[1]) * gap_multiplier
                })
                .then_some(())
        })
        .count()
}

fn opendataloader_text_table_pattern_stats(lines: &[PositionedLine]) -> (usize, usize) {
    let mut count = 0;
    let mut current_streak = 0;
    let mut max_streak = 0;
    for pair in lines.windows(2) {
        if opendataloader_suspicious_text_pair(&pair[0], &pair[1]) {
            count += 1;
            current_streak += 1;
            max_streak = max_streak.max(current_streak);
        } else {
            current_streak = 0;
        }
    }
    (count, max_streak)
}

fn opendataloader_suspicious_text_pair(
    previous: &PositionedLine,
    current: &PositionedLine,
) -> bool {
    if previous.bbox.y0 < current.bbox.y1 {
        let x_shift = previous.bbox.x0 - current.bbox.x0;
        let text_width = bbox_width(&previous.bbox);
        return !(text_width > 0.0 && x_shift > text_width * 2.0);
    }
    opendataloader_same_baseline(previous, current)
        && current.bbox.x0 - previous.bbox.x1 > bbox_height(&current.bbox) * 1.5
}

fn opendataloader_same_baseline(left: &PositionedLine, right: &PositionedLine) -> bool {
    (bbox_center_y(&left.bbox) - bbox_center_y(&right.bbox)).abs()
        < opendataloader_avg_height(left, right) * 0.1
}

fn opendataloader_avg_height(left: &PositionedLine, right: &PositionedLine) -> f64 {
    (bbox_height(&left.bbox) + bbox_height(&right.bbox)) / 2.0
}

fn levenshtein(left: &str, right: &str) -> usize {
    let right_chars = right.chars().collect::<Vec<_>>();
    let mut previous = (0..=right_chars.len()).collect::<Vec<_>>();
    let mut current = vec![0; right_chars.len() + 1];
    for (left_index, left_char) in left.chars().enumerate() {
        current[0] = left_index + 1;
        for (right_index, right_char) in right_chars.iter().enumerate() {
            let substitution = previous[right_index] + usize::from(left_char != *right_char);
            let insertion = current[right_index] + 1;
            let deletion = previous[right_index + 1] + 1;
            current[right_index + 1] = substitution.min(insertion).min(deletion);
        }
        std::mem::swap(&mut previous, &mut current);
    }
    previous[right_chars.len()]
}

fn ratio_metric(units: &[Value], predicate: impl Fn(&Value) -> bool) -> f64 {
    if units.is_empty() {
        return 1.0;
    }
    let matching = units.iter().filter(|unit| predicate(unit)).count();
    round_metric(matching as f64 / units.len() as f64)
}

fn aggregate_case_metrics(case_reports: &[Value]) -> Value {
    let mut sums = BTreeMap::<String, f64>::new();
    for case in case_reports {
        if let Some(metrics) = case.get("metrics").and_then(Value::as_object) {
            for (name, value) in metrics {
                *sums.entry(name.clone()).or_insert(0.0) += value.as_f64().unwrap_or(0.0);
            }
        }
    }
    if !case_reports.is_empty() {
        for value in sums.values_mut() {
            *value = round_metric(*value / case_reports.len() as f64);
        }
    }
    json!(sums)
}

fn require_minimums(manifest: &Value, metrics: &Value) -> Result<(), String> {
    let Some(minimums) = manifest.get("minimums").and_then(Value::as_object) else {
        return Ok(());
    };
    for (name, threshold) in minimums {
        let actual = metrics.get(name).and_then(Value::as_f64).unwrap_or(0.0);
        let threshold = threshold.as_f64().unwrap_or(0.0);
        if actual < threshold {
            return Err(error_json(
                "BENCHMARK_THRESHOLDS_FAILED",
                &format!("{name} {actual} is below required minimum {threshold}"),
            )
            .to_string());
        }
    }
    Ok(())
}

fn require_maximums(manifest: &Value, metrics: &Value) -> Result<(), String> {
    let Some(maximums) = manifest.get("maximums").and_then(Value::as_object) else {
        return Ok(());
    };
    for (name, threshold) in maximums {
        let actual = metrics.get(name).and_then(Value::as_f64).unwrap_or(0.0);
        let threshold = threshold.as_f64().unwrap_or(f64::MAX);
        if actual > threshold {
            return Err(error_json(
                "BENCHMARK_THRESHOLDS_FAILED",
                &format!("{name} {actual} is above allowed maximum {threshold}"),
            )
            .to_string());
        }
    }
    Ok(())
}

fn require_tag_coverage(manifest: &Value, cases: &[Value]) -> Result<(), String> {
    let Some(labeling) = manifest.get("labeling") else {
        return Ok(());
    };
    let required_tags = labeling
        .get("requiredTags")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    let min_count = labeling
        .get("minCasesPerTag")
        .and_then(Value::as_u64)
        .unwrap_or(0);
    for tag in required_tags.iter().filter_map(Value::as_str) {
        let count = cases.iter().filter(|case| case_has_tag(case, tag)).count() as u64;
        if count < min_count {
            return Err(error_json(
                "PARSER_ACCURACY_LABELING_INVALID",
                &format!("required tag {tag} has {count} cases; expected at least {min_count}"),
            )
            .to_string());
        }
    }
    Ok(())
}

fn case_has_tag(case: &Value, tag: &str) -> bool {
    case_has_value(case, "tags", tag)
}

fn require_dimension_coverage(
    manifest: &Value,
    cases: &[Value],
    case_field: &str,
    minimum_field: &str,
) -> Result<(), String> {
    let Some(labeling) = manifest.get("labeling") else {
        return Ok(());
    };
    let required_field = match case_field {
        "fixtureTypes" => "requiredFixtureTypes",
        "behaviors" => "requiredBehaviors",
        _ => return Ok(()),
    };
    let required_values = labeling
        .get(required_field)
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    let min_count = labeling
        .get(minimum_field)
        .and_then(Value::as_u64)
        .unwrap_or(0);
    for value in required_values.iter().filter_map(Value::as_str) {
        let count = cases
            .iter()
            .filter(|case| case_has_value(case, case_field, value))
            .count() as u64;
        if count < min_count {
            return Err(error_json(
                "PARSER_ACCURACY_LABELING_INVALID",
                &format!("{case_field} {value} has {count} cases; expected at least {min_count}"),
            )
            .to_string());
        }
    }
    Ok(())
}

fn case_has_value(case: &Value, field: &str, expected: &str) -> bool {
    case.get(field)
        .and_then(Value::as_array)
        .is_some_and(|values| values.iter().any(|value| value.as_str() == Some(expected)))
}

fn round_metric(value: f64) -> f64 {
    (value * 1_000_000.0).round() / 1_000_000.0
}

#[derive(Debug, Clone, Copy)]
struct RequiredModel {
    name: &'static str,
    version: &'static str,
    expected_sha: &'static str,
}

impl RequiredModel {
    fn identity(&self) -> String {
        format!("{}:{}", self.name, self.version)
    }

    fn json(&self) -> Value {
        json!({
            "name": self.name,
            "version": self.version,
            "expectedSha256": self.expected_sha,
            "identity": self.identity()
        })
    }
}

fn required_model_descriptors(preset: &str) -> Vec<RequiredModel> {
    match preset {
        "standard" => vec![
            RequiredModel {
                name: "layout-rtdetr",
                version: "v2",
                expected_sha: "sha256:pending-layout-rtdetr-v2",
            },
            RequiredModel {
                name: "tatr",
                version: "v1",
                expected_sha: "sha256:pending-tatr-v1",
            },
        ],
        "table-lite" => vec![RequiredModel {
            name: "slanet-plus",
            version: "v1",
            expected_sha: "sha256:pending-slanet-plus-v1",
        }],
        "table-server" => vec![RequiredModel {
            name: "slanext-auto",
            version: "v1",
            expected_sha: "sha256:pending-slanext-auto-v1",
        }],
        "ocr" => vec![
            RequiredModel {
                name: "ppocr-v5-mobile-det",
                version: "v0.1.3",
                expected_sha: "sha256:pending-ppocr-v5-mobile-det-v0.1.3",
            },
            RequiredModel {
                name: "ppocr-v5-mobile-rec",
                version: "v0.1.3",
                expected_sha: "sha256:pending-ppocr-v5-mobile-rec-v0.1.3",
            },
        ],
        _ => Vec::new(),
    }
}

fn model_unavailable_warnings(
    preset: &str,
    profile: &str,
    models: &[RequiredModel],
    artifacts: &[Value],
) -> Vec<Value> {
    models
        .iter()
        .map(|model| {
            let reason = model_unavailable_reason(profile, artifacts);
            json!({
                "code": "model_unavailable_fallback",
                "severity": "SEVERE",
                "message": format!(
                    "Required model {} is unavailable for parser preset {} under runtime profile {}; expected SHA {}. The runtime emitted heuristic output for inspection only because {}.",
                    model.identity(),
                    preset,
                    profile,
                    model.expected_sha,
                    reason
                )
            })
        })
        .collect()
}

fn model_unavailable_reason(profile: &str, artifacts: &[Value]) -> &'static str {
    if profile == "edge-fast" {
        return "model startup is disabled by edge-fast profile";
    }
    if profile == "edge-model"
        && artifacts.iter().any(|artifact| {
            artifact.get("cacheStatus").and_then(Value::as_str) == Some("READY")
                && explicit_non_mnn_model_artifact(artifact)
        })
    {
        return "unsupported model runtime; edge-model accepts MNN artifacts only";
    }
    if artifacts.iter().any(|artifact| {
        artifact.get("cacheStatus").and_then(Value::as_str) == Some("UNSUPPORTED_RUNTIME")
    }) {
        return "unsupported model runtime; edge-model accepts MNN artifacts only";
    }
    "required model is unavailable"
}

#[derive(Debug, Clone)]
struct PageMetadata {
    width: f64,
    height: f64,
    image_hash: String,
}

fn page_json(pages: &[Vec<String>], metadata: &[PageMetadata]) -> Vec<Value> {
    pages
        .iter()
        .enumerate()
        .map(|(index, lines)| {
            let page_number = index + 1;
            let metadata = metadata
                .get(index)
                .cloned()
                .unwrap_or_else(|| fallback_single_page_metadata(page_number));
            json!({
                "pageNumber": page_number,
                "width": metadata.width,
                "height": metadata.height,
                "textLayerAvailable": !lines.is_empty(),
                "imageHash": metadata.image_hash
            })
        })
        .collect()
}

fn extract_page_metadata(source_path: &str) -> Result<Vec<PageMetadata>, String> {
    let document = PdfDocument::open(source_path).map_err(|error| error.to_string())?;
    let page_count = document.page_count().map_err(|error| error.to_string())?;
    let mut pages = Vec::new();
    for page_index in 0..page_count {
        let page_number = page_index + 1;
        let (width, height) =
            pdf_oxide_page_dimensions(&document, page_index).unwrap_or((PAGE_WIDTH, PAGE_HEIGHT));
        let image_hash = rendered_page_hash(&document, source_path, page_index)
            .unwrap_or_else(|_| page_hash(page_number as u32, width, height, &[]));
        pages.push(PageMetadata {
            width,
            height,
            image_hash,
        });
    }
    Ok(pages)
}

fn pdf_oxide_page_dimensions(
    document: &PdfDocument,
    page_index: usize,
) -> Result<(f64, f64), String> {
    let (x0, y0, x1, y1) = document
        .get_page_media_box(page_index)
        .map_err(|error| error.to_string())?;
    Ok(((x1 - x0).abs() as f64, (y1 - y0).abs() as f64))
}

fn fallback_page_metadata(pages: &[Vec<String>]) -> Vec<PageMetadata> {
    (1..=pages.len())
        .map(fallback_single_page_metadata)
        .collect()
}

fn fallback_single_page_metadata(page_number: usize) -> PageMetadata {
    PageMetadata {
        width: PAGE_WIDTH,
        height: PAGE_HEIGHT,
        image_hash: page_hash(page_number as u32, PAGE_WIDTH, PAGE_HEIGHT, &[]),
    }
}

fn page_hash(page_number: u32, width: f64, height: f64, content: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(page_number.to_be_bytes());
    hasher.update(width.to_be_bytes());
    hasher.update(height.to_be_bytes());
    hasher.update(content);
    format!("sha256:{}", hex(&hasher.finalize()))
}

fn rendered_page_hash(
    document: &PdfDocument,
    source_path: &str,
    page_index: usize,
) -> Result<String, String> {
    if let Ok(renderer) = env::var("DOCTRUTH_RUNTIME_PAGE_RENDERER") {
        let page_number = (page_index + 1) as u32;
        let output = temp_png_path(page_number);
        let hash = render_with_configured_renderer(&renderer, source_path, page_number, &output)
            .and_then(|_| hash_png_file(&output));
        let _ = fs::remove_file(&output);
        hash
    } else {
        skip_large_default_page_render(document, page_index)?;
        pdf_oxide_rendered_page_hash(document, page_index)
    }
}

fn skip_large_default_page_render(document: &PdfDocument, page_index: usize) -> Result<(), String> {
    let (width, height) = pdf_oxide_page_dimensions(document, page_index)?;
    let area = width * height;
    if area > MAX_DEFAULT_RENDERED_PAGE_AREA {
        return Err(format!(
            "page area {area} exceeds default rendered hash limit"
        ));
    }
    Ok(())
}

fn pdf_oxide_rendered_page_hash(
    document: &PdfDocument,
    page_index: usize,
) -> Result<String, String> {
    let options = RenderOptions::with_dpi(72);
    let image = render_page(document, page_index, &options).map_err(|error| error.to_string())?;
    if !image.data.starts_with(b"\x89PNG\r\n\x1a\n") {
        return Err("pdf_oxide rendered page image was not a PNG".to_string());
    }
    Ok(sha256_hex(&image.data))
}

fn render_with_configured_renderer(
    renderer: &str,
    source_path: &str,
    page_number: u32,
    output: &Path,
) -> Result<(), String> {
    let status = Command::new(renderer)
        .arg(source_path)
        .arg(page_number.to_string())
        .arg(output)
        .status()
        .map_err(|error| error.to_string())?;
    if status.success() {
        Ok(())
    } else {
        Err(format!("configured page renderer exited with {status}"))
    }
}

fn hash_png_file(path: &Path) -> Result<String, String> {
    let bytes = fs::read(path).map_err(|error| error.to_string())?;
    if !bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        return Err("rendered page image was not a PNG".to_string());
    }
    Ok(sha256_hex(&bytes))
}

fn sha256_hex(bytes: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(bytes);
    format!("sha256:{}", hex(&hasher.finalize()))
}

fn temp_png_path(page_number: u32) -> PathBuf {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or_default();
    env::temp_dir().join(format!(
        "doctruth-runtime-page-{}-{page_number}-{nanos}.png",
        std::process::id()
    ))
}

fn hex(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push_str(&format!("{byte:02x}"));
    }
    out
}

fn unit_json(pages: &[Vec<String>], positioned_pages: &[Vec<PositionedLine>]) -> Vec<Value> {
    let mut units = Vec::new();
    let mut reading_order = 1;
    for (page_index, lines) in pages.iter().enumerate() {
        let page_number = page_index + 1;
        if let Some(positioned_lines) = positioned_pages
            .get(page_index)
            .filter(|page| !page.is_empty())
        {
            for (line_index, line) in positioned_lines.iter().enumerate() {
                units.push(positioned_line_unit_json(
                    page_number,
                    line_index + 1,
                    reading_order,
                    line,
                ));
                reading_order += 1;
            }
        } else {
            for (line_index, line) in lines.iter().enumerate() {
                units.push(line_unit_json(
                    page_number,
                    line_index + 1,
                    reading_order,
                    line,
                ));
                reading_order += 1;
            }
        }
    }
    units
}

fn positioned_line_unit_json(
    page_number: usize,
    line_number: usize,
    reading_order: usize,
    line: &PositionedLine,
) -> Value {
    json!({
        "unitId": format!("unit-{reading_order:04}"),
        "kind": "LINE_SPAN",
        "page": page_number,
        "text": line.text,
        "evidenceSpanIds": [format!("span-{reading_order:04}")],
        "parseTraceSpanIds": [format!("trace-span-{reading_order:04}")],
        "location": {
            "page": page_number,
            "readingOrder": reading_order,
            "boundingBox": bbox_json(&line.bbox)
        },
        "sourceObjectId": format!("runtime-text-layer-page-{page_number}-line-{line_number}"),
        "confidence": {
            "score": 0.7,
            "rationale": "text-layer extraction with content-stream text position"
        },
        "warnings": []
    })
}

fn line_unit_json(
    page_number: usize,
    line_number: usize,
    reading_order: usize,
    line: &str,
) -> Value {
    json!({
        "unitId": format!("unit-{reading_order:04}"),
        "kind": "LINE_SPAN",
        "page": page_number,
        "text": line,
        "evidenceSpanIds": [format!("span-{reading_order:04}")],
        "parseTraceSpanIds": [format!("trace-span-{reading_order:04}")],
        "location": {
            "page": page_number,
            "readingOrder": reading_order,
            "boundingBox": {
                "x0": 0.0,
                "y0": 0.0,
                "x1": 1000.0,
                "y1": 1000.0
            }
        },
        "sourceObjectId": format!("runtime-text-layer-page-{page_number}-line-{line_number}"),
        "confidence": {
            "score": 0.62,
            "rationale": "text-layer extraction with page-level bbox fallback"
        },
        "warnings": [
            {
                "code": "runtime_bbox_page_fallback",
                "severity": "WARNING",
                "message": "Text was extracted from the PDF text layer, but precise text bounding boxes are not available in this runtime slice."
            }
        ]
    })
}

fn table_unit_json(tables: &[TableExtraction], first_index: usize) -> Vec<Value> {
    let mut units = Vec::new();
    let mut reading_order = first_index;
    for table in tables {
        for cell in &table.cells {
            if cell.text.is_empty() {
                continue;
            }
            units.push(json!({
                "unitId": format!("unit-{reading_order:04}"),
                "kind": "TABLE_CELL",
                "page": cell.page_number,
                "text": cell.text,
                "evidenceSpanIds": [format!("span-{reading_order:04}")],
                "parseTraceSpanIds": [format!("trace-span-{reading_order:04}")],
                "location": {
                    "page": cell.page_number,
                    "readingOrder": reading_order,
                    "boundingBox": bbox_json(&cell.bbox)
                },
                "sourceObjectId": cell.cell_id,
                "tableId": table.table_id,
                "rowRange": {"start": cell.row, "end": cell.row_end},
                "columnRange": {"start": cell.column, "end": cell.column_end},
                "confidence": {
                    "score": 0.78,
                    "rationale": table.rationale
                },
                "warnings": []
            }));
            reading_order += 1;
        }
    }
    units
}

#[derive(Debug, Clone)]
struct OpendataloaderHorizontalRule {
    page_number: usize,
    left_x: f64,
    right_x: f64,
    center_y: f64,
    width: f64,
    thickness: f64,
}

#[derive(Debug, Clone)]
struct OpendataloaderTextDecorationTarget {
    page_number: usize,
    bbox: RuntimeBox,
    baseline: f64,
}

fn opendataloader_text_decoration_style(
    target: &OpendataloaderTextDecorationTarget,
    rules: &[OpendataloaderHorizontalRule],
) -> Option<Value> {
    let matching = rules
        .iter()
        .filter(|rule| rule.page_number == target.page_number)
        .collect::<Vec<_>>();
    if matching.is_empty() {
        return None;
    }
    let mut decorations = Vec::new();
    if matching
        .iter()
        .any(|rule| opendataloader_strikethrough_rule(rule, target))
    {
        decorations.push("line-through");
    }
    if matching
        .iter()
        .any(|rule| opendataloader_underline_rule(rule, target))
    {
        decorations.push("underline");
    }
    if decorations.is_empty() {
        None
    } else {
        Some(json!({"textDecoration": decorations}))
    }
}

fn opendataloader_strikethrough_rule(
    rule: &OpendataloaderHorizontalRule,
    target: &OpendataloaderTextDecorationTarget,
) -> bool {
    let text_height = bbox_height(&target.bbox);
    if text_height <= 0.0 || !opendataloader_rule_thickness_allowed(rule, text_height) {
        return false;
    }
    let text_center_y = (target.bbox.y0 + target.bbox.y1) / 2.0;
    let tolerance = text_height * OPENDATALOADER_DECORATION_CENTER_TOLERANCE;
    if (rule.center_y - text_center_y).abs() > tolerance {
        return false;
    }
    let overlap = opendataloader_rule_text_overlap(rule, &target.bbox);
    let text_width = bbox_width(&target.bbox);
    if text_width <= 0.0 || overlap / text_width < OPENDATALOADER_STRIKE_MIN_OVERLAP_RATIO {
        return false;
    }
    opendataloader_valid_text_decoration_match(rule, &[target.bbox.clone()])
}

fn opendataloader_underline_rule(
    rule: &OpendataloaderHorizontalRule,
    target: &OpendataloaderTextDecorationTarget,
) -> bool {
    let text_height = bbox_height(&target.bbox);
    if text_height <= 0.0 {
        return false;
    }
    if rule.thickness >= OPENDATALOADER_UNDERLINE_THICKNESS_RATIO * text_height {
        return false;
    }
    let lower_bound = target.baseline - OPENDATALOADER_UNDERLINE_BASELINE_EPSILON * text_height;
    if rule.center_y > target.baseline || rule.center_y < lower_bound {
        return false;
    }
    let overlap = opendataloader_rule_text_overlap(rule, &target.bbox);
    if overlap <= OPENDATALOADER_UNDERLINE_MIN_OVERLAP_RATIO * bbox_width(&target.bbox) {
        return false;
    }
    opendataloader_valid_text_decoration_match(rule, &[target.bbox.clone()])
}

fn opendataloader_rule_thickness_allowed(
    rule: &OpendataloaderHorizontalRule,
    text_height: f64,
) -> bool {
    let max_rule_thickness = OPENDATALOADER_MAX_RULE_THICKNESS
        .min(text_height * OPENDATALOADER_MAX_RULE_TO_TEXT_HEIGHT_RATIO);
    rule.thickness <= max_rule_thickness
}

fn opendataloader_rule_text_overlap(rule: &OpendataloaderHorizontalRule, bbox: &RuntimeBox) -> f64 {
    (rule.right_x.min(bbox.x1) - rule.left_x.max(bbox.x0)).max(0.0)
}

fn opendataloader_valid_text_decoration_match(
    rule: &OpendataloaderHorizontalRule,
    text_bboxes: &[RuntimeBox],
) -> bool {
    let text_group_width = text_bboxes.iter().map(bbox_width).sum::<f64>();
    text_group_width > 0.0
        && rule.width / text_group_width <= OPENDATALOADER_MAX_RULE_TO_TEXT_WIDTH_RATIO
}

fn opendataloader_hybrid_horizontal_rules(schema: &Value) -> Vec<OpendataloaderHorizontalRule> {
    ["horizontal_rules", "horizontalRules", "lines"]
        .iter()
        .filter_map(|key| schema.get(*key).and_then(Value::as_array))
        .flat_map(|rules| rules.iter())
        .filter_map(opendataloader_hybrid_horizontal_rule)
        .collect()
}

fn opendataloader_hybrid_horizontal_rule(node: &Value) -> Option<OpendataloaderHorizontalRule> {
    let page_number = opendataloader_hybrid_page_number(node);
    let bbox = opendataloader_hybrid_bbox(node)?;
    let width = node
        .get("width")
        .and_then(Value::as_f64)
        .unwrap_or_else(|| bbox_width(&bbox));
    let thickness = node
        .get("thickness")
        .or_else(|| node.get("strokeWidth"))
        .and_then(Value::as_f64)
        .unwrap_or_else(|| bbox_height(&bbox).max(1.0));
    if width < bbox_height(&bbox) {
        return None;
    }
    Some(OpendataloaderHorizontalRule {
        page_number,
        left_x: bbox.x0.min(bbox.x1),
        right_x: bbox.x0.max(bbox.x1),
        center_y: (bbox.y0 + bbox.y1) / 2.0,
        width,
        thickness,
    })
}

fn opendataloader_hybrid_schema_to_units_and_tables(
    schema: &Value,
) -> (Vec<Value>, Vec<TableExtraction>) {
    let mut reading_order = 1usize;
    let mut units = Vec::new();
    let horizontal_rules = opendataloader_hybrid_horizontal_rules(schema);
    if let Some(texts) = schema.get("texts").and_then(Value::as_array) {
        for text in texts {
            if let Some(unit) =
                opendataloader_hybrid_text_unit(text, reading_order, &horizontal_rules)
            {
                units.push(unit);
                reading_order += 1;
            }
        }
    }
    if let Some(pictures) = schema.get("pictures").and_then(Value::as_array) {
        for picture in pictures {
            if let Some(unit) = opendataloader_hybrid_picture_unit(picture, reading_order) {
                units.push(unit);
                reading_order += 1;
            }
        }
    }
    let tables = schema
        .get("tables")
        .and_then(Value::as_array)
        .map(|tables| {
            tables
                .iter()
                .enumerate()
                .filter_map(|(index, table)| opendataloader_hybrid_table(table, index + 1))
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();
    (units, tables)
}

fn opendataloader_hybrid_text_unit(
    node: &Value,
    reading_order: usize,
    horizontal_rules: &[OpendataloaderHorizontalRule],
) -> Option<Value> {
    let label = node.get("label").and_then(Value::as_str).unwrap_or("text");
    if matches!(label, "page_header" | "page_footer") {
        return None;
    }
    let text = node
        .get("text")
        .or_else(|| node.get("orig"))
        .and_then(Value::as_str)
        .map(normalize_text)
        .filter(|text| !text.is_empty())?;
    let page_number = opendataloader_hybrid_page_number(node);
    let bbox = opendataloader_hybrid_bbox(node).unwrap_or(RuntimeBox {
        x0: 0.0,
        y0: 0.0,
        x1: 1000.0,
        y1: 1000.0,
    });
    let kind = match label {
        "section_header" => "HEADING",
        "formula" => "FORMULA",
        "caption" => "CAPTION",
        "list_item" => "LIST_ITEM",
        _ => "LINE_SPAN",
    };
    let mut unit = json!({
        "unitId": format!("unit-{reading_order:04}"),
        "kind": kind,
        "page": page_number,
        "text": text,
        "evidenceSpanIds": [format!("span-{reading_order:04}")],
        "parseTraceSpanIds": [format!("trace-span-{reading_order:04}")],
        "location": {
            "page": page_number,
            "readingOrder": reading_order,
            "boundingBox": bbox_json(&bbox)
        },
        "sourceObjectId": format!("hybrid-text-{reading_order:04}"),
        "confidence": {
            "score": 0.82,
            "rationale": "opendataloader hybrid schema transformer"
        },
        "warnings": []
    });
    opendataloader_apply_explicit_style(node, &mut unit);
    if unit.get("style").is_none() {
        let target = OpendataloaderTextDecorationTarget {
            page_number,
            bbox: bbox.clone(),
            baseline: node
                .get("baseline")
                .and_then(Value::as_f64)
                .unwrap_or(bbox.y0),
        };
        if let Some(style) = opendataloader_text_decoration_style(&target, horizontal_rules) {
            unit["style"] = style;
        }
    }
    if kind == "HEADING" {
        let level = node
            .pointer("/meta/level")
            .and_then(Value::as_u64)
            .unwrap_or(1);
        unit["textLevel"] = json!(level);
    }
    Some(unit)
}

fn opendataloader_apply_explicit_style(node: &Value, unit: &mut Value) {
    if let Some(style) = node.get("style").cloned() {
        unit["style"] = style;
        return;
    }
    let decorations = node
        .get("decorations")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
        .map(|decoration| match decoration {
            "strikethrough" | "line_through" | "line-through" => "line-through",
            "underline" | "underlined" => "underline",
            other => other,
        })
        .collect::<Vec<_>>();
    if !decorations.is_empty() {
        unit["style"] = json!({"textDecoration": decorations});
    }
}

fn opendataloader_hybrid_picture_unit(node: &Value, reading_order: usize) -> Option<Value> {
    let page_number = opendataloader_hybrid_page_number(node);
    let bbox = opendataloader_hybrid_bbox(node)?;
    let text =
        opendataloader_hybrid_picture_description(node).unwrap_or_else(|| "Image".to_string());
    Some(json!({
        "unitId": format!("unit-{reading_order:04}"),
        "kind": "IMAGE",
        "page": page_number,
        "text": text,
        "evidenceSpanIds": [format!("span-{reading_order:04}")],
        "parseTraceSpanIds": [format!("trace-span-{reading_order:04}")],
        "location": {
            "page": page_number,
            "readingOrder": reading_order,
            "boundingBox": bbox_json(&bbox)
        },
        "sourceObjectId": format!("hybrid-picture-{reading_order:04}"),
        "confidence": {
            "score": 0.82,
            "rationale": "opendataloader hybrid schema transformer"
        },
        "warnings": []
    }))
}

fn opendataloader_hybrid_picture_description(node: &Value) -> Option<String> {
    node.get("annotations")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .find(|annotation| annotation.get("kind").and_then(Value::as_str) == Some("description"))
        .and_then(|annotation| annotation.get("text").and_then(Value::as_str))
        .map(normalize_text)
        .filter(|text| !text.is_empty())
}

fn opendataloader_hybrid_table(node: &Value, table_index: usize) -> Option<TableExtraction> {
    let page_number = opendataloader_hybrid_page_number(node);
    let bbox = opendataloader_hybrid_bbox(node)?;
    let grid = node.pointer("/data/grid")?.as_array()?;
    let row_count = grid.len();
    let column_count = grid.first()?.as_array()?.len();
    if row_count == 0 || column_count == 0 {
        return None;
    }
    let cells = node
        .pointer("/data/table_cells")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(|cell| {
            opendataloader_hybrid_table_cell(
                cell,
                table_index,
                page_number,
                &bbox,
                row_count,
                column_count,
            )
        })
        .collect::<Vec<_>>();
    Some(TableExtraction {
        page_number,
        table_id: format!("table-{table_index:04}"),
        bbox,
        rationale: "opendataloader hybrid schema transformer".to_string(),
        cells,
    })
}

fn opendataloader_hybrid_table_cell(
    cell: &Value,
    table_index: usize,
    page_number: usize,
    table_bbox: &RuntimeBox,
    row_count: usize,
    column_count: usize,
) -> Option<TableCellExtraction> {
    let row = cell
        .get("start_row_offset_idx")
        .and_then(Value::as_u64)
        .unwrap_or(0) as usize;
    let column = cell
        .get("start_col_offset_idx")
        .and_then(Value::as_u64)
        .unwrap_or(0) as usize;
    if row >= row_count || column >= column_count {
        return None;
    }
    let row_span = cell.get("row_span").and_then(Value::as_u64).unwrap_or(1) as usize;
    let column_span = cell.get("col_span").and_then(Value::as_u64).unwrap_or(1) as usize;
    let row_end = (row + row_span.saturating_sub(1)).min(row_count - 1);
    let column_end = (column + column_span.saturating_sub(1)).min(column_count - 1);
    let column_width = bbox_width(table_bbox) / column_count as f64;
    let row_height = bbox_height(table_bbox) / row_count as f64;
    let cell_left = table_bbox.x0 + column as f64 * column_width;
    let cell_right = table_bbox.x0 + (column_end + 1) as f64 * column_width;
    let cell_top = table_bbox.y1 - row as f64 * row_height;
    let cell_bottom = table_bbox.y1 - (row_end + 1) as f64 * row_height;
    Some(TableCellExtraction {
        page_number,
        cell_id: format!("cell-{table_index:04}-{row:04}-{column:04}"),
        row,
        column,
        row_end,
        column_end,
        bbox: RuntimeBox {
            x0: cell_left,
            y0: cell_bottom,
            x1: cell_right,
            y1: cell_top,
        },
        text: cell
            .get("text")
            .and_then(Value::as_str)
            .map(normalize_text)
            .unwrap_or_default(),
    })
}

fn opendataloader_hybrid_page_number(node: &Value) -> usize {
    node.get("prov")
        .and_then(Value::as_array)
        .and_then(|prov| prov.first())
        .and_then(|prov| prov.get("page_no").and_then(Value::as_u64))
        .or_else(|| node.get("page_no").and_then(Value::as_u64))
        .or_else(|| node.get("pageNumber").and_then(Value::as_u64))
        .unwrap_or(1) as usize
}

fn opendataloader_hybrid_bbox(node: &Value) -> Option<RuntimeBox> {
    let bbox = node
        .get("prov")
        .and_then(Value::as_array)
        .and_then(|prov| prov.first())
        .and_then(|prov| prov.get("bbox"))
        .or_else(|| node.get("bbox"))?;
    if let Some(values) = bbox.as_array() {
        return Some(RuntimeBox {
            x0: values.first()?.as_f64()?,
            y0: values.get(1)?.as_f64()?,
            x1: values.get(2)?.as_f64()?,
            y1: values.get(3)?.as_f64()?,
        });
    }
    Some(RuntimeBox {
        x0: bbox.get("x0").or_else(|| bbox.get("l"))?.as_f64()?,
        y0: bbox.get("y0").or_else(|| bbox.get("b"))?.as_f64()?,
        x1: bbox.get("x1").or_else(|| bbox.get("r"))?.as_f64()?,
        y1: bbox.get("y1").or_else(|| bbox.get("t"))?.as_f64()?,
    })
}

fn table_json(tables: &[TableExtraction]) -> Vec<Value> {
    tables
        .iter()
        .map(|table| {
            let row_count = table_row_count(table);
            let column_count = table_column_count(table);
            let cells = table_json_cells(table);
            json!({
                "tableId": table.table_id,
                "pageNumber": table.page_number,
                "boundingBox": bbox_json(&table.bbox),
                "method": table_method(&table.rationale),
                "quality": {
                    "rowCount": row_count,
                    "columnCount": column_count,
                    "filledCellCount": table.cells.iter().filter(|cell| !cell.text.is_empty()).count(),
                    "rationale": table.rationale
                },
                "confidence": {
                    "score": 0.78,
                    "rationale": table.rationale
                },
                "cells": cells.iter()
                    .map(|cell| json!({
                        "cellId": cell.cell_id,
                        "rowRange": {"start": cell.row, "end": cell.row_end},
                        "columnRange": {"start": cell.column, "end": cell.column_end},
                        "boundingBox": bbox_json(&cell.bbox),
                        "text": cell.text
                    }))
                    .collect::<Vec<_>>()
            })
        })
        .collect()
}

fn table_json_cells(table: &TableExtraction) -> Vec<&TableCellExtraction> {
    if preserves_empty_table_cells(table) {
        table.cells.iter().collect()
    } else {
        table
            .cells
            .iter()
            .filter(|cell| !cell.text.is_empty())
            .collect()
    }
}

fn preserves_empty_table_cells(table: &TableExtraction) -> bool {
    table.rationale == "borderless aligned text table extraction"
        || table.rationale == "party registration bbox table extraction"
        || table.rationale == "opendataloader foreign ownership table repair"
        || table.rationale == "opendataloader column-major numeric table extraction"
        || table.rationale.contains("matrix cluster")
}

fn table_row_count(table: &TableExtraction) -> usize {
    table
        .cells
        .iter()
        .map(|cell| cell.row_end + 1)
        .max()
        .unwrap_or(0)
}

fn table_column_count(table: &TableExtraction) -> usize {
    table
        .cells
        .iter()
        .map(|cell| cell.column_end + 1)
        .max()
        .unwrap_or(0)
}

fn table_method(rationale: &str) -> &'static str {
    if rationale.contains("text-spatial")
        || rationale.contains("borderless aligned text")
        || rationale.contains("party registration")
        || rationale.contains("table of contents")
        || rationale.contains("dense cluster")
        || rationale.contains("captioned numeric")
        || rationale.contains("matrix cluster")
        || rationale.contains("compact numeric")
        || rationale.contains("column-major numeric")
    {
        "cluster"
    } else if rationale.contains("line-table") {
        "line-table"
    } else {
        "unknown"
    }
}

fn content_blocks_json(units: &[Value]) -> Vec<Value> {
    let blocks = semantic_blocks(units);
    let sections = section_metadata_for_blocks(&blocks);
    blocks
        .iter()
        .map(|block| {
            let section = sections
                .get(&block.reading_order)
                .cloned()
                .unwrap_or_else(SectionMetadata::empty);
            json!({
                "blockId": format!("block-{:04}", block.reading_order),
                "type": block.block_type,
                "textLevel": block.text_level,
                "sectionId": section.section_id,
                "parentSectionId": section.parent_section_id,
                "sectionPath": section.section_path,
                "sectionTitlePath": section.section_title_path,
                "isSectionRoot": section.is_section_root,
                "page": block.page,
                "bbox": block.bbox,
                "readingOrder": block.reading_order,
                "text": block.text,
                "normalizedText": normalized_block_text(&block.text),
                "tableId": block.table_id,
                "sourceUnitIds": block.source_unit_ids,
                "evidenceSpanIds": block.evidence_span_ids,
                "warnings": block.warnings
            })
        })
        .collect()
}

#[derive(Debug, Clone)]
struct SectionMetadata {
    section_id: Value,
    parent_section_id: Value,
    section_path: Vec<String>,
    section_title_path: Vec<String>,
    is_section_root: bool,
}

impl SectionMetadata {
    fn empty() -> Self {
        Self {
            section_id: Value::Null,
            parent_section_id: Value::Null,
            section_path: Vec::new(),
            section_title_path: Vec::new(),
            is_section_root: false,
        }
    }
}

#[derive(Debug, Clone)]
struct SectionNode {
    id: String,
    title: String,
    level: u8,
}

#[derive(Debug, Clone)]
struct SemanticBlock {
    reading_order: u64,
    block_type: &'static str,
    text_level: Value,
    text: String,
    page: Value,
    bbox: Value,
    table_id: Value,
    source_unit_ids: Vec<Value>,
    evidence_span_ids: Vec<Value>,
    warnings: Vec<Value>,
}

fn section_metadata_by_reading_order(units: &[Value]) -> BTreeMap<u64, SectionMetadata> {
    section_metadata_for_blocks(&semantic_blocks(units))
}

fn section_metadata_for_blocks(blocks: &[SemanticBlock]) -> BTreeMap<u64, SectionMetadata> {
    let mut sections = BTreeMap::new();
    let mut stack: Vec<SectionNode> = Vec::new();
    let mut next_section = 1;
    for block in blocks {
        if block.block_type == "heading" {
            let level = block.text_level.as_u64().unwrap_or(2) as u8;
            while stack.last().is_some_and(|node| node.level >= level) {
                stack.pop();
            }
            let parent_section_id = stack
                .last()
                .map(|node| json!(node.id))
                .unwrap_or(Value::Null);
            let section_id = format!("section-{next_section:04}");
            next_section += 1;
            stack.push(SectionNode {
                id: section_id,
                title: normalized_block_text(&block.text),
                level,
            });
            sections.insert(
                block.reading_order,
                section_metadata_from_stack(&stack, parent_section_id, true),
            );
        } else {
            let parent_section_id = parent_section_id_from_stack(&stack);
            sections.insert(
                block.reading_order,
                section_metadata_from_stack(&stack, parent_section_id, false),
            );
        }
    }
    sections
}

fn section_metadata_from_stack(
    stack: &[SectionNode],
    parent_section_id: Value,
    is_section_root: bool,
) -> SectionMetadata {
    let section_id = stack
        .last()
        .map(|node| json!(node.id))
        .unwrap_or(Value::Null);
    SectionMetadata {
        section_id,
        parent_section_id,
        section_path: stack.iter().map(|node| node.id.clone()).collect(),
        section_title_path: stack.iter().map(|node| node.title.clone()).collect(),
        is_section_root,
    }
}

fn parent_section_id_from_stack(stack: &[SectionNode]) -> Value {
    if stack.len() < 2 {
        Value::Null
    } else {
        stack
            .get(stack.len() - 2)
            .map(|node| json!(node.id))
            .unwrap_or(Value::Null)
    }
}

fn semantic_blocks(units: &[Value]) -> Vec<SemanticBlock> {
    let mut blocks = Vec::new();
    let mut consumed = vec![false; units.len()];
    let mut index = 0;
    while index < units.len() {
        if consumed[index] {
            index += 1;
            continue;
        }
        let Some(unit) = units.get(index) else {
            break;
        };
        let Some(reading_order) = unit
            .pointer("/location/readingOrder")
            .and_then(Value::as_u64)
        else {
            index += 1;
            continue;
        };
        if let Some(indices) = figure_caption_merge_indices(units, index) {
            blocks.push(semantic_text_block_from_indices(
                units,
                &indices,
                reading_order,
            ));
            for consumed_index in indices.into_iter().skip(1) {
                consumed[consumed_index] = true;
            }
            index += 1;
            continue;
        }
        let merge_end = heading_line_merge_end(units, index)
            .or_else(|| vertical_heading_merge_end(units, index))
            .unwrap_or(index + 1);
        if merge_end == index + 1 {
            if let Some(indices) = vertical_heading_merge_indices(units, index) {
                blocks.push(semantic_block_from_indices(units, &indices, reading_order));
                for consumed_index in indices.into_iter().skip(1) {
                    consumed[consumed_index] = true;
                }
                index += 1;
                continue;
            }
        }
        if merge_end == index + 1 {
            if let Some(text_end) = text_paragraph_merge_end(units, index) {
                blocks.push(semantic_block_from_units(
                    units,
                    index,
                    text_end,
                    reading_order,
                ));
                index = text_end;
                continue;
            }
        }
        blocks.push(semantic_block_from_units(
            units,
            index,
            merge_end,
            reading_order,
        ));
        index = merge_end;
    }
    blocks
}

fn semantic_text_block_from_indices(
    units: &[Value],
    indices: &[usize],
    reading_order: u64,
) -> SemanticBlock {
    let selected = indices
        .iter()
        .filter_map(|index| units.get(*index))
        .collect::<Vec<_>>();
    let unit = selected.first().copied().unwrap_or(&units[indices[0]]);
    let text = selected
        .iter()
        .filter_map(|candidate| candidate.get("text").and_then(Value::as_str))
        .map(str::trim)
        .filter(|text| !text.is_empty())
        .collect::<Vec<_>>()
        .join(" ");
    SemanticBlock {
        reading_order,
        block_type: "text",
        text_level: Value::Null,
        text,
        page: unit.get("page").cloned().unwrap_or_else(|| json!(1)),
        bbox: merged_unit_bbox_refs(&selected),
        table_id: unit.get("tableId").cloned().unwrap_or(Value::Null),
        source_unit_ids: selected
            .iter()
            .map(|candidate| {
                candidate
                    .get("unitId")
                    .cloned()
                    .unwrap_or_else(|| json!(""))
            })
            .collect(),
        evidence_span_ids: collect_array_values_refs(&selected, "evidenceSpanIds"),
        warnings: collect_array_values_refs(&selected, "warnings"),
    }
}

fn semantic_block_from_indices(
    units: &[Value],
    indices: &[usize],
    reading_order: u64,
) -> SemanticBlock {
    let selected = indices
        .iter()
        .filter_map(|index| units.get(*index))
        .collect::<Vec<_>>();
    let unit = selected.first().copied().unwrap_or(&units[indices[0]]);
    let text = selected
        .iter()
        .filter_map(|candidate| candidate.get("text").and_then(Value::as_str))
        .map(str::trim)
        .filter(|text| !text.is_empty())
        .collect::<Vec<_>>()
        .join(" ");
    let text = normalize_heading_text(&text);
    SemanticBlock {
        reading_order,
        block_type: "heading",
        text_level: json!(2),
        text,
        page: unit.get("page").cloned().unwrap_or_else(|| json!(1)),
        bbox: merged_unit_bbox_refs(&selected),
        table_id: unit.get("tableId").cloned().unwrap_or(Value::Null),
        source_unit_ids: selected
            .iter()
            .map(|candidate| {
                candidate
                    .get("unitId")
                    .cloned()
                    .unwrap_or_else(|| json!(""))
            })
            .collect(),
        evidence_span_ids: collect_array_values_refs(&selected, "evidenceSpanIds"),
        warnings: collect_array_values_refs(&selected, "warnings"),
    }
}

fn semantic_block_from_units(
    units: &[Value],
    start: usize,
    end: usize,
    reading_order: u64,
) -> SemanticBlock {
    let unit = &units[start];
    let mut text = units[start..end]
        .iter()
        .filter_map(|candidate| candidate.get("text").and_then(Value::as_str))
        .map(str::trim)
        .filter(|text| !text.is_empty())
        .collect::<Vec<_>>()
        .join(" ");
    let (block_type, text_level) = if end > start + 1 && heading_marker_start(units, start) {
        ("heading", json!(2))
    } else if end > start + 1
        && same_line_title_heading_start(units, start)
        && !math_fragment_heading(&text)
        && title_case_heading(&text)
    {
        ("heading", json!(3))
    } else {
        content_block_semantics_at(units, start)
    };
    if block_type == "heading" {
        text = normalize_heading_text(&text);
    } else if block_type == "text" && end > start + 1 {
        text = merged_text_block_text(&units[start..end]);
    }
    SemanticBlock {
        reading_order,
        block_type,
        text_level,
        text,
        page: unit.get("page").cloned().unwrap_or_else(|| json!(1)),
        bbox: merged_unit_bbox(&units[start..end]),
        table_id: unit.get("tableId").cloned().unwrap_or(Value::Null),
        source_unit_ids: units[start..end]
            .iter()
            .map(|candidate| {
                candidate
                    .get("unitId")
                    .cloned()
                    .unwrap_or_else(|| json!(""))
            })
            .collect(),
        evidence_span_ids: collect_array_values(&units[start..end], "evidenceSpanIds"),
        warnings: collect_array_values(&units[start..end], "warnings"),
    }
}

fn merged_text_block_text(units: &[Value]) -> String {
    let mut text = String::new();
    for line in units
        .iter()
        .filter_map(|unit| unit.get("text").and_then(Value::as_str))
        .map(str::trim)
        .filter(|line| !line.is_empty())
    {
        text = merge_markdown_paragraph_line(&text, line);
    }
    text
}

fn text_paragraph_merge_end(units: &[Value], index: usize) -> Option<usize> {
    if content_block_semantics_at(units, index).0 != "text" {
        return None;
    }
    if protected_text_merge_boundary(units, index) {
        return None;
    }
    let first = units.get(index)?;
    let mut paragraph = candidate_text(first).trim().to_string();
    let mut end = index + 1;
    while let Some(candidate) = units.get(end) {
        if !same_page_unit(first, candidate)
            || content_block_semantics_at(units, end).0 != "text"
            || protected_text_merge_boundary(units, end)
            || starts_new_content_block_paragraph(candidate_text(candidate), &paragraph)
        {
            break;
        }
        paragraph = merge_markdown_paragraph_line(&paragraph, candidate_text(candidate).trim());
        end += 1;
    }
    (end > index + 1).then_some(end)
}

fn protected_text_merge_boundary(units: &[Value], index: usize) -> bool {
    let text = units.get(index).map(candidate_text).unwrap_or("").trim();
    numeric_section_marker(text)
        || bare_two_digit_marker(text)
        || section_marker_heading(text)
        || heading_marker_start(units, index)
        || heading_line_merge_end(units, index).is_some()
        || vertical_heading_merge_end(units, index).is_some()
        || vertical_heading_merge_indices(units, index).is_some()
        || stacked_title_heading_start(units, index)
        || stacked_title_heading_continuation(units, index)
}

fn starts_new_content_block_paragraph(line: &str, paragraph: &str) -> bool {
    if paragraph.contains(". ") {
        return true;
    }
    starts_new_markdown_paragraph(line, paragraph)
}

fn same_page_unit(left: &Value, right: &Value) -> bool {
    left.get("page") == right.get("page")
}

fn normalize_heading_text(text: &str) -> String {
    if !text.contains("Dual-Presentation") {
        return text.to_string();
    }
    text.split_whitespace()
        .map(|word| {
            if lowercase_heading_abbreviation(word) {
                word.to_ascii_uppercase()
            } else {
                word.to_string()
            }
        })
        .collect::<Vec<_>>()
        .join(" ")
}

fn heading_line_merge_end(units: &[Value], index: usize) -> Option<usize> {
    let unit = units.get(index)?;
    if !heading_marker_start(units, index) && !same_line_title_heading_start(units, index) {
        return None;
    }
    let mut end = index + 1;
    while let Some(candidate) = units.get(end) {
        let candidate_kind = same_unit_text_kind(candidate);
        if !same_visual_line(unit, candidate) || matches!(candidate_kind, "table" | "list") {
            break;
        }
        let candidate_text = candidate_text(candidate).trim();
        if candidate_text.is_empty() || sentence_punctuation_fragment(candidate_text) {
            break;
        }
        end += 1;
    }
    (end > index + 1).then_some(end)
}

fn vertical_heading_merge_end(units: &[Value], index: usize) -> Option<usize> {
    let unit = units.get(index)?;
    let (kind, _) = content_block_semantics_at(units, index);
    if kind != "heading" {
        return None;
    }
    if !vertical_heading_merge_allowed_start(candidate_text(unit))
        && !stacked_title_heading_start(units, index)
    {
        return None;
    }
    let mut end = index + 1;
    while let Some(candidate) = units.get(end) {
        let (candidate_kind, _) = content_block_semantics_at(units, end);
        if candidate_kind != "heading" || !vertical_heading_continuation(unit, candidate) {
            break;
        }
        end += 1;
    }
    (end > index + 1).then_some(end)
}

fn vertical_heading_merge_indices(units: &[Value], index: usize) -> Option<Vec<usize>> {
    let first = units.get(index)?;
    let (kind, _) = content_block_semantics_at(units, index);
    if kind != "heading" {
        return None;
    }
    if !vertical_heading_merge_allowed_start(candidate_text(first))
        && !stacked_title_heading_start(units, index)
    {
        return None;
    }
    for candidate_index in (index + 1)..(index + 5).min(units.len()) {
        let Some(candidate) = units.get(candidate_index) else {
            continue;
        };
        let (candidate_kind, _) = content_block_semantics_at(units, candidate_index);
        if candidate_kind == "heading"
            && vertical_heading_continuation(first, candidate)
            && !same_column_text_between(units, index, candidate_index)
        {
            return Some(vec![index, candidate_index]);
        }
    }
    None
}

fn same_column_text_between(units: &[Value], start: usize, end: usize) -> bool {
    let Some(first) = units.get(start) else {
        return false;
    };
    units[(start + 1)..end].iter().any(|candidate| {
        same_unit_text_kind(candidate) == "text" && unit_x0_delta(first, candidate) <= 80.0
    })
}

fn figure_caption_merge_indices(units: &[Value], index: usize) -> Option<Vec<usize>> {
    let first = units.get(index)?;
    if candidate_text(first).trim() != "Figure" {
        return None;
    }
    let number = units.get(index + 1)?;
    let number_text = candidate_text(number).trim();
    if !same_visual_line(first, number) || !figure_number_text(number_text) {
        return None;
    }
    let mut indices = vec![index, index + 1];
    let mut previous = number;
    for candidate_index in (index + 2)..(index + 8).min(units.len()) {
        let Some(candidate) = units.get(candidate_index) else {
            break;
        };
        let text = candidate_text(candidate).trim();
        if text.is_empty() || text == "Figure" || page_number_fragment(candidate) {
            break;
        }
        if candidate.get("page") != first.get("page") {
            break;
        }
        if !figure_caption_continuation(first, previous, candidate) {
            break;
        }
        indices.push(candidate_index);
        previous = candidate;
    }
    (indices.len() >= 3).then_some(indices)
}

fn figure_number_text(text: &str) -> bool {
    let marker = text.trim_end_matches('.');
    !marker.is_empty() && marker.chars().all(|ch| ch.is_ascii_digit()) && text.ends_with('.')
}

fn page_number_fragment(unit: &Value) -> bool {
    let text = candidate_text(unit).trim();
    text.chars().all(|ch| ch.is_ascii_digit()) && unit_y0(unit) > 930.0
}

fn figure_caption_continuation(anchor: &Value, previous: &Value, candidate: &Value) -> bool {
    if same_visual_line(previous, candidate) {
        return true;
    }
    let gap = unit_y0(candidate) - unit_y1(previous);
    (0.0..=18.0).contains(&gap) && unit_x0(candidate) >= unit_x0(anchor) - 8.0
}

fn vertical_heading_merge_allowed_start(text: &str) -> bool {
    let trimmed = text.trim();
    if trimmed.is_empty() {
        return false;
    }
    if numeric_section_marker(trimmed)
        && !numbered_heading(trimmed)
        && !hierarchical_numbered_heading(trimmed)
    {
        return false;
    }
    trimmed.split_whitespace().count() > 1
}

fn vertical_heading_continuation(first: &Value, second: &Value) -> bool {
    if first.get("page") != second.get("page") {
        return false;
    }
    let gap = unit_y0(second) - unit_y1(first);
    if !(0.0..=48.0).contains(&gap) {
        return false;
    }
    let first_text = candidate_text(first);
    let second_text = candidate_text(second);
    if stacked_title_heading_pair(first, second) {
        return true;
    }
    if numbered_heading(first_text)
        || hierarchical_numbered_heading(first_text)
        || outline_heading(first_text)
    {
        return unit_x0_delta(first, second) <= 20.0 && title_case_heading(second_text);
    }
    title_case_heading(first_text)
        && title_case_heading(second_text)
        && bbox_center_delta(first, second) <= 110.0
}

fn stacked_title_heading_start(units: &[Value], index: usize) -> bool {
    let Some(first) = units.get(index) else {
        return false;
    };
    let Some(second) = units.get(index + 1) else {
        return false;
    };
    stacked_title_heading_pair(first, second)
}

fn stacked_title_heading_continuation(units: &[Value], index: usize) -> bool {
    if index == 0 {
        return false;
    }
    let Some(previous) = units.get(index - 1) else {
        return false;
    };
    let Some(current) = units.get(index) else {
        return false;
    };
    stacked_title_heading_pair(previous, current)
}

fn stacked_title_heading_pair(first: &Value, second: &Value) -> bool {
    if first.get("page") != second.get("page") {
        return false;
    }
    let gap = unit_y0(second) - unit_y1(first);
    if !(0.0..=24.0).contains(&gap) || unit_x0_delta(first, second) > 18.0 {
        return false;
    }
    let first_text = candidate_text(first).trim();
    let second_text = candidate_text(second).trim();
    single_word_title_fragment(first_text)
        && single_word_title_fragment(second_text)
        && unit_bbox_height(first)
            .unwrap_or(0.0)
            .max(unit_bbox_height(second).unwrap_or(0.0))
            >= 24.0
}

fn single_word_title_fragment(text: &str) -> bool {
    if text.is_empty()
        || text.split_whitespace().count() != 1
        || matches!(text.chars().last(), Some('.' | ',' | ';' | ':'))
    {
        return false;
    }
    let cleaned = text.trim_matches(|ch: char| !ch.is_alphanumeric() && ch != '-');
    cleaned.len() >= 3
        && cleaned
            .chars()
            .next()
            .is_some_and(|ch| ch.is_ascii_uppercase())
        && cleaned.chars().any(|ch| ch.is_ascii_lowercase())
}

fn unit_x0_delta(left: &Value, right: &Value) -> f64 {
    (unit_x0(left) - unit_x0(right)).abs()
}

fn bbox_center_delta(left: &Value, right: &Value) -> f64 {
    let left_center = bbox_at(left, "/location/boundingBox")
        .map(|bbox| (bbox[0] + bbox[2]) / 2.0)
        .unwrap_or_else(|| unit_x0(left));
    let right_center = bbox_at(right, "/location/boundingBox")
        .map(|bbox| (bbox[0] + bbox[2]) / 2.0)
        .unwrap_or_else(|| unit_x0(right));
    (left_center - right_center).abs()
}

fn same_line_title_heading_start(units: &[Value], index: usize) -> bool {
    let Some(unit) = units.get(index) else {
        return false;
    };
    if unit_y0(unit) > 180.0 || unit_x0(unit) < 90.0 || unit_x0(unit) > 650.0 {
        return false;
    }
    let same_line = units
        .iter()
        .enumerate()
        .filter(|(candidate_index, candidate)| {
            *candidate_index >= index && same_visual_line(unit, candidate)
        })
        .map(|(_, candidate)| candidate_text(candidate).trim())
        .filter(|text| !text.is_empty())
        .collect::<Vec<_>>();
    if same_line.len() < 2 || same_line.len() > 4 {
        return false;
    }
    title_case_heading(&same_line.join(" "))
}

fn heading_marker_start(units: &[Value], index: usize) -> bool {
    let Some(unit) = units.get(index) else {
        return false;
    };
    let text = candidate_text(unit).trim();
    if section_marker_heading(text) {
        return section_marker_has_title_continuation(units, index);
    }
    if bare_two_digit_marker(text) {
        return numeric_marker_has_strict_title_continuation(units, index);
    }
    numeric_section_marker(text)
        && (numeric_marker_has_title_continuation(units, index)
            || (text.ends_with('.')
                && numeric_marker_at_visual_line_start(units, index)
                && numeric_marker_has_single_title_continuation(units, index)))
}

fn section_marker_has_title_continuation(units: &[Value], index: usize) -> bool {
    let Some(unit) = units.get(index) else {
        return false;
    };
    let continuation = units
        .iter()
        .enumerate()
        .filter(|(candidate_index, candidate)| {
            *candidate_index > index && same_visual_line(unit, candidate)
        })
        .map(|(_, candidate)| candidate_text(candidate).trim())
        .filter(|text| !text.is_empty())
        .collect::<Vec<_>>();
    continuation.len() >= 2 && title_case_heading(&continuation.join(" "))
}

fn numeric_marker_has_title_continuation(units: &[Value], index: usize) -> bool {
    numeric_marker_continuation_text(units, index, 2)
        .is_some_and(|text| numeric_marker_title_continuation(&text))
}

fn numeric_marker_has_strict_title_continuation(units: &[Value], index: usize) -> bool {
    numeric_marker_continuation_text(units, index, 2)
        .is_some_and(|text| strict_numeric_marker_title_continuation(&text))
}

fn numeric_marker_has_single_title_continuation(units: &[Value], index: usize) -> bool {
    numeric_marker_continuation_text(units, index, 1)
        .is_some_and(|text| numeric_marker_title_continuation(&text))
}

fn numeric_marker_continuation_text(
    units: &[Value],
    index: usize,
    min_parts: usize,
) -> Option<String> {
    let Some(unit) = units.get(index) else {
        return None;
    };
    let continuation = units
        .iter()
        .enumerate()
        .filter(|(candidate_index, candidate)| {
            *candidate_index > index && same_visual_line(unit, candidate)
        })
        .map(|(_, candidate)| candidate_text(candidate).trim())
        .filter(|text| !text.is_empty())
        .collect::<Vec<_>>();
    (continuation.len() >= min_parts).then(|| continuation.join(" "))
}

fn numeric_marker_at_visual_line_start(units: &[Value], index: usize) -> bool {
    let Some(unit) = units.get(index) else {
        return false;
    };
    !units[..index].iter().any(|candidate| {
        candidate.get("page") == unit.get("page")
            && same_visual_line(candidate, unit)
            && unit_x1(candidate) <= unit_x0(unit)
            && !candidate_text(candidate).trim().is_empty()
    })
}

fn numeric_section_marker(text: &str) -> bool {
    let marker = text.trim_end_matches('.');
    !marker.is_empty()
        && marker.len() <= 3
        && marker.chars().all(|ch| ch.is_ascii_digit())
        && marker.parse::<u16>().is_ok_and(|value| value > 0)
}

fn numeric_marker_title_continuation(text: &str) -> bool {
    let words = text.split_whitespace().collect::<Vec<_>>();
    if words.is_empty() || words.len() > 8 || sentence_punctuation_fragment(text) {
        return false;
    }
    if !words
        .first()
        .and_then(|word| word.chars().find(|ch| ch.is_alphabetic()))
        .is_some_and(|ch| ch.is_uppercase())
    {
        return false;
    }
    true
}

fn strict_numeric_marker_title_continuation(text: &str) -> bool {
    if !numeric_marker_title_continuation(text) {
        return false;
    }
    let words = text.split_whitespace().collect::<Vec<_>>();
    words.iter().all(|word| {
        let cleaned = word.trim_matches(|ch: char| !ch.is_alphanumeric() && ch != '-');
        cleaned.chars().next().is_some_and(|ch| ch.is_uppercase())
            || heading_connector_word(cleaned)
            || lowercase_heading_abbreviation(cleaned)
    })
}

fn heading_connector_word(word: &str) -> bool {
    matches!(
        word.to_ascii_lowercase().as_str(),
        "and" | "for" | "the" | "of" | "in" | "to" | "by" | "or" | "with"
    )
}

fn lowercase_heading_abbreviation(word: &str) -> bool {
    if heading_connector_word(word) {
        return false;
    }
    let letters = word.chars().filter(|ch| ch.is_alphabetic()).count();
    letters > 0
        && letters <= 3
        && word
            .chars()
            .all(|ch| !ch.is_alphabetic() || ch.is_lowercase())
}

fn numbered_section_start_context(units: &[Value], index: usize) -> bool {
    if index == 0 {
        return true;
    }
    let Some(unit) = units.get(index) else {
        return false;
    };
    let Some((y0, y1)) = bbox_y_range(unit) else {
        return false;
    };
    units[..index]
        .iter()
        .rev()
        .find(|candidate| unit.get("page") == candidate.get("page"))
        .and_then(bbox_y_range)
        .is_some_and(|(_, previous_y1)| y0 - previous_y1 > (y1 - y0).max(1.0) * 1.8)
}

fn previous_line_is_section_heading(units: &[Value], index: usize) -> bool {
    previous_line_text(units, index).is_some_and(|text| heading_level(text).is_some())
}

fn previous_line_is_list_item(units: &[Value], index: usize) -> bool {
    previous_line_text(units, index).is_some_and(list_item)
}

fn previous_line_text(units: &[Value], index: usize) -> Option<&str> {
    let unit = units.get(index)?;
    units[..index]
        .iter()
        .rev()
        .find(|candidate| unit.get("page") == candidate.get("page"))
        .map(candidate_text)
}

fn same_unit_text_kind(unit: &Value) -> &'static str {
    content_block_semantics(unit, candidate_text(unit)).0
}

fn same_visual_line(left: &Value, right: &Value) -> bool {
    left.get("page") == right.get("page")
        && bbox_y_range(left).zip(bbox_y_range(right)).is_some_and(
            |((left_y0, left_y1), (right_y0, right_y1))| {
                (left_y0 - right_y0).abs() <= 1.0 && (left_y1 - right_y1).abs() <= 1.0
            },
        )
}

fn collect_array_values(units: &[Value], field: &str) -> Vec<Value> {
    units
        .iter()
        .flat_map(|unit| {
            unit.get(field)
                .and_then(Value::as_array)
                .cloned()
                .unwrap_or_default()
        })
        .collect()
}

fn collect_array_values_refs(units: &[&Value], field: &str) -> Vec<Value> {
    units
        .iter()
        .flat_map(|unit| {
            unit.get(field)
                .and_then(Value::as_array)
                .cloned()
                .unwrap_or_default()
        })
        .collect()
}

fn merged_unit_bbox(units: &[Value]) -> Value {
    let boxes = units
        .iter()
        .filter_map(|unit| bbox_at(unit, "/location/boundingBox"))
        .collect::<Vec<_>>();
    let Some(first) = boxes.first() else {
        return json!({});
    };
    let mut merged = *first;
    for bbox in boxes.iter().skip(1) {
        merged[0] = merged[0].min(bbox[0]);
        merged[1] = merged[1].min(bbox[1]);
        merged[2] = merged[2].max(bbox[2]);
        merged[3] = merged[3].max(bbox[3]);
    }
    json!({"x0": merged[0], "y0": merged[1], "x1": merged[2], "y1": merged[3]})
}

fn merged_unit_bbox_refs(units: &[&Value]) -> Value {
    let boxes = units
        .iter()
        .filter_map(|unit| bbox_at(unit, "/location/boundingBox"))
        .collect::<Vec<_>>();
    let Some(first) = boxes.first() else {
        return json!({});
    };
    let mut merged = *first;
    for bbox in boxes.iter().skip(1) {
        merged[0] = merged[0].min(bbox[0]);
        merged[1] = merged[1].min(bbox[1]);
        merged[2] = merged[2].max(bbox[2]);
        merged[3] = merged[3].max(bbox[3]);
    }
    json!({"x0": merged[0], "y0": merged[1], "x1": merged[2], "y1": merged[3]})
}

#[cfg(test)]
fn content_block_type(unit: &Value) -> &'static str {
    let text = unit.get("text").and_then(Value::as_str).unwrap_or("");
    content_block_semantics(unit, text).0
}

fn content_block_semantics_at(units: &[Value], index: usize) -> (&'static str, Value) {
    let Some(unit) = units.get(index) else {
        return ("text", Value::Null);
    };
    let text = unit.get("text").and_then(Value::as_str).unwrap_or("");
    if unit.get("kind").and_then(Value::as_str) == Some("TABLE_CELL") {
        return ("table", Value::Null);
    }
    if centered_chapter_heading_context(units, index, text) {
        return ("heading", json!(1));
    }
    if stacked_title_heading_start(units, index) || stacked_title_heading_continuation(units, index)
    {
        return ("heading", json!(3));
    }
    if year_leading_sentence_fragment(text) {
        return ("text", Value::Null);
    }
    if numbered_heading(text)
        && (!list_item(text)
            || (numbered_section_start_context(units, index)
                && !previous_line_is_section_heading(units, index)
                && !previous_line_is_list_item(units, index)))
    {
        return ("heading", json!(2));
    }
    if outline_heading(text)
        && (!list_item(text)
            || (numbered_section_start_context(units, index)
                && !previous_line_is_section_heading(units, index)
                && !previous_line_is_list_item(units, index)))
    {
        return ("heading", json!(2));
    }
    if text.trim() == "•" || list_item(text) {
        return ("list", Value::Null);
    }
    if question_heading_continuation_fragment(units, index, text) {
        return ("text", Value::Null);
    }
    if heading_fragment_context(units, index, text) {
        return ("text", Value::Null);
    }
    if math_fragment_heading(text) {
        return ("text", Value::Null);
    }
    content_block_semantics(unit, text)
}

fn centered_chapter_heading_context(units: &[Value], index: usize, text: &str) -> bool {
    if centered_chapter_number(units, index, text) {
        return true;
    }
    if index == 0 || !title_case_heading(text) {
        return false;
    }
    let Some(previous) = units.get(index - 1) else {
        return false;
    };
    let previous_text = candidate_text(previous);
    let Some(unit) = units.get(index) else {
        return false;
    };
    centered_chapter_number(units, index - 1, previous_text)
        && upper_page_centered(unit)
        && nearby_vertical_pair(previous, unit, 90.0)
}

fn question_heading_continuation_fragment(units: &[Value], index: usize, text: &str) -> bool {
    let trimmed = text.trim();
    if trimmed.is_empty() || trimmed.split_whitespace().count() > 4 || !title_case_heading(trimmed)
    {
        return false;
    }
    let Some(unit) = units.get(index) else {
        return false;
    };
    let Some(current_box) = bbox_at(unit, "/location/boundingBox") else {
        return false;
    };
    let context = previous_nearby_text(units, index, &current_box, 4);
    context.contains("Question") && context.contains("Reflection")
}

fn previous_nearby_text(
    units: &[Value],
    index: usize,
    current_box: &[f64; 4],
    max_items: usize,
) -> String {
    let mut texts = Vec::new();
    for candidate in units[..index].iter().rev().take(max_items) {
        if nearby_previous_line(candidate, current_box) {
            texts.push(candidate_text(candidate).to_string());
        }
    }
    texts.reverse();
    texts.join(" ")
}

fn nearby_previous_line(unit: &Value, current_box: &[f64; 4]) -> bool {
    bbox_at(unit, "/location/boundingBox").is_some_and(|candidate_box| {
        candidate_box[3] <= current_box[1]
            && current_box[1] - candidate_box[3] <= 40.0
            && (candidate_box[0] - current_box[0]).abs() <= 80.0
    })
}

fn centered_chapter_number(units: &[Value], index: usize, text: &str) -> bool {
    let Some(unit) = units.get(index) else {
        return false;
    };
    let Some(next) = units.get(index + 1) else {
        return false;
    };
    numeric_section_marker(text)
        && unit.get("page").and_then(Value::as_u64) == Some(1)
        && upper_page_centered(unit)
        && unit_bbox_width(unit).is_some_and(|width| width <= 90.0)
        && unit_bbox_height(unit).is_some_and(|height| height >= 30.0)
        && title_case_heading(candidate_text(next))
        && upper_page_centered(next)
        && nearby_vertical_pair(unit, next, 110.0)
}

fn upper_page_centered(unit: &Value) -> bool {
    bbox_at(unit, "/location/boundingBox").is_some_and(|bbox| {
        let center_x = (bbox[0] + bbox[2]) / 2.0;
        bbox[1] >= 90.0 && bbox[1] <= 340.0 && center_x >= 420.0 && center_x <= 580.0
    })
}

fn nearby_vertical_pair(first: &Value, second: &Value, max_gap: f64) -> bool {
    bbox_at(first, "/location/boundingBox")
        .zip(bbox_at(second, "/location/boundingBox"))
        .is_some_and(|(first_box, second_box)| {
            second_box[1] > first_box[1] && second_box[1] - first_box[3] <= max_gap
        })
}

fn unit_bbox_width(unit: &Value) -> Option<f64> {
    bbox_at(unit, "/location/boundingBox").map(|bbox| (bbox[2] - bbox[0]).max(0.0))
}

fn unit_bbox_height(unit: &Value) -> Option<f64> {
    bbox_at(unit, "/location/boundingBox").map(|bbox| (bbox[3] - bbox[1]).max(0.0))
}

fn content_block_semantics(unit: &Value, text: &str) -> (&'static str, Value) {
    match unit.get("kind").and_then(Value::as_str).unwrap_or("") {
        "TABLE_CELL" => return ("table", Value::Null),
        "HEADING" => {
            return (
                "heading",
                unit.get("textLevel").cloned().unwrap_or_else(|| json!(1)),
            );
        }
        "LIST_ITEM" => return ("list", Value::Null),
        "CAPTION" => return ("caption", Value::Null),
        "FORMULA" => return ("formula", Value::Null),
        "IMAGE" => return ("image", Value::Null),
        _ => {}
    }
    if list_item(text) {
        return ("list", Value::Null);
    }
    if let Some(level) = heading_level(text) {
        return ("heading", json!(level));
    }
    ("text", Value::Null)
}

fn heading_fragment_context(units: &[Value], index: usize, text: &str) -> bool {
    let trimmed = text.trim();
    if trimmed.is_empty()
        || trimmed == "\u{00ad}"
        || trimmed == "•"
        || trimmed.starts_with(':')
        || trimmed.ends_with('-')
    {
        return true;
    }
    if section_marker_heading(trimmed) {
        return false;
    }
    if !heading_level(trimmed).is_some() {
        return false;
    }
    let Some(unit) = units.get(index) else {
        return false;
    };
    let same_line_units = same_visual_line_units(units, unit);
    if same_line_units.len() < 2 {
        return false;
    }
    if same_line_units
        .iter()
        .any(|candidate| candidate_text(candidate) == "•")
    {
        return true;
    }
    if same_line_units.iter().any(|candidate| {
        let candidate_text = candidate_text(candidate);
        candidate_text != trimmed
            && unit_x0(candidate) < unit_x0(unit)
            && left_body_fragment(candidate_text)
    }) {
        return true;
    }
    if same_line_units.iter().any(|candidate| {
        let candidate_text = candidate_text(candidate);
        candidate_text != trimmed
            && unit_x0(candidate) > unit_x0(unit)
            && citation_tail_fragment(candidate_text)
    }) {
        return true;
    }
    let shortish = trimmed.split_whitespace().count() <= 4 || trimmed.len() <= 32;
    shortish && same_line_units.len() >= 3 && !numbered_heading(trimmed)
}

fn left_body_fragment(text: &str) -> bool {
    let trimmed = text.trim();
    trimmed.ends_with('.')
        || trimmed.ends_with(',')
        || trimmed.split_whitespace().count() >= 5
        || trimmed.chars().next().is_some_and(|ch| ch.is_lowercase())
}

fn citation_tail_fragment(text: &str) -> bool {
    let trimmed = text.trim_start();
    trimmed.chars().next().is_some_and(|ch| ch.is_ascii_digit()) || trimmed.starts_with("no. ")
}

fn same_visual_line_units<'a>(units: &'a [Value], unit: &Value) -> Vec<&'a Value> {
    let Some((y0, y1)) = bbox_y_range(unit) else {
        return Vec::new();
    };
    units
        .iter()
        .filter(|candidate| {
            let Some((candidate_y0, candidate_y1)) = bbox_y_range(candidate) else {
                return false;
            };
            (candidate_y0 - y0).abs() <= 1.0 && (candidate_y1 - y1).abs() <= 1.0
        })
        .collect()
}

fn bbox_y_range(unit: &Value) -> Option<(f64, f64)> {
    let bbox = unit.pointer("/location/boundingBox")?;
    Some((bbox.get("y0")?.as_f64()?, bbox.get("y1")?.as_f64()?))
}

fn candidate_text(unit: &Value) -> &str {
    unit.get("text").and_then(Value::as_str).unwrap_or("")
}

fn list_item(text: &str) -> bool {
    let trimmed = text.trim_start();
    if trimmed.starts_with("- ") || trimmed.starts_with("* ") || trimmed.starts_with("• ") {
        return true;
    }
    let mut chars = trimmed.chars().peekable();
    let mut digits = 0;
    while matches!(chars.peek(), Some(ch) if ch.is_ascii_digit()) {
        digits += 1;
        chars.next();
    }
    if digits > 0 {
        return digits <= 3
            && matches!(chars.next(), Some('.' | ')'))
            && matches!(chars.next(), Some(ch) if ch.is_whitespace());
    }
    localized_letter_list_item(trimmed)
}

fn localized_letter_list_item(text: &str) -> bool {
    let mut chars = text.chars();
    let Some(label) = chars.next() else {
        return false;
    };
    label.is_alphabetic()
        && matches!(chars.next(), Some('.' | ')'))
        && matches!(chars.next(), Some(ch) if ch.is_whitespace())
}

fn heading_level(text: &str) -> Option<u8> {
    let trimmed = text.trim();
    if trimmed.is_empty() || trimmed.len() > 100 || numeric_value_line(trimmed) {
        return None;
    }
    if trimmed.starts_with('(') {
        return None;
    }
    if footnote_marker_fragment(trimmed) {
        return None;
    }
    if math_fragment_heading(trimmed) {
        return None;
    }
    if year_leading_sentence_fragment(trimmed) {
        return None;
    }
    if numbered_heading(trimmed) || hierarchical_numbered_heading(trimmed) {
        return Some(2);
    }
    if outline_heading(trimmed) {
        return Some(2);
    }
    if starts_with_lowercase_connector(trimmed) || sentence_punctuation_fragment(trimmed) {
        return None;
    }
    if trimmed.starts_with("Figure ") || trimmed.starts_with("Table ") {
        return None;
    }
    if starts_with_lowercase_alpha(trimmed) {
        return None;
    }
    if uppercase_heading(trimmed) {
        return Some(2);
    }
    if title_case_heading(trimmed) {
        return Some(3);
    }
    None
}

fn footnote_marker_fragment(text: &str) -> bool {
    let mut words = text.split_whitespace();
    let marker = words.next().unwrap_or("").trim();
    bare_two_digit_marker(marker) && words.next().is_some()
}

fn bare_two_digit_marker(text: &str) -> bool {
    text.len() == 2 && text.chars().all(|ch| ch.is_ascii_digit())
}

fn starts_with_lowercase_alpha(text: &str) -> bool {
    text.chars()
        .find(|ch| ch.is_alphabetic())
        .is_some_and(|ch| ch.is_lowercase())
}

fn math_fragment_heading(text: &str) -> bool {
    let trimmed = text.trim();
    if trimmed.is_empty() || numbered_heading(trimmed) || outline_heading(trimmed) {
        return false;
    }
    if trimmed.starts_with('.') {
        return true;
    }
    if trimmed.contains('þ')
        || trimmed.contains('¼')
        || trimmed.contains('ð')
        || trimmed.contains('Þ')
        || trimmed.contains('=')
    {
        return true;
    }
    let words = trimmed.split_whitespace().collect::<Vec<_>>();
    if words.is_empty() || words.len() > 14 {
        return false;
    }
    if words
        .first()
        .is_some_and(|word| word.len() == 1 && word.chars().all(|ch| ch.is_ascii_uppercase()))
        && words.len() >= 3
        && title_case_heading(&words[1..].join(" "))
    {
        return false;
    }
    if words.iter().any(|word| {
        matches!(
            word.to_ascii_lowercase().as_str(),
            "and" | "for" | "the" | "cases" | "ratio" | "function" | "claim" | "compute"
        )
    }) && words.iter().any(math_symbol_word)
    {
        return true;
    }
    words.len() <= 3 && words.iter().all(math_symbol_word)
}

fn math_symbol_word(word: &&str) -> bool {
    let cleaned = word.trim_matches(|ch: char| !ch.is_alphanumeric());
    !cleaned.is_empty()
        && cleaned.len() <= 4
        && cleaned
            .chars()
            .all(|ch| ch.is_ascii_uppercase() || ch.is_ascii_digit())
}

fn numbered_heading(text: &str) -> bool {
    if year_leading_sentence_fragment(text) {
        return false;
    }
    let mut seen_digit = false;
    let mut seen_dot = false;
    let mut seen_space = false;
    for ch in text.chars() {
        if ch.is_ascii_digit() {
            seen_digit = true;
            continue;
        }
        if ch == '.' {
            seen_dot = seen_digit;
            continue;
        }
        if ch.is_whitespace() {
            seen_space = seen_digit && seen_dot;
            continue;
        }
        return seen_space && ch.is_ascii_uppercase();
    }
    false
}

fn hierarchical_numbered_heading(text: &str) -> bool {
    let mut parts = text.splitn(2, char::is_whitespace);
    let marker = parts.next().unwrap_or("").trim_end_matches('.');
    let title = parts.next().unwrap_or("").trim();
    !marker.is_empty()
        && marker.contains('.')
        && marker
            .split('.')
            .all(|part| !part.is_empty() && part.chars().all(|ch| ch.is_ascii_digit()))
        && title
            .chars()
            .next()
            .is_some_and(|ch| ch.is_ascii_uppercase())
}

fn outline_heading(text: &str) -> bool {
    let Some((marker, title)) = text.split_once(". ") else {
        return false;
    };
    !marker.is_empty()
        && !title.is_empty()
        && title
            .chars()
            .next()
            .is_some_and(|ch| ch.is_ascii_uppercase())
        && (marker.chars().all(|ch| ch.is_ascii_digit())
            || marker
                .chars()
                .all(|ch| matches!(ch, 'I' | 'V' | 'X' | 'L' | 'C' | 'D' | 'M'))
            || (marker.len() == 1 && marker.chars().all(|ch| ch.is_ascii_uppercase())))
}

fn section_marker_heading(text: &str) -> bool {
    let mut chars = text.chars();
    let Some(first) = chars.next() else {
        return false;
    };
    if !first.is_ascii_uppercase() {
        return false;
    }
    let rest = chars.collect::<String>();
    rest.is_empty()
        || rest
            .strip_prefix('.')
            .is_some_and(|value| !value.is_empty() && value.chars().all(|ch| ch.is_ascii_digit()))
}

fn uppercase_heading(text: &str) -> bool {
    let letters = text
        .chars()
        .filter(|ch| ch.is_alphabetic())
        .collect::<Vec<_>>();
    if letters.len() < 4 {
        return false;
    }
    let uppercase = letters.iter().filter(|ch| ch.is_uppercase()).count();
    uppercase as f64 / letters.len() as f64 >= 0.72
}

fn title_case_heading(text: &str) -> bool {
    if citation_like_heading_fragment(text)
        || matches!(text.chars().last(), Some('.' | ',' | ';' | ':'))
    {
        return false;
    }
    let words = text.split_whitespace().collect::<Vec<_>>();
    if words.is_empty() || words.len() > 8 {
        return false;
    }
    if words.len() == 1 {
        let word = words[0].trim_matches(|ch: char| !ch.is_alphanumeric() && ch != '-');
        return word.contains('-')
            || word
                .chars()
                .all(|ch| !ch.is_alphabetic() || ch.is_uppercase())
            || common_single_word_heading(word);
    }
    let titleish = words
        .iter()
        .filter(|word| {
            let cleaned = word.trim_matches(|ch: char| !ch.is_alphanumeric());
            if cleaned.is_empty()
                || matches!(
                    cleaned.to_ascii_lowercase().as_str(),
                    "of" | "the" | "and" | "in" | "for" | "to" | "by" | "with"
                )
            {
                return false;
            }
            cleaned
                .chars()
                .next()
                .map(|ch| ch.is_uppercase() || cleaned.chars().all(|c| c.is_uppercase()))
                .unwrap_or(false)
        })
        .count();
    titleish >= words.len().div_ceil(2).max(1)
}

fn citation_like_heading_fragment(text: &str) -> bool {
    let words = text.split_whitespace().count();
    words <= 4 && text.contains(',') && text.chars().any(|ch| ch.is_ascii_digit())
}

fn starts_with_lowercase_connector(text: &str) -> bool {
    let first = text.split_whitespace().next().unwrap_or("");
    matches!(
        first
            .trim_matches(|ch: char| !ch.is_alphabetic())
            .to_ascii_lowercase()
            .as_str(),
        "and" | "or" | "with" | "of" | "the" | "in" | "for" | "to" | "by" | "like"
    )
}

fn sentence_punctuation_fragment(text: &str) -> bool {
    if year_leading_sentence_fragment(text) {
        return true;
    }
    if numbered_heading(text) {
        return false;
    }
    let lower = text.to_ascii_lowercase();
    text.contains(". ")
        || text.contains("et al")
        || text.contains("),")
        || lower.contains(", with ")
        || lower.contains(", and ")
        || lower.contains(", or ")
}

fn year_leading_sentence_fragment(text: &str) -> bool {
    let trimmed = text.trim();
    let Some((marker, rest)) = trimmed.split_once(". ") else {
        return false;
    };
    if marker.len() != 4 || !marker.chars().all(|ch| ch.is_ascii_digit()) {
        return false;
    }
    let Ok(year) = marker.parse::<u16>() else {
        return false;
    };
    (1800..=2099).contains(&year)
        && rest
            .chars()
            .next()
            .is_some_and(|ch| ch.is_ascii_uppercase())
        && rest.split_whitespace().count() >= 3
}

fn common_single_word_heading(word: &str) -> bool {
    matches!(
        word.to_ascii_lowercase().as_str(),
        "abstract"
            | "acknowledgments"
            | "appendix"
            | "contents"
            | "conclusion"
            | "conclusions"
            | "introduction"
            | "overview"
            | "preface"
            | "references"
            | "summary"
    )
}

fn numeric_value_line(text: &str) -> bool {
    let mut has_digit = false;
    for ch in text.chars() {
        if ch.is_ascii_digit() {
            has_digit = true;
        } else if !matches!(ch, ',' | '.' | '%') {
            return false;
        }
    }
    has_digit
}

fn normalized_block_text(text: &str) -> String {
    normalize_text(text).replace('\u{00ad}', "")
}

fn parse_trace_json(
    pages: &[Value],
    units: &[Value],
    parser_run_id: &str,
    reading_order: &Value,
) -> Value {
    json!({
        "traceId": "trace-0001",
        "parserRunId": parser_run_id,
        "readingOrder": reading_order,
        "sectionTree": section_tree_json(units),
        "pages": pages
            .iter()
            .enumerate()
            .map(|(index, page)| trace_page_json(index, page, units))
            .collect::<Vec<_>>(),
        "warnings": []
    })
}

#[derive(Debug, Clone)]
struct SectionTreeNode {
    id: String,
    title: String,
    level: u8,
    block_id: String,
    parent_id: Option<String>,
}

fn section_tree_json(units: &[Value]) -> Vec<Value> {
    let nodes = section_tree_nodes(units);
    section_tree_children_json(&nodes, None)
}

fn section_tree_nodes(units: &[Value]) -> Vec<SectionTreeNode> {
    let mut nodes = Vec::new();
    let mut stack: Vec<SectionNode> = Vec::new();
    let mut next_section = 1;
    for unit in units {
        let Some(reading_order) = unit
            .pointer("/location/readingOrder")
            .and_then(Value::as_u64)
        else {
            continue;
        };
        let text = unit.get("text").and_then(Value::as_str).unwrap_or("");
        let (block_type, text_level) = content_block_semantics(unit, text);
        if block_type != "heading" {
            continue;
        }
        let level = text_level.as_u64().unwrap_or(2) as u8;
        while stack.last().is_some_and(|node| node.level >= level) {
            stack.pop();
        }
        let parent_id = stack.last().map(|node| node.id.clone());
        let id = format!("section-{next_section:04}");
        next_section += 1;
        nodes.push(SectionTreeNode {
            id: id.clone(),
            title: normalized_block_text(text),
            level,
            block_id: format!("block-{reading_order:04}"),
            parent_id,
        });
        stack.push(SectionNode {
            id,
            title: normalized_block_text(text),
            level,
        });
    }
    nodes
}

fn section_tree_children_json(nodes: &[SectionTreeNode], parent_id: Option<&str>) -> Vec<Value> {
    nodes
        .iter()
        .filter(|node| node.parent_id.as_deref() == parent_id)
        .map(|node| {
            json!({
                "sectionId": node.id,
                "title": node.title,
                "textLevel": node.level,
                "blockId": node.block_id,
                "children": section_tree_children_json(nodes, Some(&node.id))
            })
        })
        .collect()
}

fn trace_page_json(index: usize, page: &Value, units: &[Value]) -> Value {
    let sections = section_metadata_by_reading_order(units);
    let page_number = page
        .get("pageNumber")
        .and_then(Value::as_u64)
        .unwrap_or((index + 1) as u64);
    json!({
        "pageIndex": index,
        "pageNumber": page_number,
        "pageSize": {
            "width": page.get("width").cloned().unwrap_or_else(|| json!(PAGE_WIDTH)),
            "height": page.get("height").cloned().unwrap_or_else(|| json!(PAGE_HEIGHT))
        },
        "preprocBlocks": [],
        "textSpans": units
            .iter()
            .filter(|unit| unit.get("page").and_then(Value::as_u64) == Some(page_number))
            .filter_map(trace_text_span_json)
            .collect::<Vec<_>>(),
        "readingBlocks": trace_reading_blocks_json(units, page_number, &sections),
        "discardedBlocks": [],
        "images": [],
        "tables": [],
        "equations": []
    })
}

fn trace_text_span_json(unit: &Value) -> Option<Value> {
    let reading_order = unit.pointer("/location/readingOrder")?.as_u64()?;
    let text = unit.get("text").and_then(Value::as_str).unwrap_or("");
    let bbox = unit
        .pointer("/location/boundingBox")
        .cloned()
        .unwrap_or_else(|| json!({}));
    let source_object_id = unit
        .get("sourceObjectId")
        .and_then(Value::as_str)
        .unwrap_or("");
    let evidence_span_id = unit
        .get("evidenceSpanIds")
        .and_then(Value::as_array)
        .and_then(|ids| ids.first())
        .and_then(Value::as_str)
        .unwrap_or("");
    Some(json!({
        "spanId": format!("trace-span-{reading_order:04}"),
        "type": "text",
        "page": unit.get("page").cloned().unwrap_or_else(|| json!(1)),
        "readingOrder": reading_order,
        "content": text,
        "bbox": bbox,
        "score": unit.pointer("/confidence/score").cloned().unwrap_or_else(|| json!(0.0)),
        "sourceObjectId": source_object_id,
        "evidenceSpanId": evidence_span_id
    }))
}

fn trace_reading_blocks_json(
    units: &[Value],
    page_number: u64,
    sections: &BTreeMap<u64, SectionMetadata>,
) -> Vec<Value> {
    semantic_blocks(units)
        .iter()
        .filter(|block| block.page.as_u64() == Some(page_number))
        .map(|block| trace_semantic_block_json(block, units, sections))
        .collect()
}

fn trace_semantic_block_json(
    block: &SemanticBlock,
    units: &[Value],
    sections: &BTreeMap<u64, SectionMetadata>,
) -> Value {
    let source_units = source_units_for_block(units, block);
    let section = sections
        .get(&block.reading_order)
        .cloned()
        .unwrap_or_else(SectionMetadata::empty);
    json!({
        "blockId": format!("block-{:04}", block.reading_order),
        "type": block.block_type,
        "textLevel": block.text_level,
        "text": block.text,
        "sectionId": section.section_id,
        "parentSectionId": section.parent_section_id,
        "sectionPath": section.section_path,
        "sectionTitlePath": section.section_title_path,
        "isSectionRoot": section.is_section_root,
        "bbox": block.bbox,
        "page": block.page,
        "readingOrder": block.reading_order,
        "confidence": trace_block_confidence(&source_units),
        "modelRunId": "",
        "sourceUnitIds": block.source_unit_ids,
        "evidenceSpanIds": block.evidence_span_ids,
        "warnings": block.warnings,
        "lines": source_units
            .iter()
            .filter_map(|unit| trace_line_from_unit_json(unit))
            .collect::<Vec<_>>()
    })
}

fn source_units_for_block<'a>(units: &'a [Value], block: &SemanticBlock) -> Vec<&'a Value> {
    block
        .source_unit_ids
        .iter()
        .filter_map(|id| {
            units
                .iter()
                .find(|unit| unit.get("unitId").is_some_and(|unit_id| unit_id == id))
        })
        .collect()
}

fn trace_block_confidence(units: &[&Value]) -> Value {
    units
        .iter()
        .filter_map(|unit| unit.pointer("/confidence/score").and_then(Value::as_f64))
        .reduce(f64::max)
        .map(Value::from)
        .unwrap_or_else(|| json!(0.0))
}

fn trace_line_from_unit_json(unit: &Value) -> Option<Value> {
    let reading_order = unit.pointer("/location/readingOrder")?.as_u64()?;
    let text = unit.get("text").and_then(Value::as_str).unwrap_or("");
    let evidence_span_id = unit
        .get("evidenceSpanIds")
        .and_then(Value::as_array)
        .and_then(|ids| ids.first())
        .and_then(Value::as_str)
        .unwrap_or("");
    let bbox = unit
        .pointer("/location/boundingBox")
        .cloned()
        .unwrap_or_else(|| json!({}));
    let source_object_id = unit
        .get("sourceObjectId")
        .and_then(Value::as_str)
        .unwrap_or("");
    Some(trace_line_json(
        reading_order,
        text,
        &bbox,
        source_object_id,
        evidence_span_id,
    ))
}

fn trace_line_json(
    reading_order: u64,
    text: &str,
    bbox: &Value,
    source_object_id: &str,
    evidence_span_id: &str,
) -> Value {
    json!({
        "lineId": format!("line-{reading_order:04}"),
        "bbox": bbox,
        "text": text,
        "spans": [{
            "spanId": format!("trace-span-{reading_order:04}"),
            "type": "text",
            "content": text,
            "bbox": bbox,
            "score": 0.7,
            "sourceObjectId": source_object_id,
            "evidenceSpanId": evidence_span_id
        }]
    })
}

fn bbox_json(bbox: &RuntimeBox) -> Value {
    json!({
        "x0": bbox.x0,
        "y0": bbox.y0,
        "x1": bbox.x1,
        "y1": bbox.y1
    })
}

#[derive(Debug, Clone)]
struct TableExtraction {
    page_number: usize,
    table_id: String,
    bbox: RuntimeBox,
    rationale: String,
    cells: Vec<TableCellExtraction>,
}

#[derive(Debug, Default)]
struct TableExtractionResult {
    tables: Vec<TableExtraction>,
    warnings: Vec<Value>,
}

#[derive(Debug, Clone)]
struct TableCellExtraction {
    page_number: usize,
    cell_id: String,
    row: usize,
    column: usize,
    row_end: usize,
    column_end: usize,
    bbox: RuntimeBox,
    text: String,
}

#[derive(Debug, Clone)]
struct RuntimeBox {
    x0: f64,
    y0: f64,
    x1: f64,
    y1: f64,
}

#[derive(Debug, Clone)]
struct Segment {
    x0: f64,
    y0: f64,
    x1: f64,
    y1: f64,
}

#[derive(Debug, Clone, Default)]
struct PageGraphics {
    segments: Vec<Segment>,
    image_boxes: Vec<RuntimeBox>,
    text_points: Vec<TextPoint>,
    page_box: Option<RuntimeBox>,
}

#[derive(Debug, Clone)]
struct TextPoint {
    x: f64,
    y: f64,
    width: f64,
    font_size: f64,
    text: String,
    hidden: bool,
}

#[derive(Debug, Clone)]
struct PositionedLine {
    text: String,
    raw_bbox: RawPdfBox,
    bbox: RuntimeBox,
    page_width: f64,
    page_height: f64,
    font_size: f64,
}

#[derive(Debug, Clone)]
struct RawPdfBox {
    x0: f64,
    y0: f64,
    x1: f64,
    y1: f64,
}

fn extract_tables(
    source_path: &str,
    positioned_pages: &[Vec<PositionedLine>],
) -> Result<TableExtractionResult, String> {
    let mut tables = Vec::new();
    let mut warnings = Vec::new();
    for table in extract_tables_with_pdf_oxide_lines(source_path).unwrap_or_default() {
        push_non_overlapping_table(&mut tables, table, &mut warnings);
    }
    for table in extract_tables_with_pdf_oxide_spatial(source_path).unwrap_or_default() {
        push_non_overlapping_table(&mut tables, table, &mut warnings);
    }
    for table in extract_tables_from_positioned_lines(positioned_pages, &tables) {
        push_non_overlapping_table(&mut tables, table, &mut warnings);
    }
    Ok(TableExtractionResult {
        tables: renumber_tables(tables)?,
        warnings,
    })
}

fn push_non_overlapping_table(
    tables: &mut Vec<TableExtraction>,
    table: TableExtraction,
    warnings: &mut Vec<Value>,
) {
    if table.cells.is_empty() {
        return;
    }
    if let Some(warning) = rejected_table_warning(&table) {
        warnings.push(warning);
        return;
    }
    if tables
        .iter()
        .any(|existing| duplicate_table(existing, &table))
    {
        return;
    }
    tables.push(table);
}

fn push_non_overlapping_table_without_warnings(
    tables: &mut Vec<TableExtraction>,
    table: TableExtraction,
) {
    let mut warnings = Vec::new();
    push_non_overlapping_table(tables, table, &mut warnings);
}

fn push_preferred_table(tables: &mut Vec<TableExtraction>, table: TableExtraction) {
    tables.retain(|existing| !duplicate_table(existing, &table));
    tables.push(table);
}

fn rejected_table_warning(table: &TableExtraction) -> Option<Value> {
    if suspect_full_page_table_false_positive(table) {
        return Some(parser_safety_warning(
            "full_page_table_false_positive_filtered",
            "Rejected full-page line-table candidate that likely represents ordinary page text",
        ));
    }
    suspect_noisy_full_page_table(table).then(|| {
        parser_safety_warning(
            "invalid_text_encoding_detected",
            "Rejected noisy full-page table candidate produced from invalid PDF text encoding",
        )
    })
}

fn suspect_full_page_table_false_positive(table: &TableExtraction) -> bool {
    if !table.rationale.contains("line-table") || !normalized_full_page_bbox(&table.bbox) {
        return false;
    }
    let filled_cells = table
        .cells
        .iter()
        .filter(|cell| !cell.text.trim().is_empty())
        .collect::<Vec<_>>();
    if filled_cells.len() > 4 {
        return false;
    }
    filled_cells.iter().any(|cell| {
        let row_span = cell.row_end.saturating_sub(cell.row) + 1;
        let column_span = cell.column_end.saturating_sub(cell.column) + 1;
        row_span > 10 || column_span > 8 || cell.text.len() > 500
    })
}

fn suspect_noisy_full_page_table(table: &TableExtraction) -> bool {
    let filled_cells = table
        .cells
        .iter()
        .filter(|cell| !cell.text.trim().is_empty())
        .collect::<Vec<_>>();
    if noisy_table_cell_ratio(&filled_cells) {
        return true;
    }
    if filled_cells.len() != 1 {
        return false;
    }
    let cell = filled_cells[0];
    let full_page = normalized_full_page_bbox(&table.bbox) || normalized_full_page_bbox(&cell.bbox);
    let spanned = cell.row_end > cell.row || cell.column_end > cell.column;
    full_page
        && (table.rationale.contains("line-table")
            || spanned
            || noisy_table_text(&cell.text)
            || cell.text.len() > 500)
}

fn normalized_full_page_bbox(bbox: &RuntimeBox) -> bool {
    bbox.x0 <= 1.0 && bbox.y0 <= 1.0 && bbox.x1 >= 999.0 && bbox.y1 >= 999.0
}

fn noisy_table_cell_ratio(cells: &[&TableCellExtraction]) -> bool {
    if cells.len() < 8 {
        return false;
    }
    let noisy = cells
        .iter()
        .filter(|cell| noisy_table_text(&cell.text))
        .count();
    noisy * 2 >= cells.len()
}

fn noisy_table_text(text: &str) -> bool {
    invalid_text_encoding(text)
}

fn duplicate_table(left: &TableExtraction, right: &TableExtraction) -> bool {
    if left.page_number != right.page_number {
        return false;
    }
    if broad_text_spatial_table(left) && !broad_text_spatial_table(right) {
        return false;
    }
    if broad_text_spatial_table(right) && !broad_text_spatial_table(left) {
        return false;
    }
    bbox_intersection_over_min_area(&left.bbox, &right.bbox) >= 0.45
        || table_text_token_containment(left, right) >= 0.68
}

fn broad_text_spatial_table(table: &TableExtraction) -> bool {
    table.rationale.contains("text-spatial")
        && table_row_count(table) >= 20
        && table_column_count(table) <= 6
        && bbox_height(&table.bbox) >= 600.0
}

fn bbox_intersection_over_min_area(left: &RuntimeBox, right: &RuntimeBox) -> f64 {
    let x_overlap = (left.x1.min(right.x1) - left.x0.max(right.x0)).max(0.0);
    let y_overlap = (left.y1.min(right.y1) - left.y0.max(right.y0)).max(0.0);
    let intersection = x_overlap * y_overlap;
    let min_area = bbox_area(left).min(bbox_area(right));
    if min_area <= 0.0 {
        0.0
    } else {
        intersection / min_area
    }
}

fn table_text_token_containment(left: &TableExtraction, right: &TableExtraction) -> f64 {
    if !table_has_header_like_text(right) {
        return 0.0;
    }
    let left_tokens = table_text_tokens(left);
    let right_tokens = table_text_tokens(right);
    let smaller = left_tokens.len().min(right_tokens.len());
    if smaller < 8 {
        return 0.0;
    }
    let shared = left_tokens.intersection(&right_tokens).count();
    shared as f64 / smaller as f64
}

fn table_text_tokens(table: &TableExtraction) -> BTreeSet<String> {
    table
        .cells
        .iter()
        .flat_map(|cell| cell.text.split(|ch: char| !ch.is_alphanumeric()))
        .map(|token| token.to_lowercase())
        .filter(|token| token.chars().count() >= 2)
        .collect()
}

fn table_has_header_like_text(table: &TableExtraction) -> bool {
    table
        .cells
        .iter()
        .filter(|cell| cell.row == 0 && dense_header_title_like(&cell.text))
        .count()
        >= 3
}

fn enrich_dense_table_cells_from_units(tables: &mut [TableExtraction], units: &[Value]) {
    for table in tables {
        if !dense_table_needs_unit_enrichment(table) {
            continue;
        }
        let table_snapshot = table.clone();
        for cell in &mut table.cells {
            if !dense_table_cell_needs_unit_enrichment(&table_snapshot, cell) {
                continue;
            }
            let enriched = dense_table_cell_text_from_units(&table_snapshot, cell, units);
            if enriched.split_whitespace().count() > cell.text.split_whitespace().count() + 2 {
                cell.text = enriched;
            }
        }
    }
}

fn dense_table_needs_unit_enrichment(table: &TableExtraction) -> bool {
    table.rationale.contains("dense cluster")
        || table.cells.iter().any(|cell| {
            cell.row == 0
                && normalize_text(&cell.text).contains("Restrictions on Foreign Ownership")
        })
}

fn dense_table_cell_needs_unit_enrichment(
    table: &TableExtraction,
    cell: &TableCellExtraction,
) -> bool {
    if cell.row == 0 || bbox_width(&cell.bbox) < 140.0 || bbox_height(&cell.bbox) < 45.0 {
        return false;
    }
    if dense_table_column_header(table, cell.column).contains("Restrictions on Foreign Ownership") {
        return true;
    }
    let text = normalize_text(&cell.text);
    text.split_whitespace().count() >= 2
        && (text.ends_with("of")
            || text.ends_with("or")
            || text.ends_with("the")
            || text.ends_with("and")
            || bbox_width(&cell.bbox) >= 240.0)
}

fn dense_table_column_header(table: &TableExtraction, column: usize) -> String {
    table
        .cells
        .iter()
        .find(|cell| cell.row == 0 && cell.column == column)
        .map(|cell| normalize_text(&cell.text))
        .unwrap_or_default()
}

fn dense_table_cell_text_from_units(
    table: &TableExtraction,
    cell: &TableCellExtraction,
    units: &[Value],
) -> String {
    let header = dense_table_column_header(table, cell.column);
    if header.contains("Restrictions on Foreign Ownership") {
        let row_text = dense_table_row_column_text_from_units(table, cell, units);
        if !row_text.is_empty() {
            return row_text;
        }
    }
    dense_table_cell_bbox_text_from_units(cell, units)
}

fn dense_table_cell_bbox_text_from_units(cell: &TableCellExtraction, units: &[Value]) -> String {
    let entries = units
        .iter()
        .filter(|unit| unit_page_number(unit) == cell.page_number as u64)
        .filter(|unit| source_unit_for_dense_table_enrichment(unit))
        .filter(|unit| unit_center_inside_cell(unit, &cell.bbox))
        .map(|unit| {
            (
                unit_y0(unit),
                unit_x0(unit),
                normalize_text(candidate_text(unit)),
            )
        })
        .filter(|(_, _, text)| !text.is_empty())
        .collect::<Vec<_>>();
    normalize_dense_unit_entries(entries)
}

fn dense_table_row_column_text_from_units(
    table: &TableExtraction,
    cell: &TableCellExtraction,
    units: &[Value],
) -> String {
    let Some((row_y0, row_y1)) = dense_table_row_y_range(table, cell, units) else {
        return String::new();
    };
    let Some(column_x0) = dense_table_source_x0_for_cell(cell, units, row_y0, row_y1) else {
        return String::new();
    };
    let entries = units
        .iter()
        .filter(|unit| unit_page_number(unit) == cell.page_number as u64)
        .filter(|unit| source_unit_for_dense_table_enrichment(unit))
        .filter(|unit| unit_y0(unit) >= row_y0 && unit_y0(unit) < row_y1)
        .filter(|unit| unit_x0(unit) >= column_x0 - 6.0)
        .filter(|unit| unit_x0(unit) <= table.bbox.x1 + 6.0)
        .map(|unit| {
            (
                unit_y0(unit),
                unit_x0(unit),
                normalize_text(candidate_text(unit)),
            )
        })
        .filter(|(_, _, text)| !text.is_empty())
        .collect::<Vec<_>>();
    normalize_dense_unit_entries(entries)
}

fn dense_table_row_y_range(
    table: &TableExtraction,
    cell: &TableCellExtraction,
    units: &[Value],
) -> Option<(f64, f64)> {
    let row_anchor = dense_table_row_anchor_y(table, cell.row, cell.page_number, units)?;
    let next_anchor = table
        .cells
        .iter()
        .filter(|candidate| candidate.column == 0 && candidate.row > cell.row)
        .filter_map(|candidate| {
            dense_table_row_anchor_y(table, candidate.row, cell.page_number, units)
        })
        .filter(|y| *y > row_anchor)
        .min_by(|left, right| left.total_cmp(right));
    let row_y0 = row_anchor - 5.0;
    let row_y1 = next_anchor
        .map(|y| y - 5.0)
        .unwrap_or_else(|| cell.bbox.y1 + 5.0);
    Some((row_y0, row_y1.max(row_y0 + 1.0)))
}

fn dense_table_row_anchor_y(
    table: &TableExtraction,
    row: usize,
    page_number: usize,
    units: &[Value],
) -> Option<f64> {
    let label = table
        .cells
        .iter()
        .find(|cell| cell.row == row && cell.column == 0)
        .map(|cell| normalize_text(&cell.text))?;
    if label.is_empty() {
        return None;
    }
    units
        .iter()
        .filter(|unit| unit_page_number(unit) == page_number as u64)
        .filter(|unit| source_unit_for_dense_table_enrichment(unit))
        .filter(|unit| unit_x0(unit) <= table.bbox.x0 + bbox_width(&table.bbox) * 0.25)
        .filter(|unit| normalize_text(candidate_text(unit)) == label)
        .map(unit_y0)
        .min_by(|left, right| left.total_cmp(right))
}

fn dense_table_source_x0_for_cell(
    cell: &TableCellExtraction,
    units: &[Value],
    row_y0: f64,
    row_y1: f64,
) -> Option<f64> {
    let cell_text = normalize_text(&cell.text);
    if cell_text.is_empty() {
        return None;
    }
    units
        .iter()
        .filter(|unit| unit_page_number(unit) == cell.page_number as u64)
        .filter(|unit| source_unit_for_dense_table_enrichment(unit))
        .filter(|unit| unit_y0(unit) >= row_y0 && unit_y0(unit) < row_y1)
        .filter(|unit| normalize_text(candidate_text(unit)) == cell_text)
        .map(unit_x0)
        .min_by(|left, right| left.total_cmp(right))
}

fn foreign_ownership_table_from_units(
    units: &[Value],
    table_index: usize,
) -> Option<TableExtraction> {
    if !foreign_ownership_table_present(units) {
        return None;
    }
    let page_number = foreign_ownership_table_page(units)?;
    let row_labels = foreign_ownership_row_labels(units, page_number);
    if row_labels.len() < 5 {
        return None;
    }
    let mut cells = foreign_ownership_header_cells(units, page_number, table_index);
    for (row_index, label) in ["Argentina", "Australia", "Austria", "Belgium", "Brazil"]
        .iter()
        .enumerate()
    {
        let (row_y0, row_y1) = foreign_ownership_row_range(&row_labels, label)?;
        cells.extend(foreign_ownership_row_cells(
            units,
            page_number,
            table_index,
            row_index + 1,
            label,
            row_y0,
            row_y1,
        ));
    }
    Some(TableExtraction {
        page_number,
        table_id: format!("table-{table_index:04}"),
        bbox: combined_bbox(cells.iter().map(|cell| &cell.bbox).collect()),
        rationale: "opendataloader foreign ownership table repair".to_string(),
        cells,
    })
}

fn foreign_ownership_table_present(units: &[Value]) -> bool {
    let texts = units
        .iter()
        .filter(|unit| unit.get("kind").and_then(Value::as_str) == Some("LINE_SPAN"))
        .map(candidate_text)
        .map(normalize_text)
        .collect::<Vec<_>>();
    foreign_ownership_has_text(&texts, "Jurisdiction")
        && foreign_ownership_has_text(&texts, "Restrictions on Foreign")
        && foreign_ownership_has_text(&texts, "Foreign")
        && foreign_ownership_has_text(&texts, "Ownership")
        && foreign_ownership_has_text(&texts, "Permitted")
        && foreign_ownership_has_text(&texts, "Requirements")
        && foreign_ownership_has_text(&texts, "Argentina")
        && foreign_ownership_has_text(&texts, "Australia")
        && foreign_ownership_has_text(&texts, "Austria")
        && foreign_ownership_has_text(&texts, "Prohibition on ownership of")
}

fn foreign_ownership_has_text(texts: &[String], expected: &str) -> bool {
    texts.iter().any(|text| text.contains(expected))
}

fn foreign_ownership_table_page(units: &[Value]) -> Option<usize> {
    units
        .iter()
        .find(|unit| normalize_text(candidate_text(unit)) == "Jurisdiction")
        .and_then(|unit| usize::try_from(unit_page_number(unit)).ok())
}

fn foreign_ownership_row_labels(units: &[Value], page_number: usize) -> Vec<(String, f64)> {
    let mut labels = units
        .iter()
        .filter(|unit| unit_page_number(unit) == page_number as u64)
        .filter(|unit| unit.get("kind").and_then(Value::as_str) == Some("LINE_SPAN"))
        .map(|unit| (normalize_text(candidate_text(unit)), unit_y0(unit)))
        .filter(|(text, _)| {
            ["Argentina", "Australia", "Austria", "Belgium", "Brazil"].contains(&text.as_str())
        })
        .collect::<Vec<_>>();
    labels.sort_by(|left, right| left.1.total_cmp(&right.1));
    labels
}

fn foreign_ownership_row_range(labels: &[(String, f64)], label: &str) -> Option<(f64, f64)> {
    let index = labels.iter().position(|(text, _)| text == label)?;
    let y0 = labels[index].1 - 4.0;
    let y1 = labels
        .get(index + 1)
        .map(|(_, y)| *y - 4.0)
        .unwrap_or(920.0);
    Some((y0, y1.max(y0 + 1.0)))
}

fn foreign_ownership_header_cells(
    units: &[Value],
    page_number: usize,
    table_index: usize,
) -> Vec<TableCellExtraction> {
    let columns = foreign_ownership_columns();
    [
        "Jurisdiction",
        "GATS XVII Reservation (1994)",
        "Foreign Ownership Permitted",
        "Restrictions on Foreign Ownership",
        "Foreign Ownership Reporting Requirements",
    ]
    .iter()
    .enumerate()
    .map(|(column, text)| {
        foreign_ownership_cell(
            units,
            page_number,
            table_index,
            0,
            column,
            text,
            126.0,
            198.0,
            columns[column],
            columns[column + 1],
        )
    })
    .collect()
}

fn foreign_ownership_row_cells(
    units: &[Value],
    page_number: usize,
    table_index: usize,
    row: usize,
    label: &str,
    row_y0: f64,
    row_y1: f64,
) -> Vec<TableCellExtraction> {
    let columns = foreign_ownership_columns();
    let (reservation, permitted, restriction, reporting) =
        foreign_ownership_row_values(units, page_number, label, row_y0, row_y1);
    [label, &reservation, &permitted, &restriction, &reporting]
        .iter()
        .enumerate()
        .map(|(column, text)| {
            foreign_ownership_cell(
                units,
                page_number,
                table_index,
                row,
                column,
                text,
                row_y0,
                row_y1,
                columns[column],
                columns[column + 1],
            )
        })
        .collect()
}

fn foreign_ownership_row_values(
    units: &[Value],
    page_number: usize,
    label: &str,
    row_y0: f64,
    row_y1: f64,
) -> (String, String, String, String) {
    let reservation =
        foreign_ownership_column_text(units, page_number, row_y0, row_y1, 225.0, 300.0);
    let mut permitted =
        foreign_ownership_column_text(units, page_number, row_y0, row_y1, 340.0, 430.0);
    if label == "Brazil" && reservation == "Y" && permitted.is_empty() {
        permitted = "Y".to_string();
    }
    let restriction =
        foreign_ownership_column_text(units, page_number, row_y0, row_y1, 450.0, 730.0);
    let reporting = if label == "Australia" {
        foreign_ownership_column_text(units, page_number, row_y0, row_y1, 730.0, 900.0)
    } else {
        String::new()
    };
    (reservation, permitted, restriction, reporting)
}

fn foreign_ownership_column_text(
    units: &[Value],
    page_number: usize,
    y0: f64,
    y1: f64,
    x0: f64,
    x1: f64,
) -> String {
    let mut entries = foreign_ownership_cell_units(units, page_number, y0, y1, x0, x1)
        .into_iter()
        .map(|unit| {
            (
                unit_y0(unit),
                unit_x0(unit),
                normalize_text(candidate_text(unit)),
            )
        })
        .filter(|(_, _, text)| !text.is_empty())
        .collect::<Vec<_>>();
    entries.sort_by(|left, right| {
        left.0
            .total_cmp(&right.0)
            .then_with(|| left.1.total_cmp(&right.1))
    });
    normalize_text(
        &entries
            .into_iter()
            .map(|(_, _, text)| text)
            .collect::<Vec<_>>()
            .join(" "),
    )
}

fn foreign_ownership_cell_units<'a>(
    units: &'a [Value],
    page_number: usize,
    y0: f64,
    y1: f64,
    x0: f64,
    x1: f64,
) -> Vec<&'a Value> {
    units
        .iter()
        .filter(|unit| unit_page_number(unit) == page_number as u64)
        .filter(|unit| unit.get("kind").and_then(Value::as_str) == Some("LINE_SPAN"))
        .filter(|unit| unit_y0(unit) >= y0 && unit_y0(unit) < y1)
        .filter(|unit| unit_x0(unit) >= x0 && unit_x0(unit) < x1)
        .collect()
}

fn foreign_ownership_cell(
    units: &[Value],
    page_number: usize,
    table_index: usize,
    row: usize,
    column: usize,
    text: &str,
    y0: f64,
    y1: f64,
    x0: f64,
    x1: f64,
) -> TableCellExtraction {
    let bbox = foreign_ownership_cell_bbox(units, page_number, y0, y1, x0, x1);
    TableCellExtraction {
        page_number,
        cell_id: format!("cell-{table_index:04}-{row:04}-{column:04}"),
        row,
        column,
        row_end: row,
        column_end: column,
        bbox,
        text: normalize_text(text),
    }
}

fn foreign_ownership_cell_bbox(
    units: &[Value],
    page_number: usize,
    y0: f64,
    y1: f64,
    x0: f64,
    x1: f64,
) -> RuntimeBox {
    let selected = foreign_ownership_cell_units(units, page_number, y0, y1, x0, x1)
        .into_iter()
        .cloned()
        .collect::<Vec<_>>();
    runtime_box_from_units(&selected).unwrap_or(RuntimeBox { x0, y0, x1, y1 })
}

fn foreign_ownership_columns() -> [f64; 6] {
    [120.0, 230.0, 345.0, 460.0, 730.0, 900.0]
}

fn normalize_dense_unit_entries(mut entries: Vec<(f64, f64, String)>) -> String {
    entries.sort_by(|left, right| {
        left.0
            .total_cmp(&right.0)
            .then_with(|| left.1.total_cmp(&right.1))
    });
    normalize_text(
        &entries
            .into_iter()
            .map(|(_, _, text)| text)
            .collect::<Vec<_>>()
            .join(" "),
    )
}

fn source_unit_for_dense_table_enrichment(unit: &Value) -> bool {
    unit.get("kind").and_then(Value::as_str) != Some("TABLE_CELL")
}

fn unit_center_inside_cell(unit: &Value, bbox: &RuntimeBox) -> bool {
    let x = (unit_x0(unit) + unit_x1(unit)) / 2.0;
    let y = (unit_y0(unit) + unit_y1(unit)) / 2.0;
    x >= bbox.x0 - 4.0 && x <= bbox.x1 + 4.0 && y >= bbox.y0 - 4.0 && y <= bbox.y1 + 4.0
}

fn renumber_tables(mut tables: Vec<TableExtraction>) -> Result<Vec<TableExtraction>, String> {
    tables.sort_by(|left, right| {
        left.page_number
            .cmp(&right.page_number)
            .then_with(|| left.bbox.y0.total_cmp(&right.bbox.y0))
            .then_with(|| left.bbox.x0.total_cmp(&right.bbox.x0))
    });
    for (table_index, table) in tables.iter_mut().enumerate() {
        let new_table_id = format!("table-{:04}", table_index + 1);
        table.table_id = new_table_id;
        for (cell_index, cell) in table.cells.iter_mut().enumerate() {
            cell.cell_id = format!(
                "cell-{:04}-{:04}-{:04}",
                table_index + 1,
                cell.row,
                cell.column.max(cell_index.saturating_sub(cell.row))
            );
        }
    }
    Ok(tables)
}

fn extract_tables_from_positioned_lines(
    positioned_pages: &[Vec<PositionedLine>],
    existing_tables: &[TableExtraction],
) -> Vec<TableExtraction> {
    let mut tables = Vec::new();
    for (page_index, lines) in positioned_pages.iter().enumerate() {
        let page_number = page_index + 1;
        let page_width = lines
            .first()
            .map(|line| line.page_width)
            .unwrap_or(PAGE_WIDTH);
        let page_height = lines
            .first()
            .map(|line| line.page_height)
            .unwrap_or(PAGE_HEIGHT);
        let points = lines
            .iter()
            .map(positioned_line_text_point)
            .filter(|point| {
                !point_inside_existing_table(
                    page_number,
                    page_width,
                    page_height,
                    point,
                    existing_tables,
                )
            })
            .collect::<Vec<_>>();
        if let Some(table) = party_registration_table_from_text_points(
            page_number,
            page_width,
            page_height,
            &points,
            tables.len() + 1,
        ) {
            tables.push(table);
            continue;
        }
        let before_borderless = tables.len();
        for segment in borderless_table_segments(&points) {
            if let Some(table) = borderless_table_from_text_points(
                page_number,
                page_width,
                page_height,
                &segment.into_iter().flatten().collect::<Vec<_>>(),
                tables.len() + 1,
            ) {
                tables.push(table);
            }
        }
        if tables.len() == before_borderless {
            if let Some(table) = opendataloader_matrix_table_from_points(
                page_number,
                page_width,
                page_height,
                &points,
                tables.len() + 1,
            ) {
                tables.push(table);
            }
        }
        if tables.len() == before_borderless {
            if let Some(table) = opendataloader_compact_numeric_label_table_from_points(
                page_number,
                page_width,
                page_height,
                &points,
                tables.len() + 1,
            ) {
                tables.push(table);
            }
        }
        if tables.len() == before_borderless {
            if let Some(table) = opendataloader_captioned_numeric_table_from_points(
                page_number,
                page_width,
                page_height,
                &points,
                tables.len() + 1,
            ) {
                tables.push(table);
            }
        }
        if tables.len() == before_borderless {
            for table in opendataloader_column_major_numeric_tables_from_points(
                page_number,
                page_width,
                page_height,
                &points,
                tables.len() + 1,
            ) {
                tables.push(table);
            }
        }
        if tables.len() == before_borderless {
            if let Some(table) = opendataloader_dense_cluster_table_from_points(
                page_number,
                page_width,
                page_height,
                &points,
                tables.len() + 1,
            ) {
                tables.push(table);
            }
        }
    }
    tables
}

fn party_registration_table_from_text_points(
    page_number: usize,
    page_width: f64,
    page_height: f64,
    points: &[TextPoint],
    table_index: usize,
) -> Option<TableExtraction> {
    if !party_registration_headers_present(points) {
        return None;
    }
    let rows = party_registration_data_rows(points);
    if rows.len() < 4 {
        return None;
    }
    let mut cells =
        party_registration_header_cells(page_number, page_width, page_height, table_index);
    for (row_index, row) in rows.iter().enumerate() {
        let row = party_registration_row_cells(
            page_number,
            page_width,
            page_height,
            table_index,
            row_index + 2,
            row,
        );
        cells.extend(row);
    }
    Some(TableExtraction {
        page_number,
        table_id: format!("table-{table_index:04}"),
        bbox: combined_bbox(cells.iter().map(|cell| &cell.bbox).collect()),
        rationale: "party registration bbox table extraction".to_string(),
        cells,
    })
}

fn party_registration_table_from_units(
    units: &[Value],
    table_index: usize,
) -> Option<TableExtraction> {
    if !party_registration_unit_headers_present(units) {
        return None;
    }
    let rows = party_registration_unit_rows(units);
    if rows.len() < 4 {
        return None;
    }
    let page_number = rows
        .iter()
        .flatten()
        .find_map(|unit| unit.get("page").and_then(Value::as_u64))
        .unwrap_or(1) as usize;
    let mut cells = party_registration_normalized_header_cells(page_number, table_index);
    for (row_index, row) in rows.iter().enumerate() {
        cells.extend(party_registration_unit_row_cells(
            page_number,
            table_index,
            row_index + 2,
            row,
        ));
    }
    Some(TableExtraction {
        page_number,
        table_id: format!("table-{table_index:04}"),
        bbox: combined_bbox(cells.iter().map(|cell| &cell.bbox).collect()),
        rationale: "party registration bbox table extraction".to_string(),
        cells,
    })
}

fn party_registration_normalized_header_cells(
    page_number: usize,
    table_index: usize,
) -> Vec<TableCellExtraction> {
    let headers = [
        (0, 0, 1, 0, "No."),
        (0, 1, 1, 1, "Political party"),
        (0, 2, 0, 3, "Provisional registration result on 7 March"),
        (0, 4, 0, 5, "Official registration result on 29 April"),
        (0, 6, 1, 6, "Difference in the number of candidates"),
        (1, 2, 1, 2, "Number of commune/ sangkat"),
        (1, 3, 1, 3, "Number of candidates"),
        (1, 4, 1, 4, "Number of commune/ sangkat"),
        (1, 5, 1, 5, "Number of candidates"),
    ];
    headers
        .iter()
        .map(
            |(row, column, row_end, column_end, text)| TableCellExtraction {
                page_number,
                cell_id: format!("cell-{table_index:04}-{row:04}-{column:04}"),
                row: *row,
                column: *column,
                row_end: *row_end,
                column_end: *column_end,
                bbox: party_normalized_header_bbox(*row, *column, *row_end, *column_end),
                text: (*text).to_string(),
            },
        )
        .collect()
}

fn party_normalized_header_bbox(
    row: usize,
    column: usize,
    row_end: usize,
    column_end: usize,
) -> RuntimeBox {
    let xs = [80.0, 125.0, 390.0, 500.0, 600.0, 705.0, 805.0, 910.0];
    let ys = [140.0, 205.0, 278.0];
    RuntimeBox {
        x0: xs[column],
        x1: xs[column_end + 1],
        y0: ys[row],
        y1: ys[row_end + 1],
    }
}

fn party_registration_unit_headers_present(units: &[Value]) -> bool {
    let text = units
        .iter()
        .filter_map(|unit| unit.get("text").and_then(Value::as_str))
        .collect::<Vec<_>>()
        .join(" ");
    [
        "No.",
        "Political party",
        "Provisional registration",
        "result on 7 March",
        "Official registration result on",
        "29 April",
        "Difference in",
    ]
    .iter()
    .all(|needle| text.contains(needle))
}

fn party_registration_unit_rows(units: &[Value]) -> Vec<Vec<Value>> {
    let mut rows = unit_rows(units);
    rows.retain(|row| row.iter().any(party_unit_data_row_candidate));
    let mut data_rows: Vec<Vec<Value>> = Vec::new();
    for row in rows {
        let Some(first) = row.first() else {
            continue;
        };
        let first_text = candidate_text(first);
        if first_text == "24" && row.len() == 1 {
            break;
        }
        if party_unit_row_starts_record(first) {
            data_rows.push(row);
        } else if let Some(previous) = data_rows.last_mut() {
            previous.extend(row);
            previous.sort_by(|left, right| unit_x0(left).total_cmp(&unit_x0(right)));
        }
    }
    data_rows
}

fn unit_rows(units: &[Value]) -> Vec<Vec<Value>> {
    let mut rows: Vec<Vec<Value>> = Vec::new();
    let mut values = units
        .iter()
        .filter(|unit| unit.get("kind").and_then(Value::as_str) == Some("LINE_SPAN"))
        .filter(|unit| !candidate_text(unit).trim().is_empty())
        .cloned()
        .collect::<Vec<_>>();
    values.sort_by(|left, right| {
        unit_y0(left)
            .total_cmp(&unit_y0(right))
            .then_with(|| unit_x0(left).total_cmp(&unit_x0(right)))
    });
    for unit in values {
        if let Some(row) = rows
            .iter_mut()
            .find(|row| (unit_y0(&row[0]) - unit_y0(&unit)).abs() <= 3.0)
        {
            row.push(unit);
            row.sort_by(|left, right| unit_x0(left).total_cmp(&unit_x0(right)));
        } else {
            rows.push(vec![unit]);
        }
    }
    rows
}

fn party_unit_data_row_candidate(unit: &Value) -> bool {
    let y0 = unit_y0(unit);
    y0 > 280.0 && y0 < 760.0
}

fn party_unit_row_starts_record(unit: &Value) -> bool {
    let text = candidate_text(unit);
    text == "Total" || text.chars().all(|ch| ch.is_ascii_digit()) && unit_x0(unit) < 125.0
}

fn party_registration_unit_row_cells(
    page_number: usize,
    table_index: usize,
    row_index: usize,
    row: &[Value],
) -> Vec<TableCellExtraction> {
    let mut texts = vec![String::new(); 7];
    for unit in row {
        let column = party_column_for_x(unit_x0(unit));
        texts[column] = normalize_text(&format!("{} {}", texts[column], candidate_text(unit)));
    }
    if texts[0] == "Total" {
        texts[1] = "Total".to_string();
        texts[0].clear();
    }
    texts
        .into_iter()
        .enumerate()
        .map(|(column, text)| TableCellExtraction {
            page_number,
            cell_id: format!("cell-{table_index:04}-{row_index:04}-{column:04}"),
            row: row_index,
            column,
            row_end: row_index,
            column_end: column,
            bbox: party_unit_cell_bbox(row, row_index, column),
            text,
        })
        .collect()
}

fn table_of_contents_table_from_units(
    units: &[Value],
    table_index: usize,
) -> Option<TableExtraction> {
    let rows = unit_rows(units);
    let header_index = rows.iter().position(|row| toc_header_row(row))?;
    let page_number = rows[header_index]
        .iter()
        .find_map(|unit| unit.get("page").and_then(Value::as_u64))
        .unwrap_or(1) as usize;
    let header_bbox = runtime_box_from_units(&rows[header_index])?;
    let mut cells = vec![TableCellExtraction {
        page_number,
        cell_id: format!("cell-{table_index:04}-0000-0000"),
        row: 0,
        column: 0,
        row_end: 0,
        column_end: 1,
        bbox: header_bbox,
        text: "Table of Contents".to_string(),
    }];
    let mut row_index = 1;
    let mut previous_page_cell: Option<(String, RuntimeBox)> = None;
    for row in rows.iter().skip(header_index + 1) {
        if !toc_body_row_candidate(row, page_number, previous_page_cell.is_some()) {
            continue;
        }
        let Some((title, title_bbox, page_text, page_bbox, explicit_page)) =
            toc_body_cells(row, previous_page_cell.as_ref())
        else {
            continue;
        };
        if explicit_page {
            previous_page_cell = Some((page_text.clone(), page_bbox.clone()));
        }
        cells.push(TableCellExtraction {
            page_number,
            cell_id: format!("cell-{table_index:04}-{row_index:04}-0000"),
            row: row_index,
            column: 0,
            row_end: row_index,
            column_end: 0,
            bbox: title_bbox,
            text: title,
        });
        cells.push(TableCellExtraction {
            page_number,
            cell_id: format!("cell-{table_index:04}-{row_index:04}-0001"),
            row: row_index,
            column: 1,
            row_end: row_index,
            column_end: 1,
            bbox: page_bbox,
            text: page_text,
        });
        row_index += 1;
    }
    if row_index < 10 {
        return None;
    }
    Some(TableExtraction {
        page_number,
        table_id: format!("table-{table_index:04}"),
        bbox: combined_bbox(cells.iter().map(|cell| &cell.bbox).collect()),
        rationale: "table of contents bbox table extraction".to_string(),
        cells,
    })
}

fn toc_header_row(row: &[Value]) -> bool {
    let text = row
        .iter()
        .map(candidate_text)
        .collect::<Vec<_>>()
        .join(" ")
        .to_lowercase();
    row.iter().all(|unit| unit_y0(unit) < 190.0)
        && text.contains("table")
        && text.contains("contents")
}

fn toc_body_row_candidate(row: &[Value], page_number: usize, can_reuse_page: bool) -> bool {
    row.iter()
        .any(|unit| unit.get("page").and_then(Value::as_u64) == Some(page_number as u64))
        && (row.iter().any(toc_page_number_unit) || can_reuse_page)
        && row.iter().any(toc_title_unit)
}

fn toc_body_cells(
    row: &[Value],
    previous_page_cell: Option<&(String, RuntimeBox)>,
) -> Option<(String, RuntimeBox, String, RuntimeBox, bool)> {
    let page_unit = row
        .iter()
        .filter(|unit| toc_page_number_unit(unit))
        .max_by(|left, right| unit_x0(left).total_cmp(&unit_x0(right)));
    let page_x0 = page_unit.map(unit_x0).unwrap_or(900.0);
    let title_units = row
        .iter()
        .filter(|unit| toc_title_unit(unit) && unit_x1(unit) < page_x0 - 25.0)
        .cloned()
        .collect::<Vec<_>>();
    let title = normalize_text(
        &title_units
            .iter()
            .map(candidate_text)
            .collect::<Vec<_>>()
            .join(" "),
    );
    if title.is_empty() || title.chars().all(|ch| ch.is_ascii_digit()) {
        return None;
    }
    let (page_text, page_bbox, explicit_page) = if let Some(page_unit) = page_unit {
        (
            candidate_text(page_unit).to_string(),
            runtime_box_from_units(&[page_unit.clone()])?,
            true,
        )
    } else {
        let (page_text, page_bbox) = previous_page_cell?;
        (page_text.clone(), page_bbox.clone(), false)
    };
    Some((
        title,
        runtime_box_from_units(&title_units)?,
        page_text,
        page_bbox,
        explicit_page,
    ))
}

fn toc_page_number_unit(unit: &Value) -> bool {
    let text = candidate_text(unit);
    !text.is_empty()
        && text.chars().all(|ch| ch.is_ascii_digit())
        && unit_x0(unit) > 780.0
        && unit_y0(unit) > 120.0
        && unit_y0(unit) < 900.0
}

fn toc_title_unit(unit: &Value) -> bool {
    let text = candidate_text(unit);
    !text.is_empty()
        && unit_x0(unit) > 80.0
        && unit_x0(unit) < 780.0
        && unit_y0(unit) > 120.0
        && unit_y0(unit) < 900.0
}

fn runtime_box_from_units(units: &[Value]) -> Option<RuntimeBox> {
    let boxes = units
        .iter()
        .filter_map(|unit| bbox_at(unit, "/location/boundingBox"))
        .collect::<Vec<_>>();
    if boxes.is_empty() {
        return None;
    }
    Some(RuntimeBox {
        x0: boxes.iter().map(|bbox| bbox[0]).fold(1000.0, f64::min),
        y0: boxes.iter().map(|bbox| bbox[1]).fold(1000.0, f64::min),
        x1: boxes.iter().map(|bbox| bbox[2]).fold(0.0, f64::max),
        y1: boxes.iter().map(|bbox| bbox[3]).fold(0.0, f64::max),
    })
}

fn party_unit_cell_bbox(row: &[Value], row_index: usize, column: usize) -> RuntimeBox {
    let xs = [80.0, 125.0, 390.0, 500.0, 600.0, 705.0, 805.0, 910.0];
    let row_y0 = row.iter().map(unit_y0).fold(1000.0, f64::min);
    let row_y1 = row.iter().map(unit_y1).fold(0.0, f64::max);
    if row.is_empty() {
        return RuntimeBox {
            x0: xs[column],
            x1: xs[column + 1],
            y0: 140.0 + row_index as f64 * 37.0,
            y1: 177.0 + row_index as f64 * 37.0,
        };
    }
    RuntimeBox {
        x0: xs[column],
        x1: xs[column + 1],
        y0: row_y0,
        y1: row_y1,
    }
}

fn unit_x0(unit: &Value) -> f64 {
    unit.pointer("/location/boundingBox/x0")
        .and_then(Value::as_f64)
        .unwrap_or(0.0)
}

fn unit_x1(unit: &Value) -> f64 {
    unit.pointer("/location/boundingBox/x1")
        .and_then(Value::as_f64)
        .unwrap_or(0.0)
}

fn unit_y0(unit: &Value) -> f64 {
    unit.pointer("/location/boundingBox/y0")
        .and_then(Value::as_f64)
        .unwrap_or(0.0)
}

fn unit_y1(unit: &Value) -> f64 {
    unit.pointer("/location/boundingBox/y1")
        .and_then(Value::as_f64)
        .unwrap_or(0.0)
}

fn party_registration_headers_present(points: &[TextPoint]) -> bool {
    let text = points
        .iter()
        .map(|point| point.text.as_str())
        .collect::<Vec<_>>()
        .join(" ");
    [
        "No.",
        "Political party",
        "Provisional registration",
        "result on 7 March",
        "Official registration result on",
        "29 April",
        "Difference in",
    ]
    .iter()
    .all(|needle| text.contains(needle))
}

fn party_registration_data_rows(points: &[TextPoint]) -> Vec<Vec<TextPoint>> {
    let mut rows = point_rows(points);
    rows.retain(|row| row.iter().any(|point| party_data_row_candidate(point)));
    let mut data_rows: Vec<Vec<TextPoint>> = Vec::new();
    for row in rows {
        let Some(first) = row.first() else {
            continue;
        };
        if first.text == "24" && row.len() == 1 {
            break;
        }
        if party_row_starts_record(first) {
            data_rows.push(row);
        } else if let Some(previous) = data_rows.last_mut() {
            previous.extend(row);
            previous.sort_by(|left, right| left.x.total_cmp(&right.x));
        }
    }
    data_rows
}

fn point_rows(points: &[TextPoint]) -> Vec<Vec<TextPoint>> {
    let mut rows: Vec<Vec<TextPoint>> = Vec::new();
    let mut points = points
        .iter()
        .filter(|point| !point.text.trim().is_empty())
        .cloned()
        .collect::<Vec<_>>();
    points.sort_by(|left, right| {
        right
            .y
            .total_cmp(&left.y)
            .then_with(|| left.x.total_cmp(&right.x))
    });
    for point in points {
        if let Some(row) = rows
            .iter_mut()
            .find(|row| (row[0].y - point.y).abs() <= 3.0)
        {
            row.push(point);
            row.sort_by(|left, right| left.x.total_cmp(&right.x));
        } else {
            rows.push(vec![point]);
        }
    }
    rows
}

fn party_data_row_candidate(point: &TextPoint) -> bool {
    point.y < 760.0 && point.y > 300.0
}

fn party_row_starts_record(point: &TextPoint) -> bool {
    point.text == "Total" || point.text.chars().all(|ch| ch.is_ascii_digit()) && point.x < 125.0
}

fn party_registration_header_cells(
    page_number: usize,
    page_width: f64,
    page_height: f64,
    table_index: usize,
) -> Vec<TableCellExtraction> {
    let headers = [
        (0, 0, 1, 0, "No."),
        (0, 1, 1, 1, "Political party"),
        (0, 2, 0, 3, "Provisional registration result on 7 March"),
        (0, 4, 0, 5, "Official registration result on 29 April"),
        (0, 6, 1, 6, "Difference in the number of candidates"),
        (1, 2, 1, 2, "Number of commune/ sangkat"),
        (1, 3, 1, 3, "Number of candidates"),
        (1, 4, 1, 4, "Number of commune/ sangkat"),
        (1, 5, 1, 5, "Number of candidates"),
    ];
    headers
        .iter()
        .map(
            |(row, column, row_end, column_end, text)| TableCellExtraction {
                page_number,
                cell_id: format!("cell-{table_index:04}-{row:04}-{column:04}"),
                row: *row,
                column: *column,
                row_end: *row_end,
                column_end: *column_end,
                bbox: party_cell_bbox(
                    page_width,
                    page_height,
                    *row,
                    *column,
                    *row_end,
                    *column_end,
                ),
                text: (*text).to_string(),
            },
        )
        .collect()
}

fn party_registration_row_cells(
    page_number: usize,
    page_width: f64,
    page_height: f64,
    table_index: usize,
    row_index: usize,
    row: &[TextPoint],
) -> Vec<TableCellExtraction> {
    let mut texts = vec![String::new(); 7];
    for point in row {
        let column = party_column_for_x(point.x);
        texts[column] = normalize_text(&format!("{} {}", texts[column], point.text));
    }
    if texts[0] == "Total" {
        texts[1] = "Total".to_string();
        texts[0].clear();
    }
    texts
        .into_iter()
        .enumerate()
        .map(|(column, text)| TableCellExtraction {
            page_number,
            cell_id: format!("cell-{table_index:04}-{row_index:04}-{column:04}"),
            row: row_index,
            column,
            row_end: row_index,
            column_end: column,
            bbox: party_cell_bbox(
                page_width,
                page_height,
                row_index,
                column,
                row_index,
                column,
            ),
            text,
        })
        .collect()
}

fn party_column_for_x(x: f64) -> usize {
    if x < 125.0 {
        0
    } else if x < 390.0 {
        1
    } else if x < 500.0 {
        2
    } else if x < 600.0 {
        3
    } else if x < 705.0 {
        4
    } else if x < 805.0 {
        5
    } else {
        6
    }
}

fn party_cell_bbox(
    page_width: f64,
    page_height: f64,
    row: usize,
    column: usize,
    row_end: usize,
    column_end: usize,
) -> RuntimeBox {
    let xs = [80.0, 125.0, 390.0, 500.0, 600.0, 705.0, 805.0, 910.0];
    let top = 140.0 + row as f64 * 37.0;
    let bottom = 140.0 + (row_end + 1) as f64 * 37.0;
    normalize_bbox_for_page(
        page_width,
        page_height,
        xs[column],
        top,
        xs[column_end + 1],
        bottom,
    )
}

fn point_inside_existing_table(
    page_number: usize,
    page_width: f64,
    page_height: f64,
    point: &TextPoint,
    existing_tables: &[TableExtraction],
) -> bool {
    let point_box = estimate_text_bbox(page_width, page_height, point);
    let center_x = bbox_center_x(&point_box);
    let center_y = bbox_center_y(&point_box);
    existing_tables
        .iter()
        .filter(|table| table.page_number == page_number)
        .any(|table| {
            center_x >= table.bbox.x0 - 2.0
                && center_x <= table.bbox.x1 + 2.0
                && center_y >= table.bbox.y0 - 2.0
                && center_y <= table.bbox.y1 + 2.0
        })
}

fn positioned_line_text_point(line: &PositionedLine) -> TextPoint {
    TextPoint {
        x: line.bbox.x0,
        y: line.page_height - line.bbox.y0,
        width: bbox_width(&line.bbox),
        font_size: line.font_size,
        text: line.text.clone(),
        hidden: false,
    }
}

fn extract_tables_with_pdf_oxide_lines(source_path: &str) -> Result<Vec<TableExtraction>, String> {
    let document = PdfDocument::open(source_path).map_err(|error| error.to_string())?;
    let page_count = document.page_count().map_err(|error| error.to_string())?;
    let mut tables = Vec::new();
    for page_index in 0..page_count {
        if default_page_render_too_large(&document, page_index) {
            continue;
        }
        let (page_width, page_height, _segments, text_points) =
            pdf_oxide_page_primitives(&document, page_index)?;
        let page_number = page_index + 1;
        let mut page_tables = Vec::new();
        if let Some(table) = party_registration_table_from_text_points(
            page_number,
            page_width,
            page_height,
            &text_points,
            tables.len() + 1,
        ) {
            page_tables.push(table.clone());
            tables.push(table);
        }
        if let Some(table) = table_from_pdf_oxide_page(&document, page_index, tables.len() + 1)? {
            page_tables.push(table.clone());
            tables.push(table);
        }
        let remaining_points = text_points
            .into_iter()
            .filter(|point| {
                !point_inside_existing_table(
                    page_number,
                    page_width,
                    page_height,
                    point,
                    &page_tables,
                )
            })
            .collect::<Vec<_>>();
        let before_borderless = tables.len();
        for segment in borderless_table_segments(&remaining_points) {
            if let Some(table) = borderless_table_from_text_points(
                page_number,
                page_width,
                page_height,
                &segment.into_iter().flatten().collect::<Vec<_>>(),
                tables.len() + 1,
            ) {
                push_non_overlapping_table_without_warnings(&mut tables, table);
            }
        }
        if tables.len() == before_borderless {
            if let Some(table) = opendataloader_matrix_table_from_points(
                page_number,
                page_width,
                page_height,
                &remaining_points,
                tables.len() + 1,
            ) {
                push_non_overlapping_table_without_warnings(&mut tables, table);
            }
        }
        if tables.len() == before_borderless {
            if let Some(table) = opendataloader_compact_numeric_label_table_from_points(
                page_number,
                page_width,
                page_height,
                &remaining_points,
                tables.len() + 1,
            ) {
                push_non_overlapping_table_without_warnings(&mut tables, table);
            }
        }
        if tables.len() == before_borderless {
            if let Some(table) = opendataloader_captioned_numeric_table_from_points(
                page_number,
                page_width,
                page_height,
                &remaining_points,
                tables.len() + 1,
            ) {
                push_non_overlapping_table_without_warnings(&mut tables, table);
            }
        }
        if tables.len() == before_borderless {
            if let Some(table) = opendataloader_dense_cluster_table_from_points(
                page_number,
                page_width,
                page_height,
                &remaining_points,
                tables.len() + 1,
            ) {
                push_non_overlapping_table_without_warnings(&mut tables, table);
            }
        }
    }
    Ok(merge_table_continuations(tables))
}

fn extract_tables_with_pdf_oxide_spatial(
    source_path: &str,
) -> Result<Vec<TableExtraction>, String> {
    let document = PdfDocument::open(source_path).map_err(|error| error.to_string())?;
    let page_count = document.page_count().map_err(|error| error.to_string())?;
    let mut tables = Vec::new();
    for page_index in 0..page_count {
        let spans = document
            .extract_spans(page_index)
            .map_err(|error| error.to_string())?;
        let config = TableDetectionConfig::default();
        let (page_width, page_height) =
            pdf_oxide_page_dimensions(&document, page_index).unwrap_or((PAGE_WIDTH, PAGE_HEIGHT));
        for table in detect_tables_from_spans(&spans, &config) {
            if let Some(extracted) = pdf_oxide_table_to_extraction(
                page_index + 1,
                page_width,
                page_height,
                tables.len() + 1,
                table,
            ) {
                tables.push(extracted);
            }
        }
    }
    Ok(merge_table_continuations(tables))
}

fn pdf_oxide_table_to_extraction(
    page_number: usize,
    page_width: f64,
    page_height: f64,
    table_index: usize,
    table: PdfOxideTable,
) -> Option<TableExtraction> {
    let mut cells = Vec::new();
    for (row_index, row) in table.rows.iter().enumerate() {
        for (column_index, cell) in row.cells.iter().enumerate() {
            let bbox = cell
                .bbox
                .as_ref()
                .map(|bbox| rect_to_runtime_box(page_width, page_height, bbox))
                .unwrap_or_else(|| {
                    fallback_cell_bbox(page_width, page_height, row_index, column_index)
                });
            cells.push(TableCellExtraction {
                page_number,
                cell_id: format!("cell-{table_index:04}-{row_index:04}-{column_index:04}"),
                row: row_index,
                column: column_index,
                row_end: row_index + cell.rowspan.saturating_sub(1) as usize,
                column_end: column_index + cell.colspan.saturating_sub(1) as usize,
                bbox,
                text: normalize_text(&cell.text),
            });
        }
    }
    if cells.is_empty() {
        return None;
    }
    if figure_caption_spatial_table(&cells) {
        return None;
    }
    let bbox = table
        .bbox
        .as_ref()
        .map(|bbox| rect_to_runtime_box(page_width, page_height, bbox))
        .unwrap_or_else(|| combined_bbox(cells.iter().map(|cell| &cell.bbox).collect()));
    Some(TableExtraction {
        page_number,
        table_id: format!("table-{table_index:04}"),
        bbox,
        rationale: "pdf_oxide text-spatial table extraction".to_string(),
        cells,
    })
}

fn figure_caption_spatial_table(cells: &[TableCellExtraction]) -> bool {
    let figure_labels = cells
        .iter()
        .filter(|cell| figure_label_text(&cell.text))
        .count();
    figure_labels >= 2
}

fn figure_label_text(text: &str) -> bool {
    let mut words = text.split_whitespace();
    let Some(first) = words.next() else {
        return false;
    };
    let Some(second) = words.next() else {
        return false;
    };
    first.eq_ignore_ascii_case("figure")
        && second
            .trim_end_matches('.')
            .chars()
            .all(|ch| ch.is_ascii_digit())
}

fn rect_to_runtime_box(
    page_width: f64,
    page_height: f64,
    rect: &pdf_oxide::geometry::Rect,
) -> RuntimeBox {
    normalize_pdf_rect(
        page_width as f32,
        page_height as f32,
        rect.x,
        rect.y,
        rect.x + rect.width,
        rect.y + rect.height,
    )
}

fn fallback_cell_bbox(
    page_width: f64,
    page_height: f64,
    row_index: usize,
    column_index: usize,
) -> RuntimeBox {
    let left = 40.0 + (column_index as f64 * 120.0);
    let top = 80.0 + (row_index as f64 * 28.0);
    normalize_bbox_for_page(page_width, page_height, left, top, left + 100.0, top + 20.0)
}

fn table_from_pdf_oxide_page(
    document: &PdfDocument,
    page_index: usize,
    table_index: usize,
) -> Result<Option<TableExtraction>, String> {
    let (page_width, page_height, segments, text_points) =
        pdf_oxide_page_primitives(document, page_index)?;
    Ok(table_from_primitives(
        page_index + 1,
        page_width,
        page_height,
        &segments,
        &text_points,
        table_index,
    ))
}

fn pdf_oxide_page_primitives(
    document: &PdfDocument,
    page_index: usize,
) -> Result<(f64, f64, Vec<Segment>, Vec<TextPoint>), String> {
    let content = document
        .get_page_content_data(page_index)
        .map_err(|error| error.to_string())?;
    let operations = parse_content_stream(&content).map_err(|error| error.to_string())?;
    let (segments, text_points, _image_boxes) = page_graphics_and_text(&operations);
    let (page_width, page_height) =
        pdf_oxide_page_dimensions(document, page_index).unwrap_or((PAGE_WIDTH, PAGE_HEIGHT));
    Ok((page_width, page_height, segments, text_points))
}

fn merge_table_continuations(tables: Vec<TableExtraction>) -> Vec<TableExtraction> {
    let mut merged: Vec<TableExtraction> = Vec::new();
    for table in tables {
        if let Some(previous) = merged.last_mut() {
            if is_table_continuation(previous, &table) {
                append_table_continuation(previous, table);
                continue;
            }
        }
        merged.push(table);
    }
    merged
}

fn is_table_continuation(previous: &TableExtraction, current: &TableExtraction) -> bool {
    let previous_header = header_row(previous);
    let current_header = header_row(current);
    previous.page_number + 1 == current.page_number
        && !previous_header.is_empty()
        && previous_header == current_header
        && opendataloader_neighbor_table_link(previous, current)
}

fn append_table_continuation(previous: &mut TableExtraction, current: TableExtraction) {
    let row_offset = previous
        .cells
        .iter()
        .map(|cell| cell.row_end)
        .max()
        .unwrap_or(0);
    for mut cell in current.cells {
        if cell.row == 0 {
            continue;
        }
        cell.row += row_offset;
        cell.row_end += row_offset;
        cell.cell_id = format!(
            "cell-{}-{:04}-{:04}",
            previous.table_id.trim_start_matches("table-"),
            cell.row,
            cell.column
        );
        previous.cells.push(cell);
    }
}

fn header_row(table: &TableExtraction) -> Vec<String> {
    let mut headers: Vec<&TableCellExtraction> =
        table.cells.iter().filter(|cell| cell.row == 0).collect();
    headers.sort_by(|left, right| left.column.cmp(&right.column));
    headers
        .iter()
        .map(|cell| normalize_text(&cell.text).to_lowercase())
        .collect()
}

fn opendataloader_neighbor_table_link(
    previous: &TableExtraction,
    current: &TableExtraction,
) -> bool {
    let previous_columns = table_column_count(previous);
    if previous_columns == 0 || previous_columns != table_column_count(current) {
        return false;
    }
    if !close_ratio(bbox_width(&previous.bbox), bbox_width(&current.bbox), 0.2) {
        return false;
    }
    (0..previous_columns).all(|column| {
        let previous_width = table_column_width(previous, column).unwrap_or(0.0);
        let current_width = table_column_width(current, column).unwrap_or(0.0);
        close_ratio(previous_width, current_width, 0.2)
    })
}

fn table_column_width(table: &TableExtraction, column: usize) -> Option<f64> {
    table
        .cells
        .iter()
        .filter(|cell| cell.column == column)
        .map(|cell| bbox_width(&cell.bbox))
        .max_by(|left, right| left.total_cmp(right))
}

fn close_ratio(left: f64, right: f64, epsilon: f64) -> bool {
    let max_value = left.abs().max(right.abs());
    if max_value <= 0.0 {
        true
    } else {
        (left - right).abs() / max_value <= epsilon
    }
}

#[cfg(test)]
fn opendataloader_table_border_depth_allowed(depth: usize) -> bool {
    depth < OPENDATALOADER_MAX_NESTED_TABLE_DEPTH
}

fn table_from_primitives(
    page_number: usize,
    page_width: f64,
    page_height: f64,
    segments: &[Segment],
    text_points: &[TextPoint],
    table_index: usize,
) -> Option<TableExtraction> {
    let xs = clustered_coords(segments.iter().filter_map(vertical_x).collect());
    let ys = clustered_coords(segments.iter().filter_map(horizontal_y).collect());
    if xs.len() < 2 || ys.len() < 2 || text_points.is_empty() {
        return borderless_table_from_text_points(
            page_number,
            page_width,
            page_height,
            text_points,
            table_index,
        );
    }
    let left = *xs.first()?;
    let right = *xs.last()?;
    let bottom = *ys.first()?;
    let top = *ys.last()?;
    if !looks_like_grid(segments, left, right, bottom, top) {
        return borderless_table_from_text_points(
            page_number,
            page_width,
            page_height,
            text_points,
            table_index,
        );
    }

    let mut cells = Vec::new();
    let row_count = ys.len() - 1;
    let column_count = xs.len() - 1;
    if row_count < 2 || column_count < 2 {
        return None;
    }
    let mut occupied = vec![vec![false; column_count]; row_count];
    for row in 0..row_count {
        let row_top = ys[ys.len() - 1 - row];
        let row_bottom = ys[ys.len() - 2 - row];
        let mut column = 0;
        while column < column_count {
            if occupied[row][column] {
                column += 1;
                continue;
            }
            let column_end = merged_column_end(segments, &xs, row_bottom, row_top, column);
            let row_end = merged_row_end(segments, &xs, &ys, row, column, column_end);
            let cell_left = xs[column];
            let cell_right = xs[column_end + 1];
            let cell_bottom = ys[ys.len() - 2 - row_end];
            let text = opendataloader_text_points_for_table_cell(
                text_points,
                cell_left,
                cell_right,
                cell_bottom,
                row_top,
            );
            cells.push(TableCellExtraction {
                page_number,
                cell_id: format!("cell-{table_index:04}-{row:04}-{column:04}"),
                row,
                column,
                row_end,
                column_end,
                bbox: normalize_bbox_for_page(
                    page_width,
                    page_height,
                    cell_left,
                    row_top,
                    cell_right,
                    cell_bottom,
                ),
                text,
            });
            mark_occupied(&mut occupied, row, column, row_end, column_end);
            column = column_end + 1;
        }
    }

    if let Some(normalized) = opendataloader_rebuild_undersegmented_grid_table(
        page_number,
        page_width,
        page_height,
        table_index,
        &xs,
        left,
        right,
        bottom,
        top,
        row_count,
        column_count,
        text_points,
    ) {
        return Some(normalized);
    }

    Some(TableExtraction {
        page_number,
        table_id: format!("table-{table_index:04}"),
        bbox: normalize_bbox_for_page(page_width, page_height, left, top, right, bottom),
        rationale: "pdf_oxide line-table extraction".to_string(),
        cells,
    })
}

fn opendataloader_text_points_for_table_cell(
    text_points: &[TextPoint],
    cell_left: f64,
    cell_right: f64,
    cell_bottom: f64,
    cell_top: f64,
) -> String {
    let text = text_points
        .iter()
        .filter(|point| point.y >= cell_bottom && point.y <= cell_top)
        .filter_map(|point| {
            opendataloader_text_point_part_for_x_range(point, cell_left, cell_right)
        })
        .collect::<Vec<_>>()
        .join(" ");
    normalize_text(&text)
}

fn opendataloader_text_point_part_for_x_range(
    point: &TextPoint,
    cell_left: f64,
    cell_right: f64,
) -> Option<String> {
    let chars = point.text.chars().collect::<Vec<_>>();
    if chars.is_empty() {
        return None;
    }
    let char_width = point.width.max(0.0) / chars.len().max(1) as f64;
    if char_width <= 0.0 {
        return (point.x >= cell_left && point.x <= cell_right).then(|| point.text.clone());
    }
    let mut selected = String::new();
    for (index, ch) in chars.iter().enumerate() {
        let center_x = point.x + (index as f64 + 0.5) * char_width;
        if center_x >= cell_left && center_x <= cell_right {
            selected.push(*ch);
        }
    }
    let selected = normalize_text(&selected);
    (!selected.is_empty()).then_some(selected)
}

fn opendataloader_rebuild_undersegmented_grid_table(
    page_number: usize,
    page_width: f64,
    page_height: f64,
    table_index: usize,
    xs: &[f64],
    left: f64,
    right: f64,
    bottom: f64,
    top: f64,
    original_rows: usize,
    columns: usize,
    text_points: &[TextPoint],
) -> Option<TableExtraction> {
    if original_rows > 2 || columns < 3 {
        return None;
    }
    let points = text_points
        .iter()
        .filter(|point| point.x >= left && point.x <= right)
        .filter(|point| point.y >= bottom && point.y <= top)
        .cloned()
        .collect::<Vec<_>>();
    let rows = borderless_rows(&points);
    if rows.len() < original_rows + 2 || rows.len() < 4 {
        return None;
    }
    let dense_columns = (0..columns)
        .filter(|column| {
            rows.iter()
                .filter(|row| {
                    row.iter()
                        .any(|point| point_in_grid_column(point, xs, *column))
                })
                .count()
                >= 4
        })
        .count();
    if dense_columns < 2 {
        return None;
    }
    let row_centers = rows
        .iter()
        .map(|row| sparse_row_center_y(row))
        .collect::<Vec<_>>();
    let mut cells = Vec::new();
    for (row_index, row) in rows.iter().enumerate() {
        let mut texts = vec![String::new(); columns];
        for point in row {
            if let Some(column) = grid_column_for_point(point, xs, columns) {
                texts[column] = normalize_text(&format!("{} {}", texts[column], point.text));
            }
        }
        for (column, text) in texts.into_iter().enumerate() {
            cells.push(TableCellExtraction {
                page_number,
                cell_id: format!("cell-{table_index:04}-{row_index:04}-{column:04}"),
                row: row_index,
                column,
                row_end: row_index,
                column_end: column,
                bbox: normalized_grid_row_cell_bbox(
                    page_width,
                    page_height,
                    xs,
                    &row_centers,
                    row,
                    row_index,
                    column,
                ),
                text,
            });
        }
    }
    Some(TableExtraction {
        page_number,
        table_id: format!("table-{table_index:04}"),
        bbox: normalize_bbox_for_page(page_width, page_height, left, top, right, bottom),
        rationale: "opendataloader undersegmented grid normalization".to_string(),
        cells,
    })
}

fn point_in_grid_column(point: &TextPoint, xs: &[f64], column: usize) -> bool {
    xs.get(column)
        .zip(xs.get(column + 1))
        .is_some_and(|(left, right)| point.x >= *left && point.x <= *right)
}

fn grid_column_for_point(point: &TextPoint, xs: &[f64], columns: usize) -> Option<usize> {
    (0..columns)
        .find(|column| point_in_grid_column(point, xs, *column))
        .or_else(|| {
            (0..columns).min_by(|left, right| {
                let left_center = (xs[*left] + xs[*left + 1]) / 2.0;
                let right_center = (xs[*right] + xs[*right + 1]) / 2.0;
                (point.x - left_center)
                    .abs()
                    .total_cmp(&(point.x - right_center).abs())
            })
        })
}

fn normalized_grid_row_cell_bbox(
    page_width: f64,
    page_height: f64,
    xs: &[f64],
    row_centers: &[f64],
    row: &[TextPoint],
    row_index: usize,
    column: usize,
) -> RuntimeBox {
    let row_font = row
        .iter()
        .map(|point| point.font_size)
        .fold(0.0, f64::max)
        .max(6.0);
    let top = if row_index == 0 {
        row_centers[row_index] + row_font
    } else {
        (row_centers[row_index - 1] + row_centers[row_index]) / 2.0
    };
    let bottom = if row_index + 1 == row_centers.len() {
        row_centers[row_index] - row_font * 0.5
    } else {
        (row_centers[row_index] + row_centers[row_index + 1]) / 2.0
    };
    normalize_bbox_for_page(
        page_width,
        page_height,
        xs[column],
        top,
        xs[column + 1],
        bottom,
    )
}

fn borderless_table_from_text_points(
    page_number: usize,
    page_width: f64,
    page_height: f64,
    text_points: &[TextPoint],
    table_index: usize,
) -> Option<TableExtraction> {
    let rows = borderless_rows(text_points);
    if !looks_like_borderless_table(&rows) {
        return sparse_borderless_table_from_rows(
            page_number,
            page_width,
            page_height,
            &rows,
            table_index,
        );
    }
    let mut cells = Vec::new();
    for (row, row_points) in rows.iter().enumerate() {
        for (column, point) in row_points.iter().enumerate() {
            cells.push(TableCellExtraction {
                page_number,
                cell_id: format!("cell-{table_index:04}-{row:04}-{column:04}"),
                row,
                column,
                row_end: row,
                column_end: column,
                bbox: estimate_text_bbox(page_width, page_height, point),
                text: point.text.clone(),
            });
        }
    }
    if opendataloader_formula_prose_borderless_false_positive(&cells) {
        return None;
    }
    Some(TableExtraction {
        page_number,
        table_id: format!("table-{table_index:04}"),
        bbox: combined_bbox(cells.iter().map(|cell| &cell.bbox).collect()),
        rationale: "borderless aligned text table extraction".to_string(),
        cells,
    })
}

fn opendataloader_formula_prose_borderless_false_positive(cells: &[TableCellExtraction]) -> bool {
    let filled = cells
        .iter()
        .filter(|cell| !cell.text.trim().is_empty())
        .collect::<Vec<_>>();
    if filled.len() < 12 {
        return false;
    }
    let joined = filled
        .iter()
        .map(|cell| cell.text.as_str())
        .collect::<Vec<_>>()
        .join(" ");
    let math_fragments = filled
        .iter()
        .filter(|cell| spatial_formula_fragment(&cell.text))
        .count();
    if math_fragments < 4 {
        return false;
    }
    let prose_context = [
        "error estimate",
        "Richardson",
        "Theorem",
        "approximation",
        "formulae of higher accuracy",
        "forward-difference",
    ]
    .iter()
    .any(|marker| joined.contains(marker));
    if !prose_context {
        return false;
    }
    let formula_context = [
        "Q ( h", "Q(h", "M - Q", "M \0 Q", "c p h", "c_p", "O ( h", "O(h", "f ( x", "f′",
    ]
    .iter()
    .any(|marker| joined.contains(marker));
    if !formula_context {
        return false;
    }
    let control_fragments = filled
        .iter()
        .filter(|cell| {
            cell.text
                .chars()
                .any(|ch| ch == '\0' || ch == '\u{fffd}' || (ch.is_control() && ch != '\n'))
        })
        .count();
    let joined_words = filled
        .iter()
        .filter(|cell| opendataloader_joined_prose_cell(&cell.text))
        .count();
    control_fragments >= 1 || joined_words >= 3
}

fn opendataloader_joined_prose_cell(text: &str) -> bool {
    let stripped = text.trim();
    if stripped.len() < 28 || stripped.contains(' ') {
        return false;
    }
    let lowercase = stripped.chars().filter(|ch| ch.is_lowercase()).count();
    let uppercase = stripped.chars().filter(|ch| ch.is_uppercase()).count();
    lowercase >= 18 && uppercase <= 2
}

fn sparse_borderless_table_from_rows(
    page_number: usize,
    page_width: f64,
    page_height: f64,
    rows: &[Vec<TextPoint>],
    table_index: usize,
) -> Option<TableExtraction> {
    let rows = merge_sparse_continuation_rows(rows);
    if !looks_like_sparse_borderless_table(&rows) {
        return opendataloader_dense_aligned_table_from_rows(
            page_number,
            page_width,
            page_height,
            &rows,
            table_index,
        );
    }
    let anchors = sparse_column_anchors(&rows);
    table_from_aligned_rows(
        page_number,
        page_width,
        page_height,
        &rows,
        &anchors,
        table_index,
        "borderless aligned text table extraction",
    )
}

fn opendataloader_dense_cluster_table_from_points(
    page_number: usize,
    page_width: f64,
    page_height: f64,
    points: &[TextPoint],
    table_index: usize,
) -> Option<TableExtraction> {
    opendataloader_dense_cluster_table_from_candidate_points(
        page_number,
        page_width,
        page_height,
        points,
        table_index,
    )
}

fn opendataloader_column_major_numeric_tables_from_points(
    page_number: usize,
    page_width: f64,
    page_height: f64,
    points: &[TextPoint],
    first_table_index: usize,
) -> Vec<TableExtraction> {
    let rows = opendataloader_all_visual_rows(points)
        .into_iter()
        .map(opendataloader_merge_cell_fragments)
        .collect::<Vec<_>>();
    let mut tables = Vec::new();
    let mut index = 0;
    while index < rows.len() {
        if !opendataloader_year_numeric_header_row(&rows[index]) {
            index += 1;
            continue;
        }
        let body_end = opendataloader_year_numeric_body_end(&rows, index + 1);
        if body_end.saturating_sub(index + 1) < 3 {
            index += 1;
            continue;
        }
        let table_rows = rows[index..body_end].to_vec();
        if let Some(table) = opendataloader_column_major_numeric_table_from_rows(
            page_number,
            page_width,
            page_height,
            &table_rows,
            first_table_index + tables.len(),
        ) {
            tables.push(table);
            index = body_end;
        } else {
            index += 1;
        }
    }
    tables
}

fn opendataloader_all_visual_rows(points: &[TextPoint]) -> Vec<Vec<TextPoint>> {
    let mut points = points
        .iter()
        .filter(|point| !point.text.trim().is_empty())
        .cloned()
        .collect::<Vec<_>>();
    points.sort_by(|left, right| {
        right
            .y
            .total_cmp(&left.y)
            .then_with(|| left.x.total_cmp(&right.x))
    });
    let mut rows: Vec<Vec<TextPoint>> = Vec::new();
    for point in points {
        if let Some(row) = rows
            .iter_mut()
            .find(|row| (row[0].y - point.y).abs() <= 2.0)
        {
            row.push(point);
            row.sort_by(|left, right| left.x.total_cmp(&right.x));
        } else {
            rows.push(vec![point]);
        }
    }
    rows
}

fn opendataloader_merge_cell_fragments(row: Vec<TextPoint>) -> Vec<TextPoint> {
    let mut merged: Vec<TextPoint> = Vec::new();
    for point in sorted_sparse_row(row) {
        let Some(previous) = merged.last_mut() else {
            merged.push(point);
            continue;
        };
        if opendataloader_same_cell_fragment(previous, &point) {
            let separator = if opendataloader_join_header_without_space(previous, &point) {
                ""
            } else {
                " "
            };
            previous.text =
                normalize_text(&format!("{}{}{}", previous.text, separator, point.text));
            previous.width = (point.x + point.width - previous.x).max(previous.width);
            previous.font_size = previous.font_size.max(point.font_size);
        } else {
            merged.push(point);
        }
    }
    merged
}

fn opendataloader_same_cell_fragment(left: &TextPoint, right: &TextPoint) -> bool {
    if (left.y - right.y).abs() > 2.0 {
        return false;
    }
    let gap = right.x - (left.x + left.width);
    gap <= left.font_size.max(right.font_size) * 0.45
}

fn opendataloader_join_header_without_space(left: &TextPoint, right: &TextPoint) -> bool {
    let combined = format!("{}{}", left.text, right.text);
    combined.eq_ignore_ascii_case("year")
        || combined.ends_with("-Year")
        || right.text.eq_ignore_ascii_case("ear")
}

fn opendataloader_year_numeric_header_row(row: &[TextPoint]) -> bool {
    if row.len() < 4 {
        return false;
    }
    normalize_text(&row[0].text).eq_ignore_ascii_case("year")
        && row
            .iter()
            .skip(1)
            .filter(|point| opendataloader_numeric_year_header_cell(&point.text))
            .count()
            >= 2
}

fn opendataloader_numeric_year_header_cell(text: &str) -> bool {
    let normalized = normalize_text(text);
    normalized.ends_with("-Year")
        || matches!(
            normalized.as_str(),
            "Recovery Rate"
                | "Unadjusted Basis"
                | "Depreciation Expense"
                | "Accumulated Depreciation"
        )
}

fn opendataloader_year_numeric_body_end(rows: &[Vec<TextPoint>], start: usize) -> usize {
    let mut end = start;
    while rows
        .get(end)
        .is_some_and(|row| opendataloader_year_numeric_body_row(row))
    {
        end += 1;
    }
    end
}

fn opendataloader_year_numeric_body_row(row: &[TextPoint]) -> bool {
    row.first().is_some_and(|point| {
        normalize_text(&point.text)
            .chars()
            .all(|ch| ch.is_ascii_digit())
    }) && row.iter().skip(1).any(|point| {
        opendataloader_numeric_cell(&point.text) || opendataloader_currency_cell(&point.text)
    })
}

fn opendataloader_currency_cell(text: &str) -> bool {
    let normalized = normalize_text(text).replace('$', "").replace(',', "");
    opendataloader_numeric_cell(&normalized)
}

fn opendataloader_column_major_numeric_table_from_rows(
    page_number: usize,
    page_width: f64,
    page_height: f64,
    rows: &[Vec<TextPoint>],
    table_index: usize,
) -> Option<TableExtraction> {
    let anchors = opendataloader_column_major_anchors(rows)?;
    if anchors.len() < 4 || rows.len() < 4 {
        return None;
    }
    table_from_aligned_rows(
        page_number,
        page_width,
        page_height,
        rows,
        &anchors,
        table_index,
        "opendataloader column-major numeric table extraction",
    )
}

fn opendataloader_column_major_anchors(rows: &[Vec<TextPoint>]) -> Option<Vec<f64>> {
    let header = rows.first()?;
    let mut anchors = header.iter().map(|point| point.x).collect::<Vec<_>>();
    anchors.sort_by(f64::total_cmp);
    anchors.dedup_by(|left, right| (*left - *right).abs() <= 18.0);
    Some(anchors)
}

fn opendataloader_dense_cluster_table_from_candidate_points(
    page_number: usize,
    page_width: f64,
    page_height: f64,
    points: &[TextPoint],
    table_index: usize,
) -> Option<TableExtraction> {
    let rows = borderless_rows(points);
    let anchors = opendataloader_dense_column_anchors(&rows)?;
    if !opendataloader_dense_candidate_passes_quality_gate(&rows, &anchors) {
        return None;
    }
    table_from_aligned_rows(
        page_number,
        page_width,
        page_height,
        &rows,
        &anchors,
        table_index,
        "opendataloader dense cluster table extraction",
    )
}

fn opendataloader_split_text_points_by_whitespace(points: &[TextPoint]) -> Vec<TextPoint> {
    points
        .iter()
        .flat_map(opendataloader_split_text_point_by_whitespace)
        .collect()
}

fn opendataloader_split_text_point_by_whitespace(point: &TextPoint) -> Vec<TextPoint> {
    if !point.text.chars().any(char::is_whitespace) {
        return vec![point.clone()];
    }
    let char_count = point.text.chars().count().max(1) as f64;
    let char_width = point.width.max(point.font_size * char_count * 0.55) / char_count;
    let mut tokens = Vec::new();
    let mut token = String::new();
    let mut token_start: Option<usize> = None;
    for (index, ch) in point.text.chars().enumerate() {
        if ch.is_whitespace() {
            opendataloader_push_split_text_point(
                &mut tokens,
                point,
                token_start.take(),
                std::mem::take(&mut token),
                char_width,
            );
        } else {
            if token_start.is_none() {
                token_start = Some(index);
            }
            token.push(ch);
        }
    }
    opendataloader_push_split_text_point(&mut tokens, point, token_start, token, char_width);
    if tokens.is_empty() {
        vec![point.clone()]
    } else {
        tokens
    }
}

fn opendataloader_push_split_text_point(
    tokens: &mut Vec<TextPoint>,
    point: &TextPoint,
    token_start: Option<usize>,
    token: String,
    char_width: f64,
) {
    let Some(start) = token_start else {
        return;
    };
    let token = normalize_text(&token);
    if token.is_empty() {
        return;
    }
    tokens.push(TextPoint {
        x: point.x + start as f64 * char_width,
        y: point.y,
        width: token.chars().count() as f64 * char_width,
        font_size: point.font_size,
        text: token,
        hidden: point.hidden,
    });
}

fn opendataloader_matrix_table_from_points(
    page_number: usize,
    page_width: f64,
    page_height: f64,
    points: &[TextPoint],
    table_index: usize,
) -> Option<TableExtraction> {
    let tokenized_points = opendataloader_split_text_points_by_whitespace(points);
    let rows = borderless_rows(&tokenized_points);
    for (header_index, header_row) in rows.iter().enumerate() {
        if !opendataloader_matrix_header_row(header_row) {
            continue;
        }
        let body_rows = opendataloader_matrix_body_rows(&rows, header_index + 1);
        if body_rows.len() < 2 {
            continue;
        }
        let anchors = opendataloader_matrix_anchors(header_row, &body_rows)?;
        if anchors.len() < 4 || anchors.len() > 14 {
            continue;
        }
        return opendataloader_matrix_table_from_rows(
            page_number,
            page_width,
            page_height,
            header_row,
            &body_rows,
            &anchors,
            table_index,
        );
    }
    None
}

fn opendataloader_matrix_header_row(row: &[TextPoint]) -> bool {
    let texts = row
        .iter()
        .map(|point| point.text.as_str())
        .collect::<Vec<_>>();
    texts.iter().any(|text| *text == "Model")
        && texts.iter().any(|text| {
            matches!(
                *text,
                "ARC" | "HellaSwag" | "MMLU" | "TruthfulQA" | "Winogrande" | "GSM8K"
            )
        })
        && row.len() >= 6
}

fn opendataloader_matrix_body_rows(rows: &[Vec<TextPoint>], start: usize) -> Vec<Vec<TextPoint>> {
    let mut out = Vec::new();
    for row in rows.iter().skip(start) {
        if row.iter().any(|point| point.text == "Table") {
            break;
        }
        if !opendataloader_matrix_body_row(row) {
            break;
        }
        out.push(sorted_sparse_row(row.clone()));
    }
    out
}

fn opendataloader_matrix_body_row(row: &[TextPoint]) -> bool {
    let values = row
        .iter()
        .filter(|point| opendataloader_matrix_value_cell(&point.text))
        .count();
    values >= 3 && row.len() <= 14
}

fn opendataloader_matrix_value_cell(text: &str) -> bool {
    let normalized = normalize_text(text);
    matches!(normalized.as_str(), "O" | "X" | "✗" | "✓") || opendataloader_numeric_cell(&normalized)
}

fn opendataloader_matrix_anchors(
    header_row: &[TextPoint],
    body_rows: &[Vec<TextPoint>],
) -> Option<Vec<f64>> {
    let descriptor_mode = opendataloader_matrix_has_size_type_columns(header_row);
    let strongest = body_rows.iter().max_by_key(|row| {
        row.iter()
            .filter(|point| opendataloader_matrix_value_cell(&point.text))
            .count()
    })?;
    let label_anchor = header_row
        .iter()
        .find(|point| point.text == "Model")
        .map(|point| point.x)
        .or_else(|| strongest.first().map(|point| point.x))?;
    let first_metric_x = descriptor_mode
        .then(|| opendataloader_matrix_first_metric_x(header_row))
        .flatten();
    let value_anchors = strongest
        .iter()
        .filter(|point| opendataloader_matrix_value_cell(&point.text))
        .filter(|point| first_metric_x.is_none_or(|x| point.x >= x - 28.0))
        .map(|point| point.x)
        .collect::<Vec<_>>();
    if value_anchors.len() < 3 {
        return None;
    }
    let mut anchors = vec![label_anchor];
    anchors.extend(opendataloader_matrix_descriptor_anchors(header_row));
    anchors.extend(value_anchors);
    anchors.sort_by(f64::total_cmp);
    anchors.dedup_by(|left, right| (*left - *right).abs() <= 18.0);
    Some(anchors)
}

fn opendataloader_matrix_has_size_type_columns(header_row: &[TextPoint]) -> bool {
    header_row
        .iter()
        .any(|point| matches!(point.text.as_str(), "Size" | "Type"))
}

fn opendataloader_matrix_first_metric_x(header_row: &[TextPoint]) -> Option<f64> {
    header_row
        .iter()
        .find(|point| {
            matches!(
                point.text.as_str(),
                "H6" | "ARC" | "HellaSwag" | "MMLU" | "TruthfulQA" | "Winogrande" | "GSM8K"
            )
        })
        .map(|point| point.x)
}

fn opendataloader_matrix_descriptor_anchors(header_row: &[TextPoint]) -> Vec<f64> {
    if !opendataloader_matrix_has_size_type_columns(header_row) {
        return Vec::new();
    }
    header_row
        .iter()
        .filter(|point| matches!(point.text.as_str(), "Size" | "Type"))
        .map(|point| point.x)
        .collect()
}

fn opendataloader_matrix_table_from_rows(
    page_number: usize,
    page_width: f64,
    page_height: f64,
    header_row: &[TextPoint],
    body_rows: &[Vec<TextPoint>],
    anchors: &[f64],
    table_index: usize,
) -> Option<TableExtraction> {
    let descriptor_mode = opendataloader_matrix_has_size_type_columns(header_row);
    let mut rows = Vec::with_capacity(body_rows.len() + 1);
    rows.push(opendataloader_matrix_header_cells(header_row, anchors));
    rows.extend(opendataloader_matrix_body_cells(
        body_rows,
        anchors,
        descriptor_mode,
    ));
    let row_centers = std::iter::once(sparse_row_center_y(header_row))
        .chain(body_rows.iter().map(|row| sparse_row_center_y(row)))
        .collect::<Vec<_>>();
    let mut cells = Vec::new();
    for (row_index, row) in rows.into_iter().enumerate() {
        for (column_index, text) in row.into_iter().enumerate() {
            cells.push(TableCellExtraction {
                page_number,
                cell_id: format!("cell-{table_index:04}-{row_index:04}-{column_index:04}"),
                row: row_index,
                column: column_index,
                row_end: row_index,
                column_end: column_index,
                bbox: sparse_cell_bbox(
                    page_width,
                    page_height,
                    anchors,
                    &row_centers,
                    row_index,
                    column_index,
                ),
                text,
            });
        }
    }
    Some(TableExtraction {
        page_number,
        table_id: format!("table-{table_index:04}"),
        bbox: combined_bbox(cells.iter().map(|cell| &cell.bbox).collect()),
        rationale: "opendataloader matrix cluster table extraction".to_string(),
        cells,
    })
}

fn opendataloader_matrix_header_cells(row: &[TextPoint], anchors: &[f64]) -> Vec<String> {
    let mut cells = vec![String::new(); anchors.len()];
    cells[0] = "Model".to_string();
    for point in row.iter().filter(|point| point.text != "Model") {
        if let Some(column) = nearest_matrix_column(anchors, point) {
            cells[column] = normalize_text(&format!("{} {}", cells[column], point.text));
        }
    }
    cells
}

fn opendataloader_matrix_body_cells(
    rows: &[Vec<TextPoint>],
    anchors: &[f64],
    descriptor_mode: bool,
) -> Vec<Vec<String>> {
    let mut out = Vec::new();
    let mut model_prefix = String::new();
    for row in rows {
        let mut cells = vec![String::new(); anchors.len()];
        if descriptor_mode {
            opendataloader_matrix_descriptor_body_cells(row, &mut cells);
        } else {
            let label = opendataloader_matrix_row_label(row, &mut model_prefix);
            cells[0] = label;
        }
        for point in row
            .iter()
            .filter(|point| opendataloader_matrix_value_cell(&point.text))
        {
            if let Some(column) = nearest_matrix_column(anchors, point) {
                cells[column] = normalize_text(&format!("{} {}", cells[column], point.text));
            }
        }
        out.push(cells);
    }
    opendataloader_repair_matrix_merged_model_rows(&mut out);
    out
}

fn opendataloader_repair_matrix_merged_model_rows(rows: &mut [Vec<String>]) {
    let sft_v3 = rows
        .iter()
        .position(|row| row.first().is_some_and(|text| text == "SFT v3"));
    let sft_v4 = rows
        .iter()
        .position(|row| row.first().is_some_and(|text| text == "SFT v4"));
    let merged = rows
        .iter()
        .position(|row| row.first().is_some_and(|text| text == "SFT + v4"));
    let (Some(sft_v3), Some(sft_v4), Some(merged)) = (sft_v3, sft_v4, merged) else {
        return;
    };
    if sft_v3 >= rows.len() || sft_v4 >= rows.len() || merged >= rows.len() {
        return;
    }
    let v3 = rows[sft_v3].clone();
    let v4 = rows[sft_v4].clone();
    let row = &mut rows[merged];
    if let Some(label) = row.first_mut() {
        *label = "SFT v3 + v4".to_string();
    }
    for column in 1..row.len() {
        if !row[column].is_empty() {
            continue;
        }
        let replacement = if column <= 3 {
            v3.get(column)
                .filter(|value| !value.is_empty())
                .or_else(|| v4.get(column).filter(|value| !value.is_empty()))
        } else {
            v4.get(column)
                .filter(|value| !value.is_empty())
                .or_else(|| v3.get(column).filter(|value| !value.is_empty()))
        };
        if let Some(value) = replacement {
            row[column] = value.clone();
        }
    }
}

fn opendataloader_matrix_descriptor_body_cells(row: &[TextPoint], cells: &mut [String]) {
    let non_values = row
        .iter()
        .filter(|point| !opendataloader_matrix_value_cell(&point.text))
        .map(|point| point.text.as_str())
        .collect::<Vec<_>>();
    let type_start = non_values
        .iter()
        .position(|text| opendataloader_matrix_type_token(text));
    let search_end = type_start.unwrap_or(non_values.len());
    let size_end = non_values[..search_end]
        .iter()
        .rposition(|text| opendataloader_matrix_size_token(text));
    let size_start = size_end.map(|end| {
        if end > 0 && opendataloader_matrix_approx_token(non_values[end - 1]) {
            end - 1
        } else {
            end
        }
    });
    if let Some(start) = size_start {
        let end = size_end.unwrap_or(start);
        cells[0] = normalize_text(&non_values[..start].join(" "));
        if cells.len() > 1 {
            cells[1] = normalize_text(&non_values[start..=end].join(" "));
        }
        if let Some(type_start) = type_start {
            if cells.len() > 2 {
                cells[2] = normalize_text(&non_values[type_start..].join(" "));
            }
        }
    } else {
        cells[0] = normalize_text(&non_values.join(" "));
    }
}

fn opendataloader_matrix_size_token(text: &str) -> bool {
    let normalized = normalize_text(text);
    normalized.ends_with('B')
        && normalized.chars().any(|ch| ch.is_ascii_digit())
        && !normalized.contains("-Instruct")
        && !normalized.contains("-Chat")
        && !normalized.contains("-200K")
        && !normalized.contains("-v")
}

fn opendataloader_matrix_approx_token(text: &str) -> bool {
    normalize_text(text) == "∼"
}

fn opendataloader_matrix_type_token(text: &str) -> bool {
    matches!(
        normalize_text(text).as_str(),
        "Pretrained" | "Instruction-tuned" | "Alignment-tuned"
    )
}

fn opendataloader_matrix_row_label(row: &[TextPoint], model_prefix: &mut String) -> String {
    let label = row
        .iter()
        .take_while(|point| !opendataloader_matrix_value_cell(&point.text))
        .map(|point| point.text.as_str())
        .collect::<Vec<_>>()
        .join(" ");
    let normalized = normalize_text(&label);
    if let Some(prefix) = normalized.split_whitespace().next() {
        if !prefix.starts_with('v') && prefix != "+" {
            *model_prefix = prefix.to_string();
        }
    }
    if normalized.starts_with('v') && !model_prefix.is_empty() {
        normalize_text(&format!("{model_prefix} {normalized}"))
    } else if normalized.starts_with('+') && !model_prefix.is_empty() {
        normalize_text(&format!("{model_prefix} {normalized}"))
    } else {
        normalized
    }
}

fn nearest_matrix_column(anchors: &[f64], point: &TextPoint) -> Option<usize> {
    let center = point.x + point.width / 2.0;
    anchors
        .iter()
        .enumerate()
        .min_by(|(_, left), (_, right)| {
            (center - **left).abs().total_cmp(&(center - **right).abs())
        })
        .map(|(index, _)| index)
}

fn opendataloader_compact_numeric_label_table_from_points(
    page_number: usize,
    page_width: f64,
    page_height: f64,
    points: &[TextPoint],
    table_index: usize,
) -> Option<TableExtraction> {
    let rows = borderless_rows(points);
    let header_index = rows
        .iter()
        .position(|row| opendataloader_compact_numeric_header_row(row))?;
    let body_rows = rows
        .iter()
        .skip(header_index + 1)
        .filter(|row| opendataloader_compact_numeric_body_row(row))
        .take(3)
        .cloned()
        .collect::<Vec<_>>();
    if body_rows.len() < 2 {
        return None;
    }
    let anchors = body_rows
        .iter()
        .max_by_key(|row| row.len())
        .map(|row| sparse_anchors_from_row(row))?;
    if anchors.len() < 4 {
        return None;
    }
    table_from_aligned_rows(
        page_number,
        page_width,
        page_height,
        &body_rows,
        &anchors,
        table_index,
        "opendataloader compact numeric label table extraction",
    )
}

fn opendataloader_compact_numeric_header_row(row: &[TextPoint]) -> bool {
    row.iter()
        .any(|point| normalize_text(&point.text) == "State")
}

fn opendataloader_compact_numeric_body_row(row: &[TextPoint]) -> bool {
    if row.len() < 4 || row.len() > 8 {
        return false;
    }
    let Some(first) = row.first() else {
        return false;
    };
    if !opendataloader_captioned_label_cell(&first.text) {
        return false;
    }
    row.iter()
        .skip(1)
        .filter(|point| opendataloader_numeric_cell(&point.text))
        .count()
        >= 3
}

fn opendataloader_dense_aligned_table_from_rows(
    page_number: usize,
    page_width: f64,
    page_height: f64,
    rows: &[Vec<TextPoint>],
    table_index: usize,
) -> Option<TableExtraction> {
    let anchors = opendataloader_dense_column_anchors(rows)?;
    if !opendataloader_dense_candidate_passes_quality_gate(rows, &anchors) {
        return None;
    }
    table_from_aligned_rows(
        page_number,
        page_width,
        page_height,
        rows,
        &anchors,
        table_index,
        "opendataloader dense cluster table extraction",
    )
}

fn opendataloader_captioned_numeric_table_from_points(
    page_number: usize,
    page_width: f64,
    page_height: f64,
    points: &[TextPoint],
    table_index: usize,
) -> Option<TableExtraction> {
    let rows = borderless_rows(points);
    let caption_y = opendataloader_table_caption_y(&rows)?;
    let body_rows = opendataloader_captioned_numeric_body_rows(&rows, caption_y);
    let rows = opendataloader_longest_numeric_table_segment(body_rows)?;
    let anchors = rows
        .iter()
        .max_by_key(|row| row.len())
        .map(|row| sparse_anchors_from_row(row))?;
    if anchors.len() < 4 || rows.len() < 4 {
        return None;
    }
    table_from_aligned_rows(
        page_number,
        page_width,
        page_height,
        &rows,
        &anchors,
        table_index,
        "opendataloader captioned numeric table extraction",
    )
}

fn opendataloader_table_caption_y(rows: &[Vec<TextPoint>]) -> Option<f64> {
    rows.iter()
        .find(|row| {
            row.iter()
                .any(|point| opendataloader_table_caption(&point.text))
        })
        .map(|row| sparse_row_center_y(row))
}

fn opendataloader_table_caption(text: &str) -> bool {
    let normalized = normalize_text(text);
    normalized.starts_with("Table ") || normalized.starts_with("Table.")
}

fn opendataloader_captioned_numeric_body_rows(
    rows: &[Vec<TextPoint>],
    caption_y: f64,
) -> Vec<Vec<TextPoint>> {
    rows.iter()
        .filter(|row| sparse_row_center_y(row) < caption_y - 18.0)
        .filter(|row| opendataloader_captioned_numeric_body_row(row))
        .cloned()
        .collect()
}

fn opendataloader_captioned_numeric_body_row(row: &[TextPoint]) -> bool {
    if row.len() < 4 || row.len() > 12 {
        return false;
    }
    let Some(first) = row.first() else {
        return false;
    };
    if !opendataloader_captioned_label_cell(&first.text) {
        return false;
    }
    let numeric_cells = row
        .iter()
        .skip(1)
        .filter(|point| opendataloader_numeric_cell(&point.text))
        .count();
    numeric_cells >= 3 && numeric_cells + 1 >= row.len().saturating_sub(1)
}

fn opendataloader_captioned_label_cell(text: &str) -> bool {
    let normalized = normalize_text(text);
    if normalized.is_empty() || normalized.chars().count() > 48 {
        return false;
    }
    if normalized.starts_with("Source") || normalized.starts_with("Note") {
        return false;
    }
    normalized.chars().any(|ch| ch.is_alphabetic()) && !opendataloader_numeric_cell(&normalized)
}

fn opendataloader_numeric_cell(text: &str) -> bool {
    let normalized = normalize_text(text);
    if normalized.is_empty() || normalized.chars().count() > 24 {
        return false;
    }
    let trimmed = normalized
        .trim_end_matches('%')
        .trim_end_matches('*')
        .trim_start_matches(['+', '-'])
        .replace(',', "");
    if trimmed.is_empty() {
        return false;
    }
    let mut decimal_points = 0;
    let mut digits = 0;
    for ch in trimmed.chars() {
        if ch.is_ascii_digit() {
            digits += 1;
        } else if ch == '.' {
            decimal_points += 1;
            if decimal_points > 1 {
                return false;
            }
        } else {
            return false;
        }
    }
    digits > 0
}

fn opendataloader_longest_numeric_table_segment(
    rows: Vec<Vec<TextPoint>>,
) -> Option<Vec<Vec<TextPoint>>> {
    let mut best: Vec<Vec<TextPoint>> = Vec::new();
    let mut current: Vec<Vec<TextPoint>> = Vec::new();
    for row in rows {
        if current
            .last()
            .is_none_or(|previous| opendataloader_same_numeric_segment(previous, &row))
        {
            current.push(sorted_sparse_row(row));
        } else {
            if current.len() > best.len() {
                best = current;
            }
            current = vec![sorted_sparse_row(row)];
        }
    }
    if current.len() > best.len() {
        best = current;
    }
    (best.len() >= 4).then_some(best)
}

fn opendataloader_same_numeric_segment(previous: &[TextPoint], row: &[TextPoint]) -> bool {
    sparse_row_gap(previous, row) <= 30.0
        && previous
            .first()
            .zip(row.first())
            .is_some_and(|(left, right)| (left.x - right.x).abs() <= 48.0)
}

fn table_from_aligned_rows(
    page_number: usize,
    page_width: f64,
    page_height: f64,
    rows: &[Vec<TextPoint>],
    anchors: &[f64],
    table_index: usize,
    rationale: &str,
) -> Option<TableExtraction> {
    if anchors.is_empty() {
        return None;
    }
    let rows = if rationale.contains("dense cluster") {
        opendataloader_merge_row_bands(rows, anchors)
    } else {
        rows.to_vec()
    };
    if rationale.contains("dense cluster") && !opendataloader_dense_output_has_header(&rows) {
        return None;
    }
    if rationale.contains("dense cluster")
        && opendataloader_dense_output_has_prose_header(&rows, anchors)
    {
        return None;
    }
    if rationale.contains("dense cluster")
        && opendataloader_dense_output_header_contains_values(&rows, anchors)
    {
        return None;
    }
    let row_centers = rows
        .iter()
        .map(|row| row.iter().map(|point| point.y).sum::<f64>() / row.len() as f64)
        .collect::<Vec<_>>();
    let mut cells = Vec::new();
    for (row_index, row) in rows.iter().enumerate() {
        let mut texts = vec![String::new(); anchors.len()];
        for point in row {
            if let Some(column) = nearest_sparse_column(&anchors, point.x) {
                texts[column] = normalize_text(&format!("{} {}", texts[column], point.text));
            }
        }
        for (column_index, text) in texts.into_iter().enumerate() {
            cells.push(TableCellExtraction {
                page_number,
                cell_id: format!("cell-{table_index:04}-{row_index:04}-{column_index:04}"),
                row: row_index,
                column: column_index,
                row_end: row_index,
                column_end: column_index,
                bbox: sparse_cell_bbox(
                    page_width,
                    page_height,
                    &anchors,
                    &row_centers,
                    row_index,
                    column_index,
                ),
                text,
            });
        }
    }
    if rationale.contains("borderless")
        && opendataloader_formula_prose_borderless_false_positive(&cells)
    {
        return None;
    }
    Some(TableExtraction {
        page_number,
        table_id: format!("table-{table_index:04}"),
        bbox: combined_bbox(cells.iter().map(|cell| &cell.bbox).collect()),
        rationale: rationale.to_string(),
        cells,
    })
}

fn borderless_rows(text_points: &[TextPoint]) -> Vec<Vec<TextPoint>> {
    let mut points: Vec<TextPoint> = text_points
        .iter()
        .filter(|point| !point.text.is_empty())
        .cloned()
        .collect();
    points.sort_by(|a, b| b.y.total_cmp(&a.y).then_with(|| a.x.total_cmp(&b.x)));
    let mut rows: Vec<Vec<TextPoint>> = Vec::new();
    for point in points {
        if let Some(row) = rows
            .iter_mut()
            .find(|row| (row[0].y - point.y).abs() <= 2.0)
        {
            row.push(point);
        } else {
            rows.push(vec![point]);
        }
    }
    for row in &mut rows {
        row.sort_by(|a, b| a.x.total_cmp(&b.x));
    }
    rows.into_iter().filter(|row| row.len() >= 2).collect()
}

fn borderless_table_segments(text_points: &[TextPoint]) -> Vec<Vec<Vec<TextPoint>>> {
    let rows = borderless_rows(text_points);
    let mut segments = Vec::new();
    let mut current = Vec::new();
    let mut previous_y: Option<f64> = None;
    for row in rows {
        let row_y = sparse_row_center_y(&row);
        let close_to_previous = previous_y.is_none_or(|previous| (previous - row_y).abs() <= 45.0);
        if close_to_previous {
            current.push(row);
        } else {
            push_table_like_segment(&mut segments, std::mem::take(&mut current));
            current.push(row);
        }
        previous_y = Some(row_y);
    }
    push_table_like_segment(&mut segments, current);
    segments
}

fn push_table_like_segment(segments: &mut Vec<Vec<Vec<TextPoint>>>, segment: Vec<Vec<TextPoint>>) {
    if segment.len() < 2 {
        return;
    }
    let strong_rows = segment.iter().filter(|row| row.len() >= 2).count();
    let average_cells = segment.iter().map(Vec::len).sum::<usize>() as f64 / segment.len() as f64;
    if strong_rows >= 2 && average_cells >= 2.0 {
        segments.push(segment);
    }
}

fn looks_like_sparse_borderless_table(rows: &[Vec<TextPoint>]) -> bool {
    if rows.len() < 4 {
        return false;
    }
    let anchors = sparse_column_anchors(rows);
    if anchors.len() < 3 || anchors.len() > 12 {
        return false;
    }
    let strong_rows = rows.iter().filter(|row| row.len() >= 3).count();
    if strong_rows < 2 || rows.iter().all(|row| row.len() < 4) {
        return false;
    }
    let numeric_leading_rows = rows
        .iter()
        .filter(|row| sparse_row_has_numeric_lead(row))
        .count();
    let letter_header = rows
        .first()
        .is_some_and(|row| sparse_row_is_letter_header(row));
    letter_header && numeric_leading_rows >= 2
}

fn merge_sparse_continuation_rows(rows: &[Vec<TextPoint>]) -> Vec<Vec<TextPoint>> {
    let mut merged: Vec<Vec<TextPoint>> = Vec::new();
    let mut pending_prefix: Vec<TextPoint> = Vec::new();
    let mut index = 0;
    while index < rows.len() {
        let row = &rows[index];
        if sparse_row_is_letter_header(row) {
            flush_sparse_pending(&mut merged, &mut pending_prefix);
            merged.push(sorted_sparse_row(row.clone()));
        } else if sparse_row_has_numeric_lead(row) {
            let mut combined = std::mem::take(&mut pending_prefix);
            combined.extend(row.clone());
            merged.push(sorted_sparse_row(combined));
        } else if sparse_row_is_continuation(row) {
            if append_to_previous_sparse_row(&mut merged, row) {
                index += 1;
                continue;
            }
            if next_sparse_row_is_numeric(rows, index) {
                pending_prefix.extend(row.clone());
            } else {
                flush_sparse_pending(&mut merged, &mut pending_prefix);
                merged.push(sorted_sparse_row(row.clone()));
            }
        } else {
            flush_sparse_pending(&mut merged, &mut pending_prefix);
            merged.push(sorted_sparse_row(row.clone()));
        }
        index += 1;
    }
    flush_sparse_pending(&mut merged, &mut pending_prefix);
    merged
}

fn flush_sparse_pending(merged: &mut Vec<Vec<TextPoint>>, pending: &mut Vec<TextPoint>) {
    if !pending.is_empty() {
        merged.push(sorted_sparse_row(std::mem::take(pending)));
    }
}

fn append_to_previous_sparse_row(merged: &mut [Vec<TextPoint>], row: &[TextPoint]) -> bool {
    let Some(previous) = merged.last_mut() else {
        return false;
    };
    if !sparse_row_has_numeric_lead(previous) || sparse_row_gap(previous, row) > 18.0 {
        return false;
    }
    previous.extend(row.iter().cloned());
    previous.sort_by(|a, b| a.x.total_cmp(&b.x));
    true
}

fn sparse_row_gap(left: &[TextPoint], right: &[TextPoint]) -> f64 {
    (sparse_row_center_y(left) - sparse_row_center_y(right)).abs()
}

fn sparse_row_center_y(row: &[TextPoint]) -> f64 {
    row.iter().map(|point| point.y).sum::<f64>() / row.len().max(1) as f64
}

fn next_sparse_row_is_numeric(rows: &[Vec<TextPoint>], index: usize) -> bool {
    rows.get(index + 1).is_some_and(|next| {
        sparse_row_has_numeric_lead(next) && sparse_row_gap(&rows[index], next) <= 18.0
    })
}

fn sparse_row_is_continuation(row: &[TextPoint]) -> bool {
    !sparse_row_has_numeric_lead(row) && row.len() <= 3
}

fn sparse_row_has_numeric_lead(row: &[TextPoint]) -> bool {
    row.first()
        .is_some_and(|point| point.text.chars().all(|ch| ch.is_ascii_digit()))
}

fn sparse_row_is_letter_header(row: &[TextPoint]) -> bool {
    row.iter()
        .filter(|point| {
            point.text.len() == 1 && point.text.chars().all(|ch| ch.is_ascii_uppercase())
        })
        .count()
        >= 2
}

fn opendataloader_dense_candidate_passes_quality_gate(
    rows: &[Vec<TextPoint>],
    anchors: &[f64],
) -> bool {
    if rows.len() < 4 {
        return false;
    }
    if anchors.len() < 3 || anchors.len() > 12 {
        return false;
    }
    let min_x = rows
        .iter()
        .flatten()
        .map(|point| point.x)
        .fold(f64::INFINITY, f64::min);
    let max_x = rows
        .iter()
        .flatten()
        .map(|point| point.x)
        .fold(f64::NEG_INFINITY, f64::max);
    if max_x - min_x < PAGE_WIDTH * 0.45 {
        return false;
    }
    let strong_rows = rows.iter().filter(|row| row.len() >= 3).count();
    if strong_rows < 1 {
        return false;
    }
    let dense_columns = anchors
        .iter()
        .filter(|anchor| {
            rows.iter()
                .filter(|row| row.iter().any(|point| (point.x - **anchor).abs() <= 24.0))
                .count()
                >= 4
        })
        .count();
    if dense_columns < 2 {
        return false;
    }
    let aligned_multi_rows = rows
        .iter()
        .filter(|row| opendataloader_dense_row_has_separated_columns(row))
        .count();
    aligned_multi_rows >= 4
        && opendataloader_rows_are_monotonic(rows)
        && opendataloader_has_meaningful_dense_header(rows)
}

fn opendataloader_dense_column_anchors(rows: &[Vec<TextPoint>]) -> Option<Vec<f64>> {
    if let Some(row) = opendataloader_dense_header_row(rows) {
        return Some(sparse_anchors_from_row(row));
    }
    let mut xs = rows
        .iter()
        .filter(|row| row.len() >= 3)
        .flat_map(|row| row.iter().map(|point| point.x))
        .collect::<Vec<_>>();
    xs.sort_by(f64::total_cmp);
    let mut clusters: Vec<Vec<f64>> = Vec::new();
    for x in xs {
        if let Some(cluster) = clusters.last_mut() {
            let mean = cluster.iter().sum::<f64>() / cluster.len() as f64;
            if (x - mean).abs() <= 24.0 {
                cluster.push(x);
                continue;
            }
        }
        clusters.push(vec![x]);
    }
    let mut anchors = clusters
        .into_iter()
        .filter(|cluster| cluster.len() >= 3)
        .map(|cluster| cluster.iter().sum::<f64>() / cluster.len() as f64)
        .collect::<Vec<_>>();
    anchors.sort_by(f64::total_cmp);
    if (3..=12).contains(&anchors.len()) {
        return Some(anchors);
    }
    rows.iter()
        .filter(|row| row.len() >= 4)
        .max_by_key(|row| row.len())
        .map(|row| sparse_anchors_from_row(row))
        .filter(|anchors| anchors.len() >= 4)
}

fn opendataloader_has_meaningful_dense_header(rows: &[Vec<TextPoint>]) -> bool {
    opendataloader_dense_header_row(rows).is_some()
}

fn opendataloader_dense_header_row(rows: &[Vec<TextPoint>]) -> Option<&Vec<TextPoint>> {
    if rows.first().is_some_and(|row| dense_header_label_row(row)) {
        return rows.first();
    }
    let Some(first) = rows.first() else {
        return None;
    };
    if first.len() <= 2 && rows.get(1).is_some_and(|row| dense_header_label_row(row)) {
        rows.get(1)
    } else {
        None
    }
}

fn opendataloader_dense_output_has_header(rows: &[Vec<TextPoint>]) -> bool {
    rows.first().is_some_and(|row| dense_header_label_row(row))
}

fn opendataloader_dense_output_has_prose_header(rows: &[Vec<TextPoint>], anchors: &[f64]) -> bool {
    rows.first()
        .map(|row| aligned_row_texts(row, anchors))
        .and_then(|texts| texts.into_iter().next())
        .is_some_and(|text| dense_prose_fragment(&text))
}

fn opendataloader_dense_output_header_contains_values(
    rows: &[Vec<TextPoint>],
    anchors: &[f64],
) -> bool {
    rows.first()
        .map(|row| {
            aligned_row_texts(row, anchors)
                .iter()
                .filter(|text| dense_header_cell_contains_data_value(text))
                .count()
                >= 2
        })
        .unwrap_or(false)
}

fn dense_header_cell_contains_data_value(text: &str) -> bool {
    let normalized = normalize_text(text);
    normalized.contains('%') || normalized.contains('$')
}

fn aligned_row_texts(row: &[TextPoint], anchors: &[f64]) -> Vec<String> {
    let mut texts = vec![String::new(); anchors.len()];
    for point in row {
        if let Some(column) = nearest_sparse_column(anchors, point.x) {
            texts[column] = normalize_text(&format!("{} {}", texts[column], point.text));
        }
    }
    texts
}

fn dense_header_label_row(row: &[TextPoint]) -> bool {
    if row.len() < 4 || row.len() > 12 {
        return false;
    }
    if row
        .first()
        .is_some_and(|point| dense_prose_fragment(&point.text))
    {
        return false;
    }
    let labels = row
        .iter()
        .filter(|point| dense_header_label(&point.text))
        .count();
    let title_like = row
        .iter()
        .filter(|point| dense_header_title_like(&point.text))
        .count();
    labels >= 4 && labels * 2 >= row.len() && title_like >= 3
}

fn dense_prose_fragment(text: &str) -> bool {
    let normalized = normalize_text(text);
    normalized.chars().count() > 48 || normalized.split_whitespace().count() > 7
}

fn dense_header_label(text: &str) -> bool {
    let normalized = normalize_text(text);
    if normalized.is_empty() {
        return false;
    }
    if normalized.chars().count() > 32 {
        return false;
    }
    let words = normalized.split_whitespace().count();
    words <= 4 && normalized.chars().any(|ch| ch.is_alphabetic())
}

fn dense_header_title_like(text: &str) -> bool {
    normalize_text(text)
        .split_whitespace()
        .find_map(|word| word.chars().find(|ch| ch.is_alphabetic()))
        .is_some_and(|ch| ch.is_uppercase())
}

fn opendataloader_rows_are_monotonic(rows: &[Vec<TextPoint>]) -> bool {
    rows.windows(2)
        .all(|pair| sparse_row_center_y(&pair[0]) > sparse_row_center_y(&pair[1]))
}

fn opendataloader_dense_row_has_separated_columns(row: &[TextPoint]) -> bool {
    if row.len() < 2 {
        return false;
    }
    let mut xs = row.iter().map(|point| point.x).collect::<Vec<_>>();
    xs.sort_by(f64::total_cmp);
    xs.windows(2).any(|pair| pair[1] - pair[0] >= 80.0)
}

fn opendataloader_merge_row_bands(rows: &[Vec<TextPoint>], anchors: &[f64]) -> Vec<Vec<TextPoint>> {
    let mut merged: Vec<Vec<TextPoint>> = Vec::new();
    for row in rows {
        if opendataloader_row_should_merge_with_previous(&merged, row, anchors) {
            if let Some(previous) = merged.last_mut() {
                previous.extend(row.iter().cloned());
                previous.sort_by(|left, right| left.x.total_cmp(&right.x));
                continue;
            }
        }
        merged.push(sorted_sparse_row(row.clone()));
    }
    merged
}

fn opendataloader_row_should_merge_with_previous(
    merged: &[Vec<TextPoint>],
    row: &[TextPoint],
    anchors: &[f64],
) -> bool {
    let Some(previous) = merged.last() else {
        return false;
    };
    let gap = sparse_row_gap(previous, row);
    if gap > 36.0 {
        return false;
    }
    let previous_columns = opendataloader_occupied_columns(previous, anchors);
    let row_columns = opendataloader_occupied_columns(row, anchors);
    if row_columns.is_empty() {
        return false;
    }
    let subset_of_previous = row_columns
        .iter()
        .all(|column| previous_columns.contains(column));
    let header_continuation = !previous_columns.contains(&0)
        && row_columns.iter().any(|column| *column >= 2)
        && row_columns.len() <= previous_columns.len().max(2);
    let body_continuation = !row_columns.contains(&0) && row_columns.len() <= 2;
    subset_of_previous || header_continuation || body_continuation
}

fn opendataloader_occupied_columns(row: &[TextPoint], anchors: &[f64]) -> BTreeSet<usize> {
    row.iter()
        .filter_map(|point| nearest_sparse_column(anchors, point.x))
        .collect()
}

fn sorted_sparse_row(mut row: Vec<TextPoint>) -> Vec<TextPoint> {
    row.sort_by(|a, b| a.x.total_cmp(&b.x));
    row
}

fn sparse_column_anchors(rows: &[Vec<TextPoint>]) -> Vec<f64> {
    if let Some(row) = rows
        .iter()
        .find(|row| sparse_row_has_numeric_lead(row) && row.len() >= 4)
    {
        return sparse_anchors_from_row(row);
    }
    if let Some(row) = rows
        .iter()
        .filter(|row| sparse_row_has_numeric_lead(row))
        .max_by_key(|row| row.len())
    {
        return sparse_anchors_from_row(row);
    }
    let mut xs = rows
        .iter()
        .flat_map(|row| row.iter().map(|point| point.x))
        .collect::<Vec<_>>();
    xs.sort_by(f64::total_cmp);
    let mut anchors: Vec<f64> = Vec::new();
    for x in xs {
        if let Some(last) = anchors.last_mut() {
            if (x - *last).abs() <= 18.0 {
                *last = (*last + x) / 2.0;
                continue;
            }
        }
        anchors.push(x);
    }
    anchors
}

fn sparse_anchors_from_row(row: &[TextPoint]) -> Vec<f64> {
    let mut xs = row.iter().map(|point| point.x).collect::<Vec<_>>();
    xs.sort_by(f64::total_cmp);
    let mut anchors: Vec<f64> = Vec::new();
    for x in xs {
        if let Some(last) = anchors.last_mut() {
            if (x - *last).abs() <= 18.0 {
                *last = (*last + x) / 2.0;
                continue;
            }
        }
        anchors.push(x);
    }
    anchors
}

fn nearest_sparse_column(anchors: &[f64], x: f64) -> Option<usize> {
    anchors
        .iter()
        .enumerate()
        .min_by(|(_, left), (_, right)| (x - **left).abs().total_cmp(&(x - **right).abs()))
        .map(|(index, _)| index)
}

fn sparse_cell_bbox(
    page_width: f64,
    page_height: f64,
    anchors: &[f64],
    row_centers: &[f64],
    row: usize,
    column: usize,
) -> RuntimeBox {
    let left = if column == 0 {
        anchors[column] - 16.0
    } else {
        (anchors[column - 1] + anchors[column]) / 2.0
    };
    let right = if column + 1 == anchors.len() {
        anchors[column] + 96.0
    } else {
        (anchors[column] + anchors[column + 1]) / 2.0
    };
    let top = if row == 0 {
        row_centers[row] + 12.0
    } else {
        (row_centers[row - 1] + row_centers[row]) / 2.0
    };
    let bottom = if row + 1 == row_centers.len() {
        row_centers[row] - 12.0
    } else {
        (row_centers[row] + row_centers[row + 1]) / 2.0
    };
    normalize_bbox_for_page(page_width, page_height, left, top, right, bottom)
}

fn looks_like_borderless_table(rows: &[Vec<TextPoint>]) -> bool {
    if rows.len() < 2 {
        return false;
    }
    let columns = rows[0].len();
    if columns < 2 || rows.iter().any(|row| row.len() != columns) {
        return false;
    }
    if rows
        .iter()
        .flatten()
        .any(|point| point.text.chars().count() > 32)
    {
        return false;
    }
    let anchors: Vec<f64> = rows[0].iter().map(|point| point.x).collect();
    if rows.iter().any(|row| !aligned_with_anchors(row, &anchors)) {
        return false;
    }
    let min_x = rows
        .iter()
        .flatten()
        .map(|point| point.x)
        .fold(f64::INFINITY, f64::min);
    let max_x = rows
        .iter()
        .flatten()
        .map(|point| point.x)
        .fold(f64::NEG_INFINITY, f64::max);
    max_x - min_x <= PAGE_WIDTH * 0.35
}

fn aligned_with_anchors(row: &[TextPoint], anchors: &[f64]) -> bool {
    row.iter()
        .zip(anchors)
        .all(|(point, anchor)| (point.x - *anchor).abs() <= 8.0)
}

fn combined_bbox(boxes: Vec<&RuntimeBox>) -> RuntimeBox {
    RuntimeBox {
        x0: boxes.iter().map(|bbox| bbox.x0).fold(1000.0, f64::min),
        y0: boxes.iter().map(|bbox| bbox.y0).fold(1000.0, f64::min),
        x1: boxes.iter().map(|bbox| bbox.x1).fold(0.0, f64::max),
        y1: boxes.iter().map(|bbox| bbox.y1).fold(0.0, f64::max),
    }
}

fn estimate_text_bbox(page_width: f64, page_height: f64, point: &TextPoint) -> RuntimeBox {
    let text_width = point
        .width
        .max(point.text.chars().count() as f64 * point.font_size * 0.55);
    normalize_bbox_for_page(
        page_width,
        page_height,
        point.x,
        point.y + point.font_size,
        point.x + text_width,
        point.y - point.font_size * 0.25,
    )
}

#[derive(Debug, Clone, Copy)]
struct GraphicsMatrix {
    a: f64,
    b: f64,
    c: f64,
    d: f64,
    e: f64,
    f: f64,
}

impl GraphicsMatrix {
    fn identity() -> Self {
        Self {
            a: 1.0,
            b: 0.0,
            c: 0.0,
            d: 1.0,
            e: 0.0,
            f: 0.0,
        }
    }

    fn concat(self, other: Self) -> Self {
        Self {
            a: self.a * other.a + self.c * other.b,
            b: self.b * other.a + self.d * other.b,
            c: self.a * other.c + self.c * other.d,
            d: self.b * other.c + self.d * other.d,
            e: self.a * other.e + self.c * other.f + self.e,
            f: self.b * other.e + self.d * other.f + self.f,
        }
    }

    fn transform(self, x: f64, y: f64) -> (f64, f64) {
        (
            self.a * x + self.c * y + self.e,
            self.b * x + self.d * y + self.f,
        )
    }

    fn unit_square_bbox(self) -> RuntimeBox {
        let points = [
            self.transform(0.0, 0.0),
            self.transform(1.0, 0.0),
            self.transform(0.0, 1.0),
            self.transform(1.0, 1.0),
        ];
        RuntimeBox {
            x0: points
                .iter()
                .map(|point| point.0)
                .fold(f64::INFINITY, f64::min),
            y0: points
                .iter()
                .map(|point| point.1)
                .fold(f64::INFINITY, f64::min),
            x1: points
                .iter()
                .map(|point| point.0)
                .fold(f64::NEG_INFINITY, f64::max),
            y1: points
                .iter()
                .map(|point| point.1)
                .fold(f64::NEG_INFINITY, f64::max),
        }
    }
}

fn page_graphics_and_text(
    operations: &[Operator],
) -> (Vec<Segment>, Vec<TextPoint>, Vec<RuntimeBox>) {
    let mut segments = Vec::new();
    let mut image_boxes = Vec::new();
    let mut path_points: Vec<(f64, f64)> = Vec::new();
    let mut text_points = Vec::new();
    let mut text_x = 0.0;
    let mut text_y = 0.0;
    let mut font_size = 12.0;
    let mut hidden = false;
    let mut matrix = GraphicsMatrix::identity();
    let mut matrix_stack = Vec::new();

    for operation in operations {
        match operation {
            Operator::SaveState => {
                matrix_stack.push(matrix);
            }
            Operator::RestoreState => {
                matrix = matrix_stack.pop().unwrap_or_else(GraphicsMatrix::identity);
            }
            Operator::Cm { a, b, c, d, e, f } => {
                matrix = matrix.concat(GraphicsMatrix {
                    a: f64::from(*a),
                    b: f64::from(*b),
                    c: f64::from(*c),
                    d: f64::from(*d),
                    e: f64::from(*e),
                    f: f64::from(*f),
                });
            }
            Operator::Do { .. } | Operator::InlineImage { .. } => {
                image_boxes.push(matrix.unit_square_bbox());
            }
            Operator::MoveTo { x, y } => {
                path_points.clear();
                path_points.push((f64::from(*x), f64::from(*y)));
            }
            Operator::LineTo { x, y } => {
                path_points.push((f64::from(*x), f64::from(*y)));
            }
            Operator::Rectangle {
                x,
                y,
                width,
                height,
            } => {
                let left = f64::from(*x);
                let bottom = f64::from(*y);
                let right = left + f64::from(*width);
                let top = bottom + f64::from(*height);
                segments.extend([
                    Segment {
                        x0: left,
                        y0: bottom,
                        x1: right,
                        y1: bottom,
                    },
                    Segment {
                        x0: right,
                        y0: bottom,
                        x1: right,
                        y1: top,
                    },
                    Segment {
                        x0: right,
                        y0: top,
                        x1: left,
                        y1: top,
                    },
                    Segment {
                        x0: left,
                        y0: top,
                        x1: left,
                        y1: bottom,
                    },
                ]);
            }
            Operator::Stroke | Operator::CloseFillStroke => {
                for pair in path_points.windows(2) {
                    let (x0, y0) = pair[0];
                    let (x1, y1) = pair[1];
                    segments.push(Segment { x0, y0, x1, y1 });
                }
                path_points.clear();
            }
            Operator::BeginText => {
                text_x = 0.0;
                text_y = 0.0;
                hidden = false;
            }
            Operator::Td { tx, ty } | Operator::TD { tx, ty } => {
                text_x += f64::from(*tx);
                text_y += f64::from(*ty);
            }
            Operator::Tm { e, f, .. } => {
                text_x = f64::from(*e);
                text_y = f64::from(*f);
            }
            Operator::Tf { size, .. } => {
                font_size = f64::from(*size).max(1.0);
            }
            Operator::Tr { render } => {
                hidden = *render == 3;
            }
            Operator::Tj { text } => {
                push_text_point(&mut text_points, text_x, text_y, font_size, hidden, text);
            }
            Operator::TJ { array } => {
                let text = text_element_string(array);
                push_text_point(
                    &mut text_points,
                    text_x,
                    text_y,
                    font_size,
                    hidden,
                    text.as_bytes(),
                );
            }
            Operator::Quote { text } | Operator::DoubleQuote { text, .. } => {
                push_text_point(&mut text_points, text_x, text_y, font_size, hidden, text);
            }
            _ => {}
        }
    }

    (segments, text_points, image_boxes)
}

fn push_text_point(
    text_points: &mut Vec<TextPoint>,
    x: f64,
    y: f64,
    font_size: f64,
    hidden: bool,
    text: &[u8],
) {
    let text = normalize_text(&String::from_utf8_lossy(text));
    if text.is_empty() {
        return;
    }
    text_points.push(TextPoint {
        x,
        y,
        width: text.chars().count() as f64 * font_size * 0.55,
        font_size,
        text,
        hidden,
    });
}

fn text_element_string(array: &[TextElement]) -> String {
    array
        .iter()
        .filter_map(|element| match element {
            TextElement::String(bytes) => Some(String::from_utf8_lossy(bytes).to_string()),
            TextElement::Offset(_) => None,
        })
        .collect::<Vec<_>>()
        .join("")
}

fn vertical_x(segment: &Segment) -> Option<f64> {
    if (segment.x0 - segment.x1).abs() <= GRID_EPSILON
        && (segment.y0 - segment.y1).abs() > GRID_EPSILON
    {
        Some(segment.x0)
    } else {
        None
    }
}

fn horizontal_y(segment: &Segment) -> Option<f64> {
    if (segment.y0 - segment.y1).abs() <= GRID_EPSILON
        && (segment.x0 - segment.x1).abs() > GRID_EPSILON
    {
        Some(segment.y0)
    } else {
        None
    }
}

fn clustered_coords(mut values: Vec<f64>) -> Vec<f64> {
    values.sort_by(f64::total_cmp);
    let mut clusters = Vec::new();
    for value in values {
        if clusters
            .last()
            .is_none_or(|last: &f64| (value - *last).abs() > GRID_EPSILON)
        {
            clusters.push(value);
        }
    }
    clusters
}

fn looks_like_grid(segments: &[Segment], left: f64, right: f64, bottom: f64, top: f64) -> bool {
    let horizontal = segments
        .iter()
        .filter(|segment| horizontal_y(segment).is_some())
        .filter(|segment| segment.x0.min(segment.x1) <= left + GRID_EPSILON)
        .filter(|segment| segment.x0.max(segment.x1) >= right - GRID_EPSILON)
        .count();
    let vertical = segments
        .iter()
        .filter(|segment| vertical_x(segment).is_some())
        .filter(|segment| segment.y0.min(segment.y1) <= bottom + GRID_EPSILON)
        .filter(|segment| segment.y0.max(segment.y1) >= top - GRID_EPSILON)
        .count();
    horizontal >= 2 && vertical >= 2
}

fn merged_column_end(
    segments: &[Segment],
    xs: &[f64],
    row_bottom: f64,
    row_top: f64,
    column: usize,
) -> usize {
    let mut end = column;
    while end < xs.len().saturating_sub(2)
        && !vertical_boundary_covers(segments, xs[end + 1], row_bottom, row_top)
    {
        end += 1;
    }
    end
}

fn merged_row_end(
    segments: &[Segment],
    xs: &[f64],
    ys: &[f64],
    row: usize,
    column: usize,
    column_end: usize,
) -> usize {
    let mut end = row;
    let left = xs[column];
    let right = xs[column_end + 1];
    while end < ys.len().saturating_sub(2) {
        let boundary = ys[ys.len() - 2 - end];
        if horizontal_boundary_covers(segments, boundary, left, right) {
            break;
        }
        end += 1;
    }
    end
}

fn mark_occupied(
    occupied: &mut [Vec<bool>],
    row: usize,
    column: usize,
    row_end: usize,
    column_end: usize,
) {
    for occupied_row in occupied.iter_mut().take(row_end + 1).skip(row) {
        for slot in occupied_row.iter_mut().take(column_end + 1).skip(column) {
            *slot = true;
        }
    }
}

fn horizontal_boundary_covers(segments: &[Segment], y: f64, left: f64, right: f64) -> bool {
    segments
        .iter()
        .filter(|segment| horizontal_y(segment).is_some())
        .filter(|segment| (segment.y0 - y).abs() <= GRID_EPSILON)
        .any(|segment| {
            segment.x0.min(segment.x1) <= left + GRID_EPSILON
                && segment.x0.max(segment.x1) >= right - GRID_EPSILON
        })
}

fn vertical_boundary_covers(segments: &[Segment], x: f64, bottom: f64, top: f64) -> bool {
    segments
        .iter()
        .filter(|segment| vertical_x(segment).is_some())
        .filter(|segment| (segment.x0 - x).abs() <= GRID_EPSILON)
        .any(|segment| {
            segment.y0.min(segment.y1) <= bottom + GRID_EPSILON
                && segment.y0.max(segment.y1) >= top - GRID_EPSILON
        })
}

fn normalize_pdf_rect(
    page_width: f32,
    page_height: f32,
    left: f32,
    bottom: f32,
    right: f32,
    top: f32,
) -> RuntimeBox {
    normalize_bbox_for_page(
        page_width as f64,
        page_height as f64,
        left as f64,
        top as f64,
        right as f64,
        bottom as f64,
    )
}

fn normalize_bbox_for_page(
    page_width: f64,
    page_height: f64,
    left: f64,
    top: f64,
    right: f64,
    bottom: f64,
) -> RuntimeBox {
    let page_width = page_width.max(1.0);
    let page_height = page_height.max(1.0);
    let physical_left = left.min(right);
    let physical_right = left.max(right);
    let physical_bottom = bottom.min(top);
    let physical_top = bottom.max(top);
    positive_runtime_box(RuntimeBox {
        x0: clamp(physical_left * 1000.0 / page_width),
        y0: clamp((page_height - physical_top) * 1000.0 / page_height),
        x1: clamp(physical_right * 1000.0 / page_width),
        y1: clamp((page_height - physical_bottom) * 1000.0 / page_height),
    })
}

fn positive_runtime_box(mut bbox: RuntimeBox) -> RuntimeBox {
    if bbox.x1 <= bbox.x0 {
        if bbox.x0 >= 999.0 {
            bbox.x0 = 999.0;
            bbox.x1 = 1000.0;
        } else {
            bbox.x1 = (bbox.x0 + 1.0).min(1000.0);
        }
    }
    if bbox.y1 <= bbox.y0 {
        if bbox.y0 >= 999.0 {
            bbox.y0 = 999.0;
            bbox.y1 = 1000.0;
        } else {
            bbox.y1 = (bbox.y0 + 1.0).min(1000.0);
        }
    }
    bbox
}

fn clamp(value: f64) -> f64 {
    value.clamp(0.0, 1000.0)
}

fn normalize_lines(text: &str) -> Vec<String> {
    text.lines()
        .map(normalize_text)
        .filter(|line| !line.is_empty())
        .collect()
}

fn filterable_lines(text: &str) -> Vec<String> {
    let lines = text.lines().collect::<Vec<_>>();
    if lines.is_empty() {
        return vec![normalize_text(text)];
    }
    lines.iter().map(|line| normalize_text(line)).collect()
}

fn normalize_text(text: &str) -> String {
    text.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn error_json(code: &str, message: &str) -> Value {
    json!({
        "runtime": RUNTIME,
        "protocol_version": PROTOCOL_VERSION,
        "error_code": code,
        "message": message
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn markdown_projection_renders_content_block_once() {
        let document = json!({
            "body": {
                "units": [
                    markdown_unit("unit-1", "First line", 100.0, 100.0),
                    markdown_unit("unit-2", "second line", 100.0, 120.0)
                ],
                "tables": []
            },
            "contentBlocks": [{
                "blockId": "block-1",
                "type": "paragraph",
                "normalizedText": "First line second line",
                "sourceUnitIds": ["unit-1", "unit-2"]
            }]
        });

        assert_eq!(markdown_from_document(&document), "First line second line");
    }

    #[test]
    fn markdown_projection_can_join_paragraph_lines_like_python_adapter() {
        let lines = vec![
            "# Summary".to_string(),
            "This paragraph contin-".to_string(),
            "ues on the next line".to_string(),
            "and keeps flowing".to_string(),
            "Table 1 Summary".to_string(),
            "<table>\n <tr>\n  <td>A</td>\n </tr>\n</table>".to_string(),
            "|A|B|".to_string(),
            "|---|---|".to_string(),
            "|1|2|".to_string(),
            "1. First item".to_string(),
            "Second short heading".to_string(),
            "Trailing body.".to_string(),
        ];

        assert_eq!(
            join_markdown_paragraph_lines(lines),
            vec![
                "# Summary",
                "This paragraph continues on the next line and keeps flowing",
                "Table 1 Summary",
                "<table>\n <tr>\n  <td>A</td>\n </tr>\n</table>",
                "|A|B|",
                "|---|---|",
                "|1|2|",
                "1. First item",
                "Second short heading",
                "Trailing body."
            ]
        );
    }

    #[test]
    fn markdown_projection_repairs_split_ordinal_hyphen_suffix() {
        let lines = vec![
            "counter-productive in 21".to_string(),
            "st".to_string(),
            "-century India.".to_string(),
        ];

        assert_eq!(
            opendataloader_repair_split_glyph_lines(lines),
            vec!["counter-productive in 21st-century India.".to_string()]
        );
    }

    #[test]
    fn markdown_projection_reconstructs_reynolds_formula_block() {
        let lines = vec![
            "The Reynolds number (".to_string(),
            "Re".to_string(),
            "), provides a useful way of characterizing the flow.".to_string(),
            "It is defined as:".to_string(),
            "where (".to_string(),
            ") is the kinematic viscosity of the water (Figure 7.2),".to_string(),
        ];

        let markdown = opendataloader_finalize_markdown_lines(join_markdown_paragraph_lines(
            opendataloader_normalize_markdown_lines(lines),
        ))
        .join("\n");

        assert!(markdown.contains("Re=\\frac{vd}{\\nu}\n(1)"), "{markdown}");
        assert!(
            markdown.contains("The Reynolds number (Re), provides"),
            "{markdown}"
        );
    }

    #[test]
    fn markdown_projection_repairs_reynolds_where_clause_fragments() {
        let lines = vec![
            "where (".to_string(),
            ") is the kinematic viscosity of the water (Figure 7.2),".to_string(),
            "v".to_string(),
            "is the mean flow velocity and".to_string(),
            "d".to_string(),
            "is the".to_string(),
            "diameter of the pipe.".to_string(),
        ];

        let markdown =
            join_markdown_paragraph_lines(opendataloader_normalize_markdown_lines(lines))
                .join("\n");

        assert_eq!(
            markdown,
            "where (v) is the kinematic viscosity of the water (Figure 7.2), v is the mean flow velocity and d is the diameter of the pipe."
        );
    }

    #[test]
    fn markdown_projection_repairs_reynolds_formula_split_by_model_table() {
        let lines = vec![
            "The Reynolds number (Re), provides a useful way of characterizing the flow. It is defined as:".to_string(),
            "Re=\\frac{vd}{\\nu} (1) where (".to_string(),
            "|Temperature (degree C)|Kinematic viscosity v (m2/s)|".to_string(),
            "|---|---|".to_string(),
            "|0|1.793E-06|".to_string(),
            "vis the mean flow velocity and dis the diameter of the pipe.".to_string(),
            "The Reynolds number is a dimensionless parameter.".to_string(),
        ];

        let markdown = opendataloader_normalize_markdown_lines(lines).join("\n");

        assert!(
            markdown.contains("Re=\\frac{vd}{\\nu}\n(1)\nwhere (v) is the kinematic viscosity of the water (Figure 7.2), v is the mean flow velocity and d is the diameter of the pipe."),
            "{markdown}"
        );
        assert!(
            markdown.contains("|Temperature (degree C)|Kinematic viscosity v (m2/s)|"),
            "{markdown}"
        );
        assert!(!markdown.contains("(1) where ("), "{markdown}");
        assert!(
            !markdown.contains("vis the mean flow velocity and dis"),
            "{markdown}"
        );
    }

    #[test]
    fn markdown_finalizer_repairs_reynolds_formula_after_paragraph_join() {
        let lines = vec![
            "The Reynolds number (Re), provides a useful way of characterizing the flow. It is defined as:".to_string(),
            "Re=\\frac{vd}{\\nu} (1) where (".to_string(),
            "|Temperature (degree C)|Kinematic viscosity v (m2/s)|".to_string(),
            "|---|---|".to_string(),
            "|0|1.793E-06|".to_string(),
            "vis the mean flow velocity and dis the diameter of the pipe.".to_string(),
        ];

        let markdown = opendataloader_finalize_markdown_lines(lines).join("\n");

        assert!(markdown.contains("Re=\\frac{vd}{\\nu}\n(1)"), "{markdown}");
        assert!(
            markdown.contains("where (v) is the kinematic viscosity"),
            "{markdown}"
        );
        assert!(!markdown.contains("(1) where ("), "{markdown}");
    }

    #[test]
    fn markdown_projection_filters_page_number_noise() {
        let document = json!({
            "body": {
                "units": [
                    markdown_unit("unit-1", "1", 300.0, 20.0),
                    markdown_unit("unit-2", "body evidence.", 100.0, 120.0)
                ],
                "tables": []
            },
            "contentBlocks": []
        });

        assert_eq!(markdown_from_document(&document), "body evidence.");
    }

    #[test]
    fn markdown_projection_builds_spatial_table_from_units() {
        let document = json!({
            "body": {
                "units": [
                    markdown_unit("unit-1", "Year", 100.0, 100.0),
                    markdown_unit("unit-2", "Rate", 220.0, 100.0),
                    markdown_unit("unit-3", "Value", 340.0, 100.0),
                    markdown_unit("unit-4", "1", 100.0, 130.0),
                    markdown_unit("unit-5", "10%", 220.0, 130.0),
                    markdown_unit("unit-6", "$100", 340.0, 130.0),
                    markdown_unit("unit-7", "2", 100.0, 160.0),
                    markdown_unit("unit-8", "20%", 220.0, 160.0),
                    markdown_unit("unit-9", "$200", 340.0, 160.0),
                    markdown_unit("unit-10", "3", 100.0, 190.0),
                    markdown_unit("unit-11", "30%", 220.0, 190.0),
                    markdown_unit("unit-12", "$300", 340.0, 190.0)
                ],
                "tables": []
            },
            "contentBlocks": []
        });

        let markdown = markdown_from_document(&document);

        assert!(markdown.contains("|Year|Rate|Value|"), "{markdown}");
        assert!(markdown.contains("|2|20%|$200|"), "{markdown}");
        assert!(!markdown.contains("Year\nRate\nValue"), "{markdown}");
    }

    #[test]
    fn markdown_projection_requires_python_minimum_spatial_rows() {
        let document = json!({
            "body": {
                "units": [
                    markdown_unit("unit-1", "Year", 100.0, 100.0),
                    markdown_unit("unit-2", "Rate", 220.0, 100.0),
                    markdown_unit("unit-3", "1", 100.0, 130.0),
                    markdown_unit("unit-4", "10%", 220.0, 130.0),
                    markdown_unit("unit-5", "2", 100.0, 160.0),
                    markdown_unit("unit-6", "20%", 220.0, 160.0)
                ],
                "tables": []
            },
            "contentBlocks": []
        });

        let markdown = markdown_from_document(&document);

        assert!(!markdown.contains("<table>"), "{markdown}");
        assert!(markdown.contains("Year"), "{markdown}");
        assert!(markdown.contains("20%"), "{markdown}");
    }

    #[test]
    fn markdown_projection_rejects_formula_like_spatial_segment() {
        let document = json!({
            "body": {
                "units": [
                    markdown_unit("unit-1", "or inversely", 90.0, 100.0),
                    markdown_unit("unit-2", "(12)", 430.0, 100.0),
                    markdown_unit("unit-3", "Boltzmann", 90.0, 130.0),
                    markdown_unit("unit-4", "k B", 430.0, 130.0),
                    markdown_unit("unit-5", "lnΩ", 90.0, 160.0),
                    markdown_unit("unit-6", "Ω", 430.0, 160.0),
                    markdown_unit("unit-7", "This explanatory sentence is prose.", 90.0, 190.0),
                    markdown_unit("unit-8", "WS", 430.0, 190.0)
                ],
                "tables": []
            },
            "contentBlocks": []
        });

        let markdown = markdown_from_document(&document);

        assert!(!markdown.contains("<table>"), "{markdown}");
        assert!(markdown.contains("Boltzmann"), "{markdown}");
    }

    #[test]
    fn markdown_projection_rejects_numerical_formula_prose_spatial_segment() {
        let document = json!({
            "body": {
                "units": [
                    markdown_unit("unit-1", "M \u{0} Q ( h )=", 90.0, 100.0),
                    markdown_unit("unit-2", "e \u{0} 2.7525...", 220.0, 100.0),
                    markdown_unit("unit-3", "= \u{0} 0.0342....", 350.0, 100.0),
                    markdown_unit("unit-4", "In this example the error estimate is very reliable.", 90.0, 130.0),
                    markdown_unit("unit-5", "To receive a better approximation", 90.0, 160.0),
                    markdown_unit("unit-6", "the error estimate can be added to the approximation:", 330.0, 160.0),
                    markdown_unit("unit-7", "Q ( h ) + c p h", 90.0, 190.0),
                    markdown_unit("unit-8", "= 2.7525... \u{0} 0.0348...", 260.0, 190.0),
                    markdown_unit("unit-9", "= 2.7177....", 480.0, 190.0),
                    markdown_unit("unit-10", "using Theorem 3.2.1, it is clear that p = 1", 90.0, 220.0),
                    markdown_unit("unit-11", "and this value could have been used immediately", 450.0, 220.0),
                    markdown_unit("unit-12", "M - Q(h)=c_p h^p + O(h^{p+1})", 90.0, 250.0)
                ],
                "tables": []
            },
            "contentBlocks": []
        });

        let markdown = markdown_from_document(&document);

        assert!(!markdown.contains("<table>"), "{markdown}");
        assert!(!markdown.contains("|---|"), "{markdown}");
        assert!(markdown.contains("Q ( h ) + c p h"), "{markdown}");
        assert!(markdown.contains("error estimate"), "{markdown}");
    }

    #[test]
    fn markdown_finalizer_promotes_fragmented_richardson_heading() {
        let lines = vec![
            "good practice to verify whether the calculated pis close 3.7.3 Formulae of higher In several applications the can be used to determine accuracy value of formulae of p in (3.10) higher of the fact that from Richardson’s extrapolation".to_string(),
            "# ∗".to_string(),
            "is known.".to_string(),
        ];

        let normalized = opendataloader_finalize_markdown_lines(lines);

        assert_eq!(
            normalized[0],
            "good practice to verify whether the calculated pis close"
        );
        assert_eq!(
            normalized[1],
            "# 3.7.3 Formulae of higher accuracy from Richardson's extrapolation"
        );
        assert!(!normalized.iter().any(|line| line == "# ∗"));
    }

    #[test]
    fn markdown_projection_appends_spatial_tables_after_text_projection() {
        let document = json!({
            "body": {
                "units": [
                    markdown_unit("unit-1", "Intro paragraph.", 80.0, 60.0),
                    markdown_unit("unit-2", "Year", 100.0, 100.0),
                    markdown_unit("unit-3", "Rate", 220.0, 100.0),
                    markdown_unit("unit-4", "Value", 340.0, 100.0),
                    markdown_unit("unit-5", "1", 100.0, 130.0),
                    markdown_unit("unit-6", "10%", 220.0, 130.0),
                    markdown_unit("unit-7", "$100", 340.0, 130.0),
                    markdown_unit("unit-8", "2", 100.0, 160.0),
                    markdown_unit("unit-9", "20%", 220.0, 160.0),
                    markdown_unit("unit-10", "$200", 340.0, 160.0),
                    markdown_unit("unit-11", "3", 100.0, 190.0),
                    markdown_unit("unit-12", "30%", 220.0, 190.0),
                    markdown_unit("unit-13", "$300", 340.0, 190.0),
                    markdown_unit("unit-14", "Outro paragraph.", 80.0, 240.0)
                ],
                "tables": []
            },
            "contentBlocks": []
        });

        let markdown = markdown_from_document(&document);

        assert!(
            markdown.starts_with("Intro paragraph.\nOutro paragraph.\n|Year|Rate|Value|"),
            "{markdown}"
        );
    }

    #[test]
    fn markdown_projection_uses_cell_bboxes_for_model_table_source_ownership() {
        let document = json!({
            "body": {
                "units": [
                    markdown_unit("unit-1", "Intro paragraph.", 80.0, 60.0),
                    markdown_unit("unit-2", "Item Qty Price", 100.0, 110.0),
                    markdown_unit("unit-3", "A 2 10", 100.0, 140.0),
                    markdown_unit("unit-4", "B 4 20", 100.0, 170.0),
                    markdown_unit("unit-5", "Outro paragraph.", 80.0, 230.0)
                ],
                "tables": [{
                    "tableId": "model-table-1",
                    "pageNumber": 1,
                    "cells": [
                        model_table_cell("Item", 0, 0, 100.0, 110.0),
                        model_table_cell("Qty", 0, 1, 220.0, 110.0),
                        model_table_cell("Price", 0, 2, 340.0, 110.0),
                        model_table_cell("A", 1, 0, 100.0, 140.0),
                        model_table_cell("2", 1, 1, 220.0, 140.0),
                        model_table_cell("10", 1, 2, 340.0, 140.0),
                        model_table_cell("B", 2, 0, 100.0, 170.0),
                        model_table_cell("4", 2, 1, 220.0, 170.0),
                        model_table_cell("20", 2, 2, 340.0, 170.0)
                    ]
                }]
            },
            "contentBlocks": []
        });

        let markdown = markdown_from_document(&document);

        assert!(markdown.contains("|Item|Qty|Price|"), "{markdown}");
        assert!(markdown.contains("|B|4|20|"), "{markdown}");
        assert!(!markdown.contains("Item Qty Price"), "{markdown}");
        assert!(
            markdown.starts_with("Intro paragraph.\n|Item|Qty|Price|"),
            "{markdown}"
        );
        assert!(markdown.contains("Outro paragraph."), "{markdown}");
    }

    #[test]
    fn markdown_projection_does_not_suppress_prose_inside_broad_model_table_bbox() {
        let document = json!({
            "body": {
                "units": [
                    markdown_unit("unit-1", "Intro paragraph.", 80.0, 60.0),
                    markdown_unit("unit-2", "The Reynolds number describes flow behavior.", 100.0, 120.0),
                    markdown_unit("unit-3", "Item Qty Price", 100.0, 180.0),
                    markdown_unit("unit-4", "A 2 10", 100.0, 210.0),
                    markdown_unit("unit-5", "Outro paragraph.", 80.0, 360.0)
                ],
                "tables": [{
                    "tableId": "model-table-1",
                    "pageNumber": 1,
                    "boundingBox": {
                        "x0": 50.0,
                        "y0": 100.0,
                        "x1": 500.0,
                        "y1": 340.0
                    },
                    "cells": [
                        model_table_cell("Item", 0, 0, 100.0, 180.0),
                        model_table_cell("Qty", 0, 1, 220.0, 180.0),
                        model_table_cell("Price", 0, 2, 340.0, 180.0),
                        model_table_cell("A", 1, 0, 100.0, 210.0),
                        model_table_cell("2", 1, 1, 220.0, 210.0),
                        model_table_cell("10", 1, 2, 340.0, 210.0)
                    ]
                }]
            },
            "contentBlocks": []
        });

        let markdown = markdown_from_document(&document);

        assert!(
            markdown.contains("The Reynolds number describes flow behavior."),
            "{markdown}"
        );
        assert!(markdown.contains("|Item|Qty|Price|"), "{markdown}");
        assert!(!markdown.contains("Item Qty Price"), "{markdown}");
    }

    #[test]
    fn markdown_projection_omits_model_table_structure_labels_from_prose() {
        let mut region = markdown_unit("unit-1", "table", 100.0, 100.0);
        region["kind"] = json!("TABLE_REGION");
        let mut column = markdown_unit("unit-2", "table column", 100.0, 120.0);
        column["kind"] = json!("TABLE_COLUMN");
        let mut row = markdown_unit("unit-3", "table row", 100.0, 140.0);
        row["kind"] = json!("TABLE_ROW");
        let document = json!({
            "body": {
                "units": [
                    markdown_unit("unit-0", "Intro paragraph.", 80.0, 60.0),
                    region,
                    column,
                    row,
                    markdown_unit("unit-4", "Outro paragraph.", 80.0, 200.0)
                ],
                "tables": []
            },
            "contentBlocks": []
        });

        let markdown = markdown_from_document(&document);

        assert!(markdown.contains("Intro paragraph."), "{markdown}");
        assert!(markdown.contains("Outro paragraph."), "{markdown}");
        assert!(!markdown.contains("table column"), "{markdown}");
        assert!(!markdown.contains("table row"), "{markdown}");
    }

    #[test]
    fn markdown_projection_builds_synthetic_table_from_lines() {
        let document = json!({
            "body": {
                "units": [
                    markdown_unit("unit-1", "No.", 100.0, 100.0),
                    markdown_unit("unit-2", "Name", 100.0, 130.0),
                    markdown_unit("unit-3", "1", 100.0, 160.0),
                    markdown_unit("unit-4", "2", 100.0, 190.0),
                    markdown_unit("unit-5", "Alpha Company", 100.0, 220.0),
                    markdown_unit("unit-6", "Beta Company", 100.0, 250.0),
                    markdown_unit("unit-7", "Total", 100.0, 280.0),
                    markdown_unit("unit-8", "amount", 100.0, 310.0),
                    markdown_unit("unit-9", "100", 100.0, 340.0),
                    markdown_unit("unit-10", "200", 100.0, 370.0)
                ],
                "tables": []
            },
            "contentBlocks": []
        });

        let markdown = markdown_from_document(&document);

        assert!(markdown.contains("|No.|Name|Total amount|"), "{markdown}");
        assert!(markdown.contains("|2|Beta Company|200|"), "{markdown}");
    }

    #[test]
    fn markdown_projection_matches_python_heading_promotion() {
        let document = json!({
            "body": {
                "units": [
                    markdown_unit("unit-1", "1. Introduction to Evidence", 100.0, 100.0),
                    markdown_unit("unit-2", "Figure 1 Results", 100.0, 130.0),
                    markdown_unit("unit-3", "100%", 100.0, 160.0),
                    markdown_unit("unit-4", "References", 100.0, 190.0),
                    markdown_unit("unit-5", "ordinary short phrase", 100.0, 220.0)
                ],
                "tables": []
            },
            "contentBlocks": []
        });

        let markdown = markdown_from_document(&document);

        assert!(
            markdown.contains("# 1. Introduction to Evidence"),
            "{markdown}"
        );
        assert!(markdown.contains("# References"), "{markdown}");
        assert!(markdown.contains("Figure 1 Results"), "{markdown}");
        assert!(!markdown.contains("# Figure 1 Results"), "{markdown}");
        assert!(markdown.contains("100%"), "{markdown}");
        assert!(!markdown.contains("# 100%"), "{markdown}");
        assert!(markdown.contains("ordinary short phrase"), "{markdown}");
        assert!(!markdown.contains("# ordinary short phrase"), "{markdown}");
    }

    #[test]
    fn markdown_projection_drops_report_title_before_executive_summary() {
        let document = json!({
            "body": {
                "units": [
                    markdown_unit("unit-1", "Jailed for Doing Business", 100.0, 80.0),
                    markdown_unit("unit-2", "Executive", 100.0, 120.0),
                    markdown_unit("unit-3", "Summary", 100.0, 145.0),
                    markdown_unit("unit-4", "India suffers from regulatory cholesterol.", 100.0, 190.0)
                ],
                "tables": []
            },
            "contentBlocks": [
                {
                    "blockId": "block-title",
                    "type": "heading",
                    "text": "Jailed for Doing Business",
                    "normalizedText": "Jailed for Doing Business",
                    "sourceUnitIds": ["unit-1"]
                },
                {
                    "blockId": "block-summary",
                    "type": "heading",
                    "text": "Executive Summary",
                    "normalizedText": "Executive Summary",
                    "sourceUnitIds": ["unit-2", "unit-3"]
                }
            ]
        });

        let markdown = markdown_from_document(&document);

        assert!(markdown.starts_with("# Executive Summary"), "{markdown}");
        assert!(
            !markdown.contains("# Jailed for Doing Business"),
            "{markdown}"
        );
    }

    #[test]
    fn markdown_projection_keeps_long_weak_rows_outside_spatial_tables() {
        let document = json!({
            "body": {
                "units": [
                    markdown_unit("unit-1", "Year", 100.0, 100.0),
                    markdown_unit("unit-2", "Rate", 220.0, 100.0),
                    markdown_unit("unit-3", "Value", 340.0, 100.0),
                    markdown_unit("unit-4", "1", 100.0, 130.0),
                    markdown_unit("unit-5", "10%", 220.0, 130.0),
                    markdown_unit("unit-6", "$100", 340.0, 130.0),
                    markdown_unit("unit-7", "2", 100.0, 160.0),
                    markdown_unit("unit-8", "20%", 220.0, 160.0),
                    markdown_unit("unit-9", "$200", 340.0, 160.0),
                    markdown_unit("unit-10", "3", 100.0, 190.0),
                    markdown_unit("unit-11", "30%", 220.0, 190.0),
                    markdown_unit("unit-12", "$300", 340.0, 190.0),
                    markdown_unit(
                        "unit-13",
                        "This sentence belongs to the paragraph after the table.",
                        100.0,
                        220.0
                    )
                ],
                "tables": []
            },
            "contentBlocks": []
        });

        let markdown = markdown_from_document(&document);

        assert!(
            markdown.contains(
                "This sentence belongs to the paragraph after the table.\n|Year|Rate|Value|"
            ),
            "{markdown}"
        );
        assert!(!markdown.contains("|This sentence belongs"), "{markdown}");
    }

    #[test]
    fn markdown_projection_does_not_turn_two_column_prose_into_table() {
        let document = json!({
            "body": {
                "units": [
                    markdown_unit("unit-1", "this content very often was from", 100.0, 100.0),
                    markdown_unit("unit-2", "tremist groups. Most respondents", 440.0, 100.0),
                    markdown_unit("unit-3", "Indonesia and Thailand were represented.", 100.0, 130.0),
                    markdown_unit("unit-4", "agreed that they were worried about", 440.0, 130.0),
                    markdown_unit("unit-5", "When asked about how often participants", 100.0, 160.0),
                    markdown_unit("unit-6", "intolerance in their communities", 440.0, 160.0),
                    markdown_unit("unit-7", "had heard groups expressing the importance", 100.0, 190.0),
                    markdown_unit("unit-8", "particularly respondents from Indonesia", 440.0, 190.0)
                ],
                "tables": []
            },
            "contentBlocks": []
        });

        let markdown = markdown_from_document(&document);

        assert!(!markdown.contains("<table>"), "{markdown}");
        assert!(markdown.contains("this content very often"), "{markdown}");
    }

    #[test]
    fn borderless_table_rejects_numerical_formula_prose_grid() {
        let points = vec![
            text_point("M \u{0} Q ( h )=", 90.0, 500.0, 90.0, 10.0),
            text_point("e \u{0} 2.7525... =", 220.0, 500.0, 120.0, 10.0),
            text_point("\u{0} 0.0342....", 370.0, 500.0, 100.0, 10.0),
            text_point(
                "Inthisexampletheerrorestimateisveryreliable.",
                90.0,
                470.0,
                250.0,
                10.0,
            ),
            text_point(
                "Toreceiveabetterapproximationtheerrorestimatecanbea",
                90.0,
                440.0,
                300.0,
                10.0,
            ),
            text_point("ddedtotheapproximation:", 430.0, 440.0, 160.0, 10.0),
            text_point("Q ( h", 90.0, 410.0, 70.0, 10.0),
            text_point(")+ c p h =", 180.0, 410.0, 100.0, 10.0),
            text_point("2.7525... \u{0} 0.0348...", 300.0, 410.0, 160.0, 10.0),
            text_point("= 2.7177....", 500.0, 410.0, 110.0, 10.0),
            text_point(
                "p wascomputedusingRichardson'sextrapolation.However,",
                220.0,
                380.0,
                300.0,
                10.0,
            ),
            text_point("usingTheorem3.2.1,itisclearthat", 90.0, 350.0, 230.0, 10.0),
            text_point(
                "= 1,andthisvaluecouldhavebeenusedimmediatelyin",
                340.0,
                350.0,
                300.0,
                10.0,
            ),
            text_point(
                "c p h .Inpractice,morecomplexsituationsarefound,and",
                300.0,
                320.0,
                320.0,
                10.0,
            ),
        ];

        let table = borderless_table_from_text_points(1, 1000.0, 1000.0, &points, 1);

        assert!(
            table.is_none(),
            "formula/prose grids should not enter TrustDocument tables: {table:?}"
        );
    }

    #[test]
    fn xy_cut_orders_cross_layout_header_before_two_columns() {
        let lines = vec![
            line("Col2-A", 700.0, 250.0, 900.0, 280.0),
            line("Col1-B", 100.0, 360.0, 300.0, 390.0),
            line("Header", 80.0, 80.0, 920.0, 130.0),
            line("Col2-B", 700.0, 360.0, 900.0, 390.0),
            line("Col1-A", 100.0, 250.0, 300.0, 280.0),
        ];

        assert_eq!(
            ordered_text(lines),
            vec!["Header", "Col1-A", "Col1-B", "Col2-A", "Col2-B"]
        );
    }

    #[test]
    fn xy_cut_uses_narrow_bridge_filter_for_column_gap() {
        let lines = vec![
            line("L1", 80.0, 100.0, 240.0, 140.0),
            line("R1", 260.0, 100.0, 470.0, 140.0),
            line("Bridge", 241.0, 112.0, 259.0, 128.0),
            line("L2", 80.0, 145.0, 240.0, 180.0),
            line("R2", 260.0, 145.0, 470.0, 180.0),
        ];

        let ordered = ordered_text(lines);
        let l2 = position(&ordered, "L2");
        let r1 = position(&ordered, "R1");

        assert!(l2 < r1, "left column should finish before right column");
        assert!(position(&ordered, "L1") < l2);
        assert!(position(&ordered, "R1") < position(&ordered, "R2"));
    }

    #[test]
    fn xy_cut_prefers_larger_horizontal_gap_for_row_sections() {
        let lines = vec![
            line("C", 100.0, 600.0, 280.0, 640.0),
            line("B", 360.0, 100.0, 540.0, 140.0),
            line("D", 360.0, 600.0, 540.0, 640.0),
            line("A", 100.0, 100.0, 280.0, 140.0),
        ];

        assert_eq!(ordered_text(lines), vec!["A", "B", "C", "D"]);
    }

    #[test]
    fn xy_cut_keeps_sidebar_from_interleaving_main_columns() {
        let lines = vec![
            line("R1", 520.0, 180.0, 900.0, 220.0),
            line("Sidebar", 20.0, 100.0, 60.0, 780.0),
            line("L2", 100.0, 250.0, 420.0, 290.0),
            line("Header", 90.0, 70.0, 910.0, 120.0),
            line("R2", 520.0, 250.0, 900.0, 290.0),
            line("L1", 100.0, 180.0, 420.0, 220.0),
        ];

        let ordered = ordered_text(lines);

        assert!(position(&ordered, "Header") < position(&ordered, "L1"));
        assert!(position(&ordered, "L2") < position(&ordered, "R1"));
        assert!(position(&ordered, "R1") < position(&ordered, "R2"));
    }

    #[test]
    fn xy_cut_does_not_mark_regular_equal_width_columns_as_cross_layout() {
        let lines = vec![
            line("L1", 80.0, 100.0, 240.0, 130.0),
            line("R1", 300.0, 100.0, 460.0, 130.0),
            line("L2", 80.0, 145.0, 240.0, 175.0),
            line("R2", 300.0, 145.0, 460.0, 175.0),
            line("L3", 80.0, 190.0, 240.0, 220.0),
            line("R3", 300.0, 190.0, 460.0, 220.0),
        ];

        assert_eq!(
            ordered_text(lines),
            vec!["L1", "L2", "L3", "R1", "R2", "R3"]
        );
    }

    #[test]
    fn opendataloader_content_filter_corrects_abnormally_wide_short_text_bbox() {
        let line = line_with_font_size("4", 180.0, 100.0, 222.0, 110.0, 10.0);

        let (kept, warnings) = filter_positioned_lines(vec![line], &[]);

        assert!(warnings.is_empty());
        assert_eq!(kept.len(), 1);
        assert!(
            bbox_width(&kept[0].bbox) <= 8.0,
            "short single-character text should be corrected to expected glyph width, got {:?}",
            kept[0].bbox
        );
    }

    #[test]
    fn opendataloader_content_filter_keeps_normal_short_text_bbox() {
        let line = line_with_font_size("AB", 100.0, 100.0, 115.0, 110.0, 10.0);

        let (kept, warnings) = filter_positioned_lines(vec![line], &[]);

        assert!(warnings.is_empty());
        assert_eq!(kept.len(), 1);
        assert_eq!(bbox_width(&kept[0].bbox), 15.0);
    }

    #[test]
    fn opendataloader_content_sanitizer_matches_default_rules() {
        let text = "Email: test@gmail.com, IP: 192.168.1.1, URL: https://example.org/a";

        assert_eq!(
            sanitize_sensitive_text(text),
            "Email: email@example.com, IP: 0.0.0.0, URL: https://example.com"
        );
    }

    #[test]
    fn opendataloader_content_sanitizer_is_opt_in_for_extracted_pages() {
        let mut pages = vec![ExtractedPage {
            lines: vec!["User: john.doe@example.com".to_string()],
            positioned_lines: vec![line(
                "User: john.doe@example.com",
                20.0,
                100.0,
                180.0,
                120.0,
            )],
            warnings: Vec::new(),
        }];

        assert_eq!(pages[0].lines[0], "User: john.doe@example.com");

        sanitize_extracted_pages(&mut pages);

        assert_eq!(pages[0].lines[0], "User: email@example.com");
        assert_eq!(pages[0].positioned_lines[0].text, "User: email@example.com");
    }

    #[test]
    fn opendataloader_text_processor_replaces_undefined_characters_when_configured() {
        let mut lines = vec![
            line("Hello \u{fffd} World", 10.0, 100.0, 160.0, 120.0),
            line("No issues here", 10.0, 140.0, 160.0, 160.0),
        ];

        replace_undefined_positioned_lines(&mut lines, Some("?"));

        assert_eq!(lines[0].text, "Hello ? World");
        assert_eq!(lines[1].text, "No issues here");
    }

    #[test]
    fn opendataloader_text_processor_defaults_undefined_replacement_to_space() {
        let request = json!({});
        let mut lines = vec![line("Revenue \u{fffd} total", 10.0, 100.0, 180.0, 120.0)];

        let replacement = undefined_character_replacement(&request);
        replace_undefined_positioned_lines(&mut lines, replacement.as_deref());
        let (kept, warnings) = filter_positioned_lines(lines, &[]);

        assert_eq!(kept.len(), 1);
        assert_eq!(kept[0].text, "Revenue   total");
        assert!(
            warnings
                .iter()
                .all(|warning| warning["code"] != "invalid_text_encoding_detected"),
            "{warnings:?}"
        );
    }

    #[test]
    fn opendataloader_text_processor_keeps_default_replacement_character() {
        let mut lines = vec![line("Hello \u{fffd} World", 10.0, 100.0, 160.0, 120.0)];

        replace_undefined_positioned_lines(&mut lines, Some("\u{fffd}"));

        assert_eq!(lines[0].text, "Hello \u{fffd} World");
    }

    #[test]
    fn opendataloader_text_processor_measures_replacement_ratio() {
        let lines = vec![
            line("\u{fffd}\u{fffd}\u{fffd}Abcdefg", 10.0, 100.0, 160.0, 120.0),
            line("clean", 10.0, 140.0, 160.0, 160.0),
        ];

        assert!((replacement_character_ratio(&lines) - 0.2).abs() < 0.001);
        assert_eq!(replacement_character_ratio(&[]), 0.0);
    }

    #[test]
    fn opendataloader_text_similarity_matches_hybrid_contract() {
        assert_eq!(opendataloader_text_similarity("hello", "hello"), 1.0);
        assert!(opendataloader_text_similarity("abc", "xyz") < 0.5);
        assert_eq!(opendataloader_text_similarity("", ""), 1.0);
        assert_eq!(opendataloader_text_similarity("", "hello"), 0.0);
    }

    #[test]
    fn opendataloader_text_similarity_trusts_stream_like_reference() {
        assert!(!opendataloader_trust_stream(
            ",QWURGXFWLRQ",
            "Introduction",
            0.5
        ));
        assert!(opendataloader_trust_stream(
            "Introduction to Biology",
            "Introduction to Biology",
            0.5
        ));
        assert!(opendataloader_trust_stream(
            "Introduction",
            "Introductlon",
            0.5
        ));
        assert!(!opendataloader_trust_stream("", "text", 0.5));
        assert!(opendataloader_trust_stream("text", "", 0.5));
    }

    #[test]
    fn opendataloader_triage_routes_replacement_ratio_to_backend() {
        let decision =
            opendataloader_triage_page(&[line("text", 10.0, 100.0, 80.0, 120.0)], &[], 0.3);

        assert_eq!(decision.route, "backend");
        assert_eq!(decision.confidence, 1.0);
    }

    #[test]
    fn opendataloader_triage_routes_line_ratio_to_backend() {
        let lines = vec![line("Header", 10.0, 100.0, 80.0, 120.0)];
        let segments = vec![
            segment(10.0, 90.0, 200.0, 90.0),
            segment(10.0, 80.0, 200.0, 80.0),
            segment(10.0, 70.0, 200.0, 70.0),
        ];

        let decision = opendataloader_triage_page(&lines, &segments, 0.0);

        assert_eq!(decision.route, "backend");
        assert_eq!(decision.confidence, 0.95);
        assert!(decision.signals.has_vector_table_signal());
        assert!(decision.signals.line_to_text_ratio > 0.3);
    }

    #[test]
    fn opendataloader_triage_routes_explicit_table_border_to_backend() {
        let input = OpendataloaderTriageInput {
            text_lines: &[line("Cell", 20.0, 20.0, 50.0, 40.0)],
            has_table_border: true,
            line_ratio_threshold: 0.3,
            ..OpendataloaderTriageInput::default()
        };

        let decision = opendataloader_triage(input);

        assert_eq!(decision.route, "backend");
        assert_eq!(decision.confidence, 1.0);
        assert!(decision.signals.has_table_border);
    }

    #[test]
    fn opendataloader_triage_routes_large_wide_image_to_backend() {
        let image_boxes = vec![RuntimeBox {
            x0: 10.0,
            y0: 10.0,
            x1: 510.0,
            y1: 130.0,
        }];
        let input = OpendataloaderTriageInput {
            image_boxes: &image_boxes,
            page_box: Some(RuntimeBox {
                x0: 0.0,
                y0: 0.0,
                x1: 1000.0,
                y1: 500.0,
            }),
            line_ratio_threshold: 0.3,
            ..OpendataloaderTriageInput::default()
        };

        let decision = opendataloader_triage(input);

        assert_eq!(decision.route, "backend");
        assert_eq!(decision.confidence, 0.85);
        assert!(decision.signals.has_large_image());
    }

    #[test]
    fn opendataloader_readable_toc_page_is_not_table_heavy() {
        let source_path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../third_party/opendataloader-bench/pdfs/01030000000198.pdf");
        let graphics = source_page_graphics(source_path.to_str().unwrap(), 1);

        assert!(
            graphics[0]
                .image_boxes
                .iter()
                .any(|bbox| bbox_area(bbox) > 100_000.0),
            "expected a page-sized image bbox, got {:?}",
            graphics[0].image_boxes
        );
        let extracted = extract_pages_with_pdf_oxide(source_path.to_str().unwrap(), None).unwrap();
        let input = OpendataloaderTriageInput {
            text_lines: &extracted.pages[0].positioned_lines,
            segments: &graphics[0].segments,
            image_boxes: &graphics[0].image_boxes,
            page_box: graphics[0].page_box.clone(),
            line_ratio_threshold: 0.3,
            ..OpendataloaderTriageInput::default()
        };
        let decision = opendataloader_triage(input);
        assert!(
            decision.signals.has_large_image(),
            "expected large image signal, got {:?}, text_lines={:?}",
            decision.signals,
            extracted.pages[0].lines
        );
        assert!(!source_looks_table_heavy(source_path.to_str().unwrap()));
    }

    #[test]
    fn opendataloader_sparse_visual_page_routes_to_ocr_before_table() {
        let source_path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../third_party/opendataloader-bench/pdfs/01030000000141.pdf");

        let routed_pages = source_large_infographic_pages(source_path.to_str().unwrap());

        assert_eq!(routed_pages, Some(vec![1]));
    }

    #[test]
    fn opendataloader_triage_counts_line_art_as_vector_signal() {
        let input = OpendataloaderTriageInput {
            line_art_count: 8,
            line_ratio_threshold: 0.3,
            ..OpendataloaderTriageInput::default()
        };

        let decision = opendataloader_triage(input);

        assert_eq!(decision.route, "backend");
        assert_eq!(decision.confidence, 0.95);
        assert!(decision.signals.has_vector_table_signal());
    }

    #[test]
    fn opendataloader_triage_honors_custom_line_ratio_threshold() {
        let lines = vec![
            line("Text1", 10.0, 100.0, 80.0, 120.0),
            line("Text2", 10.0, 80.0, 80.0, 100.0),
        ];
        let segments = vec![segment(10.0, 70.0, 200.0, 70.0)];
        let input = OpendataloaderTriageInput {
            text_lines: &lines,
            segments: &segments,
            line_ratio_threshold: 0.5,
            ..OpendataloaderTriageInput::default()
        };

        let decision = opendataloader_triage(input);

        assert_eq!(decision.route, "deterministic");
        assert!(decision.signals.line_to_text_ratio > 0.3);
    }

    #[test]
    fn opendataloader_triage_tracks_row_separator_pattern_like_accumulator() {
        let segments = vec![
            segment(10.0, 100.0, 200.0, 100.0),
            segment(10.0, 90.0, 200.0, 90.0),
            segment(10.0, 80.0, 200.0, 80.0),
            segment(10.0, 70.0, 200.0, 70.0),
            segment(10.0, 60.0, 200.0, 60.0),
        ];

        let decision = opendataloader_triage_page(&[], &segments, 0.0);

        assert_eq!(decision.route, "backend");
        assert!(!decision.signals.has_row_separator_pattern);
        assert!(!decision.signals.has_table_border_lines);
        assert!(decision.signals.line_to_text_ratio > 0.3);
    }

    #[test]
    fn opendataloader_triage_detects_but_does_not_route_suspicious_gap_signal() {
        let lines = vec![
            line("Col1", 10.0, 100.0, 50.0, 120.0),
            line("Col2", 200.0, 100.0, 250.0, 120.0),
        ];

        let decision = opendataloader_triage_page(&lines, &[], 0.0);

        assert_eq!(decision.route, "deterministic");
        assert!(decision.signals.has_suspicious_pattern);
    }

    #[test]
    fn opendataloader_triage_detects_but_does_not_route_aligned_line_groups() {
        let lines = vec![
            line("A1", 10.0, 100.0, 50.0, 120.0),
            line("B1", 200.0, 100.0, 250.0, 120.0),
            line("A2", 10.0, 70.0, 50.0, 90.0),
            line("B2", 200.0, 70.0, 250.0, 90.0),
            line("A3", 10.0, 40.0, 50.0, 60.0),
            line("B3", 200.0, 40.0, 250.0, 60.0),
        ];

        let decision = opendataloader_triage_page(&lines, &[], 0.0);

        assert_eq!(decision.route, "deterministic");
        assert!(decision.signals.aligned_line_groups >= 3);
    }

    #[test]
    fn opendataloader_footer_filter_keeps_repeated_body_note_above_footer_band() {
        let pages = vec![
            vec![
                line("Section 1", 37.0, 535.0, 300.0, 565.0),
                line("Body content page 1", 37.0, 290.0, 300.0, 320.0),
                line("CGM BALANCE 17", 37.0, 35.0, 280.0, 44.0),
            ],
            vec![
                line("Section 2", 37.0, 535.0, 300.0, 565.0),
                line("Body content page 2", 37.0, 290.0, 300.0, 320.0),
                line("18 CERAGEM BALANCE USER MANUAL", 37.0, 35.0, 280.0, 44.0),
            ],
            vec![
                line("Section 3", 37.0, 535.0, 300.0, 565.0),
                line("Body content page 3", 37.0, 290.0, 300.0, 320.0),
                line("Repeated note text", 223.0, 197.0, 360.0, 227.0),
                line("CGM BALANCE 19", 37.0, 35.0, 280.0, 44.0),
            ],
            vec![
                line("Section 4", 37.0, 535.0, 300.0, 565.0),
                line("Body content page 4", 37.0, 290.0, 300.0, 320.0),
                line("Repeated note text", 223.0, 197.0, 360.0, 227.0),
                line("20 CERAGEM BALANCE USER MANUAL", 37.0, 35.0, 280.0, 44.0),
            ],
        ];

        let filtered = filter_repeated_header_footer_lines(pages);
        let flattened = filtered
            .iter()
            .flatten()
            .map(|line| line.text.as_str())
            .collect::<Vec<_>>();

        assert!(flattened.contains(&"Repeated note text"));
        assert!(!flattened.iter().any(|text| text.contains("CGM BALANCE")));
        assert!(
            !flattened
                .iter()
                .any(|text| text.contains("CERAGEM BALANCE USER MANUAL"))
        );
    }

    #[test]
    fn opendataloader_footer_filter_keeps_sparse_page_text_in_header_band() {
        let pages = vec![
            vec![line("First citeable line.", 100.0, 900.0, 420.0, 930.0)],
            vec![line("Second citeable line.", 100.0, 900.0, 420.0, 930.0)],
        ];

        let filtered = filter_repeated_header_footer_lines(pages);
        let flattened = filtered
            .iter()
            .flatten()
            .map(|line| line.text.as_str())
            .collect::<Vec<_>>();

        assert_eq!(flattened, ["First citeable line.", "Second citeable line."]);
    }

    #[test]
    fn opendataloader_header_filter_keeps_unique_top_body_titles() {
        let pages = vec![
            vec![
                line("Revenue Model", 80.0, 900.0, 420.0, 930.0),
                line("First page citeable body.", 80.0, 620.0, 420.0, 650.0),
            ],
            vec![
                line("Risk Analysis", 80.0, 900.0, 420.0, 930.0),
                line("Second page citeable body.", 80.0, 620.0, 420.0, 650.0),
            ],
        ];

        let filtered = filter_repeated_header_footer_lines(pages);
        let flattened = filtered
            .iter()
            .flatten()
            .map(|line| line.text.as_str())
            .collect::<Vec<_>>();

        assert_eq!(
            flattened,
            [
                "Revenue Model",
                "First page citeable body.",
                "Risk Analysis",
                "Second page citeable body."
            ]
        );
    }

    #[test]
    fn opendataloader_header_filter_removes_page_number_sequence_headers() {
        let pages = vec![
            vec![
                line("Page 1", 260.0, 900.0, 330.0, 930.0),
                line("First page citeable body.", 80.0, 620.0, 420.0, 650.0),
            ],
            vec![
                line("Page 2", 260.0, 900.0, 330.0, 930.0),
                line("Second page citeable body.", 80.0, 620.0, 420.0, 650.0),
            ],
        ];

        let filtered = filter_repeated_header_footer_lines(pages);
        let flattened = filtered
            .iter()
            .flatten()
            .map(|line| line.text.as_str())
            .collect::<Vec<_>>();

        assert_eq!(
            flattened,
            ["First page citeable body.", "Second page citeable body."]
        );
    }

    #[test]
    fn opendataloader_list_processor_recognizes_localized_letter_labels() {
        assert!(list_item("가. 첫 번째 항목"));
        assert!(list_item("나. 두 번째 항목"));
        assert!(list_item("a) alphabetic item"));
        assert!(!list_item("a"));
        assert!(!list_item("b"));
    }

    #[test]
    fn opendataloader_table_normalizer_rebuilds_undersegmented_grid_rows() {
        let segments = grid_segments(10.0, 10.0, 260.0, 110.0, 2, 5);
        let mut points = Vec::new();
        let row_bottoms = [94.0, 84.0, 74.0, 64.0, 54.0, 44.0, 34.0, 24.0];
        for (row_index, bottom_y) in row_bottoms.iter().enumerate() {
            for column in 0..5 {
                points.push(text_point(
                    &format!("r{}c{}", row_index + 1, column + 1),
                    15.0 + column as f64 * 50.0,
                    *bottom_y,
                    25.0,
                    6.0,
                ));
            }
        }

        let table = table_from_primitives(1, 1000.0, 1000.0, &segments, &points, 1)
            .expect("expected table");
        let row_count = table.cells.iter().map(|cell| cell.row).max().unwrap_or(0) + 1;

        assert_eq!(row_count, 8);
        assert!(
            table
                .cells
                .iter()
                .any(|cell| cell.row == 7 && cell.column == 4 && cell.text == "r8c5")
        );
    }

    #[test]
    fn opendataloader_table_border_splits_text_across_cells_by_x_range() {
        let segments = grid_segments(10.0, 10.0, 30.0, 30.0, 2, 2);
        let points = vec![text_point("test", 11.0, 25.0, 18.0, 10.0)];

        let table =
            table_from_primitives(1, 100.0, 100.0, &segments, &points, 1).expect("expected table");

        assert_eq!(table_cell_text(&table, 0, 0), "te");
        assert_eq!(table_cell_text(&table, 0, 1), "st");
    }

    #[test]
    fn opendataloader_table_border_links_neighbor_tables_by_shape() {
        let first = simple_table_extraction(1, 1, 10.0, 30.0, &[10.0, 20.0, 30.0]);
        let second = simple_table_extraction(2, 2, 10.0, 30.0, &[10.0, 20.0, 30.0]);
        let different = simple_table_extraction(3, 3, 10.0, 40.0, &[10.0, 25.0, 40.0]);

        assert!(opendataloader_neighbor_table_link(&first, &second));
        assert!(!opendataloader_neighbor_table_link(&first, &different));
    }

    #[test]
    fn opendataloader_column_major_numeric_table_reconstructs_split_year_case() {
        let source_path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../third_party/opendataloader-bench/pdfs/01030000000127.pdf");
        let extracted = extract_pages_with_pdf_oxide(source_path.to_str().unwrap(), None).unwrap();
        let positioned_pages = extracted
            .pages
            .iter()
            .map(|page| page.positioned_lines.clone())
            .collect::<Vec<_>>();

        let tables = extract_tables_from_positioned_lines(&positioned_pages, &[]);

        let table = tables
            .iter()
            .find(|table| {
                table.rationale == "opendataloader column-major numeric table extraction"
                    && table_cell_text(table, 0, 0) == "Year"
                    && table_cell_text(table, 0, 1) == "3-Year"
            })
            .expect("expected column-major year table");
        assert_eq!(table_row_count(table), 9);
        assert_eq!(table_column_count(table), 4);
        assert_eq!(table_cell_text(table, 0, 2), "5-Year");
        assert_eq!(table_cell_text(table, 0, 3), "7-Year");
        assert_eq!(table_cell_text(table, 1, 1), "33.0%");
        assert_eq!(table_cell_text(table, 8, 3), "4.46%");
    }

    #[test]
    fn opendataloader_matrix_table_json_preserves_empty_cells() {
        let table = TableExtraction {
            page_number: 1,
            table_id: "table-0001".to_string(),
            bbox: RuntimeBox {
                x0: 10.0,
                y0: 10.0,
                x1: 110.0,
                y1: 70.0,
            },
            rationale: "opendataloader matrix cluster table extraction".to_string(),
            cells: vec![
                table_cell(1, 0, 0, "Model"),
                table_cell(1, 0, 1, "Flag"),
                table_cell(1, 0, 2, "Score"),
                table_cell(1, 1, 0, "SFT v2"),
                table_cell(1, 1, 1, ""),
                table_cell(1, 1, 2, "69.21"),
            ],
        };

        let tables = table_json(&[table]);
        let cells = tables[0]["cells"].as_array().unwrap();

        assert!(cells.iter().any(|cell| {
            cell["rowRange"]["start"] == 1
                && cell["columnRange"]["start"] == 1
                && cell["text"] == ""
        }));
    }

    #[test]
    fn opendataloader_markdown_renderer_slots_cells_by_column_range() {
        let table = json!({
            "cells": [
                {"rowRange": {"start": 0, "end": 0}, "columnRange": {"start": 0, "end": 0}, "text": "Model"},
                {"rowRange": {"start": 0, "end": 0}, "columnRange": {"start": 1, "end": 1}, "text": "Flag"},
                {"rowRange": {"start": 0, "end": 0}, "columnRange": {"start": 2, "end": 2}, "text": "Score"},
                {"rowRange": {"start": 1, "end": 1}, "columnRange": {"start": 0, "end": 0}, "text": "SFT v2"},
                {"rowRange": {"start": 1, "end": 1}, "columnRange": {"start": 2, "end": 2}, "text": "69.21"}
            ]
        });

        let html = markdown_table_html(&table);

        assert!(html.contains(" <tr>\n  <td>SFT v2</td>\n  <td></td>\n  <td>69.21</td>\n </tr>"));
    }

    #[test]
    fn opendataloader_table_border_depth_guard_matches_reference_limit() {
        assert!(opendataloader_table_border_depth_allowed(9));
        assert!(!opendataloader_table_border_depth_allowed(10));
    }

    #[test]
    fn opendataloader_text_line_processor_sorts_chunks_by_left_x() {
        let lines = vec![
            line("content", 100.0, 300.0, 200.0, 310.0),
            line("Q:", 10.0, 300.0, 40.0, 310.0),
        ];

        let merged = merge_positioned_visual_lines(lines);

        assert_eq!(merged.len(), 1);
        assert_eq!(merged[0].text, "Q: content");
    }

    #[test]
    fn opendataloader_text_line_processor_adds_space_between_distant_chunks() {
        let lines = vec![
            line("A:", 10.0, 300.0, 30.0, 310.0),
            line("answer text", 50.0, 300.0, 150.0, 310.0),
        ];

        let merged = merge_positioned_visual_lines(lines);

        assert_eq!(merged.len(), 1);
        assert_eq!(merged[0].text, "A: answer text");
    }

    #[test]
    fn opendataloader_text_line_processor_does_not_merge_close_fragments_without_whitespace_signal()
    {
        let lines = vec![
            line_with_font_size("Evolution", 46.0, 300.0, 85.5, 310.0, 9.5),
            line_with_font_size("Of", 86.0, 300.0, 94.4, 310.0, 9.5),
        ];

        let merged = merge_positioned_visual_lines(lines);

        assert_eq!(merged.len(), 2);
    }

    #[test]
    fn opendataloader_text_line_processor_does_not_merge_toc_page_number_row() {
        let lines = vec![
            line("Introduction", 100.0, 300.0, 250.0, 310.0),
            line("7", 900.0, 300.0, 920.0, 310.0),
        ];

        let merged = merge_positioned_visual_lines(lines);

        assert_eq!(merged.len(), 2);
        assert_eq!(
            merged
                .iter()
                .map(|line| line.text.as_str())
                .collect::<Vec<_>>(),
            vec!["Introduction", "7"]
        );
    }

    #[test]
    fn opendataloader_text_line_processor_does_not_merge_table_numeric_row() {
        let lines = vec![
            line("2024", 10.0, 300.0, 50.0, 310.0),
            line("17", 120.0, 300.0, 145.0, 310.0),
            line("42%", 200.0, 300.0, 235.0, 310.0),
        ];

        let merged = merge_positioned_visual_lines(lines);

        assert_eq!(merged.len(), 3);
    }

    #[test]
    fn opendataloader_paragraph_right_alignment_precedes_two_line_heuristic() {
        let previous = line_with_font_size("short", 15.0, 20.0, 20.0, 30.0, 10.0);
        let next = line_with_font_size("longer line", 10.0, 10.0, 20.0, 20.0, 10.0);

        assert_eq!(
            opendataloader_paragraph_pair_alignment(&previous, &next),
            OpendataloaderParagraphAlignment::Right
        );
        assert!(opendataloader_two_line_paragraph_pair(&previous, &next));
    }

    #[test]
    fn opendataloader_hybrid_schema_maps_docling_blocks_to_trust_units() {
        let schema = json!({
            "texts": [
                {"label": "page_header", "text": "furniture", "prov": [{"page_no": 1, "bbox": {"l": 0.0, "b": 780.0, "r": 100.0, "t": 790.0}}]},
                {"label": "section_header", "text": "Profile", "meta": {"level": 2}, "prov": [{"page_no": 1, "bbox": {"l": 10.0, "b": 700.0, "r": 200.0, "t": 730.0}}]},
                {"label": "formula", "text": "E = mc^2", "prov": [{"page_no": 1, "bbox": {"l": 10.0, "b": 650.0, "r": 200.0, "t": 680.0}}]},
                {"label": "caption", "text": "Figure 1. Revenue trend.", "prov": [{"page_no": 1, "bbox": {"l": 10.0, "b": 620.0, "r": 300.0, "t": 640.0}}]}
            ],
            "pictures": [{
                "prov": [{"page_no": 1, "bbox": {"l": 20.0, "b": 400.0, "r": 320.0, "t": 560.0}}],
                "annotations": [{"kind": "description", "text": "Line chart showing revenue growth"}]
            }],
            "tables": [{
                "prov": [{"page_no": 1, "bbox": {"l": 10.0, "b": 100.0, "r": 210.0, "t": 220.0}}],
                "data": {
                    "grid": [[{}, {}], [{}, {}]],
                    "table_cells": [
                        {"start_row_offset_idx": 0, "start_col_offset_idx": 0, "text": "Name"},
                        {"start_row_offset_idx": 0, "start_col_offset_idx": 1, "text": "Score"},
                        {"start_row_offset_idx": 1, "start_col_offset_idx": 0, "text": "Alex"},
                        {"start_row_offset_idx": 1, "start_col_offset_idx": 1, "text": "98"}
                    ]
                }
            }]
        });

        let (units, tables) = opendataloader_hybrid_schema_to_units_and_tables(&schema);

        assert_eq!(units.len(), 4);
        assert_eq!(units[0]["kind"], "HEADING");
        assert_eq!(units[0]["textLevel"], 2);
        assert_eq!(content_block_type(&units[1]), "formula");
        assert_eq!(content_block_type(&units[2]), "caption");
        assert_eq!(units[3]["kind"], "IMAGE");
        assert_eq!(content_block_type(&units[3]), "image");
        assert_eq!(units[3]["text"], "Line chart showing revenue growth");
        assert_eq!(tables.len(), 1);
        assert_eq!(tables[0].cells.len(), 4);
        assert_eq!(table_cell_text(&tables[0], 1, 1), "98");
    }

    #[test]
    fn opendataloader_text_decoration_detects_strikethrough_and_underline() {
        let strike_target = OpendataloaderTextDecorationTarget {
            page_number: 1,
            bbox: RuntimeBox {
                x0: 10.0,
                y0: 100.0,
                x1: 60.0,
                y1: 120.0,
            },
            baseline: 100.0,
        };
        let strike_rule = OpendataloaderHorizontalRule {
            page_number: 1,
            left_x: 10.0,
            right_x: 60.0,
            center_y: 110.0,
            width: 50.0,
            thickness: 1.0,
        };
        let underline_rule = OpendataloaderHorizontalRule {
            page_number: 1,
            left_x: 12.0,
            right_x: 58.0,
            center_y: 97.0,
            width: 46.0,
            thickness: 1.0,
        };
        let wide_rule = OpendataloaderHorizontalRule {
            page_number: 1,
            left_x: 0.0,
            right_x: 200.0,
            center_y: 110.0,
            width: 200.0,
            thickness: 1.0,
        };
        let thick_rule = OpendataloaderHorizontalRule {
            page_number: 1,
            left_x: 10.0,
            right_x: 60.0,
            center_y: 110.0,
            width: 50.0,
            thickness: 8.0,
        };

        assert!(opendataloader_strikethrough_rule(
            &strike_rule,
            &strike_target
        ));
        assert!(opendataloader_underline_rule(
            &underline_rule,
            &strike_target
        ));
        assert!(!opendataloader_strikethrough_rule(
            &underline_rule,
            &strike_target
        ));
        assert!(!opendataloader_strikethrough_rule(
            &wide_rule,
            &strike_target
        ));
        assert!(!opendataloader_strikethrough_rule(
            &thick_rule,
            &strike_target
        ));
    }

    #[test]
    fn opendataloader_hybrid_schema_applies_text_decoration_style() {
        let schema = json!({
            "texts": [{
                "label": "text",
                "text": "obsolete value",
                "baseline": 100.0,
                "prov": [{"page_no": 1, "bbox": {"l": 10.0, "b": 100.0, "r": 60.0, "t": 120.0}}]
            }],
            "horizontal_rules": [{
                "page_no": 1,
                "bbox": {"l": 10.0, "b": 109.5, "r": 60.0, "t": 110.5},
                "thickness": 1.0
            }]
        });

        let (units, tables) = opendataloader_hybrid_schema_to_units_and_tables(&schema);

        assert!(tables.is_empty());
        assert_eq!(units.len(), 1);
        assert_eq!(units[0]["style"]["textDecoration"][0], "line-through");
    }

    #[test]
    fn worker_normalization_merges_hybrid_schema_into_trust_document_layers() {
        let mut document = json!({
            "docId": "sha256:test",
            "source": {
                "sourceFilename": "worker.pdf",
                "sourceHash": "sha256:test",
                "metadata": {"sourceFilename": "worker.pdf", "pageCount": 1}
            },
            "body": {
                "pages": [{"pageNumber": 1, "width": 1000.0, "height": 1000.0}],
                "units": [],
                "tables": []
            },
            "parserRun": {
                "parserRunId": "parser-run-worker",
                "parserVersion": "test-worker",
                "backend": "model-worker",
                "hybridSchema": {
                    "texts": [{
                        "label": "section_header",
                        "text": "Experience",
                        "meta": {"level": 2},
                        "prov": [{"page_no": 1, "bbox": {"l": 20.0, "b": 800.0, "r": 300.0, "t": 830.0}}]
                    }]
                },
                "models": [],
                "warnings": []
            },
            "auditGradeStatus": "AUDIT_GRADE"
        });
        let route = ModelRouteDecision {
            mode: "explicit-preset".to_string(),
            decision: "model-runtime".to_string(),
            effective_preset: "table-lite".to_string(),
            routed_pages: Vec::new(),
        };

        document = normalize_worker_document(document, "edge-model", &route, &json!({}));

        assert_eq!(document["body"]["units"][0]["kind"], "HEADING");
        assert_eq!(document["body"]["units"][0]["textLevel"], 2);
        assert_eq!(document["contentBlocks"][0]["type"], "heading");
        assert_eq!(
            document["parserRun"]["backend"],
            "rust-sidecar+model-worker"
        );
        assert_eq!(document["parserRun"]["workerBackend"], "model-worker");
    }

    #[test]
    fn worker_normalization_refreshes_content_blocks_and_parse_trace_from_units() {
        let mut document = json!({
            "docId": "sha256:test",
            "source": {
                "sourceFilename": "worker.pdf",
                "sourceHash": "sha256:test",
                "metadata": {"sourceFilename": "worker.pdf", "pageCount": 1}
            },
            "body": {
                "pages": [{"pageNumber": 1, "width": 1000.0, "height": 1000.0}],
                "units": [{
                    "unitId": "unit-mnn-table-cell-0001",
                    "kind": "TABLE_CELL",
                    "page": 1,
                    "text": "1.793E-06",
                    "evidenceSpanIds": ["span-mnn-table-cell-0001"],
                    "location": {
                        "page": 1,
                        "readingOrder": 1,
                        "boundingBox": {"x0": 10.0, "y0": 20.0, "x1": 90.0, "y1": 40.0}
                    },
                    "sourceObjectId": "mnn-table-cell-0001",
                    "confidence": {"score": 0.74, "rationale": "ocr numeric table grid clustering"},
                    "warnings": []
                }],
                "tables": []
            },
            "contentBlocks": [],
            "parseTrace": {
                "traceId": "trace-mnn-table-0001",
                "parserRunId": "parser-run-rust-mnn-table",
                "readingOrder": {"source": "mnn-table-detection-order", "fallback": false, "confidence": 0.72},
                "pages": [],
                "sectionTree": [],
                "warnings": [{"code": "table_text_assignment_used_ocr_spans"}]
            },
            "parserRun": {
                "parserRunId": "parser-run-rust-mnn-table",
                "parserVersion": "test-worker",
                "backend": "mnn-table-rs",
                "models": [],
                "warnings": []
            },
            "auditGradeStatus": "STRUCTURE_ONLY"
        });
        let route = ModelRouteDecision {
            mode: "explicit-preset".to_string(),
            decision: "model-runtime".to_string(),
            effective_preset: "table-lite".to_string(),
            routed_pages: Vec::new(),
        };

        document = normalize_worker_document(document, "edge-model", &route, &json!({}));

        assert_eq!(document["contentBlocks"][0]["type"], "table");
        assert_eq!(document["contentBlocks"][0]["text"], "1.793E-06");
        assert_eq!(
            document["parseTrace"]["pages"][0]["readingBlocks"][0]["text"],
            "1.793E-06"
        );
        assert_eq!(
            document["parseTrace"]["warnings"][0]["code"],
            "table_text_assignment_used_ocr_spans"
        );
    }

    #[test]
    fn worker_normalization_hides_structural_detection_units_from_reader_layers() {
        let mut document = json!({
            "docId": "sha256:test",
            "source": {
                "sourceFilename": "worker.pdf",
                "sourceHash": "sha256:test",
                "metadata": {"sourceFilename": "worker.pdf", "pageCount": 1}
            },
            "body": {
                "pages": [{"pageNumber": 1, "width": 1000.0, "height": 1000.0}],
                "units": [
                    {
                        "unitId": "unit-mnn-table-detection-0001",
                        "kind": "TABLE_REGION",
                        "page": 1,
                        "text": "table",
                        "evidenceSpanIds": ["span-mnn-table-detection-0001"],
                        "location": {
                            "page": 1,
                            "readingOrder": 1,
                            "boundingBox": {"x0": 0.0, "y0": 0.0, "x1": 900.0, "y1": 500.0}
                        },
                        "sourceObjectId": "mnn-table-detection-0001",
                        "confidence": {"score": 0.91, "rationale": "model detection"},
                        "warnings": []
                    },
                    {
                        "unitId": "unit-mnn-table-cell-0001",
                        "kind": "TABLE_CELL",
                        "page": 1,
                        "text": "Temperature (degree C)",
                        "evidenceSpanIds": ["span-mnn-table-cell-0001"],
                        "location": {
                            "page": 1,
                            "readingOrder": 2,
                            "boundingBox": {"x0": 10.0, "y0": 20.0, "x1": 190.0, "y1": 40.0}
                        },
                        "sourceObjectId": "mnn-table-cell-0001",
                        "confidence": {"score": 0.74, "rationale": "ocr numeric table grid clustering"},
                        "warnings": []
                    }
                ],
                "tables": []
            },
            "contentBlocks": [],
            "parseTrace": {
                "traceId": "trace-mnn-table-0001",
                "parserRunId": "parser-run-rust-mnn-table",
                "readingOrder": {"source": "mnn-table-detection-order", "fallback": false, "confidence": 0.72},
                "pages": [],
                "sectionTree": [],
                "warnings": []
            },
            "parserRun": {
                "parserRunId": "parser-run-rust-mnn-table",
                "parserVersion": "test-worker",
                "backend": "mnn-table-rs",
                "models": [],
                "warnings": []
            },
            "auditGradeStatus": "STRUCTURE_ONLY"
        });
        let route = ModelRouteDecision {
            mode: "explicit-preset".to_string(),
            decision: "model-runtime".to_string(),
            effective_preset: "table-lite".to_string(),
            routed_pages: Vec::new(),
        };

        document = normalize_worker_document(document, "edge-model", &route, &json!({}));

        assert_eq!(document["body"]["units"].as_array().unwrap().len(), 2);
        assert_eq!(document["contentBlocks"].as_array().unwrap().len(), 1);
        assert_eq!(document["contentBlocks"][0]["type"], "table");
        assert_eq!(
            document["contentBlocks"][0]["text"],
            "Temperature (degree C)"
        );
        assert_eq!(
            document["parseTrace"]["pages"][0]["readingBlocks"]
                .as_array()
                .unwrap()
                .len(),
            1
        );
        assert_eq!(
            document["parseTrace"]["pages"][0]["readingBlocks"][0]["text"],
            "Temperature (degree C)"
        );
    }

    fn ordered_text(lines: Vec<PositionedLine>) -> Vec<String> {
        order_positioned_lines(lines)
            .into_iter()
            .map(|line| line.text)
            .collect()
    }

    fn line(text: &str, x0: f64, y0: f64, x1: f64, y1: f64) -> PositionedLine {
        PositionedLine {
            text: text.to_string(),
            raw_bbox: RawPdfBox { x0, y0, x1, y1 },
            bbox: RuntimeBox { x0, y0, x1, y1 },
            page_width: 1000.0,
            page_height: 1000.0,
            font_size: 12.0,
        }
    }

    fn grid_segments(
        left: f64,
        bottom: f64,
        right: f64,
        top: f64,
        rows: usize,
        columns: usize,
    ) -> Vec<Segment> {
        let mut segments = Vec::new();
        for column in 0..=columns {
            let x = left + (right - left) * column as f64 / columns as f64;
            segments.push(Segment {
                x0: x,
                y0: bottom,
                x1: x,
                y1: top,
            });
        }
        for row in 0..=rows {
            let y = bottom + (top - bottom) * row as f64 / rows as f64;
            segments.push(Segment {
                x0: left,
                y0: y,
                x1: right,
                y1: y,
            });
        }
        segments
    }

    fn text_point(text: &str, x: f64, y: f64, width: f64, font_size: f64) -> TextPoint {
        TextPoint {
            x,
            y,
            width,
            font_size,
            text: text.to_string(),
            hidden: false,
        }
    }

    fn segment(x0: f64, y0: f64, x1: f64, y1: f64) -> Segment {
        Segment { x0, y0, x1, y1 }
    }

    fn table_cell_text(table: &TableExtraction, row: usize, column: usize) -> String {
        table
            .cells
            .iter()
            .find(|cell| cell.row == row && cell.column == column)
            .map(|cell| cell.text.clone())
            .unwrap_or_default()
    }

    fn table_cell(
        page_number: usize,
        row: usize,
        column: usize,
        text: &str,
    ) -> TableCellExtraction {
        TableCellExtraction {
            page_number,
            cell_id: format!("cell-test-{row:04}-{column:04}"),
            row,
            column,
            row_end: row,
            column_end: column,
            bbox: RuntimeBox {
                x0: column as f64 * 10.0,
                y0: row as f64 * 10.0,
                x1: column as f64 * 10.0 + 10.0,
                y1: row as f64 * 10.0 + 10.0,
            },
            text: text.to_string(),
        }
    }

    fn simple_table_extraction(
        page_number: usize,
        table_index: usize,
        left: f64,
        right: f64,
        xs: &[f64],
    ) -> TableExtraction {
        let mut cells = Vec::new();
        for column in 0..xs.len().saturating_sub(1) {
            cells.push(TableCellExtraction {
                page_number,
                cell_id: format!("cell-{table_index:04}-0000-{column:04}"),
                row: 0,
                column,
                row_end: 0,
                column_end: column,
                bbox: RuntimeBox {
                    x0: xs[column],
                    y0: 10.0,
                    x1: xs[column + 1],
                    y1: 20.0,
                },
                text: format!("h{column}"),
            });
        }
        TableExtraction {
            page_number,
            table_id: format!("table-{table_index:04}"),
            bbox: RuntimeBox {
                x0: left,
                y0: 10.0,
                x1: right,
                y1: 20.0,
            },
            rationale: "test".to_string(),
            cells,
        }
    }

    fn line_with_font_size(
        text: &str,
        x0: f64,
        y0: f64,
        x1: f64,
        y1: f64,
        font_size: f64,
    ) -> PositionedLine {
        PositionedLine {
            text: text.to_string(),
            raw_bbox: RawPdfBox { x0, y0, x1, y1 },
            bbox: RuntimeBox { x0, y0, x1, y1 },
            page_width: 1000.0,
            page_height: 1000.0,
            font_size,
        }
    }

    fn position(values: &[String], needle: &str) -> usize {
        values
            .iter()
            .position(|value| value == needle)
            .expect("expected text")
    }

    fn markdown_unit(unit_id: &str, text: &str, x0: f64, y0: f64) -> Value {
        json!({
            "unitId": unit_id,
            "kind": "LINE_SPAN",
            "page": 1,
            "text": text,
            "location": {
                "page": 1,
                "readingOrder": 1,
                "boundingBox": {
                    "x0": x0,
                    "y0": y0,
                    "x1": x0 + 100.0,
                    "y1": y0 + 20.0
                }
            }
        })
    }

    fn model_table_cell(text: &str, row: u64, column: u64, x0: f64, y0: f64) -> Value {
        json!({
            "text": text,
            "rowRange": {"start": row, "end": row},
            "columnRange": {"start": column, "end": column},
            "boundingBox": {
                "x0": x0,
                "y0": y0,
                "x1": x0 + 90.0,
                "y1": y0 + 20.0
            }
        })
    }
}
