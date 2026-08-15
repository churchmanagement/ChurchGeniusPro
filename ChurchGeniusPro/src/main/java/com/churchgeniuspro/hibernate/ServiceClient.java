package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Represents a client (church representative) registered by the Service Admin.
 * Maps to the {@code service_client} table (auto-created by Hibernate DDL update).
 */
@Entity
@Table(name = "service_client")
@Data
public class ServiceClient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** Formatted sequence: CGP-XXXXX.  Set by service after initial save. */
    @Column(name = "client_id")
    private String clientId;

    // ── Contact ──────────────────────────────────────────────────────────────

    private String name;

    @Column(name = "church_name")
    private String churchName;

    private String email;
    private String phone;

    // ── Address ──────────────────────────────────────────────────────────────

    @Column(name = "address_line1")
    private String addressLine1;

    @Column(name = "address_line2")
    private String addressLine2;

    private String city;
    private String state;

    @Column(length = 100)
    private String country = "USA";

    @Column(name = "pin_code")
    private String pinCode;

    // ── Website & Social Media (optional) ─────────────────────────────────────
    @Column(name = "website_url")   private String websiteUrl;
    @Column(name = "facebook_url")  private String facebookUrl;
    @Column(name = "instagram_url") private String instagramUrl;
    @Column(name = "youtube_url")   private String youtubeUrl;

    // ── Subscription / Active Period ─────────────────────────────────────────

    @Column(name = "active_period")
    private Integer activePeriod;

    /** MONTHS or YEARS */
    @Column(name = "active_period_unit", length = 10)
    private String activePeriodUnit = "MONTHS";

    @Column(name = "start_date")
    private LocalDate startDate;

    /** Calculated automatically as startDate + activePeriod (in activePeriodUnit). */
    @Column(name = "end_date")
    private LocalDate endDate;

    /** NOT_REQUIRED | PENDING | PAID */
    @Column(name = "payment_status", length = 20)
    private String paymentStatus = "PENDING";

    /** FREE | LIMITED | FULL */
    @Column(name = "subscription_type", length = 20)
    private String subscriptionType = "FREE";

    /**
     * Additional SMS credits granted to THIS client on top of its subscription
     * plan's monthly allowance (effective limit = plan limit + this value).
     * Configured per client by the Service Admin; defaults to 0.
     */
    @Column(name = "extra_sms_count", nullable = false,
            columnDefinition = "integer not null default 0")
    private Integer extraSmsCount = 0;

    @Column(columnDefinition = "text")
    private String note;

    /** Active | Hold | Inactive */
    @Column(length = 20)
    private String status = "Active";

    // ── Workflow flags ────────────────────────────────────────────────────────

    @Column(nullable = false, columnDefinition = "boolean default false")
    private Boolean approved = false;

    @Column(name = "delete_flag", nullable = false, columnDefinition = "boolean default false")
    private Boolean deleteFlag = false;

    @Column(name = "created_date")
    private LocalDateTime createdDate;

    @PrePersist
    public void prePersist() {
        if (createdDate == null) {
            createdDate = LocalDateTime.now();
        }
    }
}
