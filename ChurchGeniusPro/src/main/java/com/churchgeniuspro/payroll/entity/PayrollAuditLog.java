package com.churchgeniuspro.payroll.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.util.Date;

/**
 * Append-only audit trail for payroll changes and approval actions
 * (requirement #10). One row per significant event: employee/W-4 edits, run
 * submit/approve/void, paystub generation, tax-config changes, etc.
 */
@Data
@Entity
@Table(name = "payroll_audit_log")
public class PayrollAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_client_id", nullable = false)
    private String appClientId;

    /** Logical entity affected, e.g. "PayrollRun", "PayrollEmployee". */
    private String entityType;
    private Long entityId;

    /** Action verb, e.g. "CREATE", "UPDATE", "SUBMIT", "APPROVE", "VOID". */
    private String action;

    /** Who performed it (service-admin/user identifier). */
    private String actor;

    @Column(length = 2000)
    private String details;

    @Column(updatable = false)
    private Date created;

    @PrePersist void onCreate() { created = new Date(); }
}
