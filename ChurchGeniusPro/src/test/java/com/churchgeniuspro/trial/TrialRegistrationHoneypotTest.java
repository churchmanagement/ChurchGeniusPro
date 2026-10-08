package com.churchgeniuspro.trial;

import com.churchgeniuspro.controller.TrialRegistrationController;
import com.churchgeniuspro.hibernate.TrialRegistrationLink;
import com.churchgeniuspro.repository.TrialRegistrationLinkRepository;
import com.churchgeniuspro.service.TrialRegistrationLinkService;
import com.churchgeniuspro.service.TrialRegistrationService;
import com.churchgeniuspro.util.PublicFormGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The honeypot on the public trial form, and the shape of what it returns.
 *
 * <p>Written after a live false positive: Chrome autofilled the honeypot — it was
 * the first input in a form carrying eleven {@code autocomplete} tokens, and it
 * was named {@code website}, which Chrome maps — so a genuine registration was
 * classified as a bot, silently discarded, and the visitor was shown a
 * confirmation for a church that did not exist.
 *
 * <p>The fix is in the page (the input now sits after the real fields under a
 * name no browser can map). What is pinned here is the part that made the bug so
 * hard to see: the decoy response carries no {@code churchName}, and that is the
 * signature to recognise it by.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Trial registration honeypot")
class TrialRegistrationHoneypotTest {

    @Mock private TrialRegistrationService trialService;
    @Mock private TrialRegistrationLinkRepository linkRepo;

    private TrialRegistrationController controller;
    private TrialRegistrationLinkService links;
    private String invite;
    private int ipCounter;

    @BeforeEach
    void setUp() {
        links = new TrialRegistrationLinkService(linkRepo);
        // One live invitation, so these tests exercise the honeypot rather than the
        // link gate that now sits in front of it.
        TrialRegistrationLink link = new TrialRegistrationLink();
        link.setId(1);
        link.setToken("test-invite-token");
        link.setExpiresAt(java.time.LocalDateTime.now().plusDays(7));
        link.setRevoked(false);
        invite = link.getToken();
        when(linkRepo.findByToken("test-invite-token")).thenReturn(java.util.Optional.of(link));
        when(linkRepo.save(any(TrialRegistrationLink.class))).thenAnswer(i -> i.getArgument(0));
        FakeLinkUpdates.install(linkRepo, t -> "test-invite-token".equals(t) ? link : null);

        // Form tokens are signed with the guard's own key (audit P10), so the aged token
        // every submission carries is issued here, by that guard, ten seconds "ago".
        PublicFormGuard guard = new PublicFormGuard();
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis() - 10_000L);
        guard.setClock(clock::get);
        agedFormToken = guard.issueToken(null);
        guard.setClock(null);
        controller = new TrialRegistrationController(trialService, guard, links);
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("clientId", "TRIAL-1");
        ok.put("churchName", "Grace Chapel");
        ok.put("email", "ada@gracechapel.org");
        ok.put("invited", true);
        when(trialService.register(any())).thenReturn(ok);
    }

    /** Issued by the controller's guard in {@code setUp}, ten seconds before "now". */
    private String agedFormToken;

    /** A well-formed submission, aged past the time-trap, from a fresh IP. */
    private Map<String, Object> submission() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("churchName", "Grace Chapel");
        body.put("firstName", "Ada");
        body.put("lastName", "Okoye");
        body.put("email", "ada@gracechapel.org");
        body.put("formToken", agedFormToken);
        body.put("inviteToken", invite);
        return body;
    }

    /** Each test gets its own IP so the shared rate-limit window never bleeds across. */
    private MockHttpServletRequest request() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/trial-registration");
        req.setRemoteAddr("198.51.100." + (++ipCounter));
        return req;
    }

    @Test
    @DisplayName("a clean submission provisions the tenant and reports it")
    void cleanSubmissionProvisions() throws Exception {
        ResponseEntity<Map<String, Object>> res = controller.register(submission(), request());

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).containsEntry("status", "success");
        // The signature of a REAL success: it names the tenant it created.
        assertThat(res.getBody()).containsEntry("churchName", "Grace Chapel");
        assertThat(res.getBody()).containsEntry("email", "ada@gracechapel.org");
        verify(trialService).register(any());
    }

    @Test
    @DisplayName("an empty honeypot is not a trip — the field is present on every submission")
    void emptyHoneypotIsFine() throws Exception {
        Map<String, Object> body = submission();
        body.put("website", "");            // what a human's browser should send

        ResponseEntity<Map<String, Object>> res = controller.register(body, request());

        assertThat(res.getBody()).containsEntry("churchName", "Grace Chapel");
        verify(trialService).register(any());
    }

    @Test
    @DisplayName("a filled honeypot provisions nothing and returns a decoy with no church")
    void filledHoneypotIsADecoy() throws Exception {
        Map<String, Object> body = submission();
        body.put("website", "Grace Chapel");   // exactly what Chrome autofill put there

        ResponseEntity<Map<String, Object>> res = controller.register(body, request());

        // Looks like success to a bot...
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).containsEntry("status", "success");
        // ...but nothing was created, and the missing churchName is the tell. This
        // is the exact response that produced a blank "Church:" on screen.
        assertThat(res.getBody()).doesNotContainKey("churchName");
        assertThat(res.getBody()).doesNotContainKey("email");
        assertThat(res.getBody()).doesNotContainKey("clientId");
        verify(trialService, never()).register(any());
    }

    @Test
    @DisplayName("a submission with no invitation is refused, form fields notwithstanding")
    void noInvitationIsRefused() throws Exception {
        Map<String, Object> body = submission();
        body.remove("inviteToken");

        ResponseEntity<Map<String, Object>> res = controller.register(body, request());

        // Posting straight to the API is no easier than opening the page: the
        // filter guards the door, this guards the action.
        assertThat(res.getStatusCode().value()).isEqualTo(403);
        verify(trialService, never()).register(any());
    }

    @Test
    @DisplayName("a successful registration spends the invitation")
    void successConsumesTheInvitation() throws Exception {
        controller.register(submission(), request());

        assertThat(links.validate(invite).outcome())
                .isEqualTo(TrialRegistrationLinkService.Outcome.USED);
    }

    @Test
    @DisplayName("the guard only ever looks at the wire key, whatever the input is called")
    void guardChecksTheWireKeyOnly() {
        PublicFormGuard guard = new PublicFormGuard();

        // Renaming the INPUT to cgp_form_ref keeps the page out of Chrome's
        // autofill map; the value still travels under "website", so the shared
        // guard used by every public form needs no change.
        assertThat(guard.isHoneypotTripped(Map.of("website", "filled"))).isTrue();
        assertThat(guard.isHoneypotTripped(Map.of("cgp_form_ref", "filled"))).isFalse();
    }
}
