#!/usr/bin/env sh
set -eu
export DOCTRUTH_ALLOW_PYTHON_ORACLE="${DOCTRUTH_ALLOW_PYTHON_ORACLE:-1}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ "${DOCTRUTH_RUNTIME_REAL_OCR_CORPUS_SMOKE:-${DOCTRUTH_RUNTIME_REAL_OCR_CORPUS:-0}}" != "1" ]; then
    echo "skipping Rust runtime real OCR corpus smoke; set DOCTRUTH_RUNTIME_REAL_OCR_CORPUS_SMOKE=1"
    exit 0
fi

if [ -n "${DOCTRUTH_RAPIDOCR_PYTHON:-}" ]; then
    PYTHON="$DOCTRUTH_RAPIDOCR_PYTHON"
elif command -v python3.10 >/dev/null 2>&1; then
    PYTHON="$(command -v python3.10)"
elif command -v python3 >/dev/null 2>&1; then
    PYTHON="$(command -v python3)"
else
    echo "python3.10 or python3 is required for Rust runtime real OCR corpus smoke" >&2
    exit 1
fi

if ! command -v pdftoppm >/dev/null 2>&1; then
    echo "pdftoppm is required for Rust runtime real OCR corpus smoke" >&2
    exit 1
fi

MANIFEST_PATH="runtime/doctruth-runtime/Cargo.toml"
cargo build --manifest-path "$MANIFEST_PATH" >/dev/null
BIN="runtime/doctruth-runtime/target/debug/doctruth-runtime"

WORK_DIR="${DOCTRUTH_RUNTIME_REAL_OCR_CORPUS_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/doctruth-runtime-real-ocr.XXXXXX")}"
VENV="${DOCTRUTH_RAPIDOCR_VENV:-$WORK_DIR/venv}"
MODEL_CACHE="${DOCTRUTH_RUNTIME_REAL_OCR_MODEL_CACHE:-target/runtime-real-ocr-model-cache}"
case "$MODEL_CACHE" in
    /*) ;;
    *) MODEL_CACHE="$ROOT/$MODEL_CACHE" ;;
esac
mkdir -p "$WORK_DIR" "$MODEL_CACHE"

if [ ! -x "$VENV/bin/python" ]; then
    "$PYTHON" -m venv "$VENV"
    "$VENV/bin/python" -m pip install --upgrade pip setuptools wheel
    "$VENV/bin/python" -m pip install 'numpy<2.0' 'rapidocr==3.8.1' 'rapidocr_onnxruntime==1.4.4'
fi

PATH="$VENV/bin:${PATH:-}" scripts/doctruth-rapidocr-mnn-worker --doctor > "$WORK_DIR/rapidocr-doctor.json"

"$VENV/bin/python" - "$WORK_DIR/rapidocr-doctor.json" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines()[-1])
assert payload["ok"] is True, payload
assert payload["runtime"] == "rapidocr", payload
assert payload["code"] == "ready", payload
PY

PDF="$WORK_DIR/runtime-real-ocr-invoice.pdf"
MODEL_MANIFEST="$WORK_DIR/models.json"
MODEL_BYTES="$MODEL_CACHE/ocr-router-v1.bin"
REPORT="$WORK_DIR/report.json"

SOURCE_HASH="$("$VENV/bin/python" - "$PDF" "$MODEL_MANIFEST" "$MODEL_BYTES" <<'PY'
import hashlib
import json
import pathlib
import sys
from PIL import Image, ImageDraw, ImageFont

pdf = pathlib.Path(sys.argv[1])
manifest = pathlib.Path(sys.argv[2])
model = pathlib.Path(sys.argv[3])

image = Image.new("RGB", (900, 280), "white")
draw = ImageDraw.Draw(image)
try:
    font = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial.ttf", 74)
except Exception:
    font = ImageFont.load_default()
draw.text((50, 78), "Invoice Total 123", fill="black", font=font)
image.save(pdf, "PDF", resolution=150.0)

payload = b"rapidocr-runtime-ocr-router-v1\n"
model.write_bytes(payload)
sha = "sha256:" + hashlib.sha256(payload).hexdigest()
manifest.write_text(json.dumps({
    "presets": {
        "ocr": [{
            "name": "ocr-router",
            "version": "v1",
            "source": str(model),
            "sha256": sha,
            "expectedSha256": sha,
            "sizeBytes": len(payload),
            "required": True,
            "task": "ocr",
            "backend": "rapidocr",
            "format": "worker",
            "precision": "runtime",
            "license": "rapidocr-stack-local",
        }]
    }
}, separators=(",", ":")), encoding="utf-8")
print("sha256:" + hashlib.sha256(pdf.read_bytes()).hexdigest())
PY
)"

"$VENV/bin/python" - "$MODEL_MANIFEST" "$MODEL_BYTES" <<'PY'
import hashlib
import json
import pathlib
import sys

manifest = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
model = pathlib.Path(sys.argv[2])
artifact = manifest["presets"]["ocr"][0]
assert model.is_file(), model
assert artifact["sha256"] == "sha256:" + hashlib.sha256(model.read_bytes()).hexdigest(), artifact
assert artifact["sizeBytes"] == model.stat().st_size, artifact
PY

PATH="$VENV/bin:${PATH:-}" \
DOCTRUTH_MODEL_MANIFEST="$MODEL_MANIFEST" \
DOCTRUTH_MODEL_CACHE="$MODEL_CACHE" \
DOCTRUTH_RUNTIME_MODEL_COMMAND="$ROOT/scripts/doctruth-rapidocr-mnn-worker" \
    "$BIN" <<EOF_REQUEST > "$REPORT"
{"command":"parse_pdf","source_path":"$PDF","source_hash":"$SOURCE_HASH","preset":"ocr","offline_mode":true,"allow_model_downloads":false}
EOF_REQUEST

"$VENV/bin/python" - "$REPORT" <<'PY'
import json
import pathlib
import sys

report = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
parser = report["parserRun"]
assert parser["backend"] == "rust-sidecar+model-worker", parser
assert parser.get("workerBackend") == "rapidocr-worker", parser
assert parser.get("runtime") == "doctruth-runtime", parser
assert parser["preset"] == "ocr", parser
assert "ocr-router:v1" in parser["models"], parser
assert report["auditGradeStatus"] in {"AUDIT_GRADE", "NOT_AUDIT_GRADE"}, report
units = report["body"]["units"]
assert units, report["body"]
assert any(unit["kind"] == "OCR_REGION" for unit in units), units
text = "\n".join(unit.get("text", "") for unit in units)
assert "123" in text, text
assert any(unit.get("location", {}).get("boundingBox") for unit in units), units
PY

echo "doctruth Rust runtime real OCR corpus smoke passed"
