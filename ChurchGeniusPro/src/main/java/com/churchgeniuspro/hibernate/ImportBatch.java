package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A single load attempt within a run, scoped to ONE target table. A run loads
 * {@code family}, then {@code income}, then {@code expense} as separate batches,
 * each independently reversible by {@code batch_id} (see the ETL design doc, §9).
 *
 * <p>Phase 1 scope: the entity + counters exist; the loader/rollback are Phase 6.
 */
@Data
@Entity
@Table(name = "import_batch")
public class ImportBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    /** Copied from the parent run; the tenant this batch may write to. */
    @Column(name = "client_id", nullable = false, length = 64)
    private String clientId;

    /** family | income | expense */
    @Column(name = "target_table", nullable = false, length = 32)
    private String targetTable;

    /** STAGED | APPROVED | LOADED | ROLLED_BACK | FAILED */
    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "approved_by", length = 128)
    private String approvedBy;

    @Column(name = "approved_date")
    private LocalDateTime approvedDate;

    @Column(name = "inserted_count") private Integer insertedCount = 0;
    @Column(name = "updated_count")  private Integer updatedCount  = 0;
    @Column(name = "skipped_count")  private Integer skippedCount  = 0;
    @Column(name = "failed_count")   private Integer failedCount   = 0;

    @Column(name = "created_date", nullable = false, updatable = false)
    private LocalDateTime createdDate;

    @PrePersist
    void onCreate() {
        if (createdDate == null) createdDate = LocalDateTime.now();
    }
}
