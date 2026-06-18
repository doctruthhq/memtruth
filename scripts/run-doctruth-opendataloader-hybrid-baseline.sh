#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

echo "warning: this is an oracle-only legacy Python/OpenDataLoader hybrid baseline, not the default DocTruth Rust parser path" >&2

python3 "$ROOT/scripts/doctruth_opendataloader_prediction.py" \
  --bench-dir "$ROOT/third_party/opendataloader-bench" \
  --engine doctruth-opendataloader-hybrid-baseline \
  --reference-engine opendataloader-hybrid \
  "$@"
