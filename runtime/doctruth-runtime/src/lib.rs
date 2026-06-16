use std::collections::BTreeMap;
use std::env;
use std::fs;
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::{SystemTime, UNIX_EPOCH};

use pdf_oxide::content::{Operator, TextElement, parse_content_stream};
use pdf_oxide::document::PdfDocument;
use pdf_oxide::layout::TextSpan;
use pdf_oxide::pipeline::page_reading_order;
use pdf_oxide::rendering::{RenderOptions, render_page};
use pdf_oxide::structure::{
    Table as PdfOxideTable, TableDetectionConfig, detect_tables_from_spans,
};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

const RUNTIME: &str = "doctruth-runtime";
const PROTOCOL_VERSION: &str = "1";
const PDF_BACKEND_TARGET: &str = "pdf_oxide";
const PDF_BACKEND_CURRENT: &str = "pdf_oxide";
const PDF_BACKEND_STATUS: &str = "DEFAULT";
const PAGE_WIDTH: f64 = 612.0;
const PAGE_HEIGHT: f64 = 792.0;
const GRID_EPSILON: f64 = 1.0;
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
        Some("verify_benchmark_report") => {
            verify_benchmark_report_json(&request).map(|json| json.to_string())
        }
        Some(_) => Err(error_json("UNKNOWN_COMMAND", "unsupported runtime command").to_string()),
        None => Err(error_json("MISSING_COMMAND", "request.command is required").to_string()),
    }
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
        "capabilities": capabilities
    })
}

fn pdf_backend_json() -> Value {
    json!({
        "target": PDF_BACKEND_TARGET,
        "current": PDF_BACKEND_CURRENT,
        "status": PDF_BACKEND_STATUS,
        "features": ["legacy-crypto", "rendering"]
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
    let preset = request
        .get("preset")
        .and_then(Value::as_str)
        .unwrap_or("lite");
    let required_models = required_model_descriptors(preset);
    if !required_models.is_empty() {
        if let Some(document) = configured_model_worker_parse(
            source_path,
            source_hash,
            preset,
            &required_models,
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
    let extracted = extract_pages_with_pdf_oxide(source_path)?;
    let extracted_pages = extracted.pages;
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
    let tables = extract_tables(source_path).unwrap_or_default();
    let page_metadata =
        extract_page_metadata(source_path).unwrap_or_else(|_| fallback_page_metadata(&page_lines));
    let mut units = unit_json(&page_lines, &positioned_lines);
    units.extend(table_unit_json(&tables, units.len() + 1));
    let mut warnings = extracted_pages
        .iter()
        .flat_map(|page| page.warnings.clone())
        .collect::<Vec<_>>();
    warnings.extend(extracted.warnings.clone());
    warnings.extend(model_unavailable_warnings(preset, &required_models));
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
            "preset": preset,
            "backend": "rust-sidecar",
            "pdfBackend": pdf_backend_json(),
            "readingOrder": reading_order,
            "models": model_identities,
            "warnings": warnings
        },
        "auditGradeStatus": audit_grade_status
    }))
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

fn extract_pages_with_pdf_oxide(source_path: &str) -> Result<ExtractedDocument, String> {
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
    let pages = (0..page_count)
        .map(|page_index| {
            let (page_width, page_height) = pdf_oxide_page_dimensions(&document, page_index)
                .unwrap_or((PAGE_WIDTH, PAGE_HEIGHT));
            page_reading_order(&document, page_index)
                .map(|ordered_spans| {
                    let raw_safety = raw_content_safety(&document, page_index);
                    let canonical_lines = positioned_lines_from_spans(
                        ordered_spans.iter().map(|ordered_span| &ordered_span.span),
                        page_width,
                        page_height,
                    );
                    let (positioned_lines, mut warnings) =
                        filter_positioned_lines(canonical_lines, &raw_safety.hidden_texts);
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
    let Ok(operations) = parse_content_stream(&content) else {
        return RawContentSafety::default();
    };
    let (_segments, text_points) = page_graphics_and_text(&operations);
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
        color: RuntimeColor {
            r: span.color.r as f64,
            g: span.color.g as f64,
            b: span.color.b as f64,
        },
    }
}

const XY_CUT_CROSS_LAYOUT_BETA: f64 = 0.7;
const XY_CUT_DENSITY_THRESHOLD: f64 = 0.9;
const XY_CUT_OVERLAP_THRESHOLD: f64 = 0.1;
const XY_CUT_MIN_OVERLAP_COUNT: usize = 2;
const XY_CUT_MIN_GAP: f64 = 5.0;
const XY_CUT_NARROW_WIDTH_RATIO: f64 = 0.1;
const XY_CUT_CROSS_LAYOUT_MEDIAN_RATIO: f64 = 1.5;

// Adapted from OpenDataLoader PDF's Apache-2.0 XYCutPlusPlusSorter at
// opendataloader-project/opendataloader-pdf@58a6dc782d27a42d433ffd1052be3f2c61f75cb3.
// DocTruth keeps TrustDocument as the only canonical output contract.
fn order_positioned_lines(lines: Vec<PositionedLine>) -> Vec<PositionedLine> {
    xy_cut_plus_plus_sort(lines, XY_CUT_CROSS_LAYOUT_BETA, XY_CUT_DENSITY_THRESHOLD)
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
    let mut widths = lines
        .iter()
        .map(|line| bbox_width(&line.bbox))
        .collect::<Vec<_>>();
    widths.sort_by(f64::total_cmp);
    let max_width = widths.iter().copied().fold(0.0, f64::max);
    let median_width = widths[widths.len() / 2].max(1.0);
    let threshold = beta * max_width;
    lines
        .iter()
        .map(|line| {
            let width = bbox_width(&line.bbox);
            width >= threshold
                && width >= median_width * XY_CUT_CROSS_LAYOUT_MEDIAN_RATIO
                && horizontal_overlap_count(line, lines) >= XY_CUT_MIN_OVERLAP_COUNT
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
        if near_white_positioned_line(&line) {
            warnings.push(parser_safety_warning(
                "background_text_filtered",
                &format!(
                    "Filtered near-white/background-like text-layer span: {}",
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

fn off_page_positioned_line(line: &PositionedLine) -> bool {
    line.raw_bbox.x1 <= 0.0
        || line.raw_bbox.y1 <= 0.0
        || line.raw_bbox.x0 >= line.page_width
        || line.raw_bbox.y0 >= line.page_height
}

fn tiny_positioned_line(line: &PositionedLine) -> bool {
    line.font_size <= 2.0 || bbox_width(&line.bbox) <= 2.0 || bbox_height(&line.bbox) <= 2.0
}

fn near_white_positioned_line(line: &PositionedLine) -> bool {
    line.color.r >= 0.98 && line.color.g >= 0.98 && line.color.b >= 0.98
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
    required_models: &[RequiredModel],
    request: &Value,
) -> Result<Option<Value>, String> {
    let Some(command) = configured_model_worker_command() else {
        return Ok(None);
    };
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
        "offline_mode": request.get("offline_mode").and_then(Value::as_bool).unwrap_or(true),
        "allow_model_downloads": request.get("allow_model_downloads").and_then(Value::as_bool).unwrap_or(false),
        "modelCacheDirectory": model_cache_directory(),
        "requiredModels": required_models.iter().map(RequiredModel::json).collect::<Vec<_>>(),
        "models": worker_model_artifacts(preset, required_models)
    });
    let output = run_model_worker(&command, &worker_request)?;
    let response: Value = serde_json::from_str(&output).map_err(|error| {
        error_json("MODEL_WORKER_FAILED", &format!("invalid JSON: {error}")).to_string()
    })?;
    let document = normalize_worker_document(worker_document(response)?);
    validate_worker_document(&document)?;
    Ok(Some(document))
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

fn normalize_worker_document(mut document: Value) -> Value {
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
    }
    document
}

fn configured_model_worker_command() -> Option<String> {
    env::var("DOCTRUTH_RUNTIME_MODEL_COMMAND")
        .ok()
        .or_else(|| env::var("DOCTRUTH_MODEL_COMMAND").ok())
        .filter(|command| !command.trim().is_empty())
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
    let Some(command) = configured_model_worker_command() else {
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

fn worker_model_artifacts(preset: &str, required_models: &[RequiredModel]) -> Vec<Value> {
    worker_model_artifacts_with_cache_dir(preset, required_models, &model_cache_directory())
}

fn worker_model_artifacts_with_cache_dir(
    preset: &str,
    required_models: &[RequiredModel],
    cache_dir: &str,
) -> Vec<Value> {
    let manifest_models = model_manifest_artifacts(preset);
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

fn model_manifest_artifacts(preset: &str) -> Vec<Value> {
    let Some(manifest_path) = configured_model_manifest_path() else {
        return Vec::new();
    };
    let Ok(manifest) = read_json_file(Path::new(&manifest_path), "MODEL_MANIFEST_INVALID") else {
        return Vec::new();
    };
    manifest
        .pointer(&format!("/presets/{preset}"))
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default()
}

fn configured_model_manifest_path() -> Option<String> {
    env::var("DOCTRUTH_MODEL_MANIFEST")
        .ok()
        .filter(|value| !value.trim().is_empty())
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
    let cache_path = Path::new(cache_dir).join(model_cache_filename(&name, &version));
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

fn model_cache_filename(name: &str, version: &str) -> String {
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
    let external = external_metrics(base_dir, &manifest)?;
    let mut case_reports = Vec::new();
    for case in cases {
        case_reports.push(run_benchmark_case(base_dir, case)?);
    }
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
        "passed": true,
        "metrics": metrics,
        "cases": public_case_reports
    });
    write_benchmark_report_if_requested(request, manifest_path, &report)?;
    Ok(report)
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
    for case in case_reports {
        let id = case
            .get("labelId")
            .and_then(Value::as_str)
            .or_else(|| case.get("name").and_then(Value::as_str))
            .unwrap_or("document");
        let markdown = case
            .get("_actualMarkdown")
            .and_then(Value::as_str)
            .unwrap_or("");
        fs::write(
            markdown_dir.join(format!("{}.md", safe_document_id(id))),
            markdown,
        )
        .map_err(|error| {
            error_json("BENCHMARK_REPORT_WRITE_FAILED", &error.to_string()).to_string()
        })?;
    }
    let summary = json!({
        "engine_name": "doctruth",
        "engine_version": env!("CARGO_PKG_VERSION"),
        "document_count": case_reports.len()
    });
    fs::write(root.join("summary.json"), pretty_json(&summary)?).map_err(|error| {
        error_json("BENCHMARK_REPORT_WRITE_FAILED", &error.to_string()).to_string()
    })?;
    Ok(json!({
        "opendataloaderPrediction": {
            "engine": "doctruth",
            "path": root.to_string_lossy(),
            "markdownPath": markdown_dir.to_string_lossy(),
            "documentCount": case_reports.len()
        }
    }))
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

fn run_benchmark_case(base_dir: &Path, case: &Value) -> Result<Value, String> {
    let source_path = resolve_case_path(base_dir, case, "source")?;
    let expected_markdown =
        fs::read_to_string(resolve_case_path(base_dir, case, "expectedMarkdown")?).map_err(
            |error| error_json("BENCHMARK_CORPUS_INVALID", &error.to_string()).to_string(),
        )?;
    let expected_document = resolve_case_path(base_dir, case, "expectedDocument")?;
    let expected_document = read_json_file(&expected_document, "BENCHMARK_CORPUS_INVALID")?;
    let preset = case.get("preset").and_then(Value::as_str).unwrap_or("lite");
    let source_sha = checked_source_sha(&source_path, case)?;
    let document = parse_pdf_json(&json!({
        "command": "parse_pdf",
        "source_path": source_path,
        "source_hash": source_sha,
        "preset": preset,
        "offline_mode": true,
        "allow_model_downloads": false
    }))?;
    let actual_markdown = markdown_from_document(&document);
    let metrics = case_metrics(
        &document,
        &expected_document,
        &actual_markdown,
        &expected_markdown,
    );

    Ok(json!({
        "name": case.get("name").and_then(Value::as_str).unwrap_or("unnamed"),
        "labelId": required_nested_str(case, "labelId")?,
        "sourceSha256": source_sha,
        "tags": case.get("tags").cloned().unwrap_or_else(|| json!([])),
        "fixtureTypes": case.get("fixtureTypes").cloned().unwrap_or_else(|| json!([])),
        "behaviors": case.get("behaviors").cloned().unwrap_or_else(|| json!([])),
        "preset": preset,
        "source": source_path.file_name().and_then(|name| name.to_str()).unwrap_or(""),
        "_actualMarkdown": actual_markdown,
        "metrics": metrics,
        "replay": case_replay(&json!({
            "sourceSha256": source_sha,
            "metrics": metrics
        }))
    }))
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
    if let Some(units) = document.pointer("/body/units").and_then(Value::as_array) {
        for unit in units {
            if let Some(text) = unit.get("text").and_then(Value::as_str) {
                let text = normalize_text(text);
                if !text.is_empty() {
                    lines.push(text);
                }
            }
        }
    }
    lines.join("\n")
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
        "ocr" => vec![RequiredModel {
            name: "ocr-router",
            version: "v1",
            expected_sha: "sha256:pending-ocr-router-v1",
        }],
        _ => Vec::new(),
    }
}

fn model_unavailable_warnings(preset: &str, models: &[RequiredModel]) -> Vec<Value> {
    models
        .iter()
        .map(|model| {
            json!({
                "code": "model_unavailable_fallback",
                "severity": "SEVERE",
                "message": format!(
                    "Required model {} is unavailable for parser preset {}; expected SHA {}. The runtime emitted heuristic output for inspection only.",
                    model.identity(),
                    preset,
                    model.expected_sha
                )
            })
        })
        .collect()
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
        pdf_oxide_rendered_page_hash(document, page_index)
    }
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
                "location": {
                    "page": cell.page_number,
                    "readingOrder": reading_order,
                    "boundingBox": bbox_json(&cell.bbox)
                },
                "sourceObjectId": cell.cell_id,
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

fn table_json(tables: &[TableExtraction]) -> Vec<Value> {
    tables
        .iter()
        .map(|table| {
            json!({
                "tableId": table.table_id,
                "pageNumber": table.page_number,
                "boundingBox": bbox_json(&table.bbox),
                "confidence": {
                    "score": 0.78,
                    "rationale": table.rationale
                },
                "cells": table.cells.iter()
                    .filter(|cell| !cell.text.is_empty())
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

fn content_blocks_json(units: &[Value]) -> Vec<Value> {
    units
        .iter()
        .filter_map(|unit| {
            let reading_order = unit.pointer("/location/readingOrder")?.as_u64()?;
            Some(json!({
                "blockId": format!("block-{reading_order:04}"),
                "type": content_block_type(unit),
                "page": unit.get("page").cloned().unwrap_or_else(|| json!(1)),
                "bbox": unit.pointer("/location/boundingBox").cloned().unwrap_or_else(|| json!({})),
                "readingOrder": reading_order,
                "text": unit.get("text").cloned().unwrap_or_else(|| json!("")),
                "sourceUnitIds": [unit.get("unitId").cloned().unwrap_or_else(|| json!(""))],
                "evidenceSpanIds": unit.get("evidenceSpanIds").cloned().unwrap_or_else(|| json!([])),
                "warnings": unit.get("warnings").cloned().unwrap_or_else(|| json!([]))
            }))
        })
        .collect()
}

fn content_block_type(unit: &Value) -> &'static str {
    match unit.get("kind").and_then(Value::as_str).unwrap_or("") {
        "TABLE_CELL" => "table",
        _ => "text",
    }
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
        "pages": pages
            .iter()
            .enumerate()
            .map(|(index, page)| trace_page_json(index, page, units))
            .collect::<Vec<_>>(),
        "warnings": []
    })
}

fn trace_page_json(index: usize, page: &Value, units: &[Value]) -> Value {
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
        "readingBlocks": units
            .iter()
            .filter(|unit| unit.get("page").and_then(Value::as_u64) == Some(page_number))
            .filter_map(trace_block_json)
            .collect::<Vec<_>>(),
        "discardedBlocks": [],
        "images": [],
        "tables": [],
        "equations": []
    })
}

fn trace_block_json(unit: &Value) -> Option<Value> {
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
    Some(json!({
        "blockId": format!("block-{reading_order:04}"),
        "type": content_block_type(unit),
        "bbox": bbox,
        "readingOrder": reading_order,
        "confidence": unit.pointer("/confidence/score").cloned().unwrap_or_else(|| json!(0.0)),
        "modelRunId": "",
        "sourceUnitIds": [unit.get("unitId").cloned().unwrap_or_else(|| json!(""))],
        "evidenceSpanIds": unit.get("evidenceSpanIds").cloned().unwrap_or_else(|| json!([])),
        "warnings": unit.get("warnings").cloned().unwrap_or_else(|| json!([])),
        "lines": [trace_line_json(reading_order, text, &bbox, source_object_id, evidence_span_id)]
    }))
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

#[derive(Debug, Clone)]
struct TextPoint {
    x: f64,
    y: f64,
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
    color: RuntimeColor,
}

#[derive(Debug, Clone)]
struct RawPdfBox {
    x0: f64,
    y0: f64,
    x1: f64,
    y1: f64,
}

#[derive(Debug, Clone)]
struct RuntimeColor {
    r: f64,
    g: f64,
    b: f64,
}

fn extract_tables(source_path: &str) -> Result<Vec<TableExtraction>, String> {
    let line_tables = extract_tables_with_pdf_oxide_lines(source_path)?;
    if !line_tables.is_empty() {
        return Ok(line_tables);
    }
    let pdf_oxide_tables = extract_tables_with_pdf_oxide_spatial(source_path).unwrap_or_default();
    if pdf_oxide_tables.is_empty() {
        Ok(Vec::new())
    } else {
        Ok(pdf_oxide_tables)
    }
}

fn extract_tables_with_pdf_oxide_lines(source_path: &str) -> Result<Vec<TableExtraction>, String> {
    let document = PdfDocument::open(source_path).map_err(|error| error.to_string())?;
    let page_count = document.page_count().map_err(|error| error.to_string())?;
    let mut tables = Vec::new();
    for page_index in 0..page_count {
        if let Some(table) = table_from_pdf_oxide_page(&document, page_index, tables.len() + 1)? {
            tables.push(table);
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
    let content = document
        .get_page_content_data(page_index)
        .map_err(|error| error.to_string())?;
    let operations = parse_content_stream(&content).map_err(|error| error.to_string())?;
    let (segments, text_points) = page_graphics_and_text(&operations);
    let (page_width, page_height) =
        pdf_oxide_page_dimensions(document, page_index).unwrap_or((PAGE_WIDTH, PAGE_HEIGHT));
    Ok(table_from_primitives(
        page_index + 1,
        page_width,
        page_height,
        &segments,
        &text_points,
        table_index,
    ))
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
        && aligned_table_boxes(previous, current)
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

fn aligned_table_boxes(previous: &TableExtraction, current: &TableExtraction) -> bool {
    (previous.bbox.x0 - current.bbox.x0).abs() <= 20.0
        && (previous.bbox.x1 - current.bbox.x1).abs() <= 20.0
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
            let text = text_points
                .iter()
                .filter(|point| point.x >= cell_left && point.x <= cell_right)
                .filter(|point| point.y >= cell_bottom && point.y <= row_top)
                .map(|point| point.text.as_str())
                .collect::<Vec<_>>()
                .join(" ");
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
                text: normalize_text(&text),
            });
            mark_occupied(&mut occupied, row, column, row_end, column_end);
            column = column_end + 1;
        }
    }

    Some(TableExtraction {
        page_number,
        table_id: format!("table-{table_index:04}"),
        bbox: normalize_bbox_for_page(page_width, page_height, left, top, right, bottom),
        rationale: "pdf_oxide line-table extraction".to_string(),
        cells,
    })
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
        return None;
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
    Some(TableExtraction {
        page_number,
        table_id: format!("table-{table_index:04}"),
        bbox: combined_bbox(cells.iter().map(|cell| &cell.bbox).collect()),
        rationale: "borderless aligned text table extraction".to_string(),
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
    let text_width = point.text.chars().count() as f64 * point.font_size * 0.55;
    normalize_bbox_for_page(
        page_width,
        page_height,
        point.x,
        point.y + point.font_size,
        point.x + text_width,
        point.y - point.font_size * 0.25,
    )
}

fn page_graphics_and_text(operations: &[Operator]) -> (Vec<Segment>, Vec<TextPoint>) {
    let mut segments = Vec::new();
    let mut path_points: Vec<(f64, f64)> = Vec::new();
    let mut text_points = Vec::new();
    let mut text_x = 0.0;
    let mut text_y = 0.0;
    let mut font_size = 12.0;
    let mut hidden = false;

    for operation in operations {
        match operation {
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

    (segments, text_points)
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
            color: RuntimeColor {
                r: 0.0,
                g: 0.0,
                b: 0.0,
            },
        }
    }

    fn position(values: &[String], needle: &str) -> usize {
        values
            .iter()
            .position(|value| value == needle)
            .expect("expected text")
    }
}
