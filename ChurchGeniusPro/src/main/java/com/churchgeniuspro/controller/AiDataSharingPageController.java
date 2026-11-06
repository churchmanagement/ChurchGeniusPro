package com.churchgeniuspro.controller;

import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * App Store readiness D6 — the "AI data sharing" page: every signed-in person (staff of any
 * role, Member Portal, church owner) sees their OWN answers to the three AI data-sharing
 * notices and can stop sharing. The data comes from {@code /api/ai/consent} (the session's
 * own login only). In a web browser the page explains that the notices are used in the app.
 */
@Controller
public class AiDataSharingPageController {

    @GetMapping("/aiDataSharing")
    public String page(HttpServletRequest request) {
        // Any signed-in person: staff of every role, the church owner login and Member Portal
        // logins (who sign in with a member id rather than a username).
        jakarta.servlet.http.HttpSession s = request.getSession(false);
        boolean member = s != null && "Member".equals(s.getAttribute("role")) && s.getAttribute("memberId") != null;
        if (s == null || (!member && s.getAttribute("username") == null)) return RoleGuard.REDIRECT_LOGIN;
        return "forward:/aiDataSharing.html";
    }
}
