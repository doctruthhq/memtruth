#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
MANIFEST="$ROOT_DIR/runtime/doctruth-runtime/Cargo.toml"
MODEL="${DOCTRUTH_MNN_NATIVE_PROBE_MODEL:-}"

if [ -z "$MODEL" ]; then
    echo "skipping native MNN probe: set DOCTRUTH_MNN_NATIVE_PROBE_MODEL=/path/to/model.mnn"
    exit 0
fi

if [ ! -f "$MODEL" ]; then
    echo "native MNN probe model not found: $MODEL" >&2
    exit 2
fi

REPORT="$(cargo run --quiet \
    --manifest-path "$MANIFEST" \
    --features mnn-native \
    --bin doctruth-mnn-model-worker \
    -- --probe-model "$MODEL")"

printf '%s\n' "$REPORT" | python3 -c '
import json
import sys

payload = json.load(sys.stdin)
assert payload["ok"] is True, payload
assert payload["runtime"] == "mnn", payload
assert payload["engine"] == "mnn", payload
assert payload["command"] == "probe_model", payload
assert payload["nativeBackend"]["compiled"] is True, payload
assert payload["nativeBackend"]["crate"] == "mnn-rs", payload
assert payload["mnnSessionReady"] is True, payload
assert payload["inferenceRan"] is True, payload
assert payload["metrics"]["inferenceMs"] >= 0, payload
assert payload["metrics"]["totalMs"] >= payload["metrics"]["inferenceMs"], payload
'

echo "doctruth native MNN probe smoke passed"
