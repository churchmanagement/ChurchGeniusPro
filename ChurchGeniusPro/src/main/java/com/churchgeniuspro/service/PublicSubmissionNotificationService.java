package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.PublicSubmissionNotification;
import com.churchgeniuspro.hibernate.PublicSubmissionNotificationState;
import com.churchgeniuspro.hibernate.UserPermissions;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.PublicSubmissionNotificationRepository;
import com.churchgeniuspro.repository.PublicSubmissionNotificationStateRepository;
import com.churchgeniuspro.repository.UserPermissionsRepository;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * In-app notifications for submissions made on a church's public pages.
 *
 * <p>Who sees a notification is decided on every read, from the existing rules, so
 * nothing here is a second permission system:
 * <ol>
 *   <li><b>Tenant</b> — only the signed-in user's own church ({@link RoleGuard#clientId}).</li>
 *   <li><b>Destination page's role guard</b> — the exact {@code RoleGuard} check the
 *       destination page runs, so a notification is never shown to someone the page
 *       would refuse.</li>
 *   <li><b>Both viewUsers permissions</b> — the section AND the page key (e.g.
 *       {@code accounting} + {@code accounting.donation}), evaluated with
 *       {@link RoleGuard#permissionAllows} against the permissions as currently saved,
 *       so removing one in viewUsers hides the notification on the next poll.</li>
 *   <li><b>Plan</b> — the destination page's subscription feature must be enabled.</li>
 *   <li><b>Active account</b> — {@link SubscriptionService#isAccountActive}; an
 *       expired/inactive church sees none (rows are kept, and reappear on renewal).</li>
 * </ol>
 * The destination pages keep enforcing their own guards when the link is followed.
 */
@Service
public class PublicSubmissionNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PublicSubmissionNotificationService.class);

    /** How far back the panel looks for undismissed submissions. */
    static final int WINDOW_DAYS = 30;
    static final int MAX_ITEMS   = 50;
    /** Prefix that keeps these ids distinct from the existing notification-log ids. */
    public static final String ID_PREFIX = "ps-";

    /** The four notification types, their destination page and the permissions that gate them. */
    public enum Type {
        PRAYER    ("/followups?tab=prayer",  "more",       "more.followups",      RoleGuard::requireAdminOrUser,       "followUps",  "🙏"),
        CONNECT   ("/followups?tab=connect", "more",       "more.followups",      RoleGuard::requireAdminOrUser,       "followUps",  "🤝"),
        MEMBERSHIP("/membershipRequests",    "admin",      "admin.membership",    RoleGuard::requireAdminOrChurch,     null,         "📝"),
        DONATION  ("/donation-review",       "accounting", "accounting.donation", RoleGuard::requireAccountantOrAdmin, "accounting", "💝");

        public final String url;
        public final String sectionKey;
        public final String pageKey;
        /** The destination page's own role guard (null = allowed). */
        final Function<HttpServletRequest, String> pageGuard;
        /** Subscription feature gating the destination page (SubscriptionFeatureCatalog), or null. */
        public final String planFeature;
        public final String icon;

        Type(String url, String sectionKey, String pageKey,
             Function<HttpServletRequest, String> pageGuard, String planFeature, String icon) {
            this.url = url; this.sectionKey = sectionKey; this.pageKey = pageKey;
            this.pageGuard = pageGuard; this.planFeature = planFeature; this.icon = icon;
        }

        public String tag() { return "submission-" + name().toLowerCase(); }

        static Type of(String s) {
            try { return s == null ? null : Type.valueOf(s); } catch (IllegalArgumentException e) { return null; }
        }
    }

    private final PublicSubmissionNotificationRepository      repo;
    private final PublicSubmissionNotificationStateRepository stateRepo;
    private final UserPermissionsRepository                   permsRepo;
    private final AppUserRepository                           userRepo;
    private final SubscriptionService                         subscriptions;

    public PublicSubmissionNotificationService(PublicSubmissionNotificationRepository repo,
                                               PublicSubmissionNotificationStateRepository stateRepo,
                                               UserPermissionsRepository permsRepo,
                                               AppUserRepository userRepo,
                                               SubscriptionService subscriptions) {
        this.repo = repo;
        this.stateRepo = stateRepo;
        this.permsRepo = permsRepo;
        this.userRepo = userRepo;
        this.subscriptions = subscriptions;
    }

    // ── recording (called by the public submission paths) ─────────────────

    /**
     * Records one notification. Runs in its own transaction and never throws: a
     * notification problem must never cost a visitor their submission.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String clientId, Type type, String title, String body, Long sourceId) {
        if (clientId == null || clientId.isBlank() || type == null) return;
        try {
            PublicSubmissionNotification n = new PublicSubmissionNotification();
            n.setClientId(clientId);
            n.setType(type.name());
            n.setTitle(clip(title == null || title.isBlank() ? "New submission" : title, 255));
            n.setBody(clip(body, 2000));
            n.setSourceId(sourceId);
            n.setCreatedAt(Instant.now());
            repo.save(n);
        } catch (Exception e) {
            log.warn("Could not record {} notification for {} — {}", type, clientId, e.getMessage());
        }
    }

    // ── visibility ─────────────────────────────────────────────────────────

    /** Whether the signed-in user may see notifications of this type right now. */
    public boolean mayView(HttpServletRequest request, Type type) {
        HttpSession session = request.getSession(false);
        if (session == null || type == null) return false;
        // Staff notification panel only; the member portal has its own.
        if ("Member".equals(session.getAttribute("role"))) return false;
        String orgClientId = RoleGuard.clientId(request);
        if (orgClientId == null) return false;
        if (type.pageGuard.apply(request) != null) return false;          // destination page would refuse
        if (!RoleGuard.isChurch(request)) {                                // church logins bypass granular perms, as everywhere
            String privileges = currentPrivileges(session);
            if (!RoleGuard.permissionAllows(privileges, type.sectionKey)) return false;
            if (!RoleGuard.permissionAllows(privileges, type.pageKey))    return false;
        }
        // The plan must include the destination page, or the link would only show the plan notice.
        if (type.planFeature != null && !subscriptions.isFeatureEnabled(orgClientId, type.planFeature)) return false;
        return subscriptions.isAccountActive(orgClientId);
    }

    /**
     * The user's permissions as saved now — the same source login uses
     * ({@code user_permissions}, else {@code app_user.privileges}) — so a change in
     * viewUsers applies without re-login. Sessions not tied to an app_user (NTag,
     * temporary access) fall back to the session's own privileges.
     */
    String currentPrivileges(HttpSession session) {
        Object idAttr = session.getAttribute("appUserId");
        Integer appUserId = null;
        if (idAttr != null) {
            try { appUserId = Integer.valueOf(String.valueOf(idAttr)); } catch (NumberFormatException ignored) { }
        }
        if (appUserId != null) {
            final Integer id = appUserId;
            return permsRepo.findByAppUserId(id).map(UserPermissions::getPermissions)
                    .orElseGet(() -> userRepo.findById(id).map(AppUser::getPrivileges).orElse(null));
        }
        Object p = session.getAttribute("privileges");
        return p == null ? null : String.valueOf(p);
    }

    private static String userKey(HttpServletRequest request) {
        HttpSession s = request.getSession(false);
        Object v = s == null ? null : s.getAttribute("clientId");
        return v == null ? null : v.toString();
    }

    /** Visible, undismissed notifications for the signed-in user, newest first. */
    public List<Map<String, Object>> listFor(HttpServletRequest request) {
        String orgClientId = RoleGuard.clientId(request);
        String userKey = userKey(request);
        if (orgClientId == null || userKey == null) return List.of();

        Map<Type, Boolean> allowed = new HashMap<>();
        for (Type t : Type.values()) allowed.put(t, mayView(request, t));
        if (!allowed.containsValue(Boolean.TRUE)) return List.of();

        List<PublicSubmissionNotification> rows = repo.findRecent(orgClientId,
                Instant.now().minus(WINDOW_DAYS, ChronoUnit.DAYS), PageRequest.of(0, MAX_ITEMS));
        rows = rows.stream().filter(n -> Boolean.TRUE.equals(allowed.get(Type.of(n.getType())))).toList();
        if (rows.isEmpty()) return List.of();

        Map<Long, PublicSubmissionNotificationState> states = new HashMap<>();
        for (PublicSubmissionNotificationState s :
                stateRepo.findByUserKeyAndNotificationIdIn(userKey, rows.stream().map(PublicSubmissionNotification::getId).toList())) {
            states.put(s.getNotificationId(), s);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (PublicSubmissionNotification n : rows) {
            PublicSubmissionNotificationState st = states.get(n.getId());
            if (st != null && st.getDismissedAt() != null) continue;
            Type t = Type.of(n.getType());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",          ID_PREFIX + n.getId());
            m.put("title",       n.getTitle());
            m.put("body",        n.getBody());
            m.put("url",         t.url);
            m.put("tag",         t.tag());
            m.put("icon",        t.icon);
            m.put("sentAt",      DateTimeFormatter.ISO_INSTANT.format(n.getCreatedAt()));
            m.put("read",        st != null && st.getReadAt() != null);
            m.put("dismissible", true);
            out.add(m);
        }
        return out;
    }

    /** Marks every currently visible notification read for this user. */
    @Transactional
    public void markAllRead(HttpServletRequest request) {
        String userKey = userKey(request);
        if (userKey == null) return;
        Instant now = Instant.now();
        for (Map<String, Object> item : listFor(request)) {
            if (Boolean.TRUE.equals(item.get("read"))) continue;
            PublicSubmissionNotificationState st = state(idOf(item), userKey);
            st.setReadAt(now);
            stateRepo.save(st);
        }
    }

    /**
     * Clears one notification from this user's panel. Refused (false) unless the
     * notification belongs to the user's own church AND the user may view its type —
     * an id from another church, or of a type the user cannot see, is simply not found.
     */
    @Transactional
    public boolean dismiss(HttpServletRequest request, Long id) {
        String orgClientId = RoleGuard.clientId(request);
        String userKey = userKey(request);
        if (id == null || orgClientId == null || userKey == null) return false;
        PublicSubmissionNotification n = repo.findByIdAndClientId(id, orgClientId).orElse(null);
        if (n == null || !mayView(request, Type.of(n.getType()))) return false;
        PublicSubmissionNotificationState st = state(id, userKey);
        Instant now = Instant.now();
        st.setDismissedAt(now);
        if (st.getReadAt() == null) st.setReadAt(now);
        stateRepo.save(st);
        return true;
    }

    /** Clears every notification currently visible to this user. */
    @Transactional
    public int dismissAll(HttpServletRequest request) {
        int n = 0;
        for (Map<String, Object> item : listFor(request)) {
            if (dismiss(request, idOf(item))) n++;
        }
        return n;
    }

    private PublicSubmissionNotificationState state(Long id, String userKey) {
        return stateRepo.findByNotificationIdAndUserKey(id, userKey).orElseGet(() -> {
            PublicSubmissionNotificationState s = new PublicSubmissionNotificationState();
            s.setNotificationId(id);
            s.setUserKey(userKey);
            return s;
        });
    }

    private static Long idOf(Map<String, Object> item) {
        return Long.valueOf(String.valueOf(item.get("id")).substring(ID_PREFIX.length()));
    }

    /** Parses "ps-123" → 123, or null. */
    public static Long parseId(String raw) {
        if (raw == null || !raw.startsWith(ID_PREFIX)) return null;
        try { return Long.valueOf(raw.substring(ID_PREFIX.length())); } catch (NumberFormatException e) { return null; }
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
