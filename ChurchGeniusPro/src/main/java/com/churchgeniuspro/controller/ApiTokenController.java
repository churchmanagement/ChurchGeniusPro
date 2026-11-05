package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.PersonalAccessToken;
import com.churchgeniuspro.mcp.PersonalAccessTokenService;
import com.churchgeniuspro.mcp.TokenSession;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Personal access tokens for external AI tools (AI Phase 5, 2026-11-05): the page {@code /apiTokens}
 * and its API under {@code /api/tokens} (plan feature {@code mcp}).
 *
 * <p>Who: staff logins only (SuperAdmin / Admin / Accountant / User) with the AI Assistant permission;
 * never Member, Kids, church-owner or temporary-access sessions, and never a token-backed request
 * (a token cannot mint tokens). Admins (SuperAdmin / Admin) also see every token of the church, switch
 * the church's opt-in and can revoke all. The secret is in the create response ONCE.
 */
@Controller
public class ApiTokenController {

    private final PersonalAccessTokenService tokens;
    private final AppUserRepository users;

    public ApiTokenController(PersonalAccessTokenService tokens, AppUserRepository users) {
        this.tokens = tokens; this.users = users;
    }

    /* ───────────────────────── page ───────────────────────── */

    @GetMapping("/apiTokens")
    public String page(HttpServletRequest request) {
        if (gate(request) != null) return RoleGuard.FORWARD_ACCESS_DENIED;
        return "forward:/apiTokens.html";
    }

    /* ───────────────────────── API ───────────────────────── */

    @GetMapping("/api/tokens")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> g = gate(req);
        if (g != null) return g;
        String cid = SessionUtil.getAppClientId(req);
        HttpSession s = req.getSession(false);
        boolean admin = isAdmin(s);
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("serverEnabled", tokens.serverEnabled());
        out.put("planAllows", tokens.planAllows(cid));
        out.put("tenantEnabled", tokens.tenantEnabled(cid));
        out.put("admin", admin);
        out.put("scopes", new ArrayList<>(PersonalAccessTokenService.SCOPES));
        out.put("defaultExpiryDays", PersonalAccessTokenService.DEFAULT_EXPIRY_DAYS);
        out.put("maxExpiryDays", PersonalAccessTokenService.MAX_EXPIRY_DAYS);
        out.put("endpoint", baseUrl(req) + "/mcp");
        Integer uid = userId(s);
        List<Map<String, Object>> mine = new ArrayList<>();
        for (PersonalAccessToken t : tokens.mine(cid, uid)) mine.add(PersonalAccessTokenService.view(t, now));
        out.put("mine", mine);
        if (admin) {
            List<Map<String, Object>> all = new ArrayList<>();
            for (PersonalAccessToken t : tokens.all(cid)) all.add(PersonalAccessTokenService.view(t, now));
            out.put("all", all);
        }
        return ResponseEntity.ok(out);
    }

    @PostMapping("/api/tokens")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> g = gate(req);
        if (g != null) return g;
        String cid = SessionUtil.getAppClientId(req);
        HttpSession s = req.getSession(false);
        if (!tokens.serverEnabled()) return ResponseEntity.status(503).body(Map.of("error", "External AI connections are switched off on this server."));
        if (!tokens.planAllows(cid)) return ResponseEntity.status(403).body(Map.of("error", "External AI connections are not included in your plan.", "code", "PLAN"));
        if (!tokens.tenantEnabled(cid)) return ResponseEntity.status(403).body(Map.of("error", "An administrator has to switch on external AI connections for your church first.", "code", "TENANT_OFF"));
        AppUser u = users.findByIdAndClientId(userId(s), cid).orElse(null);
        if (u == null || u.isDeleteFlag() || !u.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "Your staff account could not be found."));
        if (body == null) body = Map.of();
        Object scopesIn = body.get("scopes");
        Collection<?> scopes = scopesIn instanceof Collection<?> c ? c : List.of();
        // Only admins may hand out the actions scope: a write token is the church's decision, not one person's.
        if (scopes.stream().anyMatch(x -> "actions".equalsIgnoreCase(String.valueOf(x).trim())) && !isAdmin(s))
            return ResponseEntity.status(403).body(Map.of("error", "Only SuperAdmin and Admin accounts can create a token with the actions scope."));
        Integer days = null;
        Object d = body.get("expiresDays");
        if (d != null && !String.valueOf(d).isBlank()) {
            try { days = Integer.parseInt(String.valueOf(d).trim()); } catch (NumberFormatException e) { return ResponseEntity.badRequest().body(Map.of("error", "expiresDays must be a whole number.")); }
        }
        try {
            PersonalAccessTokenService.Created c = tokens.create(cid, u, String.valueOf(s.getAttribute("username")), body.get("name") == null ? null : String.valueOf(body.get("name")), scopes, days);
            Map<String, Object> out = new LinkedHashMap<>(PersonalAccessTokenService.view(c.token(), LocalDateTime.now()));
            out.put("token", c.secret());
            out.put("note", "Copy the token now — it is shown only once.");
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/api/tokens/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> revoke(@PathVariable Long id, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> g = gate(req);
        if (g != null) return g;
        String cid = SessionUtil.getAppClientId(req);
        HttpSession s = req.getSession(false);
        boolean ok = tokens.revoke(cid, id, userId(s), !isAdmin(s), String.valueOf(s.getAttribute("username")));
        if (!ok) return ResponseEntity.status(404).body(Map.of("error", "Token not found."));
        return ResponseEntity.ok(Map.of("status", "success"));
    }

    @PostMapping("/api/tokens/revoke-all")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> revokeAll(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> g = gate(req);
        if (g != null) return g;
        HttpSession s = req.getSession(false);
        if (!isAdmin(s)) return ResponseEntity.status(403).body(Map.of("error", "Only SuperAdmin and Admin accounts can revoke every token."));
        int n = tokens.revokeAll(SessionUtil.getAppClientId(req), String.valueOf(s.getAttribute("username")));
        return ResponseEntity.ok(Map.of("status", "success", "revoked", n));
    }

    @PutMapping("/api/tokens/settings")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> settings(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> g = gate(req);
        if (g != null) return g;
        HttpSession s = req.getSession(false);
        if (!isAdmin(s)) return ResponseEntity.status(403).body(Map.of("error", "Only SuperAdmin and Admin accounts can change this setting."));
        String cid = SessionUtil.getAppClientId(req);
        boolean on = body != null && Boolean.TRUE.equals(body.get("enabled"));
        if (on && !tokens.planAllows(cid)) return ResponseEntity.status(403).body(Map.of("error", "External AI connections are not included in your plan.", "code", "PLAN"));
        tokens.setTenantEnabled(cid, on, String.valueOf(s.getAttribute("username")));
        return ResponseEntity.ok(Map.of("status", "success", "tenantEnabled", on));
    }

    /* ───────────────────────── gates ───────────────────────── */

    /** Null when the session may manage tokens, else the ready 401/403. */
    static ResponseEntity<Map<String, Object>> gate(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        String cid = SessionUtil.getAppClientId(req);
        if (s == null || s.getAttribute("username") == null || cid == null || cid.isBlank())
            return ResponseEntity.status(401).body(Map.of("error", "Not signed in."));
        if (TokenSession.isToken(s)) return ResponseEntity.status(403).body(Map.of("error", "A token cannot manage tokens."));
        if (!VoiceCommandController.isStaffSession(req) || RoleGuard.isKidsPortalSession(s) || Boolean.TRUE.equals(s.getAttribute("church"))
                || com.churchgeniuspro.service.AiAccessPolicy.isTemporaryAccess(s) || userId(s) == null)
            return ResponseEntity.status(403).body(Map.of("error", "API tokens are available to staff accounts."));
        if (RoleGuard.requireFeature(req, RoleGuard.PERM_AI_ASSISTANT) != null)
            return ResponseEntity.status(403).body(Map.of("error", "You do not have access to the AI Assistant."));
        return null;
    }

    static boolean isAdmin(HttpSession s) {
        String role = String.valueOf(s.getAttribute("role"));
        return "SuperAdmin".equals(role) || "Admin".equals(role);
    }

    static Integer userId(HttpSession s) {
        Object o = s.getAttribute("appUserId");
        if (o instanceof Number n) return n.intValue();
        try { return o == null ? null : Integer.valueOf(String.valueOf(o)); } catch (NumberFormatException e) { return null; }
    }

    private static String baseUrl(HttpServletRequest req) {
        String proto = Optional.ofNullable(req.getHeader("X-Forwarded-Proto")).orElse(req.getScheme());
        String host = Optional.ofNullable(req.getHeader("X-Forwarded-Host")).orElse(req.getHeader("Host"));
        if (host == null || host.isBlank()) host = req.getServerName() + (req.getServerPort() == 80 || req.getServerPort() == 443 ? "" : ":" + req.getServerPort());
        return proto + "://" + host;
    }
}
