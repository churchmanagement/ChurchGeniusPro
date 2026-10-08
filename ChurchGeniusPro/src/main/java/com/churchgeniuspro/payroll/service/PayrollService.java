package com.churchgeniuspro.payroll.service;

import com.churchgeniuspro.payroll.config.FederalWithholdingConfig;
import com.churchgeniuspro.payroll.config.FicaConfig;
import com.churchgeniuspro.payroll.config.StateWithholdingConfig;
import com.churchgeniuspro.payroll.engine.*;
import com.churchgeniuspro.payroll.entity.*;
import com.churchgeniuspro.payroll.model.DeductionScope;
import com.churchgeniuspro.payroll.model.PayFrequency;
import com.churchgeniuspro.payroll.model.PayType;
import com.churchgeniuspro.payroll.model.PayrollRunStatus;
import com.churchgeniuspro.payroll.model.EarningType;
import com.churchgeniuspro.payroll.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
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
        // Financial audit H3: a second call for the same employee in the same run
        // (double-click, retry, or re-running process-all after a partial failure)
        // must not silently create a duplicate paystub. IllegalArgumentException (not
        // IllegalStateException) so PayrollAdminController#processAll's existing
        // skip-and-continue handling covers this the same way it covers "no hours
        // supplied" — re-running process-all on a partially-processed run just skips
        // employees who are already done, instead of logging a batch error for them.
        if (paystubRepo.existsByRunIdAndEmployeeIdAndVoidedFalse(runId, employeeId)) {
            throw new IllegalArgumentException(emp.fullName() + " already has a paystub in this run.");
        }

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
        int year = run.getPayDate() != null ? run.getPayDate().getYear() : LocalDate.now().getYear();
        PayrollCalculationInput in = new PayrollCalculationInput();
        in.setPayFrequency(run.getPayFrequency());
        in.setPayPeriodsPerYearOverride(emp.getPayPeriodsPerYearOverride());
        in.setEarnings(earningLines);
        in.setDeductions(buildDeductions(emp, gross, year, run.getPayDate()));
        in.setW4(buildW4(employeeId));
        in.setPriorYtd(computePriorYtd(emp.getAppClientId(), employeeId, year, run.getPayDate()));

        FederalWithholdingConfig fed = taxConfig.loadFederal(year);
        FicaConfig fica = taxConfig.loadFica(year);
        StateWithholdingConfig state = taxConfig.loadState(year, emp.getStateTaxCode());

        PayrollCalculationResult res = PayrollCalculator.calculate(in, fed, fica, state);

        Paystub stub;
        try {
            stub = persistPaystub(run, emp, res);
        } catch (DataIntegrityViolationException dup) {
            // Race-safe backstop for the same duplicate the pre-check above guards
            // against (see ux_paystub_run_employee in DatabaseIndexInitializer): two
            // concurrent requests for the same employee could both pass that check.
            throw new IllegalArgumentException(emp.fullName() + " already has a paystub in this run.");
        }
        recomputeRunTotals(run);
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
        for (Paystub s : paystubRepo.findByRunId(runId)) {
            s.setVoided(true);
            paystubRepo.save(s);
        }
        // Financial audit H3: every stub in the run is now voided, so this recomputes
        // (and saves) the run's totals down to zero/zero-employees instead of leaving
        // the last-accumulated gross/net figures standing on a VOIDED run.
        recomputeRunTotals(run);
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

    private List<DeductionLine> buildDeductions(PayrollEmployee emp, BigDecimal gross,
                                                int year, LocalDate beforePayDate) {
        List<EmployeeDeduction> assignments = employeeDeductionRepo.findByEmployeeIdAndActiveTrue(emp.getId());
        if (assignments.isEmpty()) return new ArrayList<>();
        Map<Long, DeductionDefinition> defs = deductionDefRepo
                .findByAppClientIdAndActiveTrue(emp.getAppClientId()).stream()
                .collect(Collectors.toMap(DeductionDefinition::getId, d -> d));

        // Financial audit M12a: lazily fetched only if some assignment actually
        // carries an annualLimit, and fetched at most once per call (not once per
        // capped deduction) — the ids of this employee's own non-voided paystubs
        // so far this year, same "same pay date still counts as prior" boundary
        // as computePriorYtd.
        List<Long> priorStubIdsThisYear = null;

        List<DeductionLine> lines = new ArrayList<>();
        for (EmployeeDeduction a : assignments) {
            DeductionDefinition def = defs.get(a.getDefinitionId());
            if (def == null) continue;
            BigDecimal amount = a.getAmountOrRate() == null ? BigDecimal.ZERO : a.getAmountOrRate();
            if (def.isPercentageBased()) {
                amount = gross.multiply(amount).setScale(2, RoundingMode.HALF_UP);
            }
            // Financial audit M12a: annualLimit (e.g. a 401(k) elective-deferral
            // cap) was stored and echoed back by the API but never enforced here,
            // so a deferral could run past the statutory limit all year. A
            // non-positive limit is treated as "no cap" (the field's own default/
            // unset shape), matching how amountOrRate is already treated the same
            // way elsewhere in this method.
            if (a.getAnnualLimit() != null && a.getAnnualLimit().signum() > 0) {
                if (priorStubIdsThisYear == null) {
                    priorStubIdsThisYear = priorStubIdsThisYear(emp.getAppClientId(), emp.getId(), year, beforePayDate);
                }
                PaystubItem.Category cat = def.getScope() == DeductionScope.PRE_TAX
                        ? PaystubItem.Category.PRE_TAX_DEDUCTION : PaystubItem.Category.POST_TAX_DEDUCTION;
                BigDecimal ytdSoFar = priorStubIdsThisYear.isEmpty() ? BigDecimal.ZERO
                        : sumDeductionItems(priorStubIdsThisYear, cat, def.getName());
                BigDecimal remaining = a.getAnnualLimit().subtract(ytdSoFar);
                amount = remaining.signum() <= 0 ? BigDecimal.ZERO : amount.min(remaining);
            }
            DeductionLine line;
            if (def.getScope() == DeductionScope.PRE_TAX) {
                line = DeductionLine.preTax(def.getName(), amount,
                        def.isReducesFederalTaxable(), def.isReducesStateTaxable(), def.isReducesFicaWages());
            } else {
                line = DeductionLine.postTax(def.getName(), amount);
            }
            lines.add(line);
        }
        return lines;
    }

    /** Ids of this employee's non-voided paystubs in {@code year}, on or before {@code beforePayDate}. */
    private List<Long> priorStubIdsThisYear(String appClientId, Long employeeId, int year, LocalDate beforePayDate) {
        List<Long> ids = new ArrayList<>();
        for (Paystub s : paystubRepo.findByAppClientIdAndEmployeeIdOrderByPayDateDesc(appClientId, employeeId)) {
            if (s.isVoided() || s.getPayDate() == null) continue;
            if (s.getPayDate().getYear() != year) continue;
            if (beforePayDate != null && s.getPayDate().isAfter(beforePayDate)) continue;
            ids.add(s.getId());
        }
        return ids;
    }

    /** Sum of currentAmount across the given paystubs' items matching one category + label. */
    private BigDecimal sumDeductionItems(List<Long> paystubIds, PaystubItem.Category category, String label) {
        BigDecimal total = BigDecimal.ZERO;
        for (PaystubItem item : paystubItemRepo.findByPaystubIdInAndCategoryAndLabel(paystubIds, category, label)) {
            total = total.add(nz(item.getCurrentAmount()));
        }
        return total;
    }

    /**
     * Prior YTD = sum of this employee's non-voided paystubs on or before
     * {@code beforePayDate}, within the same calendar year. Every category —
     * FICA wages included — is summed directly from each stub's own per-period
     * columns.
     *
     * <p>Financial audit H1/H2. Two runs sharing the same pay date (e.g. a
     * same-day off-cycle correction) both count: the boundary excludes only pay
     * dates strictly <em>after</em> the run being processed, not same-day stubs.
     * And because every category (including FICA wages) is a plain sum of each
     * stub's own current-period amount, a voided stub's contribution can never
     * leak forward the way it could when FICA wages were instead taken from the
     * most recent prior stub's cumulative YTD-FICA snapshot — that snapshot could
     * still include a stub that has since been voided.
     */
    private YtdAmounts computePriorYtd(String appClientId, Long employeeId, int year, LocalDate beforePayDate) {
        YtdAmounts ytd = YtdAmounts.zero();
        for (Paystub s : paystubRepo.findByAppClientIdAndEmployeeIdOrderByPayDateDesc(appClientId, employeeId)) {
            if (s.isVoided() || s.getPayDate() == null) continue;
            if (s.getPayDate().getYear() != year) continue;
            if (beforePayDate != null && s.getPayDate().isAfter(beforePayDate)) continue;
            ytd.setGrossEarnings(ytd.getGrossEarnings().add(nz(s.getGrossEarnings())));
            ytd.setPreTaxDeductions(ytd.getPreTaxDeductions().add(nz(s.getPreTaxDeductions())));
            ytd.setFicaWages(ytd.getFicaWages().add(nz(s.getFicaWages())));
            ytd.setFederalWithholding(ytd.getFederalWithholding().add(nz(s.getFederalWithholding())));
            ytd.setSocialSecurity(ytd.getSocialSecurity().add(nz(s.getSocialSecurity())));
            ytd.setMedicare(ytd.getMedicare().add(nz(s.getMedicare())));
            ytd.setAdditionalMedicare(ytd.getAdditionalMedicare().add(nz(s.getAdditionalMedicare())));
            ytd.setStateWithholding(ytd.getStateWithholding().add(nz(s.getStateWithholding())));
            ytd.setLocalTax(ytd.getLocalTax().add(nz(s.getLocalTax())));
            ytd.setPostTaxDeductions(ytd.getPostTaxDeductions().add(nz(s.getPostTaxDeductions())));
            ytd.setNetPay(ytd.getNetPay().add(nz(s.getNetPay())));
        }
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
        stub.setArrearsAmount(res.getArrearsAmount());
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

    /**
     * Recompute this run's denormalized totals from its own non-voided paystubs
     * (and save the run). Financial audit H3: the previous incremental
     * accumulation (each call adding one more employee's figures) double-counted
     * a paystub reprocessed after a partial failure, and never reduced the totals
     * when a run was voided — a VOIDED run kept reporting its last-accumulated
     * gross/net. Recomputing from source on every mutation is idempotent and
     * always reflects the true current state, matching how the downstream
     * reports already aggregate directly from non-voided {@link Paystub} rows.
     */
    private void recomputeRunTotals(PayrollRun run) {
        BigDecimal gross = BigDecimal.ZERO, taxes = BigDecimal.ZERO,
                deductions = BigDecimal.ZERO, net = BigDecimal.ZERO;
        int count = 0;
        for (Paystub s : paystubRepo.findByRunId(run.getId())) {
            if (s.isVoided()) continue;
            gross = gross.add(nz(s.getGrossEarnings()));
            taxes = taxes.add(nz(s.getTotalTaxes()));
            deductions = deductions.add(nz(s.getPreTaxDeductions())).add(nz(s.getPostTaxDeductions()));
            net = net.add(nz(s.getNetPay()));
            count++;
        }
        run.setTotalGross(gross);
        run.setTotalTaxes(taxes);
        run.setTotalDeductions(deductions);
        run.setTotalNet(net);
        run.setEmployeeCount(count);
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
