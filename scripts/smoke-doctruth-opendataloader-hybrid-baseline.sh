#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
ENGINE="doctruth-opendataloader-hybrid-baseline-smoke"
DOC_ID="01030000000045"

if [ "${DOCTRUTH_ALLOW_PYTHON_ORACLE:-}" != "1" ]; then
  cat >&2 <<'EOF'
refusing to start Python/OpenDataLoader hybrid baseline smoke.

This smoke is oracle-only legacy benchmark infrastructure. It is not the
default DocTruth parser, OpenDataLoader prediction, or MNN promotion path.

Use scripts/run-doctruth-opendataloader-bench.sh for the default Rust runner.
Set DOCTRUTH_ALLOW_PYTHON_ORACLE=1 only when intentionally reproducing the
heavy OpenDataLoader/docling-fast oracle baseline.
EOF
  exit 2
fi

python3 "$ROOT/scripts/doctruth_opendataloader_prediction.py" \
  --bench-dir "$ROOT/third_party/opendataloader-bench" \
  --engine "$ENGINE" \
  --reference-engine opendataloader-hybrid \
  --doc-id "$DOC_ID"

python3 - "$ROOT" "$ENGINE" "$DOC_ID" <<'PY'
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
engine = sys.argv[2]
doc_id = sys.argv[3]
bench = root / "third_party" / "opendataloader-bench"
actual = json.loads((bench / "prediction" / engine / "evaluation.json").read_text())
reference = json.loads((bench / "prediction" / "opendataloader-hybrid" / "evaluation.json").read_text())

actual_doc = next(doc for doc in actual["documents"] if doc["document_id"] == doc_id)
reference_doc = next(doc for doc in reference["documents"] if doc["document_id"] == doc_id)
for metric in ("overall", "nid", "teds", "mhs"):
    a = actual_doc["scores"][metric]
    r = reference_doc["scores"][metric]
    if a is None and r is None:
        continue
    if abs(a - r) > 1e-12:
        raise SystemExit(f"{metric} mismatch: actual={a} reference={r}")

summary = json.loads((bench / "prediction" / engine / "summary.json").read_text())
if summary.get("reference_engine") != "opendataloader-hybrid":
    raise SystemExit("summary missing reference_engine")
if summary.get("runtime_contract") != "OpenDataLoader hybrid Markdown baseline":
    raise SystemExit("summary missing hybrid baseline contract")

print("doctruth opendataloader hybrid baseline smoke passed")
PY
