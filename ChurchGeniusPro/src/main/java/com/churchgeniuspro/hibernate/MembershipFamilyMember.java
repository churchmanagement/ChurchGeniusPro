package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.util.Date;

/**
 * Stores individual member records submitted via the public membership form.
 * Approved records are transferred to the {@code family_member} table
 * with {@code memberType = "Member"}.
 */
@Data
@Entity
@Table(name = "membership_family_member")
public class MembershipFamilyMember {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "membership_family_member_seq")
    @SequenceGenerator(name = "membership_family_member_seq", sequenceName = "membership_family_member_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "membership_family_id", nullable = false)
    private MembershipFamily membershipFamily;

    @Column(name = "role")
    private String role;

    /**
     * Gender of this member. Values: Male, Female, or {@code null} if not specified.
     */
    @Column(name = "gender")
    private String gender;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "middle_name")
    private String middleName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "phone")
    private String phone;

    @Column(name = "email")
    private String email;

    /** Always "Member" — set at save time, not editable by the submitter. */
    @Column(name = "member_type")
    private String memberType = "Member";

    @Column(name = "birthday_month")
    private Integer birthdayMonth;

    @Column(name = "birthday_day")
    private Integer birthdayDay;

    @Column(name = "birthday_year")
    private Integer birthdayYear;

    @Column(name = "anniversary_month")
    private Integer anniversaryMonth;

    @Column(name = "anniversary_day")
    private Integer anniversaryDay;

    @Column(name = "anniversary_year")
    private Integer anniversaryYear;

    @Column(name = "address1")
    private String address1;

    @Column(name = "address2")
    private String address2;

    @Column(name = "city")
    private String city;

    @Column(name = "state")
    private String state;

    @Column(name = "country")
    private String country;

    @Column(name = "pin_code")
    private String pinCode;

    @Column(name = "same_as_family_address")
    private boolean sameAsFamilyAddress = true;

    /** Privacy flags from the public form's "Make it Private" checkboxes. */
    @Column(name = "phone_private")   private Boolean phonePrivate;
    @Column(name = "email_private")   private Boolean emailPrivate;
    @Column(name = "address_private") private Boolean addressPrivate;

    @Column(name = "other_name")
    private String otherName;

    @Column(name = "comments", columnDefinition = "TEXT")
    private String comments;

    /** Base64 data-URI photo uploaded via the public membership form (e.g. "data:image/png;base64,..."). */
    @Column(name = "photo_data", columnDefinition = "TEXT")
    private String photoData;

    /** Stored as {@code false} — hidden from the public form. */
    @Column(name = "inactive")
    private boolean inactive = false;

    /** Stored as {@code false} — hidden from the public form. */
    @Column(name = "disable_alerts")
    private boolean disableAlerts = false;

    /** Stored as {@code false} — hidden from the public form. */
    @Column(name = "include_contributions")
    private boolean includeContributions = false;

    @Column(name = "app_client_id")
    private String appClientId;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;

    @Column(name = "created_date", nullable = false, updatable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date createdDate;

    @PrePersist
    protected void onCreate() {
        this.createdDate         = new Date();
        this.deleteFlag          = false;
        this.memberType          = "Member";
        this.inactive            = false;
        this.disableAlerts       = false;
        // includeContributions is set by the controller based on role (true for Head)
    }
}
