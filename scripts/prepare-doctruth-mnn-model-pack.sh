#!/usr/bin/env sh
set -eu

usage() {
    cat >&2 <<'EOF'
Usage: prepare-doctruth-mnn-model-pack.sh \
  --reference-manifest ONNX_PACK.json \
  --reference-cache CACHE_DIR \
  --output-manifest MNN_PACK.json \
  --output-cache CACHE_DIR

Converts reference ONNX artifacts into an MNN model pack manifest/cache using
MNNConvert. This is build/preparation tooling only; runtime promotion still
requires check-doctruth-mnn-pack-readiness.sh and benchmark acceptance.
EOF
}

REFERENCE_MANIFEST=""
REFERENCE_CACHE=""
OUTPUT_MANIFEST=""
OUTPUT_CACHE=""

while [ "$#" -gt 0 ]; do
    case "$1" in
        --reference-manifest)
            REFERENCE_MANIFEST="${2:-}"
            shift 2
            ;;
        --reference-cache)
            REFERENCE_CACHE="${2:-}"
            shift 2
            ;;
        --output-manifest)
            OUTPUT_MANIFEST="${2:-}"
            shift 2
            ;;
        --output-cache)
            OUTPUT_CACHE="${2:-}"
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

json_error() {
    code="$1"
    message="$2"
    jq -n --arg code "$code" --arg message "$message" \
        '{ok:false,code:$code,message:$message}'
}

if ! command -v jq >/dev/null 2>&1; then
    echo '{"ok":false,"code":"jq_unavailable","message":"jq is required"}'
    exit 2
fi

if [ -z "$REFERENCE_MANIFEST" ] || [ -z "$REFERENCE_CACHE" ] \
  || [ -z "$OUTPUT_MANIFEST" ] || [ -z "$OUTPUT_CACHE" ]; then
    json_error "missing_arguments" "reference manifest/cache and output manifest/cache are required"
    exit 2
fi

if [ ! -f "$REFERENCE_MANIFEST" ]; then
    json_error "reference_manifest_missing" "reference manifest does not exist"
    exit 2
fi

if [ ! -d "$REFERENCE_CACHE" ]; then
    json_error "reference_cache_missing" "reference cache does not exist"
    exit 2
fi

if [ -n "${DOCTRUTH_MNN_CONVERT_BIN:-}" ]; then
    CONVERTER="$DOCTRUTH_MNN_CONVERT_BIN"
else
    CONVERTER="$(
        command -v MNNConvert 2>/dev/null \
            || command -v mnnconvert 2>/dev/null \
            || true
    )"
fi

if [ -z "$CONVERTER" ] || [ ! -x "$CONVERTER" ]; then
    jq -n --arg converter "$CONVERTER" \
        '{ok:false,code:"mnn_convert_unavailable",converter:(if $converter == "" then null else $converter end)}'
    exit 2
fi

mkdir -p "$OUTPUT_CACHE" "$(dirname "$OUTPUT_MANIFEST")"
WORK_MANIFEST="$(mktemp "${TMPDIR:-/tmp}/doctruth-mnn-pack.XXXXXX.json")"
REPORT="$(mktemp "${TMPDIR:-/tmp}/doctruth-mnn-pack-report.XXXXXX.json")"

cleanup() {
    rm -f "$WORK_MANIFEST" "$REPORT"
}
trap cleanup EXIT INT TERM

jq -n \
  --arg referenceManifest "$REFERENCE_MANIFEST" \
  --arg referenceCache "$REFERENCE_CACHE" \
  --arg outputCache "$OUTPUT_CACHE" \
  --slurpfile pack "$REFERENCE_MANIFEST" '
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

  def mnn_cache_filename($artifact):
    (($artifact.name // "model" | tostring | sanitize)
     + "-"
     + ($artifact.version // "v1" | tostring | sanitize)
     + ".mnn");

  ($pack[0]) as $modelPack
  | {
      packId: (($modelPack.packId // "doctruth-model-pack") + "-mnn"),
      version: ($modelPack.version // "mnn-prepared"),
      source: ($modelPack.source // {}),
      presets: (
        ($modelPack.presets // {})
        | with_entries(
            .value = [
              .value[]
              | select((.parity.candidateEngine // "") == "rust-mnn")
              | . as $artifact
              | .backend = "mnn"
              | .format = "mnn"
              | .sourceBackend = $artifact.backend
              | .sourceFormat = $artifact.format
              | .sourceSha256 = $artifact.sha256
              | .sourceSizeBytes = $artifact.sizeBytes
              | .sourceCacheFilename = cache_filename($artifact)
              | .cacheFilename = mnn_cache_filename($artifact)
              | .url = ("file://" + $outputCache + "/" + mnn_cache_filename($artifact))
              | del(.sha256, .sizeBytes)
            ]
          )
      )
    }
    + (if ($modelPack.promotionGates? != null) then {promotionGates: $modelPack.promotionGates} else {} end)
  ' > "$WORK_MANIFEST"

CONVERT_COUNT="$(jq '[.presets[]?[]?] | length' "$WORK_MANIFEST")"
if [ "$CONVERT_COUNT" -eq 0 ]; then
    json_error "no_convertible_artifacts" "no artifacts declare parity.candidateEngine=rust-mnn"
    exit 2
fi

jq -n '{ok:true,converted:0,artifacts:[]}' > "$REPORT"

INDEX=0
while [ "$INDEX" -lt "$CONVERT_COUNT" ]; do
    ARTIFACT="$(jq -c "[.presets[]?[]?][$INDEX]" "$WORK_MANIFEST")"
    NAME="$(printf '%s' "$ARTIFACT" | jq -r '.name')"
    SOURCE_FILE="$(printf '%s' "$ARTIFACT" | jq -r '.sourceCacheFilename')"
    TARGET_FILE="$(printf '%s' "$ARTIFACT" | jq -r '.cacheFilename')"
    SOURCE_PATH="$REFERENCE_CACHE/$SOURCE_FILE"
    TARGET_PATH="$OUTPUT_CACHE/$TARGET_FILE"
    EXPECTED_SOURCE_SHA="$(printf '%s' "$ARTIFACT" | jq -r '.sourceSha256 // empty')"
    EXPECTED_SOURCE_SIZE="$(printf '%s' "$ARTIFACT" | jq -r '.sourceSizeBytes // empty')"

    if [ ! -f "$SOURCE_PATH" ]; then
        jq -n --arg name "$NAME" --arg path "$SOURCE_PATH" \
            '{ok:false,code:"reference_artifact_missing",artifact:$name,path:$path}'
        exit 2
    fi

    ACTUAL_SOURCE_SHA="sha256:$(shasum -a 256 "$SOURCE_PATH" | awk '{print $1}')"
    ACTUAL_SOURCE_SIZE="$(wc -c < "$SOURCE_PATH" | tr -d ' ')"
    if [ -n "$EXPECTED_SOURCE_SHA" ] && [ "$EXPECTED_SOURCE_SHA" != "$ACTUAL_SOURCE_SHA" ]; then
        jq -n --arg name "$NAME" --arg expected "$EXPECTED_SOURCE_SHA" --arg actual "$ACTUAL_SOURCE_SHA" \
            '{ok:false,code:"reference_sha_mismatch",artifact:$name,expected:$expected,actual:$actual}'
        exit 2
    fi
    if [ -n "$EXPECTED_SOURCE_SIZE" ] && [ "$EXPECTED_SOURCE_SIZE" != "$ACTUAL_SOURCE_SIZE" ]; then
        jq -n --arg name "$NAME" --arg expected "$EXPECTED_SOURCE_SIZE" --arg actual "$ACTUAL_SOURCE_SIZE" \
            '{ok:false,code:"reference_size_mismatch",artifact:$name,expected:($expected|tonumber),actual:($actual|tonumber)}'
        exit 2
    fi

    "$CONVERTER" -f ONNX --modelFile "$SOURCE_PATH" --MNNModel "$TARGET_PATH" >/dev/null

    if [ ! -f "$TARGET_PATH" ]; then
        jq -n --arg name "$NAME" --arg path "$TARGET_PATH" \
            '{ok:false,code:"mnn_convert_missing_output",artifact:$name,path:$path}'
        exit 2
    fi

    TARGET_SHA="sha256:$(shasum -a 256 "$TARGET_PATH" | awk '{print $1}')"
    TARGET_SIZE="$(wc -c < "$TARGET_PATH" | tr -d ' ')"
    jq \
      --arg name "$NAME" \
      --arg source "$SOURCE_PATH" \
      --arg target "$TARGET_PATH" \
      --arg sha "$TARGET_SHA" \
      --argjson size "$TARGET_SIZE" \
      '.converted += 1
       | .artifacts += [{
           name: $name,
           source: $source,
           target: $target,
           targetBackend: "mnn",
           sourceBackend: "onnxruntime",
           sha256: $sha,
           sizeBytes: $size
         }]' "$REPORT" > "$REPORT.next"
    mv "$REPORT.next" "$REPORT"

    jq \
      --arg targetFile "$TARGET_FILE" \
      --arg sha "$TARGET_SHA" \
      --argjson size "$TARGET_SIZE" \
      '(.presets[]?[]? | select(.cacheFilename == $targetFile)) |= (.sha256 = $sha | .sizeBytes = $size)' \
      "$WORK_MANIFEST" > "$WORK_MANIFEST.next"
    mv "$WORK_MANIFEST.next" "$WORK_MANIFEST"

    INDEX=$((INDEX + 1))
done

mv "$WORK_MANIFEST" "$OUTPUT_MANIFEST"
jq \
  --arg manifest "$OUTPUT_MANIFEST" \
  --arg cache "$OUTPUT_CACHE" \
  --arg converter "$CONVERTER" \
  '. + {outputManifest:$manifest,outputCache:$cache,converter:$converter}' \
  "$REPORT"
