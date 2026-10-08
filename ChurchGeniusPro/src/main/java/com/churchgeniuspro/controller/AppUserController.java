package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.UserPermissions;
import com.churchgeniuspro.model.AppUserBO;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.UserPermissionsRepository;
import com.churchgeniuspro.service.AppUserService;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.PasswordResetService;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Handles requests for the Users page and the user management REST API.
 *
 * <h3>Page routes</h3>
 * <ul>
 *   <li>{@code GET /viewusers} → {@code viewusers.html}</li>
 * </ul>
 *
 * <h3>API routes</h3>
 * <ul>
 *   <li>{@code GET    /api/users}                → list all active users</li>
 *   <li>{@code POST   /api/users}                → create a user</li>
 *   <li>{@code PUT    /api/users/{id}}           → update a user</li>
 *   <li>{@code DELETE /api/users/{id}}           → soft-delete a user</li>
 *   <li>{@code PATCH  /api/users/{id}/toggle}    → enable / disable a user</li>
 *   <li>{@code POST   /api/users/{id}/email}     → send a notification email</li>
 * </ul>
 */
@Controller
public class AppUserController {

    private final AppUserService             userService;
    private final UserPermissionsRepository  permissionsRepo;
    private final FamilyMemberRepository     familyMemberRepository;
    private final LoginRepository            loginRepository;
    private final EmailService               emailService;
    private final PasswordResetService       passwordResetService;

    /** Plan user limit (e.g. Standard = 10). Optional so existing construction sites are unaffected. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.service.SubscriptionService subscriptionService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.repository.AppUserRepository appUserRepository;

    /** Test seam. */
    public void setPlanLimitDeps(com.churchgeniuspro.service.SubscriptionService s,
                          com.churchgeniuspro.repository.AppUserRepository r) {
        this.subscriptionService = s;
        this.appUserRepository = r;
    }

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    public AppUserController(AppUserService userService,
                             UserPermissionsRepository permissionsRepo,
                             FamilyMemberRepository familyMemberRepository,
                             LoginRepository loginRepository,
                             EmailService emailService,
                             PasswordResetService passwordResetService) {
        this.userService            = userService;
        this.permissionsRepo        = permissionsRepo;
        this.familyMemberRepository = familyMemberRepository;
        this.loginRepository        = loginRepository;
        this.emailService           = emailService;
        this.passwordResetService   = passwordResetService;
    }

    // ── Authorisation helpers ─────────────────────────────────────────────

    /**
     * Staff-user management is owner-only (the {@code /viewusers} page already says
     * so via {@code RoleGuard.requireChurch}); the JSON API now enforces the same.
     * Returns the owner's clientId, or {@code null} when the caller is not a church
     * session — callers respond 403 in that case. The clientId is taken from the
     * session, never from the request body.
     */
    private static String ownerClientId(HttpServletRequest request) {
        if (RoleGuard.requireChurch(request) != null) return null;
        Object v = request.getSession(false).getAttribute("clientId");
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    private static ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(403).body(Map.of("error", "Only the church account can manage users."));
    }

    // ── Page route ────────────────────────────────────────────────────────

    @GetMapping("/viewusers")
    public String viewUsersPage(HttpServletRequest request) {
        // Owner-only page: ONLY church-type logins may access it. All other roles
        // (SuperAdmin, Admin, Accountant, User, Limited, Member, Child) are denied.
        String deny = RoleGuard.requireChurch(request);
        if (deny != null) return deny;
        // Case must match viewUsers.html exactly — jar classpath lookup is
        // case-sensitive (works from Windows filesystem, 404s from the JAR).
        return "forward:/viewUsers.html";
    }

    // ── List ──────────────────────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/users")
    public ResponseEntity<List<Map<String, Object>>> getAll(HttpServletRequest request) {
        // Every session type resolves to its own tenant; a null tenant is 401, never
        // "list everyone". (Previously only church sessions were scoped and any staff
        // or member session received every church's user directory.)
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).body(List.of());
        return ResponseEntity.ok(userService.getAll(clientId));
    }

    // ── Create ────────────────────────────────────────────────────────────

    @ResponseBody
    @PostMapping("/api/users")
    public ResponseEntity<Map<String, Object>> create(@RequestBody AppUserBO bo, HttpServletRequest request) {
        String clientId = ownerClientId(request);
        if (clientId == null) return forbidden();
        bo.setClientId(clientId);   // never from the body — that was a create-SuperAdmin-anywhere hole
        if (blank(bo.getFirstName())) return bad("First name is required.");
        if (blank(bo.getLastName()))  return bad("Last name is required.");
        if (blank(bo.getEmail()))     return bad("Email is required.");
        if (blank(bo.getRole()))      return bad("Role is required.");
        // Plan user limit: counted from the database (non-deleted users of this church),
        // never from what the page has loaded, so paging or a stale page cannot miscount.
        if (subscriptionService != null && appUserRepository != null) {
            String limit = subscriptionService.checkStaffUserLimit(clientId,
                    appUserRepository.countByClientIdAndDeleteFlagFalse(clientId));
            if (limit != null) return ResponseEntity.status(403).body(Map.of("error", limit, "limitReached", true));
        }
        try {
            AppUser saved = userService.create(bo);
            return ResponseEntity.ok(Map.of("id", saved.getId(), "success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        } catch (Exception ex) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to save user: " + ex.getMessage()));
        }
    }

    // ── Update ────────────────────────────────────────────────────────────

    @ResponseBody
    @PutMapping("/api/users/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Integer id,
                                                      @RequestBody AppUserBO bo,
                                                      HttpServletRequest request) {
        String clientId = ownerClientId(request);
        if (clientId == null) return forbidden();
        if (blank(bo.getFirstName())) return bad("First name is required.");
        if (blank(bo.getLastName()))  return bad("Last name is required.");
        if (blank(bo.getEmail()))     return bad("Email is required.");
        if (blank(bo.getRole()))      return bad("Role is required.");
        try {
            AppUser saved = userService.update(id, clientId, bo);
            return ResponseEntity.ok(Map.of("id", saved.getId(), "success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        } catch (Exception ex) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to update user: " + ex.getMessage()));
        }
    }

    // ── Soft-Delete ───────────────────────────────────────────────────────

    @ResponseBody
    @DeleteMapping("/api/users/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Integer id, HttpServletRequest request) {
        String clientId = ownerClientId(request);
        if (clientId == null) return forbidden();
        try {
            userService.delete(id, clientId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    // ── Toggle Enabled / Disabled ─────────────────────────────────────────

    @ResponseBody
    @PatchMapping("/api/users/{id}/toggle")
    public ResponseEntity<Map<String, Object>> toggle(@PathVariable Integer id, HttpServletRequest request) {
        String clientId = ownerClientId(request);
        if (clientId == null) return forbidden();
        try {
            AppUser u = userService.toggle(id, clientId);
            return ResponseEntity.ok(Map.of("success", true, "enabled", u.isEnabled()));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    // ── Link Accounts ─────────────────────────────────────────────────────

    /**
     * Links multiple users into a shared role-switching group.
     * Body: {@code { "userIds": [1, 2, 3] }}
     */
    @ResponseBody
    @PostMapping("/api/users/link")
    public ResponseEntity<Map<String, Object>> linkUsers(@RequestBody Map<String, Object> body,
                                                         HttpServletRequest request) {
        String clientId = ownerClientId(request);
        if (clientId == null) return forbidden();
        Object rawIds = body.get("userIds");
        if (!(rawIds instanceof List)) return bad("userIds list is required.");
        try {
            List<Integer> ids = ((List<?>) rawIds).stream()
                    .map(v -> v instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(v)))
                    .collect(Collectors.toList());
            userService.linkUsers(ids, clientId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        } catch (Exception ex) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to link users: " + ex.getMessage()));
        }
    }

    /**
     * Removes the given user from their link-group.
     * If only one user would remain in the group, that user is also unlinked.
     */
    @ResponseBody
    @DeleteMapping("/api/users/{id}/unlink")
    public ResponseEntity<Map<String, Object>> unlinkUser(@PathVariable Integer id, HttpServletRequest request) {
        String clientId = ownerClientId(request);
        if (clientId == null) return forbidden();
        try {
            userService.unlinkUser(id, clientId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        } catch (Exception ex) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to unlink user: " + ex.getMessage()));
        }
    }

    /**
     * Returns the linked-role entries for the currently authenticated user.
     * Used by the sidebar role-switcher dropdown in session.js.
     * Returns an empty list when the user has no link-group or is not authenticated.
     */
    @ResponseBody
    @GetMapping("/api/users/linked-roles")
    public ResponseEntity<List<Map<String, Object>>> getLinkedRoles(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return ResponseEntity.ok(List.of());
        Object appUserIdAttr = session.getAttribute("appUserId");
        if (!(appUserIdAttr instanceof Integer appUserId)) return ResponseEntity.ok(List.of());
        return ResponseEntity.ok(userService.getLinkedRoles(appUserId));
    }

    /**
     * Returns member accounts eligible for cross-type account linking.
     * Eligible = has an active signup record (client_id starts with MBR),
     * and the underlying FamilyMember role is NOT "Child".
     * Only accessible by Church-role sessions.
     *
     * <p>Response list items: {@code { signupId, memberRef, firstName, lastName, email, memberRole, alreadyLinked }}
     */
    @ResponseBody
    @GetMapping("/api/users/linkable-members")
    public ResponseEntity<List<Map<String, Object>>> getLinkableMembers(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return ResponseEntity.status(401).body(List.of());
        Object churchAttr = session.getAttribute("church");
        boolean isChurch = Boolean.TRUE.equals(churchAttr) || "true".equalsIgnoreCase(String.valueOf(churchAttr));
        if (!isChurch) return ResponseEntity.status(403).body(List.of());

        String churchClientId = (String) session.getAttribute("clientId");
        if (churchClientId == null || churchClientId.isBlank()) return ResponseEntity.ok(List.of());

        // Find all active member signups — their client_id is the MBR token
        List<com.churchgeniuspro.hibernate.SignUp> memberSignups =
                loginRepository.findAll().stream()
                        .filter(s -> s.getClientId() != null && s.getClientId().toUpperCase().startsWith("MBR"))
                        .filter(s -> Boolean.TRUE.equals(s.getActive()) && (s.getDeleted() == null || !s.getDeleted()))
                        .collect(Collectors.toList());

        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (com.churchgeniuspro.hibernate.SignUp signup : memberSignups) {
            com.churchgeniuspro.hibernate.FamilyMember fm =
                    familyMemberRepository.findByMemberRef(signup.getClientId()).orElse(null);
            if (fm == null) continue;

            // Scope to the current church's appClientId
            String famAppClientId = fm.getFamily() != null ? fm.getFamily().getAppClientId() : fm.getAppClientId();
            if (!churchClientId.equals(famAppClientId)) continue;

            // Exclude Child role
            String memberRole = fm.getRole() != null ? fm.getRole() : "";
            if ("Child".equalsIgnoreCase(memberRole)) continue;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("signupId",      signup.getId());
            m.put("memberRef",     signup.getClientId());
            m.put("firstName",     fm.getFirstName());
            m.put("lastName",      fm.getLastName());
            m.put("email",         fm.getEmail());
            // loginEmail is signup.username — the email used to log in; used by the
            // frontend same-email filter to match against staff app_user.email.
            m.put("loginEmail",    signup.getUsername());
            m.put("memberRole",    memberRole);
            m.put("linkGroup",     signup.getLinkGroup());
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Links app_user accounts AND/OR member signup accounts into a single
     * cross-type link-group so they can switch accounts without re-entering credentials.
     *
     * <p>Body: {@code { "appUserIds": [1, 2], "memberSignupIds": [5] }}
     * At least two total entries are required.  All entries receive the same
     * new link-group UUID.  Former group-mates no longer included are cleaned up.
     *
     * <p>Only Church-role sessions may call this endpoint.
     */
    @ResponseBody
    @PostMapping("/api/users/link-with-members")
    public ResponseEntity<Map<String, Object>> linkWithMembers(@RequestBody Map<String, Object> body,
                                                               HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated."));
        Object churchAttr = session.getAttribute("church");
        boolean isChurch = Boolean.TRUE.equals(churchAttr) || "true".equalsIgnoreCase(String.valueOf(churchAttr));
        if (!isChurch) return ResponseEntity.status(403).body(Map.of("error", "Only Church accounts can link accounts."));

        List<Integer> appUserIds = parseIntList(body.get("appUserIds"));
        List<Integer> memberSignupIds = parseIntList(body.get("memberSignupIds"));

        if (appUserIds.size() + memberSignupIds.size() < 2) {
            return bad("Select at least 2 accounts to link.");
        }
        try {
            String churchClientId = (String) session.getAttribute("clientId");
            for (Integer sid : memberSignupIds) {
                if (!memberSignupBelongsTo(sid, churchClientId)) return bad("Could not find the selected accounts.");
            }
            userService.linkWithMembers(appUserIds, memberSignupIds, loginRepository, churchClientId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        } catch (Exception ex) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to link accounts: " + ex.getMessage()));
        }
    }

    /**
     * Removes a member signup from its link-group.
     * If only one account would remain in the group, that account is also unlinked.
     * Only Church-role sessions may call this.
     */
    @ResponseBody
    @DeleteMapping("/api/users/unlink-member/{signupId}")
    public ResponseEntity<Map<String, Object>> unlinkMember(@PathVariable Integer signupId,
                                                            HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated."));
        Object churchAttr = session.getAttribute("church");
        boolean isChurch = Boolean.TRUE.equals(churchAttr) || "true".equalsIgnoreCase(String.valueOf(churchAttr));
        if (!isChurch) return ResponseEntity.status(403).body(Map.of("error", "Only Church accounts can unlink accounts."));
        try {
            if (!memberSignupBelongsTo(signupId, (String) session.getAttribute("clientId"))) {
                return bad("Member account not found.");
            }
            userService.unlinkMemberSignup(signupId, loginRepository);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        } catch (Exception ex) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to unlink member: " + ex.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Integer> parseIntList(Object raw) {
        if (!(raw instanceof List)) return List.of();
        return ((List<?>) raw).stream()
                .map(v -> v instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(v)))
                .collect(Collectors.toList());
    }

    // ── Update Privileges ─────────────────────────────────────────────────

    /**
     * Saves the privilege JSON for a specific user.
     * Body: {@code { "privileges": "{...}" }}
     */
    @ResponseBody
    @PatchMapping("/api/users/{id}/privileges")
    public ResponseEntity<Map<String, Object>> updatePrivileges(@PathVariable Integer id,
                                                                @RequestBody Map<String, Object> body,
                                                                HttpServletRequest request) {
        String clientId = ownerClientId(request);
        if (clientId == null) return forbidden();
        try {
            Object raw = body.get("privileges");
            String privilegesJson = raw != null ? raw.toString() : null;
            userService.updatePrivileges(id, clientId, privilegesJson);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        } catch (Exception ex) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to update privileges: " + ex.getMessage()));
        }
    }

    // ── Send Email ────────────────────────────────────────────────────────

    @ResponseBody
    @PostMapping("/api/users/{id}/email")
    public ResponseEntity<Map<String, Object>> sendEmail(@PathVariable Integer id, HttpServletRequest request) {
        String clientId = ownerClientId(request);
        if (clientId == null) return forbidden();
        try {
            userService.sendEmail(id, clientId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        } catch (Exception ex) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to send email: " + ex.getMessage()));
        }
    }

    // ── Permissions ───────────────────────────────────────────────────────

    /**
     * Returns the saved permission map for a user as a JSON string.
     * Response: {@code { "permissions": "<json-string-or-null>" }}
     */
    @ResponseBody
    @GetMapping("/api/users/{id}/permissions")
    public ResponseEntity<Map<String, Object>> getPermissions(@PathVariable Integer id,
                                                              HttpServletRequest request) {
        // session.js reads the CURRENT user's own map at login; the owner reads any
        // user's map from the permissions modal. Nobody reads another church's.
        if (!isSelf(request, id)) {
            String clientId = ownerClientId(request);
            if (clientId == null) return forbidden();
            try { userService.findOrThrow(id, clientId); }
            catch (IllegalArgumentException ex) { return ResponseEntity.status(404).body(Map.of("error", "User not found.")); }
        }
        Map<String, Object> res = new HashMap<>();
        permissionsRepo.findByAppUserId(id)
                .ifPresentOrElse(
                        p -> res.put("permissions", p.getPermissions()),
                        () -> res.put("permissions", null));
        return ResponseEntity.ok(res);
    }

    /**
     * Saves (or replaces) the permission map for a user.
     * Body: {@code { "permissions": "<json-string>" }}
     */
    @ResponseBody
    @PostMapping("/api/users/{id}/permissions")
    public ResponseEntity<Map<String, Object>> savePermissions(
            @PathVariable Integer id,
            @RequestBody  Map<String, Object> body,
            HttpServletRequest request) {
        // Owner-only. A staff session posting "{}" to its own id used to grant itself
        // full access (the session's privileges were refreshed in place below).
        String clientId = ownerClientId(request);
        if (clientId == null) return forbidden();
        try { userService.findOrThrow(id, clientId); }
        catch (IllegalArgumentException ex) { return ResponseEntity.status(404).body(Map.of("error", "User not found.")); }
        Object raw = body.get("permissions");
        String json = raw != null ? raw.toString() : "{}";

        UserPermissions perms = permissionsRepo.findByAppUserId(id)
                .orElseGet(UserPermissions::new);
        perms.setAppUserId(id);
        perms.setPermissions(json);
        permissionsRepo.save(perms);

        // If the saved user is the currently logged-in user, refresh the session's
        // privileges immediately so nav/permission hiding takes effect without re-login.
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object sessionUserId = session.getAttribute("appUserId");
            if (sessionUserId != null && sessionUserId.toString().equals(id.toString())) {
                session.setAttribute("privileges", json);
                com.churchgeniuspro.service.PermissionRefresher.markFresh(session);
            }
        }

        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── Member Permissions (used by viewusers.html Account Permissions modal) ─

    /**
     * Returns all active family members who have a member portal login account
     * (their signup.client_id starts with "MBR"), scoped to the current church.
     *
     * <p>Response list items:
     * {@code { id, firstName, lastName, email, role, memberRef, permissions }}
     */
    @ResponseBody
    @GetMapping("/api/members/with-logins")
    public ResponseEntity<List<Map<String, Object>>> getMembersWithLogins(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return ResponseEntity.status(401).body(List.of());
        Object churchAttr = session.getAttribute("church");
        boolean isChurch = Boolean.TRUE.equals(churchAttr)
                || "true".equalsIgnoreCase(String.valueOf(churchAttr));
        if (!isChurch) return ResponseEntity.status(403).body(List.of());

        String churchClientId = (String) session.getAttribute("clientId");
        if (churchClientId == null || churchClientId.isBlank()) return ResponseEntity.ok(List.of());

        // All active member signups (clientId starts with MBR)
        List<com.churchgeniuspro.hibernate.SignUp> memberSignups =
                loginRepository.findAll().stream()
                        .filter(s -> s.getClientId() != null
                                && s.getClientId().toUpperCase().startsWith("MBR"))
                        .filter(s -> Boolean.TRUE.equals(s.getActive())
                                && (s.getDeleted() == null || !s.getDeleted()))
                        .collect(Collectors.toList());

        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (com.churchgeniuspro.hibernate.SignUp signup : memberSignups) {
            com.churchgeniuspro.hibernate.FamilyMember fm =
                    familyMemberRepository.findByMemberRef(signup.getClientId()).orElse(null);
            if (fm == null) continue;

            // Scope to this church
            String famAppClientId = fm.getFamily() != null
                    ? fm.getFamily().getAppClientId() : fm.getAppClientId();
            if (!churchClientId.equals(famAppClientId)) continue;

            String memberRole = fm.getRole() != null ? fm.getRole() : "";

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",          fm.getId());
            m.put("signupId",    signup.getId());
            m.put("firstName",   fm.getFirstName());
            m.put("lastName",    fm.getLastName());
            m.put("email",       fm.getEmail());
            m.put("role",        memberRole);
            m.put("memberRef",   fm.getMemberRef());
            m.put("permissions", fm.getMemberPrivileges());
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Admin: edit a member portal user's basic details (name / email).
     * Body: {@code { firstName, lastName, email }}
     */
    @ResponseBody
    @PutMapping("/api/members/{id}/edit")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Map<String, Object>> editMemberPortalUser(
            @PathVariable Integer id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated."));
        Object churchAttr = session.getAttribute("church");
        boolean isChurch = Boolean.TRUE.equals(churchAttr) || "true".equalsIgnoreCase(String.valueOf(churchAttr));
        if (!isChurch) return ResponseEntity.status(403).body(Map.of("error", "Access denied."));

        com.churchgeniuspro.hibernate.FamilyMember fm = familyMemberRepository.findById(id)
                .filter(m -> belongsToChurch(m, (String) session.getAttribute("clientId")))
                .orElse(null);
        if (fm == null) return ResponseEntity.status(404).body(Map.of("error", "Member not found."));

        if (body.containsKey("firstName") && body.get("firstName") != null)
            fm.setFirstName(body.get("firstName").toString().trim());
        if (body.containsKey("lastName") && body.get("lastName") != null)
            fm.setLastName(body.get("lastName").toString().trim());
        if (body.containsKey("email") && body.get("email") != null)
            fm.setEmail(body.get("email").toString().trim());

        familyMemberRepository.save(fm);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * Admin: soft-delete a member portal user.
     * Sets deleteFlag on the FamilyMember and marks their SignUp as deleted+inactive.
     */
    @ResponseBody
    @DeleteMapping("/api/members/{id}/soft-delete")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Map<String, Object>> softDeleteMemberPortalUser(
            @PathVariable Integer id,
            HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated."));
        Object churchAttr = session.getAttribute("church");
        boolean isChurch = Boolean.TRUE.equals(churchAttr) || "true".equalsIgnoreCase(String.valueOf(churchAttr));
        if (!isChurch) return ResponseEntity.status(403).body(Map.of("error", "Access denied."));

        com.churchgeniuspro.hibernate.FamilyMember fm = familyMemberRepository.findById(id)
                .filter(m -> belongsToChurch(m, (String) session.getAttribute("clientId")))
                .orElse(null);
        if (fm == null) return ResponseEntity.status(404).body(Map.of("error", "Member not found."));

        fm.setDeleteFlag(true);
        familyMemberRepository.save(fm);

        // Also mark their SignUp (login) as deleted + inactive
        if (fm.getMemberRef() != null && !fm.getMemberRef().isBlank()) {
            loginRepository.findAll().stream()
                    .filter(s -> fm.getMemberRef().equals(s.getClientId()))
                    .forEach(s -> {
                        s.setDeleted(true);
                        s.setActive(false);
                        loginRepository.save(s);
                    });
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * Admin: send a password reset link to a member portal user's email.
     */
    @ResponseBody
    @PostMapping("/api/members/{id}/send-reset")
    public ResponseEntity<Map<String, Object>> sendMemberPasswordReset(
            @PathVariable Integer id,
            HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated."));
        Object churchAttr = session.getAttribute("church");
        boolean isChurch = Boolean.TRUE.equals(churchAttr) || "true".equalsIgnoreCase(String.valueOf(churchAttr));
        if (!isChurch) return ResponseEntity.status(403).body(Map.of("error", "Access denied."));

        com.churchgeniuspro.hibernate.FamilyMember fm = familyMemberRepository.findById(id)
                .filter(m -> belongsToChurch(m, (String) session.getAttribute("clientId")))
                .orElse(null);
        if (fm == null) return ResponseEntity.status(404).body(Map.of("error", "Member not found."));

        String memberRef = fm.getMemberRef();
        if (memberRef == null || memberRef.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "No login account linked to this member."));

        com.churchgeniuspro.hibernate.SignUp signup = loginRepository.findAll().stream()
                .filter(s -> memberRef.equals(s.getClientId())
                        && Boolean.TRUE.equals(s.getActive())
                        && (s.getDeleted() == null || !s.getDeleted()))
                .findFirst().orElse(null);
        if (signup == null)
            return ResponseEntity.badRequest().body(Map.of("error", "No active login account found for this member."));

        try {
            String username = signup.getUsername();

            // Determine target email — for Child accounts, fall back to Head of Household
            // email when the child has no email address of their own.
            boolean isChild = "Child".equalsIgnoreCase(fm.getRole());
            String childEmail = fm.getEmail();
            boolean useChildEmail = childEmail != null && !childEmail.isBlank();

            String sendToEmail;
            boolean sentToHoh = false;
            String hohName = null;
            if (useChildEmail) {
                sendToEmail = childEmail;
            } else if (isChild) {
                // Fall back to Head of Household's email
                String hohEmail = familyMemberRepository.findHohEmailByFamilyMemberId(fm.getId());
                if (hohEmail == null || hohEmail.isBlank()) {
                    return ResponseEntity.badRequest().body(Map.of("error",
                            "This child has no email address and no Head of Household email could be found."));
                }
                sendToEmail = hohEmail;
                sentToHoh   = true;
                // Look up HoH name for personalised greeting
                List<com.churchgeniuspro.hibernate.FamilyMember> heads =
                        familyMemberRepository.findHeadWithMemberRefByFamilyId(
                                fm.getFamily() != null ? fm.getFamily().getId() : -1);
                if (!heads.isEmpty()) hohName = heads.get(0).getFirstName();
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", "Member has no email address on file."));
            }

            String token     = passwordResetService.createToken(username, sendToEmail);
            String resetLink = baseUrl + "/resetPassword?token=" + token;
            String churchName = (String) session.getAttribute("churchName");
            if (churchName == null || churchName.isBlank()) churchName = "ChurchGenius Pro";

            // Greeting: address the HoH by name when sending on behalf of a child
            String greetingName = sentToHoh
                    ? (hohName != null && !hohName.isBlank() ? hohName : "Parent/Guardian")
                    : fm.getFirstName();
            String childClause  = sentToHoh
                    ? "<p>This reset link is for your child <strong>" + esc(fm.getFirstName())
                      + " " + esc(fm.getLastName() != null ? fm.getLastName() : "") + "</strong>'s account, "
                      + "since they do not have an email address on file.</p>"
                    : "";

            String htmlBody = "<div style='font-family:sans-serif;max-width:600px;margin:auto;'>"
                + "<h2 style='color:#673147;'>Password Reset</h2>"
                + "<p>Hello " + esc(greetingName) + ",</p>"
                + "<p>An administrator has requested a password reset for a " + esc(churchName) + " account.</p>"
                + childClause
                + "<p><a href='" + resetLink + "' style='background:#673147;color:#fff;padding:12px 24px;"
                + "border-radius:8px;text-decoration:none;font-weight:600;display:inline-block;"
                + "margin:12px 0;'>Reset My Password</a></p>"
                + "<p style='font-size:12px;color:#888;'>This link expires in 10 minutes. "
                + "If you did not request this, you can ignore this email.</p></div>";

            // Account recovery: delivered whatever the tenant's plan says.
            emailService.sendAccountEmail(sendToEmail, "Password Reset — " + churchName, htmlBody);
            String successMsg = sentToHoh
                    ? "Reset link sent to Head of Household (" + sendToEmail + ") on behalf of " + fm.getFirstName()
                    : "Reset link sent to " + sendToEmail;
            return ResponseEntity.ok(Map.of("success", true, "message", successMsg));
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to send reset email: " + ex.getMessage()));
        }
    }

    /**
     * Returns the memberPrivileges JSON for a specific family member.
     */
    @ResponseBody
    @GetMapping("/api/members/{id}/permissions")
    public ResponseEntity<Map<String, Object>> getMemberPermissions(@PathVariable Integer id,
                                                                    HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated."));
        Object churchAttr = session.getAttribute("church");
        boolean isChurch = Boolean.TRUE.equals(churchAttr) || "true".equalsIgnoreCase(String.valueOf(churchAttr));
        if (!isChurch) return ResponseEntity.status(403).body(Map.of("error", "Access denied."));

        com.churchgeniuspro.hibernate.FamilyMember fm = familyMemberRepository.findById(id)
                .filter(m -> belongsToChurch(m, (String) session.getAttribute("clientId")))
                .orElse(null);
        if (fm == null) return ResponseEntity.status(404).body(Map.of("error", "Member not found."));

        Map<String, Object> res = new HashMap<>();
        res.put("permissions", fm.getMemberPrivileges());
        return ResponseEntity.ok(res);
    }

    /**
     * Saves the memberPrivileges JSON for a specific family member.
     */
    @ResponseBody
    @PostMapping("/api/members/{id}/permissions")
    public ResponseEntity<Map<String, Object>> saveMemberPermissions(@PathVariable Integer id,
                                                                     @RequestBody Map<String, Object> body,
                                                                     HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated."));
        Object churchAttr = session.getAttribute("church");
        boolean isChurch = Boolean.TRUE.equals(churchAttr) || "true".equalsIgnoreCase(String.valueOf(churchAttr));
        if (!isChurch) return ResponseEntity.status(403).body(Map.of("error", "Access denied."));

        com.churchgeniuspro.hibernate.FamilyMember fm2 = familyMemberRepository.findById(id)
                .filter(m -> belongsToChurch(m, (String) session.getAttribute("clientId")))
                .orElse(null);
        if (fm2 == null) return ResponseEntity.status(404).body(Map.of("error", "Member not found."));

        Object raw = body.get("permissions");
        String json = raw != null ? raw.toString() : null;
        fm2.setMemberPrivileges(json);
        familyMemberRepository.save(fm2);
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private boolean blank(String s) { return s == null || s.isBlank(); }

    private ResponseEntity<Map<String, Object>> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ── Tenant helpers ────────────────────────────────────────────────────

    /** True when the session's own appUserId is {@code id}. */
    private static boolean isSelf(HttpServletRequest request, Integer id) {
        HttpSession session = request.getSession(false);
        if (session == null || id == null) return false;
        Object v = session.getAttribute("appUserId");
        return v != null && v.toString().equals(id.toString());
    }

    /** A family member belongs to the church whose clientId is on the family (or the member itself). */
    private static boolean belongsToChurch(com.churchgeniuspro.hibernate.FamilyMember fm, String churchClientId) {
        if (fm == null || churchClientId == null) return false;
        String owner = fm.getFamily() != null ? fm.getFamily().getAppClientId() : fm.getAppClientId();
        return churchClientId.equals(owner);
    }

    /**
     * A member signup ({@code signup.client_id = MBR…}) belongs to a church only
     * through the family member that carries that memberRef.
     */
    private boolean memberSignupBelongsTo(Integer signupId, String churchClientId) {
        if (signupId == null || churchClientId == null) return false;
        return loginRepository.findById(signupId)
                .map(su -> su.getClientId())
                .flatMap(ref -> ref == null ? java.util.Optional.empty() : familyMemberRepository.findByMemberRef(ref))
                .map(fm -> belongsToChurch(fm, churchClientId))
                .orElse(false);
    }
}
