package com.churchgeniuspro.trialemail;

import com.churchgeniuspro.controller.TrialTestEmailController;
import com.churchgeniuspro.service.TrialTestEmailService;
import com.churchgeniuspro.util.PublicSendLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Who may configure the test address, and whose tenant it is. */
@DisplayName("Trial/Demo test email — authorization and tenant isolation")
class TrialTestEmailControllerTest {

    TrialTestEmailService service = mock(TrialTestEmailService.class);
    TrialTestEmailController controller;

    @BeforeEach
    void setUp() throws Exception {
        controller = new TrialTestEmailController(service, new PublicSendLimiter());
        when(service.status(anyString())).thenAnswer(i -> Map.<String, Object>of("eligible", true, "tenant", i.getArgument(0)));
        when(service.requestCode(anyString(), any(), any(), any())).thenAnswer(i -> Map.<String, Object>of("tenant", i.getArgument(0)));
        when(service.verify(anyString(), any(), any())).thenAnswer(i -> Map.<String, Object>of("tenant", i.getArgument(0)));
    }

    static MockHttpServletRequest session(String role, String clientId, boolean member) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "someone@" + clientId);
        s.setAttribute("role", role);
        s.setAttribute("appClientId", clientId);
        if (member) s.setAttribute("memberId", 77);
        req.setSession(s);
        return req;
    }

    @Test @DisplayName("SuperAdmin and Admin of the tenant may read and configure it")
    void adminsAllowed() {
        for (String role : List.of("SuperAdmin", "Admin")) {
            assertThat(controller.status(session(role, "TRIAL-1", false)).getStatusCode().value()).isEqualTo(200);
            assertThat(controller.request(Map.of("email", "t@x.org"), session(role, "TRIAL-1", false)).getStatusCode().value()).isEqualTo(200);
            assertThat(controller.verify(Map.of("code", "123456"), session(role, "TRIAL-1", false)).getStatusCode().value()).isEqualTo(200);
        }
    }

    @ParameterizedTest(name = "{0} is refused")
    @ValueSource(strings = {"Member", "Child", "Limited", "User", "Accountant"})
    @DisplayName("18. other roles cannot configure it")
    void othersRefused(String role) throws Exception {
        boolean member = role.equals("Member") || role.equals("Child");
        assertThat(controller.status(session(role, "TRIAL-1", member)).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.request(Map.of("email", "t@x.org"), session(role, "TRIAL-1", member)).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.verify(Map.of("code", "123456"), session(role, "TRIAL-1", member)).getStatusCode().value()).isEqualTo(403);
        verify(service, never()).requestCode(anyString(), any(), any(), any());
        verify(service, never()).verify(anyString(), any(), any());
    }

    @Test @DisplayName("no session → refused")
    void noSession() {
        assertThat(controller.status(new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(403);
    }

    @Test @DisplayName("19. the tenant is always the session's — a clientId in the body is ignored")
    void tenantFromSessionOnly() throws Exception {
        ResponseEntity<?> res = controller.request(Map.of("email", "t@x.org", "clientId", "TRIAL-OTHER"),
                session("Admin", "TRIAL-MINE", false));
        assertThat(((Map<?, ?>) res.getBody()).get("tenant")).isEqualTo("TRIAL-MINE");
        verify(service).requestCode(eq("TRIAL-MINE"), eq("t@x.org"), any(), eq("someone@TRIAL-MINE"));

        controller.verify(Map.of("code", "123456", "clientId", "TRIAL-OTHER"), session("Admin", "TRIAL-MINE", false));
        verify(service).verify(eq("TRIAL-MINE"), eq("123456"), any());
        verify(service, never()).requestCode(eq("TRIAL-OTHER"), any(), any(), any());
        verify(service, never()).verify(eq("TRIAL-OTHER"), any(), any());
    }

    @Test @DisplayName("the repository exposes only a by-tenant finder")
    void repositoryIsTenantScoped() throws IOException {
        String src = Files.readString(Paths.get("src/main/java/com/churchgeniuspro/repository/TrialTestEmailRepository.java"));
        assertThat(src).contains("findByClientId(String clientId)");
        assertThat(src.split("\n")).filteredOn(l -> l.trim().matches("^(Optional|List|TrialTestEmail|void|long|boolean)\\b.*\\(.*;$")).hasSize(1);
    }

    @Test @DisplayName("the Email Settings page shows the card only when the server says the tenant is eligible")
    void pageGatedByServer() throws IOException {
        String page = Files.readString(Paths.get("src/main/resources/static/emailSettings.html"));
        assertThat(page).contains("id=\"testEmailCard\" style=\"display:none;\"",
                "if (!s || !s.eligible) { card.style.display = 'none'; return; }",
                "'/api/email-settings/test-email/request'", "'/api/email-settings/test-email/verify'");
    }
}
