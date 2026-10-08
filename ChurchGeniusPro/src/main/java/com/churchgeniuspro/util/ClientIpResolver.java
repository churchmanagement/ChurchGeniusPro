package com.churchgeniuspro.util;

import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Resolves the client address and a coarse device fingerprint for security logging
 * and rate limiting.
 *
 * <p>The forwarded-header behaviour is a deployment decision, not a code decision —
 * see {@code security.login-protection.trust-forwarded-headers} in
 * {@link com.churchgeniuspro.config.LoginProtectionProperties}. Callers pass the flag
 * in rather than this class reading configuration, so it stays a pure utility that is
 * trivial to unit test.
 */
public final class ClientIpResolver {

    private ClientIpResolver() {}

    /** Value stored when no address could be determined, so keys are never null. */
    public static final String UNKNOWN_IP = "unknown";

    private static final int MAX_IP_LENGTH = 100;

    /**
     * Returns the client IP address.
     *
     * @param req                   the current request
     * @param trustForwardedHeaders when {@code true}, prefer the first hop in
     *                              {@code X-Forwarded-For}, then {@code X-Real-IP};
     *                              when {@code false}, always use the socket address
     */
    public static String resolve(HttpServletRequest req, boolean trustForwardedHeaders) {
        if (req == null) return UNKNOWN_IP;

        if (trustForwardedHeaders) {
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                // Left-most entry is the originating client; the rest are proxies.
                return clamp(stripPort(xff.split(",")[0].trim()));
            }
            String realIp = req.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return clamp(stripPort(realIp.trim()));
            }
        }

        String remote = req.getRemoteAddr();
        return (remote == null || remote.isBlank()) ? UNKNOWN_IP : clamp(stripPort(remote.trim()));
    }

    /**
     * Removes a trailing {@code :port} from an address.
     *
     * <p><b>This is not optional on Azure App Service.</b> Its front end writes
     * {@code X-Forwarded-For: 203.0.113.7:54321} — the client IP <em>with the ephemeral
     * source port attached</em>, which is unusual and easy to miss. Keeping the port would
     * be quietly catastrophic for anything that uses the address as a key: every request
     * from one client carries a different port, so it would look like a different client
     * every time. The username+IP and IP rate-limit scopes would never accumulate a second
     * failure, the brute-force protection would be defeated with no error anywhere, and
     * geolocation would fail to parse every address.
     *
     * <p>Handles the four shapes that actually arrive:
     * <ul>
     *   <li>{@code 203.0.113.7:54321} → {@code 203.0.113.7} (App Service IPv4)</li>
     *   <li>{@code [2001:db8::1]:54321} → {@code 2001:db8::1} (App Service IPv6)</li>
     *   <li>{@code 2001:db8::1} → unchanged (bare IPv6 — every colon is part of the address)</li>
     *   <li>{@code ::ffff:203.0.113.7} → unchanged (IPv4-mapped IPv6)</li>
     * </ul>
     */
    static String stripPort(String address) {
        if (address == null || address.isEmpty()) return address;

        // [2001:db8::1]:54321  or  [2001:db8::1]
        if (address.charAt(0) == '[') {
            int close = address.indexOf(']');
            return close > 1 ? address.substring(1, close) : address;
        }

        int firstColon = address.indexOf(':');
        if (firstColon < 0) return address;                      // plain IPv4, nothing to do

        // More than one colon means IPv6 in some form. A bare IPv6 address is never
        // followed by an unbracketed port (RFC 3986), so the whole string is the address.
        if (address.indexOf(':', firstColon + 1) >= 0) return address;

        // Exactly one colon: host:port. Only treat it as such when the left side is
        // actually an IPv4-looking address, so a malformed header is passed through
        // rather than silently truncated to something that looks valid.
        String head = address.substring(0, firstColon);
        return head.indexOf('.') > 0 ? head : address;
    }

    /**
     * SHA-256 (hex) of the User-Agent header — a stable device signal for incident
     * review that does not store the raw header. Returns {@code null} when absent.
     */
    public static String deviceHash(HttpServletRequest req) {
        if (req == null) return null;
        String ua = req.getHeader("User-Agent");
        if (ua == null || ua.isBlank()) return null;
        return sha256Hex(ua);
    }

    /**
     * SHA-256 of the current session id, or {@code null} when there is no session.
     * Never creates one.
     *
     * <p>Returns a hash rather than the id itself, and there is deliberately no accessor
     * for the raw value: a session id is a bearer credential, so anyone who reads one out
     * of a log file or an audit table can replay it and impersonate that user until the
     * session expires. The hash is still perfectly good for correlating a login with its
     * matching logout, which is the only thing the audit trail needs it for.
     */
    public static String sessionHash(HttpServletRequest req) {
        if (req == null) return null;
        var session = req.getSession(false);
        return session == null ? null : sha256Hex(session.getId());
    }

    /**
     * Short, log-friendly form of a session hash — the first 12 characters, enough to
     * match a login to a logout by eye without filling the line.
     */
    public static String shortSessionHash(String fullHash) {
        if (fullHash == null || fullHash.isBlank()) return null;
        return fullHash.length() <= 12 ? fullHash : fullHash.substring(0, 12);
    }

    /** SHA-256 of {@code value}, hex-encoded. */
    public static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the platform; unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Guards against an over-long forged header overflowing the {@code ip_address}
     * column (a client fully controls the header value when proxies are trusted).
     */
    private static String clamp(String value) {
        if (value.isEmpty()) return UNKNOWN_IP;
        return value.length() <= MAX_IP_LENGTH ? value : value.substring(0, MAX_IP_LENGTH);
    }
}
