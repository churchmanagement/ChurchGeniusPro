package com.churchgeniuspro.model;

import lombok.Data;

/**
 * Business Object representing an event registration form submission.
 *
 * <p>Received as the JSON request body of {@code POST /api/event-register/{eventId}}
 * (public endpoint, no session required), then forwarded to
 * {@link com.churchgeniuspro.service.ChurchEventService}.
 */
@Data
public class EventRegistrationBO {

    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private int adults;
    private int kids;

    /** {@code true} = attending, {@code false} = not attending, {@code null} = maybe. */
    private Boolean attending;

    private String note;

    /** {@code true} = vegetarian preference (when food is available). */
    private Boolean vegetarian;

    /**
     * JSON array of selected food item names when the event defines specific food options
     * (e.g. "[\"Pasta\",\"Chicken Biryani\"]").
     */
    private String selectedFoodItems;

    /** JSON-encoded array of day-order numbers for Multiple Days events, e.g. "[1,3]". */
    private String attendingDays;

    // Walk-in address fields
    private String address1;
    private String address2;
    private String city;
    private String state;
    private String zipCode;

    /** True when submitted as a walk-in at the event (admin check-in page). */
    private Boolean walkIn;
}
