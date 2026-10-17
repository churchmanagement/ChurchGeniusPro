package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;

import java.util.Date;

/**
 * Represents a person (member) belonging to a {@link Group}.
 * Mapped to the {@code group_member} table.
 */
@Data
@Entity
@Table(name = "group_member")
public class GroupMember {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "group_member_seq")
    @SequenceGenerator(name = "group_member_seq", sequenceName = "group_member_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private Group group;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "email")
    private String email;

    /**
     * ETL Migration Phase 4 (approved decision 6): the member this membership belongs to,
     * when an import could resolve one. Nullable and informational — nothing in the Groups
     * feature reads or maintains it, so memberships added or edited in the app leave it null.
     */
    @Column(name = "family_member_id")
    private Integer familyMemberId;

    /** Optional org identifier from the app_user who created this record. Null for church-level accounts. */
    @Column(name = "app_client_id")
    private String appClientId;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag;

    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    @PrePersist
    protected void onCreate() {
        this.createdDate = new Date();
        this.deleteFlag  = false;
    }
}
