# Logging & Security Audit Trail

Daily categorised logs, IP geolocation on sign-in, credential redaction, and 30-day
retention — designed for Azure App Service. Added 2026-08-17.

---

## 1. What you get

Three files, rolled at midnight, deleted after 30 days:

```
$HOME/LogFiles/churchgeniuspro/
    security-2026-08-17.log      LOGIN / LOGOUT audit trail
    error-2026-08-17.log         ERROR only, with full stack traces
    application-2026-08-17.log   everything else
```

**security-2026-08-17.log**

```
2026-08-17 09:14:22.331 | LOGIN  | church="Grace Chapel" | username=john@example.com | role=Admin | at=2026-08-17T09:14:22 | ip=203.0.113.7 | city=Olathe | state=Kansas | country=United States | sessionRef=9f2a1c4b8e01
2026-08-17 09:52:10.004 | LOGOUT | church="Grace Chapel" | username=john@example.com | role=Admin | at=2026-08-17T09:52:10 | ip=203.0.113.7 | sessionRef=9f2a1c4b8e01
```

**error-2026-08-17.log**

```
2026-08-17 09:31:07.882 ERROR [http-nio-8080-exec-3] c.c.controller.GlobalExceptionHandler - Unhandled exception [ref=a1b2c3d4] for POST /api/family/save | user=john@example.com | church=Grace Chapel | role=Admin | ip=203.0.113.7 : null
java.lang.NullPointerException: Cannot invoke "String.trim()" because "name" is null
	at com.churchgeniuspro.service.FamilyService.save(FamilyService.java:212)
	...
```

`ref=a1b2c3d4` is also returned to the caller in the 500 response body, so "it said error
a1b2c3d4" points at exactly one log entry.

`sessionRef` pairs a LOGIN with its LOGOUT. It is a truncated SHA-256 of the session id,
never the id itself — see §5.

---

## 2. Azure App Service architecture

### The target environment

| | |
|---|---|
| Resource group | `churchgeniuspro_group` |
| App Service plan | `ASP-churchgeniusprogroup-a30f`, **Premium v3 P0v3** |
| Region | Central US |
| Runtime | **Java SE, Java 25** (embedded server — the JAR runs directly, no Tomcat container) |
| URL | `churchgeniuspro-bcgehmgya6audmg2.centralus-01.azurewebsites.net` |

Nothing in the repository configures deployment — `scripts/deploy.sh` is an explicit no-op,
`ci.yml` never deploys, and there is no Dockerfile, `web.config` or Bicep template. Deployment
is done from the IDE's Azure toolkit / publish profile. Two facts still need confirming from
the portal, because they are not visible in the publish panel: **instance count** (§4) and
**Always On** (§2.5). Commands for both are in §2.4.

The design works unchanged on Linux and Windows App Service, so the OS does not need to be
pinned down before deploying.

### 2.1 The durability problem

On App Service, almost everything the app can write to is scratch space Azure may discard —
the deployment directory, the container filesystem, `/tmp`, and the Unix user's home
directory. A restart, a redeploy, a scale event or the instance moving to different hardware
takes it with it.

**The Azure Files share is the exception.** It is mounted into every instance, it is
persistent, it is shared across all instances, and it is what Kudu, the FTP endpoint and the
Log stream blade expose:

- Linux App Service → `/home/LogFiles/churchgeniuspro/`
- Windows App Service → `D:\home\LogFiles\churchgeniuspro\`

**Do not use `$HOME` to find it.** This is worth stating plainly because the first
production deployment got it wrong. On Windows App Service `HOME` does point at the share,
which makes the assumption look sound — but on the Linux Java SE container the process runs
as root, so **`HOME=/root`**, which is container-local scratch storage. The audit trail was
written there for a full deployment cycle: the files existed, the app was healthy, nothing
errored, and every one of those logs would have vanished at the next restart. The only
outward symptom was that `/home/LogFiles/churchgeniuspro/` never appeared.

`LogDirectoryPropertyDefiner` now identifies the share by a property only it has — it is the
directory containing `site` (as in `/home/site/wwwroot`). `HOME` is still tried first, since
it is correct on Windows, but only accepted if it passes that test. If no share can be
identified, the fallback is a known mount point (`/home`, `D:\home`) and never `HOME`.

### 2.2 The recommended architecture — simplest and cheapest that is actually reliable

| Layer | Where | Cost | Survives |
|---|---|---|---|
| **1. Security events (store of record)** | PostgreSQL `login_attempt_log` | £0 — the database already exists | Everything. Restarts, redeploys, scale events, the App Service being recreated, someone clearing the share |
| **2. Daily files** | `$HOME/LogFiles/churchgeniuspro/` on the Azure Files share | £0 — included in the plan | Restarts, redeploys, instance moves, scale-out |
| **3. Optional off-box archive** | Diagnostic Setting → Storage Account | pennies/month | Loss of the App Service entirely |

**The database layer is the answer to "security logs must not be lost."** A row in
`login_attempt_log` is not a file on a share that a retention job deletes at 30 days — it is
transactional, backed up with everything else, queryable per tenant, and it is the only form
that can answer *"show this church every sign-in last month"* without grepping thirty files.
Every LOGIN and LOGOUT is written to both layers. If the file share were wiped tomorrow the
audit trail would still be intact.

**Deliberately not recommended:** shipping logs to Log Analytics from inside the application
(an SDK, an appender, an ingestion key). It adds a dependency and a per-GB bill for something
Azure already does for free from outside the process — and it puts a network call on the
login path. If you want Log Analytics, turn it on at the platform level (§2.3); the console
appender already emits every security event to stdout for it to collect.

### 2.3 Optional: off-box archive or Log Analytics

Neither is required. Both are configured at the platform, with no code change — the console
appender already writes every security and error event to stdout.

```bash
# Archive App Service logs (including stdout) to a Storage Account, 30-day retention
az webapp log config --name <app> --resource-group <rg> \
  --application-logging filesystem --level information

az monitor diagnostic-settings create \
  --name cgp-logs \
  --resource $(az webapp show -n <app> -g <rg> --query id -o tsv) \
  --logs '[{"category":"AppServiceConsoleLogs","enabled":true},
           {"category":"AppServiceAppLogs","enabled":true}]' \
  --storage-account <storage-account-name>          # cheapest
# ...or --workspace <log-analytics-workspace-id>    # queryable with KQL, per-GB cost
```

Storage Account archive is the cost-effective choice for compliance retention. Log Analytics
is worth it only if you actually want to query and alert across the logs.

### 2.4 Confirm your own setup

I could not read these; run them and keep the output with your ops notes:

```bash
# Linux or Windows, plan tier, and instance count
az webapp show -n <app> -g <rg> --query "{os:kind, state:state, plan:appServicePlanId}" -o table
az appservice plan show --ids $(az webapp show -n <app> -g <rg> --query appServicePlanId -o tsv) \
  --query "{tier:sku.tier, size:sku.size, instances:sku.capacity, workers:numberOfWorkers}" -o table

# Is anything already shipping logs off-box?
az monitor diagnostic-settings list \
  --resource $(az webapp show -n <app> -g <rg> --query id -o tsv) -o table

# After deploying, confirm the files are actually being written
#   Kudu console:  https://<app>.scm.azurewebsites.net  ->  Debug console
ls -la /home/LogFiles/churchgeniuspro/
```

**Two things worth checking specifically:**

- **`WEBSITE_LOCAL_CACHE_OPTION`** — if this is set to `Always`, `$HOME` becomes a *local,
  non-persistent* copy and the durability guarantee is gone. It is off by default. Confirm
  with `az webapp config appsettings list -n <app> -g <rg> -o table`.
- **Instance count** — if the plan runs more than one instance, see §4.

### 2.5 Always On, and the App Service X-Forwarded-For quirk

**Always On must be enabled.** Without it App Service unloads the app after ~20 minutes of no
traffic, and an unloaded app runs no `@Scheduled` job — so the daily log sweep, the
`login_attempt_log` retention purge and the nine existing reminder crons simply do not fire.
Premium v3 supports it; confirm with:

```bash
az webapp config show -n <app> -g churchgeniuspro_group --query alwaysOn
```

**App Service puts the client's port in `X-Forwarded-For`.** Its front end sends
`X-Forwarded-For: 203.0.113.7:54321` — the client IP *with the ephemeral source port
attached*. This is unusual (most proxies send a bare address) and it is silently destructive
for anything that uses the address as a key: every request from one client carries a different
port, so it looks like a different client each time. Left unhandled, the username+IP and IP
rate-limit scopes would never reach a second failure and the brute-force protection would be
defeated with no error logged anywhere.

`ClientIpResolver.stripPort` handles it, for IPv4 (`203.0.113.7:54321`) and bracketed IPv6
(`[2001:db8::1]:54321`), while leaving bare and IPv4-mapped IPv6 addresses untouched.
`ClientIpResolverTest` pins the behaviour, including the assertion that three different
ephemeral ports from one client collapse to one rate-limit key.

The same bug was present in `ForgotUsernameController.clientIp()` — meaning its documented
"5 recovery attempts per hour per IP" limit had never actually worked in production. It now
delegates to the shared resolver.

Because App Service always sets this header, `security.login-protection.trust-forwarded-headers=true`
(the default) is correct here and should stay on. With it off, every user would resolve to the
App Service front-end address and share one bucket.

---

---

## 3. Retention: how 30 days is actually enforced

Two independent mechanisms, because the first one has a hole:

1. **logback `maxHistory=30`** on each appender, plus `cleanHistoryOnStart=true`. This only
   removes files matching the exact filename pattern of a *currently configured* appender.
2. **`LogRetentionService`** — a daily sweep (03:40, `logging.cgp.purge-cron`) that deletes
   any file in the log directory older than `logging.cgp.retention-days`.

The sweeper exists because the share outlives any single instance, so it accumulates files
logback has no idea about: written by an instance that has since been scaled in, or during a
period when per-instance filenames were enabled. Without it those files — full of usernames
and IP addresses — would sit there indefinitely.

The sweeper is deliberately narrow. It only touches regular files, in one directory, whose
names start with `security-` / `error-` / `application-` / `churchgeniuspro` **and** end in
`.log` / `.gz` / `.zip`. `GeoLite2-City.mmdb`, backups and anything else are left alone —
asserted in `LogDestinationTest.sweeperLeavesForeignFilesAlone`. Age comes from the
filesystem's last-modified time, not the date in the filename, so a file still being appended
to is never deleted for carrying an old date.

It refuses to run if `retention-days` is below 1, so a bad environment variable cannot
silently wipe the audit trail.

**The database rows have their own 30-day purge** (`security.login-protection.retention`, from
the earlier login-security work). Raise that if you want the queryable trail to outlive the
files — 90 or 365 days costs very little at this row volume.

---

## 4. Multiple instances

All instances mount the same `$HOME` share, so by default they would append to the same
daily file. There are two ways to make that safe, and the default changed after the first
production deployment.

**Default: prudent mode OFF — for overhead, not correctness.** Logback's prudent mode takes
an exclusive byte-range lock per write, which is the textbook answer for several JVMs sharing
one file. It was tested on this deployment's `/home` Azure Files (SMB) mount and **works
correctly** — an earlier version of this document claimed otherwise, on the strength of a
misdiagnosis; see the troubleshooting note about Kudu below for how that happened.

It is off by default simply because it buys nothing on a single instance while adding a lock
round-trip to every write on a network mount. Turn it on with `CGP_LOG_PRUDENT=true` if you
scale out and want one shared file.

**Scaling out: set `CGP_LOG_PER_INSTANCE_FILES=true`.** Each instance then writes its own
file — `security-2026-08-17-a1b2c3d4.log`, suffixed with the first 8 characters of
`WEBSITE_INSTANCE_ID`. No locking is involved at all, which makes it the more reliable of
the two options. The retention sweeper already recognises that filename shape.

`CGP_LOG_PRUDENT=true` remains available if one shared file is genuinely required, but
prefer per-instance files.

Either way, **the database trail is unaffected** — it is a single table, correct at any
instance count, which is the other reason it is the store of record.

### If no log files appear at all

The startup line from `LogRetentionService` tells you immediately. Search the app's stdout
(App Service -> Log stream, or `/home/LogFiles/*_docker.log`) for:

```
Log destination OK - logDir='/home/LogFiles/churchgeniuspro' exists=true writable=true | HOME=/home WEBSITE_INSTANCE_ID=(set) ...
```

If it instead says `LOG DESTINATION PROBLEM`, the resolved path is in that same line along
with the environment variables the resolution depends on. The usual causes:

| Symptom in the line | Cause | Fix |
|---|---|---|
| `logDir='logs'` (relative) | `HOME` or `WEBSITE_INSTANCE_ID` missing from the app's environment | Set `CGP_LOG_DIR=/home/LogFiles/churchgeniuspro` explicitly |
| `writable=false` | Directory not creatable on the mount | Create it once via Kudu, or point `CGP_LOG_DIR` at a writable path |
| Line absent entirely | The app did not start, or is running an older JAR | Check the JAR contains `BOOT-INF/classes/logback-spring.xml` |
| Line says OK but no files | Prudent mode locking failure | Confirm `CGP_LOG_PRUDENT` is unset or `false` |

**Check file sizes from the app container, not Kudu.** On Linux App Service the Kudu debug
console runs in a *separate container* from the app. Both mount the same `/home` share, but
Kudu's view of file metadata is cached and can report **0 bytes for files that are actually
being written**. During this feature's first deployment that sent the investigation down a
completely wrong path. Use Development Tools → SSH (the app container) and `wc -c`:

```bash
wc -c /home/LogFiles/churchgeniuspro/*
```

Kudu also cannot see the app's processes or environment variables at all — which is why
`HOME=/root` was invisible until someone looked from the right container.

## 5. Credentials are never written

Two layers, because "just don't log secrets" does not survive contact with 90+ controllers.

**Layer 1 — nothing sensitive is passed to a logger.** The audit line contains no password,
no password length, no token, no cookie, and no raw session id. The session appears only as
`sessionRef`, a truncated SHA-256. That matters: a session id is a bearer credential, so
anyone who reads one out of a log file can replay it and impersonate that user until the
session expires. The hash still pairs a login with its logout, which is all it was needed
for. The same applies to the database: `login_attempt_log.session_hash` replaces the old
`session_id` column, and the migration **drops** the old column rather than copying the raw
values across.

**Layer 2 — `MaskingPatternLayout` redacts the rendered line**, including the stack trace,
on the way to every appender. There is no appender that bypasses it. It catches:

- `password` / `passwd` / `pwd` / `newPassword` / `demoPassword`
- `token` / `accessToken` / `refreshToken` / `rememberToken` / `sessionId` / `csrf`
- `secret` / `clientSecret` / `apiKey` / `privateKey` / `encKey`
- `Authorization: Bearer …` and `Basic …`
- `Cookie` / `Set-Cookie` values, `JSESSIONID`, `SESSION`
- BCrypt hashes (`$2a$…`) — a stored hash is still a credential
- Vendor key shapes: OpenAI `sk-…`, Twilio `AC…`/`SK…`, Plaid `access-production-…`

in JSON, `key=value`, query strings, `Map.toString()` output and `SCREAMING_SNAKE_CASE`
environment variable names alike. 22 tests in `SensitiveDataMaskerTest` assert that a
specific secret does not survive each form.

Masking a *rendered line* rather than a message is deliberate: exception messages are the
common accidental leak — a failed HTTP call to Plaid or Twilio puts the request body, headers
and all, into `getMessage()`, and that text only appears after the throwable is formatted.

Two implementation notes that were bugs before they were tests:

- The field-name patterns use `(?<![A-Za-z0-9])` rather than `\b`, because `\b` does not fire
  between `_` and a letter — so `\bENC_KEY\b` silently failed to match inside
  `PLAID_TOKEN_ENC_KEY`, which is exactly how secrets are named in this app's environment.
- Value-shape patterns (`$2a$…`, `sk-…`) run unconditionally rather than behind the
  trigger-word fast path, because a bare key can appear with no field name near it.

---

## 6. Geolocation

City / state / country come from the **MaxMind GeoLite2 City** database, read from a local
file. No outbound call on the login path, no rate limit, and no member's IP address is sent
to a third party.

**The database file is not in this repository** — MaxMind's licence forbids redistribution
and the file is ~60MB. Install it with `scripts/update-geoip.sh` (free MaxMind account +
licence key; the script header has the steps). Default location
`$HOME/data/GeoLite2-City.mmdb`, which on App Service is the persistent share, so it survives
redeployments and does not bloat the build artifact.

**Without the file the application runs normally** — one warning at startup, and
`city=- state=- country=-` in the audit trail. Every failure mode (missing file, corrupt
file, unparseable address, address not in the database) degrades to "unknown". Geolocation is
decoration on an audit record; it is never worth turning a lookup problem into a failed
sign-in. `GeoIpServiceTest` covers each of those paths.

Refresh monthly — MaxMind republishes twice a week and stale data slowly loses accuracy.

Treat the result as a hint, not a fact. VPNs, mobile carrier NAT and corporate egress
routinely place a user hundreds of miles from where they are. It is useful for spotting
"this account signed in from three countries in an hour"; it is not evidence of anyone's
whereabouts.

---

## 7. Files

### New

| File | Purpose |
|---|---|
| `src/main/resources/logback-spring.xml` | The three daily appenders, rotation, 30-day retention, masking |
| `logging/SensitiveDataMasker.java` | Credential redaction patterns |
| `logging/MaskingPatternLayout.java` | Applies redaction to every rendered line, stack traces included |
| `logging/LogDirectoryPropertyDefiner.java` | Resolves the persistent App Service log directory |
| `logging/InstanceIdPropertyDefiner.java` | Optional per-instance filename suffix |
| `service/SecurityAuditService.java` | Writes the LOGIN / LOGOUT trail to both file and database |
| `service/GeoIpService.java` | Local GeoLite2 lookup, cached, degrades to "unknown" |
| `service/LogRetentionService.java` | Daily 30-day sweep, backstopping logback's own cleanup |
| `config/GeoIpProperties.java` | `geoip.*` |
| `config/LogRetentionProperties.java` | `logging.cgp.*` |
| `model/GeoLocationBO.java` | city / region / country value type |
| `scripts/update-geoip.sh` | Download / refresh the GeoLite2 database |
| `LOGGING.md` | This document |
| `logging/SensitiveDataMaskerTest.java` | 22 tests — no secret survives any serialisation |
| `logging/LogDestinationTest.java` | 8 tests — directory resolution and sweeper safety |
| `security/SecurityAuditServiceTest.java` | 8 tests — every required field present, no credential |
| `service/GeoIpServiceTest.java` | 6 tests — every degraded path |

### Modified

| File | Change |
|---|---|
| `controller/LoginController.java` | LOGIN audit after the session is built (password login *and* remember-me auto-login); LOGOUT audit before `invalidate()` |
| `controller/GlobalExceptionHandler.java` | Error context (user, church, role, IP) + `ref=` shared with the response body |
| `service/LoginProtectionService.java` | `AuthDetails` enrichment, `recordLogout`, session id stored hashed |
| `hibernate/LoginAttemptLog.java` | `church_name`, `user_role`, `city`, `region`, `country`, `LOGOUT` outcome, `session_id` → `session_hash` |
| `util/ClientIpResolver.java` | `sessionId()` → `sessionHash()`; there is deliberately no accessor for the raw id |
| `src/main/resources/application.properties` | `logging.cgp.*`, `geoip.*`; removed the now-inert `logging.file.*` properties |
| `pom.xml` | `com.maxmind.geoip2:geoip2:4.4.0` |
| `migrate_production.sql` | New columns, `session_id` dropped, per-tenant index |

---

## 8. Configuration reference

| Property | Default | Meaning |
|---|---|---|
| `logging.cgp.purge-enabled` | `true` | Enable the daily retention sweep |
| `logging.cgp.retention-days` | `30` | Delete log files older than this |
| `logging.cgp.purge-cron` | `0 40 3 * * *` | When the sweep runs |
| `logging.cgp.directory` | *(blank)* | Blank = sweep exactly where logback writes |
| `geoip.enabled` | `true` | Master switch for IP → location |
| `geoip.database-path` | `$HOME/data/GeoLite2-City.mmdb` | `GEOIP_DB_PATH` overrides |
| `geoip.cache-size` | `5000` | Resolved addresses held in memory |

Environment variables (App Service application settings):

| Variable | When to set it |
|---|---|
| `CGP_LOG_DIR` | Only to override the automatic App Service path |
| `CGP_LOG_PER_INSTANCE_FILES` | `true` when scaled out far enough for lock contention to matter (§4) |
| `GEOIP_DB_PATH` | Only if the database is not at `$HOME/data/GeoLite2-City.mmdb` |
| `MAXMIND_LICENSE_KEY` | Only when running `scripts/update-geoip.sh` |

---

## 9. Deployment checklist

1. Run the new section of `migrate_production.sql` against production **before** deploying the
   JAR. Note it **drops** `login_attempt_log.session_id` — that column holds raw session
   identifiers and should never have been stored.
2. Deploy the JAR. Nothing else needs configuring: the log directory resolves itself.
3. Confirm the files appear — Kudu console → `ls -la /home/LogFiles/churchgeniuspro/`.
4. Sign in and out once, then `tail security-$(date +%F).log` to confirm the trail.
5. *(Optional)* Install the GeoIP database (§6) and restart, or accept `city=-` for now.
6. *(Optional)* Add a Diagnostic Setting for off-box archive (§2.3).
7. Confirm `WEBSITE_LOCAL_CACHE_OPTION` is not set to `Always` (§2.4), and that **Always On
   is enabled** (§2.5) — without it no scheduled job runs at all.

---

## 10. Known limitations and recommendations

1. **The progressive delay and the audit write are both synchronous** on the request thread.
   The audit write is one INSERT plus an async log append, so it is small — but it is on the
   login path. If sign-in latency ever matters, the database write is the piece to move to an
   `@Async` executor.
2. **Log file writes go over SMB.** The `AsyncAppender` wrappers keep that off the request
   thread, but the security and error appenders are configured with
   `discardingThreshold=0, neverBlock=false` — under extreme write pressure they will block
   rather than drop an audit record. That is the right trade for an audit trail; be aware it
   is a trade.
3. **`ForgotPasswordController` still logs email addresses via `System.out.println`**
   (pre-existing). Those bypass the masker entirely, because `System.out` is not a logger.
   They should move to a logger at DEBUG with the addresses redacted.
4. **The 500 response still echoes `ex.getMessage()`** to the client (pre-existing). That can
   leak SQL fragments and file paths. Now that every 500 carries a `ref`, the body could
   safely be reduced to *"An unexpected error occurred (ref: a1b2c3d4)"* — a small frontend
   change away.
5. **Nothing surfaces the audit data in the UI.** Both tables carry `client_id`, so a
   per-church "recent sign-in activity" page is a straightforward next step, and would be
   more useful to a church admin than a log file they cannot reach.
6. **No alerting.** The audit trail records a suspicious sign-in; nothing tells anyone. An
   Azure Monitor alert on `LOGIN_BLOCK_STARTED` in the console stream, or a nightly query
   over `login_attempt_log`, would close that loop cheaply.
7. **Daily files roll at UTC midnight, which is 7pm Central.** App Service runs in UTC unless
   `WEBSITE_TIME_ZONE` is set, so `security-2026-08-17.log` covers 16 Aug 7pm → 17 Aug 7pm
   local. That is worth knowing before reading an audit log by date. Setting
   `WEBSITE_TIME_ZONE=Central Standard Time` would align it — but it also shifts the nine
   existing reminder crons in `scheduler.properties`, which have been running on UTC, so it is
   a deliberate decision rather than a safe default. I have not changed it.
8. **GeoLite2 accuracy is roughly city-level at best**, and materially worse for mobile
   networks. Do not build an access-control rule on it.
9. **Deployment slots have separate storage.** If a staging slot is ever used, its logs stay
   in that slot's own `/home` and do not follow a swap.

---

## 11. Test coverage

`./mvnw test` — 168 tests, all passing (58 new across the two work items). All three
file-writing modes (default, `CGP_LOG_PRUDENT=true`, `CGP_LOG_PER_INSTANCE_FILES=true`)
were additionally verified by running the real logback config outside the test suite.

The logging config was also verified end-to-end outside the test suite: loading
`logback-spring.xml` in a real logback context produced exactly
`security-2026-08-17.log`, `error-2026-08-17.log` and `application-2026-08-17.log`, with the
audit fields intact, the stack trace attached in the error file, and a planted secret absent
from all three. That run is what caught `%wEx` — Spring Boot's throwable converter, which is
not registered for a standalone logback config and would have printed
`%PARSER_ERROR[wEx]` in front of every production log line.
