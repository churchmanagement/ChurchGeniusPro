package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 2026-11-09 — one login's own warning thresholds for new Income or Expense entries (user spec "Income &amp; Expense
 * Alerts, Validation, and AI Entry Confirmation"). One row per church + login + role + kind, so a person who works in
 * two roles keeps two sets and nobody's settings affect anyone else. No row = no alerts. A threshold is only used when
 * its switch is on; alerts are warnings the user can confirm, never blockers.
 */
@Entity
@Table(name = "entry_alert_setting",
       uniqueConstraints = @UniqueConstraint(name = "ux_entry_alert_login_role_kind",
                                             columnNames = {"app_client_id", "username", "role", "kind"}))
@Getter @Setter
public class EntryAlertSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_client_id", nullable = false, length = 100)
    private String appClientId;

    /** The sign-in name of the login (session username). */
    @Column(name = "username", nullable = false, length = 150)
    private String username;

    /** The role the login was using when it saved these settings (SuperAdmin, Admin, Accountant …). */
    @Column(name = "role", nullable = false, length = 40)
    private String role;

    /** INCOME or EXPENSE. */
    @Column(name = "kind", nullable = false, length = 10)
    private String kind;

    @Column(name = "app_user_id")
    private Integer appUserId;

    @Column(name = "future_enabled", nullable = false)
    private boolean futureEnabled;
    @Column(name = "future_days")
    private Integer futureDays;

    @Column(name = "past_enabled", nullable = false)
    private boolean pastEnabled;
    @Column(name = "past_days")
    private Integer pastDays;

    @Column(name = "max_enabled", nullable = false)
    private boolean maxEnabled;
    @Column(name = "max_amount", precision = 15, scale = 2)
    private BigDecimal maxAmount;

    @Column(name = "min_enabled", nullable = false)
    private boolean minEnabled;
    @Column(name = "min_amount", precision = 15, scale = 2)
    private BigDecimal minAmount;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
