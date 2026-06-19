#!/usr/bin/env sh
set -eu
export DOCTRUTH_ALLOW_PYTHON_ORACLE="${DOCTRUTH_ALLOW_PYTHON_ORACLE:-1}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-rapidocr-worker-smoke.XXXXXX")"
FAKE_MODULE_DIR="$WORK_DIR/python"
REQUEST="$WORK_DIR/request.json"
OUTPUT="$WORK_DIR/output.json"
DOCTOR_OUTPUT="$WORK_DIR/doctor.json"
PDF="$WORK_DIR/scanned.pdf"
JSON_OUT="$WORK_DIR/trust-document.json"

mkdir -p "$FAKE_MODULE_DIR/rapidocr"
cat > "$FAKE_MODULE_DIR/rapidocr/__init__.py" <<'PY'
class ArrayLike:
    def __init__(self, values):
        self.values = values

    def __iter__(self):
        return iter(self.values)

    def __len__(self):
        return len(self.values)

    def __getitem__(self, index):
        return self.values[index]

    def __bool__(self):
        raise ValueError("truth value of array-like OCR output is ambiguous")


class OCRResult:
    txts = ArrayLike(["Invoice", "Total"])
    scores = ArrayLike([0.91, 0.87])
    boxes = ArrayLike([
        [[10, 20], [70, 20], [70, 40], [10, 40]],
        [[80, 50], [130, 50], [130, 70], [80, 70]],
    ])


class RapidOCR:
    def __init__(self):
        self.ready = True

    def __call__(self, image_path):
        assert image_path.endswith(".png")
        return OCRResult()
PY

PYTHONPATH="$FAKE_MODULE_DIR" scripts/doctruth-rapidocr-mnn-worker --doctor > "$DOCTOR_OUTPUT"

python3 - "$DOCTOR_OUTPUT" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
assert payload["ok"] is True
assert payload["engine"] == "mnn"
assert payload["code"] == "ready"
assert payload["runtime"] == "rapidocr"
PY

cat > "$REQUEST" <<'JSON'
{
  "version": 1,
  "engine": "mnn",
  "fallbackEngine": "onnxruntime",
  "renderMaxWidth": 320,
  "maxPages": 1,
  "fileName": "page-1.png",
  "fileType": "png",
  "mimeType": "image/png",
  "bytesBase64": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="
}
JSON

PYTHONPATH="$FAKE_MODULE_DIR" scripts/doctruth-rapidocr-mnn-worker < "$REQUEST" > "$OUTPUT"

python3 - "$OUTPUT" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
assert payload["ok"] is True
assert payload["engine"] == "mnn"
assert payload["text"] == "Invoice\nTotal"
assert round(payload["averageConfidence"], 2) == 0.89
assert payload["pages"][0]["page"] == 1
regions = payload["pages"][0]["regions"]
assert len(regions) == 2
assert regions[0]["text"] == "Invoice"
assert regions[0]["bbox"] == {"x": 10, "y": 20, "width": 60, "height": 20}
assert regions[1]["text"] == "Total"
assert regions[1]["bbox"] == {"x": 80, "y": 50, "width": 50, "height": 20}
PY

python3 - "$PDF" <<'PY'
import sys

path = sys.argv[1]
objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> >>",
]
pdf = bytearray(b"%PDF-1.4\n")
offsets = []
for i, obj in enumerate(objects, start=1):
    offsets.append(len(pdf))
    pdf.extend(f"{i} 0 obj\n{obj}\nendobj\n".encode())
xref = len(pdf)
pdf.extend(f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode())
for offset in offsets:
    pdf.extend(f"{offset:010} 00000 n \n".encode())
pdf.extend(f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
with open(path, "wb") as handle:
    handle.write(pdf)
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
PATH="$ROOT/scripts:${PATH:-}" PYTHONPATH="$FAKE_MODULE_DIR" "$JAVA_BIN" -jar "$CLI_JAR" \
  parse "$PDF" --format json --preset ocr -o "$JSON_OUT" > "$WORK_DIR/parse.out"

python3 - "$JSON_OUT" <<'PY'
import json
import pathlib
import sys

doc = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
assert doc["parserRun"]["preset"] == "ocr"
assert doc["parserRun"]["backend"] == "pdfbox+ocr"
assert doc["body"]["units"][0]["kind"] == "OCR_REGION"
assert doc["body"]["units"][0]["text"] == "Invoice\nTotal"
assert doc["body"]["units"][0]["location"]["boundingBox"] is not None
PY

echo "doctruth RapidOCR worker smoke passed"
