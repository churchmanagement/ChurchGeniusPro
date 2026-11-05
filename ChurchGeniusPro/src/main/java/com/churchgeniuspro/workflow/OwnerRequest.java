package com.churchgeniuspro.workflow;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.mcp.TokenSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A request-shaped view of a rule owner's staff sign-in (AI Phase 6). The engine runs outside any HTTP
 * request, but every permission rule in the application reads one — {@code AiAccessPolicy.resolve},
 * {@code RoleGuard.requirePagePermission}. This builds the same in-memory session a sign-in would
 * ({@link TokenSession} without the token markers) behind a minimal {@link HttpServletRequest} proxy:
 * {@code getSession} returns it, request attributes work, everything else answers empty. Nothing is
 * persisted and the object dies with the run.
 */
public final class OwnerRequest {

    private OwnerRequest() {}

    public static HttpServletRequest of(AppUser owner, String username, String privileges) {
        TokenSession s = new TokenSession(null);
        s.setAttribute("clientId", owner.getUserId() == null ? "" : owner.getUserId());
        s.setAttribute("username", username == null ? "" : username);
        s.setAttribute("church", Boolean.FALSE);
        s.setAttribute("churchId", "");
        s.setAttribute("role", owner.getRole());
        s.setAttribute("appClientId", owner.getClientId());
        s.setAttribute("appUserId", owner.getId());
        s.setAttribute("firstName", owner.getFirstName());
        s.setAttribute("lastName", owner.getLastName());
        if (privileges != null) s.setAttribute("privileges", privileges);
        s.setAttribute(com.churchgeniuspro.service.PermissionRefresher.CHECKED_AT, System.currentTimeMillis());
        s.setAttribute("workflowOwner", Boolean.TRUE);
        return wrap(s);
    }

    static HttpServletRequest wrap(HttpSession session) {
        Map<String, Object> attrs = new HashMap<>();
        return (HttpServletRequest) Proxy.newProxyInstance(OwnerRequest.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class}, (proxy, m, args) -> {
            switch (m.getName()) {
                case "getSession": return session;
                case "getAttribute": return attrs.get(String.valueOf(args[0]));
                case "setAttribute": if (args[1] == null) attrs.remove(String.valueOf(args[0])); else attrs.put(String.valueOf(args[0]), args[1]); return null;
                case "removeAttribute": attrs.remove(String.valueOf(args[0])); return null;
                case "getAttributeNames": return Collections.enumeration(attrs.keySet());
                case "getRequestURI": case "getServletPath": return "/workflow";
                case "getContextPath": case "getQueryString": case "getPathInfo": return m.getName().equals("getContextPath") ? "" : null;
                case "getMethod": return "WORKFLOW";
                case "getHeader": case "getParameter": case "getRemoteAddr": case "getRemoteUser": case "getRequestedSessionId": case "getScheme": case "getServerName": return null;
                case "getHeaders": case "getHeaderNames": case "getParameterNames": return Collections.emptyEnumeration();
                case "getParameterMap": return Collections.emptyMap();
                case "getCookies": return null;
                case "isSecure": case "isRequestedSessionIdValid": case "isRequestedSessionIdFromCookie": case "isRequestedSessionIdFromURL": return false;
                case "getServerPort": case "getContentLength": case "getRemotePort": case "getLocalPort": return 0;
                case "getContentLengthLong": case "getDateHeader": return -1L;
                case "getIntHeader": return -1;
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                case "toString": return "OwnerRequest[" + session.getAttribute("username") + "]";
                default:
                    Class<?> r = m.getReturnType();
                    if (r == boolean.class) return false;
                    if (r == int.class) return 0;
                    if (r == long.class) return 0L;
                    return null;
            }
        });
    }
}
