# ChurchGenius Pro — test & quality framework entry points.
# Every target shells out to scripts/ so the same commands run locally and in any CI.
SHELL := /bin/bash
BASE_URL ?= http://localhost:8080
export BASE_URL

.PHONY: help build unit integration quality deps security e2e load db multitenant gate deploy clean

help:
	@echo "Targets:"
	@echo "  make build         - package the app (no tests)"
	@echo "  make unit          - fast unit + web-slice tests (no Docker)"
	@echo "  make integration   - Testcontainers Postgres tests + coverage gate (Docker)"
	@echo "  make quality       - SpotBugs + PMD (dead code, bugs, CPD)"
	@echo "  make deps          - unused/undeclared dependency analysis"
	@echo "  make security      - OWASP dependency-check + Trivy + ZAP baseline"
	@echo "  make e2e           - Playwright end-to-end tests (app must be running)"
	@echo "  make load          - k6 load test + performance gate (app must be running)"
	@echo "  make db            - database query analysis (Hibernate stats)"
	@echo "  make multitenant   - multi-tenant readiness check"
	@echo "  make gate          - run the full promotion gate (all critical checks)"
	@echo "  make deploy        - deploy (only if PROMOTE_OK=true)"

build:        ; bash scripts/build.sh
unit:         ; bash scripts/test-unit.sh
integration:  ; bash scripts/test-integration.sh
quality:      ; bash scripts/quality-scan.sh
deps:         ; bash scripts/analyze-deps.sh
security:     ; bash scripts/security-scan.sh
e2e:          ; bash scripts/test-e2e.sh
load:         ; bash scripts/load-test.sh
db:           ; bash scripts/db-query-analysis.sh
multitenant:  ; bash scripts/multitenant-check.sh
gate:         ; bash scripts/ci-gate.sh
deploy:       ; bash scripts/deploy.sh

clean:        ; ./mvnw -B -ntp clean
