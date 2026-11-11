package com.churchgeniuspro.security;

import com.churchgeniuspro.controller.ForgotPasswordController;
import com.churchgeniuspro.controller.LoginController;
import com.churchgeniuspro.controller.SessionController;
import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.PasswordResetToken;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.*;
import com.churchgeniuspro.util.CredentialSessions;
import com.churchgeniuspro.util.PasswordUtil;
import com.churchgeniuspro.webfilter.AuthFilter;
import com.churchgeniuspro.webfilter.ServiceAdminAuthFilter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * App Store security review (2026-11-11): a password change or reset must end every other session of that login —
 * every browser, device and app instance — and no earlier session cookie, remember-me / device token or personal
 * access token may get back in. Runs the REAL /login, /api/reset-password, /api/session and AuthFilter.
 */
@DisplayName("Password change / reset ends every other session")
class PasswordChangeEndsSessionsTest {

    static final String OLD = "CorrectHorse7!", NEW = "BatteryStaple9!";

    @RestController static class Protected { @GetMapping("/api/ping") String ping() { return "pong"; } }

    InMemoryRememberMeTokens store;
    LoginRepository loginRepo;
    AppUserRepository appUsers;
    PersonalAccessTokenRepository pats;
    MockMvc instance1, instance2;
    PasswordResetToken resetToken;

    @BeforeEach
    void setUp() {
        loginRepo = mock(LoginRepository.class);
        store = new InMemoryRememberMeTokens(loginRepo);
        for (Object[] u : new Object[][] { {7, "alice@example.org", "MBR-alice"}, {8, "bob@example.org", "MBR-bob"}, {9, "staff@example.org", "USR-staff"} }) {
            SignUp s = store.signup((Integer) u[0], (String) u[1], (String) u[2]);
            s.setPassword(PasswordUtil.encode(OLD)); s.setChurch(false);
        }
        when(loginRepo.findByUsernameAndDeletedFalse(anyString())).thenAnswer(i -> byUser(i.getArgument(0)));
        when(loginRepo.findActiveByUsername(anyString())).thenAnswer(i -> byUser(i.getArgument(0)));
        when(loginRepo.findByClientId(anyString())).thenAnswer(i -> store.logins.values().stream()
                .filter(s -> s.getClientId().equals(i.getArgument(0)) && !Boolean.TRUE.equals(s.getDeleted())).findFirst());
        when(loginRepo.findLiveCredentialVersion(anyInt())).thenAnswer(i -> {
            SignUp s = store.logins.get((Integer) i.getArgument(0));
            return s == null || Boolean.TRUE.equals(s.getDeleted()) ? null : s.getCredentialVersion();
        });
        appUsers = mock(AppUserRepository.class);
        AppUser staff = new AppUser(); staff.setEnabled(true); staff.setDeleteFlag(false);
        when(appUsers.findById(90)).thenReturn(Optional.of(staff));
        pats = mock(PersonalAccessTokenRepository.class);
        when(pats.revokeAllForUsername(anyString(), anyString(), any())).thenReturn(2);

        FamilyMemberRepository familyRepo = mock(FamilyMemberRepository.class);
        when(familyRepo.findByMemberRef(anyString())).thenReturn(Optional.empty());
        when(familyRepo.findActiveByEmail(anyString())).thenReturn(List.of());
        LoginProtectionService protection = mock(LoginProtectionService.class);
        when(protection.check(any(), anyString())).thenReturn(new LoginProtectionService.GuardResult(false, 0L, false));
        RememberMeService rememberMe = new RememberMeService(store.tokens, store.loginRepo);
        LoginController login = new LoginController(loginRepo, appUsers, mock(ChurchRegistrationRepository.class),
                mock(ServiceClientRepository.class), mock(UserPermissionsRepository.class), familyRepo, protection,
                mock(SecurityAuditService.class), mock(DemoAccessService.class), rememberMe, mock(PushSubscriptionRepository.class));

        PasswordResetTokenRepository tokenRepo = mock(PasswordResetTokenRepository.class);
        resetToken = new PasswordResetToken(); resetToken.setToken("tok-1"); resetToken.setUsername("alice@example.org");
        resetToken.setExpiryTime(new Date(System.currentTimeMillis() + 600_000));
        when(tokenRepo.findByToken(PasswordResetService.hash("tok-1"))).thenAnswer(i -> Optional.of(resetToken));   // stored hashed
        ForgotPasswordController forgot = new ForgotPasswordController(loginRepo, appUsers, mock(ChurchRegistrationRepository.class),
                mock(FamilyMemberRepository.class), mock(ServiceClientRepository.class), tokenRepo, mock(EmailService.class),
                mock(PasswordResetService.class), protection, rememberMe);
        ReflectionTestUtils.setField(forgot, "credentialChanges", new CredentialChangeService(rememberMe, pats));

        // Two app instances sharing the same database (the repositories) and the same session store (the session objects).
        instance1 = mvc(login, forgot);
        instance2 = mvc(login, forgot);
    }

    MockMvc mvc(LoginController login, ForgotPasswordController forgot) {
        return MockMvcBuilders.standaloneSetup(login, forgot, new SessionController(mock(AppUserRepository.class)), new Protected())
                .addFilter(new AuthFilter(appUsers, loginRepo), "/api/*").build();   // as FilterConfig registers it
    }

    Optional<SignUp> byUser(String u) { return store.logins.values().stream().filter(s -> s.getUsername().equalsIgnoreCase(u)).findFirst(); }

    MvcResult signIn(MockMvc m, String user, String pw, boolean remember) throws Exception {
        return m.perform(post("/login").contentType("application/json")
                .content("{\"username\":\"" + user + "\",\"password\":\"" + pw + "\",\"rememberMe\":" + remember + "}")).andReturn();
    }
    MockHttpSession browser(MockMvc m, String user, String pw) throws Exception {
        MvcResult r = signIn(m, user, pw, false);
        assertThat(r.getResponse().getStatus()).as("sign in " + user).isEqualTo(200);
        return (MockHttpSession) r.getRequest().getSession(false);
    }
    int ping(MockMvc m, MockHttpSession s) throws Exception { return m.perform(get("/api/ping").session(s)).andReturn().getResponse().getStatus(); }
    void reset(MockMvc m, String pw) throws Exception {
        m.perform(post("/api/reset-password").contentType("application/json").content("{\"token\":\"tok-1\",\"newPassword\":\"" + pw + "\"}"))
                .andExpect(status().isOk());
    }

    @Test @DisplayName("1+7: signed in on Browser A and Browser B (different app instances); a reset ends BOTH, on the API and on /api/session")
    void resetEndsEverySession() throws Exception {
        MockHttpSession a = browser(instance1, "alice@example.org", OLD);
        MockHttpSession b = browser(instance2, "alice@example.org", OLD);
        assertThat(a.getAttribute(CredentialSessions.ATTR)).as("login stamps the version").isEqualTo(0);
        assertThat(ping(instance1, a)).isEqualTo(200);
        assertThat(ping(instance2, b)).isEqualTo(200);

        reset(instance1, NEW);
        assertThat(store.logins.get(7).getCredentialVersion()).isEqualTo(1);

        instance2.perform(get("/api/ping").session(b)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGED"));
        assertThat(b.isInvalid()).as("session destroyed on the server, not just refused").isTrue();
        MockHttpSession a2 = a;   // the other browser, on the other instance, via the page-load check
        instance1.perform(get("/api/session").session(a2)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGED"));
        assertThat(a2.isInvalid()).isTrue();
    }

    @Test @DisplayName("2+5: after the change the old password fails, the new one signs in and stays signed in")
    void newPasswordWorks() throws Exception {
        reset(instance1, NEW);
        assertThat(signIn(instance1, "alice@example.org", OLD, false).getResponse().getStatus()).isEqualTo(401);
        MockHttpSession fresh = browser(instance2, "alice@example.org", NEW);
        assertThat(fresh.getAttribute(CredentialSessions.ATTR)).isEqualTo(1);
        assertThat(ping(instance1, fresh)).isEqualTo(200);
        assertThat(ping(instance2, fresh)).isEqualTo(200);
        instance1.perform(get("/api/session").session(fresh)).andExpect(status().isOk());
    }

    @Test @DisplayName("3+8: remember-me / mobile device tokens and personal access tokens issued before the reset are revoked; Remember me works again afterwards")
    void tokensCannotBeReused() throws Exception {
        Cookie remembered = signIn(instance1, "alice@example.org", OLD, true).getResponse().getCookie(RememberMeService.COOKIE_NAME);
        assertThat(remembered).isNotNull();
        assertThat(store.active(7)).isEqualTo(1);
        reset(instance1, NEW);
        assertThat(store.active(7)).as("every remembered device revoked").isZero();
        String body = instance2.perform(get("/api/auth/check-remember").cookie(remembered)).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("\"status\":\"success\"");
        verify(pats).revokeAllForUsername(eq("alice@example.org"), eq("password-reset"), any());
        Cookie again = signIn(instance1, "alice@example.org", NEW, true).getResponse().getCookie(RememberMeService.COOKIE_NAME);
        assertThat(instance1.perform(get("/api/auth/check-remember").cookie(again)).andReturn().getResponse().getContentAsString())
                .contains("\"status\":\"success\"");
    }

    @Test @DisplayName("6: other users' sessions are untouched")
    void otherUsersUnaffected() throws Exception {
        MockHttpSession alice = browser(instance1, "alice@example.org", OLD);
        MockHttpSession bob = browser(instance1, "bob@example.org", OLD);
        reset(instance1, NEW);
        assertThat(ping(instance1, alice)).isEqualTo(401);
        assertThat(ping(instance2, bob)).isEqualTo(200);
        assertThat(store.logins.get(8).getCredentialVersion()).isZero();
        verify(pats, never()).revokeAllForUsername(eq("bob@example.org"), anyString(), any());
    }

    @Test @DisplayName("staff session (app_user path) ends too; a session from before this release (no stamp, no login id) is ended by the next change")
    void staffAndPreDeploySessions() throws Exception {
        MockHttpSession staff = new MockHttpSession();        // what buildResponse leaves, minus anything new
        staff.setAttribute("clientId", "USR-staff"); staff.setAttribute("username", "staff@example.org"); staff.setAttribute("appUserId", 90);
        assertThat(ping(instance1, staff)).as("old session, password unchanged → still fine").isEqualTo(200);
        assertThat(staff.getAttribute("loginId")).as("login id resolved once and kept").isEqualTo(9);
        SignUp row = store.logins.get(9);
        new CredentialChangeService(null, pats).beforeSave(row);   // e.g. Service Admin demo reset / account claim
        assertThat(ping(instance2, staff)).isEqualTo(401);
    }

    @Test @DisplayName("a stale session cannot be swapped for a fresh one through the public /api/auth paths (switch account)")
    void noRouteAround() throws Exception {
        MockHttpSession a = browser(instance1, "alice@example.org", OLD);
        reset(instance1, NEW);
        instance2.perform(post("/api/auth/switch-account").session(a).contentType("application/json").content("{\"targetSignupId\":8}"));
        assertThat(a.isInvalid()).as("destroyed before the handler ran").isTrue();
    }

    @Test @DisplayName("temporary-access and NTAG sessions (no password) are not affected; an unidentifiable login fails closed")
    void exemptionsAndFailClosed() {
        MockHttpSession temp = new MockHttpSession(); temp.setAttribute("clientId", "MBR-alice"); temp.setAttribute("tempAccessId", 5L);
        assertThat(CredentialSessions.isCurrent(temp, loginRepo, null)).isTrue();
        MockHttpSession ntag = new MockHttpSession(); ntag.setAttribute("clientId", "MBR-alice"); ntag.setAttribute("ntagCredId", 3);
        assertThat(CredentialSessions.isCurrent(ntag, loginRepo, null)).isTrue();
        MockHttpSession unknown = new MockHttpSession(); unknown.setAttribute("clientId", "MBR-nobody"); unknown.setAttribute("username", "x");
        assertThat(CredentialSessions.isCurrent(unknown, loginRepo, null)).isFalse();
        MockHttpSession wrongUser = new MockHttpSession(); wrongUser.setAttribute("clientId", "MBR-alice"); wrongUser.setAttribute("username", "bob@example.org");
        assertThat(CredentialSessions.isCurrent(wrongUser, loginRepo, null)).isFalse();
    }

    @Test @DisplayName("Service Admin: a password change ends its other sessions; a removed admin is refused; others pass")
    void serviceAdmin() throws Exception {
        ServiceAdminRepository admins = mock(ServiceAdminRepository.class);
        ServiceAdminAuthFilter f = new ServiceAdminAuthFilter(admins);
        MockHttpSession s = new MockHttpSession(); s.setAttribute("serviceAdminId", 1);
        s.setAttribute(CredentialSessions.SERVICE_ADMIN_ATTR, 0);
        when(admins.findLiveCredentialVersion(1)).thenReturn(0);
        assertThat(run(f, s)).isEqualTo(200);
        when(admins.findLiveCredentialVersion(1)).thenReturn(1);
        assertThat(run(f, s)).isEqualTo(401);
        assertThat(s.isInvalid()).isTrue();
        MockHttpSession gone = new MockHttpSession(); gone.setAttribute("serviceAdminId", 2);
        when(admins.findLiveCredentialVersion(2)).thenReturn(null);
        assertThat(run(f, gone)).isEqualTo(401);
    }

    int run(ServiceAdminAuthFilter f, MockHttpSession s) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/serviceadmin/clients"); req.setSession(s);
        MockHttpServletResponse res = new MockHttpServletResponse(); MockFilterChain chain = new MockFilterChain();
        f.doFilter(req, res, chain);
        return chain.getRequest() != null ? 200 : res.getStatus();
    }

    @Test @DisplayName("every place that sets a new password on an existing login raises the version; new accounts start at 0")
    void wiring() throws Exception {
        String root = "src/main/java/com/churchgeniuspro/";
        assertThat(read(root + "controller/ForgotPasswordController.java")).contains("credentialChanges.beforeSave(user);", "credentialChanges.afterSave(user, \"reset\");");
        assertThat(read(root + "controller/ServiceAdminController.java")).contains("admin.get().markCredentialChanged();", "CredentialSessions.SERVICE_ADMIN_ATTR");
        assertThat(read(root + "controller/SignupController.java")).contains("credentialChanges.beforeSave(provisioned)");
        String demo = read(root + "service/TestDataService.java");
        assertThat(demo).contains("credentialChanges.afterSave(su, \"reissue\")", "credentialChanges.afterSave(su, \"admin-reset\")");
        assertThat(read(root + "controller/LoginController.java")).contains("CredentialSessions.stamp(session, user);");
        assertThat(read(root + "webfilter/FilterConfig.java")).contains("new ServiceAdminAuthFilter(serviceAdminRepository)");
        assertThat(new SignUp().getCredentialVersion()).isZero();
        assertThat(read("scripts/sql/migrate_production.sql")).contains("ADD COLUMN IF NOT EXISTS credential_version INTEGER NOT NULL DEFAULT 0");
    }

    static String read(String p) throws Exception { return java.nio.file.Files.readString(java.nio.file.Path.of(p)); }
}
