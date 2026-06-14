#!/usr/bin/env sh
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ -z "${DOCTRUTH_REAL_MODEL_MANIFEST:-}" ]; then
    echo "skipping real model artifact smoke: DOCTRUTH_REAL_MODEL_MANIFEST is not set"
    exit 0
fi

PRESET="${DOCTRUTH_REAL_MODEL_PRESET:-standard}"
EXPECTED_MODEL="${DOCTRUTH_REAL_MODEL_EXPECTED_ID:-}"
EXPECTED_TASK="${DOCTRUTH_REAL_MODEL_EXPECTED_TASK:-}"
CACHE="${DOCTRUTH_REAL_MODEL_CACHE:-target/real-model-cache}"
WORK_DIR="${DOCTRUTH_REAL_MODEL_SMOKE_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/doctruth-real-model-artifact.XXXXXX")}"
PDF="$WORK_DIR/real-model-smoke.pdf"
OUT="$WORK_DIR/real-model-output.json"
DOCTOR_OUT="$WORK_DIR/onnx-doctor.json"

mkdir -p "$CACHE" "$WORK_DIR"

mvn -q -DskipTests package

JAVA_BIN="${JAVA_HOME:-}/bin/java"
if [ ! -x "$JAVA_BIN" ] && [ -x /opt/homebrew/opt/openjdk/bin/java ]; then
    JAVA_BIN=/opt/homebrew/opt/openjdk/bin/java
fi
if [ ! -x "$JAVA_BIN" ]; then
    JAVA_BIN=java
fi

CLI_JAR="$(find target -maxdepth 1 -name 'doctruth-java-*-all.jar' | sort | tail -1)"

scripts/doctruth-onnx-model-worker --doctor > "$DOCTOR_OUT"
python3 - "$DOCTOR_OUT" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
assert payload["ok"] is True, payload
assert payload["runtime"] == "onnxruntime", payload
assert payload["code"] == "ready", payload
PY

"$JAVA_BIN" -jar "$CLI_JAR" cache warm "$DOCTRUTH_REAL_MODEL_MANIFEST" \
  --preset "$PRESET" --cache "$CACHE" --json > "$WORK_DIR/cache.json"

if [ -n "${DOCTRUTH_REAL_MODEL_SOURCE_PDF:-}" ]; then
    cp "$DOCTRUTH_REAL_MODEL_SOURCE_PDF" "$PDF"
else
python3 - "$PDF" <<'PY'
import sys

path = sys.argv[1]
text = "Real model artifact smoke source."
stream = f"BT\n/F1 18 Tf\n72 720 Td\n({text}) Tj\nET\n"
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
fi

"$JAVA_BIN" \
  -Ddoctruth.model.command="$ROOT/scripts/doctruth-onnx-model-worker" \
  -Ddoctruth.model.cache="$CACHE" \
  -Ddoctruth.model.manifest="$DOCTRUTH_REAL_MODEL_MANIFEST" \
  -jar "$CLI_JAR" parse "$PDF" --format json --preset "$PRESET" -o "$OUT" > "$WORK_DIR/parse.out"

python3 - "$OUT" "$PRESET" "$EXPECTED_MODEL" "$EXPECTED_TASK" <<'PY'
import json
import pathlib
import sys

doc = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
preset = sys.argv[2]
expected_model = sys.argv[3]
expected_task = sys.argv[4]
parser = doc["parserRun"]
assert parser["backend"] == "rust-sidecar+model-worker", parser
assert parser["preset"] == preset, parser
assert parser["models"], parser
if expected_model:
    assert expected_model in parser["models"], parser
payload = json.dumps(doc, ensure_ascii=False)
if expected_task == "layout-detection":
    assert doc["body"]["units"], doc["body"]
    assert any(unit["kind"] == "TEXT_BLOCK" for unit in doc["body"]["units"]), doc["body"]["units"]
elif expected_task == "table-structure-recognition":
    assert doc["body"]["tables"], doc["body"]
    assert any(unit["kind"] == "TABLE_CELL" for unit in doc["body"]["units"]), doc["body"]["units"]
else:
    assert "ONNX inference succeeded" in payload or doc["body"]["units"] or doc["body"]["tables"], doc
assert doc["auditGradeStatus"] in {"AUDIT_GRADE", "NOT_AUDIT_GRADE", "UNKNOWN"}, doc["auditGradeStatus"]
PY

echo "doctruth real model artifact smoke passed"
