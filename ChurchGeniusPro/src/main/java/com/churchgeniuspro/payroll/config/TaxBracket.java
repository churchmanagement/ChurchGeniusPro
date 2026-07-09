package com.churchgeniuspro.payroll.config;

import java.math.BigDecimal;

/**
 * One row of a progressive tax-rate schedule, expressed in the "base + marginal
 * rate on the excess" form used by IRS Publication 15-T percentage-method
 * tables and by typical state bracket tables.
 *
 * <p>Example (2026 Standard, Single, the 12% row):
 * {@code lowerBound=$19,900, upperBound=$57,900, base=$1,240, rate=0.12} means
 * tax = $1,240 + 12% × (income − $19,900) for income in [19,900, 57,900).
 */
public final class TaxBracket {

    private final BigDecimal lowerBound;   // inclusive
    private final BigDecimal upperBound;   // exclusive; null = no upper limit
    private final BigDecimal base;         // tax accumulated at lowerBound
    private final BigDecimal rate;         // marginal rate on the excess (e.g. 0.12)

    public TaxBracket(BigDecimal lowerBound, BigDecimal upperBound,
                      BigDecimal base, BigDecimal rate) {
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
        this.base = base;
        this.rate = rate;
    }

    /** Convenience constructor taking plain numbers; {@code upperBound < 0} means no limit. */
    public static TaxBracket of(double lower, double upper, double base, double rate) {
        return new TaxBracket(
                BigDecimal.valueOf(lower),
                upper < 0 ? null : BigDecimal.valueOf(upper),
                BigDecimal.valueOf(base),
                BigDecimal.valueOf(rate));
    }

    /** True if {@code amount} falls in [lowerBound, upperBound). */
    public boolean contains(BigDecimal amount) {
        if (amount.compareTo(lowerBound) < 0) return false;
        return upperBound == null || amount.compareTo(upperBound) < 0;
    }

    /** Tax for an amount known to fall in this bracket: base + rate × (amount − lowerBound). */
    public BigDecimal taxFor(BigDecimal amount) {
        return base.add(amount.subtract(lowerBound).multiply(rate));
    }

    public BigDecimal getLowerBound() { return lowerBound; }
    public BigDecimal getUpperBound() { return upperBound; }
    public BigDecimal getBase()       { return base; }
    public BigDecimal getRate()       { return rate; }
}
