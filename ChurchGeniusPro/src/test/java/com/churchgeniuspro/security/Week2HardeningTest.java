package com.churchgeniuspro.security;

import com.churchgeniuspro.config.SessionConfig;
import com.churchgeniuspro.controller.GroupMemberController;
import com.churchgeniuspro.controller.StripeSettingsController;
import com.churchgeniuspro.controller.VoiceCommandController;
import com.churchgeniuspro.controller.WhatsAppSettingsController;
import com.churchgeniuspro.repository.GroupMemberRepository;
import com.churchgeniuspro.repository.StripeSettingsRepository;
import com.churchgeniuspro.repository.WhatsAppSettingsRepository;
import com.churchgeniuspro.service.ChurchVoiceSettingService;
import com.churchgeniuspro.service.GroupEmailService;
import com.churchgeniuspro.service.GroupMemberService;
import com.churchgeniuspro.service.OpenAiUsageService;
import com.churchgeniuspro.service.OpenAiVoiceService;
import com.churchgeniuspro.webfilter.CsrfOriginFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Week 2: role guards on credential stores, relay lock-down, CSRF origin check, cookie policy. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Week 2 hardening")
class Week2HardeningTest {

    private static final String OURS = "CHR-ours";

    private static MockHttpServletRequest session(String role, boolean church, boolean member, boolean temp) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("clientId", church ? OURS : (member ? "MBRx" : OURS));
        s.setAttribute("appClientId", OURS);
        s.setAttribute("username", temp ? "temp:9" : (member ? null : "u@" + OURS));
        if (member) { s.setAttribute("role", "Member"); s.setAttribute("memberId", 5); }
        else s.setAttribute("role", role);
        if (church) s.setAttribute("church", true);
        if (temp) s.setAttribute("tempAccessId", 9);
        req.setSession(s);
        return req;
    }
    private static MockHttpServletRequest owner()  { return session("church", true, false, false); }
    private static MockHttpServletRequest admin()  { return session("Admin", false, false, false); }
    private static MockHttpServletRequest member() { return session("Member", false, true, false); }
    private static MockHttpServletRequest temp()   { return session("User", false, false, true); }

    @Nested @DisplayName("credential stores are owner-only")
    class Credentials {
        @Mock StripeSettingsRepository stripeRepo;
        @Mock WhatsAppSettingsRepository waRepo;

        @Test void stripeKeysCannotBeReadOrWrittenByStaffOrMembers() {
            StripeSettingsController c = new StripeSettingsController(stripeRepo);
            for (MockHttpServletRequest r : List.of(admin(), member(), temp())) {
                assertThat(c.get(r).getStatusCode().value()).isEqualTo(403);
                assertThat(c.save(Map.of("publishableKey", "pk", "secretKey", "sk"), r).getStatusCode().value()).isEqualTo(403);
            }
            verify(stripeRepo, never()).save(any());
        }
        @Test void ownerStillManagesStripe() {
            StripeSettingsController c = new StripeSettingsController(stripeRepo);
            when(stripeRepo.findByClientId(OURS)).thenReturn(java.util.Optional.empty());
            assertThat(c.get(owner()).getStatusCode().is2xxSuccessful()).isTrue();
        }
        @Test void whatsAppTokenIsOwnerOnly() {
            WhatsAppSettingsController c = new WhatsAppSettingsController(waRepo);
            assertThat(c.get(member()).getStatusCode().value()).isEqualTo(403);
            assertThat(c.get(admin()).getStatusCode().value()).isEqualTo(403);
            verify(waRepo, never()).save(any());
        }
    }

    @Nested @DisplayName("group mailer only reaches the church's own members")
    class Relay {
        @Mock GroupMemberService memberService;
        @Mock GroupEmailService emailService;
        @Mock GroupMemberRepository memberRepo;

        @Test void foreignAddressesAreDropped() throws Exception {
            GroupMemberController c = new GroupMemberController(memberService, emailService, memberRepo);
            when(memberRepo.findActiveEmailsByAppClientId(OURS)).thenReturn(List.of("a@ours.org"));
            ResponseEntity<?> res = c.sendEmail("Hi", "Body", "[\"a@ours.org\",\"victim@elsewhere.com\"]", null, admin());
            assertThat(res.getStatusCode().is2xxSuccessful()).as("%s", res.getBody()).isTrue();
            verify(emailService).sendBccEmail(anyString(), anyString(), org.mockito.ArgumentMatchers.eq(List.of("a@ours.org")), any(), anyString());
        }
        @Test void nothingIsSentWhenNoAddressIsOurs() throws Exception {
            GroupMemberController c = new GroupMemberController(memberService, emailService, memberRepo);
            when(memberRepo.findActiveEmailsByAppClientId(OURS)).thenReturn(List.of("a@ours.org"));
            ResponseEntity<?> res = c.sendEmail("Hi", "Body", "[\"victim@elsewhere.com\"]", null, admin());
            assertThat(res.getStatusCode().value()).isEqualTo(400);
            verify(emailService, never()).sendBccEmail(anyString(), anyString(), any(), any(), anyString());
        }
        @Test void memberPortalSessionsCannotUseTheMailer() throws Exception {
            GroupMemberController c = new GroupMemberController(memberService, emailService, memberRepo);
            // member with an explicit false on admin.groups is denied; a member with no stored
            // privileges passes the member-permission check by design (opt-in denial) — so
            // assert the explicit case, which is what the modal saves.
            MockHttpServletRequest r = member();
            ((MockHttpSession) r.getSession(false)).setAttribute("memberPrivileges", "{\"admin.groups\":false}");
            assertThat(c.sendEmail("Hi", "Body", "[\"a@ours.org\"]", null, r).getStatusCode().value()).isEqualTo(403);
        }
    }

    @Nested @DisplayName("voice / AI quota is for staff logins")
    class Voice {
        @Mock OpenAiVoiceService voiceService;
        @Mock OpenAiUsageService usageService;
        @Mock ChurchVoiceSettingService voiceFeatures;

        @Test void memberAndTempSessionsAreRefused() {
            VoiceCommandController c = new VoiceCommandController(voiceService, usageService, voiceFeatures);
            MockMultipartFile audio = new MockMultipartFile("audio", "a.webm", "audio/webm", new byte[] {1});
            assertThat(c.command(audio, "income", member()).getStatusCode().value()).isEqualTo(403);
            assertThat(c.command(audio, "income", temp()).getStatusCode().value()).isEqualTo(403);
            verify(voiceService, never()).transcribe(any(), anyString(), anyString());
        }
    }

    @Nested @DisplayName("CSRF origin filter")
    class Csrf {
        final CsrfOriginFilter filter = new CsrfOriginFilter("https://churchgeniuspro.net");

        private int status(String method, String uri, String origin, String referer) throws Exception {
            MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
            req.setRequestURI(uri); req.setServerName("churchgeniuspro.net");
            if (origin != null) req.addHeader("Origin", origin);
            if (referer != null) req.addHeader("Referer", referer);
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(req, res, chain);
            return chain.getRequest() != null ? 200 : res.getStatus();
        }

        @Test void crossSitePostIsRefused() throws Exception {
            assertThat(status("POST", "/api/users", "https://evil.example", null)).isEqualTo(403);
            assertThat(status("DELETE", "/api/families/3", null, "https://evil.example/page")).isEqualTo(403);
            assertThat(status("POST", "/login", "https://evil.example", null)).isEqualTo(403);
        }
        @Test void sameSitePostPasses() throws Exception {
            assertThat(status("POST", "/api/users", "https://churchgeniuspro.net", null)).isEqualTo(200);
            assertThat(status("POST", "/api/users", null, "https://churchgeniuspro.net/viewusers")).isEqualTo(200);
            // A localhost Origin is no longer trusted against a production base-url
            // (audit 2026-10-07, Phase 4.5) — see webfilter/CsrfOriginFilterTest.
            assertThat(status("POST", "/api/users", "http://localhost:8080", null)).isEqualTo(403);
        }
        @Test void getsAndNonBrowserClientsPass() throws Exception {
            assertThat(status("GET", "/api/users", "https://evil.example", null)).isEqualTo(200);
            assertThat(status("POST", "/api/users", null, null)).isEqualTo(200);   // no Origin/Referer → not a browser
        }
        @Test void signedWebhooksAreExempt() throws Exception {
            assertThat(status("POST", "/webhook/sms", "https://evil.example", null)).isEqualTo(200);
            assertThat(status("POST", "/api/plaid/webhook", "https://evil.example", null)).isEqualTo(200);
        }
    }

    @Test @DisplayName("session cookie SameSite is read from configuration")
    void sameSiteFromProperty() {
        assertThat(SessionConfig.normaliseSameSite("strict")).isEqualTo("Strict");
        assertThat(SessionConfig.normaliseSameSite("LAX")).isEqualTo("Lax");
        assertThat(SessionConfig.normaliseSameSite("none")).isEqualTo("None");
        assertThat(SessionConfig.normaliseSameSite("bogus")).isEqualTo("Lax");
        assertThat(SessionConfig.normaliseSameSite(null)).isEqualTo("Lax");
    }
}
