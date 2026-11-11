package com.churchgeniuspro.util;

import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.LoginRepository;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

/**
 * Ends sessions whose password has changed (2026-11-11, App Store security review).
 *
 * <p>A login row carries {@code credential_version}, raised by every password set. A session records the version
 * it signed in with ({@link #stamp}); a session created before this rule existed has no stamp and counts as
 * version 0, which is what every row starts at. {@link #isCurrent} compares the two — a mismatch means the
 * password was changed after this session signed in, on this or any other instance, and the session must end.
 * The comparison is by number, not time, so instance clock skew cannot log anyone out wrongly.
 */
public final class CredentialSessions {

    private CredentialSessions() {}

    public static final String ATTR = "credVersion";
    public static final String SERVICE_ADMIN_ATTR = "serviceAdminCredVersion";
    public static final String CODE = "PASSWORD_CHANGED";
    public static final String MESSAGE = "Your password was changed. Please sign in again with your new password.";

    /** Records the version this session signed in with. Call wherever a login session is established. */
    public static void stamp(HttpSession session, SignUp login) {
        if (session != null && login != null) session.setAttribute(ATTR, version(login.getCredentialVersion()));
    }

    public static int version(Integer v) { return v == null ? 0 : v; }

    /** The version a session signed in with; 0 for sessions from before the stamp existed. */
    public static int sessionVersion(HttpSession session, String attr) {
        Object v = session == null ? null : session.getAttribute(attr);
        return v instanceof Number n ? n.intValue() : 0;
    }

    /**
     * Is this session still on the login's current password? {@code preloaded} is the login row when the caller has
     * already read it (church / member path in AuthFilter), otherwise the row is found by the session's login id
     * (one indexed read), falling back once to client id + username for sessions from before the login id was kept.
     * A login that cannot be identified, or no longer exists, is NOT current (fails closed).
     * Sessions that never had a password — temporary access passes and NTAG badge logins — are not checked here.
     */
    public static boolean isCurrent(HttpSession session, LoginRepository logins, SignUp preloaded) {
        if (session == null) return false;
        if (session.getAttribute("tempAccessId") != null || session.getAttribute("ntagCredId") != null) return true;
        Integer dbVersion;
        if (preloaded != null) {
            dbVersion = version(preloaded.getCredentialVersion());
            if (preloaded.getId() != null && !(session.getAttribute("loginId") instanceof Number))
                session.setAttribute("loginId", preloaded.getId());
        } else {
            Integer loginId = loginId(session, logins);
            if (loginId == null) return false;
            dbVersion = logins.findLiveCredentialVersion(loginId);
            if (dbVersion == null) return false;
        }
        return sessionVersion(session, ATTR) == dbVersion;
    }

    private static Integer loginId(HttpSession s, LoginRepository logins) {
        Object v = s.getAttribute("loginId");
        if (v instanceof Number n) return n.intValue();
        Object cid = s.getAttribute("clientId"), user = s.getAttribute("username");
        if (!(cid instanceof String c) || c.isBlank() || !(user instanceof String u) || u.isBlank() || logins == null) return null;
        try {
            SignUp row = logins.findByClientId(c).orElse(null);
            if (row == null || row.getId() == null || !u.equalsIgnoreCase(row.getUsername())) return null;
            s.setAttribute("loginId", row.getId());
            return row.getId();
        } catch (RuntimeException e) {
            return null;   // several rows / lookup error → cannot prove which login this is
        }
    }

    /** Ends the session and answers 401 PASSWORD_CHANGED (JSON). */
    public static void reject(HttpSession session, HttpServletResponse res) throws IOException {
        try { if (session != null) session.invalidate(); } catch (IllegalStateException ignored) { /* already gone */ }
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"status\":\"error\",\"code\":\"" + CODE + "\",\"message\":\"" + MESSAGE + "\"}");
    }
}
