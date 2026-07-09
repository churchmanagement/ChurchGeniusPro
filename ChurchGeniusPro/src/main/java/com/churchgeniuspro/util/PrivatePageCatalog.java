package com.churchgeniuspro.util;

import java.util.List;

/**
 * The catalog of page groups that can be marked Private (network-restricted).
 *
 * <p>Each entry maps a stable {@code key} (stored on {@code PrivatePageRule}) to a
 * friendly label and the request-path prefixes that belong to that group. To make a
 * new area gateable later, add an entry here and add its prefixes to the filter's
 * URL patterns in {@code FilterConfig} — everything else (config UI, logging, audit)
 * is generic.
 */
public final class PrivatePageCatalog {

    private PrivatePageCatalog() {}

    /**
     * @param preAuth when true the page is public/pre-login (no session yet), so the owning
     *                church must be resolved from the request itself (a church-scoped login
     *                link or the remember-me cookie) rather than from the session. The rule
     *                is still strictly per-church and never affects other churches' logins.
     */
    public record Page(String key, String label, List<String> pathPrefixes, boolean preAuth) {}

    public static final List<Page> PAGES = List.of(
        new Page("kids", "Kids Ministry (Children, Register, Check-In/Out)",
                 List.of("/kidsMinistry", "/api/kids-ministry"), false),
        new Page("eventcheckin", "Event Check-In (attendee check-in & details)",
                 List.of("/event-checkin-admin", "/event-details"), false),
        // Pre-login page — per-church, gated only when the church can be identified
        // from the request (the encrypted church link ?c=<token> or remember-me cookie).
        // The main login page (/) is intentionally NOT gateable: only the temporary-access
        // entry point is network-restricted. Gating for this page is driven by the master
        // "Enable Private Page Access" setting alone (see PrivateAccessService).
        new Page("templogin", "Temporary Access Login (private entry point)",
                 List.of("/private-access", "/tempLogin"), true)
    );

    /** Resolve the page-group key for a request path, or {@code null} if none match. */
    public static String keyForPath(String path) {
        if (path == null) return null;
        for (Page p : PAGES) {
            for (String prefix : p.pathPrefixes()) {
                // Exact path, a sub-path under it, or the ".html" forward target — but NOT
                // a different route that merely shares the prefix (e.g. /kidsMinistryPage).
                if (path.equals(prefix) || path.startsWith(prefix + "/") || path.equals(prefix + ".html")) {
                    return p.key();
                }
            }
        }
        return null;
    }

    public static Page byKey(String key) {
        for (Page p : PAGES) if (p.key().equals(key)) return p;
        return null;
    }
}
