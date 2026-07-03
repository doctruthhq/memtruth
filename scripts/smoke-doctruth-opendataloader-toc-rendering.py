#!/usr/bin/env python3
"""Smoke test DocTruth's OpenDataLoader adapter renders TOC as Markdown."""

from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RUNTIME_MANIFEST = ROOT / "runtime/doctruth-runtime/Cargo.toml"
RUNTIME_BIN = ROOT / "runtime/doctruth-runtime/target/debug/doctruth-runtime"
PDF_PATH = ROOT / "third_party/opendataloader-bench/pdfs/01030000000044.pdf"


def load_prediction_module():
    module_path = ROOT / "scripts/doctruth_opendataloader_prediction.py"
    spec = importlib.util.spec_from_file_location("doctruth_opendataloader_prediction", module_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {module_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def build_runtime() -> None:
    subprocess.run(["cargo", "build", "--manifest-path", str(RUNTIME_MANIFEST)], check=True)


def parse_pdf() -> dict:
    prediction = load_prediction_module()
    return prediction.run_runtime(RUNTIME_BIN, PDF_PATH, "lite", 30.0)


def main() -> int:
    build_runtime()
    prediction = load_prediction_module()
    document = parse_pdf()
    markdown = prediction.markdown_from_document(document)
    failures = []
    if not markdown.startswith("# Table of Contents\n"):
        failures.append("TOC must render as a Markdown heading")
    if "<table>" in markdown:
        failures.append("TOC must not render as an HTML table")
    for expected in [
        "Executive Summary 4",
        "Legal Framework 6",
        "Political Parties, Candidates Registration and Election 18",
        "Campaign",
        "Recommendations 39",
    ]:
        if expected not in markdown:
            failures.append(f"missing TOC line: {expected}")
    if failures:
        print(json.dumps({"failures": failures, "markdown": markdown}, indent=2), file=sys.stderr)
        return 1
    print("doctruth OpenDataLoader TOC rendering smoke passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
