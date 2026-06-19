#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-preprocess-probe.XXXXXX")"

cleanup() {
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT INT TERM

python3 - "$WORK_DIR/pack.json" "$WORK_DIR/input.ppm" <<'PY'
import json
import pathlib
import sys

manifest = pathlib.Path(sys.argv[1])
image = pathlib.Path(sys.argv[2])
image.write_bytes(b"P6\n2 2\n255\n" + bytes([
    255, 0, 0,
    0, 255, 0,
    0, 0, 255,
    255, 255, 255,
]))
manifest.write_text(json.dumps({
    "packId": "probe",
    "source": {"repository": "file://probe", "license": "Apache-2.0"},
    "presets": {
        "probe": [{
            "name": "probe-model",
            "version": "v1",
            "sha256": "sha256:" + "0" * 64,
            "sizeBytes": 1,
            "required": True,
            "task": "layout-detection",
            "backend": "onnxruntime",
            "format": "onnx",
            "license": "Apache-2.0",
            "url": "file://probe",
            "preprocessing": {
                "inputLayout": "NCHW",
                "dtype": "float32",
                "colorSpace": "sRGB",
                "channelOrder": "RGB",
                "resize": {"width": 2, "height": 2, "keepAspectRatio": False},
                "resample": "nearest",
                "scale": 0.00392156862745098,
                "mean": [0.0, 0.0, 0.0],
                "std": [1.0, 1.0, 1.0]
            },
            "parity": {
                "referenceEngine": "python-onnxruntime",
                "candidateEngine": "rust-mnn",
                "tensorDumpRequired": True,
                "firstTensorValuesRequired": True,
                "maxAbsDiff": 0.000001
            }
        }]
    }
}), encoding="utf-8")
PY

python3 "$ROOT/scripts/doctruth-preprocess-tensor-probe.py" \
  --manifest "$WORK_DIR/pack.json" \
  --preset probe \
  --model probe-model \
  --image "$WORK_DIR/input.ppm" \
  --first 8 \
  > "$WORK_DIR/tensor.json"

python3 - "$WORK_DIR/tensor.json" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
assert payload["shape"] == [1, 3, 2, 2], payload
assert payload["firstValues"][:8] == [1.0, 0.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0], payload
assert payload["sha256"].startswith("sha256:"), payload
PY

echo "doctruth preprocess tensor probe smoke passed"
