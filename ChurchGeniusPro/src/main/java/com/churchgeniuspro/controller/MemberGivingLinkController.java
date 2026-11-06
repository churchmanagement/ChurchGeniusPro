package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.PublicScreenLink;
import com.churchgeniuspro.repository.PublicScreenLinkRepository;
import com.churchgeniuspro.util.AppClock;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * App Store readiness D5 — the iOS app does not show the Stripe card form (Apple guideline
 * 3.2.2(iv): donations are collected outside the app, e.g. in Safari). The Member Portal's
 * Give tab asks this endpoint for the church's existing PUBLIC donation page
 * ({@code /donate/<token>}, the same link churches already share and text) and opens it in
 * Safari.
 *
 * <p>Read-only; returns only a path that is already public. Member sessions only (role
 * Member with a member id; not Kids Portal, temporary-access or NTAG sessions), scoped to the
 * session's own church. No donation link → {@code available:false} with a message.
 */
@RestController
public class MemberGivingLinkController {

    private final PublicScreenLinkRepository links;

    public MemberGivingLinkController(PublicScreenLinkRepository links) {
        this.links = links;
    }

    @GetMapping("/api/member/giving-link")
    public ResponseEntity<Map<String, Object>> givingLink(HttpServletRequest request) {
        String appClientId = memberChurch(request);
        if (appClientId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not a member account"));
        }
        String path = activeDonationPath(appClientId, AppClock.today());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("available", path != null);
        if (path != null) {
            body.put("path", path);
        } else {
            body.put("message", "Online giving isn't set up for your church yet. Please contact your church office.");
        }
        return ResponseEntity.ok(body);
    }

    /** The first non-revoked, unexpired {@code /donate} link of this church, or null. */
    String activeDonationPath(String appClientId, LocalDate today) {
        List<PublicScreenLink> found =
                links.findByAppClientIdAndPageUrlStartingWithAndRevokedFalse(appClientId, "/donate");
        for (PublicScreenLink link : found) {
            if (link.getToken() == null || link.getToken().isBlank()) continue;
            if (link.getExpirationDate() != null && link.getExpirationDate().isBefore(today)) continue;
            return "/donate/" + link.getToken();
        }
        return null;
    }

    /** The member session's church, or null when this is not a (non-child) member session. */
    static String memberChurch(HttpServletRequest request) {
        HttpSession s = request.getSession(false);
        if (s == null) return null;
        if (!"Member".equals(s.getAttribute("role"))) return null;
        if (!(s.getAttribute("memberId") instanceof Number)) return null;
        if (RoleGuard.isKidsPortalSession(s)) return null;
        if (s.getAttribute("tempAccessId") != null || s.getAttribute("ntagCredId") != null) return null;
        Object app = s.getAttribute("appClientId");
        return app instanceof String str && !str.isBlank() ? str : null;
    }
}
