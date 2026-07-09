#!/usr/bin/env bash
# Load / performance test (k6). Exits non-zero if any latency/error threshold in
# load/smoke.js is breached — this is the performance promotion gate.
set -euo pipefail
cd "$(dirname "$0")/.."
BASE_URL="${BASE_URL:-http://localhost:8080}"
echo "==> k6 load test against ${BASE_URL}"
if command -v k6 >/dev/null 2>&1; then
  k6 run -e BASE_URL="${BASE_URL}" --summary-export=load/summary.json load/smoke.js
elif command -v docker >/dev/null 2>&1; then
  docker run --rm --network=host -e BASE_URL="${BASE_URL}" -v "$(pwd)/load:/load" \
    grafana/k6 run -e BASE_URL="${BASE_URL}" /load/smoke.js
else
  echo "ERROR: neither k6 nor docker is available to run the load test." >&2
  exit 2
fi
