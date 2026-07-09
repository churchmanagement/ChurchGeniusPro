package com.churchgeniuspro.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import org.springframework.session.web.http.CookieHttpSessionIdResolver;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.web.http.HttpSessionIdResolver;

import javax.sql.DataSource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.util.Collections;
import java.util.List;

/**
 * Switches the HTTP session store from in-memory Tomcat sessions to
 * PostgreSQL-backed Spring Session JDBC.
 *
 * <p>On first startup the {@code SPRING_SESSION} and
 * {@code SPRING_SESSION_ATTRIBUTES} tables are created automatically using
 * the PostgreSQL DDL script bundled inside {@code spring-session-jdbc}.
 * The {@code IF NOT EXISTS} guards mean subsequent restarts are safe.
 *
 * <p>The default max-inactive interval is 1800 seconds (30 minutes),
 * matching {@code server.servlet.session.timeout=30m} in application.properties.
 * Members who enable "Don't log out automatically" have their individual
 * session timeout overridden to -1 (never expire) by
 * {@link com.churchgeniuspro.webfilter.NoAutoLogoutFilter} on every request.
 *
 * <h3>Session cookie persistence across app restarts</h3>
 * <p>By default Spring Session issues a session-scoped cookie (no {@code Max-Age}),
 * which the browser discards when the PWA or browser tab is closed.  Even though
 * the session row survives in PostgreSQL, the cookie is gone so the next visit
 * looks like a brand-new, unauthenticated request.
 *
 * <p>The {@link DefaultCookieSerializer} bean below gives the JSESSIONID cookie a
 * 30-day {@code Max-Age} so it persists across restarts just like the remember-me
 * cookie.  The server-side idle timeout still governs actual session expiry — a
 * cookie surviving 30 days is only useful while the session row in
 * {@code SPRING_SESSION} is still valid.
 *
 * <h3>Public path session bypass</h3>
 * <p>Requests to {@code /public/**} (e.g. the church-registration validate endpoint)
 * must never trigger a PostgreSQL session lookup.  The custom
 * {@link HttpSessionIdResolver} below returns an empty list for those paths,
 * so Spring Session's {@code SessionRepositoryFilter} skips the DB entirely —
 * eliminating the "Verifying your link…" hang caused by pool contention on the
 * {@code SPRING_SESSION} table.
 */
@Configuration
@EnableJdbcHttpSession(maxInactiveIntervalInSeconds = 1800)
public class SessionConfig {

    /** 30 days in seconds — matches the remember-me cookie lifetime. */
    private static final int SESSION_COOKIE_MAX_AGE = 30 * 24 * 60 * 60;

    @Value("${server.servlet.session.cookie.secure:false}")
    private boolean cookieSecure;

    /**
     * Configures the JSESSIONID cookie with a persistent Max-Age so it
     * survives PWA restarts and server redeployments.
     *
     * <ul>
     *   <li>{@code cookieName}  — keeps the standard JSESSIONID name</li>
     *   <li>{@code cookieMaxAge} — 30 days; browser keeps the cookie across restarts</li>
     *   <li>{@code httpOnly}    — true; not accessible to JavaScript</li>
     *   <li>{@code useSecureCookie} — mirrors {@code server.servlet.session.cookie.secure}</li>
     *   <li>{@code sameSite}    — "Lax" (default); works for same-origin PWA navigation</li>
     * </ul>
     */
    @Bean
    public DefaultCookieSerializer cookieSerializer() {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("JSESSIONID");
        serializer.setCookieMaxAge(SESSION_COOKIE_MAX_AGE);
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(cookieSecure);
        serializer.setSameSite("Lax");
        serializer.setCookiePath("/");
        return serializer;
    }

    /**
     * Custom session-ID resolver that bypasses PostgreSQL session lookup for
     * {@code /public/**} requests.
     *
     * <p>Spring Session's {@code SessionRepositoryFilter} calls
     * {@code resolveSessionIds()} on every request.  By returning an empty list
     * for public paths, we prevent the filter from issuing a
     * {@code SELECT * FROM SPRING_SESSION WHERE …} — which would otherwise
     * block when the DB connection pool is under pressure.
     *
     * <p>For all other paths, this delegates to the standard
     * {@link CookieHttpSessionIdResolver} so normal cookie-based sessions
     * continue to work unchanged.
     */
    @Bean("httpSessionIdResolver")
    public HttpSessionIdResolver httpSessionIdResolver() {
        CookieHttpSessionIdResolver delegate = new CookieHttpSessionIdResolver();
        delegate.setCookieSerializer(cookieSerializer());

        return new HttpSessionIdResolver() {
            @Override
            public List<String> resolveSessionIds(HttpServletRequest request) {
                // Public paths: tell Spring Session there is no session to load.
                // This skips the SPRING_SESSION DB SELECT entirely.
                if (request.getRequestURI().startsWith("/public/")) {
                    return Collections.emptyList();
                }
                return delegate.resolveSessionIds(request);
            }

            @Override
            public void setSessionId(HttpServletRequest request,
                                     HttpServletResponse response,
                                     String sessionId) {
                if (!request.getRequestURI().startsWith("/public/")) {
                    delegate.setSessionId(request, response, sessionId);
                }
            }

            @Override
            public void expireSession(HttpServletRequest request,
                                      HttpServletResponse response) {
                if (!request.getRequestURI().startsWith("/public/")) {
                    delegate.expireSession(request, response);
                }
            }
        };
    }

    /**
     * Creates the SPRING_SESSION / SPRING_SESSION_ATTRIBUTES tables on startup
     * using the PostgreSQL schema script shipped with spring-session-jdbc.
     * Safe to run repeatedly — the script uses CREATE TABLE IF NOT EXISTS.
     */
    @Bean
    public DataSourceInitializer springSessionSchemaInitializer(DataSource dataSource) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(
            new ClassPathResource("org/springframework/session/jdbc/schema-postgresql.sql"));
        populator.setContinueOnError(true); // tolerate "already exists" errors gracefully

        DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(dataSource);
        initializer.setDatabasePopulator(populator);
        return initializer;
    }

}
