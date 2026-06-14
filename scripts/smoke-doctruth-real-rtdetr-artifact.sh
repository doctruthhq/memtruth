#!/usr/bin/env sh
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ "${DOCTRUTH_REAL_RTDETR_SMOKE:-0}" != "1" ]; then
    echo "skipping real RT-DETR artifact smoke; set DOCTRUTH_REAL_RTDETR_SMOKE=1 to download/run the public ONNX artifact"
    exit 0
fi

if command -v python3 >/dev/null 2>&1; then
    PYTHON="$(command -v python3)"
else
    echo "python3 is required for real RT-DETR artifact smoke" >&2
    exit 1
fi

REPO="Kreuzberg/layout-models"
VARIANT="${DOCTRUTH_REAL_RTDETR_VARIANT:-rtdetr/model.onnx}"
CACHE="${DOCTRUTH_REAL_RTDETR_CACHE:-target/real-rtdetr-cache}"
case "$CACHE" in
    /*) ;;
    *) CACHE="$ROOT/$CACHE" ;;
esac
WORK_DIR="${DOCTRUTH_REAL_RTDETR_SMOKE_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/doctruth-real-rtdetr.XXXXXX")}"
MODEL="$CACHE/$(basename "$(dirname "$VARIANT")")-$(basename "$VARIANT")"
MANIFEST="$WORK_DIR/rtdetr-manifest.json"
PDF="$WORK_DIR/rtdetr-layout-input.pdf"
REQUEST="$WORK_DIR/rtdetr-worker-request.json"
WORKER_OUT="$WORK_DIR/rtdetr-worker-output.json"
mkdir -p "$CACHE" "$WORK_DIR"

scripts/doctruth-onnx-model-worker --doctor > "$WORK_DIR/onnx-doctor.json"

"$PYTHON" - "$REPO" "$VARIANT" "$MODEL" "$MANIFEST" <<'PY'
import hashlib
import json
import pathlib
import sys
import urllib.request

repo, variant, model_path, manifest_path = sys.argv[1:5]
model_path = pathlib.Path(model_path)
manifest_path = pathlib.Path(manifest_path)
url = f"https://huggingface.co/{repo}/resolve/main/{variant}"
if not model_path.exists():
    with urllib.request.urlopen(url, timeout=300) as response:
        tmp = model_path.with_suffix(model_path.suffix + ".tmp")
        with tmp.open("wb") as handle:
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                handle.write(chunk)
        tmp.replace(model_path)
payload = model_path.read_bytes()
manifest_path.write_text(json.dumps({
    "presets": {
        "standard": [{
            "name": "kreuzberg-rtdetr-layout",
            "version": pathlib.Path(variant).name.replace(".onnx", ""),
            "source": str(model_path),
            "sha256": "sha256:" + hashlib.sha256(payload).hexdigest(),
            "sizeBytes": len(payload),
            "required": True,
            "task": "layout-detection",
            "backend": "onnxruntime",
            "format": "onnx",
            "precision": "fp32",
            "license": "apache-2.0",
        }]
    }
}, indent=2), encoding="utf-8")
PY

"$PYTHON" - "$PDF" "$REQUEST" "$MODEL" <<'PY'
import base64
import hashlib
import json
import pathlib
import sys

pdf = pathlib.Path(sys.argv[1])
request = pathlib.Path(sys.argv[2])
model = pathlib.Path(sys.argv[3])
lines = [
    "BT /F1 28 Tf 72 720 Td (Quarterly Operating Review) Tj ET",
    "BT /F1 14 Tf 72 675 Td (Revenue grew 18 percent while support backlog fell.) Tj ET",
    "BT /F1 14 Tf 72 650 Td (The table below summarizes the operating metrics.) Tj ET",
    "1 w",
    "72 540 m 540 540 l S",
    "72 504 m 540 504 l S",
    "72 468 m 540 468 l S",
    "72 432 m 540 432 l S",
    "72 432 m 72 540 l S",
    "228 432 m 228 540 l S",
    "384 432 m 384 540 l S",
    "540 432 m 540 540 l S",
    "BT /F1 12 Tf 96 516 Td (Metric) Tj ET",
    "BT /F1 12 Tf 252 516 Td (Q1) Tj ET",
    "BT /F1 12 Tf 408 516 Td (Q2) Tj ET",
]
stream = "\n".join(lines) + "\n"
objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    f"<< /Length {len(stream.encode())} >>\nstream\n{stream}endstream",
]
raw = bytearray(b"%PDF-1.4\n")
offsets = []
for index, obj in enumerate(objects, start=1):
    offsets.append(len(raw))
    raw.extend(f"{index} 0 obj\n{obj}\nendobj\n".encode())
xref = len(raw)
raw.extend(f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode())
for offset in offsets:
    raw.extend(f"{offset:010} 00000 n \n".encode())
raw.extend(f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
pdf.write_bytes(raw)
model_bytes = model.read_bytes()
request.write_text(json.dumps({
    "version": 1,
    "preset": "standard",
    "sourcePath": str(pdf),
    "sourceFilename": pdf.name,
    "sourceHash": "sha256:" + hashlib.sha256(raw).hexdigest(),
    "modelCacheDirectory": str(model.parent),
    "models": [{
        "name": "kreuzberg-rtdetr-layout",
        "version": model.name.replace(".onnx", ""),
        "sha256": "sha256:" + hashlib.sha256(model_bytes).hexdigest(),
        "sizeBytes": len(model_bytes),
        "required": True,
        "cachePath": str(model),
        "cacheStatus": "READY",
        "actualSha256": "sha256:" + hashlib.sha256(model_bytes).hexdigest(),
        "actualSizeBytes": len(model_bytes),
        "task": "layout-detection",
        "backend": "onnxruntime",
        "format": "onnx",
        "precision": "fp32",
        "license": "apache-2.0",
    }],
    "bytesBase64": base64.b64encode(raw).decode("ascii"),
}, separators=(",", ":")), encoding="utf-8")
PY

scripts/doctruth-onnx-model-worker < "$REQUEST" > "$WORKER_OUT"
"$PYTHON" - "$WORKER_OUT" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
assert payload["ok"] is True, payload
assert payload["metrics"]["inputSource"] == "rendered_page+orig_target_sizes", payload["metrics"]
doc = payload["document"]
assert doc["parserRun"]["backend"] == "pdfbox+model-worker", doc["parserRun"]
assert doc["body"]["units"], doc["body"]
assert any(unit["kind"] == "TEXT_BLOCK" for unit in doc["body"]["units"]), doc["body"]["units"]
assert all(unit["location"]["boundingBox"]["x0"] < unit["location"]["boundingBox"]["x1"] for unit in doc["body"]["units"]), doc["body"]["units"]
PY

DOCTRUTH_REAL_MODEL_MANIFEST="$MANIFEST" \
DOCTRUTH_REAL_MODEL_PRESET="standard" \
DOCTRUTH_REAL_MODEL_EXPECTED_ID="kreuzberg-rtdetr-layout:$(basename "$VARIANT" .onnx)" \
DOCTRUTH_REAL_MODEL_EXPECTED_TASK="layout-detection" \
DOCTRUTH_REAL_MODEL_CACHE="$CACHE/model-cache" \
DOCTRUTH_REAL_MODEL_SOURCE_PDF="$PDF" \
DOCTRUTH_REAL_MODEL_SMOKE_DIR="$WORK_DIR/harness" \
    scripts/smoke-doctruth-real-model-artifact.sh

echo "doctruth real RT-DETR artifact smoke passed"
