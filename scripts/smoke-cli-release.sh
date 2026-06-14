#!/usr/bin/env sh
set -eu

version="${VERSION:-0.2.0-alpha}"
dist="${DIST_DIR:-dist}"
work="${SMOKE_DIR:-target/cli-release-smoke}"
java_bin="${JAVA:-}"

contains() {
    case "$1" in
        *"$2"*) return 0 ;;
        *) return 1 ;;
    esac
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --version)
            shift
            [ "$#" -gt 0 ] || {
                echo "missing value for --version" >&2
                exit 2
            }
            version="$1"
            ;;
        --dist)
            shift
            [ "$#" -gt 0 ] || {
                echo "missing value for --dist" >&2
                exit 2
            }
            dist="$1"
            ;;
        *)
            echo "unknown option: $1" >&2
            exit 2
            ;;
    esac
    shift
done

if [ -z "$java_bin" ]; then
    java_bin="${JAVA_HOME:-}/bin/java"
fi
if [ ! -x "$java_bin" ] && [ -x /opt/homebrew/opt/openjdk/bin/java ]; then
    java_bin=/opt/homebrew/opt/openjdk/bin/java
fi
if [ ! -x "$java_bin" ]; then
    java_bin=java
fi

tarball="${dist}/doctruth-${version}.tar.gz"

if [ ! -f "$tarball" ]; then
    echo "CLI tarball not found: $tarball" >&2
    echo "Package it first: scripts/package-cli-release.sh --version $version" >&2
    exit 1
fi

rm -rf "$work"
mkdir -p "$work"
tar -xzf "$tarball" -C "$work"

output="$("$java_bin" -jar "$work/doctruth-${version}/lib/doctruth-java-all.jar" version)"
case "$output" in
    "DocTruth ${version}") ;;
    *)
        echo "unexpected jar version output: $output" >&2
        exit 1
        ;;
esac

launcher_output="$(JAVA="$java_bin" "$work/doctruth-${version}/bin/doctruth" version)"
case "$launcher_output" in
    "DocTruth ${version}") ;;
    *)
        echo "unexpected launcher version output: $launcher_output" >&2
        exit 1
        ;;
esac

if [ ! -x "$work/doctruth-${version}/bin/doctruth-rapidocr-mnn-worker" ]; then
    echo "release tarball did not include executable RapidOCR worker adapter" >&2
    exit 1
fi

if [ ! -x "$work/doctruth-${version}/bin/doctruth-runtime" ]; then
    echo "release tarball did not include executable Rust runtime" >&2
    exit 1
fi

"$work/doctruth-${version}/bin/doctruth-runtime" --doctor \
  | python3 -c '
import json, sys
payload = json.load(sys.stdin)
assert payload["runtime"] == "doctruth-runtime"
assert payload["capabilities"]["parse_pdf"] is True
'

if [ ! -x "$work/doctruth-${version}/bin/doctruth-onnx-model-worker" ]; then
    echo "release tarball did not include executable ONNX model worker adapter" >&2
    exit 1
fi

if [ ! -x "$work/doctruth-${version}/bin/doctruth-slanext-table-worker" ]; then
    echo "release tarball did not include executable SLANeXT table worker adapter" >&2
    exit 1
fi

if [ ! -f "$work/doctruth-${version}/bin/doctruth_onnx_worker_lib.py" ]; then
    echo "release tarball did not include ONNX model worker support module" >&2
    exit 1
fi

if [ ! -x "$work/doctruth-${version}/bin/smoke-doctruth-real-model-suite.sh" ]; then
    echo "release tarball did not include executable real model suite smoke" >&2
    exit 1
fi

"$work/doctruth-${version}/bin/smoke-doctruth-real-model-suite.sh" \
  | grep -q "skipping real model suite smoke"

if [ ! -x "$work/doctruth-${version}/bin/smoke-doctruth-runtime-real-model-artifacts.sh" ]; then
    echo "release tarball did not include executable Rust runtime real model artifact smoke" >&2
    exit 1
fi

"$work/doctruth-${version}/bin/smoke-doctruth-runtime-real-model-artifacts.sh" \
  | grep -q "skipping Rust runtime real model artifact smoke"

if [ ! -x "$work/doctruth-${version}/bin/smoke-doctruth-runtime-real-ocr-corpus.sh" ]; then
    echo "release tarball did not include executable Rust runtime real OCR corpus smoke" >&2
    exit 1
fi

"$work/doctruth-${version}/bin/smoke-doctruth-runtime-real-ocr-corpus.sh" \
  | grep -q "skipping Rust runtime real OCR corpus smoke"

if [ ! -x "$work/doctruth-${version}/bin/smoke-doctruth-runtime-real-slanext-artifact.sh" ]; then
    echo "release tarball did not include executable Rust runtime real SLANeXT artifact smoke" >&2
    exit 1
fi

"$work/doctruth-${version}/bin/smoke-doctruth-runtime-real-slanext-artifact.sh" \
  | grep -q "skipping Rust runtime real SLANeXT smoke"

if [ ! -x "$work/doctruth-${version}/bin/smoke-doctruth-runtime-ocr-worker.sh" ]; then
    echo "release tarball did not include executable Rust runtime OCR worker smoke" >&2
    exit 1
fi

if [ ! -x "$work/doctruth-${version}/bin/smoke-doctruth-runtime-slanext-worker.sh" ]; then
    echo "release tarball did not include executable Rust runtime SLANeXT worker smoke" >&2
    exit 1
fi

fake_module_dir="$work/python"
mkdir -p "$fake_module_dir/rapidocr"
mkdir -p "$fake_module_dir/paddleocr"
cat > "$fake_module_dir/rapidocr/__init__.py" <<'PY'
class RapidOCR:
    def __init__(self):
        self.ready = True
PY
cat > "$fake_module_dir/paddleocr/__init__.py" <<'PY'
__version__ = "release-smoke"

class TableStructureRecognition:
    def __init__(self, model_name="SLANeXt_wired"):
        self.model_name = model_name
PY
cat > "$fake_module_dir/MNN.py" <<'PY'
__version__ = "release-smoke"
PY

PYTHONPATH="$fake_module_dir" DOCTRUTH_RAPIDOCR_BACKEND=mnn \
  "$work/doctruth-${version}/bin/doctruth-rapidocr-mnn-worker" --doctor \
  | python3 -c '
import json, sys
payload = json.load(sys.stdin)
assert payload["ok"] is True
assert payload["runtime"] == "rapidocr"
assert payload["backend"] == "mnn"
assert payload["backendReady"] is True
assert payload["code"] == "ready"
'

PYTHONPATH="$fake_module_dir" \
  "$work/doctruth-${version}/bin/doctruth-slanext-table-worker" --doctor \
  | python3 -c '
import json, sys
payload = json.load(sys.stdin)
assert payload["ok"] is True
assert payload["runtime"] == "paddleocr-slanext"
assert payload["code"] == "ready"
'

"$work/doctruth-${version}/bin/doctruth-onnx-model-worker" --doctor \
  | python3 -c '
import json, sys
payload = json.load(sys.stdin)
assert payload["runtime"] == "onnxruntime"
assert payload["code"] in {"ready", "onnxruntime_unavailable"}
'

doctor_output="$(JAVA="$java_bin" "$work/doctruth-${version}/bin/doctruth" doctor)"
contains "$doctor_output" "DocTruth doctor" || {
    echo "unexpected doctor output:" >&2
    echo "$doctor_output" >&2
    exit 1
}
contains "$doctor_output" "java:" || {
    echo "doctor output did not include java readiness:" >&2
    echo "$doctor_output" >&2
    exit 1
}
contains "$doctor_output" "ready:" || {
    echo "doctor output did not include final readiness:" >&2
    echo "$doctor_output" >&2
    exit 1
}

runtime_pdf="$work/runtime-default.pdf"
# The packaged launcher should set DOCTRUTH_RUNTIME_COMMAND from bin/doctruth-runtime.
python3 - "$runtime_pdf" <<'PY'
import sys

path = sys.argv[1]
text = "Packaged Rust runtime default."
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

JAVA="$java_bin" "$work/doctruth-${version}/bin/doctruth" parse "$runtime_pdf" --format json \
  | python3 -c '
import json, sys
payload = json.load(sys.stdin)
assert payload["parserRun"]["backend"] == "rust-sidecar" or payload["parserRun"]["backend"] == "sidecar"
assert "Packaged Rust runtime default." in payload["body"]["units"][0]["text"]
'

completion_output="$(JAVA="$java_bin" "$work/doctruth-${version}/bin/doctruth" completion bash)"
contains "$completion_output" "_doctruth()" || {
    echo "unexpected completion output:" >&2
    echo "$completion_output" >&2
    exit 1
}
contains "$completion_output" "doctor" || {
    echo "completion output did not include doctor command:" >&2
    echo "$completion_output" >&2
    exit 1
}
contains "$completion_output" "completion" || {
    echo "completion output did not include completion command:" >&2
    echo "$completion_output" >&2
    exit 1
}

echo "CLI release smoke passed for $version"
