package com.churchgeniuspro.plaid.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;

import java.util.Date;

/**
 * Audit + idempotency record for every inbound Plaid webhook. The raw payload is
 * stored with credentials stripped; {@code signatureValid} records the JWT
 * verification result.
 */
@Data
@Entity
@Table(name = "plaid_webhook_event")
public class PlaidWebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "plaid_webhook_event_seq")
    @SequenceGenerator(name = "plaid_webhook_event_seq",
            sequenceName = "plaid_webhook_event_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    @Column(name = "item_id", length = 200)
    private String itemId;

    @Column(name = "webhook_type", length = 50)
    private String webhookType;

    @Column(name = "webhook_code", length = 50)
    private String webhookCode;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Column(name = "signature_valid", nullable = false)
    private boolean signatureValid;

    @Column(name = "processed", nullable = false)
    private boolean processed;

    @Column(name = "received_date", nullable = false, updatable = false)
    private Date receivedDate;

    @PrePersist
    void onCreate() {
        this.receivedDate = new Date();
    }
}
