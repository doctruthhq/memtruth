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
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-page-images-smoke.XXXXXX")"
PDF="$WORK_DIR/page-image-smoke.pdf"
OUT_DIR="$WORK_DIR/pages"

python3 - "$PDF" <<'PY'
import sys

path = sys.argv[1]
lines = ["Rendered page image smoke.", "Evidence line for hash check."]
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

"$JAVA_BIN" -jar "$CLI_JAR" render-pages "$PDF" -o "$OUT_DIR" > "$WORK_DIR/render.out"

test -s "$OUT_DIR/page-0001.png"
test -s "$OUT_DIR/page-images.json"
grep -q "pages: 1" "$WORK_DIR/render.out"
grep -q "page-images:" "$WORK_DIR/render.out"

python3 - "$OUT_DIR/page-images.json" "$OUT_DIR/page-0001.png" <<'PY'
import hashlib
import json
import pathlib
import sys

manifest = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
png = pathlib.Path(sys.argv[2]).read_bytes()
page = manifest["pages"][0]
assert manifest["sourceFilename"] == "page-image-smoke.pdf"
assert len(manifest["pages"]) == 1
assert page["pageNumber"] == 1
assert page["width"] > 0
assert page["height"] > 0
assert page["textLayerAvailable"] is True
assert page["path"] == "page-0001.png"
assert png.startswith(b"\x89PNG\r\n\x1a\n")
assert page["imageHash"] == "sha256:" + hashlib.sha256(png).hexdigest()
PY

echo "doctruth page image smoke passed"
