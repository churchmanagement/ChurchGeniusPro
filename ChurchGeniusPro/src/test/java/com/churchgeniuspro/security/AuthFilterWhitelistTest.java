package com.churchgeniuspro.security;

import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.webfilter.AuthFilter;
import com.churchgeniuspro.webfilter.ServiceAdminAuthFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two filters that decide whether an anonymous request reaches a controller.
 *
 * <p>AuthFilter's whitelist used to match by string prefix, so "/api/public" also
 * exempted "/api/public-screens" (the admin API for minting public links), and
 * "/api/sms-opt-in" — the admin listing — was on the list outright. Matching is now
 * by path segment. ServiceAdminAuthFilter is new: the service-admin prefix is
 * exempt from AuthFilter by design, and the controller behind it had no check.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Anonymous request gating")
class AuthFilterWhitelistTest {

    @Mock AppUserRepository appUserRepo;
    @Mock LoginRepository   loginRepo;

    private int statusFor(AuthFilter f, String uri, MockHttpSession session) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        if (session != null) req.setSession(session);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(req, res, chain);
        // MockFilterChain records the request only when the filter let it through
        return chain.getRequest() != null ? 200 : res.getStatus();
    }

    @Nested
    @DisplayName("AuthFilter whitelist is by path segment, not string prefix")
    class Whitelist {
        final AuthFilter filter = new AuthFilter(appUserRepo, loginRepo);

        @Test void publicDonateConfigStaysPublic() throws Exception {
            assertThat(statusFor(filter, "/api/public/donate/config", null)).isEqualTo(200);
        }
        @Test void publicScreensAdminApiNowRequiresSession() throws Exception {
            assertThat(statusFor(filter, "/api/public-screens", null)).isEqualTo(401);
            assertThat(statusFor(filter, "/api/public-screens/17", null)).isEqualTo(401);
            assertThat(statusFor(filter, "/api/public-screens/pages", null)).isEqualTo(401);
        }
        @Test void smsOptInAdminListingNowRequiresSession() throws Exception {
            assertThat(statusFor(filter, "/api/sms-opt-in", null)).isEqualTo(401);
            assertThat(statusFor(filter, "/api/sms-opt-in/3", null)).isEqualTo(401);
        }
        @Test void smsOptInPublicFormStaysPublic() throws Exception {
            assertThat(statusFor(filter, "/api/public/sms-opt-in", null)).isEqualTo(200);
            assertThat(statusFor(filter, "/api/public/sms-opt-in/church-name", null)).isEqualTo(200);
        }
        @Test void eventCalendarPublicEndpointsStayPublicIndividually() throws Exception {
            for (String p : new String[] {"public-church-info", "public-logo", "public-events", "public-ics"}) {
                assertThat(statusFor(filter, "/api/event-calendar/" + p, null)).as(p).isEqualTo(200);
            }
            assertThat(statusFor(filter, "/api/event-calendar/events", null)).isEqualTo(401);
        }
        @Test void retiredMidRegMeetEndpointsAreNoLongerPublic() throws Exception {
            // Midwest Region Meet was retired: its former public prefix is no longer whitelisted.
            assertThat(statusFor(filter, "/api/mid-reg-meet/public/config", null)).isEqualTo(401);
            assertThat(statusFor(filter, "/api/mid-reg-meet/public-link", null)).isEqualTo(401);
        }
        @Test void serviceAdminPrefixIsLeftToItsOwnFilter() throws Exception {
            assertThat(statusFor(filter, "/api/serviceadmin/clients", null)).isEqualTo(200);
            // but nothing else starting with "/api/service" is exempt any more
            assertThat(statusFor(filter, "/api/services", null)).isEqualTo(401);
        }
        @Test void aTenantSessionStillPasses() throws Exception {
            MockHttpSession s = new MockHttpSession();
            s.setAttribute("clientId", "CHR-x");
            s.setAttribute("tempAccessId", 1);   // skip the DB re-validation branch
            assertThat(statusFor(filter, "/api/public-screens", s)).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("ServiceAdminAuthFilter")
    class ServiceAdmin {
        final ServiceAdminAuthFilter filter = new ServiceAdminAuthFilter();

        private int status(String uri, MockHttpSession session) throws Exception {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
            req.setRequestURI(uri);
            if (session != null) req.setSession(session);
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(req, res, chain);
            return chain.getRequest() != null ? 200 : res.getStatus();
        }

        @Test void anonymousIsRefused() throws Exception {
            assertThat(status("/api/serviceadmin/clients", null)).isEqualTo(401);
            assertThat(status("/api/serviceadmin/backup/restore", null)).isEqualTo(401);
        }
        @Test void loginItselfIsOpen() throws Exception {
            assertThat(status("/api/serviceadmin/login", null)).isEqualTo(200);
        }
        @Test void aTenantUserWithRoleStringServiceAdminIsRefused() throws Exception {
            // app_user.role is free text and is copied into the session verbatim; the
            // role string must never be what unlocks the service-admin API.
            MockHttpSession s = new MockHttpSession();
            s.setAttribute("clientId", "CHR-x");
            s.setAttribute("username", "owner@church.org");
            s.setAttribute("role", "ServiceAdmin");
            assertThat(status("/api/serviceadmin/clients", s)).isEqualTo(401);
        }
        @Test void aRealServiceAdminSessionPasses() throws Exception {
            MockHttpSession s = new MockHttpSession();
            s.setAttribute("serviceAdminId", 1);
            assertThat(status("/api/serviceadmin/clients", s)).isEqualTo(200);
        }
    }
}
