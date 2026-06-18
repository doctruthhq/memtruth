#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
MANIFEST="$ROOT/runtime/doctruth-runtime/Cargo.toml"
BIN="$ROOT/runtime/doctruth-runtime/target/debug/doctruth-runtime"
WORKER="$ROOT/runtime/doctruth-runtime/target/debug/examples/mnn_promotion_smoke_worker"
BENCH_PDF="$ROOT/third_party/opendataloader-bench/pdfs/01030000000001.pdf"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-rust-opendataloader-prediction.XXXXXX")"
MODEL_CACHE="$WORK_DIR/model-cache"
MODEL_MANIFEST="$WORK_DIR/models.json"
MODEL_BYTES="$MODEL_CACHE/slanet-plus-v1.bin"
PDF="$WORK_DIR/01030000000001.pdf"
CORPUS="$WORK_DIR/corpus.json"
EXPECTED_MD="$WORK_DIR/expected.md"
EXPECTED_DOC="$WORK_DIR/expected.json"
PREDICTION="$WORK_DIR/prediction/doctruth-rust-mnn"
REPORT="$WORK_DIR/report.json"

mkdir -p "$MODEL_CACHE"
cp "$BENCH_PDF" "$PDF"
printf '%s\n' "MNN promotion smoke worker evidence" > "$EXPECTED_MD"
printf '%s\n' '{}' > "$EXPECTED_DOC"
printf '%s' "rust-owned-mnn-promotion-model" > "$MODEL_BYTES"

MODEL_SHA="$(shasum -a 256 "$MODEL_BYTES" | awk '{print $1}')"
MODEL_SIZE="$(wc -c < "$MODEL_BYTES" | tr -d ' ')"

cat > "$MODEL_MANIFEST" <<EOF_MODEL
{"presets":{"table-lite":[{"name":"slanet-plus","version":"v1","sha256":"sha256:$MODEL_SHA","sizeBytes":$MODEL_SIZE,"required":true,"task":"table","backend":"mnn","format":"mnn","precision":"fp32","license":"test"}]}}
EOF_MODEL

cat > "$CORPUS" <<EOF_CORPUS
{
  "name": "rust-opendataloader-prediction-smoke",
  "kind": "human-labeled",
  "qualityProfile": "parser-accuracy",
  "labeling": {
    "labelSetVersion": "rust-opendataloader-smoke-v1",
    "reviewedAt": "2026-06-18",
    "reviewer": "rust-runtime-smoke",
    "reviewType": "generated-seed",
    "requiredMetrics": ["reading_order_f1", "quote_anchor_accuracy"],
    "requiredTags": ["mnn-routing"],
    "minCasesPerTag": 1
  },
  "minimums": {
    "reading_order_f1": 1.0,
    "quote_anchor_accuracy": 1.0
  },
  "cases": [
    {
      "name": "rust-owned-opendataloader-prediction",
      "labelId": "01030000000001",
      "tags": ["mnn-routing"],
      "fixtureTypes": ["table"],
      "behaviors": ["mnn-promotion-routing"],
      "preset": "auto",
      "source": "01030000000001.pdf",
      "expectedMarkdown": "expected.md",
      "expectedDocument": "expected.json"
    }
  ]
}
EOF_CORPUS

cargo build --manifest-path "$MANIFEST" \
  --example mnn_promotion_smoke_worker >/dev/null
cargo build --manifest-path "$MANIFEST" >/dev/null

DOCTRUTH_MODEL_MANIFEST="$MODEL_MANIFEST" \
DOCTRUTH_MODEL_CACHE="$MODEL_CACHE" \
DOCTRUTH_RUNTIME_MODEL_COMMAND="$WORKER" \
  "$BIN" <<EOF_REQUEST > "$REPORT"
{"command":"benchmark_corpus","manifest_path":"$CORPUS","offline":true,"runtime_profile":"edge-model","opendataloader_prediction_dir":"$PREDICTION"}
EOF_REQUEST

test -s "$PREDICTION/markdown/01030000000001.md"
test -s "$PREDICTION/summary.json"
test -s "$PREDICTION/errors.json"

jq -e '.passed == true' "$REPORT" >/dev/null
jq -e '.externalArtifacts.opendataloaderPrediction.engine == "doctruth"' "$REPORT" >/dev/null
jq -e '.engine_name == "doctruth"' "$PREDICTION/summary.json" >/dev/null
jq -e '.runtime_contract == "TrustDocument"' "$PREDICTION/summary.json" >/dev/null
jq -e '.runtime_profile == "edge-model"' "$PREDICTION/summary.json" >/dev/null
jq -e '.parsed_count == 1 and .failed_count == 0' "$PREDICTION/summary.json" >/dev/null
jq -e '.production_residency.python_torch_docling == false' "$PREDICTION/summary.json" >/dev/null
jq -e '.documents[0].modelRuntime.runtime == "mnn"' "$PREDICTION/summary.json" >/dev/null
jq -e '.documents[0].modelRouting.route == "table-model"' "$PREDICTION/summary.json" >/dev/null
jq -e '.documents == []' "$PREDICTION/errors.json" >/dev/null

rm -rf "$WORK_DIR"

echo "doctruth rust opendataloader prediction smoke passed"
