package com.churchgeniuspro.model;

import lombok.Data;

import java.util.List;

/**
 * Business Object representing the Save Event form submission from
 * {@code event.html}.
 *
 * <p>Received as the JSON request body of {@code POST /api/events} and
 * {@code PUT /api/events/{id}}, then forwarded to
 * {@link com.churchgeniuspro.service.ChurchEventService}.
 */
@Data
public class ChurchEventBO {

    // ── Core ──────────────────────────────────────────────────────────────

    private String eventName;

    /** Optional unique short code (e.g. "EVT-A3B7C2"). */
    private String eventCode;

    /** "One Day" or "Multiple Days" */
    private String eventType;

    // ── One Day schedule ─────────────────────────────────────────────────

    /** ISO date: "YYYY-MM-DD" (One Day events only) */
    private String eventDate;

    /** 24-hour time "HH:mm" (One Day events only) */
    private String startTime;

    /** 24-hour time "HH:mm" (One Day events only) */
    private String endTime;

    // ── Multiple Days schedule ────────────────────────────────────────────

    /** Day rows for Multiple Days events (null / empty for One Day events). */
    private List<DayBO> days;

    // ── Registration ──────────────────────────────────────────────────────

    /** ISO date: "YYYY-MM-DD" */
    private String registrationEndDate;

    private String registrationLink;
    private String fee;
    private Integer maxCapacity;

    /** Whether to show the registrants list on the public registration page. */
    private Boolean showRegistrants;

    /** When {@code true}, the "Maybe" RSVP option appears on the public registration page. */
    private Boolean allowMaybeRsvp;

    /**
     * When {@code true}, a unique registration code and QR code are generated
     * for each registrant after they submit their RSVP.
     */
    private Boolean generateQrCode;

    /** When {@code true}, a short Registration ID is shown/printed on the check-in label. */
    private Boolean generateRegistrationId;

    /**
     * When {@code true}, registrants can self check-in on mobile via
     * the "I'm Here" button on the public event registration page.
     */
    private Boolean selfCheckinEnabled;

    // ── Location ──────────────────────────────────────────────────────────

    private String address1;
    private String address2;
    private String city;
    private String state;
    private String country;
    private String pinCode;

    // ── Host contact ──────────────────────────────────────────────────────

    private String hostName;
    private String hostPhone;
    private String hostEmail;
    private String hostNote;

    // ── Details ───────────────────────────────────────────────────────────

    private String note;

    /** Whether food is available at this event. */
    private Boolean foodAvailable;

    /**
     * JSON array of food item names (e.g. "[\"Pasta\",\"Chicken\"]").
     * Only relevant when foodAvailable is true.
     */
    private String foodItems;

    /** Configurable label for the food section on the registration page (default "Dietary Preferences"). */
    private String foodLabel;

    /** Whether accommodation is available. */
    private Boolean accommodationAvailable;

    private String accommodationAddress;
    private String accommodationComments;

    /** Base64 data-URI string, or null if no image uploaded */
    private String imageData;

    // ── Inner class ───────────────────────────────────────────────────────

    @Data
    public static class DayBO {
        /** ISO date: "YYYY-MM-DD" */
        private String eventDate;
        private String startTime;
        private String endTime;
        private int dayOrder;
        /** Whether food is available on this specific day. */
        private Boolean foodAvailable;
    }
}
