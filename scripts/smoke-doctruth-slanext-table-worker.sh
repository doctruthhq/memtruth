#!/usr/bin/env sh
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-slanext-worker.XXXXXX")"
FAKE_MODULE_DIR="$WORK_DIR/python"
PDF="$WORK_DIR/table.pdf"
REQUEST="$WORK_DIR/request.json"
DIRECT_OUT="$WORK_DIR/direct.json"
MODEL_CACHE="$WORK_DIR/model-cache"
MODEL_MANIFEST="$WORK_DIR/models.json"
CLI_OUT="$WORK_DIR/table-server.json"
mkdir -p "$FAKE_MODULE_DIR/paddleocr" "$MODEL_CACHE"

cat > "$FAKE_MODULE_DIR/paddleocr/__init__.py" <<'PY'
__version__ = "fake-slanext"

class TableStructureRecognition:
    def __init__(self, model_name="SLANeXt_wired"):
        self.model_name = model_name

    def predict(self, image_path):
        assert image_path.endswith(".png")
        return [{
            "cells": [
                {"text": "Name", "row": 0, "column": 0, "bbox": [100, 100, 220, 150], "confidence": 0.96},
                {"text": "Score", "row": 0, "column": 1, "bbox": [220, 100, 340, 150], "confidence": 0.95},
                {"text": "Ada", "row": 1, "column": 0, "bbox": [100, 150, 220, 200], "confidence": 0.94},
                {"text": "98", "row": 1, "column": 1, "bbox": [220, 150, 340, 200], "confidence": 0.93},
            ]
        }]
PY

PYTHONPATH="$FAKE_MODULE_DIR" scripts/doctruth-slanext-table-worker --doctor > "$WORK_DIR/doctor.json"
python3 - "$WORK_DIR/doctor.json" <<'PY'
import json
import pathlib
import sys
payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
assert payload["ok"] is True, payload
assert payload["runtime"] == "paddleocr-slanext", payload
assert payload["code"] == "ready", payload
PY

python3 - "$PDF" "$REQUEST" "$MODEL_CACHE" "$MODEL_MANIFEST" <<'PY'
import base64
import hashlib
import json
import pathlib
import sys

pdf = pathlib.Path(sys.argv[1])
request = pathlib.Path(sys.argv[2])
cache = pathlib.Path(sys.argv[3])
manifest = pathlib.Path(sys.argv[4])
stream = "\n".join([
    "1 w",
    "72 648 m 540 648 l S",
    "72 576 m 540 576 l S",
    "72 504 m 540 504 l S",
    "72 504 m 72 648 l S",
    "306 504 m 306 648 l S",
    "540 504 m 540 648 l S",
    "BT /F1 14 Tf 96 615 Td (Name) Tj ET",
    "BT /F1 14 Tf 330 615 Td (Score) Tj ET",
    "BT /F1 14 Tf 96 543 Td (Ada) Tj ET",
    "BT /F1 14 Tf 330 543 Td (98) Tj ET",
]) + "\n"
objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    f"<< /Length {len(stream.encode())} >>\nstream\n{stream}endstream",
]
raw = bytearray(b"%PDF-1.4\n")
offsets = []
for index, obj in enumerate(objects, start=1):
    offsets.append(len(raw))
    raw.extend(f"{index} 0 obj\n{obj}\nendobj\n".encode())
xref = len(raw)
raw.extend(f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode())
for offset in offsets:
    raw.extend(f"{offset:010} 00000 n \n".encode())
raw.extend(f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
pdf.write_bytes(raw)
artifact = cache / "slanext-wired-local-smoke.bin"
payload = b"fake slanext model"
artifact.write_bytes(payload)
sha = "sha256:" + hashlib.sha256(payload).hexdigest()
manifest.write_text(json.dumps({
    "presets": {
        "table-server": [{
            "name": "slanext-wired",
            "version": "local-smoke",
            "source": str(artifact),
            "sha256": sha,
            "sizeBytes": len(payload),
            "required": True,
            "task": "table-structure-recognition",
            "backend": "paddleocr",
            "format": "paddle",
            "precision": "fp32",
            "license": "apache-2.0"
        }]
    }
}, indent=2), encoding="utf-8")
request.write_text(json.dumps({
    "version": 1,
    "preset": "table-server",
    "sourcePath": str(pdf),
    "sourceFilename": pdf.name,
    "sourceHash": "sha256:" + hashlib.sha256(raw).hexdigest(),
    "modelCacheDirectory": str(cache),
    "models": [{
        "name": "slanext-wired",
        "version": "local-smoke",
        "sha256": sha,
        "sizeBytes": len(payload),
        "required": True,
        "cachePath": str(artifact),
        "cacheStatus": "READY",
        "actualSha256": sha,
        "actualSizeBytes": len(payload),
        "task": "table-structure-recognition",
        "backend": "paddleocr",
        "format": "paddle",
        "precision": "fp32",
        "license": "apache-2.0"
    }],
    "bytesBase64": base64.b64encode(raw).decode("ascii")
}, separators=(",", ":")), encoding="utf-8")
PY

PYTHONPATH="$FAKE_MODULE_DIR" scripts/doctruth-slanext-table-worker < "$REQUEST" > "$DIRECT_OUT"
python3 - "$DIRECT_OUT" <<'PY'
import json
import pathlib
import sys
payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
assert payload["ok"] is True, payload
doc = payload["document"]
assert doc["parserRun"]["models"] == ["slanext-wired:local-smoke"], doc["parserRun"]
assert doc["body"]["tables"][0]["cells"][3]["text"] == "98", doc["body"]["tables"]
assert doc["body"]["units"][0]["kind"] == "TABLE_CELL", doc["body"]["units"]
assert doc["auditGradeStatus"] == "AUDIT_GRADE", doc["auditGradeStatus"]
PY

mvn -q -DskipTests package
JAVA_BIN="${JAVA_HOME:-}/bin/java"
if [ ! -x "$JAVA_BIN" ] && [ -x /opt/homebrew/opt/openjdk/bin/java ]; then
    JAVA_BIN=/opt/homebrew/opt/openjdk/bin/java
fi
if [ ! -x "$JAVA_BIN" ]; then
    JAVA_BIN=java
fi
CLI_JAR="$(find target -maxdepth 1 -name 'doctruth-java-*-all.jar' | sort | tail -1)"
"$JAVA_BIN" -jar "$CLI_JAR" cache warm "$MODEL_MANIFEST" --preset table-server --cache "$MODEL_CACHE" --json > "$WORK_DIR/cache.json"

PYTHONPATH="$FAKE_MODULE_DIR" "$JAVA_BIN" \
  -Ddoctruth.model.command="$ROOT/scripts/doctruth-slanext-table-worker" \
  -Ddoctruth.model.cache="$MODEL_CACHE" \
  -Ddoctruth.model.manifest="$MODEL_MANIFEST" \
  -jar "$CLI_JAR" parse "$PDF" --format json --preset table-server -o "$CLI_OUT" > "$WORK_DIR/parse.out"

python3 - "$CLI_OUT" <<'PY'
import json
import pathlib
import sys
doc = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
assert doc["parserRun"]["backend"] == "pdfbox+model-worker", doc["parserRun"]
assert doc["parserRun"]["models"] == ["slanext-wired:local-smoke"], doc["parserRun"]
assert doc["body"]["tables"][0]["cells"][0]["rowRange"] == {"start": 0, "end": 0}
assert doc["body"]["tables"][0]["cells"][3]["columnRange"] == {"start": 1, "end": 1}
assert doc["body"]["units"][3]["text"] == "98", doc["body"]["units"]
PY

echo "doctruth SLANeXT table worker smoke passed"
