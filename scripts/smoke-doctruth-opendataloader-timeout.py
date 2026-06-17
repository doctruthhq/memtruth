#!/usr/bin/env python3
"""Smoke-test per-document timeout handling in the OpenDataLoader runner."""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import tempfile
from pathlib import Path


SCRIPT = Path(__file__).with_name("doctruth_opendataloader_prediction.py")
spec = importlib.util.spec_from_file_location("doctruth_opendataloader_prediction", SCRIPT)
module = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(module)


with tempfile.TemporaryDirectory(prefix="doctruth-opendataloader-timeout-") as raw:
    root = Path(raw)
    bench = root / "bench"
    (bench / "pdfs").mkdir(parents=True)
    (bench / "pdfs" / "slow.pdf").write_bytes(b"%PDF-1.4\n%%EOF\n")
    runtime = root / "slow-runtime.sh"
    runtime.write_text(
        "#!/usr/bin/env sh\n"
        "sleep 0.2\n"
        "printf '%s\\n' '{\"body\":{\"units\":[{\"kind\":\"LINE_SPAN\",\"text\":\"late\"}]}}'\n"
    )
    os.chmod(runtime, 0o755)

    args = argparse.Namespace(
        bench_dir=str(bench),
        engine="timeout-smoke",
        doc_id="slow",
        limit=None,
        preset="lite",
        runtime_bin=str(runtime),
        skip_eval=True,
        timeout_seconds=0.01,
    )
    output = module.write_predictions(args)
    summary = json.loads((output / "summary.json").read_text())
    errors = json.loads((output / "errors.json").read_text())

assert summary["document_count"] == 1, summary
assert summary["parsed_count"] == 0, summary
assert summary["failed_count"] == 1, summary
assert "timed out" in errors["documents"][0]["error"], errors

print("doctruth opendataloader timeout smoke passed")
