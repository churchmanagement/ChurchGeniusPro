package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Per-column profile of an extracted source table (ETL Phase 2). One row per
 * (run, source_table, column): inferred type, null rate, distinct/total counts,
 * value-length range, and a small JSON sample of values (see the ETL design doc,
 * §3 stage 2). The mapping phase consumes these to suggest target columns and
 * transforms. Tenant-scoped; derived purely from {@code staging_raw} — no live
 * data is touched.
 */
@Data
@Entity
@Table(name = "import_source_profile",
       indexes = {
           @Index(name = "ix_src_profile_run",   columnList = "run_id"),
           @Index(name = "ix_src_profile_table", columnList = "run_id, source_table")
       },
       uniqueConstraints = @UniqueConstraint(name = "uq_src_profile_col",
               columnNames = {"run_id", "source_table", "column_name"}))
public class SourceColumnProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "run_id", nullable = false)         private Long runId;
    @Column(name = "client_id", nullable = false, length = 64) private String clientId;
    @Column(name = "source_table", nullable = false, length = 128) private String sourceTable;
    @Column(name = "column_name", nullable = false, length = 255)  private String columnName;

    /** 0-based position of the column in the source. */
    @Column(name = "ordinal") private Integer ordinal;

    /** STRING | INTEGER | DECIMAL | DATE | BOOLEAN | EMAIL | PHONE | EMPTY | MIXED */
    @Column(name = "inferred_type", length = 16) private String inferredType;

    @Column(name = "total_count")     private Integer totalCount;
    @Column(name = "non_null_count")  private Integer nonNullCount;
    @Column(name = "null_count")      private Integer nullCount;
    @Column(name = "distinct_count")  private Integer distinctCount;

    /** nullCount / totalCount, 0.000–1.000. */
    @Column(name = "null_rate", precision = 5, scale = 4) private BigDecimal nullRate;

    @Column(name = "min_length") private Integer minLength;
    @Column(name = "max_length") private Integer maxLength;

    /** JSON array of up to N example non-null values. */
    @Column(name = "sample_values", columnDefinition = "TEXT") private String sampleValues;

    @Column(name = "created_date", nullable = false, updatable = false) private LocalDateTime createdDate;

    @PrePersist
    void onCreate() { if (createdDate == null) createdDate = LocalDateTime.now(); }
}
