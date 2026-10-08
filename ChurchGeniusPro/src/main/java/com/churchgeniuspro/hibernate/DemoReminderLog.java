package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One row per reminder actually sent, so a reminder fires once and not daily.
 *
 * <p>{@code for_end_date} is part of the identity on purpose: if a role's expiry
 * is moved, its reminders become eligible again against the NEW date rather than
 * being suppressed by what was sent for the old one.
 */
@Data
@Entity
@Table(name = "demo_reminder_log",
       uniqueConstraints = @UniqueConstraint(
           name = "uk_demo_reminder_once",
           columnNames = {"role_access_id", "days_before", "for_end_date"}))
public class DemoReminderLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "role_access_id", nullable = false)
    private Long roleAccessId;

    @Column(name = "days_before", nullable = false)
    private Integer daysBefore;

    /** The end date this reminder was calculated from. */
    @Column(name = "for_end_date", nullable = false)
    private LocalDate forEndDate;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    /** Whether real delivery happened, or it was recorded as blocked/in-app only. */
    @Column(name = "delivery", length = 40)
    private String delivery;

    /** Tenant column (H2): backfilled by W4 from the parent; set on create by the owning
     *  service/controller. Nullable for now — flipped to NOT NULL once every create-path
     *  is deployed (see db/window/W4_tenant_columns.sql). */
    @Column(name = "client_id")
    private String clientId;
}
