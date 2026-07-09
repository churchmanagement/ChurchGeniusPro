package com.churchgeniuspro.payroll.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Assignment of a {@link DeductionDefinition} to an employee, with the specific
 * amount (or percentage) to withhold each period. The definition supplies the
 * scope and tax-exemption behavior; this row supplies the per-employee value.
 */
@Data
@Entity
@Table(name = "payroll_employee_deduction")
public class EmployeeDeduction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_client_id", nullable = false)
    private String appClientId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "definition_id", nullable = false)
    private Long definitionId;

    /** Flat per-period amount, or a rate (e.g., 0.06) when the definition is percentage-based. */
    private BigDecimal amountOrRate;

    /** Optional annual cap (e.g., 401(k) elective-deferral limit). */
    private BigDecimal annualLimit;

    private boolean active = true;
}
