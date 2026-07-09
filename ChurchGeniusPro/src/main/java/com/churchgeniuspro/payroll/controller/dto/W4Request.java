package com.churchgeniuspro.payroll.controller.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Set/replace an employee's Form W-4 (2020 or later). */
@Data
public class W4Request {
    private String filingStatus;       // SINGLE, MARRIED_FILING_JOINTLY, MARRIED_FILING_SEPARATELY, HEAD_OF_HOUSEHOLD
    private boolean step2Checked;
    private BigDecimal step3AnnualCredits;
    private BigDecimal step4aOtherIncome;
    private BigDecimal step4bDeductions;
    private BigDecimal step4cExtraPerPeriod;
    private Integer stateAllowances;
    private BigDecimal stateExtraPerPeriod;
    private LocalDate effectiveDate;
}
