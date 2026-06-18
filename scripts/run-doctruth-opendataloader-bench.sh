#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
BENCH_DIR="$ROOT/third_party/opendataloader-bench"
MANIFEST="$ROOT/runtime/doctruth-runtime/Cargo.toml"
BUILD_PROFILE="${DOCTRUTH_RUNTIME_BUILD_PROFILE:-debug}"
BIN="${DOCTRUTH_RUNTIME_BIN:-}"
ENGINE="doctruth-runtime"
DOC_ID=""
LIMIT=""
PRESET="lite"
RUNTIME_PROFILE="${DOCTRUTH_RUNTIME_PROFILE:-edge-model}"
OUTPUT_DIR=""
EVALUATOR="rust"
TIMEOUT_SECONDS=""

usage() {
  cat <<'EOF'
Usage: run-doctruth-opendataloader-bench.sh [options]

Rust-owned OpenDataLoader Bench runner.

Options:
  --bench-dir DIR              OpenDataLoader Bench root.
  --engine NAME                Prediction engine name.
  --doc-id ID                  Run one document id.
  --limit N                    Run first N PDFs.
  --preset NAME                DocTruth parser preset.
  --runtime-profile PROFILE    edge-fast or edge-model.
  --runtime-bin PATH           doctruth-runtime binary.
  --release                    Build and run the release runtime binary.
  --output-dir DIR             Prediction output directory.
  --timeout-seconds SECONDS    Per-document Rust parse timeout.
  --skip-eval                  Do not run evaluator.
  --evaluator rust|official    Rust evaluator by default; official is oracle-only.
  --official-eval              Alias for --evaluator official.
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --bench-dir)
      BENCH_DIR="$2"
      shift 2
      ;;
    --engine)
      ENGINE="$2"
      shift 2
      ;;
    --doc-id)
      DOC_ID="$2"
      shift 2
      ;;
    --limit)
      LIMIT="$2"
      shift 2
      ;;
    --preset)
      PRESET="$2"
      shift 2
      ;;
    --runtime-profile)
      RUNTIME_PROFILE="$2"
      shift 2
      ;;
    --runtime-bin)
      BIN="$2"
      shift 2
      ;;
    --release)
      BUILD_PROFILE="release"
      shift
      ;;
    --output-dir)
      OUTPUT_DIR="$2"
      shift 2
      ;;
    --skip-eval)
      EVALUATOR="none"
      shift
      ;;
    --evaluator)
      EVALUATOR="$2"
      shift 2
      ;;
    --official-eval)
      EVALUATOR="official"
      shift
      ;;
    --reference-engine)
      echo "--reference-engine is oracle-only; use scripts/run-doctruth-opendataloader-hybrid-baseline.sh" >&2
      exit 2
      ;;
    --timeout-seconds)
      TIMEOUT_SECONDS="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

case "$EVALUATOR" in
  rust|official|none) ;;
  *)
    echo "--evaluator must be rust, official, or none" >&2
    exit 2
    ;;
esac

if [ -z "$OUTPUT_DIR" ]; then
  OUTPUT_DIR="$BENCH_DIR/prediction/$ENGINE"
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to build the runtime protocol request" >&2
  exit 2
fi

case "$BUILD_PROFILE" in
  debug|release) ;;
  *)
    echo "DOCTRUTH_RUNTIME_BUILD_PROFILE must be debug or release" >&2
    exit 2
    ;;
esac

if [ -z "$BIN" ]; then
  BIN="$ROOT/runtime/doctruth-runtime/target/$BUILD_PROFILE/doctruth-runtime"
fi

if [ "$BUILD_PROFILE" = "release" ]; then
  cargo build --release --manifest-path "$MANIFEST" >/dev/null
else
  cargo build --manifest-path "$MANIFEST" >/dev/null
fi
REPORT_TMP="$(mktemp "${TMPDIR:-/tmp}/doctruth-opendataloader-prediction-report.XXXXXX")"

REQUEST="$(jq -n \
  --arg bench_dir "$BENCH_DIR" \
  --arg engine "$ENGINE" \
  --arg doc_id "$DOC_ID" \
  --arg limit "$LIMIT" \
  --arg preset "$PRESET" \
  --arg runtime_profile "$RUNTIME_PROFILE" \
  --arg output_dir "$OUTPUT_DIR" \
  --arg timeout_seconds "$TIMEOUT_SECONDS" \
  '{
    command: "opendataloader_prediction",
    bench_dir: $bench_dir,
    engine: $engine,
    preset: $preset,
    runtime_profile: $runtime_profile,
    output_dir: $output_dir
  }
  + (if $doc_id == "" then {} else {doc_id: $doc_id} end)
  + (if $limit == "" then {} else {limit: ($limit | tonumber)} end)
  + (if $timeout_seconds == "" then {} else {timeout_seconds: ($timeout_seconds | tonumber)} end)')"

printf '%s' "$REQUEST" | "$BIN" > "$REPORT_TMP"
mkdir -p "$OUTPUT_DIR"
mv "$REPORT_TMP" "$OUTPUT_DIR/prediction-report.json"

case "$EVALUATOR" in
  rust)
    EVAL_REQUEST="$(jq -n \
      --arg ground_truth_dir "$BENCH_DIR/ground-truth/markdown" \
      --arg prediction_dir "$OUTPUT_DIR" \
      --arg output_path "$OUTPUT_DIR/evaluation.json" \
      --arg doc_id "$DOC_ID" \
      '{
        command: "opendataloader_evaluate_prediction",
        ground_truth_dir: $ground_truth_dir,
        prediction_dir: $prediction_dir,
        output_path: $output_path
      }
      + (if $doc_id == "" then {} else {doc_id: $doc_id} end)')"
    printf '%s' "$EVAL_REQUEST" | "$BIN" >/dev/null
    ;;
  official)
    if [ "${DOCTRUTH_ALLOW_PYTHON_ORACLE:-}" != "1" ]; then
      cat >&2 <<'EOF'
refusing to start official Python OpenDataLoader evaluator.

The official evaluator is oracle-only comparison infrastructure. It is not the
default DocTruth benchmark evaluator.

Use --evaluator rust for the default Rust path. Set
DOCTRUTH_ALLOW_PYTHON_ORACLE=1 only when intentionally comparing against the
upstream Python/APTED/lxml/rapidfuzz oracle.
EOF
      exit 2
    fi
    if command -v uv >/dev/null 2>&1; then
      set -- run src/evaluator.py --engine "$ENGINE"
      if [ -n "$DOC_ID" ]; then
        set -- "$@" --doc-id "$DOC_ID"
      fi
      (cd "$BENCH_DIR" && uv "$@")
    else
      set -- src/evaluator.py --engine "$ENGINE"
      if [ -n "$DOC_ID" ]; then
        set -- "$@" --doc-id "$DOC_ID"
      fi
      (cd "$BENCH_DIR" && python3 "$@")
    fi
    ;;
  none) ;;
esac

printf '%s\n' "$OUTPUT_DIR"
