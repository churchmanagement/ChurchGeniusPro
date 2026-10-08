package com.churchgeniuspro.payroll.engine;

import com.churchgeniuspro.payroll.config.FederalWithholdingConfig;
import com.churchgeniuspro.payroll.config.FicaConfig;
import com.churchgeniuspro.payroll.config.StateWithholdingConfig;
import com.churchgeniuspro.payroll.model.EarningType;
import com.churchgeniuspro.payroll.model.FilingStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Financial audit M12c: an earning line's dollar amount could carry more than 2
 * decimal places — the {@code hours × rate × multiplier} product (e.g. 10.333
 * hrs × $15.375) almost never lands on a whole cent, and a caller-supplied fixed
 * amount was never itself validated to 2 decimals — while {@link PayrollCalculator}
 * separately rounded the displayed line item. Accumulating the RAW amount into
 * gross while displaying a separately-rounded line item meant "sum then round"
 * and "round then sum" could disagree by a cent, so the printed line items
 * didn't always foot to the printed gross.
 *
 * <p>{@link EarningLine#of} and {@link EarningLine#hourly} now round to 2
 * decimals the moment the amount is established, and {@link PayrollCalculator}
 * re-rounds defensively before accumulating into gross and reuses that exact
 * rounded value for the displayed line item — so gross is always exactly the
 * sum of what's printed, even for a hand-built {@code EarningLine} that bypasses
 * both factories via Lombok's public setters.
 */
@DisplayName("EarningLine / PayrollCalculator — rounding at construction and footing to gross (M12c)")
class EarningLineRoundingTest {

    @Nested
    @DisplayName("EarningLine factories round at construction")
    class ConstructionRounding {

        @Test
        @DisplayName("hourly(): an hours × rate product with more than 2 decimal places is rounded HALF_UP")
        void hourlyRoundsFractionalCentProduct() {
            // 10.333 hrs x $15.375/hr x 1 = 158.869875 raw -> 158.87
            EarningLine e = EarningLine.hourly(EarningType.REGULAR, "Hourly",
                    new BigDecimal("10.333"), new BigDecimal("15.375"), BigDecimal.ONE);

            assertThat(e.getAmount()).isEqualByComparingTo("158.87");
        }

        @Test
        @DisplayName("hourly(): the multiplier (e.g. 1.5x overtime) is applied before rounding, not after")
        void hourlyAppliesMultiplierBeforeRounding() {
            // 6 hrs x $20.125/hr x 1.5 = 181.125 raw, exactly at the HALF_UP boundary -> 181.13
            EarningLine e = EarningLine.hourly(EarningType.OVERTIME, "Overtime",
                    new BigDecimal("6"), new BigDecimal("20.125"), new BigDecimal("1.5"));

            assertThat(e.getAmount()).isEqualByComparingTo("181.13");
        }

        @Test
        @DisplayName("hourly(): null hours/rate default to zero and null multiplier defaults to one, never NPE")
        void hourlyNullInputsDefaultSafely() {
            EarningLine e = EarningLine.hourly(EarningType.REGULAR, "Empty", null, null, null);

            assertThat(e.getAmount()).isEqualByComparingTo("0.00");
            assertThat(e.getMultiplier()).isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("of(): a raw caller-supplied fixed amount (e.g. straight off a request body) is rounded too")
        void ofRoundsCallerSuppliedAmount() {
            // Not just the hours x rate path — a directly-submitted amount (as
            // PayrollAdminController accepts) is equally unvalidated input.
            EarningLine e = EarningLine.of(EarningType.BONUS, "Signing bonus", new BigDecimal("250.006"));

            assertThat(e.getAmount()).isEqualByComparingTo("250.01");
        }

        @Test
        @DisplayName("of(): a null amount is treated as zero, never NPE")
        void ofNullAmountDefaultsToZero() {
            EarningLine e = EarningLine.of(EarningType.OTHER, "Misc", null);

            assertThat(e.getAmount()).isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("PayrollCalculator: gross always foots exactly to the displayed earning line items")
    class FootingInvariant {

        private static final FederalWithholdingConfig ZERO_FEDERAL =
                new FederalWithholdingConfig(2026, new EnumMap<>(FilingStatus.class),
                        new EnumMap<>(FilingStatus.class), new EnumMap<>(FilingStatus.class));
        private static final FicaConfig ZERO_FICA =
                new FicaConfig(2026, BigDecimal.ZERO, BigDecimal.valueOf(1_000_000),
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(1_000_000));
        private static final StateWithholdingConfig NO_STATE = StateWithholdingConfig.none("TX");

        /** Builds an EarningLine with a raw, unrounded amount, bypassing EarningLine's own factories. */
        private static EarningLine handBuilt(EarningType type, String description, String rawAmount) {
            EarningLine e = new EarningLine();
            e.setType(type);
            e.setDescription(description);
            e.setAmount(new BigDecimal(rawAmount));
            return e;
        }

        @Test
        @DisplayName("defense-in-depth: two hand-built lines whose raw sum rounds differently than their individually-rounded sum still foot correctly")
        void defenseInDepthClosesTheDriftEvenForHandBuiltLines() {
            // 10.005 + 10.005 = 20.010 raw.
            //   sum-then-round (the old bug's effective behavior): round(20.010) = 20.01
            //   round-then-sum (the fix):     round(10.005) + round(10.005) = 10.01 + 10.01 = 20.02
            // These genuinely disagree by a cent, so this is real discriminating power,
            // not just re-asserting the same figure twice.
            PayrollCalculationInput in = new PayrollCalculationInput();
            in.addEarning(handBuilt(EarningType.REGULAR, "Line A", "10.005"));
            in.addEarning(handBuilt(EarningType.REGULAR, "Line B", "10.005"));

            PayrollCalculationResult r = PayrollCalculator.calculate(in, ZERO_FEDERAL, ZERO_FICA, NO_STATE);

            assertThat(r.getEarningItems()).hasSize(2);
            assertThat(r.getEarningItems().get(0).getCurrent()).isEqualByComparingTo("10.01");
            assertThat(r.getEarningItems().get(1).getCurrent()).isEqualByComparingTo("10.01");

            BigDecimal sumOfDisplayedItems = r.getEarningItems().stream()
                    .map(com.churchgeniuspro.payroll.engine.PaystubLineItem::getCurrent)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            assertThat(r.getGrossEarnings()).isEqualByComparingTo(sumOfDisplayedItems);
            assertThat(r.getGrossEarnings()).isEqualByComparingTo("20.02");
            // Proves this isn't a coincidence: the naive sum-then-round figure a
            // reader might expect is a different, wrong number.
            assertThat(r.getGrossEarnings()).isNotEqualByComparingTo("20.01");
        }

        @Test
        @DisplayName("realistic mix of hourly, overtime, and fixed lines: gross exactly equals the sum of the printed line items")
        void realisticMixFootsExactly() {
            PayrollCalculationInput in = new PayrollCalculationInput();
            in.addEarning(EarningLine.hourly(EarningType.REGULAR, "Hourly",
                    new BigDecimal("37.833"), new BigDecimal("18.625"), BigDecimal.ONE));
            in.addEarning(EarningLine.hourly(EarningType.OVERTIME, "Overtime",
                    new BigDecimal("3.167"), new BigDecimal("18.625"), new BigDecimal("1.5")));
            in.addEarning(EarningLine.of(EarningType.BONUS, "Spot bonus", new BigDecimal("75.003")));

            PayrollCalculationResult r = PayrollCalculator.calculate(in, ZERO_FEDERAL, ZERO_FICA, NO_STATE);

            BigDecimal sumOfDisplayedItems = r.getEarningItems().stream()
                    .map(com.churchgeniuspro.payroll.engine.PaystubLineItem::getCurrent)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            assertThat(r.getGrossEarnings()).isEqualByComparingTo(sumOfDisplayedItems);
        }

        @Test
        @DisplayName("a single unrounded hand-built line: the displayed item and gross agree on the same rounded value")
        void singleHandBuiltLineRoundsConsistently() {
            PayrollCalculationInput in = new PayrollCalculationInput();
            in.addEarning(handBuilt(EarningType.OTHER, "Stipend", "99.995"));

            PayrollCalculationResult r = PayrollCalculator.calculate(in, ZERO_FEDERAL, ZERO_FICA, NO_STATE);

            assertThat(r.getEarningItems().get(0).getCurrent()).isEqualByComparingTo("100.00");
            assertThat(r.getGrossEarnings()).isEqualByComparingTo("100.00");
        }
    }
}
