package com.churchgeniuspro.payroll.model;

/**
 * Federal filing status from the employee's Form W-4, Step 1(c).
 *
 * <p>IRS Publication 15-T provides percentage-method rate schedules for three
 * groups: Married Filing Jointly, Single (also used for Married Filing
 * Separately), and Head of Household. {@link #scheduleStatus()} maps each value
 * to the schedule group that should be used.
 */
public enum FilingStatus {

    SINGLE,
    MARRIED_FILING_JOINTLY,
    MARRIED_FILING_SEPARATELY,
    HEAD_OF_HOUSEHOLD;

    /**
     * The rate-schedule group to use for this status. Married Filing Separately
     * uses the Single schedules and Single standard-deduction add-back, per
     * Pub 15-T.
     */
    public FilingStatus scheduleStatus() {
        switch (this) {
            case MARRIED_FILING_JOINTLY: return MARRIED_FILING_JOINTLY;
            case HEAD_OF_HOUSEHOLD:      return HEAD_OF_HOUSEHOLD;
            default:                     return SINGLE; // SINGLE and MFS
        }
    }
}
