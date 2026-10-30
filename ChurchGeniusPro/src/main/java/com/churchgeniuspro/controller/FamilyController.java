package com.churchgeniuspro.controller;

import com.churchgeniuspro.util.ErrorResponses;
import com.churchgeniuspro.hibernate.Family;
import com.churchgeniuspro.model.FamilyBO;
import com.churchgeniuspro.service.FamilyService;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles requests for the Family pages and the family REST API.
 *
 * <h3>Page routes</h3>
 * <ul>
 *   <li>{@code GET  /family}      → {@code family.html} (add / edit)</li>
 *   <li>{@code GET  /viewfamily}  → {@code viewFamily.html} (list)</li>
 * </ul>
 *
 * <h3>API routes</h3>
 * <ul>
 *   <li>{@code POST   /api/family}                    → save a new family</li>
 *   <li>{@code GET    /api/families}                  → list families (filtered)</li>
 *   <li>{@code DELETE /api/families/{id}}             → soft-delete</li>
 *   <li>{@code PATCH  /api/families/{id}/restore}     → un-delete</li>
 *   <li>{@code PATCH  /api/families/{id}/inactive}    → set inactive flag</li>
 *   <li>{@code POST   /api/families/bulk}             → bulk delete / inactive</li>
 * </ul>
 */
@Controller
public class FamilyController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FamilyController.class);

    private final FamilyService familyService;
    private final com.churchgeniuspro.repository.FamilyMemberRepository familyMemberRepository;
    private final com.churchgeniuspro.service.SubscriptionService subscriptionService;

    /** Optional: lets a staff login that uses the Member Portal be matched to its own family. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.repository.AppUserRepository appUserRepository;
    private com.churchgeniuspro.service.MemberSessionResolver memberSessionResolver;

    public FamilyController(FamilyService familyService,
                            com.churchgeniuspro.repository.FamilyMemberRepository familyMemberRepository,
                            com.churchgeniuspro.service.SubscriptionService subscriptionService) {
        this.familyService = familyService;
        this.familyMemberRepository = familyMemberRepository;
        this.subscriptionService = subscriptionService;
    }

    // ── Page routes ───────────────────────────────────────────────────────

    /** Serves the add/edit family page. */
    @GetMapping("/family")
    public String familyPage(HttpServletRequest request) {
        // Member Portal logins only when the church explicitly granted Family access
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        if (com.churchgeniuspro.service.MemberSessionResolver.isMemberSession(session)) {
            return memberHasFamilyGrant(session) ? "forward:/family.html" : RoleGuard.FORWARD_ACCESS_DENIED;
        }
        String deny = RoleGuard.requireAdminOrAccountant(request);
        if (deny != null) return deny;
        return "forward:/family.html";
    }

    /**
     * POST entry-point for navigating to the family page without exposing
     * the record ID in the URL. Stores {id, mode} in the HTTP session and
     * redirects to GET /family (clean URL).
     */
    @PostMapping("/family")
    public String familyPagePost(@RequestParam Integer id,
                                 @RequestParam(defaultValue = "view") String mode,
                                 HttpServletRequest request,
                                 HttpSession session) {
        // Members with admin.family permission may navigate to view family records
        boolean isMember = "Member".equals(session.getAttribute("role"))
                && session.getAttribute("memberId") != null;
        if (isMember) {
            String deny = RoleGuard.requireMemberPermission(request, "admin.family");
            if (deny != null) return deny;
        } else {
            String deny = RoleGuard.requireAdminOrAccountant(request);
            if (deny != null) return deny;
        }
        session.setAttribute("navFamilyId",   id);
        session.setAttribute("navFamilyMode", mode);
        return "redirect:/family";
    }

    /**
     * Returns the pending navigation context stored by POST /family (or any other
     * POST nav handler) and immediately removes it from the session so it is
     * consumed exactly once.
     */
    @GetMapping("/api/nav-context")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> navContext(HttpSession session) {
        Map<String, Object> ctx = new HashMap<>();
        Object id   = session.getAttribute("navFamilyId");
        Object mode = session.getAttribute("navFamilyMode");
        if (id != null) {
            ctx.put("id",   id);
            ctx.put("mode", mode != null ? mode : "view");
            session.removeAttribute("navFamilyId");
            session.removeAttribute("navFamilyMode");
        }
        return ResponseEntity.ok(ctx);
    }

    /** Serves the family list page. */
    @GetMapping("/viewfamily")
    public String viewFamilyPage(HttpServletRequest request) {
        // Member Portal logins only when the church explicitly granted Family access
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        if (com.churchgeniuspro.service.MemberSessionResolver.isMemberSession(session)) {
            String deny = memberHasFamilyGrant(session) ? null : RoleGuard.FORWARD_ACCESS_DENIED;
            // NOTE: the static file is viewFamily.html (capital F). The forward target
            // must match exactly — classpath (jar) resource lookup is case-sensitive,
            // so "viewfamily.html" 404s when running the packaged JAR even though it
            // happens to work from a case-insensitive Windows filesystem.
            return deny != null ? deny : "forward:/viewFamily.html";
        }
        String deny = RoleGuard.requireAdminOrAccountant(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(request, "admin.family");
        if (deny != null) return deny;
        return "forward:/viewFamily.html";
    }

    /** Serves the all-members list page. */
    @GetMapping("/members")
    public String membersPage(HttpServletRequest request) {
        String deny = RoleGuard.requireAdmin(request);
        if (deny != null) return deny;
        return "forward:/members.html";
    }

    /** Serves the family report page (select-one view). */
    @GetMapping("/familyReports")
    public String familyReportsPage(HttpServletRequest request) {
        String deny = RoleGuard.requireAdmin(request);
        if (deny != null) return deny;
        return "forward:/familyReports.html";
    }

    /** Serves the bulk family report page (ids= query param, opened from viewFamily). */
    @GetMapping("/familyReport")
    public String familyReportBulkPage(HttpServletRequest request) {
        String deny = RoleGuard.requireAdmin(request);
        if (deny != null) return deny;
        return "forward:/familyReports.html";
    }

    // ── All Members ───────────────────────────────────────────────────────

    /**
     * Returns non-deleted family members together with the parent family's id and name.
     * When {@code search} is provided the results are filtered to members whose first or
     * last name contains the query string (case-insensitive). Used by the Members list
     * page ({@code members.html}) and the member-search autocomplete on the Family form.
     */
    @ResponseBody
    @GetMapping("/api/members")
    public ResponseEntity<List<Map<String, Object>>> getAllMembers(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String role,
            @RequestParam(defaultValue = "") String memberType,
            @RequestParam(defaultValue = "") String phone,
            @RequestParam(defaultValue = "") String email,
            @RequestParam(defaultValue = "false") boolean showDeleted,
            @RequestParam(defaultValue = "false") boolean showInactive,
            @RequestParam(defaultValue = "false") boolean includeInContributions,
            HttpServletRequest request) {
        String appClientId = SessionUtil.getAppClientId(request);
        if (memberWithoutFamilyGrant(request.getSession(false))) {
            // Member Portal pages (e.g. Worship pick-lists) need names only: never another
            // person's contact details, birthday or status, and never deleted/inactive people.
            List<Map<String, Object>> rows = familyService.getAllMembers(search, role, memberType, "", "",
                    false, false, includeInContributions, appClientId);
            return ResponseEntity.ok(rows.stream().map(FamilyController::namesOnly).toList());
        }
        return ResponseEntity.ok(familyService.getAllMembers(search, role, memberType, phone, email,
                showDeleted, showInactive, includeInContributions, appClientId));
    }

    // ── Save ──────────────────────────────────────────────────────────────

    /**
     * Saves a new family together with all its members in one transaction.
     *
     * @param bo JSON body: {@code { familyName, inactive, members:[...] }}
     * @return 200 with {@code { id, familyName, members }} on success,
     *         400 on validation failure, 500 on unexpected error
     */
    @ResponseBody
    @PostMapping("/api/family")
    public ResponseEntity<Map<String, Object>> saveFamily(@RequestBody FamilyBO bo,
                                                          HttpServletRequest request) {
        // Add and Edit are combined under admin.family.edit. Creating a family is for
        // family managers (and members granted Family access) only.
        String deny = changeGuard(request, "admin.family.edit", null);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        if (bo.getMembers() == null || bo.getMembers().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "At least one family member is required."));
        }
        String appClientId = SessionUtil.getAppClientId(request);

        // Subscription plan: maximum-people limit (POST /api/family always
        // creates a new family; updates go through PUT /api/families/{id}).
        try {
            long current = familyMemberRepository.countActiveMembers(appClientId);
            String limitMsg = subscriptionService.checkPeopleLimit(
                    appClientId, current, bo.getMembers().size());
            if (limitMsg != null) {
                return ResponseEntity.status(403).body(Map.of("error", limitMsg));
            }
        } catch (Exception e) {
            // fail-open by design (a counting error must not block a save) — but logged (audit 2026-10-28, M3)
            log.warn("[PeopleLimit] limit check failed for client {} on family create; allowing — {}",
                    appClientId, com.churchgeniuspro.util.LogSafe.exception(e));
        }

        try {
            Family saved = familyService.save(bo, appClientId);
            String primaryName = saved.getMembers().stream()
                    .filter(m -> !m.isDeleteFlag())
                    .filter(m -> "Head".equalsIgnoreCase(m.getRole())
                              || "Head of Household".equalsIgnoreCase(m.getRole()))
                    .findFirst()
                    .map(m -> ((m.getFirstName() != null ? m.getFirstName() : "") + " "
                             + (m.getLastName()  != null ? m.getLastName()  : "")).trim())
                    .orElse(saved.getMembers().stream().filter(m -> !m.isDeleteFlag()).findFirst()
                            .map(m -> ((m.getFirstName() != null ? m.getFirstName() : "") + " "
                                     + (m.getLastName()  != null ? m.getLastName()  : "")).trim())
                            .orElse(""));
            return ResponseEntity.ok(Map.of(
                    "id",          saved.getId(),
                    "familyName",  primaryName,
                    "members",     saved.getMembers().size()
            ));
        } catch (Exception ex) {
            return ErrorResponses.internal(log, "Failed to save family", ex);
        }
    }

    // ── List ──────────────────────────────────────────────────────────────

    /**
     * Returns a paginated list of families matching the supplied filter parameters,
     * sorted alphabetically by family name.
     *
     * @param search       substring match on family name (empty = no filter)
     * @param showDeleted  include soft-deleted families when {@code true}
     * @param showInactive include inactive families when {@code true}
     * @param familyType   {@code ""} all · {@code member} · {@code nonmember} (Guest/Visitor
     *                     primaries) · or one type such as {@code Guest} / {@code Visitor}
     * @param page         0-based page number (default 0); pass -1 to return all results
     * @param pageSize     items per page (default 25); ignored when page == -1
     * @return JSON object {@code {"items": [...], "total": N}}
     */
    @ResponseBody
    @GetMapping("/api/families")
    public ResponseEntity<Map<String, Object>> getFamilies(
            @RequestParam(defaultValue = "")      String  search,
            @RequestParam(defaultValue = "false") boolean showDeleted,
            @RequestParam(defaultValue = "false") boolean showInactive,
            @RequestParam(defaultValue = "")      String  familyType,
            @RequestParam(defaultValue = "0")     int     page,
            @RequestParam(defaultValue = "10")    int     pageSize,
            HttpServletRequest request) {
        // The church-wide family list is staff data: a Member Portal login needs the
        // explicit Family grant (it was readable by any signed-in login before 2026-10-29).
        if (memberWithoutFamilyGrant(request.getSession(false))) {
            return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        }
        String appClientId = SessionUtil.getAppClientId(request);
        return ResponseEntity.ok(
                familyService.getFamilies(search, showDeleted, showInactive, familyType, appClientId, page, pageSize));
    }

    // ── Single-family detail ──────────────────────────────────────────────

    /**
     * Returns the full details of one family, including its complete member list.
     * Consumed by {@code family.html} in both view-mode and edit-mode.
     */
    @ResponseBody
    @GetMapping("/api/families/{id}")
    public ResponseEntity<Map<String, Object>> getFamilyById(@PathVariable Integer id,
                                                             HttpServletRequest request) {
        String deny = familyPageGuard(request);
        // Anyone may read their OWN family (Member Portal → Family); other families need the staff rule.
        if (deny != null && !isOwnFamily(request, id)) {
            return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        }
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        try {
            return ResponseEntity.ok(familyService.getById(id, appClientId));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (Exception ex) {
            return ErrorResponses.internal(log, "Failed to load family", ex);
        }
    }

    // ── Update ────────────────────────────────────────────────────────────

    /**
     * Updates an existing family's name, inactive flag, and member list.
     * Replaces the entire member collection — any members omitted from the
     * request body are removed.
     *
     * @param bo JSON body: {@code { familyName, inactive, members:[...] }}
     */
    @ResponseBody
    @org.springframework.web.bind.annotation.PutMapping("/api/families/{id}")
    public ResponseEntity<Map<String, Object>> updateFamily(@PathVariable Integer id,
                                                            @RequestBody FamilyBO bo,
                                                            HttpServletRequest request) {
        // Family managers / granted members: admin.family.edit. Everyone else: own family only.
        String deny = changeGuard(request, "admin.family.edit", id);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        if (bo.getMembers() == null || bo.getMembers().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "At least one family member is required."));
        }
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));

        // Subscription plan: the maximum-people limit applies here too. It was
        // enforced only on POST, so a church at its limit could keep adding people
        // indefinitely by putting them into an existing family. Only members with no
        // id are new; editing or removing the existing ones is never blocked.
        try {
            int adding = (int) bo.getMembers().stream()
                    .filter(m -> m.getId() == null || m.getId() == 0)
                    .count();
            if (adding > 0) {
                long current = familyMemberRepository.countActiveMembers(appClientId);
                String limitMsg = subscriptionService.checkPeopleLimit(appClientId, current, adding);
                if (limitMsg != null) return ResponseEntity.status(403).body(Map.of("error", limitMsg));
            }
        } catch (Exception e) {
            log.warn("[PeopleLimit] limit check failed for client {} on family edit; allowing — {}",
                    appClientId, com.churchgeniuspro.util.LogSafe.exception(e));
        }

        try {
            Family saved = familyService.update(id, bo, appClientId);
            String primaryName = saved.getMembers().stream()
                    .filter(m -> !m.isDeleteFlag())
                    .filter(m -> "Head".equalsIgnoreCase(m.getRole())
                              || "Head of Household".equalsIgnoreCase(m.getRole()))
                    .findFirst()
                    .map(m -> ((m.getFirstName() != null ? m.getFirstName() : "") + " "
                             + (m.getLastName()  != null ? m.getLastName()  : "")).trim())
                    .orElse(saved.getMembers().stream().filter(m -> !m.isDeleteFlag()).findFirst()
                            .map(m -> ((m.getFirstName() != null ? m.getFirstName() : "") + " "
                                     + (m.getLastName()  != null ? m.getLastName()  : "")).trim())
                            .orElse(""));
            return ResponseEntity.ok(Map.of(
                    "id",         saved.getId(),
                    "familyName", primaryName,
                    "members",    saved.getMembers().size()
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (Exception ex) {
            return ErrorResponses.internal(log, "Failed to update family", ex);
        }
    }

    // ── Soft-Delete ───────────────────────────────────────────────────────

    /**
     * Soft-deletes a family by setting {@code delete_flag = true}.
     * The record is NOT physically removed from the database.
     */
    @ResponseBody
    @DeleteMapping("/api/families/{id}")
    public ResponseEntity<Map<String, Object>> deleteFamily(@PathVariable Integer id,
                                                            HttpServletRequest request) {
        String deny = changeGuard(request, "admin.family.delete", null);   // never via "own family"
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        try {
            familyService.softDelete(id, appClientId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    // ── Soft-Delete Member ────────────────────────────────────────────────

    /**
     * Soft-deletes a single family member by setting its {@code delete_flag = true}.
     * Called from the family edit page when the user removes a member who already
     * exists in the database.
     */
    @ResponseBody
    @DeleteMapping("/api/families/{familyId}/members/{memberId}")
    public ResponseEntity<Map<String, Object>> deleteMember(@PathVariable Integer familyId,
                                                            @PathVariable Integer memberId,
                                                            HttpServletRequest request) {
        // Member Portal → Family can remove a person from the user's OWN family.
        String deny = changeGuard(request, "admin.family.delete", familyId);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        try {
            familyService.softDeleteMember(familyId, memberId, appClientId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    // ── Restore ───────────────────────────────────────────────────────────

    /**
     * Restores a soft-deleted family by clearing its {@code delete_flag}.
     */
    @ResponseBody
    @PatchMapping("/api/families/{id}/restore")
    public ResponseEntity<Map<String, Object>> restoreFamily(@PathVariable Integer id,
                                                             HttpServletRequest request) {
        String deny = familyPageGuard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        try {
            familyService.restore(id, appClientId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    // ── Inactive ──────────────────────────────────────────────────────────

    /**
     * Sets the {@code inactive} flag of a family.
     *
     * @param value {@code true} to mark inactive, {@code false} to re-activate
     */
    @ResponseBody
    @PatchMapping("/api/families/{id}/inactive")
    public ResponseEntity<Map<String, Object>> setInactive(
            @PathVariable Integer id,
            @RequestParam  boolean value,
            HttpServletRequest request) {
        String deny = changeGuard(request, "admin.family.inactive", null);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        try {
            familyService.setInactive(id, value, appClientId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    // ── Bulk Operations ───────────────────────────────────────────────────

    /**
     * Applies a bulk action to multiple families in one transaction.
     *
     * <p>Request body: {@code { "ids": [1, 2, 3], "action": "delete" | "inactive" }}
     *
     * @return 200 with {@code { success, affected }} on success, 400 on error
     */
    @ResponseBody
    @PostMapping("/api/families/bulk")
    public ResponseEntity<Map<String, Object>> bulkAction(
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        String action0 = body.get("action") instanceof String s ? s : "";
        // Require the relevant sub-permission for the bulk action
        String permKey = "delete".equalsIgnoreCase(action0)   ? "admin.family.delete"
                       : "inactive".equalsIgnoreCase(action0) ? "admin.family.inactive"
                       : "admin.family";
        String deny = changeGuard(request, permKey, null);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        try {
            @SuppressWarnings("unchecked")
            List<Integer> ids = (List<Integer>) body.get("ids");
            String action = (String) body.get("action");

            if (ids == null || ids.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "No ids provided"));
            }
            int count = familyService.bulkAction(ids, action, appClientId);
            return ResponseEntity.ok(Map.of("count", count));
        } catch (Exception ex) {
            return ErrorResponses.internal(log, "The request could not be completed", ex);
        }
    }

    // ── Guards ────────────────────────────────────────────────────────────

    /**
     * Same role gate as the {@code /viewfamily} page route: a member-portal session
     * needs the Family grant ({@link #memberHasFamilyGrant}); staff must be Admin /
     * Accountant / SuperAdmin with {@code admin.family} enabled.
     */
    static String familyPageGuard(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (com.churchgeniuspro.service.MemberSessionResolver.isMemberSession(session)) {
            return memberHasFamilyGrant(session) ? null : RoleGuard.FORWARD_ACCESS_DENIED;
        }
        String deny = RoleGuard.requireAdminOrAccountant(request);
        if (deny != null) return deny;
        return RoleGuard.requirePermission(request, "admin.family");
    }

    // ── Who may see and change families (security fix 2026-10-29) ─────────
    //
    // Before: GET /api/families and GET /api/members had no check at all, every change
    // endpoint checked only a permission key (a Member Portal login carries no staff
    // permission map, so a missing key read as "allowed"), and GET /api/families/{id}
    // let any member through. A member could read and overwrite any family of the church.
    //
    // Now:
    //  - family managers = SuperAdmin / Admin / Accountant staff logins (the /viewfamily
    //    roles), plus Member Portal logins the church explicitly granted Family access,
    //    keep the permission-key checks they had;
    //  - everyone else may read and update only their OWN family and remove a person
    //    from it (Member Portal → Family), resolved by MemberSessionResolver — the same
    //    rule the portal uses to show "my family";
    //  - church-wide lists are refused to Member Portal logins without the grant, and
    //    /api/members returns names only to them.

    private static final java.util.Set<String> FAMILY_MANAGER_ROLES = java.util.Set.of("SuperAdmin", "Admin", "Accountant");

    /** A staff login with a family-manager role (not a church login, not a Member Portal login). */
    static boolean isFamilyManager(HttpSession session) {
        if (session == null || session.getAttribute("username") == null) return false;
        if (com.churchgeniuspro.service.MemberSessionResolver.isMemberSession(session)) return false;
        Object church = session.getAttribute("church");
        if (Boolean.TRUE.equals(church) || "true".equalsIgnoreCase(String.valueOf(church))) return false;
        return FAMILY_MANAGER_ROLES.contains(String.valueOf(session.getAttribute("role")));
    }

    /**
     * A Member Portal login the church owner explicitly granted the staff Families pages
     * ("Family", {@code admin.family} or one of its actions, ticked). Opt-in: a member whose
     * permissions were never saved does NOT have it — the Permissions screen saves it off
     * by default for Member Portal logins.
     */
    static boolean memberHasFamilyGrant(HttpSession session) {
        if (!com.churchgeniuspro.service.MemberSessionResolver.isMemberSession(session)) return false;
        com.churchgeniuspro.service.PermissionRefresher.refreshIfStale(session);
        Object privileges = session.getAttribute("memberPrivileges");
        if (privileges == null) return false;
        try {
            Map<?, ?> map = new com.fasterxml.jackson.databind.ObjectMapper().readValue(String.valueOf(privileges), Map.class);
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                if ((key.equals("admin.family") || key.startsWith("admin.family.")) && Boolean.TRUE.equals(e.getValue())) return true;
            }
        } catch (Exception ignored) { /* unreadable map: no grant */ }
        return false;
    }

    /** A Member Portal login without the Family grant: names-only lists, no church-wide family data. */
    static boolean memberWithoutFamilyGrant(HttpSession session) {
        return com.churchgeniuspro.service.MemberSessionResolver.isMemberSession(session) && !memberHasFamilyGrant(session);
    }

    /**
     * Gate for every change. Family managers and granted members keep the permission-key
     * check ({@code permKey}). Anyone else is allowed only when {@code ownFamilyId} is the
     * family being changed and it is the session's own family; pass {@code null} for actions
     * that are never allowed on the strength of "own family" (create, delete family,
     * inactive, bulk).
     */
    String changeGuard(HttpServletRequest request, String permKey, Integer ownFamilyId) {
        HttpSession session = request.getSession(false);
        if (session == null) return RoleGuard.REDIRECT_LOGIN;
        if (isFamilyManager(session) || memberHasFamilyGrant(session)) return RoleGuard.requirePermission(request, permKey);
        if (ownFamilyId != null && isOwnFamily(request, ownFamilyId)) return null;
        return RoleGuard.FORWARD_ACCESS_DENIED;
    }

    /**
     * True when {@code familyId} is the family of the person this session belongs to
     * (member login, member token, or a staff login matched to its own member record) —
     * and, for a Member Portal login, the church has not switched its Family tab off.
     */
    boolean isOwnFamily(HttpServletRequest request, Integer familyId) {
        HttpSession session = request.getSession(false);
        if (session == null || familyId == null) return false;
        if (com.churchgeniuspro.service.MemberSessionResolver.isMemberSession(session)
                && RoleGuard.requireMemberPermission(request, "member.family") != null) return false;
        try {
            Integer own = memberResolver().resolve(session)
                    .map(r -> r.member().getFamily() != null ? r.member().getFamily().getId() : null)
                    .orElse(null);
            return familyId.equals(own);
        } catch (Exception e) {
            log.warn("[FamilyAccess] own-family check failed — refusing: {}", com.churchgeniuspro.util.LogSafe.exception(e));
            return false;
        }
    }

    private com.churchgeniuspro.service.MemberSessionResolver memberResolver() {
        if (memberSessionResolver == null) {
            memberSessionResolver = new com.churchgeniuspro.service.MemberSessionResolver(familyMemberRepository, appUserRepository);
        }
        return memberSessionResolver;
    }

    /** The fields a Member Portal page may see about other people: names and household only. */
    static Map<String, Object> namesOnly(Map<String, Object> row) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (String k : List.of("id", "firstName", "middleName", "lastName", "nickname", "displayName", "familyId", "familyName", "role")) {
            if (row.containsKey(k)) out.put(k, row.get(k));
        }
        return out;
    }
}
