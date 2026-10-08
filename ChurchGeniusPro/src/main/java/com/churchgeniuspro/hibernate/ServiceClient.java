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

    /**
     * Random token in the church-owner registration link. Minted at approve /
     * re-approve, so a fresh approval invalidates the previous link. Replaces
     * AES(clientId), which anyone with the key could mint for any tenant.
     */
    @Column(name = "registration_token", unique = true, length = 64)
    private String registrationToken;

    /**
     * Random token in the "Request a subscription" link sent with trial reminder
     * emails ({@code /subscriptionReq.html?t=…}). Stored as issued so every reminder
     * can carry the same working link (as registration and public-screen tokens are);
     * it only lets someone submit a subscription request for this one church. Issued
     * and renewed by {@code SubscriptionRequestService}. Never sent to the browser.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name = "subscription_request_token", unique = true, length = 64)
    private String subscriptionRequestToken;

    /** Last day the subscription-request link works (60 days after the trial end date when issued). */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name = "subscription_request_token_expires")
    private java.time.LocalDate subscriptionRequestTokenExpires;

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

    /**
     * The plan code (FREE, STANDARD, PRO, TRIAL or any custom plan). Legacy rows may
     * still hold LIMITED / FULL, which {@code SubscriptionService.toPlanCode} maps to
     * STANDARD / PRO. Length matches {@code subscription_plan.plan_code} (V7 widens
     * the existing column from 20).
     */
    @Column(name = "subscription_type", length = 40)
    private String subscriptionType = "FREE";

    // ── Billing (what THIS client pays) ──────────────────────────────────────
    // Written only by SubscriptionLifecycleService. The price is copied from the
    // plan's monthly or yearly list price when the plan is assigned, or entered by
    // the Service Admin as a negotiated price; a later change to the plan's list
    // price never alters it. Next billing date = endDate.

    /** MONTHLY | YEARLY; null on rows created before billing existed (= MONTHLY). */
    @Column(name = "billing_frequency", length = 10)
    private String billingFrequency;

    /** The client's actual price per billing period, USD. Null = not set. */
    @Column(name = "subscription_price", precision = 10, scale = 2)
    private java.math.BigDecimal subscriptionPrice;

    /** True when subscriptionPrice is a negotiated price rather than the plan's list price. */
    @Column(name = "price_overridden")
    private Boolean priceOverridden;

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

    /**
     * The Stripe Customer ({@code cus_…}) created in the ChurchGeniusPro billing Stripe
     * account the first time this client pays a platform invoice online — Stripe requires
     * one for bank transfers. An identifier, not a credential; null until first use.
     */
    @Column(name = "billing_stripe_customer_id", length = 100)
    private String billingStripeCustomerId;

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
