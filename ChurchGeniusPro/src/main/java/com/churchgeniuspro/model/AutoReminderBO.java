package com.churchgeniuspro.model;

import lombok.Data;

/**
 * Request/response body for AutoReminder CRUD operations.
 */
@Data
public class AutoReminderBO {

    /** FK to {@code auto_reminder_types.id}. */
    private Integer reminderTypeId;

    /** Display name — mirrors the type name. */
    private String name;

    /** Comma-separated: "Members", "Guests", "Celebrant" */
    private String recipients;

    private String  imageData;
    private Boolean disabled;
    private String  note;

    /** Custom holiday email template + SMS message (blank = use default). */
    private String  emailTemplate;
    private String  smsTemplate;

    /** Notification channels */
    private Boolean sendEmail;
    private Boolean sendSms;
    private Boolean sendWhatsApp;
}
