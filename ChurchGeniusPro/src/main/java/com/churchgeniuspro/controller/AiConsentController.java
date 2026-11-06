package com.churchgeniuspro.controller;

import com.churchgeniuspro.ai.consent.AiConsentNotices;
import com.churchgeniuspro.ai.consent.AiConsentNotices.Type;
import com.churchgeniuspro.ai.consent.AiConsentService;
import com.churchgeniuspro.ai.consent.AiConsentService.LoginRef;
import com.churchgeniuspro.ai.consent.AppShellDetector;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * App Store readiness D6 — the signed-in person's own AI data-sharing consent.
 * Every call acts on the session's own login only; nothing here takes a login id.
 *
 * <ul>
 *   <li>{@code GET  /api/ai/consent} → {app, available, notices[]}</li>
 *   <li>{@code POST /api/ai/consent {type, version}} → agree (current version only)</li>
 *   <li>{@code POST /api/ai/consent/withdraw {type | "all"}} → "Stop sharing with AI"</li>
 * </ul>
 */
@RestController
public class AiConsentController {

    private final AiConsentService consent;

    public AiConsentController(AiConsentService consent) {
        this.consent = consent;
    }

    @GetMapping("/api/ai/consent")
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest request) {
        if (request.getSession(false) == null) return ResponseEntity.status(401).body(Map.of("error", "Not signed in."));
        boolean app = AppShellDetector.isApp(request);
        Optional<LoginRef> who = consent.login(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("app", app);
        body.put("available", who.isPresent());
        body.put("notices", consent.status(who.orElse(null)));
        return ResponseEntity.ok(body);
    }

    @PostMapping("/api/ai/consent")
    public ResponseEntity<Map<String, Object>> agree(@RequestBody(required = false) Map<String, Object> body,
                                                     HttpServletRequest request) {
        if (request.getSession(false) == null) return ResponseEntity.status(401).body(Map.of("error", "Not signed in."));
        AppShellDetector.isApp(request);   // marks the session (sticky) when the app calls
        Optional<LoginRef> who = consent.login(request);
        if (who.isEmpty()) return ResponseEntity.status(403).body(Map.of("error",
                "AI data sharing cannot be confirmed for this sign-in. Please sign in again.", "code", "AI_CONSENT_UNAVAILABLE"));
        Type type = AiConsentNotices.parse(body == null ? null : String.valueOf(body.get("type")));
        if (type == null) return ResponseEntity.badRequest().body(Map.of("error", "Unknown notice."));
        int version;
        try { version = Integer.parseInt(String.valueOf(body.get("version"))); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "Notice version is required.")); }
        if (!consent.agree(who.get(), type, version, AppShellDetector.source(request))) {
            return ResponseEntity.status(409).body(Map.of("error", "This notice has been updated. Please read the new version.",
                    "code", "AI_CONSENT_OUTDATED", "version", AiConsentNotices.current(type).version()));
        }
        return ResponseEntity.ok(Map.of("ok", true, "type", type.name(), "version", version));
    }

    @PostMapping("/api/ai/consent/withdraw")
    public ResponseEntity<Map<String, Object>> withdraw(@RequestBody(required = false) Map<String, Object> body,
                                                        HttpServletRequest request) {
        if (request.getSession(false) == null) return ResponseEntity.status(401).body(Map.of("error", "Not signed in."));
        AppShellDetector.isApp(request);
        Optional<LoginRef> who = consent.login(request);
        if (who.isEmpty()) return ResponseEntity.status(403).body(Map.of("error",
                "AI data sharing cannot be changed for this sign-in. Please sign in again.", "code", "AI_CONSENT_UNAVAILABLE"));
        String raw = body == null || body.get("type") == null ? "all" : String.valueOf(body.get("type"));
        List<Type> types = new ArrayList<>();
        if (!"all".equalsIgnoreCase(raw.trim())) {
            Type t = AiConsentNotices.parse(raw);
            if (t == null) return ResponseEntity.badRequest().body(Map.of("error", "Unknown notice."));
            types.add(t);
        }
        int n = consent.withdraw(who.get(), types);
        return ResponseEntity.ok(Map.of("ok", true, "withdrawn", n));
    }
}
