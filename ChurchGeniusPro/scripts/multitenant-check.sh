#!/usr/bin/env bash
# Multi-tenant readiness check: asserts JPA entities are tenant-scoped
# (clientId/appClientId) above the coverage floor and prints any offenders.
set -euo pipefail
cd "$(dirname "$0")/.."
echo "==> Multi-tenant readiness check"
./mvnw -B -ntp -Dtest=MultiTenantReadinessTest test
