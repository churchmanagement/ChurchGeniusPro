package com.churchgeniuspro.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the public-form anti-spam layers: honeypot, time-trap token,
 * per-IP rate limiting, and server-side field validation.
 */
class PublicFormGuardTest {

    private PublicFormGuard guard;

    @BeforeEach
    void setUp() {
        guard = new PublicFormGuard();
    }

    /* ── honeypot ─────────────────────────────────────────────────────── */

    @Test
    void honeypotTripsOnlyWhenFilled() {
        assertFalse(guard.isHoneypotTripped(Map.of()));
        assertFalse(guard.isHoneypotTripped(Map.of("website", "")));
        assertTrue(guard.isHoneypotTripped(Map.of("website", "http://spam.example")));
        Map<String, Object> nullBody = new HashMap<>();
        nullBody.put("website", null);
        assertFalse(guard.isHoneypotTripped(nullBody));
    }

    /* ── time-trap token ──────────────────────────────────────────────── */

    @Test
    void tokenRoundTripRejectsTooFastAndAcceptsAfterMinAge() throws Exception {
        String token = guard.issueToken("CID123");
        assertNotNull(token);
        // Immediately: too fast
        assertNotNull(guard.checkToken(token, "CID123"));
        // After the minimum age it passes
        Thread.sleep((PublicFormGuard.MIN_FORM_SECONDS + 1) * 1000L);
        assertNull(guard.checkToken(token, "CID123"));
    }

    @Test
    void tokenRejectsMissingGarbageAndWrongCid() throws Exception {
        assertNotNull(guard.checkToken(null, "CID123"));
        assertNotNull(guard.checkToken("", "CID123"));
        assertNotNull(guard.checkToken("not-a-token", "CID123"));
        String token = guard.issueToken("CID123");
        Thread.sleep((PublicFormGuard.MIN_FORM_SECONDS + 1) * 1000L);
        assertNotNull(guard.checkToken(token, "OTHER"));   // bound to cid
        assertNull(guard.checkToken(token, "CID123"));
    }

    /* ── rate limiting ────────────────────────────────────────────────── */

    @Test
    void rateLimitBlocksAfterWindowMax() {
        for (int i = 0; i < PublicFormGuard.MAX_PER_WINDOW; i++) {
            assertNull(guard.checkRate("1.2.3.4", "connect"), "hit " + i + " should pass");
        }
        assertNotNull(guard.checkRate("1.2.3.4", "connect"));
        // Different IP and different form are unaffected
        assertNull(guard.checkRate("5.6.7.8", "connect"));
        assertNull(guard.checkRate("1.2.3.4", "prayer"));
    }

    /* ── validation ───────────────────────────────────────────────────── */

    @Test
    void emailValidation() {
        assertNull(guard.checkEmail(null));
        assertNull(guard.checkEmail(""));
        assertNull(guard.checkEmail("sam@example.org"));
        assertNotNull(guard.checkEmail("not-an-email"));
        assertNotNull(guard.checkEmail("a@b"));
    }

    @Test
    void phoneValidation() {
        assertNull(guard.checkPhone(null));
        assertNull(guard.checkPhone("(555) 201-8890"));
        assertNotNull(guard.checkPhone("123"));
        assertNotNull(guard.checkPhone("12345678901234567890"));
    }

    @Test
    void textLinkSpamAndLength() {
        assertNull(guard.checkText(null, 100, 0));
        assertNull(guard.checkText("I visited last Sunday", 100, 0));
        assertNotNull(guard.checkText("buy now http://spam.example", 100, 0));
        assertNull(guard.checkText("see https://mychurch.org", 100, 1));   // one link allowed
        assertNotNull(guard.checkText("x".repeat(101), 100, 0));
    }

    /* ── captcha (not configured) ─────────────────────────────────────── */

    @Test
    void captchaDisabledWhenNoSecret() {
        assertFalse(guard.captchaEnabled());
        assertNull(guard.captchaSiteKey());
        assertNull(guard.checkCaptcha(null, "1.2.3.4"));   // no-op when disabled
    }
}
