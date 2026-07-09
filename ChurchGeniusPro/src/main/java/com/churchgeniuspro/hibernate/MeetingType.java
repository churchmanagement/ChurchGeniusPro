package com.churchgeniuspro.hibernate;

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
 * Hibernate entity for the {@code meeting_type} table.
 *
 * <p>Stores configurable meeting-type categories (e.g. Sunday Service, Bible Study, Prayer).
 * Records are never physically removed — a {@code delete_flag} is used instead.
 */
@Data
@Entity
@Table(name = "meeting_type")
public class MeetingType {

    // ── Primary Key ───────────────────────────────────────────────────────

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "meeting_type_seq")
    @SequenceGenerator(
            name           = "meeting_type_seq",
            sequenceName   = "meeting_type_id_seq",
            allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    // ── Data ──────────────────────────────────────────────────────────────

    @Column(name = "type_name", nullable = false)
    private String typeName;

    // ── Status ────────────────────────────────────────────────────────────

    /** Optional org identifier from the app_user who created this record. Null for church-level accounts. */
    @Column(name = "app_client_id")
    private String appClientId;

    /** Soft-delete flag — record is never physically removed. */
    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag;

    // ── Audit ─────────────────────────────────────────────────────────────

    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @PrePersist
    protected void onCreate() {
        this.createdDate = new Date();
        this.deleteFlag  = false;
    }
}
