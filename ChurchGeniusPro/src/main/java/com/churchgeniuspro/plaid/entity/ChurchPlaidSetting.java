package com.churchgeniuspro.plaid.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.util.Date;

/**
 * Per-church Plaid feature switches, controlled from the Service Admin portal.
 * Mirrors the per-church OpenAI/Voice settings pattern (keyed by {@code client_id}).
 *
 * <p>Effective access requires {@code plaidEnabled = true}; automatic
 * synchronization additionally requires {@code syncEnabled = true}.
 */
@Data
@Entity
@Table(name = "church_plaid_setting",
        uniqueConstraints = @UniqueConstraint(name = "uq_church_plaid_client",
                columnNames = {"client_id"}))
public class ChurchPlaidSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "church_plaid_setting_seq")
    @SequenceGenerator(name = "church_plaid_setting_seq",
            sequenceName = "church_plaid_setting_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    /** Church tenant identifier (matches {@code service_client.client_id}). */
    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /** Master switch: when false, all Plaid endpoints for this church are denied. */
    @Column(name = "plaid_enabled", nullable = false)
    private boolean plaidEnabled;

    /** When false, automatic/webhook-driven sync is suspended (queue stays reviewable). */
    @Column(name = "sync_enabled", nullable = false)
    private boolean syncEnabled;

    @Column(name = "updated_by", length = 150)
    private String updatedBy;

    @Column(name = "updated_date")
    private Date updatedDate;

    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    @PrePersist
    void onCreate() {
        this.createdDate = new Date();
    }
}
