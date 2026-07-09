package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

/**
 * Persists a user's dashboard layout preferences.
 *
 * <p>Keyed on {@code (username, client_id)} — one row per user per organization.
 * Hibernate {@code ddl-auto=update} creates the table automatically.
 *
 * <ul>
 *   <li>{@code hidden_sections} — JSON array of section IDs the user has hidden,
 *       e.g. {@code ["s-admin-growth","s-acct-stats"]}</li>
 *   <li>{@code section_order}   — JSON array of ALL section IDs in the user's
 *       preferred display order, e.g. {@code ["s-admin-banner","s-acct-net-balance",...]}</li>
 * </ul>
 */
@Data
@Entity
@Table(
    name = "dashboard_preference",
    uniqueConstraints = @UniqueConstraint(
        name       = "uq_dash_pref_user_client",
        columnNames = {"username", "client_id"}
    )
)
public class DashboardPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "dash_pref_seq")
    @SequenceGenerator(
        name           = "dash_pref_seq",
        sequenceName   = "dashboard_preference_id_seq",
        allocationSize = 1
    )
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    /** Login username (email) of the user who owns this preference row. */
    @Column(name = "username", nullable = false)
    private String username;

    /** Organization client-ID (e.g. {@code CGP-00001}) that this user belongs to. */
    @Column(name = "client_id", nullable = false)
    private String clientId;

    /**
     * JSON array of hidden section IDs.
     * Stored as plain TEXT so it survives without a JSON column type.
     * Example: {@code ["s-admin-growth","s-acct-stats"]}
     */
    @Column(name = "hidden_sections", columnDefinition = "TEXT")
    private String hiddenSections;

    /**
     * JSON array of section IDs in the user's preferred display order.
     * Example: {@code ["s-admin-banner","s-acct-net-balance","s-admin-growth"]}
     */
    @Column(name = "section_order", columnDefinition = "TEXT")
    private String sectionOrder;
}
