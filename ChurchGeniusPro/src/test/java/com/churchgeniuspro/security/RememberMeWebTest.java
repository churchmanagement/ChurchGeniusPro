package com.churchgeniuspro.security;

import com.churchgeniuspro.controller.LoginController;
import com.churchgeniuspro.controller.SessionController;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.PushSubscriptionRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.UserPermissionsRepository;
import com.churchgeniuspro.service.LoginProtectionService;
import com.churchgeniuspro.service.RememberMeService;
import com.churchgeniuspro.service.SecurityAuditService;
import com.churchgeniuspro.util.PasswordUtil;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Mobile Phase 1, M2 + M3 through the real endpoints: per-device Remember me on
 * {@code /login} and {@code /api/auth/check-remember}, device-scoped revocation on
 * {@code /api/auth/logout} and {@code /api/logout}, and push deactivation on logout.
 */
class RememberMeWebTest {

    private static final String PASSWORD = "CorrectHorse7";
    private static final String HASH     = PasswordUtil.encode(PASSWORD);

    private InMemoryRememberMeTokens   store;
    private PushSubscriptionRepository pushRepo;
    private SecurityAuditService       audit;
    private MockMvc                    mvc;

    @BeforeEach
    void setUp() {
        LoginRepository loginRepo = mock(LoginRepository.class);
        store    = new InMemoryRememberMeTokens(loginRepo);
        pushRepo = mock(PushSubscriptionRepository.class);
        audit    = mock(SecurityAuditService.class);

        SignUp member = store.signup(7, "member@example.org", "MBR-token-1");
        member.setPassword(HASH);
        member.setChurch(false);
        when(loginRepo.findByUsernameAndDeletedFalse("member@example.org")).thenReturn(Optional.of(member));

        FamilyMemberRepository familyRepo = mock(FamilyMemberRepository.class);
        when(familyRepo.findByMemberRef(anyString())).thenReturn(Optional.empty());
        when(familyRepo.findActiveByEmail(anyString())).thenReturn(List.of());

        LoginProtectionService protection = mock(LoginProtectionService.class);
        when(protection.check(any(), anyString()))
                .thenReturn(new LoginProtectionService.GuardResult(false, 0L, false));

        RememberMeService rememberMe = new RememberMeService(store.tokens, store.loginRepo);

        LoginController login = new LoginController(
                loginRepo, mock(AppUserRepository.class), mock(ChurchRegistrationRepository.class),
                mock(ServiceClientRepository.class), mock(UserPermissionsRepository.class), familyRepo,
                protection, audit, mock(com.churchgeniuspro.service.DemoAccessService.class),
                rememberMe, pushRepo);
        SessionController sessionCtl = new SessionController(mock(AppUserRepository.class));
        ReflectionTestUtils.setField(sessionCtl, "loginController", login);

        mvc = MockMvcBuilders.standaloneSetup(login, sessionCtl).build();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Cookie login(boolean rememberMe, String ua) throws Exception {
        MvcResult r = mvc.perform(post("/login").contentType("application/json")
                        .header("User-Agent", ua)
                        .content("{\"username\":\"member@example.org\",\"password\":\"" + PASSWORD
                                 + "\",\"rememberMe\":" + rememberMe + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andReturn();
        return r.getResponse().getCookie(RememberMeService.COOKIE_NAME);
    }

    private MvcResult checkRemember(Cookie cookie) throws Exception {
        var req = get("/api/auth/check-remember");
        if (cookie != null) req = req.cookie(cookie);
        return mvc.perform(req).andExpect(status().isOk()).andReturn();
    }

    private static String statusOf(MvcResult r) throws Exception {
        String body = r.getResponse().getContentAsString();
        return body.contains("\"status\":\"success\"") ? "success" : "none";
    }

    // ── M2 ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Remember me on a phone and a laptop: both auto-login, independently")
    void twoDevices() throws Exception {
        Cookie phone  = login(true, "Mozilla/5.0 (iPhone) Safari/605");
        Cookie laptop = login(true, "Mozilla/5.0 (Windows NT 10.0) Chrome/130");
        assertThat(phone.getValue()).isNotEqualTo(laptop.getValue());
        assertThat(phone.isHttpOnly()).isTrue();

        assertThat(statusOf(checkRemember(phone))).isEqualTo("success");
        assertThat(statusOf(checkRemember(laptop))).isEqualTo("success");
        assertThat(store.active(7)).isEqualTo(2);
        assertThat(store.rows.values()).extracting("deviceLabel")
                .containsExactlyInAnyOrder("Safari on iPhone", "Chrome on Windows");
        // The old single column is never written any more.
        assertThat(store.logins.get(7).getRemember()).isNull();
        verify(audit, times(4)).recordLogin(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("auto-login rotates the cookie; the new value keeps working")
    void checkRememberRotates() throws Exception {
        Cookie first = login(true, "x");
        MvcResult r = checkRemember(first);
        Cookie second = r.getResponse().getCookie(RememberMeService.COOKIE_NAME);
        assertThat(second).isNotNull();
        assertThat(second.getValue()).isNotEqualTo(first.getValue());
        assertThat(second.getMaxAge()).isEqualTo(30 * 24 * 60 * 60);
        assertThat(statusOf(checkRemember(second))).isEqualTo("success");
    }

    @Test
    @DisplayName("logging in WITHOUT the box clears only this browser — the other device stays remembered")
    void loginWithoutBoxIsDeviceScoped() throws Exception {
        Cookie laptop = login(true, "Windows");
        Cookie phone  = login(true, "iPhone");

        // Phone logs in again with the box unticked, presenting its own cookie.
        MvcResult r = mvc.perform(post("/login").contentType("application/json").cookie(phone)
                        .content("{\"username\":\"member@example.org\",\"password\":\"" + PASSWORD + "\",\"rememberMe\":false}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getCookie(RememberMeService.COOKIE_NAME).getMaxAge()).isZero();

        assertThat(statusOf(checkRemember(phone))).isEqualTo("none");
        assertThat(statusOf(checkRemember(laptop))).as("laptop untouched (the old code wiped it)").isEqualTo("success");
    }

    @Test
    @DisplayName("/api/auth/logout revokes this device only")
    void logoutIsDeviceScoped() throws Exception {
        Cookie laptop = login(true, "Windows");
        Cookie phone  = login(true, "iPhone");

        mvc.perform(post("/api/auth/logout").cookie(phone)).andExpect(status().isOk())
           .andExpect(cookie().maxAge(RememberMeService.COOKIE_NAME, 0));

        assertThat(statusOf(checkRemember(phone))).isEqualTo("none");
        assertThat(statusOf(checkRemember(laptop))).isEqualTo("success");
    }

    @Test
    @DisplayName("a tampered or unknown cookie is cleared and never signs anyone in")
    void tamperedCookieCleared() throws Exception {
        Cookie real = login(true, "x");
        Cookie bad  = new Cookie(RememberMeService.COOKIE_NAME, real.getValue() + "Z");
        MvcResult r = checkRemember(bad);
        assertThat(statusOf(r)).isEqualTo("none");
        assertThat(r.getResponse().getCookie(RememberMeService.COOKIE_NAME).getMaxAge()).isZero();
        assertThat(statusOf(checkRemember(real))).as("the real one is unaffected").isEqualTo("success");
    }

    @Test
    @DisplayName("a cookie from the old signup.remember column still works once, then is a per-device row")
    void legacyCookieBridged() throws Exception {
        store.logins.get(7).setRemember("legacy-uuid-1234");
        Cookie legacy = new Cookie(RememberMeService.COOKIE_NAME, "legacy-uuid-1234");
        MvcResult r = checkRemember(legacy);
        assertThat(statusOf(r)).isEqualTo("success");
        Cookie fresh = r.getResponse().getCookie(RememberMeService.COOKIE_NAME);
        assertThat(fresh.getValue()).isNotEqualTo("legacy-uuid-1234");
        assertThat(store.logins.get(7).getRemember()).isNull();
        assertThat(store.active(7)).isEqualTo(1);
        assertThat(statusOf(checkRemember(fresh))).isEqualTo("success");
    }

    @Test
    @DisplayName("no cookie → status none, nothing touched")
    void noCookie() throws Exception {
        assertThat(statusOf(checkRemember(null))).isEqualTo("none");
        assertThat(store.rows).isEmpty();
    }

    // ── M3 ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("logout deactivates the push subscription the page names, on both logout endpoints")
    void logoutDeactivatesPush() throws Exception {
        login(true, "x");
        mvc.perform(post("/api/auth/logout").contentType("application/json")
                        .content("{\"pushEndpoint\":\"https://fcm.googleapis.com/fcm/send/abc\"}"))
                .andExpect(status().isOk());
        verify(pushRepo).deactivateByEndpoint("https://fcm.googleapis.com/fcm/send/abc");

        mvc.perform(post("/api/logout").contentType("application/json")
                        .content("{\"pushEndpoint\":\"https://web.push.apple.com/xyz\"}"))
                .andExpect(status().isOk());
        verify(pushRepo).deactivateByEndpoint("https://web.push.apple.com/xyz");
    }

    @Test
    @DisplayName("logout without a body, or with an absurd endpoint, still logs out and touches no push row")
    void logoutWithoutEndpoint() throws Exception {
        mvc.perform(post("/api/auth/logout")).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").contentType("application/json").content("{}")).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").contentType("application/json")
                .content("{\"pushEndpoint\":\"" + "x".repeat(3000) + "\"}")).andExpect(status().isOk());
        verify(pushRepo, never()).deactivateByEndpoint(anyString());
    }

    @Test
    @DisplayName("/api/logout now revokes the remember-me credential like /api/auth/logout")
    void sessionLogoutRevokesRememberMe() throws Exception {
        Cookie c = login(true, "x");
        MockHttpServletResponse res = mvc.perform(post("/api/logout").cookie(c)).andExpect(status().isOk())
                .andReturn().getResponse();
        assertThat(res.getCookie(RememberMeService.COOKIE_NAME).getMaxAge()).isZero();
        assertThat(statusOf(checkRemember(c))).isEqualTo("none");
    }
}
