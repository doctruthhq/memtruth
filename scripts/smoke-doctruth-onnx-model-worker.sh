#!/usr/bin/env sh
set -eu

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
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-onnx-model-worker-smoke.XXXXXX")"
PDF="$WORK_DIR/onnx-worker.pdf"
MODEL="$WORK_DIR/identity.onnx"
MANIFEST="$WORK_DIR/models.json"
CACHE="$WORK_DIR/model-cache"
DOCTOR_OUT="$WORK_DIR/doctor.json"
OUT="$WORK_DIR/onnx-worker-output.json"
mkdir -p "$CACHE"

python3 - "$PDF" <<'PY'
import sys

path = sys.argv[1]
stream = "BT\n/F1 18 Tf\n72 720 Td\n(ONNX worker source) Tj\nET\n"
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
input_info = helper.make_tensor_value_info("input", TensorProto.FLOAT, [1, 1])
output_info = helper.make_tensor_value_info("output", TensorProto.FLOAT, [1, 1])
node = helper.make_node("Identity", ["input"], ["output"])
graph = helper.make_graph([node], "doctruth-smoke-identity", [input_info], [output_info])
model = helper.make_model(graph, producer_name="doctruth-smoke")
model.ir_version = 10
for opset in model.opset_import:
    opset.version = 21
onnx.checker.check_model(model)
onnx.save(model, model_path)
payload = model_path.read_bytes()
manifest_path.write_text(json.dumps({
    "presets": {
        "table-lite": [{
            "name": "onnx-identity",
            "version": "smoke",
            "source": str(model_path),
            "sha256": "sha256:" + hashlib.sha256(payload).hexdigest(),
            "sizeBytes": len(payload),
            "required": True,
            "task": "onnx-smoke",
            "backend": "onnxruntime",
            "format": "onnx",
            "precision": "fp32",
            "license": "apache-2.0",
        }]
    }
}, indent=2), encoding="utf-8")
PY

"$JAVA_BIN" -jar "$CLI_JAR" cache warm "$MANIFEST" --preset table-lite --cache "$CACHE" --json > "$WORK_DIR/cache.json"

scripts/doctruth-onnx-model-worker --doctor > "$DOCTOR_OUT"

python3 - "$DOCTOR_OUT" <<'PY'
import json
import pathlib
import sys

doc = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
assert doc["ok"] is True
assert doc["runtime"] == "onnxruntime"
assert len(doc["providers"]) >= 1
PY

"$JAVA_BIN" -Ddoctruth.model.command="$ROOT/scripts/doctruth-onnx-model-worker" \
    -Ddoctruth.model.cache="$CACHE" \
    -Ddoctruth.model.manifest="$MANIFEST" \
    -jar "$CLI_JAR" parse "$PDF" --format json --preset table-lite -o "$OUT" > "$WORK_DIR/parse.out"

python3 - "$OUT" <<'PY'
import json
import pathlib
import sys

doc = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
assert doc["parserRun"]["backend"] == "rust-sidecar+model-worker"
assert doc["parserRun"]["models"] == ["onnx-identity:smoke"]
assert doc["body"]["units"][0]["text"] == "ONNX inference succeeded"
assert doc["body"]["units"][0]["confidence"]["score"] == 1.0
assert doc["auditGradeStatus"] == "AUDIT_GRADE"
PY

echo "doctruth ONNX model worker smoke passed"
