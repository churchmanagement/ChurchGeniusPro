package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.RememberMeToken;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.service.RememberMeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Mobile Phase 6 — "Your devices": the remembered browsers and app installs of the
 * signed-in login, with per-device sign-out.
 *
 * <p>Every row is a {@code remember_me_token} (Phase 1, M2): a browser that ticked
 * Remember me, or an app install holding a device secret behind the biometric prompt.
 * Revoking a row means that browser/app has to sign in with the password next time;
 * its current server session is untouched (a 30-minute idle session, or none at all
 * when "Don't log out automatically" is on) — so "sign out of other devices" takes
 * effect the next time that device would have auto-logged in.
 *
 * <p>{@code /api/auth/**} is public at the filter (the login page needs
 * {@code check-remember} before any session exists), so these endpoints check the
 * session themselves and answer 401 without one — the same discipline as
 * {@code /api/auth/linked-accounts}.
 */
@RestController
@RequestMapping("/api/auth/devices")
public class DeviceSessionController {

    private static final Logger LOG = LoggerFactory.getLogger(DeviceSessionController.class);

    private final RememberMeService rememberMe;
    private final LoginRepository   logins;

    public DeviceSessionController(RememberMeService rememberMe, LoginRepository logins) {
        this.rememberMe = rememberMe;
        this.logins = logins;
    }

    /** The list, oldest first; {@code current} marks the row proving this request (cookie or device secret). */
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(value = "deviceToken", required = false) String deviceToken,
                                  HttpServletRequest request) {
        SignUp me = currentLogin(request).orElse(null);
        if (me == null) return unauthorized();
        Long current = rememberMe.currentRowId(request, deviceToken);
        List<Map<String, Object>> out = new ArrayList<>();
        for (RememberMeToken t : rememberMe.activeDevices(me.getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",         t.getId());
            m.put("label",      t.getDeviceLabel() == null ? "Unknown device" : t.getDeviceLabel());
            m.put("platform",   t.getPlatform());
            m.put("createdAt",  t.getCreatedAt() == null ? null : t.getCreatedAt().toString());
            m.put("lastUsedAt", t.getLastUsedAt() == null ? null : t.getLastUsedAt().toString());
            m.put("expiresAt",  t.getExpiresAt() == null ? null : t.getExpiresAt().toString());
            m.put("current",    current != null && current.equals(t.getId()));
            out.add(m);
        }
        return ResponseEntity.ok(Map.of("status", "ok", "devices", out));
    }

    /** Signs one device out (its remembered credential is revoked). 404 for a row that is not this login's. */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> revoke(@PathVariable("id") Long id, HttpServletRequest request) {
        SignUp me = currentLogin(request).orElse(null);
        if (me == null) return unauthorized();
        if (!rememberMe.revokeOne(me.getId(), id)) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Device not found."));
        }
        LOG.info("Devices: username='{}' signed out device row {}", me.getUsername(), id);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * "Sign out of all other devices": every remembered device except this one.
     * The app passes its device secret so its own row is the one kept.
     */
    @PostMapping("/revoke-others")
    public ResponseEntity<?> revokeOthers(@RequestBody(required = false) Map<String, Object> body,
                                          HttpServletRequest request) {
        SignUp me = currentLogin(request).orElse(null);
        if (me == null) return unauthorized();
        String deviceToken = body != null && body.get("deviceToken") instanceof String s ? s : null;
        int n = rememberMe.revokeOthers(me.getId(), request, deviceToken);
        LOG.info("Devices: username='{}' signed out {} other device(s)", me.getUsername(), n);
        return ResponseEntity.ok(Map.of("status", "ok", "revoked", n));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** The login row of the session, by the username the login put on it. */
    private Optional<SignUp> currentLogin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return Optional.empty();
        Object u = session.getAttribute("username");
        if (!(u instanceof String username) || username.isBlank()) return Optional.empty();
        return logins.findActiveByUsername(username);
    }

    private static ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("status", "error", "message", "Session expired. Please log in."));
    }
}
