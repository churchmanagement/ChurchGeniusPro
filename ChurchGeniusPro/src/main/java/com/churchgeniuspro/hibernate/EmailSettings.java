package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

/**
 * Single email-configuration record per organization.
 * A unique constraint on {@code client_id} enforces the one-record-per-org rule.
 */
@Data
@Entity
@Table(name = "email_settings",
       uniqueConstraints = @UniqueConstraint(name = "uq_email_settings_client", columnNames = {"client_id"}))
public class EmailSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "email_settings_seq")
    @SequenceGenerator(name = "email_settings_seq", sequenceName = "email_settings_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, unique = true)
    private String clientId;

    /** Sender display name shown in email clients (e.g. "Grace Church"). */
    @Column(name = "display_name", length = 200)
    private String displayName;

    /** Text appended to every outgoing email's footer. */
    @Column(name = "footer_comments", columnDefinition = "TEXT")
    private String footerComments;

    /** When true, the organization's logo is included in the email footer. */
    @Column(name = "include_logo", nullable = false)
    private boolean includeLogo;

    /** When true, a randomly selected promise verse is appended to each email. */
    @Column(name = "include_daily_verse", nullable = false)
    private boolean includeDailyVerse;

    /** Closing signature text (e.g. "By Admin Team"). */
    @Column(name = "signature", columnDefinition = "TEXT")
    private String signature;

    @PrePersist
    protected void onCreate() {
        this.includeLogo       = false;
        this.includeDailyVerse = false;
    }
}
