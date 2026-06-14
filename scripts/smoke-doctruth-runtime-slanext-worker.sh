#!/usr/bin/env sh
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

MANIFEST="runtime/doctruth-runtime/Cargo.toml"
cargo build --manifest-path "$MANIFEST" >/dev/null
BIN="runtime/doctruth-runtime/target/debug/doctruth-runtime"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-runtime-slanext.XXXXXX")"
PDF="$WORK_DIR/table.pdf"
REPORT="$WORK_DIR/report.json"
PYTHON_MODULES="$WORK_DIR/python"
MODEL_CACHE="$WORK_DIR/model-cache"
MODEL_MANIFEST="$WORK_DIR/models.json"
MODEL_BYTES="$MODEL_CACHE/slanext-wired-paddleocr-runtime.bin"
mkdir -p "$PYTHON_MODULES/paddleocr" "$MODEL_CACHE"

cat > "$PYTHON_MODULES/paddleocr/__init__.py" <<'PY'
__version__ = "runtime-smoke"

class TableStructureRecognition:
    def __init__(self, model_name="SLANeXt_wired"):
        self.model_name = model_name

    def predict(self, path):
        return [{
            "res": {
                "bbox": [
                    [72, 72, 220, 120],
                    [220, 72, 368, 120],
                    [72, 120, 220, 168],
                    [220, 120, 368, 168],
                ],
                "structure": ["<tr>", "<td>", "<td>", "</tr>", "<tr>", "<td>", "<td>", "</tr>"],
                "structure_score": 0.96,
            }
        }]
PY

python3 - "$PDF" "$MODEL_MANIFEST" "$MODEL_BYTES" <<'PY'
import hashlib
import json
import pathlib
import sys

pdf = pathlib.Path(sys.argv[1])
manifest = pathlib.Path(sys.argv[2])
model = pathlib.Path(sys.argv[3])
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
payload = b"runtime-slanext-worker"
model.write_bytes(payload)
manifest.write_text(json.dumps({
    "presets": {
        "table-server": [{
            "name": "slanext-wired",
            "version": "paddleocr-runtime",
            "source": str(model),
            "sha256": "sha256:" + hashlib.sha256(payload).hexdigest(),
            "sizeBytes": len(payload),
            "required": True,
            "task": "table-structure-recognition",
            "backend": "paddleocr",
            "format": "paddle",
            "precision": "fp32",
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
DOCTRUTH_RUNTIME_MODEL_COMMAND="$ROOT/scripts/doctruth-slanext-table-worker" \
    "$BIN" <<EOF_REQUEST > "$REPORT"
{"command":"parse_pdf","source_path":"$PDF","source_hash":"$SOURCE_HASH","preset":"table-server","offline_mode":true,"allow_model_downloads":false}
EOF_REQUEST

python3 - "$REPORT" <<'PY'
import json
import pathlib
import sys

doc = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
parser = doc["parserRun"]
assert parser["backend"] == "rust-sidecar+model-worker", parser
assert parser.get("workerBackend") == "pdfbox+model-worker", parser
assert parser["runtime"] == "doctruth-runtime", parser
assert parser["preset"] == "table-server", parser
assert "slanext-wired:paddleocr-runtime" in parser["models"], parser
assert doc["auditGradeStatus"] == "AUDIT_GRADE", doc
assert doc["body"]["tables"], doc["body"]
assert any(unit["kind"] == "TABLE_CELL" for unit in doc["body"]["units"]), doc["body"]["units"]
PY

echo "doctruth runtime SLANeXT worker smoke passed"
