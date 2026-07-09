package com.churchgeniuspro.model;

import lombok.Data;

/**
 * Request/response body for EventReminder CRUD operations.
 */
@Data
public class EventReminderBO {

    private Integer eventId;
    private Boolean sameDay;
    private Integer beforeDays;
    private Integer afterDays;

    /** Custom email templates per timing (blank = use default). */
    private String sameDayTemplate;
    private String beforeDaysTemplate;
    private String afterDaysTemplate;

    /** Comma-separated: "Members", "Guests" */
    private String recipients;

    private Boolean disabled;
    private String  note;

    /** Notification channels */
    private Boolean sendEmail;
    private Boolean sendSms;
    private Boolean sendWhatsApp;
}
