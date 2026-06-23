#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
DEFAULT_BENCH="$ROOT/scripts/run-doctruth-opendataloader-bench.sh"
RUNTIME="$ROOT/runtime/doctruth-runtime/src/lib.rs"

if ! rg -n 'BACKEND="\$\{DOCTRUTH_OPENDATALOADER_BACKEND:-opendataloader-java-core\}"' "$DEFAULT_BENCH" >/dev/null; then
  echo "default OpenDataLoader benchmark backend must be opendataloader-java-core" >&2
  exit 1
fi

if ! rg -n 'opendataloader-backend --stdio-jsonl' "$DEFAULT_BENCH" >/dev/null; then
  echo "default OpenDataLoader benchmark runner must use the warm Java stdio backend" >&2
  exit 1
fi

if rg -n 'python3 .*doctruth_opendataloader_prediction\.py|python .*doctruth_opendataloader_prediction\.py' "$DEFAULT_BENCH"; then
  echo "default OpenDataLoader benchmark runner must not call the Python prediction adapter" >&2
  exit 1
fi

if ! rg -n 'DOCTRUTH_ALLOW_PYTHON_ORACLE' "$DEFAULT_BENCH" >/dev/null; then
  echo "official Python evaluator must remain gated by DOCTRUTH_ALLOW_PYTHON_ORACLE" >&2
  exit 1
fi

if ! rg -n 'PYTHON_DEFAULT_BACKEND_FORBIDDEN' "$RUNTIME" >/dev/null; then
  echo "runtime must reject Python/Torch/Docling default backend commands" >&2
  exit 1
fi

echo "no Python default benchmark path check passed"
