package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

/**
 * One of up to 365 promise-verse entries per organization.
 * Mapped to the {@code promise_verse} table (auto-created by Hibernate DDL update).
 */
@Data
@Entity
@Table(name = "promise_verse")
public class PromiseVerse {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "promise_verse_seq")
    @SequenceGenerator(name = "promise_verse_seq", sequenceName = "promise_verse_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Organization this verse belongs to. */
    @Column(name = "client_id", nullable = false)
    private String clientId;

    /** Day-of-year (1–365) this verse is assigned to. */
    @Column(name = "day_number", nullable = false)
    private Integer dayNumber;

    /** Scripture reference, e.g. "John 3:16". */
    @Column(name = "reference", length = 200)
    private String reference;

    /** Full verse text. */
    @Column(name = "verse_text", columnDefinition = "TEXT")
    private String verseText;

    /** Soft-delete flag — record is never physically removed. */
    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag;

    @PrePersist
    protected void onCreate() {
        this.deleteFlag = false;
    }
}
