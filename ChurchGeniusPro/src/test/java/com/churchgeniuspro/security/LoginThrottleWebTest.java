package com.churchgeniuspro.security;

import com.churchgeniuspro.controller.LoginController;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.UserPermissionsRepository;
import com.churchgeniuspro.service.LoginProtectionService;
import com.churchgeniuspro.service.SecurityAuditService;
import com.churchgeniuspro.util.PasswordUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP-level contract of {@code POST /login} under the brute-force policy, using a
 * standalone MockMvc (no Spring context) to match the existing web tests in
 * {@code com.churchgeniuspro.web}.
 *
 * <p>The assertions people actually care about here are the negative ones: that a
 * throttled request never reaches the account lookup, and that "no such user" and "wrong
 * password" are byte-for-byte indistinguishable to the caller.
 */
class LoginThrottleWebTest {

    private MockMvc mvc;
    private LoginRepository        loginRepository;
    private LoginProtectionService protection;
    private SecurityAuditService   securityAudit;

    @BeforeEach
    void setUp() {
        loginRepository = mock(LoginRepository.class);
        protection      = mock(LoginProtectionService.class);
        securityAudit   = mock(SecurityAuditService.class);

        when(protection.check(any(), any())).thenReturn(new LoginProtectionService.GuardResult(false, 0, false));
        when(protection.recordFailure(any(), any(), any(), any(), any()))
                .thenReturn(new LoginProtectionService.FailureOutcome(0, false));

        LoginController controller = new LoginController(
                loginRepository,
                mock(AppUserRepository.class),
                mock(ChurchRegistrationRepository.class),
                mock(ServiceClientRepository.class),
                mock(UserPermissionsRepository.class),
                mock(FamilyMemberRepository.class),
                protection,
                securityAudit,
                mock(com.churchgeniuspro.service.DemoAccessService.class));

        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("a blocked client gets 429 + Retry-After and a message that names no threshold")
    void blocked_returns429() throws Exception {
        when(protection.check(any(), eq("john@example.com")))
                .thenReturn(new LoginProtectionService.GuardResult(true, 900, false));

        mvc.perform(json("john@example.com", "whatever"))
           .andExpect(status().is(429))
           .andExpect(header().string("Retry-After", "900"))
           .andExpect(jsonPath("$.blocked").value(true))
           .andExpect(jsonPath("$.message").value(LoginProtectionService.BLOCKED_MESSAGE))
           .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                   org.hamcrest.Matchers.containsString("attempts remaining"))));
    }

    @Test
    @DisplayName("the throttle runs before the account lookup, so a blocked attacker learns nothing")
    void blocked_neverTouchesTheAccount() throws Exception {
        when(protection.check(any(), any()))
                .thenReturn(new LoginProtectionService.GuardResult(true, 42, false));

        mvc.perform(json("john@example.com", "whatever")).andExpect(status().is(429));

        verifyNoInteractions(loginRepository);
        verify(protection).recordBlockedAttempt(any(), eq("john@example.com"), eq("/login"));
        verify(protection, never()).recordFailure(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("an unknown username and a wrong password are indistinguishable to the client")
    void unknownUser_andBadPassword_areIdentical() throws Exception {
        // Unknown username.
        when(loginRepository.findByUsernameAndDeletedFalse("ghost@example.com")).thenReturn(Optional.empty());
        MvcResult unknown = mvc.perform(json("ghost@example.com", "hunter2"))
                               .andExpect(status().isUnauthorized())
                               .andReturn();

        // Real account, wrong password.
        SignUp real = new SignUp();
        real.setUsername("john@example.com");
        real.setClientId("CHR-1");
        real.setPassword(PasswordUtil.encode("the-real-password"));
        when(loginRepository.findByUsernameAndDeletedFalse("john@example.com")).thenReturn(Optional.of(real));
        MvcResult wrongPassword = mvc.perform(json("john@example.com", "hunter2"))
                                     .andExpect(status().isUnauthorized())
                                     .andReturn();

        assertEquals(unknown.getResponse().getStatus(), wrongPassword.getResponse().getStatus());
        assertEquals(unknown.getResponse().getContentAsString(),
                     wrongPassword.getResponse().getContentAsString(),
                     "the two responses must be identical, or the endpoint enumerates accounts");
        assertTrue(unknown.getResponse().getContentAsString()
                        .contains(LoginProtectionService.GENERIC_FAILURE_MESSAGE));

        // The distinction survives server-side, where it is useful and safe.
        verify(protection).recordFailure(any(), eq("ghost@example.com"),
                eq(LoginProtectionService.REASON_UNKNOWN_USER), isNull(), eq("/login"));
        verify(protection).recordFailure(any(), eq("john@example.com"),
                eq(LoginProtectionService.REASON_BAD_PASSWORD), eq("CHR-1"), eq("/login"));
    }

    @Test
    @DisplayName("an empty submission is rejected and still counted")
    void blankCredentials_areCounted() throws Exception {
        mvc.perform(json("", ""))
           .andExpect(status().isBadRequest());

        verify(protection).recordFailure(any(), eq(""),
                eq(LoginProtectionService.REASON_MISSING_FIELDS), isNull(), eq("/login"));
    }

    @Test
    @DisplayName("a correct password against a policy-refused account is audited, not counted")
    void inactiveAccount_isDeniedNotCounted() throws Exception {
        SignUp inactive = new SignUp();
        inactive.setUsername("dormant@example.com");
        inactive.setClientId("CHR-2");
        inactive.setActive(false);
        inactive.setPassword(PasswordUtil.encode("correct-horse"));
        when(loginRepository.findByUsernameAndDeletedFalse("dormant@example.com"))
                .thenReturn(Optional.of(inactive));

        mvc.perform(json("dormant@example.com", "correct-horse"))
           .andExpect(status().isForbidden());

        verify(protection).recordDenied(any(), eq("dormant@example.com"),
                eq("INACTIVE"), eq("CHR-2"), eq("/login"));
        verify(protection, never()).recordFailure(any(), any(), any(), any(), any());
        verify(securityAudit, never()).recordLogin(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a refused login is never written to the security audit trail as a sign-in")
    void failedLoginIsNotAudited() throws Exception {
        when(loginRepository.findByUsernameAndDeletedFalse("ghost@example.com")).thenReturn(Optional.empty());

        mvc.perform(json("ghost@example.com", "hunter2"))
           .andExpect(status().isUnauthorized());

        verify(securityAudit, never()).recordLogin(any(), any(), any(), any());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            json(String username, String password) {
        return post("/login")
                .contentType("application/json")
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    }
}
