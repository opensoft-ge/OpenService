#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
npm ci --prefix tools/browser-coverage --ignore-scripts --no-audit --no-fund
python3 scripts/test_collect_service_inventory.py
./mvnw -B clean verify
node tools/browser-coverage/check.cjs
