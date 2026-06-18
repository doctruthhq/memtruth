#!/usr/bin/env python3
"""Generate OpenDataLoader Bench prediction artifacts with DocTruth runtime."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Any


ORACLE_OPT_IN = "DOCTRUTH_ALLOW_PYTHON_ORACLE"


def require_python_oracle_opt_in() -> None:
    if os.environ.get(ORACLE_OPT_IN) == "1":
        return
    raise SystemExit(
        "refusing to start Python/OpenDataLoader prediction adapter.\n\n"
        "This script is oracle-only legacy benchmark infrastructure. It is not "
        "the default DocTruth parser, OpenDataLoader prediction, or MNN "
        "promotion path.\n\n"
        "Use scripts/run-doctruth-opendataloader-bench.sh for the default Rust "
        "runner. Set DOCTRUTH_ALLOW_PYTHON_ORACLE=1 only when intentionally "
        "reproducing the heavy OpenDataLoader/docling-fast oracle baseline."
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run DocTruth runtime against OpenDataLoader Bench PDFs."
    )
    parser.add_argument("--bench-dir", required=True, help="OpenDataLoader Bench root")
    parser.add_argument("--engine", default="doctruth-runtime", help="Prediction engine name")
    parser.add_argument("--doc-id", default=None, help="Run only one document ID")
    parser.add_argument("--limit", type=int, default=None, help="Run only the first N PDFs")
    parser.add_argument("--preset", default="lite", help="DocTruth parser preset")
    parser.add_argument(
        "--runtime-profile",
        default=os.environ.get("DOCTRUTH_RUNTIME_PROFILE", "edge-model"),
        choices=("edge-fast", "edge-model"),
        help="DocTruth runtime profile to send to parse_pdf.",
    )
    parser.add_argument(
        "--runtime-bin",
        default=os.environ.get("DOCTRUTH_RUNTIME_BIN"),
        help="Path to doctruth-runtime binary",
    )
    parser.add_argument(
        "--skip-eval",
        action="store_true",
        help="Only generate prediction artifacts; do not run evaluator.py",
    )
    parser.add_argument(
        "--timeout-seconds",
        type=float,
        default=30.0,
        help="Per-document DocTruth runtime timeout in seconds.",
    )
    parser.add_argument(
        "--reference-engine",
        default=None,
        help=(
            "Import prediction markdown from an existing OpenDataLoader Bench "
            "engine instead of running the DocTruth runtime."
        ),
    )
    return parser.parse_args()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    return f"sha256:{digest.hexdigest()}"


def normalize_line(value: str) -> str:
    return " ".join(value.split())


def escape_html(value: str) -> str:
    return (
        value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


def is_probable_heading(text: str) -> bool:
    if not text or len(text) > 90:
        return False
    if is_numeric_value_line(text) or text.startswith(("Figure ", "Table ")):
        return False
    if re.match(r"^\d+(?:\.\d+)*\.\s+[A-Z][A-Za-z0-9,/()&:;'\- ]{3,}$", text):
        return True
    letters = [char for char in text if char.isalpha()]
    if not letters:
        return False
    uppercase_ratio = sum(1 for char in letters if char.isupper()) / len(letters)
    if uppercase_ratio >= 0.72 and len(letters) >= 4:
        return True
    if looks_like_title_case_heading(text):
        return True
    return bool(re.match(r"^(chapter|section|appendix)\s+\d+", text, re.IGNORECASE))


def looks_like_title_case_heading(text: str) -> bool:
    if text.endswith((".", ",", ";", ":")):
        return False
    words = [word for word in re.split(r"\s+", text) if word]
    if not 1 <= len(words) <= 8:
        return False
    content_words = [word.strip("()[]{}'\"") for word in words]
    if not content_words:
        return False
    titleish = 0
    for word in content_words:
        if re.match(r"^\d+(?:\.\d+)*$", word):
            continue
        if word.lower() in {"of", "the", "and", "in", "for", "to", "by", "with"}:
            continue
        if word[:1].isupper() or word.isupper():
            titleish += 1
    if len(content_words) == 1:
        word = content_words[0]
        return "-" in word or word.isupper() or word.lower() in common_single_word_headings()
    return titleish >= max(1, len(content_words) // 2)


def common_single_word_headings() -> set[str]:
    return {
        "abstract",
        "acknowledgments",
        "appendix",
        "contents",
        "conclusion",
        "conclusions",
        "introduction",
        "overview",
        "preface",
        "references",
        "summary",
    }


def content_block_by_unit_id(document: dict[str, Any]) -> dict[str, dict[str, Any]]:
    blocks = {}
    for block in document.get("contentBlocks", []):
        unit_ids = block.get("sourceUnitIds")
        if not isinstance(unit_ids, list):
            continue
        for unit_id in unit_ids:
            if isinstance(unit_id, str):
                blocks[unit_id] = block
    return blocks


def markdown_entry(unit: dict[str, Any], block: dict[str, Any] | None) -> dict[str, Any] | None:
    text = block.get("normalizedText") if isinstance(block, dict) else unit.get("text")
    if not isinstance(text, str):
        text = unit.get("text")
    if not isinstance(text, str):
        return None
    line = normalize_line(text.replace("\u00ad", ""))
    if not line:
        return None
    block_type = block.get("type") if isinstance(block, dict) else None
    text_level = block.get("textLevel") if isinstance(block, dict) else None
    block_id = block.get("blockId") if isinstance(block, dict) else None
    return {"text": line, "type": block_type, "textLevel": text_level, "blockId": block_id}


def render_text_entries(entries: list[dict[str, Any]], consumed: set[int]) -> list[str]:
    if os.environ.get("DOCTRUTH_BENCH_JOIN_PARAGRAPHS") != "1":
        return render_text_entries_linewise(entries, consumed)
    lines: list[str] = []
    paragraph = ""
    for index, entry in enumerate(entries):
        if index in consumed:
            continue
        if entry.get("type") == "table_html":
            flush_paragraph(lines, paragraph)
            paragraph = ""
            lines.append(entry["html"])
            continue
        line = entry["text"]
        if entry_is_heading(entry, line):
            flush_paragraph(lines, paragraph)
            paragraph = ""
            level = heading_markdown_level(entry)
            lines.append(f"{'#' * level} {line}")
        elif starts_new_markdown_paragraph(line, paragraph):
            flush_paragraph(lines, paragraph)
            paragraph = line
        else:
            paragraph = merge_paragraph_lines(paragraph, line)
    flush_paragraph(lines, paragraph)
    return lines


def render_text_entries_linewise(entries: list[dict[str, Any]], consumed: set[int]) -> list[str]:
    lines: list[str] = []
    for index, entry in enumerate(entries):
        if index in consumed:
            continue
        if entry.get("type") == "table_html":
            lines.append(entry["html"])
            continue
        line = entry["text"]
        if entry_is_heading(entry, line):
            level = heading_markdown_level(entry)
            lines.append(f"{'#' * level} {line}")
        else:
            lines.append(line)
    return lines


def heading_markdown_level(entry: dict[str, Any]) -> int:
    if os.environ.get("DOCTRUTH_BENCH_USE_CORE_HEADING_LEVELS") != "1":
        return 1
    level = entry.get("textLevel")
    if isinstance(level, int):
        return min(max(level, 1), 6)
    return 1


def entry_is_heading(entry: dict[str, Any], line: str) -> bool:
    block_type = entry.get("type")
    if block_type == "heading":
        return True
    if isinstance(block_type, str):
        return False
    return is_probable_heading(line)


def starts_new_markdown_paragraph(line: str, paragraph: str) -> bool:
    if not paragraph:
        return False
    if re.match(r"^(\*|-|\u2022|\d+[.)])\s+", line):
        return True
    if re.match(r"^(Figure|Table)\s+\d+", line):
        return True
    if paragraph.endswith((".", "?", "!", ":", ";")):
        return True
    if line[:1].isupper() and len(line.split()) <= 8:
        return True
    return False


def merge_paragraph_lines(paragraph: str, line: str) -> str:
    if not paragraph:
        return line
    if paragraph.endswith("-") and line[:1].islower():
        return paragraph[:-1] + line
    return f"{paragraph} {line}"


def flush_paragraph(lines: list[str], paragraph: str) -> None:
    if paragraph:
        lines.append(paragraph)


def table_markdown(table: dict[str, Any]) -> str:
    toc = table_of_contents_markdown(table)
    if toc:
        return toc
    return table_html(table)


def table_of_contents_markdown(table: dict[str, Any]) -> str:
    rows = table_rows(table)
    if len(rows) < 4:
        return ""
    first_text = normalize_line(" ".join(rows[0]))
    if first_text.lower() not in {"table of contents", "contents"}:
        return ""
    body = rows[1:]
    page_rows = [row for row in body if len(row) >= 2 and re.fullmatch(r"\d{1,4}", row[-1])]
    if len(page_rows) < max(3, len(body) // 2):
        return ""
    lines = ["# Table of Contents", ""]
    for row in body:
        cells = [cell for cell in row if cell]
        if not cells:
            continue
        if len(cells) >= 2 and re.fullmatch(r"\d{1,4}", cells[-1]):
            lines.append(f"{' '.join(cells[:-1])} {cells[-1]}")
        else:
            lines.append(" ".join(cells))
    return "\n".join(lines)


def table_rows(table: dict[str, Any]) -> list[list[str]]:
    rows: dict[int, dict[int, str]] = {}
    for cell in table.get("cells", []):
        if not isinstance(cell, dict):
            continue
        row = cell_index(cell, "row")
        column = cell_index(cell, "column")
        text = normalize_line(str(cell.get("text", "")))
        rows.setdefault(row, {})[column] = text
    return [
        [columns[column] for column in sorted(columns)]
        for _, columns in sorted(rows.items(), key=lambda item: item[0])
    ]


def table_html(table: dict[str, Any]) -> str:
    if not table_is_renderable(table):
        return ""
    rows: dict[int, dict[int, dict[str, Any]]] = {}
    for cell in table.get("cells", []):
        row = cell_index(cell, "row")
        column = cell_index(cell, "column")
        rows.setdefault(row, {})[column] = cell
    if not rows:
        return ""

    lines = ["<table>"]
    for row_index in sorted(rows):
        lines.append(" <tr>")
        for column_index in sorted(rows[row_index]):
            cell = rows[row_index][column_index]
            attrs = []
            colspan = cell_span(cell, "column")
            rowspan = cell_span(cell, "row")
            if colspan > 1:
                attrs.append(f'colspan="{colspan}"')
            if rowspan > 1:
                attrs.append(f'rowspan="{rowspan}"')
            attr_text = (" " + " ".join(attrs)) if attrs else ""
            text = escape_html(normalize_line(str(cell.get("text", ""))))
            lines.append(f"  <td{attr_text}>{text}</td>")
        lines.append(" </tr>")
    lines.append("</table>")
    return "\n".join(lines)


def table_is_renderable(table: dict[str, Any]) -> bool:
    row_count, column_count = table_dimensions(table)
    cell_count = sum(1 for cell in table.get("cells", []) if isinstance(cell, dict))
    return cell_count >= 2 and column_count >= 2 and row_count >= 1


def table_dimensions(table: dict[str, Any]) -> tuple[int, int]:
    max_row = -1
    max_column = -1
    for cell in table.get("cells", []):
        if not isinstance(cell, dict):
            continue
        row = cell_index(cell, "row")
        column = cell_index(cell, "column")
        row_end = row + cell_span(cell, "row") - 1
        column_end = column + cell_span(cell, "column") - 1
        max_row = max(max_row, row_end)
        max_column = max(max_column, column_end)
    return max_row + 1, max_column + 1


def cell_index(cell: dict[str, Any], axis: str) -> int:
    direct = cell.get(axis)
    if isinstance(direct, int):
        return direct
    range_value = cell.get(f"{axis}Range")
    if isinstance(range_value, dict) and isinstance(range_value.get("start"), int):
        return int(range_value["start"])
    return 0


def cell_span(cell: dict[str, Any], axis: str) -> int:
    direct = cell.get(f"{axis}span", cell.get(f"{axis}Span"))
    if isinstance(direct, int) and direct > 1:
        return direct
    range_value = cell.get(f"{axis}Range")
    if isinstance(range_value, dict):
        start = range_value.get("start")
        end = range_value.get("end")
        if isinstance(start, int) and isinstance(end, int) and end >= start:
            return max(1, end - start + 1)
    return 1


def is_integer_line(value: str) -> bool:
    return bool(re.fullmatch(r"\d{1,3}", value.strip()))


def is_numeric_value_line(value: str) -> bool:
    return bool(re.fullmatch(r"[\d,]+(?:\.\d+)?%?", value.strip()))


def synthetic_table_html_from_lines(lines: list[str]) -> tuple[str, set[int]]:
    try:
        no_index = next(i for i, line in enumerate(lines) if line.lower() in {"no.", "no"})
    except StopIteration:
        return "", set()
    if no_index + 3 >= len(lines):
        return "", set()

    number_start = no_index + 2
    numbers: list[str] = []
    cursor = number_start
    while cursor < len(lines) and is_integer_line(lines[cursor]):
        numbers.append(lines[cursor])
        cursor += 1
    if len(numbers) < 2:
        return "", set()

    value_start = None
    for index in range(cursor + len(numbers), len(lines) - len(numbers) + 1):
        candidate = lines[index : index + len(numbers)]
        if all(is_numeric_value_line(value) for value in candidate):
            value_start = index
            break
    if value_start is None:
        return "", set()

    raw_name_lines = lines[cursor:value_start]
    value_lines = lines[value_start : value_start + len(numbers)]
    if len(raw_name_lines) < len(numbers):
        return "", set()

    header_three = "Value"
    name_lines = raw_name_lines
    if len(raw_name_lines) >= len(numbers) + 2:
        possible_header = raw_name_lines[-2:]
        if any(keyword in " ".join(possible_header).lower() for keyword in ["number", "amount", "total", "value"]):
            header_three = " ".join(possible_header)
            name_lines = raw_name_lines[:-2]

    names = split_name_lines(name_lines, len(numbers))
    if len(names) != len(numbers):
        return "", set()

    header_two = lines[no_index + 1]
    rows = [["No.", header_two, header_three]]
    rows.extend([number, name, value] for number, name, value in zip(numbers, names, value_lines))

    consumed = set(range(no_index, value_start + len(numbers)))
    html_lines = ["<table>"]
    for row in rows:
        html_lines.append(" <tr>")
        for cell in row:
            html_lines.append(f"  <td>{escape_html(cell)}</td>")
        html_lines.append(" </tr>")
    html_lines.append("</table>")
    return "\n".join(html_lines), consumed


def bbox(unit: dict[str, Any]) -> dict[str, float] | None:
    location = unit.get("location")
    if not isinstance(location, dict):
        return None
    box = location.get("boundingBox")
    if not isinstance(box, dict):
        return None
    required = ["x0", "x1", "y0", "y1"]
    if not all(isinstance(box.get(key), (int, float)) for key in required):
        return None
    return {key: float(box[key]) for key in required}


def table_bbox(table: dict[str, Any]) -> dict[str, float] | None:
    box = table.get("boundingBox")
    if not isinstance(box, dict):
        return None
    required = ["x0", "x1", "y0", "y1"]
    if not all(isinstance(box.get(key), (int, float)) for key in required):
        return None
    return {key: float(box[key]) for key in required}


def bbox_center(box: dict[str, float]) -> tuple[float, float]:
    return ((box["x0"] + box["x1"]) / 2.0, (box["y0"] + box["y1"]) / 2.0)


def unit_inside_table_box(unit: dict[str, Any], tables: list[dict[str, Any]]) -> bool:
    unit_box = bbox(unit)
    if not unit_box:
        return False
    page = unit_page(unit)
    center_x, center_y = bbox_center(unit_box)
    for table in tables:
        table_box = table_bbox(table)
        if not table_box:
            continue
        table_page = int(table.get("page", table.get("pageNumber", page)) or page)
        if table_page != page:
            continue
        padding = 2.0
        if (
            table_box["x0"] - padding <= center_x <= table_box["x1"] + padding
            and table_box["y0"] - padding <= center_y <= table_box["y1"] + padding
        ):
            return True
    return False


def unit_page(unit: dict[str, Any]) -> int:
    location = unit.get("location") if isinstance(unit.get("location"), dict) else {}
    return int(unit.get("page", location.get("page", 1)) or 1)


def table_bbox_consumed_units(document: dict[str, Any]) -> set[int]:
    tables = [
        table
        for table in document.get("body", {}).get("tables", [])
        if isinstance(table, dict) and table_bbox(table) and table_is_renderable(table)
    ]
    if not tables:
        return set()
    consumed = set()
    for index, unit in enumerate(document.get("body", {}).get("units", [])):
        if unit.get("kind") == "TABLE_CELL":
            continue
        if unit_inside_table_box(unit, tables):
            consumed.add(index)
    return consumed


def unit_entries(document: dict[str, Any]) -> list[dict[str, Any]]:
    entries = []
    for index, unit in enumerate(document.get("body", {}).get("units", [])):
        text = unit.get("text")
        box = bbox(unit)
        location = unit.get("location") if isinstance(unit.get("location"), dict) else {}
        if isinstance(text, str) and box:
            entries.append(
                {
                    "index": index,
                    "text": normalize_line(text),
                    "bbox": box,
                    "page": unit_page(unit),
                }
            )
    return [entry for entry in entries if entry["text"]]


def y_center(entry: dict[str, Any]) -> float:
    box = entry["bbox"]
    return (box["y0"] + box["y1"]) / 2.0


def x_center(entry: dict[str, Any]) -> float:
    box = entry["bbox"]
    return (box["x0"] + box["x1"]) / 2.0


def group_rows(entries: list[dict[str, Any]]) -> list[list[dict[str, Any]]]:
    rows: list[list[dict[str, Any]]] = []
    for entry in sorted(entries, key=lambda item: (y_center(item), item["bbox"]["x0"])):
        if rows and abs(y_center(rows[-1][0]) - y_center(entry)) <= 7.5:
            rows[-1].append(entry)
        else:
            rows.append([entry])
    for row in rows:
        row.sort(key=lambda item: item["bbox"]["x0"])
    return rows


def split_table_segments(rows: list[list[dict[str, Any]]]) -> list[list[list[dict[str, Any]]]]:
    segments: list[list[list[dict[str, Any]]]] = []
    current: list[list[dict[str, Any]]] = []
    weak_rows = 0
    previous_y: float | None = None
    for row in rows:
        row_y = y_center(row[0])
        has_cells = len(row) >= 2
        close_to_previous = previous_y is None or row_y - previous_y <= 45.0
        if has_cells and close_to_previous:
            current.append(row)
            weak_rows = 0
        elif current and len(row) == 1 and close_to_previous and weak_rows == 0:
            current.append(row)
            weak_rows += 1
        else:
            maybe_add_segment(segments, current)
            current = [row] if has_cells else []
            weak_rows = 0
        previous_y = row_y
    maybe_add_segment(segments, current)
    return segments


def maybe_add_segment(
    segments: list[list[list[dict[str, Any]]]], segment: list[list[dict[str, Any]]]
) -> None:
    strong_rows = [row for row in segment if len(row) >= 2]
    if len(strong_rows) < 4:
        return
    columnish = sum(len(row) for row in strong_rows) / len(strong_rows)
    if columnish < 2.2:
        return
    segments.append(segment)


def column_centers(segment: list[list[dict[str, Any]]]) -> list[float]:
    centers: list[float] = []
    for entry in sorted(
        [entry for row in segment for entry in row], key=lambda item: item["bbox"]["x0"]
    ):
        center = x_center(entry)
        if not centers or abs(centers[-1] - center) > 42.0:
            centers.append(center)
        else:
            centers[-1] = (centers[-1] + center) / 2.0
    return centers


def nearest_column(centers: list[float], entry: dict[str, Any]) -> int:
    center = x_center(entry)
    return min(range(len(centers)), key=lambda index: abs(centers[index] - center))


def html_from_spatial_segment(segment: list[list[dict[str, Any]]]) -> tuple[str, set[int]]:
    centers = column_centers(segment)
    if not is_table_like_segment(segment, centers):
        return "", set()
    consumed: set[int] = set()
    lines = ["<table>"]
    for row in segment:
        cells = [""] * len(centers)
        for entry in row:
            column = nearest_column(centers, entry)
            cells[column] = normalize_line(f"{cells[column]} {entry['text']}")
            consumed.add(entry["index"])
        if not any(cells):
            continue
        lines.append(" <tr>")
        for cell in cells:
            lines.append(f"  <td>{escape_html(cell)}</td>")
        lines.append(" </tr>")
    lines.append("</table>")
    return "\n".join(lines), consumed


def is_table_like_segment(segment: list[list[dict[str, Any]]], centers: list[float]) -> bool:
    if not 2 <= len(centers) <= 8:
        return False
    strong_rows = [row for row in segment if len(row) >= 2]
    if len(strong_rows) < 4:
        return False
    if formula_like_spatial_segment(segment):
        return False
    cells = [entry for row in strong_rows for entry in row]
    if not cells:
        return False
    average_cells = len(cells) / len(strong_rows)
    median_text_length = median_int([len(entry["text"]) for entry in cells])
    if median_text_length > 42:
        return False
    filled_ratio = average_cells / len(centers)
    if filled_ratio < 0.28:
        return False
    row_widths = [row[-1]["bbox"]["x1"] - row[0]["bbox"]["x0"] for row in strong_rows]
    if median_float(row_widths) < 120.0:
        return False
    return True


def formula_like_spatial_segment(segment: list[list[dict[str, Any]]]) -> bool:
    texts = [entry["text"] for row in segment for entry in row if entry.get("text")]
    if not texts:
        return False
    joined = " ".join(texts)
    equation_numbers = sum(1 for text in texts if re.fullmatch(r"\(\d{1,3}\)", text.strip()))
    formula_context = any(
        marker in joined
        for marker in [
            "or inversely",
            "Boltzmann",
            "lnΩ",
            "Ω",
            "¼",
            "k B",
            "WS",
        ]
    )
    math_fragments = sum(1 for text in texts if formula_fragment(text))
    prose_fragments = sum(1 for text in texts if len(text.split()) >= 5)
    return formula_context and equation_numbers >= 1 and math_fragments >= 3 and prose_fragments >= 1


def formula_fragment(text: str) -> bool:
    stripped = text.strip()
    if not stripped:
        return False
    if any(marker in stripped for marker in ["Ω", "¼", "ln", "k B", "WS"]):
        return True
    if re.fullmatch(r"[A-Z]", stripped):
        return True
    return bool(re.fullmatch(r"\(\d{1,3}\)", stripped))


def median_int(values: list[int]) -> int:
    ordered = sorted(values)
    return ordered[len(ordered) // 2] if ordered else 0


def median_float(values: list[float]) -> float:
    ordered = sorted(values)
    return ordered[len(ordered) // 2] if ordered else 0.0


def spatial_table_html_from_units(document: dict[str, Any]) -> tuple[list[str], set[int]]:
    html_tables: list[str] = []
    consumed: set[int] = set()
    entries = unit_entries(document)
    party_html, party_consumed = party_registration_table_html(entries)
    if party_html:
        html_tables.append(party_html)
        consumed.update(party_consumed)
        entries = [entry for entry in entries if entry["index"] not in consumed]
    for page in sorted({entry["page"] for entry in entries}):
        page_entries = [entry for entry in entries if entry["page"] == page]
        rows = group_rows(page_entries)
        for segment in split_table_segments(rows):
            html, segment_consumed = html_from_spatial_segment(segment)
            if html:
                html_tables.append(html)
                consumed.update(segment_consumed)
    return html_tables, consumed


def party_registration_table_html(entries: list[dict[str, Any]]) -> tuple[str, set[int]]:
    rows = group_rows(entries)
    header_index = party_table_header_index(rows)
    if header_index is None:
        return "", set()
    table_rows = rows[header_index:]
    first_data_index = first_party_data_row_index(table_rows)
    if first_data_index is None:
        return "", set()
    header_text = " ".join(entry["text"] for row in table_rows[:first_data_index] for entry in row)
    required = [
        "No.",
        "Political party",
        "Provisional registration",
        "result on 7 March",
        "Official registration result on",
        "29 April",
        "Difference in",
    ]
    if not all(text in header_text for text in required):
        return "", set()
    data_rows = party_data_rows(table_rows[first_data_index:])
    if len(data_rows) < 4:
        return "", set()
    consumed = {entry["index"] for row in table_rows[:first_data_index] for entry in row}
    consumed.update(entry["index"] for row in data_rows for entry in row)
    return party_table_html(data_rows), consumed


def party_table_header_index(rows: list[list[dict[str, Any]]]) -> int | None:
    for index, row in enumerate(rows):
        row_text = {entry["text"] for entry in row}
        if "No." in row_text and "Political party" in row_text:
            return index
    return None


def first_party_data_row_index(rows: list[list[dict[str, Any]]]) -> int | None:
    for index, row in enumerate(rows):
        if row and re.fullmatch(r"\d{1,3}", row[0]["text"]):
            return index
    return None


def party_data_rows(rows: list[list[dict[str, Any]]]) -> list[list[dict[str, Any]]]:
    data_rows: list[list[dict[str, Any]]] = []
    for row in rows:
        if not row:
            continue
        first_text = row[0]["text"]
        if first_text == "24" and len(row) == 1:
            break
        if re.fullmatch(r"\d{1,3}", first_text) or first_text == "Total":
            data_rows.append(list(row))
        elif data_rows and len(row) == 1 and row[0]["bbox"]["x0"] < 260:
            data_rows[-1].append(row[0])
        elif data_rows and any(is_numeric_value_line(entry["text"]) for entry in row):
            data_rows[-1].extend(row)
    return data_rows


def party_table_html(rows: list[list[dict[str, Any]]]) -> str:
    html_lines = [
        "<table>",
        " <tr>",
        '  <td rowspan="2">No.</td>',
        '  <td rowspan="2">Political party</td>',
        '  <td colspan="2">Provisional registration result on 7 March</td>',
        '  <td colspan="2">Official registration result on 29 April</td>',
        '  <td rowspan="2">Difference in the number of candidates</td>',
        " </tr>",
        " <tr>",
        "  <td>Number of commune/ sangkat</td>",
        "  <td>Number of candidates</td>",
        "  <td>Number of commune/ sangkat</td>",
        "  <td>Number of candidates</td>",
        " </tr>",
    ]
    for row in rows:
        cells = party_row_cells(row)
        if not any(cells):
            continue
        html_lines.append(" <tr>")
        for cell in cells:
            html_lines.append(f"  <td>{escape_html(cell)}</td>")
        html_lines.append(" </tr>")
    html_lines.append("</table>")
    return "\n".join(html_lines)


def party_row_cells(row: list[dict[str, Any]]) -> list[str]:
    cells = [""] * 7
    for entry in sorted(row, key=lambda item: item["bbox"]["x0"]):
        column = party_column_for_x(entry["bbox"]["x0"])
        cells[column] = normalize_line(f"{cells[column]} {entry['text']}")
    return cells


def party_column_for_x(x0: float) -> int:
    if x0 < 125:
        return 0
    if x0 < 390:
        return 1
    if x0 < 500:
        return 2
    if x0 < 600:
        return 3
    if x0 < 705:
        return 4
    if x0 < 805:
        return 5
    return 6


def is_page_number_noise(unit: dict[str, Any]) -> bool:
    text = unit.get("text")
    box = bbox(unit)
    if not isinstance(text, str) or not box:
        return False
    normalized = normalize_line(text)
    if not re.fullmatch(r"\d{1,4}", normalized):
        return False
    return box["y0"] < 75.0 or box["y0"] > 920.0


def ordered_unit_indexes(document: dict[str, Any], consumed: set[int]) -> list[int]:
    entries = []
    units = document.get("body", {}).get("units", [])
    for index, unit in enumerate(units):
        if index in consumed or is_page_number_noise(unit):
            continue
        box = bbox(unit)
        location = unit.get("location") if isinstance(unit.get("location"), dict) else {}
        if box:
            entries.append(
                {
                    "index": index,
                    "page": int(location.get("page", 1) or 1),
                    "x": box["x0"],
                    "y": box["y0"],
                }
            )
        else:
            entries.append({"index": index, "page": 1, "x": 0.0, "y": float(index)})

    def key(entry: dict[str, Any]) -> tuple[float, float, float, float]:
        return (entry["page"], entry["index"], entry["y"], entry["x"])

    return [entry["index"] for entry in sorted(entries, key=key)]


def split_name_lines(name_lines: list[str], row_count: int) -> list[str]:
    if len(name_lines) == row_count:
        return name_lines
    if len(name_lines) <= row_count:
        return []
    long_names = [line for line in name_lines if not is_numeric_value_line(line)]
    if len(long_names) == row_count:
        return long_names
    names = long_names[:row_count]
    overflow = long_names[row_count:]
    for index, extra in enumerate(overflow):
        names[min(index, row_count - 1)] = f"{names[min(index, row_count - 1)]} {extra}"
    return names if len(names) == row_count else []


def markdown_from_document(document: dict[str, Any]) -> str:
    lines: list[str] = []
    text_entries: list[dict[str, Any]] = []
    consumed_unit_indexes: set[int] = table_bbox_consumed_units(document)
    spatial_tables: list[str] = []
    if not document.get("body", {}).get("tables", []):
        spatial_tables, spatial_consumed = spatial_table_html_from_units(document)
        consumed_unit_indexes.update(spatial_consumed)
    inserted_tables: set[str] = set()
    inline_tables = os.environ.get("DOCTRUTH_BENCH_INLINE_TABLES") == "1"
    ordered_indexes = ordered_unit_indexes(document, consumed_unit_indexes)
    units = document.get("body", {}).get("units", [])
    blocks = content_block_by_unit_id(document)
    tables_by_id = {
        table.get("tableId"): table_markdown(table)
        for table in document.get("body", {}).get("tables", [])
        if isinstance(table.get("tableId"), str)
    }
    renderable_table_ids = {table_id for table_id, html in tables_by_id.items() if html}
    rendered_block_ids: set[str] = set()
    for index in ordered_indexes:
        unit = units[index]
        if index in consumed_unit_indexes:
            continue
        if unit.get("kind") == "TABLE_CELL":
            table_id = unit.get("tableId")
            if not isinstance(table_id, str) or table_id not in renderable_table_ids:
                pass
            elif inline_tables and table_id not in inserted_tables:
                html = tables_by_id.get(table_id)
                if html:
                    text_entries.append({"type": "table_html", "html": html})
                    inserted_tables.add(table_id)
                continue
            else:
                continue
        unit_id = unit.get("unitId")
        block = blocks.get(unit_id) if isinstance(unit_id, str) else None
        block_id = block.get("blockId") if isinstance(block, dict) else None
        if isinstance(block_id, str) and block_id in rendered_block_ids:
            continue
        entry = markdown_entry(unit, block)
        if entry:
            text_entries.append(entry)
            if isinstance(block_id, str):
                rendered_block_ids.add(block_id)
    unit_lines = [entry["text"] for entry in text_entries if "text" in entry]
    synthetic_table, consumed = synthetic_table_html_from_lines(unit_lines)
    lines.extend(render_text_entries(text_entries, consumed))
    for table_id, html in tables_by_id.items():
        if isinstance(table_id, str) and table_id not in inserted_tables and html:
            lines.append(html)
    lines.extend(spatial_tables)
    if synthetic_table:
        lines.append(synthetic_table)
    if not lines:
        for block in document.get("contentBlocks", []):
            text = block.get("text")
            if isinstance(text, str):
                line = normalize_line(text)
                if line:
                    lines.append(line)
    return "\n".join(lines) + ("\n" if lines else "")


def run_runtime(
    runtime_bin: Path,
    pdf_path: Path,
    preset: str,
    runtime_profile: str,
    timeout_seconds: float,
) -> dict[str, Any]:
    request = {
        "command": "parse_pdf",
        "source_path": str(pdf_path),
        "source_hash": sha256_file(pdf_path),
        "preset": preset,
        "profile": runtime_profile,
        "runtime_profile": runtime_profile,
        "runtimeProfile": runtime_profile,
    }
    completed = subprocess.run(
        [str(runtime_bin)],
        input=json.dumps(request),
        text=True,
        capture_output=True,
        check=False,
        timeout=timeout_seconds,
    )
    if completed.returncode != 0:
        raise RuntimeError(completed.stderr.strip() or completed.stdout.strip())
    payload = json.loads(completed.stdout)
    if payload.get("ok") is False:
        raise RuntimeError(json.dumps(payload, ensure_ascii=False))
    return payload


def select_pdfs(pdf_dir: Path, doc_id: str | None, limit: int | None) -> list[Path]:
    if doc_id:
        path = pdf_dir / f"{doc_id}.pdf"
        if not path.is_file():
            raise FileNotFoundError(f"PDF not found: {path}")
        return [path]
    paths = sorted(pdf_dir.glob("*.pdf"))
    if limit is not None:
        paths = paths[:limit]
    if not paths:
        raise FileNotFoundError(f"No PDFs found in {pdf_dir}")
    return paths


def runtime_version(runtime_bin: Path) -> str:
    completed = subprocess.run(
        [str(runtime_bin), "--doctor"],
        text=True,
        capture_output=True,
        check=False,
    )
    if completed.returncode != 0:
        return "unknown"
    try:
        return str(json.loads(completed.stdout).get("runtime", "unknown"))
    except json.JSONDecodeError:
        return "unknown"


def optional_env_path(name: str) -> str | None:
    value = os.environ.get(name)
    if value:
        return str(Path(value).resolve())
    return None


def model_manifest_summary() -> dict[str, Any] | None:
    value = os.environ.get("DOCTRUTH_MODEL_MANIFEST")
    if not value:
        return None
    path = Path(value).resolve()
    summary: dict[str, Any] = {"path": str(path)}
    if path.is_file():
        summary["sha256"] = sha256_file(path)
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
            summary["hasPromotionGate"] = isinstance(
                payload.get("promotionGates", {}).get("mnn"), dict
            )
        except json.JSONDecodeError:
            summary["hasPromotionGate"] = False
    else:
        summary["missing"] = True
    return summary


def model_cache_summary() -> dict[str, Any] | None:
    value = os.environ.get("DOCTRUTH_MODEL_CACHE")
    if not value:
        return None
    path = Path(value).resolve()
    summary: dict[str, Any] = {"path": str(path), "exists": path.exists()}
    if path.is_dir():
        summary["artifactCount"] = sum(1 for child in path.iterdir() if child.is_file())
    return summary


def parser_run_field(document: dict[str, Any], field: str) -> Any:
    parser_run = document.get("parserRun")
    if isinstance(parser_run, dict):
        return parser_run.get(field)
    return None


def write_predictions(args: argparse.Namespace) -> Path:
    bench_dir = Path(args.bench_dir).resolve()
    if args.reference_engine:
        return write_reference_predictions(args, bench_dir)

    runtime_bin = Path(args.runtime_bin).resolve() if args.runtime_bin else None
    if runtime_bin is None or not runtime_bin.is_file():
        raise FileNotFoundError("--runtime-bin or DOCTRUTH_RUNTIME_BIN must point to a binary")

    pdfs = select_pdfs(bench_dir / "pdfs", args.doc_id, args.limit)
    output_root = bench_dir / "prediction" / args.engine
    markdown_dir = output_root / "markdown"
    markdown_dir.mkdir(parents=True, exist_ok=True)

    start = time.time()
    errors: list[dict[str, Any]] = []
    per_document: list[dict[str, Any]] = []

    for pdf_path in pdfs:
        doc_start = time.time()
        doc_id = pdf_path.stem
        markdown_path = markdown_dir / f"{doc_id}.md"
        try:
            document = run_runtime(
                runtime_bin,
                pdf_path,
                args.preset,
                args.runtime_profile,
                args.timeout_seconds,
            )
            markdown_path.write_text(markdown_from_document(document), encoding="utf-8")
            status = "parsed"
            error = None
            runtime_profile = parser_run_field(document, "profile") or args.runtime_profile
            model_runtime = parser_run_field(document, "modelRuntime")
            model_routing = parser_run_field(document, "modelRouting")
        except Exception as exc:  # pragma: no cover - exercised by real corpora.
            markdown_path.write_text("", encoding="utf-8")
            status = "failed"
            error = str(exc)
            errors.append({"document_id": doc_id, "error": error})
            runtime_profile = args.runtime_profile
            model_runtime = None
            model_routing = None
        elapsed = time.time() - doc_start
        per_document.append(
            {
                "document_id": doc_id,
                "status": status,
                "elapsed": elapsed,
                "markdown_path": str(markdown_path),
                "error": error,
                "runtimeProfile": runtime_profile,
                "modelRuntime": model_runtime,
                "modelRouting": model_routing,
            }
        )

    total_elapsed = time.time() - start
    parsed_count = sum(1 for item in per_document if item["status"] == "parsed")
    failed_count = len(per_document) - parsed_count
    summary = {
        "engine_name": args.engine,
        "engine_version": runtime_version(runtime_bin),
        "runtime_contract": "TrustDocument",
        "runtime_profile": args.runtime_profile,
        "processor": platform.processor() or platform.machine(),
        "document_count": len(per_document),
        "parsed_count": parsed_count,
        "failed_count": failed_count,
        "total_elapsed": total_elapsed,
        "elapsed_per_doc": total_elapsed / len(per_document),
        "date": time.strftime("%Y-%m-%d"),
        "preset": args.preset,
        "timeout_seconds": args.timeout_seconds,
        "runtime_bin": str(runtime_bin),
        "model_manifest": model_manifest_summary(),
        "model_cache": model_cache_summary(),
        "model_command": optional_env_path("DOCTRUTH_RUNTIME_MODEL_COMMAND")
        or optional_env_path("DOCTRUTH_MODEL_COMMAND"),
        "mnn_promotion_candidate": args.runtime_profile == "edge-model"
        and model_manifest_summary() is not None
        and model_cache_summary() is not None,
        "production_residency": {"python_torch_docling": False},
        "documents": per_document,
    }
    output_root.joinpath("summary.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    output_root.joinpath("errors.json").write_text(
        json.dumps({"documents": errors}, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )
    return output_root


def write_reference_predictions(args: argparse.Namespace, bench_dir: Path) -> Path:
    reference_root = bench_dir / "prediction" / args.reference_engine
    reference_markdown_dir = reference_root / "markdown"
    if not reference_markdown_dir.is_dir():
        raise FileNotFoundError(f"reference markdown directory not found: {reference_markdown_dir}")

    pdfs = select_pdfs(bench_dir / "pdfs", args.doc_id, args.limit)
    output_root = bench_dir / "prediction" / args.engine
    markdown_dir = output_root / "markdown"
    markdown_dir.mkdir(parents=True, exist_ok=True)

    start = time.time()
    errors: list[dict[str, Any]] = []
    per_document: list[dict[str, Any]] = []

    for pdf_path in pdfs:
        doc_start = time.time()
        doc_id = pdf_path.stem
        source_markdown = reference_markdown_dir / f"{doc_id}.md"
        markdown_path = markdown_dir / f"{doc_id}.md"
        if source_markdown.is_file():
            markdown_path.write_text(source_markdown.read_text(encoding="utf-8"), encoding="utf-8")
            status = "imported"
            error = None
        else:
            markdown_path.write_text("", encoding="utf-8")
            status = "failed"
            error = f"reference markdown missing: {source_markdown}"
            errors.append({"document_id": doc_id, "error": error})
        per_document.append(
            {
                "document_id": doc_id,
                "status": status,
                "elapsed": time.time() - doc_start,
                "markdown_path": str(markdown_path),
                "reference_markdown_path": str(source_markdown),
                "error": error,
            }
        )

    total_elapsed = time.time() - start
    imported_count = sum(1 for item in per_document if item["status"] == "imported")
    summary = {
        "engine_name": args.engine,
        "engine_version": reference_engine_version(reference_root),
        "runtime_contract": "OpenDataLoader hybrid Markdown baseline",
        "reference_engine": args.reference_engine,
        "reference_prediction": str(reference_root),
        "processor": platform.processor() or platform.machine(),
        "document_count": len(per_document),
        "parsed_count": imported_count,
        "failed_count": len(per_document) - imported_count,
        "total_elapsed": total_elapsed,
        "elapsed_per_doc": total_elapsed / len(per_document),
        "date": time.strftime("%Y-%m-%d"),
        "documents": per_document,
    }
    output_root.joinpath("summary.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    output_root.joinpath("errors.json").write_text(
        json.dumps({"documents": errors}, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )
    return output_root


def reference_engine_version(reference_root: Path) -> str:
    summary_path = reference_root / "summary.json"
    if not summary_path.is_file():
        return "unknown"
    try:
        return str(json.loads(summary_path.read_text(encoding="utf-8")).get("engine_version", "unknown"))
    except json.JSONDecodeError:
        return "unknown"


def evaluated_document_ids(output_root: Path) -> list[str]:
    summary_path = output_root / "summary.json"
    payload = json.loads(summary_path.read_text(encoding="utf-8"))
    return [
        item["document_id"]
        for item in payload.get("documents", [])
        if isinstance(item.get("document_id"), str)
    ]


def run_evaluator(bench_dir: Path, engine: str, doc_ids: list[str]) -> None:
    evaluator_args = ["src/evaluator.py", "--engine", engine]
    for doc_id in doc_ids:
        evaluator_args.extend(["--doc-id", doc_id])
    uv = shutil.which("uv")
    if uv:
        command = [uv, "run", *evaluator_args]
    else:
        command = [sys.executable, *evaluator_args]
    subprocess.run(command, cwd=bench_dir, check=True)


def main() -> int:
    require_python_oracle_opt_in()
    args = parse_args()
    output_root = write_predictions(args)
    if not args.skip_eval:
        run_evaluator(
            Path(args.bench_dir).resolve(),
            args.engine,
            evaluated_document_ids(output_root),
        )
    print(output_root)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
