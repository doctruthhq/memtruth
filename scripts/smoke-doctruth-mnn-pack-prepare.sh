#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-mnn-pack-prepare.XXXXXX")"

cleanup() {
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT INT TERM

if ! command -v jq >/dev/null 2>&1; then
    echo "jq is required for MNN pack prepare smoke" >&2
    exit 2
fi

mkdir -p "$WORK_DIR/reference-cache" "$WORK_DIR/source" "$WORK_DIR/fake-bin"
printf 'onnx reference model bytes' > "$WORK_DIR/source/table.onnx"
SOURCE_SHA="$(shasum -a 256 "$WORK_DIR/source/table.onnx" | awk '{print $1}')"
SOURCE_SIZE="$(wc -c < "$WORK_DIR/source/table.onnx" | tr -d ' ')"
cp "$WORK_DIR/source/table.onnx" "$WORK_DIR/reference-cache/table-reference.onnx"

cat > "$WORK_DIR/reference-pack.json" <<EOF_PACK
{
  "packId": "reference-pack",
  "version": "test",
  "source": {
    "project": "test",
    "repository": "https://example.invalid/reference",
    "license": "Apache-2.0"
  },
  "presets": {
    "table-lite": [
      {
        "name": "table-reference",
        "version": "v1",
        "cacheFilename": "table-reference.onnx",
        "sha256": "sha256:$SOURCE_SHA",
        "sizeBytes": $SOURCE_SIZE,
        "required": true,
        "task": "table-structure-recognition",
        "role": "table-structure-decoder",
        "backend": "onnxruntime",
        "format": "onnx",
        "precision": "fp32",
        "license": "Apache-2.0",
        "url": "file://$WORK_DIR/source/table.onnx",
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
  },
  "promotionGates": {
    "mnn": {
      "quality": {"overall": 0.88, "nid": 0.91, "teds": 0.88, "mhs": 0.78}
    }
  }
}
EOF_PACK

set +e
MISSING_OUT="$(DOCTRUTH_MNN_CONVERT_BIN="$WORK_DIR/no-such-MNNConvert" \
  sh "$ROOT/scripts/prepare-doctruth-mnn-model-pack.sh" \
    --reference-manifest "$WORK_DIR/reference-pack.json" \
    --reference-cache "$WORK_DIR/reference-cache" \
    --output-manifest "$WORK_DIR/missing-output.json" \
    --output-cache "$WORK_DIR/missing-cache" 2>&1)"
MISSING_STATUS="$?"
set -e

if [ "$MISSING_STATUS" -eq 0 ]; then
    echo "MNN pack preparation must fail closed when no converter is available" >&2
    echo "$MISSING_OUT" >&2
    exit 1
fi
printf '%s' "$MISSING_OUT" | jq -e '.ok == false and .code == "mnn_convert_unavailable"' >/dev/null

cat > "$WORK_DIR/fake-bin/MNNConvert" <<'EOF_CONVERT'
#!/usr/bin/env sh
set -eu
MODEL_IN=""
MODEL_OUT=""
WEIGHT_QUANT_BITS=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        --modelFile)
            MODEL_IN="$2"
            shift 2
            ;;
        --MNNModel)
            MODEL_OUT="$2"
            shift 2
            ;;
        --weightQuantBits)
            WEIGHT_QUANT_BITS="$2"
            shift 2
            ;;
        *)
            shift
            ;;
    esac
done
if [ -z "$MODEL_IN" ] || [ -z "$MODEL_OUT" ]; then
    echo "missing model input/output" >&2
    exit 2
fi
if [ "$WEIGHT_QUANT_BITS" != "8" ]; then
    echo "expected --weightQuantBits 8" >&2
    exit 2
fi
printf 'mnn:' > "$MODEL_OUT"
cat "$MODEL_IN" >> "$MODEL_OUT"
EOF_CONVERT
chmod +x "$WORK_DIR/fake-bin/MNNConvert"

PATH="$WORK_DIR/fake-bin:$PATH" \
sh "$ROOT/scripts/prepare-doctruth-mnn-model-pack.sh" \
    --reference-manifest "$WORK_DIR/reference-pack.json" \
    --reference-cache "$WORK_DIR/reference-cache" \
    --output-manifest "$WORK_DIR/output-pack.json" \
    --output-cache "$WORK_DIR/output-cache" \
    --weight-quant-bits 8 > "$WORK_DIR/prepare.json"

jq -e --arg converter "$WORK_DIR/fake-bin/MNNConvert" --arg sourceSha "sha256:$SOURCE_SHA" '
  .ok == true
  and .converted == 1
  and .artifacts[0].sourceBackend == "onnxruntime"
  and .artifacts[0].targetBackend == "mnn"
  and .artifacts[0].conversion.weightQuantBits == 8
  and .artifacts[0].conversion.converter == $converter
  and .artifacts[0].conversion.sourceSha256 == $sourceSha
' "$WORK_DIR/prepare.json" >/dev/null

jq -e '
  .packId == "reference-pack-mnn"
  and .presets["table-lite"][0].backend == "mnn"
  and .presets["table-lite"][0].format == "mnn"
  and .presets["table-lite"][0].sourceBackend == "onnxruntime"
  and .presets["table-lite"][0].sourceFormat == "onnx"
  and .presets["table-lite"][0].cacheFilename == "table-reference-v1.mnn"
  and .presets["table-lite"][0].parity.candidateEngine == "rust-mnn"
  and .promotionGates.mnn.quality.overall == 0.88
' "$WORK_DIR/output-pack.json" >/dev/null

jq -e --arg converter "$WORK_DIR/fake-bin/MNNConvert" --arg sourceSha "sha256:$SOURCE_SHA" '
  .presets["table-lite"][0].conversion.weightQuantBits == 8
  and .presets["table-lite"][0].conversion.converter == $converter
  and .presets["table-lite"][0].conversion.sourceSha256 == $sourceSha
' "$WORK_DIR/output-pack.json" >/dev/null

sh "$ROOT/scripts/check-doctruth-mnn-pack-readiness.sh" \
    --manifest "$WORK_DIR/output-pack.json" \
    --cache "$WORK_DIR/output-cache" >/dev/null

echo "doctruth MNN pack prepare smoke passed"
