package com.churchgeniuspro.payroll.service;

import com.churchgeniuspro.payroll.entity.Paystub;
import com.churchgeniuspro.payroll.repository.PayrollEmployeeRepository;
import com.churchgeniuspro.payroll.repository.PaystubRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Financial audit M12b: {@link PayrollReportingService#taxLiability(Long)} used
 * to total only what's withheld FROM the employee and present that as the full
 * tax liability — but the employer separately owes its own matching Social
 * Security and Medicare on top of what was withheld, which previously appeared
 * nowhere and understated the actual 941 deposit by that matching amount.
 * {@code employerSocialSecurity}/{@code employerMedicare} now report that
 * employer-side cost, mirrored from the already-persisted employee-side
 * figures (correct under current law, where the employer rate equals the
 * employee rate), and specifically exclude the employee-only Additional
 * Medicare surtax.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PayrollReportingService — employer FICA liability reporting (M12b)")
class PayrollEmployerFicaLiabilityTest {

    private static final Long RUN_ID = 700L;

    @Mock PaystubRepository paystubRepo;
    @Mock PayrollEmployeeRepository employeeRepo;
    @Mock TaxConfigService taxConfig;

    PayrollReportingService service;

    @BeforeEach
    void setUp() {
        service = new PayrollReportingService(paystubRepo, employeeRepo, taxConfig);
    }

    private static Paystub stub(Long id, String ss, String medicare, String addlMedicare, boolean voided) {
        Paystub s = new Paystub();
        s.setId(id);
        s.setRunId(RUN_ID);
        s.setSocialSecurity(new BigDecimal(ss));
        s.setMedicare(new BigDecimal(medicare));
        s.setAdditionalMedicare(new BigDecimal(addlMedicare));
        s.setFederalWithholding(BigDecimal.ZERO);
        s.setStateWithholding(BigDecimal.ZERO);
        s.setLocalTax(BigDecimal.ZERO);
        s.setVoided(voided);
        return s;
    }

    @Test
    @DisplayName("employerSocialSecurity mirrors the summed employee-withheld Social Security")
    void employerSocialSecurityMirrorsEmployeeWithheld() {
        when(paystubRepo.findByRunId(RUN_ID)).thenReturn(List.of(
                stub(1L, "310.00", "72.50", "0.00", false),
                stub(2L, "190.00", "44.40", "0.00", false)));

        Map<String, BigDecimal> out = service.taxLiability(RUN_ID);

        assertThat(out.get("socialSecurity")).isEqualByComparingTo("500.00");
        assertThat(out.get("employerSocialSecurity")).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("employerMedicare mirrors the summed employee-withheld regular Medicare")
    void employerMedicareMirrorsEmployeeWithheld() {
        when(paystubRepo.findByRunId(RUN_ID)).thenReturn(List.of(
                stub(1L, "310.00", "72.50", "0.00", false),
                stub(2L, "190.00", "44.40", "0.00", false)));

        Map<String, BigDecimal> out = service.taxLiability(RUN_ID);

        assertThat(out.get("medicare")).isEqualByComparingTo("116.90");
        assertThat(out.get("employerMedicare")).isEqualByComparingTo("116.90");
    }

    @Test
    @DisplayName("Additional Medicare has no employer match, so it is excluded from employerMedicare")
    void additionalMedicareExcludedFromEmployerMatch() {
        // A high earner over the $200k threshold: $145 regular Medicare plus a
        // $36 Additional Medicare surtax withheld from the EMPLOYEE only.
        when(paystubRepo.findByRunId(RUN_ID)).thenReturn(List.of(
                stub(1L, "0.00", "145.00", "36.00", false)));

        Map<String, BigDecimal> out = service.taxLiability(RUN_ID);

        assertThat(out.get("additionalMedicare")).isEqualByComparingTo("36.00");
        // Employer owes only the regular-Medicare match — the surtax is
        // statutorily employee-only, so it must NOT leak into the employer figure.
        assertThat(out.get("employerMedicare")).isEqualByComparingTo("145.00");
        assertThat(out.get("employerMedicare")).isNotEqualByComparingTo("181.00");
    }

    @Test
    @DisplayName("a voided paystub contributes to neither the employee nor the employer figures")
    void voidedStubExcludedFromBothSides() {
        when(paystubRepo.findByRunId(RUN_ID)).thenReturn(List.of(
                stub(1L, "310.00", "72.50", "0.00", false),
                stub(2L, "999.00", "999.00", "999.00", true))); // voided — must be ignored entirely

        Map<String, BigDecimal> out = service.taxLiability(RUN_ID);

        assertThat(out.get("socialSecurity")).isEqualByComparingTo("310.00");
        assertThat(out.get("medicare")).isEqualByComparingTo("72.50");
        assertThat(out.get("employerSocialSecurity")).isEqualByComparingTo("310.00");
        assertThat(out.get("employerMedicare")).isEqualByComparingTo("72.50");
    }

    @Test
    @DisplayName("an empty run reports all zeros, including the new employer keys")
    void emptyRunIsAllZeros() {
        when(paystubRepo.findByRunId(RUN_ID)).thenReturn(List.of());

        Map<String, BigDecimal> out = service.taxLiability(RUN_ID);

        assertThat(out.get("employerSocialSecurity")).isEqualByComparingTo("0.00");
        assertThat(out.get("employerMedicare")).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("pre-existing employee-side keys are unchanged by the new employer keys")
    void preExistingKeysUnaffected() {
        Paystub withOtherTaxes = stub(2L, "190.00", "44.40", "0.00", false);
        withOtherTaxes.setFederalWithholding(new BigDecimal("400.00"));
        withOtherTaxes.setStateWithholding(new BigDecimal("55.00"));
        withOtherTaxes.setLocalTax(new BigDecimal("12.00"));
        when(paystubRepo.findByRunId(RUN_ID)).thenReturn(List.of(
                stub(1L, "310.00", "72.50", "10.00", false), withOtherTaxes));

        Map<String, BigDecimal> out = service.taxLiability(RUN_ID);

        assertThat(out.get("federalIncomeTax")).isEqualByComparingTo("400.00");
        assertThat(out.get("socialSecurity")).isEqualByComparingTo("500.00");
        assertThat(out.get("medicare")).isEqualByComparingTo("116.90");
        assertThat(out.get("additionalMedicare")).isEqualByComparingTo("10.00");
        assertThat(out.get("stateIncomeTax")).isEqualByComparingTo("55.00");
        assertThat(out.get("localTax")).isEqualByComparingTo("12.00");
        // And the new employer keys are simply additive alongside these, not a
        // replacement for them.
        assertThat(out).containsKeys("employerSocialSecurity", "employerMedicare");
    }

    @Test
    @DisplayName("multiple non-voided stubs accumulate the employer figures across the whole run")
    void accumulatesAcrossManyStubs() {
        when(paystubRepo.findByRunId(RUN_ID)).thenReturn(List.of(
                stub(1L, "100.00", "25.00", "0.00", false),
                stub(2L, "200.00", "50.00", "0.00", false),
                stub(3L, "300.00", "75.00", "0.00", false)));

        Map<String, BigDecimal> out = service.taxLiability(RUN_ID);

        assertThat(out.get("employerSocialSecurity")).isEqualByComparingTo("600.00");
        assertThat(out.get("employerMedicare")).isEqualByComparingTo("150.00");
    }
}
