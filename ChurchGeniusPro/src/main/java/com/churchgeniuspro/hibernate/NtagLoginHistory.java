package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** One NTAG login attempt (success or failure) — powers the admin login-history view. */
@Data
@Entity
@Table(name = "ntag_login_history")
public class NtagLoginHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "credential_id")
    private Long credentialId;

    @Column(name = "client_id", length = 100)
    private String clientId;

    @Column(name = "holder_name", length = 150)
    private String holderName;

    @Column(name = "serial", length = 120)
    private String serial;

    @Column(name = "login_time", nullable = false)
    private LocalDateTime loginTime = LocalDateTime.now();

    @Column(name = "ip_address", length = 60)
    private String ipAddress;

    @Column(name = "device_info", columnDefinition = "TEXT")
    private String deviceInfo;

    /** SUCCESS | FAILED_PIN | FAILED_OTP | BLOCKED | NOT_FOUND | DISABLED | EXPIRED */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "reason", length = 200)
    private String reason;
}
