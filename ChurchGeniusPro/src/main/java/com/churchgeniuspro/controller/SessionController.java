package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.repository.AppUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Provides session-state endpoints for the frontend.
 *
 * <ul>
 *   <li>{@code GET  /api/session} — returns session attributes when the caller
 *       has a valid session; returns HTTP 401 otherwise.</li>
 *   <li>{@code POST /api/logout}  — invalidates the current session and returns
 *       HTTP 200.  Safe to call even when no session exists.</li>
 * </ul>
 *
 * <p>These endpoints are intentionally <em>excluded</em> from the
 * {@code AuthFilter} path-checks so they work regardless of session state.
 */
@RestController
public class SessionController {

    private final AppUserRepository appUserRepository;

    public SessionController(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    // ── GET /api/session ──────────────────────────────────────────────────────

    /**
     * Returns the attributes stored in the current HTTP session.
     *
     * <p>HTTP 200 + body {@code {"authenticated":true, "clientId":…, …}} when a
     * valid session exists.
     * <p>HTTP 401 + body {@code {"authenticated":false}} when there is no
     * session or the session does not carry a {@code clientId} attribute (i.e.
     * the user has not logged in).
     */
    @GetMapping("/api/session")
    public ResponseEntity<Map<String, Object>> getSession(HttpServletRequest request) {

        HttpSession session = request.getSession(false);   // do NOT create a new session

        if (session == null || session.getAttribute("clientId") == null) {
            return ResponseEntity.status(401)
                    .body(Map.of("authenticated", false));
        }

        Map<String, Object> res = new HashMap<>();
        res.put("authenticated", true);
        // Temporary-access session flag — temp sessions own their sidebar via
        // temp-session.js, so session.js must NOT apply role/permission nav
        // filtering over it (which would hide the permitted pages).
        res.put("temporary", session.getAttribute("tempAccessId") != null);
        // NTAG restricted session flag — like temp sessions, the NTAG user's sidebar is
        // built from their exact permitted pages (ntag-session.js), so session.js must
        // NOT apply its role/permission nav filtering over it.
        res.put("ntag", session.getAttribute("ntagCredId") != null);
        res.put("clientId",   nvl(session.getAttribute("clientId")));
        res.put("username",   nvl(session.getAttribute("username")));
        res.put("church",     session.getAttribute("church"));          // Boolean
        res.put("churchId",   nvl(session.getAttribute("churchId")));
        res.put("appClientId",  session.getAttribute("appClientId"));  // String or null
        res.put("appUserId",    session.getAttribute("appUserId"));    // Integer or null
        res.put("privileges",   session.getAttribute("privileges"));   // JSON string or null
        res.put("subscription", nvl(session.getAttribute("subscription")));
        res.put("churchName", nvl(session.getAttribute("churchName")));

        // ── Self-heal: if firstName/lastName/role are missing from session,
        // look them up from app_user using clientId (for non-church staff accounts).
        String firstName = nvl(session.getAttribute("firstName"));
        String lastName  = nvl(session.getAttribute("lastName"));
        String role      = nvl(session.getAttribute("role"));

        boolean isChurch = Boolean.TRUE.equals(session.getAttribute("church"));
        if (!isChurch && firstName.isEmpty()) {
            String clientId = nvl(session.getAttribute("clientId"));
            if (!clientId.isEmpty()) {
                AppUser appUser = appUserRepository
                        .findByUserIdAndDeleteFlagFalse(clientId)
                        .orElse(null);
                if (appUser != null) {
                    firstName = nvl(appUser.getFirstName());
                    lastName  = nvl(appUser.getLastName());
                    role      = nvl(appUser.getRole());
                    // Persist back into session so subsequent calls don't repeat the lookup
                    session.setAttribute("firstName", firstName);
                    session.setAttribute("lastName",  lastName);
                    session.setAttribute("role",      role);
                }
            }
        }

        res.put("firstName",  firstName);
        res.put("lastName",   lastName);
        res.put("role",       role);
        res.put("memberRole",       nvl(session.getAttribute("memberRole")));       // FamilyMember role (e.g. Son, Daughter, Child)
        res.put("memberId",         session.getAttribute("memberId"));               // Integer or null
        res.put("memberPrivileges", session.getAttribute("memberPrivileges"));       // JSON string or null (Member portal only)

        return ResponseEntity.ok(res);
    }

    // ── POST /api/logout ──────────────────────────────────────────────────────

    /**
     * Invalidates the current HTTP session (if one exists) and returns a
     * success confirmation.  The frontend is responsible for redirecting to the
     * login page.
     */
    @PostMapping("/api/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {

        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.ok(Map.of("status", "success"));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /** Returns the attribute as a String, or an empty string when null. */
    private static String nvl(Object value) {
        return value != null ? value.toString() : "";
    }
}
