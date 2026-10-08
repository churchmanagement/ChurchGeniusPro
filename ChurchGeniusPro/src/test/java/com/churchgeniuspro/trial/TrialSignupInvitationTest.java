package com.churchgeniuspro.trial;

import com.churchgeniuspro.controller.SignupController;
import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.DemoRoleAccess;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.SignupService;
import com.churchgeniuspro.service.VerificationStore;
import com.churchgeniuspro.util.PasswordUtil;
import com.churchgeniuspro.util.PublicSendLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Trial registration: provisioning creates the SuperAdmin WITH a generated login
 * (tracked in demo_role_access) and then emails the staff invitation so the registrant
 * can choose their own username and password. The signup page refused that
 * invitation — "An account has already been created from this invitation." — because
 * a login already existed. The provisioned login is now claimed instead; every other
 * "login already exists" case is still refused, and a spent link stays spent.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Trial signup invitation — claiming the provisioned login")
class TrialSignupInvitationTest {

    @Mock SignupService signupService; @Mock LoginRepository loginRepo; @Mock AppUserRepository appUserRepo;
    @Mock ChurchRegistrationRepository churchRepo; @Mock ServiceClientRepository scRepo;
    @Mock VerificationStore otp; @Mock EmailService email; @Mock DemoRoleAccessRepository draRepo;

    SignupController controller;
    AppUser superAdmin;
    SignUp provisioned;

    @BeforeEach
    void setUp() {
        controller = new SignupController(signupService, loginRepo, appUserRepo, churchRepo, scRepo, otp, email, new PublicSendLimiter());
        controller.setDemoRoleAccessRepository(draRepo);

        superAdmin = new AppUser(); superAdmin.setId(3); superAdmin.setUserId("USR-trial-3");
        superAdmin.setEmail("pastor@newchurch.test"); superAdmin.setClientId("TRIAL-1700000000000");
        superAdmin.setInviteToken("2K4ed1i3nYw2rz38Ugj0GAa1mwSVzA8kxyGiCo9UHWs");

        provisioned = new SignUp(); provisioned.setId(55); provisioned.setClientId("USR-trial-3");
        provisioned.setUsername("grace.pastor"); provisioned.setPassword(PasswordUtil.encode("Generated#1"));
        provisioned.setDemoPassword("Generated#1"); provisioned.setActive(true); provisioned.setDeleted(false);
        provisioned.setChurch(false);

        when(appUserRepo.findByInviteTokenAndDeleteFlagFalse(superAdmin.getInviteToken())).thenReturn(Optional.of(superAdmin));
        when(loginRepo.findActiveSignupByUserId("USR-trial-3")).thenReturn(Optional.of(provisioned));
        when(loginRepo.existsByUsername(anyString())).thenReturn(false);
        when(otp.validate("USR-trial-3", "user", "123456")).thenReturn(true);
        when(loginRepo.save(any(SignUp.class))).thenAnswer(i -> i.getArgument(0));
    }

    private ResponseEntity<?> signup(String token, String username) {
        return controller.verifyAndSignup(Map.of("clientId", token, "username", username,
                "password", "Str0ng!Passw0rd", "code", "123456"));
    }

    @Test
    @DisplayName("a valid, unused trial invitation claims the provisioned login with the registrant's credentials")
    void validInvitationClaimsProvisionedLogin() {
        DemoRoleAccess dra = new DemoRoleAccess(); dra.setSignupId(55); dra.setUsername("grace.pastor");
        when(draRepo.findBySignupId(55)).thenReturn(Optional.of(dra));

        ResponseEntity<?> res = signup(superAdmin.getInviteToken(), "pastor.john");

        assertThat(res.getStatusCode().value()).as("%s", res.getBody()).isEqualTo(200);
        assertThat(provisioned.getUsername()).isEqualTo("pastor.john");
        assertThat(PasswordUtil.matches("Str0ng!Passw0rd", provisioned.getPassword())).isTrue();
        assertThat(provisioned.getDemoPassword()).as("generated password no longer shown").isNull();
        assertThat(provisioned.getActive()).isTrue();
        assertThat(dra.getUsername()).isEqualTo("pastor.john");
        assertThat(superAdmin.getInviteToken()).as("the link is spent").isNull();
        verify(loginRepo, times(1)).save(provisioned);          // updated in place — never a second row
        verify(otp).remove("USR-trial-3", "user");
    }

    @Test
    @DisplayName("the registrant may keep the generated username")
    void mayKeepGeneratedUsername() {
        DemoRoleAccess dra = new DemoRoleAccess(); dra.setSignupId(55);
        when(draRepo.findBySignupId(55)).thenReturn(Optional.of(dra));
        when(loginRepo.existsByUsername("grace.pastor")).thenReturn(true);   // it exists — it is this row

        assertThat(signup(superAdmin.getInviteToken(), "grace.pastor").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("a genuinely used invitation is still refused: its token is gone")
    void usedInvitationStillRefused() {
        DemoRoleAccess dra = new DemoRoleAccess(); dra.setSignupId(55);
        when(draRepo.findBySignupId(55)).thenReturn(Optional.of(dra));
        String token = superAdmin.getInviteToken();
        assertThat(signup(token, "pastor.john").getStatusCode().value()).isEqualTo(200);

        // Second attempt with the same link: the token was cleared on first use.
        when(appUserRepo.findByInviteTokenAndDeleteFlagFalse(token)).thenReturn(Optional.empty());
        ResponseEntity<?> again = signup(token, "someone.else");
        assertThat(again.getStatusCode().value()).isEqualTo(400);
        assertThat(String.valueOf(again.getBody())).contains("invalid, has been used, or has expired");
        verify(loginRepo, times(1)).save(any(SignUp.class));
    }

    @Test
    @DisplayName("a login that provisioning did NOT create is still 'already created' (non-trial accounts unchanged)")
    void ordinaryExistingLoginStillRefused() {
        when(draRepo.findBySignupId(55)).thenReturn(Optional.empty());

        ResponseEntity<?> res = signup(superAdmin.getInviteToken(), "pastor.john");

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(res.getBody())).contains("already been created");
        verify(loginRepo, never()).save(any());
        assertThat(superAdmin.getInviteToken()).isNotNull();
    }

    @Test
    @DisplayName("a provisioned login with a username already taken by someone else is still refused")
    void takenUsernameStillRefused() {
        DemoRoleAccess dra = new DemoRoleAccess(); dra.setSignupId(55);
        when(draRepo.findBySignupId(55)).thenReturn(Optional.of(dra));
        when(loginRepo.existsByUsername("taken.name")).thenReturn(true);

        assertThat(signup(superAdmin.getInviteToken(), "taken.name").getStatusCode().value()).isEqualTo(409);
        verify(loginRepo, never()).save(any());
    }
}
