#!/usr/bin/env sh
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ "${DOCTRUTH_REAL_SLANEXT_SMOKE:-0}" != "1" ]; then
    echo "skipping real SLANeXT smoke; set DOCTRUTH_REAL_SLANEXT_SMOKE=1 with PaddleOCR installed"
    exit 0
fi

if [ -n "${DOCTRUTH_SLANEXT_PYTHON:-}" ]; then
    PYTHON="$DOCTRUTH_SLANEXT_PYTHON"
    if [ ! -x "$PYTHON" ]; then
        echo "DOCTRUTH_SLANEXT_PYTHON is not executable: $PYTHON" >&2
        exit 1
    fi
    PATH="$(dirname "$PYTHON"):$PATH"
    export PATH
elif command -v python3 >/dev/null 2>&1; then
    PYTHON="$(command -v python3)"
else
    echo "python3 is required for real SLANeXT smoke" >&2
    exit 1
fi

WORK_DIR="${DOCTRUTH_REAL_SLANEXT_SMOKE_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/doctruth-real-slanext.XXXXXX")}"
MODEL_CACHE="$WORK_DIR/model-cache"
MODEL_MANIFEST="$WORK_DIR/models.json"
MODEL_MARKER="$MODEL_CACHE/slanext-wired-paddleocr-runtime.marker"
PDF="$WORK_DIR/slanext-table.pdf"
OUT="$WORK_DIR/slanext-output.json"
mkdir -p "$WORK_DIR" "$MODEL_CACHE"

scripts/doctruth-slanext-table-worker --doctor > "$WORK_DIR/slanext-doctor.json"
"$PYTHON" - "$WORK_DIR/slanext-doctor.json" <<'PY'
import json
import pathlib
import sys
payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
assert payload["ok"] is True, payload
assert payload["runtime"] == "paddleocr-slanext", payload
PY

"$PYTHON" - "$PDF" "$MODEL_MANIFEST" "$MODEL_MARKER" <<'PY'
import hashlib
import json
import pathlib
import sys

pdf = pathlib.Path(sys.argv[1])
manifest = pathlib.Path(sys.argv[2])
marker = pathlib.Path(sys.argv[3])
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
payload = b"paddleocr-managed-slanext-runtime"
marker.write_bytes(payload)
sha = "sha256:" + hashlib.sha256(payload).hexdigest()
manifest.write_text(json.dumps({
    "presets": {
        "table-server": [{
            "name": "slanext-wired",
            "version": "paddleocr-runtime",
            "source": str(marker),
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

"$JAVA_BIN" \
  -Ddoctruth.model.command="$ROOT/scripts/doctruth-slanext-table-worker" \
  -Ddoctruth.model.cache="$MODEL_CACHE" \
  -Ddoctruth.model.manifest="$MODEL_MANIFEST" \
  -jar "$CLI_JAR" parse "$PDF" --format json --preset table-server -o "$OUT" > "$WORK_DIR/parse.out"

"$PYTHON" - "$OUT" <<'PY'
import json
import pathlib
import sys
doc = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
assert doc["parserRun"]["backend"] == "rust-sidecar+model-worker", doc["parserRun"]
assert doc["parserRun"]["models"] == ["slanext-wired:paddleocr-runtime"], doc["parserRun"]
assert doc["body"]["tables"], doc["body"]
assert any(unit["kind"] == "TABLE_CELL" for unit in doc["body"]["units"]), doc["body"]["units"]
PY

echo "doctruth real SLANeXT smoke passed"
