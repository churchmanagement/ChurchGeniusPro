package com.churchgeniuspro.webfilter;

import com.churchgeniuspro.service.AccountStatusService;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Re-asks, on every API call, the questions sign-in asked once.
 *
 * <p>Expiry and demo blocks were enforced only at authentication, so they governed
 * new sign-ins and nothing else: a live session kept working after its church's
 * subscription lapsed or after a Service Admin pressed Block, for as long as the
 * session lasted — which, for a member who had turned on "Don't log out
 * automatically", is forever.
 *
 * <p>Like {@link DemoTrialAgreementFilter}, this refuses the DATA and lets the page
 * render, so the application can show the reason instead of a blank screen, and it
 * answers 403 rather than 401 so {@code session.js} does not bounce to the login
 * screen before the message is read. The session is deliberately NOT invalidated:
 * refusing every API call already ends the access, and leaving the session intact
 * keeps sign-out and the explanation working.
 *
 * <p>Fails OPEN on any error — see {@link AccountStatusService}.
 */
public class AccountStatusFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(AccountStatusFilter.class);

    /** Endpoints that must keep working so the notice can be shown and dismissed. */
    private static final String[] ALWAYS_ALLOWED = {
            "/api/session",                // session.js bootstrap
            "/api/logout",                 // signing out must always work
            "/api/auth/logout",
            "/api/logo/image",             // cosmetic header logo
            "/api/subscription/features",  // the page that explains the plan
            "/api/demo/trial-agreement",
            "/api/subscription-request",   // an ended trial/subscription must still be able to ask for a plan
            "/api/invoice"                 // …and to see the invoice it was sent
    };

    private final AccountStatusService status;

    public AccountStatusFilter(AccountStatusService status) {
        this.status = status;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest  request  = (HttpServletRequest)  req;
        HttpServletResponse response = (HttpServletResponse) res;
        try {
            HttpSession session = request.getSession(false);
            if (session == null) { chain.doFilter(req, res); return; }                 // anonymous
            if (session.getAttribute("serviceAdminId") != null) {                      // platform staff
                chain.doFilter(req, res); return;
            }

            // Decoded and normalised, so an encoded spelling of an allow-listed path
            // is treated as the path it will be served as (see RequestPaths).
            String path = RequestPaths.path(request);
            if (!path.startsWith("/api/") || isAllowed(path)) { chain.doFilter(req, res); return; }

            String clientId = firstNonBlank(str(session.getAttribute("appClientId")),
                                            str(session.getAttribute("clientId")));
            AccountStatusService.Block block = status.forTenant(clientId);
            if (block == null) block = status.forLogin(clientId, str(session.getAttribute("username")));
            if (block == null) { chain.doFilter(req, res); return; }

            log.info("Account status block: tenant={} user={} path={} code={}",
                     clientId, session.getAttribute("username"), path, block.code());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"" + escape(block.message()) + "\","
                    + "\"code\":\"" + block.code() + "\"}");
            // Phase C: a trial set to Pending / Disabled / Deleted is signed out as soon as
            // it is detected — the message above is the last thing this session gets;
            // the next page load lands on the sign-in screen, which refuses it too.
            if (AccountStatusService.TRIAL_STATUS_CODE.equals(block.code())) {
                try { session.invalidate(); } catch (IllegalStateException ignored) { /* already gone */ }
            }
        } catch (Exception e) {
            log.warn("AccountStatusFilter failed open — {}", e.getMessage());
            chain.doFilter(req, res);
        }
    }

    private static boolean isAllowed(String path) {
        for (String p : ALWAYS_ALLOWED) {
            if (path.equals(p) || path.startsWith(p + "/")) return true;
        }
        return false;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return (b != null && !b.isBlank()) ? b : null;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
