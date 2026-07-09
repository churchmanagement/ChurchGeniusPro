package com.churchgeniuspro.payroll.engine;

import com.churchgeniuspro.payroll.model.FilingStatus;
import lombok.Data;

import java.math.BigDecimal;

/**
 * The Form W-4 (2020 or later) values that drive federal income-tax withholding
 * under Publication 15-T, Worksheet 1A.
 */
@Data
public class W4Input {

    /** Step 1(c) filing status. */
    private FilingStatus filingStatus = FilingStatus.SINGLE;

    /** Step 2 checkbox (multiple jobs / working spouse). */
    private boolean step2Checked;

    /** Step 3 annual tax credits (e.g., $2,000 per qualifying child). Subtracted from annual tax. */
    private BigDecimal step3AnnualCredits = BigDecimal.ZERO;

    /** Step 4(a) other annual income (not from jobs) subject to withholding. */
    private BigDecimal step4aOtherIncome = BigDecimal.ZERO;

    /** Step 4(b) annual deductions beyond the standard deduction. */
    private BigDecimal step4bDeductions = BigDecimal.ZERO;

    /** Step 4(c) additional withholding requested per pay period. */
    private BigDecimal step4cExtraPerPeriod = BigDecimal.ZERO;

    public BigDecimal step3OrZero()  { return step3AnnualCredits  == null ? BigDecimal.ZERO : step3AnnualCredits; }
    public BigDecimal step4aOrZero() { return step4aOtherIncome   == null ? BigDecimal.ZERO : step4aOtherIncome; }
    public BigDecimal step4bOrZero() { return step4bDeductions    == null ? BigDecimal.ZERO : step4bDeductions; }
    public BigDecimal step4cOrZero() { return step4cExtraPerPeriod== null ? BigDecimal.ZERO : step4cExtraPerPeriod; }
}
