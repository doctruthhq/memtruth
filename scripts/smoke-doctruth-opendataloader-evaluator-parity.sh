#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
BENCH_DIR="$ROOT/third_party/opendataloader-bench"
MANIFEST="$ROOT/runtime/doctruth-runtime/Cargo.toml"
BIN="$ROOT/runtime/doctruth-runtime/target/debug/doctruth-runtime"
ENGINE="doctruth-rust-evaluator-parity"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-opendataloader-evaluator-parity.XXXXXX")"
GT_DIR="$WORK_DIR/ground-truth/markdown"
PRED_ROOT="$WORK_DIR/prediction"
PRED_DIR="$PRED_ROOT/$ENGINE"
MARKDOWN_DIR="$PRED_DIR/markdown"
OFFICIAL_EVAL="$PRED_DIR/official-evaluation.json"
RUST_EVAL="$PRED_DIR/rust-evaluation.json"

cleanup() {
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

PYTHON=""
if [ -x "$BENCH_DIR/.venv/bin/python" ]; then
  PYTHON="$BENCH_DIR/.venv/bin/python"
elif command -v python3 >/dev/null 2>&1; then
  PYTHON="$(command -v python3)"
fi

if [ -z "$PYTHON" ]; then
  echo "skip: no python available for upstream OpenDataLoader evaluator parity" >&2
  exit 0
fi

if ! "$PYTHON" - <<'PY' >/dev/null 2>&1
import rapidfuzz, apted, lxml, bs4
PY
then
  if command -v uv >/dev/null 2>&1; then
    PYTHON="uv run --project $BENCH_DIR python"
  else
    echo "skip: upstream OpenDataLoader evaluator dependencies are missing" >&2
    exit 0
  fi
fi

mkdir -p "$GT_DIR" "$MARKDOWN_DIR"

cat > "$GT_DIR/exact.md" <<'EOF_GT'
# Exact Title

Exact body text.
EOF_GT
cat > "$MARKDOWN_DIR/exact.md" <<'EOF_PRED'
# Exact Title

Exact body text.
EOF_PRED

cat > "$GT_DIR/heading-level.md" <<'EOF_GT'
# Shared Heading

Body under the shared heading.
EOF_GT
cat > "$MARKDOWN_DIR/heading-level.md" <<'EOF_PRED'
### Shared Heading

Body under the shared heading.
EOF_PRED

cat > "$GT_DIR/table-wrapper.md" <<'EOF_GT'
<table><thead><tr><th>Name</th><th>Score</th></tr></thead><tbody><tr><td>Ada</td><td>10</td></tr></tbody></table>
EOF_GT
cat > "$MARKDOWN_DIR/table-wrapper.md" <<'EOF_PRED'
<table><tr><td>Name</td><td>Score</td></tr><tr><td>Ada</td><td>10</td></tr></table>
EOF_PRED

cat > "$PRED_DIR/summary.json" <<EOF_SUMMARY
{
  "engine_name": "$ENGINE",
  "engine_version": "parity-smoke",
  "runtime_contract": "TrustDocument",
  "document_count": 3,
  "parsed_count": 3,
  "failed_count": 0
}
EOF_SUMMARY

cargo build --manifest-path "$MANIFEST" >/dev/null

# shellcheck disable=SC2086
$PYTHON "$BENCH_DIR/src/evaluator.py" \
  --ground-truth-dir "$GT_DIR" \
  --prediction-root "$PRED_ROOT" \
  --engine "$ENGINE" \
  --output-filename "$(basename "$OFFICIAL_EVAL")" \
  --log-level WARNING

"$BIN" <<EOF_RUST >/dev/null
{"command":"opendataloader_evaluate_prediction","ground_truth_dir":"$GT_DIR","prediction_dir":"$PRED_DIR","output_path":"$RUST_EVAL"}
EOF_RUST

python3 - "$OFFICIAL_EVAL" "$RUST_EVAL" <<'PY'
import json
import math
import pathlib
import sys

official = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
rust = json.loads(pathlib.Path(sys.argv[2]).read_text(encoding="utf-8"))

keys = [
    "overall_mean",
    "nid_mean",
    "nid_s_mean",
    "teds_mean",
    "teds_s_mean",
    "mhs_mean",
    "mhs_s_mean",
]

def close(left, right, tolerance=0.05):
    if left is None or right is None:
        return left is None and right is None
    return math.isclose(float(left), float(right), abs_tol=tolerance)

official_scores = official["metrics"]["score"]
rust_scores = rust["metrics"]["score"]
for key in keys:
    if not close(official_scores.get(key), rust_scores.get(key)):
        raise SystemExit(
            f"{key} mismatch: official={official_scores.get(key)} rust={rust_scores.get(key)}"
        )

official_docs = {
    item["document_id"]: item["scores"] for item in official.get("documents", [])
}
rust_docs = {
    item["document_id"]: item["scores"] for item in rust.get("documents", [])
}
if set(official_docs) != set(rust_docs):
    raise SystemExit(f"document id mismatch: {set(official_docs)} vs {set(rust_docs)}")

for doc_id, scores in official_docs.items():
    for key in ["nid", "nid_s", "teds", "teds_s", "mhs", "mhs_s"]:
        if not close(scores.get(key), rust_docs[doc_id].get(key)):
            raise SystemExit(
                f"{doc_id}.{key} mismatch: official={scores.get(key)} rust={rust_docs[doc_id].get(key)}"
            )

print("doctruth opendataloader evaluator parity smoke passed")
PY
