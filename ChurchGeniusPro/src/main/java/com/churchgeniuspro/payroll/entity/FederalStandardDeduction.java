package com.churchgeniuspro.payroll.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;

/**
 * The fixed standard-deduction add-back (Pub 15-T Worksheet 1A, line 1g) for a
 * tax year and filing group, applied when the W-4 Step 2 checkbox is NOT checked.
 */
@Data
@Entity
@Table(name = "payroll_federal_std_deduction")
public class FederalStandardDeduction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "effective_year", nullable = false)
    private Integer effectiveYear;

    /** "MFJ", "SINGLE", or "HOH". */
    @Column(name = "filing_group", nullable = false)
    private String filingGroup;

    private BigDecimal amount;
}
