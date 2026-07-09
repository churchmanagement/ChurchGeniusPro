package com.churchgeniuspro.plaid.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;

import java.util.Date;

/**
 * Full audit trail for Plaid-related financial actions. Mirrors the existing
 * per-domain audit-log convention (e.g. PayrollAuditLog). Never stores secrets.
 */
@Data
@Entity
@Table(name = "plaid_audit_log")
public class PlaidAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "plaid_audit_log_seq")
    @SequenceGenerator(name = "plaid_audit_log_seq",
            sequenceName = "plaid_audit_log_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    @Column(name = "client_id", length = 100)
    private String clientId;

    /** Username, or SYSTEM / WEBHOOK for non-interactive actions. */
    @Column(name = "actor", length = 150)
    private String actor;

    /**
     * Action code, e.g. LINK_CREATED, ITEM_LINKED, SYNC_RUN, TXN_APPROVED,
     * TXN_REJECTED, TXN_EDITED, BULK_APPROVED, ITEM_DISCONNECTED, PLAID_ENABLED,
     * SYNC_TOGGLED, WEBHOOK_RECEIVED.
     */
    @Column(name = "action", length = 60)
    private String action;

    /** Item / account / transaction identifier the action targeted. */
    @Column(name = "target_ref", length = 200)
    private String targetRef;

    /** Human-readable before/after summary (no secrets). */
    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    @PrePersist
    void onCreate() {
        this.createdDate = new Date();
    }
}
