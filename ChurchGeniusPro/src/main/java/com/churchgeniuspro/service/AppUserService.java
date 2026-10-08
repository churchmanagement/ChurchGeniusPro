package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.hibernate.UserPermissions;
import com.churchgeniuspro.model.AppUserBO;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.UserPermissionsRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service layer for {@link AppUser} CRUD and email notifications.
 */
@Service
public class AppUserService {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(AppUserService.class);

    private final AppUserRepository    userRepository;
    private final LoginRepository      loginRepository;
    private final EmailService         emailService;
    private final WhatsAppSenderService      whatsAppSender;
    private final UserPermissionsRepository  permissionsRepository;

    @Value("${app.base-url}")
    private String baseUrl;

    /**
     * The only roles a staff user may hold. {@code app_user.role} is copied verbatim
     * into the session, and four service-admin controllers used to trust
     * {@code role == "ServiceAdmin"} — so an unvalidated role string was a privilege
     * escalation, not just a typo.
     */
    public static final java.util.Set<String> STAFF_ROLES =
            java.util.Set.of("SuperAdmin", "Admin", "Accountant", "User", "Limited");

    private static void requireValidRole(String role) {
        if (role == null || !STAFF_ROLES.contains(role.trim())) {
            throw new IllegalArgumentException("Invalid role.");
        }
    }

    public AppUserService(AppUserRepository       userRepository,
                          LoginRepository         loginRepository,
                          EmailService            emailService,
                          WhatsAppSenderService   whatsAppSender,
                          UserPermissionsRepository permissionsRepository) {
        this.userRepository       = userRepository;
        this.loginRepository      = loginRepository;
        this.emailService         = emailService;
        this.whatsAppSender       = whatsAppSender;
        this.permissionsRepository = permissionsRepository;
    }

    // ── List ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAll(String clientId) {
        // Always tenant-scoped. The old "no clientId → list everything" fallback returned
        // every church's staff (with privileges JSON and signup ids) to any staff session.
        if (clientId == null || clientId.isBlank()) return List.of();
        List<AppUser> users =
                userRepository.findByClientIdAndDeleteFlagFalseOrderByLastNameAscFirstNameAsc(clientId);
        Set<String> activeLoginIds = batchActiveLoginIds(users);
        return users.stream()
                .map(u -> toMap(u, activeLoginIds))
                .collect(Collectors.toList());
    }

    // ── Create ────────────────────────────────────────────────────────────

    @Transactional
    public AppUser create(AppUserBO bo) {
        requireValidRole(bo.getRole());
        // Uniqueness is per (email + role + clientId) — same email+role allowed across orgs
        String clientId = (bo.getClientId() != null && !bo.getClientId().isBlank())
                ? bo.getClientId().trim() : null;
        if (userRepository.existsByEmailRoleClientIdAndNotDeleted(bo.getEmail(), bo.getRole(), clientId)) {
            throw new IllegalArgumentException(
                    "A user with this email and role already exists in this organization.");
        }
        AppUser u = new AppUser();
        apply(u, bo);
        return userRepository.save(u);
    }

    // ── Update ────────────────────────────────────────────────────────────

    @Transactional
    public AppUser update(Integer id, String clientId, AppUserBO bo) {
        requireValidRole(bo.getRole());
        AppUser u = findOrThrow(id, clientId);
        // The record is known to belong to clientId; the duplicate check is scoped to it.
        if (userRepository.existsByEmailRoleClientIdAndNotDeletedAndIdNot(
                bo.getEmail(), bo.getRole(), clientId, id)) {
            throw new IllegalArgumentException(
                    "Another user with this email and role already exists in this organization.");
        }
        apply(u, bo);
        return userRepository.save(u);
    }

    // ── Soft-Delete ───────────────────────────────────────────────────────

    @Transactional
    public void delete(Integer id, String clientId) {
        AppUser u = findOrThrow(id, clientId);
        u.setDeleteFlag(true);
        userRepository.save(u);
    }

    // ── Update Privileges ─────────────────────────────────────────────────

    @Transactional
    public void updatePrivileges(Integer id, String clientId, String privilegesJson) {
        // Verify the user exists in THIS tenant
        findOrThrow(id, clientId);

        String json = (privilegesJson == null || privilegesJson.isBlank()) ? null : privilegesJson.trim();

        // Upsert into user_permissions table (the authoritative store for granular permissions)
        UserPermissions row = permissionsRepository.findByAppUserId(id)
                .orElseGet(() -> {
                    UserPermissions np = new UserPermissions();
                    np.setClientId(clientId);
                    np.setAppUserId(id);
                    return np;
                });
        row.setPermissions(json);
        permissionsRepository.save(row);
    }

    // ── Enable / Disable toggle ───────────────────────────────────────────

    @Transactional
    public AppUser toggle(Integer id, String clientId) {
        AppUser u = findOrThrow(id, clientId);
        u.setEnabled(!u.isEnabled());
        return userRepository.save(u);
    }

    // ── Link / Unlink ─────────────────────────────────────────────────────

    /**
     * Groups the given app_user IDs into a single link-group so they can switch
     * roles without re-entering credentials.
     *
     * <p>Rules enforced:
     * <ul>
     *   <li>At least 2 users must be selected.</li>
     *   <li>No two users may share the same role.</li>
     *   <li>Every selected user must have an active login in the signup table.</li>
     * </ul>
     *
     * <p>Assigning a new link-group UUID replaces any previous groupings for the
     * selected users.  Former group-mates that are no longer included have their
     * {@code link_group} cleared if they would be left alone in a group of one.
     */
    @Transactional
    public void linkUsers(List<Integer> userIds, String clientId) {
        if (userIds == null || userIds.size() < 2) {
            throw new IllegalArgumentException("Select at least 2 users to link.");
        }

        List<AppUser> users = userRepository.findAllById(userIds);
        requireAllInTenant(users, clientId);
        if (users.size() < 2) {
            throw new IllegalArgumentException("Could not find the selected users.");
        }

        // Validate: no two users may share the same role
        long distinctRoles = users.stream().map(AppUser::getRole).distinct().count();
        if (distinctRoles != users.size()) {
            throw new IllegalArgumentException("Cannot link users with the same role.");
        }

        // Validate: all users must share the same email address
        Set<String> staffEmails = users.stream()
                .filter(u -> u.getEmail() != null && !u.getEmail().isBlank())
                .map(u -> u.getEmail().trim().toLowerCase())
                .collect(Collectors.toSet());
        if (staffEmails.size() > 1) {
            throw new IllegalArgumentException(
                "All accounts must share the same email address to be linked.");
        }

        // Validate: every user must have an active login (batch fetch — one query)
        Set<String> activeLoginIds = batchActiveLoginIds(users);
        for (AppUser u : users) {
            if (!activeLoginIds.contains(u.getUserId())) {
                throw new IllegalArgumentException(
                        "\"" + u.getFirstName() + " " + u.getLastName() +
                        "\" does not have an active login and cannot be linked.");
            }
        }

        // Collect old link-groups that will be partially vacated
        Set<String> oldGroups = users.stream()
                .map(AppUser::getLinkGroup)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // Assign a fresh shared UUID to all selected users
        String newGroup = UUID.randomUUID().toString();
        users.forEach(u -> u.setLinkGroup(newGroup));
        userRepository.saveAll(users);

        // Clean up orphaned solo entries in former groups
        for (String oldGroup : oldGroups) {
            List<AppUser> remaining = userRepository.findByLinkGroupAndDeleteFlagFalse(oldGroup);
            if (remaining.size() == 1) {
                remaining.get(0).setLinkGroup(null);
                userRepository.save(remaining.get(0));
            }
        }
    }

    /**
     * Removes the given user from their link-group.
     * If only one user would remain in the group afterwards, that user's
     * {@code link_group} is also cleared (a group of one is meaningless).
     */
    @Transactional
    public void unlinkUser(Integer id, String clientId) {
        AppUser u = findOrThrow(id, clientId);
        String oldGroup = u.getLinkGroup();
        if (oldGroup == null) return;           // not currently linked — nothing to do

        u.setLinkGroup(null);

        // If only one user remains in the old group, clear their link_group too
        List<AppUser> remaining = userRepository.findByLinkGroupAndDeleteFlagFalse(oldGroup);
        if (remaining.size() == 1) {
            remaining.get(0).setLinkGroup(null);
            userRepository.saveAll(List.of(u, remaining.get(0)));
        } else {
            userRepository.save(u);
        }
    }

    /**
     * Returns all users sharing the same link-group as the given app_user ID.
     * Each entry includes whether the user has an active login (required for
     * the role-switcher dropdown to show only switchable accounts).
     *
     * <p>Returns an empty list when the user has no link-group.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getLinkedRoles(Integer appUserId) {
        AppUser user = userRepository.findById(appUserId).orElse(null);
        if (user == null || user.getLinkGroup() == null) return List.of();

        List<AppUser> linked = userRepository.findByLinkGroupAndDeleteFlagFalse(user.getLinkGroup());
        Set<String> activeLoginIds = batchActiveLoginIds(linked);
        return linked.stream()
                .map(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("appUserId",      u.getId());
                    m.put("role",           u.getRole());
                    m.put("firstName",      u.getFirstName());
                    m.put("lastName",       u.getLastName());
                    m.put("hasActiveLogin", activeLoginIds.contains(u.getUserId()));
                    return m;
                })
                .collect(Collectors.toList());
    }

    // ── Cross-type Link / Unlink (app_user + member signups) ─────────────

    /**
     * Links any combination of app_user rows and member signup rows into a single
     * shared link-group.  At least 2 total entries are required.
     *
     * <p>All selected accounts must share the same email address — this is the
     * identity invariant that proves they belong to the same person.
     *
     * <p>The same fresh UUID is stamped onto:
     * <ul>
     *   <li>{@code app_user.link_group} for every app_user ID supplied.</li>
     *   <li>{@code signup.link_group} for every member signup ID supplied.</li>
     * </ul>
     *
     * Former group-mates that are no longer included are cleaned up:
     * if only one account would be left alone in an old group, its link_group
     * is cleared so a "group of one" never lingers.
     */
    @Transactional
    public void linkWithMembers(java.util.List<Integer> appUserIds,
                                java.util.List<Integer> memberSignupIds,
                                LoginRepository loginRepo,
                                String clientId) {
        int total = (appUserIds == null ? 0 : appUserIds.size())
                  + (memberSignupIds == null ? 0 : memberSignupIds.size());
        if (total < 2) throw new IllegalArgumentException("Select at least 2 accounts to link.");

        List<AppUser>  users   = appUserIds != null && !appUserIds.isEmpty()
                ? userRepository.findAllById(appUserIds) : List.of();
        requireAllInTenant(users, clientId);
        List<com.churchgeniuspro.hibernate.SignUp> signups = memberSignupIds != null && !memberSignupIds.isEmpty()
                ? loginRepo.findAllById(memberSignupIds) : List.of();

        // ── Enforce same-email constraint ──────────────────────────────────
        // All accounts must share one common email address. For staff (AppUser),
        // the email is app_user.email. For member/church signups, the email is
        // signup.username (which is always the email address for member accounts).
        Set<String> emails = new java.util.HashSet<>();
        for (AppUser u : users) {
            if (u.getEmail() != null && !u.getEmail().isBlank()) {
                emails.add(u.getEmail().trim().toLowerCase());
            }
        }
        for (com.churchgeniuspro.hibernate.SignUp s : signups) {
            if (s.getUsername() != null && !s.getUsername().isBlank()) {
                emails.add(s.getUsername().trim().toLowerCase());
            }
        }
        if (emails.size() > 1) {
            throw new IllegalArgumentException(
                "All accounts must share the same email address to be linked. " +
                "Found different emails: " + emails);
        }
        if (emails.isEmpty()) {
            throw new IllegalArgumentException(
                "Cannot determine email addresses for the selected accounts.");
        }

        // Collect old link-groups for cleanup later
        Set<String> oldGroups = new java.util.HashSet<>();
        users.forEach(u -> { if (u.getLinkGroup() != null) oldGroups.add(u.getLinkGroup()); });
        signups.forEach(s -> { if (s.getLinkGroup() != null) oldGroups.add(s.getLinkGroup()); });

        // Assign fresh link-group to all
        String newGroup = UUID.randomUUID().toString();
        users.forEach(u -> u.setLinkGroup(newGroup));
        userRepository.saveAll(users);

        signups.forEach(s -> s.setLinkGroup(newGroup));
        loginRepo.saveAll(signups);

        // Cleanup orphaned solo members in former app_user link-groups
        for (String oldGroup : oldGroups) {
            List<AppUser> remainingUsers = userRepository.findByLinkGroupAndDeleteFlagFalse(oldGroup);
            List<com.churchgeniuspro.hibernate.SignUp> remainingSignups = loginRepo.findByLinkGroup(oldGroup);
            int remaining = remainingUsers.size() + remainingSignups.size();
            if (remaining == 1) {
                remainingUsers.forEach(u -> u.setLinkGroup(null));
                remainingSignups.forEach(s -> s.setLinkGroup(null));
                userRepository.saveAll(remainingUsers);
                loginRepo.saveAll(remainingSignups);
            }
        }
    }

    /**
     * Removes a member signup record from its link-group.
     * If only one account remains in the old group, it is also unlinked.
     */
    @Transactional
    public void unlinkMemberSignup(Integer signupId, LoginRepository loginRepo) {
        com.churchgeniuspro.hibernate.SignUp signup = loginRepo.findById(signupId)
                .orElseThrow(() -> new IllegalArgumentException("Member account not found: " + signupId));
        String oldGroup = signup.getLinkGroup();
        if (oldGroup == null) return;

        signup.setLinkGroup(null);
        loginRepo.save(signup);

        List<AppUser> remainingUsers = userRepository.findByLinkGroupAndDeleteFlagFalse(oldGroup);
        List<com.churchgeniuspro.hibernate.SignUp> remainingSignups = loginRepo.findByLinkGroup(oldGroup);
        int remaining = remainingUsers.size() + remainingSignups.size();
        if (remaining == 1) {
            remainingUsers.forEach(u -> u.setLinkGroup(null));
            remainingSignups.forEach(s -> s.setLinkGroup(null));
            userRepository.saveAll(remainingUsers);
            loginRepo.saveAll(remainingSignups);
        }
    }

    // ── Send Email ────────────────────────────────────────────────────────

    /**
     * Sends a signup invitation email to the user.
     *
     * <p>The email contains a personalised greeting and a "Create My Account"
     * button linking to {@code {app.base-url}/signup?clientId=<encrypted>} so the
     * recipient can create their login credentials.
     *
     * <p>The link carries a random single-use invite token stored on the user row
     * ({@code app_user.invite_token}); {@code SignupController} looks it up and
     * clears it when the signup completes. Re-sending replaces the token.
     *
     * <h2>Why this is account mail</h2>
     * An invitation is addressed to the person completing the sign-up, not to the
     * congregation, and nothing else can get them an account — so it is sent with
     * {@link EmailService#sendAccountEmailOrThrow} rather than as ordinary
     * organization mail. Sent as org mail it was being dropped silently by any of
     * three guards that exist to protect the congregation from a church that is
     * still evaluating the product: the Trial/demo plan block, the recipient's
     * unsubscribe preference, and the monthly email allowance. Each returns
     * quietly, so the admin saw "Email sent successfully" and the invitee received
     * nothing. This is the same exemption {@code sendSignupInvitation} already
     * applies to the church registrant's own invitation.
     *
     * <p>Failures are thrown, so the button reports the truth: the caller turns
     * them into the message the admin sees.
     */
    /** Controller entry point — the invite may only be (re)sent for a user of the caller's church. */
    public void sendEmail(Integer id, String clientId) throws Exception {
        findOrThrow(id, clientId);
        sendEmail(id);
    }

    /**
     * Internal entry point used by tenant provisioning (TrialRegistrationService),
     * where the caller has just created the user and holds no tenant session.
     */
    public void sendEmail(Integer id) throws Exception {
        AppUser u          = findOrThrow(id);
        // A fresh random invite token per send: single-use, and re-sending replaces it.
        u.setInviteToken(PublicLinkResolver.newToken());
        userRepository.save(u);
        String link        = baseUrl + "/signup?clientId=" + u.getInviteToken();

        if (u.getEmail() == null || u.getEmail().isBlank()) {
            throw new IllegalArgumentException(
                    "This account has no email address on file. Add one before inviting.");
        }

        // ── Email ──────────────────────────────────────────────────────────
        String churchName = emailService.getChurchName(u.getClientId());
        String html = buildSignupInvitationEmail(u.getFirstName(), link, churchName);
        emailService.sendAccountEmailOrThrow(
                u.getEmail(),
                "Complete Your " + churchName + " Sign-Up",
                html,
                u.getClientId());

        // ── WhatsApp ───────────────────────────────────────────────────────
        // Best-effort, and deliberately after the email: WhatsApp is a convenience
        // copy of a link that has already been delivered. Letting it throw here
        // would report a failed invitation for a message that did arrive, and send
        // the admin chasing an email that is already in the invitee's inbox.
        if (u.getPhone() != null && !u.getPhone().isBlank()) {
            try {
                String whatsAppMsg = buildSignupWhatsAppMessage(u.getFirstName(), link, churchName);
                whatsAppSender.sendWhatsAppToPhone(u.getPhone(), whatsAppMsg, u.getClientId());
            } catch (Exception ex) {
                LOG.warn("Invitation WhatsApp to {} failed (email was sent) - {}",
                         u.getPhone(), ex.getMessage());
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private void apply(AppUser u, AppUserBO bo) {
        u.setFirstName(bo.getFirstName().trim());
        u.setLastName(bo.getLastName().trim());
        u.setEmail(bo.getEmail().trim().toLowerCase());
        u.setPhone(bo.getPhone());
        u.setAddress1(bo.getAddress1());
        u.setAddress2(bo.getAddress2());
        u.setCity(bo.getCity());
        u.setState(bo.getState());
        u.setCountry(
                (bo.getCountry() != null && !bo.getCountry().isBlank())
                ? bo.getCountry() : "USA");
        u.setPinCode(bo.getPinCode());
        u.setRole(bo.getRole());
        // Only set clientId on create (non-null BO value); never overwrite on update
        if (bo.getClientId() != null && !bo.getClientId().isBlank() && u.getClientId() == null) {
            u.setClientId(bo.getClientId().trim());
        }
        // Privileges may be null (full access) or a JSON string
        if (bo.getPrivileges() != null) {
            u.setPrivileges(bo.getPrivileges().isBlank() ? null : bo.getPrivileges().trim());
        }
    }

    /** Fetches active-login user IDs for the given users in a single batch query. */
    private Set<String> batchActiveLoginIds(List<AppUser> users) {
        if (users.isEmpty()) return Set.of();
        List<String> userIds = users.stream().map(AppUser::getUserId).collect(Collectors.toList());
        return loginRepository.findActiveSignupsByUserIds(userIds).stream()
                .map(SignUp::getClientId)
                .collect(Collectors.toSet());
    }

    private Map<String, Object> toMap(AppUser u, Set<String> activeLoginIds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",             u.getId());
        m.put("userId",         u.getUserId());
        m.put("firstName",      u.getFirstName());
        m.put("lastName",       u.getLastName());
        m.put("email",          u.getEmail());
        m.put("phone",          u.getPhone());
        m.put("address1",       u.getAddress1());
        m.put("address2",       u.getAddress2());
        m.put("city",           u.getCity());
        m.put("state",          u.getState());
        m.put("country",        u.getCountry());
        m.put("pinCode",        u.getPinCode());
        m.put("clientId",       u.getClientId());
        m.put("role",           u.getRole());
        m.put("enabled",        u.isEnabled());
        m.put("linkGroup",      u.getLinkGroup());
        m.put("hasActiveLogin", activeLoginIds.contains(u.getUserId()));
        m.put("privileges",     u.getPrivileges());
        return m;
    }

    private AppUser findOrThrow(Integer id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
    }

    /**
     * Loads a staff user belonging to {@code clientId}, or throws "not found" — the
     * same answer whether the id is unknown or belongs to another church, so the
     * endpoint is not an existence oracle. Rows with a null client_id (pre-tenancy
     * legacy) are treated as not found: they must be back-filled, not assumed.
     */
    public AppUser findOrThrow(Integer id, String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("User not found: " + id);
        }
        return userRepository.findByIdAndClientId(id, clientId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
    }

    private static void requireAllInTenant(List<AppUser> users, String clientId) {
        for (AppUser u : users) {
            if (clientId == null || u.getClientId() == null || !clientId.equals(u.getClientId())) {
                throw new IllegalArgumentException("Could not find the selected users.");
            }
        }
    }

    // ── Email builder ─────────────────────────────────────────────────────────

    private String buildSignupInvitationEmail(String firstName, String link, String churchName) {
        String safeName  = escapeHtml(firstName);
        String safeChurch = escapeHtml(churchName);
        return "<!DOCTYPE html><html lang='en'><head>"
             + "<meta charset='UTF-8'/><meta name='viewport' content='width=device-width,initial-scale=1.0'/>"
             + "<title>Complete Your Sign-Up</title></head>"
             + "<body style='margin:0;padding:0;background-color:#f5f6fa;"
             +   "font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;'>"
             + "<table width='100%' cellpadding='0' cellspacing='0' style='background-color:#f5f6fa;padding:40px 20px;'>"
             + "<tr><td align='center'>"
             + "<table width='100%' cellpadding='0' cellspacing='0'"
             +   " style='max-width:520px;background:#ffffff;border-radius:16px;"
             +          "box-shadow:0 4px 24px rgba(0,0,0,0.08);overflow:hidden;'>"
             // Header
             + "<tr><td style='background-color:#673147;padding:32px 40px;text-align:center;'>"
             +   "<p style='margin:0;font-size:22px;font-weight:700;color:#ffffff;letter-spacing:0.5px;'>" + safeChurch + "</p>"
             +   "<p style='margin:8px 0 0;font-size:13px;color:rgba(255,255,255,0.75);'>Account Setup Invitation</p>"
             + "</td></tr>"
             // Body
             + "<tr><td style='padding:36px 40px;'>"
             +   "<p style='margin:0 0 16px;font-size:16px;color:#1a1a2e;font-weight:600;'>Hello " + safeName + ",</p>"
             +   "<p style='margin:0 0 16px;font-size:14px;color:#555;line-height:1.7;'>"
             +     "You have been added as a user in <strong>" + safeChurch + "</strong>. "
             +     "You can now create your login account.</p>"
             +   "<p style='margin:0 0 28px;font-size:14px;color:#555;line-height:1.7;'>"
             +     "Click the button below to set up your username and password.</p>"
             +   "<table cellpadding='0' cellspacing='0' style='margin:0 auto 28px;'><tr>"
             +     "<td style='background-color:#673147;border-radius:8px;'>"
             +       "<a href='" + link + "'"
             +          " style='display:inline-block;padding:14px 36px;font-size:15px;"
             +                 "font-weight:600;color:#ffffff;text-decoration:none;'>Create Your Account</a>"
             +     "</td></tr></table>"
             +   "<p style='margin:0 0 8px;font-size:12px;color:#aaa;'>If the button doesn't work, copy this link:</p>"
             +   "<p style='margin:0;font-size:12px;word-break:break-all;'>"
             +     "<a href='" + link + "' style='color:#3a7bd5;'>" + link + "</a></p>"
             + "</td></tr>"
             // Footer
             + "<tr><td style='background-color:#f8f9ff;padding:20px 40px;"
             +             "border-top:1px solid #e8eaf6;text-align:center;'>"
             +   "<p style='margin:0;font-size:12px;color:#aaa;line-height:1.6;'>"
             +     "This email was sent by <strong>" + safeChurch + "</strong>.<br/>"
             +     "If you did not expect this, please ignore this email.</p>"
             + "</td></tr>"
             + "</table></td></tr></table></body></html>";
    }

    private String escapeHtml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String buildSignupWhatsAppMessage(String firstName, String link, String churchName) {
        return "Hello " + (firstName != null ? firstName : "") + ",\n\n"
             + "You have been added as a user in " + churchName + ".\n\n"
             + "Please use the link below to create your login account:\n"
             + link + "\n\n"
             + "If you did not expect this message, please ignore it.";
    }

}
