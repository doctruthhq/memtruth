#!/usr/bin/env sh
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ "${DOCTRUTH_REAL_MODEL_SUITE:-0}" != "1" ]; then
    echo "skipping real model suite smoke; set DOCTRUTH_REAL_MODEL_SUITE=1"
    exit 0
fi

DOCTRUTH_REAL_RTDETR_SMOKE=1 sh scripts/smoke-doctruth-real-rtdetr-artifact.sh
DOCTRUTH_REAL_TATR_SMOKE=1 sh scripts/smoke-doctruth-real-tatr-artifact.sh

if [ "${DOCTRUTH_REAL_MODEL_SUITE_SKIP_SLANEXT:-0}" = "1" ]; then
    echo "skipping real SLANeXT smoke inside model suite"
else
    DOCTRUTH_REAL_SLANEXT_SMOKE=1 sh scripts/smoke-doctruth-real-slanext-artifact.sh
fi

echo "doctruth real model suite smoke passed"
