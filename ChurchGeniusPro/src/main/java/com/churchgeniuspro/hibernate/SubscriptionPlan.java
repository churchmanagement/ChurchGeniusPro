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
