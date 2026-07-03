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
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-mcp-smoke.XXXXXX")"
PDF="$WORK_DIR/mcp-smoke.pdf"
TABLE_PDF="$WORK_DIR/mcp-table-smoke.pdf"
MODEL_DIR="$WORK_DIR/models"
REQUESTS="$WORK_DIR/requests.jsonl"
RESPONSES="$WORK_DIR/responses.jsonl"

python3 - "$PDF" <<'PY'
import sys

path = sys.argv[1]
lines = ["MCP smoke evidence line.", "Replayable source span."]
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
stream = """0.8 w
72 650 m 272 650 l
72 620 m 272 620 l
72 590 m 272 590 l
72 650 m 72 590 l
172 650 m 172 590 l
272 650 m 272 590 l
S
BT
/F1 12 Tf
84 630 Td
(Name) Tj
100 0 Td
(Score) Tj
-100 -30 Td
(Alex) Tj
100 0 Td
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

mkdir -p "$MODEL_DIR"
printf 'local model bytes' > "$MODEL_DIR/layout-v1.bin"
MODEL_SHA="$(python3 - "$MODEL_DIR/layout-v1.bin" <<'PY'
import hashlib
import pathlib
import sys

print("sha256:" + hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())
PY
)"

python3 - "$PDF" "$TABLE_PDF" "$MODEL_DIR" "$MODEL_SHA" "$REQUESTS" <<'PY'
import json
import pathlib
import sys

pdf = pathlib.Path(sys.argv[1])
table_pdf = pathlib.Path(sys.argv[2])
model_dir = pathlib.Path(sys.argv[3])
model_sha = sys.argv[4]
requests = pathlib.Path(sys.argv[5])
lines = [
    {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": {
            "protocolVersion": "2025-06-18",
            "capabilities": {},
            "clientInfo": {"name": "smoke-agent", "version": "1"},
        },
    },
    {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}},
    {
        "jsonrpc": "2.0",
        "id": 3,
        "method": "tools/call",
        "params": {
            "name": "doctruth.parse_document",
            "arguments": {"path": str(pdf), "format": "compact_llm", "sourceMap": True},
        },
    },
    {
        "jsonrpc": "2.0",
        "id": 4,
        "method": "tools/call",
        "params": {"name": "doctruth.get_layout_regions", "arguments": {"path": str(pdf)}},
    },
    {
        "jsonrpc": "2.0",
        "id": 5,
        "method": "tools/call",
        "params": {"name": "doctruth.get_table_cells", "arguments": {"path": str(table_pdf)}},
    },
    {
        "jsonrpc": "2.0",
        "id": 6,
        "method": "tools/call",
        "params": {
            "name": "doctruth.get_evidence_span",
            "arguments": {"path": str(pdf), "evidenceSpanId": "span-0001"},
        },
    },
    {
        "jsonrpc": "2.0",
        "id": 7,
        "method": "tools/call",
        "params": {
            "name": "doctruth.verify_citation",
            "arguments": {
                "path": str(pdf),
                "evidenceSpanId": "span-0001",
                "quote": "MCP smoke evidence line.",
            },
        },
    },
    {
        "jsonrpc": "2.0",
        "id": 8,
        "method": "tools/call",
        "params": {
            "name": "doctruth.warm_model_cache",
            "arguments": {
                "cacheDir": str(model_dir),
                "models": [
                    {
                        "name": "layout",
                        "version": "v1",
                        "sha256": model_sha,
                        "sizeBytes": 17,
                        "required": True,
                    }
                ],
            },
        },
    },
]
requests.write_text("\n".join(json.dumps(line, separators=(",", ":")) for line in lines) + "\n", encoding="utf-8")
PY

"$JAVA_BIN" -jar "$CLI_JAR" mcp < "$REQUESTS" > "$RESPONSES"

python3 - "$RESPONSES" <<'PY'
import json
import pathlib
import sys

responses = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines()]
assert len(responses) == 8
assert responses[0]["result"]["serverInfo"]["name"] == "doctruth"
tool_names = [tool["name"] for tool in responses[1]["result"]["tools"]]
assert "doctruth.parse_document" in tool_names
assert "doctruth.get_layout_regions" in tool_names
assert "doctruth.get_table_cells" in tool_names
assert "doctruth.get_evidence_span" in tool_names
assert "doctruth.verify_citation" in tool_names
assert "doctruth.warm_model_cache" in tool_names
result = responses[2]["result"]
structured = result["structuredContent"]
assert result["isError"] is False
assert "MCP smoke evidence line." in structured["compact"]
assert structured["jsonEvidence"]["units"][0]["evidenceSpanIds"][0].startswith("span-")
assert "boundingBox" in structured["jsonEvidence"]["units"][0]["location"]
assert structured["sourceMap"]["sourceMap"][0]["unitId"].startswith("unit-")
regions = responses[3]["result"]["structuredContent"]["regions"]
assert regions[0]["unitId"].startswith("unit-")
assert "boundingBox" in regions[0]
tables = responses[4]["result"]["structuredContent"]["tables"]
assert tables
cell_text = [cell["text"] for cell in tables[0]["cells"]]
assert "Name" in cell_text and "Score" in cell_text and "Alex" in cell_text and "98" in cell_text
assert "boundingBox" in tables[0]["cells"][0]
span = responses[5]["result"]["structuredContent"]["span"]
assert span["evidenceSpanId"] == "span-0001"
assert "MCP smoke evidence line." in span["text"]
verification = responses[6]["result"]["structuredContent"]["verification"]
assert verification["verified"] is True
assert verification["matchScore"] == 1.0
cache = responses[7]["result"]["structuredContent"]
assert cache["allReady"] is True
assert cache["networkAccessRequired"] is False
assert cache["artifacts"][0]["status"] == "READY"
PY

echo "doctruth MCP smoke passed"
