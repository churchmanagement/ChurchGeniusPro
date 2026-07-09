package com.churchgeniuspro.payroll.entity;

import com.churchgeniuspro.payroll.model.DeductionScope;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;

/**
 * A tenant-configurable deduction type (e.g., "Medical PPO", "401(k)",
 * "United Way", "Wage garnishment"). The exemption flags capture which tax bases
 * a pre-tax deduction reduces — letting an admin model Section 125 benefits
 * (exempt from income tax and FICA) versus 401(k) deferrals (exempt from income
 * tax only) without code changes.
 */
@Data
@Entity
@Table(name = "payroll_deduction_definition")
public class DeductionDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_client_id", nullable = false)
    private String appClientId;

    private String name;
    private String code;

    @Enumerated(EnumType.STRING)
    private DeductionScope scope;       // PRE_TAX or POST_TAX

    // Which tax bases a PRE_TAX deduction reduces (ignored for POST_TAX).
    private boolean reducesFederalTaxable;
    private boolean reducesStateTaxable;
    private boolean reducesFicaWages;

    /** If true, the employee amount is a percentage of gross rather than a flat amount. */
    private boolean percentageBased;

    /** Default flat amount or percentage (e.g., 0.06 for 6%). */
    private BigDecimal defaultAmount;

    private boolean active = true;
}
