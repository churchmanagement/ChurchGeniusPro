package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.PushSubscription;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.AiConsentRepository;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.DashboardPreferenceRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.PersonalAccessTokenRepository;
import com.churchgeniuspro.repository.PushSubscriptionRepository;
import com.churchgeniuspro.repository.TrialTipStateRepository;
import com.churchgeniuspro.repository.UserFavoriteRepository;
import com.churchgeniuspro.repository.UserPermissionsRepository;
import com.churchgeniuspro.util.PasswordUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.HexFormat;

/**
 * App Store readiness D7 — a staff login deletes ITSELF (Apple 5.1.1(v)).
 *
 * <h3>Deleted</h3>
 * The {@code signup} login (password erased, username and client id released, unlinked, every
 * remembered device revoked), the {@code app_user} staff record (marked deleted and disabled,
 * e-mail / phone / address erased), the person's saved permissions, personal access tokens,
 * push subscriptions, AI-consent answers, favourites, dashboard layout and trial-tip state.
 *
 * <h3>Kept</h3>
 * Records the person entered for the church (gifts, expenses, follow-ups …) keep the name they
 * were saved with: the church's financial and ministry history must show who entered them. The
 * {@code app_user} row keeps first and last name for the same reason (some history views join it).
 *
 * <h3>Refused</h3>
 * Church logins (they request whole-church deletion instead), Member Portal / Kids Portal
 * sessions (they have their own path), temporary-access, NTAG and API-token sessions, and the
 * church's LAST active SuperAdmin — someone must still be able to run the church.
 */
@Service
public class StaffAccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(StaffAccountDeletionService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final LoginRepository logins;
    private final AppUserRepository users;
    private final UserPermissionsRepository permissions;
    private final PersonalAccessTokenRepository tokens;
    private final PushSubscriptionRepository pushSubs;
    private final AiConsentRepository consents;
    private final UserFavoriteRepository favorites;
    private final DashboardPreferenceRepository dashboardPrefs;
    private final TrialTipStateRepository tipState;
    private final RememberMeService rememberMe;
    private final PublicSubmissionNotificationService notifications;
    private final EmailService email;
    private final JdbcTemplate jdbc;

    public StaffAccountDeletionService(LoginRepository logins, AppUserRepository users,
                                       UserPermissionsRepository permissions, PersonalAccessTokenRepository tokens,
                                       PushSubscriptionRepository pushSubs, AiConsentRepository consents,
                                       UserFavoriteRepository favorites, DashboardPreferenceRepository dashboardPrefs,
                                       TrialTipStateRepository tipState, RememberMeService rememberMe,
                                       PublicSubmissionNotificationService notifications, EmailService email,
                                       JdbcTemplate jdbc) {
        this.logins = logins; this.users = users; this.permissions = permissions; this.tokens = tokens;
        this.pushSubs = pushSubs; this.consents = consents; this.favorites = favorites;
        this.dashboardPrefs = dashboardPrefs; this.tipState = tipState; this.rememberMe = rememberMe;
        this.notifications = notifications; this.email = email; this.jdbc = jdbc;
    }

    public record Outcome(boolean ok, int status, String code, String error, String email, String name) {
        static Outcome fail(int status, String code, String error) { return new Outcome(false, status, code, error, null, null); }
    }

    /** True when another enabled, not-deleted SuperAdmin of the church exists. */
    boolean anotherSuperAdmin(String appClientId, Integer appUserId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE client_id = ? AND id <> ? AND delete_flag = false "
                        + "AND enabled = true AND LOWER(role) = 'superadmin'",
                Integer.class, appClientId, appUserId);
        return n != null && n > 0;
    }

    /**
     * @param staffRef    the session's clientId (signup.client_id = app_user.user_id, "USR…")
     * @param appUserId   the session's app_user id
     * @param appClientId the session's church
     */
    @Transactional
    public Outcome deleteOwnLogin(String staffRef, Integer appUserId, String appClientId, String password) {
        if (staffRef == null || appUserId == null || appClientId == null || appClientId.isBlank()) {
            return Outcome.fail(403, "NOT_STAFF", "Only a staff login can be deleted here.");
        }
        AppUser user = users.findByIdAndClientId(appUserId, appClientId).filter(u -> !u.isDeleteFlag()).orElse(null);
        if (user == null || !staffRef.equals(user.getUserId())) {
            return Outcome.fail(404, "NOT_FOUND", "Account not found.");
        }
        SignUp login = logins.findByClientId(staffRef).filter(s -> !Boolean.TRUE.equals(s.getDeleted())).orElse(null);
        if (login == null) return Outcome.fail(404, "NOT_FOUND", "Account not found.");
        if (password == null || password.isBlank() || !PasswordUtil.matches(password, login.getPassword())) {
            return Outcome.fail(403, "BAD_PASSWORD", "The password you entered is not correct.");
        }
        if ("superadmin".equalsIgnoreCase(user.getRole()) && !anotherSuperAdmin(appClientId, appUserId)) {
            return Outcome.fail(409, "LAST_SUPERADMIN",
                    "You are the church's only SuperAdmin. Ask the church account to give another person the "
                            + "SuperAdmin role first, or request deletion of the whole church account.");
        }

        String toEmail = user.getEmail();
        String name = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
        String username = login.getUsername();

        // 1. The login.
        login.setDeleted(true);
        login.setActive(false);
        login.setPassword("");
        login.setUsername("deleted-" + login.getId() + "-" + hex(4));
        login.setClientId(login.getClientId() + "#deleted-" + login.getId());
        login.setLinkGroup(null);
        login.setRemember(null);
        login.setUpdated(new Date());
        logins.save(login);
        rememberMe.revokeAllFor(login.getId());

        // 2. The staff record: off, contact details erased, name kept for history.
        user.setDeleteFlag(true);
        user.setEnabled(false);
        user.setLinkGroup(null);
        user.setEmail("deleted-" + user.getId() + "@deleted.invalid");
        user.setPhone(null);
        user.setAddress1(null); user.setAddress2(null); user.setCity(null);
        user.setState(null); user.setCountry(null); user.setPinCode(null);
        user.setPrivileges(null);
        user.setInviteToken(null);
        users.save(user);

        // 3. Everything that exists only for this login.
        permissions.findByAppUserId(appUserId).ifPresent(permissions::delete);
        tokens.findByAppClientIdAndAppUserIdOrderByCreatedAtDesc(appClientId, appUserId).forEach(t -> {
            if (t.getRevokedAt() == null) { t.setRevokedAt(LocalDateTime.now()); t.setRevokedBy("account-deleted"); tokens.save(t); }
        });
        for (PushSubscription ps : pushSubs.findByUserKeyAndActiveTrue(staffRef)) {
            ps.setActive(false);
            pushSubs.save(ps);
        }
        consents.deleteAll(consents.findByAppClientIdAndLoginId(appClientId, login.getId()));
        if (username != null) {
            favorites.findByClientIdAndUserRefOrderBySortOrderAscIdAsc(appClientId, "user:" + username).forEach(favorites::delete);
            dashboardPrefs.findByUsernameAndClientId(username, appClientId).ifPresent(dashboardPrefs::delete);
            tipState.findByClientIdAndUsername(appClientId, username).forEach(tipState::delete);
        }

        // 4. Tell the church owner (bell on /viewusers) — never blocks.
        notifications.record(appClientId, PublicSubmissionNotificationService.Type.ACCOUNT_DELETION,
                "Staff login deleted",
                (name.isBlank() ? "A staff member" : name) + " deleted their own staff login (" + user.getRole()
                        + "). Records they entered keep their name.",
                user.getId() == null ? null : user.getId().longValue());

        log.info("Staff login deleted by its owner: signup id={} app_user id={} church={}", login.getId(), appUserId, appClientId);
        return new Outcome(true, 200, null, null, toEmail, name);
    }

    /** Confirmation to the address the login had. Failures only logged. */
    public void sendConfirmation(String toEmail, String name, String appClientId) {
        if (toEmail == null || toEmail.isBlank()) return;
        try {
            String safe = name == null ? "" : name.replace("<", "&lt;").replace(">", "&gt;");
            String html = "<p>Hello " + safe + ",</p>"
                    + "<p>Your ChurchGeniusPro staff login has been deleted as you requested. You can no longer "
                    + "sign in with it, and every device it was remembered on has been signed out.</p>"
                    + "<p>Records you entered for your church (for example gifts or expenses) stay in the church's "
                    + "history with your name, because the church needs them for its accounts.</p>"
                    + "<p>If you did not request this, contact your church immediately.</p>";
            email.sendAccountEmail(toEmail, "Your ChurchGeniusPro staff login has been deleted", html, appClientId);
        } catch (Exception e) {
            log.warn("Staff-deletion confirmation email failed for {}: {}", appClientId, e.getClass().getSimpleName());
        }
    }

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
