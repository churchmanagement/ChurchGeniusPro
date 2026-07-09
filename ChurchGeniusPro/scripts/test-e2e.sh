#!/usr/bin/env bash
# Playwright end-to-end tests against a running app (BASE_URL, default :8080).
set -euo pipefail
cd "$(dirname "$0")/../e2e"
BASE_URL="${BASE_URL:-http://localhost:8080}"
echo "==> Installing Playwright + browsers"
npm ci || npm install
npx playwright install --with-deps chromium
echo "==> Running E2E suite against ${BASE_URL}"
BASE_URL="${BASE_URL}" npx playwright test
