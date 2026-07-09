package com.churchgeniuspro.model;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

/**
 * Business Object representing the Church Registration form submission
 * from {@code churchregistration.html}.
 *
 * <p>This class carries the raw form values POSTed to
 * {@code POST /api/churchregistration} and is used at the service layer
 * to populate the corresponding Hibernate entities
 * ({@code ChurchSetUp}, {@code AddressDTO}, {@code Payment}, {@code Subscription}).
 *
 * <p>Field mapping:
 * <pre>
 * Form field          BO field            Target entity / column
 * ─────────────────   ─────────────────   ──────────────────────────────
 * id          (hidden) id                 ChurchSetUp.id
 * clientId    (hidden) clientId           ChurchSetUp.clientId
 * firstName            firstName          ChurchSetUp.firstName  (mandatory)
 * lastName             lastName           ChurchSetUp.lastName   (mandatory)
 * Church Name          churchName         ChurchSetUp.orgName
 * Email                email              ChurchSetUp.email      (mandatory)
 * Phone                phone              ChurchSetUp.phone
 * Address Line 1       address1           AddressDTO.address1
 * Address Line 2       address2           AddressDTO.address2
 * City                 city               AddressDTO.city
 * State                state              AddressDTO.state  (1–50, States.java)
 * Country              country            AddressDTO.country (default: "USA")
 * Pin / Zip Code       pinCode            AddressDTO.pinCode
 * EIN                  ein                ChurchSetUp.ein
 * Payment ID           payment            Payment.id  (FK)
 * Subscription ID      subscription       Subscription.id (FK)
 * Terms of Use         declarationCheck   ChurchSetUp.declaration
 * </pre>
 */

@Data
public class ChurchRegistrationBO {

    // ── Hidden Fields ─────────────────────────────────────────────────────

    /** Primary key — null on new registration, populated on update. */
    private Integer id;

    /** Client identifier — assigned by the backend. */
    public String clientId;

    // ── Personal Information ──────────────────────────────────────────────

    /** First name of the registrant. Mandatory. */
    @Getter @Setter
    private String firstName;

    /** Last name of the registrant. Mandatory. */
    private String lastName;

    /** Name of the church being registered. */
    private String churchName;

    /** Contact email address. Mandatory. */
    private String email;

    /** Contact phone number. */
    private String phone;

    // ── Address Fields ────────────────────────────────────────────────────

    /** Street address line 1 — maps to {@code AddressDTO.address1}. */
    private String address1;

    /** Street address line 2 — maps to {@code AddressDTO.address2}. */
    private String address2;

    /** City — maps to {@code AddressDTO.city}. */
    private String city;

    /**
     * Numeric state code (1–50) — maps to {@code AddressDTO.state}.
     * Values are defined in {@code com.churchgeniuspro.common.States}.
     */
    private Integer state;

    /**
     * Country code — maps to {@code AddressDTO.country}.
     * Defaults to {@code "USA"}.
     */
    private String country = "USA";

    /** Postal / ZIP code — maps to {@code AddressDTO.pinCode}. */
    private String pinCode;

    // ── Church Details ────────────────────────────────────────────────────

    /** Employer Identification Number — maps to {@code ChurchSetUp.ein}. */
    private String ein;

    // ── Website & Social Media (optional) ─────────────────────────────────
    private String websiteUrl;
    private String facebookUrl;
    private String instagramUrl;
    private String youtubeUrl;

    // ── Account References ────────────────────────────────────────────────

    /**
     * Foreign key referencing {@code Payment.id}.
     * Links this registration to an existing payment record.
     */
    private Integer payment;

    /**
     * Foreign key referencing {@code Subscription.id}.
     * Links this registration to an existing subscription record.
     */
    private Integer subscription;

    // ── Declaration ───────────────────────────────────────────────────────

    /**
     * Indicates the user has accepted the Terms of Use.
     * {@code true} = accepted; form submission is blocked when {@code false}.
     */
    private Boolean declarationCheck;
}
