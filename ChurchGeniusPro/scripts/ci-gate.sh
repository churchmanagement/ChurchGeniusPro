#!/usr/bin/env bash
# Platform-agnostic promotion gate. Runs every CRITICAL check in order and stops
# at the first failure. Used by non-GitHub CIs (Jenkins/GitLab) to reproduce the
# same gate the GitHub Actions pipeline enforces. The app must already be running
# at BASE_URL for the e2e/load/security steps.
set -euo pipefail
cd "$(dirname "$0")/.."
export BASE_URL="${BASE_URL:-http://localhost:8080}"

step() { echo; echo "════════ $1 ════════"; }

step "Build";                bash scripts/build.sh
step "Unit + web-slice";     bash scripts/test-unit.sh
step "Multi-tenant check";   bash scripts/multitenant-check.sh
step "Integration + coverage gate"; bash scripts/test-integration.sh
step "Code quality (SpotBugs/PMD)";  bash scripts/quality-scan.sh
step "Dependency hygiene";   FAIL=0 bash scripts/analyze-deps.sh
step "Security (deps/DAST)"; bash scripts/security-scan.sh
step "E2E (Playwright)";     bash scripts/test-e2e.sh
step "Load / performance gate"; bash scripts/load-test.sh

echo
echo "✅ All critical gates passed — safe to promote."
