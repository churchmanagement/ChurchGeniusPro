package com.churchgeniuspro.webfilter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import java.io.IOException;

/**
 * Servlet filter registered for all {@code /api/*} paths (order 2, runs just
 * after {@link AuthFilter}).
 *
 * <p>Session details (clientId, churchName, subscription, role, username) are
 * logged <em>once</em> at login time by
 * {@link com.churchgeniuspro.controller.LoginController} — not repeated on
 * every request here.  This filter simply passes the request down the chain.
 */
public class LoginFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request,
                         ServletResponse response,
                         FilterChain chain)
            throws IOException, ServletException {
        chain.doFilter(request, response);
    }
}
