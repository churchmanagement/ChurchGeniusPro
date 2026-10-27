package com.churchgeniuspro.config;

import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

/** Test-only bridge to SessionConfig's package-private query and its repository customizer. */
public final class SessionConfigAccess {
    private SessionConfigAccess() {}
    public static String deleteSessionQuery() { return SessionConfig.DELETE_SESSION_QUERY; }
    public static void customize(JdbcIndexedSessionRepository r) { new SessionConfig().sessionAttributeUpsertCustomizer().customize(r); }
}
