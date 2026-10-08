package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Per-role access window for a demo/test tenant credential.
 *
 * <p>Demo credentials come from three different places — the church signup row,
 * {@code app_user} staff rows and {@code family_member} portal rows — so there is
 * no single table to hang an expiry date on. This table is that place: one row per
 * demo login, keyed by the stable {@code signup.id}, carrying the dates and the
 * block flag that {@code LoginController} enforces.
 *
 * <p>Keyed by signup id rather than username on purpose: Reset issues a NEW
 * username, and an access window that survives a reset has to outlive the name.
 */
@Data
@Entity
@Table(name = "demo_role_access",
       uniqueConstraints = @UniqueConstraint(name = "uk_demo_role_signup", columnNames = "signup_id"))
public class DemoRoleAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** The signup row that owns this login. Stable across username changes. */
    @Column(name = "signup_id", nullable = false)
    private Integer signupId;

    @Column(name = "client_id", length = 100, nullable = false)
    private String clientId;

    /** Current username, denormalised so the admin list needs no extra join. */
    @Column(name = "username", length = 150)
    private String username;

    /** Church / Staff / Member Portal / Child Portal — display only. */
    @Column(name = "role_label", length = 60)
    private String roleLabel;

    @Column(name = "member_name", length = 200)
    private String memberName;

    @Column(name = "start_date")
    private LocalDate startDate;

    /**
     * Last day this login works. Checked at login; the role is refused from the
     * day AFTER this date, so an end date of today is still a usable day.
     */
    @Column(name = "end_date")
    private LocalDate endDate;

    /**
     * Manual block, independent of the date. Lets a service admin cut off one
     * role immediately without moving its end date backwards.
     *
     * <p>{@code @ColumnDefault} is load-bearing: ddl-auto cannot add a NOT NULL
     * column to a table that already has rows without one.
     */
    @Column(name = "blocked", nullable = false)
    @ColumnDefault("false")
    private Boolean blocked = Boolean.FALSE;

    /** Bumped by Reset, so the admin can see when the login was last reissued. */
    @Column(name = "last_reset_at")
    private LocalDateTime lastResetAt;

    /**
     * When the person behind this login accepted the trial-account agreement.
     *
     * <p>Null means "not yet accepted" — the acknowledgement popup is shown on the
     * next sign-in and the application is gated until they click OK. Nullable on
     * purpose: ddl-auto adds a nullable column to a populated table without a
     * default, and "never accepted" is exactly what an existing demo row means.
     *
     * <p>Cleared by {@code reissue()}: Reset hands out a NEW username and a NEW
     * end date, so the person holding it has not agreed to that window yet.
     */
    @Column(name = "agreement_accepted_at")
    private LocalDateTime agreementAcceptedAt;

    /**
     * When the person behind this login finished — or skipped — the product tour.
     *
     * <p>Null means "not shown yet", which is what every existing row means, so
     * nothing had to be back-filled. Recorded per LOGIN rather than per session,
     * the same way the agreement above is, so the tour appears once and signing in
     * again does not replay it.
     *
     * <p>Cleared by {@code reissue()} for the same reason the acceptance is: a
     * Reset hands the credential to a new person, who has not seen the tour.
     */
    @Column(name = "product_tour_at")
    private LocalDateTime productTourAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** ACTIVE / EXPIRED / BLOCKED — derived, never stored, so it cannot go stale. */
    @Transient
    public String getStatus() {
        if (Boolean.TRUE.equals(blocked)) return "BLOCKED";
        if (endDate != null && LocalDate.now().isAfter(endDate)) return "EXPIRED";
        return "ACTIVE";
    }

    @Transient
    public boolean isUsable() { return "ACTIVE".equals(getStatus()); }

    /** Whether the trial-account agreement has been accepted for this login. */
    @Transient
    public boolean isAgreementAccepted() { return agreementAcceptedAt != null; }

    /** Whether the product tour has already been finished or skipped for this login. */
    @Transient
    public boolean isProductTourDone() { return productTourAt != null; }
}
