package com.churchgeniuspro.payroll.controller;

import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Central authorization + tenant resolution for the payroll REST controllers.
 *
 * <p>{@link #authorize(HttpServletRequest)} runs the {@link RoleGuard#requirePayroll}
 * policy and resolves the effective tenant ({@code appClientId} for staff logins,
 * {@code clientId} for church logins) via {@link RoleGuard#clientId}. Controllers
 * call it once per request and either short-circuit with the returned HTTP status
 * or proceed using {@code clientId}/{@code actor}.
 */
public final class PayrollAuth {

    private PayrollAuth() {}

    public static final class Result {
        public final boolean ok;
        public final int status;       // 401 if unauthenticated, 403 if not authorized
        public final String clientId;  // effective tenant id (null when !ok)
        public final String actor;     // session username for audit (null when !ok)

        Result(boolean ok, int status, String clientId, String actor) {
            this.ok = ok; this.status = status; this.clientId = clientId; this.actor = actor;
        }
    }

    public static Result authorize(HttpServletRequest request) {
        String deny = RoleGuard.requirePayroll(request);
        if (deny != null) {
            int status = RoleGuard.REDIRECT_LOGIN.equals(deny) ? 401 : 403;
            return new Result(false, status, null, null);
        }
        String clientId = RoleGuard.clientId(request);
        if (clientId == null || clientId.isBlank()) {
            return new Result(false, 403, null, null);
        }
        return new Result(true, 200, clientId, actor(request));
    }

    private static String actor(HttpServletRequest request) {
        HttpSession s = request.getSession(false);
        Object u = s == null ? null : s.getAttribute("username");
        return u == null ? "system" : u.toString();
    }
}
