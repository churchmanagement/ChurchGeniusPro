package com.churchgeniuspro.service;

import com.churchgeniuspro.model.GeoLocationBO;
import com.churchgeniuspro.util.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Writes the login / logout audit trail.
 *
 * <p>Every event is recorded in <b>two</b> places, on purpose:
 * <ol>
 *   <li><b>{@code security-YYYY-MM-DD.log}</b> — a flat file on the persistent Azure Files
 *       share, for operators, incident review and shipping to a SIEM.</li>
 *   <li><b>{@code login_attempt_log} in PostgreSQL</b> — the durable store of record.
 *       This is the part that matters: a database row survives an App Service restart, a
 *       redeployment, a scale-out, an instance being moved to new hardware, and someone
 *       clearing out the file share. It is also the only form that can answer "show this
 *       church every sign-in last month" without grepping thirty files.</li>
 * </ol>
 *
 * <h2>What is deliberately absent</h2>
 * No password, no password length, no authentication token, no raw session id, no cookie.
 * The session is identified by a truncated SHA-256 hash ({@code sessionRef}), which
 * correlates a login with its logout but is worthless to anyone who reads it — reversing it
 * would mean brute-forcing a high-entropy random session id. It is named {@code sessionRef}
 * rather than {@code session} on purpose: {@code session=} is a redaction trigger in
 * {@code SensitiveDataMasker}, so the useful hash would otherwise be masked along with the
 * real session cookies it is there to protect. {@code MaskingPatternLayout} scrubs the
 * rendered line as a second line of defence.
 *
 * <h2>Line format</h2>
 * <pre>
 * 2026-08-17 09:14:22.331 | LOGIN  | church="Grace Chapel" | username=john@example.com | role=Admin
 *                           | at=2026-08-17T09:14:22 | ip=203.0.113.7 | city=Olathe | state=Kansas
 *                           | country=United States | sessionRef=9f2a1c4b8e01
 * </pre>
 * Flat {@code key=value} pairs on one line: greppable with no tooling, and parseable by a
 * log shipper without a custom decoder.
 */
@Service
public class SecurityAuditService {

    /**
     * Routed by {@code logback-spring.xml} to {@code security-YYYY-MM-DD.log} with
     * {@code additivity=false}, so these events do not also fill the application log.
     */
    private static final Logger AUDIT = LoggerFactory.getLogger("com.churchgeniuspro.security.audit");

    private static final Logger LOG = LoggerFactory.getLogger(SecurityAuditService.class);

    private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** Written when a value is unavailable, so every line has the same shape. */
    private static final String NONE = "-";

    private final GeoIpService           geoIpService;
    private final LoginProtectionService loginProtection;

    public SecurityAuditService(GeoIpService geoIpService, LoginProtectionService loginProtection) {
        this.geoIpService    = geoIpService;
        this.loginProtection = loginProtection;
    }

    /**
     * Records a successful sign-in: the audit line, and the database row that also resets
     * this account's brute-force counters.
     *
     * <p>Call this <em>after</em> the session has been built, so church name and role can be
     * read from it.
     *
     * @param session the freshly created session — supplies churchName and role
     */
    public void recordLogin(HttpServletRequest request, String username, String clientId, HttpSession session) {
        try {
            String ip          = loginProtection.clientIp(request);
            GeoLocationBO geo  = geoIpService.resolve(ip);
            String churchName  = attr(session, "churchName");
            String role        = attr(session, "role");
            String fullHash    = ClientIpResolver.sessionHash(request);
            String shortHash   = ClientIpResolver.shortSessionHash(fullHash);

            AUDIT.info("LOGIN  | church={} | username={} | role={} | at={} | ip={} | city={} | state={} | country={} | sessionRef={}",
                    quote(churchName), value(username), value(role), LocalDateTime.now().format(EVENT_TIME),
                    value(ip), value(geo.city()), value(geo.region()), value(geo.country()), value(shortHash));

            loginProtection.recordSuccess(request, username, clientId, "/login",
                    new LoginProtectionService.AuthDetails(churchName, role,
                            geo.cityOrNull(), geo.regionOrNull(), geo.countryOrNull(), fullHash));

        } catch (Exception ex) {
            // An audit failure must never cost the user their sign-in. Losing one record is
            // bad; refusing a legitimate login because geolocation hiccuped is worse.
            LOG.error("Failed to record LOGIN audit entry for username='{}'", username, ex);
        }
    }

    /**
     * Records a sign-out. Reads its values from the session <em>before</em> the caller
     * invalidates it.
     */
    public void recordLogout(HttpServletRequest request, HttpSession session) {
        if (session == null) return;
        try {
            String username   = attr(session, "username");
            String clientId   = attr(session, "clientId");
            String churchName = attr(session, "churchName");
            String role       = attr(session, "role");
            String ip         = loginProtection.clientIp(request);
            GeoLocationBO geo = geoIpService.resolve(ip);
            String fullHash   = ClientIpResolver.sessionHash(request);

            AUDIT.info("LOGOUT | church={} | username={} | role={} | at={} | ip={} | sessionRef={}",
                    quote(churchName), value(username), value(role),
                    LocalDateTime.now().format(EVENT_TIME), value(ip),
                    value(ClientIpResolver.shortSessionHash(fullHash)));

            loginProtection.recordLogout(request, username, clientId, "/api/auth/logout",
                    new LoginProtectionService.AuthDetails(churchName, role,
                            geo.cityOrNull(), geo.regionOrNull(), geo.countryOrNull(), fullHash));

        } catch (Exception ex) {
            LOG.error("Failed to record LOGOUT audit entry", ex);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Session attribute as a string, or {@code null}. Never creates a session. */
    private static String attr(HttpSession session, String name) {
        if (session == null) return null;
        Object v = session.getAttribute(name);
        return v == null ? null : String.valueOf(v);
    }

    /** Placeholder for a missing value, so every line keeps the same column shape. */
    private static String value(String v) {
        return (v == null || v.isBlank()) ? NONE : v;
    }

    /** Church names contain spaces, so they are quoted to stay one field when parsed. */
    private static String quote(String v) {
        return (v == null || v.isBlank()) ? NONE : "\"" + v.replace("\"", "'") + "\"";
    }
}
