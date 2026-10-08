package com.churchgeniuspro.controller;

import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping("/home")
    public String home(HttpServletRequest request) {
        String deny = RoleGuard.requireAuth(request);
        if (deny != null) return deny;
        if (RoleGuard.isChurch(request)) return "redirect:/viewusers";
        return "forward:/home.html";
    }

    @GetMapping("/organization")
    public String organization(HttpServletRequest request) {
        String deny = RoleGuard.requireAuth(request);
        if (deny != null) return deny;
        return "forward:/organization.html";
    }

    @GetMapping("/declaration")
    public String declaration(HttpServletRequest request) {
        String deny = RoleGuard.requireAuth(request);
        if (deny != null) return deny;
        return "forward:/declaration.html";
    }

    // ── Accounting Report pages (Accountant + SuperAdmin only) ────────────

    @GetMapping("/income-report")
    public String incomeReport(HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(request, "accounting.reports");
        if (deny != null) return deny;
        return "forward:/income-report.html";
    }

    @GetMapping("/expense-report")
    public String expenseReport(HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(request, "accounting.reports");
        if (deny != null) return deny;
        return "forward:/expense-report.html";
    }

    @GetMapping("/transactions-report")
    public String transactionsReport(HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(request, "accounting.reports");
        if (deny != null) return deny;
        return "forward:/transactions-report.html";
    }

    @GetMapping("/tax-report")
    public String taxReport(HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(request, "accounting.reports");
        if (deny != null) return deny;
        return "forward:/tax-report.html";
    }

    @GetMapping("/financial-report")
    public String financialReport(HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(request, "accounting.reports");
        if (deny != null) return deny;
        return "forward:/financial-report.html";
    }

    @GetMapping("/accountingReports")
    public String accountingReports(HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(request, "accounting.reports");
        if (deny != null) return deny;
        return "forward:/accountingReports.html";
    }

    // ── Worship Planning (Admin/User/SuperAdmin + Member Portal with worship permission) ──

    @GetMapping("/worshipPlanning")
    public String worshipPlanning(HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrUser(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(request, "general.ministry.worship");
        if (deny != null) return deny;
        deny = RoleGuard.requireMemberPermission(request, "member.worship");
        if (deny != null) return deny;
        return "forward:/worshipPlanning.html";
    }

    // ── Member Worship Planning ───────────────────────────────────────────
    // Originally hard-gated to role=Member. Spec change: anyone who can reach
    // /worshipPlanning should also be able to reach the Manage page, so the
    // access rule now mirrors that route's chain exactly — Admin/User/
    // SuperAdmin/Member with the general.ministry.worship permission (legacy
    // general.worshipplanning honoured via PERMISSION_ALIASES), or a
    // Member-portal user with the member.worship permission.

    @GetMapping("/memberWorship")
    public String memberWorship(HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrUser(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(request, "general.ministry.worship");
        if (deny != null) return deny;
        deny = RoleGuard.requireMemberPermission(request, "member.worship");
        if (deny != null) return deny;
        return "forward:/memberWorship.html";
    }



    // ── Sunday School — still serves its own page (iframe target + direct access) ──

    @GetMapping("/sundaySchool")
    public String sundaySchool(HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrUser(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(request, "general.ministry.kids");
        if (deny != null) return deny;
        return "forward:/sundaySchool.html";
    }

    // ── Ministry Hub (replaces /kidsMinistryPage) ──────────────────────────────

    @GetMapping("/ministry")
    public String ministryHub(HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrUser(request);
        if (deny != null) return deny;
        // Staff: check general.ministry permission; Members: check general.ministry.kids
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        boolean isMember = session != null && "Member".equals(session.getAttribute("role"));
        deny = RoleGuard.requirePagePermission(request, isMember ? "general.ministry.kids" : "general.ministry");
        if (deny != null) return deny;
        return "forward:/ministry.html";
    }

    // ── Legacy redirect: /kidsMinistryPage → /ministry ─────────────────────────

    @GetMapping("/kidsMinistryPage")
    public String kidsMinistryPageRedirect(HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrUser(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(request, "general.ministry.kids");
        if (deny != null) return deny;
        return "redirect:/ministry";
    }

    // ── Kids Ministry (Admin/User/SuperAdmin + Member Portal with kidsministry permission) ─

    @GetMapping("/kidsMinistry")
    public String kidsMinistry(HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrUser(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(request, "general.ministry.kids");
        if (deny != null) return deny;
        deny = RoleGuard.requireMemberPagePermission(request, "member.classes");
        if (deny != null) return deny;
        return "forward:/kidsMinistry.html";
    }

    // ── Volunteer Setup (Admin, User + SuperAdmin; permission-gated) ──────

    @GetMapping("/volunteers")
    public String volunteers(HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrUser(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(request, "general.volunteers");
        if (deny != null) return deny;
        return "forward:/volunteers.html";
    }

    // ── Events Landing Page ───────────────────────────────────────────

    @GetMapping("/events")
    public String events(HttpServletRequest request) {
        String deny = RoleGuard.requireAuth(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(request, "general.events");
        if (deny != null) return deny;
        return "forward:/events.html";
    }

    // ── Event Details Page ────────────────────────────────────────────

    @GetMapping("/event-details")
    public String eventDetails(HttpServletRequest request) {
        String deny = RoleGuard.requireAuth(request);
        if (deny != null) return deny;
        return "forward:/event-details.html";
    }

    // ── Guess It public page (no auth required) ───────────────────────

    @GetMapping("/guessIt")
    public String guessIt() {
        // Public page — no session check required.
        // Org is identified via the encrypted ?cid= query parameter.
        return "forward:/guessIt.html";
    }

}
