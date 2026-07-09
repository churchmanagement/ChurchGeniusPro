package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Target-shaped staging row for the {@code income} import. {@code family_link}
 * holds the SOURCE family key; at load time it is resolved to a live
 * family_member id within the same tenant via the per-run link map (see the ETL
 * design doc, §10). An unresolvable link is a validation ERROR — children are
 * never silently attached to the wrong family. Tenant-scoped.
 */
@Data
@Entity
@Table(name = "staging_income",
       indexes = {
           @Index(name = "ix_stg_income_run",    columnList = "run_id, row_status"),
           @Index(name = "ix_stg_income_tenant", columnList = "client_id")
       })
public class StagingIncome {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "run_id", nullable = false)        private Long runId;
    @Column(name = "client_id", nullable = false, length = 64) private String clientId;
    @Column(name = "source_row_id", length = 128)     private String sourceRowId;
    @Column(name = "source_payload", columnDefinition = "TEXT", nullable = false) private String sourcePayload;

    /** SOURCE family key — resolved to a tenant family_member id at load time. */
    @Column(name = "family_link", length = 128)       private String familyLink;

    // ── Target-shaped columns ──
    @Column(name = "amount", precision = 14, scale = 2) private BigDecimal amount;
    @Column(name = "income_date")                       private LocalDate incomeDate;
    @Column(name = "source_name",    length = 128)      private String sourceName;
    @Column(name = "sub_source_name",length = 128)      private String subSourceName;
    @Column(name = "purpose_name",   length = 128)      private String purposeName;
    @Column(name = "method",         length = 64)       private String method;
    @Column(name = "reference_no",   length = 128)      private String referenceNo;
    @Column(name = "notes",          columnDefinition = "TEXT") private String notes;

    // ── Pipeline bookkeeping ──
    @Column(name = "row_status", nullable = false, length = 16) private String rowStatus = "PENDING";
    @Column(name = "validation_msgs", columnDefinition = "TEXT") private String validationMsgs;
    @Column(name = "dedupe_match_id") private Long dedupeMatchId;
    @Column(name = "dedupe_action", length = 16) private String dedupeAction;
    @Column(name = "resolved_member_id") private Long resolvedMemberId;   // family_link → live id
    @Column(name = "target_id") private Long targetId;
    /** JSON snapshot of the live row BEFORE an UPDATE load (for rollback). Null for INSERTs. */
    @Column(name = "before_image", columnDefinition = "TEXT") private String beforeImage;
    @Column(name = "batch_id")  private Long batchId;

    @Column(name = "created_date", nullable = false, updatable = false) private LocalDateTime createdDate;

    @PrePersist
    void onCreate() { if (createdDate == null) createdDate = LocalDateTime.now(); }
}
