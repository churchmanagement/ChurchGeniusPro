package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ETL Migration Phase 3 — the persistent resolver: one row per
 * "<source system>'s id for <entity> → our live id" learned by an import, plus
 * one row per reference record (fund, purpose, payment method) an import had to
 * create. Tenant-bound like everything else in the pipeline.
 *
 * <p>Why a table of its own when {@code family_member.source_id} already exists:
 * links outlive a single table (households, gifts, funds), every later run of the
 * same church resolves "Person 1042" with one indexed lookup instead of scanning
 * members, and a run's rollback can delete exactly the links (and only the
 * reference rows) that run created — which is what makes "rolled back" true.
 *
 * <p>{@code entity} values: {@code person}, {@code household}, {@code income},
 * {@code expense} (live rows) and {@code ref:sub_source}, {@code ref:main_source},
 * {@code ref:purpose}, {@code ref:transaction_type} (reference rows created by the
 * load — {@code source_key} is the name as the file spelled it).
 */
@Data
@Entity
@Table(name = "import_link",
       uniqueConstraints = @UniqueConstraint(name = "uq_import_link", columnNames = {"client_id", "source_system", "entity", "source_key"}),
       indexes = { @Index(name = "ix_import_link_run", columnList = "run_id"),
                   @Index(name = "ix_import_link_target", columnList = "client_id, entity, target_id") })
public class ImportLink {

    public static final String PERSON = "person", HOUSEHOLD = "household", INCOME = "income", EXPENSE = "expense";
    public static final String REF_SUB_SOURCE = "ref:sub_source", REF_MAIN_SOURCE = "ref:main_source",
                               REF_PURPOSE = "ref:purpose", REF_TRANSACTION_TYPE = "ref:transaction_type",
                               // Phase 4: a group / Kids Ministry classroom an import created because a membership or child named it
                               REF_GROUP = "ref:group", REF_KM_CLASSROOM = "ref:km_classroom";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 64)
    private String clientId;

    /** Preset code of the source product (GENERIC when the file came from nowhere in particular). */
    @Column(name = "source_system", nullable = false, length = 32)
    private String sourceSystem;

    @Column(name = "entity", nullable = false, length = 32)
    private String entity;

    /** The source product's own id (or, for a reference row, the name as spelled in the file). */
    @Column(name = "source_key", nullable = false, length = 160)
    private String sourceKey;

    /** Our live id (family_member.id, family.id, income.id, expense.id, sub_source.id, …). */
    @Column(name = "target_id", nullable = false)
    private Long targetId;

    /** Run and batch that learned/created this link — deleted with the batch's rollback. */
    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "batch_id")
    private Long batchId;

    @Column(name = "created_date", nullable = false, updatable = false)
    private LocalDateTime createdDate;

    @PrePersist
    void onCreate() {
        if (createdDate == null) createdDate = LocalDateTime.now();
    }
}
