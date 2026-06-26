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
  --backend rust-edge-fast \
  --runtime-profile edge-fast \
  --preset lite \
  --skip-eval

test -s "$OUT_DIR/markdown/$DOC_ID.md"
test -s "$OUT_DIR/summary.json"
test -d "$OUT_DIR/failures"
test -s "$OUT_DIR/prediction-report.json"
test ! -e "$OUT_DIR/errors.json"

python3 - "$OUT_DIR/summary.json" "$OUT_DIR/failures" "$OUT_DIR/prediction-report.json" <<'PY'
import json
import pathlib
import sys

summary = json.loads(pathlib.Path(sys.argv[1]).read_text())
failures = pathlib.Path(sys.argv[2])
report = json.loads(pathlib.Path(sys.argv[3]).read_text())
assert summary["engine_name"] == "doctruth-runtime-smoke", summary
assert summary["document_count"] == 1, summary
assert summary["preset"] == "lite", summary
assert summary["backend"] == "rust-edge-fast", summary
assert summary["parsed_count"] + summary["failed_count"] == 1, summary
assert summary["parsed_count"] == 1, summary
assert summary["failed_count"] == 0, summary
assert summary["runtime_contract"] == "TrustDocument", summary
assert list(failures.iterdir()) == [], list(failures.iterdir())
assert report["runtime"] == "doctruth-runtime", report
assert report["prediction"]["engine"] == "doctruth-runtime-smoke", report
assert report["resourceProfile"]["pythonTorchDoclingProductionResidency"] is False, report
PY

rm -rf "$OUT_DIR"

echo "doctruth opendataloader bench runner smoke passed"
