#!/usr/bin/env sh
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

MANIFEST="runtime/doctruth-runtime/Cargo.toml"
cargo build --manifest-path "$MANIFEST" >/dev/null
BIN="runtime/doctruth-runtime/target/debug/doctruth-runtime"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-runtime-ocr.XXXXXX")"
PDF="$WORK_DIR/scanned.pdf"
REPORT="$WORK_DIR/report.json"
PYTHON_MODULES="$WORK_DIR/python"
MODEL_CACHE="$WORK_DIR/model-cache"
MODEL_MANIFEST="$WORK_DIR/models.json"
MODEL_BYTES="$MODEL_CACHE/ocr-router-v1.bin"
mkdir -p "$PYTHON_MODULES/rapidocr" "$MODEL_CACHE"

cat > "$PYTHON_MODULES/rapidocr/__init__.py" <<'PY'
class RapidOCR:
    def __call__(self, path):
        return [
            [
                [[42, 70], [620, 70], [620, 140], [42, 140]],
                ("Runtime OCR recovered evidence", 0.94),
            ]
        ]
PY

python3 - "$PDF" "$MODEL_MANIFEST" "$MODEL_BYTES" <<'PY'
import hashlib
import json
import pathlib
import sys

pdf = pathlib.Path(sys.argv[1])
manifest = pathlib.Path(sys.argv[2])
model = pathlib.Path(sys.argv[3])
objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> >>",
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
payload = b"runtime-ocr-router"
model.write_bytes(payload)
manifest.write_text(json.dumps({
    "presets": {
        "ocr": [{
            "name": "ocr-router",
            "version": "v1",
            "source": str(model),
            "sha256": "sha256:" + hashlib.sha256(payload).hexdigest(),
            "sizeBytes": len(payload),
            "required": True,
            "task": "ocr",
            "backend": "rapidocr",
            "format": "worker",
            "precision": "runtime",
            "license": "test",
        }]
    }
}, separators=(",", ":")), encoding="utf-8")
print("sha256:" + hashlib.sha256(raw).hexdigest())
PY

SOURCE_HASH="$(python3 - "$PDF" <<'PY'
import hashlib
import pathlib
import sys
print("sha256:" + hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())
PY
)"

PYTHONPATH="$PYTHON_MODULES" \
DOCTRUTH_MODEL_MANIFEST="$MODEL_MANIFEST" \
DOCTRUTH_MODEL_CACHE="$MODEL_CACHE" \
DOCTRUTH_RUNTIME_MODEL_COMMAND="$ROOT/scripts/doctruth-rapidocr-mnn-worker" \
    "$BIN" <<EOF_REQUEST > "$REPORT"
{"command":"parse_pdf","source_path":"$PDF","source_hash":"$SOURCE_HASH","preset":"ocr","offline_mode":true,"allow_model_downloads":false}
EOF_REQUEST

python3 - "$REPORT" <<'PY'
import json
import pathlib
import sys

doc = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
parser = doc["parserRun"]
assert parser["backend"] == "rust-sidecar+model-worker", parser
assert parser.get("workerBackend") == "rapidocr-worker", parser
assert parser["runtime"] == "doctruth-runtime", parser
assert parser["preset"] == "ocr", parser
assert "ocr-router:v1" in parser["models"], parser
assert doc["auditGradeStatus"] == "AUDIT_GRADE", doc
units = doc["body"]["units"]
assert units[0]["kind"] == "OCR_REGION", units
assert units[0]["text"] == "Runtime OCR recovered evidence", units
assert units[0]["location"]["boundingBox"]["x0"] < units[0]["location"]["boundingBox"]["x1"], units
PY

echo "doctruth runtime OCR worker smoke passed"
