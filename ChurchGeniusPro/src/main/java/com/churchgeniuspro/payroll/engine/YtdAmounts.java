package com.churchgeniuspro.payroll.engine;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Year-to-date accumulators for an employee, used both as input (prior YTD,
 * before this run) and as output (new YTD, after this run). Carrying YTD FICA
 * wages is essential for the Social Security wage-base cap and the Additional
 * Medicare threshold.
 */
@Data
public class YtdAmounts {

    private BigDecimal grossEarnings     = BigDecimal.ZERO;
    private BigDecimal preTaxDeductions  = BigDecimal.ZERO;
    private BigDecimal ficaWages         = BigDecimal.ZERO; // Social Security / Medicare taxable wages
    private BigDecimal federalWithholding= BigDecimal.ZERO;
    private BigDecimal socialSecurity    = BigDecimal.ZERO;
    private BigDecimal medicare          = BigDecimal.ZERO;
    private BigDecimal additionalMedicare= BigDecimal.ZERO;
    private BigDecimal stateWithholding  = BigDecimal.ZERO;
    private BigDecimal localTax          = BigDecimal.ZERO;
    private BigDecimal postTaxDeductions = BigDecimal.ZERO;
    private BigDecimal netPay            = BigDecimal.ZERO;

    public static YtdAmounts zero() {
        return new YtdAmounts();
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    /** Returns a new YtdAmounts equal to this + the given run result amounts. */
    public YtdAmounts plus(PayrollCalculationResult r) {
        YtdAmounts y = new YtdAmounts();
        y.grossEarnings      = nz(grossEarnings).add(r.getGrossEarnings());
        y.preTaxDeductions   = nz(preTaxDeductions).add(r.getTotalPreTaxDeductions());
        y.ficaWages          = nz(ficaWages).add(r.getFicaWages());
        y.federalWithholding = nz(federalWithholding).add(r.getFederalWithholding());
        y.socialSecurity     = nz(socialSecurity).add(r.getSocialSecurity());
        y.medicare           = nz(medicare).add(r.getMedicare());
        y.additionalMedicare = nz(additionalMedicare).add(r.getAdditionalMedicare());
        y.stateWithholding   = nz(stateWithholding).add(r.getStateWithholding());
        y.localTax           = nz(localTax).add(r.getLocalTax());
        y.postTaxDeductions  = nz(postTaxDeductions).add(r.getTotalPostTaxDeductions());
        y.netPay             = nz(netPay).add(r.getNetPay());
        return y;
    }

    public BigDecimal getFicaWagesOrZero() { return nz(ficaWages); }
}
