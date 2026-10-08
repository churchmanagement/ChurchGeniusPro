package com.churchgeniuspro.payroll.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Configurable state income-tax rules for a state code and tax year. No state is
 * seeded by default. A flat-rate state needs only {@code flatRate}; a bracketed
 * state additionally has {@link StateTaxBracket} child rows. States with no
 * income tax set {@code hasIncomeTax=false}.
 */
@Data
@Entity
@Table(name = "payroll_state_tax_config")
public class StateTaxConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "effective_year", nullable = false)
    private Integer effectiveYear;

    @Column(name = "state_code", nullable = false)
    private String stateCode;            // e.g. "CA", "TX"

    private boolean hasIncomeTax;

    /** Used when there are no bracket rows: a single flat rate (e.g., 0.0307). */
    @Column(precision = 9, scale = 6)   // explicit: numeric(38,2) would store 0.0307 as 0.03 (audit C1)
    private BigDecimal flatRate;

    private BigDecimal annualStandardDeduction = BigDecimal.ZERO;

    /** Optional flat local/city income-tax rate applied to period state wages. */
    @Column(precision = 9, scale = 6)
    private BigDecimal localTaxRate = BigDecimal.ZERO;
}
