#!/usr/bin/env sh
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-rapidocr-mnn-backend.XXXXXX")"
FAKE_PY="$WORK_DIR/fakepy"
NO_MNN_RAW="$WORK_DIR/no-mnn.raw"
WITH_MNN_RAW="$WORK_DIR/with-mnn.raw"

mkdir -p "$FAKE_PY/rapidocr"
cat > "$FAKE_PY/rapidocr/__init__.py" <<'PY'
class RapidOCR:
    def __call__(self, image):
        return []
PY

PYTHONPATH="$FAKE_PY" DOCTRUTH_RAPIDOCR_BACKEND=mnn \
    scripts/doctruth-rapidocr-mnn-worker --doctor > "$NO_MNN_RAW"

python3 - "$NO_MNN_RAW" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
assert payload["ok"] is False, payload
assert payload["runtime"] == "rapidocr", payload
assert payload["engine"] == "mnn", payload
assert payload["backend"] == "mnn", payload
assert payload["backendReady"] is False, payload
assert payload["code"] == "mnn_unavailable", payload
PY

cat > "$FAKE_PY/MNN.py" <<'PY'
__version__ = "fake-smoke"
PY

PYTHONPATH="$FAKE_PY" DOCTRUTH_RAPIDOCR_BACKEND=mnn \
    scripts/doctruth-rapidocr-mnn-worker --doctor > "$WITH_MNN_RAW"

python3 - "$WITH_MNN_RAW" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
assert payload["ok"] is True, payload
assert payload["runtime"] == "rapidocr", payload
assert payload["engine"] == "mnn", payload
assert payload["backend"] == "mnn", payload
assert payload["backendReady"] is True, payload
assert payload["backendVersion"] == "fake-smoke", payload
assert payload["code"] == "ready", payload
PY

echo "doctruth RapidOCR MNN backend smoke passed"
