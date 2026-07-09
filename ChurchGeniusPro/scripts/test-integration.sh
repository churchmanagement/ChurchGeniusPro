#!/usr/bin/env bash
# Integration tests against a real PostgreSQL via Testcontainers (requires Docker),
# plus the JaCoCo coverage gate. Failsafe runs *IT classes on `verify`.
set -euo pipefail
cd "$(dirname "$0")/.."
echo "==> Running integration tests (Testcontainers Postgres) + coverage gate"
./mvnw -B -ntp -Pcoverage verify
echo "==> Coverage report: target/site/jacoco/index.html"
