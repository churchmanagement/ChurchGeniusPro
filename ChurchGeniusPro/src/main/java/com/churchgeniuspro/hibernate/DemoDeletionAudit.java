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
 * Persistent audit trail for Service-Admin demo-tenant deletions.
 *
 * <p>One row is written each time a service admin clears a demo tenant from
 * {@code /serviceadminhome}. Captures everything the spec requires: tenant name,
 * Client ID, App Client ID, the acting admin, the timestamp, the total number of
 * rows removed, and a JSON breakdown of the per-module counts.
 */
@Data
@Entity
@Table(name = "demo_deletion_audit")
public class DemoDeletionAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** When the deletion completed. */
    @Column(name = "deleted_at", nullable = false)
    private LocalDateTime deletedAt;

    /** Church / tenant display name, resolved before deletion. */
    @Column(name = "tenant_name", length = 255)
    private String tenantName;

    @Column(name = "client_id", length = 100)
    private String clientId;

    @Column(name = "app_client_id", length = 100)
    private String appClientId;

    /** Username (or id) of the service admin who performed the deletion. */
    @Column(name = "performed_by", length = 255)
    private String performedBy;

    /** Total rows removed across all modules. */
    @Column(name = "total_deleted")
    private Integer totalDeleted;

    /** JSON map of per-module delete counts, e.g. {"income":30,"expense":18,...}. */
    @Column(name = "counts_json", columnDefinition = "TEXT")
    private String countsJson;
}
