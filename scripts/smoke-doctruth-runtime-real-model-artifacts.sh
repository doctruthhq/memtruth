#!/usr/bin/env sh
set -eu
export DOCTRUTH_ALLOW_PYTHON_ORACLE="${DOCTRUTH_ALLOW_PYTHON_ORACLE:-1}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ "${DOCTRUTH_RUNTIME_REAL_MODEL_ARTIFACTS:-0}" != "1" ]; then
    echo "skipping Rust runtime real model artifact smoke; set DOCTRUTH_RUNTIME_REAL_MODEL_ARTIFACTS=1"
    exit 0
fi

if command -v python3 >/dev/null 2>&1; then
    PYTHON="$(command -v python3)"
else
    echo "python3 is required for Rust runtime real model artifact smoke" >&2
    exit 1
fi

MANIFEST_PATH="runtime/doctruth-runtime/Cargo.toml"
cargo build --manifest-path "$MANIFEST_PATH" >/dev/null
BIN="runtime/doctruth-runtime/target/debug/doctruth-runtime"
WORK_DIR="${DOCTRUTH_RUNTIME_REAL_MODEL_ARTIFACTS_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/doctruth-runtime-real-models.XXXXXX")}"
CACHE="${DOCTRUTH_RUNTIME_REAL_MODEL_CACHE:-target/runtime-real-model-cache}"
case "$CACHE" in
    /*) ;;
    *) CACHE="$ROOT/$CACHE" ;;
esac
mkdir -p "$WORK_DIR" "$CACHE"

scripts/doctruth-onnx-model-worker --doctor > "$WORK_DIR/onnx-doctor.json"

prepare_case() {
    repo="$1"
    variant="$2"
    preset="$3"
    name="$4"
    task="$5"
    precision="$6"
    pdf="$7"
    manifest="$8"
    "$PYTHON" - "$repo" "$variant" "$CACHE" "$preset" "$name" "$task" "$precision" "$pdf" "$manifest" <<'PY'
import hashlib
import json
import pathlib
import re
import sys
import urllib.request

repo, variant, cache, preset, name, task, precision, pdf, manifest = sys.argv[1:10]
cache = pathlib.Path(cache)
pdf = pathlib.Path(pdf)
manifest = pathlib.Path(manifest)
cache.mkdir(parents=True, exist_ok=True)
version = pathlib.Path(variant).name.replace(".onnx", "")

downloaded = cache / (repo.replace("/", "__") + "__" + variant.replace("/", "__"))
url = f"https://huggingface.co/{repo}/resolve/main/{variant}"
if not downloaded.exists():
    with urllib.request.urlopen(url, timeout=300) as response:
        tmp = downloaded.with_suffix(downloaded.suffix + ".tmp")
        with tmp.open("wb") as handle:
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                handle.write(chunk)
        tmp.replace(downloaded)
payload = downloaded.read_bytes()
sha = "sha256:" + hashlib.sha256(payload).hexdigest()

def sanitize(value: str) -> str:
    return "".join(ch if re.match(r"[A-Za-z0-9._-]", ch) else "_" for ch in value)

runtime_cache_path = cache / f"{sanitize(name)}-{sanitize(version)}.bin"
if not runtime_cache_path.exists() or runtime_cache_path.read_bytes() != payload:
    runtime_cache_path.write_bytes(payload)

lines = [
    "BT /F1 28 Tf 72 720 Td (DocTruth Runtime Model Smoke) Tj ET",
    "BT /F1 14 Tf 72 675 Td (Runtime entrypoint should call the ONNX model worker.) Tj ET",
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

manifest.write_text(json.dumps({
    "presets": {
        preset: [{
            "name": name,
            "version": version,
            "source": str(downloaded),
            "sha256": sha,
            "sizeBytes": len(payload),
            "required": True,
            "task": task,
            "backend": "onnxruntime",
            "format": "onnx",
            "precision": precision,
            "license": "external-model-license",
        }]
    }
}, separators=(",", ":")), encoding="utf-8")
print("sha256:" + hashlib.sha256(raw).hexdigest())
PY
}

run_runtime_case() {
    preset="$1"
    expected_id="$2"
    expected_kind="$3"
    pdf="$4"
    manifest="$5"
    report="$6"
    source_hash="$(prepare_case "$7" "$8" "$preset" "$9" "${10}" "${11}" "$pdf" "$manifest")"
    DOCTRUTH_MODEL_MANIFEST="$manifest" \
    DOCTRUTH_MODEL_CACHE="$CACHE" \
    DOCTRUTH_RUNTIME_MODEL_COMMAND="$ROOT/scripts/doctruth-onnx-model-worker" \
        "$BIN" <<EOF_REQUEST > "$report"
{"command":"parse_pdf","source_path":"$pdf","source_hash":"$source_hash","preset":"$preset","offline_mode":true,"allow_model_downloads":false}
EOF_REQUEST
    "$PYTHON" - "$report" "$expected_id" "$expected_kind" <<'PY'
import json
import pathlib
import sys

report = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
expected_id = sys.argv[2]
expected_kind = sys.argv[3]
parser = report["parserRun"]
assert parser["backend"] == "rust-sidecar+model-worker", parser
assert parser.get("workerBackend") == "pdfbox+model-worker", parser
assert parser.get("runtime") == "doctruth-runtime", parser
assert expected_id in parser["models"], parser
assert report["auditGradeStatus"] in {"AUDIT_GRADE", "UNKNOWN", "NOT_AUDIT_GRADE"}, report
units = report["body"]["units"]
assert units, report["body"]
if expected_kind == "TABLE_CELL":
    assert any(unit["kind"] == "TABLE_CELL" for unit in units), units
    assert report["body"]["tables"], report["body"]
else:
    assert any(unit["kind"] == "TEXT_BLOCK" for unit in units), units
PY
}

run_runtime_case \
    "standard" \
    "kreuzberg-rtdetr-layout:model" \
    "TEXT_BLOCK" \
    "$WORK_DIR/rtdetr.pdf" \
    "$WORK_DIR/rtdetr-manifest.json" \
    "$WORK_DIR/rtdetr-report.json" \
    "Kreuzberg/layout-models" \
    "${DOCTRUTH_REAL_RTDETR_VARIANT:-rtdetr/model.onnx}" \
    "kreuzberg-rtdetr-layout" \
    "layout-detection" \
    "fp32"

run_runtime_case \
    "table-lite" \
    "xenova-table-transformer-structure-recognition:model_quantized" \
    "TABLE_CELL" \
    "$WORK_DIR/tatr.pdf" \
    "$WORK_DIR/tatr-manifest.json" \
    "$WORK_DIR/tatr-report.json" \
    "Xenova/table-transformer-structure-recognition" \
    "${DOCTRUTH_REAL_TATR_VARIANT:-onnx/model_quantized.onnx}" \
    "xenova-table-transformer-structure-recognition" \
    "table-structure-recognition" \
    "quantized"

echo "doctruth Rust runtime real model artifact smoke passed"
