#!/usr/bin/env bash
# Build the application package (no tests). Platform-agnostic — any CI can call it.
set -euo pipefail
cd "$(dirname "$0")/.."
echo "==> Building ChurchGenius Pro"
./mvnw -B -ntp clean package -DskipTests
echo "==> Artifact:"; ls -1 target/*.jar
