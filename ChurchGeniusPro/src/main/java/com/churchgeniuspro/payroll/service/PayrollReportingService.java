package com.churchgeniuspro.payroll.service;

import com.churchgeniuspro.payroll.entity.Paystub;
import com.churchgeniuspro.payroll.entity.PayrollEmployee;
import com.churchgeniuspro.payroll.repository.PaystubRepository;
import com.churchgeniuspro.payroll.repository.PayrollEmployeeRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-side payroll reporting (requirement #9): employee earnings, payroll
 * register, deduction and tax-liability summaries, and year-end W-2 box values.
 * All figures aggregate the persisted {@link Paystub} rows, so reports are
 * reproducible and consistent with what employees were actually paid.
 */
@Service
public class PayrollReportingService {

    private final PaystubRepository paystubRepo;
    private final PayrollEmployeeRepository employeeRepo;
    private final TaxConfigService taxConfig;

    public PayrollReportingService(PaystubRepository paystubRepo,
                                   PayrollEmployeeRepository employeeRepo,
                                   TaxConfigService taxConfig) {
        this.paystubRepo = paystubRepo;
        this.employeeRepo = employeeRepo;
        this.taxConfig = taxConfig;
    }

    /** Year-end W-2 box values for one employee, aggregated from non-voided paystubs. */
    public W2Box generateW2(String appClientId, Long employeeId, int year) {
        W2Box w2 = new W2Box();
        w2.setAppClientId(appClientId);
        w2.setEmployeeId(employeeId);
        w2.setTaxYear(year);
        employeeRepo.findById(employeeId).ifPresent(e -> w2.setEmployeeName(e.fullName()));

        BigDecimal box1 = BigDecimal.ZERO, box2 = BigDecimal.ZERO, box4 = BigDecimal.ZERO;
        BigDecimal medicareWages = BigDecimal.ZERO, box6 = BigDecimal.ZERO;
        BigDecimal box16 = BigDecimal.ZERO, box17 = BigDecimal.ZERO, box19 = BigDecimal.ZERO;

        for (Paystub s : yearStubs(appClientId, employeeId, year)) {
            box1 = box1.add(nz(s.getFederalTaxableWages()));
            box2 = box2.add(nz(s.getFederalWithholding()));
            box4 = box4.add(nz(s.getSocialSecurity()));
            medicareWages = medicareWages.add(nz(s.getFicaWages()));
            box6 = box6.add(nz(s.getMedicare())).add(nz(s.getAdditionalMedicare()));
            box16 = box16.add(nz(s.getStateTaxableWages()));
            box17 = box17.add(nz(s.getStateWithholding()));
            box19 = box19.add(nz(s.getLocalTax()));
        }

        // Social Security wages (Box 3) are capped at the year's wage base.
        BigDecimal ssWageBase = taxConfig.loadFica(year).getSocialSecurityWageBase();
        BigDecimal box3 = medicareWages.min(ssWageBase);

        w2.setBox1WagesTipsOtherComp(box1);
        w2.setBox2FederalIncomeTax(box2);
        w2.setBox3SocialSecurityWages(box3);
        w2.setBox4SocialSecurityTax(box4);
        w2.setBox5MedicareWages(medicareWages);
        w2.setBox6MedicareTax(box6);
        w2.setBox16StateWages(box16);
        w2.setBox17StateIncomeTax(box17);
        w2.setBox18LocalWages(box16); // local wages typically track state wages absent a separate base
        w2.setBox19LocalIncomeTax(box19);
        return w2;
    }

    /** Payroll register for a run: every paystub in the run. */
    public List<Paystub> payrollRegister(Long runId) {
        return paystubRepo.findByRunId(runId);
    }

    /** Employee earnings report: all non-voided paystubs for the employee in a year. */
    public List<Paystub> employeeEarnings(String appClientId, Long employeeId, int year) {
        return yearStubs(appClientId, employeeId, year);
    }

    /**
     * Tax-liability summary for one run: total of each withholding category
     * across all paystubs (the employer's withholding liability for the period).
     */
    public Map<String, BigDecimal> taxLiability(Long runId) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        out.put("federalIncomeTax", BigDecimal.ZERO);
        out.put("socialSecurity",   BigDecimal.ZERO);
        out.put("medicare",         BigDecimal.ZERO);
        out.put("additionalMedicare", BigDecimal.ZERO);
        out.put("stateIncomeTax",   BigDecimal.ZERO);
        out.put("localTax",         BigDecimal.ZERO);
        for (Paystub s : paystubRepo.findByRunId(runId)) {
            if (s.isVoided()) continue;
            add(out, "federalIncomeTax", s.getFederalWithholding());
            add(out, "socialSecurity",   s.getSocialSecurity());
            add(out, "medicare",         s.getMedicare());
            add(out, "additionalMedicare", s.getAdditionalMedicare());
            add(out, "stateIncomeTax",   s.getStateWithholding());
            add(out, "localTax",         s.getLocalTax());
        }
        return out;
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private List<Paystub> yearStubs(String appClientId, Long employeeId, int year) {
        List<Paystub> out = new ArrayList<>();
        for (Paystub s : paystubRepo.findByAppClientIdAndEmployeeIdOrderByPayDateDesc(appClientId, employeeId)) {
            if (s.isVoided() || s.getPayDate() == null) continue;
            if (s.getPayDate().getYear() == year) out.add(s);
        }
        return out;
    }

    private static void add(Map<String, BigDecimal> m, String k, BigDecimal v) {
        m.put(k, m.get(k).add(nz(v)));
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
