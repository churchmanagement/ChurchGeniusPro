package com.churchgeniuspro.payroll.service;

import com.churchgeniuspro.payroll.config.FederalWithholdingConfig;
import com.churchgeniuspro.payroll.config.FicaConfig;
import com.churchgeniuspro.payroll.config.StateWithholdingConfig;
import com.churchgeniuspro.payroll.engine.EarningLine;
import com.churchgeniuspro.payroll.entity.PayrollEmployee;
import com.churchgeniuspro.payroll.entity.PayrollRun;
import com.churchgeniuspro.payroll.entity.Paystub;
import com.churchgeniuspro.payroll.model.EarningType;
import com.churchgeniuspro.payroll.model.FilingStatus;
import com.churchgeniuspro.payroll.model.PayFrequency;
import com.churchgeniuspro.payroll.model.PayType;
import com.churchgeniuspro.payroll.model.PayrollRunStatus;
import com.churchgeniuspro.payroll.repository.DeductionDefinitionRepository;
import com.churchgeniuspro.payroll.repository.EmployeeDeductionRepository;
import com.churchgeniuspro.payroll.repository.PayrollAuditLogRepository;
import com.churchgeniuspro.payroll.repository.PayrollEmployeeRepository;
import com.churchgeniuspro.payroll.repository.PayrollRunRepository;
import com.churchgeniuspro.payroll.repository.PayrollW4Repository;
import com.churchgeniuspro.payroll.repository.PaystubItemRepository;
import com.churchgeniuspro.payroll.repository.PaystubRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Financial audit H1/H2/H3, exercised through {@link PayrollService}'s public API
 * (the private {@code computePriorYtd}/{@code recomputeRunTotals} helpers are
 * implementation detail; what must hold is the end-to-end behavior of
 * {@code processEmployee}/{@code voidRun}).
 *
 * <p>All tests use zero-rate tax configs so the only amounts that matter are
 * gross earnings and the FICA wage base — the tax math itself is out of scope
 * for these findings.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PayrollService — YTD accuracy and duplicate-paystub prevention (H1/H2/H3)")
class PayrollServiceYtdAndDuplicateTest {

    private static final String CLIENT = "CHR-ours";
    private static final Long RUN_ID = 100L;
    private static final Long EMPLOYEE_ID = 7L;

    @Mock PayrollEmployeeRepository employeeRepo;
    @Mock PayrollW4Repository w4Repo;
    @Mock EmployeeDeductionRepository employeeDeductionRepo;
    @Mock DeductionDefinitionRepository deductionDefRepo;
    @Mock PayrollRunRepository runRepo;
    @Mock PaystubRepository paystubRepo;
    @Mock PaystubItemRepository paystubItemRepo;
    @Mock PayrollAuditLogRepository auditRepo;
    @Mock TaxConfigService taxConfig;

    PayrollService service;

    private static final FederalWithholdingConfig ZERO_FEDERAL =
            new FederalWithholdingConfig(2026, new EnumMap<>(FilingStatus.class),
                    new EnumMap<>(FilingStatus.class), new EnumMap<>(FilingStatus.class));
    private static final FicaConfig ZERO_FICA =
            new FicaConfig(2026, BigDecimal.ZERO, BigDecimal.valueOf(1_000_000),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(1_000_000));
    private static final StateWithholdingConfig NO_STATE = StateWithholdingConfig.none("TX");

    @BeforeEach
    void setUp() {
        service = new PayrollService(employeeRepo, w4Repo, employeeDeductionRepo, deductionDefRepo,
                runRepo, paystubRepo, paystubItemRepo, auditRepo, taxConfig);
        when(taxConfig.loadFederal(anyInt())).thenReturn(ZERO_FEDERAL);
        when(taxConfig.loadFica(anyInt())).thenReturn(ZERO_FICA);
        when(taxConfig.loadState(anyInt(), any())).thenReturn(NO_STATE);
        when(employeeDeductionRepo.findByEmployeeIdAndActiveTrue(any())).thenReturn(List.of());
        when(w4Repo.findByEmployeeIdAndActiveTrue(any())).thenReturn(Optional.empty());
        when(paystubRepo.existsByRunIdAndEmployeeIdAndVoidedFalse(any(), any())).thenReturn(false);
        when(paystubRepo.save(any(Paystub.class))).thenAnswer(inv -> inv.getArgument(0));
        when(runRepo.save(any(PayrollRun.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private PayrollRun draftRun(LocalDate payDate) {
        PayrollRun run = new PayrollRun();
        run.setId(RUN_ID);
        run.setAppClientId(CLIENT);
        run.setPayFrequency(PayFrequency.BIWEEKLY);
        run.setPayDate(payDate);
        run.setStatus(PayrollRunStatus.DRAFT);
        return run;
    }

    private PayrollEmployee employee() {
        PayrollEmployee emp = new PayrollEmployee();
        emp.setId(EMPLOYEE_ID);
        emp.setAppClientId(CLIENT);
        emp.setFirstName("Jamie");
        emp.setLastName("Rivera");
        emp.setPayType(PayType.SALARY);
        return emp;
    }

    private Paystub priorStub(LocalDate payDate, boolean voided, BigDecimal gross, BigDecimal ficaWages) {
        Paystub s = new Paystub();
        s.setAppClientId(CLIENT);
        s.setRunId(RUN_ID - 1);
        s.setEmployeeId(EMPLOYEE_ID);
        s.setPayDate(payDate);
        s.setVoided(voided);
        s.setGrossEarnings(gross);
        s.setFicaWages(ficaWages);
        return s;
    }

    // ── H1: same-day pay date must still count as "prior" ─────────────────────

    @Nested
    @DisplayName("computePriorYtd date boundary (H1)")
    class SameDayYtd {

        @Test
        @DisplayName("a prior stub with the SAME pay date as the run being processed still counts toward YTD")
        void sameDayPriorStubCountsTowardYtd() {
            LocalDate payDate = LocalDate.of(2026, 3, 13);
            when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(draftRun(payDate)));
            when(employeeRepo.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee()));

            Paystub earlierSameDay = priorStub(payDate, false, new BigDecimal("1000.00"), new BigDecimal("1000.00"));
            when(paystubRepo.findByAppClientIdAndEmployeeIdOrderByPayDateDesc(CLIENT, EMPLOYEE_ID))
                    .thenReturn(List.of(earlierSameDay));
            when(paystubRepo.findByRunId(RUN_ID)).thenReturn(List.of());

            List<EarningLine> earnings = List.of(
                    EarningLine.of(EarningType.REGULAR, "Off-cycle correction", new BigDecimal("500.00")));
            Paystub result = service.processEmployee(RUN_ID, EMPLOYEE_ID, earnings, "tester");

            // Old behavior excluded any prior stub dated on-or-after the run's own pay
            // date, so this would have come back as exactly the new stub's own $500.00.
            assertThat(result.getYtdGross()).isEqualByComparingTo(new BigDecimal("1500.00"));
        }
    }

    // ── H2: a voided stub's FICA wages must never leak forward ─────────────────

    @Nested
    @DisplayName("computePriorYtd FICA summation (H2)")
    class VoidedFicaLeak {

        @Test
        @DisplayName("a voided stub's FICA wages never leak forward through a later stub's cumulative snapshot")
        void voidedStubFicaDoesNotLeak() {
            LocalDate newPayDate = LocalDate.of(2026, 2, 15);
            when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(draftRun(newPayDate)));
            when(employeeRepo.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee()));

            // Stub A: voided. Its own FICA wage base was $1000 — it was the first stub
            // of the year, so its stored ytdFicaWages snapshot was also $1000.
            Paystub stubA = priorStub(LocalDate.of(2026, 1, 15), true,
                    new BigDecimal("1000.00"), new BigDecimal("1000.00"));
            stubA.setYtdFicaWages(new BigDecimal("1000.00"));

            // Stub B: non-voided, processed while A was still active — so its stored
            // cumulative ytdFicaWages snapshot ($1800) still includes A's $1000, even
            // though A has since been voided. Its own per-period FICA wage base is $800.
            Paystub stubB = priorStub(LocalDate.of(2026, 1, 31), false,
                    new BigDecimal("800.00"), new BigDecimal("800.00"));
            stubB.setYtdFicaWages(new BigDecimal("1800.00"));

            when(paystubRepo.findByAppClientIdAndEmployeeIdOrderByPayDateDesc(CLIENT, EMPLOYEE_ID))
                    .thenReturn(List.of(stubB, stubA)); // desc by pay date, like the real query
            when(paystubRepo.findByRunId(RUN_ID)).thenReturn(List.of());

            List<EarningLine> earnings = List.of(EarningLine.of(EarningType.REGULAR, "Salary", new BigDecimal("900.00")));
            Paystub result = service.processEmployee(RUN_ID, EMPLOYEE_ID, earnings, "tester");

            // Correct: 800 (stub B's own wage base; A is voided and excluded) + 900
            // (this period) = 1700. The old snapshot-based code would have read stub
            // B's stale cumulative snapshot (1800, still containing voided A's 1000)
            // and produced 1800 + 900 = 2700 instead.
            assertThat(result.getYtdFicaWages()).isEqualByComparingTo(new BigDecimal("1700.00"));
        }
    }

    // ── H3: duplicate-paystub prevention ────────────────────────────────────────

    @Nested
    @DisplayName("duplicate-paystub prevention (H3)")
    class DuplicateGuard {

        @Test
        @DisplayName("a second processEmployee call for the same run+employee throws IllegalArgumentException before persisting anything")
        void preCheckBlocksDuplicate() {
            when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(draftRun(LocalDate.of(2026, 3, 13))));
            when(employeeRepo.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee()));
            when(paystubRepo.existsByRunIdAndEmployeeIdAndVoidedFalse(RUN_ID, EMPLOYEE_ID)).thenReturn(true);

            assertThatThrownBy(() -> service.processEmployee(RUN_ID, EMPLOYEE_ID, List.of(), "tester"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already has a paystub in this run");

            verify(paystubRepo, never()).save(any());
        }

        @Test
        @DisplayName("a racing duplicate that slips past the pre-check is also IllegalArgumentException, not a raw DB exception")
        void raceIsTranslatedToIllegalArgumentException() {
            when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(draftRun(LocalDate.of(2026, 3, 13))));
            when(employeeRepo.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee()));
            when(paystubRepo.existsByRunIdAndEmployeeIdAndVoidedFalse(RUN_ID, EMPLOYEE_ID)).thenReturn(false);
            when(paystubRepo.findByAppClientIdAndEmployeeIdOrderByPayDateDesc(CLIENT, EMPLOYEE_ID))
                    .thenReturn(List.of());
            when(paystubRepo.save(any(Paystub.class))).thenThrow(new DataIntegrityViolationException("duplicate key"));

            List<EarningLine> earnings = List.of(EarningLine.of(EarningType.REGULAR, "Salary", new BigDecimal("500.00")));
            assertThatThrownBy(() -> service.processEmployee(RUN_ID, EMPLOYEE_ID, earnings, "tester"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .isNotInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("already has a paystub in this run");
        }
    }

    // ── H3: run totals reflect actual state, not incremental accumulation ──────

    @Nested
    @DisplayName("run totals reflect actual state, not incremental accumulation (H3)")
    class RunTotals {

        @Test
        @DisplayName("processing an employee sets totalGross to the sum of the run's actual stubs, ignoring a stale pre-existing value")
        void recomputeIgnoresStaleAccumulatedTotal() {
            PayrollRun run = draftRun(LocalDate.of(2026, 3, 13));
            run.setTotalGross(new BigDecimal("9999.00")); // stale/leftover value from before this call
            run.setEmployeeCount(42);                     // stale/leftover value from before this call
            when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(run));
            when(employeeRepo.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee()));
            when(paystubRepo.findByAppClientIdAndEmployeeIdOrderByPayDateDesc(CLIENT, EMPLOYEE_ID))
                    .thenReturn(List.of());

            // Simulates the run, after this save, actually containing exactly this one stub.
            Paystub onlyStubInRun = new Paystub();
            onlyStubInRun.setGrossEarnings(new BigDecimal("500.00"));
            onlyStubInRun.setTotalTaxes(BigDecimal.ZERO);
            onlyStubInRun.setNetPay(new BigDecimal("500.00"));
            onlyStubInRun.setVoided(false);
            when(paystubRepo.findByRunId(RUN_ID)).thenReturn(List.of(onlyStubInRun));

            List<EarningLine> earnings = List.of(EarningLine.of(EarningType.REGULAR, "Salary", new BigDecimal("500.00")));
            service.processEmployee(RUN_ID, EMPLOYEE_ID, earnings, "tester");

            ArgumentCaptor<PayrollRun> savedRun = ArgumentCaptor.forClass(PayrollRun.class);
            verify(runRepo).save(savedRun.capture());
            assertThat(savedRun.getValue().getTotalGross()).isEqualByComparingTo(new BigDecimal("500.00"));
            assertThat(savedRun.getValue().getEmployeeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("voiding a run zeroes its reported totals and employee count, not just each stub's voided flag")
        void voidingZeroesRunTotals() {
            PayrollRun run = draftRun(LocalDate.of(2026, 3, 13));
            run.setStatus(PayrollRunStatus.APPROVED);
            run.setTotalGross(new BigDecimal("5000.00"));
            run.setTotalNet(new BigDecimal("4000.00"));
            run.setEmployeeCount(3);
            when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(run));

            Paystub s1 = new Paystub();
            s1.setGrossEarnings(new BigDecimal("1500.00"));
            s1.setNetPay(new BigDecimal("1300.00"));
            s1.setVoided(false);
            Paystub s2 = new Paystub();
            s2.setGrossEarnings(new BigDecimal("2000.00"));
            s2.setNetPay(new BigDecimal("1700.00"));
            s2.setVoided(false);
            when(paystubRepo.findByRunId(RUN_ID)).thenReturn(List.of(s1, s2));

            PayrollRun result = service.voidRun(RUN_ID, "tester", "processed in error");

            assertThat(result.getStatus()).isEqualTo(PayrollRunStatus.VOIDED);
            assertThat(result.getTotalGross()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.getTotalNet()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.getEmployeeCount()).isEqualTo(0);
            assertThat(s1.isVoided()).isTrue();
            assertThat(s2.isVoided()).isTrue();
        }
    }
}
