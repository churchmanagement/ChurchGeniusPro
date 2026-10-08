package com.churchgeniuspro.payroll.service;

import com.churchgeniuspro.payroll.config.FederalWithholdingConfig;
import com.churchgeniuspro.payroll.config.FicaConfig;
import com.churchgeniuspro.payroll.config.StateWithholdingConfig;
import com.churchgeniuspro.payroll.engine.EarningLine;
import com.churchgeniuspro.payroll.entity.DeductionDefinition;
import com.churchgeniuspro.payroll.entity.EmployeeDeduction;
import com.churchgeniuspro.payroll.entity.PayrollEmployee;
import com.churchgeniuspro.payroll.entity.PayrollRun;
import com.churchgeniuspro.payroll.entity.Paystub;
import com.churchgeniuspro.payroll.entity.PaystubItem;
import com.churchgeniuspro.payroll.model.DeductionScope;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Financial audit M12a: {@code EmployeeDeduction.annualLimit} (e.g. a 401(k)
 * elective-deferral cap) was stored and echoed back by the API but never read in
 * {@code PayrollService.buildDeductions} — a deferral could run past the
 * statutory limit all year with nothing stopping it. Deductions with a limit are
 * now reduced (never below zero) so this employee's year-to-date total for that
 * SPECIFIC deduction never exceeds its cap.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PayrollService — annual deduction limit enforcement (M12a)")
class PayrollAnnualDeductionLimitTest {

    private static final String CLIENT = "CHR-ours";
    private static final Long RUN_ID = 200L;
    private static final Long EMPLOYEE_ID = 9L;
    private static final Long DEF_401K_ID = 1L;
    private static final Long DEF_HSA_ID = 2L;

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
        when(w4Repo.findByEmployeeIdAndActiveTrue(any())).thenReturn(Optional.empty());
        when(paystubRepo.existsByRunIdAndEmployeeIdAndVoidedFalse(any(), any())).thenReturn(false);
        when(paystubRepo.save(any(Paystub.class))).thenAnswer(inv -> inv.getArgument(0));
        when(runRepo.save(any(PayrollRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paystubRepo.findByRunId(RUN_ID)).thenReturn(List.of());
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
        emp.setFirstName("Robin");
        emp.setLastName("Chen");
        emp.setPayType(PayType.SALARY);
        return emp;
    }

    private static DeductionDefinition def401k() {
        DeductionDefinition d = new DeductionDefinition();
        d.setId(DEF_401K_ID);
        d.setAppClientId(CLIENT);
        d.setName("401(k)");
        d.setScope(DeductionScope.PRE_TAX);
        d.setReducesFederalTaxable(true);
        d.setReducesStateTaxable(true);
        d.setReducesFicaWages(false);
        d.setActive(true);
        return d;
    }

    private static DeductionDefinition defHsa() {
        DeductionDefinition d = new DeductionDefinition();
        d.setId(DEF_HSA_ID);
        d.setAppClientId(CLIENT);
        d.setName("HSA");
        d.setScope(DeductionScope.PRE_TAX);
        d.setReducesFederalTaxable(true);
        d.setReducesStateTaxable(true);
        d.setReducesFicaWages(true);
        d.setActive(true);
        return d;
    }

    private static EmployeeDeduction assignment(Long defId, String amountOrRate, String annualLimit) {
        EmployeeDeduction a = new EmployeeDeduction();
        a.setAppClientId(CLIENT);
        a.setEmployeeId(EMPLOYEE_ID);
        a.setDefinitionId(defId);
        a.setAmountOrRate(new BigDecimal(amountOrRate));
        a.setAnnualLimit(annualLimit == null ? null : new BigDecimal(annualLimit));
        a.setActive(true);
        return a;
    }

    private static Paystub priorStub(Long id, LocalDate payDate) {
        Paystub s = new Paystub();
        s.setId(id);
        s.setAppClientId(CLIENT);
        s.setEmployeeId(EMPLOYEE_ID);
        s.setPayDate(payDate);
        s.setVoided(false);
        return s;
    }

    private static PaystubItem preTaxItem(BigDecimal amount, String label) {
        PaystubItem i = new PaystubItem();
        i.setCategory(PaystubItem.Category.PRE_TAX_DEDUCTION);
        i.setLabel(label);
        i.setCurrentAmount(amount);
        return i;
    }

    @Test
    @DisplayName("a deduction with no annualLimit is completely unaffected")
    void noLimitIsUnaffected() {
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(draftRun(LocalDate.of(2026, 3, 13))));
        when(employeeRepo.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee()));
        when(employeeDeductionRepo.findByEmployeeIdAndActiveTrue(EMPLOYEE_ID))
                .thenReturn(List.of(assignment(DEF_401K_ID, "500.00", null)));
        when(deductionDefRepo.findByAppClientIdAndActiveTrue(CLIENT)).thenReturn(List.of(def401k()));
        when(paystubRepo.findByAppClientIdAndEmployeeIdOrderByPayDateDesc(CLIENT, EMPLOYEE_ID))
                .thenReturn(List.of());

        List<EarningLine> earnings = List.of(EarningLine.of(EarningType.REGULAR, "Salary", new BigDecimal("2000.00")));
        Paystub result = service.processEmployee(RUN_ID, EMPLOYEE_ID, earnings, "tester");

        assertThat(result.getPreTaxDeductions()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("this period's deduction is reduced so YTD lands exactly on the limit, not over it")
    void reducesToExactlyTheRemainingRoom() {
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(draftRun(LocalDate.of(2026, 6, 1))));
        when(employeeRepo.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee()));
        // $22,600 already withheld this year toward a $23,000 annual limit — only
        // $400 of room remains, even though the assignment's own per-period amount is $900.
        when(employeeDeductionRepo.findByEmployeeIdAndActiveTrue(EMPLOYEE_ID))
                .thenReturn(List.of(assignment(DEF_401K_ID, "900.00", "23000.00")));
        when(deductionDefRepo.findByAppClientIdAndActiveTrue(CLIENT)).thenReturn(List.of(def401k()));

        Paystub prior = priorStub(500L, LocalDate.of(2026, 5, 15));
        when(paystubRepo.findByAppClientIdAndEmployeeIdOrderByPayDateDesc(CLIENT, EMPLOYEE_ID))
                .thenReturn(List.of(prior));
        when(paystubItemRepo.findByPaystubIdInAndCategoryAndLabel(
                eq(List.of(500L)), eq(PaystubItem.Category.PRE_TAX_DEDUCTION), eq("401(k)")))
                .thenReturn(List.of(preTaxItem(new BigDecimal("22600.00"), "401(k)")));

        List<EarningLine> earnings = List.of(EarningLine.of(EarningType.REGULAR, "Salary", new BigDecimal("3000.00")));
        Paystub result = service.processEmployee(RUN_ID, EMPLOYEE_ID, earnings, "tester");

        assertThat(result.getPreTaxDeductions()).isEqualByComparingTo("400.00");
        assertThat(result.getYtdPreTaxDeductions()).isEqualByComparingTo("400.00"); // this run's own YTD accumulator
    }

    @Test
    @DisplayName("a deduction already at (or past) its annual limit is reduced to zero, never negative")
    void reducesToZeroNeverNegative() {
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(draftRun(LocalDate.of(2026, 12, 1))));
        when(employeeRepo.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee()));
        when(employeeDeductionRepo.findByEmployeeIdAndActiveTrue(EMPLOYEE_ID))
                .thenReturn(List.of(assignment(DEF_401K_ID, "900.00", "23000.00")));
        when(deductionDefRepo.findByAppClientIdAndActiveTrue(CLIENT)).thenReturn(List.of(def401k()));

        Paystub prior = priorStub(501L, LocalDate.of(2026, 11, 15));
        when(paystubRepo.findByAppClientIdAndEmployeeIdOrderByPayDateDesc(CLIENT, EMPLOYEE_ID))
                .thenReturn(List.of(prior));
        // Already at the cap — no room left at all.
        when(paystubItemRepo.findByPaystubIdInAndCategoryAndLabel(
                eq(List.of(501L)), eq(PaystubItem.Category.PRE_TAX_DEDUCTION), eq("401(k)")))
                .thenReturn(List.of(preTaxItem(new BigDecimal("23000.00"), "401(k)")));

        List<EarningLine> earnings = List.of(EarningLine.of(EarningType.REGULAR, "Salary", new BigDecimal("3000.00")));
        Paystub result = service.processEmployee(RUN_ID, EMPLOYEE_ID, earnings, "tester");

        assertThat(result.getPreTaxDeductions()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("the cap applies AFTER a percentage-based deduction is resolved to a dollar amount")
    void capAppliesAfterPercentageResolution() {
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(draftRun(LocalDate.of(2026, 6, 1))));
        when(employeeRepo.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee()));
        DeductionDefinition pctDef = def401k();
        pctDef.setPercentageBased(true);
        // 10% of a $5,000 gross = $500/period, but only $150 of room remains.
        when(employeeDeductionRepo.findByEmployeeIdAndActiveTrue(EMPLOYEE_ID))
                .thenReturn(List.of(assignment(DEF_401K_ID, "0.10", "23000.00")));
        when(deductionDefRepo.findByAppClientIdAndActiveTrue(CLIENT)).thenReturn(List.of(pctDef));

        Paystub prior = priorStub(502L, LocalDate.of(2026, 5, 15));
        when(paystubRepo.findByAppClientIdAndEmployeeIdOrderByPayDateDesc(CLIENT, EMPLOYEE_ID))
                .thenReturn(List.of(prior));
        when(paystubItemRepo.findByPaystubIdInAndCategoryAndLabel(
                eq(List.of(502L)), eq(PaystubItem.Category.PRE_TAX_DEDUCTION), eq("401(k)")))
                .thenReturn(List.of(preTaxItem(new BigDecimal("22850.00"), "401(k)")));

        List<EarningLine> earnings = List.of(EarningLine.of(EarningType.REGULAR, "Salary", new BigDecimal("5000.00")));
        Paystub result = service.processEmployee(RUN_ID, EMPLOYEE_ID, earnings, "tester");

        assertThat(result.getPreTaxDeductions()).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("only non-voided stubs from THIS calendar year count toward the limit")
    void onlyNonVoidedCurrentYearStubsCount() {
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(draftRun(LocalDate.of(2026, 3, 1))));
        when(employeeRepo.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee()));
        when(employeeDeductionRepo.findByEmployeeIdAndActiveTrue(EMPLOYEE_ID))
                .thenReturn(List.of(assignment(DEF_401K_ID, "900.00", "1000.00")));
        when(deductionDefRepo.findByAppClientIdAndActiveTrue(CLIENT)).thenReturn(List.of(def401k()));

        Paystub voided2026 = priorStub(503L, LocalDate.of(2026, 2, 1));
        voided2026.setVoided(true);
        Paystub last2025 = priorStub(504L, LocalDate.of(2025, 12, 15));
        when(paystubRepo.findByAppClientIdAndEmployeeIdOrderByPayDateDesc(CLIENT, EMPLOYEE_ID))
                .thenReturn(List.of(voided2026, last2025));
        // Neither prior stub is eligible (one voided, one a prior year), so the
        // repository is never even asked to sum items for them — the id list
        // priorStubIdsThisYear() builds is empty. If the code incorrectly counted
        // either stub, this stub would come back capped instead of full-amount.

        List<EarningLine> earnings = List.of(EarningLine.of(EarningType.REGULAR, "Salary", new BigDecimal("3000.00")));
        Paystub result = service.processEmployee(RUN_ID, EMPLOYEE_ID, earnings, "tester");

        assertThat(result.getPreTaxDeductions()).isEqualByComparingTo("900.00");
    }

    @Test
    @DisplayName("two differently-capped deductions on the same employee are tracked independently")
    void twoCappedDeductionsAreIndependent() {
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(draftRun(LocalDate.of(2026, 6, 1))));
        when(employeeRepo.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee()));
        when(employeeDeductionRepo.findByEmployeeIdAndActiveTrue(EMPLOYEE_ID)).thenReturn(List.of(
                assignment(DEF_401K_ID, "500.00", "1000.00"),
                assignment(DEF_HSA_ID, "300.00", "2000.00")));
        when(deductionDefRepo.findByAppClientIdAndActiveTrue(CLIENT))
                .thenReturn(List.of(def401k(), defHsa()));

        Paystub prior = priorStub(505L, LocalDate.of(2026, 5, 1));
        when(paystubRepo.findByAppClientIdAndEmployeeIdOrderByPayDateDesc(CLIENT, EMPLOYEE_ID))
                .thenReturn(List.of(prior));
        // 401(k) has only $200 of room left; HSA has plenty of room and is untouched.
        when(paystubItemRepo.findByPaystubIdInAndCategoryAndLabel(
                eq(List.of(505L)), eq(PaystubItem.Category.PRE_TAX_DEDUCTION), eq("401(k)")))
                .thenReturn(List.of(preTaxItem(new BigDecimal("800.00"), "401(k)")));
        when(paystubItemRepo.findByPaystubIdInAndCategoryAndLabel(
                eq(List.of(505L)), eq(PaystubItem.Category.PRE_TAX_DEDUCTION), eq("HSA")))
                .thenReturn(List.of(preTaxItem(new BigDecimal("300.00"), "HSA")));

        List<EarningLine> earnings = List.of(EarningLine.of(EarningType.REGULAR, "Salary", new BigDecimal("5000.00")));
        Paystub result = service.processEmployee(RUN_ID, EMPLOYEE_ID, earnings, "tester");

        // 401(k) capped to $200, HSA unaffected at $300 => total pre-tax = $500.
        assertThat(result.getPreTaxDeductions()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("a zero annualLimit is treated as no cap, not as a $0 cap")
    void zeroLimitTreatedAsNoCap() {
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(draftRun(LocalDate.of(2026, 3, 13))));
        when(employeeRepo.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee()));
        when(employeeDeductionRepo.findByEmployeeIdAndActiveTrue(EMPLOYEE_ID))
                .thenReturn(List.of(assignment(DEF_401K_ID, "500.00", "0.00")));
        when(deductionDefRepo.findByAppClientIdAndActiveTrue(CLIENT)).thenReturn(List.of(def401k()));

        List<EarningLine> earnings = List.of(EarningLine.of(EarningType.REGULAR, "Salary", new BigDecimal("2000.00")));
        Paystub result = service.processEmployee(RUN_ID, EMPLOYEE_ID, earnings, "tester");

        assertThat(result.getPreTaxDeductions()).isEqualByComparingTo("500.00");
        // No cap enabled => the prior-stub lookup for the limit check is never needed.
        assertThat(result.getPreTaxDeductions()).isNotEqualByComparingTo("0.00");
    }
}
