package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Append-only audit trail for the Help Center AI assistant and article views.
 *
 * <p>Records every assistant query (text or voice), article view, and
 * permission-denied request so administrators can review how help is used and
 * which restricted features were requested. Tenant-scoped by {@code client_id}.
 */
@Data
@Entity
@Table(name = "help_audit_log")
public class HelpAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /** Username / display name of the requester. */
    @Column(name = "actor", length = 150)
    private String actor;

    @Column(name = "role", length = 40)
    private String role;

    /** QUERY (text), VOICE (spoken), VIEW (article opened), DENIED (restricted feature). */
    @Column(name = "kind", length = 20)
    private String kind;

    @Column(name = "query", columnDefinition = "TEXT")
    private String query;

    @Column(name = "article_id", length = 80)
    private String articleId;

    @Column(name = "perm_key", length = 80)
    private String permKey;

    @Column(name = "answer_summary", columnDefinition = "TEXT")
    private String answerSummary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { this.createdAt = LocalDateTime.now(); }
}
