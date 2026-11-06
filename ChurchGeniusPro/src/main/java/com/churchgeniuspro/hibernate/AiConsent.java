package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * App Store readiness D6 (2026-11-06): one person's answer to one AI data-sharing notice.
 *
 * <p>Keyed by the INDIVIDUAL login ({@code signup.id}) inside a church — staff, Member Portal
 * and church-owner logins alike — so nobody can agree on behalf of another login. One row per
 * (church, login, notice type): agreeing stores the version agreed to and clears a withdrawal;
 * withdrawing stamps {@code withdrawn_at}. A consent is valid only while it is not withdrawn
 * and its version equals the current notice version. No question, recording or document is
 * ever stored here. Each agree / withdraw also writes one {@code ai_audit_log} row (dated
 * history).
 */
@Data
@Entity
@Table(name = "ai_consent", indexes = {
        @Index(name = "ux_ai_consent_login_type", columnList = "app_client_id, login_id, notice_type", unique = true)
})
public class AiConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The church (tenant) of the session. */
    @Column(name = "app_client_id", nullable = false, length = 100)
    private String appClientId;

    /** {@code signup.id} of the individual login that answered. */
    @Column(name = "login_id", nullable = false)
    private Integer loginId;

    /** STAFF · MEMBER · CHURCH — informational only; the key is the login id. */
    @Column(name = "login_kind", nullable = false, length = 10)
    private String loginKind;

    /** VOICE · ASSISTANT · SCAN. */
    @Column(name = "notice_type", nullable = false, length = 12)
    private String noticeType;

    @Column(name = "notice_version", nullable = false)
    private Integer noticeVersion;

    @Column(name = "agreed_at")
    private LocalDateTime agreedAt;

    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    /** IOS · ANDROID (the app the answer came from). */
    @Column(name = "source", length = 10)
    private String source;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
