package com.churchgeniuspro.payroll.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;

/**
 * A single employee's paystub for a single {@link PayrollRun}. Stores the
 * computed amounts plus year-to-date snapshots so historical stubs remain
 * reproducible even as tax tables change (historical paystub retention,
 * requirement #10). Itemized lines are in {@link PaystubItem}.
 */
@Data
@Entity
@Table(name = "payroll_paystub")
public class Paystub {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_client_id", nullable = false)
    private String appClientId;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    // Snapshot of identifying detail (so the stub renders even if the employee changes later)
    private String employeeName;
    private String employerName;
    private LocalDate payPeriodStart;
    private LocalDate payPeriodEnd;
    private LocalDate payDate;

    // Current-period amounts
    private BigDecimal grossEarnings        = BigDecimal.ZERO;
    // Tax bases for this period (persisted so W-2 boxes and reports aggregate accurately)
    private BigDecimal federalTaxableWages  = BigDecimal.ZERO; // W-2 Box 1 contribution
    private BigDecimal ficaWages            = BigDecimal.ZERO; // Social Security / Medicare wages
    private BigDecimal stateTaxableWages    = BigDecimal.ZERO; // W-2 Box 16 contribution
    private BigDecimal preTaxDeductions     = BigDecimal.ZERO;
    private BigDecimal federalWithholding   = BigDecimal.ZERO;
    private BigDecimal socialSecurity       = BigDecimal.ZERO;
    private BigDecimal medicare             = BigDecimal.ZERO;
    private BigDecimal additionalMedicare   = BigDecimal.ZERO;
    private BigDecimal stateWithholding     = BigDecimal.ZERO;
    private BigDecimal localTax             = BigDecimal.ZERO;
    private BigDecimal postTaxDeductions    = BigDecimal.ZERO;
    private BigDecimal totalTaxes           = BigDecimal.ZERO;
    private BigDecimal netPay               = BigDecimal.ZERO;

    // Year-to-date snapshots (after this stub)
    private BigDecimal ytdGross             = BigDecimal.ZERO;
    private BigDecimal ytdPreTaxDeductions  = BigDecimal.ZERO;
    private BigDecimal ytdTaxes             = BigDecimal.ZERO;
    private BigDecimal ytdPostTaxDeductions = BigDecimal.ZERO;
    private BigDecimal ytdNetPay            = BigDecimal.ZERO;
    private BigDecimal ytdFicaWages         = BigDecimal.ZERO;

    // Masked direct deposit shown on the stub
    private String directDepositAccountLast4;
    private String directDepositAccountType;

    private boolean voided;

    @Column(updatable = false)
    private Date created;

    @PrePersist void onCreate() { created = new Date(); }
}
