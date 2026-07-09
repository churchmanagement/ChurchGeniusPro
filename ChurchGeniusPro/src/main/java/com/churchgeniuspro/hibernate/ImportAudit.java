package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Append-only audit of every meaningful ETL action (stage transitions, mapping
 * confirmations, approvals, loads, rollbacks). Mirrors the spirit of the app's
 * existing {@code AccessAudit}. Never updated or deleted (see design doc, §11).
 *
 * <p>{@code detail} holds a JSON string (counts, params, error summaries). Stored
 * as TEXT for portability; can be migrated to JSONB later without code changes.
 */
@Data
@Entity
@Table(name = "import_audit")
public class ImportAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "batch_id")
    private Long batchId;

    @Column(name = "client_id", nullable = false, length = 64)
    private String clientId;

    @Column(name = "actor", nullable = false, length = 128)
    private String actor;

    /** EXTRACT | MAP_SUGGEST | MAP_CONFIRM | VALIDATE | PREVIEW | APPROVE | LOAD | ROLLBACK | STATUS | ... */
    @Column(name = "action", nullable = false, length = 48)
    private String action;

    /** JSON string with action-specific detail. */
    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Column(name = "created_date", nullable = false, updatable = false)
    private LocalDateTime createdDate;

    @PrePersist
    void onCreate() {
        if (createdDate == null) createdDate = LocalDateTime.now();
    }
}
