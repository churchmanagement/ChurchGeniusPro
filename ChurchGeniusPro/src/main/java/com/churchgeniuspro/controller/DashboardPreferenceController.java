package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.DashboardPreference;
import com.churchgeniuspro.repository.DashboardPreferenceRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST endpoints for persisting per-user dashboard layout preferences.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET  /api/dashboard/preferences} — load saved preferences for the
 *       current session user; returns empty arrays when no prefs are stored yet.</li>
 *   <li>{@code POST /api/dashboard/preferences} — upsert (create or update) the
 *       preferences for the current session user.</li>
 * </ul>
 *
 * <p>User identity is resolved from the HTTP session attributes {@code username}
 * and {@code clientId} set at login time.  Both must be present; a missing or
 * blank value returns HTTP 401.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardPreferenceController {

    private final DashboardPreferenceRepository prefRepo;

    public DashboardPreferenceController(DashboardPreferenceRepository prefRepo) {
        this.prefRepo = prefRepo;
    }

    // ── GET /api/dashboard/preferences ───────────────────────────────────────

    /**
     * Returns the stored dashboard preferences for the current user.
     *
     * <p>Response body:
     * <pre>
     * {
     *   "hiddenSections": "[\"s-admin-growth\"]",   // JSON-encoded array string
     *   "sectionOrder":   "[\"s-admin-banner\",…]"  // JSON-encoded array string
     * }
     * </pre>
     * Both fields default to {@code "[]"} when no preferences have been saved yet.
     */
    @GetMapping("/preferences")
    public ResponseEntity<Map<String, Object>> getPreferences(HttpServletRequest request) {
        String[] uc = resolveUser(request);
        if (uc == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        prefRepo.findByUsernameAndClientId(uc[0], uc[1])
            .ifPresentOrElse(
                p -> {
                    body.put("hiddenSections", nvl(p.getHiddenSections()));
                    body.put("sectionOrder",   nvl(p.getSectionOrder()));
                },
                () -> {
                    body.put("hiddenSections", "[]");
                    body.put("sectionOrder",   "[]");
                }
            );
        return ResponseEntity.ok(body);
    }

    // ── POST /api/dashboard/preferences ──────────────────────────────────────

    /**
     * Upserts the dashboard preferences for the current user.
     *
     * <p>Expected request body:
     * <pre>
     * {
     *   "hiddenSections": "[\"s-admin-growth\"]",
     *   "sectionOrder":   "[\"s-admin-banner\",…]"
     * }
     * </pre>
     */
    @PostMapping("/preferences")
    public ResponseEntity<Map<String, Object>> savePreferences(
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {

        String[] uc = resolveUser(request);
        if (uc == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        DashboardPreference pref = prefRepo
            .findByUsernameAndClientId(uc[0], uc[1])
            .orElse(new DashboardPreference());

        pref.setUsername(uc[0]);
        pref.setClientId(uc[1]);
        pref.setHiddenSections(body.getOrDefault("hiddenSections", "[]"));
        pref.setSectionOrder(body.getOrDefault("sectionOrder",   "[]"));
        prefRepo.save(pref);

        return ResponseEntity.ok(Map.of("status", "saved"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Extracts {@code [username, clientId]} from the current session.
     * Returns {@code null} when either value is missing or blank.
     */
    private static String[] resolveUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;

        Object usernameObj = session.getAttribute("username");
        Object clientIdObj = session.getAttribute("clientId");
        if (usernameObj == null || clientIdObj == null) return null;

        String username = usernameObj.toString().trim();
        String clientId = clientIdObj.toString().trim();
        if (username.isEmpty() || clientId.isEmpty()) return null;

        return new String[]{ username, clientId };
    }

    /** Returns the string value, or {@code "[]"} when null or blank. */
    private static String nvl(String value) {
        return (value != null && !value.isBlank()) ? value : "[]";
    }
}
