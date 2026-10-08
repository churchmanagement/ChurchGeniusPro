package com.churchgeniuspro.payroll.controller;

import com.churchgeniuspro.payroll.service.SsnRotationService;
import com.churchgeniuspro.webfilter.CsrfOriginFilter;
import com.churchgeniuspro.webfilter.ServiceAdminAuthFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Security audit 2026-10-07, Phase 5. The endpoint sits behind the same three checks as
 * the Plaid rotation endpoint: ServiceAdminAuthFilter (session serviceAdminId),
 * CsrfOriginFilter (Origin/Referer of the POST), and the controller's own role check.
 * Each layer is exercised here with the real filters; no new public exemption exists.
 */
@DisplayName("POST/GET /api/serviceadmin/payroll/ssn-rotation — auth + CSRF")
class ServiceAdminPayrollControllerTest {

    static final String URI = "/api/serviceadmin/payroll/ssn-rotation";
    static final String BASE = "https://churchgeniuspro.net";

    final SsnRotationService service = mock(SsnRotationService.class);
    final ServiceAdminPayrollController controller = new ServiceAdminPayrollController(service);
    final ServiceAdminAuthFilter authFilter = new ServiceAdminAuthFilter();
    final CsrfOriginFilter csrfFilter = new CsrfOriginFilter(BASE);

    private MockHttpServletRequest request(String method, MockHttpSession session, String origin) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, URI);
        req.setRequestURI(URI); req.setServerName("churchgeniuspro.net");
        if (session != null) req.setSession(session);
        if (origin != null) req.addHeader("Origin", origin);
        return req;
    }

    /** Runs the request through both filters, then the controller if they let it through. */
    private int run(String method, MockHttpSession session, String origin) throws Exception {
        MockHttpServletRequest req = request(method, session, origin);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        csrfFilter.doFilter(req, res, chain);
        if (chain.getRequest() == null) return res.getStatus();
        chain = new MockFilterChain();
        authFilter.doFilter(req, res, chain);
        if (chain.getRequest() == null) return res.getStatus();
        return "POST".equals(method) ? controller.rotate(req).getStatusCode().value()
                                     : controller.rotationStatus(req).getStatusCode().value();
    }

    private static MockHttpSession serviceAdmin() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("serviceAdminId", 1); s.setAttribute("role", "ServiceAdmin"); s.setAttribute("username", "admin");
        return s;
    }

    @Test @DisplayName("unauthenticated → 401 (ServiceAdminAuthFilter), service never called")
    void unauthenticated() throws Exception {
        assertThat(run("POST", null, BASE)).isEqualTo(401);
        assertThat(run("GET", null, null)).isEqualTo(401);
        verifyNoInteractions(service);
    }

    @Test @DisplayName("authenticated church user — even with role string 'ServiceAdmin' — → 401")
    void tenantUser() throws Exception {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("clientId", "CHR-x"); s.setAttribute("username", "owner"); s.setAttribute("role", "ServiceAdmin");
        assertThat(run("POST", s, BASE)).isEqualTo(401);
        assertThat(run("GET", s, null)).isEqualTo(401);
        verifyNoInteractions(service);
    }

    @Test @DisplayName("service admin session but cross-site Origin → 403 (CsrfOriginFilter) before the service")
    void serviceAdminWithoutCsrf() throws Exception {
        assertThat(run("POST", serviceAdmin(), "https://evil.example")).isEqualTo(403);
        verifyNoInteractions(service);
    }

    @Test @DisplayName("controller's own role check: serviceAdminId without role ServiceAdmin → 401")
    void roleCheckedAgain() throws Exception {
        MockHttpSession s = new MockHttpSession(); s.setAttribute("serviceAdminId", 1);
        assertThat(run("GET", s, null)).isEqualTo(401);
        verifyNoInteractions(service);
    }

    @Test @DisplayName("valid service admin session + same-site Origin → 200; actor passed; no ids in response")
    void validRequest() throws Exception {
        when(service.rotate(false, "admin")).thenReturn(Map.of("status", "success", "rotated", 2, "pending", 0));
        when(service.status()).thenReturn(Map.of("status", "success", "wouldRotate", 2));
        assertThat(run("POST", serviceAdmin(), BASE)).isEqualTo(200);
        verify(service).rotate(false, "admin");
        assertThat(run("GET", serviceAdmin(), null)).isEqualTo(200);   // GET needs no Origin (not state-changing)
        verify(service).status();
    }

    @Test @DisplayName("the endpoint is not in any public/exempt list")
    void notExempted() throws Exception {
        String auth = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/churchgeniuspro/webfilter/AuthFilter.java"));
        String csrf = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/churchgeniuspro/webfilter/CsrfOriginFilter.java"));
        String sa = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/churchgeniuspro/webfilter/ServiceAdminAuthFilter.java"));
        assertThat(auth).doesNotContain("ssn-rotation").doesNotContain("/api/serviceadmin/payroll");
        assertThat(csrf).doesNotContain("ssn-rotation").doesNotContain("/api/serviceadmin/payroll");
        assertThat(sa).doesNotContain("ssn-rotation").doesNotContain("/api/serviceadmin/payroll");
    }
}
