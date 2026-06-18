#!/usr/bin/env python3
"""Smoke test DocTruth's OpenDataLoader adapter recovers party table rows."""

from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RUNTIME_MANIFEST = ROOT / "runtime/doctruth-runtime/Cargo.toml"
RUNTIME_BIN = ROOT / "runtime/doctruth-runtime/target/debug/doctruth-runtime"
PDF_PATH = ROOT / "third_party/opendataloader-bench/pdfs/01030000000047.pdf"


def load_prediction_module():
    module_path = ROOT / "scripts/doctruth_opendataloader_prediction.py"
    spec = importlib.util.spec_from_file_location("doctruth_opendataloader_prediction", module_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {module_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main() -> int:
    subprocess.run(["cargo", "build", "--manifest-path", str(RUNTIME_MANIFEST)], check=True)
    prediction = load_prediction_module()
    document = prediction.run_runtime(RUNTIME_BIN, PDF_PATH, "lite", 30.0)
    markdown = prediction.markdown_from_document(document)
    failures = []
    for expected in [
        '<td rowspan="2">No.</td>',
        '<td rowspan="2">Political party</td>',
        '<td colspan="2">Provisional registration result on 7 March</td>',
        '<td colspan="2">Official registration result on 29 April</td>',
        '<td rowspan="2">Difference in the number of candidates</td>',
        "<td>11</td>",
        "<td>Khmer United Party</td>",
        "<td>35</td>",
        "<td>498</td>",
        "<td>30</td>",
        "<td>457</td>",
        "<td>-41</td>",
        "<td>Total</td>",
        "<td>84,208</td>",
        "<td>86,092</td>",
        "<td>+1,884</td>",
    ]:
        if expected not in markdown:
            failures.append(f"missing table fragment: {expected}")
    if "Khmer United Party Khmer Economic Development Party" in markdown:
        failures.append("party names from different rows must not merge into one cell")
    if "<td>24</td>" in markdown:
        failures.append("page number must not become a party table row")
    if failures:
        print(json.dumps({"failures": failures, "markdown": markdown}, indent=2), file=sys.stderr)
        return 1
    print("doctruth OpenDataLoader party table smoke passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
