package com.churchgeniuspro.payroll.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * An ordered set of {@link TaxBracket} rows for a single filing status. Used for
 * the federal percentage-method schedules and for configurable state bracket
 * tables. A flat-rate state tax is simply a one-bracket schedule.
 */
public final class TaxRateSchedule {

    private final List<TaxBracket> brackets;

    public TaxRateSchedule(List<TaxBracket> brackets) {
        // Defensive copy; assumed pre-sorted ascending by lowerBound.
        this.brackets = new ArrayList<>(brackets);
    }

    /**
     * Progressive tax on {@code amount}. Amounts at or below zero produce zero.
     * If no bracket matches (amount below the first lowerBound), returns zero.
     */
    public BigDecimal taxOn(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) return BigDecimal.ZERO;
        for (int i = brackets.size() - 1; i >= 0; i--) {
            TaxBracket b = brackets.get(i);
            if (amount.compareTo(b.getLowerBound()) >= 0) {
                return b.taxFor(amount);
            }
        }
        return BigDecimal.ZERO;
    }

    /** Single flat-rate schedule (e.g., a state with one income-tax rate). */
    public static TaxRateSchedule flat(double rate) {
        List<TaxBracket> one = new ArrayList<>();
        one.add(TaxBracket.of(0, -1, 0, rate));
        return new TaxRateSchedule(one);
    }

    public List<TaxBracket> getBrackets() { return brackets; }
}
