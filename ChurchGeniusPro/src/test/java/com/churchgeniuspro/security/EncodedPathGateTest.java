package com.churchgeniuspro.security;

import com.churchgeniuspro.hibernate.DemoRoleAccess;
import com.churchgeniuspro.hibernate.PrivateAccessSetting;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.service.DemoAccessService;
import com.churchgeniuspro.service.PrivateAccessService;
import com.churchgeniuspro.service.PublicLinkResolver;
import com.churchgeniuspro.service.SubscriptionService;
import com.churchgeniuspro.service.TestDataService;
import com.churchgeniuspro.util.PrivatePageCatalog;
import com.churchgeniuspro.util.SubscriptionFeatureCatalog;
import com.churchgeniuspro.webfilter.DemoTrialAgreementFilter;
import com.churchgeniuspro.webfilter.PrivatePageFilter;
import com.churchgeniuspro.webfilter.RequestPaths;
import com.churchgeniuspro.webfilter.SubscriptionFeatureFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * H1 regression: path-gated filters must decide on the DECODED, normalised path.
 *
 * <p>{@code getRequestURI()} is the raw request line. Spring MVC and the static
 * resource handler match on decoded segments, so {@code /api/guess%2Dit/…} reaches
 * the same handler as {@code /api/guess-it/…}. Verified against the project's own
 * Tomcat 11 + Spring MVC 7 jars: the servlet container still runs an {@code /api/*}
 * filter for {@code /%61pi/…}, but the filter used to compare the raw string and let
 * the request through. These tests pin the fix in all three affected filters.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Encoded-path bypass (H1)")
class EncodedPathGateTest {

    /* ── the helper itself ─────────────────────────────────────────────── */

    @Nested
    @DisplayName("RequestPaths.normalise")
    class Normalise {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
            "/api/guess-it/admin/games,        /api/guess-it/admin/games",
            "/api/guess%2Dit/admin/games,      /api/guess-it/admin/games",
            "/%61pi/guess-it/admin/games,      /api/guess-it/admin/games",
            "//api/guess-it/admin/games,       /api/guess-it/admin/games",
            "/api/./guess-it/admin/games,      /api/guess-it/admin/games",
            "/api/x/../guess-it/admin/games,   /api/guess-it/admin/games",
            "/api;x=1/guess-it;y/admin,        /api/guess-it/admin",
            "/%68ome.html,                     /home.html",
            "/../../etc/passwd,                /etc/passwd",
            "/a+b,                             /a+b",
            "/api/,                            /api/",
            "/,                                /",
        })
        void decodesAndNormalises(String raw, String expected) {
            assertThat(RequestPaths.normalise(raw)).isEqualTo(expected);
        }

        @Test
        @DisplayName("an undecodable URI is returned untouched (nothing will match it)")
        void malformedLeftRaw() {
            assertThat(RequestPaths.normalise("/api/%zz")).isEqualTo("/api/%zz");
        }

        @Test
        @DisplayName("the context path is stripped before matching")
        void contextPathStripped() {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/cgp/api/guess%2Dit/admin");
            req.setContextPath("/cgp");
            assertThat(RequestPaths.path(req)).isEqualTo("/api/guess-it/admin");
        }
    }

    /* ── the catalog sees the same key for every spelling ──────────────── */

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "/api/guess%2Dit/admin/games",
        "/%61pi/guess-it/admin/games",
        "/api;v=1/guess-it/admin/games",
        "//api/guess-it/admin/games",
        "/guess%49t",            // /guessIt page
    })
    @DisplayName("every spelling of an Activity Corner path resolves to the activityCorner key")
    void catalogKeyIsSpellingIndependent(String raw) {
        assertThat(SubscriptionFeatureCatalog.keyForPath(RequestPaths.normalise(raw)))
                .isEqualTo("activityCorner");
    }

    /* ── SubscriptionFeatureFilter ─────────────────────────────────────── */

    @Nested
    @DisplayName("SubscriptionFeatureFilter")
    class FeatureFilter {

        @Mock SubscriptionService subs;

        private MockHttpServletResponse run(String uri) throws Exception {
            when(subs.isFeatureEnabled(eq("CHR-1"), eq("activityCorner"))).thenReturn(false);
            when(subs.isFeatureEnabled(eq("CHR-1"), eq("attendance"))).thenReturn(true);
            MockHttpSession session = new MockHttpSession();
            session.setAttribute("appClientId", "CHR-1");
            return exercise(new SubscriptionFeatureFilter(subs), session, uri);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "/api/guess-it/admin/games",
            "/api/guess%2Dit/admin/games",
            "/%61pi/guess-it/admin/games",
            "//api/guess-it/admin/games",
            "/api;x=1/guess-it/admin/games",
        })
        @DisplayName("a disabled feature is refused whatever the spelling")
        void encodedSpellingStillBlocked(String uri) throws Exception {
            MockHttpServletResponse res = run(uri);
            assertThat(res.getStatus()).isEqualTo(403);
            assertThat(res.getContentAsString()).contains("SUBSCRIPTION_FEATURE_DISABLED");
        }

        @Test
        @DisplayName("an enabled feature still passes (no over-blocking)")
        void enabledFeaturePasses() throws Exception {
            assertThat(run("/api/attendance/today").getStatus()).isEqualTo(200);
        }
    }

    /* ── DemoTrialAgreementFilter ──────────────────────────────────────── */

    @Nested
    @DisplayName("DemoTrialAgreementFilter")
    class AgreementFilter {

        @Mock DemoAccessService demoAccess;

        private MockHttpServletResponse run(String uri) throws Exception {
            String tenant = TestDataService.DEMO_CLIENT_PREFIX + "1";
            when(demoAccess.isDemoClient(tenant)).thenReturn(true);
            DemoRoleAccess w = new DemoRoleAccess();
            w.setSignupId(7);
            w.setEndDate(LocalDate.now().plusDays(10));
            w.setAgreementAcceptedAt(null);                    // NOT accepted yet
            when(demoAccess.forUsername("demo.pastor")).thenReturn(Optional.of(w));

            MockHttpSession session = new MockHttpSession();
            session.setAttribute("appClientId", tenant);
            session.setAttribute("username", "demo.pastor");
            return exercise(new DemoTrialAgreementFilter(demoAccess), session, uri);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "/api/members",
            "/%61pi/members",
            "//api/members",
            "/api;x=1/members",
        })
        @DisplayName("an unaccepted demo login cannot reach /api/* by re-spelling the prefix")
        void encodedApiPrefixStillGated(String uri) throws Exception {
            MockHttpServletResponse res = run(uri);
            assertThat(res.getStatus()).isEqualTo(403);
            assertThat(res.getContentAsString()).contains("DEMO_AGREEMENT_REQUIRED");
        }

        @Test
        @DisplayName("the allow-list still works when spelled plainly")
        void allowListStillPasses() throws Exception {
            assertThat(run("/api/session").getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("an encoded spelling of an allow-listed path is also allowed (same path, same answer)")
        void allowListSpellingIndependent() throws Exception {
            assertThat(run("/%61pi/session").getStatus()).isEqualTo(200);
        }
    }

    /* ── PrivatePageFilter ─────────────────────────────────────────────── */

    @Nested
    @DisplayName("PrivatePageFilter")
    class PrivatePages {

        @Mock PrivateAccessService service;
        @Mock LoginRepository loginRepository;
        @Mock PublicLinkResolver links;

        private MockHttpServletResponse run(String uri) throws Exception {
            // Pick any gated in-app page from the catalog so the test does not hard-code one.
            PrivateAccessSetting enabled = new PrivateAccessSetting();
            enabled.setEnabled(true);
            when(service.getSetting("CHR-1")).thenReturn(enabled);
            PrivateAccessService.Decision blocked = new PrivateAccessService.Decision();
            blocked.status = "BLOCKED"; blocked.allowed = false; blocked.reason = "test";
            when(service.evaluate(eq("CHR-1"), anyString(), anyString(), anyBoolean())).thenReturn(blocked);

            MockHttpSession session = new MockHttpSession();
            session.setAttribute("appClientId", "CHR-1");
            return exercise(new PrivatePageFilter(service, loginRepository, "", links), session, uri);
        }

        @Test
        @DisplayName("an encoded spelling of a gated page is recognised and refused")
        void encodedPageStillGated() throws Exception {
            String page = firstInAppPage();
            String encoded = page.replaceFirst("^/(.)", "/%" + Integer.toHexString(page.charAt(1)));
            assertThat(PrivatePageCatalog.keyForPath(encoded)).as("raw spelling matches nothing").isNull();
            assertThat(run(encoded).getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("the plain spelling is refused too (sanity)")
        void plainPageGated() throws Exception {
            assertThat(run(firstInAppPage()).getStatus()).isEqualTo(403);
        }

        private static String firstInAppPage() {
            for (PrivatePageCatalog.Page p : PrivatePageCatalog.PAGES) {
                if (!p.preAuth() && !p.pathPrefixes().isEmpty()) return p.pathPrefixes().get(0);
            }
            throw new AssertionError("catalog has no in-app page");
        }
    }

    /* ── shared harness ────────────────────────────────────────────────── */

    private static MockHttpServletResponse exercise(Filter filter, MockHttpSession session, String uri)
            throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setSession(session);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(200);
        filter.doFilter(req, res, chain);
        return res;
    }
}
