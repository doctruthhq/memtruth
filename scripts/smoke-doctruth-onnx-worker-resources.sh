#!/usr/bin/env sh
set -eu
export DOCTRUTH_ALLOW_PYTHON_ORACLE="${DOCTRUTH_ALLOW_PYTHON_ORACLE:-1}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-onnx-worker-resources.XXXXXX")"
MODEL="$WORK_DIR/identity.onnx"
REQUEST="$WORK_DIR/request.json"
OUT="$WORK_DIR/worker.json"

python3 - "$MODEL" "$REQUEST" <<'PY'
import hashlib
import json
import pathlib
import sys

import onnx
from onnx import TensorProto, helper

model_path = pathlib.Path(sys.argv[1])
request_path = pathlib.Path(sys.argv[2])
input_info = helper.make_tensor_value_info("input", TensorProto.FLOAT, [1, 16])
output_info = helper.make_tensor_value_info("output", TensorProto.FLOAT, [1, 16])
node = helper.make_node("Identity", ["input"], ["output"])
graph = helper.make_graph([node], "doctruth-resource-identity", [input_info], [output_info])
model = helper.make_model(graph, producer_name="doctruth-resource-smoke")
model.ir_version = 10
for opset in model.opset_import:
    opset.version = 21
onnx.checker.check_model(model)
onnx.save(model, model_path)
payload = model_path.read_bytes()
request_path.write_text(json.dumps({
    "version": 1,
    "preset": "table-lite",
    "sourcePath": str(model_path.with_suffix(".pdf")),
    "sourceFilename": "resource.pdf",
    "sourceHash": "sha256:resource-smoke",
    "modelCacheDirectory": str(model_path.parent),
    "models": [{
        "name": "onnx-resource",
        "version": "smoke",
        "sha256": "sha256:" + hashlib.sha256(payload).hexdigest(),
        "sizeBytes": len(payload),
        "required": True,
        "cachePath": str(model_path),
        "cacheStatus": "READY",
        "actualSha256": "sha256:" + hashlib.sha256(payload).hexdigest(),
        "actualSizeBytes": len(payload),
        "task": "onnx-smoke",
        "backend": "onnxruntime",
        "format": "onnx",
        "precision": "fp32",
        "license": "apache-2.0",
    }],
    "bytesBase64": "",
}), encoding="utf-8")
PY

scripts/doctruth-onnx-model-worker < "$REQUEST" > "$OUT"

python3 - "$OUT" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
assert payload["ok"] is True, payload
metrics = payload["metrics"]
assert metrics["inferenceWallMs"] > 0, metrics
assert metrics["wallMs"] >= metrics["inferenceWallMs"], metrics
assert metrics["inputSource"] == "synthetic_tensor", metrics
assert metrics["rssMb"] > 0, metrics
assert metrics["peakMemoryMb"] > 0, metrics
PY

echo "doctruth ONNX worker resource smoke passed"
