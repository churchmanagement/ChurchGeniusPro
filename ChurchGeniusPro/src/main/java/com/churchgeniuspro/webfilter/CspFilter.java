package com.churchgeniuspro.webfilter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Adds a Content-Security-Policy header to every response.
 *
 * <p>'unsafe-eval' is included in script-src because some of our pages use
 * patterns that the browser classifies as dynamic code evaluation (e.g. the
 * QR-code library, chart renderers, or template-literal-based innerHTML that
 * the browser's CSP heuristic flags as eval-like).  This is intentional and
 * matches the same trust level as 'unsafe-inline', which most CSP deployments
 * of single-page apps already carry.
 *
 * <p>This filter runs at order -1 (before all other custom filters) and maps
 * to every URL so that even static-resource responses carry the header.
 */
public class CspFilter implements Filter {

    private static final String CSP_VALUE =
            "default-src 'self'; " +
            // jsdelivr / unpkg host the in-browser OCR engine (Tesseract.js) used by the
            // check & bank-statement scanners; tessdata.projectnaptha.com hosts its language data.
            // cdn.plaid.com serves the Plaid Link SDK for the Bank Sync feature.
            // www.google.com + www.gstatic.com serve the reCAPTCHA widget used by the
            // public forms (Connect With Us, Public Prayer, Trial Registration). Without
            // them the widget script is blocked and the captcha silently never renders.
            "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://cdnjs.cloudflare.com https://js.stripe.com https://cdn.jsdelivr.net https://unpkg.com https://cdn.plaid.com https://www.google.com https://www.gstatic.com; " +
            "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com https://cdnjs.cloudflare.com; " +
            "font-src 'self' https://fonts.gstatic.com; " +
            "img-src 'self' data: blob: https://ssl.gstatic.com; " +
            // Plaid Link exchanges data with *.plaid.com while a bank connection is in progress.
            "connect-src 'self' https://api.stripe.com https://cdnjs.cloudflare.com https://cdn.jsdelivr.net https://unpkg.com https://tessdata.projectnaptha.com https://cdn.plaid.com https://production.plaid.com https://sandbox.plaid.com; " +
            // Plaid Link renders its bank-login flow inside an iframe hosted on cdn.plaid.com.
            // reCAPTCHA renders its challenge in an iframe on www.google.com.
            "frame-src 'self' https://js.stripe.com https://cdn.plaid.com https://www.google.com; " +
            "worker-src 'self' blob:; " +
            "object-src 'none';";

    /**
     * Pages a church may legitimately place inside an iframe on its own website —
     * the public screens. Everything else (the application, where a signed-in user
     * can be tricked into clicking) refuses to be framed by another site.
     */
    static final String[] EMBEDDABLE_PREFIXES = {
            "/donate/", "/viewEventCalendar", "/connect", "/publicPrayer", "/membershipForm",
            "/memberSignup", "/smsOptIn", "/guessIt", "/kidsCheckin", "/kidsPickup",
            "/event-register/", "/event-checkin/", "/ntagLanding", "/upcomingEvents", "/songbook/view",
            "/pub/", "/web", "/unsubscribe"
    };

    /** Segment match: {@code /connect} and {@code /connect/…} are embeddable, {@code /connectAdmin} is not. */
    static boolean embeddable(String path) {
        if (path == null) return false;
        for (String p : EMBEDDABLE_PREFIXES) {
            String bare = p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
            if (path.equals(bare) || path.startsWith(bare + "/")) return true;
        }
        return false;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (response instanceof HttpServletResponse httpResp && request instanceof HttpServletRequest httpReq) {
            String path = RequestPaths.path(httpReq);
            boolean embeddable = embeddable(path);
            // Set our policy; overwrites any header nginx may have already set. The
            // application pages also refuse to be framed by other sites (clickjacking);
            // the public screens stay embeddable so a church can iframe them on its
            // own website (security audit P9).
            httpResp.setHeader("Content-Security-Policy",
                    embeddable ? CSP_VALUE : CSP_VALUE + " frame-ancestors 'self';");
            if (!embeddable) httpResp.setHeader("X-Frame-Options", "SAMEORIGIN");
            httpResp.setHeader("X-Content-Type-Options", "nosniff");
            httpResp.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
            if (httpReq.isSecure() || "https".equalsIgnoreCase(httpReq.getHeader("X-Forwarded-Proto"))) {
                httpResp.setHeader("Strict-Transport-Security", "max-age=31536000");
            }
        }
        chain.doFilter(request, response);
    }
}
