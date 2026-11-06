package com.churchgeniuspro.ai.consent;

import com.churchgeniuspro.controller.LoginController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * App Store readiness D6 — is this request from the iOS / Android app?
 *
 * <p>Three independent signals; ANY one makes it an app request, so losing one never opens a
 * consent bypass by accident:
 * <ol>
 *   <li>the user-agent marker {@code ChurchGeniusProApp/} compiled into the app
 *       ({@code appendUserAgent} in the mobile repo's capacitor.config.ts — builds 1 and 2
 *       send it; {@code scripts/check-config.mjs} fails the build if it is removed);</li>
 *   <li>the header {@code X-CGP-Client: app}, added by native-bridge.js, which only runs
 *       inside the app;</li>
 *   <li>a STICKY session flag: once any request of a session carried 1 or 2 (sign-in, device
 *       sign-in, an AI call, the consent endpoints), every later request of that session is
 *       treated as the app until sign-out — even without the marker.</li>
 * </ol>
 *
 * <p><b>Not proof.</b> A user-agent and a header are set by the client. This detects the
 * genuine app reliably and stops ACCIDENTAL bypasses (a build that lost the marker, a request
 * path that drops it mid-session); it does NOT stop deliberate spoofing — a client that strips
 * all signals from the first request gets the website's behaviour, which is unchanged by
 * design. Cryptographic proof (Apple App Attest / Google Play Integrity) is a possible future
 * enhancement.
 */
public final class AppShellDetector {

    private AppShellDetector() {}

    public static final String HEADER = "X-CGP-Client";
    public static final String PLATFORM_HEADER = "X-CGP-Platform";
    public static final String SESSION_ATTR = "cgpAppShell";

    /** The request itself carries signal 1 or 2. */
    public static boolean signals(HttpServletRequest request) {
        if (request == null) return false;
        String ua = request.getHeader("User-Agent");
        if (ua != null && ua.contains(LoginController.APP_UA_MARKER)) return true;
        String h = request.getHeader(HEADER);
        return h != null && "app".equalsIgnoreCase(h.trim());
    }

    /** IOS · ANDROID · APP (unknown platform). */
    public static String platform(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        String p = request.getHeader(PLATFORM_HEADER);
        String s = ((ua == null ? "" : ua) + " " + (p == null ? "" : p)).toLowerCase(java.util.Locale.ROOT);
        if (s.contains("(ios)") || s.contains(" ios")) return "IOS";
        if (s.contains("(android)") || s.contains(" android")) return "ANDROID";
        return "APP";
    }

    /** Marks the session as an app session when this request carries a signal. Never clears it. */
    public static void markSession(HttpServletRequest request, HttpSession session) {
        if (session == null || !signals(request)) return;
        try {
            if (session.getAttribute(SESSION_ATTR) == null) session.setAttribute(SESSION_ATTR, platform(request));
        } catch (IllegalStateException ignored) { /* invalidated session */ }
    }

    /** Signal 1, 2 or 3. Marks the session (sticky) as a side effect. */
    public static boolean isApp(HttpServletRequest request) {
        if (request == null) return false;
        HttpSession s = request.getSession(false);
        if (signals(request)) { markSession(request, s); return true; }
        try {
            return s != null && s.getAttribute(SESSION_ATTR) != null;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** IOS / ANDROID for the consent row: this request's platform, else the session's. */
    public static String source(HttpServletRequest request) {
        String p = platform(request);
        if (!"APP".equals(p)) return p;
        HttpSession s = request.getSession(false);
        Object v = s == null ? null : s.getAttribute(SESSION_ATTR);
        return v == null ? "APP" : String.valueOf(v);
    }
}
