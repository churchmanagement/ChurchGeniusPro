package com.churchgeniuspro.payroll.engine;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The full, itemized result of a single-period payroll calculation: gross
 * earnings, the tax bases, every withholding and deduction, net pay, the
 * itemized paystub line items, and the rolled-forward year-to-date totals.
 */
@Data
public class PayrollCalculationResult {

    // Earnings and bases
    private BigDecimal grossEarnings        = BigDecimal.ZERO;
    private BigDecimal federalTaxableWages  = BigDecimal.ZERO; // gross − income-tax-exempt pre-tax
    private BigDecimal ficaWages            = BigDecimal.ZERO; // gross − FICA-exempt pre-tax
    private BigDecimal stateTaxableWages    = BigDecimal.ZERO;

    // Deduction totals
    private BigDecimal totalPreTaxDeductions  = BigDecimal.ZERO;
    private BigDecimal totalPostTaxDeductions = BigDecimal.ZERO;

    // Tax withholdings
    private BigDecimal federalWithholding   = BigDecimal.ZERO;
    private BigDecimal socialSecurity       = BigDecimal.ZERO;
    private BigDecimal medicare             = BigDecimal.ZERO;
    private BigDecimal additionalMedicare   = BigDecimal.ZERO;
    private BigDecimal stateWithholding     = BigDecimal.ZERO;
    private BigDecimal localTax             = BigDecimal.ZERO;
    private BigDecimal totalTaxes           = BigDecimal.ZERO;

    private BigDecimal netPay               = BigDecimal.ZERO;

    // Itemized lines (current + YTD) for the paystub
    private List<PaystubLineItem> earningItems   = new ArrayList<>();
    private List<PaystubLineItem> preTaxItems    = new ArrayList<>();
    private List<PaystubLineItem> taxItems       = new ArrayList<>();
    private List<PaystubLineItem> postTaxItems   = new ArrayList<>();

    /** Year-to-date totals after applying this run. */
    private YtdAmounts newYtd = YtdAmounts.zero();
}
