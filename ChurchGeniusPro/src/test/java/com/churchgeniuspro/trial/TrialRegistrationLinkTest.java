package com.churchgeniuspro.trial;

import com.churchgeniuspro.hibernate.TrialRegistrationLink;
import com.churchgeniuspro.repository.TrialRegistrationLinkRepository;
import com.churchgeniuspro.service.TrialRegistrationLinkService;
import com.churchgeniuspro.service.TrialRegistrationLinkService.Outcome;
import com.churchgeniuspro.webfilter.TrialRegistrationLinkFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The invitation link that gates the Trial Registration form.
 *
 * <p>The form provisions a tenant, so the tests that matter are the ones proving
 * the door stays SHUT: no token, an unknown token, an expired one, a spent one and
 * a revoked one all have to be refused, and the page is a static file, so the
 * refusal has to come from the filter rather than from any controller.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Trial registration links")
class TrialRegistrationLinkTest {

    @Mock private TrialRegistrationLinkRepository repo;

    private TrialRegistrationLinkService service;

    /** Rows the fake repository holds, keyed by token. */
    private final Map<String, TrialRegistrationLink> stored = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new TrialRegistrationLinkService(repo);
        ReflectionTestUtils.setField(service, "baseUrl", "http://localhost:8080");

        when(repo.save(any(TrialRegistrationLink.class))).thenAnswer(i -> {
            TrialRegistrationLink l = i.getArgument(0);
            if (l.getId() == null) l.setId(stored.size() + 1);
            stored.put(l.getToken(), l);
            return l;
        });
        when(repo.findByToken(anyString()))
                .thenAnswer(i -> Optional.ofNullable(stored.get(i.<String>getArgument(0))));
        FakeLinkUpdates.install(repo, stored::get);
        when(repo.findByProspectEmailIgnoreCaseAndUsedAtIsNullAndRevokedFalse(anyString()))
                .thenAnswer(i -> {
                    String email = i.getArgument(0);
                    List<TrialRegistrationLink> out = new ArrayList<>();
                    for (TrialRegistrationLink l : stored.values()) {
                        if (l.getProspectEmail() != null
                                && l.getProspectEmail().equalsIgnoreCase(email)
                                && l.getUsedAt() == null
                                && !Boolean.TRUE.equals(l.getRevoked())) out.add(l);
                    }
                    return out;
                });
    }

    private TrialRegistrationLink issue() {
        return service.generate("Grace Chapel", "pastor@example.org", null, null, "admin");
    }

    /* ── issuing ────────────────────────────────────────────────────────── */

    @Nested
    @DisplayName("issuing")
    class Issuing {

        @Test
        @DisplayName("the token is long, unguessable and URL-safe — never a sequential id")
        void tokenShape() {
            TrialRegistrationLink a = issue();
            TrialRegistrationLink b = service.generate("Other", "other@example.org", null, null, "admin");

            // 32 random bytes, unpadded URL-safe base64.
            assertThat(a.getToken()).hasSize(43).matches("^[A-Za-z0-9_-]+$");
            assertThat(a.getToken()).isNotEqualTo(b.getToken());
            // Nothing about the prospect is recoverable from it.
            assertThat(a.getToken()).doesNotContain("Grace").doesNotContain("example.org");
            assertThat(a.getToken()).isNotEqualTo(String.valueOf(a.getId()));
        }

        @Test
        @DisplayName("it expires seven days out by default")
        void defaultExpiry() {
            TrialRegistrationLink link = issue();
            assertThat(link.getExpiresAt())
                    .isAfter(LocalDateTime.now().plusDays(6).plusHours(23))
                    .isBefore(LocalDateTime.now().plusDays(7).plusMinutes(1));
        }

        @Test
        @DisplayName("an out-of-range validity falls back to the default")
        void validityIsClamped() {
            assertThat(service.generate("x", "a@b.co", null, 0, "admin").getExpiresAt())
                    .isAfter(LocalDateTime.now().plusDays(6));
            assertThat(service.generate("x", "c@d.co", null, 9999, "admin").getExpiresAt())
                    .isBefore(LocalDateTime.now().plusDays(8));
            assertThat(service.generate("x", "e@f.co", null, 14, "admin").getExpiresAt())
                    .isAfter(LocalDateTime.now().plusDays(13));
        }

        @Test
        @DisplayName("issuing a replacement revokes the prospect's previous link")
        void replacementRevokesTheOld() {
            TrialRegistrationLink first = issue();
            assertThat(service.validate(first.getToken()).valid()).isTrue();

            TrialRegistrationLink second = issue();

            // The old URL stops working the moment the new one exists — not when it
            // would have expired a week later.
            assertThat(service.validate(first.getToken()).outcome()).isEqualTo(Outcome.REVOKED);
            assertThat(service.validate(second.getToken()).valid()).isTrue();
            assertThat(second.getToken()).isNotEqualTo(first.getToken());
        }

        @Test
        @DisplayName("a usable link exposes its URL; a spent one does not")
        void urlOnlyWhileUsable() {
            TrialRegistrationLink link = issue();
            assertThat(service.toRow(link).get("url"))
                    .isEqualTo("http://localhost:8080/trialRegistration.html?id=" + link.getToken());

            service.consume(link.getToken(), "TRIAL-1");
            assertThat(service.toRow(link).get("url")).isNull();
        }
    }

    /* ── validating ─────────────────────────────────────────────────────── */

    @Nested
    @DisplayName("validating")
    class Validating {

        @Test
        @DisplayName("a fresh token is valid")
        void validToken() {
            assertThat(service.validate(issue().getToken()).valid()).isTrue();
        }

        @Test
        @DisplayName("no token and an unknown token are refused identically")
        void missingAndUnknownAreIndistinguishable() {
            var missing = service.validate(null);
            var blank   = service.validate("   ");
            var unknown = service.validate("Ai8sYm9ndXMtdG9rZW4tdGhhdC1kb2VzLW5vdC1leGlzdA");

            assertThat(missing.valid()).isFalse();
            assertThat(blank.valid()).isFalse();
            assertThat(unknown.valid()).isFalse();
            // Same wording on purpose: a different message for "unknown" would turn
            // the page into an oracle for guessing tokens.
            assertThat(unknown.message()).isEqualTo(missing.message());
        }

        @Test
        @DisplayName("an expired token is refused with the expiry wording")
        void expiredToken() {
            TrialRegistrationLink link = issue();
            link.setExpiresAt(LocalDateTime.now().minusMinutes(1));

            var v = service.validate(link.getToken());

            assertThat(v.outcome()).isEqualTo(Outcome.EXPIRED);
            assertThat(v.message()).isEqualTo(
                    "This Trial Registration link has expired. "
                  + "Please contact the administrator for a new registration link.");
        }

        @Test
        @DisplayName("a token cannot be used twice")
        void singleUse() {
            TrialRegistrationLink link = issue();

            var first = service.consume(link.getToken(), "TRIAL-1");
            assertThat(first.valid()).isTrue();
            assertThat(link.getUsedClientId()).isEqualTo("TRIAL-1");

            assertThat(service.validate(link.getToken()).outcome()).isEqualTo(Outcome.USED);
            // And a second consume changes nothing about the first registration.
            assertThat(service.consume(link.getToken(), "TRIAL-2").valid()).isFalse();
            assertThat(link.getUsedClientId()).isEqualTo("TRIAL-1");
        }

        @Test
        @DisplayName("a soft-deleted link stops working, and says the same as a revoked one")
        void softDeletedLinkRefused() {
            TrialRegistrationLink link = issue();
            link.setDeletedAt(java.time.LocalDateTime.now());
            link.setDeletedBy("ops@churchgeniuspro.com");

            // The prospect is told it no longer works and to ask for another — what
            // the Service Admin did with their own list is not their business.
            assertThat(link.getStatus()).isEqualTo("DELETED");
            assertThat(link.isUsable()).isFalse();
            assertThat(service.validate(link.getToken()).outcome()).isEqualTo(Outcome.REVOKED);
            assertThat(service.claim(link.getToken()).valid()).isFalse();
        }

        @Test
        @DisplayName("a revoked token is refused")
        void revokedToken() {
            TrialRegistrationLink link = issue();
            when(repo.findById(link.getId())).thenReturn(Optional.of(link));

            service.revoke(link.getId(), "admin");

            assertThat(service.validate(link.getToken()).outcome()).isEqualTo(Outcome.REVOKED);
        }

        @Test
        @DisplayName("an expired link is not silently consumed")
        void expiredIsNotConsumed() {
            TrialRegistrationLink link = issue();
            link.setExpiresAt(LocalDateTime.now().minusMinutes(1));

            assertThat(service.consume(link.getToken(), "TRIAL-9").valid()).isFalse();
            assertThat(link.getUsedAt()).isNull();
        }
    }

    /* ── the page gate ──────────────────────────────────────────────────── */

    @Nested
    @DisplayName("page gate")
    class PageGate {

        private record Result(boolean reachedPage, int status, String forwardedTo) {}

        private Result open(String queryParam, String tokenValue) throws Exception {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/trialRegistration.html");
            if (queryParam != null) req.setParameter(queryParam, tokenValue);
            MockHttpServletResponse res = new MockHttpServletResponse();

            boolean[] reached = { false };
            FilterChain chain = (rq, rs) -> reached[0] = true;
            new TrialRegistrationLinkFilter(service).doFilter(req, res, chain);

            return new Result(reached[0], res.getStatus(), res.getForwardedUrl());
        }

        @Test
        @DisplayName("a valid link opens the form")
        void validLinkOpensTheForm() throws Exception {
            Result r = open("id", issue().getToken());

            assertThat(r.reachedPage()).isTrue();
            assertThat(r.status()).isEqualTo(200);
        }

        @Test
        @DisplayName("direct access with no token is refused — the static file is not a way in")
        void directAccessBlocked() throws Exception {
            Result r = open(null, null);

            assertThat(r.reachedPage()).isFalse();
            assertThat(r.status()).isEqualTo(403);
            assertThat(r.forwardedTo()).startsWith("/trialRegistrationInvalid.html");
        }

        @Test
        @DisplayName("an invalid token is refused")
        void invalidTokenBlocked() throws Exception {
            Result r = open("id", "not-a-real-token");

            assertThat(r.reachedPage()).isFalse();
            assertThat(r.status()).isEqualTo(403);
            assertThat(r.forwardedTo()).contains("reason=unknown");
        }

        @Test
        @DisplayName("an expired token is refused, and the page is told why")
        void expiredTokenBlocked() throws Exception {
            TrialRegistrationLink link = issue();
            link.setExpiresAt(LocalDateTime.now().minusDays(1));

            Result r = open("id", link.getToken());

            assertThat(r.reachedPage()).isFalse();
            assertThat(r.forwardedTo()).contains("reason=expired");
        }

        @Test
        @DisplayName("a spent token is refused")
        void usedTokenBlocked() throws Exception {
            TrialRegistrationLink link = issue();
            service.consume(link.getToken(), "TRIAL-1");

            Result r = open("id", link.getToken());

            assertThat(r.reachedPage()).isFalse();
            assertThat(r.forwardedTo()).contains("reason=used");
        }

        @Test
        @DisplayName("?token= is accepted as well as ?id=")
        void bothParameterSpellings() throws Exception {
            assertThat(open("token", issue().getToken()).reachedPage()).isTrue();
        }

        @Test
        @DisplayName("an unreadable token table fails CLOSED, unlike most guards here")
        void validationFailureFailsClosed() throws Exception {
            when(repo.findByToken(anyString())).thenThrow(new RuntimeException("db down"));

            Result r = open("id", "anything");

            // This page provisions a tenant, so a database blip must not open it to
            // everyone. Refusing a genuine prospect for a minute is the cheaper error.
            assertThat(r.reachedPage()).isFalse();
            assertThat(r.status()).isEqualTo(403);
            assertThat(r.forwardedTo()).contains("reason=error");
        }
    }
}
