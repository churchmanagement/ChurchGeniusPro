package com.churchgeniuspro.payroll.entity;

import com.churchgeniuspro.payroll.model.PayFrequency;
import com.churchgeniuspro.payroll.model.PayrollRunStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;

/**
 * A batch payroll run for one pay period: the unit of the approval workflow,
 * history tracking, and voiding. Individual {@link Paystub}s reference their run.
 */
@Data
@Entity
@Table(name = "payroll_run")
public class PayrollRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_client_id", nullable = false)
    private String appClientId;

    @Enumerated(EnumType.STRING)
    private PayFrequency payFrequency;

    private LocalDate payPeriodStart;
    private LocalDate payPeriodEnd;
    private LocalDate payDate;

    @Enumerated(EnumType.STRING)
    private PayrollRunStatus status = PayrollRunStatus.DRAFT;

    // Roll-up totals (denormalized for reporting)
    private BigDecimal totalGross = BigDecimal.ZERO;
    private BigDecimal totalTaxes = BigDecimal.ZERO;
    private BigDecimal totalDeductions = BigDecimal.ZERO;
    private BigDecimal totalNet = BigDecimal.ZERO;
    private Integer employeeCount = 0;

    // Workflow / audit fields
    private String createdBy;
    private String submittedBy;
    private Date submittedAt;
    private String approvedBy;
    private Date approvedAt;
    private String voidedBy;
    private Date voidedAt;
    private String voidReason;

    /** If this run is an adjustment to a prior run, the prior run id. */
    private Long adjustsRunId;

    private String notes;

    @Column(updatable = false)
    private Date created;
    private Date updated;

    @PrePersist void onCreate() { created = new Date(); updated = created; }
    @PreUpdate  void onUpdate() { updated = new Date(); }
}
