#!/bin/sh
# Produce a self-contained release bundle without access to the operator's secrets.
set -eu
out=${1:?Usage: sh scripts/package-compose.sh OUTPUT_DIRECTORY}
mkdir -p "$out"
out=$(cd "$out" && pwd)
cd "$(dirname "$0")/.."
version=$(sed -n 's/.*image: ghcr.io\/opensoft-ge\/openservice:\([^ ]*\)$/\1/p' compose.yaml)
test -n "$version"
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
name="openservice-compose-$version"
mkdir "$stage/$name"
cp compose.yaml compose.https.yaml compose.probe.yaml nginx-desk.conf LICENSE NOTICE THIRD_PARTY_NOTICES.md "$stage/$name/"
cp docs/self-hosting.md "$stage/$name/README.md"
cp -R licenses "$stage/$name/"
COPYFILE_DISABLE=1 tar -czf "$out/$name.tar.gz" -C "$stage" "$name"
