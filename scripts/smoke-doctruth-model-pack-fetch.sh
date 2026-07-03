#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-model-pack-fetch.XXXXXX")"

cleanup() {
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT INT TERM

mkdir -p "$WORK_DIR/source" "$WORK_DIR/cache"
printf 'det-model' > "$WORK_DIR/source/det.mnn"
printf 'rec-model' > "$WORK_DIR/source/rec.mnn"
printf 'abc\n' > "$WORK_DIR/source/keys.txt"

DET_SHA="$(shasum -a 256 "$WORK_DIR/source/det.mnn" | awk '{print $1}')"
REC_SHA="$(shasum -a 256 "$WORK_DIR/source/rec.mnn" | awk '{print $1}')"
KEYS_SHA="$(shasum -a 256 "$WORK_DIR/source/keys.txt" | awk '{print $1}')"

cat > "$WORK_DIR/pack.json" <<EOF_PACK
{
  "packId": "test-pack",
  "presets": {
    "ocr": [
      {
        "name": "test-det",
        "version": "v1",
        "sha256": "sha256:$DET_SHA",
        "sizeBytes": 9,
        "required": true,
        "task": "ocr",
        "backend": "mnn",
        "format": "mnn",
        "url": "file://$WORK_DIR/source/det.mnn"
      },
      {
        "name": "test-rec",
        "version": "v1",
        "sha256": "sha256:$REC_SHA",
        "sizeBytes": 9,
        "required": true,
        "task": "ocr",
        "backend": "mnn",
        "format": "mnn",
        "url": "file://$WORK_DIR/source/rec.mnn"
      }
    ]
  },
  "auxiliary": [
    {
      "name": "test-keys",
      "version": "v1",
      "sha256": "sha256:$KEYS_SHA",
      "sizeBytes": 4,
      "url": "file://$WORK_DIR/source/keys.txt"
    }
  ]
}
EOF_PACK

python3 "$ROOT/scripts/fetch-doctruth-model-pack.py" \
  --manifest "$WORK_DIR/pack.json" \
  --cache "$WORK_DIR/cache"

test -f "$WORK_DIR/cache/test-det-v1.bin"
test -f "$WORK_DIR/cache/test-rec-v1.bin"
test -f "$WORK_DIR/cache/test-keys-v1.bin"

DOCTRUTH_MODEL_CACHE="$WORK_DIR/cache" \
DOCTRUTH_MODEL_MANIFEST="$WORK_DIR/pack.json" \
  "$ROOT/runtime/doctruth-runtime/target/debug/doctruth-runtime" --doctor \
  | python3 -c '
import json
import sys

doctor = json.load(sys.stdin)
models = doctor["models"]["presets"]["ocr"]["models"]
assert len(models) == 2, models
assert all(model["cacheStatus"] == "READY" for model in models), models
assert doctor["capabilities"]["ocr"]["available"] is True, doctor["capabilities"]["ocr"]
'

echo "doctruth model pack fetch smoke passed"
