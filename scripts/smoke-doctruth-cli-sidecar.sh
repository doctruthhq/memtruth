#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
MANIFEST="$ROOT_DIR/runtime/doctruth-runtime/Cargo.toml"
RUNTIME_BIN="$ROOT_DIR/runtime/doctruth-runtime/target/debug/doctruth-runtime"
CLI_JAR="$ROOT_DIR/target/doctruth-java-0.2.0-alpha-all.jar"
WORK_DIR="${TMPDIR:-/tmp}/doctruth-cli-sidecar-smoke"
PDF="$WORK_DIR/sidecar-smoke.pdf"
TABLE_PDF="$WORK_DIR/sidecar-table-smoke.pdf"
MERGED_TABLE_PDF="$WORK_DIR/sidecar-merged-table-smoke.pdf"
ROW_SPAN_TABLE_PDF="$WORK_DIR/sidecar-row-span-table-smoke.pdf"
CONTINUED_TABLE_PDF="$WORK_DIR/sidecar-continued-table-smoke.pdf"
JSON_OUT="$WORK_DIR/sidecar-smoke.json"
CONTENT_BLOCKS_OUT="$WORK_DIR/sidecar-smoke.content_blocks.json"
PARSE_TRACE_OUT="$WORK_DIR/sidecar-smoke.parse_trace.json"
MODEL_FALLBACK_JSON_OUT="$WORK_DIR/sidecar-model-fallback-smoke.json"
OCR_JSON_OUT="$WORK_DIR/sidecar-ocr-smoke.json"
TABLE_JSON_OUT="$WORK_DIR/sidecar-table-smoke.json"
MERGED_TABLE_JSON_OUT="$WORK_DIR/sidecar-merged-table-smoke.json"
ROW_SPAN_TABLE_JSON_OUT="$WORK_DIR/sidecar-row-span-table-smoke.json"
CONTINUED_TABLE_JSON_OUT="$WORK_DIR/sidecar-continued-table-smoke.json"
MD_OUT="$WORK_DIR/sidecar-smoke.md"
AUDIT_OUT="$WORK_DIR/sidecar-smoke.audit.json"
HTML_OUT="$WORK_DIR/sidecar-smoke.html"
COMPACT_OUT="$WORK_DIR/sidecar-smoke.compact.txt"
COMPACT_MAP_OUT="$WORK_DIR/sidecar-smoke.compact.doctruth-map.json"
TABLE_MD_OUT="$WORK_DIR/sidecar-table-smoke.md"
TABLE_PLAIN_OUT="$WORK_DIR/sidecar-table-smoke.txt"
MAP_OUT="$WORK_DIR/sidecar-smoke.doctruth-map.json"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
elif [ -x "/opt/homebrew/opt/openjdk/bin/java" ]; then
  JAVA_BIN="/opt/homebrew/opt/openjdk/bin/java"
else
  JAVA_BIN="java"
fi

mkdir -p "$WORK_DIR"

cargo build --manifest-path "$MANIFEST" >/dev/null
mvn -q -DskipTests package >/dev/null

python3 - "$PDF" <<'PY'
import sys

path = sys.argv[1]
lines = ["CLI sidecar first evidence line.", "CLI sidecar second evidence line."]
stream = "BT\n/F1 24 Tf\n72 720 Td\n"
for index, line in enumerate(lines):
    if index:
        stream += "0 -30 Td\n"
    escaped = line.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
    stream += f"({escaped}) Tj\n"
stream += "ET\n"
objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    f"<< /Length {len(stream.encode())} >>\nstream\n{stream}endstream",
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

python3 - "$TABLE_PDF" <<'PY'
import sys

path = sys.argv[1]
stream = """q
72 720 m
360 720 l
360 640 l
72 640 l
72 720 l
S
216 720 m
216 640 l
S
72 680 m
360 680 l
S
BT
/F1 16 Tf
90 695 Td
(Name) Tj
144 0 Td
(Score) Tj
-144 -40 Td
(Alex) Tj
144 0 Td
(98) Tj
ET
Q
"""
objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    f"<< /Length {len(stream.encode())} >>\nstream\n{stream}endstream",
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

python3 - "$MERGED_TABLE_PDF" <<'PY'
import sys

path = sys.argv[1]
stream = """q
72 720 m
360 720 l
360 640 l
72 640 l
72 720 l
S
72 680 m
360 680 l
S
216 680 m
216 640 l
S
BT
/F1 16 Tf
155 695 Td
(Header) Tj
-35 -40 Td
(A) Tj
145 0 Td
(B) Tj
ET
Q
"""
objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    f"<< /Length {len(stream.encode())} >>\nstream\n{stream}endstream",
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

python3 - "$ROW_SPAN_TABLE_PDF" <<'PY'
import sys

path = sys.argv[1]
stream = """q
72 720 m
360 720 l
360 640 l
72 640 l
72 720 l
S
216 720 m
216 640 l
S
216 680 m
360 680 l
S
BT
/F1 16 Tf
120 675 Td
(Role) Tj
145 20 Td
(Top) Tj
-10 -40 Td
(Bottom) Tj
ET
Q
"""
objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    f"<< /Length {len(stream.encode())} >>\nstream\n{stream}endstream",
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

python3 - "$CONTINUED_TABLE_PDF" <<'PY'
import sys

path = sys.argv[1]

def table_stream(name, score):
    return f"""q
72 720 m
360 720 l
360 640 l
72 640 l
72 720 l
S
216 720 m
216 640 l
S
72 680 m
360 680 l
S
BT
/F1 16 Tf
90 695 Td
(Name) Tj
144 0 Td
(Score) Tj
-144 -40 Td
({name}) Tj
144 0 Td
({score}) Tj
ET
Q
"""

objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "",
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
]
page_refs = []
for stream in (table_stream("Alex", "98"), table_stream("Bea", "97")):
    page_obj = len(objects) + 1
    stream_obj = len(objects) + 2
    page_refs.append(f"{page_obj} 0 R")
    objects.append(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents {stream_obj} 0 R >>")
    objects.append(f"<< /Length {len(stream.encode())} >>\nstream\n{stream}endstream")
objects[1] = f"<< /Type /Pages /Kids [{' '.join(page_refs)}] /Count 2 >>"
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

"$JAVA_BIN" -jar "$CLI_JAR" parse "$PDF" \
  --backend sidecar \
  --runtime "$RUNTIME_BIN" \
  --preset lite \
  --format json \
  --profile full \
  --out "$JSON_OUT"

python3 - "$JSON_OUT" "$PDF" <<'PY'
import json, sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)
assert data["parserRun"]["backend"] == "rust-sidecar"
assert data["auditGradeStatus"] == "AUDIT_GRADE"
page = data["body"]["pages"][0]
assert page["imageHash"].startswith("sha256:")
units = data["body"]["units"]
assert len(units) == 2
assert units[0]["kind"] == "LINE_SPAN"
assert units[0]["text"] == "CLI sidecar first evidence line."
assert units[0]["sourceObjectId"] == "runtime-text-layer-page-1-line-1"
assert units[1]["kind"] == "LINE_SPAN"
assert units[1]["text"] == "CLI sidecar second evidence line."
assert units[1]["sourceObjectId"] == "runtime-text-layer-page-1-line-2"
PY

"$JAVA_BIN" -jar "$CLI_JAR" parse "$PDF" \
  --backend sidecar \
  --runtime "$RUNTIME_BIN" \
  --preset lite \
  --format content_blocks \
  --out "$CONTENT_BLOCKS_OUT"

python3 - "$CONTENT_BLOCKS_OUT" <<'PY'
import json, sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)
assert data["format"] == "doctruth.content_blocks.v1"
assert data["sourceHash"].startswith("sha256:")
blocks = data["contentBlocks"]
assert [block["text"] for block in blocks] == [
    "CLI sidecar first evidence line.",
    "CLI sidecar second evidence line.",
]
assert blocks[0]["blockId"] == "block-0001"
assert blocks[0]["sourceUnitIds"] == ["unit-0001"]
assert blocks[0]["evidenceSpanIds"] == ["span-0001"]
PY

"$JAVA_BIN" -jar "$CLI_JAR" parse "$PDF" \
  --backend sidecar \
  --runtime "$RUNTIME_BIN" \
  --preset lite \
  --format parse_trace \
  --out "$PARSE_TRACE_OUT"

python3 - "$PARSE_TRACE_OUT" <<'PY'
import json, sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)
assert data["format"] == "doctruth.parse_trace.v1"
trace = data["parseTrace"]
page = trace["pages"][0]
assert page["pageIndex"] == 0
assert len(page["readingBlocks"]) == 2
block = page["readingBlocks"][0]
assert block["blockId"] == "block-0001"
assert block["sourceUnitIds"] == ["unit-0001"]
assert block["evidenceSpanIds"] == ["span-0001"]
line = block["lines"][0]
assert line["lineId"] == "line-0001"
assert line["spans"][0]["sourceObjectId"] == "runtime-text-layer-page-1-line-1"
assert line["spans"][0]["evidenceSpanId"] == "span-0001"
PY

"$JAVA_BIN" -jar "$CLI_JAR" parse "$PDF" \
  --backend sidecar \
  --runtime "$RUNTIME_BIN" \
  --preset table-lite \
  --format json \
  --profile full \
  --out "$MODEL_FALLBACK_JSON_OUT"

python3 - "$MODEL_FALLBACK_JSON_OUT" <<'PY'
import json, sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)
assert data["parserRun"]["backend"] == "rust-sidecar"
assert data["parserRun"]["preset"] == "table-lite"
assert data["parserRun"]["models"] == ["slanet-plus:v1"]
assert data["auditGradeStatus"] == "NOT_AUDIT_GRADE"
warnings = data["parserRun"]["warnings"]
assert any(
    warning["code"] == "model_unavailable_fallback"
    and warning["severity"] == "SEVERE"
    and "slanet-plus:v1" in warning["message"]
    for warning in warnings
)
assert data["body"]["units"][0]["text"] == "CLI sidecar first evidence line."
PY

"$JAVA_BIN" -jar "$CLI_JAR" parse "$PDF" \
  --backend sidecar \
  --runtime "$RUNTIME_BIN" \
  --preset ocr \
  --format json \
  --profile full \
  --out "$OCR_JSON_OUT"

python3 - "$OCR_JSON_OUT" <<'PY'
import json, sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)
assert data["parserRun"]["backend"] == "rust-sidecar"
assert data["parserRun"]["preset"] == "ocr"
assert data["body"]["units"][0]["text"] == "CLI sidecar first evidence line."
PY

"$JAVA_BIN" -jar "$CLI_JAR" parse "$PDF" \
  --backend sidecar \
  --runtime "$RUNTIME_BIN" \
  --preset lite \
  --format markdown \
  --profile clean \
  --source-map \
  --out "$MD_OUT"

grep -q "CLI sidecar first evidence line." "$MD_OUT"
grep -q "CLI sidecar second evidence line." "$MD_OUT"
test -s "$MAP_OUT"

python3 - "$MAP_OUT" <<'PY'
import json, sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)
assert data["format"] == "markdown"
assert len(data["sourceMap"]) >= 2
PY

"$JAVA_BIN" -jar "$CLI_JAR" verify-source-map "$MD_OUT" "$MAP_OUT" --source "$PDF" >/dev/null

"$JAVA_BIN" -jar "$CLI_JAR" parse "$PDF" \
  --backend sidecar \
  --runtime "$RUNTIME_BIN" \
  --preset lite \
  --format audit \
  --out "$AUDIT_OUT"

python3 - "$AUDIT_OUT" <<'PY'
import json, sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)
assert data["format"] == "doctruth.trust_document.audit.v1"
assert data["sourceHash"].startswith("sha256:")
assert data["canonicalHash"].startswith("sha256:")
assert data["evidenceHash"].startswith("sha256:")
assert data["parserRun"]["backend"] == "rust-sidecar"
assert data["evidence"]
PY

"$JAVA_BIN" -jar "$CLI_JAR" verify-audit "$JSON_OUT" "$AUDIT_OUT" >/dev/null

"$JAVA_BIN" -jar "$CLI_JAR" parse "$PDF" \
  --backend sidecar \
  --runtime "$RUNTIME_BIN" \
  --preset lite \
  --format html \
  --out "$HTML_OUT"

grep -q "data-trust-page-number=\"1\"" "$HTML_OUT"
grep -q "data-page-width=\"612\"" "$HTML_OUT"
grep -q "data-page-height=\"792\"" "$HTML_OUT"
grep -q "data-text-layer-available=\"true\"" "$HTML_OUT"
grep -q "data-image-hash=\"sha256:" "$HTML_OUT"
if grep -q ":page-1\"" "$HTML_OUT"; then
  echo "html output leaked placeholder page hash" >&2
  exit 1
fi
grep -q "data-trust-unit-id=\"unit-0001\"" "$HTML_OUT"
grep -q "data-trust-overlay-layer=\"bbox\"" "$HTML_OUT"
grep -q "data-trust-bbox-overlay=\"unit\"" "$HTML_OUT"
grep -q "data-trust-overlay-for=\"unit-0001\"" "$HTML_OUT"

"$JAVA_BIN" -jar "$CLI_JAR" parse "$PDF" \
  --backend sidecar \
  --runtime "$RUNTIME_BIN" \
  --preset lite \
  --format compact \
  --source-map \
  --out "$COMPACT_OUT"

grep -q "doc|sha256:" "$COMPACT_OUT"
grep -q "CLI sidecar first evidence line." "$COMPACT_OUT"
grep -q "|bbox=" "$COMPACT_OUT"
test -s "$COMPACT_MAP_OUT"

python3 - "$COMPACT_MAP_OUT" <<'PY'
import json, sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)
assert data["format"] == "compact_llm"
assert len(data["sourceMap"]) >= 2
PY

"$JAVA_BIN" -jar "$CLI_JAR" verify-source-map "$COMPACT_OUT" "$COMPACT_MAP_OUT" --source "$PDF" >/dev/null

"$JAVA_BIN" -jar "$CLI_JAR" parse "$TABLE_PDF" \
  --backend sidecar \
  --runtime "$RUNTIME_BIN" \
  --preset lite \
  --format json \
  --profile full \
  --out "$TABLE_JSON_OUT"

python3 - "$TABLE_JSON_OUT" <<'PY'
import json, sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)
tables = data["body"]["tables"]
units = data["body"]["units"]
table_units = [unit for unit in units if unit["kind"] == "TABLE_CELL"]
assert data["parserRun"]["backend"] == "rust-sidecar"
assert len(tables) == 1
assert len(tables[0]["cells"]) == 4
assert len(table_units) == 4
assert tables[0]["cells"][0]["text"] == "Name"
assert tables[0]["cells"][1]["text"] == "Score"
assert tables[0]["cells"][2]["text"] == "Alex"
assert tables[0]["cells"][3]["text"] == "98"
PY

"$JAVA_BIN" -jar "$CLI_JAR" parse "$MERGED_TABLE_PDF" \
  --backend sidecar \
  --runtime "$RUNTIME_BIN" \
  --preset lite \
  --format json \
  --profile full \
  --out "$MERGED_TABLE_JSON_OUT"

python3 - "$MERGED_TABLE_JSON_OUT" <<'PY'
import json, sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)
tables = data["body"]["tables"]
table_units = [unit for unit in data["body"]["units"] if unit["kind"] == "TABLE_CELL"]
assert data["parserRun"]["backend"] == "rust-sidecar"
assert len(tables) == 1
assert len(tables[0]["cells"]) == 3
assert len(table_units) == 3
assert [cell["text"] for cell in tables[0]["cells"]] == ["Header", "A", "B"]
assert tables[0]["cells"][0]["rowRange"] == {"start": 0, "end": 0}
assert tables[0]["cells"][0]["columnRange"] == {"start": 0, "end": 1}
assert tables[0]["cells"][1]["columnRange"] == {"start": 0, "end": 0}
assert tables[0]["cells"][2]["columnRange"] == {"start": 1, "end": 1}
PY

"$JAVA_BIN" -jar "$CLI_JAR" parse "$ROW_SPAN_TABLE_PDF" \
  --backend sidecar \
  --runtime "$RUNTIME_BIN" \
  --preset lite \
  --format json \
  --profile full \
  --out "$ROW_SPAN_TABLE_JSON_OUT"

python3 - "$ROW_SPAN_TABLE_JSON_OUT" <<'PY'
import json, sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)
tables = data["body"]["tables"]
table_units = [unit for unit in data["body"]["units"] if unit["kind"] == "TABLE_CELL"]
assert data["parserRun"]["backend"] == "rust-sidecar"
assert len(tables) == 1
assert len(tables[0]["cells"]) == 3
assert len(table_units) == 3
assert [cell["text"] for cell in tables[0]["cells"]] == ["Role", "Top", "Bottom"]
assert tables[0]["cells"][0]["rowRange"] == {"start": 0, "end": 1}
assert tables[0]["cells"][0]["columnRange"] == {"start": 0, "end": 0}
assert tables[0]["cells"][1]["rowRange"] == {"start": 0, "end": 0}
assert tables[0]["cells"][1]["columnRange"] == {"start": 1, "end": 1}
assert tables[0]["cells"][2]["rowRange"] == {"start": 1, "end": 1}
assert tables[0]["cells"][2]["columnRange"] == {"start": 1, "end": 1}
PY

"$JAVA_BIN" -jar "$CLI_JAR" parse "$CONTINUED_TABLE_PDF" \
  --backend sidecar \
  --runtime "$RUNTIME_BIN" \
  --preset lite \
  --format json \
  --profile full \
  --out "$CONTINUED_TABLE_JSON_OUT"

python3 - "$CONTINUED_TABLE_JSON_OUT" <<'PY'
import json, sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)
tables = data["body"]["tables"]
table_units = [unit for unit in data["body"]["units"] if unit["kind"] == "TABLE_CELL"]
assert data["parserRun"]["backend"] == "rust-sidecar"
assert len(tables) == 1
assert tables[0]["pageNumber"] == 1
assert [cell["text"] for cell in tables[0]["cells"]] == ["Name", "Score", "Alex", "98", "Bea", "97"]
assert len(table_units) == 6
assert table_units[4]["text"] == "Bea"
assert table_units[4]["location"]["page"] == 2
assert table_units[5]["text"] == "97"
assert table_units[5]["location"]["page"] == 2
PY

"$JAVA_BIN" -jar "$CLI_JAR" parse "$TABLE_PDF" \
  --backend sidecar \
  --runtime "$RUNTIME_BIN" \
  --preset lite \
  --format markdown \
  --profile clean \
  --out "$TABLE_MD_OUT"

grep -q "| Name | Score |" "$TABLE_MD_OUT"
grep -q "| --- | --- |" "$TABLE_MD_OUT"
grep -q "| Alex | 98 |" "$TABLE_MD_OUT"

"$JAVA_BIN" -jar "$CLI_JAR" parse "$TABLE_PDF" \
  --backend sidecar \
  --runtime "$RUNTIME_BIN" \
  --preset lite \
  --format plain \
  --out "$TABLE_PLAIN_OUT"

grep -q "Name	Score" "$TABLE_PLAIN_OUT"
grep -q "Alex	98" "$TABLE_PLAIN_OUT"
if grep -q "| --- |" "$TABLE_PLAIN_OUT"; then
  echo "plain output leaked markdown table syntax" >&2
  exit 1
fi
if grep -q "{#ev:" "$TABLE_PLAIN_OUT"; then
  echo "plain output leaked evidence anchors" >&2
  exit 1
fi

echo "doctruth CLI sidecar smoke passed"
