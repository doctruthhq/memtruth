#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
MANIFEST="$ROOT_DIR/runtime/doctruth-runtime/Cargo.toml"
BIN="$ROOT_DIR/runtime/doctruth-runtime/target/debug/doctruth-runtime"
WORK_DIR="${TMPDIR:-/tmp}/doctruth-runtime-real-model-suite-smoke-$$"

cargo test --manifest-path "$MANIFEST" --test model_worker_contract >/dev/null
mkdir -p "$WORK_DIR"

python3 - "$WORK_DIR/fixture.pdf" <<'PY'
import sys

path = sys.argv[1]
text = "Fallback real model suite text should not be used."
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

cat > "$WORK_DIR/fake-real-model-worker.py" <<'PY'
#!/usr/bin/env python3
import json
import sys

request = json.load(sys.stdin)
identities = [model["identity"] for model in request["requiredModels"]]
assert request["runtime"] == "doctruth-runtime", request
assert request["command"] == "parse_pdf", request
assert request["preset"] == "standard", request
assert identities == ["layout-rtdetr:v2", "tatr:v1"], identities
print(json.dumps({
    "docId": request["source_hash"],
    "source": {
        "sourceFilename": "runtime-real-model-suite.pdf",
        "sourceHash": request["source_hash"],
        "metadata": {"sourceFilename": "runtime-real-model-suite.pdf", "pageCount": 1}
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
            "kind": "TEXT_BLOCK",
            "page": 1,
            "text": "Runtime real model suite worker evidence",
            "evidenceSpanIds": ["span-0001"],
            "location": {
                "page": 1,
                "readingOrder": 1,
                "boundingBox": {"x0": 72.0, "y0": 90.0, "x1": 540.0, "y1": 132.0}
            },
            "sourceObjectId": "worker-layout-1",
            "confidence": {"score": 0.97, "rationale": "fake real-model-suite worker"},
            "warnings": []
        }],
        "tables": []
    },
    "parserRun": {
        "parserRunId": "runtime-real-model-suite-smoke",
        "parserVersion": "smoke-worker",
        "preset": request["preset"],
        "backend": "rust-sidecar+model-worker",
        "models": ["layout-rtdetr:v2", "tatr:v1"],
        "runtime": request["runtime"],
        "modelWorker": {
            "mode": "fake" if request.get("offline_mode", True) else "real",
            "entrypoint": "DOCTRUTH_RUNTIME_MODEL_COMMAND"
        },
        "warnings": []
    },
    "auditGradeStatus": "AUDIT_GRADE"
}))
PY
chmod +x "$WORK_DIR/fake-real-model-worker.py"

MODEL_COMMAND="${DOCTRUTH_RUNTIME_REAL_MODEL_COMMAND:-$WORK_DIR/fake-real-model-worker.py}"
REPORT="$(DOCTRUTH_RUNTIME_MODEL_COMMAND="$MODEL_COMMAND" "$BIN" <<EOF_REQUEST
{"command":"parse_pdf","source_path":"$WORK_DIR/fixture.pdf","source_hash":"sha256:runtime-real-model-suite-smoke","preset":"standard","offline_mode":true,"allow_model_downloads":false}
EOF_REQUEST
)"
printf '%s\n' "$REPORT" > "$WORK_DIR/report.json"

python3 - "$WORK_DIR/report.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    report = json.load(handle)
assert report["docId"] == "sha256:runtime-real-model-suite-smoke", report
assert report["parserRun"]["backend"] == "rust-sidecar+model-worker", report["parserRun"]
assert report["parserRun"]["preset"] == "standard", report["parserRun"]
assert "layout-rtdetr:v2" in report["parserRun"]["models"], report["parserRun"]
assert "tatr:v1" in report["parserRun"]["models"], report["parserRun"]
assert report["parserRun"].get("runtime") == "doctruth-runtime", report["parserRun"]
assert report["parserRun"].get("modelWorker"), report["parserRun"]
assert report["auditGradeStatus"] == "AUDIT_GRADE", report
assert report["body"]["units"][0]["text"] == "Runtime real model suite worker evidence", report["body"]
PY

rm -rf "$WORK_DIR"
echo "doctruth-runtime real model suite smoke passed"
