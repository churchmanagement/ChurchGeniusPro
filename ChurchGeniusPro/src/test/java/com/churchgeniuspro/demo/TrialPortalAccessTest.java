package com.churchgeniuspro.demo;

import com.churchgeniuspro.controller.DemoTrialAgreementController;
import com.churchgeniuspro.controller.SessionController;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The three portals a trial account comes with, and who may see their sign-ins.
 *
 * <p>Provisioning generates a username and password for the Member and Kids
 * portals, but only the Service Admin console could read them — so the person
 * actually evaluating the product had no way into two thirds of their own
 * account. These pin the tenant-scoped view of those credentials, and the guards
 * that keep it to demo and trial tenants and to the staff inside them.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Trial portal access")
class TrialPortalAccessTest {

    private static final String TRIAL  = TestDataService.TRIAL_CLIENT_PREFIX + "1757300000456";
    private static final String CHURCH = "CHR-real-church";

    @Mock DemoAccessService demoAccess;

    private PortalCredentialService portals;

    @BeforeEach
    void setUp() {
        portals = new PortalCredentialService(demoAccess);
        when(demoAccess.allDemoLogins()).thenReturn(logins());
    }

    private static List<Map<String, Object>> logins() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(login(TRIAL, TestDataService.ROLE_CHURCH, "trial.church", "ChurchPass1", ""));
        rows.add(login(TRIAL, "SuperAdmin", "ada.okoye_1757", "StaffPass1", "Ada Okoye"));
        rows.add(login(TRIAL, TestDataService.ROLE_MEMBER_PORTAL, "member_grace_7", "MemberPass1", "Grace Hall"));
        rows.add(login(TRIAL, TestDataService.ROLE_CHILD_PORTAL, "child_lily_9", "KidPass1", "Lily Hall"));
        rows.add(login("TRIAL-other", "SuperAdmin", "someone.else", "Nope1", "Someone Else"));
        return rows;
    }

    private static Map<String, Object> login(String tenant, String role, String user,
                                             String pwd, String memberName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenant", tenant);
        m.put("role_label", role);
        m.put("username", user);
        m.put("demo_password", pwd);
        m.put("member_name", memberName);
        m.put("signup_id", 1);
        return m;
    }

    /* ── which portals are reported ─────────────────────────────────────── */

    @Nested
    @DisplayName("the portal list")
    class Listing {

        @Test
        @DisplayName("names the Staff, Member and Kids portals, in that order")
        void threePortalsInOrder() {
            List<Map<String, Object>> out = portals.portalsFor(TRIAL);

            assertThat(out).hasSize(3);
            assertThat(out).extracting(m -> m.get("key")).containsExactly("staff", "member", "child");
            assertThat(out).extracting(m -> m.get("label"))
                    .containsExactly("Staff Portal", "Member Portal", "Kids Portal");
        }

        @Test
        @DisplayName("carries the generated sign-in and who it signs in as")
        void credentialsAreCarried() {
            Map<String, Object> memberPortal = portals.portalsFor(TRIAL).get(1);

            assertThat(memberPortal.get("username")).isEqualTo("member_grace_7");
            assertThat(memberPortal.get("password")).isEqualTo("MemberPass1");
            assertThat(memberPortal.get("memberName")).isEqualTo("Grace Hall");
        }

        @Test
        @DisplayName("every portal explains what it is for")
        void everyPortalHasAPurpose() {
            for (Map<String, Object> p : portals.portalsFor(TRIAL)) {
                assertThat(String.valueOf(p.get("purpose"))).isNotBlank().hasSizeGreaterThan(30);
            }
        }

        @Test
        @DisplayName("another tenant's logins are never mixed in")
        void otherTenantsExcluded() {
            assertThat(portals.portalsFor(TRIAL))
                    .extracting(m -> m.get("username"))
                    .doesNotContain("someone.else");
        }

        @Test
        @DisplayName("a real church has no generated sign-ins to show")
        void realChurchGetsNothing() {
            assertThat(portals.portalsFor(CHURCH)).isEmpty();
            assertThat(portals.portalsFor(null)).isEmpty();
        }

        @Test
        @DisplayName("a tenant created before portals existed reports only its staff portal")
        void olderTenantReportsWhatItHas() {
            List<Map<String, Object>> onlyStaff = new ArrayList<>();
            onlyStaff.add(login(TRIAL, "SuperAdmin", "ada.okoye_1757", "StaffPass1", "Ada Okoye"));
            when(demoAccess.allDemoLogins()).thenReturn(onlyStaff);

            assertThat(portals.portalsFor(TRIAL)).hasSize(1);
        }

        @Test
        @DisplayName("a password that was never stored is reported as absent, not as blank")
        void missingPasswordIsNull() {
            List<Map<String, Object>> legacy = new ArrayList<>();
            legacy.add(login(TRIAL, "SuperAdmin", "ada.okoye_1757", "", "Ada Okoye"));
            when(demoAccess.allDemoLogins()).thenReturn(legacy);

            assertThat(portals.portalsFor(TRIAL).get(0).get("password")).isNull();
        }

        @Test
        @DisplayName("a lookup failure reports nothing rather than failing the page")
        void lookupFailureIsEmpty() {
            when(demoAccess.allDemoLogins()).thenThrow(new RuntimeException("db down"));
            assertThat(portals.portalsFor(TRIAL)).isEmpty();
        }
    }

    /* ── who may read them ──────────────────────────────────────────────── */

    @Nested
    @DisplayName("the endpoint guard")
    class Guard {

        private DemoTrialAgreementController controller;

        @BeforeEach
        void setUp() {
            controller = new DemoTrialAgreementController(demoAccess);
            controller.setPortalCredentials(portals);
        }

        private MockHttpServletRequest sessionOf(String tenant, String role, Boolean church) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/demo/portal-credentials");
            MockHttpSession s = new MockHttpSession();
            s.setAttribute("appClientId", tenant);
            s.setAttribute("clientId", tenant);
            s.setAttribute("username", "someone");
            s.setAttribute("role", role);
            if (church != null) s.setAttribute("church", church);
            req.setSession(s);
            return req;
        }

        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> portalsIn(ResponseEntity<Map<String, Object>> res) {
            return (List<Map<String, Object>>) res.getBody().get("portals");
        }

        @Test
        @DisplayName("the tenant's own SuperAdmin sees all three")
        void superAdminSees() {
            ResponseEntity<Map<String, Object>> res =
                    controller.portalCredentials(sessionOf(TRIAL, "SuperAdmin", false));
            assertThat(res.getBody().get("demo")).isEqualTo(true);
            assertThat(portalsIn(res)).hasSize(3);
        }

        @Test
        @DisplayName("the Member Portal login is not handed the keys to the other two")
        void memberPortalLoginSeesNothing() {
            ResponseEntity<Map<String, Object>> res =
                    controller.portalCredentials(sessionOf(TRIAL, "Member", false));
            assertThat(res.getBody().get("demo")).isEqualTo(false);
            assertThat(portalsIn(res)).isEmpty();
        }

        @Test
        @DisplayName("nor is the Kids Portal login")
        void childPortalLoginSeesNothing() {
            assertThat(portalsIn(controller.portalCredentials(sessionOf(TRIAL, "Child", false)))).isEmpty();
        }

        @Test
        @DisplayName("a real church's admin sees nothing, because there is nothing to see")
        void realChurchAdminSeesNothing() {
            ResponseEntity<Map<String, Object>> res =
                    controller.portalCredentials(sessionOf(CHURCH, "SuperAdmin", false));
            assertThat(res.getBody().get("demo")).isEqualTo(false);
            assertThat(portalsIn(res)).isEmpty();
        }

        @Test
        @DisplayName("an anonymous request sees nothing")
        void anonymousSeesNothing() {
            MockHttpServletRequest anon = new MockHttpServletRequest("GET", "/api/demo/portal-credentials");
            assertThat(portalsIn(controller.portalCredentials(anon))).isEmpty();
        }
    }

    /* ── what the portal pages are told ─────────────────────────────────── */

    @Nested
    @DisplayName("the session payload")
    class SessionPayload {

        @Mock AppUserRepository appUserRepository;
        @Mock SubscriptionService subs;

        private SessionController session;

        @BeforeEach
        void setUp() {
            session = new SessionController(appUserRepository);
            session.setSubscriptionService(subs);
        }

        private MockHttpServletRequest signedIn(String tenant) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/session");
            MockHttpSession s = new MockHttpSession();
            s.setAttribute("clientId", "MBR-abc");
            s.setAttribute("appClientId", tenant);
            s.setAttribute("username", "member_grace_7");
            s.setAttribute("role", "Member");
            req.setSession(s);
            return req;
        }

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("a portal page is told it is on an evaluation account, and what is withheld")
        void evaluationFlagAndFeatures() {
            when(subs.isEvaluationTenant(TRIAL)).thenReturn(true);
            when(subs.featureMap(TRIAL)).thenReturn(Map.of("activityCorner", false));

            Map<String, Object> body = session.getSession(signedIn(TRIAL)).getBody();

            assertThat(body.get("evaluationAccount")).isEqualTo(true);
            assertThat((Map<String, Boolean>) body.get("features"))
                    .containsEntry("activityCorner", false);
        }

        @Test
        @DisplayName("a real church's member portal is told nothing is withheld")
        void realChurchUnaffected() {
            when(subs.isEvaluationTenant(CHURCH)).thenReturn(false);
            when(subs.featureMap(CHURCH)).thenReturn(Map.of());

            Map<String, Object> body = session.getSession(signedIn(CHURCH)).getBody();

            assertThat(body.get("evaluationAccount")).isEqualTo(false);
            assertThat((Map<?, ?>) body.get("features")).isEmpty();
        }

        @Test
        @DisplayName("a plan lookup failure hides nothing")
        void lookupFailureHidesNothing() {
            when(subs.isEvaluationTenant(anyString())).thenThrow(new RuntimeException("db down"));

            Map<String, Object> body = session.getSession(signedIn(TRIAL)).getBody();

            assertThat(body.get("evaluationAccount")).isEqualTo(false);
            assertThat((Map<?, ?>) body.get("features")).isEmpty();
        }
    }
}
