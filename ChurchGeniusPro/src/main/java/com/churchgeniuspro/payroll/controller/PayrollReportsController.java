package com.churchgeniuspro.payroll.controller;

import com.churchgeniuspro.payroll.entity.Paystub;
import com.churchgeniuspro.payroll.entity.PayrollAuditLog;
import com.churchgeniuspro.payroll.entity.PayrollEmployee;
import com.churchgeniuspro.payroll.repository.PayrollAuditLogRepository;
import com.churchgeniuspro.payroll.repository.PayrollEmployeeRepository;
import com.churchgeniuspro.payroll.repository.PaystubRepository;
import com.churchgeniuspro.payroll.service.PayrollReportingService;
import com.churchgeniuspro.payroll.service.W2Box;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only reporting + audit endpoints: the tenant's payroll activity log, an
 * employee's paystub history, and an employee's year-to-date totals with W-2 box
 * values. All tenant-scoped via the session {@code clientId}.
 */
@RestController
public class PayrollReportsController {

    private final PayrollAuditLogRepository auditRepo;
    private final PaystubRepository paystubRepo;
    private final PayrollEmployeeRepository employeeRepo;
    private final PayrollReportingService reporting;

    public PayrollReportsController(PayrollAuditLogRepository auditRepo,
                                    PaystubRepository paystubRepo,
                                    PayrollEmployeeRepository employeeRepo,
                                    PayrollReportingService reporting) {
        this.auditRepo = auditRepo;
        this.paystubRepo = paystubRepo;
        this.employeeRepo = employeeRepo;
        this.reporting = reporting;
    }

    // ── Audit log ────────────────────────────────────────────────────────────

    @GetMapping("/api/payroll/audit")
    public ResponseEntity<Map<String, Object>> audit(
            @RequestParam(value = "entityType", required = false) String entityType,
            @RequestParam(value = "entityId", required = false) Long entityId,
            @RequestParam(value = "limit", defaultValue = "200") int limit,
            HttpServletRequest request) {
        String clientId = tenant(request);
        if (clientId == null) return unauthorized();

        List<PayrollAuditLog> rows = (entityType != null && entityId != null)
                ? auditRepo.findByAppClientIdAndEntityTypeAndEntityIdOrderByCreatedDesc(clientId, entityType, entityId)
                : auditRepo.findByAppClientIdOrderByCreatedDesc(clientId);

        int cap = Math.max(1, Math.min(limit, 1000));
        List<Map<String, Object>> out = new ArrayList<>();
        for (PayrollAuditLog a : rows) {
            if (out.size() >= cap) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("created", str(a.getCreated()));
            m.put("actor", a.getActor());
            m.put("action", a.getAction());
            m.put("entityType", a.getEntityType());
            m.put("entityId", a.getEntityId());
            m.put("details", a.getDetails());
            out.add(m);
        }
        return ResponseEntity.ok(ok(out));
    }

    // ── Employee paystub history ─────────────────────────────────────────────

    @GetMapping("/api/payroll/employees/{id}/paystubs")
    public ResponseEntity<Map<String, Object>> employeePaystubs(@PathVariable Long id, HttpServletRequest request) {
        String clientId = tenant(request);
        if (clientId == null) return unauthorized();
        if (notTenantEmployee(clientId, id)) return notFound();

        List<Map<String, Object>> out = new ArrayList<>();
        for (Paystub s : paystubRepo.findByAppClientIdAndEmployeeIdOrderByPayDateDesc(clientId, id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("runId", s.getRunId());
            m.put("payDate", str(s.getPayDate()));
            m.put("payPeriodStart", str(s.getPayPeriodStart()));
            m.put("payPeriodEnd", str(s.getPayPeriodEnd()));
            m.put("grossEarnings", s.getGrossEarnings());
            m.put("totalTaxes", s.getTotalTaxes());
            m.put("netPay", s.getNetPay());
            m.put("arrearsAmount", s.getArrearsAmount());
            m.put("voided", s.isVoided());
            m.put("pdfUrl", "/api/payroll/paystubs/" + s.getId() + "/pdf");
            out.add(m);
        }
        return ResponseEntity.ok(ok(out));
    }

    // ── Employee year-to-date + W-2 boxes ────────────────────────────────────

    @GetMapping("/api/payroll/employees/{id}/ytd")
    public ResponseEntity<Map<String, Object>> employeeYtd(@PathVariable Long id,
                                                          @RequestParam(value = "year", required = false) Integer year,
                                                          HttpServletRequest request) {
        String clientId = tenant(request);
        if (clientId == null) return unauthorized();
        if (notTenantEmployee(clientId, id)) return notFound();

        int yr = year != null ? year : LocalDate.now().getYear();

        // Friendly YTD totals from the year's non-voided paystubs.
        BigDecimal gross = BigDecimal.ZERO, taxes = BigDecimal.ZERO, net = BigDecimal.ZERO;
        int count = 0;
        for (Paystub s : reporting.employeeEarnings(clientId, id, yr)) {
            gross = gross.add(nz(s.getGrossEarnings()));
            taxes = taxes.add(nz(s.getTotalTaxes()));
            net = net.add(nz(s.getNetPay()));
            count++;
        }

        W2Box w2 = reporting.generateW2(clientId, id, yr);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("year", yr);
        data.put("paystubCount", count);
        data.put("ytdGross", gross);
        data.put("ytdTaxes", taxes);
        data.put("ytdNet", net);
        Map<String, Object> w2m = new LinkedHashMap<>();
        w2m.put("box1_wages", w2.getBox1WagesTipsOtherComp());
        w2m.put("box2_federalIncomeTax", w2.getBox2FederalIncomeTax());
        w2m.put("box3_socialSecurityWages", w2.getBox3SocialSecurityWages());
        w2m.put("box4_socialSecurityTax", w2.getBox4SocialSecurityTax());
        w2m.put("box5_medicareWages", w2.getBox5MedicareWages());
        w2m.put("box6_medicareTax", w2.getBox6MedicareTax());
        w2m.put("box16_stateWages", w2.getBox16StateWages());
        w2m.put("box17_stateIncomeTax", w2.getBox17StateIncomeTax());
        w2m.put("box18_localWages", w2.getBox18LocalWages());
        w2m.put("box19_localIncomeTax", w2.getBox19LocalIncomeTax());
        data.put("w2", w2m);
        return ResponseEntity.ok(ok(data));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private boolean notTenantEmployee(String clientId, Long id) {
        Optional<PayrollEmployee> e = employeeRepo.findById(id);
        return e.isEmpty() || !clientId.equals(e.get().getAppClientId());
    }

    /** Effective tenant clientId if authorized for payroll; null otherwise. */
    private static String tenant(HttpServletRequest request) {
        return PayrollAuth.authorize(request).clientId;
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "success");
        m.put("data", data);
        return m;
    }
    private static ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(403).body(Map.of("status", "error", "message", "Not authorized for payroll."));
    }
    private static ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Employee not found."));
    }
}
