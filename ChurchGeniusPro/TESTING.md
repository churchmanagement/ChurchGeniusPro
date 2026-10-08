# Testing, Quality & CI/CD Framework

A runnable foundation covering every requested area. Each suite has a script in
`scripts/` and a `make` target, so the same commands run locally and in any CI
(GitHub Actions is provided; Jenkins/GitLab can call `scripts/ci-gate.sh`).

## Prerequisites

| Tool | Needed for | Notes |
|------|-----------|-------|
| JDK 25 + `./mvnw` | build, unit, integration, quality, security | bundled wrapper |
| Docker | integration tests (Testcontainers), ZAP, k6 (fallback) | required for `*IT` |
| Node 18+ / npm | Playwright E2E | installs browsers on first run |
| k6 (or Docker) | load/performance gate | `scripts/load-test.sh` falls back to Docker |
| Trivy (optional) | extra CVE scan | skipped if absent |

## Test taxonomy

| Category | Where | Run |
|----------|-------|-----|
| **Unit** | `src/test/java/**/*Test.java` — `EncryptionUtilTest`, `NtagSerialTest` | `make unit` |
| **Concurrency** | `EncryptionUtilTest#concurrentRoundTrips...` (32 threads) | `make unit` |
| **Role-based access** | `security/RoleBasedAccessTest` (opt-in denial guard) | `make unit` |
| **Session management** | `web/SessionAndAuthWebTest` (login/401/logout) | `make unit` |
| **Integration (web slice)** | `web/HelpApiSecurityWebTest`, `web/SessionAndAuthWebTest` (`@WebMvcTest`, H2) | `make unit` |
| **Integration (DB, prod-like)** | `integration/HelpAuditLogRepositoryIT` (`@DataJpaTest` + Testcontainers Postgres) | `make integration` |
| **Integration (full boot)** | `integration/ApplicationSmokeIT` (`@SpringBootTest` + Testcontainers; health + auth) | `make integration` |
| **Multi-tenant readiness** | `architecture/MultiTenantReadinessTest` (reflection; coverage floor) | `make multitenant` |
| **E2E** | `e2e/tests/smoke.spec.ts` (Playwright) | `make e2e` |
| **Security (DAST + headers)** | `e2e/tests/security.spec.ts` + ZAP baseline | `make security`, `make e2e` |
| **Load / performance** | `load/smoke.js` (k6, with thresholds) | `make load` |

Unit/web-slice tests are `*Test` (run by Surefire on `test`); Testcontainers
integration tests are `*IT` (run by Failsafe on `verify`) so they never slow the
fast feedback loop.

## Code quality, dead code, dependencies, DB, performance

| Concern | Tool | Run |
|---------|------|-----|
| Code quality / bugs / **dead code** | SpotBugs + PMD (+ CPD duplication) | `make quality` |
| **Unused / undeclared dependencies** | `mvn dependency:analyze` | `make deps` (`FAIL=1` to fail build) |
| **Vulnerable dependencies** | OWASP dependency-check (fails on CVSS ≥ 7) + Trivy | `make security` |
| **DAST** | OWASP ZAP baseline (`security/zap-rules.tsv`) | `make security` / CI |
| **Database query analysis** | Hibernate statistics + SQL logging | `make db` |
| **Performance monitoring** | Spring Boot Actuator + Micrometer/Prometheus (`/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`) | always on |
| **Coverage** | JaCoCo (report + gate in `coverage` profile) | `make integration` |

## The production-promotion gate

Production is promoted **only** when every critical check passes:

1. `build` succeeds
2. unit + web-slice tests pass
3. multi-tenant readiness ≥ floor
4. integration tests (real Postgres) pass **and** JaCoCo coverage ≥ threshold
5. SpotBugs/PMD + dependency hygiene
6. OWASP dependency-check + Trivy (no High/Critical) + ZAP baseline
7. Playwright E2E passes
8. **k6 performance thresholds met** (`p95 < 800ms`, `p99 < 1500ms`, errors < 1%)

- **GitHub Actions** (`.github/workflows/ci.yml`): the `promote` job declares
  `needs: [build-unit, integration, quality, security, e2e-load]` and only runs
  on `push` to `main`. `scripts/deploy.sh` refuses to deploy unless
  `PROMOTE_OK=true`, which CI sets only after the gate.
- **Any other CI**: run `bash scripts/ci-gate.sh` — it executes the same checks
  in order and stops at the first failure.

## Tuning the gates

- Coverage floor starts at **5%** (`pom.xml` → `coverage` profile) so the gate is
  green on day one; raise `<minimum>` as coverage grows.
- Multi-tenant floor is **80%** (`MultiTenantReadinessTest.COVERAGE_FLOOR`); the
  test prints any untenanted entities so legitimately-global tables can be
  reviewed and the floor raised.
- Performance thresholds live in `load/smoke.js` → `options.thresholds`.
- SpotBugs/PMD are report-first (`failOn* = false`); flip to `true` once the
  baseline is clean.

## Reading the build output

Surefire prints the application's own logs while tests run, so **a green build
still contains ERROR lines and stack traces**. 32 test classes deliberately make a
dependency throw in order to prove the failure path behaves; the log entry that
follows is the code under test doing its job.

Judge a run by the Surefire summary, never by the presence of a trace:

```
[INFO] Tests run: 1547, Failures: 0, Errors: 0, Skipped: 1
[INFO] BUILD SUCCESS
```

A real failure names the test and the assertion — e.g. `PublicSurfaceFollowupTest.callerCodeIsIgnored`,
`Expecting actual "6-0513" to match pattern "[A-Z]-\d{4}"` — and is written to
`target/surefire-reports/<class>.txt`.

Two tells that a trace was injected rather than hit for real:

- the exception message is a test's stub (`RuntimeException: db down`,
  `IllegalArgumentException: Church name is required.`);
- a `*Test` frame sits directly beneath the production frames, so the throw came
  from a mock inside the test rather than from a live dependency:

```
at com.churchgeniuspro.service.TrialRegistrationService.register(TrialRegistrationService.java:75)
at com.churchgeniuspro.controller.TrialRegistrationController.register(TrialRegistrationController.java:151)
at com.churchgeniuspro.trial.TrialLinkAtomicClaimTest.failureReleasesTheLink(TrialLinkAtomicClaimTest.java:173)
```

That example is `TrialLinkAtomicClaimTest#failureReleasesTheLink`: it stubs
provisioning to fail with `db down`, and the INFO line printed just above the trace —
`Trial registration link released after a failed registration` — is exactly what the
test asserts. If that trace ever disappeared, it would mean the controller had
stopped logging failed registrations.

Don't quiet these logs globally to clean up the output: the same logger
configuration is what makes a real production failure legible.

## Notes / constraints

- The React frontend is shipped as **pre-built bundles** in
  `src/main/resources/static/` (no frontend build in this repo), so JS is
  validated via E2E rather than component unit tests. If frontend source is added
  later, wire Vitest/Jest into `scripts/test-unit.sh` and the CI.
- Testcontainers tests require Docker. On machines without Docker, run
  `make unit` (fast, no Docker) for the inner loop.
- `application-test.properties` (H2, PostgreSQL mode) backs fast context tests;
  `application-it.properties` backs the Testcontainers integration profile.
- The active profile is never hard-coded: Azure sets `SPRING_PROFILES_ACTIVE=prod`;
  run the app locally with `restart-app.bat` or
  `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` (or `trial`).
