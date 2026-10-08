package com.churchgeniuspro.demo;

import com.churchgeniuspro.hibernate.DemoRoleAccess;
import com.churchgeniuspro.repository.DemoClientSettingsRepository;
import com.churchgeniuspro.repository.DemoRoleAccessRepository;
import com.churchgeniuspro.service.DemoAccessService;
import com.churchgeniuspro.service.TestDataService;
import com.churchgeniuspro.webfilter.DemoTrialAgreementFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The first-login trial agreement: recording it, clearing it on Reset, and the
 * request gate that holds a demo login at the popup until it is recorded.
 *
 * <p>The gate is the part worth testing hardest — it sits on every {@code /api/*}
 * call, so a mistake there either locks demo users out of an application they are
 * entitled to see, or lets an unaccepted one straight through.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Demo trial-account agreement")
class DemoTrialAgreementTest {

    private static final String DEMO_TENANT = TestDataService.DEMO_CLIENT_PREFIX + "0001";

    @Mock private DemoRoleAccessRepository     accessRepo;
    @Mock private DemoClientSettingsRepository settingsRepo;
    @Mock private JdbcTemplate                 jdbc;

    private DemoAccessService service;

    @BeforeEach
    void setUp() {
        service = new DemoAccessService(accessRepo, settingsRepo, jdbc);
        when(accessRepo.save(any(DemoRoleAccess.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private static DemoRoleAccess window(boolean accepted) {
        DemoRoleAccess a = new DemoRoleAccess();
        a.setId(7L);
        a.setSignupId(42);
        a.setClientId(DEMO_TENANT);
        a.setUsername("demo.pastor");
        a.setEndDate(LocalDate.now().plusDays(20));
        a.setBlocked(false);
        if (accepted) a.setAgreementAcceptedAt(LocalDateTime.now().minusDays(3));
        return a;
    }

    /* ── recording the acceptance ───────────────────────────────────────── */

    @Test
    @DisplayName("accept stamps the row and reports the window as accepted")
    void acceptStampsTheRow() {
        DemoRoleAccess a = window(false);
        when(accessRepo.findBySignupId(42)).thenReturn(Optional.of(a));

        Optional<DemoRoleAccess> saved = service.acceptAgreement(42);

        assertThat(saved).isPresent();
        assertThat(saved.get().getAgreementAcceptedAt()).isNotNull();
        assertThat(saved.get().isAgreementAccepted()).isTrue();
        verify(accessRepo, times(1)).save(any(DemoRoleAccess.class));
    }

    @Test
    @DisplayName("a second accept does not move the original timestamp")
    void acceptIsIdempotent() {
        DemoRoleAccess a = window(true);
        LocalDateTime first = a.getAgreementAcceptedAt();
        when(accessRepo.findBySignupId(42)).thenReturn(Optional.of(a));

        service.acceptAgreement(42);

        assertThat(a.getAgreementAcceptedAt()).isEqualTo(first);
        verify(accessRepo, never()).save(any(DemoRoleAccess.class));
    }

    @Test
    @DisplayName("accepting an unknown signup is a no-op, not an error")
    void acceptUnknownSignup() {
        when(accessRepo.findBySignupId(99)).thenReturn(Optional.empty());
        assertThat(service.acceptAgreement(99)).isEmpty();
        assertThat(service.acceptAgreement(null)).isEmpty();
    }

    @Test
    @DisplayName("Reset clears the acceptance, so the new window is acknowledged again")
    void reissueClearsAcceptance() {
        DemoRoleAccess a = window(true);
        when(accessRepo.findById(7L)).thenReturn(Optional.of(a));

        DemoRoleAccess out = service.reissue(7L, "demo.pastor.2");

        assertThat(out.getAgreementAcceptedAt()).isNull();
        assertThat(out.isAgreementAccepted()).isFalse();
        assertThat(out.getUsername()).isEqualTo("demo.pastor.2");
    }

    /* ── the request gate ───────────────────────────────────────────────── */

    /** The filter's outcome: did the request reach the rest of the chain, and what was written. */
    private record Outcome(boolean passedThrough, MockHttpServletResponse response) {
        int status() { return response.getStatus(); }
        String body() {
            try { return response.getContentAsString(); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
    }

    private Outcome runFilter(MockHttpSession session, String uri) throws Exception {
        MockHttpServletRequest  req = new MockHttpServletRequest("GET", uri);
        req.setSession(session);
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean[] reached = { false };
        FilterChain chain = (rq, rs) -> reached[0] = true;

        new DemoTrialAgreementFilter(service).doFilter(req, res, chain);
        return new Outcome(reached[0], res);
    }

    @Test
    @DisplayName("a regular (non-demo) session is never touched — and nothing is written to it")
    void regularSessionPassesThrough() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("clientId", "CHR12345");
        session.setAttribute("username", "real.user");

        Outcome out = runFilter(session, "/api/members");

        assertThat(out.passedThrough()).isTrue();
        assertThat(out.status()).isEqualTo(200);
        // The filter used to cache FALSE here. It must not: with Spring Session JDBC
        // a session write from a filter is an INSERT decided from the snapshot the
        // request loaded, so the burst of parallel calls a page makes on load all
        // believed they were creating this attribute and all INSERTed it — the
        // losers failing on spring_session_attributes_pk and taking their whole
        // request down with them. Caching bought nothing (the check is a prefix
        // test), so the write is gone and an ordinary church cannot hit that race.
        assertThat(session.getAttribute(DemoTrialAgreementFilter.ATTR_TRIAL)).isNull();
    }

    @Test
    @DisplayName("…so a page's parallel API calls write nothing for a real church")
    void parallelRequestsWriteNothingForARealChurch() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("clientId", "CHR12345");
        session.setAttribute("username", "real.user");

        // The shape of a page load: several requests against one session, none of
        // which has seen another's result yet. Each must pass and leave no trace.
        for (String uri : new String[] { "/api/family/avatar", "/api/session",
                                         "/api/members", "/api/demo/product-tour" }) {
            assertThat(runFilter(session, uri).passedThrough()).isTrue();
        }

        assertThat(java.util.Collections.list(session.getAttributeNames()))
                .containsExactlyInAnyOrder("clientId", "username");
    }

    @Test
    @DisplayName("a demo session still stores its four attributes")
    void demoSessionStillCachesItsWindow() throws Exception {
        when(accessRepo.findByUsername("demo.pastor")).thenReturn(Optional.of(window(true)));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("appClientId", DEMO_TENANT);
        session.setAttribute("username", "demo.pastor");

        runFilter(session, "/api/members");

        assertThat(session.getAttribute(DemoTrialAgreementFilter.ATTR_TRIAL)).isEqualTo(Boolean.TRUE);
        assertThat(session.getAttribute(DemoTrialAgreementFilter.ATTR_SIGNUP_ID)).isNotNull();
        // Those four writes CAN still race on the first page load of a demo login,
        // which is why SessionConfig makes the attribute insert an UPSERT rather
        // than leaving the fix to any one filter.
    }

    @Test
    @DisplayName("an anonymous request is never touched")
    void anonymousPassesThrough() throws Exception {
        MockHttpServletRequest  req   = new MockHttpServletRequest("GET", "/api/members");
        MockHttpServletResponse res   = new MockHttpServletResponse();
        FilterChain             chain = mock(FilterChain.class);

        new DemoTrialAgreementFilter(service).doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("an unaccepted demo session is refused with 403 DEMO_AGREEMENT_REQUIRED")
    void unacceptedDemoSessionIsBlocked() throws Exception {
        when(accessRepo.findByUsername("demo.pastor")).thenReturn(Optional.of(window(false)));

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("appClientId", DEMO_TENANT);
        session.setAttribute("username", "demo.pastor");

        Outcome out = runFilter(session, "/api/members");

        assertThat(out.passedThrough()).isFalse();
        assertThat(out.status()).isEqualTo(403);
        assertThat(out.body()).contains("DEMO_AGREEMENT_REQUIRED");
        // 403, never 401 — session.js signs the user out on a 401 and they would
        // never reach the popup they are being held for.
        assertThat(out.status()).isNotEqualTo(401);
    }

    @Test
    @DisplayName("the agreement endpoints stay reachable while the gate is closed")
    void agreementEndpointsAreWhitelisted() throws Exception {
        when(accessRepo.findByUsername("demo.pastor")).thenReturn(Optional.of(window(false)));

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("appClientId", DEMO_TENANT);
        session.setAttribute("username", "demo.pastor");

        assertThat(runFilter(session, "/api/demo/trial-agreement").passedThrough()).isTrue();
        assertThat(runFilter(session, "/api/demo/trial-agreement/accept").passedThrough()).isTrue();
        assertThat(runFilter(session, "/api/session").passedThrough()).isTrue();
        assertThat(runFilter(session, "/api/auth/logout").passedThrough()).isTrue();
    }

    @Test
    @DisplayName("once accepted, the demo session is unrestricted")
    void acceptedDemoSessionPassesThrough() throws Exception {
        when(accessRepo.findByUsername("demo.pastor")).thenReturn(Optional.of(window(true)));

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("appClientId", DEMO_TENANT);
        session.setAttribute("username", "demo.pastor");

        Outcome out = runFilter(session, "/api/members");

        assertThat(out.passedThrough()).isTrue();
        assertThat(out.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("the end date travels to the client on the refusal")
    void refusalCarriesTheEndDate() throws Exception {
        DemoRoleAccess a = window(false);
        a.setEndDate(LocalDate.of(2026, 10, 7));
        when(accessRepo.findByUsername("demo.pastor")).thenReturn(Optional.of(a));

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("appClientId", DEMO_TENANT);
        session.setAttribute("username", "demo.pastor");

        Outcome out = runFilter(session, "/api/members");

        assertThat(out.body()).contains("2026-10-07");
    }

    @Test
    @DisplayName("a demo tenant with no window row is not blocked")
    void demoTenantWithoutWindowPassesThrough() throws Exception {
        when(accessRepo.findByUsername("demo.pastor")).thenReturn(Optional.empty());

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("appClientId", DEMO_TENANT);
        session.setAttribute("username", "demo.pastor");

        assertThat(runFilter(session, "/api/members").passedThrough()).isTrue();
    }

    @Test
    @DisplayName("a repository failure fails open rather than locking the tenant out")
    void repositoryFailureFailsOpen() throws Exception {
        when(accessRepo.findByUsername("demo.pastor"))
                .thenThrow(new RuntimeException("connection reset"));

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("appClientId", DEMO_TENANT);
        session.setAttribute("username", "demo.pastor");

        assertThat(runFilter(session, "/api/members").passedThrough()).isTrue();
    }
}
