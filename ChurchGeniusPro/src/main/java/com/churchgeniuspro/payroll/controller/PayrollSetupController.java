package com.churchgeniuspro.payroll.controller;

import com.churchgeniuspro.payroll.controller.dto.AssignDeductionRequest;
import com.churchgeniuspro.payroll.controller.dto.DeductionDefinitionRequest;
import com.churchgeniuspro.payroll.controller.dto.EmployeeRequest;
import com.churchgeniuspro.payroll.controller.dto.W4Request;
import com.churchgeniuspro.payroll.entity.*;
import com.churchgeniuspro.payroll.service.PayrollSetupService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Employee-setup REST API: employees, their Form W-4, the tenant's deduction
 * catalog, and per-employee deduction assignments — the data needed before a
 * payroll run can be calculated. Thin controller: tenant resolution + request
 * mapping + JSON; logic and audit live in {@link PayrollSetupService}.
 *
 * <p>Tenant-scoped via the session {@code clientId}; audit actor is the session
 * {@code username}. Gate behind the project's role/privilege model before
 * production.
 */
@RestController
public class PayrollSetupController {

    private final PayrollSetupService setup;

    public PayrollSetupController(PayrollSetupService setup) {
        this.setup = setup;
    }

    // ── Employees ────────────────────────────────────────────────────────────

    @GetMapping("/api/payroll/employees")
    public ResponseEntity<Map<String, Object>> listEmployees(HttpServletRequest request) {
        String cid = tenant(request); if (cid == null) return unauthorized();
        return exec(() -> setup.listEmployees(cid).stream().map(this::employeeJson).collect(Collectors.toList()));
    }

    @GetMapping("/api/payroll/employees/{id}")
    public ResponseEntity<Map<String, Object>> getEmployee(@PathVariable Long id, HttpServletRequest request) {
        String cid = tenant(request); if (cid == null) return unauthorized();
        return exec(() -> {
            PayrollEmployee e = setup.getEmployee(cid, id);
            Map<String, Object> m = employeeJson(e);
            m.put("w4", setup.currentW4(cid, id).map(this::w4Json).orElse(null));
            m.put("deductions", setup.listAssignments(cid, id).stream().map(this::assignmentJson).collect(Collectors.toList()));
            return m;
        });
    }

    @PostMapping("/api/payroll/employees")
    public ResponseEntity<Map<String, Object>> createEmployee(@RequestBody EmployeeRequest body, HttpServletRequest request) {
        String cid = tenant(request); if (cid == null) return unauthorized();
        return exec(() -> employeeJson(setup.createEmployee(cid, body, actor(request))));
    }

    @PutMapping("/api/payroll/employees/{id}")
    public ResponseEntity<Map<String, Object>> updateEmployee(@PathVariable Long id, @RequestBody EmployeeRequest body, HttpServletRequest request) {
        String cid = tenant(request); if (cid == null) return unauthorized();
        return exec(() -> employeeJson(setup.updateEmployee(cid, id, body, actor(request))));
    }

    @PostMapping("/api/payroll/employees/{id}/deactivate")
    public ResponseEntity<Map<String, Object>> deactivateEmployee(@PathVariable Long id, HttpServletRequest request) {
        String cid = tenant(request); if (cid == null) return unauthorized();
        return exec(() -> { setup.deactivateEmployee(cid, id, actor(request)); return Map.of("id", id, "active", false); });
    }

    @PostMapping("/api/payroll/employees/{id}/activate")
    public ResponseEntity<Map<String, Object>> activateEmployee(@PathVariable Long id, HttpServletRequest request) {
        String cid = tenant(request); if (cid == null) return unauthorized();
        return exec(() -> { setup.activateEmployee(cid, id, actor(request)); return Map.of("id", id, "active", true); });
    }

    @DeleteMapping("/api/payroll/employees/{id}")
    public ResponseEntity<Map<String, Object>> deleteEmployee(@PathVariable Long id, HttpServletRequest request) {
        String cid = tenant(request); if (cid == null) return unauthorized();
        return exec(() -> { setup.deleteEmployee(cid, id, actor(request)); return Map.of("id", id, "deleted", true); });
    }

    // ── W-4 ──────────────────────────────────────────────────────────────────

    @GetMapping("/api/payroll/employees/{id}/w4")
    public ResponseEntity<Map<String, Object>> getW4(@PathVariable Long id, HttpServletRequest request) {
        String cid = tenant(request); if (cid == null) return unauthorized();
        return exec(() -> setup.currentW4(cid, id).map(this::w4Json).orElse(null));
    }

    @PutMapping("/api/payroll/employees/{id}/w4")
    public ResponseEntity<Map<String, Object>> setW4(@PathVariable Long id, @RequestBody W4Request body, HttpServletRequest request) {
        String cid = tenant(request); if (cid == null) return unauthorized();
        return exec(() -> w4Json(setup.setW4(cid, id, body, actor(request))));
    }

    // ── Deduction definitions (catalog) ──────────────────────────────────────

    @GetMapping("/api/payroll/deduction-definitions")
    public ResponseEntity<Map<String, Object>> listDefinitions(HttpServletRequest request) {
        String cid = tenant(request); if (cid == null) return unauthorized();
        return exec(() -> setup.listDefinitions(cid).stream().map(this::definitionJson).collect(Collectors.toList()));
    }

    @PostMapping("/api/payroll/deduction-definitions")
    public ResponseEntity<Map<String, Object>> createDefinition(@RequestBody DeductionDefinitionRequest body, HttpServletRequest request) {
        String cid = tenant(request); if (cid == null) return unauthorized();
        return exec(() -> definitionJson(setup.createDefinition(cid, body, actor(request))));
    }

    @PutMapping("/api/payroll/deduction-definitions/{id}")
    public ResponseEntity<Map<String, Object>> updateDefinition(@PathVariable Long id, @RequestBody DeductionDefinitionRequest body, HttpServletRequest request) {
        String cid = tenant(request); if (cid == null) return unauthorized();
        return exec(() -> definitionJson(setup.updateDefinition(cid, id, body, actor(request))));
    }

    @PostMapping("/api/payroll/deduction-definitions/{id}/deactivate")
    public ResponseEntity<Map<String, Object>> deactivateDefinition(@PathVariable Long id, HttpServletRequest request) {
        String cid = tenant(request); if (cid == null) return unauthorized();
        return exec(() -> { setup.deactivateDefinition(cid, id, actor(request)); return Map.of("id", id, "active", false); });
    }

    // ── Employee deduction assignments ───────────────────────────────────────

    @GetMapping("/api/payroll/employees/{id}/deductions")
    public ResponseEntity<Map<String, Object>> listAssignments(@PathVariable Long id, HttpServletRequest request) {
        String cid = tenant(request); if (cid == null) return unauthorized();
        return exec(() -> setup.listAssignments(cid, id).stream().map(this::assignmentJson).collect(Collectors.toList()));
    }

    @PostMapping("/api/payroll/employees/{id}/deductions")
    public ResponseEntity<Map<String, Object>> assignDeduction(@PathVariable Long id, @RequestBody AssignDeductionRequest body, HttpServletRequest request) {
        String cid = tenant(request); if (cid == null) return unauthorized();
        return exec(() -> assignmentJson(setup.assignDeduction(cid, id, body, actor(request))));
    }

    @DeleteMapping("/api/payroll/employees/{id}/deductions/{assignmentId}")
    public ResponseEntity<Map<String, Object>> removeAssignment(@PathVariable Long id, @PathVariable Long assignmentId, HttpServletRequest request) {
        String cid = tenant(request); if (cid == null) return unauthorized();
        return exec(() -> { setup.removeAssignment(cid, id, assignmentId, actor(request)); return Map.of("id", assignmentId, "removed", true); });
    }

    // ── JSON builders ────────────────────────────────────────────────────────

    private Map<String, Object> employeeJson(PayrollEmployee e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("employeeNumber", e.getEmployeeNumber());
        m.put("firstName", e.getFirstName());
        m.put("lastName", e.getLastName());
        m.put("fullName", e.fullName());
        m.put("email", e.getEmail());
        m.put("phone", e.getPhone());
        m.put("ssnLast4", e.getSsnLast4());
        m.put("addressLine1", e.getAddressLine1());
        m.put("addressLine2", e.getAddressLine2());
        m.put("city", e.getCity());
        m.put("state", e.getState());
        m.put("postalCode", e.getPostalCode());
        m.put("hireDate", str(e.getHireDate()));
        m.put("terminationDate", str(e.getTerminationDate()));
        m.put("payType", e.getPayType() == null ? null : e.getPayType().name());
        m.put("hourlyRate", e.getHourlyRate());
        m.put("annualSalary", e.getAnnualSalary());
        m.put("payFrequency", e.getPayFrequency() == null ? null : e.getPayFrequency().name());
        m.put("payPeriodsPerYearOverride", e.getPayPeriodsPerYearOverride());
        m.put("stateTaxCode", e.getStateTaxCode());
        m.put("bankName", e.getBankName());
        m.put("directDepositAccountLast4", e.getDirectDepositAccountLast4());
        m.put("directDepositRoutingLast4", e.getDirectDepositRoutingLast4());
        m.put("directDepositAccountType", e.getDirectDepositAccountType());
        m.put("active", e.isActive());
        m.put("status", employeeStatus(e));   // Active | Inactive | Terminated (for display)
        return m;
    }

    /** Display status derived from the active flag + termination date. Only
     *  "Active" employees are included in new payroll runs. */
    private static String employeeStatus(PayrollEmployee e) {
        if (e.isActive()) return "Active";
        return e.getTerminationDate() != null ? "Terminated" : "Inactive";
    }

    private Map<String, Object> w4Json(PayrollW4 w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", w.getId());
        m.put("employeeId", w.getEmployeeId());
        m.put("filingStatus", w.getFilingStatus() == null ? null : w.getFilingStatus().name());
        m.put("step2Checked", w.isStep2Checked());
        m.put("step3AnnualCredits", w.getStep3AnnualCredits());
        m.put("step4aOtherIncome", w.getStep4aOtherIncome());
        m.put("step4bDeductions", w.getStep4bDeductions());
        m.put("step4cExtraPerPeriod", w.getStep4cExtraPerPeriod());
        m.put("stateAllowances", w.getStateAllowances());
        m.put("stateExtraPerPeriod", w.getStateExtraPerPeriod());
        m.put("effectiveDate", str(w.getEffectiveDate()));
        m.put("active", w.isActive());
        return m;
    }

    private Map<String, Object> definitionJson(DeductionDefinition d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("name", d.getName());
        m.put("code", d.getCode());
        m.put("scope", d.getScope() == null ? null : d.getScope().name());
        m.put("reducesFederalTaxable", d.isReducesFederalTaxable());
        m.put("reducesStateTaxable", d.isReducesStateTaxable());
        m.put("reducesFicaWages", d.isReducesFicaWages());
        m.put("percentageBased", d.isPercentageBased());
        m.put("defaultAmount", d.getDefaultAmount());
        m.put("active", d.isActive());
        return m;
    }

    private Map<String, Object> assignmentJson(EmployeeDeduction ed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ed.getId());
        m.put("employeeId", ed.getEmployeeId());
        m.put("definitionId", ed.getDefinitionId());
        m.put("amountOrRate", ed.getAmountOrRate());
        m.put("annualLimit", ed.getAnnualLimit());
        m.put("active", ed.isActive());
        return m;
    }

    // ── Session helpers + response wrapping ──────────────────────────────────

    private ResponseEntity<Map<String, Object>> exec(Supplier<Object> action) {
        try {
            return ResponseEntity.ok(ok(action.get()));
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            int code = msg.toLowerCase().contains("not found") ? 404 : 400;
            return ResponseEntity.status(code).body(err(msg));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(err(e.getMessage()));
        }
    }

    /** Effective tenant clientId if authorized for payroll; null otherwise. */
    private static String tenant(HttpServletRequest request) {
        return PayrollAuth.authorize(request).clientId;
    }

    private static String actor(HttpServletRequest request) {
        HttpSession s = request.getSession(false);
        Object u = s == null ? null : s.getAttribute("username");
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
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "error");
        m.put("message", "Not authorized for payroll.");
        return ResponseEntity.status(403).body(m);
    }

    private static Map<String, Object> err(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "error");
        m.put("message", message);
        return m;
    }
}
