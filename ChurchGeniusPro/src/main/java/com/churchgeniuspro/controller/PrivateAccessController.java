package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.PrivateAccessSetting;
import com.churchgeniuspro.hibernate.PrivateNetwork;
import com.churchgeniuspro.service.PrivateAccessService;
import com.churchgeniuspro.util.NetworkMatcher;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Admin configuration, audit reporting, and page-status endpoints for the
 * Private Page Access (church-network restriction) feature.
 */
@Controller
public class PrivateAccessController {

    private final PrivateAccessService service;

    public PrivateAccessController(PrivateAccessService service) {
        this.service = service;
    }

    /**
     * Admin setup + audit page. Relocated from {@code /private-access} (now the
     * temporary-access login entry point) to {@code /private-access-settings}.
     */
    @GetMapping("/private-access-settings")
    public String page(HttpServletRequest request) {
        String deny = RoleGuard.requireAdmin(request);
        if (deny != null) return deny;
        return "forward:/private-access-settings.html";
    }

    // ── Config (admin) ───────────────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/private-access/config")
    public ResponseEntity<?> config(HttpServletRequest request) {
        String deny = adminAndClient(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        String clientId = SessionUtil.getAppClientId(request);
        Map<String, Object> cfg = new java.util.LinkedHashMap<>(service.configMap(clientId));
        // Church-scoped login link token so staff/kiosks can reach the gated login for THIS church.
        try { cfg.put("loginToken", com.churchgeniuspro.util.EncryptionUtil.encrypt(clientId)); }
        catch (Exception e) { cfg.put("loginToken", null); }
        return ResponseEntity.ok(cfg);
    }

    @ResponseBody
    @PutMapping("/api/private-access/setting")
    public ResponseEntity<?> saveSetting(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String deny = adminAndClient(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        String clientId = SessionUtil.getAppClientId(request);
        PrivateAccessSetting s = service.saveSetting(clientId,
                asBool(body.get("enabled"), false), asBoolN(body.get("trustProxy")));
        return ResponseEntity.ok(Map.of("enabled", s.isEnabled(), "trustProxy", s.isTrustProxy()));
    }

    @ResponseBody
    @PostMapping("/api/private-access/networks")
    public ResponseEntity<?> saveNetwork(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String deny = adminAndClient(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        String clientId = SessionUtil.getAppClientId(request);
        Long id = body.get("id") != null ? Long.valueOf(body.get("id").toString()) : null;
        PrivateNetwork n = service.saveNetwork(clientId, id,
                str(body.get("name")), str(body.get("ipRanges")), asBool(body.get("enabled"), true));
        return ResponseEntity.ok(Map.of("id", n.getId(), "name", n.getName(),
                "ipRanges", n.getIpRanges() == null ? "" : n.getIpRanges(), "enabled", n.isEnabled()));
    }

    @ResponseBody
    @DeleteMapping("/api/private-access/networks/{id}")
    public ResponseEntity<?> deleteNetwork(@PathVariable Long id, HttpServletRequest request) {
        String deny = adminAndClient(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        service.deleteNetwork(SessionUtil.getAppClientId(request), id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @ResponseBody
    @PutMapping("/api/private-access/rules")
    public ResponseEntity<?> saveRule(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String deny = adminAndClient(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        String clientId = SessionUtil.getAppClientId(request);
        String pageKey = str(body.get("pageKey"));
        boolean enabled = asBool(body.get("enabled"), false);
        List<Long> ids = new ArrayList<>();
        Object raw = body.get("networkIds");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                try { ids.add(Long.valueOf(o.toString())); } catch (NumberFormatException ignored) {}
            }
        }
        try {
            service.saveRule(clientId, pageKey, enabled, ids);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Returns the caller's detected IP — used by the "Detect my IP" helper. */
    @ResponseBody
    @GetMapping("/api/private-access/my-ip")
    public ResponseEntity<?> myIp(HttpServletRequest request) {
        String deny = adminAndClient(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        PrivateAccessSetting s = service.getSetting(SessionUtil.getAppClientId(request));
        return ResponseEntity.ok(Map.of("ip", NetworkMatcher.clientIp(request, s.isTrustProxy())));
    }

    @ResponseBody
    @GetMapping("/api/private-access/audit")
    public ResponseEntity<?> audit(@RequestParam(defaultValue = "100") int limit, HttpServletRequest request) {
        String deny = adminAndClient(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        return ResponseEntity.ok(service.report(SessionUtil.getAppClientId(request), limit));
    }

    /**
     * Lightweight status for the restricted-page indicator — any authenticated user on
     * a gated page calls this to decide whether to show the "🔒 church network" badge.
     */
    @ResponseBody
    @GetMapping("/api/private-access/page-status")
    public ResponseEntity<?> pageStatus(@RequestParam String key, HttpServletRequest request) {
        if (RoleGuard.requireAuth(request) != null) return ResponseEntity.status(401).body(Map.of("error", "Sign in"));
        String clientId = SessionUtil.getAppClientId(request);
        boolean priv = clientId != null && service.isPrivate(clientId, key);
        return ResponseEntity.ok(Map.of("private", priv));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String adminAndClient(HttpServletRequest request) {
        if (RoleGuard.requireAdmin(request) != null) return "Permission denied";
        if (SessionUtil.getAppClientId(request) == null) return "Not authenticated";
        return null;
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static boolean asBool(Object o, boolean dflt) {
        if (o == null) return dflt;
        if (o instanceof Boolean b) return b;
        return Boolean.parseBoolean(o.toString());
    }
    private static Boolean asBoolN(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean b) return b;
        return Boolean.parseBoolean(o.toString());
    }
}
