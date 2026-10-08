package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A platform invoice from ChurchGeniusPro to a client church (subscription renewal,
 * a subscription request, or additional charges). Not related to anything a church
 * bills its own members.
 *
 * <p>Lifecycle: {@link #DRAFT} (built, reviewed and edited by the Service Admin) →
 * {@link #SENT} (lines and totals frozen, secure link emailed) → {@link #PAID}
 * (recorded by the Service Admin). {@link #VOID} cancels a draft or a sent invoice
 * and revokes its link. Every status change is a conditional update, so a double
 * click or two admins cannot send, pay or void twice.
 *
 * <p>The secure link carries a random token; only its SHA-256 hash is stored
 * ({@code accessTokenHash}). It works through the due date + 60 days. Re-sending
 * replaces it; voiding clears it.
 */
@Data
@Entity
@Table(name = "billing_invoice",
       uniqueConstraints = {
           @UniqueConstraint(name = "uq_billing_invoice_number", columnNames = "invoice_number"),
           @UniqueConstraint(name = "uq_billing_invoice_token", columnNames = "access_token_hash")
       },
       indexes = {
           @Index(name = "ix_billing_invoice_client_due", columnList = "client_id, due_date"),
           @Index(name = "ix_billing_invoice_status", columnList = "status")
       })
public class BillingInvoice {

    public static final String DRAFT = "DRAFT";
    public static final String SENT  = "SENT";
    public static final String PAID  = "PAID";
    public static final String VOID  = "VOID";

    /** Renewal of the client's subscription for its next billing date. */
    public static final String KIND_RENEWAL = "RENEWAL";
    /** Created from a Subscription Request (the requested plan). */
    public static final String KIND_REQUEST = "REQUEST";
    /** Additional charges / custom lines only. */
    public static final String KIND_MANUAL  = "MANUAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Optimistic lock: an edit made from a stale screen, or a send after an edit, is refused. */
    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "invoice_number", length = 20)        private String invoiceNumber;
    @Column(name = "client_id", nullable = false, length = 100) private String clientId;
    @Column(name = "kind", nullable = false, length = 12) private String kind;
    @Column(name = "subscription_request_id")             private Long subscriptionRequestId;
    @Column(name = "status", nullable = false, length = 10) private String status;

    @Column(name = "church_name", length = 200)  private String churchName;
    @Column(name = "bill_to_name", length = 200) private String billToName;
    @Column(name = "bill_to_email", length = 320) private String billToEmail;

    @Column(name = "plan_code", length = 40)          private String planCode;
    @Column(name = "billing_frequency", length = 10)  private String billingFrequency;
    /**
     * Start of the billed service period. For a RENEWAL invoice this is the billing date
     * (the client's end date when the invoice was created) and identifies the invoice:
     * one live renewal per client per period_start (V11). Never changed after creation.
     */
    @Column(name = "period_start", updatable = false) private LocalDate periodStart;
    @Column(name = "period_end")                      private LocalDate periodEnd;
    @Column(name = "issue_date")                      private LocalDate issueDate;
    @Column(name = "due_date", nullable = false)      private LocalDate dueDate;

    @Column(name = "subtotal", nullable = false, precision = 12, scale = 2) private BigDecimal subtotal;
    @Column(name = "discount", nullable = false, precision = 12, scale = 2) private BigDecimal discount;
    @Column(name = "total", nullable = false, precision = 12, scale = 2)    private BigDecimal total;

    /** Shown on the invoice. */
    @Column(name = "note", columnDefinition = "TEXT") private String note;

    @Column(name = "access_token_hash", length = 64)  private String accessTokenHash;
    @Column(name = "access_token_expires")            private LocalDate accessTokenExpires;

    @Column(name = "sent_at")                   private LocalDateTime sentAt;
    @Column(name = "sent_by", length = 100)     private String sentBy;
    @Column(name = "send_count")                private Integer sendCount;

    @Column(name = "paid_date")                     private LocalDate paidDate;
    @Column(name = "payment_method", length = 30)   private String paymentMethod;
    @Column(name = "payment_reference", length = 120) private String paymentReference;
    @Column(name = "paid_recorded_by", length = 100) private String paidRecordedBy;
    @Column(name = "paid_recorded_at")              private LocalDateTime paidRecordedAt;

    /**
     * The Stripe PaymentIntent (platform Stripe account, Phase 6) currently used to pay
     * this invoice by card. Reused while open; unique when set (V12). Not a secret.
     */
    @Column(name = "stripe_payment_intent_id", length = 100) private String stripePaymentIntentId;
    /** When the payment receipt email was sent (card payments, or a manual mark-paid with "send receipt"). */
    @Column(name = "receipt_sent_at")                         private LocalDateTime receiptSentAt;

    @Column(name = "voided_at")                 private LocalDateTime voidedAt;
    @Column(name = "voided_by", length = 100)   private String voidedBy;
    @Column(name = "void_reason", length = 500) private String voidReason;

    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "created_by", length = 100)  private String createdBy;
    @Column(name = "updated_at")                private LocalDateTime updatedAt;
    @Column(name = "updated_by", length = 100)  private String updatedBy;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null || status.isBlank()) status = DRAFT;
        if (subtotal == null) subtotal = BigDecimal.ZERO;
        if (discount == null) discount = BigDecimal.ZERO;
        if (total == null) total = BigDecimal.ZERO;
    }
}
