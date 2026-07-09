package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.util.Date;

/**
 * Per-tenant rule marking a page group as Private and listing which approved
 * networks may reach it.
 *
 * <p>{@code pageKey} is a stable key from the {@code PrivatePageCatalog}
 * (e.g. {@code kids}, {@code eventcheckin}). {@code networkIds} is a comma-separated
 * list of {@link PrivateNetwork} ids that may access the page; an empty/blank value
 * means "any approved network" (all enabled networks for the tenant).
 */
@Data
@Entity
@Table(name = "private_page_rule",
       uniqueConstraints = @UniqueConstraint(columnNames = {"client_id", "page_key"}))
public class PrivatePageRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "page_key", nullable = false, length = 60)
    private String pageKey;

    /** When true, the page is restricted to the approved networks below. */
    @Column(name = "enabled", nullable = false, columnDefinition = "boolean not null default false")
    private boolean enabled;

    /** CSV of allowed PrivateNetwork ids; blank = all of the tenant's enabled networks. */
    @Column(name = "network_ids", columnDefinition = "TEXT")
    private String networkIds;

    @Column(name = "updated_at")
    private Date updatedAt;

    @PreUpdate @PrePersist
    void touch() { this.updatedAt = new Date(); }
}
