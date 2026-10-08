package com.churchgeniuspro.trialemail;

import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.TrialTestEmail;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.TrialTestEmailRepository;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.MessagingPolicy;
import com.churchgeniuspro.service.TrialTestEmailService;
import com.churchgeniuspro.util.PublicSendLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The verification flow: request a code, confirm it, replace an address — and what is refused. */
@DisplayName("Trial/Demo test email — verification")
class TrialTestEmailServiceTest {

    static final String TRIAL = "CHR-TRIAL", PAID = "CHR-PAID";

    TrialTestEmailRepository repo;
    EmailService email;
    TrialTestEmailService service;
    AtomicReference<LocalDateTime> now = new AtomicReference<>(LocalDateTime.of(2026, 10, 6, 12, 0));
    TrialTestEmail row;   // the single row the mocked repo holds, or null

    @BeforeEach
    void setUp() throws Exception {
        ServiceClientRepository clients = mock(ServiceClientRepository.class);
        ServiceClient trial = new ServiceClient(); trial.setClientId(TRIAL); trial.setSubscriptionType("TRIAL");
        ServiceClient paid  = new ServiceClient(); paid.setClientId(PAID);  paid.setSubscriptionType("FULL");
        when(clients.findByClientId(TRIAL)).thenReturn(Optional.of(trial));
        when(clients.findByClientId(PAID)).thenReturn(Optional.of(paid));

        repo = mock(TrialTestEmailRepository.class);
        when(repo.findByClientId(anyString())).thenAnswer(i -> Optional.ofNullable(row != null && row.getClientId().equals(i.getArgument(0)) ? row : null));
        when(repo.save(any(TrialTestEmail.class))).thenAnswer(i -> { row = i.getArgument(0); return row; });
        email = mock(EmailService.class);
        when(email.getChurchName(anyString())).thenReturn("Trial Church");
        service = new TrialTestEmailService(repo, new MessagingPolicy(clients), email, new PublicSendLimiter());
        service.setClock(now::get);
    }

    /** The 6-digit code from the verification email the service sent. */
    String sentCode() throws Exception {
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email, atLeastOnce()).sendAccountEmailOrThrow(anyString(), anyString(), html.capture(), eq(TRIAL));
        Matcher m = Pattern.compile(">(\\d{6})<").matcher(html.getValue());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @Test @DisplayName("3. the code goes to the requested address as account mail; the address is pending, not verified")
    void codeSent() throws Exception {
        Map<String, Object> st = service.requestCode(TRIAL, "  Tester@Trial.TEST ", "1.2.3.4", "admin");
        verify(email).sendAccountEmailOrThrow(eq("tester@trial.test"), contains("verification code"), anyString(), eq(TRIAL));
        assertThat(row.getPendingEmail()).isEqualTo("tester@trial.test");
        assertThat(row.getPendingCodeHash()).hasSize(64).doesNotContain(sentCode());
        assertThat(row.getPendingExpiresAt()).isEqualTo(now.get().plusMinutes(15));
        assertThat(row.getVerifiedEmail()).isNull();
        assertThat(st).containsEntry("pendingEmail", "tester@trial.test").containsEntry("verifiedEmail", null);
        assertThat(service.verifiedAddress(TRIAL)).as("pending is never a redirect target").isNull();
    }

    @Test @DisplayName("4. the correct code verifies: the address becomes active and the code is gone")
    void correctCode() throws Exception {
        service.requestCode(TRIAL, "tester@trial.test", "1.2.3.4", "admin");
        Map<String, Object> st = service.verify(TRIAL, sentCode(), "admin");
        assertThat(row.getVerifiedEmail()).isEqualTo("tester@trial.test");
        assertThat(row.getVerifiedAt()).isEqualTo(now.get());
        assertThat(row.getPendingEmail()).isNull();
        assertThat(row.getPendingCodeHash()).isNull();
        assertThat(st).containsEntry("verifiedEmail", "tester@trial.test").containsEntry("pendingEmail", null);
        assertThat(service.verifiedAddress(TRIAL)).isEqualTo("tester@trial.test");
    }

    @Test @DisplayName("5. a wrong code is rejected, counted, and after five attempts the code is discarded")
    void wrongCode() throws Exception {
        service.requestCode(TRIAL, "tester@trial.test", "1.2.3.4", "admin");
        String code = sentCode();
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 1; i <= 5; i++) {
            assertThatThrownBy(() -> service.verify(TRIAL, wrong, "admin")).hasMessageContaining("not correct");
            assertThat(row.getPendingAttempts()).isEqualTo(i);
        }
        assertThatThrownBy(() -> service.verify(TRIAL, code, "admin")).hasMessageContaining("Too many");
        assertThat(row.getPendingCodeHash()).isNull();
        assertThat(row.getVerifiedEmail()).isNull();
    }

    @Test @DisplayName("6. an expired code is rejected")
    void expiredCode() throws Exception {
        service.requestCode(TRIAL, "tester@trial.test", "1.2.3.4", "admin");
        String code = sentCode();
        now.set(now.get().plusMinutes(16));
        assertThatThrownBy(() -> service.verify(TRIAL, code, "admin")).hasMessageContaining("expired");
        assertThat(row.getVerifiedEmail()).isNull();
        assertThat(service.status(TRIAL)).containsEntry("pendingEmail", null);
    }

    @Test @DisplayName("7. a new address replaces the old one only after its own code is verified")
    void replaceOnlyAfterVerification() throws Exception {
        service.requestCode(TRIAL, "first@trial.test", "1.2.3.4", "admin");
        service.verify(TRIAL, sentCode(), "admin");
        reset(email);
        service.requestCode(TRIAL, "second@trial.test", "1.2.3.4", "admin");
        assertThat(service.verifiedAddress(TRIAL)).as("still the old one while pending").isEqualTo("first@trial.test");
        assertThat(row.getPendingEmail()).isEqualTo("second@trial.test");
        service.verify(TRIAL, sentCode(), "admin");
        assertThat(service.verifiedAddress(TRIAL)).isEqualTo("second@trial.test");
        assertThat(row.getPendingEmail()).isNull();
    }

    @Test @DisplayName("20. a code cannot be reused")
    void noReuse() throws Exception {
        service.requestCode(TRIAL, "tester@trial.test", "1.2.3.4", "admin");
        String code = sentCode();
        service.verify(TRIAL, code, "admin");
        assertThatThrownBy(() -> service.verify(TRIAL, code, "admin")).hasMessageContaining("No verification is in progress");
    }

    @Test @DisplayName("a paying church is not eligible: nothing is stored or sent, nothing is shown")
    void paidNotEligible() throws Exception {
        assertThat(service.eligible(PAID)).isFalse();
        assertThat(service.status(PAID)).containsEntry("eligible", false).doesNotContainKey("verifiedEmail");
        assertThatThrownBy(() -> service.requestCode(PAID, "x@y.org", "1.2.3.4", "admin")).hasMessageContaining("Trial/Demo");
        assertThatThrownBy(() -> service.verify(PAID, "123456", "admin")).hasMessageContaining("Trial/Demo");
        verify(email, never()).sendAccountEmailOrThrow(anyString(), anyString(), anyString(), any());
        verify(repo, never()).save(any());
    }

    @Test @DisplayName("a bad address is refused before anything is sent")
    void badAddress() {
        for (String bad : new String[]{"", "   ", "nope", "a@b", "a b@c.org", null}) {
            assertThatThrownBy(() -> service.requestCode(TRIAL, bad, "1.2.3.4", "admin")).hasMessageContaining("valid email");
        }
        verifyNoInteractions(email);
    }

    @Test @DisplayName("the existing rate limiter applies to code requests")
    void rateLimited() throws Exception {
        for (int i = 0; i < 3; i++) service.requestCode(TRIAL, "tester@trial.test", "1.2.3.4", "admin");
        assertThatThrownBy(() -> service.requestCode(TRIAL, "tester@trial.test", "1.2.3.4", "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(email, times(3)).sendAccountEmailOrThrow(anyString(), anyString(), anyString(), eq(TRIAL));
    }

    @Test @DisplayName("a failed code email stores nothing")
    void failedCodeMailStoresNothing() throws Exception {
        doThrow(new RuntimeException("smtp")).when(email).sendAccountEmailOrThrow(anyString(), anyString(), anyString(), any());
        assertThatThrownBy(() -> service.requestCode(TRIAL, "tester@trial.test", "1.2.3.4", "admin")).hasMessage("smtp");
        verify(repo, never()).save(any());
    }

    @Test @DisplayName("the notice sits at the top, inside <body> when there is one, and masks the recipient")
    void notice() {
        String n = TrialTestEmailService.withNotice("<html><body><p>Hi</p></body></html>", "john.doe@example.com", 1);
        assertThat(n).startsWith("<html><body><div class=\"cgp-trial-notice\"").contains(TrialTestEmailService.NOTICE, "jo***@exa***.com")
                     .doesNotContain("john.doe@example.com").endsWith("<p>Hi</p></body></html>");
        assertThat(TrialTestEmailService.withNotice("<p>x</p>", "a@b.org", 12)).contains("12 recipients").endsWith("<p>x</p>");
    }

    @Test @DisplayName("verification codes never appear in log statements")
    void codesNotLogged() throws IOException {
        String src = Files.readString(Paths.get("src/main/java/com/churchgeniuspro/service/TrialTestEmailService.java"));
        for (String line : src.split("\n")) {
            if (line.contains("log.")) assertThat(line).doesNotContain("code)").doesNotContain("code,").doesNotContain("+ code");
        }
        assertThat(src).contains("MessageDigest.getInstance(\"SHA-256\")", "MessageDigest.isEqual(");
    }
}
