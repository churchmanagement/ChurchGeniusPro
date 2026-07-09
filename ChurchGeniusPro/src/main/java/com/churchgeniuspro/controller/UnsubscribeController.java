package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.UnsubscribeService;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Handles email unsubscribe flow and the admin Unsubscribed List page.
 *
 * <p>Public endpoints (no auth required):
 * <ul>
 *   <li>{@code GET  /unsubscribe}                → unsubscribe confirmation page</li>
 *   <li>{@code POST /api/public/unsubscribe}      → save unsubscribe record</li>
 * </ul>
 *
 * <p>Admin endpoints:
 * <ul>
 *   <li>{@code GET    /unsubscribed-list}         → admin list page</li>
 *   <li>{@code GET    /api/unsubscribe/list}       → JSON list for current org</li>
 *   <li>{@code DELETE /api/unsubscribe/{id}}       → re-subscribe (remove from list)</li>
 * </ul>
 */
@Controller
public class UnsubscribeController {

    private final UnsubscribeService unsubscribeService;

    public UnsubscribeController(UnsubscribeService unsubscribeService) {
        this.unsubscribeService = unsubscribeService;
    }

    // ── Public pages / API ────────────────────────────────────────────────

    @GetMapping("/unsubscribe")
    public String unsubscribePage() {
        return "forward:/unsubscribe.html";
    }

    @PostMapping("/api/public/unsubscribe")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> doUnsubscribe(
            @RequestBody Map<String, Object> body) {
        String email     = str(body.get("email"));
        String clientId  = str(body.get("clientId"));
        String firstName = str(body.get("firstName"));
        String lastName  = str(body.get("lastName"));
        if (email == null || clientId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Email and organization ID are required."));
        }
        boolean saved = unsubscribeService.unsubscribe(email, clientId, firstName, lastName);
        String msg = saved
                ? "You have been successfully unsubscribed from future emails."
                : "This email address is already on our unsubscribe list.";
        return ResponseEntity.ok(Map.of("message", msg));
    }

    // ── Admin pages / API ─────────────────────────────────────────────────

    @GetMapping("/unsubscribed-list")
    public String unsubscribedListPage(HttpServletRequest request) {
        // Members with admin.unsubscribed permission are allowed through
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        boolean isMember = session != null
                && "Member".equals(session.getAttribute("role"))
                && session.getAttribute("memberId") != null;
        if (isMember) {
            String deny = RoleGuard.requireMemberPermission(request, "admin.unsubscribed");
            return deny != null ? deny : "forward:/unsubscribedList.html";
        }
        String deny = RoleGuard.requireAdmin(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePermission(request, "admin.unsubscribed");
        if (deny != null) return deny;
        return "forward:/unsubscribedList.html";
    }

    @GetMapping("/api/unsubscribe/list")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getList(HttpServletRequest request) {
        jakarta.servlet.http.HttpSession _s = request.getSession(false);
        boolean _isMbr = _s != null && "Member".equals(_s.getAttribute("role")) && _s.getAttribute("memberId") != null;
        if (_isMbr) {
            String deny = RoleGuard.requireMemberPermission(request, "admin.unsubscribed");
            if (deny != null) return ResponseEntity.status(403).build();
        } else {
            String deny = RoleGuard.requireAdmin(request);
            if (deny != null) return ResponseEntity.status(403).build();
        }
        String clientId = _isMbr ? RoleGuard.clientId(request) : SessionUtil.getAppClientId(request);
        return ResponseEntity.ok(unsubscribeService.getUnsubscribed(clientId));
    }

    @DeleteMapping("/api/unsubscribe/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> resubscribe(
            @PathVariable Integer id, HttpServletRequest request) {
        jakarta.servlet.http.HttpSession _s = request.getSession(false);
        boolean _isMbr = _s != null && "Member".equals(_s.getAttribute("role")) && _s.getAttribute("memberId") != null;
        if (_isMbr) {
            String deny = RoleGuard.requireMemberPermission(request, "admin.unsubscribed");
            if (deny != null) return ResponseEntity.status(403).build();
        } else {
            String deny = RoleGuard.requireAdmin(request);
            if (deny != null) return ResponseEntity.status(403).build();
        }
        try {
            unsubscribeService.resubscribe(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String str(Object val) {
        if (val == null) return null;
        String s = val.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
