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
        "--runtime-bin",
        default=os.environ.get("DOCTRUTH_RUNTIME_BIN"),
        help="Path to doctruth-runtime binary",
    )
    parser.add_argument(
        "--skip-eval",
        action="store_true",
        help="Only generate prediction artifacts; do not run evaluator.py",
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
    letters = [char for char in text if char.isalpha()]
    if not letters:
        return False
    uppercase_ratio = sum(1 for char in letters if char.isupper()) / len(letters)
    if uppercase_ratio >= 0.72 and len(letters) >= 4:
        return True
    return bool(re.match(r"^(chapter|section|appendix)\s+\d+", text, re.IGNORECASE))


def table_html(table: dict[str, Any]) -> str:
    rows: dict[int, dict[int, dict[str, Any]]] = {}
    for cell in table.get("cells", []):
        row = int(cell.get("row", 0))
        column = int(cell.get("column", 0))
        rows.setdefault(row, {})[column] = cell
    if not rows:
        return ""

    lines = ["<table>"]
    for row_index in sorted(rows):
        lines.append(" <tr>")
        for column_index in sorted(rows[row_index]):
            cell = rows[row_index][column_index]
            attrs = []
            colspan = int(cell.get("colspan", cell.get("columnSpan", 1)) or 1)
            rowspan = int(cell.get("rowspan", cell.get("rowSpan", 1)) or 1)
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
    unit_lines: list[str] = []
    table_units = set()
    for unit in document.get("body", {}).get("units", []):
        if unit.get("kind") == "TABLE_CELL":
            table_units.add(id(unit))

    for unit in document.get("body", {}).get("units", []):
        if id(unit) in table_units:
            continue
        text = unit.get("text")
        if isinstance(text, str):
            line = normalize_line(text)
            if line:
                unit_lines.append(line)
    synthetic_table, consumed = synthetic_table_html_from_lines(unit_lines)
    for index, line in enumerate(unit_lines):
        if index in consumed:
            continue
        if is_probable_heading(line):
            line = f"# {line}"
        lines.append(line)
    for table in document.get("body", {}).get("tables", []):
        html = table_html(table)
        if html:
            lines.append(html)
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


def run_runtime(runtime_bin: Path, pdf_path: Path, preset: str) -> dict[str, Any]:
    request = {
        "command": "parse_pdf",
        "source_path": str(pdf_path),
        "source_hash": sha256_file(pdf_path),
        "preset": preset,
    }
    completed = subprocess.run(
        [str(runtime_bin)],
        input=json.dumps(request),
        text=True,
        capture_output=True,
        check=False,
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


def write_predictions(args: argparse.Namespace) -> Path:
    bench_dir = Path(args.bench_dir).resolve()
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
            document = run_runtime(runtime_bin, pdf_path, args.preset)
            markdown_path.write_text(markdown_from_document(document), encoding="utf-8")
            status = "parsed"
            error = None
        except Exception as exc:  # pragma: no cover - exercised by real corpora.
            markdown_path.write_text("", encoding="utf-8")
            status = "failed"
            error = str(exc)
            errors.append({"document_id": doc_id, "error": error})
        elapsed = time.time() - doc_start
        per_document.append(
            {
                "document_id": doc_id,
                "status": status,
                "elapsed": elapsed,
                "markdown_path": str(markdown_path),
                "error": error,
            }
        )

    total_elapsed = time.time() - start
    parsed_count = sum(1 for item in per_document if item["status"] == "parsed")
    failed_count = len(per_document) - parsed_count
    summary = {
        "engine_name": args.engine,
        "engine_version": runtime_version(runtime_bin),
        "runtime_contract": "TrustDocument",
        "processor": platform.processor() or platform.machine(),
        "document_count": len(per_document),
        "parsed_count": parsed_count,
        "failed_count": failed_count,
        "total_elapsed": total_elapsed,
        "elapsed_per_doc": total_elapsed / len(per_document),
        "date": time.strftime("%Y-%m-%d"),
        "preset": args.preset,
        "runtime_bin": str(runtime_bin),
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


def run_evaluator(bench_dir: Path, engine: str, doc_id: str | None) -> None:
    evaluator_args = ["src/evaluator.py", "--engine", engine]
    if doc_id:
        evaluator_args.extend(["--doc-id", doc_id])
    uv = shutil.which("uv")
    if uv:
        command = [uv, "run", *evaluator_args]
    else:
        command = [sys.executable, *evaluator_args]
    subprocess.run(command, cwd=bench_dir, check=True)


def main() -> int:
    args = parse_args()
    output_root = write_predictions(args)
    if not args.skip_eval:
        run_evaluator(Path(args.bench_dir).resolve(), args.engine, args.doc_id)
    print(output_root)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
