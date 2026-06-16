#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
MANIFEST="$ROOT_DIR/runtime/doctruth-runtime/Cargo.toml"
BIN="$ROOT_DIR/runtime/doctruth-runtime/target/debug/doctruth-runtime"
WORK_DIR="${TMPDIR:-/tmp}/doctruth-runtime-corpus-smoke-$$"

cargo test --manifest-path "$MANIFEST" --test benchmark_corpus_contract >/dev/null
mkdir -p "$WORK_DIR"

python3 - "$WORK_DIR/fixture.pdf" <<'PY'
import sys

path = sys.argv[1]
text = "Fallback corpus smoke evidence."
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
pdf.extend(
    f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
)
with open(path, "wb") as handle:
    handle.write(pdf)
PY

cat > "$WORK_DIR/expected.md" <<'EOF_EXPECTED'
Worker corpus smoke evidence.
EOF_EXPECTED

cat > "$WORK_DIR/expected.json" <<'EOF_EXPECTED_JSON'
{"docId":"expected","body":{"units":[]}}
EOF_EXPECTED_JSON

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
            "text": "Worker corpus smoke evidence.",
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

cat > "$WORK_DIR/corpus.json" <<'EOF_MANIFEST'
{
  "name": "rust-parser-accuracy-smoke",
  "kind": "human-labeled",
  "qualityProfile": "parser-accuracy",
  "labeling": {
    "labelSetVersion": "rust-smoke-v1",
    "reviewedAt": "2026-06-13",
    "reviewer": "runtime-smoke",
    "reviewType": "generated-seed",
    "requiredMetrics": [
      "reading_order_f1",
      "quote_anchor_accuracy",
      "bbox_coverage"
    ],
    "requiredTags": ["multi-layout"],
    "minCasesPerTag": 1
  },
  "minimums": {
    "reading_order_f1": 1.0,
    "quote_anchor_accuracy": 1.0,
    "bbox_coverage": 1.0
  },
  "cases": [
    {
      "name": "runtime-smoke",
      "labelId": "rust-smoke-v1-0001",
      "tags": ["multi-layout"],
      "preset": "table-lite",
      "source": "fixture.pdf",
      "expectedMarkdown": "expected.md",
      "expectedDocument": "expected.json"
    }
  ]
}
EOF_MANIFEST

REPORT="$(DOCTRUTH_RUNTIME_MODEL_COMMAND="$WORK_DIR/model-worker.py" "$BIN" <<EOF_REQUEST
{"command":"benchmark_corpus","manifest_path":"$WORK_DIR/corpus.json","offline":true,"report_path":"$WORK_DIR/recorded-report.json"}
EOF_REQUEST
)"
printf '%s\n' "$REPORT" > "$WORK_DIR/report.json"

python3 - "$WORK_DIR/report.json" "$WORK_DIR/recorded-report.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    report = json.load(handle)
with open(sys.argv[2], encoding="utf-8") as handle:
    recorded = json.load(handle)
assert report["runtime"] == "doctruth-runtime"
assert report["corpus"] == "rust-parser-accuracy-smoke"
assert report["passed"] is True
assert report["metrics"]["reading_order_f1"] == 1.0
assert report["metrics"]["quote_anchor_accuracy"] == 1.0
assert report["metrics"]["bbox_coverage"] == 1.0
assert report["cases"][0]["labelId"] == "rust-smoke-v1-0001"
assert report["cases"][0]["preset"] == "table-lite"
assert recorded["reportFormat"] == "doctruth.parser-benchmark.report.v1"
assert recorded["manifest"].endswith("corpus.json")
assert recorded["manifestSha256"].startswith("sha256:")
assert recorded["caseCount"] == 1
assert recorded["casesPerTag"]["multi-layout"] == 1
assert recorded["minCasesPerTag"]["multi-layout"] == 1
assert recorded["minimums"]["reading_order_f1"] == 1.0
assert isinstance(recorded["maximums"], dict)
assert recorded["runtime"] == report["runtime"]
assert recorded["corpus"] == report["corpus"]
assert recorded["cases"][0]["labelId"] == report["cases"][0]["labelId"]
assert recorded["cases"][0]["sourceSha256"].startswith("sha256:")
PY

printf '{"command":"verify_benchmark_report","report_path":"%s"}' "$WORK_DIR/recorded-report.json" \
  | "$BIN" > "$WORK_DIR/verified-report.json"
python3 - "$WORK_DIR/verified-report.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    verified = json.load(handle)
assert verified["verified"] is True
assert verified["caseCount"] == 1
PY
cp "$WORK_DIR/recorded-report.json" "$WORK_DIR/recorded-report-tampered.json"
python3 - "$WORK_DIR/recorded-report-tampered.json" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text())
data["minCasesPerTag"]["multi-layout"] = 2
path.write_text(json.dumps(data))
PY
if printf '{"command":"verify_benchmark_report","report_path":"%s"}' "$WORK_DIR/recorded-report-tampered.json" \
  | "$BIN" >/dev/null 2>"$WORK_DIR/recorded-report-tampered.err"; then
    echo "expected runtime benchmark report verifier failure" >&2
    exit 1
fi
grep -q "minCasesPerTag mismatch" "$WORK_DIR/recorded-report-tampered.err"
cp "$WORK_DIR/recorded-report.json" "$WORK_DIR/recorded-report-metric-tampered.json"
python3 - "$WORK_DIR/recorded-report-metric-tampered.json" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text())
data["metrics"]["reading_order_f1"] = 0.0
path.write_text(json.dumps(data))
PY
if printf '{"command":"verify_benchmark_report","report_path":"%s"}' "$WORK_DIR/recorded-report-metric-tampered.json" \
  | "$BIN" >/dev/null 2>"$WORK_DIR/recorded-report-metric-tampered.err"; then
    echo "expected runtime benchmark report metric verifier failure" >&2
    exit 1
fi
grep -q "aggregate metric mismatch" "$WORK_DIR/recorded-report-metric-tampered.err"
grep -q "reading_order_f1" "$WORK_DIR/recorded-report-metric-tampered.err"
cp "$WORK_DIR/recorded-report.json" "$WORK_DIR/recorded-report-aggregate-tampered.json"
python3 - "$WORK_DIR/recorded-report-aggregate-tampered.json" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text())
data["cases"][0]["metrics"]["reading_order_f1"] = 0.5
path.write_text(json.dumps(data))
PY
if printf '{"command":"verify_benchmark_report","report_path":"%s"}' "$WORK_DIR/recorded-report-aggregate-tampered.json" \
  | "$BIN" >/dev/null 2>"$WORK_DIR/recorded-report-aggregate-tampered.err"; then
    echo "expected runtime benchmark report aggregate verifier failure" >&2
    exit 1
fi
grep -q "actualTrustDocument metrics mismatch" "$WORK_DIR/recorded-report-aggregate-tampered.err"
grep -q "reading_order_f1" "$WORK_DIR/recorded-report-aggregate-tampered.err"
cp "$WORK_DIR/corpus.json" "$WORK_DIR/corpus-maximum-fail.json"
python3 - "$WORK_DIR/corpus-maximum-fail.json" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text())
data["maximums"] = {"reading_order_f1": 0.0}
path.write_text(json.dumps(data))
PY
if printf '{"command":"benchmark_corpus","manifest_path":"%s","offline":true}' "$WORK_DIR/corpus-maximum-fail.json" \
  | DOCTRUTH_RUNTIME_MODEL_COMMAND="$WORK_DIR/model-worker.py" "$BIN" >/dev/null 2>"$WORK_DIR/maximum-fail.err"; then
    echo "expected runtime benchmark maximum threshold failure" >&2
    exit 1
fi
grep -q "BENCHMARK_THRESHOLDS_FAILED" "$WORK_DIR/maximum-fail.err"
grep -q "above allowed maximum" "$WORK_DIR/maximum-fail.err"

rm -rf "$WORK_DIR"
echo "doctruth-runtime benchmark corpus smoke passed"
