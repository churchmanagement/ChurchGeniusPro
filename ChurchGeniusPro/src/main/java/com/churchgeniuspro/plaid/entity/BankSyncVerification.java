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
 * A single-use email verification code for the Bank Sync step-up gate. The code
 * itself is stored only in AES-256-GCM encrypted form ({@code tokenEnc}); it is
 * decrypted and compared during verification, never stored or logged in clear.
 */
@Data
@ToString(exclude = {"tokenEnc"})
@Entity
@Table(name = "bank_sync_verification")
public class BankSyncVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "bank_sync_verification_seq")
    @SequenceGenerator(name = "bank_sync_verification_seq",
            sequenceName = "bank_sync_verification_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    /** app_user.id of the user who must verify. */
    @Column(name = "app_user_id", nullable = false)
    private Integer appUserId;

    @Column(name = "client_id", length = 100)
    private String clientId;

    /** AES-256-GCM encrypted verification code. */
    @Column(name = "token_enc", nullable = false, columnDefinition = "TEXT")
    private String tokenEnc;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** True once the code has been used (one-time). */
    @Column(name = "used", nullable = false)
    private boolean used;

    @Column(name = "used_date")
    private Date usedDate;

    /** True when an administrator (Church role) generated this as a temporary code. */
    @Column(name = "is_temp", nullable = false)
    private boolean isTemp;

    /** Username of the administrator who generated a temporary code (audit). */
    @Column(name = "generated_by", length = 150)
    private String generatedBy;

    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    @PrePersist
    void onCreate() {
        this.createdDate = new Date();
    }
}
