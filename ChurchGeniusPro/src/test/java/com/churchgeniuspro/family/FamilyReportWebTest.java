package com.churchgeniuspro.family;

import com.churchgeniuspro.controller.FamilyReportController;
import com.churchgeniuspro.service.FamilyReportService;
import com.churchgeniuspro.service.FamilyReportService.Criteria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GET /api/family-report} and the {@code /familySearchReport} page: the gate is the
 * Families page's (SuperAdmin / Admin / Accountant with {@code admin.family}; member-portal
 * logins need the member permission), the church is always the session's — no parameter
 * can choose another one — and member-portal viewers get private fields masked.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Family & Member Report — access and tenant binding")
class FamilyReportWebTest {

    private static final String OURS = "CHR-ours";

    @Mock FamilyReportService service;

    FamilyReportController controller;

    @BeforeEach
    void setUp() {
        controller = new FamilyReportController(service);
        when(service.run(any(), anyString(), anyBoolean())).thenReturn(Map.of("rows", java.util.List.of(), "total", 0));
    }

    private static MockHttpServletRequest sessionOf(String clientId, String role) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("appClientId", clientId);
        s.setAttribute("clientId", clientId);
        s.setAttribute("username", role.toLowerCase() + "@" + clientId);
        s.setAttribute("role", role);
        req.setSession(s);
        return req;
    }

    private static MockHttpServletRequest memberOf(String clientId, String privileges) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("appClientId", clientId);
        s.setAttribute("role", "Member");
        s.setAttribute("memberId", 42);
        if (privileges != null) s.setAttribute("memberPrivileges", privileges);
        req.setSession(s);
        return req;
    }

    private ResponseEntity<Map<String, Object>> call(MockHttpServletRequest req) {
        return call(req, "members", null, null);
    }

    private ResponseEntity<Map<String, Object>> call(MockHttpServletRequest req, String type, Integer bm, Integer am) {
        return controller.report(type, bm, am, "", "", "", "", "", "", "", false, false, req);
    }

    // ── API gate ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SuperAdmin, Admin and Accountant may run it; User, Church and no session may not")
    void roleGate() {
        assertThat(call(sessionOf(OURS, "SuperAdmin")).getStatusCode().value()).isEqualTo(200);
        assertThat(call(sessionOf(OURS, "Admin")).getStatusCode().value()).isEqualTo(200);
        assertThat(call(sessionOf(OURS, "Accountant")).getStatusCode().value()).isEqualTo(200);
        assertThat(call(sessionOf(OURS, "User")).getStatusCode().value()).isEqualTo(403);

        MockHttpServletRequest church = sessionOf(OURS, "Church");
        church.getSession().setAttribute("church", true);
        assertThat(call(church).getStatusCode().value()).isEqualTo(403);

        assertThat(call(new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(401);
        verify(service, never()).run(any(), eq("CHR-nobody"), anyBoolean());
    }

    @Test
    @DisplayName("A staff record with admin.family switched off is refused, like the Families page")
    void permissionGate() {
        MockHttpServletRequest admin = sessionOf(OURS, "Admin");
        admin.getSession().setAttribute("privileges", "{\"admin.family\":false}");
        assertThat(call(admin).getStatusCode().value()).isEqualTo(403);
        admin.getSession().setAttribute("privileges", "{\"admin.family\":true}");
        assertThat(call(admin).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("Member-portal logins need admin.family; their results are masked")
    void memberPortal() {
        assertThat(call(memberOf(OURS, "{\"admin.family\":false}")).getStatusCode().value()).isEqualTo(403);
        verify(service, never()).run(any(), anyString(), anyBoolean());

        assertThat(call(memberOf(OURS, "{\"admin.family\":true}")).getStatusCode().value()).isEqualTo(200);
        verify(service).run(any(), eq(OURS), eq(true));        // masked
    }

    @Test
    @DisplayName("Staff results are not masked and the church is the session's")
    void staffUnmasked() {
        call(sessionOf(OURS, "Admin"));
        verify(service).run(any(), eq(OURS), eq(false));
    }

    // ── Tenant binding ───────────────────────────────────────────────────────

    @Test
    @DisplayName("The church comes from the session only — request parameters cannot pick another one")
    void tenantFromSessionOnly() {
        MockHttpServletRequest req = sessionOf(OURS, "Admin");
        req.setParameter("appClientId", "CHR-theirs");
        req.setParameter("clientId", "CHR-theirs");
        call(req);
        ArgumentCaptor<String> client = ArgumentCaptor.forClass(String.class);
        verify(service).run(any(), client.capture(), anyBoolean());
        assertThat(client.getValue()).isEqualTo(OURS);
    }

    @Test
    @DisplayName("A session without a client id is refused (never an unscoped report)")
    void noClientId() {
        MockHttpServletRequest req = sessionOf(OURS, "Admin");
        req.getSession().removeAttribute("appClientId");
        req.getSession().removeAttribute("clientId");
        assertThat(call(req).getStatusCode().value()).isEqualTo(401);
        verify(service, never()).run(any(), any(), anyBoolean());
    }

    // ── Validation ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Bad report type, month or gender → 400; criteria are passed through otherwise")
    void validation() {
        MockHttpServletRequest req = sessionOf(OURS, "Admin");
        assertThat(call(req, "people", null, null).getStatusCode().value()).isEqualTo(400);
        assertThat(call(req, "members", 13, null).getStatusCode().value()).isEqualTo(400);
        assertThat(call(req, "families", null, 0).getStatusCode().value()).isEqualTo(200);   // 0 = not applied
        assertThat(controller.report("members", null, null, "", "", "", "Other", "", "", "", false, false, req)
                .getStatusCode().value()).isEqualTo(400);
        assertThat(controller.report("members", null, null, "x".repeat(101), "", "", "", "", "", "", false, false, req)
                .getStatusCode().value()).isEqualTo(400);

        controller.report("Families", 3, 6, "Olathe", "16", "66062", "Female", "Spouse", "nonmember", "Ada", true, true, req);
        ArgumentCaptor<Criteria> cap = ArgumentCaptor.forClass(Criteria.class);
        verify(service, org.mockito.Mockito.times(2)).run(cap.capture(), eq(OURS), eq(false));   // the valid "families" call above + this one
        Criteria c = cap.getValue();
        assertThat(c.reportType()).isEqualTo("families");
        assertThat(c.birthdayMonth()).isEqualTo(3);
        assertThat(c.anniversaryMonth()).isEqualTo(6);
        assertThat(c.city()).isEqualTo("Olathe");
        assertThat(c.state()).isEqualTo("16");
        assertThat(c.zip()).isEqualTo("66062");
        assertThat(c.gender()).isEqualTo("Female");
        assertThat(c.role()).isEqualTo("Spouse");
        assertThat(c.memberType()).isEqualTo("nonmember");
        assertThat(c.name()).isEqualTo("Ada");
        assertThat(c.showDeleted()).isTrue();
        assertThat(c.showInactive()).isTrue();
    }

    // ── Page route ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("/familySearchReport is gated like /viewfamily")
    void pageRoute() {
        assertThat(controller.familySearchReportPage(sessionOf(OURS, "Admin"))).isEqualTo("forward:/familySearchReport.html");
        assertThat(controller.familySearchReportPage(sessionOf(OURS, "Accountant"))).isEqualTo("forward:/familySearchReport.html");
        assertThat(controller.familySearchReportPage(sessionOf(OURS, "User"))).isEqualTo("forward:/access-denied.html");
        assertThat(controller.familySearchReportPage(new MockHttpServletRequest())).isEqualTo("redirect:/login");
        assertThat(controller.familySearchReportPage(memberOf(OURS, "{\"admin.family\":true}"))).isEqualTo("forward:/familySearchReport.html");
        assertThat(controller.familySearchReportPage(memberOf(OURS, "{\"admin.family\":false}"))).isEqualTo("forward:/access-denied.html");
        MockHttpServletRequest off = sessionOf(OURS, "Admin");
        off.getSession().setAttribute("privileges", "{\"admin.family\":false}");
        assertThat(controller.familySearchReportPage(off)).isEqualTo("forward:/access-denied.html");
    }
}
