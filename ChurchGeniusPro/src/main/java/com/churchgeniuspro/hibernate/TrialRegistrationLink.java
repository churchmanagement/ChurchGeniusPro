package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

/**
 * A one-time, time-limited invitation to the self-service Trial Registration form.
 *
 * <p>The form used to be a public URL, so anyone who found it could provision a
 * tenant. It is now reachable only with a token from this table, issued by a
 * Service Admin for a named prospect.
 *
 * <p>Four things can make a link unusable, and they are kept distinct because the
 * visitor is told something different for each: it expired, it was already used to
 * create a church, it was revoked when a replacement was issued, or the token
 * never existed at all.
 *
 * <p>The token is stored as issued rather than hashed. That is a deliberate
 * trade-off: a Service Admin has to be able to re-copy a link they generated
 * earlier, and what the token grants is narrow — creating one sandbox-confined
 * trial tenant, behind the same honeypot, time-trap and rate limit as before.
 */
@Data
@Entity
@Table(name = "trial_registration_link",
       uniqueConstraints = @UniqueConstraint(name = "uk_trial_link_token", columnNames = "token"),
       indexes = @Index(name = "ix_trial_link_token", columnList = "token"))
public class TrialRegistrationLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    /**
     * The secret in the URL. 32 random bytes, URL-safe base64, unpadded — never a
     * sequential id, and carrying no information about the prospect.
     */
    @Column(name = "token", nullable = false, length = 100)
    private String token;

    /** Who the link was issued for. Display only — the token encodes nothing. */
    @Column(name = "prospect_name", length = 200)
    private String prospectName;

    @Column(name = "prospect_email", length = 200)
    private String prospectEmail;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "created_by", length = 150)
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * How long the trial account this link creates will run, in days (30, 60, 90 …).
     *
     * <p>Chosen by the Service Admin when issuing the link, and applied when the
     * prospect registers — the trial still ends on a date like every other trial;
     * only its length is selectable. Nullable, and null means the standard
     * {@code TrialRegistrationService.TRIAL_DAYS}, so rows issued before this column
     * existed behave exactly as they did.
     */
    @Column(name = "trial_days")
    private Integer trialDays;

    /** Last moment the link works. Seven days out by default. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** Set the moment a registration succeeds; a link is single-use. */
    @Column(name = "used_at")
    private LocalDateTime usedAt;

    /** The tenant this link created, so an admin can trace a church to its invite. */
    @Column(name = "used_client_id", length = 100)
    private String usedClientId;

    /**
     * Set when a replacement link is issued for the same prospect, so the old URL
     * stops working immediately rather than lingering until it expires.
     *
     * <p>{@code @ColumnDefault} is load-bearing: ddl-auto cannot add a NOT NULL
     * column to a table that already has rows without one.
     */
    @Column(name = "revoked", nullable = false)
    @ColumnDefault("false")
    private Boolean revoked = Boolean.FALSE;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /**
     * When a Service Admin soft-deleted this row, or null while it is listed.
     *
     * <p>Distinct from {@code revoked}, and both are kept because they mean
     * different things to different people. Revoking is about the PROSPECT: the
     * link stops working and the person who follows it is told so. Soft-deleting is
     * about the SCREEN: the row leaves the Service Admin's list without anything
     * being destroyed, and it can be brought back. A revoked link is still listed;
     * a soft-deleted one is not.
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /** Which Service Admin soft-deleted it. */
    @Column(name = "deleted_by", length = 150)
    private String deletedBy;

    /* ── derived state ──────────────────────────────────────────────────── */

    /** True once a Service Admin has soft-deleted this row. */
    @Transient
    public boolean isDeleted() { return deletedAt != null; }

    /**
     * DELETED / USED / REVOKED / EXPIRED / ACTIVE — derived, never stored, so it
     * cannot go stale.
     *
     * <p>Deleted outranks the rest: a soft-deleted link is out of use whatever else
     * was true of it, and reporting it as "Active" in a list of deleted rows would
     * invite someone to hand it out again.
     */
    @Transient
    public String getStatus() {
        if (deletedAt != null) return "DELETED";
        if (usedAt != null) return "USED";
        if (Boolean.TRUE.equals(revoked)) return "REVOKED";
        if (expiresAt != null && LocalDateTime.now().isAfter(expiresAt)) return "EXPIRED";
        return "ACTIVE";
    }

    @Transient
    public boolean isUsable() { return "ACTIVE".equals(getStatus()); }
}
