package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.EmailSettings;
import com.churchgeniuspro.repository.EmailSettingsRepository;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Controller
public class EmailSettingsController {

    private final EmailSettingsRepository repo;

    public EmailSettingsController(EmailSettingsRepository repo) {
        this.repo = repo;
    }

    // ── Page ──────────────────────────────────────────────────────────────────

    @GetMapping("/emailSettings")
    public String page(HttpServletRequest request) {
        String redirect = RoleGuard.requireAdmin(request);
        return redirect != null ? redirect : "forward:/emailSettings.html";
    }

    // ── REST ──────────────────────────────────────────────────────────────────

    /** Return the current settings (or an empty object if never saved). */
    @ResponseBody
    @GetMapping("/api/email-settings")
    public ResponseEntity<?> get(HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(
                repo.findByClientId(clientId).orElse(new EmailSettings()));
    }

    /** Create or update the single settings record for this organization (upsert). */
    @ResponseBody
    @PostMapping("/api/email-settings")
    public ResponseEntity<?> save(@RequestBody Map<String, Object> body,
                                   HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();

        EmailSettings s = repo.findByClientId(clientId).orElse(new EmailSettings());
        s.setClientId(clientId);
        s.setDisplayName(str(body.get("displayName")));
        s.setFooterComments(str(body.get("footerComments")));
        s.setIncludeLogo(bool(body.get("includeLogo")));
        s.setIncludeDailyVerse(bool(body.get("includeDailyVerse")));
        s.setSignature(str(body.get("signature")));
        return ResponseEntity.ok(repo.save(s));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String str(Object o) {
        return o == null ? null : o.toString().trim();
    }

    private static boolean bool(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        return Boolean.parseBoolean(o.toString());
    }
}
