package com.churchgeniuspro.webfilter;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.LoginRepository;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

/**
 * Servlet filter that protects {@code /api/**} endpoints by requiring a valid
 * server-side HTTP session.
 *
 * <p>The following paths are explicitly whitelisted and bypass the session
 * check so they remain accessible without authentication:
 * <ul>
 *   <li>{@code POST /login}                  — authenticate and create a session</li>
 *   <li>{@code GET  /api/session}             — check / retrieve session data</li>
 *   <li>{@code POST /api/logout}              — invalidate session (safe to call when unauthenticated)</li>
 *   <li>{@code /api/churchregistration/**}   — public church self-registration</li>
 *   <li>{@code /api/signup/**}               — public user-signup link flow</li>
 *   <li>{@code /api/serviceadmin/**}          — service-admin endpoints (own auth)</li>
 *   <li>{@code POST /api/forgot-password}        — request password-reset email (unauthenticated)</li>
 *   <li>{@code POST /api/reset-password}         — apply new password via token (unauthenticated)</li>
 *   <li>{@code GET  /api/reset-password/**}      — validate reset token (unauthenticated)</li>
 *   <li>{@code /api/event-register/**}           — public event registration page (no login required)</li>
 *   <li>{@code /api/mid-reg-meet/public/**}     — Midwest Region Meet RSVP public endpoints (no login required)</li>
 *   <li>{@code /api/event-calendar/public-*}    — public event calendar data (public-church-info, public-logo, public-events, public-ics — no login required)</li>
 * </ul>
 *
 * <p>In addition to session presence, this filter re-validates the account status on
 * every request:
 * <ul>
 *   <li>Staff accounts ({@code appUserId} present in session): checks {@code app_user.enabled}
 *       and {@code app_user.delete_flag} — a disabled or deleted staff user is immediately
 *       blocked even if their session is still alive.</li>
 *   <li>Signup-based accounts (church / member): checks {@code signup.active} and
 *       {@code signup.deleted} via the {@code clientId} stored in the session.</li>
 * </ul>
 *
 * <p>All other {@code /api/**} requests that arrive without a valid session
 * receive HTTP 401 with a JSON error body so the frontend can redirect to the
 * login page.
 */
public class AuthFilter implements Filter {

    private final AppUserRepository appUserRepository;
    private final LoginRepository   loginRepository;

    public AuthFilter(AppUserRepository appUserRepository, LoginRepository loginRepository) {
        this.appUserRepository = appUserRepository;
        this.loginRepository   = loginRepository;
    }

    // Paths that do not require a logged-in session
    private static final String[] PUBLIC_PREFIXES = {
            "/api/session",
            "/api/logout",
            "/api/churchregistration",
            "/api/signup",
            "/api/serviceadmin",
            "/api/service",
            "/api/forgot-password",
            "/api/reset-password",
            "/api/membership-form",          // public membership application form (no login required)
            "/api/member-signup",            // public member account signup flow (no login required)
            "/api/public",                   // public donation page endpoints (no login required)
            "/api/auth",                     // auth endpoints: check-remember, resolve-client-id (no session needed)
            "/api/push/vapid-public-key",    // VAPID key is public (needed before session is established)
            "/webhook/sms",                  // Twilio inbound SMS webhook (no session)
            "/api/plaid/webhook",            // Plaid webhook (no session; verified by JWT signature). Only the webhook is public — other /api/plaid/* endpoints remain session-protected.
            "/api/event-register",           // public event registration page (no login required)
            "/api/mid-reg-meet/public",      // public Midwest Region Meet RSVP endpoints (no login required)
            "/api/sms-opt-in",               // public SMS opt-in form (no login required)
            "/api/event-calendar/public-",  // public event calendar endpoints (public-church-info, public-logo, public-events, public-ics — no login required)
            "/api/guess-it/public",         // GuessIt big-screen display — no session required (org identified by encrypted cid param)
            "/api/temp-access/login",       // temporary-access badge + code login (establishes the session — no prior session)
            "/api/ntag-login",              // NTAG (NFC) 3-factor temporary login (start/pin/otp/finalize — no prior session)
            "/api/policy-acceptance",       // legal policy acceptance recording (cookie banner / pre-session accept)
            "/api/web"                      // public marketing website endpoints (/web/* pages: contact form — no login required)
    };

    @Override
    public void doFilter(ServletRequest request,
                         ServletResponse response,
                         FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  req = (HttpServletRequest)  request;
        HttpServletResponse res = (HttpServletResponse) response;

        String uri = req.getRequestURI();

        // ── Whitelist check ────────────────────────────────────────────────
        for (String prefix : PUBLIC_PREFIXES) {
            if (uri.equals(prefix) || uri.startsWith(prefix + "/")
                    || uri.startsWith(prefix + "?") || uri.startsWith(prefix)) {
                chain.doFilter(request, response);
                return;
            }
        }

        // ── Session presence check ────────────────────────────────────────
        HttpSession session = req.getSession(false);   // never create a new session here

        if (session == null || session.getAttribute("clientId") == null) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write(
                    "{\"status\":\"error\",\"message\":\"Session expired. Please log in.\"}");
            return;
        }

        // ── Temporary-access sessions ─────────────────────────────────────
        // Temp passes have no app_user row and may have no signup row for their
        // org clientId, so the account re-validation below does not apply. Their
        // validity (time window / revocation) is enforced by TempAccessFilter.
        if (session.getAttribute("tempAccessId") != null) {
            chain.doFilter(request, response);
            return;
        }

        // ── Per-request account status re-validation ──────────────────────
        // Catches the case where an admin disables/deletes an account that
        // already has an active session, ensuring the change takes effect
        // immediately without waiting for the session to naturally expire.

        Object appUserIdAttr = session.getAttribute("appUserId");
        String clientId      = String.valueOf(session.getAttribute("clientId"));

        if (appUserIdAttr instanceof Integer appUserId) {
            // ── Staff account: validate against app_user table ────────────
            AppUser appUser = appUserRepository.findById(appUserId).orElse(null);
            if (appUser == null || appUser.isDeleteFlag() || !appUser.isEnabled()) {
                session.invalidate();
                res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write(
                        "{\"status\":\"error\",\"message\":\"Your account has been disabled or removed. Please contact your administrator.\"}");
                return;
            }
        } else if (clientId != null && !clientId.isBlank() && !"null".equals(clientId)) {
            // ── Church / Member account: validate against signup table ─────
            // Skip the re-validation for non-staff accounts that have no app_user row.
            // We only check signup.active and signup.deleted here.
            SignUp signup = loginRepository.findByClientId(clientId).orElse(null);
            if (signup == null
                    || Boolean.TRUE.equals(signup.getDeleted())
                    || Boolean.FALSE.equals(signup.getActive())) {
                session.invalidate();
                res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write(
                        "{\"status\":\"error\",\"message\":\"Your account has been disabled or removed. Please contact your administrator.\"}");
                return;
            }
        }

        chain.doFilter(request, response);
    }
}
