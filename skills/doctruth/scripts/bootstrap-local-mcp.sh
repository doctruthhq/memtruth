#!/usr/bin/env sh
set -eu

COMMAND="${DOCTRUTH_COMMAND:-doctruth}"
OUT=""
PRINT_JSON=0

while [ "$#" -gt 0 ]; do
    case "$1" in
        --command)
            COMMAND="${2:?missing value for --command}"
            shift 2
            ;;
        --out)
            OUT="${2:?missing value for --out}"
            shift 2
            ;;
        --print-json)
            PRINT_JSON=1
            shift
            ;;
        -h|--help)
            echo "usage: bootstrap-local-mcp.sh [--command doctruth] [--out path] [--print-json]"
            exit 0
            ;;
        *)
            echo "unknown argument: $1" >&2
            exit 2
            ;;
    esac
done

json="$(python3 - "$COMMAND" <<'PY'
import json
import sys

command = sys.argv[1]
config = {
    "mcpServers": {
        "doctruth": {
            "command": command,
            "args": ["mcp"],
            "transport": "stdio",
        }
    }
}
print(json.dumps(config, indent=2, sort_keys=True))
PY
)"

if [ "$PRINT_JSON" -eq 1 ] || [ -z "$OUT" ]; then
    printf '%s\n' "$json"
fi

if [ -n "$OUT" ]; then
    mkdir -p "$(dirname "$OUT")"
    printf '%s\n' "$json" > "$OUT"
    echo "wrote MCP config to $OUT"
fi
