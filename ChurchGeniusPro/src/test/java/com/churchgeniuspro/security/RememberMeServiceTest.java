package com.churchgeniuspro.security;

import com.churchgeniuspro.hibernate.RememberMeToken;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.service.RememberMeService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Per-device "Remember me" (mobile Phase 1, M2): the credential model that replaces
 * the single clear-text {@code signup.remember} column.
 */
class RememberMeServiceTest {

    private InMemoryRememberMeTokens store;
    private RememberMeService service;
    private SignUp alice;

    @BeforeEach
    void setUp() {
        store   = new InMemoryRememberMeTokens(mock(LoginRepository.class));
        service = new RememberMeService(store.tokens, store.loginRepo);
        alice   = store.signup(1, "alice", "USR-1");
    }

    private static MockHttpServletRequest request(String ua, String cookieValue) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        if (ua != null) r.addHeader("User-Agent", ua);
        if (cookieValue != null) r.setCookies(new Cookie(RememberMeService.COOKIE_NAME, cookieValue));
        return r;
    }

    private static String cookieOf(MockHttpServletResponse res) {
        Cookie c = res.getCookie(RememberMeService.COOKIE_NAME);
        return c == null ? null : c.getValue();
    }

    private String issue(String ua) {
        MockHttpServletResponse res = new MockHttpServletResponse();
        service.issue(alice, request(ua, null), res);
        return cookieOf(res);
    }

    @Test
    @DisplayName("the database holds the SHA-256 of the cookie, never the cookie value")
    void storesHashOnly() {
        String cookie = issue("Mozilla/5.0 (Windows NT 10.0) Chrome/130");
        RememberMeToken row = store.rows.values().iterator().next();
        assertThat(cookie).isNotBlank().hasSizeGreaterThanOrEqualTo(43);
        assertThat(row.getTokenHash()).isEqualTo(RememberMeService.sha256Hex(cookie)).isNotEqualTo(cookie);
        assertThat(row.getDeviceLabel()).isEqualTo("Chrome on Windows");
        assertThat(row.getPlatform()).isEqualTo("web");
        assertThat(row.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofDays(29)));
        assertThat(row.toString()).doesNotContain(row.getTokenHash());
    }

    @Test
    @DisplayName("the cookie has the flags the session cookie has")
    void cookieFlags() {
        service.setCookieSecure(true);
        MockHttpServletResponse res = new MockHttpServletResponse();
        service.issue(alice, request("x", null), res);
        Cookie c = res.getCookie(RememberMeService.COOKIE_NAME);
        assertThat(c.isHttpOnly()).isTrue();
        assertThat(c.getSecure()).isTrue();
        assertThat(c.getPath()).isEqualTo("/");
        assertThat(c.getMaxAge()).isEqualTo(30 * 24 * 60 * 60);
        assertThat(c.getAttribute("SameSite")).isEqualTo("Lax");
    }

    @Test
    @DisplayName("two devices get two independent credentials — the old single column allowed one")
    void twoDevicesBothWork() {
        String phone  = issue("iPhone Safari/605");
        String laptop = issue("Windows Chrome/130");
        assertThat(service.resolve(request(null, phone))).isPresent();
        assertThat(service.resolve(request(null, laptop))).isPresent();
        assertThat(store.active(1)).isEqualTo(2);
    }

    @Test
    @DisplayName("a value that matches nothing, or a tampered value, resolves to nothing")
    void unknownOrTamperedIsRefused() {
        String cookie = issue("x");
        assertThat(service.resolve(request(null, cookie + "x"))).isEmpty();
        assertThat(service.resolve(request(null, "not-a-token"))).isEmpty();
        assertThat(service.resolve(request(null, null))).isEmpty();
    }

    @Test
    @DisplayName("rotation issues a new value; the old one works only inside the grace window")
    void rotationWithGrace() {
        String first = issue("x");
        RememberMeService.Resolved r = service.resolve(request(null, first)).orElseThrow();
        MockHttpServletResponse res = new MockHttpServletResponse();
        service.rotate(r.token(), res);
        String second = cookieOf(res);

        assertThat(second).isNotEqualTo(first);
        assertThat(service.resolve(request(null, second))).isPresent();
        assertThat(service.resolve(request(null, first))).as("second tab inside the grace window").isPresent();

        r.token().setRotatedAt(Instant.now().minus(RememberMeService.ROTATION_GRACE).minusSeconds(1));
        assertThat(service.resolve(request(null, first))).as("after the grace window").isEmpty();
        assertThat(service.resolve(request(null, second))).isPresent();
    }

    @Test
    @DisplayName("expiry slides on use; an unused credential dies after 30 days")
    void slidingExpiry() {
        String cookie = issue("x");
        RememberMeToken row = store.rows.values().iterator().next();
        row.setExpiresAt(Instant.now().minusSeconds(1));
        assertThat(service.resolve(request(null, cookie))).isEmpty();

        row.setExpiresAt(Instant.now().plusSeconds(60));
        RememberMeService.Resolved r = service.resolve(request(null, cookie)).orElseThrow();
        service.rotate(r.token(), new MockHttpServletResponse());
        assertThat(row.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofDays(29)));
    }

    @Test
    @DisplayName("revoking the current device leaves the other devices signed in")
    void revokeCurrentOnly() {
        String phone  = issue("iPhone");
        String laptop = issue("Windows");
        MockHttpServletResponse res = new MockHttpServletResponse();
        service.revokeCurrent(request(null, phone), res);

        assertThat(res.getCookie(RememberMeService.COOKIE_NAME).getMaxAge()).isZero();
        assertThat(service.resolve(request(null, phone))).isEmpty();
        assertThat(service.resolve(request(null, laptop))).isPresent();
        assertThat(store.active(1)).isEqualTo(1);
    }

    @Test
    @DisplayName("revokeAllFor signs every device out (password reset)")
    void revokeAll() {
        String a = issue("a"); String b = issue("b"); String c = issue("c");
        assertThat(service.revokeAllFor(1)).isEqualTo(3);
        assertThat(service.resolve(request(null, a))).isEmpty();
        assertThat(service.resolve(request(null, b))).isEmpty();
        assertThat(service.resolve(request(null, c))).isEmpty();
        assertThat(service.revokeAllFor(1)).isZero();
        assertThat(service.revokeAllFor(null)).isZero();
    }

    @Test
    @DisplayName("at most 10 live devices per login; the oldest is dropped")
    void capPerAccount() {
        String oldest = issue("d0");
        for (int i = 1; i < 10; i++) issue("d" + i);
        assertThat(store.active(1)).isEqualTo(10);
        assertThat(service.resolve(request(null, oldest))).isPresent();

        String eleventh = issue("d10");
        assertThat(store.active(1)).isEqualTo(10);
        assertThat(service.resolve(request(null, oldest))).as("oldest evicted").isEmpty();
        assertThat(service.resolve(request(null, eleventh))).isPresent();
    }

    @Test
    @DisplayName("a cookie issued by the old code (signup.remember) still logs in once and is migrated")
    void legacyTokenMigrates() {
        alice.setRemember("3f1c2d0e-legacy-uuid");
        Optional<RememberMeService.Resolved> r = service.resolve(request("Windows Chrome/1", "3f1c2d0e-legacy-uuid"));
        assertThat(r).isPresent();
        assertThat(r.get().user().getId()).isEqualTo(1);
        assertThat(alice.getRemember()).as("legacy column cleared").isNull();
        assertThat(store.rows).hasSize(1);
        assertThat(store.rows.values().iterator().next().getTokenHash())
                .isEqualTo(RememberMeService.sha256Hex("3f1c2d0e-legacy-uuid"));

        // After rotation the raw legacy value is dead for good.
        MockHttpServletResponse res = new MockHttpServletResponse();
        service.rotate(r.get().token(), res);
        r.get().token().setRotatedAt(Instant.now().minus(RememberMeService.ROTATION_GRACE).minusSeconds(1));
        assertThat(service.resolve(request(null, "3f1c2d0e-legacy-uuid"))).isEmpty();
        assertThat(service.resolve(request(null, cookieOf(res)))).isPresent();
    }

    @Test
    @DisplayName("a deleted login never resolves even with a live row")
    void deletedLoginRefused() {
        String cookie = issue("x");
        alice.setDeleted(true);
        assertThat(service.resolve(request(null, cookie))).isEmpty();
    }

    @Test
    @DisplayName("purge deletes only rows that can never authenticate and are a week old")
    void purge() {
        String live = issue("live");
        issue("revoked-old"); issue("expired-old");
        RememberMeToken revokedOld = store.rows.get(2L); revokedOld.setRevokedAt(Instant.now().minus(Duration.ofDays(8)));
        RememberMeToken expiredOld = store.rows.get(3L); expiredOld.setExpiresAt(Instant.now().minus(Duration.ofDays(8)));
        issue("revoked-recent"); store.rows.get(4L).setRevokedAt(Instant.now().minus(Duration.ofDays(1)));

        service.purgeDeadTokens();
        assertThat(store.rows.keySet()).containsExactlyInAnyOrder(1L, 4L);
        assertThat(service.resolve(request(null, live))).isPresent();
    }

    @Test
    @DisplayName("platform comes from the X-CGP-Platform header the native shells will send")
    void platformHeader() {
        MockHttpServletRequest r = request("Android", null);
        r.addHeader("X-CGP-Platform", "android");
        service.issue(alice, r, new MockHttpServletResponse());
        assertThat(store.rows.get(1L).getPlatform()).isEqualTo("android");
        MockHttpServletRequest bogus = request("x", null);
        bogus.addHeader("X-CGP-Platform", "toaster");
        service.issue(alice, bogus, new MockHttpServletResponse());
        assertThat(store.rows.get(2L).getPlatform()).isEqualTo("web");
    }
}
