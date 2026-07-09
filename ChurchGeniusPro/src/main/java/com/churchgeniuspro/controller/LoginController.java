package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.hibernate.UserPermissions;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.UserPermissionsRepository;
import com.churchgeniuspro.util.PasswordUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Controller
public class LoginController {

    private static final Logger LOG = LoggerFactory.getLogger(LoginController.class);
    private static final DateTimeFormatter LOG_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String COOKIE_NAME    = "rememberToken";
    private static final int    COOKIE_MAX_AGE = 30 * 24 * 60 * 60; // 30 days in seconds

    /**
     * Mirrors {@code server.servlet.session.cookie.secure} so the remember-me
     * cookie also gets the Secure flag in production (where HTTPS is used).
     * Defaults to {@code false} for local HTTP development.
     */
    @Value("${server.servlet.session.cookie.secure:false}")
    private boolean cookieSecure;

    private final LoginRepository              loginRepository;
    private final AppUserRepository            appUserRepository;
    private final ChurchRegistrationRepository churchRegistrationRepository;
    private final ServiceClientRepository      serviceClientRepository;
    private final UserPermissionsRepository    userPermissionsRepository;
    private final FamilyMemberRepository       familyMemberRepository;

    public LoginController(LoginRepository loginRepository,
                           AppUserRepository appUserRepository,
                           ChurchRegistrationRepository churchRegistrationRepository,
                           ServiceClientRepository serviceClientRepository,
                           UserPermissionsRepository userPermissionsRepository,
                           FamilyMemberRepository familyMemberRepository) {
        this.loginRepository              = loginRepository;
        this.appUserRepository            = appUserRepository;
        this.churchRegistrationRepository = churchRegistrationRepository;
        this.serviceClientRepository      = serviceClientRepository;
        this.userPermissionsRepository    = userPermissionsRepository;
        this.familyMemberRepository       = familyMemberRepository;
    }

    /** Serves the login page at the root URL (canonical). */
    @GetMapping("/")
    public String rootPage() {
        return "forward:/login.html";
    }

    /**
     * Redirects the legacy /login GET URL to the canonical root URL (/).
     * Keeps old bookmarks and direct links working.
     * NOTE: POST /login is still the login API endpoint and is unaffected.
     */
    @GetMapping("/login")
    public String loginRedirect() {
        return "redirect:/";
    }

    /**
     * Validates credentials and optionally sets a persistent remember-me cookie.
     *
     * <p>Request body: {@code { "username": "...", "password": "...", "rememberMe": true }}
     * <p>Success:      {@code { "status": "success", "clientId": "CGP-00001", ... }}
     * <p>Failure:      {@code { "status": "error", "message": "..." }} with HTTP 401
     */
    @ResponseBody
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, Object> body,
                                                      HttpServletRequest request,
                                                      HttpServletResponse response) {
        String  username   = body.get("username")   instanceof String s ? s.trim() : "";
        String  password   = body.get("password")   instanceof String s ? s       : "";
        boolean rememberMe = Boolean.TRUE.equals(body.get("rememberMe"));

        Map<String, Object> res = new HashMap<>();

        if (username.isBlank() || password.isBlank()) {
            res.put("status",  "error");
            res.put("message", "Username and password are required.");
            return ResponseEntity.status(400).body(res);
        }

        SignUp user = loginRepository.findByUsernameAndDeletedFalse(username).orElse(null);

        if (user == null) {
            // No row in signup with that username (or deleted != false).
            // Logged so demo-credential troubleshooting from
            // /serviceadminhome → Test Data → credentials table is obvious
            // in the server log.
            LOG.warn("Login 401 — no active signup row found for username='{}'", username);
            res.put("status",  "error");
            res.put("message", "Invalid username or password.");
            return ResponseEntity.status(401).body(res);
        }
        // ── Password verification (BCrypt-aware) ─────────────────────────────
        // PasswordUtil.matches() handles both BCrypt hashes ($2a$...) and
        // legacy plaintext rows so existing accounts keep working during migration.
        if (!PasswordUtil.matches(password, user.getPassword())) {
            LOG.warn("Login 401 — password mismatch for username='{}' " +
                     "(submitted length={}, stored length={})",
                     username, password.length(),
                     user.getPassword() != null ? user.getPassword().length() : -1);
            res.put("status",  "error");
            res.put("message", "Invalid username or password.");
            return ResponseEntity.status(401).body(res);
        }
        // ── Transparent migration: re-hash plaintext passwords on first login ─
        // If the stored value is still plaintext (no BCrypt prefix), hash it now
        // so the database is progressively migrated without any downtime or bulk
        // script. The user never notices — they typed the correct password.
        if (!PasswordUtil.isBCrypt(user.getPassword())) {
            try {
                user.setPassword(PasswordUtil.encode(password));
                loginRepository.save(user);
                LOG.info("Migrated plaintext password to BCrypt for username='{}'", username);
            } catch (Exception ex) {
                LOG.warn("Could not migrate password for username='{}': {}", username, ex.getMessage());
            }
        }

        if (Boolean.FALSE.equals(user.getActive())) {
            res.put("status",  "error");
            res.put("message", "Your account is inactive. Please contact your administrator.");
            return ResponseEntity.status(403).body(res);
        }

        boolean isChurch  = Boolean.TRUE.equals(user.getChurch());
        // Member accounts (clientId starts with "MBR") have no AppUser row;
        // skip the subscription/active query that would always return 0 for them.
        String clientIdForCheck = user.getClientId() != null ? user.getClientId() : "";
        boolean isMemberAccount = clientIdForCheck.toUpperCase().startsWith("MBR");
        if (!isMemberAccount && !isChurch) {
            // For staff accounts (USR…): explicitly check app_user.enabled and delete_flag
            // before running the heavier subscription query, so the error message is clear.
            AppUser staffUser = appUserRepository.findByUserIdAndDeleteFlagFalse(clientIdForCheck).orElse(null);
            if (staffUser == null) {
                // No app_user row yet (invite not yet accepted) OR already hard-deleted.
                // Fall through to countValidNonChurchLogin which will also return 0.
            } else if (!staffUser.isEnabled()) {
                res.put("status",  "error");
                res.put("message", "Your account has been disabled. Please contact your administrator.");
                return ResponseEntity.status(403).body(res);
            } else if (staffUser.isDeleteFlag()) {
                res.put("status",  "error");
                res.put("message", "Your account has been removed. Please contact your administrator.");
                return ResponseEntity.status(403).body(res);
            }
        }
        if (!isMemberAccount) {
            int validCount = isChurch
                    ? loginRepository.countValidChurchLogin(username)
                    : loginRepository.countValidNonChurchLogin(username);
            if (validCount == 0) {
                res.put("status",  "error");
                res.put("message", "Account access is restricted. Please contact your administrator.");
                return ResponseEntity.status(403).body(res);
            }
        }

        // ── Build session ──────────────────────────────────────────────────
        HttpSession existing = request.getSession(false);
        if (existing != null) existing.invalidate();
        HttpSession session = request.getSession(true);

        buildResponse(res, user, isChurch, session);

        // ── Remember-me: persist token to DB and set cookie ───────────────
        if (rememberMe) {
            String token = UUID.randomUUID().toString();
            user.setRemember(token);
            loginRepository.save(user);
            addRememberCookie(response, token);
        } else {
            // Clear any existing token if user unchecked the box
            if (user.getRemember() != null) {
                user.setRemember(null);
                loginRepository.save(user);
            }
            clearRememberCookie(response);
        }

        return ResponseEntity.ok(res);
    }

    /**
     * Auto-login endpoint: reads the {@code rememberToken} cookie, validates it against
     * the database, rebuilds the session, and returns the same payload as a normal login.
     *
     * <p>Called by login.html on page load when the cookie is present.
     */
    @ResponseBody
    @GetMapping("/api/auth/check-remember")
    public ResponseEntity<Map<String, Object>> checkRemember(HttpServletRequest request,
                                                              HttpServletResponse response) {
        Map<String, Object> res = new HashMap<>();

        String token = readRememberCookie(request);
        if (token == null || token.isBlank()) {
            res.put("status", "none");
            return ResponseEntity.ok(res);
        }

        SignUp user = loginRepository.findByRememberToken(token).orElse(null);
        if (user == null || Boolean.FALSE.equals(user.getActive())) {
            clearRememberCookie(response);
            res.put("status", "none");
            return ResponseEntity.ok(res);
        }

        boolean isChurch   = Boolean.TRUE.equals(user.getChurch());
        String  cid4check  = user.getClientId() != null ? user.getClientId() : "";
        boolean isMbrAcct  = cid4check.toUpperCase().startsWith("MBR");
        if (!isMbrAcct && !isChurch) {
            // For staff accounts: explicitly reject disabled or deleted app_users
            // so the remember-me cookie is cleared immediately and login is required.
            AppUser staffUser = appUserRepository.findByUserIdAndDeleteFlagFalse(cid4check).orElse(null);
            if (staffUser == null || !staffUser.isEnabled()) {
                clearRememberCookie(response);
                res.put("status", "none");
                return ResponseEntity.ok(res);
            }
        }
        if (!isMbrAcct) {
            int validCount = isChurch
                    ? loginRepository.countValidChurchLogin(user.getUsername())
                    : loginRepository.countValidNonChurchLogin(user.getUsername());
            if (validCount == 0) {
                clearRememberCookie(response);
                res.put("status", "none");
                return ResponseEntity.ok(res);
            }
        }

        // Rebuild session
        HttpSession existing = request.getSession(false);
        if (existing != null) existing.invalidate();
        HttpSession session = request.getSession(true);

        buildResponse(res, user, isChurch, session);

        // Refresh cookie lifetime
        addRememberCookie(response, token);

        return ResponseEntity.ok(res);
    }

    /**
     * Resolves an encrypted clientId token (from a login link) to its username.
     * Used by the login page to pre-fill the username field when a member arrives
     * via the approval email link: {@code /login?clientId=<encrypted>}.
     *
     * <p>This endpoint is public (whitelisted in {@code AuthFilter}).
     */
    @ResponseBody
    @GetMapping("/api/auth/resolve-client-id")
    public ResponseEntity<Map<String, Object>> resolveClientId(
            @RequestParam String token) {
        Map<String, Object> res = new HashMap<>();
        try {
            String plainClientId = com.churchgeniuspro.util.EncryptionUtil.decrypt(token);
            SignUp signup = loginRepository.findByClientId(plainClientId).orElse(null);
            if (signup == null || Boolean.TRUE.equals(signup.getDeleted())) {
                res.put("found", false);
                return ResponseEntity.ok(res);
            }
            res.put("found",    true);
            res.put("username", signup.getUsername());
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            res.put("found", false);
            return ResponseEntity.ok(res);
        }
    }

    /**
     * Logout endpoint: clears the remember-me token from the database and cookie,
     * then invalidates the session.
     */
    @ResponseBody
    @PostMapping("/api/auth/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request,
                                                       HttpServletResponse response) {
        // Capture session info before invalidation for logout log
        HttpSession session = request.getSession(false);
        if (session != null) {
            String logoutTime  = LocalDateTime.now().format(LOG_FMT);
            String clientId    = str(session.getAttribute("clientId"));
            String churchName  = str(session.getAttribute("churchName"));
            String role        = str(session.getAttribute("role"));
            String username    = str(session.getAttribute("username"));
            LOG.info("[LOGOUT] logoutTime={} | clientId={} | churchName={} | role={} | username={}",
                    logoutTime, clientId, churchName, role, username);
            session.invalidate();
        }

        // Clear remember token from DB if present
        String token = readRememberCookie(request);
        if (token != null && !token.isBlank()) {
            loginRepository.findByRememberToken(token).ifPresent(user -> {
                user.setRemember(null);
                loginRepository.save(user);
            });
        }
        clearRememberCookie(response);

        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * Returns all accounts in the current session user's link-group.
     * Works for ALL account types (church, staff, member).
     *
     * <p>Response list items include enough info to render the switcher:
     * {@code { type, signupId, appUserId?, memberRef?, firstName, lastName,
     *           role, hasActiveLogin, isCurrent }}
     *
     * <p>Returns an empty list when no link-group exists.
     */
    @ResponseBody
    @GetMapping("/api/auth/linked-accounts")
    public ResponseEntity<java.util.List<Map<String, Object>>> getLinkedAccounts(
            HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return ResponseEntity.ok(java.util.List.of());

        // Determine the current user's signup record to find their link_group
        String currentClientId = str(session.getAttribute("clientId")); // MBR… or CHR… or USR…
        boolean isChurch = Boolean.TRUE.equals(session.getAttribute("church")) ||
                           "true".equalsIgnoreCase(str(session.getAttribute("church")));
        boolean isMember = currentClientId.toUpperCase().startsWith("MBR");

        String linkGroup = null;

        if (isMember || isChurch) {
            // member or church accounts: link_group lives on signup.link_group
            SignUp signup = loginRepository.findByClientId(currentClientId).orElse(null);
            if (signup != null) linkGroup = signup.getLinkGroup();
        } else {
            // staff accounts: link_group lives on app_user.link_group
            Object appUserIdAttr = session.getAttribute("appUserId");
            if (appUserIdAttr instanceof Integer appUserId) {
                AppUser appUser = appUserRepository.findById(appUserId).orElse(null);
                if (appUser != null) linkGroup = appUser.getLinkGroup();
            }
        }

        if (linkGroup == null || linkGroup.isBlank()) return ResponseEntity.ok(java.util.List.of());

        // Collect all members of this link-group from both tables
        java.util.List<Map<String, Object>> results = new java.util.ArrayList<>();

        // 1. Staff app_users in this link-group
        java.util.List<AppUser> linkedUsers = appUserRepository.findByLinkGroupAndDeleteFlagFalse(linkGroup);
        java.util.Set<String> activeLoginIds = linkedUsers.isEmpty() ? java.util.Set.of() :
                loginRepository.findActiveSignupsByUserIds(
                        linkedUsers.stream().map(AppUser::getUserId).collect(java.util.stream.Collectors.toList())
                ).stream().map(SignUp::getClientId).collect(java.util.stream.Collectors.toSet());

        Object currentAppUserIdAttr = session.getAttribute("appUserId");
        Integer currentAppUserId = currentAppUserIdAttr instanceof Integer i ? i : null;

        for (AppUser u : linkedUsers) {
            boolean isCurrent = u.getId().equals(currentAppUserId);
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("type",          "staff");
            m.put("appUserId",     u.getId());
            m.put("firstName",     u.getFirstName());
            m.put("lastName",      u.getLastName());
            m.put("role",          u.getRole());
            m.put("enabled",       u.isEnabled());
            // hasActiveLogin is true as long as the signup record is active (not revoked).
            // Disabled accounts keep their signup active, so they remain switchable.
            m.put("hasActiveLogin", activeLoginIds.contains(u.getUserId()));
            m.put("isCurrent",     isCurrent);
            results.add(m);
        }

        // 2. Signup records (member/church) in this link-group
        java.util.List<SignUp> linkedSignups = loginRepository.findByLinkGroup(linkGroup);
        for (SignUp s : linkedSignups) {
            boolean isCurrent = currentClientId.equals(s.getClientId());
            String cid = s.getClientId() != null ? s.getClientId() : "";
            boolean isMbrSignup = cid.toUpperCase().startsWith("MBR");

            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("type",          isMbrSignup ? "member" : "church");
            m.put("signupId",      s.getId());
            m.put("memberRef",     s.getClientId());
            m.put("hasActiveLogin", Boolean.TRUE.equals(s.getActive()) && (s.getDeleted() == null || !s.getDeleted()));
            m.put("isCurrent",     isCurrent);

            if (isMbrSignup) {
                FamilyMember fm = familyMemberRepository.findByMemberRef(cid).orElse(null);
                m.put("firstName", fm != null ? fm.getFirstName() : "");
                m.put("lastName",  fm != null ? fm.getLastName()  : "");
                m.put("role",      "Member");
            } else {
                m.put("firstName", s.getUsername());
                m.put("lastName",  "");
                m.put("role",      "Church");
            }
            results.add(m);
        }

        return ResponseEntity.ok(results);
    }

    /**
     * Universal account-switch endpoint — works for ALL account types.
     *
     * <p>Body: {@code { "targetSignupId": 5 }}  (for member/church accounts)
     *      OR {@code { "targetAppUserId": 3 }}  (for staff accounts — existing behaviour)
     *
     * <p>Invalidates the current session, creates a new one and returns the same
     * payload as a normal login.  The link-group membership is verified before
     * switching.
     *
     * <p>Church accounts CAN switch (unlike the old switch-role endpoint that rejected them).
     */
    @ResponseBody
    @PostMapping("/api/auth/switch-account")
    public ResponseEntity<Map<String, Object>> switchAccount(@RequestBody Map<String, Object> body,
                                                              HttpServletRequest request,
                                                              HttpServletResponse response) {
        Map<String, Object> res = new java.util.HashMap<>();

        HttpSession currentSession = request.getSession(false);
        if (currentSession == null || currentSession.getAttribute("username") == null) {
            res.put("error", "Not authenticated.");
            return ResponseEntity.status(401).body(res);
        }

        String currentClientId = str(currentSession.getAttribute("clientId"));
        boolean isChurch = Boolean.TRUE.equals(currentSession.getAttribute("church")) ||
                           "true".equalsIgnoreCase(str(currentSession.getAttribute("church")));
        boolean isMember = currentClientId.toUpperCase().startsWith("MBR");

        // Determine current user's link_group
        String currentLinkGroup = null;
        if (isMember || isChurch) {
            SignUp currentSignup = loginRepository.findByClientId(currentClientId).orElse(null);
            if (currentSignup != null) currentLinkGroup = currentSignup.getLinkGroup();
        } else {
            Object appUserIdAttr = currentSession.getAttribute("appUserId");
            if (appUserIdAttr instanceof Integer appUserId) {
                AppUser currentUser = appUserRepository.findById(appUserId).orElse(null);
                if (currentUser != null) currentLinkGroup = currentUser.getLinkGroup();
            }
        }

        if (currentLinkGroup == null || currentLinkGroup.isBlank()) {
            res.put("error", "No linked accounts found.");
            return ResponseEntity.status(403).body(res);
        }

        // Determine target account type
        Object rawTargetSignupId  = body.get("targetSignupId");
        Object rawTargetAppUserId = body.get("targetAppUserId");

        if (rawTargetSignupId != null) {
            // ── Switch to a member or church signup account ────────────────
            int targetSignupId;
            try {
                targetSignupId = rawTargetSignupId instanceof Number n
                        ? n.intValue() : Integer.parseInt(String.valueOf(rawTargetSignupId));
            } catch (NumberFormatException e) {
                res.put("error", "targetSignupId must be a number.");
                return ResponseEntity.status(400).body(res);
            }

            SignUp targetSignup = loginRepository.findById(targetSignupId).orElse(null);
            if (targetSignup == null) {
                res.put("error", "Target account not found.");
                return ResponseEntity.status(404).body(res);
            }
            if (!currentLinkGroup.equals(targetSignup.getLinkGroup())) {
                res.put("error", "These accounts are not linked.");
                return ResponseEntity.status(403).body(res);
            }
            if (Boolean.FALSE.equals(targetSignup.getActive()) || Boolean.TRUE.equals(targetSignup.getDeleted())) {
                res.put("error", "Target account is inactive.");
                return ResponseEntity.status(403).body(res);
            }

            boolean targetIsChurch = Boolean.TRUE.equals(targetSignup.getChurch());
            currentSession.invalidate();
            HttpSession newSession = request.getSession(true);
            buildResponse(res, targetSignup, targetIsChurch, newSession);
            return ResponseEntity.ok(res);

        } else if (rawTargetAppUserId != null) {
            // ── Switch to a staff app_user account ───────────────────────
            int targetAppUserId;
            try {
                targetAppUserId = rawTargetAppUserId instanceof Number n
                        ? n.intValue() : Integer.parseInt(String.valueOf(rawTargetAppUserId));
            } catch (NumberFormatException e) {
                res.put("error", "targetAppUserId must be a number.");
                return ResponseEntity.status(400).body(res);
            }

            AppUser targetUser = appUserRepository.findById(targetAppUserId).orElse(null);
            if (targetUser == null) {
                res.put("error", "Target account not found.");
                return ResponseEntity.status(404).body(res);
            }
            if (!currentLinkGroup.equals(targetUser.getLinkGroup())) {
                res.put("error", "These accounts are not linked.");
                return ResponseEntity.status(403).body(res);
            }
            // Deleted accounts may never be switched to; disabled accounts CAN be switched
            // to (the user was previously linked and should still be able to reach that account).
            if (targetUser.isDeleteFlag()) {
                res.put("error", "Target account has been deleted.");
                return ResponseEntity.status(403).body(res);
            }

            // findActiveSignupByUserId requires signup.active = true.  For a disabled app_user
            // the signup record may still be active, so we look it up without an enabled check.
            SignUp targetSignup = loginRepository.findActiveSignupByUserId(targetUser.getUserId()).orElse(null);
            if (targetSignup == null) {
                res.put("error", "Target account has no active login credentials.");
                return ResponseEntity.status(403).body(res);
            }

            // Skip the subscription/service-client validation for disabled accounts —
            // they were already verified when originally linked.
            if (targetUser.isEnabled()) {
                int validCount = loginRepository.countValidNonChurchLogin(targetSignup.getUsername());
                if (validCount == 0) {
                    res.put("error", "Target account access is restricted.");
                    return ResponseEntity.status(403).body(res);
                }
            }

            currentSession.invalidate();
            HttpSession newSession = request.getSession(true);
            buildResponse(res, targetSignup, false, newSession);
            return ResponseEntity.ok(res);
        }

        res.put("error", "targetSignupId or targetAppUserId is required.");
        return ResponseEntity.status(400).body(res);
    }

    /**
     * Switches the active session to a different role that belongs to the same
     * link-group as the currently authenticated user.
     *
     * <p>Request body: {@code { "targetAppUserId": 5 }}
     * <p>Success: invalidates the current session, creates a new one for the
     * target user, and returns the same payload as a normal login so the
     * frontend can update localStorage and navigate to /home.
     * <p>Requires the current session to belong to a non-church app_user.
     */
    @ResponseBody
    @PostMapping("/api/auth/switch-role")
    public ResponseEntity<Map<String, Object>> switchRole(@RequestBody Map<String, Object> body,
                                                           HttpServletRequest request,
                                                           HttpServletResponse response) {
        Map<String, Object> res = new HashMap<>();

        // ── Validate current session ───────────────────────────────────────
        HttpSession currentSession = request.getSession(false);
        if (currentSession == null || currentSession.getAttribute("username") == null) {
            res.put("error", "Not authenticated.");
            return ResponseEntity.status(401).body(res);
        }

        // Only non-church (staff) accounts participate in role switching
        Object churchAttr = currentSession.getAttribute("church");
        boolean isChurch = Boolean.TRUE.equals(churchAttr) ||
                           "true".equalsIgnoreCase(String.valueOf(churchAttr));
        if (isChurch) {
            res.put("error", "Role switching is not available for church accounts.");
            return ResponseEntity.status(403).body(res);
        }

        Object currentAppUserIdAttr = currentSession.getAttribute("appUserId");
        if (!(currentAppUserIdAttr instanceof Integer currentAppUserId)) {
            res.put("error", "Role switching is not available for this account.");
            return ResponseEntity.status(403).body(res);
        }

        // ── Resolve target app_user ────────────────────────────────────────
        Object rawTarget = body.get("targetAppUserId");
        if (rawTarget == null) {
            res.put("error", "targetAppUserId is required.");
            return ResponseEntity.status(400).body(res);
        }
        Integer targetAppUserId;
        try {
            targetAppUserId = rawTarget instanceof Number n
                    ? n.intValue()
                    : Integer.parseInt(String.valueOf(rawTarget));
        } catch (NumberFormatException e) {
            res.put("error", "targetAppUserId must be a number.");
            return ResponseEntity.status(400).body(res);
        }

        if (targetAppUserId.equals(currentAppUserId)) {
            res.put("error", "Already logged in as this account.");
            return ResponseEntity.status(400).body(res);
        }

        AppUser currentUser = appUserRepository.findById(currentAppUserId).orElse(null);
        AppUser targetUser  = appUserRepository.findById(targetAppUserId).orElse(null);

        if (currentUser == null || targetUser == null) {
            res.put("error", "Account not found.");
            return ResponseEntity.status(404).body(res);
        }

        // ── Verify shared link-group ───────────────────────────────────────
        String currentLinkGroup = currentUser.getLinkGroup();
        if (currentLinkGroup == null || !currentLinkGroup.equals(targetUser.getLinkGroup())) {
            res.put("error", "These accounts are not linked.");
            return ResponseEntity.status(403).body(res);
        }

        if (targetUser.isDeleteFlag() || !targetUser.isEnabled()) {
            res.put("error", "The target account is inactive or has been removed.");
            return ResponseEntity.status(403).body(res);
        }

        // ── Find target signup (signup.client_id = targetUser.userId) ──────
        SignUp targetSignup = loginRepository.findActiveSignupByUserId(targetUser.getUserId()).orElse(null);
        if (targetSignup == null) {
            res.put("error", "The target account has no active login credentials.");
            return ResponseEntity.status(403).body(res);
        }

        // ── Validate subscription still active ─────────────────────────────
        int validCount = loginRepository.countValidNonChurchLogin(targetSignup.getUsername());
        if (validCount == 0) {
            res.put("error", "Target account access is restricted. Please contact your administrator.");
            return ResponseEntity.status(403).body(res);
        }

        // ── Invalidate current session and start a new one ─────────────────
        currentSession.invalidate();
        HttpSession newSession = request.getSession(true);

        buildResponse(res, targetSignup, false, newSession);
        res.put("status", "success");

        return ResponseEntity.ok(res);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Populates the response map and session with user data.
     *
     * <p>Session attributes set for every login:
     * <ul>
     *   <li>{@code clientId}    — client_id from signup table</li>
     *   <li>{@code churchName}  — church_name from church_registration table</li>
     *   <li>{@code subscription}— subscription_type from service_client table</li>
     *   <li>{@code role}        — "church" when clientId starts with "CHR",
     *                             otherwise the app_user role (SuperAdmin, Admin, …)</li>
     *   <li>{@code username}    — username from signup table (set for CHR accounts and
     *                             all staff accounts)</li>
     * </ul>
     */
    private void buildResponse(Map<String, Object> res, SignUp user,
                               boolean isChurch, HttpSession session) {
        String clientId = user.getClientId() != null ? user.getClientId() : "";

        res.put("status",   "success");
        res.put("clientId", clientId);
        res.put("username", user.getUsername());
        res.put("church",   isChurch);
        res.put("churchId", user.getChurchId() != null ? user.getChurchId() : "");

        // Member role uses /memberHome; all others use existing logic
        boolean isMember = clientId.toUpperCase().startsWith("MBR");
        res.put("redirect", isChurch ? "/viewusers" : (isMember ? "/memberHome" : "/home"));

        session.setAttribute("clientId",  clientId);
        session.setAttribute("username",  user.getUsername());
        session.setAttribute("church",    isChurch);
        session.setAttribute("churchId",  user.getChurchId() != null ? String.valueOf(user.getChurchId()) : "");

        // ── Determine the client ID to use for service_client lookup ──────────
        // For church accounts it is their own clientId.
        // For staff accounts we resolve it after finding the AppUser below.
        String subscriptionClientId = null;

        if (isChurch) {
            // ── Church account ─────────────────────────────────────────────────
            if (!clientId.isBlank()) {
                ChurchRegistration church = churchRegistrationRepository
                        .findByClientIdAndDeleteFlagFalse(clientId)
                        .orElse(null);
                if (church != null) {
                    String churchName = church.getChurchName() != null ? church.getChurchName() : "";
                    res.put("churchName", churchName);
                    session.setAttribute("churchName", churchName);
                }
            }
            subscriptionClientId = clientId;

            // If the client_id starts with "CHR", set role = "church" and store username
            if (clientId.toUpperCase().startsWith("CHR")) {
                session.setAttribute("role",     "church");
                session.setAttribute("username", user.getUsername());
                res.put("role", "church");
            }

        } else if (isMember) {
            // ── Member account ─────────────────────────────────────────────────
            // signup.client_id = "MBR<uuid-token>" = family_member.member_ref.
            // Look up the family member directly by memberRef — no integer signupRef needed.
            // Always set role=Member so the session is valid regardless of linkage state.
            session.setAttribute("role", "Member");
            res.put("role", "Member");
            try {
                // clientId is the MBR<uuid> token — find the matching FamilyMember directly
                FamilyMember fm = familyMemberRepository.findByMemberRef(clientId).orElse(null);

                // Self-heal: memberRef not set yet — try matching by email (username)
                if (fm == null) {
                    String signupEmail = user.getUsername();
                    java.util.List<com.churchgeniuspro.hibernate.FamilyMember> candidates =
                            familyMemberRepository.findActiveByEmail(signupEmail);
                    if (!candidates.isEmpty()) {
                        fm = candidates.get(0);
                        if (fm.getMemberRef() == null) {
                            fm.setMemberRef(clientId);
                            familyMemberRepository.save(fm);
                        }
                    }
                }

                if (fm != null) {
                    String appClientId = fm.getFamily() != null ? fm.getFamily().getAppClientId() : fm.getAppClientId();
                    String memberRole  = fm.getRole() != null ? fm.getRole() : "Member";
                    res.put("firstName",    fm.getFirstName());
                    res.put("lastName",     fm.getLastName());
                    res.put("appClientId",  appClientId);
                    res.put("memberId",     fm.getId());
                    res.put("memberRole",   memberRole);
                    session.setAttribute("firstName",    fm.getFirstName());
                    session.setAttribute("lastName",     fm.getLastName());
                    session.setAttribute("appClientId",  appClientId);
                    session.setAttribute("memberId",     fm.getId());
                    session.setAttribute("memberRole",   memberRole);
                    // Store member portal privileges in session so RoleGuard can enforce
                    // page-level access without a DB lookup on every request.
                    // Null means "no restrictions" — all member tabs allowed.
                    session.setAttribute("memberPrivileges", fm.getMemberPrivileges());

                    subscriptionClientId = appClientId;

                    if (appClientId != null && !appClientId.isBlank()) {
                        ChurchRegistration church = churchRegistrationRepository
                                .findByClientIdAndDeleteFlagFalse(appClientId)
                                .orElse(null);
                        if (church != null) {
                            String churchName = church.getChurchName() != null ? church.getChurchName() : "";
                            res.put("churchName", churchName);
                            session.setAttribute("churchName", churchName);
                        }
                    }
                }
                // clientId (MBR...) is stored in session as "clientId" by the common block above
            } catch (Exception ignored) { /* defensive — should not throw */ }

        } else {
            // ── Staff account ──────────────────────────────────────────────────
            // signup.client_id → match app_user.user_id → resolve app_user.client_id (church's clientId)
            if (!clientId.isBlank()) {
                AppUser appUser = appUserRepository
                        .findByUserIdAndDeleteFlagFalse(clientId)
                        .orElse(null);
                if (appUser != null) {
                    String appClientId = appUser.getClientId();
                    res.put("firstName",   appUser.getFirstName());
                    res.put("lastName",    appUser.getLastName());
                    res.put("role",        appUser.getRole());
                    res.put("appClientId", appClientId);
                    res.put("appUserId",   appUser.getId());
                    session.setAttribute("firstName",   appUser.getFirstName());
                    session.setAttribute("lastName",    appUser.getLastName());
                    session.setAttribute("role",        appUser.getRole());
                    session.setAttribute("appClientId", appClientId);
                    session.setAttribute("appUserId",   appUser.getId());

                    // Prefer the granular permission map from user_permissions table;
                    // fall back to the legacy app_user.privileges column if no row exists.
                    String privileges = userPermissionsRepository
                            .findByAppUserId(appUser.getId())
                            .map(UserPermissions::getPermissions)
                            .orElse(appUser.getPrivileges());
                    session.setAttribute("privileges", privileges);

                    subscriptionClientId = appClientId;

                    // Resolve church name: try church_registration first, then service_client
                    if (appClientId != null && !appClientId.isBlank()) {
                        ChurchRegistration church = churchRegistrationRepository
                                .findByClientIdAndDeleteFlagFalse(appClientId)
                                .orElse(null);
                        String churchName = null;
                        if (church != null && church.getChurchName() != null && !church.getChurchName().isBlank()) {
                            churchName = church.getChurchName();
                        } else {
                            // Fallback: look up service_client by client_id
                            ServiceClient sc = serviceClientRepository.findByClientId(appClientId).orElse(null);
                            if (sc != null && sc.getChurchName() != null && !sc.getChurchName().isBlank()) {
                                churchName = sc.getChurchName();
                            }
                        }
                        if (churchName != null) {
                            res.put("churchName", churchName);
                            session.setAttribute("churchName", churchName);
                        }
                    }
                } else {
                    // ── Fallback for accounts with no app_user row ────────────────
                    // Use username as display name and keep whatever role was already
                    // set on the session (or default to the clientId prefix).
                    String fallbackName = user.getUsername() != null ? user.getUsername() : "";
                    res.put("firstName", fallbackName);
                    session.setAttribute("firstName", fallbackName);
                    // role may already be set above (e.g. "church"); only set if missing
                    if (session.getAttribute("role") == null) {
                        session.setAttribute("role", "SuperAdmin");
                        res.put("role", "SuperAdmin");
                    }
                }
            }
        }

        // ── Subscription type from service_client ─────────────────────────────
        if (subscriptionClientId != null && !subscriptionClientId.isBlank()) {
            serviceClientRepository.findByClientId(subscriptionClientId).ifPresent(sc -> {
                if (sc.getSubscriptionType() != null) {
                    session.setAttribute("subscription", sc.getSubscriptionType());
                    res.put("subscription", sc.getSubscriptionType());
                }
            });
        }

        // Log session details once at login — not repeated on every request.
        LOG.info("[LOGIN] clientId={} | churchName={} | subscription={} | role={} | username={}",
                session.getAttribute("clientId"),
                session.getAttribute("churchName"),
                session.getAttribute("subscription"),
                session.getAttribute("role"),
                session.getAttribute("username"));

        res.put("status", "success");
    }

    // ── Cookie helpers ────────────────────────────────────────────────────────

    private void addRememberCookie(HttpServletResponse response, String token) {
        Cookie cookie = new Cookie(COOKIE_NAME, token);
        cookie.setMaxAge(COOKIE_MAX_AGE);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        response.addCookie(cookie);
    }

    private void clearRememberCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(COOKIE_NAME, "");
        cookie.setMaxAge(0);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        response.addCookie(cookie);
    }

    private String readRememberCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies())
                .filter(c -> COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst().orElse(null);
    }

    private static String str(Object val) {
        return val != null ? String.valueOf(val) : "";
    }
}
