#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
MANIFEST="$ROOT/runtime/doctruth-runtime/Cargo.toml"
BIN="$ROOT/runtime/doctruth-runtime/target/debug/doctruth-runtime"

cargo build --manifest-path "$MANIFEST" >/dev/null

DOCTRUTH_RUNTIME_BIN="$BIN" python3 "$ROOT/scripts/doctruth_opendataloader_prediction.py" \
  --bench-dir "$ROOT/third_party/opendataloader-bench" \
  "$@"
