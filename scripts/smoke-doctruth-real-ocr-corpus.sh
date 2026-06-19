#!/usr/bin/env sh
set -eu
export DOCTRUTH_ALLOW_PYTHON_ORACLE="${DOCTRUTH_ALLOW_PYTHON_ORACLE:-1}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ "${DOCTRUTH_REAL_OCR_CORPUS_SMOKE:-0}" != "1" ]; then
    echo "skipping real OCR corpus smoke; set DOCTRUTH_REAL_OCR_CORPUS_SMOKE=1 to install/run local RapidOCR corpus gate"
    exit 0
fi

if [ -n "${DOCTRUTH_RAPIDOCR_PYTHON:-}" ]; then
    PYTHON="$DOCTRUTH_RAPIDOCR_PYTHON"
elif command -v python3.10 >/dev/null 2>&1; then
    PYTHON="$(command -v python3.10)"
elif command -v python3 >/dev/null 2>&1; then
    PYTHON="$(command -v python3)"
else
    echo "python3.10 or python3 is required for real OCR corpus smoke" >&2
    exit 1
fi

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-real-ocr-corpus.XXXXXX")"
VENV="${DOCTRUTH_RAPIDOCR_VENV:-$WORK_DIR/venv}"
PDF="$WORK_DIR/scanned-invoice.pdf"
EXPECTED_JSON="$WORK_DIR/expected-ocr.json"
EXPECTED_MD="$WORK_DIR/expected-ocr.md"
MANIFEST="$WORK_DIR/corpus.json"
RESULT="$WORK_DIR/result.json"
MIN_ACCURACY="${DOCTRUTH_REAL_OCR_MIN_ACCURACY:-0.60}"

if [ ! -x "$VENV/bin/python" ]; then
    "$PYTHON" -m venv "$VENV"
    "$VENV/bin/python" -m pip install --upgrade pip setuptools wheel
    "$VENV/bin/python" -m pip install 'numpy<2.0' 'rapidocr==3.8.1' 'rapidocr_onnxruntime==1.4.4'
fi

PATH="$VENV/bin:${PATH:-}" scripts/doctruth-rapidocr-mnn-worker --doctor > "$WORK_DIR/doctor.raw"

"$VENV/bin/python" - "$WORK_DIR/doctor.raw" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines()[-1])
assert payload["ok"] is True, payload
assert payload["runtime"] == "rapidocr", payload
assert payload["code"] == "ready", payload
PY

"$VENV/bin/python" - "$PDF" <<'PY'
import pathlib
import sys
from PIL import Image, ImageDraw, ImageFont

pdf = pathlib.Path(sys.argv[1])
image = Image.new("RGB", (820, 260), "white")
draw = ImageDraw.Draw(image)
try:
    font = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial.ttf", 72)
except Exception:
    font = ImageFont.load_default()
draw.text((42, 70), "Invoice Total 123", fill="black", font=font)
image.save(pdf, "PDF", resolution=150.0)
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
    parse "$PDF" --format json --preset ocr -o "$EXPECTED_JSON" > "$WORK_DIR/parse.out"
printf "Invoice Total 123\n" > "$EXPECTED_MD"

"$VENV/bin/python" - "$WORK_DIR" "$PDF" "$EXPECTED_MD" "$EXPECTED_JSON" "$MANIFEST" "$MIN_ACCURACY" <<'PY'
import json
import pathlib
import sys

work = pathlib.Path(sys.argv[1])
pdf = pathlib.Path(sys.argv[2])
expected_md = pathlib.Path(sys.argv[3])
expected_json = pathlib.Path(sys.argv[4])
manifest = pathlib.Path(sys.argv[5])
minimum = float(sys.argv[6])
data = {
    "name": "real-ocr-generated-corpus",
    "minimums": {"ocr_text_accuracy": minimum},
    "cases": [
        {
            "name": "real-rapidocr-generated-invoice",
            "source": pdf.relative_to(work).as_posix(),
            "preset": "ocr",
            "expectedMarkdown": expected_md.relative_to(work).as_posix(),
            "expectedDocument": expected_json.relative_to(work).as_posix(),
        }
    ],
}
manifest.write_text(json.dumps(data, separators=(",", ":")), encoding="utf-8")
PY

PATH="$VENV/bin:${PATH:-}" DOCTRUTH_OCR_COMMAND="$ROOT/scripts/doctruth-rapidocr-mnn-worker" \
    DOCTRUTH_OCR_TIMEOUT_MS="${DOCTRUTH_OCR_TIMEOUT_MS:-60000}" "$JAVA_BIN" -jar "$CLI_JAR" \
    benchmark-corpus "$MANIFEST" --json > "$RESULT"

"$VENV/bin/python" - "$RESULT" "$MIN_ACCURACY" <<'PY'
import json
import pathlib
import sys

data = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
minimum = float(sys.argv[2])
case = data["cases"][0]
score = case["metrics"]["ocr_text_accuracy"]
assert data["passed"] is True, data
assert data["corpus"] == "real-ocr-generated-corpus", data
assert case["name"] == "real-rapidocr-generated-invoice", case
assert score >= minimum, score
PY

echo "doctruth real OCR corpus smoke passed"
