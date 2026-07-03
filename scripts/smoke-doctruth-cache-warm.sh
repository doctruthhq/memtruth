#!/usr/bin/env sh
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

mvn -q -DskipTests package

JAVA_BIN="${JAVA_HOME:-}/bin/java"
if [ ! -x "$JAVA_BIN" ] && [ -x /opt/homebrew/opt/openjdk/bin/java ]; then
    JAVA_BIN=/opt/homebrew/opt/openjdk/bin/java
fi
if [ ! -x "$JAVA_BIN" ]; then
    JAVA_BIN=java
fi

CLI_JAR="$(find target -maxdepth 1 -name 'doctruth-java-*-all.jar' | sort | tail -1)"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/doctruth-cache-warm-smoke.XXXXXX")"
SOURCE="$WORK_DIR/slanet.onnx"
MANIFEST="$WORK_DIR/models.json"
REMOTE_MANIFEST="$WORK_DIR/remote-models.json"
HTTP_MANIFEST="$WORK_DIR/http-models.json"
CACHE="$WORK_DIR/cache"
HTTP_CACHE="$WORK_DIR/http-cache"
OUT="$WORK_DIR/cache.json"
HTTP_OUT="$WORK_DIR/http-cache.json"
ERR="$WORK_DIR/remote.err"
PORT_FILE="$WORK_DIR/http-port.txt"

printf "tiny local model" > "$SOURCE"
printf "tiny remote model" > "$WORK_DIR/remote-slanet.onnx"

python3 - "$SOURCE" "$MANIFEST" "$REMOTE_MANIFEST" <<'PY'
import hashlib
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
manifest = pathlib.Path(sys.argv[2])
remote_manifest = pathlib.Path(sys.argv[3])
sha = "sha256:" + hashlib.sha256(source.read_bytes()).hexdigest()
base = {
    "presets": {
        "table-lite": [{
            "name": "slanet-plus",
            "version": "local-smoke",
            "source": source.name,
            "sha256": sha,
            "sizeBytes": source.stat().st_size,
            "required": True,
            "task": "table-structure",
            "backend": "onnxruntime",
            "format": "onnx",
            "precision": "int8",
            "license": "apache-2.0",
        }]
    }
}
manifest.write_text(json.dumps(base, indent=2), encoding="utf-8")
base["presets"]["table-lite"][0]["source"] = "https://models.example/slanet.onnx"
base["presets"]["table-lite"][0]["sha256"] = "sha256:" + ("0" * 64)
remote_manifest.write_text(json.dumps(base, indent=2), encoding="utf-8")
PY

"$JAVA_BIN" -jar "$CLI_JAR" cache warm "$MANIFEST" --preset table-lite --cache "$CACHE" --json > "$OUT"

python3 - "$OUT" "$CACHE" <<'PY'
import json
import pathlib
import sys

doc = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
cache = pathlib.Path(sys.argv[2])
assert doc["cacheDir"] == str(cache)
assert doc["allReady"] is True
artifact = doc["artifacts"][0]
assert artifact["identity"] == "slanet-plus:local-smoke"
assert artifact["status"] == "READY"
assert pathlib.Path(artifact["cachePath"]).read_text(encoding="utf-8") == "tiny local model"
assert artifact["actualSha256"].startswith("sha256:")
assert artifact["task"] == "table-structure"
assert artifact["backend"] == "onnxruntime"
assert artifact["format"] == "onnx"
assert artifact["precision"] == "int8"
assert artifact["license"] == "apache-2.0"
PY

python3 - "$WORK_DIR" "$PORT_FILE" <<'PY' &
import functools
import http.server
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
port_file = pathlib.Path(sys.argv[2])
handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(root))
server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
port_file.write_text(str(server.server_address[1]), encoding="utf-8")
server.serve_forever()
PY
SERVER_PID=$!
trap 'kill "$SERVER_PID" 2>/dev/null || true' EXIT

while [ ! -s "$PORT_FILE" ]; do
    sleep 0.1
done

python3 - "$WORK_DIR/remote-slanet.onnx" "$HTTP_MANIFEST" "$PORT_FILE" <<'PY'
import hashlib
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
manifest = pathlib.Path(sys.argv[2])
port = pathlib.Path(sys.argv[3]).read_text(encoding="utf-8").strip()
payload = source.read_bytes()
manifest.write_text(json.dumps({
    "presets": {
        "table-lite": [{
            "name": "slanet-plus",
            "version": "http-smoke",
            "source": f"http://127.0.0.1:{port}/{source.name}",
            "sha256": "sha256:" + hashlib.sha256(payload).hexdigest(),
            "sizeBytes": len(payload),
            "required": True,
            "task": "table-structure",
            "backend": "onnxruntime",
            "format": "onnx",
            "precision": "int8",
            "license": "apache-2.0",
        }]
    }
}, indent=2), encoding="utf-8")
PY

"$JAVA_BIN" -jar "$CLI_JAR" cache warm "$HTTP_MANIFEST" --preset table-lite --cache "$HTTP_CACHE" --json > "$HTTP_OUT"

python3 - "$HTTP_OUT" "$HTTP_CACHE" <<'PY'
import json
import pathlib
import sys

doc = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
cache = pathlib.Path(sys.argv[2])
assert doc["cacheDir"] == str(cache)
assert doc["allReady"] is True
artifact = doc["artifacts"][0]
assert artifact["identity"] == "slanet-plus:http-smoke"
assert artifact["status"] == "READY"
assert pathlib.Path(artifact["cachePath"]).read_text(encoding="utf-8") == "tiny remote model"
assert artifact["task"] == "table-structure"
assert artifact["backend"] == "onnxruntime"
assert artifact["format"] == "onnx"
assert artifact["precision"] == "int8"
assert artifact["license"] == "apache-2.0"
PY

if "$JAVA_BIN" -jar "$CLI_JAR" cache warm "$REMOTE_MANIFEST" --preset table-lite --cache "$WORK_DIR/remote-cache" --offline 2> "$ERR"; then
    echo "expected offline remote cache warm to fail" >&2
    exit 1
fi

grep -q "offline mode refuses remote model source" "$ERR"

echo "doctruth cache warm smoke passed"
