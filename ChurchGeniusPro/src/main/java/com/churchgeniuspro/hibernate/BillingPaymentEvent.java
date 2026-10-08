package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Audit trail of online (Stripe) payments for platform invoices (Phase 6), and the de-duplication
 * record for Stripe webhooks: a webhook event id is stored once (unique), in the same
 * transaction that applies it, so a redelivered event is recognised and ignored.
 *
 * <p>Holds no secrets: Stripe ids (event, PaymentIntent) are identifiers, not credentials.
 */
@Data
@Entity
@Table(name = "billing_payment_event",
       uniqueConstraints = @UniqueConstraint(name = "uq_billing_payment_event_stripe_event", columnNames = "stripe_event_id"),
       indexes = @Index(name = "ix_billing_payment_event_invoice", columnList = "invoice_id, created_at"))
public class BillingPaymentEvent {

    /** Paid by this event (the invoice moved SENT → PAID). */
    public static final String RECORDED          = "RECORDED";
    /** The same PaymentIntent was already recorded (confirm and webhook both arrived). */
    public static final String ALREADY_RECORDED  = "ALREADY_RECORDED";
    /** Money received for an invoice already paid another way — possible double payment. */
    public static final String ALERT_ALREADY_PAID = "ALERT_ALREADY_PAID";
    /** Money received for a voided invoice. */
    public static final String ALERT_VOID        = "ALERT_VOID";
    /** Amount, currency or invoice did not match — not applied. */
    public static final String ALERT_MISMATCH    = "ALERT_MISMATCH";
    /** The PaymentIntent has not succeeded (yet) — nothing applied. */
    public static final String NOT_SUCCEEDED     = "NOT_SUCCEEDED";
    /** Stripe reported the payment attempt failed (declined card, returned ACH debit, …) — invoice unchanged, Support told. */
    public static final String PAYMENT_FAILED    = "PAYMENT_FAILED";
    /** A bank transfer arrived short of the invoice total — invoice unchanged, Support told the remainder. */
    public static final String PARTIALLY_FUNDED  = "PARTIALLY_FUNDED";
    /** Not ours / not relevant (other event type, other mode, other purpose). */
    public static final String IGNORED           = "IGNORED";

    public static final String SOURCE_WEBHOOK = "WEBHOOK";
    public static final String SOURCE_CONFIRM = "CONFIRM";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Stripe event id (evt_…) for webhooks; null for the browser confirm path. */
    @Column(name = "stripe_event_id", length = 100) private String stripeEventId;
    @Column(name = "event_type", length = 80)       private String eventType;
    @Column(name = "source", nullable = false, length = 10) private String source;
    @Column(name = "payment_intent_id", length = 100) private String paymentIntentId;
    @Column(name = "invoice_id")                    private Long invoiceId;
    @Column(name = "client_id", length = 100)       private String clientId;
    @Column(name = "amount_cents")                  private Long amountCents;
    @Column(name = "currency", length = 10)         private String currency;
    @Column(name = "outcome", nullable = false, length = 24) private String outcome;
    @Column(name = "detail", length = 500)          private String detail;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
