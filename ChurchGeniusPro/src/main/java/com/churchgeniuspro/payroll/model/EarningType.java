package com.churchgeniuspro.payroll.model;

/**
 * Categories of gross earnings that can appear on a paystub. All are fully
 * taxable for federal income tax and FICA unless a specific tax rule says
 * otherwise; the calculator treats every earning line as taxable wages.
 */
public enum EarningType {
    REGULAR,
    OVERTIME,
    HOLIDAY,
    BONUS,
    OTHER
}
