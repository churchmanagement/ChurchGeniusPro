package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Records the result of each backup execution.
 */
@Data
@Entity
@Table(name = "backup_log")
public class BackupLog {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "backup_log_seq")
    @SequenceGenerator(name = "backup_log_seq", sequenceName = "backup_log_id_seq", allocationSize = 1)
    private Integer id;

    @Column(name = "run_at", nullable = false)
    private LocalDateTime runAt;

    /** "SUCCESS" or "FAILURE". */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** Tables backed up (e.g. "ALL", "FAMILY", "INCOME"). */
    @Column(name = "table_scope", length = 20)
    private String tableScope;

    /** Human-readable summary or error message. */
    @Column(name = "message", length = 4000)
    private String message;

    /** Comma-separated list of notification emails that were notified. */
    @Column(name = "notified_emails", length = 2000)
    private String notifiedEmails;
}
