#!/usr/bin/env python3
"""Smoke test heading promotion for benchmark Markdown export."""

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


def main() -> int:
    module = load_module()
    assert module.is_probable_heading("2. General Profile of MSMEs")
    assert module.is_probable_heading("3. RECOLLECTION OF NATIONAL INITIATIVES")
    assert module.is_probable_heading("Fact-Checking")
    assert module.is_probable_heading("Contents")
    assert module.is_probable_heading("Summary")
    assert not module.is_probable_heading("Business characteristics.")
    assert not module.is_probable_heading("Figure 2.1: Surveyed MSMEs by size")
    assert not module.is_probable_heading("Germany")
    assert not module.is_probable_heading("1935 Constitution. The reluctance was expected")
    document = {
        "contentBlocks": [
            {
                "type": "heading",
                "textLevel": 2,
                "normalizedText": "CORE HEADING",
                "sourceUnitIds": ["unit-0001"],
            },
            {
                "type": "text",
                "textLevel": None,
                "normalizedText": "cross border",
                "sourceUnitIds": ["unit-0002"],
            },
            {
                "type": "text",
                "textLevel": None,
                "normalizedText": "trade evidence.",
                "sourceUnitIds": ["unit-0003"],
            },
        ],
        "body": {
            "tables": [],
            "units": [
                {"unitId": "unit-0001", "kind": "LINE_SPAN", "text": "ignored"},
                {"unitId": "unit-0002", "kind": "LINE_SPAN", "text": "cross bor-"},
                {"unitId": "unit-0003", "kind": "LINE_SPAN", "text": "der evidence."},
            ],
        },
    }
    markdown = module.markdown_from_document(document)
    assert "# CORE HEADING" in markdown, markdown
    assert "cross border" in markdown, markdown
    assert "trade evidence." in markdown, markdown
    previous_levels = os.environ.get("DOCTRUTH_BENCH_USE_CORE_HEADING_LEVELS")
    os.environ["DOCTRUTH_BENCH_USE_CORE_HEADING_LEVELS"] = "1"
    try:
        leveled_markdown = module.markdown_from_document(document)
    finally:
        if previous_levels is None:
            os.environ.pop("DOCTRUTH_BENCH_USE_CORE_HEADING_LEVELS", None)
        else:
            os.environ["DOCTRUTH_BENCH_USE_CORE_HEADING_LEVELS"] = previous_levels
    assert "## CORE HEADING" in leveled_markdown, leveled_markdown
    joined = module.render_text_entries(
        [
            {"type": "text", "text": "cross bor-"},
            {"type": "text", "text": "der evidence."},
        ],
        set(),
    )
    assert joined == ["cross bor-", "der evidence."], joined
    print("doctruth opendataloader heading promotion smoke passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
