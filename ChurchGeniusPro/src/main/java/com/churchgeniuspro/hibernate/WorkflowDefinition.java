package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One automation rule of a church (AI Phase 6, 2026-11-05): WHEN {@link #trigger} fires AND every
 * condition in {@link #conditionsJson} holds, run the actions in {@link #actionsJson} with the
 * permissions of {@link #ownerUsername} / {@link #ownerUserId}. Every rule starts DISABLED.
 */
@Entity
@Table(name = "workflow_definition",
       indexes = @Index(name = "idx_wf_def_client_trigger", columnList = "app_client_id, trigger_type, enabled"))
@Getter @Setter
public class WorkflowDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_client_id", nullable = false, length = 100)
    private String appClientId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    /** One of {@code WorkflowSchema.TRIGGERS}. */
    @Column(name = "trigger_type", nullable = false, length = 40)
    private String trigger;

    @Column(name = "conditions_json", columnDefinition = "TEXT")
    private String conditionsJson;

    @Column(name = "actions_json", columnDefinition = "TEXT", nullable = false)
    private String actionsJson;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /** The staff login whose permissions the actions run under (re-checked on every run). */
    @Column(name = "owner_user_id", nullable = false)
    private Integer ownerUserId;

    @Column(name = "owner_username", nullable = false, length = 150)
    private String ownerUsername;

    @Column(name = "template_key", length = 40)
    private String templateKey;

    @Column(name = "created_by", length = 150)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "run_count", nullable = false)
    private int runCount;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}
