package com.churchgeniuspro.webfilter;

import com.churchgeniuspro.hibernate.MemberPreference;
import com.churchgeniuspro.repository.MemberPreferenceRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Enforces the "Don't log out automatically" preference for both Member and
 * staff (Admin / SuperAdmin / Accountant / User) sessions.
 *
 * <ul>
 *   <li><b>Member sessions</b> — reads {@code member_preference.no_auto_logout}
 *       via {@link MemberPreferenceRepository}.</li>
 *   <li><b>Staff sessions</b> — reads the {@code _noAutoLogout} key from the
 *       {@code privileges} JSON string stored in the HTTP session at login time.
 *       This is the same JSON blob that the Permissions tab in viewusers.html
 *       saves to {@code user_permissions.permissions}.</li>
 * </ul>
 *
 * <p>When the flag is {@code true} for either session type,
 * {@code session.setMaxInactiveInterval(-1)} is called so the server-side
 * Spring Session never expires due to inactivity.  Otherwise the default
 * 30-minute timeout is restored.
 *
 * <p>Registered at order 0 (before {@link AuthFilter}) so the timeout is
 * adjusted on every request before authentication is checked.
 */
public class NoAutoLogoutFilter implements Filter {

    /** 30 minutes — must match server.servlet.session.timeout in application.properties. */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30 * 60;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Lazily-resolved reference to the preference repository.
     * Populated on the first request after the application context is ready.
     */
    private MemberPreferenceRepository prefRepo;

    @Override
    public void doFilter(ServletRequest request,
                         ServletResponse response,
                         FilterChain chain)
            throws IOException, ServletException {

        if (request instanceof HttpServletRequest httpReq) {
            HttpSession session = httpReq.getSession(false);

            if (session != null) {
                Object roleAttr = session.getAttribute("role");
                String role = roleAttr != null ? roleAttr.toString() : "";

                boolean noAutoLogout = false;

                if ("Member".equals(role)) {
                    // Member sessions: read from member_preference table
                    Integer memberId = memberIdFrom(session);
                    if (memberId != null) {
                        noAutoLogout = resolveMemberPreference(httpReq, memberId);
                    }
                } else if (!role.isEmpty()) {
                    // Staff sessions (Admin, SuperAdmin, Accountant, User, etc.):
                    // read _noAutoLogout from the privileges JSON stored in the session.
                    noAutoLogout = resolveStaffPreference(session);
                }

                // -1 means "never expire"; otherwise restore the default timeout.
                session.setMaxInactiveInterval(noAutoLogout ? -1 : DEFAULT_TIMEOUT_SECONDS);
            }
        }

        chain.doFilter(request, response);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the {@code noAutoLogout} flag for the given member from
     * {@code member_preference}, defaulting to {@code false} when absent.
     */
    private boolean resolveMemberPreference(HttpServletRequest request, Integer memberId) {
        try {
            MemberPreferenceRepository repo = getRepo(request);
            if (repo == null) return false;
            Optional<MemberPreference> pref = repo.findByMemberId(memberId);
            return pref.map(MemberPreference::isNoAutoLogout).orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns the {@code _noAutoLogout} flag for a staff user by parsing the
     * {@code privileges} JSON string stored in the HTTP session.
     * Defaults to {@code true} (no auto-logout) when the key is absent,
     * matching the frontend's default in session.js.
     */
    private boolean resolveStaffPreference(HttpSession session) {
        try {
            Object privAttr = session.getAttribute("privileges");
            if (privAttr == null) return true;  // no privileges saved → default no-auto-logout
            String json = privAttr.toString().trim();
            if (json.isEmpty() || json.equals("null")) return true;
            Map<String, Object> perms = MAPPER.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            Object val = perms.get("_noAutoLogout");
            if (val == null) return true;        // key absent → default no-auto-logout
            if (val instanceof Boolean b) return b;
            return Boolean.parseBoolean(val.toString());
        } catch (Exception e) {
            return true;  // on parse error, keep session alive (safe default)
        }
    }

    /** Safely extracts the {@code memberId} session attribute as an Integer. */
    private static Integer memberIdFrom(HttpSession session) {
        Object raw = session.getAttribute("memberId");
        if (raw instanceof Integer i) return i;
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    /**
     * Lazily resolves the {@link MemberPreferenceRepository} from the Spring
     * application context.  The result is cached in {@link #prefRepo} so the
     * lookup only happens once.
     */
    private MemberPreferenceRepository getRepo(HttpServletRequest request) {
        if (prefRepo == null) {
            ApplicationContext ctx = WebApplicationContextUtils
                    .getWebApplicationContext(request.getServletContext());
            if (ctx != null) {
                prefRepo = ctx.getBean(MemberPreferenceRepository.class);
            }
        }
        return prefRepo;
    }
}
