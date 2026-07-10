#!/usr/bin/env sh
set -eu

repo_root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
failures=0

require_file() {
    path="$1"
    if [ ! -f "$repo_root/$path" ]; then
        echo "missing required file: $path" >&2
        failures=$((failures + 1))
    fi
}

require_dir() {
    path="$1"
    if [ ! -d "$repo_root/$path" ]; then
        echo "missing required directory: $path" >&2
        failures=$((failures + 1))
    fi
}

reject_path() {
    path="$1"
    if [ -e "$repo_root/$path" ]; then
        echo "legacy repository path still exists: $path" >&2
        failures=$((failures + 1))
    fi
}

require_file "rust/Cargo.toml"
require_dir "rust/crates"
require_file "java/pom.xml"
require_dir "java/src/main/java"
require_dir "java/src/test/java"

reject_path "memtruth-sdk"
reject_path "pom.xml"
reject_path "src"

if [ "$failures" -ne 0 ]; then
    echo "repository layout contract failed with $failures violation(s)" >&2
    exit 1
fi

echo "repository layout contract passed"
