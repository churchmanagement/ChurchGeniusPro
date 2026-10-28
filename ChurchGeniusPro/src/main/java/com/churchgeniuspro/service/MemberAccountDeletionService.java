package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.MemberMessage;
import com.churchgeniuspro.hibernate.PushSubscription;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.DashboardPreferenceRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.MemberBlockRepository;
import com.churchgeniuspro.repository.MemberMessageRepository;
import com.churchgeniuspro.repository.MemberPreferenceRepository;
import com.churchgeniuspro.repository.PushSubscriptionRepository;
import com.churchgeniuspro.repository.TrialTipStateRepository;
import com.churchgeniuspro.repository.UserFavoriteRepository;
import com.churchgeniuspro.util.PasswordUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;

/**
 * Member self-service account deletion (mobile Phase 2, M4 — Apple 5.1.1(v) and the
 * Google Play account-deletion policy).
 *
 * <h3>What is deleted — the member's LOGIN and everything that exists only for it</h3>
 * <ul>
 *   <li>{@code signup}: marked deleted + inactive, password hash erased, username
 *       rewritten to {@code deleted-<id>-<hex>} (so the address can register again
 *       later), unlinked from any account link-group.</li>
 *   <li>Every remember-me credential (all devices), every push subscription of the
 *       login, member preferences, favourites, dashboard layout, trial-tip state,
 *       and the blocks the member placed or was the subject of.</li>
 *   <li>Members Chat: messages they SENT keep their text in the other person's inbox
 *       but the sender name becomes "Former member"; their own inbox and sent views
 *       are marked deleted on their side.</li>
 * </ul>
 *
 * <h3>What is kept — the CHURCH's records</h3>
 * The {@code family_member} row and everything under it (giving, pledges, attendance,
 * statements, certificates) is the church's membership record, which it is required
 * to keep for its own accounting and tax statements. It is not deleted, not flagged,
 * and not anonymised; only {@code portal_account_deleted_at} is stamped so staff can
 * see the member no longer has a login. Removing the member from the roster remains
 * a staff action. The deletion page says exactly this.
 *
 * <p>Refused for Kids-portal (child), temporary-access and NTAG sessions, and for a
 * staff login that merely has a linked member: only a real Member session can delete
 * its own account.
 */
@Service
public class MemberAccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(MemberAccountDeletionService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final String FORMER_MEMBER = "Former member";

    private final LoginRepository              logins;
    private final FamilyMemberRepository       members;
    private final MemberMessageRepository      messages;
    private final MemberBlockRepository        blocks;
    private final PushSubscriptionRepository   pushSubs;
    private final RememberMeService            rememberMe;
    private final MemberPreferenceRepository   memberPrefs;
    private final UserFavoriteRepository       favorites;
    private final DashboardPreferenceRepository dashboardPrefs;
    private final TrialTipStateRepository      tipState;
    private final PublicSubmissionNotificationService notifications;
    private final EmailService                 email;

    public MemberAccountDeletionService(LoginRepository logins, FamilyMemberRepository members,
                                        MemberMessageRepository messages, MemberBlockRepository blocks,
                                        PushSubscriptionRepository pushSubs, RememberMeService rememberMe,
                                        MemberPreferenceRepository memberPrefs, UserFavoriteRepository favorites,
                                        DashboardPreferenceRepository dashboardPrefs, TrialTipStateRepository tipState,
                                        PublicSubmissionNotificationService notifications, EmailService email) {
        this.logins = logins; this.members = members; this.messages = messages; this.blocks = blocks;
        this.pushSubs = pushSubs; this.rememberMe = rememberMe; this.memberPrefs = memberPrefs;
        this.favorites = favorites; this.dashboardPrefs = dashboardPrefs; this.tipState = tipState;
        this.notifications = notifications; this.email = email;
    }

    public record Outcome(boolean ok, int status, String error, String memberEmail, String memberName) {
        public static Outcome fail(int status, String error) { return new Outcome(false, status, error, null, null); }
    }

    /**
     * Deletes the login identified by {@code memberRef} (the session's clientId) after
     * checking {@code password}. {@code memberId} / {@code appClientId} come from the
     * session too; the family_member row must belong to that church.
     */
    @Transactional
    public Outcome deleteOwnAccount(String memberRef, Integer memberId, String appClientId,
                                    String username, String password) {
        if (memberRef == null || !memberRef.toUpperCase().startsWith("MBR") || memberId == null || appClientId == null) {
            return Outcome.fail(403, "Only a member account can be deleted here.");
        }
        SignUp login = logins.findByClientId(memberRef)
                .filter(s -> !Boolean.TRUE.equals(s.getDeleted()))
                .orElse(null);
        if (login == null) return Outcome.fail(404, "Account not found.");
        if (password == null || password.isBlank() || !PasswordUtil.matches(password, login.getPassword())) {
            return Outcome.fail(403, "The password you entered is not correct.");
        }
        FamilyMember member = members.findByIdAndTenant(memberId, appClientId).orElse(null);
        if (member == null) return Outcome.fail(404, "Member record not found.");

        Instant now = Instant.now();
        String memberEmail = member.getEmail();
        String memberName  = MemberSafetyService.displayName(member);

        // 1. The login itself.
        login.setDeleted(true);
        login.setActive(false);
        login.setPassword("");
        login.setUsername("deleted-" + login.getId() + "-" + hex(4));
        // Release the member reference (audit 2026-10-28, H2): the client-ID is the login's
        // key, and keeping it on the deleted row meant a later re-registration of the same
        // member inserted a second row with the same key (partial unique index in V23).
        login.setClientId(login.getClientId() + "#deleted-" + login.getId());
        login.setLinkGroup(null);
        login.setRemember(null);
        login.setUpdated(new Date());
        logins.save(login);

        // 2. Everything that exists only for the login.
        rememberMe.revokeAllFor(login.getId());
        for (PushSubscription ps : pushSubs.findByUserKeyAndActiveTrue(memberRef)) {
            ps.setActive(false);
            pushSubs.save(ps);
        }
        memberPrefs.findByMemberId(memberId).ifPresent(memberPrefs::delete);
        favorites.findByClientIdAndUserRefOrderBySortOrderAscIdAsc(appClientId, "member:" + memberId)
                 .forEach(favorites::delete);
        if (username != null) {
            dashboardPrefs.findByUsernameAndClientId(username, appClientId).ifPresent(dashboardPrefs::delete);
            tipState.findByClientIdAndUsername(appClientId, username).forEach(tipState::delete);
        }
        blocks.deleteAllInvolving(appClientId, memberId);

        // 3. Members Chat: their name leaves other people's inboxes; their own views go.
        List<MemberMessage> sent = messages.findSent(memberId);
        for (MemberMessage m : sent) {
            m.setSenderName(FORMER_MEMBER);
            m.setDeletedBySender(true);
            messages.save(m);
        }
        for (MemberMessage m : messages.findInbox(memberId)) {
            m.setDeletedByRecipient(true);
            messages.save(m);
        }

        // 4. The church's record is KEPT; stamp only.
        member.setPortalAccountDeletedAt(now);
        members.save(member);

        // 5. Tell the church (bell on /viewusers) — never blocks the deletion.
        notifications.record(appClientId, PublicSubmissionNotificationService.Type.ACCOUNT_DELETION,
                "Member portal account deleted",
                memberName + " deleted their member portal login. Their membership record, giving history and "
                        + "statements are unchanged; remove them from the roster only if the church decides to.",
                member.getId() == null ? null : member.getId().longValue());

        log.info("Member account deleted: signup id={} member id={} church={}", login.getId(), memberId, appClientId);
        return new Outcome(true, 200, null, memberEmail, memberName);
    }

    /** Confirmation to the member. Account mail (never blocked for trial tenants); failures only logged. */
    public void sendConfirmation(String toEmail, String name, String appClientId) {
        if (toEmail == null || toEmail.isBlank()) return;
        try {
            String safeName = name == null ? "" : name.replace("<", "&lt;").replace(">", "&gt;");
            String html = "<p>Hello " + safeName + ",</p>"
                    + "<p>Your ChurchGeniusPro member portal account has been deleted as you requested. "
                    + "You can no longer sign in with it.</p>"
                    + "<p>Your church keeps its own membership and giving records, which it needs for its "
                    + "accounting and year-end statements. If you would also like to be removed from the "
                    + "church's records, please contact the church office directly.</p>"
                    + "<p>If you did not request this, contact your church immediately.</p>";
            email.sendAccountEmail(toEmail, "Your member portal account has been deleted", html, appClientId);
        } catch (Exception e) {
            log.warn("Account-deletion confirmation email failed for {}: {}", appClientId, e.getMessage());
        }
    }

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
