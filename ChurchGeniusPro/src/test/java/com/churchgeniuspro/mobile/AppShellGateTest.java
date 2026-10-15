package com.churchgeniuspro.mobile;

import com.churchgeniuspro.controller.LoginController;
import com.churchgeniuspro.controller.WellKnownController;
import com.churchgeniuspro.hibernate.Family;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.LoginProtectionService;
import com.churchgeniuspro.service.RememberMeService;
import com.churchgeniuspro.service.SecurityAuditService;
import com.churchgeniuspro.util.PasswordUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Mobile Phase 3: the app shell is an adults-only client (M6 decision). A child
 * (Kids-portal) login is refused from the app with a specific message and left
 * untouched on the web; the well-known files for App Links / Universal Links are served.
 */
class AppShellGateTest {

    private static final String PASSWORD = "CorrectHorse7";
    private static final String APP_UA   = "Mozilla/5.0 (Linux; Android 14) Chrome/130 Mobile ChurchGeniusProApp/1.0 (android)";
    private static final String WEB_UA   = "Mozilla/5.0 (Windows NT 10.0) Chrome/130";

    LoginRepository loginRepo; FamilyMemberRepository familyRepo; LoginProtectionService protection; MockMvc mvc;

    @BeforeEach
    void setUp() {
        loginRepo = mock(LoginRepository.class); familyRepo = mock(FamilyMemberRepository.class);
        protection = mock(LoginProtectionService.class);
        when(protection.check(any(), anyString())).thenReturn(new LoginProtectionService.GuardResult(false, 0L, false));
        LoginController login = new LoginController(loginRepo, mock(AppUserRepository.class), mock(ChurchRegistrationRepository.class),
                mock(ServiceClientRepository.class), mock(UserPermissionsRepository.class), familyRepo, protection,
                mock(SecurityAuditService.class), mock(com.churchgeniuspro.service.DemoAccessService.class),
                mock(RememberMeService.class), mock(PushSubscriptionRepository.class));
        mvc = MockMvcBuilders.standaloneSetup(login, new WellKnownController()).build();
    }

    private void member(String username, String ref, String role) {
        SignUp s = new SignUp(); s.setUsername(username); s.setPassword(PasswordUtil.encode(PASSWORD));
        s.setClientId(ref); s.setChurch(false); s.setActive(true); s.setDeleted(false);
        when(loginRepo.findByUsernameAndDeletedFalse(username)).thenReturn(Optional.of(s));
        Family f = new Family(); f.setAppClientId("CHR-1");
        FamilyMember fm = new FamilyMember(); fm.setMemberRef(ref); fm.setFamily(f); fm.setFirstName("Kid"); fm.setLastName("Test"); fm.setRole(role); fm.setId(5);
        when(familyRepo.findByMemberRef(ref)).thenReturn(Optional.of(fm));
        when(familyRepo.findActiveByEmail(anyString())).thenReturn(List.of());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login(String username, String ua) {
        return post("/login").contentType("application/json").header("User-Agent", ua)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}");
    }

    @Test
    @DisplayName("a child login from the app shell is refused with the web-only message; the password still counts as correct")
    void childRefusedInApp() throws Exception {
        member("kid@example.org", "MBR-kid", "Child");
        mvc.perform(login("kid@example.org", APP_UA))
           .andExpect(status().isForbidden())
           .andExpect(jsonPath("$.message").value(LoginController.KIDS_PORTAL_APP_MESSAGE));
        verify(protection).recordDenied(any(), eq("kid@example.org"), eq("KIDS_PORTAL_APP"), eq("MBR-kid"), eq("/login"));
        verify(protection, never()).recordFailure(any(), anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("the same child login works unchanged from a browser")
    void childWorksOnWeb() throws Exception {
        member("kid@example.org", "MBR-kid", "Daughter");
        mvc.perform(login("kid@example.org", WEB_UA)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("success"));
    }

    @Test
    @DisplayName("an adult member login works from the app shell")
    void adultWorksInApp() throws Exception {
        member("mom@example.org", "MBR-mom", "Head");
        mvc.perform(login("mom@example.org", APP_UA)).andExpect(status().isOk()).andExpect(jsonPath("$.memberRole").value("Head"));
    }

    @Test
    @DisplayName("well-known files are served as JSON for both stores")
    void wellKnown() throws Exception {
        mvc.perform(get("/.well-known/assetlinks.json")).andExpect(status().isOk())
           .andExpect(content().contentTypeCompatibleWith("application/json"))
           .andExpect(content().string(org.hamcrest.Matchers.containsString("com.churchgeniuspro.app")));
        mvc.perform(get("/.well-known/apple-app-site-association")).andExpect(status().isOk())
           .andExpect(content().contentTypeCompatibleWith("application/json"))
           .andExpect(content().string(org.hamcrest.Matchers.containsString("com.churchgeniuspro.app")));
    }
}
