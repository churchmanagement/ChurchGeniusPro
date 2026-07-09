package com.churchgeniuspro.controller;

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

    private final FamilyService familyService;

    public FamilyController(FamilyService familyService) {
        this.familyService = familyService;
    }

    // ── Page routes ───────────────────────────────────────────────────────

    /** Serves the add/edit family page. */
    @GetMapping("/family")
    public String familyPage(HttpServletRequest request) {
        // Members with admin.family permission may view (but not edit) family records
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        boolean isMember = session != null
                && "Member".equals(session.getAttribute("role"))
                && session.getAttribute("memberId") != null;
        if (isMember) {
            String deny = RoleGuard.requireMemberPermission(request, "admin.family");
            return deny != null ? deny : "forward:/family.html";
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
        // Members with admin.family permission are allowed through
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        boolean isMember = session != null
                && "Member".equals(session.getAttribute("role"))
                && session.getAttribute("memberId") != null;
        if (isMember) {
            String deny = RoleGuard.requireMemberPermission(request, "admin.family");
            return deny != null ? deny : "forward:/viewfamily.html";
        }
        String deny = RoleGuard.requireAdminOrAccountant(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePermission(request, "admin.family");
        if (deny != null) return deny;
        return "forward:/viewfamily.html";
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
        // Add and Edit are combined under admin.family.edit
        String deny = RoleGuard.requirePermission(request, "admin.family.edit");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        if (bo.getMembers() == null || bo.getMembers().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "At least one family member is required."));
        }
        String appClientId = SessionUtil.getAppClientId(request);
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
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to save family: " + ex.getMessage()));
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
            @RequestParam(defaultValue = "0")     int     page,
            @RequestParam(defaultValue = "10")    int     pageSize,
            HttpServletRequest request) {
        String appClientId = SessionUtil.getAppClientId(request);
        return ResponseEntity.ok(
                familyService.getFamilies(search, showDeleted, showInactive, appClientId, page, pageSize));
    }

    // ── Single-family detail ──────────────────────────────────────────────

    /**
     * Returns the full details of one family, including its complete member list.
     * Consumed by {@code family.html} in both view-mode and edit-mode.
     */
    @ResponseBody
    @GetMapping("/api/families/{id}")
    public ResponseEntity<Map<String, Object>> getFamilyById(@PathVariable Integer id) {
        try {
            return ResponseEntity.ok(familyService.getById(id));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to load family: " + ex.getMessage()));
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
        String deny = RoleGuard.requirePermission(request, "admin.family.edit");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        if (bo.getMembers() == null || bo.getMembers().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "At least one family member is required."));
        }
        try {
            Family saved = familyService.update(id, bo);
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
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to update family: " + ex.getMessage()));
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
        String deny = RoleGuard.requirePermission(request, "admin.family.delete");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        try {
            familyService.softDelete(id);
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
        String deny = RoleGuard.requirePermission(request, "admin.family.delete");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        try {
            familyService.softDeleteMember(familyId, memberId);
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
    public ResponseEntity<Map<String, Object>> restoreFamily(@PathVariable Integer id) {
        try {
            familyService.restore(id);
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
        String deny = RoleGuard.requirePermission(request, "admin.family.inactive");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        try {
            familyService.setInactive(id, value);
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
        String deny = RoleGuard.requirePermission(request, permKey);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        try {
            @SuppressWarnings("unchecked")
            List<Integer> ids = (List<Integer>) body.get("ids");
            String action = (String) body.get("action");

            if (ids == null || ids.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "No ids provided"));
            }
            int count = familyService.bulkAction(ids, action);
            return ResponseEntity.ok(Map.of("count", count));
        } catch (Exception ex) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", ex.getMessage()));
        }
    }
}
