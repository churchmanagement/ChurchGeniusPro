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

import java.time.LocalDate;
import java.util.Date;

/**
 * Hibernate entity for the {@code public_screen_link} table.
 *
 * <p>Stores generated public links to internal pages.
 * Each link contains an encrypted token that encodes the org's
 * {@code appClientId} and the target page URL, allowing read-only
 * public access to that page's data.
 */
@Data
@Entity
@Table(name = "public_screen_link")
public class PublicScreenLink {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "public_screen_link_seq")
    @SequenceGenerator(
            name           = "public_screen_link_seq",
            sequenceName   = "public_screen_link_id_seq",
            allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    /** Human-readable page label (e.g. "Events", "Members"). */
    @Column(name = "page_label", nullable = false)
    private String pageLabel;

    /** Relative URL of the page (e.g. "/event"). */
    @Column(name = "page_url", nullable = false)
    private String pageUrl;

    /** AES-encrypted token encoding appClientId + "|" + pageUrl. */
    @Column(name = "token", nullable = false, unique = true)
    private String token;

    @Column(name = "app_client_id")
    private String appClientId;

    /** Optional expiration date; null = never expires. */
    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    /** Whether this link has been revoked. */
    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    /**
     * Membership-form specific: whether to show a declaration checkbox on the form.
     * Null / false for all other page types.
     */
    @Column(name = "show_declaration")
    private Boolean showDeclaration;

    /**
     * Membership-form specific: the declaration text shown to the applicant.
     * Only meaningful when {@code showDeclaration} is {@code true}.
     */
    @Column(name = "declaration_text", columnDefinition = "TEXT")
    private String declarationText;

    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    @PrePersist
    protected void onCreate() {
        this.createdDate = new Date();
        this.revoked     = false;
    }
}
