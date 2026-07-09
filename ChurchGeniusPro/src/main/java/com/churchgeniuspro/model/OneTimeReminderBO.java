package com.churchgeniuspro.model;

import lombok.Data;

/**
 * Request/response body for OneTimeReminder CRUD operations.
 */
@Data
public class OneTimeReminderBO {

    private String name;

    /** ISO date string "YYYY-MM-DD". */
    private String  eventDate;

    /** Comma-separated: "Members", "Guests" */
    private String  recipients;

    private String  imageData;
    private Boolean disabled;
    private String  note;

    /** Notification channels */
    private Boolean sendEmail;
    private Boolean sendSms;
    private Boolean sendWhatsApp;
}
