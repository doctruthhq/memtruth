#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
BENCH_DIR="$ROOT/third_party/opendataloader-bench"
ENGINE="doctruth-runtime-smoke"
DOC_ID="01030000000001"
OUT_DIR="$BENCH_DIR/prediction/$ENGINE"

rm -rf "$OUT_DIR"

sh "$ROOT/scripts/run-doctruth-opendataloader-bench.sh" \
  --engine "$ENGINE" \
  --doc-id "$DOC_ID" \
  --skip-eval

test -s "$OUT_DIR/markdown/$DOC_ID.md"
test -s "$OUT_DIR/summary.json"
test -s "$OUT_DIR/errors.json"
test -s "$OUT_DIR/prediction-report.json"

python3 - "$OUT_DIR/summary.json" "$OUT_DIR/errors.json" "$OUT_DIR/prediction-report.json" <<'PY'
import json
import pathlib
import sys

summary = json.loads(pathlib.Path(sys.argv[1]).read_text())
errors = json.loads(pathlib.Path(sys.argv[2]).read_text())
report = json.loads(pathlib.Path(sys.argv[3]).read_text())
assert summary["engine_name"] == "doctruth-runtime-smoke", summary
assert summary["document_count"] == 1, summary
assert summary["preset"] == "auto", summary
assert summary["parsed_count"] + summary["failed_count"] == 1, summary
assert summary["runtime_contract"] == "TrustDocument", summary
assert isinstance(errors["documents"], list), errors
assert report["runtime"] == "doctruth-runtime", report
assert report["prediction"]["engine"] == "doctruth-runtime-smoke", report
assert report["resourceProfile"]["pythonTorchDoclingProductionResidency"] is False, report
PY

rm -rf "$OUT_DIR"

echo "doctruth opendataloader bench runner smoke passed"
