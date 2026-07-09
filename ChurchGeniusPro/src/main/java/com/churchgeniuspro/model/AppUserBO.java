package com.churchgeniuspro.model;

import lombok.Data;

/**
 * Request body for creating or updating an {@code AppUser}.
 */
@Data
public class AppUserBO {
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String address1;
    private String address2;
    private String city;
    private String state;
    private String country;
    private String pinCode;
    private String role;
    /** Organization identifier (e.g. {@code CGP-00001}) from the logged-in session. */
    private String clientId;

    /** Granular privilege map as a JSON string (may be null = full access). */
    private String privileges;
}
