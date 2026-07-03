#!/usr/bin/env python3
"""Smoke-test DocTruth OpenDataLoader Markdown export formatting."""

from __future__ import annotations

import importlib.util
from pathlib import Path


SCRIPT = Path(__file__).with_name("doctruth_opendataloader_prediction.py")
spec = importlib.util.spec_from_file_location("doctruth_opendataloader_prediction", SCRIPT)
module = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(module)


document = {
    "body": {
        "units": [
            {"kind": "LINE_SPAN", "text": "EXECUTIVE SUMMARY"},
            {"kind": "LINE_SPAN", "text": "Revenue grew in Q4."},
            {"kind": "TABLE_CELL", "text": "Region", "tableId": "table-1", "row": 0, "column": 0},
            {"kind": "TABLE_CELL", "text": "Revenue", "tableId": "table-1", "row": 0, "column": 1},
            {"kind": "TABLE_CELL", "text": "APAC", "tableId": "table-1", "row": 1, "column": 0},
            {"kind": "TABLE_CELL", "text": "$10", "tableId": "table-1", "row": 1, "column": 1},
        ],
        "tables": [
            {
                "tableId": "table-1",
                "cells": [
                    {"text": "Region", "row": 0, "column": 0},
                    {"text": "Revenue", "row": 0, "column": 1},
                    {"text": "APAC", "row": 1, "column": 0},
                    {"text": "$10", "row": 1, "column": 1},
                ],
            }
        ],
    }
}

markdown = module.markdown_from_document(document)
assert "# EXECUTIVE SUMMARY" in markdown, markdown
assert "<table>" in markdown, markdown
assert "<td>Region</td>" in markdown, markdown
assert "Region\nRevenue\nAPAC\n$10" not in markdown, markdown

line_table_document = {
    "body": {
        "units": [
            {"kind": "LINE_SPAN", "text": "Table: Accredited observers"},
            {"kind": "LINE_SPAN", "text": "No."},
            {"kind": "LINE_SPAN", "text": "Name of organization"},
            {"kind": "LINE_SPAN", "text": "1"},
            {"kind": "LINE_SPAN", "text": "2"},
            {"kind": "LINE_SPAN", "text": "Union of Youth Federations"},
            {"kind": "LINE_SPAN", "text": "Cambodian Women for Peace"},
            {"kind": "LINE_SPAN", "text": "Number of accredited"},
            {"kind": "LINE_SPAN", "text": "observers"},
            {"kind": "LINE_SPAN", "text": "17,266"},
            {"kind": "LINE_SPAN", "text": "9,835"},
        ],
        "tables": [],
    }
}

line_table_markdown = module.markdown_from_document(line_table_document)
assert "<table>" in line_table_markdown, line_table_markdown
assert "<td>Union of Youth Federations</td>" in line_table_markdown, line_table_markdown
assert "<td>17,266</td>" in line_table_markdown, line_table_markdown

print("doctruth opendataloader export format smoke passed")
