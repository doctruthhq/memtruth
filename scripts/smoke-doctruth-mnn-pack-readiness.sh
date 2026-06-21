#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-mnn-pack-readiness.XXXXXX")"

cleanup() {
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT INT TERM

if ! command -v jq >/dev/null 2>&1; then
    echo "jq is required for MNN pack readiness smoke" >&2
    exit 2
fi

set +e
CURRENT_OUT="$(sh "$ROOT/scripts/check-doctruth-mnn-pack-readiness.sh" \
    --manifest "$ROOT/model-packs/opendataloader-hybrid-models.json" \
    --cache "$WORK_DIR/empty-cache" 2>&1)"
CURRENT_STATUS="$?"
set -e

if [ "$CURRENT_STATUS" -eq 0 ]; then
    echo "OpenDataLoader ONNX reference pack must not be MNN production-ready" >&2
    echo "$CURRENT_OUT" >&2
    exit 1
fi

printf '%s' "$CURRENT_OUT" | jq -e '
  .productionReady == false
  and .summary.total == 2
  and .summary.mnnReady == 0
  and .summary.blocked == 2
  and ([.artifacts[].blockedReasons[]] | index("missing_mnn_candidate") != null)
' >/dev/null

mkdir -p "$WORK_DIR/cache" "$WORK_DIR/source"
printf 'ready mnn model' > "$WORK_DIR/source/table.mnn"
TABLE_SHA="$(shasum -a 256 "$WORK_DIR/source/table.mnn" | awk '{print $1}')"
cp "$WORK_DIR/source/table.mnn" "$WORK_DIR/cache/table-model-v1.bin"

cat > "$WORK_DIR/ready-pack.json" <<EOF_PACK
{
  "packId": "ready-mnn-pack",
  "presets": {
    "table-lite": [
      {
        "name": "table-model",
        "version": "v1",
        "sha256": "sha256:$TABLE_SHA",
        "sizeBytes": 15,
        "required": true,
        "task": "table-structure-recognition",
        "backend": "mnn",
        "format": "mnn",
        "url": "file://$WORK_DIR/source/table.mnn",
        "preprocessing": {
          "inputLayout": "NCHW",
          "dtype": "float32",
          "colorSpace": "sRGB",
          "channelOrder": "RGB",
          "resize": {"width": 800, "height": 800, "keepAspectRatio": false},
          "resample": "bilinear",
          "scale": 0.00392156862745098,
          "mean": [0.485, 0.456, 0.406],
          "std": [0.229, 0.224, 0.225]
        },
        "parity": {
          "referenceEngine": "python-onnxruntime",
          "candidateEngine": "rust-mnn",
          "tensorDumpRequired": true,
          "firstTensorValuesRequired": true,
          "maxAbsDiff": 0.000001
        }
      }
    ]
  }
}
EOF_PACK

READY_OUT="$(sh "$ROOT/scripts/check-doctruth-mnn-pack-readiness.sh" \
    --manifest "$WORK_DIR/ready-pack.json" \
    --cache "$WORK_DIR/cache")"

printf '%s' "$READY_OUT" | jq -e '
  .productionReady == true
  and .summary.total == 1
  and .summary.mnnReady == 1
  and .summary.blocked == 0
  and .artifacts[0].cacheStatus == "READY"
' >/dev/null

printf 'tampered model' > "$WORK_DIR/cache/table-model-v1.bin"
set +e
TAMPERED_OUT="$(sh "$ROOT/scripts/check-doctruth-mnn-pack-readiness.sh" \
    --manifest "$WORK_DIR/ready-pack.json" \
    --cache "$WORK_DIR/cache" 2>&1)"
TAMPERED_STATUS="$?"
set -e

if [ "$TAMPERED_STATUS" -eq 0 ]; then
    echo "tampered MNN cache artifact must not be production-ready" >&2
    echo "$TAMPERED_OUT" >&2
    exit 1
fi

printf '%s' "$TAMPERED_OUT" | jq -e '
  .productionReady == false
  and .summary.mnnReady == 0
  and .summary.blocked == 1
  and .artifacts[0].cacheStatus == "SHA_MISMATCH"
  and (.artifacts[0].blockedReasons | index("sha_mismatch") != null)
' >/dev/null

echo "doctruth MNN pack readiness smoke passed"
