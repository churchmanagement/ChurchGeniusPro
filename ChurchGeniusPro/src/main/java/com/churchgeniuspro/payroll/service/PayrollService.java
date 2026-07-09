package com.churchgeniuspro.payroll.service;

import com.churchgeniuspro.payroll.config.FederalWithholdingConfig;
import com.churchgeniuspro.payroll.config.FicaConfig;
import com.churchgeniuspro.payroll.config.StateWithholdingConfig;
import com.churchgeniuspro.payroll.engine.*;
import com.churchgeniuspro.payroll.entity.*;
import com.churchgeniuspro.payroll.model.PayFrequency;
import com.churchgeniuspro.payroll.model.PayType;
import com.churchgeniuspro.payroll.model.PayrollRunStatus;
import com.churchgeniuspro.payroll.model.EarningType;
import com.churchgeniuspro.payroll.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Orchestrates payroll: turns employee/W-4/deduction entities into engine inputs,
 * runs {@link PayrollCalculator}, persists itemized {@link Paystub}s, rolls up run
 * totals, and drives the DRAFT → PENDING_APPROVAL → APPROVED → PAID (or VOIDED)
 * workflow with an append-only {@link PayrollAuditLog}.
 */
@Service
public class PayrollService {

    private static final Logger log = LoggerFactory.getLogger(PayrollService.class);

    private final PayrollEmployeeRepository employeeRepo;
    private final PayrollW4Repository w4Repo;
    private final EmployeeDeductionRepository employeeDeductionRepo;
    private final DeductionDefinitionRepository deductionDefRepo;
    private final PayrollRunRepository runRepo;
    private final PaystubRepository paystubRepo;
    private final PaystubItemRepository paystubItemRepo;
    private final PayrollAuditLogRepository auditRepo;
    private final TaxConfigService taxConfig;

    public PayrollService(PayrollEmployeeRepository employeeRepo,
                          PayrollW4Repository w4Repo,
                          EmployeeDeductionRepository employeeDeductionRepo,
                          DeductionDefinitionRepository deductionDefRepo,
                          PayrollRunRepository runRepo,
                          PaystubRepository paystubRepo,
                          PaystubItemRepository paystubItemRepo,
                          PayrollAuditLogRepository auditRepo,
                          TaxConfigService taxConfig) {
        this.employeeRepo = employeeRepo;
        this.w4Repo = w4Repo;
        this.employeeDeductionRepo = employeeDeductionRepo;
        this.deductionDefRepo = deductionDefRepo;
        this.runRepo = runRepo;
        this.paystubRepo = paystubRepo;
        this.paystubItemRepo = paystubItemRepo;
        this.auditRepo = auditRepo;
        this.taxConfig = taxConfig;
    }

    // ── Run lifecycle ──────────────────────────────────────────────────────

    @Transactional
    public PayrollRun createRun(String appClientId, PayFrequency freq,
                                LocalDate periodStart, LocalDate periodEnd, LocalDate payDate,
                                String actor) {
        taxConfig.seed2026IfAbsent();
        PayrollRun run = new PayrollRun();
        run.setAppClientId(appClientId);
        run.setPayFrequency(freq);
        run.setPayPeriodStart(periodStart);
        run.setPayPeriodEnd(periodEnd);
        run.setPayDate(payDate);
        run.setStatus(PayrollRunStatus.DRAFT);
        run.setCreatedBy(actor);
        run = runRepo.save(run);
        audit(appClientId, "PayrollRun", run.getId(), "CREATE", actor,
                "Created " + freq + " run, pay date " + payDate);
        return run;
    }

    /**
     * Calculate and persist one employee's paystub within a DRAFT run.
     *
     * @param earnings explicit earnings lines (regular/OT/holiday/bonus/other).
     *                 For a SALARY employee, may be empty — the salary slice is
     *                 added automatically. For an HOURLY employee, supply the
     *                 hours-based lines.
     */
    @Transactional
    public Paystub processEmployee(Long runId, Long employeeId, List<EarningLine> earnings, String actor) {
        PayrollRun run = runRepo.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        if (run.getStatus() != PayrollRunStatus.DRAFT) {
            throw new IllegalStateException("Can only add paystubs to a DRAFT run (run is " + run.getStatus() + ")");
        }
        PayrollEmployee emp = employeeRepo.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found: " + employeeId));

        int periods = run.getPayFrequency() != null
                ? (emp.getPayPeriodsPerYearOverride() != null ? emp.getPayPeriodsPerYearOverride()
                    : run.getPayFrequency().defaultPeriodsPerYear())
                : 26;

        // Earnings: auto salary slice for salaried employees when none supplied.
        List<EarningLine> earningLines = new ArrayList<>(earnings == null ? List.of() : earnings);
        if (earningLines.isEmpty() && emp.getPayType() == PayType.SALARY && emp.getAnnualSalary() != null) {
            BigDecimal slice = emp.getAnnualSalary().divide(BigDecimal.valueOf(periods), 2, RoundingMode.HALF_UP);
            earningLines.add(EarningLine.of(EarningType.REGULAR, "Salary", slice));
        }
        // Validation: there must be something to pay. A salaried employee needs an
        // annual salary configured; an hourly employee needs earnings (hours) for the run.
        if (earningLines.isEmpty()) {
            if (emp.getPayType() == PayType.SALARY) {
                throw new IllegalArgumentException(
                        "Salaried employee " + emp.fullName() + " has no annual salary configured.");
            }
            throw new IllegalArgumentException(
                    "Hourly employee " + emp.fullName() + " requires hours/earnings for this run.");
        }
        BigDecimal gross = earningLines.stream().map(EarningLine::amountOrZero)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Build the calculation input.
        PayrollCalculationInput in = new PayrollCalculationInput();
        in.setPayFrequency(run.getPayFrequency());
        in.setPayPeriodsPerYearOverride(emp.getPayPeriodsPerYearOverride());
        in.setEarnings(earningLines);
        in.setDeductions(buildDeductions(emp, gross));
        in.setW4(buildW4(employeeId));
        int year = run.getPayDate() != null ? run.getPayDate().getYear() : LocalDate.now().getYear();
        in.setPriorYtd(computePriorYtd(emp.getAppClientId(), employeeId, year, run.getPayDate()));

        FederalWithholdingConfig fed = taxConfig.loadFederal(year);
        FicaConfig fica = taxConfig.loadFica(year);
        StateWithholdingConfig state = taxConfig.loadState(year, emp.getStateTaxCode());

        PayrollCalculationResult res = PayrollCalculator.calculate(in, fed, fica, state);

        Paystub stub = persistPaystub(run, emp, res);
        rollUpRunTotals(run, res);
        audit(emp.getAppClientId(), "Paystub", stub.getId(), "CREATE", actor,
                "Paystub for " + emp.fullName() + " — gross " + res.getGrossEarnings()
                        + ", net " + res.getNetPay());
        return stub;
    }

    @Transactional
    public PayrollRun submitForApproval(Long runId, String actor) {
        PayrollRun run = requireRun(runId);
        if (run.getStatus() != PayrollRunStatus.DRAFT) {
            throw new IllegalStateException("Only a DRAFT run can be submitted (is " + run.getStatus() + ")");
        }
        run.setStatus(PayrollRunStatus.PENDING_APPROVAL);
        run.setSubmittedBy(actor);
        run.setSubmittedAt(new Date());
        runRepo.save(run);
        audit(run.getAppClientId(), "PayrollRun", runId, "SUBMIT", actor, "Submitted for approval");
        return run;
    }

    @Transactional
    public PayrollRun approve(Long runId, String actor) {
        PayrollRun run = requireRun(runId);
        if (run.getStatus() != PayrollRunStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Only a PENDING_APPROVAL run can be approved (is " + run.getStatus() + ")");
        }
        run.setStatus(PayrollRunStatus.APPROVED);
        run.setApprovedBy(actor);
        run.setApprovedAt(new Date());
        runRepo.save(run);
        audit(run.getAppClientId(), "PayrollRun", runId, "APPROVE", actor, "Approved");
        return run;
    }

    @Transactional
    public PayrollRun markPaid(Long runId, String actor) {
        PayrollRun run = requireRun(runId);
        if (run.getStatus() != PayrollRunStatus.APPROVED) {
            throw new IllegalStateException("Only an APPROVED run can be marked paid (is " + run.getStatus() + ")");
        }
        run.setStatus(PayrollRunStatus.PAID);
        runRepo.save(run);
        audit(run.getAppClientId(), "PayrollRun", runId, "PAID", actor, "Marked paid");
        return run;
    }

    @Transactional
    public PayrollRun voidRun(Long runId, String actor, String reason) {
        PayrollRun run = requireRun(runId);
        if (run.getStatus() == PayrollRunStatus.VOIDED) return run;
        run.setStatus(PayrollRunStatus.VOIDED);
        run.setVoidedBy(actor);
        run.setVoidedAt(new Date());
        run.setVoidReason(reason);
        runRepo.save(run);
        for (Paystub s : paystubRepo.findByRunId(runId)) {
            s.setVoided(true);
            paystubRepo.save(s);
        }
        audit(run.getAppClientId(), "PayrollRun", runId, "VOID", actor, "Voided: " + reason);
        return run;
    }

    // ── Mapping / building ─────────────────────────────────────────────────

    private W4Input buildW4(Long employeeId) {
        W4Input w4 = new W4Input();
        Optional<PayrollW4> rowOpt = w4Repo.findByEmployeeIdAndActiveTrue(employeeId);
        if (rowOpt.isPresent()) {
            PayrollW4 r = rowOpt.get();
            if (r.getFilingStatus() != null) w4.setFilingStatus(r.getFilingStatus());
            w4.setStep2Checked(r.isStep2Checked());
            if (r.getStep3AnnualCredits() != null)   w4.setStep3AnnualCredits(r.getStep3AnnualCredits());
            if (r.getStep4aOtherIncome() != null)    w4.setStep4aOtherIncome(r.getStep4aOtherIncome());
            if (r.getStep4bDeductions() != null)      w4.setStep4bDeductions(r.getStep4bDeductions());
            if (r.getStep4cExtraPerPeriod() != null)  w4.setStep4cExtraPerPeriod(r.getStep4cExtraPerPeriod());
        }
        return w4;
    }

    private List<DeductionLine> buildDeductions(PayrollEmployee emp, BigDecimal gross) {
        List<EmployeeDeduction> assignments = employeeDeductionRepo.findByEmployeeIdAndActiveTrue(emp.getId());
        if (assignments.isEmpty()) return new ArrayList<>();
        Map<Long, DeductionDefinition> defs = deductionDefRepo
                .findByAppClientIdAndActiveTrue(emp.getAppClientId()).stream()
                .collect(Collectors.toMap(DeductionDefinition::getId, d -> d));
        List<DeductionLine> lines = new ArrayList<>();
        for (EmployeeDeduction a : assignments) {
            DeductionDefinition def = defs.get(a.getDefinitionId());
            if (def == null) continue;
            BigDecimal amount = a.getAmountOrRate() == null ? BigDecimal.ZERO : a.getAmountOrRate();
            if (def.isPercentageBased()) {
                amount = gross.multiply(amount).setScale(2, RoundingMode.HALF_UP);
            }
            DeductionLine line;
            if (def.getScope() == com.churchgeniuspro.payroll.model.DeductionScope.PRE_TAX) {
                line = DeductionLine.preTax(def.getName(), amount,
                        def.isReducesFederalTaxable(), def.isReducesStateTaxable(), def.isReducesFicaWages());
            } else {
                line = DeductionLine.postTax(def.getName(), amount);
            }
            lines.add(line);
        }
        return lines;
    }

    /**
     * Prior YTD = sum of this employee's non-voided paystubs earlier in the same
     * calendar year. Most categories are summed from each stub's current-period
     * columns; cumulative FICA wages come from the most recent prior stub's
     * stored YTD-FICA snapshot (FICA wages aren't a per-period column).
     */
    private YtdAmounts computePriorYtd(String appClientId, Long employeeId, int year, LocalDate beforePayDate) {
        YtdAmounts ytd = YtdAmounts.zero();
        BigDecimal latestYtdFica = BigDecimal.ZERO;
        LocalDate latest = null;
        for (Paystub s : paystubRepo.findByAppClientIdAndEmployeeIdOrderByPayDateDesc(appClientId, employeeId)) {
            if (s.isVoided() || s.getPayDate() == null) continue;
            if (s.getPayDate().getYear() != year) continue;
            if (beforePayDate != null && !s.getPayDate().isBefore(beforePayDate)) continue;
            ytd.setGrossEarnings(ytd.getGrossEarnings().add(nz(s.getGrossEarnings())));
            ytd.setPreTaxDeductions(ytd.getPreTaxDeductions().add(nz(s.getPreTaxDeductions())));
            ytd.setFederalWithholding(ytd.getFederalWithholding().add(nz(s.getFederalWithholding())));
            ytd.setSocialSecurity(ytd.getSocialSecurity().add(nz(s.getSocialSecurity())));
            ytd.setMedicare(ytd.getMedicare().add(nz(s.getMedicare())));
            ytd.setAdditionalMedicare(ytd.getAdditionalMedicare().add(nz(s.getAdditionalMedicare())));
            ytd.setStateWithholding(ytd.getStateWithholding().add(nz(s.getStateWithholding())));
            ytd.setLocalTax(ytd.getLocalTax().add(nz(s.getLocalTax())));
            ytd.setPostTaxDeductions(ytd.getPostTaxDeductions().add(nz(s.getPostTaxDeductions())));
            ytd.setNetPay(ytd.getNetPay().add(nz(s.getNetPay())));
            if (latest == null || s.getPayDate().isAfter(latest)) {
                latest = s.getPayDate();
                latestYtdFica = nz(s.getYtdFicaWages());
            }
        }
        ytd.setFicaWages(latestYtdFica);
        return ytd;
    }

    // ── Persistence helpers ────────────────────────────────────────────────

    private Paystub persistPaystub(PayrollRun run, PayrollEmployee emp, PayrollCalculationResult res) {
        Paystub stub = new Paystub();
        stub.setAppClientId(emp.getAppClientId());
        stub.setRunId(run.getId());
        stub.setEmployeeId(emp.getId());
        stub.setEmployeeName(emp.fullName());
        stub.setPayPeriodStart(run.getPayPeriodStart());
        stub.setPayPeriodEnd(run.getPayPeriodEnd());
        stub.setPayDate(run.getPayDate());
        stub.setGrossEarnings(res.getGrossEarnings());
        stub.setFederalTaxableWages(res.getFederalTaxableWages());
        stub.setFicaWages(res.getFicaWages());
        stub.setStateTaxableWages(res.getStateTaxableWages());
        stub.setPreTaxDeductions(res.getTotalPreTaxDeductions());
        stub.setFederalWithholding(res.getFederalWithholding());
        stub.setSocialSecurity(res.getSocialSecurity());
        stub.setMedicare(res.getMedicare());
        stub.setAdditionalMedicare(res.getAdditionalMedicare());
        stub.setStateWithholding(res.getStateWithholding());
        stub.setLocalTax(res.getLocalTax());
        stub.setPostTaxDeductions(res.getTotalPostTaxDeductions());
        stub.setTotalTaxes(res.getTotalTaxes());
        stub.setNetPay(res.getNetPay());
        YtdAmounts y = res.getNewYtd();
        stub.setYtdGross(y.getGrossEarnings());
        stub.setYtdPreTaxDeductions(y.getPreTaxDeductions());
        stub.setYtdTaxes(y.getFederalWithholding().add(y.getSocialSecurity()).add(y.getMedicare())
                .add(y.getAdditionalMedicare()).add(y.getStateWithholding()).add(y.getLocalTax()));
        stub.setYtdPostTaxDeductions(y.getPostTaxDeductions());
        stub.setYtdNetPay(y.getNetPay());
        stub.setYtdFicaWages(y.getFicaWages());
        stub.setDirectDepositAccountLast4(emp.getDirectDepositAccountLast4());
        stub.setDirectDepositAccountType(emp.getDirectDepositAccountType());
        stub = paystubRepo.save(stub);

        int order = 0;
        order = persistItems(stub, PaystubItem.Category.EARNING, res.getEarningItems(), order);
        order = persistItems(stub, PaystubItem.Category.PRE_TAX_DEDUCTION, res.getPreTaxItems(), order);
        order = persistItems(stub, PaystubItem.Category.TAX, res.getTaxItems(), order);
        persistItems(stub, PaystubItem.Category.POST_TAX_DEDUCTION, res.getPostTaxItems(), order);
        return stub;
    }

    private int persistItems(Paystub stub, PaystubItem.Category category,
                             List<PaystubLineItem> items, int startOrder) {
        int order = startOrder;
        for (PaystubLineItem li : items) {
            PaystubItem pi = new PaystubItem();
            pi.setAppClientId(stub.getAppClientId());
            pi.setPaystubId(stub.getId());
            pi.setCategory(category);
            pi.setLabel(li.getLabel());
            pi.setCurrentAmount(li.getCurrent());
            pi.setYtdAmount(li.getYearToDate());
            pi.setSortOrder(order++);
            paystubItemRepo.save(pi);
        }
        return order;
    }

    private void rollUpRunTotals(PayrollRun run, PayrollCalculationResult res) {
        run.setTotalGross(nz(run.getTotalGross()).add(res.getGrossEarnings()));
        run.setTotalTaxes(nz(run.getTotalTaxes()).add(res.getTotalTaxes()));
        run.setTotalDeductions(nz(run.getTotalDeductions())
                .add(res.getTotalPreTaxDeductions()).add(res.getTotalPostTaxDeductions()));
        run.setTotalNet(nz(run.getTotalNet()).add(res.getNetPay()));
        run.setEmployeeCount((run.getEmployeeCount() == null ? 0 : run.getEmployeeCount()) + 1);
        runRepo.save(run);
    }

    private void audit(String appClientId, String entityType, Long entityId,
                       String action, String actor, String details) {
        PayrollAuditLog logRow = new PayrollAuditLog();
        logRow.setAppClientId(appClientId);
        logRow.setEntityType(entityType);
        logRow.setEntityId(entityId);
        logRow.setAction(action);
        logRow.setActor(actor);
        logRow.setDetails(details);
        auditRepo.save(logRow);
    }

    private PayrollRun requireRun(Long runId) {
        return runRepo.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
