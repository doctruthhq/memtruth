#!/usr/bin/env sh
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ "${DOCTRUTH_RUNTIME_MNN_OCR_SMOKE:-0}" != "1" ]; then
    echo "skipping Rust MNN OCR smoke; set DOCTRUTH_RUNTIME_MNN_OCR_SMOKE=1"
    exit 0
fi

if command -v python3 >/dev/null 2>&1; then
    PYTHON="$(command -v python3)"
else
    echo "python3 is required for Rust MNN OCR smoke" >&2
    exit 1
fi

"$PYTHON" - <<'PY'
try:
    import PIL  # noqa: F401
except Exception as exc:
    raise SystemExit(f"Pillow is required for Rust MNN OCR smoke: {exc}")
PY

MANIFEST="model-packs/ppocr-v5-mobile-mnn.json"
MODEL_CACHE="${DOCTRUTH_RUNTIME_MNN_OCR_MODEL_CACHE:-target/ppocr-v5-mobile-mnn-cache}"
WORK_DIR="${DOCTRUTH_RUNTIME_MNN_OCR_WORK_DIR:-target/mnn-ocr-smoke}"
REPORT="$WORK_DIR/report.json"
PDF="$WORK_DIR/invoice-total-123.pdf"

mkdir -p "$MODEL_CACHE" "$WORK_DIR"

"$PYTHON" scripts/fetch-doctruth-model-pack.py \
    --manifest "$MANIFEST" \
    --cache "$MODEL_CACHE" >/dev/null

cargo build \
    --manifest-path runtime/doctruth-runtime/Cargo.toml \
    --features mnn-ocr \
    --bin doctruth-runtime \
    --bin doctruth-mnn-model-worker >/dev/null

SOURCE_HASH="$("$PYTHON" - "$PDF" <<'PY'
import hashlib
import pathlib
import sys
from PIL import Image, ImageDraw, ImageFont

pdf = pathlib.Path(sys.argv[1])
image = Image.new("RGB", (1000, 360), "white")
draw = ImageDraw.Draw(image)
for font_path in [
    "/System/Library/Fonts/Supplemental/Arial.ttf",
    "/System/Library/Fonts/Supplemental/Helvetica.ttf",
]:
    try:
        font = ImageFont.truetype(font_path, 82)
        break
    except Exception:
        font = ImageFont.load_default()
draw.text((60, 110), "Invoice Total 123", fill="black", font=font)
image.save(pdf, "PDF", resolution=150.0)
print("sha256:" + hashlib.sha256(pdf.read_bytes()).hexdigest())
PY
)"

DOCTRUTH_MODEL_MANIFEST="$MANIFEST" \
DOCTRUTH_MODEL_CACHE="$MODEL_CACHE" \
DOCTRUTH_RUNTIME_MODEL_COMMAND="runtime/doctruth-runtime/target/debug/doctruth-mnn-model-worker" \
    runtime/doctruth-runtime/target/debug/doctruth-runtime <<EOF_REQUEST > "$REPORT"
{"command":"parse_pdf","source_path":"$PDF","source_hash":"$SOURCE_HASH","preset":"ocr","offline_mode":true,"allow_model_downloads":false}
EOF_REQUEST

"$PYTHON" - "$REPORT" <<'PY'
import json
import pathlib
import sys

doc = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
parser = doc["parserRun"]
assert parser["backend"] == "rust-sidecar+model-worker", parser
assert parser["workerBackend"] == "mnn-ocr-rs", parser
assert parser["modelRuntime"]["runtime"] == "mnn", parser
assert parser["modelRuntime"]["loadedModels"] == [
    "ppocr-v5-mobile-det:v0.1.3",
    "ppocr-v5-mobile-rec:v0.1.3",
], parser
assert doc["auditGradeStatus"] == "AUDIT_GRADE", doc
units = doc["body"]["units"]
assert units and units[0]["kind"] == "OCR_REGION", units
text = "\n".join(unit.get("text", "") for unit in units)
assert "Invoice Total 123" in text, text
assert units[0]["location"]["boundingBox"]["x0"] < units[0]["location"]["boundingBox"]["x1"], units
PY

echo "doctruth Rust MNN OCR smoke passed: $REPORT"
