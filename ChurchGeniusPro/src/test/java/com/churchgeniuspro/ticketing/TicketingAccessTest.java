package com.churchgeniuspro.ticketing;

import com.churchgeniuspro.controller.AiDataSearchController;
import com.churchgeniuspro.controller.AiSearchController;
import com.churchgeniuspro.controller.SupportTicketController;
import com.churchgeniuspro.controller.VoiceCommandController;
import com.churchgeniuspro.service.*;
import com.churchgeniuspro.util.RoleGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Who may use Ticketing and the AI Assistant — the same rule on the page routes and
 * on every API, so typing a URL or calling an endpoint directly is refused exactly
 * when the menu item is hidden.
 */
@DisplayName("Ticketing / AI Assistant — access by role")
class TicketingAccessTest {

    static final String ALLOWED = "allowed", DENIED = RoleGuard.FORWARD_ACCESS_DENIED, LOGIN = RoleGuard.REDIRECT_LOGIN;

    // ── session shapes (mirroring LoginController) ────────────────────────
    static MockHttpServletRequest staff(String role, String privileges) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "staff1"); s.setAttribute("role", role);
        s.setAttribute("appUserId", 7); s.setAttribute("appClientId", "CHR-1"); s.setAttribute("church", false);
        if (privileges != null) s.setAttribute("privileges", privileges);
        MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s); return r;
    }
    static MockHttpServletRequest church() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "church1"); s.setAttribute("role", "church");
        s.setAttribute("clientId", "CHR-1"); s.setAttribute("church", true);
        MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s); return r;
    }
    static MockHttpServletRequest member(String memberRole, String memberPrivileges) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("role", "Member"); s.setAttribute("memberId", 42); s.setAttribute("memberRole", memberRole);
        s.setAttribute("appClientId", "CHR-1"); s.setAttribute("clientId", "MBRtoken");
        if (memberPrivileges != null) s.setAttribute("memberPrivileges", memberPrivileges);
        MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s); return r;
    }
    static String verdict(String deny) { return deny == null ? ALLOWED : deny; }

    @Nested @DisplayName("the rule (RoleGuard.requireFeature)")
    class Rule {
        @ParameterizedTest(name = "{0} with no saved permissions → allowed")
        @ValueSource(strings = { "SuperAdmin", "Admin", "Accountant", "User", "Limited" })
        void staffDefaultOn(String role) {
            assertThat(verdict(RoleGuard.requireFeature(staff(role, null), RoleGuard.PERM_TICKETING))).isEqualTo(ALLOWED);
            assertThat(verdict(RoleGuard.requireFeature(staff(role, null), RoleGuard.PERM_AI_ASSISTANT))).isEqualTo(ALLOWED);
        }
        @ParameterizedTest(name = "{0} with the box ticked → allowed; unticked → denied")
        @ValueSource(strings = { "SuperAdmin", "Admin", "Accountant", "User" })
        void staffFollowsCheckbox(String role) {
            for (String key : new String[] { RoleGuard.PERM_TICKETING, RoleGuard.PERM_AI_ASSISTANT }) {
                assertThat(verdict(RoleGuard.requireFeature(staff(role, "{\"" + key + "\":true}"), key))).isEqualTo(ALLOWED);
                assertThat(verdict(RoleGuard.requireFeature(staff(role, "{\"" + key + "\":false}"), key))).isEqualTo(DENIED);
                // other keys saved, this one absent → still on (staff default)
                assertThat(verdict(RoleGuard.requireFeature(staff(role, "{\"more.followups\":false}"), key))).isEqualTo(ALLOWED);
            }
        }
        @Test @DisplayName("Church login → allowed, whatever is stored")
        void churchAllowed() {
            assertThat(verdict(RoleGuard.requireFeature(church(), RoleGuard.PERM_TICKETING))).isEqualTo(ALLOWED);
            assertThat(verdict(RoleGuard.requireFeature(church(), RoleGuard.PERM_AI_ASSISTANT))).isEqualTo(ALLOWED);
        }
        @Test @DisplayName("Member Portal: OFF by default — no saved permissions, or saved without the key, is denied")
        void memberDefaultOff() {
            for (String key : new String[] { RoleGuard.PERM_TICKETING, RoleGuard.PERM_AI_ASSISTANT }) {
                assertThat(verdict(RoleGuard.requireFeature(member("Head", null), key))).isEqualTo(DENIED);
                assertThat(verdict(RoleGuard.requireFeature(member("Head", "{\"member.family\":true}"), key))).isEqualTo(DENIED);
                assertThat(verdict(RoleGuard.requireFeature(member("Head", "{\"" + key + "\":false}"), key))).isEqualTo(DENIED);
                assertThat(verdict(RoleGuard.requireFeature(member("Head", "not json"), key))).isEqualTo(DENIED);
            }
        }
        @Test @DisplayName("Member Portal: granted from /viewusers (key explicitly true) → allowed; one key does not grant the other")
        void memberGranted() {
            assertThat(verdict(RoleGuard.requireFeature(member("Wife", "{\"more.ticketing\":true}"), RoleGuard.PERM_TICKETING))).isEqualTo(ALLOWED);
            assertThat(verdict(RoleGuard.requireFeature(member("Wife", "{\"more.ticketing\":true}"), RoleGuard.PERM_AI_ASSISTANT))).isEqualTo(DENIED);
            assertThat(verdict(RoleGuard.requireFeature(member("Wife", "{\"more.aiassistant\":true}"), RoleGuard.PERM_AI_ASSISTANT))).isEqualTo(ALLOWED);
        }
        @ParameterizedTest(name = "Kids Portal ({0}) → denied even when the key is true")
        @ValueSource(strings = { "Child", "Son", "Daughter", "child" })
        void kidsNever(String memberRole) {
            String both = "{\"more.ticketing\":true,\"more.aiassistant\":true}";
            assertThat(verdict(RoleGuard.requireFeature(member(memberRole, both), RoleGuard.PERM_TICKETING))).isEqualTo(DENIED);
            assertThat(verdict(RoleGuard.requireFeature(member(memberRole, both), RoleGuard.PERM_AI_ASSISTANT))).isEqualTo(DENIED);
        }
        @Test @DisplayName("no session → login")
        void noSession() {
            assertThat(RoleGuard.requireFeature(new MockHttpServletRequest(), RoleGuard.PERM_TICKETING)).isEqualTo(LOGIN);
        }
    }

    // ── the routes and APIs apply it ──────────────────────────────────────

    static SupportTicketController ticketController() throws Exception {
        SupportTicketService svc = mock(SupportTicketService.class);
        when(svc.listForClient(anyString())).thenReturn(java.util.List.of());
        when(svc.clientPackage(anyString())).thenReturn("Pro");
        Constructor<?> c = SupportTicketController.class.getDeclaredConstructors()[0];
        Object[] a = Arrays.stream(c.getParameterTypes()).map(t -> t == SupportTicketService.class ? svc : mock(t)).toArray();
        return (SupportTicketController) c.newInstance(a);
    }

    @Nested @DisplayName("Ticketing page + API")
    class TicketingRoutes {
        @Test void pageRouteFollowsTheRule() throws Exception {
            SupportTicketController c = ticketController();
            assertThat(c.ticketsPage(staff("User", null))).isEqualTo("forward:/tickets.html");
            assertThat(c.ticketsPage(staff("Accountant", "{\"more.ticketing\":false}"))).isEqualTo(DENIED);
            assertThat(c.ticketsPage(church())).isEqualTo("forward:/tickets.html");
            assertThat(c.ticketsPage(member("Head", null))).isEqualTo(DENIED);
            assertThat(c.ticketsPage(member("Head", "{\"more.ticketing\":true}"))).isEqualTo("forward:/tickets.html");
            assertThat(c.ticketsPage(member("Son", "{\"more.ticketing\":true}"))).isEqualTo(DENIED);
            assertThat(c.ticketsPage(new MockHttpServletRequest())).isEqualTo(LOGIN);
        }
        @Test void apiRefusesExactlyWhenThePageDoes() throws Exception {
            SupportTicketController c = ticketController();
            assertThat(c.list(staff("Admin", null)).getStatusCode().value()).isEqualTo(200);
            assertThat(c.list(church()).getStatusCode().value()).isEqualTo(200);
            assertThat(c.list(member("Head", "{\"more.ticketing\":true}")).getStatusCode().value()).isEqualTo(200);
            assertThat(c.list(member("Head", null)).getStatusCode().value()).isEqualTo(403);
            assertThat(c.list(member("Daughter", "{\"more.ticketing\":true}")).getStatusCode().value()).isEqualTo(403);
            assertThat(c.list(staff("User", "{\"more.ticketing\":false}")).getStatusCode().value()).isEqualTo(403);
            assertThat(c.list(new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(401);
            assertThat(c.submit(Map.of("subject", "x"), member("Head", null)).getStatusCode().value()).isEqualTo(403);
            assertThat(c.detail(1L, member("Child", "{\"more.ticketing\":true}")).getStatusCode().value()).isEqualTo(403);
            assertThat(c.context(staff("User", "{\"more.ticketing\":false}")).getStatusCode().value()).isEqualTo(403);
        }
        @Test void serviceAdminApiNeedsServiceAdminSession() throws Exception {
            SupportTicketController c = ticketController();
            assertThat(c.adminList(null, staff("SuperAdmin", null)).getStatusCode().value()).isEqualTo(401);
            assertThat(c.adminSetStatus(1L, Map.of("status", "Closed"), church()).getStatusCode().value()).isEqualTo(401);
            MockHttpSession s = new MockHttpSession(); s.setAttribute("serviceAdminId", 1);
            MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s);
            assertThat(c.adminList("Open", r).getStatusCode().value()).isEqualTo(200);
        }
    }

    @Nested @DisplayName("AI Assistant page + APIs")
    class AiRoutes {
        @Test void pageRoute() throws Exception {
            SupportTicketController c = ticketController();
            assertThat(c.aiAssistantPage(staff("Accountant", null))).isEqualTo("forward:/ai-assistant.html");
            assertThat(c.aiAssistantPage(staff("Admin", "{\"more.aiassistant\":false}"))).isEqualTo(DENIED);
            assertThat(c.aiAssistantPage(church())).isEqualTo("forward:/ai-assistant.html");
            assertThat(c.aiAssistantPage(member("Head", null))).isEqualTo(DENIED);
            assertThat(c.aiAssistantPage(member("Head", "{\"more.aiassistant\":true}"))).isEqualTo("forward:/ai-assistant.html");
            assertThat(c.aiAssistantPage(member("Child", "{\"more.aiassistant\":true}"))).isEqualTo(DENIED);
        }
        @Test void aiSearchApiEnforced() {
            AiSearchService svc = mock(AiSearchService.class);
            when(svc.search(any(), any(), anyBoolean(), any())).thenReturn(Map.of("intent", "general"));
            AiSearchController c = new AiSearchController(svc);
            assertThat(c.search(Map.of("query", "hi"), staff("User", null)).getStatusCode().value()).isEqualTo(200);
            assertThat(c.search(Map.of("query", "hi"), staff("User", "{\"more.aiassistant\":false}")).getStatusCode().value()).isEqualTo(403);
            assertThat(c.search(Map.of("query", "hi"), new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(401);
            verify(svc, times(1)).search(any(), any(), anyBoolean(), any());
        }
        @Test void aiDataSearchApiEnforced() throws Exception {
            Constructor<?> ctor = AiDataSearchController.class.getDeclaredConstructors()[0];
            Object[] a = Arrays.stream(ctor.getParameterTypes()).map(t -> (Object) mock(t)).toArray();
            AiDataSearchController c = (AiDataSearchController) ctor.newInstance(a);
            assertThat(c.data(Map.of("query", "who are our volunteers"), staff("Admin", "{\"more.aiassistant\":false}")).getStatusCode().value()).isEqualTo(403);
            assertThat(c.data(Map.of("query", "who are our volunteers"), member("Son", "{\"more.aiassistant\":true}")).getStatusCode().value()).isEqualTo(403);
        }
        @Test void aiAssistApiEnforced() throws Exception {
            Constructor<?> ctor = VoiceCommandController.class.getDeclaredConstructors()[0];
            Object[] a = Arrays.stream(ctor.getParameterTypes()).map(t -> (Object) mock(t)).toArray();
            VoiceCommandController c = (VoiceCommandController) ctor.newInstance(a);
            assertThat(c.aiAssist(Map.of("query", "hi"), staff("User", "{\"more.aiassistant\":false}")).getStatusCode().value()).isEqualTo(403);
            // staff with it on gets past the permission (then stops at "not configured" in this bare test)
            assertThat(c.aiAssist(Map.of("query", "hi"), staff("User", null)).getStatusCode().value()).isNotEqualTo(403);
        }
    }
}
