package com.churchgeniuspro.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Client address resolution, with a deliberate focus on the Azure App Service shape.
 *
 * <p>This is the single most consequential string in the brute-force protection: it is the
 * rate-limit key. If it varies per request, every limit silently stops working while every
 * test that does not model the real header keeps passing.
 */
class ClientIpResolverTest {

    // ── The Azure App Service bug ─────────────────────────────────────────────

    @Test
    @DisplayName("App Service's X-Forwarded-For carries the client PORT — it must be stripped")
    void appServiceForwardedForPortIsStripped() {
        // This is literally what Azure App Service sends. Not a hypothetical.
        assertEquals("203.0.113.7", resolveWithXff("203.0.113.7:54321"));
        assertEquals("203.0.113.7", resolveWithXff("203.0.113.7:1"));
        assertEquals("203.0.113.7", resolveWithXff("203.0.113.7:65535"));
    }

    @Test
    @DisplayName("the same client on different ephemeral ports resolves to ONE rate-limit key")
    void samePortDifferentClientCollapsesToOneKey() {
        // The consequence of getting this wrong: five failed logins from one attacker would
        // land on five different keys, so no threshold would ever be reached and the
        // brute-force protection would be defeated without a single error being logged.
        String first  = resolveWithXff("203.0.113.7:54321");
        String second = resolveWithXff("203.0.113.7:54322");
        String third  = resolveWithXff("203.0.113.7:61004");

        assertEquals(first, second);
        assertEquals(second, third);
        assertEquals("203.0.113.7", first);
    }

    @Test
    @DisplayName("App Service IPv6 arrives bracketed, and also loses its port")
    void bracketedIpv6PortIsStripped() {
        assertEquals("2001:db8::1", resolveWithXff("[2001:db8::1]:54321"));
        assertEquals("2001:db8::1", resolveWithXff("[2001:db8::1]"));
    }

    // ── Addresses that must survive untouched ─────────────────────────────────

    @Test
    @DisplayName("a bare IPv6 address is left alone — every colon belongs to the address")
    void bareIpv6IsNotTruncated() {
        assertEquals("2001:db8::1", ClientIpResolver.stripPort("2001:db8::1"));
        assertEquals("::1", ClientIpResolver.stripPort("::1"));
        assertEquals("fe80::1ff:fe23:4567:890a", ClientIpResolver.stripPort("fe80::1ff:fe23:4567:890a"));
    }

    @Test
    @DisplayName("an IPv4-mapped IPv6 address is left alone")
    void ipv4MappedIpv6IsNotTruncated() {
        assertEquals("::ffff:203.0.113.7", ClientIpResolver.stripPort("::ffff:203.0.113.7"));
    }

    @Test
    @DisplayName("a plain IPv4 address passes through unchanged")
    void plainIpv4IsUnchanged() {
        assertEquals("203.0.113.7", ClientIpResolver.stripPort("203.0.113.7"));
        assertEquals("10.0.0.1", resolveWithXff("10.0.0.1"));
    }

    @Test
    @DisplayName("a malformed value is passed through rather than truncated into a plausible one")
    void malformedValuesAreNotSilentlyRewritten() {
        // Truncating this to "garbage" would invent an address that looks legitimate.
        assertEquals("garbage:stuff", ClientIpResolver.stripPort("garbage:stuff"));
        assertEquals("", ClientIpResolver.stripPort(""));
        assertNull(ClientIpResolver.stripPort(null));
    }

    // ── Header precedence and proxy trust ─────────────────────────────────────

    @Test
    @DisplayName("the left-most X-Forwarded-For entry wins — that is the originating client")
    void leftMostHopIsTheClient() {
        assertEquals("203.0.113.7",
                resolveWithXff("203.0.113.7:54321, 10.0.0.4:8080, 10.0.0.5"));
    }

    @Test
    @DisplayName("X-Real-IP is the fallback, and is also port-stripped")
    void realIpFallback() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Real-IP", "198.51.100.9:443");
        req.setRemoteAddr("10.0.0.1");
        assertEquals("198.51.100.9", ClientIpResolver.resolve(req, true));
    }

    @Test
    @DisplayName("with proxy trust off, the socket address is used and forwarded headers ignored")
    void untrustedProxyUsesSocketAddress() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "1.2.3.4:1111");   // attacker-controlled
        req.setRemoteAddr("203.0.113.50");
        assertEquals("203.0.113.50", ClientIpResolver.resolve(req, false));
    }

    @Test
    @DisplayName("a missing address resolves to the 'unknown' sentinel, never null")
    void missingAddressIsNeverNull() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr(null);
        assertEquals(ClientIpResolver.UNKNOWN_IP, ClientIpResolver.resolve(req, true));
        assertEquals(ClientIpResolver.UNKNOWN_IP, ClientIpResolver.resolve(null, true));
    }

    @Test
    @DisplayName("an over-long forged header cannot overflow the ip_address column")
    void overlongHeaderIsClamped() {
        String forged = "1.2.3.4".repeat(100);
        String resolved = resolveWithXff(forged);
        assertTrue(resolved.length() <= 100, "resolved length was " + resolved.length());
    }

    // ── Session hashing ───────────────────────────────────────────────────────

    @Test
    @DisplayName("the session hash is stable, and never the raw id")
    void sessionHashing() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setSession(new org.springframework.mock.web.MockHttpSession(null, "RAW-SESSION-VALUE"));

        String hash = ClientIpResolver.sessionHash(req);
        assertNotNull(hash);
        assertEquals(64, hash.length(), "SHA-256 hex");
        assertNotEquals("RAW-SESSION-VALUE", hash);
        assertFalse(hash.contains("RAW-SESSION"));
        assertEquals(hash, ClientIpResolver.sessionHash(req), "must be stable for correlation");
        assertEquals(12, ClientIpResolver.shortSessionHash(hash).length());
    }

    @Test
    @DisplayName("no session means no hash, and no session is created as a side effect")
    void noSessionIsSafe() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        assertNull(ClientIpResolver.sessionHash(req));
        assertNull(req.getSession(false), "reading the hash must not create a session");
    }

    private static String resolveWithXff(String headerValue) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", headerValue);
        req.setRemoteAddr("10.0.0.1");
        return ClientIpResolver.resolve(req, true);
    }
}
