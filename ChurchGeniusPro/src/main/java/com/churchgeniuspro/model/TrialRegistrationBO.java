package com.churchgeniuspro.model;

import lombok.Data;

/**
 * What the public TrialRegistration form submits.
 *
 * <p>A plain request object, like the other {@code *BO} classes — not an entity.
 * Validation lives in {@link #validate()} rather than in the controller so the
 * same rules apply however the service is called.
 */
@Data
public class TrialRegistrationBO {

    private String churchName;
    private String firstName;
    private String lastName;
    private String phone;
    private String email;
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    /** Defaulted by the form to "USA – United States"; free text, as service_client stores it. */
    private String country;
    private String pinCode;
    private String note;

    /**
     * Trial length in days, taken from the invitation link — never from the public
     * form. {@code TrialRegistrationController.toBo} does not read it from the request
     * body, and {@code @JsonIgnore} keeps it out of any JSON binding too.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Integer trialDays;

    /** Sample-data trial (the original kind: a {@code TRIAL-} tenant with demo data). */
    public static final String SAMPLE = "SAMPLE";
    /** Empty-account trial: a regular client on the Trial plan, convertible to a paid plan later. */
    public static final String EMPTY  = "EMPTY";

    /**
     * Which kind of trial account to create: {@link #SAMPLE} or {@link #EMPTY}. The page
     * requires the choice; a request without one (a page cached from before the choice
     * existed) gets the original sample-data trial.
     */
    private String accountType;

    /** {@link #SAMPLE} or {@link #EMPTY}; anything else is refused. */
    public String accountTypeOrDefault() {
        if (accountType == null || accountType.isBlank()) return SAMPLE;
        String t = accountType.trim().toUpperCase();
        if (t.equals(SAMPLE) || t.equals(EMPTY)) return t;
        throw new IllegalArgumentException("Please choose how your trial account should be created.");
    }

    /**
     * Checks the fields the flow cannot proceed without.
     *
     * <p>Only five are required. The rest are recorded as given, because a
     * registration is a sales lead first and a data record second — refusing one
     * over a missing address line costs more than the blank field does.
     *
     * <p>The email is format-checked but NOT verified: nothing here waits on a
     * confirmation click. A wrong address costs an unusable tenant, not access,
     * since the SuperAdmin cannot sign in until someone opens the emailed link.
     *
     * @throws IllegalArgumentException with a message meant for the person filling
     *         in the form
     */
    public void validate() {
        require(churchName, "Church name is required.");
        require(firstName,  "First name is required.");
        require(lastName,   "Last name is required.");
        require(email,      "Email address is required.");

        String e = email.trim();
        // Deliberately loose: a stricter pattern rejects valid addresses far more
        // often than it catches typos, and nothing downstream trusts this value.
        if (!e.matches("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$")) {
            throw new IllegalArgumentException("Please enter a valid email address.");
        }
        if (churchName.trim().length() > 200) {
            throw new IllegalArgumentException("Church name is too long.");
        }
        accountTypeOrDefault();
    }

    private static void require(String value, String message) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(message);
    }
}
