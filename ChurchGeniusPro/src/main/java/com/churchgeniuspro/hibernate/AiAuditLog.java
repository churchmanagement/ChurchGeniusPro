package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Append-only record of every AI request (AI Phase 0, 2026-11-04): who asked what kind
 * of thing, for which church, through which channel, what it cost and how it ended.
 *
 * <p>Deliberately holds <b>no question text, no answer and no personal data</b> beyond the
 * signed-in username — the row is for accountability and metering (which competitors are
 * judged on), not for reading people's questions. Written once per request by
 * {@code AiGuardFilter}; the services only annotate the request with an intent and the
 * {@code LlmGateway} with model/tokens. Tenant-scoped by {@code client_id}; purged after
 * 90 days by {@code AuditTableRetentionService}.
 */
@Data
@Entity
@Table(name = "ai_audit_log", indexes = {
        @Index(name = "idx_ai_audit_client_time", columnList = "client_id, created_at DESC, id DESC"),
        @Index(name = "idx_ai_audit_client_day_actor", columnList = "client_id, created_at, actor")
})
public class AiAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /** Session username ("temp:<id>" for a badge session). */
    @Column(name = "actor", length = 150)
    private String actor;

    @Column(name = "role", length = 40)
    private String role;

    /** SEARCH · DATA · CONFIRM · ASSIST · VOICE · VISION · HELP · MCP … (see {@code AiChannel}). */
    @Column(name = "channel", nullable = false, length = 20)
    private String channel;

    /** Request path without query string, e.g. {@code /api/ai-search/data}. */
    @Column(name = "path", length = 160)
    private String path;

    /** What the service decided it was asked (e.g. {@code crossref.notInGroup}); never the question. */
    @Column(name = "intent", length = 80)
    private String intent;

    /** Model echoed by the provider, null for deterministic answers. */
    @Column(name = "model", length = 60)
    private String model;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    /** HTTP status the request ended with. */
    @Column(name = "http_status")
    private Integer httpStatus;

    /** OK · DENIED · RATE_LIMITED · ERROR. */
    @Column(name = "outcome", nullable = false, length = 20)
    private String outcome;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}
