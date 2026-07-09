package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One ETL import attempt, bound to exactly ONE tenant ({@code client_id}).
 *
 * <p>The {@code client_id} is chosen by a Service Admin when the run is created
 * and is the only tenant this run may ever stage or load into — it is never
 * derived from the imported source data. Every staging row and audit record
 * created under this run copies this {@code client_id}, giving structural
 * cross-tenant isolation (see the ETL design doc, §2).
 *
 * <p>Phase 1 scope: lifecycle + tenant binding + audit only. No live writes.
 */
@Data
@Entity
@Table(name = "import_run")
public class ImportRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** The ONLY tenant this run may write to. */
    @Column(name = "client_id", nullable = false, length = 64)
    private String clientId;

    /** Service-admin operator who created the run. */
    @Column(name = "created_by", nullable = false, length = 128)
    private String createdBy;

    /** Human label for the source, e.g. "LegacyChMS export 2026-06". */
    @Column(name = "source_label", length = 256)
    private String sourceLabel;

    /**
     * Lifecycle status. Values: DRAFT, PROFILED, MAPPED, STAGED, VALIDATED,
     * APPROVED, LOADED, ROLLED_BACK, DISCARDED, FAILED.
     * Managed exclusively by {@code ImportRunService}.
     */
    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "created_date", nullable = false, updatable = false)
    private LocalDateTime createdDate;

    @Column(name = "updated_date")
    private LocalDateTime updatedDate;

    @PrePersist
    void onCreate() {
        if (createdDate == null) createdDate = LocalDateTime.now();
        updatedDate = createdDate;
    }

    @PreUpdate
    void onUpdate() {
        updatedDate = LocalDateTime.now();
    }
}
