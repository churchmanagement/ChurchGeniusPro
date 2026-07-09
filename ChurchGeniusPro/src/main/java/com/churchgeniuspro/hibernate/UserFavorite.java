package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A single sidebar "Favorite" shortcut saved by one user account.
 *
 * <p>Favorites are stored <b>per user account</b> and scoped to the tenant:
 * {@code clientId} is the organization (tenant) and {@code userRef} uniquely
 * identifies the individual account within it (staff username, member id, or
 * church id — see {@code FavoritesController.userRef}). The combination
 * {@code (clientId, userRef, pageId)} is unique (enforced in the service).
 *
 * <p>Each row mirrors a nav sub-item so the Favorites section can be rebuilt
 * client-side without re-deriving labels/routes: {@code pageId} is the nav
 * item id (e.g. {@code general/meetings}), and {@code label}/{@code href}/
 * {@code icon} are a snapshot of how it should render.
 */
@Data
@Entity
@Table(name = "user_favorite")
public class UserFavorite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Organization (tenant) this favorite belongs to. */
    @Column(name = "client_id", nullable = false)
    private String clientId;

    /** Per-account identifier within the tenant (e.g. {@code user:jsmith}, {@code member:42}, {@code church:CL123}). */
    @Column(name = "user_ref", nullable = false)
    private String userRef;

    /** Nav item id of the favorited page/sub-item (e.g. {@code general/meetings}). */
    @Column(name = "page_id", nullable = false)
    private String pageId;

    /** Display label snapshot (e.g. {@code Meetings}). */
    @Column(name = "label")
    private String label;

    /** Navigation target snapshot (e.g. {@code /meetings} or {@code #mbr-family}). */
    @Column(name = "href")
    private String href;

    /** Icon snapshot (HTML entity string, e.g. {@code &#x1F4C5;}). */
    @Column(name = "icon", length = 64)
    private String icon;

    /**
     * Optional permission key required to access this favorite's target
     * (e.g. {@code general.ministry.kids}). Used for subsection favorites so the
     * Favorites list only shows entries the user can still access. {@code null}
     * for plain nav favorites (their visibility follows the nav source item).
     */
    @Column(name = "perm")
    private String perm;

    /** Ordering within the user's Favorites list (lower = higher up). */
    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(name = "created_date")
    private LocalDateTime createdDate;

    @PrePersist
    public void prePersist() {
        if (createdDate == null) createdDate = LocalDateTime.now();
        if (sortOrder == null) sortOrder = 0;
    }
}
