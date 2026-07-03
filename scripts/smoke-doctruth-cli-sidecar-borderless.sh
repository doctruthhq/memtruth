#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
MANIFEST="$ROOT_DIR/runtime/doctruth-runtime/Cargo.toml"
RUNTIME_BIN="$ROOT_DIR/runtime/doctruth-runtime/target/debug/doctruth-runtime"
CLI_JAR="$ROOT_DIR/target/doctruth-java-0.2.0-alpha-all.jar"
WORK_DIR="${TMPDIR:-/tmp}/doctruth-cli-sidecar-borderless-smoke"
PDF="$WORK_DIR/sidecar-borderless-table-smoke.pdf"
JSON_OUT="$WORK_DIR/sidecar-borderless-table-smoke.json"
MD_OUT="$WORK_DIR/sidecar-borderless-table-smoke.md"
PLAIN_OUT="$WORK_DIR/sidecar-borderless-table-smoke.txt"

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
stream = """BT
/F1 16 Tf
90 700 Td
(Name) Tj
144 0 Td
(Score) Tj
-144 -40 Td
(Alex) Tj
144 0 Td
(98) Tj
ET
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

"$JAVA_BIN" -jar "$CLI_JAR" parse "$PDF" \
  --backend sidecar \
  --runtime "$RUNTIME_BIN" \
  --preset lite \
  --format json \
  --profile full \
  --out "$JSON_OUT"

python3 - "$JSON_OUT" <<'PY'
import json, sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)
tables = data["body"]["tables"]
units = data["body"]["units"]
table_units = [unit for unit in units if unit["kind"] == "TABLE_CELL"]
assert data["parserRun"]["backend"] == "rust-sidecar"
assert len(tables) == 1
assert tables[0]["confidence"]["rationale"] == "borderless aligned text table extraction"
assert len(tables[0]["cells"]) == 4
assert len(table_units) == 4
assert [cell["text"] for cell in tables[0]["cells"]] == ["Name", "Score", "Alex", "98"]
assert all("boundingBox" in cell for cell in tables[0]["cells"])
assert all("boundingBox" in unit["location"] for unit in table_units)
PY

"$JAVA_BIN" -jar "$CLI_JAR" parse "$PDF" \
  --backend sidecar \
  --runtime "$RUNTIME_BIN" \
  --preset lite \
  --format markdown \
  --profile clean \
  --out "$MD_OUT"

grep -q "| Name | Score |" "$MD_OUT"
grep -q "| --- | --- |" "$MD_OUT"
grep -q "| Alex | 98 |" "$MD_OUT"

"$JAVA_BIN" -jar "$CLI_JAR" parse "$PDF" \
  --backend sidecar \
  --runtime "$RUNTIME_BIN" \
  --preset lite \
  --format plain \
  --out "$PLAIN_OUT"

grep -q "Name	Score" "$PLAIN_OUT"
grep -q "Alex	98" "$PLAIN_OUT"
if grep -q "| --- |" "$PLAIN_OUT"; then
  echo "plain output leaked markdown table syntax" >&2
  exit 1
fi
if grep -q "{#ev:" "$PLAIN_OUT"; then
  echo "plain output leaked evidence anchors" >&2
  exit 1
fi

echo "doctruth CLI sidecar borderless smoke passed"
