package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

/**
 * One schema-mapping decision: how a TARGET column is filled from the SOURCE.
 * Produced by the deterministic matcher (and AI fallback) in Phase 3; persisted
 * here with a confidence score and review status so the mapping screen can show
 * and the operator can confirm/edit it (see the ETL design doc, §5).
 *
 * <p>Unique per (run, target table, target column).
 */
@Data
@Entity
@Table(name = "mapping_rule",
       uniqueConstraints = @UniqueConstraint(name = "uq_mapping_rule_target",
               columnNames = {"run_id", "target_table", "target_column"}))
public class MappingRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "target_table", nullable = false, length = 32)
    private String targetTable;

    /** e.g. firstName */
    @Column(name = "target_column", nullable = false, length = 64)
    private String targetColumn;

    @Column(name = "source_table", length = 128)
    private String sourceTable;

    /** e.g. first_name */
    @Column(name = "source_column", length = 128)
    private String sourceColumn;

    /** whitelisted transform name, e.g. trim | titlecase | parse_date | cents_to_dollars */
    @Column(name = "transform", length = 64)
    private String transform;

    /** 0.000 – 1.000 */
    @Column(name = "confidence", precision = 4, scale = 3)
    private java.math.BigDecimal confidence;

    /** AUTO | NEEDS_REVIEW | CONFIRMED | REJECTED */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** Why this mapping was suggested (deterministic reason or AI rationale). */
    @Column(name = "rationale", columnDefinition = "TEXT")
    private String rationale;

    @Column(name = "decided_by", length = 128)
    private String decidedBy;

    /** Tenant column (H2): backfilled by W4 from the parent; set on create by the owning
     *  service/controller. Nullable for now — flipped to NOT NULL once every create-path
     *  is deployed (see db/window/W4_tenant_columns.sql). */
    @Column(name = "client_id")
    private String clientId;
}
