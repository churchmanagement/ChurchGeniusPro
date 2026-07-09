#!/usr/bin/env bash
# Code-quality + dead-code scanning: SpotBugs (bugs, dead stores) and PMD
# (unused code, error-prone patterns, copy-paste detection via CPD).
set -euo pipefail
cd "$(dirname "$0")/.."
echo "==> SpotBugs + PMD static analysis"
./mvnw -B -ntp -Pquality -DskipTests verify
echo "==> Reports: target/spotbugs.html (if generated), target/site/pmd.html, target/site/cpd.html"
