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
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-model-worker-smoke.XXXXXX")"
PDF="$WORK_DIR/model-worker.pdf"
WORKER="$WORK_DIR/fake-model-worker"
MODEL_CACHE="$WORK_DIR/model-cache"
MODEL_MANIFEST="$WORK_DIR/models.json"
DOCTOR_OUT="$WORK_DIR/doctor.json"
OUT="$WORK_DIR/table-lite.json"
mkdir -p "$MODEL_CACHE"

python3 - "$PDF" <<'PY'
import sys

path = sys.argv[1]
stream = "BT\n/F1 18 Tf\n72 720 Td\n(Model worker source) Tj\nET\n"
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

python3 - "$MODEL_CACHE" "$MODEL_MANIFEST" <<'PY'
import hashlib
import json
import pathlib
import sys

cache = pathlib.Path(sys.argv[1])
manifest = pathlib.Path(sys.argv[2])
artifact = cache / "slanet-plus-local-smoke.bin"
payload = b"local smoke model artifact"
artifact.write_bytes(payload)
sha = "sha256:" + hashlib.sha256(payload).hexdigest()
manifest.write_text(json.dumps({
    "presets": {
        "table-lite": [{
            "name": "slanet-plus",
            "version": "local-smoke",
            "sha256": sha,
            "sizeBytes": len(payload),
            "required": True,
            "task": "table-structure",
            "backend": "onnxruntime",
            "format": "onnx",
            "precision": "int8",
            "license": "apache-2.0",
        }]
    }
}, indent=2), encoding="utf-8")
PY

cat > "$WORKER" <<'PY'
#!/usr/bin/env python3
import hashlib
import json
import pathlib
import sys

if len(sys.argv) > 1 and sys.argv[1] == "--doctor":
    print(json.dumps({
        "ok": True,
        "engine": "onnxruntime",
        "message": "fake model worker ready",
        "loadedModels": ["slanet-plus:local-smoke"],
        "rssMb": 64,
        "peakMemoryMb": 256
    }))
    sys.exit(0)

request = json.loads(sys.stdin.read())
assert request["preset"] == "table-lite"
assert request["models"][0]["name"] == "slanet-plus"
assert pathlib.Path(request["modelCacheDirectory"]).exists()
assert request["models"][0]["version"] == "local-smoke"
assert request["models"][0]["task"] == "table-structure"
assert request["models"][0]["backend"] == "onnxruntime"
assert request["models"][0]["format"] == "onnx"
assert request["models"][0]["precision"] == "int8"
assert request["models"][0]["license"] == "apache-2.0"
assert request["models"][0]["cachePath"].endswith("slanet-plus-local-smoke.bin")
assert pathlib.Path(request["models"][0]["cachePath"]).parent.resolve() == pathlib.Path(request["modelCacheDirectory"]).resolve()
artifact = pathlib.Path(request["models"][0]["cachePath"]).read_bytes()
assert request["models"][0]["cacheStatus"] == "READY"
assert request["models"][0]["actualSha256"] == "sha256:" + hashlib.sha256(artifact).hexdigest()
assert request["models"][0]["actualSizeBytes"] == len(artifact)
source = pathlib.Path(request["sourcePath"]).name

def bbox(x0, y0, x1, y1):
    return {"x0": x0, "y0": y0, "x1": x1, "y1": y1}

def confidence():
    return {"score": 0.97, "rationale": "fake model worker"}

def unit(unit_id, text, row, col, x0, y0, x1, y1):
    return {
        "unitId": unit_id,
        "kind": "TABLE_CELL",
        "page": 1,
        "text": text,
        "evidenceSpanIds": [unit_id + "-span"],
        "location": {"page": 1, "readingOrder": row * 10 + col, "boundingBox": bbox(x0, y0, x1, y1)},
        "sourceObjectId": "model-table-1",
        "confidence": confidence(),
        "warnings": [],
    }

def cell(cell_id, text, row, col, x0, y0, x1, y1):
    return {
        "cellId": cell_id,
        "rowRange": {"start": row, "end": row},
        "columnRange": {"start": col, "end": col},
        "boundingBox": bbox(x0, y0, x1, y1),
        "text": text,
    }

payload = {
    "ok": True,
    "document": {
        "docId": request["sourceHash"],
        "source": {
            "sourceFilename": source,
            "sourceHash": request["sourceHash"],
            "metadata": {"sourceFilename": source, "pageCount": 1},
        },
        "body": {
            "pages": [{
                "pageNumber": 1,
                "width": 612,
                "height": 792,
                "textLayerAvailable": True,
                "imageHash": "sha256:model-page"
            }],
            "units": [
                unit("unit-1", "Name", 1, 1, 100, 100, 220, 150),
                unit("unit-2", "Score", 1, 2, 220, 100, 340, 150),
                unit("unit-3", "Alex", 2, 1, 100, 150, 220, 200),
                unit("unit-4", "98", 2, 2, 220, 150, 340, 200),
            ],
            "tables": [{
                "tableId": "model-table-1",
                "pageNumber": 1,
                "boundingBox": bbox(100, 100, 340, 200),
                "confidence": confidence(),
                "cells": [
                    cell("cell-1", "Name", 1, 1, 100, 100, 220, 150),
                    cell("cell-2", "Score", 1, 2, 220, 100, 340, 150),
                    cell("cell-3", "Alex", 2, 1, 100, 150, 220, 200),
                    cell("cell-4", "98", 2, 2, 220, 150, 340, 200),
                ],
            }],
        },
        "parserRun": {
            "parserVersion": "1.0.0",
            "preset": "table-lite",
            "backend": "pdfbox+model-worker",
            "models": ["slanet-plus:local-smoke"],
            "warnings": [],
        },
        "auditGradeStatus": "UNKNOWN",
    }
}
print(json.dumps(payload))
PY
chmod +x "$WORKER"

DOCTRUTH_MODEL_COMMAND="$WORKER" DOCTRUTH_MODEL_TIMEOUT_MS=3456 \
    DOCTRUTH_MODEL_CACHE="$MODEL_CACHE" DOCTRUTH_MODEL_MANIFEST="$MODEL_MANIFEST" \
    "$JAVA_BIN" -jar "$CLI_JAR" \
    doctor --json > "$DOCTOR_OUT"

python3 - "$DOCTOR_OUT" "$WORKER" <<'PY'
import json
import pathlib
import sys

doc = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
worker = doc["models"]["worker"]
assert doc["models"]["requiredModels"] == 1
assert doc["models"]["allReady"] is True
assert len(doc["models"]["artifacts"]) == 1
artifact = doc["models"]["artifacts"][0]
assert artifact["identity"] == "slanet-plus:local-smoke"
assert artifact["status"] == "READY"
assert artifact["actualSha256"].startswith("sha256:")
assert artifact["actualSizeBytes"] > 0
assert artifact["task"] == "table-structure"
assert artifact["backend"] == "onnxruntime"
assert artifact["format"] == "onnx"
assert artifact["precision"] == "int8"
assert artifact["license"] == "apache-2.0"
assert pathlib.Path(worker["command"]).resolve() == pathlib.Path(sys.argv[2]).resolve()
assert worker["available"] is True
assert worker["ready"] is True
assert worker["timeoutMs"] == 3456
assert worker["statusCode"] == "ready"
assert worker["rssMb"] == 64
assert worker["peakMemoryMb"] == 256
assert worker["loadedModels"] == ["slanet-plus:local-smoke"]
PY

"$JAVA_BIN" -Ddoctruth.model.command="$WORKER" -Ddoctruth.model.cache="$MODEL_CACHE" \
    -Ddoctruth.model.manifest="$MODEL_MANIFEST" -jar "$CLI_JAR" \
    parse "$PDF" --format json --preset table-lite -o "$OUT" > "$WORK_DIR/parse.out"

python3 - "$OUT" <<'PY'
import json
import pathlib
import sys

doc = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
assert doc["parserRun"]["preset"] == "table-lite"
assert doc["parserRun"]["backend"] == "pdfbox+model-worker"
assert doc["parserRun"]["models"] == ["slanet-plus:local-smoke"]
assert doc["parserRun"]["warnings"] == []
assert doc["auditGradeStatus"] == "AUDIT_GRADE"
assert doc["body"]["tables"][0]["tableId"] == "model-table-1"
assert [cell["text"] for cell in doc["body"]["tables"][0]["cells"]] == ["Name", "Score", "Alex", "98"]
assert len([unit for unit in doc["body"]["units"] if unit["kind"] == "TABLE_CELL"]) == 4
PY

echo "doctruth model worker smoke passed"
