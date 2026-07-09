#!/usr/bin/env bash
# Security scanning:
#   1) OWASP dependency-check  — known-vulnerable libraries (fails on CVSS>=7)
#   2) Trivy (if installed)    — filesystem/dependency CVE scan
#   3) OWASP ZAP baseline (if Docker + a running app) — DAST against $BASE_URL
set -euo pipefail
cd "$(dirname "$0")/.."
BASE_URL="${BASE_URL:-http://localhost:8080}"

echo "==> [1/3] OWASP dependency-check (vulnerable libraries)"
# Set NVD_API_KEY in the environment to speed up the data feed download.
./mvnw -B -ntp -Psecurity -DskipTests verify \
  ${NVD_API_KEY:+-DnvdApiKey="$NVD_API_KEY"}

echo "==> [2/3] Trivy filesystem scan"
if command -v trivy >/dev/null 2>&1; then
  trivy fs --scanners vuln,secret,misconfig --severity HIGH,CRITICAL --exit-code 1 .
else
  echo "    trivy not installed — skipping (install: https://aquasecurity.github.io/trivy)"
fi

echo "==> [3/3] OWASP ZAP baseline DAST against ${BASE_URL}"
if command -v docker >/dev/null 2>&1; then
  docker run --rm --network=host -v "$(pwd)/security:/zap/wrk:rw" \
    ghcr.io/zaproxy/zaproxy:stable zap-baseline.py \
    -t "${BASE_URL}" -c zap-rules.tsv -I || ZAP_RC=$?
  echo "    ZAP baseline finished (rc=${ZAP_RC:-0}); report in security/"
else
  echo "    docker not available — skipping ZAP (run against a deployed env instead)"
fi
