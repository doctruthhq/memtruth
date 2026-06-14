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
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-ocr-preset-smoke.XXXXXX")"
PDF="$WORK_DIR/scanned-smoke.pdf"
OUT_DIR="$WORK_DIR/review"
JSON_OUT="$WORK_DIR/ocr-trust.json"
LOW_JSON_OUT="$WORK_DIR/low-confidence-ocr-trust.json"
WORKER="$WORK_DIR/fake-mnn-ocr-worker"
LOW_WORKER="$WORK_DIR/fake-low-confidence-mnn-ocr-worker"

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

cat > "$WORKER" <<'SH'
#!/usr/bin/env sh
python3 -c '
import json
import sys

request = json.loads(sys.stdin.read())
assert request["engine"] == "mnn"
assert request["fileType"] == "png"
print(json.dumps({
    "ok": True,
    "engine": "mnn",
    "text": "OCR preset recovered local MNN text",
    "averageConfidence": 0.93,
    "pages": [],
    "warnings": []
}))
'
SH
chmod +x "$WORKER"

cat > "$LOW_WORKER" <<'SH'
#!/usr/bin/env sh
python3 -c '
import json
import sys

request = json.loads(sys.stdin.read())
assert request["engine"] == "mnn"
assert request["fileType"] == "png"
print(json.dumps({
    "ok": True,
    "engine": "mnn",
    "text": "Low confidence OCR preset text",
    "averageConfidence": 0.41,
    "pages": [],
    "warnings": []
}))
'
SH
chmod +x "$LOW_WORKER"

DOCTRUTH_OCR_COMMAND="$WORKER" "$JAVA_BIN" -jar "$CLI_JAR" \
  parse "$PDF" --format json --preset ocr -o "$JSON_OUT" > "$WORK_DIR/parse.out"

DOCTRUTH_OCR_COMMAND="$LOW_WORKER" "$JAVA_BIN" -jar "$CLI_JAR" \
  parse "$PDF" --format json --preset ocr -o "$LOW_JSON_OUT" > "$WORK_DIR/low-confidence-parse.out"

DOCTRUTH_OCR_COMMAND="$WORKER" "$JAVA_BIN" -jar "$CLI_JAR" \
  review-package "$PDF" --preset ocr -o "$OUT_DIR" > "$WORK_DIR/ocr.out"

test -s "$JSON_OUT"
test -s "$LOW_JSON_OUT"
test -s "$OUT_DIR/trust-document.json"
test -s "$OUT_DIR/review.html"
test -s "$OUT_DIR/pages/page-0001.png"
grep -q "review-package:" "$WORK_DIR/ocr.out"
grep -q "OCR preset recovered local MNN text" "$OUT_DIR/review.html"

python3 - "$JSON_OUT" "$OUT_DIR/trust-document.json" <<'PY'
import json
import pathlib
import sys

for path in sys.argv[1:]:
    doc = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
    assert doc["parserRun"]["preset"] == "ocr"
    assert doc["parserRun"]["backend"] == "pdfbox+ocr"
    assert "rapidocr-mnn:local" in doc["parserRun"]["models"]
    assert doc["body"]["units"][0]["kind"] == "OCR_REGION"
    assert "OCR preset recovered local MNN text" in doc["body"]["units"][0]["text"]
PY

python3 - "$LOW_JSON_OUT" <<'PY'
import json
import pathlib
import sys

doc = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
unit = doc["body"]["units"][0]
assert doc["auditGradeStatus"] == "NOT_AUDIT_GRADE"
assert unit["confidence"]["score"] == 0.41
assert unit["warnings"][0]["code"] == "ocr_low_confidence"
assert unit["warnings"][0]["severity"] == "SEVERE"
PY

echo "doctruth OCR preset smoke passed"
