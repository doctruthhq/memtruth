#!/usr/bin/env sh
set -eu

usage() {
    cat >&2 <<'EOF'
Usage: check-doctruth-mnn-pack-readiness.sh --manifest MODEL_PACK.json --cache CACHE_DIR

Checks whether a DocTruth model pack is ready for the production edge MNN lane.
ONNX/onnxruntime artifacts are reference-only unless a real MNN candidate
artifact is present and verified in the cache.
EOF
}

MANIFEST=""
CACHE=""

while [ "$#" -gt 0 ]; do
    case "$1" in
        --manifest)
            MANIFEST="${2:-}"
            shift 2
            ;;
        --cache)
            CACHE="${2:-}"
            shift 2
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            echo "unknown argument: $1" >&2
            usage
            exit 2
            ;;
    esac
done

if ! command -v jq >/dev/null 2>&1; then
    echo '{"ok":false,"productionReady":false,"code":"jq_unavailable"}'
    exit 2
fi

if [ -z "$MANIFEST" ] || [ -z "$CACHE" ]; then
    echo '{"ok":false,"productionReady":false,"code":"missing_arguments"}'
    exit 2
fi

if [ ! -f "$MANIFEST" ]; then
    jq -n --arg manifest "$MANIFEST" \
        '{ok:false,productionReady:false,code:"manifest_missing",manifest:$manifest}'
    exit 2
fi

CONVERTER_PATH="$(
    command -v MNNConvert 2>/dev/null \
        || command -v mnnconvert 2>/dev/null \
        || true
)"

jq -n \
  --arg manifest "$MANIFEST" \
  --arg cache "$CACHE" \
  --arg converter "$CONVERTER_PATH" \
  --slurpfile pack "$MANIFEST" '
  def sanitize:
    gsub("[^A-Za-z0-9._-]"; "_");

  def cache_filename($artifact):
    if ($artifact.cacheFilename | type) == "string" then
      $artifact.cacheFilename
    else
      (($artifact.name // "model" | tostring | sanitize)
       + "-"
       + ($artifact.version // "v1" | tostring | sanitize)
       + ".bin")
    end;

  def artifact_report($preset; $artifact):
    (cache_filename($artifact)) as $filename
    | ($cache + "/" + $filename) as $path
    | ($artifact.backend == "mnn" and $artifact.format == "mnn") as $is_mnn
    | (($artifact.parity.candidateEngine // "") == "rust-mnn") as $expects_mnn
    | {
        preset: $preset,
        name: $artifact.name,
        version: $artifact.version,
        task: $artifact.task,
        backend: $artifact.backend,
        format: $artifact.format,
        expectedRuntime: (if $expects_mnn then "rust-mnn" else "unknown" end),
        expectedSha256: $artifact.sha256,
        expectedSizeBytes: $artifact.sizeBytes,
        cachePath: $path,
        cacheFilename: $filename,
        cacheStatus: "MISSING",
        mnnCandidate: $is_mnn,
        blockedReasons: (
          []
          + (if $is_mnn then [] else ["missing_mnn_candidate"] end)
          + (if $expects_mnn then [] else ["missing_rust_mnn_parity_contract"] end)
        )
      };

  ($pack[0]) as $modelPack
  | [
      ($modelPack.presets // {})
      | to_entries[]
      | .key as $preset
      | .value[]
      | artifact_report($preset; .)
    ] as $initial
  | $initial as $artifacts
  | {
      ok: false,
      productionReady: false,
      code: "mnn_pack_not_ready",
      manifest: $manifest,
      cache: $cache,
      converter: {
        available: ($converter != ""),
        path: (if $converter == "" then null else $converter end)
      },
      summary: {
        total: ($artifacts | length),
        mnnReady: 0,
        blocked: ($artifacts | length)
      },
      artifacts: $artifacts
    }
  ' > /tmp/doctruth-mnn-pack-readiness.$$.json

REPORT="/tmp/doctruth-mnn-pack-readiness.$$.json"
READY_COUNT=0
BLOCKED_COUNT=0
UPDATED="$REPORT.updated"

jq '.artifacts' "$REPORT" >/dev/null

INDEX=0
COUNT="$(jq '.artifacts | length' "$REPORT")"
while [ "$INDEX" -lt "$COUNT" ]; do
    PATH_VALUE="$(jq -r ".artifacts[$INDEX].cachePath" "$REPORT")"
    EXPECTED_SHA="$(jq -r ".artifacts[$INDEX].expectedSha256 // empty" "$REPORT")"
    EXPECTED_SIZE="$(jq -r ".artifacts[$INDEX].expectedSizeBytes // empty" "$REPORT")"
    if [ -f "$PATH_VALUE" ]; then
        ACTUAL_SHA="sha256:$(shasum -a 256 "$PATH_VALUE" | awk '{print $1}')"
        ACTUAL_SIZE="$(wc -c < "$PATH_VALUE" | tr -d ' ')"
        STATUS="READY"
        EXTRA_REASONS="[]"
        if [ -n "$EXPECTED_SHA" ] && [ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]; then
            STATUS="SHA_MISMATCH"
            EXTRA_REASONS='["sha_mismatch"]'
        fi
        if [ -n "$EXPECTED_SIZE" ] && [ "$ACTUAL_SIZE" != "$EXPECTED_SIZE" ]; then
            if [ "$STATUS" = "READY" ]; then
                STATUS="SIZE_MISMATCH"
            fi
            if [ "$EXTRA_REASONS" = "[]" ]; then
                EXTRA_REASONS='["size_mismatch"]'
            else
                EXTRA_REASONS='["sha_mismatch","size_mismatch"]'
            fi
        fi
    else
        STATUS="MISSING"
        ACTUAL_SHA=""
        ACTUAL_SIZE=""
        EXTRA_REASONS='["cache_missing"]'
    fi
    jq \
      --argjson index "$INDEX" \
      --arg status "$STATUS" \
      --arg actualSha "$ACTUAL_SHA" \
      --arg actualSize "$ACTUAL_SIZE" \
      --argjson extraReasons "$EXTRA_REASONS" \
      --argjson isReady "$(if [ "$STATUS" = READY ] && jq -e ".artifacts[$INDEX].mnnCandidate == true and (.artifacts[$INDEX].blockedReasons | length == 0)" "$REPORT" >/dev/null; then echo true; else echo false; fi)" \
      ".artifacts[\$index].cacheStatus = \$status
       | .artifacts[\$index].actualSha256 = (if \$actualSha == \"\" then null else \$actualSha end)
       | .artifacts[\$index].actualSizeBytes = (if \$actualSize == \"\" then null else (\$actualSize | tonumber) end)
       | .artifacts[\$index].blockedReasons = (if \$isReady then [] else (.artifacts[\$index].blockedReasons + \$extraReasons | unique) end)" \
      "$REPORT" > "$UPDATED"
    mv "$UPDATED" "$REPORT"
    INDEX=$((INDEX + 1))
done

READY_COUNT="$(jq '[.artifacts[] | select(.cacheStatus == "READY" and .mnnCandidate == true and (.blockedReasons | length == 0))] | length' "$REPORT")"
BLOCKED_COUNT="$(jq '[.artifacts[] | select((.cacheStatus != "READY") or (.mnnCandidate != true) or ((.blockedReasons | length) > 0))] | length' "$REPORT")"
PRODUCTION_READY="$(if [ "$BLOCKED_COUNT" -eq 0 ] && [ "$READY_COUNT" -gt 0 ]; then echo true; else echo false; fi)"
CODE="$(if [ "$PRODUCTION_READY" = true ]; then echo mnn_pack_ready; else echo mnn_pack_not_ready; fi)"

jq \
  --argjson ready "$READY_COUNT" \
  --argjson blocked "$BLOCKED_COUNT" \
  --argjson productionReady "$PRODUCTION_READY" \
  --arg code "$CODE" \
  '.ok = $productionReady
   | .productionReady = $productionReady
   | .code = $code
   | .summary.mnnReady = $ready
   | .summary.blocked = $blocked' \
  "$REPORT"

rm -f "$REPORT" "$UPDATED"

if [ "$PRODUCTION_READY" = true ]; then
    exit 0
fi
exit 1
