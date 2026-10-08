package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.NtagService;
import com.churchgeniuspro.service.TemporaryAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public (pre-session) NTAG three-factor login flow:
 * <ol>
 *   <li>{@code POST /api/ntag-login/start}    — a scanned/typed tag serial → opens a challenge.</li>
 *   <li>{@code POST /api/ntag-login/pin}       — verify the 6-digit PIN.</li>
 *   <li>{@code POST /api/ntag-login/otp}       — verify the emailed OTP.</li>
 *   <li>{@code POST /api/ntag-login/finalize}  — establish the session and land the user.</li>
 * </ol>
 * Whitelisted in AuthFilter so it works without a prior session.
 */
@RestController
@RequestMapping("/api/ntag-login")
public class NtagLoginController {

    private final NtagService svc;
    private final TemporaryAccessService tempAccess;

    public NtagLoginController(NtagService svc, TemporaryAccessService tempAccess) {
        this.svc = svc;
        this.tempAccess = tempAccess;
    }

    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            NtagService.StartResult r = svc.start(str(body, "serial"), clientIp(request), device(request));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("token", r.token);
            m.put("stage", r.stage);
            m.put("holderName", r.holderName);
            m.put("maskedEmail", r.maskedEmail);
            m.put("requirePin", r.requirePin);
            m.put("requireOtp", r.requireOtp);
            return ResponseEntity.ok(m);
        } catch (NtagService.NtagLoginException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/pin")
    public ResponseEntity<?> pin(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(svc.verifyPin(str(body, "token"), str(body, "pin"), clientIp(request), device(request)));
        } catch (NtagService.NtagLoginException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/otp")
    public ResponseEntity<?> otp(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(svc.verifyOtp(str(body, "token"), str(body, "otp"), clientIp(request), device(request)));
        } catch (NtagService.NtagLoginException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/finalize")
    public ResponseEntity<?> finalize(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            NtagService.SessionInfo s = svc.finalizeLogin(str(body, "token"), clientIp(request), device(request));
            // Rotate: a pre-authentication session id must not survive login, and this
            // identity must never be layered onto a session that already holds another.
            HttpSession existing = request.getSession(false);
            if (existing != null) existing.invalidate();
            HttpSession session = request.getSession(true);
            session.setAttribute("clientId",    s.clientId);
            session.setAttribute("appClientId", s.clientId);
            session.setAttribute("username",    s.username);
            session.setAttribute("role",        s.role);
            session.setAttribute("church",      Boolean.FALSE);
            session.setAttribute("churchName",  s.churchName);
            session.setAttribute("privileges",  s.privileges);
            // ── Restricted-access markers (drive backend page-gating + restricted nav) ──
            session.setAttribute("ntagCredId", s.credentialId);
            session.setAttribute("ntagPerms",  s.permsCsv == null ? "" : s.permsCsv);
            session.setAttribute("ntagHolder", s.holderName);
            session.setAttribute("ntagRoutes", new ArrayList<>(
                    s.permittedRoutes == null ? java.util.List.<String>of() : s.permittedRoutes));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "success");
            m.put("startPage", s.startRoute != null ? s.startRoute : "/access-denied.html");
            m.put("holderName", s.holderName);
            return ResponseEntity.ok(m);
        } catch (NtagService.NtagLoginException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * Restricted-session info for the NTAG user's UI: the exact pages they may
     * access (to BUILD a permitted-only sidebar), the permitted routes, and the
     * landing page. Returns {@code {"ntag":false}} for any non-NTAG session.
     */
    @GetMapping("/session")
    public ResponseEntity<?> ntagSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object cred = session == null ? null : session.getAttribute("ntagCredId");
        if (cred == null) return ResponseEntity.ok(Map.of("ntag", false));
        String perms = String.valueOf(session.getAttribute("ntagPerms"));
        if (perms == null || "null".equals(perms)) perms = "";
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ntag", true);
        m.put("holderName", session.getAttribute("ntagHolder"));
        m.put("permittedPages",  tempAccess.permittedPages(perms));
        m.put("permittedRoutes", tempAccess.permittedRoutes(perms));
        m.put("startPage",       tempAccess.firstRoute(perms));
        return ResponseEntity.ok(m);
    }

    private static String str(Map<String, Object> b, String k) { Object v = b.get(k); return v == null ? null : v.toString(); }

    private static String device(HttpServletRequest request) { return request.getHeader("User-Agent"); }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
