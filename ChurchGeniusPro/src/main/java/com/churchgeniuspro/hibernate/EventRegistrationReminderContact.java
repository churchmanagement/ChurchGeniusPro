package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;

import java.util.Date;

/**
 * One email / phone combination on an event's Registration Reminder list.
 *
 * <p>Each row belongs to a {@link ChurchEvent} whose
 * {@code registrationReminderEnabled} flag activates the feature. Either field
 * may be blank, but at least one of {@code email} / {@code phone} must be
 * present (enforced by the controller). On the configured reminder date the
 * scheduler checks whether the email or phone is already registered for the
 * event and, if neither is, sends an email and/or SMS nudging the person to
 * register (see {@code EventRegistrationReminderService}).
 */
@Data
@Entity
@Table(name = "event_registration_reminder_contact",
       indexes = @Index(name = "idx_errc_event", columnList = "event_id"))
public class EventRegistrationReminderContact {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "err_contact_seq")
    @SequenceGenerator(
            name           = "err_contact_seq",
            sequenceName   = "event_registration_reminder_contact_id_seq",
            allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    /** FK to {@code church_event.id}. */
    @Column(name = "event_id", nullable = false)
    private Integer eventId;

    /** Recipient email address — optional (may be blank when {@code phone} is set). */
    @Column(name = "email")
    private String email;

    /** Recipient phone number as entered — optional (may be blank when {@code email} is set). */
    @Column(name = "phone", length = 40)
    private String phone;

    /** Org scoping — matches {@code church_event.app_client_id}. */
    @Column(name = "app_client_id")
    private String appClientId;

    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    @PrePersist
    protected void onCreate() {
        this.createdDate = new Date();
    }
}
