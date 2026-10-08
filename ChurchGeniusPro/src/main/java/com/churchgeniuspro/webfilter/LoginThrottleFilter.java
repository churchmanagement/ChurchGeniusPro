package com.churchgeniuspro.webfilter;

import com.churchgeniuspro.service.LoginProtectionService;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Applies the same brute-force protection to the <em>secondary</em> authentication
 * entry points that {@code LoginController} applies to {@code POST /login}.
 *
 * <p>These endpoints authenticate with something other than a username+password pair —
 * a service-admin credential, a temporary badge code, an NFC tag plus PIN and OTP — so
 * there is no account name to key on. They are therefore rate-limited by IP scope only,
 * which is exactly the signal that matters for them: a single host grinding through
 * badge codes or PINs.
 *
 * <p>The filter is intentionally thin. It refuses requests while an IP-scope block is
 * active and reports a failure whenever the downstream handler answers 401/403, letting
 * {@link LoginProtectionService} own every threshold decision. Registering it as a filter
 * rather than editing four controllers means a future authentication endpoint added under
 * one of these prefixes is protected the moment it is mapped.
 *
 * @see com.churchgeniuspro.webfilter.FilterConfig
 */
public class LoginThrottleFilter implements Filter {

    private final LoginProtectionService protection;

    public LoginThrottleFilter(LoginProtectionService protection) {
        this.protection = protection;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  req = (HttpServletRequest)  request;
        HttpServletResponse res = (HttpServletResponse) response;
        String endpoint = req.getRequestURI();

        // ── Pre-check: refuse while a block is active, without touching credentials ──
        LoginProtectionService.GuardResult guard = protection.check(req, null);
        if (guard.blocked()) {
            protection.recordBlockedAttempt(req, null, endpoint);
            writeBlocked(res, guard);
            return;
        }

        chain.doFilter(request, response);

        // ── Post-check: an authentication refusal counts as a failed attempt ──
        int status = res.getStatus();
        if (status == HttpServletResponse.SC_UNAUTHORIZED || status == HttpServletResponse.SC_FORBIDDEN) {
            LoginProtectionService.FailureOutcome outcome = protection.recordFailure(
                    req, null, LoginProtectionService.REASON_ENDPOINT_DENIED, null, endpoint);
            protection.applyProgressiveDelay(outcome);
        }
    }

    /**
     * Writes the generic throttled response. The body names neither the threshold nor
     * the number of attempts already made; {@code Retry-After} carries only how long to
     * wait, which the client needs in order to behave well.
     */
    static void writeBlocked(HttpServletResponse res, LoginProtectionService.GuardResult guard)
            throws IOException {
        res.setStatus(429);   // HttpServletResponse has no SC_TOO_MANY_REQUESTS constant
        res.setHeader("Retry-After", String.valueOf(guard.retryAfterSeconds()));
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"status\":\"error\",\"blocked\":true,\"message\":\""
                + LoginProtectionService.BLOCKED_MESSAGE + "\"}");
    }
}
