package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.NtagCredential;
import com.churchgeniuspro.service.NtagService;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Admin management of NTAG credentials (church-owner only): register/assign tags,
 * enable/disable, reset PIN, revoke, delete, and view login history. Mirrors the
 * Temporary Access admin model.
 */
@Controller
public class NtagAdminController {

    private final NtagService svc;

    public NtagAdminController(NtagService svc) { this.svc = svc; }

    /** Admin page (church-owner only). */
    @GetMapping("/ntagAccess")
    public String page(HttpServletRequest request) {
        String deny = RoleGuard.requireChurch(request);
        if (deny != null) return deny;
        return "forward:/ntag-access.html";
    }

    @ResponseBody
    @GetMapping("/api/ntag")
    public ResponseEntity<?> list(HttpServletRequest request) {
        String deny = guard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        String clientId = SessionUtil.getAppClientId(request);
        List<Map<String, Object>> rows = svc.list(clientId).stream().map(svc::toMap).collect(Collectors.toList());
        return ResponseEntity.ok(rows);
    }

    @ResponseBody
    @PostMapping("/api/ntag")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> b, HttpServletRequest request) {
        String deny = guard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        try {
            NtagCredential c = svc.create(
                    SessionUtil.getAppClientId(request), SessionUtil.getUsername(request),
                    str(b, "ntagSerial"), str(b, "holderName"), str(b, "email"), str(b, "phone"),
                    str(b, "userId"), str(b, "role"), str(b, "pin"),
                    bool(b, "requirePin", true), bool(b, "requireOtp", true), str(b, "permissions"),
                    dt(b, "validFrom"), dt(b, "validUntil"));
            return ResponseEntity.ok(svc.toMap(c));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @ResponseBody
    @PutMapping("/api/ntag/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> b, HttpServletRequest request) {
        String deny = guard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        try {
            NtagCredential c = svc.update(id, SessionUtil.getAppClientId(request),
                    str(b, "holderName"), str(b, "email"), str(b, "phone"), str(b, "userId"), str(b, "role"),
                    boolN(b, "requirePin"), boolN(b, "requireOtp"), str(b, "permissions"),
                    dt(b, "validFrom"), dt(b, "validUntil"));
            return ResponseEntity.ok(svc.toMap(c));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @ResponseBody
    @PostMapping("/api/ntag/{id}/pin")
    public ResponseEntity<?> resetPin(@PathVariable Long id, @RequestBody Map<String, Object> b, HttpServletRequest request) {
        String deny = guard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        try { svc.resetPin(id, SessionUtil.getAppClientId(request), str(b, "pin")); return ResponseEntity.ok(Map.of("success", true)); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @ResponseBody
    @PostMapping("/api/ntag/{id}/enabled")
    public ResponseEntity<?> setEnabled(@PathVariable Long id, @RequestBody Map<String, Object> b, HttpServletRequest request) {
        String deny = guard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        try { svc.setEnabled(id, SessionUtil.getAppClientId(request), bool(b, "enabled", true)); return ResponseEntity.ok(Map.of("success", true)); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @ResponseBody
    @PostMapping("/api/ntag/{id}/revoke")
    public ResponseEntity<?> revoke(@PathVariable Long id, HttpServletRequest request) {
        String deny = guard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        try { svc.revoke(id, SessionUtil.getAppClientId(request)); return ResponseEntity.ok(Map.of("success", true)); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @ResponseBody
    @DeleteMapping("/api/ntag/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, HttpServletRequest request) {
        String deny = guard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        try { svc.delete(id, SessionUtil.getAppClientId(request)); return ResponseEntity.ok(Map.of("success", true)); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @ResponseBody
    @GetMapping("/api/ntag/history")
    public ResponseEntity<?> history(@RequestParam(defaultValue = "150") int limit, HttpServletRequest request) {
        String deny = guard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        return ResponseEntity.ok(svc.historyForClient(SessionUtil.getAppClientId(request), limit));
    }

    @ResponseBody
    @GetMapping("/api/ntag/{id}/history")
    public ResponseEntity<?> credentialHistory(@PathVariable Long id, @RequestParam(defaultValue = "100") int limit, HttpServletRequest request) {
        String deny = guard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        return ResponseEntity.ok(svc.historyForCredential(id, limit));
    }

    // ── helpers ──
    private String guard(HttpServletRequest request) {
        if (RoleGuard.requireChurch(request) != null) return "Permission denied";
        if (SessionUtil.getAppClientId(request) == null) return "Not authenticated";
        return null;
    }
    private static String str(Map<String, Object> b, String k) { Object v = b.get(k); return v == null ? null : v.toString(); }
    private static boolean bool(Map<String, Object> b, String k, boolean dflt) {
        Object v = b.get(k); if (v == null) return dflt;
        return (v instanceof Boolean bb) ? bb : Boolean.parseBoolean(v.toString());
    }
    private static Boolean boolN(Map<String, Object> b, String k) {
        Object v = b.get(k); if (v == null) return null;
        return (v instanceof Boolean bb) ? bb : Boolean.parseBoolean(v.toString());
    }
    private static LocalDateTime dt(Map<String, Object> b, String k) {
        Object v = b.get(k);
        if (v == null || v.toString().isBlank()) return null;
        try { return LocalDateTime.parse(v.toString()); } catch (Exception e) { return null; }
    }
}
