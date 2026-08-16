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
 * Monthly usage counters per church, used to enforce subscription plan
 * limits (emails, SMS, online giving). One row per clientId per calendar
 * month ({@code usageMonth} = "YYYY-MM"); counters reset naturally when a
 * new month starts because a fresh row is created.
 */
@Data
@Entity
@Table(name = "subscription_usage",
       uniqueConstraints = @UniqueConstraint(
               name = "uq_subscription_usage",
               columnNames = {"client_id", "usage_month"}))
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

    /** Calendar month, e.g. "2026-07". */
    @Column(name = "usage_month", nullable = false, length = 7)
    private String usageMonth;

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
