package com.churchgeniuspro.payroll.engine;

import com.churchgeniuspro.payroll.model.PayFrequency;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * All inputs required to calculate one employee's pay for one period. This is a
 * plain, persistence-free value object so the {@link PayrollCalculator} can be
 * unit-tested in isolation; the service layer maps JPA entities into it.
 */
@Data
public class PayrollCalculationInput {

    private PayFrequency payFrequency = PayFrequency.BIWEEKLY;

    /**
     * Optional override for the number of pay periods per year used to annualize
     * wages. When null, {@link PayFrequency#defaultPeriodsPerYear()} is used.
     * Useful for hourly/daily cadences where the real divisor varies.
     */
    private Integer payPeriodsPerYearOverride;

    private List<EarningLine> earnings = new ArrayList<>();
    private List<DeductionLine> deductions = new ArrayList<>();
    private W4Input w4 = new W4Input();
    private YtdAmounts priorYtd = YtdAmounts.zero();

    public int payPeriodsPerYear() {
        if (payPeriodsPerYearOverride != null && payPeriodsPerYearOverride > 0) {
            return payPeriodsPerYearOverride;
        }
        return payFrequency.defaultPeriodsPerYear();
    }

    public PayrollCalculationInput addEarning(EarningLine e) { earnings.add(e); return this; }
    public PayrollCalculationInput addDeduction(DeductionLine d) { deductions.add(d); return this; }
}
