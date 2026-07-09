package com.churchgeniuspro.payroll.service;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Year-end W-2 box values for one employee, aggregated from their paystubs.
 * Box numbers follow IRS Form W-2. (Boxes for retirement plan / dependent-care
 * benefits — 11–14 — and codes are out of scope for this iteration; they are
 * derivable from the same paystub line items and deduction definitions.)
 */
@Data
public class W2Box {
    private String appClientId;
    private Long employeeId;
    private String employeeName;
    private int taxYear;

    private BigDecimal box1WagesTipsOtherComp   = BigDecimal.ZERO; // federal taxable wages
    private BigDecimal box2FederalIncomeTax      = BigDecimal.ZERO;
    private BigDecimal box3SocialSecurityWages   = BigDecimal.ZERO; // capped at SS wage base
    private BigDecimal box4SocialSecurityTax     = BigDecimal.ZERO;
    private BigDecimal box5MedicareWages         = BigDecimal.ZERO; // uncapped
    private BigDecimal box6MedicareTax           = BigDecimal.ZERO; // incl. Additional Medicare
    private BigDecimal box16StateWages           = BigDecimal.ZERO;
    private BigDecimal box17StateIncomeTax       = BigDecimal.ZERO;
    private BigDecimal box18LocalWages           = BigDecimal.ZERO;
    private BigDecimal box19LocalIncomeTax       = BigDecimal.ZERO;
}
