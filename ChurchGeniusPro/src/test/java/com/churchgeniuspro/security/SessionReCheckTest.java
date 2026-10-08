package com.churchgeniuspro.security;

import com.churchgeniuspro.hibernate.DemoRoleAccess;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.repository.DemoRoleAccessRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.service.AccountStatusService;
import com.churchgeniuspro.webfilter.AccountStatusFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * M3: expiry and demo blocks are re-checked on every API call, not only at sign-in.
 *
 * <p>They used to be decided once, at authentication, so they governed new sign-ins
 * and nothing else. An admin pressing <b>Block</b> on a demo login, or a
 * subscription lapsing overnight, changed nothing for anyone already signed in —
 * and for a member who had turned on "Don't log out automatically" the session is
 * set to never expire, so "already signed in" meant indefinitely.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Signed-in account re-check (M3)")
class SessionReCheckTest {

    private static final String TENANT = "CHR-church-01";
    private static final String USER   = "pastor@church-01";
    /** Windows only exist for tenants this deployment provisioned. */
    private static final String DEMO_TENANT =
            com.churchgeniuspro.service.TestDataService.DEMO_CLIENT_PREFIX + "1757300000123";

    @Mock ServiceClientRepository clientRepo;
    @Mock DemoRoleAccessRepository accessRepo;

    private AccountStatusService status;
    private ServiceClient client;

    @BeforeEach
    void setUp() {
        status = new AccountStatusService(clientRepo, accessRepo);
        client = new ServiceClient();
        client.setClientId(TENANT);
        client.setStatus("Active");
        client.setEndDate(com.churchgeniuspro.util.AppClock.today().plusDays(30));
        client.setDeleteFlag(false);
        when(clientRepo.findByClientId(TENANT)).thenReturn(Optional.of(client));
        when(accessRepo.findByUsername(anyString())).thenReturn(Optional.empty());
    }

    private MockHttpServletResponse run(String uri) throws Exception {
        return run(uri, TENANT);
    }

    private MockHttpServletResponse run(String uri, String tenant) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("appClientId", tenant);
        session.setAttribute("username", USER);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setSession(session);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(200);
        new AccountStatusFilter(status).doFilter(req, res, chain);
        return res;
    }

    /* ── the tenant ─────────────────────────────────────────────────────── */

    @Nested
    @DisplayName("the tenant's subscription")
    class Tenant {

        @Test
        @DisplayName("a live subscription passes")
        void livePasses() throws Exception {
            assertThat(run("/api/members").getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("an expired subscription stops an existing session, with the date")
        void expiredBlocks() throws Exception {
            client.setEndDate(com.churchgeniuspro.util.AppClock.today().minusDays(1));
            MockHttpServletResponse res = run("/api/members");
            assertThat(res.getStatus()).isEqualTo(403);
            assertThat(res.getContentAsString()).contains(AccountStatusService.EXPIRED_CODE);
            assertThat(res.getContentAsString()).contains(com.churchgeniuspro.util.AppClock.today().minusDays(1).toString());
        }

        @Test
        @DisplayName("today's end date is already expired — the same boundary the login SQL uses")
        void endDateTodayIsExpired() throws Exception {
            client.setEndDate(com.churchgeniuspro.util.AppClock.today());   // CHANGED 2026-10-05 (Phase 2): subscription dates are America/Chicago dates
            assertThat(run("/api/members").getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("a suspended or removed client is stopped too")
        void suspendedOrRemovedBlocks() throws Exception {
            client.setStatus("Suspended");
            assertThat(run("/api/members").getStatus()).isEqualTo(403);

            client.setStatus("Active");
            client.setDeleteFlag(true);
            assertThat(run("/api/members").getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("a client with no subscription row is never newly locked out")
        void unknownClientPasses() throws Exception {
            when(clientRepo.findByClientId(TENANT)).thenReturn(Optional.empty());
            assertThat(run("/api/members").getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("a database error fails open and is not cached")
        void lookupFailureFailsOpen() throws Exception {
            // doThrow/doReturn rather than when(...): re-stubbing with when() would
            // call the mock, and the mock is currently set to throw.
            org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                    .when(clientRepo).findByClientId(TENANT);
            assertThat(run("/api/members").getStatus()).isEqualTo(200);

            // Not cached: the next request asks again, so recovery is immediate.
            org.mockito.Mockito.doReturn(Optional.of(client)).when(clientRepo).findByClientId(TENANT);
            client.setEndDate(com.churchgeniuspro.util.AppClock.today().minusDays(1));
            assertThat(run("/api/members").getStatus()).isEqualTo(403);
        }
    }

    /* ── the individual demo login ──────────────────────────────────────── */

    @Nested
    @DisplayName("this demo login's window")
    class Window {

        @BeforeEach
        void demoTenantIsLive() {
            ServiceClient demo = new ServiceClient();
            demo.setClientId(DEMO_TENANT);
            demo.setStatus("Active");
            demo.setEndDate(LocalDate.now().plusDays(30));
            demo.setDeleteFlag(false);
            when(clientRepo.findByClientId(DEMO_TENANT)).thenReturn(Optional.of(demo));
        }

        private DemoRoleAccess window(boolean blocked, LocalDate end) {
            DemoRoleAccess w = new DemoRoleAccess();
            w.setSignupId(7);
            w.setUsername(USER);
            w.setBlocked(blocked);
            w.setEndDate(end);
            when(accessRepo.findByUsername(USER)).thenReturn(Optional.of(w));
            return w;
        }

        @Test
        @DisplayName("Block takes effect on a session that is already open")
        void blockedStopsALiveSession() throws Exception {
            window(false, LocalDate.now().plusDays(10));
            assertThat(run("/api/members", DEMO_TENANT).getStatus()).isEqualTo(200);   // still fine

            status.invalidateAll();                                      // the admin presses Block
            window(true, LocalDate.now().plusDays(10));
            MockHttpServletResponse res = run("/api/members", DEMO_TENANT);
            assertThat(res.getStatus()).isEqualTo(403);
            assertThat(res.getContentAsString()).contains(AccountStatusService.DEMO_ENDED_CODE);
        }

        @Test
        @DisplayName("an ended window stops the session even while the tenant is still live")
        void endedWindowStopsSession() throws Exception {
            window(false, LocalDate.now().minusDays(1));
            assertThat(run("/api/members", DEMO_TENANT).getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("a login with no window is untouched")
        void noWindowPasses() throws Exception {
            assertThat(run("/api/members", DEMO_TENANT).getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("a real church costs no window lookup at all")
        void realChurchIsNotLookedUp() throws Exception {
            window(true, LocalDate.now().minusDays(1));      // a row that would block, if consulted
            assertThat(run("/api/members", TENANT).getStatus()).isEqualTo(200);
            org.mockito.Mockito.verify(accessRepo, org.mockito.Mockito.never()).findByUsername(USER);
        }
    }

    /* ── what must keep working ─────────────────────────────────────────── */

    @Nested
    @DisplayName("what a stopped session can still do")
    class StillAllowed {

        @BeforeEach
        void expire() { client.setEndDate(com.churchgeniuspro.util.AppClock.today().minusDays(1)); }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "/api/session",                 // without it the page bounces to login
            "/api/logout",                  // signing out must always work
            "/api/auth/logout",
            "/api/subscription/features",   // the page that explains the plan
            "/api/logo/image"
        })
        @DisplayName("the notice can be shown and the user can sign out")
        void allowListed(String uri) throws Exception {
            assertThat(run(uri).getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("pages still render — only their data is withheld")
        void pagesRender() throws Exception {
            assertThat(run("/home").getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("an encoded spelling of /api/ is still gated")
        void encodedApiStillGated() throws Exception {
            assertThat(run("/%61pi/members").getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("an anonymous request and a Service Admin are untouched")
        void anonymousAndServiceAdmin() throws Exception {
            MockHttpServletRequest anon = new MockHttpServletRequest("GET", "/api/members");
            MockHttpServletResponse res = new MockHttpServletResponse();
            new AccountStatusFilter(status).doFilter(anon, res, (rq, rs) -> res.setStatus(200));
            assertThat(res.getStatus()).isEqualTo(200);

            MockHttpSession admin = new MockHttpSession();
            admin.setAttribute("serviceAdminId", 1);
            admin.setAttribute("appClientId", TENANT);
            MockHttpServletRequest sa = new MockHttpServletRequest("GET", "/api/members");
            sa.setSession(admin);
            MockHttpServletResponse res2 = new MockHttpServletResponse();
            new AccountStatusFilter(status).doFilter(sa, res2, (rq, rs) -> res2.setStatus(200));
            assertThat(res2.getStatus()).isEqualTo(200);
        }
    }
}
