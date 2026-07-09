package com.churchgeniuspro.model;

import lombok.Data;

/**
 * Business Object representing a single family member entry from the
 * Add Member form in {@code family.html}.
 *
 * <p>Date parts (month, day, year) are received as {@code String} values
 * because the frontend sends the raw {@code <select>} values which may be
 * empty strings. The service layer converts them to {@code Integer} when
 * persisting to {@link com.churchgeniuspro.hibernate.FamilyMember}.
 */
@Data
public class FamilyMemberBO {

    // ── DB identity (present only when updating an existing member) ──────

    /** DB primary key of an existing {@link com.churchgeniuspro.hibernate.FamilyMember}.
     *  {@code null} for brand-new members being added for the first time. */
    private Integer id;

    // ── Role ──────────────────────────────────────────────────────────────

    /** Role within the family: Head, Wife, Son, Daughter, etc. */
    private String role;

    // ── Personal Information ──────────────────────────────────────────────

    private String firstName;
    private String middleName;
    private String lastName;
    private String phone;
    private String email;

    /** Member or Guest. */
    private String memberType;

    /** Gender: Male, Female, or null/blank if not specified. */
    private String gender;

    // ── Birthday ──────────────────────────────────────────────────────────

    private String birthdayMonth;
    private String birthdayDay;
    private String birthdayYear;

    // ── Wedding Anniversary ───────────────────────────────────────────────

    private String anniversaryMonth;
    private String anniversaryDay;
    private String anniversaryYear;

    // ── Address ───────────────────────────────────────────────────────────

    private String address1;
    private String address2;
    private String city;
    private String state;
    private String country;
    private String pinCode;

    /** Mirrors the "Same As Family Address" checkbox on the form. */
    private boolean sameAsFamilyAddress;

    // ── Field-level privacy (hide from non-staff member-facing views) ──────
    private boolean phonePrivate;
    private boolean emailPrivate;
    private boolean addressPrivate;

    // ── Other Details ─────────────────────────────────────────────────────

    private String  otherName;
    private String  comments;
    /** Base64 data-URI of the member's photo (e.g. "data:image/png;base64,..."). */
    private String  photoData;
    private boolean inactive;
    private boolean disableAlerts;
    private boolean includeContributions;
}
