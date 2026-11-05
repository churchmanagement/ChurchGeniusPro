package com.churchgeniuspro.controller;

import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Admin → AI Activity page (AI Phase 0): SuperAdmin / Admin staff and the church login. */
@Controller
public class AiActivityPageController {

    @GetMapping("/aiActivity")
    public String page(HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrChurch(request);
        if (deny != null) return deny;
        return "forward:/aiActivity.html";
    }

    /** More → Saved Lists (AI Phase 1): staff only (never Member / Kids / church-owner sessions), AI Assistant + Saved Lists permissions. */
    @GetMapping("/savedLists")
    public String savedLists(HttpServletRequest request) {
        if (!VoiceCommandController.isStaffSession(request) || Boolean.TRUE.equals(request.getSession(false).getAttribute("church"))) return RoleGuard.FORWARD_ACCESS_DENIED;
        String role = String.valueOf(request.getSession(false).getAttribute("role"));
        if (!("SuperAdmin".equals(role) || "Admin".equals(role) || "Accountant".equals(role))) return RoleGuard.FORWARD_ACCESS_DENIED;
        // The page's own API (/api/ai-search/saved-lists) re-checks the AI Assistant feature; the route checks role + page key.
        String deny = RoleGuard.requirePagePermission(request, AiPeopleFilterController.PERM_SAVED_LISTS);
        if (deny != null) return deny;
        return "forward:/savedLists.html";
    }
}
