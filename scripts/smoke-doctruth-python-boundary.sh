#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
DEFAULT_BENCH="$ROOT/scripts/run-doctruth-opendataloader-bench.sh"
MNN_BENCH="$ROOT/scripts/run-doctruth-mnn-promotion-bench.sh"
LEGACY_BASELINE="$ROOT/scripts/run-doctruth-opendataloader-hybrid-baseline.sh"
LEGACY_ADAPTER="$ROOT/scripts/doctruth_opendataloader_prediction.py"

if rg -n "python3 .*doctruth_opendataloader_prediction\\.py|python .*doctruth_opendataloader_prediction\\.py" "$DEFAULT_BENCH" "$MNN_BENCH"; then
  echo "default OpenDataLoader benchmark runners must not call the Python prediction adapter" >&2
  exit 1
fi

if ! rg -n "DOCTRUTH_ALLOW_PYTHON_ORACLE" "$LEGACY_BASELINE" >/dev/null; then
  echo "legacy Python oracle runner must require DOCTRUTH_ALLOW_PYTHON_ORACLE=1" >&2
  exit 1
fi

set +e
OUT="$(sh "$LEGACY_BASELINE" --help 2>&1)"
STATUS="$?"
set -e

if [ "$STATUS" -eq 0 ]; then
  echo "legacy Python oracle runner should fail closed without DOCTRUTH_ALLOW_PYTHON_ORACLE=1" >&2
  exit 1
fi

printf '%s' "$OUT" | rg -n "oracle-only|DOCTRUTH_ALLOW_PYTHON_ORACLE" >/dev/null

set +e
ADAPTER_OUT="$(python3 "$LEGACY_ADAPTER" --help 2>&1)"
ADAPTER_STATUS="$?"
set -e

if [ "$ADAPTER_STATUS" -eq 0 ]; then
  echo "direct Python prediction adapter should fail closed without DOCTRUTH_ALLOW_PYTHON_ORACLE=1" >&2
  exit 1
fi

printf '%s' "$ADAPTER_OUT" | rg -n "oracle-only|DOCTRUTH_ALLOW_PYTHON_ORACLE" >/dev/null

echo "doctruth python boundary smoke passed"
