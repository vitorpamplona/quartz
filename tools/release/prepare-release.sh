#!/usr/bin/env bash
# Writes a release's version into the files the release commit carries: VERSION_NAME in
# gradle.properties, and the url and checksum of QuartzKit.xcframework.zip in Package.swift.
#
#   tools/release/prepare-release.sh <version> <checksum>
#
# <checksum> is what `swift package compute-checksum QuartzKit.xcframework.zip` prints.
# .github/workflows/release.yml runs this; it is safe to run locally to see the diff.
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "usage: $0 <version> <checksum>" >&2
    exit 2
fi

version="$1"
checksum="$2"

if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "version must be X.Y.Z, got '$version'" >&2
    exit 1
fi
if [[ ! "$checksum" =~ ^[0-9a-f]{64}$ ]]; then
    echo "checksum must be 64 lower-case hex characters, got '$checksum'" >&2
    exit 1
fi

root="$(cd "$(dirname "$0")/../.." && pwd)"
url="https://github.com/vitorpamplona/quartz/releases/download/v${version}/QuartzKit.xcframework.zip"

# Rewrites the file through a temp copy: `sed -i` takes different arguments on macOS and Linux.
replace() {
    local file="$1" expression="$2"
    sed -E "$expression" "$file" > "$file.tmp"
    mv "$file.tmp" "$file"
}

replace "$root/gradle.properties" "s#^VERSION_NAME=.*#VERSION_NAME=${version}#"
replace "$root/Package.swift" "s#(url: \")[^\"]*(\")#\1${url}\2#; s#(checksum: \")[^\"]*(\")#\1${checksum}\2#"

# Fail loudly if a pattern stopped matching after someone reformatted a file.
grep -qx "VERSION_NAME=${version}" "$root/gradle.properties" ||
    { echo "gradle.properties has no VERSION_NAME line to update" >&2; exit 1; }
grep -qF "url: \"${url}\"" "$root/Package.swift" ||
    { echo "Package.swift has no url to update" >&2; exit 1; }
grep -qF "checksum: \"${checksum}\"" "$root/Package.swift" ||
    { echo "Package.swift has no checksum to update" >&2; exit 1; }

echo "Prepared v${version}: ${url} (${checksum})"
