package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.Family;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.FollowUp;
import com.churchgeniuspro.hibernate.VolunteerProfile;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.FamilyRepository;
import com.churchgeniuspro.repository.FollowUpRepository;
import com.churchgeniuspro.repository.VolunteerProfileRepository;
import com.churchgeniuspro.service.FamilyService;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.*;

/**
 * Handles the Follow-Up page and its REST API.
 *
 * <p>Page route:  GET  /followups
 * <p>API:         GET/POST/PUT/DELETE  /api/followups[/{id}]
 * <p>Complete:    POST /api/followups/{id}/complete
 *
 * <p>Role access:
 * <ul>
 *   <li>SuperAdmin / Admin — full CRUD; see all follow-ups in the org.</li>
 *   <li>User — can create; sees only follow-ups assigned to them.</li>
 * </ul>
 */
@Controller
public class FollowUpController {

    private final FollowUpRepository followUpRepo;
    private final FamilyService familyService;
    private final FamilyRepository familyRepo;
    private final FamilyMemberRepository familyMemberRepo;
    private final VolunteerProfileRepository volunteerProfileRepo;

    /** Plan people limit; optional so hand-built tests are unchanged. */
    private com.churchgeniuspro.service.SubscriptionService subscriptionService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSubscriptionService(com.churchgeniuspro.service.SubscriptionService s) { this.subscriptionService = s; }

    public FollowUpController(FollowUpRepository followUpRepo,
                              FamilyService familyService,
                              FamilyRepository familyRepo,
                              FamilyMemberRepository familyMemberRepo,
                              VolunteerProfileRepository volunteerProfileRepo) {
        this.followUpRepo = followUpRepo;
        this.familyService = familyService;
        this.familyRepo = familyRepo;
        this.familyMemberRepo = familyMemberRepo;
        this.volunteerProfileRepo = volunteerProfileRepo;
    }

    // ── Assignee directory (members + volunteers) for the searchable dropdown ──

    /**
     * Combined, tenant-scoped people directory for the "Assigned To" dropdown:
     * every church member, annotated with whether they are also a volunteer
     * ({@code type} = Member / Volunteer / Both). Inactive members are flagged
     * (the UI hides them by default). Sorted alphabetically by name.
     */
    @ResponseBody
    @GetMapping("/api/followups/assignees")
    public ResponseEntity<?> assignees(HttpServletRequest req) {
        String deny = RoleGuard.requireAdminOrUser(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String cid = resolveClientId(req);
        if (cid == null) return ResponseEntity.status(403).body(Map.of("error", "Not authenticated"));

        // familyMemberId → volunteer status (Active/Inactive) for this tenant
        Map<Integer, String> volByMember = new HashMap<>();
        for (VolunteerProfile vp : volunteerProfileRepo.findByAppClientIdAndDeleteFlagFalseOrderByFamilyMemberIdAsc(cid)) {
            volByMember.put(vp.getFamilyMemberId(), vp.getStatus() == null ? "Active" : vp.getStatus());
        }

        // All members (including inactive so the UI can offer "show inactive"); not deleted.
        List<Map<String, Object>> members = familyService.getAllMembers(
                null, null, null, null, null, false, true, false, cid);

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : members) {
            Object idObj = m.get("id");
            Integer id = (idObj instanceof Number n) ? n.intValue() : null;
            String first = str(m.get("firstName"));
            String last  = str(m.get("lastName"));
            String name  = str(m.get("displayName"));
            if (name.isEmpty()) name = (first + " " + last).trim();
            if (name.isEmpty()) name = str(m.get("nickname"));
            if (name.isEmpty()) continue;
            boolean isVolunteer = id != null && volByMember.containsKey(id);
            boolean memberInactive = Boolean.TRUE.equals(m.get("inactive"));
            boolean volInactive = isVolunteer && "Inactive".equalsIgnoreCase(volByMember.get(id));

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("name", name);
            row.put("firstName", first);
            row.put("lastName", last);
            row.put("email", str(m.get("email")));
            row.put("phone", str(m.get("phone")));
            row.put("isMember", true);
            row.put("isVolunteer", isVolunteer);
            row.put("type", isVolunteer ? "Both" : "Member");
            row.put("inactive", memberInactive || volInactive);
            out.add(row);
        }
        out.sort(Comparator.comparing(r -> String.valueOf(r.get("name")).toLowerCase()));
        return ResponseEntity.ok(out);
    }

    /**
     * Inline "Add New Volunteer" from the Assigned To field: creates a person
     * record (shell family + member, memberType "Volunteer") plus an active
     * {@link VolunteerProfile} so they immediately appear in the Volunteers list
     * and can be selected as the follow-up assignee.
     */
    @ResponseBody
    @PostMapping("/api/followups/volunteers")
    public ResponseEntity<?> addVolunteer(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String deny = RoleGuard.requireAdminOrUser(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String cid = resolveClientId(req);
        if (cid == null) return ResponseEntity.status(403).body(Map.of("error", "Not authenticated"));

        String first = str(body.get("firstName"));
        String last  = str(body.get("lastName"));
        if (first.isEmpty() && last.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "First or last name is required."));
        String email = str(body.get("email"));
        String phone = str(body.get("phone"));
        String role  = str(body.get("role"));
        String notes = str(body.get("notes"));

        // The plan's people limit applies here as on the Family form: a new person is
        // being added. Fails open on a lookup error, exactly as the Family form does.
        if (subscriptionService != null) {
            try {
                Long current = familyMemberRepo.countActiveMembers(cid);
                String limitMsg = subscriptionService.checkPeopleLimit(cid, current == null ? 0 : current, 1);
                if (limitMsg != null) return ResponseEntity.status(403).body(Map.of("error", limitMsg));
            } catch (Exception ignored) { /* fail-open: never block on a limit-check error */ }
        }

        // Shell family to hold the volunteer (family_member.family_id is NOT NULL).
        Family family = new Family();
        family.setAppClientId(cid);
        family.setInactive(false);
        family.setDeleteFlag(false);
        family = familyRepo.save(family);

        FamilyMember m = new FamilyMember();
        m.setFamily(family);
        m.setAppClientId(cid);
        m.setRole(role.isEmpty() ? "Volunteer" : role);
        m.setMemberType("Volunteer");
        m.setFirstName(first);
        m.setLastName(last);
        m.setEmail(email.isEmpty() ? null : email);
        m.setPhone(phone.isEmpty() ? null : phone);
        if (!notes.isEmpty()) m.setComments(notes);
        m = familyMemberRepo.save(m);

        VolunteerProfile vp = new VolunteerProfile();
        vp.setAppClientId(cid);
        vp.setFamilyMemberId(m.getId());
        vp.setStatus("Active");
        vp.setDeleteFlag(false);
        vp.setJoinedDate(LocalDate.now());
        if (!notes.isEmpty()) vp.setNotes(notes);
        volunteerProfileRepo.save(vp);

        String name = (first + " " + last).trim();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", m.getId());
        row.put("name", name);
        row.put("firstName", first);
        row.put("lastName", last);
        row.put("email", email);
        row.put("phone", phone);
        row.put("isMember", true);
        row.put("isVolunteer", true);
        row.put("type", "Both");
        row.put("inactive", false);
        return ResponseEntity.ok(row);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String resolveClientId(HttpServletRequest req) {
        return RoleGuard.clientId(req);
    }

    private boolean isAdmin(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return false;
        String role = (String) session.getAttribute("role");
        return "SuperAdmin".equals(role) || "Admin".equals(role);
    }

    private String currentUsername(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return null;
        Object u = session.getAttribute("username");
        return u instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    private <T> T bodyVal(Map<String, Object> body, String key) {
        return body == null ? null : (T) body.get(key);
    }

    private Date parseDate(Object val) {
        if (val == null) return null;
        String s = String.valueOf(val).trim();
        if (s.isEmpty()) return null;
        try {
            return new SimpleDateFormat("yyyy-MM-dd").parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> toMap(FollowUp f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          f.getId());
        m.put("title",       f.getTitle());
        m.put("description", f.getDescription());
        m.put("priority",    f.getPriority());
        m.put("status",      f.getStatus());
        m.put("assignedTo",  f.getAssignedTo());
        m.put("linkedType",  f.getLinkedType());
        m.put("linkedId",    f.getLinkedId());
        m.put("linkedLabel", f.getLinkedLabel());
        m.put("createdBy",   f.getCreatedBy());
        if (f.getDueDate() != null) {
            m.put("dueDate", new SimpleDateFormat("yyyy-MM-dd").format(f.getDueDate()));
        } else {
            m.put("dueDate", null);
        }
        if (f.getCreatedAt() != null) {
            m.put("createdAt", new SimpleDateFormat("yyyy-MM-dd").format(f.getCreatedAt()));
        } else {
            m.put("createdAt", null);
        }
        return m;
    }

    private void applyBody(FollowUp f, Map<String, Object> body) {
        String title = bodyVal(body, "title");
        if (title != null) f.setTitle(title.trim());

        Object desc = bodyVal(body, "description");
        f.setDescription(desc != null ? String.valueOf(desc).trim() : null);

        Object priority = bodyVal(body, "priority");
        if (priority != null) f.setPriority(String.valueOf(priority).trim());

        Object status = bodyVal(body, "status");
        if (status != null) f.setStatus(String.valueOf(status).trim());

        Object assignedTo = bodyVal(body, "assignedTo");
        f.setAssignedTo(assignedTo != null ? String.valueOf(assignedTo).trim() : null);

        Object linkedType = bodyVal(body, "linkedType");
        f.setLinkedType(linkedType != null ? String.valueOf(linkedType).trim() : null);

        Object linkedId = bodyVal(body, "linkedId");
        if (linkedId != null && !String.valueOf(linkedId).isBlank()) {
            try { f.setLinkedId(Long.parseLong(String.valueOf(linkedId))); }
            catch (NumberFormatException ignored) {}
        } else {
            f.setLinkedId(null);
        }

        Object linkedLabel = bodyVal(body, "linkedLabel");
        f.setLinkedLabel(linkedLabel != null ? String.valueOf(linkedLabel).trim() : null);

        f.setDueDate(parseDate(bodyVal(body, "dueDate")));
    }

    // ── Page route ────────────────────────────────────────────────────────

    @GetMapping("/followups")
    public String page(HttpServletRequest req) {
        String deny = RoleGuard.requireAdminOrUser(req);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(req, "more.followups");
        if (deny != null) return deny;
        return "forward:/followups.html";
    }

    // ── List ──────────────────────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/followups")
    public ResponseEntity<?> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String assignedTo,
            HttpServletRequest req) {

        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));

        String username = currentUsername(req);
        boolean admin   = isAdmin(req);

        List<FollowUp> rows;

        if (!admin) {
            // Users only see their own follow-ups
            if (status != null && !status.isBlank()) {
                rows = followUpRepo.findByClientIdAndAssignedToAndStatusAndDeleteFlagFalseOrderByCreatedAtDesc(
                        clientId, username, status);
            } else {
                rows = followUpRepo.findByClientIdAndAssignedToAndDeleteFlagFalseOrderByCreatedAtDesc(
                        clientId, username);
            }
        } else if (assignedTo != null && !assignedTo.isBlank()) {
            if (status != null && !status.isBlank()) {
                rows = followUpRepo.findByClientIdAndAssignedToAndStatusAndDeleteFlagFalseOrderByCreatedAtDesc(
                        clientId, assignedTo, status);
            } else {
                rows = followUpRepo.findByClientIdAndAssignedToAndDeleteFlagFalseOrderByCreatedAtDesc(
                        clientId, assignedTo);
            }
        } else if (status != null && !status.isBlank()) {
            rows = followUpRepo.findByClientIdAndStatusAndDeleteFlagFalseOrderByCreatedAtDesc(
                    clientId, status);
        } else {
            rows = followUpRepo.findByClientIdAndDeleteFlagFalseOrderByCreatedAtDesc(clientId);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (FollowUp f : rows) result.add(toMap(f));
        return ResponseEntity.ok(result);
    }

    // ── Get one ───────────────────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/followups/{id}")
    public ResponseEntity<?> getOne(@PathVariable Long id, HttpServletRequest req) {
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));

        Optional<FollowUp> opt = followUpRepo.findByIdAndClientIdAndDeleteFlagFalse(id, clientId);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        FollowUp f = opt.get();
        if (!isAdmin(req)) {
            String username = currentUsername(req);
            if (!Objects.equals(username, f.getAssignedTo())) {
                return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
            }
        }
        return ResponseEntity.ok(toMap(f));
    }

    // ── Create ────────────────────────────────────────────────────────────

    @ResponseBody
    @PostMapping("/api/followups")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));

        String title = body == null ? null : (String) body.get("title");
        if (title == null || title.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Title is required"));
        }

        FollowUp f = new FollowUp();
        f.setClientId(clientId);
        f.setCreatedBy(currentUsername(req));
        applyBody(f, body);
        followUpRepo.save(f);

        return ResponseEntity.ok(toMap(f));
    }

    // ── Update ────────────────────────────────────────────────────────────

    @ResponseBody
    @PutMapping("/api/followups/{id}")
    public ResponseEntity<?> update(@PathVariable Long id,
                                    @RequestBody Map<String, Object> body,
                                    HttpServletRequest req) {
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));

        Optional<FollowUp> opt = followUpRepo.findByIdAndClientIdAndDeleteFlagFalse(id, clientId);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        FollowUp f = opt.get();

        // Users can only update their own follow-ups
        if (!isAdmin(req)) {
            String username = currentUsername(req);
            if (!Objects.equals(username, f.getAssignedTo())) {
                return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
            }
        }

        applyBody(f, body);
        followUpRepo.save(f);
        return ResponseEntity.ok(toMap(f));
    }

    // ── Mark complete ─────────────────────────────────────────────────────

    @ResponseBody
    @PostMapping("/api/followups/{id}/complete")
    public ResponseEntity<?> complete(@PathVariable Long id, HttpServletRequest req) {
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));

        Optional<FollowUp> opt = followUpRepo.findByIdAndClientIdAndDeleteFlagFalse(id, clientId);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        FollowUp f = opt.get();
        if (!isAdmin(req)) {
            String username = currentUsername(req);
            if (!Objects.equals(username, f.getAssignedTo())) {
                return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
            }
        }

        f.setStatus("COMPLETED");
        followUpRepo.save(f);
        return ResponseEntity.ok(toMap(f));
    }

    // ── Delete (soft) ─────────────────────────────────────────────────────

    @ResponseBody
    @DeleteMapping("/api/followups/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, HttpServletRequest req) {
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));

        // Only admins can delete
        if (!isAdmin(req)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }

        Optional<FollowUp> opt = followUpRepo.findByIdAndClientIdAndDeleteFlagFalse(id, clientId);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        FollowUp f = opt.get();
        f.setDeleteFlag(true);
        followUpRepo.save(f);
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    // ── Counts (for dashboard) ─────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/followups/counts")
    public ResponseEntity<?> counts(HttpServletRequest req) {
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));

        long pending   = followUpRepo.countByClientIdAndStatusAndDeleteFlagFalse(clientId, "PENDING");
        long missed    = followUpRepo.countByClientIdAndStatusAndDeleteFlagFalse(clientId, "MISSED");
        long completed = followUpRepo.countByClientIdAndStatusAndDeleteFlagFalse(clientId, "COMPLETED");

        return ResponseEntity.ok(Map.of(
                "pending",   pending,
                "missed",    missed,
                "completed", completed
        ));
    }

    // ── By linked record ──────────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/followups/linked")
    public ResponseEntity<?> byLinked(
            @RequestParam String linkedType,
            @RequestParam Long linkedId,
            HttpServletRequest req) {

        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));

        List<FollowUp> rows = followUpRepo
                .findByClientIdAndLinkedTypeAndLinkedIdAndDeleteFlagFalseOrderByCreatedAtDesc(
                        clientId, linkedType, linkedId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (FollowUp fu : rows) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id",          fu.getId());
            m.put("note",        fu.getDescription() != null ? fu.getDescription() : "");
            m.put("createdBy",   fu.getCreatedBy()   != null ? fu.getCreatedBy()   : "");
            m.put("createdAt",   fu.getCreatedAt()   != null ? fu.getCreatedAt().toString() : "");
            m.put("linkedType",  fu.getLinkedType()  != null ? fu.getLinkedType()  : "");
            m.put("linkedId",    fu.getLinkedId());
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }
}
