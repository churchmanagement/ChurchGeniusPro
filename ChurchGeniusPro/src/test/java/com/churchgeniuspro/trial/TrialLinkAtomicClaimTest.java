package com.churchgeniuspro.trial;

import com.churchgeniuspro.controller.TrialRegistrationController;
import com.churchgeniuspro.hibernate.TrialRegistrationLink;
import com.churchgeniuspro.repository.TrialRegistrationLinkRepository;
import com.churchgeniuspro.service.TrialRegistrationLinkService;
import com.churchgeniuspro.service.TrialRegistrationLinkService.Outcome;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * H4: one invitation token creates exactly one tenant, however many POSTs race.
 *
 * <p>The link used to be marked used only AFTER provisioning, by a read-then-save
 * with nothing exclusive in between, so every concurrent request carrying the
 * same token passed validation and each provisioned a tenant. The claim is now a
 * single conditional UPDATE taken BEFORE provisioning; a failed provisioning
 * hands the link back.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Trial invitation — atomic single-use claim (H4)")
class TrialLinkAtomicClaimTest {

    private static final String TOKEN = "one-shot-token";

    @Mock private TrialRegistrationLinkRepository repo;
    @Mock private TrialRegistrationService trialService;

    private TrialRegistrationLinkService links;
    private TrialRegistrationController controller;
    private TrialRegistrationLink link;
    private final AtomicInteger tenantsCreated = new AtomicInteger();

    @BeforeEach
    void setUp() {
        link = new TrialRegistrationLink();
        link.setId(1);
        link.setToken(TOKEN);
        link.setProspectEmail("ada@gracechapel.org");
        link.setExpiresAt(LocalDateTime.now().plusDays(7));
        link.setRevoked(false);
        when(repo.findByToken(TOKEN)).thenReturn(Optional.of(link));
        when(repo.save(any(TrialRegistrationLink.class))).thenAnswer(i -> i.getArgument(0));
        FakeLinkUpdates.install(repo, t -> TOKEN.equals(t) ? link : null);

        links = new TrialRegistrationLinkService(repo);
        // Distinct IPs per request and a 10-second-old form token keep the rate
        // limit and the time-trap out of the way: only the link claim is under test.
        // Form tokens are signed with the guard's own key (audit P10), so the aged token
        // every submission carries is issued here, by that guard, ten seconds "ago".
        PublicFormGuard guard = new PublicFormGuard();
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis() - 10_000L);
        guard.setClock(clock::get);
        agedFormToken = guard.issueToken(null);
        guard.setClock(null);
        controller = new TrialRegistrationController(trialService, guard, links);

        when(trialService.register(any())).thenAnswer(i -> {
            Thread.sleep(30);                         // provisioning takes a while — the old race window
            int n = tenantsCreated.incrementAndGet();
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("clientId", "TRIAL-" + n);
            ok.put("churchName", "Grace Chapel");
            ok.put("email", "ada@gracechapel.org");
            ok.put("invited", true);
            return ok;
        });
    }

    private Map<String, Object> submission() {
        Map<String, Object> b = new HashMap<>();
        b.put("churchName", "Grace Chapel");
        b.put("firstName", "Ada");
        b.put("lastName", "Lovelace");
        b.put("email", "ada@gracechapel.org");
        b.put("phone", "(217) 555-0100");
        b.put("inviteToken", TOKEN);
        b.put("formToken", agedFormToken);
        return b;
    }

    /** Issued by the controller's guard in {@code setUp}, ten seconds before "now"; the
     *  guard does not make tokens single-use, so every submission may carry it. */
    private String agedFormToken;

    private static MockHttpServletRequest request(int ipTail) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/trial-registration");
        req.setRemoteAddr("10.0.0." + ipTail);     // distinct IPs so the per-IP window is not what stops them
        return req;
    }

    /* ── the race ───────────────────────────────────────────────────────── */

    @Test
    @DisplayName("twelve simultaneous POSTs with one token create exactly one tenant")
    void concurrentPostsCreateOneTenant() throws Exception {
        int n = 12;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int ip = i + 1;
            results.add(pool.submit(() -> {
                go.await();
                return controller.register(submission(), request(ip)).getStatusCode().value();
            }));
        }
        go.countDown();
        int ok = 0, refused = 0;
        for (Future<Integer> f : results) {
            int status = f.get(10, TimeUnit.SECONDS);
            if (status == 200) ok++; else if (status == 403) refused++;
        }
        pool.shutdownNow();

        assertThat(ok).as("winners").isEqualTo(1);
        assertThat(refused).as("losers told the link is used").isEqualTo(n - 1);
        assertThat(tenantsCreated.get()).as("tenants provisioned").isEqualTo(1);
        verify(trialService, times(1)).register(any());
        assertThat(link.getUsedClientId()).isEqualTo("TRIAL-1");
        assertThat(links.validate(TOKEN).outcome()).isEqualTo(Outcome.USED);
    }

    /* ── ordering and release ───────────────────────────────────────────── */

    @Test
    @DisplayName("the link is claimed BEFORE provisioning, not after")
    void claimHappensBeforeProvisioning() throws Exception {
        when(trialService.register(any())).thenAnswer(i -> {
            // While provisioning runs, the link must already be spent.
            assertThat(link.getUsedAt()).isNotNull();
            assertThat(link.getUsedClientId()).startsWith("PENDING:");
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("clientId", "TRIAL-7"); ok.put("churchName", "x"); ok.put("email", "e"); ok.put("invited", true);
            return ok;
        });
        ResponseEntity<Map<String, Object>> res = controller.register(submission(), request(1));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(link.getUsedClientId()).isEqualTo("TRIAL-7");   // marker replaced by the real tenant
    }

    @Test
    @DisplayName("a failed provisioning hands the link back, so the prospect can try again")
    void failureReleasesTheLink() throws Exception {
        // The ERROR + stack trace this prints in the build output ("db down", with a
        // TrialLinkAtomicClaimTest frame at the bottom) is this test working: the
        // controller logs the failed registration before handing the link back.
        // See TESTING.md → "Reading the build output".
        when(trialService.register(any())).thenThrow(new RuntimeException("db down"));

        ResponseEntity<Map<String, Object>> res = controller.register(submission(), request(1));
        assertThat(res.getStatusCode().value()).isEqualTo(500);
        assertThat(link.getUsedAt()).isNull();
        assertThat(link.getUsedClientId()).isNull();
        assertThat(links.validate(TOKEN).outcome()).isEqualTo(Outcome.VALID);

        // ...and the retry succeeds and spends it.
        reset(trialService);
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("clientId", "TRIAL-2"); ok.put("churchName", "x"); ok.put("email", "e"); ok.put("invited", true);
        when(trialService.register(any())).thenReturn(ok);
        assertThat(controller.register(submission(), request(2)).getStatusCode().value()).isEqualTo(200);
        assertThat(links.validate(TOKEN).outcome()).isEqualTo(Outcome.USED);
    }

    @Test
    @DisplayName("a form-level refusal (bad input) also hands the link back")
    void badInputReleasesTheLink() throws Exception {
        when(trialService.register(any())).thenThrow(new IllegalArgumentException("Church name is required."));
        assertThat(controller.register(submission(), request(1)).getStatusCode().value()).isEqualTo(400);
        assertThat(links.validate(TOKEN).outcome()).isEqualTo(Outcome.VALID);
    }

    /* ── the service on its own ─────────────────────────────────────────── */

    @Test
    @DisplayName("release and complete act only on the claim that holds the marker")
    void markerScopesCompleteAndRelease() {
        TrialRegistrationLinkService.Claim first = links.claim(TOKEN);
        assertThat(first.valid()).isTrue();

        TrialRegistrationLinkService.Claim second = links.claim(TOKEN);
        assertThat(second.valid()).isFalse();
        assertThat(second.validation().outcome()).isEqualTo(Outcome.USED);

        // A loser's release must not free the winner's claim.
        links.release(second);
        assertThat(link.getUsedAt()).isNotNull();
        assertThat(link.getUsedClientId()).isEqualTo(first.marker());

        links.complete(first, "TRIAL-1");
        assertThat(link.getUsedClientId()).isEqualTo("TRIAL-1");
        // Once completed, even the winner's own release is a no-op — the tenant exists.
        links.release(first);
        assertThat(link.getUsedAt()).isNotNull();
        assertThat(link.getUsedClientId()).isEqualTo("TRIAL-1");
    }

    @Test
    @DisplayName("an expired or revoked link cannot be claimed")
    void expiredAndRevokedCannotBeClaimed() {
        link.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        assertThat(links.claim(TOKEN).validation().outcome()).isEqualTo(Outcome.EXPIRED);
        assertThat(link.getUsedAt()).isNull();

        link.setExpiresAt(LocalDateTime.now().plusDays(1));
        link.setRevoked(true);
        assertThat(links.claim(TOKEN).validation().outcome()).isEqualTo(Outcome.REVOKED);
        assertThat(link.getUsedAt()).isNull();
    }

    /* ── trial length comes from the link, never the public form ────────── */

    @Test
    @DisplayName("the trial length is the one chosen on the link; a value in the form body is ignored")
    void trialLengthComesFromTheLink() throws Exception {
        link.setTrialDays(90);
        Map<String, Object> body = submission();
        body.put("trialDays", 365);                      // a visitor trying to lengthen their own trial
        controller.register(body, request(3));

        org.mockito.ArgumentCaptor<com.churchgeniuspro.model.TrialRegistrationBO> bo =
                org.mockito.ArgumentCaptor.forClass(com.churchgeniuspro.model.TrialRegistrationBO.class);
        verify(trialService).register(bo.capture());
        assertThat(bo.getValue().getTrialDays()).isEqualTo(90);
    }
}
