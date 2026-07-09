package com.churchgeniuspro.util;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Utility for extracting session attributes from HTTP requests.
 */
public class SessionUtil {

    /**
     * Returns the {@code appClientId} for the current session.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>{@code session.appClientId} — set during login for regular (non-church) users.</li>
     *   <li>{@code session.clientId} — fallback for church-level accounts whose own
     *       {@code client_id} in the signup table is the church's app client ID.</li>
     * </ol>
     *
     * <p>Returns {@code null} only if neither attribute is present or both are blank,
     * which would otherwise cause the repository queries to bypass the client filter
     * and return data belonging to all registered clients.
     */
    public static String getAppClientId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;

        // Primary: appClientId set for regular (non-church) users
        Object val = session.getAttribute("appClientId");
        if (val instanceof String s && !s.isBlank()) return s;

        // Fallback: clientId set for church-level accounts (their own clientId IS the appClientId)
        Object clientIdVal = session.getAttribute("clientId");
        if (clientIdVal instanceof String s && !s.isBlank()) return s;

        return null;
    }

    /**
     * Returns the {@code username} of the currently logged-in user from the
     * HTTP session, or {@code null} if there is no active session.
     * Used to stamp {@code created_by} on financial transactions.
     */
    public static String getUsername(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        Object val = session.getAttribute("username");
        if (val instanceof String s) return s.isBlank() ? null : s;
        return null;
    }

    /** The logged-in user's role (e.g. SuperAdmin, Admin, Accountant, User, Member), or null. */
    public static String getRole(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        Object val = session.getAttribute("role");
        return val instanceof String s ? s : null;
    }

    /** True for a church-type (owner) login. */
    public static boolean isChurchAccount(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return false;
        Object val = session.getAttribute("church");
        return Boolean.TRUE.equals(val) || "true".equalsIgnoreCase(String.valueOf(val));
    }

    /**
     * True when the current session is an administrator who may bypass network-based
     * page restrictions: SuperAdmin/Admin staff roles, or a church (owner) account.
     */
    public static boolean isAdminLike(HttpServletRequest request) {
        if (isChurchAccount(request)) return true;
        String role = getRole(request);
        return "SuperAdmin".equals(role) || "Admin".equals(role);
    }
}
