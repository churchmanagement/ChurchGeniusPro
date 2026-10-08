package com.churchgeniuspro.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Financial audit M1: the Income and Expense "amount" fields were parsed with
 * a bare {@code new BigDecimal(val.toString())} — any number of decimal
 * digits was accepted by the API, and the database's {@code numeric(15,2)}
 * columns silently rounded whatever didn't fit on save. A value like
 * {@code 12.345} was quietly stored as {@code 12.35}, with nothing telling
 * the caller their figure had changed; a near-zero value expressed in
 * exponential notation (e.g. {@code 1e-7}) could pass a simple {@code > 0}
 * check and then round away to a phantom {@code $0.00} ledger row. Both are
 * the kind of silent financial drift this audit exists to catch.
 *
 * <p>{@link #parseStrict} is the single parsing path for a user-supplied
 * currency amount, shared by the Income and Expense controllers so the rule
 * can't drift between the two the way independently-maintained copies of the
 * same check have elsewhere in this codebase. It accepts anything
 * {@link BigDecimal}'s own constructor accepts — including exponential
 * notation, so a whole-dollar value like {@code 1e3} normalizes to
 * {@code 1000.00} — but rejects, rather than silently rounds, any value that
 * is not already exactly representable to two decimal places.
 */
public final class MoneyAmounts {

    private MoneyAmounts() {}

    /** Thrown by {@link #parseStrict} for an amount that rounding to 2 decimal places would change. */
    public static class ImpreciseAmountException extends IllegalArgumentException {
        public ImpreciseAmountException(String message) { super(message); }
    }

    /**
     * Parses a user-supplied currency amount. Returns {@code null} for
     * {@code null} input or text that isn't a number at all — matching the
     * lenient, null-on-failure convention the calling controllers already use
     * for their other field parsers, which they turn into a "field is
     * required" message. A value that parses fine but carries more than 2
     * decimal places throws {@link ImpreciseAmountException} instead of being
     * silently rounded, since that case must not be quietly "fixed" out from
     * under the caller.
     *
     * <p>The returned value is always scaled to exactly 2 decimal places, so
     * every amount that reaches the caller already matches what the database
     * will actually store — nothing downstream (a duplicate-detection
     * comparison, a pledge-credit total, a report sum) can drift from the
     * persisted ledger row the way an unrounded in-memory value could.
     */
    public static BigDecimal parseStrict(Object val) {
        if (val == null) return null;
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(val.toString());
        } catch (NumberFormatException e) {
            return null;
        }
        BigDecimal rounded = parsed.setScale(2, RoundingMode.HALF_UP);
        if (rounded.compareTo(parsed) != 0) {
            throw new ImpreciseAmountException(
                    "Amount " + parsed.toPlainString() + " has more than 2 decimal places. "
                            + "Enter a value in dollars and cents (e.g. 12.34).");
        }
        return rounded;
    }
}
