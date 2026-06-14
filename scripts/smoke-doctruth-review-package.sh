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
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-review-package-smoke.XXXXXX")"
PDF="$WORK_DIR/review-package-smoke.pdf"
OUT_DIR="$WORK_DIR/review"

python3 - "$PDF" <<'PY'
import sys

path = sys.argv[1]
lines = ["Review package smoke.", "Evidence line with page image."]
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

"$JAVA_BIN" -jar "$CLI_JAR" review-package "$PDF" -o "$OUT_DIR" > "$WORK_DIR/review.out"

test -s "$OUT_DIR/review.html"
test -s "$OUT_DIR/trust-document.json"
test -s "$OUT_DIR/content_blocks.json"
test -s "$OUT_DIR/parse_trace.json"
test -s "$OUT_DIR/layout-debug.html"
test -s "$OUT_DIR/span-debug.html"
test -s "$OUT_DIR/pages/page-0001.png"
test -s "$OUT_DIR/pages/page-images.json"
grep -q "review-package:" "$WORK_DIR/review.out"
grep -q "pages: 1" "$WORK_DIR/review.out"
grep -q "pages/page-0001.png" "$OUT_DIR/review.html"
grep -q "data-trust-page-number=\"1\"" "$OUT_DIR/review.html"
grep -q "data-doctruth-debug-artifact=\"layout\"" "$OUT_DIR/layout-debug.html"
grep -q "data-doctruth-debug-artifact=\"span\"" "$OUT_DIR/span-debug.html"

python3 - "$OUT_DIR/pages/page-images.json" "$OUT_DIR/pages/page-0001.png" "$OUT_DIR/trust-document.json" "$OUT_DIR/content_blocks.json" "$OUT_DIR/parse_trace.json" "$OUT_DIR/layout-debug.html" "$OUT_DIR/span-debug.html" <<'PY'
import hashlib
import json
import pathlib
import sys

manifest = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
png = pathlib.Path(sys.argv[2]).read_bytes()
doc = json.loads(pathlib.Path(sys.argv[3]).read_text(encoding="utf-8"))
content_blocks = json.loads(pathlib.Path(sys.argv[4]).read_text(encoding="utf-8"))
parse_trace = json.loads(pathlib.Path(sys.argv[5]).read_text(encoding="utf-8"))
layout_html = pathlib.Path(sys.argv[6]).read_text(encoding="utf-8")
span_html = pathlib.Path(sys.argv[7]).read_text(encoding="utf-8")
page = manifest["pages"][0]
assert manifest["sourceFilename"] == "review-package-smoke.pdf"
assert len(manifest["pages"]) == 1
assert page["path"] == "page-0001.png"
assert png.startswith(b"\x89PNG\r\n\x1a\n")
assert page["imageHash"] == "sha256:" + hashlib.sha256(png).hexdigest()
assert doc["source"]["sourceFilename"] == "review-package-smoke.pdf"
assert doc["body"]["pages"][0]["imageHash"] == page["imageHash"]
assert content_blocks["format"] == "doctruth.content_blocks.v1"
assert content_blocks["contentBlocks"][0]["blockId"] == "block-0001"
assert parse_trace["format"] == "doctruth.parse_trace.v1"
trace_page = parse_trace["parseTrace"]["pages"][0]
assert set(trace_page["pageSize"]) == {"width", "height"}
block = trace_page["readingBlocks"][0]
line = block["lines"][0]
span = line["spans"][0]
assert f'data-trace-block-id="{block["blockId"]}"' in layout_html
assert f'data-trace-block-id="{block["blockId"]}"' in span_html
assert f'data-trace-line-id="{line["lineId"]}"' in span_html
assert f'data-trace-span-id="{span["spanId"]}"' in span_html
PY

echo "doctruth review package smoke passed"
