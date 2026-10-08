package com.churchgeniuspro.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.churchgeniuspro.model.GeoLocationBO;
import com.churchgeniuspro.service.GeoIpService;
import com.churchgeniuspro.service.LoginProtectionService;
import com.churchgeniuspro.service.SecurityAuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * What actually gets written when someone signs in and out.
 *
 * <p>Asserts against the real logback event captured from the audit logger, so the test
 * fails if the line loses a field, and — more importantly — if it ever gains a credential.
 */
class SecurityAuditServiceTest {

    private static final String AUDIT_LOGGER = "com.churchgeniuspro.security.audit";

    private ListAppender<ILoggingEvent> captured;
    private ch.qos.logback.classic.Logger auditLogger;

    private GeoIpService           geoIp;
    private LoginProtectionService loginProtection;
    private SecurityAuditService   audit;

    @BeforeEach
    void setUp() {
        captured = new ListAppender<>();
        captured.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        captured.start();
        auditLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AUDIT_LOGGER);
        auditLogger.addAppender(captured);
        // logback-test.xml turns this channel OFF for the suite; switch it back on here,
        // and keep the events to the captured appender rather than the console.
        auditLogger.setLevel(Level.INFO);
        auditLogger.setAdditive(false);

        geoIp           = mock(GeoIpService.class);
        loginProtection = mock(LoginProtectionService.class);
        when(loginProtection.clientIp(any())).thenReturn("203.0.113.7");
        when(geoIp.resolve(anyString()))
                .thenReturn(GeoLocationBO.of("Olathe", "Kansas", "United States"));

        audit = new SecurityAuditService(geoIp, loginProtection);
    }

    @AfterEach
    void tearDown() {
        auditLogger.detachAppender(captured);
        auditLogger.setAdditive(true);
        auditLogger.setLevel(null);   // back to whatever logback-test.xml says
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a login records church, username, role, time, IP and city/state/country")
    void loginLineCarriesEveryRequestedField() {
        audit.recordLogin(request(), "john@example.com", "CHR-00001", session("Grace Chapel", "Admin"));

        String line = onlyLine();
        assertTrue(line.startsWith("LOGIN"), line);
        assertTrue(line.contains("church=\"Grace Chapel\""), line);
        assertTrue(line.contains("username=john@example.com"), line);
        assertTrue(line.contains("role=Admin"), line);
        assertTrue(line.contains("at=20"), "login date/time must be present: " + line);
        assertTrue(line.contains("ip=203.0.113.7"), line);
        assertTrue(line.contains("city=Olathe"), line);
        assertTrue(line.contains("state=Kansas"), line);
        assertTrue(line.contains("country=United States"), line);
    }

    @Test
    @DisplayName("a login is also persisted, with the same fields, to the database")
    void loginIsPersistedForDurability() {
        audit.recordLogin(request(), "john@example.com", "CHR-00001", session("Grace Chapel", "Admin"));

        ArgumentCaptor<LoginProtectionService.AuthDetails> details =
                ArgumentCaptor.forClass(LoginProtectionService.AuthDetails.class);
        verify(loginProtection).recordSuccess(any(), eq("john@example.com"), eq("CHR-00001"),
                eq("/login"), details.capture());

        LoginProtectionService.AuthDetails d = details.getValue();
        assertEquals("Grace Chapel", d.churchName());
        assertEquals("Admin",        d.role());
        assertEquals("Olathe",       d.city());
        assertEquals("Kansas",       d.region());
        assertEquals("United States", d.country());
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a logout records username, church, role, time and IP")
    void logoutLineCarriesEveryRequestedField() {
        audit.recordLogout(request(), session("Grace Chapel", "Admin"));

        String line = onlyLine();
        assertTrue(line.startsWith("LOGOUT"), line);
        assertTrue(line.contains("church=\"Grace Chapel\""), line);
        assertTrue(line.contains("username=john@example.com"), line);
        assertTrue(line.contains("role=Admin"), line);
        assertTrue(line.contains("at=20"), "logout date/time must be present: " + line);
        assertTrue(line.contains("ip=203.0.113.7"), line);

        verify(loginProtection).recordLogout(any(), eq("john@example.com"), eq("CHR-00001"),
                eq("/api/auth/logout"), any());
    }

    @Test
    @DisplayName("a logout with no session writes nothing rather than a line full of dashes")
    void logoutWithoutSessionIsSilent() {
        audit.recordLogout(request(), null);
        assertTrue(captured.list.isEmpty());
        verify(loginProtection, never()).recordLogout(any(), any(), any(), any(), any());
    }

    // ── The negative assertions that matter most ──────────────────────────────

    @Test
    @DisplayName("no credential of any kind reaches the audit line")
    void auditLineNeverContainsCredentials() {
        MockHttpSession session = session("Grace Chapel", "Admin");
        audit.recordLogin(request(session), "john@example.com", "CHR-00001", session);
        audit.recordLogout(request(session), session);

        for (ILoggingEvent event : captured.list) {
            String line = event.getFormattedMessage();
            assertFalse(line.toLowerCase().contains("password"), line);
            assertFalse(line.contains("hunter2"), line);
            assertFalse(line.toLowerCase().contains("cookie"), line);
            assertFalse(line.contains(session.getId()),
                    "the RAW session id must never be logged — it is a replayable credential: " + line);
            assertTrue(line.contains("sessionRef="),
                    "the correlation hash must survive masking, or login and logout cannot be paired: " + line);
        }
    }

    @Test
    @DisplayName("missing geolocation degrades to '-' instead of breaking the line")
    void missingGeoDegradesGracefully() {
        when(geoIp.resolve(anyString())).thenReturn(GeoLocationBO.unknown());

        audit.recordLogin(request(), "john@example.com", "CHR-00001", session("Grace Chapel", "Admin"));

        String line = onlyLine();
        assertTrue(line.contains("city=-"), line);
        assertTrue(line.contains("state=-"), line);
        assertTrue(line.contains("country=-"), line);
        assertTrue(line.contains("username=john@example.com"), "the rest of the line must survive: " + line);
    }

    @Test
    @DisplayName("an audit failure never costs the user their login, and is itself logged")
    void auditFailureDoesNotPropagate() {
        when(geoIp.resolve(anyString())).thenThrow(new IllegalStateException("geo backend exploded"));

        // The service is expected to log this at ERROR with the stack trace. Capture that
        // here rather than letting it hit the console: an 80-frame trace in the middle of a
        // green build teaches people to scroll past ERROR lines, which is exactly when they
        // stop noticing real ones. Capturing it also turns the noise into an assertion.
        ListAppender<ILoggingEvent> serviceLog = new ListAppender<>();
        serviceLog.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        serviceLog.start();
        ch.qos.logback.classic.Logger serviceLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SecurityAuditService.class);
        boolean wasAdditive = serviceLogger.isAdditive();
        serviceLogger.addAppender(serviceLog);
        serviceLogger.setAdditive(false);   // keep it off the console for this test only

        try {
            assertDoesNotThrow(() ->
                    audit.recordLogin(request(), "john@example.com", "CHR-00001", session("Grace Chapel", "Admin")));

            assertEquals(1, serviceLog.list.size(), "the failure must not be swallowed silently");
            ILoggingEvent event = serviceLog.list.get(0);
            assertEquals(Level.ERROR, event.getLevel());
            assertTrue(event.getFormattedMessage().contains("john@example.com"),
                    "the log must name the account whose audit entry was lost");
            assertNotNull(event.getThrowableProxy(), "the cause must be attached for diagnosis");
        } finally {
            serviceLogger.detachAppender(serviceLog);
            serviceLogger.setAdditive(wasAdditive);
        }
    }

    @Test
    @DisplayName("a church account with no role or church name still produces a well-formed line")
    void missingSessionAttributesUsePlaceholders() {
        MockHttpSession bare = new MockHttpSession();
        bare.setAttribute("username", "solo@example.com");

        audit.recordLogin(request(), "solo@example.com", "CHR-9", bare);

        String line = onlyLine();
        assertTrue(line.contains("church=-"), line);
        assertTrue(line.contains("role=-"), line);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String onlyLine() {
        assertEquals(1, captured.list.size(), "expected exactly one audit line");
        return captured.list.get(0).getFormattedMessage();
    }

    private static MockHttpServletRequest request() {
        return request(null);
    }

    /** Attaching the session makes ClientIpResolver.sessionHash() run for real. */
    private static MockHttpServletRequest request(MockHttpSession session) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/login");
        req.setRemoteAddr("203.0.113.7");
        req.addHeader("User-Agent", "JUnit");
        if (session != null) req.setSession(session);
        return req;
    }

    /** Distinctive session id: the default is "1", which occurs inside the timestamp and
     *  would make the "raw session id is never logged" assertion pass for the wrong reason. */
    private static final String RAW_SESSION_ID = "RAWSESSIONIDMUSTNOTAPPEAR";

    private static MockHttpSession session(String churchName, String role) {
        MockHttpSession session = new MockHttpSession(null, RAW_SESSION_ID);
        session.setAttribute("username",   "john@example.com");
        session.setAttribute("clientId",   "CHR-00001");
        session.setAttribute("churchName", churchName);
        session.setAttribute("role",       role);
        return session;
    }
}
