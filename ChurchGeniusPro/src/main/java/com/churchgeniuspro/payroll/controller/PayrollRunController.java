package com.churchgeniuspro.payroll.controller;

import com.churchgeniuspro.payroll.entity.Paystub;
import com.churchgeniuspro.payroll.entity.PayrollRun;
import com.churchgeniuspro.payroll.repository.PaystubRepository;
import com.churchgeniuspro.payroll.repository.PayrollRunRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only JSON endpoints backing the payroll run view: list the tenant's runs
 * and list the paystubs within a run. Both are scoped to the logged-in tenant
 * (session {@code clientId}) so one church can't see another's payroll.
 *
 * <p>Like {@code PayrollPaystubController}, this reuses the app's existing
 * session attributes; tighten with the project's role/privilege model before
 * production (payroll data is sensitive).
 */
@RestController
public class PayrollRunController {

    private final PayrollRunRepository runRepo;
    private final PaystubRepository paystubRepo;

    public PayrollRunController(PayrollRunRepository runRepo, PaystubRepository paystubRepo) {
        this.runRepo = runRepo;
        this.paystubRepo = paystubRepo;
    }

    /** All payroll runs for the logged-in tenant, newest pay date first. */
    @GetMapping("/api/payroll/runs")
    public ResponseEntity<Map<String, Object>> listRuns(HttpServletRequest request) {
        String clientId = tenantClientId(request);
        if (clientId == null) return ResponseEntity.status(403).body(error("Not authorized for payroll."));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (PayrollRun r : runRepo.findByAppClientIdOrderByPayDateDesc(clientId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("payFrequency", r.getPayFrequency() == null ? null : r.getPayFrequency().name());
            m.put("payPeriodStart", str(r.getPayPeriodStart()));
            m.put("payPeriodEnd", str(r.getPayPeriodEnd()));
            m.put("payDate", str(r.getPayDate()));
            m.put("status", r.getStatus() == null ? null : r.getStatus().name());
            m.put("employeeCount", r.getEmployeeCount());
            m.put("totalGross", r.getTotalGross());
            m.put("totalNet", r.getTotalNet());
            rows.add(m);
        }
        return ResponseEntity.ok(ok(rows));
    }

    /** Paystubs within a run (tenant-scoped). */
    @GetMapping("/api/payroll/runs/{runId}/paystubs")
    public ResponseEntity<Map<String, Object>> listPaystubs(@PathVariable Long runId,
                                                            HttpServletRequest request) {
        String clientId = tenantClientId(request);
        if (clientId == null) return ResponseEntity.status(403).body(error("Not authorized for payroll."));

        Optional<PayrollRun> runOpt = runRepo.findById(runId);
        if (runOpt.isEmpty()) return ResponseEntity.status(404).body(error("Run not found."));
        PayrollRun run = runOpt.get();
        if (!clientId.equals(run.getAppClientId())) {
            return ResponseEntity.status(403).body(error("Not your payroll run."));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Paystub s : paystubRepo.findByRunId(runId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("employeeId", s.getEmployeeId());
            m.put("employeeName", s.getEmployeeName());
            m.put("payDate", str(s.getPayDate()));
            m.put("grossEarnings", s.getGrossEarnings());
            m.put("totalTaxes", s.getTotalTaxes());
            m.put("netPay", s.getNetPay());
            m.put("voided", s.isVoided());
            rows.add(m);
        }

        Map<String, Object> body = ok(rows);
        Map<String, Object> runInfo = new LinkedHashMap<>();
        runInfo.put("id", run.getId());
        runInfo.put("status", run.getStatus() == null ? null : run.getStatus().name());
        runInfo.put("payFrequency", run.getPayFrequency() == null ? null : run.getPayFrequency().name());
        runInfo.put("payPeriodStart", str(run.getPayPeriodStart()));
        runInfo.put("payPeriodEnd", str(run.getPayPeriodEnd()));
        runInfo.put("payDate", str(run.getPayDate()));
        runInfo.put("totalGross", run.getTotalGross());
        runInfo.put("totalNet", run.getTotalNet());
        body.put("run", runInfo);
        return ResponseEntity.ok(body);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** Effective tenant clientId if the caller is authorized for payroll; null otherwise. */
    private static String tenantClientId(HttpServletRequest request) {
        return PayrollAuth.authorize(request).clientId;
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "success");
        m.put("data", data);
        return m;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "error");
        m.put("message", message);
        return m;
    }
}
