package com.churchgeniuspro.payroll.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;

/**
 * One persisted row of a federal percentage-method rate schedule, effective for
 * a given tax year. Federal rules are national, so these rows are global (not
 * tenant-scoped). The {@code scheduleType}/{@code filingGroup} pair selects the
 * schedule; the calculator loads these into the in-memory config.
 */
@Data
@Entity
@Table(name = "payroll_federal_bracket")
public class FederalTaxBracket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "effective_year", nullable = false)
    private Integer effectiveYear;

    /** "STANDARD" (Step 2 not checked) or "STEP2" (Step 2 checkbox checked). */
    @Column(name = "schedule_type", nullable = false)
    private String scheduleType;

    /** "MFJ", "SINGLE", or "HOH" (the schedule group; MFS uses SINGLE). */
    @Column(name = "filing_group", nullable = false)
    private String filingGroup;

    private BigDecimal lowerBound;
    private BigDecimal upperBound;   // null = no upper limit
    private BigDecimal baseTax;
    /** Marginal rate, e.g. 0.22 — explicit precision so a rate is never rounded to two places (audit C1). */
    @Column(precision = 9, scale = 6)
    private BigDecimal rate;
    private Integer sortOrder;
}
