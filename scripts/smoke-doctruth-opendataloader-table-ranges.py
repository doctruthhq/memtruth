#!/usr/bin/env python3
"""Smoke test for OpenDataLoader table rendering from TrustDocument ranges."""

from __future__ import annotations

import importlib.util
import os
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "scripts" / "doctruth_opendataloader_prediction.py"


def load_module():
    spec = importlib.util.spec_from_file_location("doctruth_opendataloader_prediction", MODULE_PATH)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def line_unit(text: str, x: float, y: float) -> dict:
    return {
        "unitId": f"line-{text}",
        "kind": "LINE_SPAN",
        "text": text,
        "location": {
            "page": 1,
            "boundingBox": {"x0": x, "y0": y, "x1": x + 40, "y1": y + 10},
        },
    }


def main() -> int:
    module = load_module()
    html = module.table_html(
        {
            "cells": [
                {
                    "rowRange": {"start": 0, "end": 0},
                    "columnRange": {"start": 0, "end": 1},
                    "text": "Header",
                },
                {
                    "rowRange": {"start": 1, "end": 1},
                    "columnRange": {"start": 0, "end": 0},
                    "text": "A",
                },
                {
                    "rowRange": {"start": 1, "end": 1},
                    "columnRange": {"start": 1, "end": 1},
                    "text": "B",
                },
            ]
        }
    )
    assert '<td colspan="2">Header</td>' in html, html
    assert "<td>A</td>" in html, html
    assert "<td>B</td>" in html, html
    document = {
        "body": {
            "tables": [
                {
                    "tableId": "table-0001",
                    "cells": [
                        {
                            "rowRange": {"start": 0, "end": 0},
                            "columnRange": {"start": 0, "end": 0},
                            "text": "Name",
                        },
                        {
                            "rowRange": {"start": 0, "end": 0},
                            "columnRange": {"start": 1, "end": 1},
                            "text": "Score",
                        },
                    ],
                }
            ],
            "units": [
                {"unitId": "unit-0001", "kind": "LINE_SPAN", "text": "Before table."},
                {
                    "unitId": "unit-0002",
                    "kind": "TABLE_CELL",
                    "tableId": "table-0001",
                    "text": "Name",
                },
                {
                    "unitId": "unit-0003",
                    "kind": "TABLE_CELL",
                    "tableId": "table-0001",
                    "text": "Score",
                },
                {"unitId": "unit-0004", "kind": "LINE_SPAN", "text": "After table."},
            ],
        }
    }
    default_markdown = module.markdown_from_document(document)
    assert default_markdown.index("Before table.") < default_markdown.index("After table."), default_markdown
    assert default_markdown.index("After table.") < default_markdown.index("<table>"), default_markdown

    previous_inline = os.environ.get("DOCTRUTH_BENCH_INLINE_TABLES")
    os.environ["DOCTRUTH_BENCH_INLINE_TABLES"] = "1"
    try:
        markdown = module.markdown_from_document(document)
    finally:
        if previous_inline is None:
            os.environ.pop("DOCTRUTH_BENCH_INLINE_TABLES", None)
        else:
            os.environ["DOCTRUTH_BENCH_INLINE_TABLES"] = previous_inline
    assert markdown.index("Before table.") < markdown.index("<table>"), markdown
    assert markdown.index("<table>") < markdown.index("After table."), markdown

    bbox_document = {
        "body": {
            "tables": [
                {
                    "tableId": "table-0002",
                    "boundingBox": {"x0": 10, "y0": 10, "x1": 180, "y1": 80},
                    "cells": [
                        {"row": 0, "column": 0, "text": "Source"},
                        {"row": 0, "column": 1, "text": "Year"},
                        {"row": 1, "column": 0, "text": "Eco-Ecole"},
                        {"row": 1, "column": 1, "text": "2005"},
                    ],
                }
            ],
            "units": [
                line_unit("Before.", 10, 0),
                line_unit("Source", 10, 20),
                line_unit("Year", 100, 20),
                line_unit("Eco-Ecole", 10, 40),
                line_unit("2005", 100, 40),
                line_unit("After.", 10, 120),
            ],
        }
    }
    bbox_markdown = module.markdown_from_document(bbox_document)
    assert bbox_markdown.count("Source") == 1, bbox_markdown
    assert bbox_markdown.count("Eco-Ecole") == 1, bbox_markdown
    assert "Before." in bbox_markdown, bbox_markdown
    assert "After." in bbox_markdown, bbox_markdown
    degenerate_document = {
        "body": {
            "tables": [
                {
                    "tableId": "table-0003",
                    "boundingBox": {"x0": 10, "y0": 10, "x1": 500, "y1": 700},
                    "cells": [
                        {
                            "rowRange": {"start": 0, "end": 1},
                            "columnRange": {"start": 0, "end": 0},
                            "text": "This page is normal prose, not a table.",
                        }
                    ],
                }
            ],
            "units": [
                {
                    "unitId": "unit-degenerate-cell",
                    "kind": "TABLE_CELL",
                    "tableId": "table-0003",
                    "text": "This page is normal prose, not a table.",
                },
                line_unit("Second prose line.", 10, 50),
            ],
        }
    }
    degenerate_markdown = module.markdown_from_document(degenerate_document)
    assert "<table>" not in degenerate_markdown, degenerate_markdown
    assert "normal prose" in degenerate_markdown, degenerate_markdown
    print("doctruth opendataloader table range smoke passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
