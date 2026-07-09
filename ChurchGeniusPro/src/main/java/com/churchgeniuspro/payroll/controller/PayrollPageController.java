package com.churchgeniuspro.payroll.controller;

import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Role-gated page routes for the payroll UI. Each route applies
 * {@link RoleGuard#requirePayroll} and then forwards to the static HTML, so an
 * unauthorized user is redirected to login or shown the access-denied page
 * instead of the payroll screen.
 *
 * <p>This mirrors the app's existing pattern (e.g. {@code /expense} →
 * {@code forward:/expense.html}). Note the underlying {@code payroll-*.html}
 * files still live in {@code static/} and remain directly reachable by URL — as
 * with every page in this app — but they render no data without the (gated)
 * {@code /api/payroll/**} calls. Navigation links point at these gated routes.
 */
@Controller
public class PayrollPageController {

    /** Payroll landing page — cards/links to the payroll sub-sections. */
    @GetMapping("/payroll")
    public String home(HttpServletRequest request) {
        String deny = RoleGuard.requirePayroll(request);
        return deny != null ? deny : "forward:/payroll-home.html";
    }

    @GetMapping("/payroll/employees")
    public String employees(HttpServletRequest request) {
        String deny = RoleGuard.requirePayroll(request);
        return deny != null ? deny : "forward:/payroll-employees.html";
    }

    @GetMapping("/payroll/runs")
    public String runs(HttpServletRequest request) {
        String deny = RoleGuard.requirePayroll(request);
        return deny != null ? deny : "forward:/payroll-run.html";
    }

    @GetMapping("/payroll/reports")
    public String reports(HttpServletRequest request) {
        String deny = RoleGuard.requirePayroll(request);
        return deny != null ? deny : "forward:/payroll-reports.html";
    }

    @GetMapping("/payroll/activity")
    public String activity(HttpServletRequest request) {
        String deny = RoleGuard.requirePayroll(request);
        return deny != null ? deny : "forward:/payroll-audit.html";
    }
}
