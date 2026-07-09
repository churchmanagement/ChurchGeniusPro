package com.churchgeniuspro.payroll.entity;

import com.churchgeniuspro.payroll.model.FilingStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A Form W-4 (2020 or later) on file for an employee. Multiple rows may exist
 * per employee over time; the one with {@code active=true} (latest effective)
 * drives withholding. Maps directly to the engine's {@code W4Input}.
 */
@Data
@Entity
@Table(name = "payroll_w4")
public class PayrollW4 {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_client_id", nullable = false)
    private String appClientId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Enumerated(EnumType.STRING)
    private FilingStatus filingStatus = FilingStatus.SINGLE;   // Step 1(c)

    private boolean step2Checked;                              // Step 2 checkbox

    private BigDecimal step3AnnualCredits   = BigDecimal.ZERO; // Step 3 (dependents/credits)
    private BigDecimal step4aOtherIncome    = BigDecimal.ZERO; // Step 4(a)
    private BigDecimal step4bDeductions     = BigDecimal.ZERO; // Step 4(b)
    private BigDecimal step4cExtraPerPeriod = BigDecimal.ZERO; // Step 4(c)

    /** Optional state withholding allowances/extra (state forms vary). */
    private Integer stateAllowances;
    private BigDecimal stateExtraPerPeriod = BigDecimal.ZERO;

    private LocalDate effectiveDate;
    private boolean active = true;
}
