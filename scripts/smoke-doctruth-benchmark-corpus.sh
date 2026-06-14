#!/usr/bin/env sh
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

mvn -q -DskipTests package

JAVA_BIN="${JAVA_HOME:-}/bin/java"
if [ ! -x "$JAVA_BIN" ] && [ -x /opt/homebrew/opt/openjdk/bin/java ]; then
    JAVA_BIN=/opt/homebrew/opt/openjdk/bin/java
fi
if [ ! -x "$JAVA_BIN" ]; then
    JAVA_BIN=java
fi
JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.awt.headless=true"
export JAVA_TOOL_OPTIONS

CLI_JAR="$(find target -maxdepth 1 -name 'doctruth-java-*-all.jar' | sort | tail -1)"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-benchmark-corpus-smoke.XXXXXX")"
WORKER="$WORK_DIR/fake-ocr-worker"
RUNTIME="$WORK_DIR/fake-runtime"

python3 - "$WORK_DIR" <<'PY'
import hashlib
import json
import pathlib
import sys

work = pathlib.Path(sys.argv[1])
pdf = work / "fixture.pdf"
ocr_pdf = work / "scanned.pdf"
lines = ["PROFILE", "Experienced operator", "WORK EXPERIENCE", "Production assistant"]
stream = "BT\n/F1 24 Tf\n72 720 Td\n"
for index, line in enumerate(lines):
    if index:
        stream += "0 -30 Td\n"
    escaped = line.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
    stream += f"({escaped}) Tj\n"
stream += "ET\n"
objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    f"<< /Length {len(stream.encode())} >>\nstream\n{stream}endstream",
]
raw = bytearray(b"%PDF-1.4\n")
offsets = []
for i, obj in enumerate(objects, start=1):
    offsets.append(len(raw))
    raw.extend(f"{i} 0 obj\n{obj}\nendobj\n".encode())
xref = len(raw)
raw.extend(f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode())
for offset in offsets:
    raw.extend(f"{offset:010} 00000 n \n".encode())
raw.extend(f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
pdf.write_bytes(raw)

def sha256(path):
    return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()

objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> >>",
]
raw = bytearray(b"%PDF-1.4\n")
offsets = []
for i, obj in enumerate(objects, start=1):
    offsets.append(len(raw))
    raw.extend(f"{i} 0 obj\n{obj}\nendobj\n".encode())
xref = len(raw)
raw.extend(f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode())
for offset in offsets:
    raw.extend(f"{offset:010} 00000 n \n".encode())
raw.extend(f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
ocr_pdf.write_bytes(raw)

expected = {
    "docId": "expected-doc",
    "source": {
        "sourceFilename": "expected.pdf",
        "sourceHash": "sha256:expected",
        "metadata": {"sourceFilename": "expected.pdf", "pageCount": 1},
    },
    "body": {
        "pages": [
            {
                "pageNumber": 1,
                "width": 1000,
                "height": 1000,
                "textLayerAvailable": True,
                "imageHash": "",
            }
        ],
        "units": [
            {
                "unitId": "unit-0001",
                "kind": "TEXT_BLOCK",
                "page": 1,
                "text": "PROFILE\nExperienced operator\nWORK EXPERIENCE\nProduction assistant",
                "evidenceSpanIds": ["span-0001"],
                "location": {
                    "page": 1,
                    "readingOrder": 1,
                    "boundingBox": {"x0": 100, "y0": 100, "x1": 500, "y1": 200},
                },
                "sourceObjectId": "section-0001",
                "confidence": {"score": 1.0, "rationale": "expected fixture"},
                "warnings": [],
            }
        ],
        "tables": [],
    },
    "parserRun": {
        "parserVersion": "1.0.0",
        "preset": "lite",
        "backend": "fixture",
        "models": [],
        "warnings": [],
    },
    "auditGradeStatus": "UNKNOWN",
}
(work / "expected.md").write_text(
    "PROFILE\nExperienced operator\nWORK EXPERIENCE\nProduction assistant\n",
    encoding="utf-8",
)
(work / "expected.json").write_text(json.dumps(expected, separators=(",", ":")), encoding="utf-8")
ocr_expected = dict(expected)
ocr_expected["docId"] = "expected-ocr-doc"
ocr_expected["source"] = {
    "sourceFilename": "expected-ocr.pdf",
    "sourceHash": "sha256:expected-ocr",
    "metadata": {"sourceFilename": "expected-ocr.pdf", "pageCount": 1},
}
ocr_expected["body"] = dict(expected["body"])
ocr_expected["body"]["pages"] = [
    {
        "pageNumber": 1,
        "width": 1000,
        "height": 1000,
        "textLayerAvailable": False,
        "imageHash": "",
    }
]
ocr_expected["body"]["units"] = [
    {
        "unitId": "ocr-unit-0001",
        "kind": "OCR_REGION",
        "page": 1,
        "text": "OCR benchmark text",
        "evidenceSpanIds": ["span-ocr-0001"],
        "location": {
            "page": 1,
            "readingOrder": 1,
            "boundingBox": {"x0": 100, "y0": 100, "x1": 600, "y1": 220},
        },
        "sourceObjectId": "ocr-page-1",
        "confidence": {"score": 0.96, "rationale": "expected OCR fixture"},
        "warnings": [],
    }
]
ocr_expected["parserRun"] = {
    "parserVersion": "1.0.0",
    "preset": "ocr",
    "backend": "fixture",
    "models": [],
    "warnings": [],
}
(work / "expected-ocr.md").write_text("OCR benchmark text\n", encoding="utf-8")
(work / "expected-ocr.json").write_text(json.dumps(ocr_expected, separators=(",", ":")), encoding="utf-8")

manifest = {
    "name": "smoke-generated-corpus",
    "minimums": {
        "reading_order_f1": 1.0,
        "section_boundary_f1": 1.0,
        "evidence_span_accuracy": 1.0,
        "quote_anchor_accuracy": 1.0,
        "bbox_coverage": 1.0,
        "ocr_text_accuracy": 1.0,
    },
    "cases": [
        {
            "name": "single-column-smoke",
            "source": "fixture.pdf",
            "expectedMarkdown": "expected.md",
            "expectedDocument": "expected.json",
        },
        {
            "name": "ocr-smoke",
            "source": "scanned.pdf",
            "preset": "ocr",
            "expectedMarkdown": "expected-ocr.md",
            "expectedDocument": "expected-ocr.json",
        }
    ],
}
manifest["cases"][1]["sourceSha256"] = sha256(work / "scanned.pdf")
(work / "corpus.json").write_text(json.dumps(manifest, separators=(",", ":")), encoding="utf-8")
human_manifest = {
    "name": "smoke-human-labeled-corpus",
    "kind": "human-labeled",
    "labeling": {
        "labelSetVersion": "smoke-layout-v1",
        "reviewedAt": "2026-06-13",
        "reviewer": "doctruth-fixture",
        "requiredMetrics": [
            "reading_order_f1",
            "bbox_coverage",
            "evidence_span_accuracy",
        ],
    },
    "minimums": {
        "reading_order_f1": 1.0,
        "bbox_coverage": 1.0,
        "evidence_span_accuracy": 1.0,
    },
    "cases": [
        {
            "name": "human-labeled-smoke",
            "labelId": "smoke-layout-v1-0001",
            "source": "fixture.pdf",
            "expectedMarkdown": "expected.md",
            "expectedDocument": "expected.json",
        }
    ],
}
(work / "corpus-human-labeled.json").write_text(json.dumps(human_manifest, separators=(",", ":")), encoding="utf-8")
broken_human_manifest = dict(human_manifest)
broken_human_manifest["minimums"] = {"reading_order_f1": 1.0}
(work / "corpus-human-labeled-fail.json").write_text(json.dumps(broken_human_manifest, separators=(",", ":")), encoding="utf-8")
parser_accuracy_manifest = dict(human_manifest)
parser_accuracy_manifest["name"] = "smoke-parser-accuracy-corpus"
parser_accuracy_manifest["qualityProfile"] = "parser-accuracy"
parser_accuracy_manifest["labeling"] = dict(human_manifest["labeling"])
parser_accuracy_manifest["labeling"]["reviewType"] = "human-reviewed"
parser_accuracy_manifest["labeling"]["requiredMetrics"] = [
    "reading_order_f1",
    "quote_anchor_accuracy",
    "bbox_coverage",
    "bbox_iou",
    "evidence_span_accuracy",
    "table_cell_f1",
    "ocr_text_accuracy",
]
parser_accuracy_manifest["labeling"]["requiredTags"] = ["multi-layout", "table", "ocr", "bbox", "source-map"]
parser_accuracy_manifest["labeling"]["minCasesPerTag"] = 1
parser_accuracy_manifest["labeling"]["minTotalCases"] = 1
parser_accuracy_manifest["minimums"] = {
    "reading_order_f1": 1.0,
    "quote_anchor_accuracy": 1.0,
    "bbox_coverage": 1.0,
    "bbox_iou": 0.0,
    "evidence_span_accuracy": 1.0,
    "table_cell_f1": 1.0,
    "ocr_text_accuracy": 1.0,
}
parser_accuracy_manifest["cases"] = [
    dict(
        human_manifest["cases"][0],
        name="parser-accuracy-smoke",
        tags=["multi-layout", "table", "ocr", "bbox", "source-map"],
        sourceSha256=sha256(work / "fixture.pdf"),
    )
]
(work / "corpus-parser-accuracy.json").write_text(
    json.dumps(parser_accuracy_manifest, separators=(",", ":")), encoding="utf-8")
broken_parser_accuracy = dict(parser_accuracy_manifest)
broken_parser_accuracy["labeling"] = dict(parser_accuracy_manifest["labeling"])
broken_parser_accuracy["labeling"]["requiredTags"] = ["multi-layout", "ocr"]
broken_parser_accuracy["labeling"]["minCasesPerTag"] = 2
(work / "corpus-parser-accuracy-fail.json").write_text(
    json.dumps(broken_parser_accuracy, separators=(",", ":")), encoding="utf-8")
remote_manifest = {
    "name": "smoke-offline-remote-corpus",
    "minimums": {"reading_order_f1": 1.0},
    "cases": [
        {
            "name": "offline-remote-smoke",
            "sourceUrl": "http://127.0.0.1:1/offline.pdf",
            "sourceSha256": "sha256:" + ("a" * 64),
            "expectedMarkdown": "expected.md",
            "expectedDocument": "expected.json",
        }
    ],
}
(work / "corpus-offline-remote.json").write_text(json.dumps(remote_manifest, separators=(",", ":")), encoding="utf-8")
ocr_fail_manifest = {
    "name": "smoke-ocr-label-corpus",
    "minimums": {"ocr_text_accuracy": 1.0},
    "cases": [
        {
            "name": "ocr-wrong-label-smoke",
            "source": "scanned.pdf",
            "preset": "ocr",
            "expectedMarkdown": "expected-ocr-wrong.md",
            "expectedDocument": "expected-ocr.json",
        }
    ],
}
(work / "expected-ocr-wrong.md").write_text("Different OCR label\n", encoding="utf-8")
(work / "corpus-ocr-fail.json").write_text(json.dumps(ocr_fail_manifest, separators=(",", ":")), encoding="utf-8")
warning_expected = dict(expected)
warning_expected["docId"] = "expected-warning-doc"
warning_expected["parserRun"] = {
    "parserVersion": "1.0.0",
    "preset": "lite",
    "backend": "fixture",
    "models": [],
    "warnings": [
        {
            "code": "layout_low_confidence",
            "severity": "SEVERE",
            "message": "expected warning fixture",
        }
    ],
}
(work / "expected-warning.json").write_text(json.dumps(warning_expected, separators=(",", ":")), encoding="utf-8")
warning_manifest = {
    "name": "smoke-warning-corpus",
    "minimums": {"reading_order_f1": 1.0},
    "maximums": {"strict_warning_false_negative_rate": 0.02},
    "cases": [
        {
            "name": "missing-warning-smoke",
            "source": "fixture.pdf",
            "expectedMarkdown": "expected.md",
            "expectedDocument": "expected-warning.json",
        }
    ],
}
(work / "corpus-warning-fail.json").write_text(json.dumps(warning_manifest, separators=(",", ":")), encoding="utf-8")
manifest["minimums"]["reading_order_f1"] = 1.01
(work / "corpus-fail.json").write_text(json.dumps(manifest, separators=(",", ":")), encoding="utf-8")
PY

cat > "$WORKER" <<'SH'
#!/usr/bin/env sh
python3 -c '
import json
import sys
request = json.loads(sys.stdin.read())
assert request["fileType"] == "png"
print(json.dumps({
    "ok": True,
    "engine": "mnn",
    "text": "OCR benchmark text",
    "averageConfidence": 0.96,
    "pages": [],
    "warnings": []
}))
'
SH
chmod +x "$WORKER"

cat > "$RUNTIME" <<'PY'
#!/usr/bin/env python3
import json
import pathlib
import sys

request = json.loads(sys.stdin.read())
assert request["command"] == "parse_pdf"
source = pathlib.Path(request["source_path"])
source_name = source.name
source_hash = request["source_hash"]
preset = request["preset"]

def bbox(x0, y0, x1, y1):
    return {"x0": x0, "y0": y0, "x1": x1, "y1": y1}

if preset == "ocr":
    text = "OCR benchmark text"
    unit = {
        "unitId": "ocr-unit-0001",
        "kind": "OCR_REGION",
        "page": 1,
        "text": text,
        "evidenceSpanIds": ["span-ocr-0001"],
        "location": {"page": 1, "readingOrder": 1, "boundingBox": bbox(100, 100, 600, 220)},
        "sourceObjectId": "ocr-page-1",
        "confidence": {"score": 0.96, "rationale": "runtime OCR fixture"},
        "warnings": [],
    }
    backend = "rust-sidecar+model-worker"
    text_layer = False
else:
    text = "PROFILE\nExperienced operator\nWORK EXPERIENCE\nProduction assistant"
    unit = {
        "unitId": "unit-0001",
        "kind": "TEXT_BLOCK",
        "page": 1,
        "text": text,
        "evidenceSpanIds": ["span-0001"],
        "location": {"page": 1, "readingOrder": 1, "boundingBox": bbox(100, 100, 500, 200)},
        "sourceObjectId": "section-0001",
        "confidence": {"score": 1.0, "rationale": "runtime text fixture"},
        "warnings": [],
    }
    backend = "sidecar"
    text_layer = True

print(json.dumps({
    "docId": source_hash,
    "source": {
        "sourceFilename": source_name,
        "sourceHash": source_hash,
        "metadata": {"sourceFilename": source_name, "pageCount": 1},
    },
    "body": {
        "pages": [{
            "pageNumber": 1,
            "width": 1000,
            "height": 1000,
            "textLayerAvailable": text_layer,
            "imageHash": "sha256:" + ("0" * 64),
        }],
        "units": [unit],
        "tables": [],
    },
    "parserRun": {
        "parserVersion": "runtime-smoke",
        "preset": preset,
        "backend": backend,
        "models": [],
        "warnings": [],
    },
    "auditGradeStatus": "AUDIT_GRADE",
}))
PY
chmod +x "$RUNTIME"

"$JAVA_BIN" -Ddoctruth.runtime.command="$RUNTIME" -Ddoctruth.ocr.command="$WORKER" -jar "$CLI_JAR" benchmark-corpus "$WORK_DIR/corpus.json" --json > "$WORK_DIR/result.json"
python3 - "$WORK_DIR/result.json" <<'PY'
import json
import pathlib
import sys

data = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert data["corpus"] == "smoke-generated-corpus"
assert data["passed"] is True
assert data["metrics"]["parser_latency_p50"] >= 0.0
assert data["metrics"]["parser_latency_p95"] >= 0.0
assert data["metrics"]["compact_llm_size_reduction_min"] >= 0.0
cases = {case["name"]: case for case in data["cases"]}
assert cases["single-column-smoke"]["metrics"]["reading_order_f1"] == 1.0
assert cases["single-column-smoke"]["metrics"]["section_boundary_f1"] == 1.0
assert cases["single-column-smoke"]["metrics"]["evidence_span_accuracy"] == 1.0
assert cases["single-column-smoke"]["metrics"]["parser_latency_ms"] >= 0.0
assert cases["single-column-smoke"]["metrics"]["rss_peak_mb"] >= 0.0
assert cases["single-column-smoke"]["metrics"]["model_cache_size_mb"] >= 0.0
assert cases["single-column-smoke"]["metrics"]["ocr_text_accuracy"] == 1.0
assert cases["ocr-smoke"]["metrics"]["ocr_text_accuracy"] == 1.0
PY

"$JAVA_BIN" -Ddoctruth.runtime.command="$RUNTIME" -Ddoctruth.ocr.command="$WORKER" -jar "$CLI_JAR" benchmark-corpus "$WORK_DIR/corpus-human-labeled.json" --json > "$WORK_DIR/human.json"
python3 - "$WORK_DIR/human.json" <<'PY'
import json
import pathlib
import sys

data = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert data["corpus"] == "smoke-human-labeled-corpus"
assert data["passed"] is True
assert data["cases"][0]["metrics"]["reading_order_f1"] == 1.0
PY

"$JAVA_BIN" -Ddoctruth.runtime.command="$RUNTIME" -Ddoctruth.ocr.command="$WORKER" -jar "$CLI_JAR" benchmark-corpus "$WORK_DIR/corpus-parser-accuracy.json" --json --report-out "$WORK_DIR/parser-accuracy-report.json" > "$WORK_DIR/parser-accuracy.json"
python3 - "$WORK_DIR/parser-accuracy.json" "$WORK_DIR/parser-accuracy-report.json" <<'PY'
import json
import pathlib
import sys

data = json.loads(pathlib.Path(sys.argv[1]).read_text())
report = json.loads(pathlib.Path(sys.argv[2]).read_text())
assert data["corpus"] == "smoke-parser-accuracy-corpus"
assert data["kind"] == "human-labeled"
assert data["qualityProfile"] == "parser-accuracy"
assert data["reviewType"] == "human-reviewed"
assert data["requiredTags"] == ["multi-layout", "table", "ocr", "bbox", "source-map"]
assert data["minCasesPerTag"]["multi-layout"] == 1
assert data["minCasesPerTag"]["source-map"] == 1
assert data["minTotalCases"] == 1
assert data["cases"][0]["tags"] == ["multi-layout", "table", "ocr", "bbox", "source-map"]
assert data["passed"] is True
assert report["reportFormat"] == "doctruth.parser-benchmark.report.v1"
assert report["manifest"].endswith("corpus-parser-accuracy.json")
assert report["manifestSha256"].startswith("sha256:")
assert report["caseCount"] == 1
assert report["casesPerTag"]["multi-layout"] == 1
assert report["casesPerTag"]["source-map"] == 1
assert report["minimums"]["reading_order_f1"] == 1.0
assert isinstance(report["maximums"], dict)
assert report["corpus"] == data["corpus"]
assert report["qualityProfile"] == "parser-accuracy"
assert report["reviewType"] == "human-reviewed"
assert report["cases"][0]["labelId"] == data["cases"][0]["labelId"]
assert report["cases"][0]["sourceSha256"].startswith("sha256:")
PY

"$JAVA_BIN" -Ddoctruth.ocr.command="$WORKER" -jar "$CLI_JAR" verify-benchmark-report "$WORK_DIR/parser-accuracy-report.json" >/dev/null
cp "$WORK_DIR/parser-accuracy-report.json" "$WORK_DIR/parser-accuracy-report-tampered.json"
python3 - "$WORK_DIR/parser-accuracy-report-tampered.json" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text())
data["caseCount"] = 999
path.write_text(json.dumps(data))
PY
if "$JAVA_BIN" -Ddoctruth.ocr.command="$WORKER" -jar "$CLI_JAR" verify-benchmark-report "$WORK_DIR/parser-accuracy-report-tampered.json" >/dev/null 2>"$WORK_DIR/report-tampered.err"; then
    echo "expected verify-benchmark-report tampered coverage failure" >&2
    exit 1
fi
grep -q "caseCount mismatch" "$WORK_DIR/report-tampered.err"
cp "$WORK_DIR/parser-accuracy-report.json" "$WORK_DIR/parser-accuracy-report-extra-tag.json"
python3 - "$WORK_DIR/parser-accuracy-report-extra-tag.json" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text())
data["casesPerTag"]["forged-tag"] = 1
path.write_text(json.dumps(data))
PY
if "$JAVA_BIN" -Ddoctruth.ocr.command="$WORKER" -jar "$CLI_JAR" verify-benchmark-report "$WORK_DIR/parser-accuracy-report-extra-tag.json" >/dev/null 2>"$WORK_DIR/report-extra-tag.err"; then
    echo "expected verify-benchmark-report extra coverage tag failure" >&2
    exit 1
fi
grep -q "casesPerTag mismatch" "$WORK_DIR/report-extra-tag.err"
cp "$WORK_DIR/parser-accuracy-report.json" "$WORK_DIR/parser-accuracy-report-threshold-tampered.json"
python3 - "$WORK_DIR/parser-accuracy-report-threshold-tampered.json" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text())
data["minCasesPerTag"]["source-map"] = 2
path.write_text(json.dumps(data))
PY
if "$JAVA_BIN" -Ddoctruth.ocr.command="$WORKER" -jar "$CLI_JAR" verify-benchmark-report "$WORK_DIR/parser-accuracy-report-threshold-tampered.json" >/dev/null 2>"$WORK_DIR/report-threshold-tampered.err"; then
    echo "expected verify-benchmark-report tampered coverage threshold failure" >&2
    exit 1
fi
grep -q "minCasesPerTag mismatch" "$WORK_DIR/report-threshold-tampered.err"
cp "$WORK_DIR/parser-accuracy-report.json" "$WORK_DIR/parser-accuracy-report-metric-tampered.json"
python3 - "$WORK_DIR/parser-accuracy-report-metric-tampered.json" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text())
data["metrics"]["reading_order_f1"] = 0.0
path.write_text(json.dumps(data))
PY
if "$JAVA_BIN" -Ddoctruth.ocr.command="$WORKER" -jar "$CLI_JAR" verify-benchmark-report "$WORK_DIR/parser-accuracy-report-metric-tampered.json" >/dev/null 2>"$WORK_DIR/report-metric-tampered.err"; then
    echo "expected verify-benchmark-report tampered metric failure" >&2
    exit 1
fi
grep -q "minimum threshold failed" "$WORK_DIR/report-metric-tampered.err"
grep -q "reading_order_f1" "$WORK_DIR/report-metric-tampered.err"
cp "$WORK_DIR/parser-accuracy-report.json" "$WORK_DIR/parser-accuracy-report-aggregate-tampered.json"
python3 - "$WORK_DIR/parser-accuracy-report-aggregate-tampered.json" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text())
data["metrics"]["parser_latency_p95"] = 999999.0
path.write_text(json.dumps(data))
PY
if "$JAVA_BIN" -Ddoctruth.ocr.command="$WORKER" -jar "$CLI_JAR" verify-benchmark-report "$WORK_DIR/parser-accuracy-report-aggregate-tampered.json" >/dev/null 2>"$WORK_DIR/report-aggregate-tampered.err"; then
    echo "expected verify-benchmark-report tampered aggregate failure" >&2
    exit 1
fi
grep -q "aggregate metric mismatch" "$WORK_DIR/report-aggregate-tampered.err"
grep -q "parser_latency_p95" "$WORK_DIR/report-aggregate-tampered.err"

if "$JAVA_BIN" -Ddoctruth.runtime.command="$RUNTIME" -Ddoctruth.ocr.command="$WORKER" -jar "$CLI_JAR" benchmark-corpus "$WORK_DIR/corpus-human-labeled-fail.json" >/dev/null 2>"$WORK_DIR/human-fail.err"; then
    echo "expected human-labeled corpus metadata failure" >&2
    exit 1
fi
grep -q "human-labeled" "$WORK_DIR/human-fail.err"
grep -q "bbox_coverage" "$WORK_DIR/human-fail.err"

if "$JAVA_BIN" -Ddoctruth.runtime.command="$RUNTIME" -Ddoctruth.ocr.command="$WORKER" -jar "$CLI_JAR" benchmark-corpus "$WORK_DIR/corpus-parser-accuracy-fail.json" >/dev/null 2>"$WORK_DIR/parser-accuracy-fail.err"; then
    echo "expected parser-accuracy coverage failure" >&2
    exit 1
fi
grep -q "parser-accuracy" "$WORK_DIR/parser-accuracy-fail.err"
grep -q "multi-layout" "$WORK_DIR/parser-accuracy-fail.err"
grep -q "minimum=2" "$WORK_DIR/parser-accuracy-fail.err"
grep -q "ocr" "$WORK_DIR/parser-accuracy-fail.err"

if "$JAVA_BIN" -Ddoctruth.runtime.command="$RUNTIME" -Ddoctruth.ocr.command="$WORKER" -jar "$CLI_JAR" benchmark-corpus "$WORK_DIR/corpus-fail.json" >/dev/null 2>"$WORK_DIR/fail.err"; then
    echo "expected benchmark-corpus threshold failure" >&2
    exit 1
fi
grep -q "parser benchmark thresholds failed" "$WORK_DIR/fail.err"

if "$JAVA_BIN" -Ddoctruth.runtime.command="$RUNTIME" -Ddoctruth.ocr.command="$WORKER" -jar "$CLI_JAR" benchmark-corpus "$WORK_DIR/corpus-ocr-fail.json" >/dev/null 2>"$WORK_DIR/ocr.err"; then
    echo "expected benchmark-corpus OCR label threshold failure" >&2
    exit 1
fi
grep -q "ocr-wrong-label-smoke" "$WORK_DIR/ocr.err"
grep -q "ocr_text_accuracy" "$WORK_DIR/ocr.err"
grep -q "minimum=1.0" "$WORK_DIR/ocr.err"

if "$JAVA_BIN" -Ddoctruth.runtime.command="$RUNTIME" -Ddoctruth.ocr.command="$WORKER" -jar "$CLI_JAR" benchmark-corpus "$WORK_DIR/corpus-warning-fail.json" >/dev/null 2>"$WORK_DIR/warning.err"; then
    echo "expected benchmark-corpus maximum threshold failure" >&2
    exit 1
fi
grep -q "strict_warning_false_negative_rate" "$WORK_DIR/warning.err"
grep -q "maximum=0.02" "$WORK_DIR/warning.err"

python3 - "$WORK_DIR/corpus.json" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text())
data["maximums"] = {"parser_latency_p95": 0.0}
path.with_name("corpus-latency-fail.json").write_text(json.dumps(data, separators=(",", ":")))
PY
if "$JAVA_BIN" -Ddoctruth.runtime.command="$RUNTIME" -Ddoctruth.ocr.command="$WORKER" -jar "$CLI_JAR" benchmark-corpus "$WORK_DIR/corpus-latency-fail.json" >/dev/null 2>"$WORK_DIR/latency.err"; then
    echo "expected benchmark-corpus latency maximum threshold failure" >&2
    exit 1
fi
grep -q "parser_latency_p95" "$WORK_DIR/latency.err"
grep -q "maximum=0.0" "$WORK_DIR/latency.err"

python3 - "$WORK_DIR/corpus.json" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text())
data["minimums"] = {
    "reading_order_f1": 1.0,
    "compact_llm_size_reduction_min": 1.0,
}
path.with_name("corpus-compact-fail.json").write_text(json.dumps(data, separators=(",", ":")))
PY
if "$JAVA_BIN" -Ddoctruth.runtime.command="$RUNTIME" -Ddoctruth.ocr.command="$WORKER" -jar "$CLI_JAR" benchmark-corpus "$WORK_DIR/corpus-compact-fail.json" >/dev/null 2>"$WORK_DIR/compact.err"; then
    echo "expected benchmark-corpus compact aggregate threshold failure" >&2
    exit 1
fi
grep -q "compact_llm_size_reduction_min" "$WORK_DIR/compact.err"
grep -q "minimum=1.0" "$WORK_DIR/compact.err"

if "$JAVA_BIN" -Ddoctruth.runtime.command="$RUNTIME" -Ddoctruth.ocr.command="$WORKER" -jar "$CLI_JAR" benchmark-corpus "$WORK_DIR/corpus-offline-remote.json" --offline >/dev/null 2>"$WORK_DIR/offline.err"; then
    echo "expected benchmark-corpus offline remote refusal" >&2
    exit 1
fi
grep -q "offline mode refuses remote benchmark source" "$WORK_DIR/offline.err"

echo "doctruth benchmark corpus smoke passed"
