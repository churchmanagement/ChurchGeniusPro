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
import lombok.ToString;

import java.time.Instant;
import java.util.Date;

/**
 * A "remember this device for 30 days" record for the Bank Sync gate. The device
 * cookie holds an opaque random token; only its SHA-256 hash is stored here, so a
 * database read cannot reconstruct a usable cookie.
 */
@Data
@ToString(exclude = {"tokenHash"})
@Entity
@Table(name = "bank_sync_trusted_device")
public class BankSyncTrustedDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "bank_sync_trusted_device_seq")
    @SequenceGenerator(name = "bank_sync_trusted_device_seq",
            sequenceName = "bank_sync_trusted_device_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    @Column(name = "app_user_id", nullable = false)
    private Integer appUserId;

    @Column(name = "client_id", length = 100)
    private String clientId;

    /** SHA-256 (hex) of the opaque device-trust cookie value. */
    @Column(name = "token_hash", nullable = false, length = 100)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    @PrePersist
    void onCreate() {
        this.createdDate = new Date();
    }
}
