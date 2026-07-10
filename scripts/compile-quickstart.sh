#!/usr/bin/env sh
set -eu

javac_bin="${JAVAC:-javac}"
version="${VERSION:-0.2.0-alpha}"
repo_root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
java_root="$repo_root/java"
build_dir="${BUILD_DIR:-$repo_root/target/quickstart-smoke}"
cp_file="${CP_FILE:-$repo_root/target/quickstart-classpath.txt}"

jar="$java_root/target/doctruth-java-${version}.jar"

if [ ! -f "$jar" ]; then
    echo "SDK jar not found: $jar" >&2
    echo "Build it first: mvn -f java/pom.xml package -DskipTests" >&2
    exit 1
fi

mvn -q -f "$java_root/pom.xml" dependency:build-classpath -Dmdep.outputFile="$cp_file"
rm -rf "$build_dir"
mkdir -p "$build_dir"

"$javac_bin" -cp "$jar:$(cat "$cp_file")" -d "$build_dir" "$repo_root/examples/quickstart/Quickstart.java"

echo "Quickstart compiles against $jar"
