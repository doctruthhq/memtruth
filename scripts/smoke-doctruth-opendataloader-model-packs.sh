#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

python3 "$ROOT/scripts/validate-doctruth-model-packs.py" \
  "$ROOT/model-packs/opendataloader-hybrid-models.json" \
  "$ROOT/model-packs/ppocr-v5-mobile-mnn.json"

echo "doctruth opendataloader model pack contract smoke passed"
