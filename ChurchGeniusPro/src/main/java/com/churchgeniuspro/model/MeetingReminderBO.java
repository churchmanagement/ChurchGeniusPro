package com.churchgeniuspro.model;

import lombok.Data;

/**
 * Request/response body for MeetingReminder CRUD operations.
 */
@Data
public class MeetingReminderBO {

    private Integer meetingId;

    /** Comma-separated: "Members", "Guests" */
    private String  recipients;

    private Boolean disabled;
    private String  note;
}
