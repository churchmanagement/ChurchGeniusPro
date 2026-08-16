package com.churchgeniuspro.webfilter;

import com.churchgeniuspro.service.SubscriptionService;
import com.churchgeniuspro.util.SubscriptionFeatureCatalog;
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
 * Enforces subscription-plan feature flags on every request (modeled on
 * {@link PrivatePageFilter}). Flow:
 *
 * <ol>
 *   <li>Map the request path to a feature key via
 *       {@link SubscriptionFeatureCatalog#keyForPath} — unknown paths pass.</li>
 *   <li>Resolve the org clientId from the session (church accounts store it
 *       as {@code clientId}, staff/members as {@code appClientId}) — requests
 *       without a session/org (public token pages, login) pass; the Service
 *       Admin always passes.</li>
 *   <li>Ask {@link SubscriptionService#isFeatureEnabled}; enabled → pass.</li>
 *   <li>Disabled → {@code /api/*} gets a 403 JSON body
 *       ({@code code:"SUBSCRIPTION_FEATURE_DISABLED"}); pages get a small
 *       HTML notice.</li>
 * </ol>
 *
 * <p>Fail-open on any exception so a subscription lookup problem can never
 * take the application down.
 */
public class SubscriptionFeatureFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionFeatureFilter.class);

    private final SubscriptionService subscriptionService;

    public SubscriptionFeatureFilter(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest  request  = (HttpServletRequest)  req;
        HttpServletResponse response = (HttpServletResponse) res;
        try {
            String featureKey = SubscriptionFeatureCatalog.keyForPath(request.getRequestURI());
            if (featureKey == null) { chain.doFilter(req, res); return; }

            HttpSession session = request.getSession(false);
            if (session == null) { chain.doFilter(req, res); return; }          // anonymous → pass
            if (session.getAttribute("serviceAdminId") != null) {               // service admin → pass
                chain.doFilter(req, res); return;
            }

            String clientId = resolveClientId(session);
            if (clientId == null) { chain.doFilter(req, res); return; }         // no org context → pass

            if (subscriptionService.isFeatureEnabled(clientId, featureKey)) {
                chain.doFilter(req, res); return;
            }

            log.info("Subscription block: clientId={} feature={} path={}",
                    clientId, featureKey, request.getRequestURI());
            writeBlocked(request, response, featureKey);
        } catch (Exception e) {
            log.warn("SubscriptionFeatureFilter failed open — {}", e.getMessage());
            chain.doFilter(req, res);
        }
    }

    /** Church accounts store the org id as "clientId"; staff/members as "appClientId". */
    private String resolveClientId(HttpSession session) {
        Object app = session.getAttribute("appClientId");
        if (app instanceof String s && !s.isBlank()) return s;
        Object cid = session.getAttribute("clientId");
        if (cid instanceof String s && !s.isBlank()) return s;
        return null;
    }

    private void writeBlocked(HttpServletRequest request, HttpServletResponse response,
                              String featureKey) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        if (request.getRequestURI().startsWith("/api/")) {
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"This feature is not included in your church's "
                    + "subscription plan. Please contact your administrator about upgrading.\","
                    + "\"code\":\"SUBSCRIPTION_FEATURE_DISABLED\",\"feature\":\"" + featureKey + "\"}");
        } else {
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write("<!DOCTYPE html><html><head><title>Feature Not Available</title></head>"
                    + "<body style='font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:#f5f6fa;"
                    + "display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0;'>"
                    + "<div style='background:#fff;border-radius:14px;padding:40px;max-width:440px;text-align:center;"
                    + "box-shadow:0 4px 24px rgba(0,0,0,.08);'>"
                    + "<div style='font-size:44px;margin-bottom:12px;'>🔒</div>"
                    + "<h2 style='color:#673147;margin:0 0 10px;'>Feature Not Available</h2>"
                    + "<p style='color:#555;font-size:14px;line-height:1.7;'>This feature is not included in your "
                    + "church's current subscription plan.<br/>Please contact your administrator about upgrading.</p>"
                    + "<a href='/home' style='display:inline-block;margin-top:18px;background:#673147;color:#fff;"
                    + "padding:10px 26px;border-radius:8px;text-decoration:none;font-weight:600;'>Return Home</a>"
                    + "</div></body></html>");
        }
    }
}
