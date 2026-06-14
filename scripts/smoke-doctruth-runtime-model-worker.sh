#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
MANIFEST="$ROOT_DIR/runtime/doctruth-runtime/Cargo.toml"
BIN="$ROOT_DIR/runtime/doctruth-runtime/target/debug/doctruth-runtime"
WORK_DIR="${TMPDIR:-/tmp}/doctruth-runtime-model-worker-smoke-$$"

cargo test --manifest-path "$MANIFEST" --test model_worker_contract >/dev/null
mkdir -p "$WORK_DIR"

python3 - "$WORK_DIR/fixture.pdf" <<'PY'
import sys

path = sys.argv[1]
text = "Fallback text should not be used."
stream = f"BT\n/F1 16 Tf\n72 700 Td\n({text}) Tj\nET\n"
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

cat > "$WORK_DIR/model-worker.py" <<'PY'
#!/usr/bin/env python3
import json
import sys

request = json.load(sys.stdin)
assert request["preset"] == "table-lite"
assert request["requiredModels"][0]["identity"] == "slanet-plus:v1"
print(json.dumps({
    "docId": request["source_hash"],
    "source": {
        "sourceFilename": "worker.pdf",
        "sourceHash": request["source_hash"],
        "metadata": {"sourceFilename": "worker.pdf", "pageCount": 1}
    },
    "body": {
        "pages": [{
            "pageNumber": 1,
            "width": 612.0,
            "height": 792.0,
            "textLayerAvailable": True,
            "imageHash": "sha256:" + "0" * 64
        }],
        "units": [{
            "unitId": "unit-0001",
            "kind": "TABLE_CELL",
            "page": 1,
            "text": "Worker smoke evidence",
            "evidenceSpanIds": ["span-0001"],
            "location": {
                "page": 1,
                "readingOrder": 1,
                "boundingBox": {"x0": 0.0, "y0": 0.0, "x1": 1000.0, "y1": 1000.0}
            },
            "sourceObjectId": "worker-cell-1",
            "confidence": {"score": 0.93, "rationale": "fake model worker"},
            "warnings": []
        }],
        "tables": []
    },
    "parserRun": {
        "parserVersion": "smoke-worker",
        "preset": request["preset"],
        "backend": "rust-sidecar+model-worker",
        "models": ["slanet-plus:v1"],
        "warnings": []
    },
    "auditGradeStatus": "AUDIT_GRADE"
}))
PY
chmod +x "$WORK_DIR/model-worker.py"

REPORT="$(DOCTRUTH_RUNTIME_MODEL_COMMAND="$WORK_DIR/model-worker.py" "$BIN" <<EOF_REQUEST
{"command":"parse_pdf","source_path":"$WORK_DIR/fixture.pdf","source_hash":"sha256:model-worker-smoke","preset":"table-lite","offline_mode":true,"allow_model_downloads":false}
EOF_REQUEST
)"
printf '%s\n' "$REPORT" > "$WORK_DIR/report.json"

python3 - "$WORK_DIR/report.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    report = json.load(handle)
assert report["docId"] == "sha256:model-worker-smoke"
assert report["parserRun"]["backend"] == "rust-sidecar+model-worker"
assert report["parserRun"]["models"] == ["slanet-plus:v1"]
assert report["auditGradeStatus"] == "AUDIT_GRADE"
assert report["body"]["units"][0]["text"] == "Worker smoke evidence"
PY

rm -rf "$WORK_DIR"
echo "doctruth-runtime model worker smoke passed"
