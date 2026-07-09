package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * An NTAG (NFC tag) credential assigned to a registered user/holder for
 * three-factor temporary login: the tag serial (something you have), a 6-digit
 * PIN (something you know), and an email OTP (something you can access). The
 * PIN and OTP steps are each independently configurable per credential.
 *
 * <p>Independent of {@code TemporaryAccess} passes — this is its own per-user
 * NTAG record. On successful login it establishes a standard session scoped to
 * {@code permissions}.
 */
@Data
@Entity
@Table(name = "ntag_credential")
public class NtagCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /** The tag's NFC serial number, normalized (uppercase hex, no separators). Globally unique. */
    @Column(name = "ntag_serial", nullable = false, unique = true, length = 120)
    private String ntagSerial;

    @Column(name = "holder_name", length = 150)
    private String holderName;

    /** Registered email — OTP is sent here. */
    @Column(name = "email", length = 200)
    private String email;

    @Column(name = "phone", length = 40)
    private String phone;

    /** Optional link to an app_user (registered user account). */
    @Column(name = "user_id", length = 100)
    private String userId;

    /** Session role granted on login (default User). */
    @Column(name = "role", length = 30)
    private String role;

    /** BCrypt hash of the 6-digit PIN (null when PIN not required). */
    @Column(name = "pin_hash", length = 100)
    private String pinHash;

    @Column(name = "ntag_enabled", nullable = false, columnDefinition = "boolean not null default true")
    private boolean ntagEnabled = true;

    @Column(name = "require_pin", nullable = false, columnDefinition = "boolean not null default true")
    private boolean requirePin = true;

    @Column(name = "require_otp", nullable = false, columnDefinition = "boolean not null default true")
    private boolean requireOtp = true;

    /** CSV of permitted page keys (drives the session privileges + landing page). */
    @Column(name = "permissions", columnDefinition = "TEXT")
    private String permissions;

    /** Active | Revoked. */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "Active";

    /** Optional validity window for when NTAG login is allowed. */
    @Column(name = "valid_from")
    private LocalDateTime validFrom;

    @Column(name = "valid_until")
    private LocalDateTime validUntil;

    @Column(name = "created_by", length = 150)
    private String createdBy;

    @Column(name = "created_date", nullable = false, updatable = false)
    private LocalDateTime createdDate;

    @Column(name = "updated_date")
    private LocalDateTime updatedDate;

    @Column(name = "delete_flag", nullable = false, columnDefinition = "boolean not null default false")
    private boolean deleteFlag = false;

    @PrePersist
    void onCreate() { this.createdDate = LocalDateTime.now(); this.updatedDate = this.createdDate; }
    @PreUpdate
    void onUpdate() { this.updatedDate = LocalDateTime.now(); }
}
