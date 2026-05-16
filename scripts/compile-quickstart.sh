#!/usr/bin/env sh
set -eu

javac_bin="${JAVAC:-javac}"
version="${VERSION:-0.2.0-alpha}"
build_dir="${BUILD_DIR:-target/quickstart-smoke}"
cp_file="${CP_FILE:-target/quickstart-classpath.txt}"

jar="target/doctruth-java-${version}.jar"

if [ ! -f "$jar" ]; then
    echo "SDK jar not found: $jar" >&2
    echo "Build it first: mvn package -DskipTests" >&2
    exit 1
fi

mvn -q dependency:build-classpath -Dmdep.outputFile="$cp_file"
rm -rf "$build_dir"
mkdir -p "$build_dir"

"$javac_bin" -cp "$jar:$(cat "$cp_file")" -d "$build_dir" examples/quickstart/Quickstart.java

echo "Quickstart compiles against $jar"
