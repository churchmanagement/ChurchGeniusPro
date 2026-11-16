package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A discount the Service Admin gave ONE customer (promotions phase 2): either a
 * promotional deal ({@link #DEAL}, by its code) or an independent manual discount
 * ({@link #MANUAL} — "Manual Discount – No Promotion Code").
 *
 * <p>Lifecycle: {@link #SCHEDULED} (assigned, not started — a trial keeps it like this
 * with no dates until the Service Admin applies it at conversion; a paying client's
 * starts on its next billing date) → {@link #ACTIVE} → {@link #EXPIRED} (phase 4), or
 * {@link #CANCELLED}. Rows are never deleted.
 *
 * <p>The customer's regular price is NOT stored here as the price to charge — it stays
 * on {@code service_client.subscription_price}, untouched, and is what the customer
 * returns to. {@code regularPrice} / {@code promotionalPrice} are a record of the
 * figures when the discount started. The end date is calculated once and never moves
 * later (it may only be shortened by the Service Admin).
 *
 * <p>The terms granted are a frozen copy: editing or deactivating the deal afterwards
 * never changes a customer who already has it. Every change writes a
 * {@link PromotionHistory} row naming the Service Admin.
 */
@Data
@Entity
@Table(name = "client_promotion",
       indexes = @Index(name = "ix_client_promotion_client_status", columnList = "client_id, status"))
public class ClientPromotion {

    public static final String DEAL   = "DEAL";
    public static final String MANUAL = "MANUAL";

    public static final String SCHEDULED = "SCHEDULED";
    public static final String ACTIVE    = "ACTIVE";
    public static final String EXPIRED   = "EXPIRED";
    public static final String CANCELLED = "CANCELLED";

    /** How a manual discount is named in history and reports. */
    public static final String MANUAL_LABEL = "Manual Discount – No Promotion Code";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "client_id", nullable = false, updatable = false, length = 100)
    private String clientId;

    /** {@link #DEAL} | {@link #MANUAL} */
    @Column(name = "kind", nullable = false, updatable = false, length = 10)
    private String kind;

    /** The deal (set once the monthly / yearly deal is known; a trial holds only the code). */
    @Column(name = "promotion_id")                 private Long promotionId;
    @Column(name = "promotion_code", length = 30)  private String promotionCode;
    @Column(name = "promotion_name", length = 100) private String promotionName;

    /** The plan and billing frequency the discount was started for. */
    @Column(name = "plan_code", length = 40)         private String planCode;
    @Column(name = "billing_frequency", length = 10) private String billingFrequency;

    /** The deal's own standard terms at the time (empty for a manual discount). */
    @Column(name = "deal_discount_type", length = 12)                    private String dealDiscountType;
    @Column(name = "deal_discount_value", precision = 10, scale = 2)     private BigDecimal dealDiscountValue;
    @Column(name = "deal_duration_amount")                               private Integer dealDurationAmount;
    @Column(name = "deal_duration_unit", length = 6)                     private String dealDurationUnit;

    /** The terms this customer was actually given. */
    @Column(name = "discount_type", length = 12)                  private String discountType;
    @Column(name = "discount_value", precision = 10, scale = 2)   private BigDecimal discountValue;
    @Column(name = "duration_amount")                             private Integer durationAmount;
    @Column(name = "duration_unit", length = 6)                   private String durationUnit;

    /** True when the Service Admin gave this customer terms that differ from the deal's. */
    @ColumnDefault("false")
    @Column(name = "manually_adjusted", nullable = false)
    private boolean manuallyAdjusted;

    /** Why / what was agreed. Required for a manual discount and for adjusted terms. */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "status", nullable = false, length = 10)
    private String status;

    /** First and last day of promotional pricing (both inclusive); empty while a trial is waiting. */
    @Column(name = "start_date") private LocalDate startDate;
    @Column(name = "end_date")   private LocalDate endDate;

    /** The figures when the discount started — a record, never the price to charge. */
    @Column(name = "regular_price", precision = 10, scale = 2)     private BigDecimal regularPrice;
    @Column(name = "promotional_price", precision = 10, scale = 2) private BigDecimal promotionalPrice;

    @Column(name = "assigned_by", length = 100, updatable = false) private String assignedBy;
    @Column(name = "assigned_at", nullable = false, updatable = false) private LocalDateTime assignedAt;
    @Column(name = "applied_by", length = 100)   private String appliedBy;
    @Column(name = "applied_at")                 private LocalDateTime appliedAt;
    @Column(name = "cancelled_by", length = 100) private String cancelledBy;
    @Column(name = "cancelled_at")               private LocalDateTime cancelledAt;
    @Column(name = "cancel_reason", length = 500) private String cancelReason;

    /** The first day back at the regular price, recorded when the discount expires (phase 4). */
    @Column(name = "restored_on") private LocalDate restoredOn;

    @Column(name = "updated_at")               private LocalDateTime updatedAt;
    @Column(name = "updated_by", length = 100) private String updatedBy;
}
