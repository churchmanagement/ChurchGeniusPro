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
 * A reusable, tenant-scoped RSVP confirmation email template.
 *
 * <p>The {@link #body} contains free text plus placeholders
 * ({@code {Registrant Name}}, {@code {Event Name}}, {@code {Date}}, {@code {Time}},
 * {@code {Location}}, {@code {Contact}}, {@code {Google Calendar Link}},
 * {@code {Directions Link}}) that are substituted with a registration's data when
 * the confirmation email is sent. One template per client may be flagged
 * {@link #isDefault}; when none is, a built-in default is used.
 */
@Data
@Entity
@Table(name = "event_email_template")
public class EventEmailTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "event_email_template_seq")
    @SequenceGenerator(
            name           = "event_email_template_seq",
            sequenceName   = "event_email_template_id_seq",
            allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

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
