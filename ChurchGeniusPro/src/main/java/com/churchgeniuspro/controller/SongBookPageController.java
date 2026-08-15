package com.churchgeniuspro.controller;

import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Page routes for the Song Book feature.
 *
 * <ul>
 *   <li>{@code GET /songbook}              → member/staff song management page.</li>
 *   <li>{@code GET /admin/songbook-access} → Admin/Super Admin access management.</li>
 *   <li>{@code GET /songbook/view}         → public published book viewer (no login).</li>
 * </ul>
 */
@Controller
public class SongBookPageController {

    /** Member portal + staff song management. Access level enforced by the API. */
    @GetMapping("/songbook")
    public String songbook(HttpServletRequest request) {
        String deny = RoleGuard.requireStaffOrMember(request);
        if (deny != null) return deny;
        // Case must match songbook.html exactly — jar classpath lookup is
        // case-sensitive (works from Windows filesystem, 404s from the JAR).
        return "forward:/songbook.html";
    }

    /** Access management — Admin / Super Admin only. */
    @GetMapping("/admin/songbook-access")
    public String access(HttpServletRequest request) {
        String deny = RoleGuard.requireAdmin(request);
        if (deny != null) return deny;
        return "forward:/songBookAccess.html";
    }

    /** Public published book — reachable without login (token in ?t=). */
    @GetMapping("/songbook/view")
    public String publicView() {
        return "forward:/songBookPublic.html";
    }
}
