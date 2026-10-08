package com.churchgeniuspro.webfilter;

import jakarta.servlet.http.HttpServletRequest;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The request path <em>as the dispatcher will see it</em>, for filters that make
 * decisions by path.
 *
 * <p>{@link HttpServletRequest#getRequestURI()} is the request line verbatim: not
 * decoded, not normalised. Spring MVC and the static-resource handler, on the other
 * hand, match on the <em>decoded</em> segments. So a filter that compares the raw URI
 * against {@code "/api/guess-it"} lets {@code /api/guess%2Dit/…} and {@code /%61pi/…}
 * straight through, while the handler behind it happily serves them. Every path-gated
 * filter must therefore compare the same thing the handler will match on — and this is
 * the one place that computes it.
 *
 * <p>What it does, in order:
 * <ol>
 *   <li>strips the context path;</li>
 *   <li>drops {@code ;matrix=params} from every segment (as Spring's
 *       {@code UrlPathHelper} does — {@code /api;x=y/guess-it} maps to {@code /api/guess-it});</li>
 *   <li>percent-decodes as UTF-8;</li>
 *   <li>collapses repeated slashes and resolves {@code .} and {@code ..} segments,
 *       never above the root.</li>
 * </ol>
 *
 * <p>Fails <em>closed</em> in the only way a path helper can: a URI that cannot be
 * decoded is returned as-is, which no catalog prefix matches — and the gates built on
 * top of this are pass-through for unknown paths only in the direction the handler
 * cannot exploit (an undecodable URI is one Tomcat will refuse anyway).
 */
public final class RequestPaths {

    private RequestPaths() {}

    /** Decoded, normalised path within the application — never null, always starts with "/". */
    public static String path(HttpServletRequest req) {
        String uri = req.getRequestURI();
        String ctx = req.getContextPath();
        if (uri == null) return "/";
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) uri = uri.substring(ctx.length());
        return normalise(uri);
    }

    /** Same normalisation applied to a raw path string (exposed for tests and catalogs). */
    public static String normalise(String raw) {
        if (raw == null || raw.isEmpty()) return "/";

        // 1. matrix parameters: "/a;x=1/b;y" → "/a/b"
        StringBuilder stripped = new StringBuilder(raw.length());
        for (String seg : raw.split("/", -1)) {
            int semi = seg.indexOf(';');
            stripped.append(semi < 0 ? seg : seg.substring(0, semi)).append('/');
        }
        if (stripped.length() > 0) stripped.setLength(stripped.length() - 1);
        String s = stripped.toString();

        // 2. percent-decode. URLDecoder turns '+' into ' ', which a URI path never
        //    means; protect it so "/a+b" stays "/a+b".
        try {
            s = URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return raw;              // undecodable → leave raw; nothing will match it
        }

        // 3. collapse "//" and resolve "." / ".." without escaping the root
        Deque<String> out = new ArrayDeque<>();
        for (String seg : s.split("/", -1)) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) { if (!out.isEmpty()) out.removeLast(); continue; }
            out.addLast(seg);
        }
        StringBuilder sb = new StringBuilder("/");
        boolean first = true;
        for (String seg : out) {
            if (!first) sb.append('/');
            sb.append(seg);
            first = false;
        }
        // keep a trailing slash if the request had one (after decoding), so
        // "/api/" and "/api" stay distinguishable for callers that care
        if (s.endsWith("/") && sb.length() > 1) sb.append('/');
        return sb.toString();
    }
}
