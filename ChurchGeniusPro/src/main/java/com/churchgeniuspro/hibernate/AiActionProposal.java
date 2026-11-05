package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI Phase 4 (2026-11-05): one proposed write action of the AI action agent — "add Sarah Lee to Welcome Team".
 * Nothing is executed until the proposer confirms with the one-time token (PROPOSED → EXECUTED), and the
 * confirmation re-checks permission, tenant and every referenced row. Terminal states: EXECUTED, REJECTED,
 * EXPIRED, FAILED. Params/result are the tool's own JSON (ids and labels — no free text beyond what the staff
 * member typed). Tenant-scoped by {@code app_client_id}.
 */
@Data
@Entity
@Table(name = "ai_action_proposal", indexes = {
        @Index(name = "idx_ai_action_client_status", columnList = "app_client_id, status, expires_at"),
        @Index(name = "idx_ai_action_token", columnList = "token")
})
public class AiActionProposal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_client_id", nullable = false, length = 100)
    private String appClientId;

    @Column(name = "username", nullable = false, length = 150)
    private String username;

    @Column(name = "tool", nullable = false, length = 40)
    private String tool;

    @Column(name = "params_json", columnDefinition = "TEXT")
    private String paramsJson;

    /** The sentence the staff member confirmed ("Add Sarah Lee to the group Welcome Team"). */
    @Column(name = "summary", length = 500)
    private String summary;

    /** PROPOSED · EXECUTED · REJECTED · EXPIRED · FAILED. */
    @Column(name = "status", nullable = false, length = 12)
    private String status;

    /** One-time confirmation token; cleared once decided. */
    @Column(name = "token", length = 64)
    private String token;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /** How the proposal was made: TEXT (typed / deterministic), LLM (tool-call fallback), VOICE. */
    @Column(name = "source", length = 10)
    private String source;

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}
