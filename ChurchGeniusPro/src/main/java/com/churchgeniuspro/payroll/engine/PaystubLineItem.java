package com.churchgeniuspro.payroll.engine;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

/**
 * An itemized line for the paystub: a label, the current-period amount, and the
 * year-to-date amount. Used for earnings, deductions, and tax-withholding
 * sections so the paystub can show "current" and "YTD" columns (requirement #7).
 */
@Data
@AllArgsConstructor
public class PaystubLineItem {
    private String label;
    private BigDecimal current;
    private BigDecimal yearToDate;
}
