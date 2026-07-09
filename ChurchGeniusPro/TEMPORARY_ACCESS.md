# Temporary Access

Time-limited, auditable login via a **printed barcode badge + a 6-digit code** — no
temporary usernames/passwords to manage. An admin creates a pass with a start/end
window and a set of permitted pages; the holder scans the badge and enters the code
to get controlled, expiring access.

## Flow

1. Admin opens **`/temporaryAccess`** (SuperAdmin / Admin / church account), sets the
   time window, picks the permitted pages, and clicks **Generate**.
2. The system creates a unique **barcode token** and a random **6-digit code**
   (stored **hashed**). The code is shown once to the admin and **emailed** to the
   holder if an address was provided.
3. Admin prints a compact **58 mm thermal badge** (church logo + Code 128 barcode +
   validity + holder).
4. The holder goes to **`/tempLogin`**, scans the badge, and enters the code.
5. The server validates the **time window + barcode + code hash** and starts a
   time-boxed session limited to the permitted pages.
6. Every page shows a **countdown banner**; it warns once at **15 minutes** left and
   redirects to the login screen the moment access ends.
7. **Login/logout events are recorded** for auditing (viewable per pass).

## Data model

- **`temporary_access`** — `id, client_id, user_id, holder_name, email,
  start_date_time, end_date_time, barcode_value (unique), access_code_hash, status,
  permissions (CSV of page keys), created_by, created_date, delete_flag`.
- **`access_audit`** — `id, temporary_access_id, client_id, login_time, logout_time,
  device_info, ip_address`.

Tables auto-create via Hibernate `ddl-auto=update`.

## Security

- The 6-digit code is stored as a **BCrypt hash** (`PasswordUtil.encode`), never in
  plaintext; it is returned to the admin **once** at creation and cannot be retrieved
  afterward.
- The barcode token is a **secure-random, unique, non-guessable** value
  (`TAC-` + 24 hex chars), uniqueness-checked at generation.
- Sessions **expire automatically** at the end time: `TempAccessFilter` re-checks the
  pass on every request and ends the session (401 for APIs, redirect for pages) the
  moment it lapses or is revoked.
- **Extend** moves the end time forward; because the filter reads the deadline from
  the database each request, an active session simply keeps working — **no forced
  logout**.
- Passes are scoped to **General-section pages only** (Meetings, Events, Event
  Check-in, Kids Ministry, Worship Planning, Sunday School, Volunteers). Accounting
  and Admin/user-management pages are **not grantable**. Page access is enforced by
  the existing `RoleGuard.requirePermission` checks: at login the granted pages are
  written into the session `privileges` JSON (every catalog key set explicitly, so
  non-granted pages are denied), and the session runs at the low-privilege `User`
  role so admin/finance pages and their APIs are refused.

## Key files

**Backend** (`com.churchgeniuspro`):
`hibernate/TemporaryAccess.java`, `hibernate/AccessAudit.java`,
`repository/TemporaryAccessRepository.java`, `repository/AccessAuditRepository.java`,
`service/TemporaryAccessService.java`,
`controller/TemporaryAccessController.java` (admin REST + `/login`, `/session`,
`/logout`, `/logo`), `controller/TempAccessPageController.java` (page routes),
`webfilter/TempAccessFilter.java` (+ registered in `webfilter/FilterConfig.java`;
`/api/temp-access/login` whitelisted in `webfilter/AuthFilter.java`).

**Frontend** (`src/main/resources/static`):
`temporaryAccess.html` (admin: create, list, extend, revoke, audit, **Code 128**
badge print via JsBarcode + church logo), `tempLogin.html` (badge + code login),
`temp-session.js` (countdown banner + 15-minute warning + expiry redirect; loaded on
every page via `shell.js`, dormant unless the session is temporary).

## Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET  | `/api/temp-access/catalog` | admin | grantable page list |
| POST | `/api/temp-access` | admin | create a pass (returns the code once) |
| GET  | `/api/temp-access` | admin | list passes |
| POST | `/api/temp-access/{id}/extend` | admin | extend end time (`endDateTime` or `addMinutes`) |
| POST | `/api/temp-access/{id}/revoke` | admin | revoke now |
| GET  | `/api/temp-access/{id}/audit` | admin | login/logout history |
| GET  | `/api/temp-access/{id}/badge` | admin | badge data |
| GET  | `/api/temp-access/logo` | admin | church logo bytes |
| POST | `/api/temp-access/login` | public | validate badge + code → session |
| GET  | `/api/temp-access/session` | session | remaining time / warn threshold |
| POST | `/api/temp-access/logout` | session | end session + stamp audit |

## Build / run

```
./mvnw clean package -DskipTests && ./mvnw spring-boot:run
```

Tables are created on first start. Visit `/temporaryAccess` as an admin to create a
pass; the holder logs in at `/tempLogin`.

## Notes / future hardening

- Page access for temporary users is enforced at the **page-permission layer**
  (`requirePermission`) plus the `User` role. If you later need temporary passes that
  reach Accounting/Admin pages, extend `PAGE_CATALOG` and revisit the session role.
- Optional follow-ups: rate-limit / lockout on repeated bad codes at `/login`;
  one-time (single-use) passes; SMS delivery of the code.
