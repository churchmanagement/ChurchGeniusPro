package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;

import java.util.Date;

/**
 * A reusable, tenant-scoped message template for meeting notifications.
 *
 * <p>The {@link #body} contains free text plus placeholders ({@code {Category}},
 * {@code {Date}}, {@code {Time}}, {@code {LocationName}}, {@code {LocationAddress}},
 * {@code {Notes}}, {@code {Image}}) that are substituted with a meeting's data when
 * the message is previewed or sent. {@link #dateFormat} / {@link #timeFormat} record
 * the author's preferred {Date}/{Time} rendering. One template per client may be
 * flagged {@link #isDefault}; when none is, a built-in default is used.
 */
@Data
@Entity
@Table(name = "meeting_message_template")
public class MeetingMessageTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "meeting_message_template_seq")
    @SequenceGenerator(
            name           = "meeting_message_template_seq",
            sequenceName   = "meeting_message_template_id_seq",
            allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    /** Date-format key for the {Date} placeholder (e.g. "MM/dd/yyyy"). */
    @Column(name = "date_format", length = 40)
    private String dateFormat;

    /** Time-format key for the {Time} placeholder: "start" or "startEnd". */
    @Column(name = "time_format", length = 20)
    private String timeFormat;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "app_client_id")
    private String appClientId;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag;

    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    @PrePersist
    protected void onCreate() {
        this.createdDate = new Date();
    }
}
