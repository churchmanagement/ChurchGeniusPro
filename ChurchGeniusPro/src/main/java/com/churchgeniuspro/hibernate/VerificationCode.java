package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persists short-lived OTP verification codes so they survive server restarts.
 * Keyed by {@code clientId} + {@code type} (e.g. {@code "signup-email"}).
 * Hibernate DDL-update will auto-create the {@code verification_code} table.
 */
@Data
@Entity
@Table(name = "verification_code",
       uniqueConstraints = @UniqueConstraint(columnNames = {"client_id", "type"}))
public class VerificationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "verification_code_seq")
    @SequenceGenerator(name = "verification_code_seq", sequenceName = "verification_code_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    @Column(name = "client_id", nullable = false, length = 512)
    private String clientId;

    @Column(name = "type", nullable = false, length = 64)
    private String type;

    @Column(name = "code", nullable = false, length = 16)
    private String code;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}
