package com.churchgeniuspro.security;

import com.churchgeniuspro.config.SessionConfigAccess;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.web.http.HeaderHttpSessionIdResolver;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.servlet.http.HttpServlet;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Root cause of "switched SuperAdmin → Member and could not switch back": Spring Session's
 * default JDBC delete keeps sessions whose timeout is -1, and NoAutoLogoutFilter gives every
 * "Don't log out automatically" session (the staff default) exactly that timeout. So
 * {@code invalidate()} deleted nothing, {@code getSession(true)} reloaded the SuperAdmin
 * session, and the Member login was written on top of it — keeping the SuperAdmin's
 * {@code appUserId}, which made the switcher show SuperAdmin as the current account.
 * The same no-op invalidate left Logout'ed sessions usable by their old cookie.
 *
 * <p>Runs Spring Session's real {@link SessionRepositoryFilter} and
 * {@link JdbcIndexedSessionRepository} against an in-memory database.
 */
@DisplayName("Session invalidation — Switch Account / Sign-in / Logout start a clean session")
class AccountSwitchTest {

    JdbcTemplate jdbc;
    TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:sess-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("org/springframework/session/jdbc/schema-h2.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    static Object attr(JdbcIndexedSessionRepository r, String id, String name) {
        return ((org.springframework.session.Session) r.findById(id)).getAttribute(name);
    }

    JdbcIndexedSessionRepository repo(boolean withAppFix) {
        JdbcIndexedSessionRepository r = new JdbcIndexedSessionRepository(jdbc, tx);
        if (withAppFix) r.setDeleteSessionQuery(SessionConfigAccess.deleteSessionQuery());
        return r;
    }

    /** Runs one request through the real Spring Session filter; returns the session id it ended with. */
    static String request(SessionRepositoryFilter<?> filter, String sessionId, Consumer<HttpServletRequest> body) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (sessionId != null) req.addHeader("X-Auth-Token", sessionId);
        MockHttpServletResponse res = new MockHttpServletResponse();
        new MockFilterChain(new HttpServlet() {}, filter, (rq, rs, ch) -> body.accept((HttpServletRequest) rq)).doFilter(req, res);
        return res.getHeader("X-Auth-Token") != null ? res.getHeader("X-Auth-Token") : sessionId;
    }

    static SessionRepositoryFilter<?> filter(JdbcIndexedSessionRepository r) {
        SessionRepositoryFilter<?> f = new SessionRepositoryFilter<>(r);
        f.setHttpSessionIdResolver(HeaderHttpSessionIdResolver.xAuthToken());
        return f;
    }

    /** Signed in as the SuperAdmin, then any /api call → NoAutoLogoutFilter sets "never expire". */
    static String superAdminSession(SessionRepositoryFilter<?> f) throws Exception {
        String id = request(f, null, rq -> {
            HttpSession s = rq.getSession(true);
            s.setAttribute("role", "SuperAdmin"); s.setAttribute("appUserId", 1); s.setAttribute("privileges", "{}");
        });
        return request(f, id, rq -> rq.getSession(false).setMaxInactiveInterval(-1));
    }

    /** What LoginController.switchAccount / login do: invalidate, getSession(true), build the new account. */
    static final String[] seen = new String[3];
    static Consumer<HttpServletRequest> switchToMember() {
        return rq -> {
            HttpSession old = rq.getSession(false);
            seen[0] = old.getId();
            old.invalidate();
            HttpSession s = rq.getSession(true);
            s.setAttribute("role", "Member"); s.setAttribute("clientId", "MBR-x"); s.setAttribute("memberId", 71);
            seen[1] = s.getId();
            seen[2] = String.valueOf(s.getAttribute("appUserId"));
        };
    }

    @Test @DisplayName("BEFORE (Spring default delete): a never-expiring SuperAdmin session survives the switch — Member keeps appUserId")
    void defaultDeleteReproducesTheBug() throws Exception {
        SessionRepositoryFilter<?> f = filter(repo(false));
        String id = superAdminSession(f);
        request(f, id, switchToMember());
        assertThat(seen[1]).as("same session reloaded").isEqualTo(seen[0]);
        assertThat(seen[2]).as("SuperAdmin's staff id left in the Member session").isEqualTo("1");
    }

    @Test @DisplayName("AFTER (app's delete query): the switch gets a new session with only the Member's attributes; the old one is gone")
    void fixedDeleteStartsCleanSession() throws Exception {
        JdbcIndexedSessionRepository r = repo(true);
        SessionRepositoryFilter<?> f = filter(r);
        String id = superAdminSession(f);
        String newId = request(f, id, switchToMember());
        assertThat(seen[1]).isNotEqualTo(seen[0]);
        assertThat(seen[2]).as("no staff identity in the Member session").isEqualTo("null");
        assertThat(newId).isNotEqualTo(id);
        assertThat(r.findById(id)).as("old SuperAdmin session deleted").isNull();
        assertThat(attr(r, newId, "appUserId")).isNull();
        assertThat(attr(r, newId, "role")).isEqualTo("Member");
    }

    @Test @DisplayName("Logout: the old cookie no longer works afterwards (before the fix it stayed signed in)")
    void logoutRemovesNeverExpiringSession() throws Exception {
        for (boolean fixed : new boolean[] { false, true }) {
            JdbcIndexedSessionRepository r = repo(fixed);
            SessionRepositoryFilter<?> f = filter(r);
            String id = superAdminSession(f);
            request(f, id, rq -> rq.getSession(false).invalidate());
            if (fixed) assertThat(r.findById(id)).as("after fix").isNull();
            else       assertThat(r.findById(id)).as("before fix: still usable").isNotNull();
        }
    }

    @Test @DisplayName("ordinary sessions (auto-logout on) behave exactly as before")
    void normalSessionsUnchanged() throws Exception {
        JdbcIndexedSessionRepository r = repo(true);
        SessionRepositoryFilter<?> f = filter(r);
        String id = request(f, null, rq -> rq.getSession(true).setAttribute("role", "Admin"));
        assertThat(r.findById(id)).isNotNull();
        request(f, id, rq -> rq.getSession(false).setAttribute("x", "y"));
        assertThat(attr(r, id, "x")).isEqualTo("y");
        request(f, id, rq -> rq.getSession(false).invalidate());
        assertThat(r.findById(id)).isNull();
    }

    @Test @DisplayName("the app configures the fixed delete query (no MAX_INACTIVE_INTERVAL guard)")
    void appConfiguresQuery() {
        assertThat(SessionConfigAccess.deleteSessionQuery()).isEqualTo("DELETE FROM %TABLE_NAME% WHERE SESSION_ID = ?");
        JdbcIndexedSessionRepository r = repo(false);
        SessionConfigAccess.customize(r);
        @SuppressWarnings({"rawtypes", "unchecked"}) org.springframework.session.SessionRepository raw = r;
        org.springframework.session.Session s = (org.springframework.session.Session) raw.createSession();
        s.setMaxInactiveInterval(java.time.Duration.ofSeconds(-1));
        raw.save(s);
        r.deleteById(s.getId());
        assertThat(r.findById(s.getId())).isNull();
    }
}
