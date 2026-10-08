package com.churchgeniuspro.security;

import com.churchgeniuspro.controller.ForgotUsernameController;
import com.churchgeniuspro.controller.SignupController;
import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.SignupService;
import com.churchgeniuspro.service.VerificationStore;
import com.churchgeniuspro.util.PublicFormGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.churchgeniuspro.util.PublicSendLimiter;

/** The LOW sweep: username recovery stays per-account; staff invites are single-use handles. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LOW sweep")
class LowSweepTest {

    @Nested @DisplayName("forgot-username by phone delivers each username to its own account's email")
    class ForgotUsername {
        @Mock LoginRepository loginRepo; @Mock AppUserRepository appUserRepo; @Mock FamilyMemberRepository memberRepo;
        @Mock ChurchRegistrationRepository churchRepo; @Mock UsernameRecoveryLogRepository logRepo;
        @Mock EmailService email; @Mock PublicFormGuard guard;

        @Test void sharedPhoneDoesNotCrossChurches() {
            // Staff of church A and a member of church B share a phone number.
            AppUser staff = new AppUser(); staff.setUserId("USR-a"); staff.setEmail("staff@a.org"); staff.setRole("Admin");
            FamilyMember member = new FamilyMember(); member.setMemberRef("MBR-b"); member.setEmail("member@b.org"); member.setRole("Member");
            SignUp sA = new SignUp(); sA.setUsername("alice.admin");
            SignUp sB = new SignUp(); sB.setUsername("bob.member");
            when(appUserRepo.findActiveByPhone(anyString())).thenReturn(List.of(staff));
            when(memberRepo.findActiveByPhoneAnyChurch(anyString())).thenReturn(List.of(member));
            when(loginRepo.findActiveSignupByUserId("USR-a")).thenReturn(Optional.of(sA));
            when(loginRepo.findActiveSignupByMemberRef("MBR-b")).thenReturn(Optional.of(sB));
            when(logRepo.countByIdentifierSince(anyString(), any())).thenReturn(0L);
            when(logRepo.countByIpSince(anyString(), any())).thenReturn(0L);
            when(guard.captchaEnabled()).thenReturn(false);

            ForgotUsernameController c = new ForgotUsernameController(loginRepo, appUserRepo, memberRepo, churchRepo, logRepo, email, guard);
            c.recover(Map.of("identifier", "555-010-1234"), new MockHttpServletRequest());

            ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(email, org.mockito.Mockito.times(2)).sendAccountEmail(to.capture(), anyString(), body.capture());
            Map<String, String> sent = Map.of(to.getAllValues().get(0), body.getAllValues().get(0),
                                              to.getAllValues().get(1), body.getAllValues().get(1));
            assertThat(sent.get("staff@a.org")).contains("alice.admin").doesNotContain("bob.member");
            assertThat(sent.get("member@b.org")).contains("bob.member").doesNotContain("alice.admin");
        }

        @Test void captchaIsVerifiedOnceRequired() {
            when(logRepo.countByIdentifierSince(anyString(), any())).thenReturn(10L);   // past the CAPTCHA threshold
            when(logRepo.countByIpSince(anyString(), any())).thenReturn(0L);
            when(guard.captchaEnabled()).thenReturn(true);
            when(guard.checkCaptcha(any(), any())).thenReturn("Please complete the CAPTCHA.");
            ForgotUsernameController c = new ForgotUsernameController(loginRepo, appUserRepo, memberRepo, churchRepo, logRepo, email, guard);
            ResponseEntity<?> res = c.recover(Map.of("identifier", "x@y.org"), new MockHttpServletRequest());
            assertThat(res.getStatusCode().value()).isEqualTo(400);
            verify(email, never()).sendAccountEmail(anyString(), anyString(), anyString());
        }
    }

    @Nested @DisplayName("staff invitations")
    class Invites {
        @Mock SignupService signupService; @Mock LoginRepository loginRepo; @Mock AppUserRepository appUserRepo;
        @Mock ChurchRegistrationRepository churchRepo; @Mock ServiceClientRepository scRepo;
        @Mock VerificationStore otp; @Mock EmailService email;

        private SignupController controller() {
            return new SignupController(signupService, loginRepo, appUserRepo, churchRepo, scRepo, otp, email, new PublicSendLimiter());
        }
        private AppUser invitee() {
            AppUser u = new AppUser(); u.setId(3); u.setUserId("USR-3"); u.setEmail("new@ours.org");
            u.setClientId("CHR-ours"); u.setInviteToken("inv-3"); u.setFirstName("N"); u.setLastName("U");
            return u;
        }

        @Test void theHandleIsLookedUpNeverDecoded() throws Exception {
            when(appUserRepo.findByInviteTokenAndDeleteFlagFalse("inv-3")).thenReturn(Optional.of(invitee()));
            when(scRepo.findByClientIdAndStatusAndDeleteFlagFalse(eq("CHR-ours"), anyString()))
                    .thenReturn(Optional.of(activeClient()));
            SignupController c = controller();
            assertThat(c.validate("inv-3", null, null).getStatusCode().value()).isEqualTo(200);
            assertThat(c.validate("USR-3", null, null).getStatusCode().value()).as("the raw user id").isEqualTo(400);
            assertThat(c.validate(com.churchgeniuspro.util.EncryptionUtil.encrypt("USR-3"), null, null).getStatusCode().value())
                    .as("the old AES shape").isEqualTo(400);
        }

        @Test void codeGoesToTheInviteesAddressAndTheHandleIsSpentOnSuccess() {
            AppUser u = invitee();
            when(appUserRepo.findByInviteTokenAndDeleteFlagFalse("inv-3")).thenReturn(Optional.of(u));
            when(loginRepo.existsByUsername(anyString())).thenReturn(false);
            when(loginRepo.findActiveSignupByUserId("USR-3")).thenReturn(Optional.empty());
            when(otp.generateAndStore(eq("USR-3"), eq("user"), anyString())).thenReturn("123456");
            when(otp.validate("USR-3", "user", "123456")).thenReturn(true);
            when(email.getChurchName(anyString())).thenReturn("Ours");
            SignupController c = controller();

            c.sendCode(Map.of("clientId", "inv-3", "username", "new.user", "password", "Str0ng!Passw0rd"), new MockHttpServletRequest());
            verify(email).sendGenericEmail(eq("new@ours.org"), anyString(), anyString());

            ResponseEntity<?> res = c.verifyAndSignup(Map.of("clientId", "inv-3", "username", "new.user",
                    "password", "Str0ng!Passw0rd", "code", "123456"));
            assertThat(res.getStatusCode().is2xxSuccessful()).as("%s", res.getBody()).isTrue();
            assertThat(u.getInviteToken()).as("single-use").isNull();
            verify(appUserRepo).save(u);
        }

        @Test void aSecondSignupFromTheSameInvitationIsRefused() {
            when(appUserRepo.findByInviteTokenAndDeleteFlagFalse("inv-3")).thenReturn(Optional.of(invitee()));
            when(loginRepo.findActiveSignupByUserId("USR-3")).thenReturn(Optional.of(new SignUp()));
            when(otp.validate(anyString(), anyString(), anyString())).thenReturn(true);
            ResponseEntity<?> res = controller().verifyAndSignup(Map.of("clientId", "inv-3", "username", "again",
                    "password", "Str0ng!Passw0rd", "code", "123456"));
            assertThat(res.getStatusCode().value()).isEqualTo(409);
            verify(loginRepo, never()).save(any());
        }

        private com.churchgeniuspro.hibernate.ServiceClient activeClient() {
            com.churchgeniuspro.hibernate.ServiceClient sc = new com.churchgeniuspro.hibernate.ServiceClient();
            sc.setClientId("CHR-ours"); sc.setStatus("Active");
            sc.setEndDate(java.time.LocalDate.now().plusYears(1));
            return sc;
        }
    }
}
