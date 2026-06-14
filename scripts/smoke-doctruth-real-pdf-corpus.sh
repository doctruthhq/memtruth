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
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-real-pdf-corpus.XXXXXX")"
MANIFEST="$WORK_DIR/corpus.json"
RESULT="$WORK_DIR/result.json"

python3 - "$WORK_DIR" "$MANIFEST" <<'PY'
import json
import pathlib
import sys

work = pathlib.Path(sys.argv[1])
manifest_path = pathlib.Path(sys.argv[2])
expected = {
    "docId": "expected-w3c-dummy",
    "source": {
        "sourceFilename": "dummy.pdf",
        "sourceHash": "sha256:expected-w3c-dummy",
        "metadata": {"sourceFilename": "dummy.pdf", "pageCount": 1},
    },
    "body": {
        "pages": [{
            "pageNumber": 1,
            "width": 1000,
            "height": 1000,
            "textLayerAvailable": True,
            "imageHash": "",
        }],
        "units": [{
            "unitId": "unit-0001",
            "kind": "TABLE_CELL",
            "page": 1,
            "text": "Dummy PDF file",
            "evidenceSpanIds": ["span-0001"],
            "location": {
                "page": 1,
                "readingOrder": 1,
                "boundingBox": {"x0": 0, "y0": 0, "x1": 1000, "y1": 999.8812351526245},
            },
            "sourceObjectId": "cell-0001-0000-0000",
            "confidence": {"score": 1.0, "rationale": "human-labeled public fixture"},
            "warnings": [],
        }],
        "tables": [{
            "tableId": "table-0001",
            "pageNumber": 1,
            "boundingBox": {"x0": 0, "y0": 0, "x1": 1000, "y1": 999.8812351526245},
            "confidence": {"score": 1.0, "rationale": "human-labeled public fixture"},
            "cells": [{
                "cellId": "cell-0001-0000-0000",
                "rowRange": {"start": 0, "end": 0},
                "columnRange": {"start": 0, "end": 0},
                "boundingBox": {"x0": 0, "y0": 0, "x1": 1000, "y1": 999.8812351526245},
                "text": "Dummy PDF file",
            }],
        }],
    },
    "parserRun": {
        "parserVersion": "1.0.0",
        "preset": "lite",
        "backend": "human-label",
        "models": [],
        "warnings": [],
    },
    "auditGradeStatus": "UNKNOWN",
}
(work / "expected.md").write_text("| Dummy PDF file |\n| --- |\n", encoding="utf-8")
(work / "expected.json").write_text(json.dumps(expected, separators=(",", ":")), encoding="utf-8")
manifest_path.write_text(json.dumps({
    "name": "w3c-real-pdf-corpus",
    "kind": "human-labeled",
    "labeling": {
        "labelSetVersion": "public-w3c-v1",
        "reviewedAt": "2026-06-13",
        "reviewer": "doctruth-fixture",
        "requiredMetrics": [
            "reading_order_f1",
            "quote_anchor_accuracy",
            "table_cell_f1",
            "bbox_iou",
            "table_region_iou"
        ]
    },
    "minimums": {
        "reading_order_f1": 1.0,
        "quote_anchor_accuracy": 1.0,
        "table_cell_f1": 1.0,
        "bbox_iou": 0.99,
        "table_region_iou": 0.99,
    },
    "cases": [{
        "name": "w3c-dummy-pdf",
        "labelId": "public-w3c-v1-0001",
        "sourceUrl": "https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf",
        "sourceSha256": "sha256:3df79d34abbca99308e79cb94461c1893582604d68329a41fd4bec1885e6adb4",
        "expectedMarkdown": "expected.md",
        "expectedDocument": "expected.json",
    }],
}, separators=(",", ":")), encoding="utf-8")
PY

"$JAVA_BIN" -jar "$CLI_JAR" benchmark-corpus "$MANIFEST" --json > "$RESULT"

python3 - "$RESULT" <<'PY'
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
assert payload["corpus"] == "w3c-real-pdf-corpus", payload
assert payload["kind"] == "human-labeled", payload
assert payload["labelSetVersion"] == "public-w3c-v1", payload
assert payload["requiredMetrics"] == [
    "reading_order_f1",
    "quote_anchor_accuracy",
    "table_cell_f1",
    "bbox_iou",
    "table_region_iou",
], payload
assert payload["passed"] is True, payload
case = payload["cases"][0]
assert case["name"] == "w3c-dummy-pdf", case
assert case["metrics"]["reading_order_f1"] == 1.0, case
assert case["metrics"]["table_cell_f1"] == 1.0, case
assert case["metrics"]["bbox_iou"] >= 0.99, case
assert case["metrics"]["table_region_iou"] >= 0.99, case
PY

test -f "$WORK_DIR/.doctruth-corpus-cache/w3c-dummy-pdf-3df79d34abbca99308e79cb94461c1893582604d68329a41fd4bec1885e6adb4.pdf"

echo "doctruth real PDF corpus smoke passed"
