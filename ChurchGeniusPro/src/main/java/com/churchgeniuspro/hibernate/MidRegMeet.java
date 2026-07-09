package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.util.Date;

/**
 * Stores the Midwest Region Meet event configuration (one record per org).
 * Hibernate auto-creates / updates the {@code mid_reg_meet} table.
 */
@Data
@Entity
@Table(name = "mid_reg_meet",
       uniqueConstraints = @UniqueConstraint(name = "uq_mid_reg_meet_client", columnNames = {"client_id"}))
public class MidRegMeet {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "mid_reg_meet_seq")
    @SequenceGenerator(name = "mid_reg_meet_seq", sequenceName = "mid_reg_meet_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, unique = true, length = 100)
    private String clientId;

    @Column(name = "event_name", length = 300)
    private String eventName;

    @Column(name = "event_address", columnDefinition = "TEXT")
    private String eventAddress;

    /**
     * JSON array of day objects: [{date, startTime, endTime}, …]
     * e.g. [{"date":"2026-05-20","startTime":"18:30","endTime":"21:00"}]
     */
    @Column(name = "event_days", columnDefinition = "TEXT")
    private String eventDays;

    /** Raw bytes of the uploaded t-shirt image. */
    @Column(name = "tshirt_image", columnDefinition = "bytea")
    private byte[] tshirtImage;

    @Column(name = "tshirt_image_content_type", length = 100)
    private String tshirtImageContentType;

    /** Raw bytes of the uploaded event flyer image. */
    @Column(name = "flyer_image", columnDefinition = "bytea")
    private byte[] flyerImage;

    @Column(name = "flyer_image_content_type", length = 100)
    private String flyerImageContentType;

    @Column(name = "registration_end_date")
    private Date registrationEndDate;

    /** Sender display name shown in outgoing emails/SMS. */
    @Column(name = "sender_name", length = 200)
    private String senderName;

    @Column(name = "sender_email", length = 200)
    private String senderEmail;

    @Column(name = "sender_phone", length = 50)
    private String senderPhone;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    /** When true, the public RSVP page offers a checkbox to cover the Stripe transaction fee (2.9% + $0.30). */
    @Column(name = "include_transaction_fee", nullable = false, columnDefinition = "boolean not null default false")
    private boolean includeTransactionFee = false;

    @Column(name = "updated_at")
    private Date updatedAt;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        this.updatedAt = new Date();
    }
}
