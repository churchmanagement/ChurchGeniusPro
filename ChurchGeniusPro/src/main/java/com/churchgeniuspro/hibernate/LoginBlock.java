package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A <b>temporary</b> login block.
 *
 * <p>Every block carries an explicit {@link #blockedUntil} timestamp and expires on
 * its own — there is deliberately no "locked" flag and no state that has to be
 * cleared by an administrator. It is therefore impossible for an attacker to put an
 * account into a permanently unusable state (the account-lockout denial-of-service
 * described in the ChurchGeniusPro login-security requirements).
 *
 * <p>Rows are append-only: a new row is written each time a threshold trips, and the
 * historic rows double as the escalation history used to lengthen repeat blocks.
 *
 * <h3>Scopes</h3>
 * <ul>
 *   <li>{@link #SCOPE_USER_IP} — {@code username + IP}. The primary control. An
 *       attacker hammering a known username from their own machine blocks only
 *       <em>their own</em> username+IP pair; the real account holder, coming from a
 *       different address, is unaffected.</li>
 *   <li>{@link #SCOPE_IP} — IP address only. Stops one host spraying many usernames.
 *       Never reset by a successful login (see {@code LoginProtectionService}).</li>
 *   <li>{@link #SCOPE_USERNAME} — username only, with a much higher threshold. A
 *       distributed-attack backstop; short, fixed duration and clearable by the real
 *       owner completing a password reset.</li>
 * </ul>
 *
 * @see LoginAttemptLog
 */
@Data
@Entity
@Table(name = "login_block")
public class LoginBlock {

    public static final String SCOPE_USER_IP  = "USER_IP";
    public static final String SCOPE_IP       = "IP";
    public static final String SCOPE_USERNAME = "USERNAME";

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "login_block_seq")
    @SequenceGenerator(name = "login_block_seq", sequenceName = "login_block_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** One of {@code USER_IP}, {@code IP}, {@code USERNAME}. */
    @Column(name = "scope", nullable = false, length = 20)
    private String scope;

    /**
     * Fully-qualified key, {@code "<scope>:<value>"} — e.g. {@code "USER_IP:john|203.0.113.7"}.
     * Prefixing with the scope keeps keys unique across scopes so an active-block
     * lookup for a request is a single indexed {@code IN} query.
     */
    @Column(name = "scope_key", nullable = false, length = 255)
    private String scopeKey;

    /** Username the block relates to ({@code null} for pure IP-scope blocks). */
    @Column(name = "username", length = 200)
    private String username;

    /** IP the block relates to ({@code null} for pure username-scope blocks). */
    @Column(name = "ip_address", length = 100)
    private String ipAddress;

    /** Organisation of the account, when resolvable — for per-tenant security reporting. */
    @Column(name = "client_id", length = 100)
    private String clientId;

    @Column(name = "blocked_at", nullable = false)
    private LocalDateTime blockedAt;

    /** Hard expiry. The block is inert from this instant on; no cleanup is required. */
    @Column(name = "blocked_until", nullable = false)
    private LocalDateTime blockedUntil;

    /** Failures counted in the window that tripped this block (for the audit trail). */
    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    /** Effective duration in seconds, after progressive escalation. */
    @Column(name = "block_seconds", nullable = false)
    private int blockSeconds;

    /** How many earlier blocks for this key were counted when escalating (0 = first). */
    @Column(name = "escalation_step", nullable = false)
    private int escalationStep;

    @PrePersist
    protected void onCreate() {
        if (this.blockedAt == null) this.blockedAt = LocalDateTime.now();
    }
}
