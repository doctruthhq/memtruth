#!/usr/bin/env sh
set -eu

version="${VERSION:-}"
repo="${GITHUB_REPOSITORY:-doctruthhq/DocTruth}"
jar="${JAR:-}"
runtime="${RUNTIME:-}"
dist="${DIST_DIR:-dist}"

usage() {
    cat <<'EOF'
Usage: scripts/package-cli-release.sh [--version VERSION] [--jar PATH] [--runtime PATH] [--dist DIR]

Creates release-ready CLI artifacts:
  dist/doctruth-VERSION.tar.gz
  dist/doctruth-java-VERSION-all.jar
  dist/checksums.txt
  dist/homebrew/doctruth.rb

The tarball contains:
  bin/doctruth
  bin/doctruth-runtime
  bin/doctruth-rapidocr-mnn-worker
  bin/doctruth-slanext-table-worker
  bin/doctruth-onnx-model-worker
  bin/doctruth_onnx_worker_lib.py
  bin/smoke-doctruth-real-model-suite.sh
  bin/smoke-doctruth-runtime-real-model-artifacts.sh
  bin/smoke-doctruth-runtime-real-ocr-corpus.sh
  bin/smoke-doctruth-runtime-real-slanext-artifact.sh
  bin/smoke-doctruth-runtime-ocr-worker.sh
  bin/smoke-doctruth-runtime-slanext-worker.sh
  lib/doctruth-java-all.jar
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --version)
            shift
            [ "$#" -gt 0 ] || {
                echo "missing value for --version" >&2
                exit 2
            }
            version="$1"
            ;;
        --jar)
            shift
            [ "$#" -gt 0 ] || {
                echo "missing value for --jar" >&2
                exit 2
            }
            jar="$1"
            ;;
        --runtime)
            shift
            [ "$#" -gt 0 ] || {
                echo "missing value for --runtime" >&2
                exit 2
            }
            runtime="$1"
            ;;
        --dist)
            shift
            [ "$#" -gt 0 ] || {
                echo "missing value for --dist" >&2
                exit 2
            }
            dist="$1"
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "unknown option: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
    shift
done

if [ -z "$version" ]; then
    version="$(awk -F'[<>]' '/<version>/ { print $3; exit }' pom.xml)"
fi

if [ -z "$jar" ]; then
    jar="target/doctruth-java-${version}-all.jar"
fi

if [ ! -f "$jar" ]; then
    echo "CLI jar not found: $jar" >&2
    echo "Build it first: mvn package -DskipTests" >&2
    exit 1
fi

if [ -z "$runtime" ]; then
    if [ -x runtime/doctruth-runtime/target/release/doctruth-runtime ]; then
        runtime="runtime/doctruth-runtime/target/release/doctruth-runtime"
    elif [ -x runtime/doctruth-runtime/target/debug/doctruth-runtime ]; then
        runtime="runtime/doctruth-runtime/target/debug/doctruth-runtime"
    fi
fi

if [ -z "$runtime" ] || [ ! -x "$runtime" ]; then
    echo "Rust runtime not found: $runtime" >&2
    echo "Build it first: cargo build --manifest-path runtime/doctruth-runtime/Cargo.toml --release" >&2
    exit 1
fi

mkdir -p "$dist/homebrew"

package_dir="${dist}/doctruth-${version}"
rm -rf "$package_dir"
mkdir -p "$package_dir/bin" "$package_dir/lib"

cp "$jar" "$package_dir/lib/doctruth-java-all.jar"
cp "$runtime" "$package_dir/bin/doctruth-runtime"
cp scripts/doctruth-rapidocr-mnn-worker "$package_dir/bin/doctruth-rapidocr-mnn-worker"
cp scripts/doctruth-slanext-table-worker "$package_dir/bin/doctruth-slanext-table-worker"
cp scripts/doctruth-onnx-model-worker "$package_dir/bin/doctruth-onnx-model-worker"
cp scripts/doctruth_onnx_worker_lib.py "$package_dir/bin/doctruth_onnx_worker_lib.py"
cp scripts/smoke-doctruth-real-model-suite.sh "$package_dir/bin/smoke-doctruth-real-model-suite.sh"
cp scripts/smoke-doctruth-runtime-real-model-artifacts.sh "$package_dir/bin/smoke-doctruth-runtime-real-model-artifacts.sh"
cp scripts/smoke-doctruth-runtime-real-ocr-corpus.sh "$package_dir/bin/smoke-doctruth-runtime-real-ocr-corpus.sh"
cp scripts/smoke-doctruth-runtime-real-slanext-artifact.sh "$package_dir/bin/smoke-doctruth-runtime-real-slanext-artifact.sh"
cp scripts/smoke-doctruth-runtime-ocr-worker.sh "$package_dir/bin/smoke-doctruth-runtime-ocr-worker.sh"
cp scripts/smoke-doctruth-runtime-slanext-worker.sh "$package_dir/bin/smoke-doctruth-runtime-slanext-worker.sh"
cat > "$package_dir/bin/doctruth" <<'EOF'
#!/usr/bin/env sh
set -eu
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
jar="${DOCTRUTH_JAR:-${script_dir}/../lib/doctruth-java-all.jar}"
if [ -z "${DOCTRUTH_RUNTIME_COMMAND:-}" ] && [ -x "${script_dir}/doctruth-runtime" ]; then
  export DOCTRUTH_RUNTIME_COMMAND="${script_dir}/doctruth-runtime"
fi
exec "${JAVA:-java}" -jar "$jar" "$@"
EOF
chmod +x "$package_dir/bin/doctruth" \
    "$package_dir/bin/doctruth-runtime" \
    "$package_dir/bin/doctruth-rapidocr-mnn-worker" \
    "$package_dir/bin/doctruth-slanext-table-worker" \
    "$package_dir/bin/doctruth-onnx-model-worker" \
    "$package_dir/bin/smoke-doctruth-real-model-suite.sh" \
    "$package_dir/bin/smoke-doctruth-runtime-real-model-artifacts.sh" \
    "$package_dir/bin/smoke-doctruth-runtime-real-ocr-corpus.sh" \
    "$package_dir/bin/smoke-doctruth-runtime-real-slanext-artifact.sh" \
    "$package_dir/bin/smoke-doctruth-runtime-ocr-worker.sh" \
    "$package_dir/bin/smoke-doctruth-runtime-slanext-worker.sh"

tarball="${dist}/doctruth-${version}.tar.gz"
jar_out="${dist}/doctruth-java-${version}-all.jar"
tar -C "$dist" -czf "$tarball" "doctruth-${version}"
cp "$jar" "$jar_out"

if command -v shasum >/dev/null 2>&1; then
    sha_cmd="shasum -a 256"
elif command -v sha256sum >/dev/null 2>&1; then
    sha_cmd="sha256sum"
else
    echo "missing shasum or sha256sum" >&2
    exit 1
fi

$sha_cmd "$tarball" "$jar_out" > "$dist/checksums.txt"
tar_sha="$($sha_cmd "$tarball" | awk '{print $1}')"

cat > "$dist/homebrew/doctruth.rb" <<EOF
class Doctruth < Formula
  desc "Auditable LLM extraction for Java"
  homepage "https://doctruth.ai"
  url "https://github.com/${repo}/releases/download/v${version}/doctruth-${version}.tar.gz"
  sha256 "${tar_sha}"
  license "Apache-2.0"
  version "${version}"

  depends_on "openjdk@25"

  def install
    libexec.install "lib/doctruth-java-all.jar"
    bin.install "bin/doctruth-runtime"
    bin.install "bin/doctruth-rapidocr-mnn-worker"
    bin.install "bin/doctruth-slanext-table-worker"
    bin.install "bin/doctruth-onnx-model-worker"
    bin.install "bin/doctruth_onnx_worker_lib.py"
    bin.install "bin/smoke-doctruth-real-model-suite.sh"
    bin.install "bin/smoke-doctruth-runtime-real-model-artifacts.sh"
    bin.install "bin/smoke-doctruth-runtime-real-ocr-corpus.sh"
    bin.install "bin/smoke-doctruth-runtime-real-slanext-artifact.sh"
    bin.install "bin/smoke-doctruth-runtime-ocr-worker.sh"
    bin.install "bin/smoke-doctruth-runtime-slanext-worker.sh"
    (bin/"doctruth").write <<~EOS
      #!/bin/sh
      export DOCTRUTH_RUNTIME_COMMAND="\${DOCTRUTH_RUNTIME_COMMAND:-#{bin}/doctruth-runtime}"
      exec "#{Formula["openjdk@25"].opt_bin}/java" -jar "#{libexec}/doctruth-java-all.jar" "\$@"
    EOS
  end

  test do
    assert_match "DocTruth ${version}", shell_output("#{bin}/doctruth version")
  end
end
EOF

echo "Created release artifacts in $dist:"
echo "  $tarball"
echo "  $jar_out"
echo "  $dist/checksums.txt"
echo "  $dist/homebrew/doctruth.rb"
