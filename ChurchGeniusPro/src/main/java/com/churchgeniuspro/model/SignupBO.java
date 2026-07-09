package com.churchgeniuspro.model;

import lombok.Data;

/**
 * Business Object for the sign-up form submitted from {@code signup.html}.
 *
 * <p>Field mapping:
 * <pre>
 * Form field   BO field    Target entity / column
 * ──────────   ─────────   ──────────────────────
 * clientId     clientId    SignUp.clientId   (passed via URL query param)
 * username     username    SignUp.username   (mandatory, unique)
 * password     password    SignUp.password   (mandatory, min 8 chars)
 * </pre>
 *
 * <p>{@code confirmPassword} is validated client-side only and is not
 * sent to the backend.
 */
@Data
public class SignupBO {

    /**
     * Client identifier token — passed in the invitation link as
     * {@code /signup?clientId=<uuid>} and forwarded transparently to
     * {@link com.churchgeniuspro.hibernate.SignUp#getClientId()}.
     */
    private String clientId;

    /** Chosen username. Must be unique across the {@code signup} table. */
    private String username;

    /** Plain-text password supplied by the user. */
    private String password;
}
