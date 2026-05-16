#!/usr/bin/env sh
set -eu

prefix="${HOME}/.local"
jar="target/doctruth-java-0.2.0-alpha-all.jar"

usage() {
    cat <<'EOF'
Usage: scripts/install-cli.sh [--prefix DIR] [--jar PATH]

Installs the DocTruth CLI wrapper:
  DIR/bin/doctruth
  DIR/lib/doctruth/doctruth-java-all.jar

Defaults:
  --prefix "$HOME/.local"
  --jar    target/doctruth-java-0.2.0-alpha-all.jar
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

install_dir="${prefix}/lib/doctruth"
bin_dir="${prefix}/bin"
installed_jar="${install_dir}/doctruth-java-all.jar"
launcher="${bin_dir}/doctruth"

mkdir -p "$install_dir" "$bin_dir"
cp "$jar" "$installed_jar"

cat > "$launcher" <<EOF
#!/usr/bin/env sh
exec "\${JAVA:-java}" -jar "$installed_jar" "\$@"
EOF

chmod +x "$launcher"

echo "Installed DocTruth CLI:"
echo "  $launcher"
echo
echo "Try:"
echo "  $launcher --help"
echo
echo "If java is not on PATH, run with:"
echo "  JAVA=/path/to/java $launcher --help"
