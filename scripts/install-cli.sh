#!/usr/bin/env sh
set -eu

prefix="${HOME}/.local"
jar="target/doctruth-java-0.2.0-alpha-all.jar"
runtime=""
mnn_worker=""

usage() {
    cat <<'EOF'
Usage: scripts/install-cli.sh [--prefix DIR] [--jar PATH] [--runtime PATH]

Installs the DocTruth CLI wrapper:
  DIR/bin/doctruth
  DIR/bin/doctruth-runtime
  DIR/bin/doctruth-mnn-model-worker
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

if [ -z "$mnn_worker" ]; then
    runtime_dir="$(dirname "$runtime")"
    if [ -x "${runtime_dir}/doctruth-mnn-model-worker" ]; then
        mnn_worker="${runtime_dir}/doctruth-mnn-model-worker"
    elif [ -x runtime/doctruth-runtime/target/release/doctruth-mnn-model-worker ]; then
        mnn_worker="runtime/doctruth-runtime/target/release/doctruth-mnn-model-worker"
    elif [ -x runtime/doctruth-runtime/target/debug/doctruth-mnn-model-worker ]; then
        mnn_worker="runtime/doctruth-runtime/target/debug/doctruth-mnn-model-worker"
    fi
fi

if [ -z "$mnn_worker" ] || [ ! -x "$mnn_worker" ]; then
    echo "Rust MNN worker not found: $mnn_worker" >&2
    echo "Build it first: cargo build --manifest-path runtime/doctruth-runtime/Cargo.toml --release --bins" >&2
    exit 1
fi

install_dir="${prefix}/lib/doctruth"
bin_dir="${prefix}/bin"
installed_jar="${install_dir}/doctruth-java-all.jar"
launcher="${bin_dir}/doctruth"
runtime_bin="${bin_dir}/doctruth-runtime"
mnn_worker_bin="${bin_dir}/doctruth-mnn-model-worker"

mkdir -p "$install_dir" "$bin_dir"
cp "$jar" "$installed_jar"
cp "$runtime" "$runtime_bin"
cp "$mnn_worker" "$mnn_worker_bin"

cat > "$launcher" <<'EOF'
#!/usr/bin/env sh
set -eu
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
jar="${DOCTRUTH_JAR:-__INSTALLED_JAR__}"
if [ -z "${DOCTRUTH_RUNTIME_COMMAND:-}" ] && [ -x "${script_dir}/doctruth-runtime" ]; then
  export DOCTRUTH_RUNTIME_COMMAND="${script_dir}/doctruth-runtime"
fi
if [ -z "${DOCTRUTH_RUNTIME_MODEL_COMMAND:-}" ] && [ -x "${script_dir}/doctruth-mnn-model-worker" ]; then
  export DOCTRUTH_RUNTIME_MODEL_COMMAND="${script_dir}/doctruth-mnn-model-worker"
fi
if [ -z "${DOCTRUTH_MODEL_COMMAND:-}" ] && [ -n "${DOCTRUTH_RUNTIME_MODEL_COMMAND:-}" ]; then
  export DOCTRUTH_MODEL_COMMAND="${DOCTRUTH_RUNTIME_MODEL_COMMAND}"
fi
exec "${JAVA:-java}" -jar "$jar" "$@"
EOF
sed -i.bak "s#__INSTALLED_JAR__#$installed_jar#g" "$launcher"
rm -f "$launcher.bak"

chmod +x "$launcher" "$runtime_bin" "$mnn_worker_bin"

echo "Installed DocTruth CLI:"
echo "  $launcher"
echo "  $runtime_bin"
echo "  $mnn_worker_bin"
echo
echo "Try:"
echo "  $launcher --help"
echo
echo "If java is not on PATH, run with:"
echo "  JAVA=/path/to/java $launcher --help"
