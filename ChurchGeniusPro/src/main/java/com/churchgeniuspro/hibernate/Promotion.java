package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A promotional deal the Service Admin configures (for example "Thanksgiving 2026",
 * code THANKS2026): a discount on the subscription price for a fixed length of time.
 *
 * <p>A deal is for ONE billing frequency — a monthly deal can only ever be given to a
 * client billed monthly, a yearly deal to a client billed yearly. The same code may
 * exist once per frequency, so one campaign can have a monthly and a yearly deal.
 *
 * <p>Promotions phase 1: this is the catalogue only. Nothing in billing, pricing or
 * subscriptions reads it yet (pinned by PromotionPhase1Test). Customers never see or
 * enter a code — the Service Admin assigns deals (phase 2).
 *
 * <p>A deal is never deleted, only deactivated, so history is never lost. Every
 * change writes a {@link PromotionHistory} row.
 */
@Data
@Entity
@Table(name = "promotion",
       uniqueConstraints = @UniqueConstraint(name = "ux_promotion_code_frequency",
                                             columnNames = {"code", "billing_frequency"}))
public class Promotion {

    public static final String PERCENT     = "PERCENT";       // percentage off the regular price
    public static final String AMOUNT_OFF  = "AMOUNT_OFF";    // fixed dollar amount off the regular price
    public static final String FIXED_PRICE = "FIXED_PRICE";   // fixed promotional subscription price

    public static final String MONTHS = "MONTHS";
    public static final String YEARS  = "YEARS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Optimistic lock: a save from a stale copy of the deal is refused, never silently applied. */
    @Version
    @Column(name = "version")
    private Long version;

    /** Stored in capitals; never changes after the deal is created. */
    @Column(name = "code", nullable = false, updatable = false, length = 30)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** MONTHLY | YEARLY (SubscriptionLifecycleService constants). */
    @Column(name = "billing_frequency", nullable = false, length = 10)
    private String billingFrequency;

    /** {@link #PERCENT} | {@link #AMOUNT_OFF} | {@link #FIXED_PRICE} */
    @Column(name = "discount_type", nullable = false, length = 12)
    private String discountType;

    /** A percentage (1–100) for PERCENT, otherwise dollars. */
    @Column(name = "discount_value", nullable = false, precision = 10, scale = 2)
    private BigDecimal discountValue;

    @Column(name = "duration_amount", nullable = false)
    private Integer durationAmount;

    /** {@link #MONTHS} | {@link #YEARS} */
    @Column(name = "duration_unit", nullable = false, length = 6)
    private String durationUnit;

    /** Plan codes the deal covers, comma-separated (e.g. "STANDARD,PRO"). */
    @Column(name = "plan_codes", nullable = false, length = 400)
    private String planCodes;

    /** Optional: the deal may be given to a customer only from / until these dates (inclusive). */
    @Column(name = "assign_from")  private LocalDate assignFrom;
    @Column(name = "assign_until") private LocalDate assignUntil;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** An inactive deal cannot be newly assigned; customers who already have it keep it. */
    @ColumnDefault("true")
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "created_by", length = 100, updatable = false)     private String createdBy;
    @Column(name = "updated_at")                                      private LocalDateTime updatedAt;
    @Column(name = "updated_by", length = 100)                        private String updatedBy;
}
