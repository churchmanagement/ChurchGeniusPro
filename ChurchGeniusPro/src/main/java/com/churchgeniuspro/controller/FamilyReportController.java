package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.FamilyReportService;
import com.churchgeniuspro.service.FamilyReportService.Criteria;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;
import java.util.Set;

/**
 * Families page → <b>Report</b>: the search / filter report over families and members.
 *
 * <ul>
 *   <li>{@code GET /familySearchReport} → {@code familySearchReport.html}. Same gate as
 *       {@code /viewfamily}: SuperAdmin / Admin / Accountant with {@code admin.family};
 *       member-portal logins need the {@code admin.family} member permission.</li>
 *   <li>{@code GET /api/family-report} → the report rows (JSON). Same gate
 *       ({@link FamilyController#familyPageGuard}); the church is always the session's —
 *       there is no client-id parameter, so nothing in the URL can reach another church's
 *       data. Member-portal viewers get private fields masked server-side.</li>
 * </ul>
 * Print and download happen in the browser from these rows, so they can never show more
 * than this endpoint returned.
 */
@Controller
public class FamilyReportController {

    private static final Set<String> GENDERS = Set.of("", "Male", "Female");

    private final FamilyReportService familyReportService;

    public FamilyReportController(FamilyReportService familyReportService) {
        this.familyReportService = familyReportService;
    }

    /** Serves the report page (gated exactly like {@code /viewfamily}). */
    @GetMapping("/familySearchReport")
    public String familySearchReportPage(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        boolean isMember = session != null
                && "Member".equals(session.getAttribute("role"))
                && session.getAttribute("memberId") != null;
        if (isMember) {
            String deny = RoleGuard.requireMemberPagePermission(request, "admin.family");
            return deny != null ? deny : "forward:/familySearchReport.html";
        }
        String deny = RoleGuard.requireAdminOrAccountant(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(request, "admin.family");
        if (deny != null) return deny;
        return "forward:/familySearchReport.html";
    }

    /**
     * The report. Every parameter is optional except {@code reportType}
     * ({@code members} | {@code families}); months are 1–12.
     */
    @ResponseBody
    @GetMapping("/api/family-report")
    public ResponseEntity<Map<String, Object>> report(
            @RequestParam(defaultValue = "members") String reportType,
            @RequestParam(required = false)        Integer birthdayMonth,
            @RequestParam(required = false)        Integer anniversaryMonth,
            @RequestParam(defaultValue = "")       String city,
            @RequestParam(defaultValue = "")       String state,
            @RequestParam(defaultValue = "")       String zip,
            @RequestParam(defaultValue = "")       String gender,
            @RequestParam(defaultValue = "")       String role,
            @RequestParam(defaultValue = "")       String memberType,
            @RequestParam(defaultValue = "")       String name,
            @RequestParam(defaultValue = "false")  boolean showDeleted,
            @RequestParam(defaultValue = "false")  boolean showInactive,
            HttpServletRequest request) {
        String deny = FamilyController.familyPageGuard(request);
        if (deny != null) {
            boolean unauthenticated = deny.startsWith("redirect:");
            return ResponseEntity.status(unauthenticated ? 401 : 403)
                    .body(Map.of("error", unauthenticated ? "Not authenticated" : "Permission denied"));
        }
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));

        String type = reportType == null ? "" : reportType.trim().toLowerCase();
        if (!FamilyReportService.TYPE_MEMBERS.equals(type) && !FamilyReportService.TYPE_FAMILIES.equals(type)) {
            return ResponseEntity.badRequest().body(Map.of("error", "reportType must be 'members' or 'families'"));
        }
        if (!validMonth(birthdayMonth) || !validMonth(anniversaryMonth)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Month must be between 1 and 12"));
        }
        if (!GENDERS.contains(gender.trim())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown gender value"));
        }
        if (city.length() > 100 || state.length() > 100 || zip.length() > 20 || name.length() > 100
                || role.length() > 40 || memberType.length() > 40) {
            return ResponseEntity.badRequest().body(Map.of("error", "Filter value too long"));
        }

        // Member-portal viewers never see phone / e-mail / address a member marked private.
        boolean maskPrivate = "Member".equals(SessionUtil.getRole(request));

        Criteria c = new Criteria(type, birthdayMonth, anniversaryMonth, city, state, zip,
                                  gender.trim(), role, memberType, name, showDeleted, showInactive);
        return ResponseEntity.ok(familyReportService.run(c, appClientId, maskPrivate));
    }

    private static boolean validMonth(Integer m) {
        return m == null || m == 0 || (m >= 1 && m <= 12);
    }
}
