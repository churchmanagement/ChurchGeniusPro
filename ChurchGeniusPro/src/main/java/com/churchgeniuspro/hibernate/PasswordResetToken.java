package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.util.Date;

/**
 * Stores time-limited password-reset tokens.
 * Each token is a UUID valid for 10 minutes; it is marked {@code used} after consumption.
 */
@Data
@Entity
@Table(name = "password_reset_token")
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "prt_seq")
    @SequenceGenerator(name = "prt_seq", sequenceName = "password_reset_token_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** The signup username this token was issued for. */
    @Column(name = "username", nullable = false)
    private String username;

    /** The email address the reset link was sent to (for audit). */
    @Column(name = "email", nullable = false)
    private String email;

    /** UUID token embedded in the reset URL. */
    @Column(name = "token", nullable = false, unique = true)
    private String token;

    /** When the token was issued — drives the reset-request cooldown. */
    @Column(name = "created_at")
    private Date createdAt;

    /** Timestamp after which the token is no longer valid (10 minutes from creation). */
    @Column(name = "expiry_time", nullable = false)
    private Date expiryTime;

    /** True once the token has been consumed (password was successfully reset). */
    @Column(name = "used", nullable = false)
    private boolean used;

    @PrePersist
    protected void onCreate() {
        this.used = false;
        if (this.createdAt == null) this.createdAt = new Date();
    }
}
