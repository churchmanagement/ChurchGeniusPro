package com.churchgeniuspro.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Tunables for the failed-login protection layer, bound from
 * {@code security.login-protection.*}.
 *
 * <p>Every threshold and duration lives here so the policy can be re-tuned from
 * {@code application.properties} (or an environment variable) without touching code.
 * Defaults implement the policy agreed for ChurchGeniusPro:
 *
 * <pre>
 *   1–2 failures          → generic error, no delay
 *   3rd failure onwards   → progressive server-side delay (400ms, 800ms, … capped at 2s)
 *   5 failures / 15 min   → 15-minute temporary block for that username+IP pair
 *   repeat blocks         → 15m → 30m → 60m → 120m (capped)
 *   20 failures / 15 min  → 15-minute block for that IP (username spraying)
 *   20 failures / 15 min  → 15-minute block for that username from ANY IP
 *                           (distributed-attack backstop; fixed length, never escalates,
 *                            and cleared the moment the owner completes a password reset)
 *   successful login      → account-scoped counters reset (IP counter is NOT reset)
 * </pre>
 *
 * @see com.churchgeniuspro.service.LoginProtectionService
 */
@Data
@Component
@ConfigurationProperties(prefix = "security.login-protection")
public class LoginProtectionProperties {

    /** Master switch. When {@code false} the limiter records nothing and blocks nothing. */
    private boolean enabled = true;

    // ── Rolling window used by every counter ──────────────────────────────────

    /**
     * Rolling window over which failures are counted. A failure older than this no
     * longer counts towards any threshold.
     */
    private Duration window = Duration.ofMinutes(15);

    // ── Scope: username + IP (primary control) ────────────────────────────────

    /** Failures from one username+IP pair that trip a temporary block. */
    private int maxFailedAttempts = 5;

    /** Initial temporary block length for a username+IP pair. */
    private Duration blockDuration = Duration.ofMinutes(15);

    /** Upper bound on an escalated block. Nothing is ever blocked for longer than this. */
    private Duration maxBlockDuration = Duration.ofHours(2);

    /** Each repeat block inside {@link #escalationWindow} multiplies the duration by this factor. */
    private int blockEscalationFactor = 2;

    /** How far back repeat blocks are counted when escalating. */
    private Duration escalationWindow = Duration.ofHours(24);

    // ── Scope: IP address ─────────────────────────────────────────────────────

    /** Set {@code false} to disable IP-scope blocking (e.g. an intranet behind one NAT address). */
    private boolean ipScopeEnabled = true;

    /** Failures from one IP across all usernames that trip an IP-scope block. */
    private int ipMaxFailedAttempts = 20;

    /** Temporary block length for an IP-scope block (escalates like the username+IP scope). */
    private Duration ipBlockDuration = Duration.ofMinutes(15);

    // ── Scope: username only (distributed-attack backstop) ────────────────────

    /**
     * Set {@code false} for zero account-scoped denial-of-service surface: an attacker
     * would then only ever be able to block their own username+IP pair. The trade-off is
     * that a botnet spread over many source addresses is slowed but not blocked.
     */
    private boolean accountScopeEnabled = true;

    /** Failures against one username from any IP that trip an account-scope block. */
    private int accountMaxFailedAttempts = 20;

    /**
     * Account-scope block length. Deliberately short and <b>non-escalating</b> — this is
     * the only scope an attacker can trip for someone else, so it stays strictly bounded.
     */
    private Duration accountBlockDuration = Duration.ofMinutes(15);

    // ── Progressive delay ─────────────────────────────────────────────────────

    /** Enable the server-side slow-down applied before the hard block threshold. */
    private boolean progressiveDelayEnabled = true;

    /** Number of failures that may occur before delays start being applied. */
    private int progressiveDelayAfterAttempts = 2;

    /** Delay added per failure beyond {@link #progressiveDelayAfterAttempts}. */
    private Duration progressiveDelayStep = Duration.ofMillis(400);

    /**
     * Hard cap on the injected delay. Keep this small: the delay occupies a servlet
     * thread, so a large value turns the limiter into its own resource-exhaustion vector.
     */
    private Duration progressiveDelayMax = Duration.ofSeconds(2);

    // ── Password reset nudge ──────────────────────────────────────────────────

    /**
     * After this many blocks for the same key inside {@link #escalationWindow}, the
     * generic "too many attempts" message additionally steers the user to a password
     * reset. The threshold itself is never disclosed.
     */
    private int suggestPasswordResetAfterBlocks = 2;

    /**
     * Minimum gap between password-reset emails for the same account. Stops someone who
     * knows a username from flooding that person's inbox by hammering
     * {@code /api/forgot-password}.
     *
     * <p>This can never lock anyone out: inside the cooldown the previously emailed link
     * is still valid, and the API still returns the same generic response, so the
     * cooldown is invisible to an attacker probing for account existence.
     */
    private Duration passwordResetRequestCooldown = Duration.ofMinutes(2);

    // ── Client IP resolution ──────────────────────────────────────────────────

    /**
     * Whether to read the client address from {@code X-Forwarded-For} /
     * {@code X-Real-IP} rather than the socket address.
     *
     * <p><b>Deployment-critical.</b> Behind a reverse proxy or load balancer this must be
     * {@code true} (matching the rest of the codebase), and the proxy must
     * <em>overwrite</em> — not append to — the incoming header; otherwise a client can
     * forge the header and side-step the IP and username+IP scopes. Running with
     * {@code true} while directly internet-facing means the header is fully
     * attacker-controlled. Running with {@code false} behind a proxy makes every user
     * share the proxy's address, so the IP scope will punish legitimate traffic.
     */
    private boolean trustForwardedHeaders = true;

    // ── Housekeeping ──────────────────────────────────────────────────────────

    /** How long attempt/block rows are kept for security review before purging. */
    private Duration retention = Duration.ofDays(30);

    /** Cron expression for the retention purge (default: 03:20 daily). */
    private String purgeCron = "0 20 3 * * *";
}
