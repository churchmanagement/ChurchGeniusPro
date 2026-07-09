package com.churchgeniuspro.payroll.config;

import com.churchgeniuspro.payroll.model.FilingStatus;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

/**
 * Effective-dated federal income-tax withholding configuration implementing the
 * IRS Publication 15-T "Percentage Method Tables for Automated Payroll Systems"
 * (Worksheet 1A) for 2020-or-later Forms W-4.
 *
 * <p>The Worksheet 1A computation, given an employee's annualized wage:
 * <ol>
 *   <li>Subtract the fixed standard-deduction add-back (line 1g): the MFJ or
 *       "other" amount when the Step 2 checkbox is <em>not</em> checked, or $0
 *       when it <em>is</em> checked.</li>
 *   <li>Apply the appropriate rate schedule — the Standard schedules when the
 *       Step 2 box is not checked, or the Step 2 Checkbox schedules when it is.</li>
 * </ol>
 *
 * <p>All figures are externally configurable and keyed by effective year, so a
 * new year's tables (or a correction) can be added without code changes — see
 * {@code Federal2026TaxData} for the seeded 2026 values and their sources.
 */
public final class FederalWithholdingConfig {

    private final int effectiveYear;

    /** Line 1g standard-deduction add-back, keyed by schedule group (MFJ, SINGLE, HOH). */
    private final Map<FilingStatus, BigDecimal> standardDeductionAddBack;

    /** Schedules used when the W-4 Step 2 checkbox is NOT checked. */
    private final Map<FilingStatus, TaxRateSchedule> standardSchedules;

    /** Schedules used when the W-4 Step 2 checkbox IS checked. */
    private final Map<FilingStatus, TaxRateSchedule> step2CheckboxSchedules;

    public FederalWithholdingConfig(int effectiveYear,
                                    Map<FilingStatus, BigDecimal> standardDeductionAddBack,
                                    Map<FilingStatus, TaxRateSchedule> standardSchedules,
                                    Map<FilingStatus, TaxRateSchedule> step2CheckboxSchedules) {
        this.effectiveYear = effectiveYear;
        this.standardDeductionAddBack = new EnumMap<>(standardDeductionAddBack);
        this.standardSchedules = new EnumMap<>(standardSchedules);
        this.step2CheckboxSchedules = new EnumMap<>(step2CheckboxSchedules);
    }

    /**
     * Annual federal income-tax withholding (before per-period division and
     * before the Step 3 credit subtraction, which the calculator applies).
     *
     * @param annualWage  annualized wage after W-4 Step 4(a) additions and
     *                    Step 4(b) deductions, but before the standard-deduction
     *                    add-back (i.e., Worksheet 1A line 1e − line 1f)
     * @param status      the employee's filing status
     * @param step2Checked whether the W-4 Step 2 checkbox is checked
     */
    public BigDecimal annualWithholding(BigDecimal annualWage, FilingStatus status, boolean step2Checked) {
        FilingStatus group = status.scheduleStatus();
        BigDecimal addBack = step2Checked ? BigDecimal.ZERO
                : standardDeductionAddBack.getOrDefault(group, BigDecimal.ZERO);
        BigDecimal adjusted = annualWage.subtract(addBack);
        if (adjusted.signum() < 0) adjusted = BigDecimal.ZERO;
        TaxRateSchedule schedule = (step2Checked ? step2CheckboxSchedules : standardSchedules).get(group);
        if (schedule == null) return BigDecimal.ZERO;
        return schedule.taxOn(adjusted);
    }

    public int getEffectiveYear() { return effectiveYear; }
    public Map<FilingStatus, BigDecimal> getStandardDeductionAddBack() { return standardDeductionAddBack; }
    public Map<FilingStatus, TaxRateSchedule> getStandardSchedules() { return standardSchedules; }
    public Map<FilingStatus, TaxRateSchedule> getStep2CheckboxSchedules() { return step2CheckboxSchedules; }
}
