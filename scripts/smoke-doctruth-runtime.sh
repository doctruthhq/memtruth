#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
MANIFEST="$ROOT_DIR/runtime/doctruth-runtime/Cargo.toml"
BIN="$ROOT_DIR/runtime/doctruth-runtime/target/debug/doctruth-runtime"
PDF="${TMPDIR:-/tmp}/doctruth-runtime-smoke.pdf"
TABLE_PDF="${TMPDIR:-/tmp}/doctruth-runtime-table-smoke.pdf"
MERGED_TABLE_PDF="${TMPDIR:-/tmp}/doctruth-runtime-merged-table-smoke.pdf"
ROW_SPAN_TABLE_PDF="${TMPDIR:-/tmp}/doctruth-runtime-row-span-table-smoke.pdf"
BORDERLESS_TABLE_PDF="${TMPDIR:-/tmp}/doctruth-runtime-borderless-table-smoke.pdf"
CONTINUED_TABLE_PDF="${TMPDIR:-/tmp}/doctruth-runtime-continued-table-smoke.pdf"

cargo test --manifest-path "$MANIFEST" >/dev/null

python3 - "$PDF" <<'PY'
import sys

path = sys.argv[1]
text = "Rust sidecar smoke extraction works."
stream = f"BT\n/F1 24 Tf\n72 720 Td\n({text}) Tj\nET\n"
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

python3 - "$BORDERLESS_TABLE_PDF" <<'PY'
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

"$BIN" --doctor | python3 -c '
import json, sys
data = json.load(sys.stdin)
assert data["runtime"] == "doctruth-runtime"
assert data["protocol_version"] == "1"
assert data["local_first"] is True
assert data["rssMb"] > 0
assert data["peakMemoryMb"] >= data["rssMb"]
assert data["pdfBackend"]["target"] == "pdf_oxide"
assert data["pdfBackend"]["current"] == "pdf_oxide+lopdf"
assert data["pdfBackend"]["status"] == "PARTIAL"
'

printf '%s' "{\"command\":\"parse_pdf\",\"source_path\":\"$PDF\",\"source_hash\":\"sha256:smoke\",\"preset\":\"lite\",\"offline_mode\":true,\"allow_model_downloads\":false}" \
  | "$BIN" \
  | python3 -c '
import json, sys

data = json.load(sys.stdin)
assert data["docId"] == "sha256:smoke"
assert data["source"]["sourceFilename"] == "doctruth-runtime-smoke.pdf"
assert data["parserRun"]["backend"] == "rust-sidecar"
assert data["parserRun"]["pdfBackend"]["target"] == "pdf_oxide"
assert data["parserRun"]["pdfBackend"]["current"] == "pdf_oxide+lopdf"
assert data["parserRun"]["pdfBackend"]["status"] == "PARTIAL"
assert data["auditGradeStatus"] == "AUDIT_GRADE"
page = data["body"]["pages"][0]
assert page["textLayerAvailable"] is True
assert page["width"] == 612
assert page["height"] == 792
assert page["imageHash"].startswith("sha256:")
assert len(page["imageHash"]) == len("sha256:") + 64
assert "smoke" not in page["imageHash"]
assert data["body"]["units"][0]["text"] == "Rust sidecar smoke extraction works."
'

printf '%s' "{\"command\":\"parse_pdf\",\"source_path\":\"$PDF\",\"source_hash\":\"sha256:model-fallback-smoke\",\"preset\":\"table-lite\",\"offline_mode\":true,\"allow_model_downloads\":false}" \
  | "$BIN" \
  | python3 -c '
import json, sys
data = json.load(sys.stdin)
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
assert data["body"]["units"][0]["text"] == "Rust sidecar smoke extraction works."
'

printf '%s' "{\"command\":\"parse_pdf\",\"source_path\":\"$TABLE_PDF\",\"source_hash\":\"sha256:table-smoke\",\"preset\":\"lite\",\"offline_mode\":true,\"allow_model_downloads\":false}" \
  | "$BIN" \
  | python3 -c '
import json, sys
data = json.load(sys.stdin)
tables = data["body"]["tables"]
units = data["body"]["units"]
table_units = [unit for unit in units if unit["kind"] == "TABLE_CELL"]
assert len(tables) == 1
assert len(tables[0]["cells"]) == 4
assert len(table_units) == 4
assert tables[0]["cells"][0]["text"] == "Name"
assert tables[0]["cells"][1]["text"] == "Score"
assert tables[0]["cells"][2]["text"] == "Alex"
assert tables[0]["cells"][3]["text"] == "98"
assert "boundingBox" in tables[0]
assert all("boundingBox" in cell for cell in tables[0]["cells"])
assert all("boundingBox" in unit["location"] for unit in table_units)
'

printf '%s' "{\"command\":\"parse_pdf\",\"source_path\":\"$BORDERLESS_TABLE_PDF\",\"source_hash\":\"sha256:borderless-table-smoke\",\"preset\":\"lite\",\"offline_mode\":true,\"allow_model_downloads\":false}" \
  | "$BIN" \
  | python3 -c '
import json, sys
data = json.load(sys.stdin)
tables = data["body"]["tables"]
units = data["body"]["units"]
table_units = [unit for unit in units if unit["kind"] == "TABLE_CELL"]
assert len(tables) == 1
assert tables[0]["confidence"]["rationale"] == "borderless aligned text table extraction"
assert len(tables[0]["cells"]) == 4
assert len(table_units) == 4
assert [cell["text"] for cell in tables[0]["cells"]] == ["Name", "Score", "Alex", "98"]
assert all("boundingBox" in cell for cell in tables[0]["cells"])
assert all("boundingBox" in unit["location"] for unit in table_units)
	'

printf '%s' "{\"command\":\"parse_pdf\",\"source_path\":\"$MERGED_TABLE_PDF\",\"source_hash\":\"sha256:merged-table-smoke\",\"preset\":\"lite\",\"offline_mode\":true,\"allow_model_downloads\":false}" \
  | "$BIN" \
  | python3 -c '
import json, sys
data = json.load(sys.stdin)
tables = data["body"]["tables"]
units = data["body"]["units"]
table_units = [unit for unit in units if unit["kind"] == "TABLE_CELL"]
assert len(tables) == 1
assert len(tables[0]["cells"]) == 3
assert len(table_units) == 3
assert [cell["text"] for cell in tables[0]["cells"]] == ["Header", "A", "B"]
assert tables[0]["cells"][0]["rowRange"] == {"start": 0, "end": 0}
assert tables[0]["cells"][0]["columnRange"] == {"start": 0, "end": 1}
assert tables[0]["cells"][1]["columnRange"] == {"start": 0, "end": 0}
assert tables[0]["cells"][2]["columnRange"] == {"start": 1, "end": 1}
assert all("boundingBox" in cell for cell in tables[0]["cells"])
assert all("boundingBox" in unit["location"] for unit in table_units)
'

printf '%s' "{\"command\":\"parse_pdf\",\"source_path\":\"$ROW_SPAN_TABLE_PDF\",\"source_hash\":\"sha256:row-span-table-smoke\",\"preset\":\"lite\",\"offline_mode\":true,\"allow_model_downloads\":false}" \
  | "$BIN" \
  | python3 -c '
import json, sys
data = json.load(sys.stdin)
tables = data["body"]["tables"]
units = data["body"]["units"]
table_units = [unit for unit in units if unit["kind"] == "TABLE_CELL"]
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
assert all("boundingBox" in cell for cell in tables[0]["cells"])
assert all("boundingBox" in unit["location"] for unit in table_units)
'

printf '%s' "{\"command\":\"parse_pdf\",\"source_path\":\"$CONTINUED_TABLE_PDF\",\"source_hash\":\"sha256:continued-table-smoke\",\"preset\":\"lite\",\"offline_mode\":true,\"allow_model_downloads\":false}" \
  | "$BIN" \
  | python3 -c '
import json, sys
data = json.load(sys.stdin)
tables = data["body"]["tables"]
units = data["body"]["units"]
table_units = [unit for unit in units if unit["kind"] == "TABLE_CELL"]
assert len(tables) == 1
assert tables[0]["pageNumber"] == 1
assert [cell["text"] for cell in tables[0]["cells"]] == ["Name", "Score", "Alex", "98", "Bea", "97"]
assert len(table_units) == 6
assert table_units[4]["text"] == "Bea"
assert table_units[4]["location"]["page"] == 2
assert table_units[5]["text"] == "97"
assert table_units[5]["location"]["page"] == 2
'

echo "doctruth-runtime smoke passed"
