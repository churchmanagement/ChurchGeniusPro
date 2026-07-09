package com.churchgeniuspro.payroll.config;

import com.churchgeniuspro.payroll.model.FilingStatus;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

/**
 * Generic, fully configurable state income-tax withholding. No specific state is
 * seeded; an administrator supplies the rules per state. This container supports
 * the common shapes:
 *
 * <ul>
 *   <li><b>Flat rate</b> — one bracket via {@link TaxRateSchedule#flat(double)}.</li>
 *   <li><b>Progressive brackets</b> — a {@link TaxRateSchedule}, optionally
 *       per filing status.</li>
 *   <li><b>Standard deduction / exemption</b> — an annual amount subtracted
 *       before applying the schedule.</li>
 *   <li><b>Local tax</b> — an optional flat rate applied to the period's
 *       state-taxable wages (e.g., a city/county income tax).</li>
 * </ul>
 *
 * <p>States that levy no income tax are represented by {@link #none(String)}.
 */
public final class StateWithholdingConfig {

    private final String stateCode;                  // e.g. "TX", "CA"
    private final boolean hasIncomeTax;
    private final Map<FilingStatus, TaxRateSchedule> schedulesByStatus; // may be empty
    private final TaxRateSchedule defaultSchedule;   // fallback when status not mapped
    private final BigDecimal annualStandardDeduction;
    private final BigDecimal localTaxRate;           // flat rate on period state wages; may be zero

    public StateWithholdingConfig(String stateCode, boolean hasIncomeTax,
                                  Map<FilingStatus, TaxRateSchedule> schedulesByStatus,
                                  TaxRateSchedule defaultSchedule,
                                  BigDecimal annualStandardDeduction, BigDecimal localTaxRate) {
        this.stateCode = stateCode;
        this.hasIncomeTax = hasIncomeTax;
        this.schedulesByStatus = schedulesByStatus == null ? new EnumMap<>(FilingStatus.class)
                : new EnumMap<>(schedulesByStatus);
        this.defaultSchedule = defaultSchedule;
        this.annualStandardDeduction = annualStandardDeduction == null ? BigDecimal.ZERO : annualStandardDeduction;
        this.localTaxRate = localTaxRate == null ? BigDecimal.ZERO : localTaxRate;
    }

    /** A no-income-tax state (e.g., TX, FL, WA). */
    public static StateWithholdingConfig none(String stateCode) {
        return new StateWithholdingConfig(stateCode, false, null, null, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** Annual state income-tax withholding for an annualized state wage. */
    public BigDecimal annualWithholding(BigDecimal annualStateWage, FilingStatus status) {
        if (!hasIncomeTax) return BigDecimal.ZERO;
        TaxRateSchedule schedule = schedulesByStatus.getOrDefault(status, defaultSchedule);
        if (schedule == null) return BigDecimal.ZERO;
        BigDecimal taxable = annualStateWage.subtract(annualStandardDeduction);
        if (taxable.signum() < 0) taxable = BigDecimal.ZERO;
        return schedule.taxOn(taxable);
    }

    /** Local income tax on this period's state-taxable wages (flat). */
    public BigDecimal localTax(BigDecimal periodStateWage) {
        if (localTaxRate.signum() <= 0 || periodStateWage.signum() <= 0) return BigDecimal.ZERO;
        return periodStateWage.multiply(localTaxRate);
    }

    public String getStateCode() { return stateCode; }
    public boolean isHasIncomeTax() { return hasIncomeTax; }
    public BigDecimal getAnnualStandardDeduction() { return annualStandardDeduction; }
    public BigDecimal getLocalTaxRate() { return localTaxRate; }
    public TaxRateSchedule getDefaultSchedule() { return defaultSchedule; }
    public Map<FilingStatus, TaxRateSchedule> getSchedulesByStatus() { return schedulesByStatus; }
}
