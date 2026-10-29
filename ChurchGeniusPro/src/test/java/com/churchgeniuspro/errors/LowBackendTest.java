package com.churchgeniuspro.errors;

import com.churchgeniuspro.controller.ErrorBodyMirrorAdvice;
import com.churchgeniuspro.controller.ForgotPasswordController;
import com.churchgeniuspro.controller.GlobalExceptionHandler;
import com.churchgeniuspro.controller.TemporaryAccessController;
import com.churchgeniuspro.controller.VoiceCommandController;
import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.hibernate.TemporaryAccess;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.ChurchLogoRepository;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.PasswordResetTokenRepository;
import com.churchgeniuspro.repository.ReminderSentLogRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.service.ChurchVoiceSettingService;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.LoginProtectionService;
import com.churchgeniuspro.service.OpenAiUsageService;
import com.churchgeniuspro.service.OpenAiVoiceService;
import com.churchgeniuspro.service.PasswordResetService;
import com.churchgeniuspro.service.ReminderClaimService;
import com.churchgeniuspro.service.TemporaryAccessService;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.webfilter.AuthFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Error-handling audit 2026-10-28 — the backend Lows (L1, L3, L4, L5, L8, L9, L10, L12). */
@DisplayName("Error-handling Lows — backend")
class LowBackendTest {

    static String src(String rel) throws Exception {
        return Files.readString(Path.of("src/main/java/com/churchgeniuspro/" + rel));
    }

    // ── L1 ────────────────────────────────────────────────────────────────────
    @Nested @DisplayName("L1 — no error answered as 200")
    class L1 {
        @Test @DisplayName("'Not a member account' is a 403 in Sunday School and Guess It (body unchanged)")
        void notAMemberIs403() throws Exception {
            for (String f : List.of("controller/SundaySchoolController.java", "controller/GuessItController.java")) {
                String s = src(f);
                assertThat(s).doesNotContain("ResponseEntity.ok(Map.of(\"success\", false, \"error\", \"Not a member account\"))")
                             .doesNotContain("ResponseEntity.ok(Map.of(\"error\", \"Not a member account\"))");
                assertThat(s).contains("status(403).body(Map.of(");
            }
            assertThat(src("controller/SundaySchoolController.java").split("status\\(403\\)\\.body\\(Map\\.of\\([^)]*Not a member account", -1)).hasSize(6);
        }

        @Test @DisplayName("Guess It game outcomes keep their keys and gain a 'reason' code")
        void guessItReason() throws Exception {
            String s = src("controller/GuessItController.java");
            assertThat(s).contains("\"gameEnded\", true, \"reason\", \"GAME_ENDED\"")
                         .contains("\"timerExpired\", true, \"reason\", \"TIMER_EXPIRED\"")
                         .contains("\"alreadyWon\", true, \"reason\", \"ALREADY_WON\"")
                         .contains("\"eliminated\", true, \"reason\", \"ELIMINATED\"")
                         .contains("\"reason\", \"ALREADY_SUBMITTED\"");
        }
    }

    // ── L3 ────────────────────────────────────────────────────────────────────
    @Nested @DisplayName("L3 — RoleGuard.denied: 401 only when nobody is signed in")
    class L3 {
        MockHttpServletRequest req(Map<String, Object> attrs) {
            MockHttpServletRequest r = new MockHttpServletRequest();
            if (attrs != null) {
                MockHttpSession s = new MockHttpSession();
                attrs.forEach(s::setAttribute);
                r.setSession(s);
            }
            return r;
        }

        @Test void noSessionIs401WithLoginCode() {
            ResponseEntity<Map<String, String>> r = RoleGuard.denied(req(null), RoleGuard.REDIRECT_LOGIN, Map.of("error", "Access denied"));
            assertThat(r.getStatusCode().value()).isEqualTo(401);
            assertThat(r.getBody()).containsEntry("error", "Access denied").containsEntry("code", RoleGuard.CODE_LOGIN_REQUIRED);
        }

        @Test void signedInButNotAllowedIs403() {
            ResponseEntity<Map<String, String>> r = RoleGuard.denied(req(Map.of("username", "ann", "role", "User")),
                    RoleGuard.FORWARD_ACCESS_DENIED, Map.of("error", "Permission denied"));
            assertThat(r.getStatusCode().value()).isEqualTo(403);
            assertThat(r.getBody()).containsEntry("error", "Permission denied").containsEntry("code", RoleGuard.CODE_ACCESS_DENIED);
        }

        @Test @DisplayName("a member or Service Admin session asked to 'log in' still gets 403 — never sent to the login page")
        void signedInSomeoneElseIs403() {
            assertThat(RoleGuard.denyStatus(req(Map.of("memberId", 7)), RoleGuard.REDIRECT_LOGIN)).isEqualTo(403);
            assertThat(RoleGuard.denyStatus(req(Map.of("serviceAdminId", 1)), RoleGuard.REDIRECT_LOGIN)).isEqualTo(403);
            assertThat(RoleGuard.denyStatus(req(Map.of("other", "x")), RoleGuard.REDIRECT_LOGIN)).isEqualTo(401);
        }

        @Test @DisplayName("an existing code is never overwritten; non-map bodies pass through")
        void keepsExistingCode() {
            ResponseEntity<Map<String, Object>> r = RoleGuard.denied(req(Map.of("username", "a")), RoleGuard.FORWARD_ACCESS_DENIED,
                    Map.<String, Object>of("error", "x", "code", "KIDS_PORTAL"));
            assertThat(r.getBody()).containsEntry("code", "KIDS_PORTAL");
            ResponseEntity<String> s = RoleGuard.denied(req(null), RoleGuard.REDIRECT_LOGIN, "plain");
            assertThat(s.getBody()).isEqualTo("plain");
        }

        @Test @DisplayName("the mechanical 403 denials were migrated (none of that shape is left)")
        void migrated() throws Exception {
            int migrated = 0;
            try (var files = Files.walk(Path.of("src/main/java"))) {
                for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                    String s = Files.readString(p);
                    // a RoleGuard.require* result answered with a hand-written 403 right after it
                    assertThat(s).as(p.toString()).doesNotContainPattern(
                            "(\\w+) = RoleGuard\\.require\\w*\\([^;]*;\\s*\\n\\s*if \\(\\1 != null\\) return ResponseEntity\\.status\\(403\\)\\.body\\(");
                    migrated += s.split("RoleGuard\\.denied\\(", -1).length - 1;
                }
            }
            assertThat(migrated).isGreaterThanOrEqualTo(130);
        }
    }

    // ── L4 ────────────────────────────────────────────────────────────────────
    @RestController
    static class Probe {
        @GetMapping("/l4/msg")  ResponseEntity<Map<String, Object>> msg()  { return ResponseEntity.status(401).body(Map.of("status", "error", "message", "Please sign in.")); }
        @GetMapping("/l4/err")  ResponseEntity<Map<String, String>> err()  { return ResponseEntity.status(403).body(Map.of("error", "Access denied")); }
        @GetMapping("/l4/both") ResponseEntity<Map<String, String>> both() { return ResponseEntity.status(400).body(Map.of("error", "A", "message", "B")); }
        @GetMapping("/l4/ok")   ResponseEntity<Map<String, Object>> ok()   { return ResponseEntity.ok(Map.of("success", false, "error", "Time is up!", "timerExpired", true)); }
        @GetMapping("/l4/boom") String boom() { throw new RuntimeException("x"); }
    }

    @Nested @DisplayName("L4 — every error body carries both 'error' and 'message' (additive)")
    class L4 {
        MockMvc mvc;

        @BeforeEach void setUp() {
            GlobalExceptionHandler advice = new GlobalExceptionHandler();
            ReflectionTestUtils.setField(advice, "maxFileSize", "15MB");
            mvc = MockMvcBuilders.standaloneSetup(new Probe())
                    .setControllerAdvice(advice, new ErrorBodyMirrorAdvice()).build();
        }

        @Test void messageMirroredToError() throws Exception {
            mvc.perform(get("/l4/msg")).andExpect(status().isUnauthorized())
               .andExpect(jsonPath("$.message").value("Please sign in."))
               .andExpect(jsonPath("$.error").value("Please sign in."))
               .andExpect(jsonPath("$.status").value("error"));
        }

        @Test void errorMirroredToMessage() throws Exception {
            mvc.perform(get("/l4/err")).andExpect(status().isForbidden())
               .andExpect(jsonPath("$.error").value("Access denied"))
               .andExpect(jsonPath("$.message").value("Access denied"));
        }

        @Test @DisplayName("nothing is overwritten; 2xx bodies untouched; advice 500s gain message")
        void neverOverwrites() throws Exception {
            mvc.perform(get("/l4/both")).andExpect(jsonPath("$.error").value("A")).andExpect(jsonPath("$.message").value("B"));
            mvc.perform(get("/l4/ok")).andExpect(status().isOk()).andExpect(jsonPath("$.message").doesNotExist());
            mvc.perform(get("/l4/boom")).andExpect(status().isInternalServerError())
               .andExpect(jsonPath("$.ref").exists()).andExpect(jsonPath("$.message").exists());
        }

        @Test @DisplayName("JSON-writing filters carry both keys")
        void filtersCarryBoth() throws Exception {
            for (String f : List.of("webfilter/AuthFilter.java", "webfilter/ServiceAdminAuthFilter.java", "webfilter/CsrfOriginFilter.java",
                    "webfilter/NtagAccessFilter.java", "webfilter/AccountStatusFilter.java", "webfilter/SubscriptionFeatureFilter.java",
                    "webfilter/DemoTrialAgreementFilter.java", "webfilter/PrivatePageFilter.java")) {
                String s = src(f);
                assertThat(s).as(f).contains("\\\"error\\\":").contains("\\\"message\\\":");
            }
        }
    }

    // ── L5 ────────────────────────────────────────────────────────────────────
    @Nested @DisplayName("L5 — reminder claim: only a duplicate key means 'already sent'")
    class L5 {
        ReminderSentLogRepository repo = mock(ReminderSentLogRepository.class);

        @Test void claimedAlreadyAndFailed() {
            ReminderClaimService svc = new ReminderClaimService(repo);
            when(repo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
            assertThat(svc.claim("CH-1", "BIRTHDAY", "k", LocalDate.of(2026, 10, 29))).isEqualTo(ReminderClaimService.Claim.CLAIMED);

            when(repo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_reminder_sent"));
            assertThat(svc.claim("CH-1", "BIRTHDAY", "k", LocalDate.of(2026, 10, 29))).isEqualTo(ReminderClaimService.Claim.ALREADY_SENT);
        }

        @Test @DisplayName("a database outage is FAILED (logged), not 'already sent'")
        void outageIsFailed() {
            when(repo.saveAndFlush(any())).thenThrow(new DataAccessResourceFailureException("connection refused"));
            assertThat(new ReminderClaimService(repo).claim("CH-1", "EVENT", "k", LocalDate.now()))
                    .isEqualTo(ReminderClaimService.Claim.FAILED);
        }

        @Test @DisplayName("with a transaction manager the insert runs in its own REQUIRES_NEW transaction, rolled back on a duplicate")
        @SuppressWarnings("unchecked")
        void ownTransaction() {
            PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
            SimpleTransactionStatus st = new SimpleTransactionStatus(true);
            when(tm.getTransaction(any())).thenReturn(st);
            ObjectProvider<PlatformTransactionManager> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(tm);
            when(repo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));
            ReminderClaimService svc = new ReminderClaimService(repo, provider);
            assertThat(svc.claim("CH-1", "EVENT", "k", LocalDate.now())).isEqualTo(ReminderClaimService.Claim.ALREADY_SENT);
            verify(tm).getTransaction(org.mockito.ArgumentMatchers.argThat(d -> d.getPropagationBehavior() == org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW));
            verify(tm).rollback(st);
            verify(tm, never()).commit(any());
        }
    }

    // ── L8 ────────────────────────────────────────────────────────────────────
    @Nested @DisplayName("L8 — temporary access code: branded account mail, honest emailSent")
    class L8 {
        TemporaryAccessService svc = mock(TemporaryAccessService.class);
        EmailService email = mock(EmailService.class);
        ChurchRegistrationRepository churches = mock(ChurchRegistrationRepository.class);
        ChurchLogoRepository logos = mock(ChurchLogoRepository.class);

        MockHttpServletRequest admin() {
            MockHttpServletRequest r = new MockHttpServletRequest();
            MockHttpSession s = new MockHttpSession();
            s.setAttribute("username", "admin"); s.setAttribute("role", "Admin");
            s.setAttribute("clientId", "CH-1"); s.setAttribute("appClientId", "CH-1");
            r.setSession(s);
            return r;
        }

        Map<String, Object> body() {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("holderName", "Pat Visitor"); b.put("email", "pat@example.org");
            b.put("startDateTime", "2026-11-01T09:00"); b.put("endDateTime", "2026-11-01T17:00");
            b.put("pages", List.of("/attendance"));
            return b;
        }

        @SuppressWarnings("unchecked")
        @Test void failedSendIsReportedAndCodeStillReturned() throws Exception {
            TemporaryAccess a = new TemporaryAccess(); a.setId(5L); a.setHolderName("Pat Visitor");
            when(svc.create(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new TemporaryAccessService.CreateResult(a, "ABC123"));
            doThrow(new RuntimeException("smtp down")).when(email).sendOrgEmailOrThrow(anyString(), anyString(), anyString(), anyString());
            TemporaryAccessController c = new TemporaryAccessController(svc, email, churches, logos);
            ResponseEntity<?> r = c.create(body(), admin());
            Map<String, Object> out = (Map<String, Object>) r.getBody();
            assertThat(r.getStatusCode().value()).isEqualTo(200);
            assertThat(out).containsEntry("emailSent", false).containsEntry("accessCode", "ABC123");
            verify(email).sendOrgEmailOrThrow(eq("pat@example.org"), anyString(), anyString(), anyString());
            verify(email, never()).sendGenericEmail(anyString(), anyString(), anyString());
        }

        @SuppressWarnings("unchecked")
        @Test void sentIsReported() throws Exception {
            TemporaryAccess a = new TemporaryAccess(); a.setId(6L); a.setHolderName("Pat Visitor");
            when(svc.create(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new TemporaryAccessService.CreateResult(a, "XYZ789"));
            Map<String, Object> out = (Map<String, Object>) new TemporaryAccessController(svc, email, churches, logos).create(body(), admin()).getBody();
            assertThat(out).containsEntry("emailSent", true);
        }
    }

    // ── L9 ────────────────────────────────────────────────────────────────────
    @Nested @DisplayName("L9 — a failed reset email releases the cooldown; answer stays generic")
    class L9 {
        @Test void releaseOnFailure() throws Exception {
            LoginRepository logins = mock(LoginRepository.class);
            ChurchRegistrationRepository churches = mock(ChurchRegistrationRepository.class);
            ServiceClientRepository clients = mock(ServiceClientRepository.class);
            EmailService email = mock(EmailService.class);
            PasswordResetService resets = mock(PasswordResetService.class);
            LoginProtectionService protection = mock(LoginProtectionService.class);
            SignUp user = new SignUp(); user.setClientId("CHR-1"); user.setChurch(true); user.setActive(true);
            ChurchRegistration reg = new ChurchRegistration(); reg.setEmail("office@example.org"); reg.setChurchName("Grace");
            when(protection.check(any(), isNull())).thenReturn(new LoginProtectionService.GuardResult(false, 0, false));
            when(logins.findActiveByUsername("grace")).thenReturn(Optional.of(user));
            when(churches.findByClientIdAndDeleteFlagFalse("CHR-1")).thenReturn(Optional.of(reg));
            when(clients.isActiveSubscription(eq("CHR-1"), any())).thenReturn(true);
            when(resets.createToken("grace", "office@example.org")).thenReturn("tok-1");
            doThrow(new RuntimeException("smtp down")).when(email).sendAccountEmailOrThrow(anyString(), anyString(), anyString(), isNull());

            ForgotPasswordController c = new ForgotPasswordController(logins, mock(AppUserRepository.class), churches,
                    mock(FamilyMemberRepository.class), clients, mock(PasswordResetTokenRepository.class), email, resets, protection,
                    mock(com.churchgeniuspro.service.RememberMeService.class));
            ReflectionTestUtils.setField(c, "baseUrl", "https://churchgeniuspro.net");
            ResponseEntity<Map<String, String>> r = c.requestReset(Map.of("username", "grace", "email", "office@example.org"),
                    new MockHttpServletRequest());

            assertThat(r.getStatusCode().value()).isEqualTo(200);
            assertThat(r.getBody().get("message")).startsWith("If an account with that username and email exists");
            verify(resets).releaseToken("grace", "tok-1");
        }

        @Test @DisplayName("releaseToken removes only that unused token of that user")
        void releaseTokenScope() {
            PasswordResetTokenRepository repo = mock(PasswordResetTokenRepository.class);
            PasswordResetService svc = new PasswordResetService(repo, mock(com.churchgeniuspro.config.LoginProtectionProperties.class));
            com.churchgeniuspro.hibernate.PasswordResetToken t = new com.churchgeniuspro.hibernate.PasswordResetToken();
            t.setUsername("grace"); t.setToken("tok-1");
            when(repo.findByToken("tok-1")).thenReturn(Optional.of(t));
            svc.releaseToken("someone-else", "tok-1");
            verify(repo, never()).delete(any());
            svc.releaseToken("grace", "tok-1");
            verify(repo).delete(t);
        }
    }

    // ── L10 ───────────────────────────────────────────────────────────────────
    @Nested @DisplayName("L10 — AuthFilter: a failing account re-check is a JSON 503, session kept")
    class L10 {
        @Test void staffLookupFails() throws Exception {
            AppUserRepository users = mock(AppUserRepository.class);
            when(users.findById(42)).thenThrow(new DataAccessResourceFailureException("db down"));
            AuthFilter f = new AuthFilter(users, mock(LoginRepository.class));
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/families");
            MockHttpSession s = new MockHttpSession();
            s.setAttribute("username", "ann"); s.setAttribute("appUserId", 42); s.setAttribute("clientId", "CH-1");
            req.setSession(s);
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            f.doFilter(req, res, chain);
            assertThat(res.getStatus()).isEqualTo(503);
            assertThat(res.getContentType()).startsWith("application/json");
            assertThat(res.getContentAsString()).contains("\"code\":\"SESSION_CHECK_UNAVAILABLE\"").contains("\"ref\":\"")
                    .contains("\"error\":").contains("\"message\":").doesNotContain("db down");
            assertThat(chain.getRequest()).isNull();
            assertThat(s.isInvalid()).isFalse();
        }

        @Test void memberLookupFails() throws Exception {
            LoginRepository logins = mock(LoginRepository.class);
            when(logins.findByClientId("MBR-1")).thenThrow(new DataAccessResourceFailureException("db down"));
            AuthFilter f = new AuthFilter(mock(AppUserRepository.class), logins);
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/member/family");
            MockHttpSession s = new MockHttpSession();
            s.setAttribute("username", "m"); s.setAttribute("clientId", "MBR-1");
            req.setSession(s);
            MockHttpServletResponse res = new MockHttpServletResponse();
            f.doFilter(req, res, new MockFilterChain());
            assertThat(res.getStatus()).isEqualTo(503);
        }
    }

    // ── L12 ───────────────────────────────────────────────────────────────────
    @Nested @DisplayName("L12 — OpenAI over its limit (429) is a 503 with a plain sentence")
    class L12 {
        @Test void quota429Is503() {
            OpenAiVoiceService voice = mock(OpenAiVoiceService.class);
            OpenAiUsageService usage = mock(OpenAiUsageService.class);
            ChurchVoiceSettingService feats = mock(ChurchVoiceSettingService.class);
            when(voice.isEnabled()).thenReturn(true);
            when(feats.audioEnabled(anyString())).thenReturn(true);
            when(usage.voiceAvailable(anyString())).thenReturn(true);
            when(usage.statusMap(anyString())).thenReturn(Map.of("voiceEnabled", true, "voiceAvailable", true));
            when(voice.transcribe(any(), anyString(), anyString(), any(), any())).thenThrow(new OpenAiVoiceService.ProviderLimitException());
            MockHttpServletRequest r = new MockHttpServletRequest(); MockHttpSession s = new MockHttpSession();
            s.setAttribute("username", "admin"); s.setAttribute("role", "Admin"); s.setAttribute("clientId", "CHR-1"); s.setAttribute("appClientId", "CHR-1");
            r.setSession(s);
            ResponseEntity<Map<String, Object>> resp = new VoiceCommandController(voice, usage, feats)
                    .command(new MockMultipartFile("audio", "a.webm", "audio/webm", new byte[] { 1 }), "home", r);
            assertThat(resp.getStatusCode().value()).isEqualTo(503);
            assertThat(resp.getBody()).containsEntry("error", OpenAiVoiceService.OVER_LIMIT_MSG).containsEntry("reason", "PROVIDER_LIMIT");
            verify(usage, never()).addVoiceSeconds(anyString(), org.mockito.ArgumentMatchers.anyLong());
        }
    }
}
