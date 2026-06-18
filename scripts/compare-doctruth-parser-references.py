#!/usr/bin/env python3
"""Compare DocTruth parser quality against reference benchmark engines."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from statistics import fmean
from typing import Any


METRICS = ["overall", "nid", "nid_s", "teds", "teds_s", "mhs", "mhs_s"]
PRIMARY_METRICS = ["nid", "teds", "mhs"]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build a per-document DocTruth/OpenDataLoader/Docling comparison report."
    )
    parser.add_argument("--bench-dir", required=True, help="OpenDataLoader Bench root")
    parser.add_argument("--target-engine", required=True, help="DocTruth prediction engine")
    parser.add_argument(
        "--reference-engine",
        action="append",
        default=None,
        help="Reference engine to compare; may be repeated.",
    )
    parser.add_argument(
        "--output",
        default=None,
        help="JSON report path. Defaults to prediction/<target-engine>/reference-comparison.json.",
    )
    parser.add_argument(
        "--markdown-output",
        default=None,
        help="Optional Markdown summary report path.",
    )
    parser.add_argument("--top", type=int, default=25, help="Number of top-loss cases to include.")
    return parser.parse_args()


def read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except FileNotFoundError:
        return ""


def load_engine(bench_dir: Path, engine: str) -> dict[str, Any]:
    path = bench_dir / "prediction" / engine / "evaluation.json"
    if not path.is_file():
        raise FileNotFoundError(f"Missing evaluation for {engine}: {path}")
    payload = read_json(path)
    documents = {}
    for item in payload.get("documents", []):
        doc_id = item.get("document_id")
        if isinstance(doc_id, str):
            documents[doc_id] = item
    return {
        "engine": engine,
        "path": str(path),
        "summary": payload.get("summary", {}),
        "metrics": payload.get("metrics", {}),
        "documents": documents,
    }


def score(item: dict[str, Any] | None, metric: str) -> float | None:
    if item is None:
        return None
    value = item.get("scores", {}).get(metric)
    return float(value) if isinstance(value, (int, float)) else None


def mean(values: list[float]) -> float | None:
    return fmean(values) if values else None


def has_table(markdown: str) -> bool:
    if "<table" in markdown.lower():
        return True
    return bool(re.search(r"^\s*\|[\s:]*-+[\s:]*\|", markdown, re.MULTILINE))


def has_heading(markdown: str) -> bool:
    return bool(re.search(r"^\s{0,3}#{1,6}\s+\S", markdown, re.MULTILINE))


def markdown_features(bench_dir: Path, engine: str, doc_id: str) -> dict[str, Any]:
    markdown = read_text(bench_dir / "prediction" / engine / "markdown" / f"{doc_id}.md")
    return {
        "has_table": has_table(markdown),
        "has_heading": has_heading(markdown),
        "char_count": len(markdown),
        "line_count": len(markdown.splitlines()),
    }


def gt_features(bench_dir: Path, doc_id: str) -> dict[str, Any]:
    markdown = read_text(bench_dir / "ground-truth" / "markdown" / f"{doc_id}.md")
    return {
        "has_table": has_table(markdown),
        "has_heading": has_heading(markdown),
        "char_count": len(markdown),
        "line_count": len(markdown.splitlines()),
    }


def best_reference_scores(ref_items: list[dict[str, Any] | None]) -> dict[str, float | None]:
    best = {}
    for metric in METRICS:
        values = [score(item, metric) for item in ref_items]
        numeric = [value for value in values if value is not None]
        best[metric] = max(numeric) if numeric else None
    return best


def metric_deltas(
    target_item: dict[str, Any] | None, best_scores: dict[str, float | None]
) -> dict[str, float | None]:
    deltas = {}
    for metric in METRICS:
        target_score = score(target_item, metric)
        best = best_scores.get(metric)
        deltas[metric] = best - target_score if best is not None and target_score is not None else None
    return deltas


def top_metric(deltas: dict[str, float | None]) -> str | None:
    candidates = {
        metric: value
        for metric, value in deltas.items()
        if metric in PRIMARY_METRICS and value is not None
    }
    if not candidates:
        return None
    return max(candidates, key=lambda metric: candidates[metric])


def classify_failure(
    target_item: dict[str, Any] | None,
    deltas: dict[str, float | None],
    gt: dict[str, Any],
    target_features: dict[str, Any],
) -> str:
    if target_item is None or target_item.get("prediction_available") is False:
        return "missing_prediction"
    metric = top_metric(deltas)
    if metric == "teds":
        if gt["has_table"] and not target_features["has_table"]:
            return "table_missing"
        return "table_structure_mismatch"
    if metric == "mhs":
        if gt["has_heading"] and not target_features["has_heading"]:
            return "heading_missing"
        return "heading_hierarchy_mismatch"
    if metric == "nid":
        if target_features["char_count"] < gt["char_count"] * 0.55:
            return "text_missing_or_truncated"
        if target_features["char_count"] > gt["char_count"] * 1.45:
            return "text_noise_or_duplicates"
        return "reading_order_or_text_normalization"
    return "no_primary_metric_delta"


def build_report(args: argparse.Namespace) -> dict[str, Any]:
    bench_dir = Path(args.bench_dir).resolve()
    reference_names = args.reference_engine or ["opendataloader", "docling", "opendataloader-hybrid"]
    target = load_engine(bench_dir, args.target_engine)
    references = [load_engine(bench_dir, name) for name in reference_names]

    doc_ids = sorted(
        set(target["documents"].keys()).union(
            *(set(reference["documents"].keys()) for reference in references)
        )
    )

    cases = []
    for doc_id in doc_ids:
        target_item = target["documents"].get(doc_id)
        ref_items = [reference["documents"].get(doc_id) for reference in references]
        best_scores = best_reference_scores(ref_items)
        deltas = metric_deltas(target_item, best_scores)
        gt = gt_features(bench_dir, doc_id)
        target_md = markdown_features(bench_dir, args.target_engine, doc_id)
        cases.append(
            {
                "document_id": doc_id,
                "failure_bucket": classify_failure(target_item, deltas, gt, target_md),
                "top_loss_metric": top_metric(deltas),
                "target_scores": target_item.get("scores", {}) if target_item else {},
                "best_reference_scores": best_scores,
                "deltas": deltas,
                "ground_truth": gt,
                "target_markdown": target_md,
                "reference_scores": {
                    reference["engine"]: (
                        reference["documents"].get(doc_id, {}).get("scores", {})
                    )
                    for reference in references
                },
            }
        )

    return {
        "report_format": "doctruth.parser-reference-comparison.v1",
        "target_engine": args.target_engine,
        "reference_engines": reference_names,
        "case_count": len(cases),
        "summary": build_summary(cases),
        "top_losses": top_losses(cases, args.top),
        "cases": cases,
    }


def build_summary(cases: list[dict[str, Any]]) -> dict[str, Any]:
    metric_deltas_summary = {}
    for metric in METRICS:
        values = [case["deltas"].get(metric) for case in cases]
        numeric = [value for value in values if value is not None]
        metric_deltas_summary[metric] = mean(numeric)
    buckets: dict[str, int] = {}
    top_metrics: dict[str, int] = {}
    for case in cases:
        buckets[case["failure_bucket"]] = buckets.get(case["failure_bucket"], 0) + 1
        metric = case.get("top_loss_metric") or "none"
        top_metrics[metric] = top_metrics.get(metric, 0) + 1
    return {
        "mean_delta_to_best_reference": metric_deltas_summary,
        "failure_buckets": dict(sorted(buckets.items())),
        "top_loss_metrics": dict(sorted(top_metrics.items())),
    }


def top_losses(cases: list[dict[str, Any]], limit: int) -> list[dict[str, Any]]:
    def loss(case: dict[str, Any]) -> float:
        overall = case["deltas"].get("overall")
        if overall is not None:
            return overall
        values = [case["deltas"].get(metric) for metric in PRIMARY_METRICS]
        numeric = [value for value in values if value is not None]
        return mean(numeric) or 0.0

    selected = sorted(cases, key=loss, reverse=True)[:limit]
    return [
        {
            "document_id": case["document_id"],
            "failure_bucket": case["failure_bucket"],
            "top_loss_metric": case["top_loss_metric"],
            "deltas": case["deltas"],
            "target_scores": case["target_scores"],
            "best_reference_scores": case["best_reference_scores"],
        }
        for case in selected
    ]


def write_markdown(report: dict[str, Any], path: Path) -> None:
    lines = [
        "# Parser Reference Comparison",
        "",
        f"Target: `{report['target_engine']}`",
        "",
        "References: " + ", ".join(f"`{name}`" for name in report["reference_engines"]),
        "",
        "## Mean Delta To Best Reference",
        "",
        "| Metric | Delta |",
        "| --- | ---: |",
    ]
    for metric, value in report["summary"]["mean_delta_to_best_reference"].items():
        text = f"{value:.3f}" if value is not None else "n/a"
        lines.append(f"| {metric} | {text} |")
    lines.extend(["", "## Failure Buckets", "", "| Bucket | Count |", "| --- | ---: |"])
    for bucket, count in report["summary"]["failure_buckets"].items():
        lines.append(f"| {bucket} | {count} |")
    lines.extend(["", "## Top Losses", "", "| Document | Bucket | Metric | Overall Delta |", "| --- | --- | --- | ---: |"])
    for case in report["top_losses"]:
        delta = case["deltas"].get("overall")
        text = f"{delta:.3f}" if delta is not None else "n/a"
        lines.append(
            f"| {case['document_id']} | {case['failure_bucket']} | {case['top_loss_metric']} | {text} |"
        )
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    args = parse_args()
    bench_dir = Path(args.bench_dir).resolve()
    output = (
        Path(args.output)
        if args.output
        else bench_dir / "prediction" / args.target_engine / "reference-comparison.json"
    )
    report = build_report(args)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    markdown_output = Path(args.markdown_output) if args.markdown_output else None
    if markdown_output:
        markdown_output.parent.mkdir(parents=True, exist_ok=True)
        write_markdown(report, markdown_output)
    print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
