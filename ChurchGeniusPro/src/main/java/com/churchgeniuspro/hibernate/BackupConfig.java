package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Stores the database-backup schedule configuration set by the Service Admin.
 *
 * <p>Only one row is expected per deployment (id = 1), but the table is
 * generic enough to hold per-client configs in the future.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code intervalMonths} — backup frequency in months (1, 3, 6, 12).
 *       {@code 0} means disabled.</li>
 *   <li>{@code tableScope}     — "ALL", "FAMILY", or "INCOME".</li>
 *   <li>{@code retentionMonths} — how long snapshots are kept before automatic
 *       deletion. {@code 0} means keep forever.</li>
 *   <li>{@code notifyEmails}   — comma-separated list of recipient emails.</li>
 *   <li>{@code nextRunDate}    — next scheduled backup date (recalculated on save).</li>
 *   <li>{@code lastRunDate}    — date the most recent backup actually ran.</li>
 *   <li>{@code updatedAt}      — timestamp of last config change.</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "backup_config")
public class BackupConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "backup_config_seq")
    @SequenceGenerator(name = "backup_config_seq", sequenceName = "backup_config_id_seq", allocationSize = 1)
    private Integer id;

    /** 0 = disabled; 1/3/6/12 = interval in months. */
    @Column(name = "interval_months", nullable = false)
    private int intervalMonths = 0;

    /** "ALL", "FAMILY", or "INCOME". */
    @Column(name = "table_scope", nullable = false, length = 20)
    private String tableScope = "ALL";

    /**
     * How long a snapshot is kept before it is dropped automatically, in months.
     * {@code 0} disables deletion entirely (keep forever).
     *
     * <p>Stored in months rather than as a number+unit pair so the arithmetic is
     * unambiguous: "1 year" is simply 12, and the UI renders whichever label reads
     * better. Defaults to 12 months.
     *
     * <p>{@code @ColumnDefault} is load-bearing, not decoration. With
     * {@code ddl-auto=update} Hibernate adds this column to an existing
     * {@code backup_config} table, and that table always has a row — adding a
     * NOT NULL column with no default to a non-empty table is rejected by
     * PostgreSQL ("contains null values"), the ALTER is logged as a WARN and
     * skipped, and every subsequent read of the entity then fails because the
     * column does not exist. Emitting {@code default 12} makes the ALTER
     * succeed and backfills existing rows with the same value
     * migrate_production.sql uses.
     */
    @Column(name = "retention_months", nullable = false)
    @ColumnDefault("12")
    private int retentionMonths = 12;

    /** Comma-separated notification email addresses. */
    @Column(name = "notify_emails", length = 2000)
    private String notifyEmails;

    /** Date the next backup should run (null when disabled). */
    @Column(name = "next_run_date")
    private LocalDate nextRunDate;

    /** Date the last backup ran (null if never). */
    @Column(name = "last_run_date")
    private LocalDate lastRunDate;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
