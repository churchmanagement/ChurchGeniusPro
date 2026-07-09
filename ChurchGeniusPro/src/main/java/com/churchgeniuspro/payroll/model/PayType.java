package com.churchgeniuspro.payroll.model;

/**
 * How an employee's base earnings are determined.
 *
 * <ul>
 *   <li>{@link #HOURLY} — paid an hourly rate; regular/overtime/holiday hours are
 *       multiplied by the rate (and applicable multipliers).</li>
 *   <li>{@link #SALARY} — paid a fixed annual salary, divided across the number
 *       of pay periods in the year for the employee's {@link PayFrequency}.</li>
 * </ul>
 */
public enum PayType {
    HOURLY,
    SALARY
}
