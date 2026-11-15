#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
npm ci --prefix tools/browser-coverage --ignore-scripts --no-audit --no-fund
./mvnw -B clean verify
node tools/browser-coverage/check.cjs
