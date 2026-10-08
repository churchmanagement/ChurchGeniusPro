package com.churchgeniuspro.payroll.controller;

import com.churchgeniuspro.payroll.controller.dto.CreateRunRequest;
import com.churchgeniuspro.payroll.controller.dto.EarningLineRequest;
import com.churchgeniuspro.payroll.controller.dto.ProcessEmployeeRequest;
import com.churchgeniuspro.payroll.engine.EarningLine;
import com.churchgeniuspro.payroll.entity.Paystub;
import com.churchgeniuspro.payroll.entity.PayrollEmployee;
import com.churchgeniuspro.payroll.entity.PayrollRun;
import com.churchgeniuspro.payroll.model.EarningType;
import com.churchgeniuspro.payroll.model.PayFrequency;
import com.churchgeniuspro.payroll.repository.PayrollEmployeeRepository;
import com.churchgeniuspro.payroll.repository.PayrollRunRepository;
import com.churchgeniuspro.payroll.service.PayrollService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * "Run payroll" lifecycle endpoints: create a run, process employees into it,
 * then submit → approve → mark paid, or void. Everything is scoped to the
 * logged-in tenant (session {@code clientId}); the audit actor is the session
 * {@code username}. The heavy lifting (calculation, persistence, audit) lives in
 * {@link PayrollService}; this controller only handles HTTP, tenant guards, and
 * request mapping.
 *
 * <p>As with the other payroll controllers, reuse-the-session auth is a
 * placeholder — gate these behind the project's role/privilege model before
 * production, since creating/approving payroll is a privileged action.
 */
@RestController
public class PayrollAdminController {

    private static final Logger log = LoggerFactory.getLogger(PayrollAdminController.class);

    private final PayrollService payrollService;
    private final PayrollRunRepository runRepo;
    private final PayrollEmployeeRepository employeeRepo;

    public PayrollAdminController(PayrollService payrollService,
                                  PayrollRunRepository runRepo,
                                  PayrollEmployeeRepository employeeRepo) {
        this.payrollService = payrollService;
        this.runRepo = runRepo;
        this.employeeRepo = employeeRepo;
    }

    // ── Create a run ─────────────────────────────────────────────────────────

    @PostMapping("/api/payroll/runs/create")
    public ResponseEntity<Map<String, Object>> createRun(@RequestBody CreateRunRequest body,
                                                         HttpServletRequest request) {
        String clientId = tenant(request);
        if (clientId == null) return unauthorized();
        PayFrequency freq;
        try {
            freq = PayFrequency.valueOf(String.valueOf(body.getPayFrequency()).trim().toUpperCase());
        } catch (Exception e) {
            return bad("Invalid or missing payFrequency. Use one of: WEEKLY, BIWEEKLY, SEMIMONTHLY, MONTHLY, DAILY, HOURLY.");
        }
        if (body.getPayDate() == null) return bad("payDate is required.");
        try {
            PayrollRun run = payrollService.createRun(clientId, freq,
                    body.getPayPeriodStart(), body.getPayPeriodEnd(), body.getPayDate(), actor(request));
            return ResponseEntity.ok(ok(runJson(run)));
        } catch (Exception e) {
            log.error("createRun failed", e);
            return serverError(e);
        }
    }

    // ── Process one employee into a DRAFT run ───────────────────────────────

    @PostMapping("/api/payroll/runs/{runId}/employees/{employeeId}/process")
    public ResponseEntity<Map<String, Object>> processEmployee(@PathVariable Long runId,
                                                              @PathVariable Long employeeId,
                                                              @RequestBody(required = false) ProcessEmployeeRequest body,
                                                              HttpServletRequest request) {
        String clientId = tenant(request);
        if (clientId == null) return unauthorized();
        ResponseEntity<Map<String, Object>> guard = guardRunAndEmployee(runId, employeeId, clientId);
        if (guard != null) return guard;

        List<EarningLine> earnings = mapEarnings(body == null ? null : body.getEarnings());
        try {
            Paystub stub = payrollService.processEmployee(runId, employeeId, earnings, actor(request));
            return ResponseEntity.ok(ok(paystubJson(stub)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return bad(e.getMessage());
        } catch (Exception e) {
            log.error("processEmployee failed", e);
            return serverError(e);
        }
    }

    // ── Process all active employees into a DRAFT run (salaried-friendly) ────

    @PostMapping("/api/payroll/runs/{runId}/process-all")
    public ResponseEntity<Map<String, Object>> processAll(@PathVariable Long runId,
                                                         HttpServletRequest request) {
        String clientId = tenant(request);
        if (clientId == null) return unauthorized();
        ResponseEntity<Map<String, Object>> guard = guardRun(runId, clientId);
        if (guard != null) return guard;

        // Only meaningful on a DRAFT run; surface a clear error otherwise.
        PayrollRun run = runRepo.findById(runId).orElse(null);
        if (run == null) return notFound("Run not found.");
        if (run.getStatus() != com.churchgeniuspro.payroll.model.PayrollRunStatus.DRAFT) {
            return bad("Can only process employees into a DRAFT run (run is " + run.getStatus() + ").");
        }

        List<PayrollEmployee> employees = employeeRepo.findByAppClientIdAndActiveTrue(clientId);
        List<Map<String, Object>> stubs = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (PayrollEmployee emp : employees) {
            try {
                Paystub stub = payrollService.processEmployee(runId, emp.getId(),
                        new ArrayList<>(), actor(request));
                stubs.add(paystubJson(stub));
            } catch (IllegalArgumentException e) {
                // Employee-specific (e.g. hourly with no hours, salaried with no salary):
                // skip and report rather than failing the whole batch.
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("employeeId", emp.getId());
                s.put("employeeName", emp.fullName());
                s.put("reason", e.getMessage());
                skipped.add(s);
            } catch (Exception e) {
                log.error("processAll: error processing employee {}", emp.getId(), e);
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("employeeId", emp.getId());
                s.put("employeeName", emp.fullName());
                s.put("reason", "Unexpected error");
                skipped.add(s);
            }
        }
        Map<String, Object> data = ok(stubs);
        data.put("processed", stubs.size());
        data.put("skipped", skipped);
        runRepo.findById(runId).ifPresent(r -> data.put("run", runJson(r)));
        return ResponseEntity.ok(data);
    }

    // ── Workflow transitions ─────────────────────────────────────────────────

    @PostMapping("/api/payroll/runs/{runId}/submit")
    public ResponseEntity<Map<String, Object>> submit(@PathVariable Long runId, HttpServletRequest request) {
        return transition(runId, request, "submit");
    }

    @PostMapping("/api/payroll/runs/{runId}/approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable Long runId, HttpServletRequest request) {
        return transition(runId, request, "approve");
    }

    @PostMapping("/api/payroll/runs/{runId}/paid")
    public ResponseEntity<Map<String, Object>> markPaid(@PathVariable Long runId, HttpServletRequest request) {
        return transition(runId, request, "paid");
    }

    @PostMapping("/api/payroll/runs/{runId}/void")
    public ResponseEntity<Map<String, Object>> voidRun(@PathVariable Long runId,
                                                      @RequestBody(required = false) Map<String, Object> body,
                                                      HttpServletRequest request) {
        String clientId = tenant(request);
        if (clientId == null) return unauthorized();
        ResponseEntity<Map<String, Object>> guard = guardRun(runId, clientId);
        if (guard != null) return guard;
        String reason = body == null ? null : String.valueOf(body.getOrDefault("reason", ""));
        try {
            PayrollRun run = payrollService.voidRun(runId, actor(request), reason);
            return ResponseEntity.ok(ok(runJson(run)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return bad(e.getMessage());
        } catch (Exception e) {
            log.error("voidRun failed", e);
            return serverError(e);
        }
    }

    private ResponseEntity<Map<String, Object>> transition(Long runId, HttpServletRequest request, String which) {
        String clientId = tenant(request);
        if (clientId == null) return unauthorized();
        ResponseEntity<Map<String, Object>> guard = guardRun(runId, clientId);
        if (guard != null) return guard;
        try {
            PayrollRun run;
            switch (which) {
                case "submit":  run = payrollService.submitForApproval(runId, actor(request)); break;
                case "approve": run = payrollService.approve(runId, actor(request)); break;
                case "paid":    run = payrollService.markPaid(runId, actor(request)); break;
                default: return bad("Unknown transition.");
            }
            return ResponseEntity.ok(ok(runJson(run)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return bad(e.getMessage());
        } catch (Exception e) {
            log.error("transition {} failed", which, e);
            return serverError(e);
        }
    }

    // ── Mapping / guards ─────────────────────────────────────────────────────

    private List<EarningLine> mapEarnings(List<EarningLineRequest> reqs) {
        List<EarningLine> out = new ArrayList<>();
        if (reqs == null) return out;
        for (EarningLineRequest r : reqs) {
            EarningType type = parseType(r.getType());
            if (r.getHours() != null && r.getRate() != null) {
                out.add(EarningLine.hourly(type, r.getDescription(), r.getHours(), r.getRate(), r.getMultiplier()));
            } else {
                out.add(EarningLine.of(type, r.getDescription(), r.getAmount() == null ? BigDecimal.ZERO : r.getAmount()));
            }
        }
        return out;
    }

    private static EarningType parseType(String s) {
        if (s == null) return EarningType.OTHER;
        try { return EarningType.valueOf(s.trim().toUpperCase()); }
        catch (Exception e) { return EarningType.OTHER; }
    }

    /** Verify the run exists and belongs to the tenant. Returns an error response, or null if OK. */
    private ResponseEntity<Map<String, Object>> guardRun(Long runId, String clientId) {
        Optional<PayrollRun> run = runRepo.findById(runId);
        if (run.isEmpty()) return notFound("Run not found.");
        if (!clientId.equals(run.get().getAppClientId())) return forbidden("Not your payroll run.");
        return null;
    }

    private ResponseEntity<Map<String, Object>> guardRunAndEmployee(Long runId, Long employeeId, String clientId) {
        ResponseEntity<Map<String, Object>> r = guardRun(runId, clientId);
        if (r != null) return r;
        Optional<PayrollEmployee> emp = employeeRepo.findById(employeeId);
        if (emp.isEmpty()) return notFound("Employee not found.");
        if (!clientId.equals(emp.get().getAppClientId())) return forbidden("Not your employee.");
        return null;
    }

    // ── JSON builders ────────────────────────────────────────────────────────

    private Map<String, Object> runJson(PayrollRun r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("status", r.getStatus() == null ? null : r.getStatus().name());
        m.put("payFrequency", r.getPayFrequency() == null ? null : r.getPayFrequency().name());
        m.put("payPeriodStart", str(r.getPayPeriodStart()));
        m.put("payPeriodEnd", str(r.getPayPeriodEnd()));
        m.put("payDate", str(r.getPayDate()));
        m.put("employeeCount", r.getEmployeeCount());
        m.put("totalGross", r.getTotalGross());
        m.put("totalTaxes", r.getTotalTaxes());
        m.put("totalNet", r.getTotalNet());
        return m;
    }

    private Map<String, Object> paystubJson(Paystub s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("runId", s.getRunId());
        m.put("employeeId", s.getEmployeeId());
        m.put("employeeName", s.getEmployeeName());
        m.put("grossEarnings", s.getGrossEarnings());
        m.put("totalTaxes", s.getTotalTaxes());
        m.put("netPay", s.getNetPay());
        m.put("arrearsAmount", s.getArrearsAmount());
        m.put("pdfUrl", "/api/payroll/paystubs/" + s.getId() + "/pdf");
        return m;
    }

    // ── Session helpers + responses ──────────────────────────────────────────

    /** Effective tenant clientId if authorized for payroll; null otherwise. */
    private static String tenant(HttpServletRequest request) {
        return PayrollAuth.authorize(request).clientId;
    }

    private static String actor(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object u = session == null ? null : session.getAttribute("username");
        return u == null ? "system" : u.toString();
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "success");
        m.put("data", data);
        return m;
    }

    private static ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(403).body(err("Not authorized for payroll."));
    }
    private static ResponseEntity<Map<String, Object>> forbidden(String msg) {
        return ResponseEntity.status(403).body(err(msg));
    }
    private static ResponseEntity<Map<String, Object>> notFound(String msg) {
        return ResponseEntity.status(404).body(err(msg));
    }
    private static ResponseEntity<Map<String, Object>> bad(String msg) {
        return ResponseEntity.status(400).body(err(msg));
    }
    private static ResponseEntity<Map<String, Object>> serverError(Exception e) {
        return ResponseEntity.status(500).body(err(e.getMessage()));
    }
    private static Map<String, Object> err(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "error");
        m.put("message", message);
        return m;
    }
}
