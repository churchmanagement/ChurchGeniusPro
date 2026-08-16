package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;

/**
 * One submission of the public "Connect With Us" form ({@code /connect?c=...}).
 *
 * <p>The submission still creates a Visitor {@link FamilyMember} (as before),
 * but this row is the admin-facing record: it carries the submitted details,
 * a workflow status, the assigned volunteer/staff member, and links to the
 * created member. Follow-up history is kept in {@link FollowUp} rows with
 * {@code linkedType="CONNECT"} and {@code linkedId=} this id.</p>
 */
@Entity
@Table(name = "connect_submission")
@Getter
@Setter
@NoArgsConstructor
public class ConnectSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", length = 64, nullable = false)
    private String clientId;

    /** The Visitor FamilyMember created from this submission (may be null on failure). */
    @Column(name = "member_id")
    private Integer memberId;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "email", length = 200)
    private String email;

    @Column(name = "phone", length = 40)
    private String phone;

    @Column(name = "address", length = 300)
    private String address;

    @Column(name = "gender", length = 20)
    private String gender;

    @Column(name = "marital_status", length = 30)
    private String maritalStatus;

    @Column(name = "birth_date", length = 20)
    private String birthDate;

    @Column(name = "contact_preferences", length = 120)
    private String contactPreferences;

    @Column(name = "how_heard", columnDefinition = "TEXT")
    private String howHeard;

    /** New | Assigned | InProgress | Contacted | Closed */
    @Column(name = "status", length = 20)
    private String status = "New";

    /** FamilyMember id of the assigned volunteer/staff person (nullable). */
    @Column(name = "assigned_member_id")
    private Integer assignedMemberId;

    /** Denormalized display name of the assignee. */
    @Column(name = "assigned_to", length = 150)
    private String assignedTo;

    /** The auto-created welcome FollowUp (nullable). */
    @Column(name = "follow_up_id")
    private Long followUpId;

    /** Whether the confirmation auto-response was sent (EMAIL / SMS / EMAIL+SMS / NONE). */
    @Column(name = "confirmation_sent", length = 20)
    private String confirmationSent;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Date createdAt;

    @Column(name = "updated_at")
    private Date updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = new Date();
        this.updatedAt = new Date();
        if (this.status == null) this.status = "New";
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = new Date();
    }
}
