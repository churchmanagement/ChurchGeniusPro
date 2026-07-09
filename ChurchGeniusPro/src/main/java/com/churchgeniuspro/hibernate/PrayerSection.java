package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.util.Date;

/**
 * Hibernate entity for the {@code prayer_section} table.
 *
 * <p>Represents a top-level prayer category (e.g. "Pray for Visa").
 * Each section belongs to one organization ({@code clientId}) and
 * can have many {@link PrayerRequest} subsections beneath it.
 */
@Data
@Entity
@Table(name = "prayer_section")
public class PrayerSection {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "prayer_section_seq")
    @SequenceGenerator(
            name           = "prayer_section_seq",
            sequenceName   = "prayer_section_id_seq",
            allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Display name of this section (e.g. "Pray for Visa"). */
    @Column(name = "name", nullable = false)
    private String name;

    /** Organization that owns this section. */
    @Column(name = "client_id")
    private String clientId;

    /** Soft-delete flag — records are never physically removed. */
    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Date createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt  = new Date();
        this.deleteFlag = false;
    }
}
