package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

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

    /** Amount in major currency units (e.g. 25.00 for $25.00). */
    @Column(name = "amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal amount;

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

    @PrePersist
    public void prePersist() {
        if (donatedAt == null) donatedAt = LocalDateTime.now();
    }
}
