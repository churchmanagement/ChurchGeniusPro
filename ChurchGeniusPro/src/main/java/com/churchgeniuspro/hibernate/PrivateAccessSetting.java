package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.util.Date;

/**
 * Per-tenant master switch for the "Private Page Access" feature.
 *
 * <p>When {@code enabled} is false, no page is ever gated by network — the
 * {@link PrivatePageFilter} short-circuits. One row per {@code client_id}.
 */
@Data
@Entity
@Table(name = "private_access_setting")
public class PrivateAccessSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, unique = true, length = 100)
    private String clientId;

    /** Master on/off for network-based page restriction. */
    @Column(name = "enabled", nullable = false, columnDefinition = "boolean not null default false")
    private boolean enabled;

    /**
     * When true, the client IP is taken from the {@code X-Forwarded-For} header
     * (correct behind a reverse proxy / CDN, which is how production runs); when
     * false, the direct socket address is used.
     */
    @Column(name = "trust_proxy", nullable = false, columnDefinition = "boolean not null default true")
    private boolean trustProxy = true;

    @Column(name = "updated_at")
    private Date updatedAt;

    @PreUpdate @PrePersist
    void touch() { this.updatedAt = new Date(); }
}
