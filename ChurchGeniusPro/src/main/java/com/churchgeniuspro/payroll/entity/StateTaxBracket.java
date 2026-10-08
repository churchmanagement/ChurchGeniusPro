package com.churchgeniuspro.payroll.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;

/**
 * One progressive bracket row for a bracketed state's income tax, linked to a
 * {@link StateTaxConfig}. Optionally per filing group; when {@code filingGroup}
 * is null the bracket applies to all statuses.
 */
@Data
@Entity
@Table(name = "payroll_state_bracket")
public class StateTaxBracket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "state_config_id", nullable = false)
    private Long stateConfigId;

    /** "MFJ", "SINGLE", "HOH", or null = all statuses. */
    @Column(name = "filing_group")
    private String filingGroup;

    private BigDecimal lowerBound;
    private BigDecimal upperBound;   // null = no upper limit
    private BigDecimal baseTax;
    /** Marginal rate, e.g. 0.0475 — explicit precision so a rate is never rounded to two places (audit C1). */
    @Column(precision = 9, scale = 6)
    private BigDecimal rate;
    private Integer sortOrder;
}
