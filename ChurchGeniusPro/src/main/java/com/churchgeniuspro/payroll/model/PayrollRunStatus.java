package com.churchgeniuspro.payroll.model;

/**
 * Lifecycle states for a payroll run, supporting the approval workflow and
 * voided-payroll requirements.
 *
 * <pre>
 *   DRAFT → PENDING_APPROVAL → APPROVED → PAID
 *                   │                        │
 *                   └────────── VOIDED ──────┘
 * </pre>
 */
public enum PayrollRunStatus {
    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    PAID,
    VOIDED
}
