#!/usr/bin/env python3
"""Smoke test for parser reference triage reports."""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "triage-doctruth-parser-reference-report.py"


def main() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        comparison = {
            "report_format": "doctruth.parser-reference-comparison.v1",
            "target_engine": "doctruth",
            "reference_engines": ["docling"],
            "case_count": 2,
            "cases": [
                {
                    "document_id": "table-case",
                    "failure_bucket": "table_missing",
                    "top_loss_metric": "teds",
                    "deltas": {"overall": 0.8, "nid": 0.1, "teds": 1.0, "mhs": 0.2},
                },
                {
                    "document_id": "heading-case",
                    "failure_bucket": "heading_missing",
                    "top_loss_metric": "mhs",
                    "deltas": {"overall": 0.6, "nid": 0.2, "mhs": 1.0},
                },
            ],
        }
        comparison_path = root / "comparison.json"
        comparison_path.write_text(json.dumps(comparison), encoding="utf-8")
        output = root / "triage.json"
        markdown = root / "triage.md"
        subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                "--comparison",
                str(comparison_path),
                "--output",
                str(output),
                "--markdown-output",
                str(markdown),
            ],
            check=True,
        )
        report = json.loads(output.read_text(encoding="utf-8"))
        assert report["report_format"] == "doctruth.parser-reference-triage.v1"
        assert report["phase_totals"]["table-cluster-rust-parity"] == 1
        assert report["phase_totals"]["heading-section-tree"] == 1
        assert "table_missing" in markdown.read_text(encoding="utf-8")
    print("doctruth parser reference triage smoke passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
