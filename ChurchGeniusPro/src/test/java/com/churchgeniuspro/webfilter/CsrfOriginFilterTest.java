package com.churchgeniuspro.webfilter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production-readiness audit 2026-10-07, Phase 4.5: which Origin hosts the CSRF
 * origin check accepts, and that the client-controlled {@code X-Forwarded-Host}
 * header can no longer vouch for an Origin.
 */
@DisplayName("CsrfOriginFilter — allowed origin hosts")
class CsrfOriginFilterTest {

    static final String PROD = "https://churchgeniuspro.net";

    /** 200 = passed to the chain, otherwise the filter's own status. */
    private static int post(String baseUrl, String serverName, String origin, String forwardedHost) throws Exception {
        CsrfOriginFilter filter = new CsrfOriginFilter(baseUrl);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/users");
        req.setRequestURI("/api/users");
        req.setServerName(serverName);
        if (origin != null) req.addHeader("Origin", origin);
        if (forwardedHost != null) req.addHeader("X-Forwarded-Host", forwardedHost);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        return chain.getRequest() != null ? 200 : res.getStatus();
    }

    @Test @DisplayName("production origin passes (matches app.base-url and the request Host)")
    void productionOrigin() throws Exception {
        assertThat(post(PROD, "churchgeniuspro.net", PROD, null)).isEqualTo(200);
        assertThat(post(PROD, "churchgeniuspro.net", "https://ChurchGeniusPro.net", null)).isEqualTo(200);
    }

    @Test @DisplayName("origin matching the request's own Host passes even when base-url differs")
    void requestHostOrigin() throws Exception {
        // Azure forwards the client's Host unchanged; a staging slot host must not refuse itself.
        assertThat(post(PROD, "cgp-staging.azurewebsites.net", "https://cgp-staging.azurewebsites.net", null)).isEqualTo(200);
    }

    @Test @DisplayName("localhost and 127.0.0.1 pass only when app.base-url is itself local")
    void localhostOrigins() throws Exception {
        assertThat(post("http://localhost:8080", "localhost", "http://localhost:8080", null)).isEqualTo(200);
        assertThat(post("http://localhost:8080", "localhost", "http://127.0.0.1:8080", null)).isEqualTo(200);
        assertThat(post("http://127.0.0.1:8080", "127.0.0.1", "http://localhost:8080", null)).isEqualTo(200);
        assertThat(post(null, "localhost", "http://localhost:8080", null)).isEqualTo(200);   // no base-url configured
        // …but not against a production deployment.
        assertThat(post(PROD, "churchgeniuspro.net", "http://localhost:8080", null)).isEqualTo(403);
        assertThat(post(PROD, "churchgeniuspro.net", "http://127.0.0.1:8080", null)).isEqualTo(403);
    }

    @Test @DisplayName("a forwarded host matching the Origin no longer vouches for it")
    void forwardedHostIsNotTrusted() throws Exception {
        // Previously: Origin host == X-Forwarded-Host → allowed. Now the request Host decides.
        assertThat(post(PROD, "churchgeniuspro.net", "https://other.example", "other.example")).isEqualTo(403);
    }

    @Test @DisplayName("spoofed X-Forwarded-Host cannot smuggle a cross-site origin through")
    void spoofedForwardedHost() throws Exception {
        assertThat(post(PROD, "churchgeniuspro.net", "https://evil.example", "evil.example")).isEqualTo(403);
        assertThat(post(PROD, "churchgeniuspro.net", "https://evil.example", "evil.example, churchgeniuspro.net")).isEqualTo(403);
        assertThat(post(PROD, "churchgeniuspro.net", "https://evil.example", "churchgeniuspro.net")).isEqualTo(403);
    }

    @Test @DisplayName("a genuine same-site request still passes with the forwarded header present")
    void forwardedHeaderPresentOnGenuineRequest() throws Exception {
        assertThat(post(PROD, "churchgeniuspro.net", PROD, "churchgeniuspro.net")).isEqualTo(200);
    }
}
