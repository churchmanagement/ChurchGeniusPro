package com.churchgeniuspro.controller;

import com.churchgeniuspro.ai.audit.AiAuditService;
import com.churchgeniuspro.ai.write.AiWritingService;
import com.churchgeniuspro.ai.write.AiWritingService.Draft;
import com.churchgeniuspro.ai.write.AiWritingService.Request;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI Phase 3 — {@code POST /api/ai/write}: draft / rewrite / shorten / expand / fix / translate for the e-mail and
 * SMS composers. Returns TEXT only; nothing here sends (no EmailService / SmsService dependency — architecture
 * test). Gates: signed-in staff session (never Member / Kids / temp / church-owner), the AI Assistant permission,
 * plan feature {@code aiWrite} (SubscriptionFeatureFilter on /api/ai/write), AiGuardFilter rate limit + audit
 * (channel WRITE, intent write.&lt;action&gt;).
 */
@RestController
public class AiWritingController {

    private final AiWritingService writing;

    public AiWritingController(AiWritingService writing) { this.writing = writing; }

    static ResponseEntity<Map<String, Object>> gate(HttpServletRequest req, String what) {
        String clientId = SessionUtil.getAppClientId(req);
        if (clientId == null || clientId.isBlank() || req.getSession(false) == null || req.getSession(false).getAttribute("username") == null)
            return ResponseEntity.status(401).body(Map.of("error", "Not signed in."));
        if (!VoiceCommandController.isStaffSession(req) || RoleGuard.isKidsPortalSession(req.getSession(false))
                || Boolean.TRUE.equals(req.getSession(false).getAttribute("church")))
            return ResponseEntity.status(403).body(Map.of("error", what + " is available to staff accounts."));
        if (RoleGuard.requireFeature(req, RoleGuard.PERM_AI_ASSISTANT) != null)
            return ResponseEntity.status(403).body(Map.of("error", "You do not have access to the AI Assistant."));
        return null;
    }

    @PostMapping("/api/ai/write")
    public ResponseEntity<Map<String, Object>> write(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> g = gate(req, "AI writing");
        if (g != null) return g;
        Request r;
        try { r = AiWritingService.validate(body); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
        AiAuditService.intent(req, "write." + r.action() + "." + r.channel());
        if (!writing.isEnabled()) return ResponseEntity.status(503).body(Map.of("error", "AI writing is not configured on this server."));
        Draft d = writing.write(r);
        if (d == null) return ResponseEntity.status(502).body(Map.of("error", "The assistant couldn't produce a draft just now. Please try again."));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", r.action()); out.put("channel", r.channel());
        if (d.subject() != null) out.put("subject", d.subject());
        out.put("body", d.body()); out.put("chars", d.chars());
        if (d.note() != null) out.put("note", d.note());
        out.put("draft", true);     // always a draft — the page inserts, the staff member sends
        return ResponseEntity.ok(out);
    }

    /**
     * 2026-11-20 — the Tax Report's "AI draft": wording for the letter around the contribution table. The model is
     * given no contributor and no figure (see AiTaxLetterService); the page and Genius Assistant put the draft
     * around the table they already drew from the database. Same people as the Tax Report itself
     * (SuperAdmin / Admin / Accountant) plus the AI Assistant permission and the aiWrite plan
     * feature (this path is under /api/ai/write). Nothing is saved.
     */
    @PostMapping("/api/ai/write/tax-letter")
    public ResponseEntity<Map<String, Object>> taxLetter(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> g = gate(req, "AI writing");
        if (g != null) return g;
        if (RoleGuard.requireAccountantOrAdmin(req) != null)                 // the Tax Report API's own rule
            return ResponseEntity.status(403).body(Map.of("error", "You do not have access to the Tax Report."));
        int year;
        try { year = Integer.parseInt(String.valueOf(body == null ? null : body.get("year")).trim()); }
        catch (RuntimeException e) { return ResponseEntity.badRequest().body(Map.of("error", "Choose the tax year first.")); }
        if (year < 2000 || year > com.churchgeniuspro.util.AppClock.today().getYear() + 1)
            return ResponseEntity.badRequest().body(Map.of("error", "That tax year is not available."));
        AiAuditService.intent(req, "write.draft.taxletter");
        if (taxLetters == null || !taxLetters.isEnabled())
            return ResponseEntity.status(503).body(Map.of("error", "AI writing is not configured on this server."));
        String clientId = SessionUtil.getAppClientId(req);
        String churchName = churches == null ? "" : churches.findByClientIdAndDeleteFlagFalse(clientId)
                .map(c -> c.getChurchName() == null ? "" : c.getChurchName()).orElse("");
        Object tone = body == null ? null : body.get("tone");
        Map<String, String> d = taxLetters.draft(churchName, year, tone instanceof String t && AiWritingService.TONES.contains(t.trim().toLowerCase()) ? t : null);
        if (d == null) return ResponseEntity.status(502).body(Map.of("error", "The assistant couldn't produce a draft just now. Please try again, or use the default template."));
        Map<String, Object> out = new java.util.LinkedHashMap<>(d);
        out.put("draft", true); out.put("year", year);
        return ResponseEntity.ok(out);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.ai.write.AiTaxLetterService taxLetters;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.repository.ChurchRegistrationRepository churches;
}
