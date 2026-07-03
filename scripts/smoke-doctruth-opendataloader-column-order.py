#!/usr/bin/env python3
"""Smoke test bbox-aware page-number filtering."""

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
            "boundingBox": {"x0": x, "x1": x + 30, "y0": y, "y1": y + 10},
        },
    }


def main() -> int:
    module = load_module()
    units = [unit("12", 500, 940), unit("Title", 50, 100), unit("Body", 50, 120)]
    document = {"body": {"tables": [], "units": units}}
    markdown = module.markdown_from_document(document)
    assert "12" not in markdown, markdown
    assert "Title" in markdown, markdown
    assert "Body" in markdown, markdown
    print("doctruth opendataloader column order smoke passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
