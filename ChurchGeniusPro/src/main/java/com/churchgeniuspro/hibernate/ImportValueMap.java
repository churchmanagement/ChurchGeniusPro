package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Migration Phase 1 — one value-dictionary entry of an import run: "source value X
 * in dictionary D means our value Y" (e.g. fund "Tithes" → "Tithe", method "cc" →
 * "Card", role "Head of Household" → "Head"). Applied at stage time to every target
 * column that declares that dictionary, so spellings are unified BEFORE load
 * instead of a new fund or payment type appearing for each variant. Per run and
 * per tenant; seeded from the source preset and the church's existing reference
 * names, then edited by the admin on the mapping screen.
 */
@Data
@Entity
@Table(name = "import_value_map", indexes = {
    @Index(name = "idx_import_value_map_run", columnList = "run_id, dictionary")
})
public class ImportValueMap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "client_id", nullable = false, length = 64)
    private String clientId;

    /** fund | method | category | gender | role | memberType */
    @Column(name = "dictionary", nullable = false, length = 32)
    private String dictionary;

    /** Source value, trimmed, compared case-insensitively. */
    @Column(name = "source_value", nullable = false, length = 255)
    private String sourceValue;

    /** Our value; blank/null means "leave the source value as is". */
    @Column(name = "target_value", length = 255)
    private String targetValue;

    @Column(name = "decided_by", length = 128)
    private String decidedBy;

    @Column(name = "created_date", nullable = false, updatable = false)
    private LocalDateTime createdDate;

    @Column(name = "updated_date")
    private LocalDateTime updatedDate;

    @PrePersist
    void onCreate() {
        if (createdDate == null) createdDate = LocalDateTime.now();
        updatedDate = createdDate;
    }

    @PreUpdate
    void onUpdate() { updatedDate = LocalDateTime.now(); }
}
