#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
CLI_JAR="$ROOT_DIR/target/doctruth-java-0.2.0-alpha-all.jar"
WORK_DIR="${TMPDIR:-/tmp}/doctruth-benchmark-oracle-smoke"
PDF="$WORK_DIR/oracle-smoke.pdf"
VENDORED_PDF="$ROOT_DIR/third_party/opendataloader-bench/pdfs/01030000000119.pdf"
ORACLE="$WORK_DIR/fake-opendataloader-hybrid-oracle"
JSON_OUT="$WORK_DIR/oracle-smoke.trust.json"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
elif [ -x "/opt/homebrew/opt/openjdk/bin/java" ]; then
  JAVA_BIN="/opt/homebrew/opt/openjdk/bin/java"
else
  JAVA_BIN="java"
fi

mkdir -p "$WORK_DIR"
mvn -q -DskipTests package >/dev/null

if [ -f "$VENDORED_PDF" ]; then
  PDF="$VENDORED_PDF"
else
  python3 - "$PDF" <<'PY'
import sys

path = sys.argv[1]
stream = "BT\n/F1 20 Tf\n72 720 Td\n(OpenDataLoader oracle smoke source) Tj\nET\n"
objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    f"<< /Length {len(stream.encode())} >>\nstream\n{stream}endstream",
]
pdf = bytearray(b"%PDF-1.4\n")
offsets = []
for index, obj in enumerate(objects, start=1):
    offsets.append(len(pdf))
    pdf.extend(f"{index} 0 obj\n{obj}\nendobj\n".encode())
xref = len(pdf)
pdf.extend(f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode())
for offset in offsets:
    pdf.extend(f"{offset:010} 00000 n \n".encode())
pdf.extend(f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
with open(path, "wb") as handle:
    handle.write(pdf)
PY
fi

cat >"$ORACLE" <<'SH'
#!/usr/bin/env sh
cat <<'JSON'
{
  "markdown": "# Oracle Smoke Title\n\nOracle smoke body.",
  "elapsedMs": 321,
  "externalBackend": {
    "name": "opendataloader-pdf",
    "version": "2.2.1",
    "doclingVersion": "2.84.0",
    "mode": "docling-fast",
    "serverUrl": "http://127.0.0.1:5002",
    "rssMb": "1510"
  }
}
JSON
SH
chmod +x "$ORACLE"

DOCTRUTH_OPENDATALOADER_HYBRID_ORACLE_COMMAND="$ORACLE" \
  "$JAVA_BIN" -jar "$CLI_JAR" benchmark-oracle --engine opendataloader-hybrid "$PDF" --json >"$JSON_OUT"

python3 - "$JSON_OUT" <<'PY'
import json
import sys

path = sys.argv[1]
with open(path, encoding="utf-8") as handle:
    doc = json.load(handle)

parser = doc["parserRun"]
assert parser["backend"] == "opendataloader-hybrid-oracle", parser
assert parser["externalBackend"]["name"] == "opendataloader-pdf", parser
assert parser["externalBackend"]["doclingVersion"] == "2.84.0", parser
assert parser["elapsedMs"] == 321, parser
assert doc["auditGradeStatus"] == "NOT_AUDIT_GRADE", doc["auditGradeStatus"]
assert doc["body"]["units"][0]["text"] == "Oracle Smoke Title", doc["body"]["units"][0]
assert parser["warnings"][0]["code"] == "opendataloader_markdown_only_source_mapping", parser
PY

printf 'benchmark oracle smoke passed: %s\n' "$JSON_OUT"
