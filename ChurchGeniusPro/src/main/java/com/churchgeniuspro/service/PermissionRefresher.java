package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.UserPermissions;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.UserPermissionsRepository;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Keeps a session's permission map in step with what is saved in the database,
 * so a change made in viewUsers reaches the affected user without a new sign-in.
 *
 * <p>Sign-in copies {@code user_permissions.permissions} (or the legacy
 * {@code app_user.privileges}) into the session attribute {@code privileges}, and
 * {@code family_member.member_privileges} into {@code memberPrivileges}. Every guard
 * reads the session copy. This class re-reads the saved value at most once every
 * {@link #THROTTLE_MS} per session and replaces the session copy when it differs.
 * It only ever copies the current saved value for the SAME user; it never invents,
 * widens or clears permissions on its own, and any failure leaves the session as it was.
 *
 * <p>Reached statically ({@link #refreshIfStale}) because the callers are
 * {@code RoleGuard} (static) and {@code AuthFilter} (constructed by hand in
 * FilterConfig). Outside a Spring context — unit tests — there is no instance and
 * the call is a no-op.
 */
@Component
public class PermissionRefresher {

    private static final Logger log = LoggerFactory.getLogger(PermissionRefresher.class);

    /** How long a session's copy is trusted before the saved value is re-read. */
    public static final long THROTTLE_MS = 15_000L;

    /** Session attribute: epoch millis of the last re-read (or of the last direct save). */
    public static final String CHECKED_AT = "privilegesCheckedAt";

    private static volatile PermissionRefresher instance;

    private final UserPermissionsRepository permsRepo;
    private final AppUserRepository userRepo;
    private final FamilyMemberRepository memberRepo;

    public PermissionRefresher(UserPermissionsRepository permsRepo,
                               AppUserRepository userRepo,
                               FamilyMemberRepository memberRepo) {
        this.permsRepo = permsRepo;
        this.userRepo = userRepo;
        this.memberRepo = memberRepo;
    }

    @PostConstruct
    void register() { instance = this; }

    /** Test seam: install (or clear with null) the instance used by the static entry point. */
    public static void setInstance(PermissionRefresher r) { instance = r; }

    /**
     * Refreshes {@code privileges} / {@code memberPrivileges} on this session if the
     * last check is older than {@link #THROTTLE_MS}. Safe to call on every request.
     *
     * @return true when the session copy was changed
     */
    public static boolean refreshIfStale(HttpSession session) {
        PermissionRefresher r = instance;
        if (r == null || session == null) return false;
        try {
            return r.refresh(session, System.currentTimeMillis());
        } catch (Exception e) {
            log.debug("Permission refresh skipped — {}", e.getMessage());
            return false;
        }
    }

    /** Marks the session as freshly loaded (used after a direct save into the session). */
    public static void markFresh(HttpSession session) {
        if (session != null) session.setAttribute(CHECKED_AT, System.currentTimeMillis());
    }

    public boolean refresh(HttpSession session, long now) {
        Object checked = session.getAttribute(CHECKED_AT);
        if (checked instanceof Long t && now - t < THROTTLE_MS) return false;
        session.setAttribute(CHECKED_AT, now);

        Object appUserId = session.getAttribute("appUserId");
        if (appUserId instanceof Integer id) {
            return refreshStaff(session, id);
        }
        Object memberId = session.getAttribute("memberId");
        if ("Member".equals(session.getAttribute("role")) && memberId instanceof Integer mid) {
            return refreshMember(session, mid);
        }
        return false;   // church, temporary-access and NTag sessions carry no saved map to re-read
    }

    private boolean refreshStaff(HttpSession session, Integer appUserId) {
        // Same precedence as sign-in: user_permissions row, else app_user.privileges.
        String saved = permsRepo.findByAppUserId(appUserId).map(UserPermissions::getPermissions).orElse(null);
        if (saved == null) {
            AppUser u = userRepo.findById(appUserId).orElse(null);
            if (u == null) return false;          // AuthFilter handles deleted users; not our job
            saved = u.getPrivileges();
        }
        return apply(session, "privileges", saved, appUserId);
    }

    private boolean refreshMember(HttpSession session, Integer memberId) {
        FamilyMember fm = memberRepo.findById(memberId).orElse(null);
        if (fm == null) return false;
        return apply(session, "memberPrivileges", fm.getMemberPrivileges(), memberId);
    }

    private static boolean apply(HttpSession session, String attr, String saved, Object who) {
        Object current = session.getAttribute(attr);
        String cur = current == null ? null : String.valueOf(current);
        if (Objects.equals(cur, saved)) return false;
        session.setAttribute(attr, saved);
        log.info("Permissions refreshed for {} {} without re-login", attr.equals("privileges") ? "app_user" : "member", who);
        return true;
    }
}
