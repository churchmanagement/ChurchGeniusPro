package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.util.Date;

/**
 * Usage counters per church for one 30-day usage period, used to enforce the
 * plan's monthly allowances (emails, SMS, online giving).
 *
 * <p>One row per clientId per period ({@code periodStart}). Periods run every
 * 30 days from the client's subscription start date (America/Chicago dates) —
 * see {@code SubscriptionService.periodStartFor}. A new period simply gets a new
 * row, so the allowance resets without deleting anything; old rows are history.
 * Rows from before 30-day periods (V10) carry the first day of their calendar
 * month as {@code periodStart}. {@code usageMonth} ("YYYY-MM" of the period start)
 * is kept for history and reports only.
 */
@Data
@Entity
@Table(name = "subscription_usage",
       uniqueConstraints = @UniqueConstraint(
               name = "uq_subscription_usage_period",
               columnNames = {"client_id", "period_start"}))
public class SubscriptionUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "subscription_usage_seq")
    @SequenceGenerator(
            name           = "subscription_usage_seq",
            sequenceName   = "subscription_usage_id_seq",
            allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /** "YYYY-MM" of {@link #periodStart}; history only (was the key before V10). */
    @Column(name = "usage_month", nullable = false, length = 7)
    private String usageMonth;

    /** First day of the 30-day usage period this row counts. */
    @Column(name = "period_start", nullable = false)
    private java.time.LocalDate periodStart;

    @Column(name = "emails_sent", nullable = false)
    private int emailsSent;

    @Column(name = "sms_sent", nullable = false)
    private int smsSent;

    @Column(name = "giving_count", nullable = false)
    private int givingCount;

    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    @PrePersist
    protected void onCreate() {
        if (this.createdDate == null) this.createdDate = new Date();
    }
}
