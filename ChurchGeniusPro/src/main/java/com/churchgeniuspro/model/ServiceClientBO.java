package com.churchgeniuspro.model;

import lombok.Data;

/**
 * Request / response DTO for Service Client registration and updates.
 */
@Data
public class ServiceClientBO {

    private Integer id;

    // ── Contact ──────────────────────────────────────────────────────────────
    private String name;
    private String churchName;
    private String email;
    private String phone;

    // ── Address ──────────────────────────────────────────────────────────────
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String country;
    private String pinCode;

    // ── Website & Social Media ───────────────────────────────────────────────
    private String websiteUrl;
    private String facebookUrl;
    private String instagramUrl;
    private String youtubeUrl;

    // ── Subscription ─────────────────────────────────────────────────────────
    private Integer activePeriod;
    /** MONTHS or YEARS */
    private String activePeriodUnit;
    /** ISO-8601 date string (yyyy-MM-dd) */
    private String startDate;

    /** NOT_REQUIRED | PENDING | PAID */
    private String paymentStatus;
    /** FREE | LIMITED | FULL */
    private String subscriptionType;
    /** Per-client extra SMS credits on top of the plan's monthly allowance (default 0). */
    private Integer extraSmsCount;

    // ── Billing (optional; null = keep the current value) ───────────────────
    /** MONTHLY | YEARLY */
    private String billingFrequency;
    /** Negotiated price per billing period; used only when priceOverridden is true. */
    private String subscriptionPrice;
    /** True = use subscriptionPrice as a custom price; false = the plan's list price. */
    private Boolean priceOverridden;
    /** True = re-copy the plan's current list price even if plan and frequency are unchanged. */
    private Boolean resetPriceToPlan;

    /**
     * Must be true to save a CHANGED start date (Edit Client): the admin has confirmed
     * that it moves the end date and re-anchors the 30-day usage period.
     */
    private Boolean confirmStartDateChange;

    // ── Misc ─────────────────────────────────────────────────────────────────
    private String note;
    /** Active | Hold | Inactive */
    private String status;

    /**
     * Edit Client, when the plan or billing frequency of a client with a discount is
     * changing: KEEP (only when the discount is still eligible) or CANCEL. Sent only
     * after the server asked (409 PROMOTION_DECISION_REQUIRED).
     */
    private String promotionDecision;
    /** The reason, required with promotionDecision = CANCEL. */
    private String promotionCancelReason;
}
