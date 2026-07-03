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

CLI_JAR="$(find target -maxdepth 1 -name 'doctruth-java-*-all.jar' | sort | tail -1)"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-parser-accuracy-seed.XXXXXX")"
WORKER="$WORK_DIR/fake-ocr-worker"
MODEL_CACHE="$WORK_DIR/model-cache"
MODEL_MANIFEST="$WORK_DIR/model-manifest.json"
MANIFEST="$WORK_DIR/parser-accuracy-seed.json"
RESULT="$WORK_DIR/result.json"

python3 - "$WORK_DIR" <<'PY'
import hashlib
import json
import pathlib
import sys

work = pathlib.Path(sys.argv[1])
model_cache = work / "model-cache"
model_cache.mkdir(parents=True, exist_ok=True)

def write_pdf(path, lines):
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
    path.write_bytes(raw)

write_pdf(work / "multi-layout.pdf", [
    "BT /F1 18 Tf 72 720 Td (CONTACT) Tj ET",
    "BT /F1 12 Tf 72 696 Td (+6011-19822183) Tj ET",
    "BT /F1 18 Tf 320 720 Td (PROFILE) Tj ET",
    "BT /F1 12 Tf 320 696 Td (Experienced operator) Tj ET",
])
write_pdf(work / "table.pdf", [
    "1 w",
    "72 648 m 540 648 l S",
    "72 576 m 540 576 l S",
    "72 504 m 540 504 l S",
    "72 504 m 72 648 l S",
    "306 504 m 306 648 l S",
    "540 504 m 540 648 l S",
    "BT /F1 14 Tf 96 615 Td (Name) Tj ET",
    "BT /F1 14 Tf 330 615 Td (Score) Tj ET",
    "BT /F1 14 Tf 96 543 Td (Ada) Tj ET",
    "BT /F1 14 Tf 330 543 Td (98) Tj ET",
])
write_pdf(work / "scanned.pdf", [])

def write_model(name, version):
    filename = f"{name}-{version}.bin"
    payload = f"fake {name} {version} mnn artifact".encode()
    path = model_cache / filename
    path.write_bytes(payload)
    return {
        "name": name,
        "version": version,
        "sha256": "sha256:" + hashlib.sha256(payload).hexdigest(),
        "sizeBytes": len(payload),
        "required": True,
        "task": "ocr",
        "backend": "mnn",
        "format": "mnn",
        "precision": "fp32",
        "license": "test",
        "cacheFilename": filename,
    }

manifest_models = {
    "presets": {
        "ocr": [
            write_model("ppocr-v5-mobile-det", "v0.1.3"),
            write_model("ppocr-v5-mobile-rec", "v0.1.3"),
        ]
    }
}
(work / "model-manifest.json").write_text(json.dumps(manifest_models, separators=(",", ":")), encoding="utf-8")

manifest = {
    "name": "parser-accuracy-seed-corpus",
    "kind": "human-labeled",
    "qualityProfile": "parser-accuracy",
    "labeling": {
        "labelSetVersion": "seed-v1",
        "reviewedAt": "2026-06-13",
        "reviewer": "doctruth-seed-fixture",
        "reviewType": "generated-seed",
        "requiredMetrics": [
            "reading_order_f1",
            "quote_anchor_accuracy",
            "bbox_coverage",
            "bbox_iou",
            "table_cell_f1",
            "table_region_iou",
            "ocr_text_accuracy",
            "compact_llm_source_map_coverage",
            "evidence_span_accuracy"
        ],
        "requiredTags": ["multi-layout", "table", "ocr", "bbox", "source-map"],
        "minCasesPerTag": 1
    },
    "minimums": {
        "reading_order_f1": 1.0,
        "quote_anchor_accuracy": 1.0,
        "bbox_coverage": 1.0,
        "bbox_iou": 1.0,
        "table_cell_f1": 1.0,
        "table_region_iou": 1.0,
        "ocr_text_accuracy": 1.0,
        "compact_llm_source_map_coverage": 1.0,
        "evidence_span_accuracy": 1.0
    },
    "cases": [
        {
            "name": "seed-multi-layout",
            "labelId": "seed-v1-0001",
            "tags": ["multi-layout", "bbox", "source-map"],
            "source": "multi-layout.pdf",
            "expectedMarkdown": "multi-layout.md",
            "expectedDocument": "multi-layout.json"
        },
        {
            "name": "seed-table",
            "labelId": "seed-v1-0002",
            "tags": ["table", "bbox", "source-map"],
            "source": "table.pdf",
            "expectedMarkdown": "table.md",
            "expectedDocument": "table.json"
        },
        {
            "name": "seed-ocr",
            "labelId": "seed-v1-0003",
            "tags": ["ocr", "bbox", "source-map"],
            "source": "scanned.pdf",
            "preset": "ocr",
            "expectedMarkdown": "scanned.md",
            "expectedDocument": "scanned.json"
        }
    ]
}
(work / "parser-accuracy-seed.json").write_text(json.dumps(manifest, separators=(",", ":")), encoding="utf-8")
PY

cat > "$WORKER" <<'SH'
#!/usr/bin/env sh
python3 -c '
import json
import pathlib
import sys
request = json.loads(sys.stdin.read())
if request.get("command") == "parse_pdf":
    source = pathlib.Path(request["source_path"])
    source_hash = request["source_hash"]
    print(json.dumps({
        "ok": True,
        "document": {
            "docId": source_hash,
            "source": {
                "sourceFilename": source.name,
                "sourceHash": source_hash,
                "metadata": {"sourceFilename": source.name, "pageCount": 1},
            },
            "body": {
                "pages": [{
                    "pageNumber": 1,
                    "width": 612,
                    "height": 792,
                    "textLayerAvailable": False,
                    "imageHash": source_hash,
                }],
                "units": [{
                    "unitId": "unit-ocr-0001",
                    "kind": "OCR_REGION",
                    "page": 1,
                    "text": "OCR seed text",
                    "evidenceSpanIds": ["span-ocr-0001"],
                    "location": {
                        "page": 1,
                        "readingOrder": 1,
                        "boundingBox": {"x0": 10, "y0": 10, "x1": 230, "y1": 90},
                    },
                    "sourceObjectId": "fake-ocr#region-0001",
                    "confidence": {"score": 0.96, "rationale": "fake runtime OCR worker"},
                    "warnings": [],
                }],
                "tables": [],
            },
            "parserRun": {
                "parserRunId": "fake-ocr-run",
                "parserVersion": "runtime-smoke",
                "preset": "ocr",
                "backend": "rapidocr-worker",
                "models": ["ocr-router:v1"],
                "warnings": [],
            },
            "auditGradeStatus": "AUDIT_GRADE",
        },
    }))
    sys.exit(0)
assert request["fileType"] == "png"
print(json.dumps({
    "ok": True,
    "engine": "mnn",
    "text": "OCR seed text",
    "averageConfidence": 0.96,
    "regions": [{"text": "OCR seed text", "x": 10, "y": 10, "width": 220, "height": 80, "confidence": 0.96}],
    "pages": [],
    "warnings": []
}))
'
SH
chmod +x "$WORKER"

write_label() {
    source="$1"
    preset="$2"
    stem="$3"
    if [ "$preset" = "ocr" ]; then
        DOCTRUTH_OCR_COMMAND="$WORKER" \
            DOCTRUTH_MODEL_COMMAND="$WORKER" \
            DOCTRUTH_MODEL_CACHE="$MODEL_CACHE" \
            DOCTRUTH_MODEL_MANIFEST="$MODEL_MANIFEST" \
            "$JAVA_BIN" -jar "$CLI_JAR" parse "$WORK_DIR/$source" --format json --preset ocr -o "$WORK_DIR/$stem.json" >/dev/null
        DOCTRUTH_OCR_COMMAND="$WORKER" \
            DOCTRUTH_MODEL_COMMAND="$WORKER" \
            DOCTRUTH_MODEL_CACHE="$MODEL_CACHE" \
            DOCTRUTH_MODEL_MANIFEST="$MODEL_MANIFEST" \
            "$JAVA_BIN" -jar "$CLI_JAR" parse "$WORK_DIR/$source" --format markdown --preset ocr -o "$WORK_DIR/$stem.md" >/dev/null
    else
        "$JAVA_BIN" -jar "$CLI_JAR" parse "$WORK_DIR/$source" --format json -o "$WORK_DIR/$stem.json" >/dev/null
        "$JAVA_BIN" -jar "$CLI_JAR" parse "$WORK_DIR/$source" --format markdown -o "$WORK_DIR/$stem.md" >/dev/null
    fi
}

write_label multi-layout.pdf lite multi-layout
write_label table.pdf lite table
write_label scanned.pdf ocr scanned

DOCTRUTH_OCR_COMMAND="$WORKER" \
    DOCTRUTH_MODEL_COMMAND="$WORKER" \
    DOCTRUTH_MODEL_CACHE="$MODEL_CACHE" \
    DOCTRUTH_MODEL_MANIFEST="$MODEL_MANIFEST" \
    "$JAVA_BIN" -jar "$CLI_JAR" benchmark-corpus "$MANIFEST" --json > "$RESULT"

python3 - "$RESULT" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
assert payload["corpus"] == "parser-accuracy-seed-corpus", payload
assert payload["kind"] == "human-labeled", payload
assert payload["qualityProfile"] == "parser-accuracy", payload
assert payload["reviewType"] == "generated-seed", payload
assert payload["requiredTags"] == ["multi-layout", "table", "ocr", "bbox", "source-map"], payload
assert payload["minCasesPerTag"]["source-map"] == 1, payload
assert payload["passed"] is True, payload
cases = {case["name"]: case for case in payload["cases"]}
assert set(cases) == {"seed-multi-layout", "seed-table", "seed-ocr"}, cases
assert cases["seed-multi-layout"]["labelId"] == "seed-v1-0001", cases["seed-multi-layout"]
assert cases["seed-table"]["labelId"] == "seed-v1-0002", cases["seed-table"]
assert cases["seed-ocr"]["labelId"] == "seed-v1-0003", cases["seed-ocr"]
assert cases["seed-multi-layout"]["tags"] == ["multi-layout", "bbox", "source-map"], cases["seed-multi-layout"]
assert cases["seed-table"]["tags"] == ["table", "bbox", "source-map"], cases["seed-table"]
assert cases["seed-ocr"]["tags"] == ["ocr", "bbox", "source-map"], cases["seed-ocr"]
for case in cases.values():
    metrics = case["metrics"]
    assert metrics["reading_order_f1"] == 1.0, case
    assert metrics["quote_anchor_accuracy"] == 1.0, case
    assert metrics["bbox_coverage"] == 1.0, case
    assert metrics["bbox_iou"] == 1.0, case
    assert metrics["compact_llm_source_map_coverage"] == 1.0, case
    assert metrics["evidence_span_accuracy"] == 1.0, case
assert cases["seed-table"]["metrics"]["table_cell_f1"] == 1.0, cases["seed-table"]
assert cases["seed-ocr"]["metrics"]["ocr_text_accuracy"] == 1.0, cases["seed-ocr"]
PY

echo "doctruth parser accuracy seed corpus smoke passed"
