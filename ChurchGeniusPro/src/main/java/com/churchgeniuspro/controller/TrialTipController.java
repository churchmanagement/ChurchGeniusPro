package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.TrialTipState;
import com.churchgeniuspro.repository.TrialTipStateRepository;
import com.churchgeniuspro.service.SubscriptionService;
import com.churchgeniuspro.service.TestDataService;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Feature-discovery tips for Trial accounts (trial-tips.js). The script asks
 * {@code GET /api/trial-tips/state} on each page; the server decides eligibility from
 * the session every time — a staff login of a sample-data trial/demo tenant or of a
 * church on the Trial plan — and returns that login's seen/dismissed keys. Everything
 * else (Free/Standard/Pro, Church portal, member, temporary and NTAG sessions) gets
 * {@code eligible:false} and the script does nothing.
 *
 * <p>Tip content lives in the script; the server only stores keys, so a new tip needs
 * no server change. Keys are validated by shape and capped per login.
 */
@RestController
public class TrialTipController {

    static final Pattern KEY = Pattern.compile("^[a-z0-9][a-z0-9-]{1,39}$");
    public static final int MAX_KEYS_PER_LOGIN = 200;

    private final TrialTipStateRepository states;
    private final SubscriptionService subscriptions;

    public TrialTipController(TrialTipStateRepository states, SubscriptionService subscriptions) {
        this.states = states;
        this.subscriptions = subscriptions;
    }

    /** The trial staff login behind this request, or null when tips must not show. */
    Login eligibleLogin(HttpServletRequest request) {
        HttpSession s = request.getSession(false);
        if (s == null || s.getAttribute("username") == null) return null;
        if (s.getAttribute("memberId") != null || s.getAttribute("tempAccessId") != null || s.getAttribute("ntagCredId") != null) return null;
        Object role = s.getAttribute("role");
        if ("Member".equals(role) || "ServiceAdmin".equals(role)) return null;
        if (Boolean.TRUE.equals(s.getAttribute("church")) || "true".equals(String.valueOf(s.getAttribute("church")))) return null;
        String clientId = SessionUtil.getAppClientId(request);
        if (clientId == null || clientId.isBlank()) return null;
        boolean trial = TestDataService.isManagedTenant(clientId);
        if (!trial) {
            try { trial = subscriptions.trialInfo(clientId) != null; } catch (Exception e) { trial = false; }
        }
        if (!trial) return null;
        return new Login(clientId, String.valueOf(s.getAttribute("username")), role == null ? "" : String.valueOf(role));
    }

    record Login(String clientId, String username, String role) {}

    @GetMapping("/api/trial-tips/state")
    public ResponseEntity<Map<String, Object>> state(HttpServletRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        Login who = eligibleLogin(request);
        if (who == null) { out.put("eligible", false); return ResponseEntity.ok(out); }
        out.put("eligible", true);
        out.put("role", who.role());
        Map<String, Object> tips = new LinkedHashMap<>();
        LocalDateTime last = null; int today = 0;
        LocalDateTime dayStart = com.churchgeniuspro.util.AppClock.today().atStartOfDay();
        for (TrialTipState t : states.findByClientIdAndUsername(who.clientId(), who.username())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("shown", t.getShownCount());
            m.put("lastShownAt", t.getLastShownAt() == null ? null : t.getLastShownAt().toString());
            m.put("dismissed", t.getDismissedAt() != null);
            tips.put(t.getTipKey(), m);
            if (t.getLastShownAt() != null) {
                if (last == null || t.getLastShownAt().isAfter(last)) last = t.getLastShownAt();
                if (!t.getLastShownAt().isBefore(dayStart)) today++;
            }
        }
        out.put("tips", tips);
        out.put("lastShownAt", last == null ? null : last.toString());
        out.put("shownToday", today);
        out.put("now", LocalDateTime.now().toString());
        return ResponseEntity.ok(out);
    }

    @PostMapping("/api/trial-tips/{key}/shown")
    public ResponseEntity<Map<String, Object>> shown(@PathVariable String key, HttpServletRequest request) {
        return record(key, request, false);
    }

    @PostMapping("/api/trial-tips/{key}/dismissed")
    public ResponseEntity<Map<String, Object>> dismissed(@PathVariable String key, HttpServletRequest request) {
        return record(key, request, true);
    }

    private ResponseEntity<Map<String, Object>> record(String key, HttpServletRequest request, boolean dismiss) {
        Map<String, Object> out = new LinkedHashMap<>();
        Login who = eligibleLogin(request);
        if (who == null) { out.put("status", "ignored"); return ResponseEntity.ok(out); }   // never an error: a tip must not break a page
        if (key == null || !KEY.matcher(key).matches()) { out.put("error", "Unknown tip."); return ResponseEntity.badRequest().body(out); }
        TrialTipState t = states.findByClientIdAndUsernameAndTipKey(who.clientId(), who.username(), key).orElse(null);
        if (t == null) {
            if (states.countByClientIdAndUsername(who.clientId(), who.username()) >= MAX_KEYS_PER_LOGIN) {
                out.put("error", "Too many tips."); return ResponseEntity.badRequest().body(out);
            }
            t = new TrialTipState();
            t.setClientId(who.clientId()); t.setUsername(who.username()); t.setTipKey(key);
        }
        LocalDateTime now = LocalDateTime.now();
        if (dismiss) {
            if (t.getDismissedAt() == null) t.setDismissedAt(now);
            if (t.getShownCount() == 0) { t.setShownCount(1); t.setLastShownAt(now); }
        } else {
            t.setShownCount(t.getShownCount() + 1);
            t.setLastShownAt(now);
        }
        states.save(t);
        out.put("status", "success");
        out.put("key", key);
        out.put("shown", t.getShownCount());
        out.put("dismissed", t.getDismissedAt() != null);
        return ResponseEntity.ok(out);
    }
}
