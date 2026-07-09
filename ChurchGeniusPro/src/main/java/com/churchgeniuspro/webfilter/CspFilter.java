package com.churchgeniuspro.webfilter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
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
            "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://cdnjs.cloudflare.com https://js.stripe.com https://cdn.jsdelivr.net https://unpkg.com https://cdn.plaid.com; " +
            "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com https://cdnjs.cloudflare.com; " +
            "font-src 'self' https://fonts.gstatic.com; " +
            "img-src 'self' data: blob: https://ssl.gstatic.com; " +
            // Plaid Link exchanges data with *.plaid.com while a bank connection is in progress.
            "connect-src 'self' https://api.stripe.com https://cdnjs.cloudflare.com https://cdn.jsdelivr.net https://unpkg.com https://tessdata.projectnaptha.com https://cdn.plaid.com https://production.plaid.com https://sandbox.plaid.com; " +
            // Plaid Link renders its bank-login flow inside an iframe hosted on cdn.plaid.com.
            "frame-src 'self' https://js.stripe.com https://cdn.plaid.com; " +
            "worker-src 'self' blob:; " +
            "object-src 'none';";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (response instanceof HttpServletResponse httpResp) {
            // Set our policy; overwrites any header nginx may have already set.
            httpResp.setHeader("Content-Security-Policy", CSP_VALUE);
        }
        chain.doFilter(request, response);
    }
}
