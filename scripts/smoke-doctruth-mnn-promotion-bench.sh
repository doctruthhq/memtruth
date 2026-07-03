#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
BENCH_DIR="$ROOT/third_party/opendataloader-bench"
ENGINE="doctruth-mnn-promotion-smoke"
DOC_ID="01030000000001"
OUT_DIR="$BENCH_DIR/prediction/$ENGINE"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-mnn-promotion-bench.XXXXXX")"
MODEL_CACHE="$WORK_DIR/model-cache"
MODEL_MANIFEST="$WORK_DIR/models.json"
MODEL_BYTES="$MODEL_CACHE/slanet-plus-v1.bin"
WORKER="$ROOT/runtime/doctruth-runtime/target/debug/examples/mnn_promotion_smoke_worker"

rm -rf "$OUT_DIR"
mkdir -p "$MODEL_CACHE"

printf '%s' "mnn-promotion-smoke-table-model" > "$MODEL_BYTES"
MODEL_SHA="$(shasum -a 256 "$MODEL_BYTES" | awk '{print $1}')"
MODEL_SIZE="$(wc -c < "$MODEL_BYTES" | tr -d ' ')"
cat > "$MODEL_MANIFEST" <<EOF_MANIFEST
{
  "presets": {
    "table-lite": [
      {
        "name": "slanet-plus",
        "version": "v1",
        "source": "$MODEL_BYTES",
        "sha256": "sha256:$MODEL_SHA",
        "sizeBytes": $MODEL_SIZE,
        "required": true,
        "task": "table",
        "backend": "mnn",
        "format": "mnn",
        "precision": "fp32",
        "license": "test",
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
      "quality": {"overall": 0.0, "nid": 0.0, "teds": 0.0, "mhs": 0.0},
      "resources": {"heavyOracleSteadyRssMb": 1500.0}
    }
  }
}
EOF_MANIFEST

cargo build --manifest-path "$ROOT/runtime/doctruth-runtime/Cargo.toml" \
  --example mnn_promotion_smoke_worker >/dev/null

DOCTRUTH_MODEL_MANIFEST="$MODEL_MANIFEST" \
DOCTRUTH_MODEL_CACHE="$MODEL_CACHE" \
DOCTRUTH_RUNTIME_MODEL_COMMAND="$WORKER" \
  sh "$ROOT/scripts/run-doctruth-mnn-promotion-bench.sh" \
    --engine "$ENGINE" \
    --doc-id "$DOC_ID" \
    --preset auto \
    --skip-eval

test -s "$OUT_DIR/markdown/$DOC_ID.md"
test -s "$OUT_DIR/summary.json"
test -s "$OUT_DIR/prediction-report.json"

python3 - "$OUT_DIR/summary.json" "$OUT_DIR/prediction-report.json" <<'PY'
import json
import pathlib
import sys

summary = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
report = json.loads(pathlib.Path(sys.argv[2]).read_text(encoding="utf-8"))
assert summary["engine_name"] == "doctruth-mnn-promotion-smoke", summary
assert summary["runtime_profile"] == "edge-model", summary
assert summary["production_residency"]["python_torch_docling"] is False, summary
assert summary["documents"][0]["runtimeProfile"] == "edge-model", summary["documents"][0]
runtime = summary["documents"][0]["modelRuntime"]
assert runtime["runtime"] == "mnn", runtime
assert runtime["coldStartMs"] == 12.0, runtime
assert runtime["peakMemoryMb"] == 123.0, runtime
assert report["runtime"] == "doctruth-runtime", report
assert report["prediction"]["engine"] == "doctruth-mnn-promotion-smoke", report
assert report["resourceProfile"]["profile"] == "edge-model", report["resourceProfile"]
assert report["resourceProfile"]["pythonTorchDoclingProductionResidency"] is False, report["resourceProfile"]
assert report["resourceProfile"]["modelRuntime"]["runtime"] == "mnn", report["resourceProfile"]
assert report["mnnPromotion"]["evaluated"] is False, report["mnnPromotion"]
PY

rm -rf "$OUT_DIR" "$WORK_DIR"

echo "doctruth mnn promotion bench smoke passed"
