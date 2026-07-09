package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Short-lived server-side state for an in-progress NTAG login. Created when a tag
 * is scanned; advanced through PIN and OTP verification. Holds the hashed OTP and
 * tracks attempts/expiry so the multi-step flow is stateless on the client (it only
 * carries the opaque {@code token}).
 */
@Data
@Entity
@Table(name = "ntag_login_challenge")
public class NtagLoginChallenge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "token", nullable = false, unique = true, length = 80)
    private String token;

    @Column(name = "credential_id", nullable = false)
    private Long credentialId;

    @Column(name = "client_id", length = 100)
    private String clientId;

    /** AWAIT_PIN | AWAIT_OTP | DONE */
    @Column(name = "stage", nullable = false, length = 20)
    private String stage;

    /** BCrypt hash of the emailed OTP (set once the OTP step begins). */
    @Column(name = "otp_hash", length = 100)
    private String otpHash;

    @Column(name = "otp_expires_at")
    private LocalDateTime otpExpiresAt;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "ip_address", length = 60)
    private String ipAddress;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
