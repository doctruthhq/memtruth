#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
MANIFEST="$ROOT/runtime/doctruth-runtime/Cargo.toml"
BIN="$ROOT/runtime/doctruth-runtime/target/debug/doctruth-runtime"

if [ -z "${DOCTRUTH_MODEL_MANIFEST:-}" ]; then
  echo "DOCTRUTH_MODEL_MANIFEST is required for the MNN promotion bench lane" >&2
  exit 2
fi

if [ -z "${DOCTRUTH_MODEL_CACHE:-}" ]; then
  echo "DOCTRUTH_MODEL_CACHE is required for the MNN promotion bench lane" >&2
  exit 2
fi

if [ ! -f "$DOCTRUTH_MODEL_MANIFEST" ]; then
  echo "DOCTRUTH_MODEL_MANIFEST does not exist: $DOCTRUTH_MODEL_MANIFEST" >&2
  exit 2
fi

if [ ! -d "$DOCTRUTH_MODEL_CACHE" ]; then
  echo "DOCTRUTH_MODEL_CACHE does not exist: $DOCTRUTH_MODEL_CACHE" >&2
  exit 2
fi

cargo build --manifest-path "$MANIFEST" >/dev/null

DOCTRUTH_RUNTIME_BIN="$BIN" python3 "$ROOT/scripts/doctruth_opendataloader_prediction.py" \
  --bench-dir "$ROOT/third_party/opendataloader-bench" \
  --runtime-profile edge-model \
  "$@"
