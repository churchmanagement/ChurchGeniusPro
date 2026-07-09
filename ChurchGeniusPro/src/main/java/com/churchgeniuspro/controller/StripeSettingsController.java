package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.StripeSettings;
import com.churchgeniuspro.repository.StripeSettingsRepository;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serves the Stripe Integration settings page and its REST API.
 *
 * <p>Only one record per organization is allowed (enforced by a unique constraint
 * on {@code client_id}).  The save endpoint is therefore always an upsert.</p>
 *
 * <ul>
 *   <li>{@code GET  /stripeIntegration}       → page (Admin / SuperAdmin / Church)</li>
 *   <li>{@code GET  /api/stripe-settings}      → load current keys</li>
 *   <li>{@code POST /api/stripe-settings}      → create or update keys</li>
 * </ul>
 */
@Controller
public class StripeSettingsController {

    private final StripeSettingsRepository repo;

    public StripeSettingsController(StripeSettingsRepository repo) {
        this.repo = repo;
    }

    // ── Page ─────────────────────────────────────────────────────────────────

    @GetMapping("/stripeIntegration")
    public String page(HttpServletRequest request) {
        // Owner-only: ONLY church-type logins may access. All other roles denied.
        String deny = RoleGuard.requireChurch(request);
        return deny != null ? deny : "forward:/stripeIntegration.html";
    }

    // ── REST: load ────────────────────────────────────────────────────────────

    /**
     * Returns the current Stripe keys for the session's organization.
     * The secret key is masked in the response — only the first 8 characters
     * are returned so the UI can confirm a key is set without exposing it.
     */
    @ResponseBody
    @GetMapping("/api/stripe-settings")
    public ResponseEntity<?> get(HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();

        StripeSettings s = repo.findByClientId(clientId).orElse(new StripeSettings());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("publishableKey", s.getPublishableKey() != null ? s.getPublishableKey() : "");
        // Return a masked hint so the secret never travels to the browser in full
        String sk = s.getSecretKey();
        out.put("secretKeySet",    sk != null && !sk.isBlank());
        out.put("secretKeyMasked", sk != null && !sk.isBlank()
                ? sk.substring(0, Math.min(sk.length(), 8)) + "••••••••••••••••"
                : "");
        return ResponseEntity.ok(out);
    }

    // ── REST: save (upsert) ───────────────────────────────────────────────────

    /**
     * Creates or updates the single Stripe-settings record for this organization.
     * If {@code secretKey} is blank in the request body the existing secret is preserved.
     */
    @ResponseBody
    @PostMapping("/api/stripe-settings")
    public ResponseEntity<?> save(@RequestBody Map<String, Object> body,
                                   HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();

        String publishableKey = str(body.get("publishableKey"));
        String secretKey      = str(body.get("secretKey"));

        if (publishableKey == null || publishableKey.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Publishable Key is required."));
        if (secretKey == null || secretKey.isBlank()) {
            // Allow blank only if a secret already exists (preserve the stored value)
            boolean hasExisting = repo.findByClientId(clientId)
                    .map(s -> s.getSecretKey() != null && !s.getSecretKey().isBlank())
                    .orElse(false);
            if (!hasExisting)
                return ResponseEntity.badRequest().body(Map.of("error", "Secret Key is required."));
        }

        StripeSettings s = repo.findByClientId(clientId).orElse(new StripeSettings());
        s.setClientId(clientId);
        s.setPublishableKey(publishableKey);
        if (secretKey != null && !secretKey.isBlank()) {
            s.setSecretKey(secretKey);
        }

        repo.save(s);
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static String str(Object o) {
        return o == null ? null : o.toString().trim();
    }
}
