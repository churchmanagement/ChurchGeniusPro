package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.util.Date;

/**
 * Immutable audit record capturing a user's acceptance of a legal/compliance
 * policy (Terms of Service, Privacy Policy, Cookie Policy, etc.).
 *
 * <p>One row is written each time a policy is accepted. Records are never updated
 * or deleted — re-acceptance (for example after a version change) writes a new row.
 * The {@code policyVersion} ties each acceptance to the exact document version that
 * was in effect at the time, which supports version tracking for future updates.
 */
@Data
@Entity
@Table(name = "policy_acceptance")
public class PolicyAcceptance {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "policy_acceptance_seq")
    @SequenceGenerator(name = "policy_acceptance_seq",
            sequenceName = "policy_acceptance_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Tenant / organization identifier (client_id) the acceptance belongs to. */
    @Column(name = "client_id")
    private String clientId;

    /** Username (or email) of the person who accepted, when known. */
    @Column(name = "username")
    private String username;

    /** Policy key: terms | privacy | cookie | acceptable-use | refund. */
    @Column(name = "policy_type", nullable = false)
    private String policyType;

    /** Version string of the policy that was accepted (e.g. "1.0"). */
    @Column(name = "policy_version")
    private String policyVersion;

    /** Always true for stored rows; present for explicit auditing. */
    @Column(name = "accepted")
    private Boolean accepted;

    /** Timestamp the acceptance was recorded (UTC server time). */
    @Column(name = "accepted_at")
    private Date acceptedAt;

    /** Best-effort client IP captured at acceptance time. */
    @Column(name = "ip_address")
    private String ipAddress;

    /** Best-effort browser user-agent captured at acceptance time. */
    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    /** Where the acceptance happened: registration | cookie-banner | reaccept. */
    @Column(name = "source")
    private String source;

    @PrePersist
    public void onCreate() {
        if (this.acceptedAt == null) this.acceptedAt = new Date();
        if (this.accepted == null)   this.accepted   = Boolean.TRUE;
    }
}
