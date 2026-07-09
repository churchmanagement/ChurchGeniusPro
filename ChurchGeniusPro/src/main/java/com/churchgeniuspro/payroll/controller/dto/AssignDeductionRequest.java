package com.churchgeniuspro.payroll.controller.dto;

import lombok.Data;

import java.math.BigDecimal;

/** Assign a deduction definition to an employee with a specific amount or rate. */
@Data
public class AssignDeductionRequest {
    private Long definitionId;
    private BigDecimal amountOrRate;   // flat amount, or rate when the definition is percentage-based
    private BigDecimal annualLimit;    // optional cap (e.g. 401(k) deferral limit)
}
