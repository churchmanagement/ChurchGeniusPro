package com.churchgeniuspro.controller;

import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Page routes for the Temporary Access feature.
 *
 * <ul>
 *   <li>{@code /temporaryAccess} — admin management screen (SuperAdmin / Admin only).</li>
 *   <li>{@code /private-access}  — the temporary-access login screen (private entry point).</li>
 *   <li>{@code /tempLogin}       — legacy alias; redirects to {@code /private-access}.</li>
 * </ul>
 */
@Controller
public class TempAccessPageController {

    @GetMapping("/temporaryAccess")
    public String adminPage(HttpServletRequest request) {
        // Owner-only: ONLY church-type logins may access. All other roles denied.
        String deny = RoleGuard.requireChurch(request);
        if (deny != null) return deny;
        return "forward:/temporaryAccess.html";
    }

    /**
     * The temporary-access login screen — the single, private entry point.
     * No session required; reached via the encrypted church link ({@code ?c=<token>}).
     * Network restriction (when the Private Page Access master setting is on) is
     * enforced upstream by {@code PrivatePageFilter}.
     */
    @GetMapping("/private-access")
    public String loginPage() {
        return "forward:/tempLogin.html";
    }

    /**
     * Legacy alias. The temporary-access page used to live here; it now lives at
     * {@code /private-access}. Redirect (preserving any query string such as the
     * {@code ?c=} church token or {@code ?reason=expired}) so old links/bookmarks
     * keep working while the page is only referenced as {@code /private-access}.
     */
    @GetMapping("/tempLogin")
    public String legacyTempLogin(HttpServletRequest request) {
        String qs = request.getQueryString();
        return "redirect:/private-access" + (qs != null && !qs.isBlank() ? "?" + qs : "");
    }
}
