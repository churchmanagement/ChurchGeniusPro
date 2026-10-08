package com.churchgeniuspro.invite;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.UserPermissionsRepository;
import com.churchgeniuspro.service.AppUserService;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.WhatsAppSenderService;
import com.churchgeniuspro.util.EncryptionUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Accounts → Invite button.
 *
 * <p>The bug these pin: the invitation was sent as ordinary organization mail, so
 * three guards meant to keep an evaluating church from messaging its congregation
 * — the Trial/demo plan block, the unsubscribe list and the monthly allowance —
 * each silently dropped it and returned. The admin was shown "Email sent
 * successfully" and the invitee got nothing. An invitation is addressed to the
 * person signing up, so it is account mail and exempt, exactly as the church
 * registrant's own invitation already was.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Staff signup invitation")
class StaffInviteEmailTest {

    private static final String BASE_URL  = "https://churchgeniuspro.net";
    private static final String CLIENT_ID = "CHR-abc-123";
    private static final String USER_ID   = "USR-9f2c-4471";

    @Mock private AppUserRepository         userRepository;
    @Mock private LoginRepository           loginRepository;
    @Mock private EmailService              emailService;
    @Mock private WhatsAppSenderService     whatsAppSender;
    @Mock private UserPermissionsRepository permissionsRepository;

    private AppUserService service;

    @BeforeEach
    void setUp() {
        service = new AppUserService(userRepository, loginRepository, emailService,
                                     whatsAppSender, permissionsRepository);
        ReflectionTestUtils.setField(service, "baseUrl", BASE_URL);
        when(emailService.getChurchName(anyString())).thenReturn("Grace Chapel");
    }

    private AppUser staff(String email, String phone) {
        AppUser u = new AppUser();
        u.setId(27);
        u.setUserId(USER_ID);
        u.setClientId(CLIENT_ID);
        u.setFirstName("Ruth");
        u.setLastName("Adeyemi");
        u.setEmail(email);
        u.setPhone(phone);
        u.setRole("Accountant");
        when(userRepository.findById(27)).thenReturn(Optional.of(u));
        return u;
    }

    /* ── the fix ────────────────────────────────────────────────────────── */

    @Test
    @DisplayName("goes out as account mail, so no congregation guard can drop it")
    void sendsAsAccountMail() throws Exception {
        staff("ruth@example.org", null);

        service.sendEmail(27);

        verify(emailService).sendAccountEmailOrThrow(
                eq("ruth@example.org"), anyString(), anyString(), eq(CLIENT_ID));
        // The regression itself: org mail is subject to the plan block, the
        // unsubscribe list and the monthly allowance, all of which return silently.
        verify(emailService, never()).sendOrgEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a send failure reaches the admin instead of the server log")
    void deliveryFailurePropagates() throws Exception {
        staff("ruth@example.org", null);
        doThrow(new jakarta.mail.MessagingException("relay refused"))
                .when(emailService)
                .sendAccountEmailOrThrow(anyString(), anyString(), anyString(), anyString());

        assertThatThrownBy(() -> service.sendEmail(27))
                .hasMessageContaining("relay refused");
    }

    /* ── the link ───────────────────────────────────────────────────────── */

    @Test
    @DisplayName("carries a signup link whose token decrypts back to the user id")
    void linkTokenRoundTrips() throws Exception {
        staff("ruth@example.org", null);

        service.sendEmail(27);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendAccountEmailOrThrow(anyString(), anyString(), body.capture(), anyString());

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(java.util.regex.Pattern.quote(BASE_URL + "/signup?clientId=") + "([A-Za-z0-9_-]+)")
                .matcher(body.getValue());
        assertThat(m.find()).as("signup link present in the email body").isTrue();

        String token = m.group(1);
        // URL-safe, unpadded: the token survives a query string untouched. It is the
        // random single-use invite token persisted on the user row — SignupController
        // looks it up; nothing is decrypted, and the user id never appears in the link.
        assertThat(token).doesNotContain("+").doesNotContain("/").doesNotContain("=").hasSize(43);
        assertThat(token).isNotEqualTo(EncryptionUtil.encrypt(USER_ID));
        ArgumentCaptor<com.churchgeniuspro.hibernate.AppUser> saved = ArgumentCaptor.forClass(com.churchgeniuspro.hibernate.AppUser.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getInviteToken()).isEqualTo(token);
    }

    /* ── WhatsApp is a convenience copy, not part of the verdict ────────── */

    @Test
    @DisplayName("a WhatsApp failure does not report the delivered email as failed")
    void whatsAppFailureDoesNotFailTheInvite() throws Exception {
        staff("ruth@example.org", "+15125550147");
        doThrow(new RuntimeException("twilio 401"))
                .when(whatsAppSender).sendWhatsAppToPhone(anyString(), anyString(), anyString());

        service.sendEmail(27);   // must not throw — the email did go out

        verify(emailService).sendAccountEmailOrThrow(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("no phone on file means no WhatsApp attempt")
    void noPhoneNoWhatsApp() throws Exception {
        staff("ruth@example.org", "   ");

        service.sendEmail(27);

        verify(whatsAppSender, never()).sendWhatsAppToPhone(anyString(), anyString(), anyString());
    }

    /* ── input the admin can actually fix ───────────────────────────────── */

    @Test
    @DisplayName("a missing address is refused with a message, not an SMTP error")
    void missingEmailIsRejectedUpFront() {
        staff(null, null);

        assertThatThrownBy(() -> service.sendEmail(27))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no email address");
    }

    @Test
    @DisplayName("an unknown user id is reported as not found")
    void unknownUser() {
        when(userRepository.findById(999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sendEmail(999))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User not found");
    }
}
