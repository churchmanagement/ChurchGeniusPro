package com.churchgeniuspro.demo;

import com.churchgeniuspro.controller.DemoTrialAgreementController;
import com.churchgeniuspro.hibernate.DemoRoleAccess;
import com.churchgeniuspro.service.DemoAccessService;
import com.churchgeniuspro.service.PortalCredentialService;
import com.churchgeniuspro.service.TestDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The first-login agreement now also says what else the account contains.
 *
 * <p>It is the one screen a new trial administrator is guaranteed to read, and it
 * was the only place that could tell them their account is more than the page
 * behind it. The existing warnings are unchanged — the portals note is added to
 * them, not in place of them.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Trial first-login message")
class TrialFirstLoginMessageTest {

    private static final String TRIAL = TestDataService.TRIAL_CLIENT_PREFIX + "1757300000456";
    private static final String DEMO  = TestDataService.DEMO_CLIENT_PREFIX  + "1757300000123";

    @Mock DemoAccessService demoAccess;
    @Mock PortalCredentialService portals;

    private DemoTrialAgreementController controller;

    @BeforeEach
    void setUp() {
        controller = new DemoTrialAgreementController(demoAccess);
        controller.setPortalCredentials(portals);
        when(portals.portalsFor(anyString())).thenReturn(
                List.of(Map.of("key", "staff"), Map.of("key", "member"), Map.of("key", "child")));
    }

    private MockHttpServletRequest sessionOf(String tenant, String role) {
        DemoRoleAccess w = new DemoRoleAccess();
        w.setSignupId(7);
        w.setClientId(tenant);
        w.setUsername("ada.okoye_1757");
        w.setEndDate(LocalDate.now().plusDays(30));
        when(demoAccess.forUsername("ada.okoye_1757")).thenReturn(Optional.of(w));
        when(demoAccess.forSignup(7)).thenReturn(Optional.of(w));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/demo/trial-agreement");
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("appClientId", tenant);
        s.setAttribute("clientId", tenant);
        s.setAttribute("username", "ada.okoye_1757");
        s.setAttribute("role", role);
        s.setAttribute("demoTrialSignupId", 7);
        req.setSession(s);
        return req;
    }

    @Test
    @DisplayName("the existing trial warning is still there, word for word")
    void originalWarningPreserved() {
        String msg = String.valueOf(controller.status(sessionOf(TRIAL, "SuperAdmin")).getBody().get("message"));

        assertThat(msg).startsWith("This is a trial account, and some features "
                + "(such as SMS, Email, WhatsApp, Bank Sync, etc.) may not be available.");
        assertThat(msg).contains("please contact the support team");
    }

    @Test
    @DisplayName("...and it now names the Member and Kids portals, and where to find them")
    void mentionsThePortals() {
        Map<String, Object> body = controller.status(sessionOf(TRIAL, "SuperAdmin")).getBody();

        assertThat(body.get("portals")).isEqualTo(true);
        String msg = String.valueOf(body.get("message"));
        assertThat(msg).contains("Member Portal", "Kids Portal");
        assertThat(msg).contains("Your trial portals");   // the panel on the home page
    }

    @Test
    @DisplayName("a demo account is told the same, in its own words")
    void demoAccountKeepsItsOwnWording() {
        String msg = String.valueOf(controller.status(sessionOf(DEMO, "SuperAdmin")).getBody().get("message"));

        assertThat(msg).startsWith("This is a demonstration account");
        assertThat(msg).contains("Member Portal", "Kids Portal");
    }

    @Test
    @DisplayName("a Member Portal login is not sent to look for other portals' credentials")
    void portalLoginIsNotToldAboutTheOthers() {
        Map<String, Object> body = controller.status(sessionOf(TRIAL, "Member")).getBody();

        assertThat(body.get("portals")).isEqualTo(false);
        assertThat(String.valueOf(body.get("message"))).doesNotContain("Kids Portal");
    }

    @Test
    @DisplayName("a trial created before portals existed is not sent to an empty panel")
    void olderTrialGetsNoPortalsNote() {
        when(portals.portalsFor(anyString())).thenReturn(List.of(Map.of("key", "staff")));

        Map<String, Object> body = controller.status(sessionOf(TRIAL, "SuperAdmin")).getBody();

        assertThat(body.get("portals")).isEqualTo(false);
        assertThat(String.valueOf(body.get("message"))).doesNotContain("Kids Portal");
    }

    @Test
    @DisplayName("a non-demo session still has nothing to accept")
    void regularAccountUnaffected() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/demo/trial-agreement");
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("appClientId", "CHR-real");
        s.setAttribute("username", "pastor");
        req.setSession(s);

        assertThat(controller.status(req).getBody().get("demo")).isEqualTo(false);
    }
}
