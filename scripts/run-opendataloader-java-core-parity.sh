#!/usr/bin/env bash
set -euo pipefail

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
BENCH_DIR="${DOCTRUTH_OPENDATALOADER_BENCH_DIR:-$ROOT/third_party/opendataloader-bench}"
BUILD_PROFILE="${DOCTRUTH_RUNTIME_BUILD_PROFILE:-debug}"
PRESET="${DOCTRUTH_OPENDATALOADER_PRESET:-lite}"
RUNTIME_PROFILE="${DOCTRUTH_RUNTIME_PROFILE:-edge-model}"
TIMEOUT_SECONDS="${DOCTRUTH_OPENDATALOADER_TIMEOUT_SECONDS:-}"
TIMESTAMP="${DOCTRUTH_OPENDATALOADER_GATE_TIMESTAMP:-$(date -u +%Y%m%dT%H%M%SZ)}"
ARTIFACT_ROOT="$BENCH_DIR/prediction/doctruth-java-core-$TIMESTAMP"
MANIFEST="$ROOT/runtime/doctruth-runtime/Cargo.toml"
LOCAL_OCR_MANIFEST="$ROOT/model-packs/ppocr-v5-mobile-mnn.json"
LOCAL_OCR_CACHE="$ROOT/target/ppocr-v5-mobile-mnn-cache"
RUN_FULL200=0
RUN_SMOKE=0

usage() {
  cat <<'EOF'
Usage: run-opendataloader-java-core-parity.sh --smoke|--full200

Runs the Java-core OpenDataLoader parity gate through the Rust benchmark runner.

Options:
  --smoke      Build once and run the selected smoke corpus only.
  --full200    Run the selected smoke corpus first, then full200 only if smoke passes.
  -h, --help   Show this help.

Environment:
  DOCTRUTH_OPENDATALOADER_GATE_TIMESTAMP  Override artifact timestamp.
  DOCTRUTH_OPENDATALOADER_PRESET          Parser preset, default lite.
  DOCTRUTH_RUNTIME_PROFILE                Runtime profile, default edge-model.
  DOCTRUTH_OPENDATALOADER_TIMEOUT_SECONDS Per-document timeout passed to the runner.
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --smoke)
      RUN_SMOKE=1
      shift
      ;;
    --full200)
      RUN_FULL200=1
      RUN_SMOKE=1
      shift
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

if [ "$RUN_SMOKE" = "0" ]; then
  echo "Choose --smoke or --full200" >&2
  usage >&2
  exit 2
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required for OpenDataLoader gate report checks" >&2
  exit 2
fi

case "$BUILD_PROFILE" in
  debug|release) ;;
  *)
    echo "DOCTRUTH_RUNTIME_BUILD_PROFILE must be debug or release" >&2
    exit 2
    ;;
esac

mkdir -p "$ARTIFACT_ROOT"

echo "Building Java CLI once..."
mvn -q -DskipTests package >/dev/null
CLI_JAR="$(find "$ROOT/target" -maxdepth 1 -name 'doctruth-java-*-all.jar' | sort | tail -1)"
if [ -z "$CLI_JAR" ] || [ ! -f "$CLI_JAR" ]; then
  echo "Java CLI jar was not produced under $ROOT/target" >&2
  exit 2
fi

USE_LOCAL_MNN_OCR=0
if [ "$RUNTIME_PROFILE" = "edge-model" ] \
  && [ -f "$LOCAL_OCR_MANIFEST" ] \
  && [ -d "$LOCAL_OCR_CACHE" ]; then
  USE_LOCAL_MNN_OCR=1
fi

echo "Building Rust runtime once..."
if [ "$BUILD_PROFILE" = "release" ]; then
  RUNTIME_BIN="$ROOT/runtime/doctruth-runtime/target/release/doctruth-runtime"
  if [ "$USE_LOCAL_MNN_OCR" = "1" ]; then
    cargo build --release --manifest-path "$MANIFEST" --features mnn-ocr --bin doctruth-runtime --bin doctruth-mnn-model-worker >/dev/null
  else
    cargo build --release --manifest-path "$MANIFEST" >/dev/null
  fi
else
  RUNTIME_BIN="$ROOT/runtime/doctruth-runtime/target/debug/doctruth-runtime"
  if [ "$USE_LOCAL_MNN_OCR" = "1" ]; then
    cargo build --manifest-path "$MANIFEST" --features mnn-ocr --bin doctruth-runtime --bin doctruth-mnn-model-worker >/dev/null
  else
    cargo build --manifest-path "$MANIFEST" >/dev/null
  fi
fi

prepare_smoke_bench() {
  local smoke_dir="$1"
  rm -rf "$smoke_dir"
  mkdir -p "$smoke_dir/pdfs" "$smoke_dir/ground-truth/markdown"

  cat >"$ARTIFACT_ROOT/smoke-docs.tsv" <<'EOF'
01030000000001	simple single column
01030000000145	two-column
01030000000160	sidebar resume/sidebar layout
01030000000083	bordered table
01030000000127	borderless table
EOF

  if [ "$USE_LOCAL_MNN_OCR" = "1" ]; then
    printf '%s\t%s\n' "01030000000165" "scanned/OCR fixture" >>"$ARTIFACT_ROOT/smoke-docs.tsv"
  else
    printf '%s\n' "scanned/OCR fixture skipped: local MNN OCR manifest/cache not found" >"$ARTIFACT_ROOT/smoke-ocr-skip.txt"
  fi

  while IFS="$(printf '\t')" read -r doc_id _label; do
    [ -n "$doc_id" ] || continue
    cp -p "$BENCH_DIR/pdfs/$doc_id.pdf" "$smoke_dir/pdfs/$doc_id.pdf"
    cp -p "$BENCH_DIR/ground-truth/markdown/$doc_id.md" "$smoke_dir/ground-truth/markdown/$doc_id.md"
  done <"$ARTIFACT_ROOT/smoke-docs.tsv"
}

run_gate() {
  local label="$1"
  local bench_dir="$2"
  local output_dir="$3"
  shift 3

  rm -rf "$output_dir"
  mkdir -p "$output_dir"

  local args=(
    "$ROOT/scripts/run-doctruth-opendataloader-bench.sh"
    --bench-dir "$bench_dir"
    --engine "doctruth-java-core-$TIMESTAMP-$label"
    --backend opendataloader-java-core
    --runtime-profile "$RUNTIME_PROFILE"
    --preset "$PRESET"
    --output-dir "$output_dir"
  )
  if [ -n "$TIMEOUT_SECONDS" ]; then
    args+=(--timeout-seconds "$TIMEOUT_SECONDS")
  fi
  args+=("$@")

  DOCTRUTH_JAVA_CLI_JAR="$CLI_JAR" \
    DOCTRUTH_RUNTIME_BIN="$RUNTIME_BIN" \
    DOCTRUTH_OPENDATALOADER_SKIP_BUILDS=1 \
    bash "${args[@]}" >"$output_dir/runner-output.txt"

  jq -e '.parsed_count == .document_count and .failed_count == 0' "$output_dir/summary.json" >/dev/null
  jq -e '.metrics.score.overall_mean >= 0 and .metrics.score.nid_mean >= 0' "$output_dir/evaluation.json" >/dev/null
}

SMOKE_BENCH="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-opendataloader-java-core-smoke.XXXXXX")"
trap 'rm -rf "$SMOKE_BENCH"' EXIT

prepare_smoke_bench "$SMOKE_BENCH"
echo "Running Java-core smoke gate..."
run_gate smoke "$SMOKE_BENCH" "$ARTIFACT_ROOT/smoke"

if [ "$RUN_FULL200" = "1" ]; then
  echo "Smoke passed; running Java-core full200 gate..."
  run_gate full200 "$BENCH_DIR" "$ARTIFACT_ROOT/full200"
fi

echo "$ARTIFACT_ROOT"
