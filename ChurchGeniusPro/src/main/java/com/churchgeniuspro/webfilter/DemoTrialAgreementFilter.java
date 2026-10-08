package com.churchgeniuspro.webfilter;

import com.churchgeniuspro.hibernate.DemoRoleAccess;
import com.churchgeniuspro.service.DemoAccessService;
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
import java.util.Optional;

/**
 * Holds a demo/trial login at the door until the trial-account agreement is accepted.
 *
 * <p>A demo user who has not yet clicked OK gets the shell of a page but no data:
 * every {@code /api/*} call outside a tiny whitelist is refused with 403 and the
 * code {@code DEMO_AGREEMENT_REQUIRED}. Page requests themselves are let through
 * on purpose — the acknowledgement popup lives inside the page, so blocking the
 * HTML would leave nothing to click.
 *
 * <p>403 rather than 401 is deliberate: {@code session.js} bounces to the login
 * screen on 401, which would log the user out instead of showing them the popup.
 *
 * <h2>Why the session is not simply trusted</h2>
 * {@code LoginController} records {@code demoTrial} on the session at sign-in, but
 * sessions are also built by remember-me, account switching and role switching.
 * Rather than patch each one, an ABSENT flag is resolved here on first request and
 * cached on the session, so every path that can produce a session is covered.
 * The resolution is skipped entirely unless the session's tenant carries the demo
 * client prefix, so a normal account costs one string comparison and NO session
 * write — see {@code resolveTrialFlag} for why the write mattered.
 *
 * <p>Fails OPEN on error, matching the login path: a database blip must not lock a
 * demo tenant out of an application they are entitled to see.
 */
public class DemoTrialAgreementFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(DemoTrialAgreementFilter.class);

    public static final String ATTR_TRIAL     = "demoTrial";
    public static final String ATTR_ACCEPTED  = "demoTrialAccepted";
    public static final String ATTR_END_DATE  = "demoTrialEndDate";
    public static final String ATTR_SIGNUP_ID = "demoTrialSignupId";

    /** Endpoints that must keep working while the popup is on screen. */
    private static final String[] ALLOWED_WHILE_PENDING = {
            "/api/demo/trial-agreement",   // read the terms, and accept them
            "/api/session",                // session.js bootstrap — without it the page bounces
            "/api/auth/logout",            // declining by signing out must always work
            "/api/logo/image",             // cosmetic header logo; harmless and avoids a broken shell
            "/api/subscription-request",   // asking for a plan never requires accepting the trial terms first
            "/api/invoice"                 // nor does viewing an invoice
    };

    private final DemoAccessService demoAccess;

    public DemoTrialAgreementFilter(DemoAccessService demoAccess) {
        this.demoAccess = demoAccess;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest req) || !(response instanceof HttpServletResponse res)) {
            chain.doFilter(request, response);
            return;
        }

        HttpSession session = req.getSession(false);
        if (session == null) {                       // anonymous — nothing to gate
            chain.doFilter(request, response);
            return;
        }

        Boolean isTrial;
        try {
            isTrial = resolveTrialFlag(session);
        } catch (Exception e) {                      // fail open, as the login path does
            LOG.warn("Demo trial gate skipped — {}", e.getMessage());
            chain.doFilter(request, response);
            return;
        }

        if (!Boolean.TRUE.equals(isTrial) || Boolean.TRUE.equals(session.getAttribute(ATTR_ACCEPTED))) {
            chain.doFilter(request, response);
            return;
        }

        // Decoded + normalised: "/%61pi/members" is "/api/members" to the dispatcher,
        // so it must be "/api/members" to this gate as well.
        String path = RequestPaths.path(req);

        // Pages render; only their data is withheld. The popup needs a page to live in.
        if (!path.startsWith("/api/") || isAllowed(path)) {
            chain.doFilter(request, response);
            return;
        }

        Object endDate = session.getAttribute(ATTR_END_DATE);
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write(
                "{\"error\":\"Trial account agreement not accepted.\","
              + "\"code\":\"DEMO_AGREEMENT_REQUIRED\","
              + "\"endDate\":\"" + (endDate == null ? "" : escape(String.valueOf(endDate))) + "\"}");
    }

    /**
     * TRUE / FALSE for "this session belongs to a demo trial login", resolved once
     * and cached on the session.
     *
     * <p>The cheap check comes first: no demo client prefix on the session's tenant
     * means no lookup at all, and nothing written. Only a session that looks like a
     * demo tenant and has never been classified reaches the repository — and only
     * those sessions ever store the flag.
     */
    private Boolean resolveTrialFlag(HttpSession session) {
        Object cached = session.getAttribute(ATTR_TRIAL);
        if (cached instanceof Boolean b) return b;

        String tenant = firstNonBlank(
                str(session.getAttribute("appClientId")),
                str(session.getAttribute("clientId")));

        if (!demoAccess.isDemoClient(tenant)) {
            // Deliberately NOT cached on the session. There is nothing to save: the
            // answer is a prefix test on a string already in the session, so caching
            // it bought nothing — and it cost every ordinary church a session WRITE
            // on the first request of every session.
            //
            // That write was a live bug. A page opens with a burst of parallel API
            // calls; each one loads the session before any of them commits, so each
            // sees demoTrial as a NEW attribute and each INSERTs it. The second one
            // to commit violates spring_session_attributes_pk and the request fails
            // with a DuplicateKeyException. Writing nothing here makes it impossible
            // for a normal church, and SessionConfig makes the write idempotent for
            // the demo/trial sessions that do legitimately store these four.
            return Boolean.FALSE;
        }

        String username = str(session.getAttribute("username"));
        Optional<DemoRoleAccess> window = demoAccess.forUsername(username);
        if (window.isEmpty()) {
            // A demo tenant with no window row yet. Nothing to enforce against, so
            // the request passes — but the answer is NOT cached on the session: the
            // sign-in and remember-me paths both create the row, and caching FALSE
            // here meant a login that reached a page a moment before its window was
            // created never saw the agreement again for the life of that session.
            // Only demo tenants ever reach this line, so the cost is a lookup per
            // request for an account that is about to acquire a row anyway.
            return Boolean.FALSE;
        }

        DemoRoleAccess w = window.get();
        session.setAttribute(ATTR_TRIAL,     Boolean.TRUE);
        session.setAttribute(ATTR_ACCEPTED,  w.isAgreementAccepted());
        session.setAttribute(ATTR_END_DATE,  w.getEndDate() == null ? "" : w.getEndDate().toString());
        session.setAttribute(ATTR_SIGNUP_ID, w.getSignupId());
        return Boolean.TRUE;
    }

    private static boolean isAllowed(String path) {
        for (String p : ALLOWED_WHILE_PENDING) {
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
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
