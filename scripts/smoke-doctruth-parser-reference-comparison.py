#!/usr/bin/env python3
"""Smoke test for parser reference comparison reports."""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "compare-doctruth-parser-references.py"


def write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def evaluation(engine: str, scores: dict[str, float]) -> dict:
    return {
        "summary": {"engine_name": engine},
        "metrics": {"score": {}},
        "documents": [
            {
                "document_id": "doc1",
                "scores": {
                    "overall": scores["overall"],
                    "nid": scores["nid"],
                    "nid_s": scores["nid"],
                    "teds": scores["teds"],
                    "teds_s": scores["teds"],
                    "mhs": scores["mhs"],
                    "mhs_s": scores["mhs"],
                },
                "prediction_available": True,
            }
        ],
    }


def main() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        bench = Path(tmp)
        (bench / "ground-truth" / "markdown").mkdir(parents=True)
        (bench / "ground-truth" / "markdown" / "doc1.md").write_text(
            "# Heading\n\n<table><tr><td>A</td></tr></table>\n",
            encoding="utf-8",
        )
        target = bench / "prediction" / "doctruth" / "markdown"
        target.mkdir(parents=True)
        (target / "doc1.md").write_text("Heading\nA\n", encoding="utf-8")
        for engine in ["opendataloader", "docling", "opendataloader-hybrid"]:
            directory = bench / "prediction" / engine / "markdown"
            directory.mkdir(parents=True)
            (directory / "doc1.md").write_text(
                "# Heading\n\n<table><tr><td>A</td></tr></table>\n",
                encoding="utf-8",
            )

        write_json(
            bench / "prediction" / "doctruth" / "evaluation.json",
            evaluation("doctruth", {"overall": 0.2, "nid": 0.8, "teds": 0.0, "mhs": 0.1}),
        )
        write_json(
            bench / "prediction" / "opendataloader" / "evaluation.json",
            evaluation("opendataloader", {"overall": 0.8, "nid": 0.9, "teds": 0.6, "mhs": 0.7}),
        )
        write_json(
            bench / "prediction" / "docling" / "evaluation.json",
            evaluation("docling", {"overall": 0.9, "nid": 0.88, "teds": 0.9, "mhs": 0.8}),
        )
        write_json(
            bench / "prediction" / "opendataloader-hybrid" / "evaluation.json",
            evaluation("opendataloader-hybrid", {"overall": 0.95, "nid": 0.95, "teds": 0.95, "mhs": 0.82}),
        )

        report_path = bench / "comparison.json"
        markdown_path = bench / "comparison.md"
        subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                "--bench-dir",
                str(bench),
                "--target-engine",
                "doctruth",
                "--output",
                str(report_path),
                "--markdown-output",
                str(markdown_path),
            ],
            check=True,
        )
        report = json.loads(report_path.read_text(encoding="utf-8"))
        assert report["report_format"] == "doctruth.parser-reference-comparison.v1"
        assert report["case_count"] == 1
        assert report["top_losses"][0]["top_loss_metric"] == "teds"
        assert report["top_losses"][0]["failure_bucket"] == "table_missing"
        assert markdown_path.read_text(encoding="utf-8").startswith("# Parser Reference Comparison")
    print("doctruth parser reference comparison smoke passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
