package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI Phase 1 (2026-11-04): a named, reusable people filter. Stores the FILTER (its predicates as
 * JSON), never the people: every run re-evaluates it under the running user's permissions, so a
 * saved list can never show rows its viewer may not see. Tenant-scoped by {@code app_client_id}.
 */
@Data
@Entity
@Table(name = "ai_saved_people_list", indexes = @Index(name = "idx_ai_saved_list_client", columnList = "app_client_id, delete_flag, name"))
public class AiSavedPeopleList {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_client_id", nullable = false, length = 100)
    private String appClientId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /** The predicates exactly as {@code PeopleFilter.toJson()} writes them. */
    @Column(name = "filter_json", nullable = false, columnDefinition = "TEXT")
    private String filterJson;

    @Column(name = "created_by", length = 150)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "last_count")
    private Integer lastCount;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); deleteFlag = false; }
}
