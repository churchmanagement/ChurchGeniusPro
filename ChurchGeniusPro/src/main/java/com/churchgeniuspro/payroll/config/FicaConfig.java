package com.churchgeniuspro.payroll.config;

import java.math.BigDecimal;

/**
 * Effective-dated FICA (Social Security + Medicare) configuration. All rates and
 * limits are configurable so future-year changes (especially the annually
 * adjusted Social Security wage base) require only new data, not code edits.
 *
 * <p>Seeded 2026 values (see {@code Federal2026TaxData}):
 * <ul>
 *   <li>Social Security: 6.2% (employee) up to a $184,500 wage base</li>
 *   <li>Medicare: 1.45% (employee) on all wages</li>
 *   <li>Additional Medicare: 0.9% on wages over $200,000 in the calendar year
 *       (employer withholding threshold is $200,000 regardless of filing status)</li>
 * </ul>
 */
public final class FicaConfig {

    private final int effectiveYear;
    private final BigDecimal socialSecurityRate;       // employee share, e.g. 0.062
    private final BigDecimal socialSecurityWageBase;   // annual cap, e.g. 184500
    private final BigDecimal medicareRate;             // employee share, e.g. 0.0145
    private final BigDecimal additionalMedicareRate;   // e.g. 0.009
    private final BigDecimal additionalMedicareThreshold; // e.g. 200000

    public FicaConfig(int effectiveYear, BigDecimal socialSecurityRate, BigDecimal socialSecurityWageBase,
                      BigDecimal medicareRate, BigDecimal additionalMedicareRate,
                      BigDecimal additionalMedicareThreshold) {
        this.effectiveYear = effectiveYear;
        this.socialSecurityRate = socialSecurityRate;
        this.socialSecurityWageBase = socialSecurityWageBase;
        this.medicareRate = medicareRate;
        this.additionalMedicareRate = additionalMedicareRate;
        this.additionalMedicareThreshold = additionalMedicareThreshold;
    }

    /**
     * Social Security tax for this period, respecting the annual wage base using
     * prior year-to-date Social Security wages.
     *
     * @param periodFicaWage    FICA-taxable wages this period
     * @param ytdFicaWageBefore FICA-taxable wages already paid earlier this year
     */
    public BigDecimal socialSecurity(BigDecimal periodFicaWage, BigDecimal ytdFicaWageBefore) {
        BigDecimal remaining = socialSecurityWageBase.subtract(ytdFicaWageBefore);
        if (remaining.signum() <= 0) return BigDecimal.ZERO;
        BigDecimal taxable = periodFicaWage.min(remaining);
        if (taxable.signum() <= 0) return BigDecimal.ZERO;
        return taxable.multiply(socialSecurityRate);
    }

    /** Regular Medicare tax (1.45%) — no wage cap. */
    public BigDecimal medicare(BigDecimal periodFicaWage) {
        if (periodFicaWage.signum() <= 0) return BigDecimal.ZERO;
        return periodFicaWage.multiply(medicareRate);
    }

    /**
     * Additional Medicare Tax (0.9%) on the portion of this period's wages that
     * carries cumulative year-to-date wages above the threshold.
     */
    public BigDecimal additionalMedicare(BigDecimal periodFicaWage, BigDecimal ytdFicaWageBefore) {
        BigDecimal afterOver  = ytdFicaWageBefore.add(periodFicaWage).subtract(additionalMedicareThreshold);
        BigDecimal beforeOver = ytdFicaWageBefore.subtract(additionalMedicareThreshold);
        if (afterOver.signum() < 0) afterOver = BigDecimal.ZERO;
        if (beforeOver.signum() < 0) beforeOver = BigDecimal.ZERO;
        BigDecimal overThisPeriod = afterOver.subtract(beforeOver);
        if (overThisPeriod.signum() <= 0) return BigDecimal.ZERO;
        return overThisPeriod.multiply(additionalMedicareRate);
    }

    public int getEffectiveYear() { return effectiveYear; }
    public BigDecimal getSocialSecurityRate() { return socialSecurityRate; }
    public BigDecimal getSocialSecurityWageBase() { return socialSecurityWageBase; }
    public BigDecimal getMedicareRate() { return medicareRate; }
    public BigDecimal getAdditionalMedicareRate() { return additionalMedicareRate; }
    public BigDecimal getAdditionalMedicareThreshold() { return additionalMedicareThreshold; }
}
