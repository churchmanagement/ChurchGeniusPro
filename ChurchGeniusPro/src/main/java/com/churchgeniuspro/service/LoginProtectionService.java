package com.churchgeniuspro.service;

import com.churchgeniuspro.config.LoginProtectionProperties;
import com.churchgeniuspro.hibernate.LoginAttemptLog;
import com.churchgeniuspro.hibernate.LoginBlock;
import com.churchgeniuspro.repository.LoginAttemptLogRepository;
import com.churchgeniuspro.repository.LoginBlockRepository;
import com.churchgeniuspro.util.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Server-side brute-force protection for every authentication entry point.
 *
 * <h2>Why not "lock the account after 5 failures"</h2>
 * A username is public knowledge — it appears in emails, invitations and member
 * directories. Permanently locking an account after N failures therefore hands any
 * attacker a one-click denial-of-service: five wrong passwords against
 * {@code john@example.com} and John is locked out until an administrator intervenes.
 *
 * <p>This service never sets a persistent lock. It writes <em>time-boxed</em>
 * {@link LoginBlock} rows that expire on their own, and it prefers keys the attacker
 * cannot share with the victim:
 *
 * <table border="1">
 *   <caption>Rate-limit scopes</caption>
 *   <tr><th>Scope</th><th>Key</th><th>Default</th><th>Who it stops</th></tr>
 *   <tr><td>{@code USER_IP}</td><td>username + IP</td><td>5 / 15 min → 15 min block, escalating</td>
 *       <td>The common case. The attacker blocks <em>their own</em> pair; John, on a
 *           different address, signs in normally throughout.</td></tr>
 *   <tr><td>{@code IP}</td><td>IP</td><td>20 / 15 min → 15 min block, escalating</td>
 *       <td>One host spraying many usernames.</td></tr>
 *   <tr><td>{@code USERNAME}</td><td>username</td><td>20 / 15 min → 15 min block, fixed</td>
 *       <td>Distributed attack from many addresses. This is the only scope a third
 *           party can trip for someone else, so it is short, never escalates, and is
 *           released the instant the real owner completes a password reset.</td></tr>
 * </table>
 *
 * <h2>Multi-instance correctness</h2>
 * All state lives in PostgreSQL ({@code login_attempt_log}, {@code login_block}) and
 * both tables are append-only on the hot path, so several application instances behind
 * a load balancer share one view of the counters with no locking and no sticky sessions.
 * A benign race between two instances can at worst write two overlapping block rows —
 * the block still ends at the later of the two expiry times.
 *
 * <h2>What is never disclosed</h2>
 * Callers receive a single generic failure message and, when throttled, a message that
 * mentions neither the threshold nor how many attempts remain. Failure <em>reasons</em>
 * are recorded server-side only. Passwords are never logged in any form — not the value,
 * not its length.
 *
 * @see LoginProtectionProperties
 */
@Service
public class LoginProtectionService {

    /** Ordinary authentication failure — identical for "no such user" and "wrong password". */
    public static final String GENERIC_FAILURE_MESSAGE = "Invalid username or password.";

    /** Shown when a temporary block is in force. Reveals neither threshold nor remaining attempts. */
    public static final String BLOCKED_MESSAGE =
            "Too many unsuccessful login attempts. Please wait and try again later or reset your password.";

    // Server-side failure categories (never returned to the client).
    public static final String REASON_UNKNOWN_USER    = "UNKNOWN_USER";
    public static final String REASON_BAD_PASSWORD    = "BAD_PASSWORD";
    public static final String REASON_ACCOUNT_STATE   = "ACCOUNT_STATE";
    public static final String REASON_MISSING_FIELDS  = "MISSING_FIELDS";
    public static final String REASON_ENDPOINT_DENIED = "ENDPOINT_DENIED";

    private static final Logger LOG = LoggerFactory.getLogger(LoginProtectionService.class);

    /**
     * Dedicated audit channel so failed logins and blocks can be shipped to a SIEM or
     * alerted on without turning on DEBUG for the whole application.
     */
    private static final Logger AUDIT = LoggerFactory.getLogger("com.churchgeniuspro.security.audit");

    private final LoginAttemptLogRepository attemptRepo;
    private final LoginBlockRepository      blockRepo;
    private final LoginProtectionProperties props;

    public LoginProtectionService(LoginAttemptLogRepository attemptRepo,
                                  LoginBlockRepository blockRepo,
                                  LoginProtectionProperties props) {
        this.attemptRepo = attemptRepo;
        this.blockRepo   = blockRepo;
        this.props       = props;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Outcome of the pre-authentication check.
     *
     * @param blocked                  whether the request must be refused without touching credentials
     * @param retryAfterSeconds        seconds until the block lifts (0 when not blocked)
     * @param passwordResetRecommended whether the message should steer the user to a reset
     */
    public record GuardResult(boolean blocked, long retryAfterSeconds, boolean passwordResetRecommended) {

        static GuardResult allowed() { return new GuardResult(false, 0L, false); }

        /** The message to return to the client — always generic. */
        public String message() { return BLOCKED_MESSAGE; }
    }

    /**
     * Result of recording a failure. The caller applies {@link #delayMillis()} via
     * {@link LoginProtectionService#applyProgressiveDelay(FailureOutcome)} <em>after</em>
     * the transaction has committed, so no database connection is held while sleeping.
     *
     * @param delayMillis   progressive slow-down to apply before responding
     * @param blockTriggered whether this failure tripped a new temporary block
     */
    public record FailureOutcome(long delayMillis, boolean blockTriggered) {
        static FailureOutcome none() { return new FailureOutcome(0L, false); }
    }

    /**
     * Audit enrichment for an authentication event — the descriptive fields that make an
     * entry readable months later, none of which the rate limiter itself needs.
     *
     * <p>Populated by {@link SecurityAuditService}, which knows the session and can resolve
     * geolocation. {@link #empty()} is used on paths where none of it is known (a failed
     * login has no session and no confirmed identity).
     */
    public record AuthDetails(String churchName, String role,
                              String city, String region, String country,
                              String sessionHash) {

        private static final AuthDetails EMPTY = new AuthDetails(null, null, null, null, null, null);

        public static AuthDetails empty() { return EMPTY; }
    }

    /**
     * Decides whether an authentication attempt may proceed. Call this <em>before</em>
     * looking up the account or verifying the password.
     *
     * <p>Costs one indexed query on the success path.
     *
     * @param req         current request (may be {@code null} in unit tests)
     * @param rawUsername submitted username, or {@code null} for username-less endpoints
     */
    @Transactional(readOnly = true)
    public GuardResult check(HttpServletRequest req, String rawUsername) {
        if (!props.isEnabled()) return GuardResult.allowed();

        String username = normaliseUsername(rawUsername);
        String ip       = clientIp(req);
        LocalDateTime now = LocalDateTime.now();

        List<String> keys = scopeKeys(username, ip);
        if (keys.isEmpty()) return GuardResult.allowed();

        List<LoginBlock> active = blockRepo.findActive(keys, now);
        if (active.isEmpty()) return GuardResult.allowed();

        LoginBlock longest = active.get(0);   // ordered by blockedUntil DESC
        long retryAfter = Math.max(1L, Duration.between(now, longest.getBlockedUntil()).toSeconds());
        boolean suggestReset = longest.getEscalationStep() >= props.getSuggestPasswordResetAfterBlocks();

        return new GuardResult(true, retryAfter, suggestReset);
    }

    /**
     * Records an attempt that was refused because a block was already in force.
     * Kept separate from {@link #recordFailure} so a blocked attempt never inflates the
     * failure counters — otherwise a persistent attacker would ratchet their own block
     * upwards for free and, worse, keep the account-scope block alive indefinitely.
     */
    @Transactional
    public void recordBlockedAttempt(HttpServletRequest req, String rawUsername, String endpoint) {
        if (!props.isEnabled()) return;
        String username = normaliseUsername(rawUsername);
        String ip       = clientIp(req);

        save(req, username, ip, endpoint, LoginAttemptLog.OUTCOME_BLOCKED, null, null);
        AUDIT.warn("event=LOGIN_BLOCKED_ATTEMPT username={} ip={} endpoint={}",
                mask(username), ip, endpoint);
    }

    /**
     * Records a failed authentication attempt and evaluates every scope threshold.
     *
     * @param rawUsername submitted username ({@code null} for username-less endpoints)
     * @param reason      server-side failure category — never returned to the client
     * @param clientId    organisation of the account when known, else {@code null}
     * @return the delay to apply before responding, and whether a block was triggered
     */
    @Transactional
    public FailureOutcome recordFailure(HttpServletRequest req,
                                        String rawUsername,
                                        String reason,
                                        String clientId,
                                        String endpoint) {
        if (!props.isEnabled()) return FailureOutcome.none();

        String username = normaliseUsername(rawUsername);
        String ip       = clientIp(req);
        LocalDateTime now = LocalDateTime.now();

        save(req, username, ip, endpoint, LoginAttemptLog.OUTCOME_FAILURE, reason, clientId);
        AUDIT.warn("event=AUTH_FAILURE username={} ip={} endpoint={} reason={}",
                mask(username), ip, endpoint, reason);

        LocalDateTime windowStart = now.minus(props.getWindow());
        // Account-scoped counters restart after a successful login; the IP counter does not.
        LocalDateTime accountCutoff = username == null
                ? windowStart
                : latest(windowStart, attemptRepo.lastSuccessOrResetAt(username));

        boolean blocked = false;
        long userIpFailures = 0;

        // ── Scope 1: username + IP — the primary control ──────────────────────
        if (username != null) {
            userIpFailures = attemptRepo.countUserIpFailuresSince(username, ip, accountCutoff);
            if (userIpFailures >= props.getMaxFailedAttempts()) {
                blocked |= openBlock(LoginBlock.SCOPE_USER_IP, userIpKey(username, ip), username, ip,
                        clientId, (int) userIpFailures, props.getBlockDuration(), true, now);
            }
        }

        // ── Scope 2: IP — one host spraying many usernames ────────────────────
        if (props.isIpScopeEnabled() && !ClientIpResolver.UNKNOWN_IP.equals(ip)) {
            long ipFailures = attemptRepo.countIpFailuresSince(ip, windowStart);
            if (ipFailures >= props.getIpMaxFailedAttempts()) {
                blocked |= openBlock(LoginBlock.SCOPE_IP, ipKey(ip), null, ip,
                        null, (int) ipFailures, props.getIpBlockDuration(), true, now);
            }
        }

        // ── Scope 3: username from anywhere — distributed-attack backstop ─────
        if (props.isAccountScopeEnabled() && username != null) {
            long accountFailures = attemptRepo.countUsernameFailuresSince(username, accountCutoff);
            if (accountFailures >= props.getAccountMaxFailedAttempts()) {
                // Fixed length, no escalation: this is the only scope a third party can
                // trip for someone else, so its worst case stays a short, bounded delay.
                blocked |= openBlock(LoginBlock.SCOPE_USERNAME, usernameKey(username), username, null,
                        clientId, (int) accountFailures, props.getAccountBlockDuration(), false, now);
            }
        }

        long delay = blocked ? 0L : progressiveDelayMillis(userIpFailures);
        return new FailureOutcome(delay, blocked);
    }

    /**
     * Records an attempt where the password was <em>correct</em> but the account was
     * refused on policy grounds (inactive, disabled, lapsed subscription).
     *
     * <p>Audited, never counted. Counting it would let a user whose subscription has
     * expired throttle themselves out by retrying a password that is in fact right, and
     * it carries no brute-force signal — whoever sent it already knows the password.
     */
    @Transactional
    public void recordDenied(HttpServletRequest req, String rawUsername,
                             String reason, String clientId, String endpoint) {
        if (!props.isEnabled()) return;
        String username = normaliseUsername(rawUsername);
        String ip       = clientIp(req);

        save(req, username, ip, endpoint, LoginAttemptLog.OUTCOME_DENIED, reason, clientId);
        AUDIT.warn("event=AUTH_DENIED username={} ip={} endpoint={} reason={}",
                mask(username), ip, endpoint, reason);
    }

    /**
     * Records a successful authentication, which resets the account-scoped counters.
     *
     * <p>The IP-scoped counter is intentionally left alone. If a successful login also
     * cleared it, an attacker holding one valid account on a host could sign in every
     * few attempts and spray other usernames from the same address indefinitely.
     */
    @Transactional
    public void recordSuccess(HttpServletRequest req, String rawUsername, String clientId, String endpoint) {
        recordSuccess(req, rawUsername, clientId, endpoint, AuthDetails.empty());
    }

    /**
     * Records a successful authentication together with its audit detail (church, role,
     * location). Writing the SUCCESS row is what resets the account-scoped counters, so
     * this must be called on every successful sign-in.
     *
     * <p>The human-readable audit line is emitted by {@link SecurityAuditService}, not
     * here — this method owns the durable database record.
     */
    @Transactional
    public void recordSuccess(HttpServletRequest req, String rawUsername, String clientId,
                              String endpoint, AuthDetails details) {
        if (!props.isEnabled()) return;

        String username = normaliseUsername(rawUsername);
        String ip       = clientIp(req);

        save(req, username, ip, endpoint, LoginAttemptLog.OUTCOME_SUCCESS, null, clientId, details);
    }

    /**
     * Records a sign-out for the audit trail.
     *
     * <p>Deliberately moves no counter in either direction. Logging out is neither evidence
     * of an attack nor evidence of a legitimate credential, and letting it reset anything
     * would hand an attacker a free way to clear state.
     */
    @Transactional
    public void recordLogout(HttpServletRequest req, String rawUsername, String clientId,
                             String endpoint, AuthDetails details) {
        if (!props.isEnabled()) return;

        String username = normaliseUsername(rawUsername);
        String ip       = clientIp(req);

        save(req, username, ip, endpoint, LoginAttemptLog.OUTCOME_LOGOUT, null, clientId, details);
    }

    /**
     * Clears the account-scoped failure counters and releases any active
     * username-bearing block, after the owner has proven control of the account
     * (currently: completing a password reset with an emailed single-use token).
     *
     * <p>This is the self-service escape hatch that makes the account-scope block safe:
     * even in the worst case a victim is never waiting on an administrator.
     */
    @Transactional
    public void clearAccountCounters(String rawUsername, String clientId, String reason) {
        if (!props.isEnabled()) return;

        String username = normaliseUsername(rawUsername);
        if (username == null) return;

        LoginAttemptLog marker = new LoginAttemptLog();
        marker.setUsername(username);
        marker.setIpAddress(null);
        marker.setEndpoint(reason);
        marker.setOutcome(LoginAttemptLog.OUTCOME_RESET);
        marker.setClientId(clientId);
        attemptRepo.save(marker);

        int released = blockRepo.releaseActiveBlocksForUsername(username, LocalDateTime.now());
        AUDIT.info("event=LOGIN_COUNTERS_RESET username={} reason={} blocksReleased={}",
                mask(username), reason, released);
    }

    /**
     * Sleeps for the progressive delay computed by {@link #recordFailure}. Call this
     * outside any transaction, immediately before writing the response.
     */
    public void applyProgressiveDelay(FailureOutcome outcome) {
        if (outcome == null || outcome.delayMillis() <= 0) return;
        try {
            Thread.sleep(outcome.delayMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Housekeeping ──────────────────────────────────────────────────────────

    /**
     * Purges attempt and expired-block rows past the retention period, so the tables
     * stay small and no login history is kept longer than the policy allows.
     */
    @Scheduled(cron = "${security.login-protection.purge-cron:0 20 3 * * *}")
    @Transactional
    public void purgeOldRecords() {
        if (!props.isEnabled()) return;
        LocalDateTime cutoff = LocalDateTime.now().minus(props.getRetention());
        int attempts = attemptRepo.deleteOlderThan(cutoff);
        int blocks   = blockRepo.deleteExpiredBefore(cutoff);
        if (attempts > 0 || blocks > 0) {
            LOG.info("Login-protection purge removed {} attempt row(s) and {} expired block row(s) older than {}",
                    attempts, blocks, cutoff);
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * Opens a temporary block for {@code scopeKey} unless one is already running.
     *
     * @param escalate when {@code true}, each earlier block inside the escalation window
     *                 multiplies the duration by {@code blockEscalationFactor}, capped by
     *                 {@code maxBlockDuration}
     * @return {@code true} if a new block row was written
     */
    private boolean openBlock(String scope,
                              String scopeKey,
                              String username,
                              String ip,
                              String clientId,
                              int failureCount,
                              Duration baseDuration,
                              boolean escalate,
                              LocalDateTime now) {

        if (!blockRepo.findActive(List.of(scopeKey), now).isEmpty()) {
            return false;   // already blocked — do not extend it for free
        }

        int step = 0;
        Duration duration = baseDuration;
        if (escalate) {
            step = (int) Math.min(blockRepo.countBlocksSince(scopeKey, now.minus(props.getEscalationWindow())),
                                  16L);   // clamp before exponentiating
            duration = escalated(baseDuration, step);
        }

        LoginBlock block = new LoginBlock();
        block.setScope(scope);
        block.setScopeKey(scopeKey);
        block.setUsername(username);
        block.setIpAddress(ip);
        block.setClientId(clientId);
        block.setBlockedAt(now);
        block.setBlockedUntil(now.plus(duration));
        block.setFailureCount(failureCount);
        block.setBlockSeconds((int) duration.toSeconds());
        block.setEscalationStep(step);
        blockRepo.save(block);

        AUDIT.warn("event=LOGIN_BLOCK_STARTED scope={} username={} ip={} failures={} seconds={} step={} until={}",
                scope, mask(username), ip, failureCount, duration.toSeconds(), step, block.getBlockedUntil());
        return true;
    }

    /** {@code base * factor^step}, capped at {@code maxBlockDuration}. */
    private Duration escalated(Duration base, int step) {
        long factor = Math.max(1, props.getBlockEscalationFactor());
        long seconds = base.toSeconds();
        for (int i = 0; i < step; i++) {
            if (seconds > props.getMaxBlockDuration().toSeconds() / factor) {
                seconds = props.getMaxBlockDuration().toSeconds();
                break;
            }
            seconds *= factor;
        }
        return Duration.ofSeconds(Math.min(seconds, props.getMaxBlockDuration().toSeconds()));
    }

    /**
     * Delay for the current failure count: nothing for the first
     * {@code progressiveDelayAfterAttempts} failures, then {@code step} per extra
     * failure, capped at {@code progressiveDelayMax}.
     */
    private long progressiveDelayMillis(long failuresSoFar) {
        if (!props.isProgressiveDelayEnabled()) return 0L;
        long over = failuresSoFar - props.getProgressiveDelayAfterAttempts();
        if (over <= 0) return 0L;
        long millis = over * props.getProgressiveDelayStep().toMillis();
        return Math.min(millis, props.getProgressiveDelayMax().toMillis());
    }

    private void save(HttpServletRequest req,
                      String username,
                      String ip,
                      String endpoint,
                      String outcome,
                      String reason,
                      String clientId) {
        save(req, username, ip, endpoint, outcome, reason, clientId, AuthDetails.empty());
    }

    private void save(HttpServletRequest req,
                      String username,
                      String ip,
                      String endpoint,
                      String outcome,
                      String reason,
                      String clientId,
                      AuthDetails details) {
        AuthDetails d = details != null ? details : AuthDetails.empty();

        LoginAttemptLog row = new LoginAttemptLog();
        row.setUsername(username);
        row.setIpAddress(ip);
        // Hash, never the raw session id: it is a bearer credential, and one leaked from a
        // log file or a database read is enough to impersonate the user.
        row.setSessionHash(d.sessionHash() != null
                ? d.sessionHash()
                : ClientIpResolver.sessionHash(req));
        row.setDeviceHash(ClientIpResolver.deviceHash(req));
        row.setEndpoint(endpoint);
        row.setOutcome(outcome);
        row.setFailureReason(reason);
        row.setClientId(clientId);
        row.setChurchName(d.churchName());
        row.setUserRole(d.role());
        row.setCity(d.city());
        row.setRegion(d.region());
        row.setCountry(d.country());
        attemptRepo.save(row);
    }

    private List<String> scopeKeys(String username, String ip) {
        List<String> keys = new ArrayList<>(3);
        boolean haveIp = !ClientIpResolver.UNKNOWN_IP.equals(ip);
        if (username != null && haveIp)                       keys.add(userIpKey(username, ip));
        if (props.isIpScopeEnabled() && haveIp)               keys.add(ipKey(ip));
        if (props.isAccountScopeEnabled() && username != null) keys.add(usernameKey(username));
        return keys;
    }

    private static String userIpKey(String username, String ip) {
        return LoginBlock.SCOPE_USER_IP + ":" + username + "|" + ip;
    }

    private static String ipKey(String ip) {
        return LoginBlock.SCOPE_IP + ":" + ip;
    }

    private static String usernameKey(String username) {
        return LoginBlock.SCOPE_USERNAME + ":" + username;
    }

    /** Resolves the client address honouring the configured proxy-trust policy. */
    public String clientIp(HttpServletRequest req) {
        return ClientIpResolver.resolve(req, props.isTrustForwardedHeaders());
    }

    /**
     * Lower-cases and bounds the submitted username so it is a stable, safe key.
     * Usernames are matched case-insensitively throughout the app, so the same
     * normalisation must be applied here or "John" and "john" would get separate
     * budgets. Over-long input is hashed rather than truncated, so two different
     * long usernames can never collide onto one counter.
     */
    static String normaliseUsername(String raw) {
        if (raw == null) return null;
        String u = raw.trim().toLowerCase();
        if (u.isEmpty()) return null;
        return u.length() <= 180 ? u : "sha256:" + ClientIpResolver.sha256Hex(u);
    }

    /** Later of two timestamps; {@code b} may be {@code null}. */
    private static LocalDateTime latest(LocalDateTime a, LocalDateTime b) {
        return (b != null && b.isAfter(a)) ? b : a;
    }

    /** Usernames go to the audit log verbatim; {@code null} is rendered explicitly. */
    private static String mask(String username) {
        return username == null ? "-" : username;
    }
}
