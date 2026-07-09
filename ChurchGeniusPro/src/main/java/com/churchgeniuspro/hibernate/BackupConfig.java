package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

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
