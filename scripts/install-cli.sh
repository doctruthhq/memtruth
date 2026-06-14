#!/usr/bin/env sh
set -eu

prefix="${HOME}/.local"
jar="target/doctruth-java-0.2.0-alpha-all.jar"
runtime=""

usage() {
    cat <<'EOF'
Usage: scripts/install-cli.sh [--prefix DIR] [--jar PATH] [--runtime PATH]

Installs the DocTruth CLI wrapper:
  DIR/bin/doctruth
  DIR/bin/doctruth-runtime
  DIR/bin/doctruth-rapidocr-mnn-worker
  DIR/bin/doctruth-slanext-table-worker
  DIR/bin/doctruth-onnx-model-worker
  DIR/bin/doctruth_onnx_worker_lib.py
  DIR/bin/smoke-doctruth-real-model-suite.sh
  DIR/bin/smoke-doctruth-runtime-real-model-artifacts.sh
  DIR/bin/smoke-doctruth-runtime-real-ocr-corpus.sh
  DIR/bin/smoke-doctruth-runtime-real-slanext-artifact.sh
  DIR/bin/smoke-doctruth-runtime-ocr-worker.sh
  DIR/bin/smoke-doctruth-runtime-slanext-worker.sh
  DIR/lib/doctruth/doctruth-java-all.jar

Defaults:
  --prefix "$HOME/.local"
  --jar    target/doctruth-java-0.2.0-alpha-all.jar
  --runtime target/release/doctruth-runtime or target/debug/doctruth-runtime
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --prefix)
            shift
            [ "$#" -gt 0 ] || {
                echo "missing value for --prefix" >&2
                exit 2
            }
            prefix="$1"
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

install_dir="${prefix}/lib/doctruth"
bin_dir="${prefix}/bin"
installed_jar="${install_dir}/doctruth-java-all.jar"
launcher="${bin_dir}/doctruth"
runtime_bin="${bin_dir}/doctruth-runtime"
ocr_worker="${bin_dir}/doctruth-rapidocr-mnn-worker"
slanext_worker="${bin_dir}/doctruth-slanext-table-worker"
onnx_worker="${bin_dir}/doctruth-onnx-model-worker"
onnx_worker_lib="${bin_dir}/doctruth_onnx_worker_lib.py"
real_model_suite="${bin_dir}/smoke-doctruth-real-model-suite.sh"
runtime_real_model_artifacts="${bin_dir}/smoke-doctruth-runtime-real-model-artifacts.sh"
runtime_real_ocr_corpus="${bin_dir}/smoke-doctruth-runtime-real-ocr-corpus.sh"
runtime_real_slanext_artifact="${bin_dir}/smoke-doctruth-runtime-real-slanext-artifact.sh"
runtime_ocr_worker_smoke="${bin_dir}/smoke-doctruth-runtime-ocr-worker.sh"
runtime_slanext_worker_smoke="${bin_dir}/smoke-doctruth-runtime-slanext-worker.sh"

mkdir -p "$install_dir" "$bin_dir"
cp "$jar" "$installed_jar"
cp "$runtime" "$runtime_bin"
cp scripts/doctruth-rapidocr-mnn-worker "$ocr_worker"
cp scripts/doctruth-slanext-table-worker "$slanext_worker"
cp scripts/doctruth-onnx-model-worker "$onnx_worker"
cp scripts/doctruth_onnx_worker_lib.py "$onnx_worker_lib"
cp scripts/smoke-doctruth-real-model-suite.sh "$real_model_suite"
cp scripts/smoke-doctruth-runtime-real-model-artifacts.sh "$runtime_real_model_artifacts"
cp scripts/smoke-doctruth-runtime-real-ocr-corpus.sh "$runtime_real_ocr_corpus"
cp scripts/smoke-doctruth-runtime-real-slanext-artifact.sh "$runtime_real_slanext_artifact"
cp scripts/smoke-doctruth-runtime-ocr-worker.sh "$runtime_ocr_worker_smoke"
cp scripts/smoke-doctruth-runtime-slanext-worker.sh "$runtime_slanext_worker_smoke"

cat > "$launcher" <<'EOF'
#!/usr/bin/env sh
set -eu
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
jar="${DOCTRUTH_JAR:-__INSTALLED_JAR__}"
if [ -z "${DOCTRUTH_RUNTIME_COMMAND:-}" ] && [ -x "${script_dir}/doctruth-runtime" ]; then
  export DOCTRUTH_RUNTIME_COMMAND="${script_dir}/doctruth-runtime"
fi
exec "${JAVA:-java}" -jar "$jar" "$@"
EOF
sed -i.bak "s#__INSTALLED_JAR__#$installed_jar#g" "$launcher"
rm -f "$launcher.bak"

chmod +x "$launcher" "$runtime_bin" "$ocr_worker" "$slanext_worker" "$onnx_worker" "$real_model_suite" "$runtime_real_model_artifacts" "$runtime_real_ocr_corpus" "$runtime_real_slanext_artifact" "$runtime_ocr_worker_smoke" "$runtime_slanext_worker_smoke"

echo "Installed DocTruth CLI:"
echo "  $launcher"
echo "  $runtime_bin"
echo "  $ocr_worker"
echo "  $slanext_worker"
echo "  $onnx_worker"
echo "  $onnx_worker_lib"
echo "  $real_model_suite"
echo "  $runtime_real_model_artifacts"
echo "  $runtime_real_ocr_corpus"
echo "  $runtime_real_slanext_artifact"
echo "  $runtime_ocr_worker_smoke"
echo "  $runtime_slanext_worker_smoke"
echo
echo "Try:"
echo "  $launcher --help"
echo
echo "If java is not on PATH, run with:"
echo "  JAVA=/path/to/java $launcher --help"
