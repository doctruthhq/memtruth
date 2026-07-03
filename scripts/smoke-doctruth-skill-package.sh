#!/usr/bin/env sh
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SKILL="skills/doctruth/SKILL.md"
BOOTSTRAP="skills/doctruth/scripts/bootstrap-local-mcp.sh"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-skill-smoke.XXXXXX")"
CONFIG="$WORK_DIR/mcp.json"
PRINTED="$WORK_DIR/printed.json"

test -f "$SKILL"
test -f "skills/doctruth/agents/openai.yaml"
test -f "$BOOTSTRAP"

grep -q "doctruth mcp" "$SKILL"
grep -q "doctruth.parse_document" "$SKILL"
grep -q "doctruth.verify_citation" "$SKILL"

sh "$BOOTSTRAP" --command /opt/doctruth/bin/doctruth --out "$CONFIG" >/tmp/doctruth-skill-bootstrap.out
sh "$BOOTSTRAP" --command /opt/doctruth/bin/doctruth --print-json > "$PRINTED"

python3 - "$CONFIG" "$PRINTED" <<'PY'
import json
import pathlib
import sys

for path in sys.argv[1:]:
    config = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
    server = config["mcpServers"]["doctruth"]
    assert server["command"] == "/opt/doctruth/bin/doctruth"
    assert server["args"] == ["mcp"]
    assert server["transport"] == "stdio"
PY

echo "doctruth skill package smoke passed"
