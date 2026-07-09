#!/usr/bin/env bash
# Fast unit tests (no Docker): pure logic, RBAC, concurrency, web-slices, multi-tenant.
# Surefire runs *Test classes; *IT (Testcontainers) are excluded here.
set -euo pipefail
cd "$(dirname "$0")/.."
echo "==> Running unit + web-slice tests (H2, no Docker)"
./mvnw -B -ntp test
