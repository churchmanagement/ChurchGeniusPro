package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Audit record for one access attempt against a Private (network-gated) page.
 * Powers the admin audit report.
 */
@Data
@Entity
@Table(name = "private_access_log")
public class PrivateAccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /** Username (or "(anonymous)" when no session). */
    @Column(name = "username", length = 150)
    private String username;

    @Column(name = "page_key", length = 60)
    private String pageKey;

    @Column(name = "requested_path", length = 300)
    private String requestedPath;

    @Column(name = "ip_address", length = 60)
    private String ipAddress;

    /** ALLOWED | BLOCKED | OVERRIDE (admin bypass). */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** Short reason, e.g. "matched: Church Staff Wi-Fi", "no approved network", "admin override". */
    @Column(name = "reason", length = 200)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
