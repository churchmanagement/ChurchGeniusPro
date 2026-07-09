package com.churchgeniuspro.payroll.controller;

import com.churchgeniuspro.payroll.entity.Paystub;
import com.churchgeniuspro.payroll.entity.PayrollEmployee;
import com.churchgeniuspro.payroll.entity.PayrollRun;
import com.churchgeniuspro.payroll.pdf.EmployerInfo;
import com.churchgeniuspro.payroll.pdf.W2PdfService;
import com.churchgeniuspro.payroll.repository.PayrollEmployeeRepository;
import com.churchgeniuspro.payroll.repository.PayrollRunRepository;
import com.churchgeniuspro.payroll.service.PayrollReportingService;
import com.churchgeniuspro.payroll.service.W2Box;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Year-end and per-run exports/reports: bulk W-2 CSV, per-employee W-2 PDF, a
 * run's payroll register, and a run's tax-liability summary. All tenant-scoped.
 */
@RestController
public class PayrollExportController {

    private final PayrollReportingService reporting;
    private final PayrollEmployeeRepository employeeRepo;
    private final PayrollRunRepository runRepo;
    private final W2PdfService w2Pdf;

    public PayrollExportController(PayrollReportingService reporting,
                                   PayrollEmployeeRepository employeeRepo,
                                   PayrollRunRepository runRepo,
                                   W2PdfService w2Pdf) {
        this.reporting = reporting;
        this.employeeRepo = employeeRepo;
        this.runRepo = runRepo;
        this.w2Pdf = w2Pdf;
    }

    // ── Bulk W-2 CSV (all employees with wages this year) ────────────────────

    @GetMapping("/api/payroll/reports/w2/csv")
    public ResponseEntity<byte[]> w2Csv(@RequestParam(value = "year", required = false) Integer year,
                                        HttpServletRequest request) {
        String clientId = tenant(request);
        if (clientId == null) return ResponseEntity.status(403).build();
        int yr = year != null ? year : LocalDate.now().getYear();

        StringBuilder sb = new StringBuilder();
        sb.append("EmployeeId,Name,Year,Box1_Wages,Box2_FedTax,Box3_SSWages,Box4_SSTax,")
          .append("Box5_MedicareWages,Box6_MedicareTax,Box16_StateWages,Box17_StateTax,Box18_LocalWages,Box19_LocalTax\r\n");
        for (PayrollEmployee e : employeeRepo.findByAppClientId(clientId)) {
            W2Box w = reporting.generateW2(clientId, e.getId(), yr);
            // Only employees who actually had wages this year.
            if (sig(w.getBox1WagesTipsOtherComp()) == 0 && sig(w.getBox5MedicareWages()) == 0) continue;
            sb.append(e.getId()).append(',')
              .append(csv(e.fullName())).append(',')
              .append(yr).append(',')
              .append(n(w.getBox1WagesTipsOtherComp())).append(',')
              .append(n(w.getBox2FederalIncomeTax())).append(',')
              .append(n(w.getBox3SocialSecurityWages())).append(',')
              .append(n(w.getBox4SocialSecurityTax())).append(',')
              .append(n(w.getBox5MedicareWages())).append(',')
              .append(n(w.getBox6MedicareTax())).append(',')
              .append(n(w.getBox16StateWages())).append(',')
              .append(n(w.getBox17StateIncomeTax())).append(',')
              .append(n(w.getBox18LocalWages())).append(',')
              .append(n(w.getBox19LocalIncomeTax())).append("\r\n");
        }
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType("text/csv"));
        h.setContentDisposition(ContentDisposition.attachment().filename("w2-" + yr + ".csv").build());
        return ResponseEntity.ok().headers(h).body(body);
    }

    // ── Per-employee W-2 summary PDF ─────────────────────────────────────────

    @GetMapping("/api/payroll/employees/{id}/w2/pdf")
    public ResponseEntity<byte[]> w2Pdf(@PathVariable Long id,
                                        @RequestParam(value = "year", required = false) Integer year,
                                        @RequestParam(value = "download", defaultValue = "false") boolean download,
                                        HttpServletRequest request) {
        String clientId = tenant(request);
        if (clientId == null) return ResponseEntity.status(403).build();
        Optional<PayrollEmployee> empOpt = employeeRepo.findById(id);
        if (empOpt.isEmpty() || !clientId.equals(empOpt.get().getAppClientId())) {
            return ResponseEntity.status(404).build();
        }
        int yr = year != null ? year : LocalDate.now().getYear();
        W2Box w2 = reporting.generateW2(clientId, id, yr);
        EmployerInfo employer = EmployerInfo.ofName(churchName(request));
        byte[] pdf = w2Pdf.generate(w2, employer, empOpt.get());

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_PDF);
        h.setContentDisposition((download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename("w2-" + id + "-" + yr + ".pdf").build());
        return ResponseEntity.ok().headers(h).body(pdf);
    }

    // ── Run payroll register ─────────────────────────────────────────────────

    @GetMapping("/api/payroll/runs/{runId}/register")
    public ResponseEntity<Map<String, Object>> register(@PathVariable Long runId, HttpServletRequest request) {
        String clientId = tenant(request);
        if (clientId == null) return ResponseEntity.status(403).body(err("Not authorized for payroll."));
        PayrollRun run = ownedRun(runId, clientId);
        if (run == null) return ResponseEntity.status(404).body(err("Run not found."));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Paystub s : reporting.payrollRegister(runId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("employeeId", s.getEmployeeId());
            m.put("employeeName", s.getEmployeeName());
            m.put("grossEarnings", s.getGrossEarnings());
            m.put("federalWithholding", s.getFederalWithholding());
            m.put("socialSecurity", s.getSocialSecurity());
            m.put("medicare", s.getMedicare());
            m.put("stateWithholding", s.getStateWithholding());
            m.put("preTaxDeductions", s.getPreTaxDeductions());
            m.put("postTaxDeductions", s.getPostTaxDeductions());
            m.put("netPay", s.getNetPay());
            m.put("voided", s.isVoided());
            rows.add(m);
        }
        return ResponseEntity.ok(ok(rows));
    }

    // ── Run tax-liability summary ────────────────────────────────────────────

    @GetMapping("/api/payroll/runs/{runId}/tax-liability")
    public ResponseEntity<Map<String, Object>> taxLiability(@PathVariable Long runId, HttpServletRequest request) {
        String clientId = tenant(request);
        if (clientId == null) return ResponseEntity.status(403).body(err("Not authorized for payroll."));
        PayrollRun run = ownedRun(runId, clientId);
        if (run == null) return ResponseEntity.status(404).body(err("Run not found."));
        return ResponseEntity.ok(ok(reporting.taxLiability(runId)));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private PayrollRun ownedRun(Long runId, String clientId) {
        return runRepo.findById(runId).filter(r -> clientId.equals(r.getAppClientId())).orElse(null);
    }

    private static int sig(BigDecimal v) { return v == null ? 0 : v.signum(); }
    private static String n(BigDecimal v) { return (v == null ? BigDecimal.ZERO : v).toPlainString(); }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /** Effective tenant clientId if authorized for payroll; null otherwise. */
    private static String tenant(HttpServletRequest request) {
        return PayrollAuth.authorize(request).clientId;
    }

    private static String churchName(HttpServletRequest request) {
        HttpSession s = request.getSession(false);
        Object n = s == null ? null : s.getAttribute("churchName");
        return n == null ? "Employer" : n.toString();
    }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "success");
        m.put("data", data);
        return m;
    }
    private static Map<String, Object> err(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "error");
        m.put("message", message);
        return m;
    }
}
