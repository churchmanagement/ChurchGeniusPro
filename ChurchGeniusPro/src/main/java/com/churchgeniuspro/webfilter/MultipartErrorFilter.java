package com.churchgeniuspro.webfilter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartException;

import java.io.IOException;

/**
 * Catches {@link MultipartException} (e.g. "Stream ended unexpectedly") at the
 * filter level, before it can bubble up to {@code GlobalExceptionHandler#handleAll}.
 *
 * <p>Spring's {@code DispatcherServlet.checkMultipart} throws this exception
 * before any handler is selected, so {@code @ExceptionHandler} methods in
 * {@code @RestControllerAdvice} classes are not guaranteed to intercept it.
 * Wrapping the filter chain here catches it reliably.
 *
 * <p>Registered at order {@code -2} so it wraps all other filters, including
 * {@link CspFilter} (order -1).
 */
public class MultipartErrorFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(MultipartErrorFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            chain.doFilter(request, response);
        } catch (MultipartException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            String msg = cause.getMessage() != null ? cause.getMessage() : ex.getMessage();
            log.warn("Upload stream cut short (client disconnected or file too large): {}", msg);

            if (response instanceof HttpServletResponse httpResp && !httpResp.isCommitted()) {
                httpResp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                httpResp.setContentType("application/json");
                httpResp.getWriter().write(
                    "{\"error\":\"Upload failed. The file may be too large or the connection was interrupted.\"}"
                );
            }
        }
    }
}
