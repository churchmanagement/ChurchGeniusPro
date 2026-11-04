package com.churchgeniuspro.kids;

import com.churchgeniuspro.controller.LoginController;
import com.churchgeniuspro.hibernate.Family;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.RememberMeToken;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.LoginProtectionService;
import com.churchgeniuspro.service.RememberMeService;
import com.churchgeniuspro.service.SecurityAuditService;
import com.churchgeniuspro.util.AppClock;
import com.churchgeniuspro.util.KidsPortalEligibility;
import com.churchgeniuspro.util.PasswordUtil;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Kids Portal 13+ rule (2026-11-04): every way of signing in — password, remembered
 * browser, the app's device sign-in, switching to a linked account — refuses an
 * undated or under-13 child (and, D1, anyone under 13), and the refusal forgets every
 * stored sign-in of that login (D2). A 13+ child and adults are unaffected.
 */
@DisplayName("Kids Portal 13+ — every sign-in path")
class KidsPortalSignInTest {

    static final String PASSWORD = "CorrectHorse7";
    static final String APP_UA = "Mozilla/5.0 (Linux; Android 14) Chrome/130 Mobile ChurchGeniusProApp/1.1 (android)";

    LoginRepository loginRepo; FamilyMemberRepository familyRepo; LoginProtectionService protection;
    RememberMeService remember; MockMvc mvc; LoginController login;

    @BeforeEach
    void setUp() {
        loginRepo = mock(LoginRepository.class); familyRepo = mock(FamilyMemberRepository.class);
        protection = mock(LoginProtectionService.class); remember = mock(RememberMeService.class);
        when(protection.check(any(), anyString())).thenReturn(new LoginProtectionService.GuardResult(false, 0L, false));
        when(familyRepo.findActiveByEmail(anyString())).thenReturn(List.of());
        login = new LoginController(loginRepo, mock(AppUserRepository.class), mock(ChurchRegistrationRepository.class),
                mock(ServiceClientRepository.class), mock(UserPermissionsRepository.class), familyRepo, protection,
                mock(SecurityAuditService.class), mock(com.churchgeniuspro.service.DemoAccessService.class),
                remember, mock(PushSubscriptionRepository.class));
        mvc = MockMvcBuilders.standaloneSetup(login).build();
    }

    /** A member login whose family record says {@code role}, born {@code yearsAgo} years ago (null = no date). */
    SignUp member(int id, String username, String role, Integer yearsAgo) {
        String ref = "MBR-" + username;
        SignUp s = new SignUp(); s.setId(id); s.setUsername(username); s.setPassword(PasswordUtil.encode(PASSWORD));
        s.setClientId(ref); s.setChurch(false); s.setActive(true); s.setDeleted(false); s.setLinkGroup("LG-1");
        when(loginRepo.findByUsernameAndDeletedFalse(username)).thenReturn(Optional.of(s));
        when(loginRepo.findById(id)).thenReturn(Optional.of(s));
        when(loginRepo.findByClientId(ref)).thenReturn(Optional.of(s));
        Family f = new Family(); f.setAppClientId("CHR-1");
        FamilyMember fm = new FamilyMember(); fm.setMemberRef(ref); fm.setFamily(f); fm.setFirstName("Pat");
        fm.setLastName("Test"); fm.setRole(role); fm.setId(100 + id);
        if (yearsAgo != null) {
            LocalDate dob = AppClock.today().minusYears(yearsAgo).minusDays(1);
            fm.setBirthdayYear(dob.getYear()); fm.setBirthdayMonth(dob.getMonthValue()); fm.setBirthdayDay(dob.getDayOfMonth());
        }
        when(familyRepo.findByMemberRef(ref)).thenReturn(Optional.of(fm));
        return s;
    }

    org.springframework.test.web.servlet.ResultActions password(String username) throws Exception {
        return mvc.perform(post("/login").contentType("application/json")
                .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\",\"rememberMe\":true}"));
    }

    @Test @DisplayName("password sign-in: undated child and under-13 child refused (403, a denial not a failure), stored sign-ins revoked")
    void passwordSignIn() throws Exception {
        SignUp undated = member(1, "nodob", "Child", null);
        password("nodob").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(KidsPortalEligibility.CODE))
                .andExpect(jsonPath("$.message").value(KidsPortalEligibility.MISSING_DOB_MESSAGE));
        verify(protection).recordDenied(any(), eq("nodob"), eq("KIDS_PORTAL_AGE"), eq(undated.getClientId()), eq("/login"));
        verify(protection, never()).recordFailure(any(), anyString(), anyString(), any(), anyString());
        verify(remember).revokeAllFor(1);
        verify(remember, never()).issue(any(), any(), any());

        member(2, "ten", "Son", 10);
        password("ten").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(KidsPortalEligibility.UNDER_13_KIDS_MESSAGE));
        verify(remember).revokeAllFor(2);
    }

    @Test @DisplayName("D1: an under-13 person on an adult role is refused; an adult with no birth date is not")
    void d1() throws Exception {
        member(3, "younghead", "Adult", 9);
        password("younghead").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(KidsPortalEligibility.UNDER_13_MEMBER_MESSAGE));
        member(4, "nodobadult", "Head of Household", null);
        password("nodobadult").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("success"));
    }

    @Test @DisplayName("a 13+ child signs in to the Kids Portal as before")
    void teenAllowed() throws Exception {
        member(5, "teen", "Child", 13);
        password("teen").andExpect(status().isOk()).andExpect(jsonPath("$.memberRole").value("Child"));
        verify(protection, never()).recordDenied(any(), anyString(), eq("KIDS_PORTAL_AGE"), any(), anyString());
    }

    @Test @DisplayName("remembered browser: refused with the reason, cookie and every stored sign-in revoked, no session")
    void rememberedBrowser() throws Exception {
        SignUp kid = member(6, "remkid", "Child", 8);
        when(remember.resolve(any())).thenReturn(Optional.of(new RememberMeService.Resolved(kid, new RememberMeToken())));
        var result = mvc.perform(get("/api/auth/check-remember").cookie(new Cookie(RememberMeService.COOKIE_NAME, "abc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("none"))
                .andExpect(jsonPath("$.code").value(KidsPortalEligibility.CODE))
                .andReturn();
        verify(remember).revokeCurrent(any(), any());
        verify(remember).revokeAllFor(6);
        verify(remember, never()).rotate(any(), any());
        org.assertj.core.api.Assertions.assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test @DisplayName("app device sign-in: refused, that device secret and every other stored sign-in revoked")
    void deviceSignIn() throws Exception {
        SignUp kid = member(7, "devkid", "Adult", 11);
        when(remember.resolveSecret(eq("secret"), any())).thenReturn(Optional.of(new RememberMeService.Resolved(kid, new RememberMeToken())));
        mvc.perform(post("/api/auth/device-login").header("User-Agent", APP_UA).contentType("application/json")
                        .content("{\"token\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("none"))
                .andExpect(jsonPath("$.message").value(KidsPortalEligibility.UNDER_13_MEMBER_MESSAGE));
        verify(remember).revokeSecret("secret");
        verify(remember).revokeAllFor(7);
        verify(remember, never()).rotateSecret(any());
    }

    @Test @DisplayName("switch account: switching to a linked under-13 login is refused and the current session survives")
    void switchAccount() throws Exception {
        member(8, "parent", "Head of Household", 40);
        SignUp kid = member(9, "kidlinked", "Daughter", 12);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", "parent"); session.setAttribute("clientId", "MBR-parent");
        mvc.perform(post("/api/auth/switch-account").session(session).contentType("application/json")
                        .content("{\"targetSignupId\":" + kid.getId() + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(KidsPortalEligibility.CODE));
        org.assertj.core.api.Assertions.assertThat(session.isInvalid()).isFalse();
        org.assertj.core.api.Assertions.assertThat(session.getAttribute("username")).isEqualTo("parent");
    }

    @Test @DisplayName("staff and church logins are never age-checked; a lookup error does not block (no Kids Portal session can be built then)")
    void scopeAndLookupError() {
        SignUp staff = new SignUp(); staff.setClientId("USR-1");
        SignUp church = new SignUp(); church.setClientId("CHR-1");
        org.assertj.core.api.Assertions.assertThat(callAgeRefusal(staff)).isNull();
        org.assertj.core.api.Assertions.assertThat(callAgeRefusal(church)).isNull();
        SignUp m = new SignUp(); m.setClientId("MBR-x"); m.setId(77);
        when(familyRepo.findByMemberRef("MBR-x")).thenThrow(new RuntimeException("db down"));
        org.assertj.core.api.Assertions.assertThat(callAgeRefusal(m)).isNull();
    }

    private String callAgeRefusal(SignUp s) {
        try {
            var m = LoginController.class.getDeclaredMethod("ageRefusal", SignUp.class);
            m.setAccessible(true);
            return (String) m.invoke(login, s);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
}
