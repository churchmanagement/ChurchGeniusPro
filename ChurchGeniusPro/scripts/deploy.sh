#!/usr/bin/env bash
# Production deployment — REFUSES to run unless the promotion gate passed.
# CI sets PROMOTE_OK=true only after every critical gate (build, unit,
# integration, quality, security, e2e, load) has succeeded. Override locally
# at your own risk with PROMOTE_OK=true.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ "${PROMOTE_OK:-false}" != "true" ]; then
  echo "REFUSING TO DEPLOY: promotion gate not satisfied (PROMOTE_OK != true)." >&2
  echo "All critical tests and performance thresholds must pass first." >&2
  exit 3
fi

ENV_NAME="${DEPLOY_ENV:-production}"
ARTIFACT="$(ls -1 target/*.jar | head -n1)"
echo "==> Promotion gate satisfied. Deploying ${ARTIFACT} to ${ENV_NAME}"

# ── Plug in your real deployment here (e.g. scp/systemd, Docker push, k8s apply). ──
# Examples (left as no-ops so this script is safe by default):
#   docker build -t registry/churchgeniuspro:"$GIT_SHA" . && docker push registry/churchgeniuspro:"$GIT_SHA"
#   kubectl set image deployment/cgp app=registry/churchgeniuspro:"$GIT_SHA"
echo "    (no deployment target configured — wire your infra into scripts/deploy.sh)"
echo "==> Deploy step complete."
