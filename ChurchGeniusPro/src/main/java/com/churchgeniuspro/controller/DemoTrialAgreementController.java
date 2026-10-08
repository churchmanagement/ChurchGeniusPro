package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.DemoRoleAccess;
import com.churchgeniuspro.service.DemoAccessService;
import com.churchgeniuspro.webfilter.DemoTrialAgreementFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The trial-account agreement a demo login must accept before using the application.
 *
 * <p>Two endpoints, both scoped to the caller's own session — there is no id in
 * either signature, so one demo user can neither read nor accept on behalf of
 * another. {@link DemoTrialAgreementFilter} whitelists this path, so it stays
 * reachable while everything else is being refused.
 *
 * <p>Non-demo callers get {@code demo:false} and nothing else happens; the client
 * script treats that as "no popup".
 */
@RestController
public class DemoTrialAgreementController {

    private static final Logger LOG = LoggerFactory.getLogger(DemoTrialAgreementController.class);

    /**
     * Shown verbatim in the popup. Kept server-side so the wording can be changed
     * in one place without touching 86 pages, and so it cannot drift from what was
     * actually agreed to.
     */
    private static final String TRIAL_MESSAGE =
            "This is a trial account, and some features (such as SMS, Email, WhatsApp, "
          + "Bank Sync, etc.) may not be available. If you would like to test these "
          + "features, please contact the support team.";

    /**
     * Appended to whichever message above applies, because both kinds of account
     * now come with all three portals.
     *
     * <p>The popup is the first thing a new trial administrator sees, and it was
     * the one place that could tell them the account is more than the screen in
     * front of them. It names where the credentials are rather than printing them:
     * the agreement is dismissed once and never seen again, while the panel on the
     * home page is there whenever they need it.
     */
    private static final String PORTALS_NOTE =
            " Your account also includes a Member Portal and a Kids Portal, each with "
          + "its own sign-in, so you can see exactly what a church member and a child "
          + "see. Both use the same sample data as this account and expire with it. "
          + "The usernames and passwords are on your home page, under \u201CYour trial "
          + "portals\u201D.";

    /**
     * A demo tenant is not on a trial — it is sample data a Service Admin loaded,
     * and it has no trial period to end. Telling its users they are on a "trial"
     * that "will end on" a date invited a renewal conversation nobody meant to
     * start; the two are distinguished here rather than in each page.
     */
    private static final String DEMO_MESSAGE =
            "This is a demonstration account holding sample data, and some features "
          + "(such as SMS, Email, WhatsApp, Bank Sync, etc.) may not be available. If "
          + "you would like to see these features, please contact the support team.";

    private final DemoAccessService demoAccess;

    /**
     * The tenant's three portal logins. Field-injected and null-checked so this
     * controller's existing construction sites (and their tests) are unchanged.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.service.PortalCredentialService portalCredentials;

    /** Trial length / start / end / days remaining for the agreement text; optional. */
    private com.churchgeniuspro.service.SubscriptionService subscriptions;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSubscriptions(com.churchgeniuspro.service.SubscriptionService s) { this.subscriptions = s; }

    public DemoTrialAgreementController(DemoAccessService demoAccess) {
        this.demoAccess = demoAccess;
    }

    /** Test seam — supply the portal reader without a Spring context. */
    public void setPortalCredentials(com.churchgeniuspro.service.PortalCredentialService p) {
        this.portalCredentials = p;
    }

    /* ── the tenant's own portal credentials ────────────────────────────── */

    /**
     * The Staff, Member and Kids portal logins for the signed-in tenant.
     *
     * <p>Answers only for a demo or trial tenant, and only to a staff session
     * inside that tenant — these are that account's own generated credentials,
     * which the Service Admin screen has always shown, being handed to the person
     * the account belongs to. A real church reaches this and gets an empty list:
     * it has no generated passwords to show.
     *
     * <p>Sits behind the agreement gate and the account-status gate like every
     * other {@code /api/*} call, which is the right way round: the popup reloads
     * the page when it is accepted, so the panel appears immediately afterwards,
     * and a blocked or expired account cannot read its own credentials to go on
     * using them.
     */
    @GetMapping("/api/demo/portal-credentials")
    public ResponseEntity<Map<String, Object>> portalCredentials(HttpServletRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        HttpSession session = request.getSession(false);
        String tenant = session == null ? null
                : com.churchgeniuspro.util.SessionUtil.getAppClientId(request);

        // Staff only. A Member or Kids portal login must not be handed the keys to
        // the other two — it is inside the tenant, but it is not the account owner.
        boolean staff = session != null
                && com.churchgeniuspro.util.SessionUtil.isAdminLike(request);

        if (!staff || tenant == null || portalCredentials == null) {
            out.put("demo", false);
            out.put("portals", java.util.List.of());
            return ResponseEntity.ok(out);
        }
        java.util.List<Map<String, Object>> portals = portalCredentials.portalsFor(tenant);
        out.put("demo",     !portals.isEmpty());
        out.put("clientId", tenant);
        out.put("portals",  portals);
        return ResponseEntity.ok(out);
    }

    /* ── read ───────────────────────────────────────────────────────────── */

    /**
     * Whether this session must acknowledge the trial agreement, and the text and
     * end date to show if so.
     */
    @GetMapping("/api/demo/trial-agreement")
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        HttpSession session = request.getSession(false);

        Optional<DemoRoleAccess> window = windowFor(session);
        if (window.isEmpty()) {
            out.put("demo", false);
            return ResponseEntity.ok(out);
        }

        DemoRoleAccess w = window.get();
        boolean isDemo = com.churchgeniuspro.service.TestDataService.isDemoTenant(w.getClientId());

        // The portals note is added only when the tenant actually HAS them: a
        // trial created before portals were included would otherwise be sent to a
        // panel with nothing in it. Staff only — a Member or Kids portal login is
        // already inside one of those portals and has no use for the others.
        boolean hasPortals = false;
        if (portalCredentials != null
                && com.churchgeniuspro.util.SessionUtil.isAdminLike(request)) {
            try {
                hasPortals = portalCredentials.portalsFor(w.getClientId()).size() > 1;
            } catch (Exception ignored) { /* the agreement must never fail on this */ }
        }

        out.put("demo",     true);
        out.put("kind",     isDemo ? "demo" : "trial");
        out.put("accepted", w.isAgreementAccepted());
        out.put("endDate",  w.getEndDate() == null ? "" : w.getEndDate().toString());
        out.put("portals",  hasPortals);
        if (!isDemo && subscriptions != null) {
            Map<String, Object> trial = subscriptions.trialInfo(w.getClientId());
            if (trial != null) out.put("trial", trial);
        }
        out.put("message",  (isDemo ? DEMO_MESSAGE : TRIAL_MESSAGE) + (hasPortals ? PORTALS_NOTE : ""));
        return ResponseEntity.ok(out);
    }

    /* ── accept ─────────────────────────────────────────────────────────── */

    /**
     * Records the OK click and lifts the gate.
     *
     * <p>Acceptance is stored on the {@code demo_role_access} row, so it is once per
     * LOGIN and not once per session — signing in again does not ask a second time.
     * (A Service Admin reset clears it, because a reissued credential is a new
     * person as far as the agreement is concerned.) The session flag is written too,
     * because the filter reads the session on every request and leaving it stale
     * would keep refusing APIs to someone who has just agreed.
     */
    @PostMapping("/api/demo/trial-agreement/accept")
    public ResponseEntity<Map<String, Object>> accept(HttpServletRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        HttpSession session = request.getSession(false);

        Optional<DemoRoleAccess> window = windowFor(session);
        if (window.isEmpty()) {
            // Not a demo login: nothing to accept, and nothing to fail either.
            out.put("demo", false);
            out.put("accepted", true);
            return ResponseEntity.ok(out);
        }

        DemoRoleAccess w = demoAccess.acceptAgreement(window.get().getSignupId()).orElse(window.get());

        if (session != null) {
            session.setAttribute(DemoTrialAgreementFilter.ATTR_ACCEPTED, Boolean.TRUE);
        }
        LOG.info("Demo trial agreement accepted — username='{}' tenant={} endDate={}",
                 w.getUsername(), w.getClientId(), w.getEndDate());

        out.put("demo",     true);
        out.put("accepted", true);
        out.put("endDate",  w.getEndDate() == null ? "" : w.getEndDate().toString());
        return ResponseEntity.ok(out);
    }

    /* ── product tour ───────────────────────────────────────────────────── */

    /**
     * Whether this sign-in should be shown the product tour.
     *
     * <p>Four conditions, all of which have to hold. The login belongs to a
     * demo or trial tenant — a paying church is not shown a tour of its own
     * product. It is a STAFF login: the Member and Kids portals have their own,
     * much smaller worlds, and a tour of the admin side would describe screens
     * those logins cannot open. The agreement has been accepted, so the tour
     * never appears on top of the acknowledgement popup or before the account is
     * usable. And it has not been finished or skipped already.
     *
     * <p>Tenant scoping comes free and is not re-derived here: the window is found
     * from this session's own signup id, and it carries the tenant. There is no
     * path by which one church's tour state can be read or written by another.
     */
    @GetMapping("/api/demo/product-tour")
    public ResponseEntity<Map<String, Object>> productTour(HttpServletRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        HttpSession session = request.getSession(false);

        Optional<DemoRoleAccess> window = windowFor(session);
        if (window.isEmpty() || !com.churchgeniuspro.util.SessionUtil.isAdminLike(request)) {
            out.put("show", false);
            return ResponseEntity.ok(out);
        }

        DemoRoleAccess w = window.get();
        boolean isDemo = com.churchgeniuspro.service.TestDataService.isDemoTenant(w.getClientId());

        out.put("show",     w.isAgreementAccepted() && !w.isProductTourDone() && w.isUsable());
        out.put("kind",     isDemo ? "demo" : "trial");
        out.put("endDate",  w.getEndDate() == null ? "" : w.getEndDate().toString());
        out.put("completed", w.isProductTourDone());
        return ResponseEntity.ok(out);
    }

    /**
     * Records that the tour is done, whether the person finished it or skipped it.
     *
     * <p>Stored on the {@code demo_role_access} row, like the agreement, so it is
     * once per LOGIN rather than once per session: closing the browser and signing
     * in again does not replay it. Answers OK for a non-demo session too, so the
     * script never has to special-case a normal church.
     */
    @PostMapping("/api/demo/product-tour/complete")
    public ResponseEntity<Map<String, Object>> completeProductTour(HttpServletRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        HttpSession session = request.getSession(false);

        Optional<DemoRoleAccess> window = windowFor(session);
        if (window.isEmpty()) {
            out.put("demo", false);
            out.put("completed", true);
            return ResponseEntity.ok(out);
        }

        DemoRoleAccess w = demoAccess.completeProductTour(window.get().getSignupId())
                                     .orElse(window.get());
        LOG.info("Product tour completed — username='{}' tenant={}", w.getUsername(), w.getClientId());

        out.put("demo", true);
        out.put("completed", true);
        return ResponseEntity.ok(out);
    }

    /* ── helpers ────────────────────────────────────────────────────────── */

    /**
     * The demo window behind this session, if it has one.
     *
     * <p>Prefers the signup id the sign-in path recorded, and falls back to the
     * username so sessions built by remember-me or account switching resolve too.
     */
    private Optional<DemoRoleAccess> windowFor(HttpSession session) {
        if (session == null) return Optional.empty();
        try {
            Object signupId = session.getAttribute(DemoTrialAgreementFilter.ATTR_SIGNUP_ID);
            if (signupId instanceof Integer id) {
                Optional<DemoRoleAccess> byId = demoAccess.forSignup(id);
                if (byId.isPresent()) return byId;
            }
            Object username = session.getAttribute("username");
            return demoAccess.forUsername(username == null ? null : String.valueOf(username));
        } catch (Exception e) {
            LOG.warn("Demo trial agreement lookup failed — {}", e.getMessage());
            return Optional.empty();
        }
    }
}
