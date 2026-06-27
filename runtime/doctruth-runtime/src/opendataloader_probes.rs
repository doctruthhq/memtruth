use serde_json::{Value, json};

use super::{
    PAGE_HEIGHT, PAGE_WIDTH, PROTOCOL_VERSION, PositionedLine, RUNTIME, RawPdfBox, RuntimeBox,
    bbox_center_y, bbox_height, error_json, merge_positioned_visual_row, normalize_text,
    sort_positioned_y_then_x,
};

const OPENDATALOADER_REPLACEMENT_CHARACTER: char = '\u{fffd}';
const OPENDATALOADER_REPLACEMENT_CHARACTER_STRING: &str = "\u{fffd}";
const OPENDATALOADER_TEXT_PROCESSOR_REFERENCE: &str = "third_party/opendataloader-pdf-reference/java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/TextProcessor.java";
const OPENDATALOADER_TEXT_LINE_PROCESSOR_REFERENCE: &str = "third_party/opendataloader-pdf-reference/java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/TextLineProcessor.java";
const OPENDATALOADER_PARAGRAPH_PROCESSOR_REFERENCE: &str = "third_party/opendataloader-pdf-reference/java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/ParagraphProcessor.java";
const OPENDATALOADER_TABLE_BORDER_PROCESSOR_REFERENCE: &str = "third_party/opendataloader-pdf-reference/java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/TableBorderProcessor.java";
const OPENDATALOADER_HEADING_PROCESSOR_REFERENCE: &str = "third_party/opendataloader-pdf-reference/java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/HeadingProcessor.java";
const OPENDATALOADER_LIST_PROCESSOR_REFERENCE: &str = "third_party/opendataloader-pdf-reference/java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/ListProcessor.java";
const OPENDATALOADER_MAX_NESTED_TABLE_DEPTH: u64 = 10;
const OPENDATALOADER_NEIGHBOUR_TABLE_EPSILON: f64 = 0.2;

pub(crate) fn opendataloader_text_processor_probe_json(request: &Value) -> Result<Value, String> {
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

pub(crate) fn opendataloader_line_paragraph_probe_json(request: &Value) -> Result<Value, String> {
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
        "paragraphAlignments": paragraph_output.paragraph_alignments,
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
    paragraph_alignments: Vec<Value>,
}

fn opendataloader_probe_paragraph_output(
    lines: Vec<PositionedLine>,
) -> OpendataloaderProbeParagraphOutput {
    let mut output = OpendataloaderProbeParagraphOutput {
        paragraphs: Vec::new(),
        joined_paragraphs: Vec::new(),
        paragraph_alignments: Vec::new(),
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
        for pair in lines.windows(2) {
            output
                .paragraph_alignments
                .push(opendataloader_probe_paragraph_alignment(&pair[0], &pair[1]));
        }
    }
}

fn opendataloader_probe_wrapped_pair(previous: &PositionedLine, next: &PositionedLine) -> bool {
    if !opendataloader_probe_terminal_line(&previous.text)
        && opendataloader_probe_right_aligned_paragraph_pair(previous, next)
    {
        return true;
    }
    let vertical_gap = next.bbox.y0 - previous.bbox.y1;
    let same_left_edge = (previous.bbox.x0 - next.bbox.x0).abs() <= 8.0;
    same_left_edge
        && (-2.0..=previous.font_size.max(next.font_size) * 0.7).contains(&vertical_gap)
        && !opendataloader_probe_terminal_line(&previous.text)
}

fn opendataloader_probe_paragraph_alignment(
    previous: &PositionedLine,
    next: &PositionedLine,
) -> Value {
    if opendataloader_probe_right_aligned_paragraph_pair(previous, next) {
        return json!({
            "alignment": "right",
            "reason": "OpenDataLoader ParagraphProcessor right-alignment precedence"
        });
    }
    if opendataloader_probe_two_line_paragraph_pair(previous, next) {
        return json!({
            "alignment": "left",
            "reason": "OpenDataLoader ParagraphProcessor two-line heuristic"
        });
    }
    json!({
        "alignment": "none",
        "reason": "no OpenDataLoader ParagraphProcessor pair rule matched"
    })
}

fn opendataloader_probe_right_aligned_paragraph_pair(
    previous: &PositionedLine,
    next: &PositionedLine,
) -> bool {
    (previous.bbox.x1 - next.bbox.x1).abs() <= 1.0
        && opendataloader_probe_adjacent_paragraph_lines(previous, next)
        && opendataloader_probe_close_ratio(previous.font_size, next.font_size, 0.05)
}

fn opendataloader_probe_two_line_paragraph_pair(
    previous: &PositionedLine,
    next: &PositionedLine,
) -> bool {
    previous.bbox.x0 >= next.bbox.x0
        && previous.bbox.x1 >= next.bbox.x1
        && opendataloader_probe_adjacent_paragraph_lines(previous, next)
        && opendataloader_probe_close_ratio(previous.font_size, next.font_size, 0.05)
}

fn opendataloader_probe_adjacent_paragraph_lines(
    previous: &PositionedLine,
    next: &PositionedLine,
) -> bool {
    let vertical_gap = next.bbox.y0 - previous.bbox.y1;
    (-2.0..=previous.font_size.max(next.font_size) * 0.35).contains(&vertical_gap)
}

fn opendataloader_probe_close_ratio(left: f64, right: f64, epsilon: f64) -> bool {
    let max_value = left.abs().max(right.abs());
    if max_value <= f64::EPSILON {
        return true;
    }
    (left - right).abs() / max_value <= epsilon
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

pub(crate) fn opendataloader_table_border_probe_json(request: &Value) -> Result<Value, String> {
    let text_chunk = opendataloader_probe_text_chunk(request)?;
    let cells = opendataloader_probe_cells(request)?;
    let tables = opendataloader_probe_neighbor_tables(request)?;
    let depths = opendataloader_probe_depths(request)?;

    Ok(json!({
        "runtime": RUNTIME,
        "protocol_version": PROTOCOL_VERSION,
        "source": "OpenDataLoader TableBorderProcessor",
        "cellTextParts": opendataloader_probe_table_cell_text_parts(&text_chunk, &cells),
        "neighborLinks": opendataloader_probe_neighbor_links(&tables),
        "depthAllowed": depths
            .into_iter()
            .map(|depth| depth < OPENDATALOADER_MAX_NESTED_TABLE_DEPTH)
            .collect::<Vec<_>>(),
        "reference": OPENDATALOADER_TABLE_BORDER_PROCESSOR_REFERENCE
    }))
}

struct OpendataloaderProbeTextChunk {
    text: String,
    x0: f64,
    x1: f64,
}

struct OpendataloaderProbeCell {
    left: f64,
    right: f64,
}

struct OpendataloaderProbeTableShape {
    width: f64,
    columns: Vec<f64>,
}

fn opendataloader_probe_text_chunk(
    request: &Value,
) -> Result<OpendataloaderProbeTextChunk, String> {
    let value = request
        .get("textChunk")
        .or_else(|| request.get("text_chunk"))
        .ok_or_else(|| {
            error_json(
                "MISSING_TEXT_CHUNK",
                "request.textChunk is required for table border probe",
            )
            .to_string()
        })?;
    let text = value.get("text").and_then(Value::as_str).ok_or_else(|| {
        error_json("INVALID_TEXT_CHUNK", "textChunk.text is required").to_string()
    })?;
    let x0 = opendataloader_probe_named_f64(value, "x0", "INVALID_TEXT_CHUNK")?;
    let x1 = opendataloader_probe_named_f64(value, "x1", "INVALID_TEXT_CHUNK")?;
    if x1 <= x0 {
        return Err(error_json("INVALID_TEXT_CHUNK", "textChunk must satisfy x0 < x1").to_string());
    }
    Ok(OpendataloaderProbeTextChunk {
        text: text.to_string(),
        x0,
        x1,
    })
}

fn opendataloader_probe_cells(request: &Value) -> Result<Vec<OpendataloaderProbeCell>, String> {
    let values = request
        .get("cells")
        .and_then(Value::as_array)
        .ok_or_else(|| error_json("MISSING_CELLS", "request.cells is required").to_string())?;
    values
        .iter()
        .map(|value| {
            let left = opendataloader_probe_named_f64(value, "left", "INVALID_CELL")?;
            let right = opendataloader_probe_named_f64(value, "right", "INVALID_CELL")?;
            if right <= left {
                return Err(
                    error_json("INVALID_CELL", "cell must satisfy left < right").to_string()
                );
            }
            Ok(OpendataloaderProbeCell { left, right })
        })
        .collect()
}

fn opendataloader_probe_neighbor_tables(
    request: &Value,
) -> Result<Vec<OpendataloaderProbeTableShape>, String> {
    let values = request
        .get("neighborTables")
        .or_else(|| request.get("neighbor_tables"))
        .and_then(Value::as_array)
        .ok_or_else(|| {
            error_json(
                "MISSING_NEIGHBOR_TABLES",
                "request.neighborTables is required",
            )
            .to_string()
        })?;
    values
        .iter()
        .map(|value| {
            let width = opendataloader_probe_named_f64(value, "width", "INVALID_TABLE_SHAPE")?;
            let columns = value
                .get("columns")
                .and_then(Value::as_array)
                .ok_or_else(|| {
                    error_json("INVALID_TABLE_SHAPE", "neighbor table columns are required")
                        .to_string()
                })?
                .iter()
                .map(|column| {
                    column
                        .as_f64()
                        .filter(|value| value.is_finite())
                        .ok_or_else(|| {
                            error_json("INVALID_TABLE_SHAPE", "column width must be finite")
                                .to_string()
                        })
                })
                .collect::<Result<Vec<_>, String>>()?;
            Ok(OpendataloaderProbeTableShape { width, columns })
        })
        .collect()
}

fn opendataloader_probe_depths(request: &Value) -> Result<Vec<u64>, String> {
    request
        .get("depths")
        .and_then(Value::as_array)
        .ok_or_else(|| error_json("MISSING_DEPTHS", "request.depths is required").to_string())?
        .iter()
        .map(|value| {
            value
                .as_u64()
                .ok_or_else(|| error_json("INVALID_DEPTH", "depth must be unsigned").to_string())
        })
        .collect()
}

fn opendataloader_probe_named_f64(value: &Value, field: &str, code: &str) -> Result<f64, String> {
    value
        .get(field)
        .and_then(Value::as_f64)
        .filter(|number| number.is_finite())
        .ok_or_else(|| error_json(code, &format!("{field} must be finite")).to_string())
}

fn opendataloader_probe_table_cell_text_parts(
    text_chunk: &OpendataloaderProbeTextChunk,
    cells: &[OpendataloaderProbeCell],
) -> Vec<String> {
    cells
        .iter()
        .map(|cell| opendataloader_probe_text_part_for_cell(text_chunk, cell))
        .collect()
}

fn opendataloader_probe_text_part_for_cell(
    text_chunk: &OpendataloaderProbeTextChunk,
    cell: &OpendataloaderProbeCell,
) -> String {
    let chars = text_chunk.text.chars().collect::<Vec<_>>();
    if chars.is_empty() {
        return String::new();
    }
    let char_width = (text_chunk.x1 - text_chunk.x0) / chars.len() as f64;
    chars
        .into_iter()
        .enumerate()
        .filter_map(|(index, ch)| {
            let center = text_chunk.x0 + char_width * (index as f64 + 0.5);
            (center >= cell.left && center <= cell.right).then_some(ch)
        })
        .collect()
}

fn opendataloader_probe_neighbor_links(tables: &[OpendataloaderProbeTableShape]) -> Vec<bool> {
    tables
        .windows(2)
        .map(|pair| opendataloader_probe_neighbor_table_link(&pair[0], &pair[1]))
        .collect()
}

fn opendataloader_probe_neighbor_table_link(
    previous: &OpendataloaderProbeTableShape,
    current: &OpendataloaderProbeTableShape,
) -> bool {
    previous.columns.len() == current.columns.len()
        && opendataloader_probe_close_ratio(
            previous.width,
            current.width,
            OPENDATALOADER_NEIGHBOUR_TABLE_EPSILON,
        )
        && previous
            .columns
            .iter()
            .zip(&current.columns)
            .all(|(left, right)| {
                opendataloader_probe_close_ratio(
                    *left,
                    *right,
                    OPENDATALOADER_NEIGHBOUR_TABLE_EPSILON,
                )
            })
}

pub(crate) fn opendataloader_structure_probe_json(request: &Value) -> Result<Value, String> {
    let lines = opendataloader_probe_structure_lines(request)?;
    let blocks = opendataloader_probe_structure_blocks(lines);

    Ok(json!({
        "runtime": RUNTIME,
        "protocol_version": PROTOCOL_VERSION,
        "source": "OpenDataLoader structure probe",
        "blocks": blocks,
        "references": [
            OPENDATALOADER_HEADING_PROCESSOR_REFERENCE,
            OPENDATALOADER_LIST_PROCESSOR_REFERENCE
        ],
        "coverageGaps": [
            {"processor": "LevelProcessor", "reason": "reference_not_vendored"},
            {"processor": "CaptionProcessor", "reason": "reference_not_vendored"}
        ]
    }))
}

struct OpendataloaderStructureLine {
    text: String,
    font_size: f64,
}

fn opendataloader_probe_structure_lines(
    request: &Value,
) -> Result<Vec<OpendataloaderStructureLine>, String> {
    let lines = request
        .get("lines")
        .and_then(Value::as_array)
        .ok_or_else(|| error_json("MISSING_LINES", "request.lines is required").to_string())?;
    lines
        .iter()
        .enumerate()
        .map(opendataloader_probe_structure_line)
        .collect()
}

fn opendataloader_probe_structure_line(
    (index, value): (usize, &Value),
) -> Result<OpendataloaderStructureLine, String> {
    let text = value.get("text").and_then(Value::as_str).ok_or_else(|| {
        error_json(
            "INVALID_STRUCTURE_LINE",
            &format!("request.lines[{index}].text is required"),
        )
        .to_string()
    })?;
    let font_size = value
        .get("fontSize")
        .and_then(Value::as_f64)
        .ok_or_else(|| {
            error_json(
                "INVALID_STRUCTURE_LINE",
                &format!("request.lines[{index}].fontSize must be a finite number"),
            )
            .to_string()
        })?;
    if !font_size.is_finite() {
        return Err(error_json(
            "INVALID_STRUCTURE_LINE",
            &format!("request.lines[{index}].fontSize must be a finite number"),
        )
        .to_string());
    }
    Ok(OpendataloaderStructureLine {
        text: text.to_string(),
        font_size,
    })
}

fn opendataloader_probe_structure_blocks(lines: Vec<OpendataloaderStructureLine>) -> Vec<Value> {
    let mut blocks = Vec::new();
    let mut pending_list = Vec::new();
    for line in lines {
        if let Some(item) = opendataloader_probe_letter_list_item(&line.text) {
            if !opendataloader_probe_next_letter_list_item(&pending_list, &item) {
                opendataloader_probe_flush_list_block(&mut blocks, &mut pending_list);
            }
            pending_list.push(item);
            continue;
        }
        opendataloader_probe_flush_list_block(&mut blocks, &mut pending_list);
        blocks.push(opendataloader_probe_structure_block(line));
    }
    opendataloader_probe_flush_list_block(&mut blocks, &mut pending_list);
    blocks
}

fn opendataloader_probe_next_letter_list_item(
    pending_list: &[OpendataloaderLetterListItem],
    item: &OpendataloaderLetterListItem,
) -> bool {
    let Some(previous) = pending_list.last() else {
        return item.ordinal == 0;
    };
    previous.case == item.case && previous.ordinal + 1 == item.ordinal
}

fn opendataloader_probe_flush_list_block(
    blocks: &mut Vec<Value>,
    pending_list: &mut Vec<OpendataloaderLetterListItem>,
) {
    if pending_list.is_empty() {
        return;
    }
    if pending_list.len() == 1 {
        let item = pending_list.pop().expect("pending list has item");
        blocks.push(json!({"type": "paragraph", "text": item.original_text}));
        return;
    }
    let items: Vec<String> = std::mem::take(pending_list)
        .into_iter()
        .map(|item| item.item_text)
        .collect();
    blocks.push(json!({
        "type": "list",
        "items": items,
        "source": "OpenDataLoader ListProcessor"
    }));
}

fn opendataloader_probe_structure_block(line: OpendataloaderStructureLine) -> Value {
    if opendataloader_probe_caption(&line.text) {
        json!({"type": "caption", "text": line.text, "source": "derived-caption-pattern"})
    } else if opendataloader_probe_heading(&line) {
        json!({
            "type": "heading",
            "text": line.text,
            "level": 1,
            "source": "OpenDataLoader HeadingProcessor"
        })
    } else {
        json!({"type": "paragraph", "text": line.text})
    }
}

fn opendataloader_probe_heading(line: &OpendataloaderStructureLine) -> bool {
    line.font_size >= 14.0 && opendataloader_probe_numbered_heading(&line.text)
}

fn opendataloader_probe_numbered_heading(text: &str) -> bool {
    let Some(marker) = text.split_whitespace().next() else {
        return false;
    };
    let marker = marker.trim_end_matches('.');
    let parts: Vec<&str> = marker.split('.').collect();
    parts.len() >= 2
        && parts
            .iter()
            .all(|part| !part.is_empty() && part.chars().all(|ch| ch.is_ascii_digit()))
}

fn opendataloader_probe_caption(text: &str) -> bool {
    let trimmed = text.trim_start();
    let mut words = trimmed.split_whitespace();
    let Some(label) = words.next() else {
        return false;
    };
    if !matches!(label, "Figure" | "Table") {
        return false;
    }
    words
        .next()
        .is_some_and(opendataloader_probe_caption_number_marker)
}

fn opendataloader_probe_caption_number_marker(marker: &str) -> bool {
    let marker = marker.trim_end_matches('.');
    !marker.is_empty() && marker.chars().all(|ch| ch.is_ascii_digit())
}

#[derive(Clone, Copy, Eq, PartialEq)]
enum OpendataloaderLetterCase {
    Lower,
    Upper,
}

struct OpendataloaderLetterListItem {
    original_text: String,
    item_text: String,
    ordinal: u8,
    case: OpendataloaderLetterCase,
}

fn opendataloader_probe_letter_list_item(text: &str) -> Option<OpendataloaderLetterListItem> {
    let trimmed = text.trim_start();
    let mut chars = trimmed.chars();
    let letter = chars.next()?;
    let marker = chars.next()?;
    let rest = chars.as_str().trim_start();
    if letter.is_ascii_alphabetic() && matches!(marker, ')' | '.') && !rest.is_empty() {
        let case = if letter.is_ascii_lowercase() {
            OpendataloaderLetterCase::Lower
        } else {
            OpendataloaderLetterCase::Upper
        };
        Some(OpendataloaderLetterListItem {
            original_text: trimmed.to_string(),
            item_text: rest.to_string(),
            ordinal: letter.to_ascii_lowercase() as u8 - b'a',
            case,
        })
    } else {
        None
    }
}
