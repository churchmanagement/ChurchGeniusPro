package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

/**
 * Single Stripe API-key record per organization.
 * A unique constraint on {@code client_id} enforces the one-record-per-org rule;
 * the UI therefore only ever performs an upsert (create on first save, update thereafter).
 */
@Data
@Entity
@Table(name = "stripe_settings",
       uniqueConstraints = @UniqueConstraint(name = "uq_stripe_settings_client", columnNames = {"client_id"}))
public class StripeSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "stripe_settings_seq")
    @SequenceGenerator(name = "stripe_settings_seq", sequenceName = "stripe_settings_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Organization identifier — exactly one record per org is permitted. */
    @Column(name = "client_id", nullable = false, unique = true)
    private String clientId;

    /** Stripe publishable key (pk_live_… or pk_test_…). Safe to expose to the browser. */
    @Column(name = "publishable_key", length = 500)
    private String publishableKey;

    /** Stripe secret key (sk_live_… or sk_test_…). Must never be sent to the client in plaintext in production. */
    @Column(name = "secret_key", length = 500)
    private String secretKey;
}
