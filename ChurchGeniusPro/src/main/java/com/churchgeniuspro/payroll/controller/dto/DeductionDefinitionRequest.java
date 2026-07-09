package com.churchgeniuspro.payroll.controller.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Create/update payload for a tenant deduction type. For PRE_TAX definitions the
 * {@code reduces*} flags model the tax treatment (Section 125 reduces all three
 * incl. FICA; 401(k) reduces federal/state but not FICA).
 */
@Data
public class DeductionDefinitionRequest {
    private String name;
    private String code;
    private String scope;              // PRE_TAX or POST_TAX
    private boolean reducesFederalTaxable;
    private boolean reducesStateTaxable;
    private boolean reducesFicaWages;
    private boolean percentageBased;
    private BigDecimal defaultAmount;  // flat amount, or rate (e.g. 0.06) when percentageBased
}
