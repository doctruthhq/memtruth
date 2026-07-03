#!/usr/bin/env python3
"""Smoke test for bbox-based spatial table fallback."""

from __future__ import annotations

import importlib.util
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "scripts" / "doctruth_opendataloader_prediction.py"


def load_module():
    spec = importlib.util.spec_from_file_location("doctruth_opendataloader_prediction", MODULE_PATH)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def unit(text: str, x: float, y: float) -> dict:
    return {
        "kind": "LINE_SPAN",
        "text": text,
        "location": {
            "page": 1,
            "boundingBox": {"x0": x, "x1": x + 10, "y0": y, "y1": y + 10},
        },
    }


def main() -> int:
    module = load_module()
    table_document = {
        "body": {
            "tables": [],
            "units": [
                unit("A", 10, 10),
                unit("B", 80, 10),
                unit("C", 150, 10),
                unit("1", 10, 30),
                unit("2", 80, 30),
                unit("3", 150, 30),
                unit("4", 10, 50),
                unit("5", 80, 50),
                unit("6", 150, 50),
                unit("7", 10, 70),
                unit("8", 80, 70),
                unit("9", 150, 70),
                unit("after table", 10, 150),
            ],
        }
    }
    markdown = module.markdown_from_document(table_document)
    assert "<table>" in markdown, markdown
    assert "<td>A</td>" in markdown, markdown
    assert "<td>9</td>" in markdown, markdown
    assert "after table" in markdown, markdown
    assert markdown.count("A") == 1, markdown

    prose_document = {
        "body": {
            "tables": [],
            "units": [
                unit("This is a long left-column prose sentence.", 10, 10),
                unit("This is a long right-column prose sentence.", 260, 10),
                unit("The extraction should preserve paragraph text.", 10, 30),
                unit("The fallback should not emit table markup.", 260, 30),
                unit("Another paragraph line with enough length.", 10, 50),
                unit("Another opposite column line with words.", 260, 50),
                unit("Final paragraph line in the left column.", 10, 70),
                unit("Final paragraph line in the right column.", 260, 70),
            ],
        }
    }
    prose_markdown = module.markdown_from_document(prose_document)
    assert "<table>" not in prose_markdown, prose_markdown
    assert "long left-column prose sentence" in prose_markdown, prose_markdown
    print("doctruth opendataloader spatial table smoke passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
