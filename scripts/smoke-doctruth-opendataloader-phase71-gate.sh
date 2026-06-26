#!/usr/bin/env bash
set -euo pipefail

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-phase71-gate.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT

REQUEST_OUT="$WORK_DIR/request.json"
FAKE_RUNTIME="$WORK_DIR/fake runtime.sh"
FAKE_JAR="$WORK_DIR/path with spaces/doctruth java all.jar"
BENCH_DIR="$WORK_DIR/bench"
OUTPUT_DIR="$WORK_DIR/output with spaces"

mkdir -p "$(dirname "$FAKE_JAR")" "$BENCH_DIR/ground-truth/markdown" "$BENCH_DIR/pdfs"
printf '%s\n' fake-jar >"$FAKE_JAR"

cat >"$FAKE_RUNTIME" <<'SH'
#!/usr/bin/env sh
set -eu
cat > "$DOCTRUTH_PHASE71_REQUEST_OUT"
printf '%s\n' '{"ok":true}'
SH
chmod +x "$FAKE_RUNTIME"

DOCTRUTH_PHASE71_REQUEST_OUT="$REQUEST_OUT" \
DOCTRUTH_JAVA_CLI_JAR="$FAKE_JAR" \
DOCTRUTH_RUNTIME_BIN="$FAKE_RUNTIME" \
DOCTRUTH_OPENDATALOADER_SKIP_BUILDS=1 \
  sh "$ROOT/scripts/run-doctruth-opendataloader-bench.sh" \
    --bench-dir "$BENCH_DIR" \
    --backend opendataloader-java-core \
    --runtime-profile edge-fast \
    --output-dir "$OUTPUT_DIR" \
    --skip-eval >/dev/null

jq -e '
  (.java_backend_command | type == "array")
  and (.java_backend_command | length == 5)
  and (.java_backend_command[1] == "-jar")
  and (.java_backend_command[2] == $jar)
  and (.java_backend_command[3] == "opendataloader-backend")
  and (.java_backend_command[4] == "--stdio-jsonl")
' --arg jar "$FAKE_JAR" "$REQUEST_OUT" >/dev/null

GATE_ROOT="$WORK_DIR/gate"
SMOKE_DOCS="$GATE_ROOT/smoke-docs.tsv"
SMOKE_OUT="$GATE_ROOT/smoke"
mkdir -p "$SMOKE_OUT/markdown"
cat >"$SMOKE_DOCS" <<'EOF'
01030000000083	bordered table
01030000000127	borderless table
EOF
printf '%s\n' '| A | B |' '| - | - |' '| 1 | 2 |' >"$SMOKE_OUT/markdown/01030000000083.md"
printf '%s\n' '| A | B |' '| - | - |' '| 3 | 4 |' >"$SMOKE_OUT/markdown/01030000000127.md"
cat >"$SMOKE_OUT/summary.json" <<'EOF'
{
  "document_count": 2,
  "parsed_count": 2,
  "failed_count": 0,
  "documents": [
    {"document_id": "01030000000083"},
    {"document_id": "01030000000127"}
  ]
}
EOF
cat >"$SMOKE_OUT/evaluation.json" <<'EOF'
{
  "metrics": {
    "score": {
      "overall_mean": 0.50,
      "nid_mean": 0.50,
      "teds_mean": 0.0,
      "mhs_mean": 0.0
    }
  }
}
EOF

bash "$ROOT/scripts/run-opendataloader-java-core-parity.sh" \
  --check-output "$SMOKE_DOCS" "$SMOKE_OUT"

rm "$SMOKE_OUT/markdown/01030000000127.md"
if bash "$ROOT/scripts/run-opendataloader-java-core-parity.sh" \
  --check-output "$SMOKE_DOCS" "$SMOKE_OUT" >/dev/null 2>&1; then
  echo "expected missing markdown validation to fail" >&2
  exit 1
fi

echo "doctruth opendataloader phase 7.1 gate smoke passed"
