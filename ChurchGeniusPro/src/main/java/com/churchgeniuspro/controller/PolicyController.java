package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.PolicyAcceptance;
import com.churchgeniuspro.repository.PolicyAcceptanceRepository;
import com.churchgeniuspro.util.PolicyVersions;
import com.churchgeniuspro.util.PublicSendLimiter;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Public legal/compliance pages and the acceptance-tracking API.
 *
 * <h3>Public pages (no login required)</h3>
 * <pre>
 *  GET /terms-of-service      GET /privacy-policy     GET /cookie-policy
 *  GET /acceptable-use-policy GET /refund-policy
 * </pre>
 *
 * <h3>Acceptance API</h3>
 * <pre>
 *  POST /api/policy-acceptance        — record an acceptance (public; used by the
 *                                        cookie banner and any explicit re-accept flow)
 *  GET  /api/policy-acceptance/audit  — list acceptances for the current org (admin)
 * </pre>
 */
@Controller
public class PolicyController {

    private final PolicyAcceptanceRepository acceptanceRepository;
    private final PublicSendLimiter sendLimiter;

    public PolicyController(PolicyAcceptanceRepository acceptanceRepository, PublicSendLimiter sendLimiter) {
        this.acceptanceRepository = acceptanceRepository;
        this.sendLimiter = sendLimiter;
    }

    // ── Public policy pages (clean URLs → static HTML) ───────────────────────

    @GetMapping("/terms-of-service")
    public String terms() { return "forward:/terms-of-service.html"; }

    @GetMapping("/privacy-policy")
    public String privacy() { return "forward:/privacy-policy.html"; }

    @GetMapping("/cookie-policy")
    public String cookie() { return "forward:/cookie-policy.html"; }

    @GetMapping("/acceptable-use-policy")
    public String acceptableUse() { return "forward:/acceptable-use-policy.html"; }

    @GetMapping("/refund-policy")
    public String refund() { return "forward:/refund-policy.html"; }

    // ── Acceptance tracking API ──────────────────────────────────────────────

    /**
     * Records a single policy acceptance. Public so it can be called from the
     * cookie-consent banner before a session exists. Tenant/user are taken from
     * the session when available, otherwise from the request body.
     */
    @ResponseBody
    @PostMapping("/api/policy-acceptance")
    public ResponseEntity<Map<String, Object>> accept(@RequestBody Map<String, Object> body,
                                                       HttpServletRequest request) {
        Map<String, Object> res = new HashMap<>();

        String policyType = str(body, "policyType");
        if (policyType == null || policyType.isBlank()) {
            res.put("status", "error");
            res.put("message", "policyType is required.");
            return ResponseEntity.badRequest().body(res);
        }
        policyType = policyType.trim().toLowerCase();
        // Anonymous, unbounded inserts with caller-chosen strings (security audit P7):
        // only a policy this application actually has, a short source label, and a
        // per-origin ceiling.
        if (!PolicyVersions.isKnown(policyType)) {
            res.put("status", "error");
            res.put("message", "Unknown policy.");
            return ResponseEntity.badRequest().body(res);
        }
        String limited = sendLimiter.check(PublicSendLimiter.POLICY_ACCEPTANCE, request, null, null);
        if (limited != null) {
            res.put("status", "error");
            res.put("message", limited);
            return ResponseEntity.status(429).body(res);
        }

        String sessionClient = SessionUtil.getAppClientId(request);
        String sessionUser   = SessionUtil.getUsername(request);

        PolicyAcceptance row = new PolicyAcceptance();
        // Identity comes from the session or not at all. A pre-login acceptance (cookie
        // banner) is recorded anonymously; the body used to be allowed to name any
        // tenant/user, which polluted churches' acceptance audits.
        row.setClientId(sessionClient);
        row.setUsername(sessionUser);
        row.setPolicyType(policyType);
        row.setPolicyVersion(PolicyVersions.versionFor(policyType));
        row.setAccepted(Boolean.TRUE);
        row.setAcceptedAt(new Date());
        row.setIpAddress(clientIp(request));
        row.setUserAgent(request.getHeader("User-Agent"));
        String source = str(body, "source");
        if (source != null && source.length() > 32) source = source.substring(0, 32);
        row.setSource(source != null ? source : "web");

        try {
            acceptanceRepository.save(row);
        } catch (Exception e) {
            res.put("status", "error");
            res.put("message", "Could not record acceptance.");
            return ResponseEntity.status(500).body(res);
        }

        res.put("status", "success");
        res.put("policyType", policyType);
        res.put("version", row.getPolicyVersion());
        return ResponseEntity.ok(res);
    }

    /** Admin-only audit listing of acceptances for the current organization. */
    @ResponseBody
    @GetMapping("/api/policy-acceptance/audit")
    public ResponseEntity<?> audit(HttpServletRequest request) {
        if (!SessionUtil.isAdminLike(request)) {
            return ResponseEntity.status(403).body(Map.of("error", "Not authorized."));
        }
        String clientId = SessionUtil.getAppClientId(request);
        if (clientId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "No session."));
        }
        List<PolicyAcceptance> rows = acceptanceRepository.findByClientIdOrderByAcceptedAtDesc(clientId);
        return ResponseEntity.ok(rows);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }
}
