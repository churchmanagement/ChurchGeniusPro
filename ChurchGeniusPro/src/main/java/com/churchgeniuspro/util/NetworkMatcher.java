package com.churchgeniuspro.util;

import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * IP-address matching for the Private Page Access feature.
 *
 * <p>Supports IPv4 exact addresses ({@code 203.0.113.7}) and CIDR ranges
 * ({@code 10.0.0.0/24}). IPv6 values are compared by normalized exact string
 * (loopback {@code ::1} is folded to IPv4 loopback). Anything unparseable is
 * ignored so a bad config line can never accidentally grant access.
 */
public final class NetworkMatcher {

    private NetworkMatcher() {}

    /** Extracts the client IP, honoring X-Forwarded-For when {@code trustProxy} (proxy/CDN setups). */
    public static String clientIp(HttpServletRequest request, boolean trustProxy) {
        if (trustProxy) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                // First hop is the originating client.
                String first = xff.split(",")[0].trim();
                if (!first.isBlank()) return normalize(first);
            }
            String real = request.getHeader("X-Real-IP");
            if (real != null && !real.isBlank()) return normalize(real.trim());
        }
        return normalize(request.getRemoteAddr());
    }

    /** Fold IPv6 loopback to IPv4 loopback and strip a zone/port suffix where obvious. */
    public static String normalize(String ip) {
        if (ip == null) return "";
        ip = ip.trim();
        if ("::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) return "127.0.0.1";
        // IPv4-mapped IPv6 (::ffff:1.2.3.4)
        int idx = ip.lastIndexOf(':');
        if (ip.startsWith("::ffff:") && ip.indexOf('.') > 0 && idx >= 0) {
            return ip.substring(idx + 1);
        }
        return ip;
    }

    /** True for a loopback address — used as a never-lock-out safeguard on public pages. */
    public static boolean isLoopback(String ip) {
        String n = normalize(ip);
        return n.equals("127.0.0.1") || n.startsWith("127.") || n.equals("0:0:0:0:0:0:0:1");
    }

    /** Parse a multi-line / comma-separated list of IPs and CIDRs into entries. */
    public static List<String> parseRanges(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String part : raw.split("[,\\n\\r]+")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** {@code true} when {@code ip} matches any address/CIDR in {@code ranges}. */
    public static boolean matchesAny(String ip, List<String> ranges) {
        String nip = normalize(ip);
        long ipv4 = ipv4ToLong(nip);
        for (String range : ranges) {
            if (range == null || range.isBlank()) continue;
            range = range.trim();
            if (range.contains("/")) {
                if (ipv4 >= 0 && cidrMatch(ipv4, range)) return true;
            } else if (range.equalsIgnoreCase(nip)) {
                return true;   // exact match (covers IPv4 and IPv6 literals)
            } else if (ipv4 >= 0) {
                long other = ipv4ToLong(range);
                if (other >= 0 && other == ipv4) return true;
            }
        }
        return false;
    }

    /** Returns the 32-bit value of a dotted-quad IPv4, or -1 if not valid IPv4. */
    static long ipv4ToLong(String ip) {
        if (ip == null) return -1;
        String[] o = ip.split("\\.");
        if (o.length != 4) return -1;
        long v = 0;
        for (String s : o) {
            try {
                int n = Integer.parseInt(s);
                if (n < 0 || n > 255) return -1;
                v = (v << 8) | n;
            } catch (NumberFormatException e) { return -1; }
        }
        return v;
    }

    /** {@code true} when an IPv4 (as long) falls inside an IPv4 CIDR like {@code 10.0.0.0/24}. */
    static boolean cidrMatch(long ipv4, String cidr) {
        int slash = cidr.indexOf('/');
        String base = cidr.substring(0, slash).trim();
        int bits;
        try { bits = Integer.parseInt(cidr.substring(slash + 1).trim()); }
        catch (NumberFormatException e) { return false; }
        if (bits < 0 || bits > 32) return false;
        long baseVal = ipv4ToLong(base);
        if (baseVal < 0) return false;
        if (bits == 0) return true;
        long mask = (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
        return (ipv4 & mask) == (baseVal & mask);
    }
}
