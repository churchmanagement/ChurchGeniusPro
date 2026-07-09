# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> Last refreshed: **2026-07-08**. Not a git repo — the "Current State" section below is the change record; update it when finishing a work session.

## Build & Run

```bash
# Build and package (skipping tests)
./mvnw clean package -DskipTests

# Run the application (or use restart-app.bat on Windows: kills :8080 then runs)
./mvnw spring-boot:run

# Run unit tests (*Test, Surefire)
./mvnw test

# Run a single test class
./mvnw test -Dtest=EncryptionUtilTest

# Unit + integration tests (*IT, Failsafe, needs Docker/Testcontainers)
./mvnw verify
```

Make targets (each shells to `scripts/*.sh`): `build`, `unit`, `integration`, `quality` (SpotBugs+PMD), `deps`, `security` (OWASP+Trivy+ZAP), `e2e` (Playwright in `e2e/`), `load` (k6 in `load/`), `db`, `multitenant`, `gate` (full CI promotion gate), `deploy`. See `TESTING.md`. Prereqs: JDK 25, Docker, Node 18+, k6.

App runs on port **8080**; `/` forwards to `/login.html`. `BASE_URL` for e2e/load defaults to `http://localhost:8080`.

## Stack

- Spring Boot **4.0.6**, Java **25**, Maven wrapper (`com.churchgeniuspro:ChurchGeniusPro:0.0.1-SNAPSHOT`)
- Starters: web, data-jpa, mail, actuator, security-crypto, session-jdbc. **No spring-boot-starter-security** — auth is custom servlet filters.
- PostgreSQL (runtime), H2 + Testcontainers (tests), Lombok
- Twilio (SMS), web-push + jose4j + BouncyCastle (VAPID push), Apache POI (.doc/.docx song uploads), PDFBox, Micrometer/Prometheus
- **No Plaid SDK** — Plaid API called via hand-rolled `plaid/service/PlaidClient`. **No Stripe SDK** — per-tenant `StripeSettings` entity only.
- OpenAI via REST (`openai.api.key`): gpt-4o vision OCR (checks/bank statements), whisper-1 + gpt-4o-mini voice commands.

## Architecture

Single Spring Boot app serving the REST API and a **plain hand-written HTML + vanilla JS** frontend (NOT React — a leftover `static/assets/index-*.js|css` bundle exists but is unused). No frontend build step; edit the HTML/JS in `src/main/resources/static/` directly (122 HTML pages; shared JS: `shell.js` nav shell, `ai-assistant.js`, per-page voice helpers). The ~25 `.html` files in the project **root are empty 3-byte stubs** (BOM only) — ignore them; real pages live in `static/`.

`package.json` at root is only for `generate_manual.js` (pdfkit user-manual generator), not a frontend build.

### Package layout (`com.churchgeniuspro`)

- `controller/` — 93 REST controllers (members/family/groups, income/expense/donation/pledge/funds, events + check-in + volunteers, attendance, kids ministry + pickup, reminders, certificates, songbook, worship planning, notifications/push/SMS/WhatsApp, AI search/voice, auth/session/signup, service-admin, Stripe settings, `GlobalExceptionHandler`)
- `service/` — 71 services (Email/Sms/WhatsApp/WebPush, reminder schedulers, AI/vision OCR, ETL import suite, TemporaryAccess, Backup, SchemaFix)
- `repository/` — 140 Spring Data JPA repositories (no DAO layer)
- **`hibernate/` — the JPA entity layer** (144 `@Entity`, e.g. `AppUser`, `Family`, `Income`, `ChurchEvent`, `AttendanceRecord`, `StripeSettings`). New entities go here.
- `model/` — 15 plain `*BO` POJOs (request/response business objects, NOT entities)
- `webfilter/` — 9 servlet filters (see Auth)
- `payroll/` — self-contained W-2 payroll module (~68 classes: controller/dto/service/engine/entity/repository/pdf); own `payroll/README.md`; gated by `RoleGuard.requirePayroll`
- `plaid/` — bank sync module (~34 classes: link, webhooks, sync, review queue, token cipher, audit, email-verification gate)
- `bankimport/` — manual CSV/bank-statement import (separate from Plaid)
- `util/` — `EncryptionUtil`, `PasswordUtil` (BCrypt), `RoleGuard`, `SessionUtil`, ETL helpers
- `config/` — `WebConfig`, `SessionConfig` (JDBC HTTP session), `DatabaseIndexInitializer`
- Root: `ChurchGeniusProApplication`, `DataSeeder`

### Authentication & filters

No Spring Security. `webfilter/FilterConfig` registers filters in order:
`MultipartErrorFilter`(-2) → `CspFilter`(-1) → `NoAutoLogoutFilter`(0, `/api/*`) → **`AuthFilter`(1, `/api/*`)** → `LoginFilter`(2, debug logging) → `TempAccessFilter`(3) → `PrivatePageFilter`(4, network-based page gating + bypass token) → `NtagAccessFilter`(5, NFC-tag page whitelist).

`AuthFilter` IS registered and does real session validation (401 without valid session; whitelists `/login`, `/api/session`, `/api/signup/**`, etc.; re-checks `app_user.enabled`/`delete_flag` per request). Sessions are server-side HTTP sessions persisted to Postgres (spring-session-jdbc, 30m timeout). Controllers additionally enforce roles via `util/RoleGuard`. Multi-tenant scoping via `clientId` (`app_client_id`).

### Database & config

- Hibernate `ddl-auto=update` — schema evolves automatically; `migrate_production.sql` (idempotent) is the manual companion run against prod **before deploying** a new JAR for changes Hibernate won't apply.
- Profiles (`spring.profiles.active=prod` in base file):
  - `local` — `jdbc:postgresql://localhost:5432/churchgeniuspro`, user `postgres` (password committed)
  - `prod` — env-injected `DB_URL_PROD`/`DB_USER_PROD`/`DB_PASSWORD_PROD`, secure cookies
  - `trial` — local `churchgeniustrial` DB
- `scheduler.properties` — 9 cron jobs (birthday/anniversary/celebrant/holiday/weekly-meeting reminders)
- Env-injected keys: `OPENAI_API_KEY`, Twilio, `plaid.*` (defaults to sandbox), `PUSH_VAPID_PRIVATE_KEY`
- `seed_test_data.sql` — idempotent test data for one hardcoded tenant; `sample-imports/` — CSVs for the ETL import feature

## Tests

`src/test/java`: 10 classes — unit (`*Test`: encryption, role-based access, `@WebMvcTest`+H2 web tests, multi-tenant architecture test, `PlaidTokenCipherTest`) and integration (`*IT`: `ApplicationSmokeIT`, `HelpAuditLogRepositoryIT` — Testcontainers Postgres). E2E: Playwright in `e2e/` (app must be running). Load: k6 in `load/`. Coverage/quality/security via Maven profiles `coverage`, `quality`, `security`.

## Key Conventions

- Package root: `com.churchgeniuspro`; entities in `hibernate/`, repos in `repository/`, business objects in `model/`
- REST controllers use `@RestController`; page-serving redirects use `@Controller`
- Auth = servlet filters + `RoleGuard`, never Spring Security annotations
- Every tenant-scoped query must filter by `clientId` (checked by `MultiTenantReadinessTest`)
- Schema changes: rely on ddl-auto for dev, mirror in `migrate_production.sql` for prod

## Feature docs (root)

`ATTENDANCE.md` (attendance module, Phase 1), `PAYROLL.md` (+ `payroll/README.md`), `PLAID_BANK_SYNC_DESIGN.md` (**header says "no implementation code yet" — stale; Plaid is fully implemented**), `TEMPORARY_ACCESS.md` (time-limited badge/code access via `TempAccessFilter`), `TESTING.md` (test/CI gate framework). Plaid review-queue flow: staging txns → approve → `income`/`expense`.

## Current State (as of 2026-07-08)

**Newest (2026-07-08): Public marketing website (/web/*)** — six anonymous, responsive marketing pages in `static/web/` (home, features, pricing, help, support, info) sharing `static/web/site.css`; served via `controller/PublicWebController` (`GET /web/{page}` records a `hibernate/PublicPageVisit` row then forwards to the static file). Public contact form `POST /api/web/contact` (whitelisted via new `"/api/web"` entry in `AuthFilter.PUBLIC_PREFIXES`) emails info@churchgeniuspro.com with a honeypot spam guard. Visitor stats: `GET /api/serviceadmin/public-page-stats` (guarded by the `serviceAdminId` session attribute, same pattern as TestDataController) feeding a "🌐 Public Website Visitors" card on `serviceadminhome.html`. DDL in `migrate_production.sql` (`public_page_visit` table). NOTE: there is no self-serve signup flow (church onboarding is invitation-based), so all "Sign Up for Free" buttons lead to `/web/support?topic=signup` which prefills the contact form as lead capture.

**Latest (2026-07-08): Event Invitations ("Send Invite")** — the reminder contact list doubles as an invitation list. `EventRegistrationReminderService.sendInvitations` sends "You're Invited" email (church-name header, RSVP Now button) and/or SMS to each valid contact; per-recipient dedupe via `event_registration_reminder_log.message_type` (`INVITE` vs `REMINDER` — new column, mirrored in `migrate_production.sql`) so invites and reminders never block each other; re-sending only reaches new contacts. `POST /api/events/{id}/registration-reminder/invite`; 📨 Send Invite button in the events.html modal (enabled only with ≥1 valid contact, save-then-send, sent/skipped summary, Type column in attempt log). Earlier same day: RSVP wording pass on reminder email/SMS, manual `…/run?force=true` endpoint + 🚀 Send Now button, scheduler observability logging, `spring.task.scheduling.pool.size=4`, `/bankSync` shell.css + base-style fix. Tests: 25 in `EventRegistrationReminderServiceTest`.

**Just completed (2026-07-08): Event Registration Reminder feature** — per-event optional list of email/phone contacts who get a "please register" email/SMS N days before the event, only if not already registered for that event (phones compared normalized to 10 digits). New: `hibernate/EventRegistrationReminderContact`, `EventRegistrationReminderLog` (+ repos), `service/EventRegistrationReminderService` (own `@Scheduled` hourly cron `scheduler.job.registration-reminders`; once-per-schedule gate via `reminder_sent_log` type `EVENT_REG_REMINDER`; per-recipient dedupe via SENT log rows; all attempts logged with reason), `controller/EventRegistrationReminderController` (`GET/PUT /api/events/{id}/registration-reminder`, `GET …/logs`), 2 new columns on `ChurchEvent` (`registration_reminder_enabled/days`), modal UI in `static/events.html` (🔔 Reg Reminder button per card, incl. collapsible attempt-log table), DDL mirrored in `migrate_production.sql`. Unit tests: `service/EventRegistrationReminderServiceTest` (17 tests — normalization, send rules incl. the spec's worked example, dedupe, gating; pure Mockito, no Spring context).

**Prior in-flight work: Plaid "Bank Sync" verification gate** — email verification + trusted-device flow gating access to bank sync. Last-touched files (2026-07-06): `plaid/entity/BankSyncVerification`, `BankSyncTrustedDevice` (+ repositories), `plaid/service/BankSyncGateService`, `plaid/controller/BankSyncGateController`, `PlaidPageController`, `plaid/util/PlaidGuard`, `static/bankSyncVerify.html`, `static/plaidReview.html`, `static/viewusers.html`. Also recently touched: `scheduler.properties`, `ReminderSchedulerService`, `EventRegistrationRepository`, `static/events.html`.

Known cleanup items / gotchas:

- **Committed secrets**: mail password in `application.properties`, OpenAI key in `application-local.properties` — should move to env vars
- `DecryptTest.java` (project root) — throwaway debug main with hardcoded AES/ECB key `"ChurchGenius2024"`; not part of the build; delete or secure
- Empty root `.html` stubs (25 files, BOM-only) and stray `logsapp_start.log` — safe to delete
- `service/TestDataService.java.bak` — stray backup file
- `fix_worship_song.sql` — applied one-off (drop NOT NULL on `worship_song.service_date`)
- Only ~6 TODO/FIXME markers in Java sources
