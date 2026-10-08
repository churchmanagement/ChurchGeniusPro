package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;

import java.util.Date;

/**
 * A configurable subscription plan (Free / Standard / Pro / future plans),
 * managed by the Service Admin. Every church (identified by
 * {@code service_client.client_id}) is linked to a plan via
 * {@code ServiceClient.subscriptionType} — legacy values map
 * FREE→FREE, LIMITED→STANDARD, FULL→PRO; new values may name a
 * {@link #planCode} directly. All users under the same clientId inherit the
 * plan's limits and feature flags (resolved by {@code SubscriptionService}).
 *
 * <p>Numeric limits use {@code null} to mean <b>unlimited</b>.
 * Feature flags live in {@link #featuresJson} as a {@code {"key":boolean}}
 * map; a missing key means <b>enabled</b> (opt-in denial, same philosophy as
 * RoleGuard permissions) so newly introduced features default on.
 */
@Data
@Entity
@Table(name = "subscription_plan")
public class SubscriptionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "subscription_plan_seq")
    @SequenceGenerator(
            name           = "subscription_plan_seq",
            sequenceName   = "subscription_plan_id_seq",
            allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Stable identifier referenced by clients: FREE, STANDARD, PRO, or custom. */
    @Column(name = "plan_code", nullable = false, unique = true, length = 40)
    private String planCode;

    @Column(name = "plan_name", nullable = false, length = 100)
    private String planName;

    /** Marketing/summary description shown in the admin UI. */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    // ── Limits (null = unlimited) ─────────────────────────────────────────

    @Column(name = "max_people")                  private Integer maxPeople;
    @Column(name = "max_emails_per_month")        private Integer maxEmailsPerMonth;
    /** Extra SMS credits are configured PER CLIENT (ServiceClient.extraSmsCount), not on the plan. */
    @Column(name = "max_sms_per_month")           private Integer maxSmsPerMonth;
    @Column(name = "max_online_giving_per_month") private Integer maxOnlineGivingPerMonth;
    @Column(name = "max_member_portals")          private Integer maxMemberPortals;
    @Column(name = "max_kids_portals")            private Integer maxKidsPortals;
    /**
     * Staff users the church may add on /viewusers (non-deleted app_user rows).
     * Null = unlimited, so every existing plan keeps its behaviour until one is set.
     */
    @Column(name = "max_staff_users")             private Integer maxStaffUsers;
    /**
     * Bank accounts the church may have connected through Bank Sync, counted as
     * individual {@code plaid_account} rows (one Plaid connection can add several).
     * Null = unlimited, 0 = none. Downgrading never disconnects anything: a church
     * over the limit keeps its accounts and simply cannot add more.
     */
    @Column(name = "max_bank_accounts")           private Integer maxBankAccounts;

    // ── Commercial ────────────────────────────────────────────────────────
    /** Monthly price in USD; null is treated as $0/month everywhere it is shown. */
    @Column(name = "monthly_price", precision = 10, scale = 2)
    private java.math.BigDecimal monthlyPrice;

    /**
     * Yearly price in USD. Optional: null means the plan is not offered on yearly
     * billing (it is never derived from the monthly price). A client's actual price
     * is copied from here or from {@link #monthlyPrice} when the plan is assigned —
     * see {@code SubscriptionLifecycleService} — so changing it later never alters
     * what an existing client pays.
     */
    @Column(name = "yearly_price", precision = 10, scale = 2)
    private java.math.BigDecimal yearlyPrice;

    /**
     * Length of a trial started on this plan, in days. Only meaningful on the TRIAL
     * plan; {@code TrialPolicy} reads it and is the single source of truth for every
     * trial-creating flow. Null falls back to {@code TrialPolicy.FALLBACK_DAYS}.
     */
    @Column(name = "trial_days")
    private Integer trialDays;

    // ── Feature flags ─────────────────────────────────────────────────────

    /** JSON map of feature key → enabled, e.g. {"accounting":false,"payroll":false}. */
    @Column(name = "features_json", columnDefinition = "TEXT")
    private String featuresJson;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    @PrePersist
    protected void onCreate() {
        if (this.createdDate == null) this.createdDate = new Date();
    }
}
