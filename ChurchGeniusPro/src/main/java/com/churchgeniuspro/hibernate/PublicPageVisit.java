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
 * One row per visit to a public marketing website page ({@code /web/home},
 * {@code /web/features}, …). Written by {@code PublicWebController} on every
 * page hit; aggregated for the Service Admin dashboard
 * ({@code GET /api/serviceadmin/public-page-stats}). Not tenant-scoped —
 * these pages exist outside any church account.
 */
@Data
@Entity
@Table(name = "public_page_visit",
       indexes = {
           @Index(name = "idx_ppv_page",      columnList = "page"),
           @Index(name = "idx_ppv_page_date", columnList = "page,visited_at")
       })
public class PublicPageVisit {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "public_page_visit_seq")
    @SequenceGenerator(
            name           = "public_page_visit_seq",
            sequenceName   = "public_page_visit_id_seq",
            allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Page key: home, features, pricing, help, support, info. */
    @Column(name = "page", nullable = false, length = 50)
    private String page;

    @Column(name = "visited_at", nullable = false, updatable = false)
    private Date visitedAt;

    /** Browser / device information (User-Agent header). */
    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    /** Requesting IP (X-Forwarded-For aware), for rough uniqueness analysis. */
    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    /** Referrer header when present (where the visitor came from). */
    @Column(name = "referrer", length = 500)
    private String referrer;

    @PrePersist
    protected void onCreate() {
        if (this.visitedAt == null) this.visitedAt = new Date();
    }
}
