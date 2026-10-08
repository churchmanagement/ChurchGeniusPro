package com.churchgeniuspro.payroll.service;

import com.churchgeniuspro.payroll.config.Federal2026TaxData;
import com.churchgeniuspro.payroll.config.FicaConfig;
import com.churchgeniuspro.payroll.entity.FicaRate;
import com.churchgeniuspro.payroll.repository.FederalStandardDeductionRepository;
import com.churchgeniuspro.payroll.repository.FederalTaxBracketRepository;
import com.churchgeniuspro.payroll.repository.FicaRateRepository;
import com.churchgeniuspro.payroll.repository.StateTaxBracketRepository;
import com.churchgeniuspro.payroll.repository.StateTaxConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Database audit C1: production's {@code payroll_fica_rate} row read {@code 0.06 / 0.01 /
 * 0.01} and {@code loadFica} handed it to the calculator without a word. For the year this
 * build carries statutory values for, a stored row that disagrees with them is now refused
 * — payroll stops, loudly, instead of withholding at the wrong rate again.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TaxConfigService.loadFica — refuses a 2026 FICA row that is not the statutory one (DB audit C1)")
class TaxConfigServiceFicaGuardTest {

    @Mock FederalTaxBracketRepository federalBracketRepo;
    @Mock FederalStandardDeductionRepository stdDeductionRepo;
    @Mock FicaRateRepository ficaRepo;
    @Mock StateTaxConfigRepository stateConfigRepo;
    @Mock StateTaxBracketRepository stateBracketRepo;

    TaxConfigService service;

    @BeforeEach
    void setUp() {
        service = new TaxConfigService(federalBracketRepo, stdDeductionRepo, ficaRepo, stateConfigRepo, stateBracketRepo);
    }

    private static FicaRate row(int year, String ss, String medicare, String additional) {
        FicaRate r = new FicaRate();
        r.setEffectiveYear(year);
        r.setSocialSecurityRate(new BigDecimal(ss));
        r.setSocialSecurityWageBase(new BigDecimal("184500.00"));
        r.setMedicareRate(new BigDecimal(medicare));
        r.setAdditionalMedicareRate(new BigDecimal(additional));
        r.setAdditionalMedicareThreshold(new BigDecimal("200000.00"));
        return r;
    }

    @Test
    @DisplayName("production's row (0.06 / 0.01 / 0.01) is refused, naming every wrong value and the remedy")
    void roundedRowIsRefused() {
        when(ficaRepo.findByEffectiveYear(2026)).thenReturn(Optional.of(row(2026, "0.06", "0.01", "0.01")));

        assertThatThrownBy(() -> service.loadFica(2026))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Social Security rate is 0.06 instead of 0.062")
                .hasMessageContaining("Medicare rate is 0.01 instead of 0.0145")
                .hasMessageContaining("Additional Medicare rate is 0.01 instead of 0.009")
                .hasMessageContaining("Payroll cannot run")
                .hasMessageContaining("Database audit C1");
    }

    @Test
    @DisplayName("the statutory row — however many trailing zeros numeric(9,6) gives it back with — is accepted as is")
    void statutoryRowIsAccepted() {
        when(ficaRepo.findByEffectiveYear(2026)).thenReturn(Optional.of(row(2026, "0.062000", "0.014500", "0.009000")));

        FicaConfig cfg = service.loadFica(2026);

        assertThat(cfg.getSocialSecurityRate()).isEqualByComparingTo("0.062");
        assertThat(cfg.getMedicareRate()).isEqualByComparingTo("0.0145");
        assertThat(cfg.getAdditionalMedicareRate()).isEqualByComparingTo("0.009");
        assertThat(cfg.getSocialSecurityWageBase()).isEqualByComparingTo(Federal2026TaxData.fica().getSocialSecurityWageBase());
    }

    @Test
    @DisplayName("no 2026 row yet — the in-code statutory values, as before")
    void missingRowFallsBackToConstants() {
        when(ficaRepo.findByEffectiveYear(2026)).thenReturn(Optional.empty());

        FicaConfig cfg = service.loadFica(2026);

        assertThat(cfg.getSocialSecurityRate()).isEqualByComparingTo("0.062");
    }

    @Test
    @DisplayName("a wrong wage base or threshold is refused too — they are statutory as well")
    void wrongAmountsAreRefused() {
        FicaRate r = row(2026, "0.062", "0.0145", "0.009");
        r.setSocialSecurityWageBase(new BigDecimal("176100"));   // the 2025 figure
        when(ficaRepo.findByEffectiveYear(2026)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.loadFica(2026))
                .hasMessageContaining("Social Security wage base is 176100 instead of 184500");
    }

    @Test
    @DisplayName("a year this build has no statutory values for is trusted from the database, as designed")
    void otherYearsAreNotChecked() {
        when(ficaRepo.findByEffectiveYear(2027)).thenReturn(Optional.of(row(2027, "0.0625", "0.0145", "0.009")));

        FicaConfig cfg = service.loadFica(2027);

        assertThat(cfg.getSocialSecurityRate()).isEqualByComparingTo("0.0625");
    }
}
