#!/usr/bin/env bash
# Unused / undeclared dependency detection. `dependency:analyze` reports:
#   - "Used undeclared dependencies": relied on transitively (should be declared)
#   - "Unused declared dependencies": declared but not referenced (candidates to remove)
# Fails the build if any are found when FAIL=1 (default warn-only).
set -euo pipefail
cd "$(dirname "$0")/.."
FAIL_FLAG="${FAIL:-0}"
echo "==> Analyzing dependency hygiene"
if [ "$FAIL_FLAG" = "1" ]; then
  ./mvnw -B -ntp dependency:analyze -DfailOnWarning=true -DignoreNonCompile=true
else
  ./mvnw -B -ntp dependency:analyze -DignoreNonCompile=true || true
fi
