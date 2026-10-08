package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Append-only audit log of authentication attempts.
 *
 * <p>Serves two purposes:
 * <ol>
 *   <li><b>Security monitoring</b> — every login attempt (success or failure) is
 *       recorded with timestamp, username identifier, IP address, session id and a
 *       device fingerprint hash. <b>Passwords are never stored or logged here.</b></li>
 *   <li><b>Rate limiting</b> — {@code com.churchgeniuspro.service.LoginProtectionService}
 *       counts rows in this table to decide when to apply a progressive delay or a
 *       temporary block. Because the table is append-only and lives in PostgreSQL,
 *       the counters are shared by every application instance (no in-memory state).</li>
 * </ol>
 *
 * <p>Rows are purged after {@code security.login-protection.retention} by the
 * scheduled cleanup in {@code LoginProtectionService}.
 *
 * @see LoginBlock
 */
@Data
@Entity
@Table(name = "login_attempt_log")
public class LoginAttemptLog {

    /** Attempt succeeded — clears the account-scoped failure counters. */
    public static final String OUTCOME_SUCCESS = "SUCCESS";
    /** Attempt failed — counts towards every scope's threshold. */
    public static final String OUTCOME_FAILURE = "FAILURE";
    /** Attempt was refused because a temporary block was already active. */
    public static final String OUTCOME_BLOCKED = "BLOCKED";
    /**
     * Correct password, but the account was refused on policy grounds (inactive,
     * disabled, subscription lapsed). Audited but never counted towards any
     * brute-force threshold — the credential was, after all, correct.
     */
    public static final String OUTCOME_DENIED  = "DENIED";
    /**
     * Administrative / self-service reset of the account counters (e.g. after a
     * successful password reset). Treated like {@link #OUTCOME_SUCCESS} when
     * computing the account-scoped counting cutoff.
     */
    public static final String OUTCOME_RESET   = "RESET";
    /**
     * The user signed out. Recorded for the audit trail only — it is not a failure and
     * not a success, so it moves no rate-limit counter.
     */
    public static final String OUTCOME_LOGOUT  = "LOGOUT";

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "login_attempt_log_seq")
    @SequenceGenerator(name = "login_attempt_log_seq", sequenceName = "login_attempt_log_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /**
     * The submitted username, normalised to lower case (usernames are matched
     * case-insensitively everywhere in the app). {@code null} for endpoints that
     * do not carry a username (badge / NFC / service-admin logins), which are
     * rate-limited by IP only.
     */
    @Column(name = "username", length = 200)
    private String username;

    /** Client IP address as resolved by {@code util/ClientIpResolver}. */
    @Column(name = "ip_address", length = 100)
    private String ipAddress;

    /**
     * SHA-256 (truncated) of the HTTP session id — enough to correlate a login with its
     * matching logout, useless to anyone who obtains it.
     *
     * <p>The raw session id is deliberately never stored or logged: it is a bearer
     * credential, and anyone who reads it out of a log file or this table can replay it to
     * impersonate the user for the life of the session.
     *
     * <p>Never used on its own as a rate-limit key either — a client controls its own
     * session id and could rotate it freely.
     */
    @Column(name = "session_hash", length = 64)
    private String sessionHash;

    /**
     * SHA-256 (hex) of the User-Agent header — a coarse device signal that is
     * useful when reviewing an incident but is not personally identifying on its own.
     */
    @Column(name = "device_hash", length = 64)
    private String deviceHash;

    /** Request path the attempt was made against, e.g. {@code /login}. */
    @Column(name = "endpoint", length = 120)
    private String endpoint;

    /** One of {@code SUCCESS}, {@code FAILURE}, {@code BLOCKED}, {@code RESET}. */
    @Column(name = "outcome", nullable = false, length = 20)
    private String outcome;

    /**
     * Coarse, <b>server-side only</b> failure category (e.g. {@code UNKNOWN_USER},
     * {@code BAD_PASSWORD}, {@code ACCOUNT_INACTIVE}). This is deliberately never
     * echoed to the client — the API always returns the same generic message so
     * the response cannot be used to enumerate accounts.
     */
    @Column(name = "failure_reason", length = 40)
    private String failureReason;

    /**
     * Organisation the username belongs to, when it could be resolved. Lets a
     * tenant's security report be filtered to its own accounts. {@code null} for
     * attempts against usernames that do not exist.
     */
    @Column(name = "client_id", length = 100)
    private String clientId;

    /**
     * Church the account belongs to, captured at the time of the event so the audit trail
     * still reads correctly after a church is renamed.
     */
    @Column(name = "church_name", length = 200)
    private String churchName;

    /** Role held at the time of the event (church / Admin / Member / …). */
    @Column(name = "user_role", length = 60)
    private String userRole;

    // ── Approximate location of the client IP (MaxMind GeoLite2, local lookup) ────
    // A hint, not a fact: VPNs and carrier NAT routinely misplace a user. Useful for
    // spotting "signed in from three countries in an hour", not for proving whereabouts.

    @Column(name = "city", length = 120)
    private String city;

    /** State in the US; province or county elsewhere. */
    @Column(name = "region", length = 120)
    private String region;

    @Column(name = "country", length = 80)
    private String country;

    @Column(name = "attempted_at", nullable = false)
    private LocalDateTime attemptedAt;

    @PrePersist
    protected void onCreate() {
        if (this.attemptedAt == null) this.attemptedAt = LocalDateTime.now();
    }
}
