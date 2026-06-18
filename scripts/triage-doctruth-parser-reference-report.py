#!/usr/bin/env python3
"""Group parser reference comparison failures into implementation slices."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from statistics import fmean
from typing import Any


PHASE_BY_BUCKET = {
    "table_missing": "table-cluster-rust-parity",
    "table_structure_mismatch": "table-cluster-rust-parity",
    "heading_missing": "heading-section-tree",
    "heading_hierarchy_mismatch": "heading-section-tree",
    "reading_order_or_text_normalization": "reading-order-text-normalization",
    "text_missing_or_truncated": "reading-order-text-normalization",
    "text_noise_or_duplicates": "reading-order-text-normalization",
    "missing_prediction": "ocr-or-runtime-failure",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Summarize parser comparison failures.")
    parser.add_argument("--comparison", required=True, help="reference-comparison.json path")
    parser.add_argument("--output", required=True, help="JSON triage report path")
    parser.add_argument("--markdown-output", default=None, help="Optional Markdown report path")
    parser.add_argument("--top-per-bucket", type=int, default=10)
    return parser.parse_args()


def mean(values: list[float]) -> float | None:
    return fmean(values) if values else None


def numeric(value: Any) -> float | None:
    return float(value) if isinstance(value, (int, float)) else None


def case_loss(case: dict[str, Any]) -> float:
    overall = numeric(case.get("deltas", {}).get("overall"))
    if overall is not None:
        return overall
    values = [
        numeric(case.get("deltas", {}).get(metric))
        for metric in ["nid", "teds", "mhs"]
    ]
    filtered = [value for value in values if value is not None]
    return mean(filtered) or 0.0


def build_bucket(name: str, cases: list[dict[str, Any]], limit: int) -> dict[str, Any]:
    sorted_cases = sorted(cases, key=case_loss, reverse=True)
    metrics = {}
    for metric in ["overall", "nid", "teds", "mhs"]:
        values = [numeric(case.get("deltas", {}).get(metric)) for case in cases]
        metrics[metric] = mean([value for value in values if value is not None])
    return {
        "bucket": name,
        "implementation_phase": PHASE_BY_BUCKET.get(name, "manual-review"),
        "case_count": len(cases),
        "mean_delta": metrics,
        "representative_cases": [
            {
                "document_id": case["document_id"],
                "top_loss_metric": case.get("top_loss_metric"),
                "loss": case_loss(case),
                "deltas": case.get("deltas", {}),
            }
            for case in sorted_cases[:limit]
        ],
    }


def build_report(comparison: dict[str, Any], limit: int) -> dict[str, Any]:
    buckets: dict[str, list[dict[str, Any]]] = {}
    for case in comparison.get("cases", []):
        buckets.setdefault(case.get("failure_bucket", "unknown"), []).append(case)

    bucket_reports = [
        build_bucket(name, cases, limit)
        for name, cases in sorted(
            buckets.items(),
            key=lambda item: (len(item[1]), mean([case_loss(case) for case in item[1]]) or 0.0),
            reverse=True,
        )
    ]

    phase_totals: dict[str, int] = {}
    for bucket in bucket_reports:
        phase = bucket["implementation_phase"]
        phase_totals[phase] = phase_totals.get(phase, 0) + bucket["case_count"]

    return {
        "report_format": "doctruth.parser-reference-triage.v1",
        "source_report_format": comparison.get("report_format"),
        "target_engine": comparison.get("target_engine"),
        "reference_engines": comparison.get("reference_engines", []),
        "case_count": comparison.get("case_count", 0),
        "phase_totals": dict(sorted(phase_totals.items())),
        "buckets": bucket_reports,
    }


def write_markdown(report: dict[str, Any], path: Path) -> None:
    lines = [
        "# Parser Reference Triage",
        "",
        f"Target: `{report['target_engine']}`",
        "",
        "## Phase Totals",
        "",
        "| Phase | Cases |",
        "| --- | ---: |",
    ]
    for phase, count in report["phase_totals"].items():
        lines.append(f"| {phase} | {count} |")
    for bucket in report["buckets"]:
        lines.extend(
            [
                "",
                f"## {bucket['bucket']}",
                "",
                f"Implementation phase: `{bucket['implementation_phase']}`",
                "",
                f"Cases: `{bucket['case_count']}`",
                "",
                "| Document | Metric | Loss |",
                "| --- | --- | ---: |",
            ]
        )
        for case in bucket["representative_cases"]:
            lines.append(
                f"| {case['document_id']} | {case['top_loss_metric']} | {case['loss']:.3f} |"
            )
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    args = parse_args()
    comparison = json.loads(Path(args.comparison).read_text(encoding="utf-8"))
    report = build_report(comparison, args.top_per_bucket)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    if args.markdown_output:
        markdown = Path(args.markdown_output)
        markdown.parent.mkdir(parents=True, exist_ok=True)
        write_markdown(report, markdown)
    print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
