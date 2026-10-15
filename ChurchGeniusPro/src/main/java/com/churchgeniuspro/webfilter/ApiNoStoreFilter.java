package com.churchgeniuspro.webfilter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * {@code Cache-Control: no-store} on every {@code /api/**} response (Phase 1, M7).
 *
 * <p>{@link com.churchgeniuspro.config.WebConfig} governs static files only; REST
 * responses carried no cache header at all, so a browser or WebView HTTP cache could
 * keep member, giving or payroll JSON after the user signed out. The header is set
 * BEFORE the chain runs, so a controller that deliberately sets its own
 * {@code Cache-Control} (long-lived images, for example) simply overwrites it.
 */
public class ApiNoStoreFilter implements Filter {

    public static final String HEADER = "Cache-Control";
    public static final String VALUE  = "no-store";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (response instanceof HttpServletResponse http) {
            http.setHeader(HEADER, VALUE);
        }
        chain.doFilter(request, response);
    }
}
