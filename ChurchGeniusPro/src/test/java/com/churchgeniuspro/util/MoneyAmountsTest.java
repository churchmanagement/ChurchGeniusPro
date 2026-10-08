package com.churchgeniuspro.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Financial audit M1: {@code IncomeController}/{@code ExpenseController} parsed a
 * user-entered amount with a bare {@code new BigDecimal(val.toString())} — any
 * number of decimal digits was accepted, and PostgreSQL's {@code numeric(15,2)}
 * column silently rounded whatever didn't fit on save. {@link MoneyAmounts#parseStrict}
 * is the replacement: it rejects, rather than silently rounds, any value that
 * rounding to 2 decimal places would change.
 */
class MoneyAmountsTest {

    @Test
    @DisplayName("null input returns null, matching the field-is-required convention")
    void nullReturnsNull() {
        assertThat(MoneyAmounts.parseStrict(null)).isNull();
    }

    @Test
    @DisplayName("non-numeric text returns null, not an exception")
    void nonNumericReturnsNull() {
        assertThat(MoneyAmounts.parseStrict("not a number")).isNull();
    }

    @Test
    @DisplayName("an ordinary two-decimal amount passes through unchanged")
    void ordinaryAmountUnaffected() {
        assertThat(MoneyAmounts.parseStrict("12.34")).isEqualByComparingTo("12.34");
    }

    @Test
    @DisplayName("a whole-dollar amount is normalized to 2 decimal places")
    void wholeDollarNormalized() {
        BigDecimal result = MoneyAmounts.parseStrict("12");
        assertThat(result).isEqualByComparingTo("12.00");
        assertThat(result.scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("a harmless trailing zero (12.340) is not treated as precision loss")
    void trailingZeroAccepted() {
        BigDecimal result = MoneyAmounts.parseStrict("12.340");
        assertThat(result).isEqualByComparingTo("12.34");
    }

    @Test
    @DisplayName("a genuine sub-cent amount is rejected, not silently rounded")
    void subCentAmountRejected() {
        assertThatThrownBy(() -> MoneyAmounts.parseStrict("12.345"))
                .isInstanceOf(MoneyAmounts.ImpreciseAmountException.class)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("12.345");
    }

    @Test
    @DisplayName("negative sub-cent precision is rejected the same way as positive")
    void negativeSubCentRejected() {
        assertThatThrownBy(() -> MoneyAmounts.parseStrict("-0.001"))
                .isInstanceOf(MoneyAmounts.ImpreciseAmountException.class);
    }

    @Test
    @DisplayName("exponential notation that rounds away to a phantom $0.00 is rejected")
    void exponentialNearZeroRejected() {
        // 1e-7 would pass a naive "> 0" check and then round away to nothing.
        assertThatThrownBy(() -> MoneyAmounts.parseStrict("1e-7"))
                .isInstanceOf(MoneyAmounts.ImpreciseAmountException.class);
    }

    @Test
    @DisplayName("exponential notation for a clean whole-dollar amount is accepted and normalized")
    void exponentialWholeDollarAccepted() {
        BigDecimal result = MoneyAmounts.parseStrict("1e3");
        assertThat(result).isEqualByComparingTo("1000.00");
        assertThat(result.scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("a non-String Object (as Jackson hands back from a JSON body) is accepted")
    void nonStringObjectAccepted() {
        assertThat(MoneyAmounts.parseStrict(Integer.valueOf(12))).isEqualByComparingTo("12.00");
    }

    @Test
    @DisplayName("the rejection message states the offending value in plain decimal, not exponential, form")
    void messageUsesPlainNotation() {
        assertThatThrownBy(() -> MoneyAmounts.parseStrict("1e-7"))
                .hasMessageContaining("0.0000001")
                .hasMessageNotContaining("E-7")
                .hasMessageNotContaining("e-7");
    }
}
