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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.ToString;

import java.util.Date;
import java.util.UUID;

/**
 * Hibernate entity for the {@code family_member} table.
 *
 * <p>Each row represents one person within a {@link Family} group.
 * The relationship is Many-to-One: many members belong to one family.
 *
 * <p>Date parts (month, day, year) are stored as separate {@code Integer}
 * columns to mirror the three-dropdown UI in {@code family.html}.
 * A {@code null} value means the field was left blank on the form.
 */
@Data
@ToString(exclude = "family")
@Entity
@Table(name = "family_member")
public class FamilyMember {

    // ── Primary Key ───────────────────────────────────────────────────────

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "family_member_seq")
    @SequenceGenerator(
            name           = "family_member_seq",
            sequenceName   = "family_member_id_seq",
            allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    // ── Parent Family ─────────────────────────────────────────────────────

    /** The family this member belongs to. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "family_id", nullable = false)
    private Family family;

    // ── Role ──────────────────────────────────────────────────────────────

    /**
     * Role of this member within the family.
     * Values: Head, Wife, Son, Daughter, Father, Mother,
     *         Mother-in-Law, Father-in-Law.
     */
    @Column(name = "role")
    private String role;

    // ── Personal Information ──────────────────────────────────────────────

    @Column(name = "first_name")  private String firstName;
    @Column(name = "middle_name") private String middleName;
    @Column(name = "last_name")   private String lastName;
    @Column(name = "phone")       private String phone;
    @Column(name = "email")       private String email;

    /**
     * Membership classification. Values: Member, Guest.
     */
    @Column(name = "member_type") private String memberType;

    /**
     * Gender of this member. Values: Male, Female, or {@code null} if not specified.
     */
    @Column(name = "gender") private String gender;

    // ── Birthday ──────────────────────────────────────────────────────────

    @Column(name = "birthday_month") private Integer birthdayMonth;
    @Column(name = "birthday_day")   private Integer birthdayDay;
    @Column(name = "birthday_year")  private Integer birthdayYear;

    // ── Wedding Anniversary ───────────────────────────────────────────────

    /**
     * Anniversary fields. For a Wife member these are auto-filled from the
     * Head member's anniversary on the frontend.
     */
    @Column(name = "anniversary_month") private Integer anniversaryMonth;
    @Column(name = "anniversary_day")   private Integer anniversaryDay;
    @Column(name = "anniversary_year")  private Integer anniversaryYear;

    // ── Address ───────────────────────────────────────────────────────────

    /** Address stored inline per member (auto-filled from Head on the UI). */
    @Column(name = "address1") private String address1;
    @Column(name = "address2") private String address2;
    @Column(name = "city")     private String city;
    @Column(name = "state")    private String state;
    @Column(name = "country")  private String country;
    @Column(name = "pin_code") private String pinCode;

    // ── Field-level privacy ───────────────────────────────────────────────
    // When true, the corresponding field is hidden from non-staff (member
    // portal / public) views everywhere. Staff always see the value (tagged
    // with a lock indicator). Null = not private (default for existing rows).
    // addressPrivate covers the whole address block (address1/2, city, state,
    // pin/zip, country).
    @Column(name = "phone_private")   private Boolean phonePrivate;
    @Column(name = "email_private")   private Boolean emailPrivate;
    @Column(name = "address_private") private Boolean addressPrivate;

    /**
     * Whether the member's address was filled by checking
     * "Same As Family Address" on the form.  When {@code true} the UI
     * re-checks the checkbox and locks the address fields on load.
     */
    @Column(name = "same_as_family_address")
    private boolean sameAsFamilyAddress;

    // ── Other Details ─────────────────────────────────────────────────────

    /**
     * Nickname / preferred name (e.g. "Johnny" for John Smith), entered via the
     * "Nick Name" field on the Family form. Displayed alongside the legal name
     * as "First Last (Nickname)" across the app — but NOT on legal documents
     * (tax reports, certificates).
     */
    @Column(name = "other_name") private String otherName;
    @Column(name = "comments")   private String comments;

    /** Base64 data-URI of the member's photo (e.g. "data:image/png;base64,..."). */
    @Column(name = "photo_data", columnDefinition = "TEXT")
    private String photoData;

    /**
     * Pre-computed 60 px JPEG thumbnail of {@link #photoData}.
     * Generated automatically whenever {@code photoData} is set via
     * {@link com.churchgeniuspro.service.FamilyService}.
     * Used by the viewfamily list endpoint to avoid resizing on every page load.
     */
    @Column(name = "photo_thumbnail", columnDefinition = "TEXT")
    private String photoThumbnail;

    @Column(name = "inactive")              private boolean inactive;
    @Column(name = "disable_alerts")        private boolean disableAlerts;
    @Column(name = "include_contributions") private boolean includeContributions;

    /**
     * JSON map of member portal privilege keys (e.g. {@code {"member.family":true, "member.give":false}}).
     * {@code null} means "no restrictions" — all member tabs are allowed.
     * Managed by admins via the Account Permissions modal in viewusers.html.
     */
    @Column(name = "member_privileges", columnDefinition = "TEXT")
    private String memberPrivileges;

    // ── Soft-Delete ───────────────────────────────────────────────────────

    /** Optional org identifier from the app_user who created this record. Null for church-level accounts. */
    @Column(name = "app_client_id")
    private String appClientId;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag;

    /**
     * Unique member reference token in the format {@code MBR<uuid>}.
     * Generated automatically on insert; never changed after that.
     * Used to identify this member in invitation links.
     */
    @Column(name = "member_ref", unique = true)
    private String memberRef;

    // ── Audit ─────────────────────────────────────────────────────────────

    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @PrePersist
    protected void onCreate() {
        this.createdDate = new Date();
        this.deleteFlag  = false;
        if (this.memberRef == null) {
            this.memberRef = "MBR" + UUID.randomUUID().toString();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        if (this.memberRef == null) {
            this.memberRef = "MBR" + UUID.randomUUID().toString();
        }
    }
}
