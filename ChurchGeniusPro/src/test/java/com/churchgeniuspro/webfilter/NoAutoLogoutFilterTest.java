package com.churchgeniuspro.webfilter;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Configuration audit 2026-10-28, H1: "never expire" is an explicit opt-in. Before this a staff
 * session with no privileges JSON, an unparsable one, or one without the key — and every
 * Service Admin session — was set to -1 and lived until an explicit logout.
 */
@DisplayName("NoAutoLogoutFilter — idle timeout defaults")
class NoAutoLogoutFilterTest {

    private static int timeoutAfterRequest(String role, String privileges) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("role", role);
        if (privileges != null) session.setAttribute("privileges", privileges);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/session");
        req.setSession(session);
        new NoAutoLogoutFilter().doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        return session.getMaxInactiveInterval();
    }

    @Test @DisplayName("staff with no privileges, empty, null or unparsable privileges → 30-minute idle timeout")
    void staffDefaultsToAutoLogout() throws Exception {
        assertThat(timeoutAfterRequest("SuperAdmin", null)).isEqualTo(NoAutoLogoutFilter.DEFAULT_TIMEOUT_SECONDS);
        assertThat(timeoutAfterRequest("Admin", "")).isEqualTo(NoAutoLogoutFilter.DEFAULT_TIMEOUT_SECONDS);
        assertThat(timeoutAfterRequest("Admin", "null")).isEqualTo(NoAutoLogoutFilter.DEFAULT_TIMEOUT_SECONDS);
        assertThat(timeoutAfterRequest("Accountant", "{\"admin.family\":true}")).isEqualTo(NoAutoLogoutFilter.DEFAULT_TIMEOUT_SECONDS);
        assertThat(timeoutAfterRequest("User", "{not json")).isEqualTo(NoAutoLogoutFilter.DEFAULT_TIMEOUT_SECONDS);
        assertThat(timeoutAfterRequest("Admin", "{\"_noAutoLogout\":false}")).isEqualTo(NoAutoLogoutFilter.DEFAULT_TIMEOUT_SECONDS);
    }

    @Test @DisplayName("staff who explicitly opted in (_noAutoLogout:true) keep a never-expiring session")
    void explicitOptInStillHonoured() throws Exception {
        assertThat(timeoutAfterRequest("Admin", "{\"_noAutoLogout\":true}")).isEqualTo(-1);
        assertThat(timeoutAfterRequest("Admin", "{\"_noAutoLogout\":\"true\"}")).isEqualTo(-1);
    }

    @Test @DisplayName("Service Admin sessions always get the fixed 60-minute idle timeout, even with an opt-in")
    void serviceAdminCapped() throws Exception {
        assertThat(timeoutAfterRequest("ServiceAdmin", null)).isEqualTo(NoAutoLogoutFilter.SERVICE_ADMIN_TIMEOUT_SECONDS);
        assertThat(timeoutAfterRequest("ServiceAdmin", "{\"_noAutoLogout\":true}")).isEqualTo(NoAutoLogoutFilter.SERVICE_ADMIN_TIMEOUT_SECONDS);
    }

    @Test @DisplayName("a request without a session passes through untouched")
    void noSession() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/session");
        MockFilterChain chain = new MockFilterChain();
        new NoAutoLogoutFilter().doFilter(req, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
        HttpSession s = req.getSession(false);
        assertThat(s).isNull();
    }
}
