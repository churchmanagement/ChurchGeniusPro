# Login Security — Failed-Login Protection

Brute-force and credential-stuffing protection for every authentication entry point in
ChurchGeniusPro. Added 2026-08-16.

---

## 1. The problem this solves, and the trap it avoids

The obvious design — *"lock the account after 5 failed attempts"* — is worse than no
protection at all.

A username in ChurchGeniusPro is **public knowledge**. It appears in invitation emails,
in the member directory, in approval links. So if five wrong passwords lock an account,
any attacker who knows `john@example.com` can lock John out whenever they like, from
anywhere, at zero cost. That is an **account-lockout denial of service**, and it converts
a security control into an attack tool. The support burden lands on church
administrators, who then have no way to distinguish a real attack from a nuisance.

The design here rests on two rules:

1. **No lock is ever permanent.** Every restriction is a row with a hard `blocked_until`
   timestamp that expires by itself. There is no flag for an administrator to clear,
   because there is nothing to clear.
2. **Prefer keys the attacker cannot share with the victim.** The primary counter is
   keyed on **username + IP**, not username. An attacker submitting wrong passwords for
   John's account throttles *their own* address. John, signing in from his own machine,
   is never affected.

The specific attack in the requirements — *an attacker knows `john@example.com` and
submits five incorrect passwords from their own computer* — is covered by a regression
test, [`LoginProtectionServiceTest.attackerCannotLockOutVictim`](src/test/java/com/churchgeniuspro/security/LoginProtectionServiceTest.java).

---

## 2. How ChurchGeniusPro authenticates (context)

There is **no Spring Security authentication** in this application, and this feature does
not introduce one. `spring-security-crypto` is used for BCrypt only. Authentication is:

```
POST /login  →  LoginController.login()
                  ├─ LoginRepository.findByUsernameAndDeletedFalse()   (signup table)
                  ├─ PasswordUtil.matches()                            (BCrypt, strength 10)
                  ├─ account-state checks (active / enabled / subscription)
                  └─ HttpSession built  →  persisted to PostgreSQL (spring-session-jdbc)

every later request  →  webfilter/AuthFilter (order 1, /api/*)
                          └─ session present? account still enabled? → else 401
```

Secondary entry points, each with their own credential type:

| Endpoint | Credential |
|---|---|
| `POST /api/serviceadmin/login` | service-admin account |
| `POST /api/temp-access/login` | temporary badge + code |
| `POST /api/ntag-login/*` | NFC tag + PIN + OTP |

**Before this change, none of these counted failed attempts.** `signup.locked` exists and
is read by `countValidChurchLogin` / `countValidNonChurchLogin`, but nothing ever sets it
to `true` — every code path writes `false`. It is left untouched here: repurposing it
would have re-created exactly the permanent-lock problem described above.

---

## 3. The policy

Three independent scopes. A failure counts against all of them at once; whichever
threshold trips first opens a block.

| Scope | Key | Threshold (default) | Block | Escalates? | Stops |
|---|---|---|---|---|---|
| `USER_IP` | username + IP | 5 / 15 min | 15 min | yes, ×2 → max 2h | The ordinary attack. Blocks the attacker's own pair only. |
| `IP` | IP | 20 / 15 min | 15 min | yes, ×2 → max 2h | One host spraying many usernames. |
| `USERNAME` | username | 20 / 15 min | 15 min | **no** | A distributed attack from many addresses. |

Overall behaviour:

```
1–2 failures        → generic error, no delay
3rd failure onward  → progressive server-side delay: 400ms, 800ms, … capped at 2s
5 failures / 15 min → 15-minute temporary block on that username+IP pair
repeat blocks       → 15m → 30m → 60m → 120m (capped), within a 24-hour escalation window
successful login    → account-scoped counters reset (the IP counter does NOT)
password reset      → account-scoped counters reset AND active blocks released
```

### Why the `USERNAME` scope is the careful one

It is the only scope a third party can trip on someone else's behalf, so it is
deliberately the weakest lever available to an attacker:

- 20 failures required, not 5;
- fixed 15 minutes — it never escalates, so it cannot be ratcheted upwards;
- attempts made *while* a block is active are recorded but **not counted**, so an
  attacker cannot hold the block open by continuing to hammer it;
- the real owner can clear it themselves at any moment by completing a password reset.

If you want *zero* account-level denial-of-service surface, set:

```properties
security.login-protection.account-scope-enabled=false
```

The trade-off is explicit: a botnet spread across many source addresses is then slowed by
the progressive delay, but never blocked. `USER_IP` and `IP` are unaffected.

### Why a successful login does not reset the IP counter

Otherwise an attacker holding one valid account on a host could sign into it every few
attempts to wipe the IP budget, and spray other usernames indefinitely. Account-scoped
counters reset on success; the IP budget only decays with time.

---

## 4. What the user is told

| Situation | HTTP | Body |
|---|---|---|
| Unknown username | 401 | `Invalid username or password.` |
| Wrong password | 401 | `Invalid username or password.` |
| Temporarily blocked | 429 + `Retry-After` | `Too many unsuccessful login attempts. Please wait and try again later or reset your password.` |

The two 401 responses are **byte-for-byte identical** — asserted by
`LoginThrottleWebTest.unknownUser_andBadPassword_areIdentical`. An unknown username also
runs a throwaway BCrypt comparison against a dummy hash, so response *time* does not
reveal which usernames exist either.

The 429 body names neither the threshold nor the attempts remaining. `Retry-After` states
only how long to wait, which a well-behaved client needs; it does not disclose the policy.

---

## 5. Multi-instance correctness

All state is in PostgreSQL — `login_attempt_log` and `login_block` — and both tables are
append-only on the hot path. Several instances behind a load balancer therefore share one
view of the counters, with no in-memory state, no locking and no session affinity.

The worst-case race is two instances opening overlapping block rows for the same key at
the same instant; the block simply ends at the later expiry. There is no correctness
problem and no lock contention to tune.

Cost on the success path: **one indexed query** (`login_block` lookup) plus one insert.
The heavier counting work happens only on the failure path — the attacker's path.

---

## 6. Files

### New

| File | Purpose |
|---|---|
| `hibernate/LoginAttemptLog.java` | Append-only attempt audit + counter source |
| `hibernate/LoginBlock.java` | Temporary blocks, each with a hard expiry |
| `repository/LoginAttemptLogRepository.java` | Counting queries + retention purge |
| `repository/LoginBlockRepository.java` | Active-block lookup, escalation history, release |
| `config/LoginProtectionProperties.java` | All thresholds/durations, `security.login-protection.*` |
| `service/LoginProtectionService.java` | The whole policy — the only place decisions are made |
| `util/ClientIpResolver.java` | IP resolution + device-hash helper |
| `webfilter/LoginThrottleFilter.java` | Same protection for the secondary login endpoints |
| `LOGIN_SECURITY.md` | This document |
| `src/test/java/com/churchgeniuspro/security/LoginProtectionServiceTest.java` | 19 policy tests |
| `src/test/java/com/churchgeniuspro/security/LoginThrottleWebTest.java` | 5 HTTP-contract tests |

### Modified

| File | Change |
|---|---|
| `controller/LoginController.java` | Guard before lookup; record failure/success/denied; dummy-hash timing defence; **removed the password-length from the failure log** |
| `controller/ForgotPasswordController.java` | Refuse resets from an IP-blocked host; honour the email cooldown; clear counters + release blocks after a successful reset |
| `service/PasswordResetService.java` | 256-bit `SecureRandom` tokens (was `UUID`); per-account email cooldown; `createdAt` recorded |
| `hibernate/PasswordResetToken.java` | New `created_at` column |
| `repository/PasswordResetTokenRepository.java` | `findFirstByUsernameOrderByIdDesc` for the cooldown |
| `webfilter/FilterConfig.java` | Registers `LoginThrottleFilter` at order 0 |
| `config/DatabaseIndexInitializer.java` | Seven indexes for the new tables |
| `src/main/resources/application.properties` | The `security.login-protection.*` block |
| `src/main/resources/static/login.html` | Surfaces the 429 message on the biometric path |
| `migrate_production.sql` | Tables, sequences, indexes, `password_reset_token.created_at` backfill |

---

## 7. Configuration reference

All under `security.login-protection.` in `application.properties`.

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch |
| `window` | `15m` | Rolling window for every counter |
| `max-failed-attempts` | `5` | username+IP threshold |
| `block-duration` | `15m` | Initial username+IP block |
| `max-block-duration` | `2h` | Absolute ceiling on any block |
| `block-escalation-factor` | `2` | Multiplier per repeat block |
| `escalation-window` | `24h` | How far back repeat blocks count |
| `ip-scope-enabled` | `true` | Disable for single-NAT intranets |
| `ip-max-failed-attempts` | `20` | IP threshold |
| `ip-block-duration` | `15m` | Initial IP block |
| `account-scope-enabled` | `true` | `false` = zero account-level DoS surface |
| `account-max-failed-attempts` | `20` | Username threshold |
| `account-block-duration` | `15m` | Username block (never escalates) |
| `progressive-delay-enabled` | `true` | Server-side slow-down |
| `progressive-delay-after-attempts` | `2` | Failures before delays start |
| `progressive-delay-step` | `400ms` | Added per extra failure |
| `progressive-delay-max` | `2s` | Cap — keep small, see §9 |
| `suggest-password-reset-after-blocks` | `2` | When to steer to a reset |
| `password-reset-request-cooldown` | `2m` | Minimum gap between reset emails |
| `trust-forwarded-headers` | `true` | **Read §8 before changing** |
| `retention` | `30d` | How long audit rows are kept |
| `purge-cron` | `0 20 3 * * *` | Nightly retention purge |

---

## 8. Deployment note: `trust-forwarded-headers`

This is the one setting that must match how the app is actually deployed. Getting it
wrong degrades the IP-based scopes in one direction or the other.

- **`true` (default)** — the client address is read from `X-Forwarded-For`, then
  `X-Real-IP`. Correct behind a reverse proxy or load balancer, **provided the proxy
  overwrites the header rather than appending to it.** This matches the existing
  `clientIp()` helper in `ForgotUsernameController`. If the application is ever exposed
  directly to the internet with this on, the header is fully attacker-controlled and both
  IP-based scopes can be side-stepped by rotating it — the `USERNAME` scope is what still
  catches that case.
- **`false`** — always use the socket address. Correct only when the app is directly
  internet-facing. Behind a proxy this makes every user share the proxy's address, and the
  `IP` scope will then throttle legitimate traffic.

A cleaner long-term alternative is Spring Boot's `server.forward-headers-strategy=FRAMEWORK`,
which fixes `getRemoteAddr()` for the whole application rather than for this feature alone.

---

## 9. Known limitations and recommendations

Worth reading before treating this as finished.

1. **The progressive delay occupies a servlet thread.** That is inherent to a synchronous
   `Thread.sleep` in a Spring MVC handler. The 2s cap keeps it modest; raising it turns
   the limiter into its own resource-exhaustion vector. Consider dropping to `1s` if the
   thread pool is ever tight.
2. **No CAPTCHA.** After repeated blocks the user is steered towards a password reset, but
   there is no proof-of-work or challenge step. `ForgotUsernameController` already has a
   `requireCaptcha` flag wired to the frontend — the same hCaptcha integration could be
   reused here, and would meaningfully raise the cost of a distributed attack.
3. **No multi-factor authentication.** Rate limiting slows credential stuffing; it does
   not stop an attacker who already has the correct password from a breach elsewhere. MFA
   for Admin-role accounts would be the single highest-value follow-up.
4. **`ForgotPasswordController` logs email addresses via `System.out.println`** (pre-existing,
   left in place to keep this change reviewable). Those lines print the resolved email,
   the provided email and the client id on every reset request. They should move to a
   logger at DEBUG, with the addresses redacted.
5. **Reset tokens are stored in plaintext.** Anyone with a read of `password_reset_token`
   inside the 10-minute window can take over an account. Storing a SHA-256 of the token
   and comparing hashes would close that, at the cost of one migration.
6. **`login.html` persists the password in `localStorage`** (base64, for the "biometric"
   replay path) — pre-existing and out of scope here, but it means a single XSS on any
   page yields the plaintext password. Worth its own ticket; the WebAuthn credential
   should be used to unlock a server-side token rather than to replay a stored password.
7. **`PasswordUtil.matches` still accepts legacy plaintext rows.** Correct as a migration
   affordance, but the fallback should be removed once `SELECT COUNT(*) FROM signup WHERE
   password NOT LIKE '$2%'` returns zero.
8. **Retention is 30 days.** `login_attempt_log` records usernames and IP addresses. If a
   tenant's data-retention policy is shorter, lower `security.login-protection.retention`
   to match; the nightly purge enforces whatever is configured.
9. **Nothing surfaces the audit data in the UI.** The `com.churchgeniuspro.security.audit`
   logger emits structured `event=` lines suited to a SIEM, and both tables carry
   `client_id`, so a per-church "recent sign-in activity" page would be a natural next step.

---

## 10. Test coverage

`./mvnw test -Dtest='LoginProtectionServiceTest,LoginThrottleWebTest'` — 24 tests, all
passing against Spring Boot 4.0.6 / Java 25.

| Area | Tests |
|---|---|
| Normal login | clean request allowed; success recorded and leaves nothing blocked |
| Incorrect passwords | failures below threshold do not block; progressive delay grows then caps |
| Temporary blocking | 5th failure opens a 15-min block; every block is time-boxed and lifts on its own; a live block is not extended by further attempts |
| **Account-lockout DoS** | attacker cannot lock the owner out; account scope needs a distributed attack, is short, never escalates; disabling the scope removes the surface entirely |
| IP / account rate limiting | username spraying trips the IP scope; distributed failures trip the account scope |
| Successful login | resets the account counters; does **not** reset the IP counter |
| Password reset | releases the account's blocks immediately |
| Repeated attacks | 15m → 30m → 45m (capped) escalation |
| Enumeration | unknown user and wrong password are identical responses; blocked message leaks no threshold |
| Logging hygiene | no password-derived field is ever persisted (asserted structurally) |
| Normalisation | five username casings share one counter |
| Kill switch | `enabled=false` records nothing and blocks nothing |
