package com.churchgeniuspro.publiclink;

import com.churchgeniuspro.hibernate.PublicScreenLink;
import com.churchgeniuspro.repository.PublicScreenLinkRepository;
import com.churchgeniuspro.service.MessagingPolicy;
import com.churchgeniuspro.service.PublicLinkResolver;
import com.churchgeniuspro.service.PublicPagePolicy;
import com.churchgeniuspro.service.SubscriptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Public links are database rows with random tokens. The resolver is the only
 * authority: a token names one row, that row's page and tenant decide, and the
 * publish policy is re-checked at resolution so a link never outlives the rule.
 * There is no "encrypted client id" shape any more — nothing is decrypted.
 */
@DisplayName("Public link resolution")
class PublicLinkResolverTest {

    private static final String CLIENT = "CGP-CLIENT-1";
    private static final String PAGE   = PublicPagePolicy.CONNECT_URL;

    private static PublicScreenLink link(String clientId, String pageUrl, String token, boolean revoked, LocalDate exp) {
        PublicScreenLink l = new PublicScreenLink();
        l.setId(token.hashCode() & 0x7fffffff);
        l.setAppClientId(clientId); l.setPageUrl(pageUrl); l.setToken(token);
        l.setRevoked(revoked); l.setExpirationDate(exp);
        return l;
    }

    private PublicScreenLinkRepository repo;
    private SubscriptionService subscriptions;

    private PublicLinkResolver resolverWith(List<PublicScreenLink> rows) {
        repo = mock(PublicScreenLinkRepository.class);
        when(repo.findByToken(anyString())).thenAnswer(inv -> rows.stream()
                .filter(l -> inv.getArgument(0).equals(l.getToken())).findFirst());
        when(repo.findByAppClientIdAndPageUrlAndRevokedFalseOrderByCreatedDateDesc(anyString(), anyString()))
                .thenAnswer(inv -> rows.stream()
                        .filter(l -> inv.getArgument(0).equals(l.getAppClientId()))
                        .filter(l -> inv.getArgument(1).equals(l.getPageUrl()))
                        .filter(l -> !l.isRevoked()).toList());
        when(repo.save(any(PublicScreenLink.class))).thenAnswer(inv -> { PublicScreenLink l = inv.getArgument(0); rows.add(l); return l; });
        subscriptions = mock(SubscriptionService.class);
        when(subscriptions.isFeatureEnabled(anyString(), anyString())).thenReturn(true);
        MessagingPolicy messaging = mock(MessagingPolicy.class);
        when(messaging.trialState(anyString())).thenReturn(Boolean.FALSE);
        return new PublicLinkResolver(repo, new PublicPagePolicy(subscriptions, messaging));
    }

    @Test @DisplayName("An active link token resolves to its tenant")
    void activeTokenResolves() {
        PublicLinkResolver r = resolverWith(new ArrayList<>(List.of(link(CLIENT, PAGE, "tok-live", false, null))));
        assertThat(r.resolveClientId("tok-live", PAGE)).isEqualTo(CLIENT);
    }

    @Test @DisplayName("Revoking a link stops that link")
    void revokedTokenRefused() {
        PublicLinkResolver r = resolverWith(new ArrayList<>(List.of(link(CLIENT, PAGE, "tok-rev", true, null))));
        assertThat(r.resolveClientId("tok-rev", PAGE)).isNull();
    }

    @Test @DisplayName("An expired link stops; one expiring today still works")
    void expiry() {
        PublicLinkResolver r = resolverWith(new ArrayList<>(List.of(
                link(CLIENT, PAGE, "tok-old", false, LocalDate.now().minusDays(1)),
                link(CLIENT, PAGE, "tok-today", false, LocalDate.now()))));
        assertThat(r.resolveClientId("tok-old", PAGE)).isNull();
        assertThat(r.resolveClientId("tok-today", PAGE)).isEqualTo(CLIENT);
    }

    @Test @DisplayName("A token minted for another page does not unlock this page")
    void tokenForOtherPageRefused() {
        PublicLinkResolver r = resolverWith(new ArrayList<>(List.of(link(CLIENT, PublicPagePolicy.DONATION_PAGE_URL, "tok-donate", false, null))));
        assertThat(r.resolveClientId("tok-donate", PAGE)).isNull();
        assertThat(r.resolveClientId("tok-donate", PublicPagePolicy.DONATION_PAGE_URL)).isEqualTo(CLIENT);
    }

    @Test @DisplayName("Nothing that is not a stored token resolves — no encrypted-id fallback")
    void noDecryptFallback() throws Exception {
        PublicLinkResolver r = resolverWith(new ArrayList<>());
        String legacy = com.churchgeniuspro.util.EncryptionUtil.encrypt(CLIENT);
        assertThat(r.resolveClientId(legacy, PAGE)).isNull();
        assertThat(r.resolveClientId(CLIENT, PAGE)).as("plaintext client id").isNull();
        assertThat(r.resolveClientId("", PAGE)).isNull();
        assertThat(r.resolveClientId(null, PAGE)).isNull();
    }

    @Test @DisplayName("A page the church may no longer publish stops resolving even for an existing link")
    void policyRecheckedAtResolution() {
        PublicLinkResolver r = resolverWith(new ArrayList<>(List.of(link(CLIENT, PublicPagePolicy.KIDS_CHECKIN_URL, "tok-kids", false, null))));
        when(subscriptions.isFeatureEnabled(CLIENT, "kidsCheckin")).thenReturn(false);
        assertThat(r.resolveClientId("tok-kids", PublicPagePolicy.KIDS_CHECKIN_URL)).isNull();
    }

    @Test @DisplayName("Tokens are long, random and never repeat")
    void tokensAreRandom() {
        String a = PublicLinkResolver.newToken(), b = PublicLinkResolver.newToken();
        assertThat(a).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(a).isNotEqualTo(b);
    }

    @Test @DisplayName("ensureLink reuses the live link and creates one only when none exists")
    void ensureLink() {
        List<PublicScreenLink> rows = new ArrayList<>(List.of(link(CLIENT, PAGE, "tok-live", false, null)));
        PublicLinkResolver r = resolverWith(rows);
        assertThat(r.ensureLink(CLIENT, PAGE, "Connect").map(PublicScreenLink::getToken)).contains("tok-live");
        verify(repo, never()).save(any());

        Optional<PublicScreenLink> made = r.ensureLink(CLIENT, PublicPagePolicy.UPCOMING_EVENTS_URL, "Events");
        assertThat(made).isPresent();
        assertThat(made.get().getToken()).hasSize(43);
        assertThat(made.get().getAppClientId()).isEqualTo(CLIENT);
        verify(repo).save(any());
    }

    @Test @DisplayName("ensureLink refuses a page the policy forbids for this church")
    void ensureLinkHonoursPolicy() {
        PublicLinkResolver r = resolverWith(new ArrayList<>());
        when(subscriptions.isFeatureEnabled(CLIENT, "activityCorner")).thenReturn(false);
        assertThat(r.ensureLink(CLIENT, PublicPagePolicy.GUESS_IT_URL, "Guess It")).isEmpty();
        verify(repo, never()).save(any());
    }

    @Test @DisplayName("ensureLink refuses a forbidden page even when an old link still exists")
    void ensureLinkHonoursPolicyBeforeReusingARow() {
        // The policy used to be consulted only when no row existed, so a church that
        // had published Guess It BEFORE losing the feature was still handed that
        // URL — one resolve() then refuses, leaving an address that goes nowhere and
        // no explanation. The row must not outrank the rule.
        List<PublicScreenLink> rows = new ArrayList<>(List.of(
                link(CLIENT, PublicPagePolicy.GUESS_IT_URL, "tok-old", false, null)));
        PublicLinkResolver r = resolverWith(rows);
        when(subscriptions.isFeatureEnabled(CLIENT, "activityCorner")).thenReturn(false);

        assertThat(r.ensureLink(CLIENT, PublicPagePolicy.GUESS_IT_URL, "Guess It")).isEmpty();
        // ...and it is still returned once the church has the feature again.
        when(subscriptions.isFeatureEnabled(CLIENT, "activityCorner")).thenReturn(true);
        assertThat(r.ensureLink(CLIENT, PublicPagePolicy.GUESS_IT_URL, "Guess It")
                .map(PublicScreenLink::getToken)).contains("tok-old");
    }

    @Test @DisplayName("Another church's link never resolves for this church")
    void otherTenantIgnored() {
        PublicLinkResolver r = resolverWith(new ArrayList<>(List.of(link("CGP-OTHER", PAGE, "tok-other", false, null))));
        assertThat(r.resolveClientId("tok-other", PAGE)).isEqualTo("CGP-OTHER");   // it resolves to ITS church
        assertThat(r.activeLink(CLIENT, PAGE)).isEmpty();
    }
}
