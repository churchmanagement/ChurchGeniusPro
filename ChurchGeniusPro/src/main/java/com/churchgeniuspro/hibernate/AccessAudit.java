package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One login/logout record for a {@link TemporaryAccess} pass — the audit trail
 * required by the spec. A new row is written on each successful temporary login;
 * {@code logoutTime} is stamped on explicit logout or when the session expires.
 */
@Data
@Entity
@Table(name = "access_audit")
public class AccessAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "temporary_access_id", nullable = false)
    private Long temporaryAccessId;

    /** Tenant clientId, copied from the pass for easy tenant-scoped queries. */
    @Column(name = "client_id")
    private String clientId;

    @Column(name = "login_time")
    private LocalDateTime loginTime;

    @Column(name = "logout_time")
    private LocalDateTime logoutTime;

    /** Browser/user-agent string captured at login. */
    @Column(name = "device_info", columnDefinition = "TEXT")
    private String deviceInfo;

    @Column(name = "ip_address")
    private String ipAddress;
}
