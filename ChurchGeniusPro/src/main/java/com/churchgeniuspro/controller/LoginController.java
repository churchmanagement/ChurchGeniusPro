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
import com.churchgeniuspro.service.LoginProtectionService;
import com.churchgeniuspro.service.SecurityAuditService;
import com.churchgeniuspro.service.SubscriptionService;
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

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Controller
public class LoginController {

    private static final Logger LOG = LoggerFactory.getLogger(LoginController.class);

    private static final String COOKIE_NAME    = "rememberToken";
    private static final int    COOKIE_MAX_AGE = 30 * 24 * 60 * 60; // 30 days in seconds

    /**
     * Mirrors {@code server.servlet.session.cookie.secure} so the remember-me
     * cookie also gets the Secure flag in production (where HTTPS is used).
     * Defaults to {@code false} for local HTTP development.
     */
    @Value("${server.servlet.session.cookie.secure:false}")
    private boolean cookieSecure;

    /**
     * Dummy BCrypt hash of a value nobody will ever submit. When the username does not
     * exist we still verify the submitted password against this, so an unknown account
     * costs the same ~100ms of hashing as a known one. Without it, response time alone
     * reveals which usernames are real — enumeration by stopwatch.
     */
    private static final String DUMMY_HASH =
            "$2a$10$6pi7JA3CYLVakVOrebM9Be5p5xbHQ92y/gWS3Mr07agmZ4XkCWu0K";

    private final LoginRepository              loginRepository;
    private final AppUserRepository            appUserRepository;
    private final ChurchRegistrationRepository churchRegistrationRepository;
    private final ServiceClientRepository      serviceClientRepository;
    private final UserPermissionsRepository    userPermissionsRepository;
    private final FamilyMemberRepository       familyMemberRepository;
    private final LoginProtectionService       loginProtection;
    private final SecurityAuditService         securityAudit;
    private final com.churchgeniuspro.service.DemoAccessService demoAccess;

    public LoginController(LoginRepository loginRepository,
                           AppUserRepository appUserRepository,
                           ChurchRegistrationRepository churchRegistrationRepository,
                           ServiceClientRepository serviceClientRepository,
                           UserPermissionsRepository userPermissionsRepository,
                           FamilyMemberRepository familyMemberRepository,
                           LoginProtectionService loginProtection,
                           SecurityAuditService securityAudit,
                           com.churchgeniuspro.service.DemoAccessService demoAccess) {
        this.securityAudit                = securityAudit;
        this.demoAccess                   = demoAccess;
        this.loginRepository              = loginRepository;
        this.appUserRepository            = appUserRepository;
        this.churchRegistrationRepository = churchRegistrationRepository;
        this.serviceClientRepository      = serviceClientRepository;
        this.userPermissionsRepository    = userPermissionsRepository;
        this.familyMemberRepository       = familyMemberRepository;
        this.loginProtection              = loginProtection;
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
     * <p>Failure:      {@code { "status": "error", "message": "Invalid username or password." }}
     *                  with HTTP 401 — deliberately identical whether the username exists
     *                  or the password was wrong, so the endpoint cannot be used to
     *                  enumerate accounts.
     * <p>Throttled:    {@code { "status": "error", "blocked": true, "message": "..." }}
     *                  with HTTP 429 and a {@code Retry-After} header, once
     *                  {@link LoginProtectionService} has opened a temporary block. The
     *                  message never states the threshold or the attempts remaining.
     *
     * <p>All brute-force decisions are made by {@link LoginProtectionService}; this method
     * only reports what happened. See {@code LOGIN_SECURITY.md} for the policy.
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

        // ── Brute-force gate ─────────────────────────────────────────────────
        // Runs before the account lookup so a throttled attacker learns nothing at
        // all — not even whether the username exists.
        LoginProtectionService.GuardResult guard = loginProtection.check(request, username);
        if (guard.blocked()) {
            loginProtection.recordBlockedAttempt(request, username, "/login");
            res.put("status",  "error");
            res.put("blocked", true);
            res.put("message", guard.message());
            return ResponseEntity.status(429)
                    .header("Retry-After", String.valueOf(guard.retryAfterSeconds()))
                    .body(res);
        }

        if (username.isBlank() || password.isBlank()) {
            // Still counted: a flood of empty submissions is an attack pattern too.
            // With a blank username only the IP-scoped counter can advance.
            loginProtection.applyProgressiveDelay(loginProtection.recordFailure(
                    request, username, LoginProtectionService.REASON_MISSING_FIELDS, null, "/login"));
            res.put("status",  "error");
            res.put("message", "Username and password are required.");
            return ResponseEntity.status(400).body(res);
        }

        SignUp user = loginRepository.findByUsernameAndDeletedFalse(username).orElse(null);

        if (user == null) {
            // Burn the same BCrypt work a real account would, so the response time of a
            // non-existent username matches that of a real one.
            PasswordUtil.matches(password, DUMMY_HASH);
            // Server-side only — the client always gets the same generic message.
            LOG.warn("Login 401 — no active signup row found for username='{}'", username);
            loginProtection.applyProgressiveDelay(loginProtection.recordFailure(
                    request, username, LoginProtectionService.REASON_UNKNOWN_USER, null, "/login"));
            res.put("status",  "error");
            res.put("message", LoginProtectionService.GENERIC_FAILURE_MESSAGE);
            return ResponseEntity.status(401).body(res);
        }
        // ── Password verification (BCrypt-aware) ─────────────────────────────
        // PasswordUtil.matches() handles both BCrypt hashes ($2a$...) and
        // legacy plaintext rows so existing accounts keep working during migration.
        if (!PasswordUtil.matches(password, user.getPassword())) {
            // NOTE: never log the submitted password or its length — the length alone
            // narrows a brute-force search and belongs nowhere near a log file.
            LOG.warn("Login 401 — password mismatch for username='{}'", username);
            loginProtection.applyProgressiveDelay(loginProtection.recordFailure(
                    request, username, LoginProtectionService.REASON_BAD_PASSWORD,
                    user.getClientId(), "/login"));
            res.put("status",  "error");
            res.put("message", LoginProtectionService.GENERIC_FAILURE_MESSAGE);
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

        // ── Account-state checks ─────────────────────────────────────────────
        // Everything below this point runs only AFTER the correct password was supplied,
        // so these outcomes are recorded for audit but never counted towards the
        // brute-force thresholds: a member whose subscription lapsed must not be able to
        // throttle themselves out by retrying a password that is in fact correct. They
        // also cannot leak account existence, because reaching them requires the password.
        if (Boolean.FALSE.equals(user.getActive())) {
            loginProtection.recordDenied(request, username, "INACTIVE", user.getClientId(), "/login");
            res.put("status",  "error");
            res.put("message", "Your account is inactive. Please contact your administrator.");
            return ResponseEntity.status(403).body(res);
        }

        boolean isChurch  = Boolean.TRUE.equals(user.getChurch());
        // Member accounts (clientId starts with "MBR") have no AppUser row;
        // skip the subscription/active query that would always return 0 for them.
        String clientIdForCheck = user.getClientId() != null ? user.getClientId() : "";
        boolean isMemberAccount = clientIdForCheck.toUpperCase().startsWith("MBR");

        // The organisation whose subscription governs this login. For a church account the
        // signup's own clientId is the organisation; for a staff account it is the one on
        // their app_user row. Resolved here (rather than inside the branch below) so the
        // expiry check further down can use it.
        String orgClientId = isChurch ? clientIdForCheck : null;

        if (!isMemberAccount && !isChurch) {
            // For staff accounts (USR…): explicitly check app_user.enabled and delete_flag
            // before running the heavier subscription query, so the error message is clear.
            AppUser staffUser = appUserRepository.findByUserIdAndDeleteFlagFalse(clientIdForCheck).orElse(null);
            if (staffUser == null) {
                // No app_user row yet (invite not yet accepted) OR already hard-deleted.
                // Fall through to countValidNonChurchLogin which will also return 0.
            } else if (!staffUser.isEnabled()) {
                loginProtection.recordDenied(request, username, "STAFF_DISABLED", user.getClientId(), "/login");
                res.put("status",  "error");
                res.put("message", "Your account has been disabled. Please contact your administrator.");
                return ResponseEntity.status(403).body(res);
            } else if (staffUser.isDeleteFlag()) {
                loginProtection.recordDenied(request, username, "STAFF_DELETED", user.getClientId(), "/login");
                res.put("status",  "error");
                res.put("message", "Your account has been removed. Please contact your administrator.");
                return ResponseEntity.status(403).body(res);
            } else {
                orgClientId = staffUser.getClientId();
            }
        }

        // ── Demo/trial access window ─────────────────────────────────────────
        // Placed AFTER orgClientId is resolved, deliberately. signup.client_id holds
        // the app_user id for a staff login and the member ref for a portal login, so
        // only the resolved tenant reveals whether this is a demo account at all.
        // Enforced at authentication rather than in the UI, so an expired role cannot
        // reach the application by navigating straight to an internal URL.
        // Fails OPEN on infrastructure errors, matching this method's existing
        // contract (see lookupFailureFailsOpen): a database blip must not cost a
        // paying member their sign-in. A window we CAN read is still enforced.
        // Carried past the session rebuild below: the window is read here, but the
        // session it has to be recorded on does not exist until buildResponse().
        boolean             demoTrial         = false;
        java.time.LocalDate demoTrialEnd      = null;
        boolean             demoTrialAccepted = false;
        try {
            String demoTenant = orgClientId;
            if (demoTenant == null && isMemberAccount) {
                FamilyMember demoFm = familyMemberRepository.findByMemberRef(clientIdForCheck).orElse(null);
                if (demoFm != null) demoTenant = demoFm.getAppClientId();
            }
            if (demoAccess.isDemoClient(demoTenant)) {
                // ensureWindow, not a plain lookup: a demo login that pre-dates this
                // feature has no window, and "no window" must never mean "no limit".
                var demoWindow = demoAccess.ensureWindow(user.getId(), demoTenant, username);
                demoTrial         = true;
                demoTrialEnd      = demoWindow.getEndDate();
                demoTrialAccepted = demoWindow.isAgreementAccepted();
                if (!demoWindow.isUsable()) {
                    String why = demoWindow.getStatus();        // EXPIRED or BLOCKED
                    loginProtection.recordDenied(request, username, "DEMO_" + why, demoTenant, "/login");
                    LOG.warn("Login 403 — demo role {} for username='{}' tenant={}", why, username, demoTenant);
                    res.put("status",  "error");
                    res.put("message", "Your demo/trial access has expired. Please contact "
                                     + "support@churchgeniuspro.com if you have any questions "
                                     + "or would like to continue using the application.");
                    return ResponseEntity.status(403).body(res);
                }
            }
        } catch (Exception demoEx) {
            LOG.warn("Demo access check skipped for username='{}' — {}", username, demoEx.getMessage());
        }

        if (!isMemberAccount) {
            int validCount = isChurch
                    ? loginRepository.countValidChurchLogin(username)
                    : loginRepository.countValidNonChurchLogin(username);
            if (validCount == 0) {
                // "Account access is restricted" covers a dozen different causes and tells a
                // user nothing they can act on. When the cause is specifically a lapsed
                // subscription — which is the normal, expected end state for a trial or a
                // demo tenant — say so and give the date, so the person knows to renew
                // rather than filing a support ticket about a broken password. Safe to be
                // specific here: reaching this line already required the correct password.
                String expired = expiredSubscriptionMessage(orgClientId);
                loginProtection.recordDenied(request, username,
                        expired != null ? "SUBSCRIPTION_EXPIRED" : "ACCESS_RESTRICTED",
                        user.getClientId(), "/login");
                res.put("status",  "error");
                res.put("message", expired != null ? expired
                        : "Account access is restricted. Please contact your administrator.");
                return ResponseEntity.status(403).body(res);
            }
        } else {
            // ── Member / child portal accounts ─────────────────────────────────────
            // Neither query above applies to them: countValidNonChurchLogin joins through
            // app_user, and a portal login has no app_user row, so it would return 0 for
            // every member and the whole branch is skipped. The consequence was that a
            // member could keep signing in indefinitely after their church's subscription
            // had lapsed — the org was cut off but its members were not.
            //
            // Resolve the owning organisation through family_member and check that one
            // subscription. Deliberately fail-open: only a subscription we can positively
            // prove has expired blocks the sign-in, so a member whose linkage row is
            // missing or malformed is never newly locked out by this check.
            String memberOrg = resolveMemberOrgClientId(clientIdForCheck, user.getUsername());
            String expired   = expiredSubscriptionMessage(memberOrg);
            if (expired != null) {
                loginProtection.recordDenied(request, username, "SUBSCRIPTION_EXPIRED",
                        user.getClientId(), "/login");
                res.put("status",  "error");
                res.put("message", expired);
                return ResponseEntity.status(403).body(res);
            }
        }

        // ── Build session ──────────────────────────────────────────────────
        HttpSession existing = request.getSession(false);
        if (existing != null) existing.invalidate();
        HttpSession session = request.getSession(true);

        buildResponse(res, user, isChurch, session);

        // ── Demo/trial agreement gate ────────────────────────────────────────
        // Recorded on the session so DemoTrialAgreementFilter can enforce it without
        // a database read per request, and so the front end knows to raise the
        // acknowledgement popup. Only demo tenants get these attributes at all — a
        // regular account leaves this block with nothing set, and both the filter and
        // the client script are no-ops without them.
        if (demoTrial) {
            session.setAttribute("demoTrial",         Boolean.TRUE);
            session.setAttribute("demoTrialSignupId", user.getId());
            session.setAttribute("demoTrialEndDate",  demoTrialEnd == null ? "" : demoTrialEnd.toString());
            session.setAttribute("demoTrialAccepted", demoTrialAccepted);
            // Reported for API clients and the audit trail. The web UI does not read
            // these: demo-trial.js asks /api/demo/trial-agreement on every page load
            // instead, which is the safer design — the popup then reflects the row
            // rather than whatever the sign-in response said minutes ago.
            res.put("demoTrial",         true);
            res.put("demoTrialEndDate",  demoTrialEnd == null ? "" : demoTrialEnd.toString());
            res.put("demoTrialAccepted", demoTrialAccepted);
        }

        // ── Authentication succeeded — audit it ────────────────────────────
        // Runs AFTER buildResponse so the church name and role are on the session and can
        // go into the audit record. This also writes the SUCCESS row that resets this
        // account's failure counters (username+IP and username scopes); the IP-scoped
        // counter is deliberately left running — see LoginProtectionService.
        securityAudit.recordLogin(request, username, user.getClientId(), session);

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

        // A remember-me auto-login is a sign-in, so it answers to the same two
        // questions the password path does. It used to answer to neither: a demo
        // role a Service Admin had blocked, or whose window had ended, kept getting
        // a session indefinitely as long as its tenant's own subscription was still
        // running, and a member's church could lapse without ever cutting them off.
        String reissueBlock = reissueBlockReason(user, isChurch, cid4check, isMbrAcct);
        if (reissueBlock != null) {
            LOG.warn("Remember-me refused for username='{}' — {}", user.getUsername(), reissueBlock);
            clearRememberCookie(response);
            res.put("status", "none");
            return ResponseEntity.ok(res);
        }

        // Rebuild session
        HttpSession existing = request.getSession(false);
        if (existing != null) existing.invalidate();
        HttpSession session = request.getSession(true);

        buildResponse(res, user, isChurch, session);

        // A remember-me auto-login is still a sign-in and belongs in the audit trail —
        // arguably more so, since it happens without anyone typing a password.
        securityAudit.recordLogin(request, user.getUsername(), user.getClientId(), session);

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
            // The registration token minted at approval — the same value the church-owner
            // registration link carries. Nothing is decrypted.
            String plainClientId = serviceClientRepository
                    .findByRegistrationTokenAndStatusAndDeleteFlagFalse(token.trim(), "Active")
                    .map(com.churchgeniuspro.hibernate.ServiceClient::getClientId).orElse(null);
            if (plainClientId == null) { res.put("found", false); return ResponseEntity.ok(res); }
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
            // Must run before invalidate() — the church name, role and username all live on
            // the session, and the session hash is what ties this line to its LOGIN entry.
            securityAudit.recordLogout(request, session);
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
            // ...and the demo/trial window for the account being switched TO. A
            // switch builds a full session, so skipping this check made it a way
            // around a block that the password path enforces.
            String switchBlock = reissueBlockReason(targetSignup, false, targetUser.getUserId(), false);
            if (switchBlock != null) {
                res.put("error", switchBlock);
                return ResponseEntity.status(403).body(res);
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

        // ── ...and this role's own demo/trial window ───────────────────────
        String roleBlock = reissueBlockReason(targetSignup, false, targetUser.getUserId(), false);
        if (roleBlock != null) {
            res.put("error", roleBlock);
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
     * The checks a session rebuilt WITHOUT a password must still pass: this login's
     * demo/trial window, and (for a member portal) the owning church's expiry.
     *
     * <p>{@code login()} performs both inline. Remember-me, account switching and
     * role switching each build a full session too, and each used to perform
     * neither — so the one path that asked for a password was the only one these
     * rules governed. Factored out here rather than copied three more times.
     *
     * <p>Fails OPEN, exactly as the sign-in path does: only a window or a
     * subscription we can positively read as finished refuses the session.
     *
     * @return the reason to refuse, or {@code null} to continue
     */
    private String reissueBlockReason(SignUp user, boolean isChurch, String clientIdForCheck, boolean isMember) {
        try {
            String org = null;
            if (isChurch) {
                org = clientIdForCheck;
            } else if (isMember) {
                FamilyMember fm = familyMemberRepository.findByMemberRef(clientIdForCheck).orElse(null);
                if (fm != null) org = fm.getAppClientId();
            } else {
                AppUser staff = appUserRepository.findByUserIdAndDeleteFlagFalse(clientIdForCheck).orElse(null);
                if (staff != null) org = staff.getClientId();
            }

            if (demoAccess.isDemoClient(org)) {
                var window = demoAccess.ensureWindow(user.getId(), org, user.getUsername());
                if (!window.isUsable()) {
                    return "Your demo/trial access has expired. Please contact "
                         + "support@churchgeniuspro.com if you have any questions "
                         + "or would like to continue using the application.";
                }
            }
            if (isMember) {
                String expired = expiredSubscriptionMessage(org);
                if (expired != null) return expired;
            }
        } catch (Exception e) {
            LOG.warn("Session re-issue checks skipped for username='{}' — {}", user.getUsername(), e.getMessage());
        }
        return null;
    }

    /**
     * Returns a message explaining that {@code orgClientId}'s subscription has expired, or
     * {@code null} when it has not — or when we cannot tell.
     *
     * <p>The test is deliberately the date alone, and delegates to
     * {@link SubscriptionService#isExpired} — the one definition of "expired", written to
     * match {@code countValidChurchLogin} / {@code countValidNonChurchLogin}
     * ({@code end_date > current_date}) — so this can never claim a subscription has lapsed
     * while those queries still let the login through. It does <em>not</em> look at
     * {@code status}: a subscription that is dated valid but marked inactive has been
     * suspended rather than expired, and for member accounts — which no query gated before
     * this change — blocking on status would lock out people over a field that was never
     * part of their sign-in.
     *
     * <p><b>Fails open by design.</b> A missing clientId, a missing {@code service_client}
     * row or a null end date all return {@code null}, which only ever means "do not
     * attribute this to expiry" — for staff and church accounts the login has already been
     * refused by the count queries and simply keeps the generic message, and for member
     * accounts it means the sign-in proceeds exactly as it did before. No login that used
     * to work can be blocked by a gap in this lookup.
     */
    private String expiredSubscriptionMessage(String orgClientId) {
        if (orgClientId == null || orgClientId.isBlank()) return null;
        ServiceClient sc = serviceClientRepository.findByClientId(orgClientId).orElse(null);
        if (sc == null) return null;
        if (Boolean.TRUE.equals(sc.getDeleteFlag())) return null;   // removed, not expired

        LocalDate end = sc.getEndDate();
        if (!SubscriptionService.isExpired(end, com.churchgeniuspro.util.AppClock.today())) return null;

        return "This subscription expired on " + end + " and access has ended. "
             + "Please contact your administrator to renew it.";
    }

    /**
     * Resolves the organisation that owns a member/child portal login, or {@code null} when
     * it cannot be determined.
     *
     * <p>Mirrors the lookup {@link #buildResponse} performs for member accounts —
     * {@code signup.client_id} is the {@code MBR…} token stored as
     * {@code family_member.member_ref}, and the organisation is the family's
     * {@code app_client_id} — including the email fallback for members whose
     * {@code member_ref} was never populated, so the check does not fire on a linkage gap
     * that the login itself would have healed a moment later.
     */
    private String resolveMemberOrgClientId(String memberRef, String usernameEmail) {
        try {
            FamilyMember fm = familyMemberRepository.findByMemberRef(memberRef).orElse(null);
            if (fm == null && usernameEmail != null && !usernameEmail.isBlank()) {
                var candidates = familyMemberRepository.findActiveByEmail(usernameEmail);
                if (!candidates.isEmpty()) fm = candidates.get(0);
            }
            if (fm == null) return null;
            return fm.getFamily() != null ? fm.getFamily().getAppClientId() : fm.getAppClientId();
        } catch (Exception e) {
            // Fail open — a lookup problem must never cost a member their sign-in.
            LOG.warn("Could not resolve owning organisation for portal login: {}", e.toString());
            return null;
        }
    }

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
                // clientId is the MBR<uuid> token — find the matching FamilyMember directly.
                //
                // There used to be a "self-heal" here: when no member carried this
                // memberRef, the login matched the username against family_member.email
                // across EVERY church and stamped the memberRef onto the first hit. That
                // turned any signup row into a session as whichever member shared the
                // email — in any tenant. A member is now bound to a signup only by the
                // flows that verify the person (member signup OTP, membership-form OTP,
                // admin link-signup); login never writes to family_member.
                FamilyMember fm = familyMemberRepository.findByMemberRef(clientId).orElse(null);

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
                    // A login with no app_user row and no church flag has no role. It used
                    // to default to SuperAdmin here; a missing record must never be a
                    // promotion. "Limited" is the lowest staff role RoleGuard recognises.
                    if (session.getAttribute("role") == null) {
                        session.setAttribute("role", "Limited");
                        res.put("role", "Limited");
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
        cookie.setSecure(cookieSecure);              // same flag as the session cookie
        cookie.setAttribute("SameSite", "Lax");      // never sent on cross-site sub-requests
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
