#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

if [ -z "${DOCTRUTH_MODEL_MANIFEST:-}" ]; then
  echo "DOCTRUTH_MODEL_MANIFEST is required for the MNN promotion bench lane" >&2
  exit 2
fi

if [ -z "${DOCTRUTH_MODEL_CACHE:-}" ]; then
  echo "DOCTRUTH_MODEL_CACHE is required for the MNN promotion bench lane" >&2
  exit 2
fi

if [ ! -f "$DOCTRUTH_MODEL_MANIFEST" ]; then
  echo "DOCTRUTH_MODEL_MANIFEST does not exist: $DOCTRUTH_MODEL_MANIFEST" >&2
  exit 2
fi

if [ ! -d "$DOCTRUTH_MODEL_CACHE" ]; then
  echo "DOCTRUTH_MODEL_CACHE does not exist: $DOCTRUTH_MODEL_CACHE" >&2
  exit 2
fi

set +e
READINESS_REPORT="$(sh "$ROOT/scripts/check-doctruth-mnn-pack-readiness.sh" \
  --manifest "$DOCTRUTH_MODEL_MANIFEST" \
  --cache "$DOCTRUTH_MODEL_CACHE" 2>&1)"
READINESS_STATUS="$?"
set -e
if [ "$READINESS_STATUS" -ne 0 ]; then
  printf '%s\n' "$READINESS_REPORT" >&2
  exit "$READINESS_STATUS"
fi

sh "$ROOT/scripts/run-doctruth-opendataloader-bench.sh" \
  --runtime-profile edge-model \
  "$@"
