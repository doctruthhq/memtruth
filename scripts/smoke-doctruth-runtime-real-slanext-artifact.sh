#!/usr/bin/env sh
set -eu
export DOCTRUTH_ALLOW_PYTHON_ORACLE="${DOCTRUTH_ALLOW_PYTHON_ORACLE:-1}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ "${DOCTRUTH_RUNTIME_REAL_SLANEXT_SMOKE:-0}" != "1" ]; then
    echo "skipping Rust runtime real SLANeXT smoke; set DOCTRUTH_RUNTIME_REAL_SLANEXT_SMOKE=1 with PaddleOCR installed"
    exit 0
fi

WORK_DIR="${DOCTRUTH_RUNTIME_REAL_SLANEXT_SMOKE_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/doctruth-runtime-real-slanext.XXXXXX")}"

if [ -n "${DOCTRUTH_SLANEXT_PYTHON:-}" ]; then
    PYTHON="$DOCTRUTH_SLANEXT_PYTHON"
    if [ ! -x "$PYTHON" ]; then
        echo "DOCTRUTH_SLANEXT_PYTHON is not executable: $PYTHON" >&2
        exit 1
    fi
    PATH="$(dirname "$PYTHON"):$PATH"
    export PATH
elif [ -n "${DOCTRUTH_SLANEXT_VENV:-}" ]; then
    VENV="$DOCTRUTH_SLANEXT_VENV"
    PYTHON="$VENV/bin/python"
    if [ ! -x "$PYTHON" ]; then
        echo "DOCTRUTH_SLANEXT_VENV does not contain an executable Python: $PYTHON" >&2
        exit 1
    fi
    PATH="$VENV/bin:$PATH"
    export PATH
elif command -v python3.10 >/dev/null 2>&1; then
    VENV="${DOCTRUTH_RUNTIME_REAL_SLANEXT_VENV:-$WORK_DIR/venv}"
    if [ ! -x "$VENV/bin/python" ]; then
        python3.10 -m venv "$VENV"
        "$VENV/bin/python" -m pip install --upgrade pip setuptools wheel
        "$VENV/bin/python" -m pip install \
            "${DOCTRUTH_SLANEXT_PADDLE_PACKAGE:-paddlepaddle}" \
            "${DOCTRUTH_SLANEXT_PADDLEOCR_PACKAGE:-paddleocr}"
    fi
    PYTHON="$VENV/bin/python"
    PATH="$VENV/bin:$PATH"
    export PATH
elif command -v python3 >/dev/null 2>&1; then
    PYTHON="$(command -v python3)"
else
    echo "python3 is required for Rust runtime real SLANeXT smoke" >&2
    exit 1
fi

MANIFEST_PATH="runtime/doctruth-runtime/Cargo.toml"
BIN="runtime/doctruth-runtime/target/debug/doctruth-runtime"
if [ ! -x "$BIN" ]; then
    cargo build --manifest-path "$MANIFEST_PATH" >/dev/null
fi

CACHE="${DOCTRUTH_RUNTIME_REAL_SLANEXT_CACHE:-$WORK_DIR/model-cache}"
MODEL_MANIFEST="${DOCTRUTH_RUNTIME_REAL_SLANEXT_MANIFEST:-$WORK_DIR/models.json}"
MODEL_BYTES="$CACHE/slanext-wired-paddleocr-runtime.bin"
PDF="$WORK_DIR/slanext-table.pdf"
REPORT="$WORK_DIR/report.json"
mkdir -p "$WORK_DIR" "$CACHE"

scripts/doctruth-slanext-table-worker --doctor > "$WORK_DIR/slanext-doctor.json"
"$PYTHON" - "$WORK_DIR/slanext-doctor.json" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
if payload.get("ok") is not True:
    print("SLANeXT worker doctor failed: " + json.dumps(payload, separators=(",", ":")), file=sys.stderr)
    raise SystemExit(1)
if payload.get("runtime") != "paddleocr-slanext":
    print("unexpected SLANeXT worker runtime: " + json.dumps(payload, separators=(",", ":")), file=sys.stderr)
    raise SystemExit(1)
PY

SOURCE_HASH="$("$PYTHON" - "$PDF" "$MODEL_MANIFEST" "$MODEL_BYTES" <<'PY'
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

payload = b"paddleocr-managed-slanext-runtime"
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
            "license": "external-model-license",
        }]
    }
}, separators=(",", ":")), encoding="utf-8")
print("sha256:" + hashlib.sha256(raw).hexdigest())
PY
)"

DOCTRUTH_MODEL_MANIFEST="$MODEL_MANIFEST" \
DOCTRUTH_MODEL_CACHE="$CACHE" \
DOCTRUTH_RUNTIME_MODEL_COMMAND="$ROOT/scripts/doctruth-slanext-table-worker" \
    "$BIN" <<EOF_REQUEST > "$REPORT"
{"command":"parse_pdf","source_path":"$PDF","source_hash":"$SOURCE_HASH","preset":"table-server","offline_mode":false,"allow_model_downloads":true}
EOF_REQUEST

"$PYTHON" - "$REPORT" <<'PY'
import json
import pathlib
import sys

doc = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
parser = doc["parserRun"]
assert parser["backend"] == "rust-sidecar+model-worker", parser
assert parser.get("workerBackend") == "pdfbox+model-worker", parser
assert parser["runtime"] == "doctruth-runtime", parser
assert parser["preset"] == "table-server", parser
assert parser["models"] == ["slanext-wired:paddleocr-runtime"], parser
assert doc["auditGradeStatus"] == "AUDIT_GRADE", doc
assert doc["body"]["tables"], doc["body"]
assert any(unit["kind"] == "TABLE_CELL" for unit in doc["body"]["units"]), doc["body"]["units"]
PY

echo "doctruth Rust runtime real SLANeXT smoke passed"
