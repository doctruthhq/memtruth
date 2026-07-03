#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
MANIFEST="$ROOT_DIR/runtime/doctruth-runtime/Cargo.toml"
RUNTIME_BIN="$ROOT_DIR/runtime/doctruth-runtime/target/debug/doctruth-runtime"
WORKER_BIN="$ROOT_DIR/runtime/doctruth-runtime/target/debug/doctruth-mnn-model-worker"
WORK_DIR="${TMPDIR:-/tmp}/doctruth-runtime-model-worker-smoke-$$"

cleanup() {
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT INT TERM

cargo build --manifest-path "$MANIFEST" --bins >/dev/null
mkdir -p "$WORK_DIR/cache"

python3 - "$WORK_DIR/fixture.pdf" "$WORK_DIR/cache" "$WORK_DIR/manifest.json" <<'PY'
import hashlib
import json
import pathlib
import sys

pdf_path = pathlib.Path(sys.argv[1])
cache = pathlib.Path(sys.argv[2])
manifest_path = pathlib.Path(sys.argv[3])

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
pdf_path.write_bytes(pdf)

artifact = b"ready mnn model artifact"
(cache / "slanet-plus-v1.bin").write_bytes(artifact)
manifest_path.write_text(
    json.dumps(
        {
            "presets": {
                "table-lite": [
                    {
                        "name": "slanet-plus",
                        "version": "v1",
                        "sha256": "sha256:" + hashlib.sha256(artifact).hexdigest(),
                        "sizeBytes": len(artifact),
                        "required": True,
                        "task": "table-structure-recognition",
                        "backend": "mnn",
                        "format": "mnn",
                        "precision": "fp32",
                        "license": "test",
                    }
                ]
            }
        },
        separators=(",", ":"),
    ),
    encoding="utf-8",
)
PY

"$WORKER_BIN" --doctor | python3 -c '
import json, sys
payload = json.load(sys.stdin)
assert payload["ok"] is True
assert payload["runtime"] == "mnn"
assert payload["engine"] == "mnn"
assert payload["productionPythonResidency"] is False
'

REPORT="$(DOCTRUTH_MNN_WORKER_STUB=1 \
  DOCTRUTH_RUNTIME_MODEL_COMMAND="$WORKER_BIN" \
  DOCTRUTH_MODEL_CACHE="$WORK_DIR/cache" \
  DOCTRUTH_MODEL_MANIFEST="$WORK_DIR/manifest.json" \
  "$RUNTIME_BIN" <<EOF_REQUEST
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
assert report["parserRun"]["workerBackend"] == "mnn-model-worker-stub"
assert report["parserRun"]["modelRuntime"]["runtime"] == "mnn"
assert report["parserRun"]["models"] == ["slanet-plus:v1"]
assert report["auditGradeStatus"] == "NOT_AUDIT_GRADE"
assert report["body"]["units"][0]["kind"] == "TABLE_CELL"
assert report["body"]["units"][0]["text"] == "Auto table MNN evidence"
PY

echo "doctruth-runtime Rust MNN model worker smoke passed"
