package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * An additional (one-off) charge for a client — setup, training, data migration,
 * extra SMS — billed on a platform invoice alongside the subscription.
 *
 * <p>Lifecycle: {@link #UNBILLED} → {@link #BILLED} (its invoice was sent) →
 * {@link #PAID} (that invoice was marked paid, or the charge was marked paid on its
 * own). {@link #VOID} cancels a charge that was never billed. While a charge is
 * UNBILLED it may sit on a DRAFT invoice ({@code invoiceId} set): a draft reserves
 * it so it cannot appear on two invoices; voiding that invoice releases it again.
 *
 * <p>{@code billingPeriod} ("YYYY-MM") is the month the charge belongs to. A renewal
 * invoice picks up only free unbilled charges of its billing month or earlier; a
 * charge for a later month waits for that month's renewal.
 *
 * <p>Reserving a charge is a conditional update ({@code invoice_id IS NULL AND
 * status = 'UNBILLED'}) — the database decides which invoice gets it, so two invoices
 * created at the same moment can never both hold the same charge.
 */
@Data
@Entity
@Table(name = "client_charge",
       indexes = @Index(name = "ix_client_charge_client_status", columnList = "client_id, status"))
public class ClientCharge {

    public static final String UNBILLED = "UNBILLED";
    public static final String BILLED   = "BILLED";
    public static final String PAID     = "PAID";
    public static final String VOID     = "VOID";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /**
     * Optimistic lock. Every status / reservation change is a conditional update that
     * also bumps this, so an edit saved from a stale copy of the charge (for example
     * one read just before another invoice claimed it) is refused instead of silently
     * undoing that claim.
     */
    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "description", nullable = false, length = 200)
    private String description;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** The billing period the charge belongs to, "YYYY-MM". */
    @Column(name = "billing_period", nullable = false, length = 7)
    private String billingPeriod;

    @Column(name = "status", nullable = false, length = 12)
    private String status;

    /** The invoice holding this charge (a DRAFT reserving it, or the sent/paid invoice). */
    @Column(name = "invoice_id")
    private Long invoiceId;

    @Column(name = "paid_date")                  private LocalDate paidDate;
    @Column(name = "payment_method", length = 30) private String paymentMethod;
    @Column(name = "note", length = 500)         private String note;

    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "created_by", length = 100)   private String createdBy;
    @Column(name = "updated_at")                 private LocalDateTime updatedAt;
    @Column(name = "updated_by", length = 100)   private String updatedBy;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null || status.isBlank()) status = UNBILLED;
    }
}
