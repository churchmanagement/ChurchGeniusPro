package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.util.Date;

/**
 * Links a {@link FamilyMember} to the prayer ministry team.
 *
 * <p>The Prayer Volunteer module is intentionally separate from FamilyMember
 * so the family directory stays clean and a single member can be added /
 * removed from the prayer team without editing their core record. A volunteer
 * has an optional free-text role (e.g. "Team Lead", "Intercessor") and an
 * {@code active} flag so leaders can mark people inactive without losing
 * their assignment history.
 *
 * <p>One-volunteer-per-member is enforced by application logic, not a DB
 * unique constraint — we want to allow re-adding a removed volunteer (soft
 * delete) without DDL gymnastics.
 */
@Data
@Entity
@Table(name = "prayer_volunteer")
public class PrayerVolunteer {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "prayer_volunteer_seq")
    @SequenceGenerator(name = "prayer_volunteer_seq", sequenceName = "prayer_volunteer_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /**
     * Optional FK → family_member.id. Null for manually-added volunteers
     * who are not in the family directory. The unique-by-member rule still
     * applies when this is set; manual volunteers can be added freely.
     */
    @Column(name = "family_member_id")
    private Integer familyMemberId;

    /** Optional role label shown in the volunteer chip. */
    @Column(name = "role", length = 100)
    private String role;

    // ── Manual-volunteer fields (used when familyMemberId is null) ────────
    // Linked-member volunteers leave these blank; the display name + contact
    // come from FamilyMember. Manual volunteers populate them directly.

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "email", length = 200)
    private String email;

    /** Free-text notes — admin comments about this volunteer. */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** Free-text availability (e.g. "Mon/Wed evenings"). */
    @Column(name = "availability", length = 200)
    private String availability;

    /** True = receives new assignments; false = retained for history only. */
    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag;

    @Column(name = "created_date")
    private Date createdDate;

    @PrePersist
    void onCreate() {
        if (createdDate == null) createdDate = new Date();
        // Caller may already have set these — only default when null.
        // (boolean primitives default to false, so we want active=true.)
    }
}
