package com.churchgeniuspro.payroll.model;

/**
 * Supported payroll cadences. The {@code defaultPeriodsPerYear} is used to
 * annualize a single period's wages for the IRS percentage-method withholding
 * computation (Pub 15-T, Worksheet 1A) and to divide an annual salary into a
 * per-period amount.
 *
 * <p>For {@link #HOURLY} and {@link #DAILY} the "periods per year" is inherently
 * variable (it depends on hours/days actually worked). The values here are the
 * conventional full-time annualization factors (2080 work hours, 260 work days);
 * a payroll run may override the divisor via
 * {@code PayrollCalculationInput.payPeriodsPerYear} when the real cadence differs.
 */
public enum PayFrequency {

    /** Paid per hour worked. Default annualization: 2080 (40h × 52 weeks). */
    HOURLY(2080),

    /** Paid per day worked. Default annualization: 260 (5 days × 52 weeks). */
    DAILY(260),

    /** Paid once a week (e.g., every Friday). 52 periods/year. */
    WEEKLY(52),

    /** Paid every other week (e.g., every other Friday). 26 periods/year. */
    BIWEEKLY(26),

    /** Paid twice a month (e.g., the 1st and 16th). 24 periods/year. */
    SEMIMONTHLY(24),

    /** Paid once a month (e.g., the 1st). 12 periods/year. */
    MONTHLY(12);

    private final int defaultPeriodsPerYear;

    PayFrequency(int defaultPeriodsPerYear) {
        this.defaultPeriodsPerYear = defaultPeriodsPerYear;
    }

    public int defaultPeriodsPerYear() {
        return defaultPeriodsPerYear;
    }
}
