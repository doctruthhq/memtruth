#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

if [ "${DOCTRUTH_ALLOW_PYTHON_ORACLE:-}" != "1" ]; then
  cat >&2 <<'EOF'
refusing to start Python/OpenDataLoader hybrid baseline.

This script is oracle-only legacy benchmark infrastructure. It is not the
default DocTruth parser, OpenDataLoader prediction, or MNN promotion path.

Use scripts/run-doctruth-opendataloader-bench.sh for the default Rust runner.
Set DOCTRUTH_ALLOW_PYTHON_ORACLE=1 only when intentionally reproducing the
heavy OpenDataLoader/docling-fast oracle baseline.
EOF
  exit 2
fi

echo "warning: running oracle-only legacy Python/OpenDataLoader hybrid baseline; this is not the default DocTruth Rust parser path" >&2

python3 "$ROOT/scripts/doctruth_opendataloader_prediction.py" \
  --bench-dir "$ROOT/third_party/opendataloader-bench" \
  --engine doctruth-opendataloader-hybrid-baseline \
  --reference-engine opendataloader-hybrid \
  "$@"
