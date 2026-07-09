package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Raw captured source rows — one row per source record, per source table, stored
 * verbatim as JSON. This is the immutable record of exactly what was extracted,
 * before any mapping/transform (see the ETL design doc, §3 stage 1). Used for
 * traceability and re-staging after a mapping change. Tenant-scoped.
 */
@Data
@Entity
@Table(name = "staging_raw")
public class StagingRaw {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "client_id", nullable = false, length = 64)
    private String clientId;

    /** Source table the row came from. */
    @Column(name = "source_table", length = 128)
    private String sourceTable;

    /** Natural key / locator in the source, for end-to-end traceability. */
    @Column(name = "source_row_id", length = 128)
    private String sourceRowId;

    /** The exact source row as a JSON object string. */
    @Column(name = "payload", columnDefinition = "TEXT", nullable = false)
    private String payload;

    @Column(name = "created_date", nullable = false, updatable = false)
    private LocalDateTime createdDate;

    @PrePersist
    void onCreate() {
        if (createdDate == null) createdDate = LocalDateTime.now();
    }
}
