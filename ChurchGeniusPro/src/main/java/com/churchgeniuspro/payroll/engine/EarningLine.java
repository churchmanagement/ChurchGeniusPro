package com.churchgeniuspro.payroll.engine;

import com.churchgeniuspro.payroll.model.EarningType;
import lombok.Data;

import java.math.BigDecimal;

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
        e.amount = amount == null ? BigDecimal.ZERO : amount;
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
        e.amount = h.multiply(r).multiply(e.multiplier);
        return e;
    }

    public BigDecimal amountOrZero() {
        return amount == null ? BigDecimal.ZERO : amount;
    }
}
