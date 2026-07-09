package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One row per OpenAI Vision/OCR call (e.g. a Kids Ministry scanned-form extraction).
 *
 * <p>Complements the per-church aggregate counters on {@link OpenAiUsage} with a
 * per-event audit trail: who ran the scan, when, how many images/pages were sent,
 * the token usage and estimated cost, the extraction outcome, and any error. This
 * is what powers the admin "AI Scan Usage" reporting.
 */
@Data
@Entity
@Table(name = "vision_usage_log")
public class VisionUsageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /** App username of the admin/user who ran the scan (may be null for system jobs). */
    @Column(name = "username", length = 150)
    private String username;

    /** What invoked the call, e.g. "kids-child-scan". */
    @Column(name = "feature", length = 60)
    private String feature;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "model", length = 60)
    private String model;

    /** Number of images / PDF pages sent to the model in this call. */
    @Column(name = "pages", nullable = false)
    private int pages = 1;

    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens = 0;

    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens = 0;

    @Column(name = "total_tokens", nullable = false)
    private int totalTokens = 0;

    /** Estimated USD cost of this call from token counts. */
    @Column(name = "estimated_cost", nullable = false)
    private double estimatedCost = 0d;

    /** SUCCESS | EMPTY | ERROR | DISABLED | REJECTED */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "SUCCESS";

    /** How many child records were extracted (for kids scans); 0 otherwise. */
    @Column(name = "children_found", nullable = false)
    private int childrenFound = 0;

    /** Failure detail when status != SUCCESS (truncated). */
    @Column(name = "error_message", length = 500)
    private String errorMessage;
}
