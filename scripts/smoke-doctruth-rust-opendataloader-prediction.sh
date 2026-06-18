#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
MANIFEST="$ROOT/runtime/doctruth-runtime/Cargo.toml"
BIN="$ROOT/runtime/doctruth-runtime/target/debug/doctruth-runtime"
WORKER="$ROOT/runtime/doctruth-runtime/target/debug/examples/mnn_promotion_smoke_worker"
BENCH_DIR="$ROOT/third_party/opendataloader-bench"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-rust-opendataloader-prediction.XXXXXX")"
MODEL_CACHE="$WORK_DIR/model-cache"
MODEL_MANIFEST="$WORK_DIR/models.json"
MODEL_BYTES="$MODEL_CACHE/slanet-plus-v1.bin"
PREDICTION="$WORK_DIR/prediction/doctruth-rust-mnn"
REPORT="$WORK_DIR/report.json"

mkdir -p "$MODEL_CACHE"
printf '%s' "rust-owned-mnn-promotion-model" > "$MODEL_BYTES"

MODEL_SHA="$(shasum -a 256 "$MODEL_BYTES" | awk '{print $1}')"
MODEL_SIZE="$(wc -c < "$MODEL_BYTES" | tr -d ' ')"

cat > "$MODEL_MANIFEST" <<EOF_MODEL
{"presets":{"table-lite":[{"name":"slanet-plus","version":"v1","sha256":"sha256:$MODEL_SHA","sizeBytes":$MODEL_SIZE,"required":true,"task":"table","backend":"mnn","format":"mnn","precision":"fp32","license":"test"}]}}
EOF_MODEL

cargo build --manifest-path "$MANIFEST" \
  --example mnn_promotion_smoke_worker >/dev/null
cargo build --manifest-path "$MANIFEST" >/dev/null

DOCTRUTH_MODEL_MANIFEST="$MODEL_MANIFEST" \
DOCTRUTH_MODEL_CACHE="$MODEL_CACHE" \
DOCTRUTH_RUNTIME_MODEL_COMMAND="$WORKER" \
  "$BIN" <<EOF_REQUEST > "$REPORT"
{"command":"opendataloader_prediction","bench_dir":"$BENCH_DIR","engine":"doctruth-rust-mnn","doc_id":"01030000000001","preset":"auto","runtime_profile":"edge-model","output_dir":"$PREDICTION"}
EOF_REQUEST

test -s "$PREDICTION/markdown/01030000000001.md"
test -s "$PREDICTION/summary.json"
test -s "$PREDICTION/errors.json"

jq -e '.prediction.engine == "doctruth-rust-mnn"' "$REPORT" >/dev/null
jq -e '.prediction.documentCount == 1 and .prediction.failedCount == 0' "$REPORT" >/dev/null
jq -e '.mnnPromotion.evaluated == false' "$REPORT" >/dev/null
jq -e '.engine_name == "doctruth-rust-mnn"' "$PREDICTION/summary.json" >/dev/null
jq -e '.runtime_contract == "TrustDocument"' "$PREDICTION/summary.json" >/dev/null
jq -e '.runtime_profile == "edge-model"' "$PREDICTION/summary.json" >/dev/null
jq -e '.parsed_count == 1 and .failed_count == 0' "$PREDICTION/summary.json" >/dev/null
jq -e '.production_residency.python_torch_docling == false' "$PREDICTION/summary.json" >/dev/null
jq -e '.documents[0].modelRuntime.runtime == "mnn"' "$PREDICTION/summary.json" >/dev/null
jq -e '.documents[0].modelRouting.route == "table-model"' "$PREDICTION/summary.json" >/dev/null
jq -e '.documents == []' "$PREDICTION/errors.json" >/dev/null

rm -rf "$WORK_DIR"

echo "doctruth rust opendataloader prediction smoke passed"
