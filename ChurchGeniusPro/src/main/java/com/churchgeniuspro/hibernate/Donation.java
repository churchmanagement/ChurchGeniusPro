package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Stores a single successful donation made via the public Stripe-powered donation page.
 * One record per completed PaymentIntent — idempotent on stripePaymentIntentId.
 */
@Data
@Entity
@Table(name = "donation",
       uniqueConstraints = @UniqueConstraint(name = "uq_donation_intent",
               columnNames = {"stripe_payment_intent_id"}))
public class Donation {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "donation_seq")
    @SequenceGenerator(name = "donation_seq", sequenceName = "donation_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Organization this donation belongs to. */
    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(name = "first_name", length = 200)
    private String firstName;

    @Column(name = "last_name", length = 200)
    private String lastName;

    @Column(name = "email", length = 320)
    private String email;

    @Column(name = "phone", length = 30)
    private String phone;

    /** Optional one-line comment left by the donor. */
    @Column(name = "note", length = 500)
    private String note;

    /** Human-readable payment method label, e.g. "Visa \u2022\u2022\u2022\u2022 4242" or "Bank Transfer". */
    @Column(name = "payment_method", length = 200)
    private String paymentMethod;

    /** Amount actually charged, in major currency units (e.g. 25.00 for $25.00).
     *  When the donor covered the processing fee this is the grossed-up charge,
     *  not their intended gift — see {@link #intendedAmount}. */
    @Column(name = "amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal amount;

    /**
     * Financial audit M3: the donor's intended gift, separate from the amount
     * actually charged. Equal to {@link #amount} unless the donor covered the
     * transaction fee, in which case it is {@code amount - feeCovered}. Null
     * only for a donation recorded before this distinction existed — use
     * {@link #getIntendedAmountOrCharge()} rather than this raw accessor when
     * what's needed is always a non-null "the gift" figure (a receipt, a
     * report, the income ledger).
     */
    @Column(name = "intended_amount", precision = 12, scale = 2)
    private BigDecimal intendedAmount;

    /**
     * Financial audit M3: the processing-fee portion of {@link #amount} that
     * the donor chose to cover on top of their intended gift, so the fee could
     * be reported separately rather than folded into "the gift". Zero — never
     * null — when no fee was covered, including for every donation recorded
     * before this column existed.
     */
    @Column(name = "fee_covered", precision = 12, scale = 2, nullable = false)
    @ColumnDefault("0")
    private BigDecimal feeCovered = BigDecimal.ZERO;

    /** The donor's intended gift — never null. Falls back to the actual charge
     *  for a donation recorded before {@link #intendedAmount} existed, so every
     *  caller that wants "the gift, not the charge" can use this unconditionally. */
    public BigDecimal getIntendedAmountOrCharge() {
        return intendedAmount != null ? intendedAmount : amount;
    }

    /** ISO 4217 currency code, e.g. "USD". */
    @Column(name = "currency", length = 10)
    private String currency;

    /** Stripe PaymentIntent ID — used for idempotency and audit trail. */
    @Column(name = "stripe_payment_intent_id", length = 500)
    private String stripePaymentIntentId;

    /** Stripe Charge ID (from latest_charge on the PaymentIntent). */
    @Column(name = "stripe_charge_id", length = 500)
    private String stripeChargeId;

    /** Stripe payment status — normally "succeeded" for saved records. */
    @Column(name = "status", length = 50)
    private String status;

    @Column(name = "donated_at")
    private LocalDateTime donatedAt;

    // ── Phase D: Member Portal contributions ────────────────────────────────
    /** Where the gift came from: null/{@link #SOURCE_PUBLIC} = the donation page; {@link #SOURCE_MEMBER_PORTAL} = My Profile → Give. */
    public static final String SOURCE_PUBLIC        = "PUBLIC";
    public static final String SOURCE_MEMBER_PORTAL = "MEMBER_PORTAL";

    @Column(name = "source", length = 20)
    private String source;

    /** The signed-in member who gave (Member Portal only; verified against the tenant at save time). */
    @Column(name = "member_id")
    private Integer memberId;

    /** The purpose (sub-source) the member chose (Member Portal only). */
    @Column(name = "sub_source_id")
    private Integer subSourceId;

    public boolean isMemberContribution() { return SOURCE_MEMBER_PORTAL.equals(source); }

    /**
     * Donation Review bookkeeping status — separate from {@link #status}, which is
     * Stripe's payment result and is never changed by review. {@code null} means
     * Pending; {@link #REVIEW_COMPLETED} means a staff member marked it Completed.
     * It affects ONLY the Donation Review page's Pending totals: the donation row,
     * its Income posting, tax statements and financial reports are untouched.
     */
    public static final String REVIEW_COMPLETED = "COMPLETED";

    @Column(name = "review_status", length = 20)
    private String reviewStatus;

    /** When it was marked Completed (null while Pending). */
    @Column(name = "review_completed_at")
    private LocalDateTime reviewCompletedAt;

    /** Username of the staff member who marked it Completed (null while Pending). */
    @Column(name = "review_completed_by", length = 320)
    private String reviewCompletedBy;

    /** True when this donation has been marked Completed on the Donation Review page. */
    public boolean isReviewCompleted() {
        return REVIEW_COMPLETED.equals(reviewStatus);
    }

    @PrePersist
    public void prePersist() {
        if (donatedAt == null) donatedAt = LocalDateTime.now();
    }
}
