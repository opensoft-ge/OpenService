#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
npm ci --prefix tools/browser-coverage --ignore-scripts --no-audit --no-fund
python3 scripts/test_collect_service_inventory.py
python3 scripts/test_enterprise_transport.py
python3 -m unittest discover -s scripts -p 'test_permit_robot.py'
python3 scripts/test_service_probe.py
python3 scripts/test_industry_operations.py
./mvnw -B clean verify
node tools/browser-coverage/check.cjs
