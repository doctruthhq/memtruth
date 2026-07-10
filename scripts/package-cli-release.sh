#!/usr/bin/env sh
set -eu

repo_root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
java_root="$repo_root/java"
version="${VERSION:-}"
repo="${GITHUB_REPOSITORY:-doctruthhq/memtruth}"
jar="${JAR:-}"
dist="${DIST_DIR:-$repo_root/dist}"

usage() {
    cat <<'EOF'
Usage: scripts/package-cli-release.sh [--version VERSION] [--jar PATH] [--dist DIR]

Creates release-ready CLI artifacts:
  dist/doctruth-VERSION.tar.gz
  dist/doctruth-java-VERSION-all.jar
  dist/checksums.txt
  dist/homebrew/doctruth.rb

The tarball contains:
  bin/doctruth
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
    version="$(awk -F'[<>]' '/<version>/ { print $3; exit }' "$java_root/pom.xml")"
fi

if [ -z "$jar" ]; then
    jar="$java_root/target/doctruth-java-${version}-all.jar"
fi

if [ ! -f "$jar" ]; then
    echo "CLI jar not found: $jar" >&2
    echo "Build it first: mvn -f java/pom.xml package -DskipTests" >&2
    exit 1
fi

mkdir -p "$dist/homebrew"

package_dir="${dist}/doctruth-${version}"
rm -rf "$package_dir"
mkdir -p "$package_dir/bin" "$package_dir/lib"

cp "$jar" "$package_dir/lib/doctruth-java-all.jar"
cat > "$package_dir/bin/doctruth" <<'EOF'
#!/usr/bin/env sh
set -eu
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
jar="${DOCTRUTH_JAR:-${script_dir}/../lib/doctruth-java-all.jar}"
exec "${JAVA:-java}" -jar "$jar" "$@"
EOF
chmod +x "$package_dir/bin/doctruth"

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
    (bin/"doctruth").write <<~EOS
      #!/bin/sh
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
