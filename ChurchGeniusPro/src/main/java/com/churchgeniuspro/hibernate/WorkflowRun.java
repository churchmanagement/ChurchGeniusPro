package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One evaluation of one rule for one event (AI Phase 6). {@code (definition_id, event_key)} is unique:
 * the same event delivered twice produces ONE run. Status: DONE (actions ran), SKIPPED (conditions
 * false), DENIED (owner lost the permission / disabled), CAPPED (per-church daily cap), FAILED
 * (an action threw — nothing of that run is kept), DRY (test run from the page).
 */
@Entity
@Table(name = "workflow_run",
       indexes = { @Index(name = "idx_wf_run_client_time", columnList = "app_client_id, created_at"),
                   @Index(name = "idx_wf_run_def_key", columnList = "definition_id, event_key", unique = true) })
@Getter @Setter
public class WorkflowRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_client_id", nullable = false, length = 100)
    private String appClientId;

    @Column(name = "definition_id", nullable = false)
    private Long definitionId;

    @Column(name = "trigger_type", nullable = false, length = 40)
    private String trigger;

    @Column(name = "event_key", nullable = false, length = 160)
    private String eventKey;

    @Column(name = "subject", length = 200)
    private String subject;

    @Column(name = "status", nullable = false, length = 10)
    private String status;

    @Column(name = "log_json", columnDefinition = "TEXT")
    private String logJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}
