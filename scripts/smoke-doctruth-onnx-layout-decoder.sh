#!/usr/bin/env sh
set -eu
export DOCTRUTH_ALLOW_PYTHON_ORACLE="${DOCTRUTH_ALLOW_PYTHON_ORACLE:-1}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

mvn -q -DskipTests package

JAVA_BIN="${JAVA_HOME:-}/bin/java"
if [ ! -x "$JAVA_BIN" ] && [ -x /opt/homebrew/opt/openjdk/bin/java ]; then
    JAVA_BIN=/opt/homebrew/opt/openjdk/bin/java
fi
if [ ! -x "$JAVA_BIN" ]; then
    JAVA_BIN=java
fi

CLI_JAR="$(find target -maxdepth 1 -name 'doctruth-java-*-all.jar' | sort | tail -1)"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-onnx-layout-decoder.XXXXXX")"
PDF="$WORK_DIR/layout-worker.pdf"
MODEL="$WORK_DIR/layout-like.onnx"
MANIFEST="$WORK_DIR/models.json"
CACHE="$WORK_DIR/model-cache"
OUT="$WORK_DIR/layout-worker-output.json"
mkdir -p "$CACHE"

python3 - "$PDF" <<'PY'
import sys

path = sys.argv[1]
stream = "BT\n/F1 18 Tf\n72 720 Td\n(Layout worker source) Tj\nET\n"
objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    f"<< /Length {len(stream.encode())} >>\nstream\n{stream}endstream",
]
raw = bytearray(b"%PDF-1.4\n")
offsets = []
for i, obj in enumerate(objects, start=1):
    offsets.append(len(raw))
    raw.extend(f"{i} 0 obj\n{obj}\nendobj\n".encode())
xref = len(raw)
raw.extend(f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode())
for offset in offsets:
    raw.extend(f"{offset:010} 00000 n \n".encode())
raw.extend(f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
with open(path, "wb") as handle:
    handle.write(raw)
PY

python3 - "$MODEL" "$MANIFEST" <<'PY'
import hashlib
import json
import pathlib
import sys

import onnx
from onnx import TensorProto, helper

model_path = pathlib.Path(sys.argv[1])
manifest_path = pathlib.Path(sys.argv[2])
logits = helper.make_tensor(
    "logits_tensor",
    TensorProto.FLOAT,
    [1, 2, 4],
    [6.0, 0.1, 0.1, 0.0, 0.1, 6.0, 0.1, 0.0],
)
boxes = helper.make_tensor(
    "boxes_tensor",
    TensorProto.FLOAT,
    [1, 2, 4],
    [0.5, 0.18, 0.7, 0.12, 0.5, 0.48, 0.8, 0.32],
)
logits_node = helper.make_node("Constant", [], ["pred_logits"], value=logits)
boxes_node = helper.make_node("Constant", [], ["pred_boxes"], value=boxes)
logits_out = helper.make_tensor_value_info("pred_logits", TensorProto.FLOAT, [1, 2, 4])
boxes_out = helper.make_tensor_value_info("pred_boxes", TensorProto.FLOAT, [1, 2, 4])
graph = helper.make_graph([logits_node, boxes_node], "doctruth-layout-like", [], [logits_out, boxes_out])
model = helper.make_model(graph, producer_name="doctruth-smoke")
model.ir_version = 10
for opset in model.opset_import:
    opset.version = 21
onnx.checker.check_model(model)
onnx.save(model, model_path)
payload = model_path.read_bytes()
manifest_path.write_text(json.dumps({
    "presets": {
        "standard": [{
            "name": "layout-rtdetr-like",
            "version": "smoke",
            "source": str(model_path),
            "sha256": "sha256:" + hashlib.sha256(payload).hexdigest(),
            "sizeBytes": len(payload),
            "required": True,
            "task": "layout-detection",
            "backend": "onnxruntime",
            "format": "onnx",
            "precision": "fp32",
            "license": "apache-2.0",
        }]
    }
}, indent=2), encoding="utf-8")
PY

"$JAVA_BIN" -jar "$CLI_JAR" cache warm "$MANIFEST" --preset standard --cache "$CACHE" --json > "$WORK_DIR/cache.json"

"$JAVA_BIN" -Ddoctruth.model.command="$ROOT/scripts/doctruth-onnx-model-worker" \
    -Ddoctruth.model.cache="$CACHE" \
    -Ddoctruth.model.manifest="$MANIFEST" \
    -jar "$CLI_JAR" parse "$PDF" --format json --preset standard -o "$OUT" > "$WORK_DIR/parse.out"

python3 - "$OUT" <<'PY'
import json
import pathlib
import sys

doc = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
assert doc["parserRun"]["backend"] == "rust-sidecar+model-worker", doc["parserRun"]
assert doc["parserRun"]["models"] == ["layout-rtdetr-like:smoke"], doc["parserRun"]
units = doc["body"]["units"]
assert [unit["kind"] for unit in units] == ["TEXT_BLOCK", "TEXT_BLOCK"], units
assert [unit["text"] for unit in units] == ["model heading region", "model body region"], units
assert units[0]["location"]["readingOrder"] == 1, units[0]
assert units[1]["location"]["readingOrder"] == 2, units[1]
assert units[0]["location"]["boundingBox"] == {"x0": 150.0, "y0": 120.0, "x1": 850.0, "y1": 240.0}, units[0]
assert units[1]["location"]["boundingBox"] == {"x0": 100.0, "y0": 320.0, "x1": 900.0, "y1": 640.0}, units[1]
assert units[0]["sourceObjectId"] == "onnx:layout-rtdetr-like:smoke#layout-0001", units[0]
assert units[0]["confidence"]["score"] > 0.99, units[0]
assert doc["body"]["tables"] == [], doc["body"]["tables"]
assert doc["auditGradeStatus"] == "AUDIT_GRADE", doc["auditGradeStatus"]
PY

echo "doctruth ONNX layout decoder smoke passed"
