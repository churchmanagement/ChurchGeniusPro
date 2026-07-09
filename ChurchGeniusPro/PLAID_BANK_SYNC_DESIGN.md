# Plaid Bank Sync — Design & Implementation Plan

**Status:** Draft for review (no implementation code yet)
**Owner:** Engineering
**Applies to:** ChurchGeniusPro (Spring Boot + PostgreSQL, static web frontend)
**Related policies:** Information Security Policy, Access Controls Policy, Data Deletion & Retention Policy

---

## 1. Goals & Non-Goals

**Goals**
- Let authorized church users connect one or more bank accounts through Plaid and continuously import transactions.
- Route every imported transaction through a **mandatory review queue** (view / edit / approve / reject, individually and in bulk) before it is written to the ledger.
- Support both **income** and **expense** transactions, mapping approved items into the existing `income` and `expense` tables.
- Give the Service Admin per-church control (enable/disable Plaid, enable/disable sync, view connected accounts, force-disconnect).
- Handle Plaid **webhooks** securely for real-time updates.
- Enforce security requirements: encrypted tokens at rest, TLS 1.2+, no secrets in the frontend or logs, RBAC, and full audit logging.

**Non-Goals (this phase)**
- Native iOS/Android app code. Per decision, mobile is served by the **shared REST API + responsive/mobile web** (Plaid Link works in mobile browsers and webviews). Native apps, if built later, consume the same endpoints.
- Payment initiation / money movement. This feature is **read-only** transaction ingestion.
- Auto-categorization ML. Category mapping is rule-assisted but human-approved.

---

## 2. Platform Strategy (Shared API + Mobile Web)

A single backend API serves all clients. The Plaid Link handshake is the only client-specific piece:

- **Desktop web (admin portal):** Plaid Link JS (`link-initialize.js`) opens in a modal; returns a `public_token`.
- **Mobile web / webview:** Same Plaid Link JS flow (Link supports mobile browsers). A native wrapper, if added later, uses Plaid's OAuth redirect + the same `/api/plaid/link-token` and `/api/plaid/exchange` endpoints.
- All transaction review, approval, and admin screens are responsive HTML reusing the existing app styles, so they work on phones without separate code.

No server logic branches on platform; only the Link initialization differs.

---

## 3. High-Level Architecture

```
Browser / Mobile Web                Spring Boot (ChurchGeniusPro)              Plaid
────────────────────                ──────────────────────────────           ─────
Plaid Link JS  ──public_token──►  PlaidLinkController
                                     └─ PlaidClient.exchange ───────────────►  /item/public_token/exchange
                                        (store access_token ENCRYPTED)
                                  PlaidSyncService
                                     └─ PlaidClient.transactionsSync ───────►  /transactions/sync (cursor)
                                        └─ writes → plaid_transaction_staging
Review Queue UI ◄──REST──────────  ReviewQueueController
   approve ─────────────────────►     └─ IncomeService / ExpenseService (existing) → income / expense
Service Admin UI ◄──REST─────────  ServiceAdminController (+ Plaid endpoints)
                                  PlaidWebhookController  ◄──webhook POST──────  Plaid events
                                     └─ verify JWT, enqueue sync
```

Key principle: **Plaid data never lands directly in `income`/`expense`.** It lands in `plaid_transaction_staging` and is promoted only on human approval, reusing the existing `IncomeService.createIncome(...)` / `ExpenseService` paths so all existing validation, pledge auto-credit, and audit fields apply.

---

## 4. Data Model

New tables (Hibernate entities in `com.churchgeniuspro.hibernate`, created automatically by `ddl-auto=update`). All money uses `NUMERIC(15,2)`; all tenant rows carry `client_id` for isolation, consistent with existing entities.

### 4.1 `church_plaid_setting` — per-church controls (mirrors OpenAI/Voice settings pattern)
| Column | Type | Notes |
|---|---|---|
| id | serial PK | |
| client_id | varchar(100) | church tenant (FK-by-value to `service_client.client_id`) |
| plaid_enabled | boolean | Service-Admin master switch for the church |
| sync_enabled | boolean | Service-Admin switch for automatic sync |
| updated_by / updated_date | varchar / timestamp | audit |

Effective access = `plaid_enabled` AND church subscription active. Sync runs only when `sync_enabled`.

### 4.2 `plaid_item` — one row per connected institution login (Plaid "Item")
| Column | Type | Notes |
|---|---|---|
| id | serial PK | |
| client_id | varchar(100) | tenant |
| created_by_user_id | integer | `app_user.id` who linked it |
| item_id | varchar(200) | Plaid item_id (unique) |
| access_token_enc | text | **AES-256-GCM encrypted** Plaid access_token |
| institution_id | varchar(100) | |
| institution_name | varchar(200) | display |
| status | varchar(30) | `ACTIVE`, `LOGIN_REQUIRED`, `ERROR`, `DEGRADED`, `DISCONNECTED` |
| error_code | varchar(100) | last Plaid error code (e.g. `ITEM_LOGIN_REQUIRED`) |
| sync_cursor | text | `/transactions/sync` cursor for incremental pulls |
| last_synced_date | timestamp | |
| delete_flag | boolean | soft delete on disconnect |
| created_date | timestamp | |

> The raw access token is **never** stored in plaintext, never logged, and never returned to any client.

### 4.3 `plaid_account` — accounts under an item
| Column | Type | Notes |
|---|---|---|
| id | serial PK | |
| client_id | varchar(100) | tenant |
| plaid_item_id | integer | FK → `plaid_item.id` |
| account_id | varchar(200) | Plaid account_id (unique) |
| name / official_name | varchar | |
| mask | varchar(10) | last 4 |
| type / subtype | varchar(50) | e.g. depository/checking |
| current_balance / available_balance | numeric(15,2) | latest snapshot (display only) |
| active | boolean | |

### 4.4 `plaid_transaction_staging` — the review queue (core)
| Column | Type | Notes |
|---|---|---|
| id | serial PK | |
| client_id | varchar(100) | tenant |
| plaid_item_id | integer | FK |
| plaid_account_id | integer | FK |
| plaid_transaction_id | varchar(200) | Plaid transaction_id (unique) — idempotency key |
| direction | varchar(10) | `INCOME` or `EXPENSE` (derived from amount sign; user-editable) |
| amount | numeric(15,2) | absolute value |
| txn_date | date | Plaid `date` (or `authorized_date`) |
| name / merchant_name | varchar | raw description |
| description | text | user-editable |
| plaid_category | varchar(200) | Plaid personal-finance category (raw) |
| mapped_sub_source_id | integer | fund for income (nullable until set) |
| mapped_purpose_id | integer | category for expense (nullable) |
| mapped_main_source_id | integer | main fund for expense |
| mapped_transaction_type_id | integer | payment method |
| status | varchar(15) | `PENDING`, `APPROVED`, `REJECTED` (default `PENDING`) |
| promoted_income_id / promoted_expense_id | integer | set when approved → ledger row |
| pending | boolean | Plaid pending flag (holds); excluded from approval until posted |
| reviewed_by / reviewed_date | varchar / timestamp | audit |
| created_date | timestamp | |

Uniqueness on `plaid_transaction_id` makes re-delivery/webhook replays idempotent (upsert on modify, soft-remove on Plaid `removed`).

### 4.5 `plaid_webhook_event` — webhook audit + idempotency
| Column | Type | Notes |
|---|---|---|
| id | serial PK | |
| item_id | varchar(200) | |
| webhook_type / webhook_code | varchar(50) | |
| payload | text | raw JSON (secrets stripped) |
| signature_valid | boolean | JWT verification result |
| processed | boolean | |
| received_date | timestamp | |

### 4.6 `plaid_audit_log` — full audit of financial actions (mirrors `PayrollAuditLog`)
| Column | Type | Notes |
|---|---|---|
| id | serial PK | |
| client_id | varchar(100) | |
| actor | varchar(150) | username or `SYSTEM`/`WEBHOOK` |
| action | varchar(60) | `LINK_CREATED`, `ITEM_LINKED`, `SYNC_RUN`, `TXN_APPROVED`, `TXN_REJECTED`, `TXN_EDITED`, `BULK_APPROVED`, `ITEM_DISCONNECTED`, `PLAID_ENABLED`, `SYNC_TOGGLED`, `WEBHOOK_RECEIVED` |
| target_ref | varchar(200) | item/account/txn id |
| detail | text | before/after summary (no secrets) |
| created_date | timestamp | |

### 4.7 Email-verification gate
`app_user` has no persistent verified flag today, but a `VerificationStore` + `VerificationCode` OTP mechanism exists. Proposed: add **`app_user.email_verified boolean default false`** (added automatically by `ddl-auto=update`) and set it `true` when a user completes the existing OTP/verification flow. Bank connect requires `email_verified = true`. (Alternative with zero schema change: require a fresh OTP verification immediately before the first Link session and cache the result in-session — documented as a fallback.)

---

## 5. Backend Components

New package: `com.churchgeniuspro.plaid` (with `entity`, `repository`, `service`, `controller`, `dto` sub-packages) to keep the feature self-contained, following the existing `payroll` package precedent.

| Component | Responsibility |
|---|---|
| `PlaidProperties` | Binds `plaid.*` config (env-backed): client id, secret, env/base URL, webhook URL, product/country. |
| `PlaidClient` | Thin REST wrapper over the Plaid API using Spring `RestClient`. Adds `client_id`/`secret` to each JSON body. Methods: `createLinkToken`, `exchangePublicToken`, `transactionsSync`, `accountsGet`, `itemRemove`, `webhookVerificationKeyGet`. Never logs request/response bodies containing tokens. |
| `PlaidTokenCipher` | AES-256-GCM encrypt/decrypt for access tokens; key from `PLAID_TOKEN_ENC_KEY` env (32-byte, base64). Uses `spring-security-crypto` (already a dependency) or JCA `AES/GCM/NoPadding` with random IV prepended. **Not** the legacy `EncryptionUtil` (AES-ECB, hardcoded key). |
| `PlaidLinkService` | Creates link tokens (scoped to church + user), exchanges public tokens, persists `plaid_item` + `plaid_account`, enforces the enable/email/role gates. |
| `PlaidSyncService` | Runs `/transactions/sync` per item using the stored cursor; upserts into `plaid_transaction_staging`; derives income/expense direction; applies category-mapping rules; updates cursor + status. Idempotent. |
| `PlaidReviewService` | List/filter queue, edit a staged txn, approve (→ `IncomeService.createIncome` or `ExpenseService`), reject, and bulk approve/reject. Writes `plaid_audit_log`. |
| `ServiceAdminPlaidService` | Per-church enable/disable Plaid & sync, list items/accounts, force-disconnect (calls `/item/remove`, soft-deletes rows, revokes cursor). |
| `PlaidWebhookService` | Verifies webhook JWT, records `plaid_webhook_event`, and dispatches to sync/status handlers. |
| `PlaidGuard` (util) | Central check: church `plaid_enabled`, user role ∈ {Admin, Accountant, authorized financial privilege}, and `email_verified`. |

### 5.1 Controllers & endpoints

**Member/admin app (session-authenticated, tenant-scoped, `/api/plaid/*` behind existing `AuthFilter`):**

| Method & path | Purpose | Guard |
|---|---|---|
| `POST /api/plaid/link-token` | Create a Link token for the current user/church | Admin/financial + email verified + plaid enabled |
| `POST /api/plaid/exchange` | Exchange `public_token`; store item/accounts | same |
| `GET  /api/plaid/items` | List this church's connected items/accounts | Admin/financial |
| `POST /api/plaid/items/{id}/sync` | Manual "sync now" | Admin/financial + sync enabled |
| `DELETE /api/plaid/items/{id}` | User-initiated disconnect | Admin |
| `GET  /api/plaid/review` | List staged transactions (filter by status/account/direction/date) | Admin/financial |
| `PUT  /api/plaid/review/{id}` | Edit amount/category/description/direction/fund | Admin/financial |
| `POST /api/plaid/review/{id}/approve` | Approve one → ledger | Admin/financial |
| `POST /api/plaid/review/{id}/reject` | Reject one | Admin/financial |
| `POST /api/plaid/review/bulk` | Bulk approve/reject `{ ids:[], action }` | Admin/financial |

**Service Admin (session role `ServiceAdmin`, `/api/serviceadmin/*`, mirrors existing OpenAI/Voice controls):**

| Method & path | Purpose |
|---|---|
| `GET  /api/serviceadmin/clients/{id}/plaid` | Get church Plaid settings + connected items/accounts |
| `PUT  /api/serviceadmin/clients/{id}/plaid` | Set `plaidEnabled` / `syncEnabled` |
| `POST /api/serviceadmin/clients/{id}/plaid/items/{itemId}/disconnect` | Force-disconnect an item |

**Webhook (public, no session — must not touch Spring Session):**

| Method & path | Purpose |
|---|---|
| `POST /api/plaid/webhook` | Receive Plaid events. Verified by JWT signature, **not** by session. |

> Note: `/api/*` is normally gated by `AuthFilter`. The webhook must be **whitelisted** in `AuthFilter` (like other public paths) so Plaid can reach it unauthenticated; security comes from JWT verification instead. Consider also mounting it under a path that bypasses Spring Session lookups, consistent with the existing `/public/...` note in `ServiceAdminController`.

---

## 6. Transaction Review Workflow

State machine per staged transaction:

```
        (sync/webhook)                 (user edits)
Plaid ─────────────────►  PENDING  ◄───────────────
                            │  │
                   approve  │  │  reject
                            ▼  ▼
                     APPROVED   REJECTED
                        │
             promote via IncomeService/ExpenseService
                        ▼
             income.id / expense.id recorded on staging row
```

Rules:
- **Direction:** default from Plaid amount sign (Plaid: positive = money out/expense, negative = money in/income) — presented to the user and **editable** before approval.
- **Income approval** requires a `sub_source` (fund) + payment method; calls existing `IncomeService.createIncome(...)` so pledge auto-credit and audit fields apply.
- **Expense approval** requires a `purpose` + `main_source` + method; calls `ExpenseService`.
- **Pending Plaid transactions** (`pending = true`) are shown read-only and cannot be approved until they post (Plaid replaces them with a posted transaction; handled via `transactions/sync` modified/removed).
- **Bulk actions** operate only on rows the user is allowed to see (tenant-scoped) and skip rows missing required mappings, returning a per-row result summary.
- Approvals/rejections/edits are all written to `plaid_audit_log`.

Category mapping: a lightweight rules table (or config) maps Plaid personal-finance categories → default `sub_source`/`purpose`; the user can override. Unmapped items default to a configurable "Uncategorized" fund and are flagged.

---

## 7. Sync Strategy

- Use **`/transactions/sync`** (cursor-based) as the single source of truth — simpler and more reliable than the legacy `/transactions/get`.
- Per item: loop `has_more` pulling `added` / `modified` / `removed`, upserting staging rows, then persist `next_cursor`.
- **Triggers:**
  1. On successful item link (initial pull).
  2. On webhook `SYNC_UPDATES_AVAILABLE` (primary continuous path).
  3. A scheduled safety-net job (e.g., hourly) for items whose webhook may have been missed — reuses the existing scheduling infrastructure. Runs only for churches with `sync_enabled`.
- All sync writes are idempotent via `plaid_transaction_id` uniqueness; `removed` marks staging rows (soft) and, if already promoted, flags the ledger row for attention rather than silently deleting.

---

## 8. Webhook Handling & Security

**Endpoint:** `POST https://churchgeniuspro.net/api/plaid/webhook`

**Verification (required):**
1. Read the `Plaid-Verification` JWT header.
2. Fetch the signing key via `/webhook_verification_key/get` (cache JWKs by `kid`).
3. Verify the JWT (ES256) and confirm the request body's SHA-256 matches the `request_body_sha256` claim, and that the token is fresh (reject if issued > 5 min ago).
4. Reject (401) and log if verification fails; record `signature_valid=false`.

**Events handled:**
| webhook_type | webhook_code | Action |
|---|---|---|
| `TRANSACTIONS` | `SYNC_UPDATES_AVAILABLE` | Enqueue `PlaidSyncService.sync(item)` |
| `TRANSACTIONS` | `DEFAULT_UPDATE` / `INITIAL_UPDATE` / `HISTORICAL_UPDATE` | Enqueue sync (compat) |
| `TRANSACTIONS` | `TRANSACTIONS_REMOVED` | Soft-remove staged rows |
| `ITEM` | `ERROR` (e.g. `ITEM_LOGIN_REQUIRED`) | Set item `status=LOGIN_REQUIRED/ERROR`; surface re-auth banner |
| `ITEM` | `PENDING_EXPIRATION` | Mark `DEGRADED`; prompt re-auth |
| `ITEM` | `USER_PERMISSION_REVOKED` | Mark `DISCONNECTED`; stop sync |
| `ITEM` | `WEBHOOK_UPDATE_ACKNOWLEDGED` | Log only |

Processing is **asynchronous** (record fast, return 200 quickly, process in a worker) and **idempotent** (dedupe by item + type + code + time window). Every receipt is written to `plaid_webhook_event` and `plaid_audit_log`.

---

## 9. Service Admin Controls (per church)

Accessed from the existing Service Admin portal (`/serviceadminlogin` → `/serviceadminhome`), added as a "Plaid / Bank Sync" panel alongside the OpenAI and Voice panels:
- **Enable/disable Plaid** for the church (`plaid_enabled`). When off, all `/api/plaid/*` endpoints for that tenant return 403 and Link is hidden.
- **Enable/disable sync** (`sync_enabled`). When off, webhooks are acknowledged but no new sync runs; existing queue stays reviewable.
- **View connected accounts** per church (institution, masked account, status, last sync).
- **Force disconnect** — calls Plaid `/item/remove`, soft-deletes `plaid_item`/`plaid_account`, clears cursor, and audit-logs the action. Existing staged/ledger rows are retained per retention policy.

---

## 10. Security Design (maps to the requirements)

| Requirement | Design |
|---|---|
| Tokens encrypted at rest | `PlaidTokenCipher` (AES-256-GCM, random IV, key from `PLAID_TOKEN_ENC_KEY` env). Access tokens stored only as `access_token_enc`. |
| TLS 1.2+ for all API comms | Outbound `RestClient` uses HTTPS to Plaid; app already serves prod over HTTPS. Enforce min TLS 1.2 on the HTTP client. |
| No secrets in frontend or logs | `client_id`/`secret`/access tokens live only in backend env/DB; never sent to the browser; `PlaidClient` and webhook logging redact tokens and never log bodies with credentials. Link tokens (short-lived, safe for client) are the only Plaid value returned to the browser. |
| RBAC enforced | `PlaidGuard` + existing `RoleGuard`/permission keys; all endpoints tenant-scoped by `client_id`; Service Admin endpoints require `ServiceAdmin` role. |
| Full audit logging of financial actions | `plaid_audit_log` records every link, sync, edit, approve/reject, disconnect, and setting change with actor + target + detail. |
| Email verified + valid role to connect | `PlaidGuard` requires `app_user.email_verified = true` and role ∈ {Admin, Accountant, or a financial privilege key}. |

---

## 11. Configuration (all via environment variables)

Add to `application.properties` (values via env; **secrets never committed**):

```properties
plaid.client-id=${PLAID_CLIENT_ID:}
plaid.secret=${PLAID_SECRET:}
plaid.env=${PLAID_ENV:sandbox}            # sandbox | production
plaid.base-url=${PLAID_BASE_URL:https://sandbox.plaid.com}
plaid.webhook-url=${PLAID_WEBHOOK_URL:https://churchgeniuspro.net/api/plaid/webhook}
plaid.products=${PLAID_PRODUCTS:transactions}
plaid.country-codes=${PLAID_COUNTRY_CODES:US}
plaid.token-enc-key=${PLAID_TOKEN_ENC_KEY:}   # 32-byte base64 AES key
```

Sandbox credentials for testing are provided out-of-band and set **only** as `PLAID_CLIENT_ID` / `PLAID_SECRET` environment variables in the backend. They must not be written into `application.properties`, source, or logs. (The current committed SMTP credential is a known anti-pattern to avoid — see Information Security Policy risk register.)

---

## 12. Frontend Plan (desktop + mobile web)

Reuse existing static-asset conventions (`src/main/resources/static/`) and app styles; remember to copy edited assets into `target/classes/static/` for the running instance.

1. **Bank Connections panel** (admin/accountant): "Connect a bank" button → Plaid Link (`cdn.plaid.com/link/v2/stable/link-initialize.js`); lists connected institutions with status and a re-auth prompt when `LOGIN_REQUIRED`.
2. **Review Queue screen:** responsive table with per-row view/edit (amount, category/fund, description, income/expense toggle), approve/reject buttons, checkbox multi-select with a bulk approve/reject bar, and status/date/account filters. Works on mobile widths (card layout under ~600px, mirroring the family-edit responsive pattern already used).
3. **Service Admin Plaid panel** in `serviceadminhome.html`: per-church enable toggles, connected-account list, force-disconnect.
4. No secrets in any script; the browser only ever receives short-lived Plaid **link tokens** and non-sensitive display data.

---

## 13. File-by-File Change List (planned)

**New (backend, `com.churchgeniuspro.plaid`):**
- `config/PlaidProperties.java`, `config/PlaidClientConfig.java` (RestClient bean, TLS)
- `entity/`: `ChurchPlaidSetting`, `PlaidItem`, `PlaidAccount`, `PlaidTransactionStaging`, `PlaidWebhookEvent`, `PlaidAuditLog`
- `repository/`: one Spring Data repo per entity
- `service/`: `PlaidClient`, `PlaidTokenCipher`, `PlaidLinkService`, `PlaidSyncService`, `PlaidReviewService`, `ServiceAdminPlaidService`, `PlaidWebhookService`, `PlaidAuditService`
- `controller/`: `PlaidLinkController`, `PlaidReviewController`, `PlaidWebhookController`
- `util/PlaidGuard.java`, `dto/` request/response records

**Modified (backend):**
- `ServiceAdminController` — add 3 Plaid endpoints (or delegate to a new `ServiceAdminPlaidController`)
- `webfilter/AuthFilter` — whitelist `/api/plaid/webhook`
- `application.properties` — add `plaid.*` (env-backed)
- `app_user` entity — add `email_verified` flag (+ set it in the OTP/verification flow)
- Reuse (no change): `IncomeService.createIncome(...)`, `ExpenseService`, `PasswordUtil`, scheduling infra

**New (frontend, `static/`):**
- `bankConnections.js` / section in the accountant portal
- `plaidReview.html` (or a section in the existing accountant dashboard) + JS
- Plaid panel additions in `serviceadminhome.html`

**Dependencies:** none required — integrate via `RestClient` (avoids the Plaid Java SDK). Optional later: add `plaid-java` if richer typing is wanted.

---

## 14. Phased Rollout

1. **Phase 1 — Foundation:** entities/repos, `PlaidProperties`, `PlaidClient`, `PlaidTokenCipher`, config, `AuthFilter` whitelist. (No UI.)
2. **Phase 2 — Link & sync:** link-token/exchange endpoints, `PlaidSyncService`, staging population, manual "sync now."
3. **Phase 3 — Review queue:** review API + desktop/mobile-web UI; promotion to `income`/`expense`; bulk actions; audit logging.
4. **Phase 4 — Webhooks:** verified webhook endpoint, async processing, item status handling, re-auth UX.
5. **Phase 5 — Service Admin controls:** per-church toggles, account view, force disconnect.
6. **Phase 6 — Hardening:** email/role gate, scheduled safety-net sync, retention alignment, tests, pen-test of the webhook.

---

## 15. Testing Strategy

- **Sandbox first** (`PLAID_ENV=sandbox`) using Plaid's test institutions and `/sandbox/item/fire_webhook` to simulate events.
- **Unit tests:** `PlaidTokenCipher` round-trip, direction derivation, category mapping, guard logic, idempotent upsert.
- **Integration tests (Testcontainers Postgres, already in the stack):** exchange→sync→review→promote flow; webhook signature verification (valid/invalid/stale); force-disconnect.
- **Security tests:** confirm no token/secret appears in logs or client responses; RBAC and tenant-isolation negative tests; webhook rejects unsigned/replayed requests.
- Cannot be built in the current offline environment; run `./mvnw verify` locally/CI.

---

## 16. Open Questions / Decisions for Review

1. **Email-verified flag:** add the persistent `app_user.email_verified` column (recommended) vs. in-session OTP-before-link fallback?
2. **Authorized financial role:** is "Accountant" sufficient, or add a dedicated `finance.plaid` privilege key for finer control?
3. **Category mapping:** ship a default Plaid-category → fund mapping table, or require manual mapping on first use?
4. **Retention:** confirm staged (unapproved) transactions' retention window and whether rejected rows are purged on a schedule (ties to the Data Deletion & Retention Policy).
5. **Multi-account approval defaults:** should approved income default to a single "General Fund" when unmapped, or block approval until a fund is chosen?
6. **Scheduled safety-net sync cadence** (hourly vs. daily) and whether it is Service-Admin configurable per church.

---

*This is a design/planning document. On approval, implementation proceeds per the phased rollout, matching existing ChurchGeniusPro conventions (entities in `hibernate`/dedicated package, session-based auth via servlet filters, per-church settings keyed by `client_id`, env-backed secrets).*
