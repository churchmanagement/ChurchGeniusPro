package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ETL Migration Phase 4 — one staged row of a "record" data type (pledge campaigns,
 * pledges, events, event registrations, attendance, groups, group memberships, Kids
 * Ministry children and check-ins). Same bookkeeping as {@link StagingFamily} /
 * {@link StagingIncome}, but the mapped values live in {@code values_json} (typed per
 * {@link com.churchgeniuspro.util.EtlTargetSchema}) instead of one column per field, so a
 * new data type needs no new staging table. family / income / expense are unchanged.
 *
 * <p>Tenant-bound: {@code client_id} is copied from the run, never from the file.
 */
@Data
@Entity
@Table(name = "staging_record",
       indexes = { @Index(name = "ix_staging_record_run", columnList = "run_id, target_table"),
                   @Index(name = "ix_staging_record_batch", columnList = "batch_id") })
public class StagingRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "run_id", nullable = false)                  private Long runId;
    @Column(name = "client_id", nullable = false, length = 64)   private String clientId;
    @Column(name = "target_table", nullable = false, length = 32) private String targetTable;

    /** The source product's own id for this row (or the row ordinal when the file has none). */
    @Column(name = "source_row_id", length = 128)                private String sourceRowId;
    @Column(name = "source_payload", columnDefinition = "TEXT", nullable = false) private String sourcePayload;

    /** Mapped, transformed values: {column → value} (dates ISO, money as plain decimal strings). */
    @Column(name = "values_json", columnDefinition = "TEXT")      private String valuesJson;
    /**
     * How the row's references resolved at validation: {"person": "m12" | "s<key>",
     * "parent": "l<live id>" | "s<staging id>" | "new:<name>", "classroom": …}. Read again at load.
     */
    @Column(name = "links_json", columnDefinition = "TEXT")       private String linksJson;

    /** PENDING | VALID | WARN | ERROR | REVIEW | APPROVED | LOADED | SKIPPED | FAILED | ROLLED_BACK */
    @Column(name = "row_status", nullable = false, length = 16)   private String rowStatus = "PENDING";
    @Column(name = "validation_msgs", columnDefinition = "TEXT")  private String validationMsgs;
    @Column(name = "dedupe_match_id")                            private Long dedupeMatchId;
    @Column(name = "dedupe_action", length = 16)                 private String dedupeAction;
    @Column(name = "match_tier", length = 16)                    private String matchTier;
    @Column(name = "match_detail", length = 255)                 private String matchDetail;

    @Column(name = "target_id")                                  private Long targetId;
    @Column(name = "before_image", columnDefinition = "TEXT")     private String beforeImage;
    @Column(name = "batch_id")                                   private Long batchId;

    @Column(name = "created_date", nullable = false, updatable = false) private LocalDateTime createdDate;

    @PrePersist
    void onCreate() { if (createdDate == null) createdDate = LocalDateTime.now(); }
}
