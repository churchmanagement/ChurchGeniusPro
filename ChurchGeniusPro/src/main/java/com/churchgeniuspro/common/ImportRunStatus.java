package com.churchgeniuspro.common;

import java.util.EnumSet;
import java.util.Set;

/**
 * The ETL run lifecycle, with the ONLY legal forward transitions encoded.
 *
 * <p>{@link ImportRunStatus} is the single source of truth for "what can happen
 * next" to a run. {@code ImportRunService} consults {@link #canTransitionTo} on
 * every state change and refuses anything not declared here — so a run can never
 * skip validation/approval to reach LOADED, and a terminal run can never move.
 *
 * <pre>
 *   DRAFT ─▶ PROFILED ─▶ MAPPED ─▶ STAGED ─▶ VALIDATED ─▶ APPROVED ─▶ LOADED
 *     │         │          │         │           │            │          │
 *     └─────────┴──────────┴─────────┴───────────┴────────────┘          │
 *                         (any non-terminal ▶ DISCARDED / FAILED)        │
 *                                            LOADED ─▶ ROLLED_BACK ◀──────┘
 * </pre>
 *
 * <p>Phase 1 implements the lifecycle and its guards only; the work each stage
 * performs (profiling, mapping, staging, loading) arrives in later phases.
 */
public enum ImportRunStatus {

    /** Run created, tenant bound, nothing extracted yet. */
    DRAFT,
    /** Source schema/rows profiled. */
    PROFILED,
    /** Column mapping proposed/confirmed. */
    MAPPED,
    /** Rows transformed into staging tables. */
    STAGED,
    /** Staging rows validated (VALID/WARN/ERROR assigned). */
    VALIDATED,
    /** Operator approved the previewed, valid rows. */
    APPROVED,
    /** Approved rows loaded into live tables. */
    LOADED,
    /** A completed load was rolled back (soft-delete + before-images). */
    ROLLED_BACK,
    /** Operator abandoned the run before load. */
    DISCARDED,
    /** Unrecoverable error. */
    FAILED;

    /** Forward "happy path" transitions, plus rollback off LOADED. */
    private static final java.util.Map<ImportRunStatus, Set<ImportRunStatus>> FORWARD =
        new java.util.EnumMap<>(ImportRunStatus.class);

    static {
        FORWARD.put(DRAFT,       EnumSet.of(PROFILED));
        FORWARD.put(PROFILED,    EnumSet.of(MAPPED));
        FORWARD.put(MAPPED,      EnumSet.of(STAGED));
        FORWARD.put(STAGED,      EnumSet.of(VALIDATED));
        FORWARD.put(VALIDATED,   EnumSet.of(APPROVED, STAGED));   // re-stage on validation failure
        FORWARD.put(APPROVED,    EnumSet.of(LOADED));
        FORWARD.put(LOADED,      EnumSet.of(ROLLED_BACK));
        FORWARD.put(ROLLED_BACK, EnumSet.noneOf(ImportRunStatus.class));
        FORWARD.put(DISCARDED,   EnumSet.noneOf(ImportRunStatus.class));
        FORWARD.put(FAILED,      EnumSet.noneOf(ImportRunStatus.class));
    }

    /** Terminal states accept no further transitions. */
    public boolean isTerminal() {
        return this == ROLLED_BACK || this == DISCARDED || this == FAILED;
    }

    /** True if {@code this} may legally move to {@code next}. */
    public boolean canTransitionTo(ImportRunStatus next) {
        if (next == null || next == this) return false;
        // Any non-terminal run can be marked FAILED at any time (incl. during load).
        if (next == FAILED && !isTerminal()) return true;
        // DISCARD only abandons a run BEFORE it has loaded; a LOADED run is reversed
        // via ROLLED_BACK, never discarded.
        if (next == DISCARDED && !isTerminal() && this != LOADED) return true;
        return FORWARD.getOrDefault(this, EnumSet.noneOf(ImportRunStatus.class)).contains(next);
    }

    /** Parse defensively; unknown/blank → null (caller decides how to treat). */
    public static ImportRunStatus fromString(String s) {
        if (s == null) return null;
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
