package com.churchgeniuspro.mcp;

import com.churchgeniuspro.hibernate.AppUser;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpSession;

import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * An in-memory {@link HttpSession} that lives for ONE token-authenticated request (AI Phase 5).
 *
 * <p>Every permission check in the application reads the HTTP session — {@code RoleGuard},
 * {@code AiAccessPolicy}, {@code SessionUtil}, the action agent's draft state. Rather than
 * teach each of them about tokens, the {@code ApiTokenFilter} wraps the request so that
 * {@code getSession()} returns this object, filled with exactly the attributes
 * {@code LoginController.buildResponse} sets for a staff sign-in. Nothing is persisted:
 * spring-session never sees it, no cookie is issued, and the object is garbage with the request.
 *
 * <p>Two extra attributes mark it: {@link #ATTR_TOKEN} (true) and {@link #ATTR_SCOPES}
 * (the token's scope set) — {@code AiAccessPolicy} narrows finance access from the latter.
 */
public final class TokenSession implements HttpSession {

    public static final String ATTR_TOKEN = "apiToken", ATTR_SCOPES = "apiTokenScopes", ATTR_TOKEN_ID = "apiTokenId";

    private final Map<String, Object> attrs = new LinkedHashMap<>();
    private final String id = "tok-" + UUID.randomUUID();
    private final long created = System.currentTimeMillis();
    private final ServletContext ctx;

    public TokenSession(ServletContext ctx) { this.ctx = ctx; }

    /** The staff sign-in shape (see {@code LoginController.buildResponse}) for this token's owner. */
    public static TokenSession forPrincipal(ServletContext ctx, PersonalAccessTokenService.Principal p) {
        TokenSession s = new TokenSession(ctx);
        AppUser u = p.user();
        s.setAttribute("clientId", u.getUserId() == null ? "" : u.getUserId());   // signup.client_id of the login
        s.setAttribute("username", p.token().getUsername());
        s.setAttribute("church", Boolean.FALSE);
        s.setAttribute("churchId", "");
        s.setAttribute("role", u.getRole());
        s.setAttribute("appClientId", u.getClientId());
        s.setAttribute("appUserId", u.getId());
        s.setAttribute("firstName", u.getFirstName());
        s.setAttribute("lastName", u.getLastName());
        if (p.privileges() != null) s.setAttribute("privileges", p.privileges());
        // The permission map was read a moment ago by authenticate(); no 15-second refresh needed in this request.
        s.setAttribute(com.churchgeniuspro.service.PermissionRefresher.CHECKED_AT, System.currentTimeMillis());
        s.setAttribute(ATTR_TOKEN, Boolean.TRUE);
        s.setAttribute(ATTR_TOKEN_ID, p.token().getId());
        s.setAttribute(ATTR_SCOPES, Set.copyOf(p.scopes()));
        return s;
    }

    /** True when {@code s} is a token-backed session. */
    public static boolean isToken(HttpSession s) { return s != null && Boolean.TRUE.equals(s.getAttribute(ATTR_TOKEN)); }

    /** The token's scopes, or null for an ordinary session. */
    @SuppressWarnings("unchecked")
    public static Set<String> scopes(HttpSession s) {
        if (s == null) return null;
        Object o = s.getAttribute(ATTR_SCOPES);
        return o instanceof Set<?> set ? (Set<String>) set : null;
    }

    /** A request whose session is {@code session}, whatever the container would have produced. */
    public static HttpServletRequest wrap(HttpServletRequest req, TokenSession session) {
        return new HttpServletRequestWrapper(req) {
            @Override public HttpSession getSession() { return session; }
            @Override public HttpSession getSession(boolean create) { return session; }
            @Override public String getRequestedSessionId() { return session.getId(); }
            @Override public boolean isRequestedSessionIdValid() { return true; }
            @Override public String changeSessionId() { return session.getId(); }
        };
    }

    @Override public long getCreationTime() { return created; }
    @Override public String getId() { return id; }
    @Override public long getLastAccessedTime() { return created; }
    @Override public ServletContext getServletContext() { return ctx; }
    @Override public void setMaxInactiveInterval(int interval) { }
    @Override public int getMaxInactiveInterval() { return 0; }
    @Override public Object getAttribute(String name) { return attrs.get(name); }
    @Override public Enumeration<String> getAttributeNames() { return Collections.enumeration(attrs.keySet()); }
    @Override public void setAttribute(String name, Object value) { if (value == null) attrs.remove(name); else attrs.put(name, value); }
    @Override public void removeAttribute(String name) { attrs.remove(name); }
    @Override public void invalidate() { attrs.clear(); }
    @Override public boolean isNew() { return true; }
}
