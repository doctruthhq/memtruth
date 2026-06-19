#!/usr/bin/env sh
set -eu
export DOCTRUTH_ALLOW_PYTHON_ORACLE="${DOCTRUTH_ALLOW_PYTHON_ORACLE:-1}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ "${DOCTRUTH_RAPIDOCR_REAL_SMOKE:-0}" != "1" ]; then
    echo "skipping real RapidOCR smoke; set DOCTRUTH_RAPIDOCR_REAL_SMOKE=1 to install/run local RapidOCR"
    exit 0
fi

if [ -n "${DOCTRUTH_RAPIDOCR_PYTHON:-}" ]; then
    PYTHON="$DOCTRUTH_RAPIDOCR_PYTHON"
elif command -v python3.10 >/dev/null 2>&1; then
    PYTHON="$(command -v python3.10)"
elif command -v python3 >/dev/null 2>&1; then
    PYTHON="$(command -v python3)"
else
    echo "python3.10 or python3 is required for real RapidOCR smoke" >&2
    exit 1
fi

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-rapidocr-real-smoke.XXXXXX")"
VENV="${DOCTRUTH_RAPIDOCR_VENV:-$WORK_DIR/venv}"
REQUEST="$WORK_DIR/request.json"
DIRECT_RAW="$WORK_DIR/direct.raw"
DOCTOR_RAW="$WORK_DIR/doctor.raw"
PDF="$WORK_DIR/scanned-rapidocr.pdf"
PNG="$WORK_DIR/invoice.png"
JSON_OUT="$WORK_DIR/trust-document.json"

if [ ! -x "$VENV/bin/python" ]; then
    "$PYTHON" -m venv "$VENV"
    "$VENV/bin/python" -m pip install --upgrade pip setuptools wheel
    "$VENV/bin/python" -m pip install 'numpy<2.0' 'rapidocr==3.8.1' 'rapidocr_onnxruntime==1.4.4'
fi

PATH="$VENV/bin:${PATH:-}" scripts/doctruth-rapidocr-mnn-worker --doctor > "$DOCTOR_RAW"

"$VENV/bin/python" - "$DOCTOR_RAW" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines()[-1])
assert payload["ok"] is True, payload
assert payload["runtime"] == "rapidocr", payload
assert payload["code"] == "ready", payload
PY

"$VENV/bin/python" - "$PNG" "$REQUEST" "$PDF" <<'PY'
import base64
import json
import pathlib
import sys
from PIL import Image, ImageDraw, ImageFont

png = pathlib.Path(sys.argv[1])
request_path = pathlib.Path(sys.argv[2])
pdf = pathlib.Path(sys.argv[3])
image = Image.new("RGB", (720, 220), "white")
draw = ImageDraw.Draw(image)
try:
    font = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial.ttf", 64)
except Exception:
    font = ImageFont.load_default()
draw.text((40, 60), "Invoice Total 123", fill="black", font=font)
image.save(png)
image.save(pdf, "PDF", resolution=150.0)
request_path.write_text(json.dumps({
    "version": 1,
    "engine": "mnn",
    "fallbackEngine": "onnxruntime",
    "renderMaxWidth": image.width,
    "maxPages": 1,
    "fileName": png.name,
    "fileType": "png",
    "mimeType": "image/png",
    "bytesBase64": base64.b64encode(png.read_bytes()).decode("ascii"),
}), encoding="utf-8")
PY

PATH="$VENV/bin:${PATH:-}" scripts/doctruth-rapidocr-mnn-worker < "$REQUEST" > "$DIRECT_RAW"

"$VENV/bin/python" - "$DIRECT_RAW" <<'PY'
import json
import pathlib
import sys

text = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
start = text.find("{")
assert start >= 0, text
for end in range(len(text), start, -1):
    candidate = text[start:end].strip()
    if not candidate.endswith("}"):
        continue
    try:
        payload = json.loads(candidate)
        break
    except json.JSONDecodeError:
        pass
else:
    raise AssertionError(text)
assert payload["ok"] is True, payload
assert "123" in payload["text"], payload
assert payload["pages"][0]["regions"], payload
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
PATH="$VENV/bin:${PATH:-}" DOCTRUTH_OCR_COMMAND="$ROOT/scripts/doctruth-rapidocr-mnn-worker" \
    DOCTRUTH_OCR_TIMEOUT_MS="${DOCTRUTH_OCR_TIMEOUT_MS:-60000}" "$JAVA_BIN" -jar "$CLI_JAR" \
    parse "$PDF" --format json --preset ocr -o "$JSON_OUT" > "$WORK_DIR/parse.out"

"$VENV/bin/python" - "$JSON_OUT" <<'PY'
import json
import pathlib
import sys

doc = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
unit = doc["body"]["units"][0]
assert doc["parserRun"]["preset"] == "ocr", doc["parserRun"]
assert doc["parserRun"]["backend"] == "pdfbox+ocr", doc["parserRun"]
assert unit["kind"] == "OCR_REGION", unit
assert "123" in unit["text"], unit["text"]
assert unit["location"]["boundingBox"] is not None, unit["location"]
assert unit["confidence"]["score"] > 0.50, unit["confidence"]
PY

echo "doctruth real RapidOCR smoke passed"
