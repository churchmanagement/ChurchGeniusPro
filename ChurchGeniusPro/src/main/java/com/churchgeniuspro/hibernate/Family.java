package com.churchgeniuspro.hibernate;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Hibernate entity for the {@code family} table.
 *
 * <p>Represents a family group consisting of one or more {@link FamilyMember}
 * records.  The display name for the family is derived at the service layer from
 * the primary member's first/last name — it is no longer stored as a separate
 * {@code family_name} column, and address fields are stored only on the primary
 * {@link FamilyMember} row (eliminating the previous duplication).
 *
 * <p>{@code createdDate} is automatically set by {@link #onCreate()} and
 * {@code deleteFlag} defaults to {@code false} on every new insert.
 */
@Data
@Entity
@Table(name = "family")
public class Family {

    // ── Primary Key ───────────────────────────────────────────────────────

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "family_seq")
    @SequenceGenerator(
            name           = "family_seq",
            sequenceName   = "family_id_seq",
            allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    // ── Members (One-to-Many) ─────────────────────────────────────────────

    /**
     * The members that belong to this family.
     * {@code CascadeType.ALL} ensures members are persisted / updated together
     * with their parent family row.  {@code orphanRemoval = false} so that
     * members removed from this collection are NOT physically deleted — soft-delete
     * (deleteFlag = true) is used instead.
     */
    @OneToMany(cascade = CascadeType.ALL, mappedBy = "family", orphanRemoval = false)
    private List<FamilyMember> members = new ArrayList<>();

    // ── Status ────────────────────────────────────────────────────────────

    /**
     * When {@code true} the family is marked inactive and excluded from
     * active-member queries.  Defaults to {@code false} on insert.
     */
    @Column(name = "inactive", nullable = false)
    private boolean inactive;

    // ── Soft-Delete / Multi-Tenant ────────────────────────────────────────

    /** Optional org identifier from the app_user who created this record. */
    @Column(name = "app_client_id")
    private String appClientId;

    /** Soft-delete flag. Defaults to {@code false} on insert. */
    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag;

    // ── Audit ─────────────────────────────────────────────────────────────

    /** Timestamp set automatically on first insert. Never updated. */
    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @PrePersist
    protected void onCreate() {
        this.createdDate = new Date();
        this.deleteFlag  = false;
    }
}
