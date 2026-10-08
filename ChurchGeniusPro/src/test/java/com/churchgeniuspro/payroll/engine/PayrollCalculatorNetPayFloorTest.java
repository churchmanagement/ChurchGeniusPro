package com.churchgeniuspro.payroll.engine;

import com.churchgeniuspro.payroll.config.FederalWithholdingConfig;
import com.churchgeniuspro.payroll.config.FicaConfig;
import com.churchgeniuspro.payroll.config.StateWithholdingConfig;
import com.churchgeniuspro.payroll.model.EarningType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.churchgeniuspro.payroll.model.FilingStatus;

import java.math.BigDecimal;
import java.util.EnumMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PayrollCalculator} must never let net pay go negative — a bank can't pay
 * out a negative amount. Financial audit H4: before this, a period whose taxes
 * and deductions exceeded its gross earnings produced a negative {@code netPay}
 * with no record anywhere that anything was short. Net pay now clamps at zero
 * and the uncollected amount is carried separately as {@code arrearsAmount}, so
 * it stays visible (paystub PDF, API responses) instead of silently vanishing.
 *
 * <p>Every test here uses zero-rate tax configs, so the arithmetic is exactly
 * {@code gross − preTax − postTax}. That isolates the floor/arrears behavior
 * from the federal/FICA/state withholding rules themselves, which are outside
 * this finding and are not what these tests are pinning down.
 */
class PayrollCalculatorNetPayFloorTest {

    private static final FederalWithholdingConfig ZERO_FEDERAL =
            new FederalWithholdingConfig(2026, new EnumMap<>(FilingStatus.class),
                    new EnumMap<>(FilingStatus.class), new EnumMap<>(FilingStatus.class));
    private static final FicaConfig ZERO_FICA =
            new FicaConfig(2026, BigDecimal.ZERO, BigDecimal.valueOf(1_000_000),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(1_000_000));
    private static final StateWithholdingConfig NO_STATE = StateWithholdingConfig.none("TX");

    private static PayrollCalculationResult run(BigDecimal gross, BigDecimal postTaxDeduction) {
        PayrollCalculationInput in = new PayrollCalculationInput();
        in.addEarning(EarningLine.of(EarningType.REGULAR, "Salary", gross));
        if (postTaxDeduction != null) {
            in.addDeduction(DeductionLine.postTax("Garnishment", postTaxDeduction));
        }
        return PayrollCalculator.calculate(in, ZERO_FEDERAL, ZERO_FICA, NO_STATE);
    }

    @Test
    @DisplayName("net pay clamps at zero and the shortfall is carried as arrearsAmount when deductions exceed gross")
    void clampsAtZeroAndCarriesArrears() {
        PayrollCalculationResult r = run(new BigDecimal("500.00"), new BigDecimal("700.00"));
        assertThat(r.getNetPay()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(r.getArrearsAmount()).isEqualByComparingTo(new BigDecimal("200.00"));
    }

    @Test
    @DisplayName("net pay is unaffected and arrears stays zero when gross fully covers deductions")
    void noArrearsWhenGrossCovers() {
        PayrollCalculationResult r = run(new BigDecimal("1000.00"), new BigDecimal("100.00"));
        assertThat(r.getNetPay()).isEqualByComparingTo(new BigDecimal("900.00"));
        assertThat(r.getArrearsAmount()).isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    @DisplayName("boundary: gross exactly equal to deductions produces zero net pay and zero arrears, not a sign error")
    void exactBoundaryIsZeroNotArrears() {
        PayrollCalculationResult r = run(new BigDecimal("500.00"), new BigDecimal("500.00"));
        assertThat(r.getNetPay()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(r.getArrearsAmount()).isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    @DisplayName("no deductions at all: net pay equals gross, arrears is zero")
    void noDeductionsNetEqualsGross() {
        PayrollCalculationResult r = run(new BigDecimal("650.00"), null);
        assertThat(r.getNetPay()).isEqualByComparingTo(new BigDecimal("650.00"));
        assertThat(r.getArrearsAmount()).isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    @DisplayName("exactly one of netPay/arrearsAmount is non-zero, and netPay minus arrearsAmount reconstructs gross minus deductions")
    void invariantHolds() {
        PayrollCalculationResult r = run(new BigDecimal("300.00"), new BigDecimal("725.50"));
        assertThat(r.getNetPay()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(r.getArrearsAmount()).isEqualByComparingTo(new BigDecimal("425.50"));
        BigDecimal reconstructed = r.getNetPay().subtract(r.getArrearsAmount());
        assertThat(reconstructed).isEqualByComparingTo(new BigDecimal("300.00").subtract(new BigDecimal("725.50")));
    }
}
