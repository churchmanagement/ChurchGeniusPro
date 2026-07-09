package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

/**
 * Stores Twilio / WhatsApp credentials for one organization.
 * One record per org is enforced by the unique constraint on {@code client_id}.
 */
@Data
@Entity
@Table(name = "whatsapp_settings",
       uniqueConstraints = @UniqueConstraint(name = "uq_whatsapp_settings_client", columnNames = {"client_id"}))
public class WhatsAppSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "whatsapp_settings_seq")
    @SequenceGenerator(name = "whatsapp_settings_seq", sequenceName = "whatsapp_settings_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Organization identifier — one record per org. */
    @Column(name = "client_id", nullable = false, unique = true)
    private String clientId;

    /** Twilio Account SID (ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx). */
    @Column(name = "account_sid", length = 200)
    private String accountSid;

    /** Twilio Auth Token — keep confidential. */
    @Column(name = "auth_token", length = 500)
    private String authToken;

    /** WhatsApp-enabled sender phone number (e.g. +15005550006). */
    @Column(name = "sender_phone", length = 50)
    private String senderPhone;

    /** Default outgoing message template. */
    @Column(name = "message_template", length = 1000)
    private String messageTemplate;

    /**
     * Twilio Content Template SID used for Meeting Reminder WhatsApp messages.
     * Format: HXxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.
     * When set, meeting reminders are sent via the Twilio Content Template API
     * instead of a plain-text body, enabling rich pre-approved WhatsApp templates.
     */
    @Column(name = "meeting_reminder_content_sid", length = 100)
    private String meetingReminderContentSid;
}
