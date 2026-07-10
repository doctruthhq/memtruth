#!/usr/bin/env sh
set -eu

repo_root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
version="${VERSION:-0.2.0-alpha}"
dist="${DIST_DIR:-$repo_root/dist}"
work="${SMOKE_DIR:-$repo_root/target/cli-release-smoke}"
java_bin="${JAVA:-java}"

contains() {
    case "$1" in
        *"$2"*) return 0 ;;
        *) return 1 ;;
    esac
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
        --dist)
            shift
            [ "$#" -gt 0 ] || {
                echo "missing value for --dist" >&2
                exit 2
            }
            dist="$1"
            ;;
        *)
            echo "unknown option: $1" >&2
            exit 2
            ;;
    esac
    shift
done

tarball="${dist}/doctruth-${version}.tar.gz"

if [ ! -f "$tarball" ]; then
    echo "CLI tarball not found: $tarball" >&2
    echo "Package it first: scripts/package-cli-release.sh --version $version" >&2
    exit 1
fi

rm -rf "$work"
mkdir -p "$work"
tar -xzf "$tarball" -C "$work"

output="$("$java_bin" -jar "$work/doctruth-${version}/lib/doctruth-java-all.jar" version)"
case "$output" in
    "DocTruth ${version}") ;;
    *)
        echo "unexpected jar version output: $output" >&2
        exit 1
        ;;
esac

launcher_output="$(JAVA="$java_bin" "$work/doctruth-${version}/bin/doctruth" version)"
case "$launcher_output" in
    "DocTruth ${version}") ;;
    *)
        echo "unexpected launcher version output: $launcher_output" >&2
        exit 1
        ;;
esac

doctor_output="$(JAVA="$java_bin" "$work/doctruth-${version}/bin/doctruth" doctor)"
contains "$doctor_output" "DocTruth doctor" || {
    echo "unexpected doctor output:" >&2
    echo "$doctor_output" >&2
    exit 1
}
contains "$doctor_output" "java:" || {
    echo "doctor output did not include java readiness:" >&2
    echo "$doctor_output" >&2
    exit 1
}
contains "$doctor_output" "ready:" || {
    echo "doctor output did not include final readiness:" >&2
    echo "$doctor_output" >&2
    exit 1
}

completion_output="$(JAVA="$java_bin" "$work/doctruth-${version}/bin/doctruth" completion bash)"
contains "$completion_output" "_doctruth()" || {
    echo "unexpected completion output:" >&2
    echo "$completion_output" >&2
    exit 1
}
contains "$completion_output" "doctor" || {
    echo "completion output did not include doctor command:" >&2
    echo "$completion_output" >&2
    exit 1
}
contains "$completion_output" "completion" || {
    echo "completion output did not include completion command:" >&2
    echo "$completion_output" >&2
    exit 1
}

echo "CLI release smoke passed for $version"
