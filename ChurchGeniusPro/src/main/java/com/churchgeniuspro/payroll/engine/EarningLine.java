package com.churchgeniuspro.payroll.engine;

import com.churchgeniuspro.payroll.model.EarningType;
import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A single gross-earnings line for one pay period. For hourly earnings, supply
 * hours + rate (+ multiplier) and the factory computes the amount; for fixed
 * amounts (salary slice, bonus, flat holiday pay) supply the amount directly.
 */
@Data
public class EarningLine {

    private EarningType type;
    private String description;
    private BigDecimal hours;       // nullable (for hourly lines)
    private BigDecimal rate;        // nullable (for hourly lines)
    private BigDecimal multiplier;  // nullable; e.g. 1.5 for overtime
    private BigDecimal amount;      // the gross dollar amount of this line

    public EarningLine() {}

    /** Fixed-amount earning (salary slice, bonus, flat holiday pay, other). */
    public static EarningLine of(EarningType type, String description, BigDecimal amount) {
        EarningLine e = new EarningLine();
        e.type = type;
        e.description = description;
        e.amount = money(amount);
        return e;
    }

    /** Hourly earning: amount = hours × rate × multiplier (multiplier defaults to 1). */
    public static EarningLine hourly(EarningType type, String description,
                                     BigDecimal hours, BigDecimal rate, BigDecimal multiplier) {
        EarningLine e = new EarningLine();
        e.type = type;
        e.description = description;
        e.hours = hours;
        e.rate = rate;
        e.multiplier = multiplier == null ? BigDecimal.ONE : multiplier;
        BigDecimal h = hours == null ? BigDecimal.ZERO : hours;
        BigDecimal r = rate == null ? BigDecimal.ZERO : rate;
        e.amount = money(h.multiply(r).multiply(e.multiplier));
        return e;
    }

    public BigDecimal amountOrZero() {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    /**
     * Financial audit M12c: an earning line's dollar amount is rounded to 2
     * decimal places the moment it is established here — whether derived from
     * hours × rate × multiplier (which can carry far more than 2 decimal places,
     * e.g. 10.333 hrs × 15.375 rate) or supplied directly as a fixed amount (a
     * caller-supplied value, such as a raw request-body figure, that was never
     * itself validated to 2 decimals) — so {@code amount} is always the same
     * real money value every consumer (gross, the paystub line item, YTD) sums
     * and displays. {@link com.churchgeniuspro.payroll.engine.PayrollCalculator}
     * also re-rounds defensively before summing into gross, so the two stay
     * correct together even for an EarningLine built by hand (a bare
     * constructor + setter) rather than through one of these factories.
     */
    private static BigDecimal money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }
}
