package com.churchgeniuspro.security;

import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.service.TemporaryAccessService;
import com.churchgeniuspro.webfilter.AuthFilter;
import com.churchgeniuspro.webfilter.NtagAccessFilter;
import com.churchgeniuspro.webfilter.RequestPaths;
import com.churchgeniuspro.webfilter.TempAccessFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Finding 1 (Critical) regression — the authentication/authorization filters must
 * make their security decision against the <em>canonical, decoded, normalised</em>
 * request path (the same path Spring MVC and the static handler route on), never the
 * raw {@link jakarta.servlet.http.HttpServletRequest#getRequestURI()} request line.
 *
 * <p><b>The discrepancy.</b> {@code getRequestURI()} is verbatim: percent-encoded and
 * with dot-segments intact. The dispatcher routes on the decoded/normalised path. So a
 * URL such as {@code /api/public/x/%2e%2e/%2e%2e/groups/5/members} is read by a filter
 * that matches the raw string as living under the public prefix {@code /api/public/},
 * yet the dispatcher resolves it to the protected {@code /api/groups/5/members}. A
 * filter that whitelisted on the raw string would wave it straight through to the
 * protected handler.
 *
 * <p>The fix routes all three filters through {@link RequestPaths#path} — the one
 * shared helper the sibling filters (Private/Subscription/Demo, pinned by
 * {@link EncodedPathGateTest}) already use. These tests pin it for {@link AuthFilter},
 * {@link TempAccessFilter} and {@link NtagAccessFilter}, covering {@code %2e%2e},
 * {@code %2E%2E}, plain {@code ..}, encoded-slash ({@code %2F}), mixed encoded/plain,
 * and repeated-segment traversal, against the concrete Group-member endpoints
 * ({@code /api/groups/{id}/members} and {@code /api/group-members/{id}}) for every verb.
 *
 * <p>Every case carries a companion assertion that the raw URI <em>would</em> have
 * matched the pre-fix allow-rule while its canonical form is the protected path — so
 * each test also documents the vulnerable behaviour it closes. Pure unit test: it fixes
 * {@code getRequestURI()} exactly via {@link MockHttpServletRequest}, so it is immune to
 * container/client encoding quirks and needs no Docker. The end-to-end proof over real
 * HTTP + real routing is {@code GroupMemberBypassIT}; server-side tenant isolation is
 * {@code GroupMemberTenantIsolationIT}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Finding 1 — filters decide on the canonical path (encoded-traversal bypass closed)")
class AuthChainCanonicalPathTest {

    /* ══════════════════════════ AuthFilter ══════════════════════════════════ */

    @Nested
    @DisplayName("AuthFilter — the /api/** session gate")
    class Auth {

        @Mock AppUserRepository appUserRepo;
        @Mock LoginRepository   loginRepo;

        /** 200 == the filter let the request through; otherwise the status it set. */
        private int statusFor(AuthFilter f, String method, String rawUri, MockHttpSession session) throws Exception {
            MockHttpServletRequest req = new MockHttpServletRequest(method, rawUri);
            req.setRequestURI(rawUri);                       // getRequestURI() == the raw request line, verbatim
            if (session != null) req.setSession(session);
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();   // records the request only if the filter passes it on
            f.doFilter(req, res, chain);
            return chain.getRequest() != null ? 200 : res.getStatus();
        }

        /**
         * Each row: an encoded-traversal URL whose raw form starts with the public
         * prefix {@code /api/public/} (so the pre-fix filter whitelisted it) but whose
         * canonical form is a protected Group-member endpoint. Unauthenticated → 401.
         */
        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({
            // ── GET/POST target: /api/groups/5/members ─────────────────────────────
            "GET,    /api/public/x/%2e%2e/%2e%2e/groups/5/members",              // lower-hex encoded ..
            "GET,    /api/public/x/%2E%2E/%2E%2E/groups/5/members",              // upper-hex encoded ..
            "GET,    /api/public/x/../../groups/5/members",                      // plain dot-segments
            "GET,    /api/public/x/../%2e%2e/groups/5/members",                  // mixed plain + encoded
            "GET,    /api/public/a/b/c/%2e%2e/%2e%2e/%2e%2e/%2e%2e/groups/5/members", // repeated segments
            "GET,    /api/public/x%2F%2e%2e%2F%2e%2e%2Fgroups/5/members",        // encoded slash + encoded ..
            "POST,   /api/public/x/%2e%2e/%2e%2e/groups/5/members",
            "POST,   /api/public/x/../../groups/5/members",
            // ── PUT/DELETE target: /api/group-members/9 ────────────────────────────
            "PUT,    /api/public/x/%2e%2e/%2e%2e/group-members/9",
            "PUT,    /api/public/x/../../group-members/9",
            "DELETE, /api/public/x/%2e%2e/%2e%2e/group-members/9",
            "DELETE, /api/public/x/../../group-members/9",
        })
        @DisplayName("unauthenticated encoded-traversal onto a protected endpoint is refused (401)")
        void encodedTraversalIsDenied(String method, String rawUri) throws Exception {
            AuthFilter filter = new AuthFilter(appUserRepo, loginRepo);

            // Precondition — the vulnerability being closed: the raw request line is a
            // public prefix (pre-fix whitelist match) yet the canonical path is protected.
            assertThat(rawUri).as("raw URI matches the pre-fix public-prefix rule")
                    .startsWith("/api/public/");
            String canonical = RequestPaths.normalise(rawUri);
            assertThat(canonical).as("canonical path is a protected Group-member endpoint")
                    .matches("/api/(groups/5/members|group-members/9)");

            // The fix: the filter now decides on the canonical path → no session → 401.
            assertThat(statusFor(filter, method, rawUri, null))
                    .as("%s %s must be refused (was a bypass pre-fix)", method, rawUri)
                    .isEqualTo(401);
        }

        @Test
        @DisplayName("the plain protected path still requires a session (sanity)")
        void plainProtectedNeedsSession() throws Exception {
            AuthFilter filter = new AuthFilter(appUserRepo, loginRepo);
            assertThat(statusFor(filter, "GET",    "/api/groups/5/members", null)).isEqualTo(401);
            assertThat(statusFor(filter, "DELETE", "/api/group-members/9",  null)).isEqualTo(401);
        }

        @Test
        @DisplayName("a valid tenant session still reaches the protected endpoint (no over-blocking)")
        void validSessionPasses() throws Exception {
            AuthFilter filter = new AuthFilter(appUserRepo, loginRepo);
            MockHttpSession s = new MockHttpSession();
            s.setAttribute("clientId", "CHR-A");
            s.setAttribute("tempAccessId", 1);   // short-circuits the per-request DB re-validation
            assertThat(statusFor(filter, "GET", "/api/groups/5/members", s)).isEqualTo(200);
        }

        @Test
        @DisplayName("legitimate public endpoints stay public — plain and re-encoded spelling")
        void publicEndpointsStayOpen() throws Exception {
            AuthFilter filter = new AuthFilter(appUserRepo, loginRepo);
            // plain
            assertThat(statusFor(filter, "GET", "/api/public/donate/config", null)).isEqualTo(200);
            // encoded spelling of the SAME public path normalises to a public path → still allowed
            assertThat(RequestPaths.normalise("/%61pi/public/donate/config")).isEqualTo("/api/public/donate/config");
            assertThat(statusFor(filter, "GET", "/%61pi/public/donate/config", null)).isEqualTo(200);
        }
    }

    /* ═══════════════════════ TempAccessFilter ═══════════════════════════════ */

    @Nested
    @DisplayName("TempAccessFilter — temp-pass liveness / revocation re-check")
    class Temp {

        @Mock TemporaryAccessService svc;

        private MockHttpSession tempSession() {
            MockHttpSession s = new MockHttpSession();
            s.setAttribute("tempAccessId", 1L);
            s.setAttribute("clientId", "CHR-1");
            return s;
        }

        private MockHttpServletResponse run(TempAccessFilter f, String rawUri, MockHttpSession s) throws Exception {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", rawUri);
            req.setRequestURI(rawUri);
            if (s != null) req.setSession(s);
            MockHttpServletResponse res = new MockHttpServletResponse();
            f.doFilter(req, res, new MockFilterChain());
            return res;   // pass-through leaves status at the 200 default; a block sets it explicitly
        }

        @Test
        @DisplayName("an encoded-traversal off the always-allowed list still runs the liveness re-check")
        void traversalStillTriggersLivenessCheck() throws Exception {
            // Raw "/tempLogin/..." matched the pre-fix always-allowed rule (startsWith "/tempLogin"),
            // so the expired/revoked pass would have skipped the DB liveness re-check and passed through.
            String rawUri = "/tempLogin/%2e%2e/api/groups/5/members";
            assertThat(rawUri).as("raw URI matches the pre-fix always-allowed rule").startsWith("/tempLogin");
            assertThat(RequestPaths.normalise(rawUri)).isEqualTo("/api/groups/5/members");

            when(svc.find(1L, "CHR-1")).thenReturn(Optional.empty());   // pass not live any more

            MockHttpServletResponse res = run(new TempAccessFilter(svc), rawUri, tempSession());

            verify(svc, times(1)).find(1L, "CHR-1");   // the re-check ran — it was NOT skipped by the raw allow-list
            assertThat(res.getStatus()).isEqualTo(401); // canonical path is /api/* → JSON 401
        }

        @Test
        @DisplayName("a genuinely always-allowed path is passed through without a DB hit (no over-blocking)")
        void allowListStillWorks() throws Exception {
            MockHttpServletResponse res = run(new TempAccessFilter(svc), "/tempLogin", tempSession());
            assertThat(res.getStatus()).isEqualTo(200);   // passed through
            verify(svc, never()).find(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
        }
    }

    /* ═══════════════════════ NtagAccessFilter ═══════════════════════════════ */

    @Nested
    @DisplayName("NtagAccessFilter — NTAG allowed-pages gate")
    class Ntag {

        private MockHttpSession ntagSession(String... routes) {
            MockHttpSession s = new MockHttpSession();
            s.setAttribute("ntagCredId", 1);
            s.setAttribute("ntagRoutes", List.of(routes));
            return s;
        }

        private MockHttpServletResponse run(String rawUri, MockHttpSession s, MockFilterChain chain) throws Exception {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", rawUri);
            req.setRequestURI(rawUri);
            req.setSession(s);
            MockHttpServletResponse res = new MockHttpServletResponse();
            new NtagAccessFilter().doFilter(req, res, chain);
            return res;
        }

        @Test
        @DisplayName("an encoded-traversal off a permitted route cannot reach a page outside the allowed set")
        void traversalOffPermittedRouteIsBlocked() throws Exception {
            // NTAG user may see only /kidsCheckin. Raw "/kidsCheckin/%2e%2e/giving" matched the
            // pre-fix isPermittedPage rule (startsWith "/kidsCheckin/") but resolves to /giving.
            String rawUri = "/kidsCheckin/%2e%2e/giving";
            assertThat(rawUri).as("raw URI matches the pre-fix permitted-route rule").startsWith("/kidsCheckin/");
            assertThat(RequestPaths.normalise(rawUri)).isEqualTo("/giving");

            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse res = run(rawUri, ntagSession("/kidsCheckin"), chain);

            assertThat(chain.getRequest()).as("request must NOT be passed through").isNull();
            assertThat(res.getForwardedUrl()).isEqualTo("/access-denied.html");
        }

        @Test
        @DisplayName("a permitted route and its sub-paths still pass (no over-blocking)")
        void permittedRouteStillPasses() throws Exception {
            MockFilterChain c1 = new MockFilterChain();
            run("/kidsCheckin", ntagSession("/kidsCheckin"), c1);
            assertThat(c1.getRequest()).as("permitted route passes through").isNotNull();

            MockFilterChain c2 = new MockFilterChain();
            run("/kidsCheckin/roster", ntagSession("/kidsCheckin"), c2);
            assertThat(c2.getRequest()).as("sub-path of a permitted route passes through").isNotNull();
        }
    }
}
