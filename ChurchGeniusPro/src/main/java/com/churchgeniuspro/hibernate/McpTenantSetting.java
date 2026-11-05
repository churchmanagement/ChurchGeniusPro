package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * The church's opt-in for external AI connections (AI Phase 5, 2026-11-05).
 *
 * <p>No row = OFF. An admin of the church switches it on from the API Tokens page;
 * while it is off no token can be created and every existing token is refused
 * (401) — the plan feature {@code mcp} is the platform's switch, this is the church's.
 */
@Entity
@Table(name = "mcp_tenant_setting",
       indexes = @Index(name = "idx_mcp_setting_client", columnList = "app_client_id", unique = true))
@Getter @Setter
public class McpTenantSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_client_id", nullable = false, length = 100)
    private String appClientId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "updated_by", length = 150)
    private String updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
