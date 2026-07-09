package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.SmsService;
import com.churchgeniuspro.service.WebPushService;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.function.Function;

/**
 * REST API for the Volunteer Setup module.
 *
 * Admin endpoints: /api/volunteers/**  (require Admin or SuperAdmin)
 * Member endpoints: /api/volunteers/my/** (require active member session)
 */
@RestController
@RequestMapping("/api/volunteers")
public class VolunteerController {

    private final VolunteerRoleRepository        roleRepo;
    private final VolunteerProfileRepository     profileRepo;
    private final VolunteerProfileRoleRepository profileRoleRepo;
    private final VolunteerAssignmentRepository  assignmentRepo;
    private final VolunteerAttendanceRepository  attendanceRepo;
    private final FamilyMemberRepository         memberRepo;
    private final FamilyRepository               familyRepo;
    private final ChurchEventRepository          eventRepo;
    private final EmailService                   emailService;
    private final SmsService                     smsService;
    private final WebPushService                 pushService;

    public VolunteerController(VolunteerRoleRepository roleRepo,
                               VolunteerProfileRepository profileRepo,
                               VolunteerProfileRoleRepository profileRoleRepo,
                               VolunteerAssignmentRepository assignmentRepo,
                               VolunteerAttendanceRepository attendanceRepo,
                               FamilyMemberRepository memberRepo,
                               FamilyRepository familyRepo,
                               ChurchEventRepository eventRepo,
                               EmailService emailService,
                               SmsService smsService,
                               WebPushService pushService) {
        this.roleRepo        = roleRepo;
        this.profileRepo     = profileRepo;
        this.profileRoleRepo = profileRoleRepo;
        this.assignmentRepo  = assignmentRepo;
        this.attendanceRepo  = attendanceRepo;
        this.memberRepo      = memberRepo;
        this.familyRepo      = familyRepo;
        this.eventRepo       = eventRepo;
        this.emailService    = emailService;
        this.smsService      = smsService;
        this.pushService     = pushService;
    }

    // ════════════════════════════════════════════════════════
    // ROLES  (admin)
    // ════════════════════════════════════════════════════════

    @GetMapping("/roles")
    public ResponseEntity<?> listRoles(HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        return ResponseEntity.ok(roleRepo
                .findByAppClientIdAndDeleteFlagFalseOrderByMinistryAscRoleNameAsc(cid)
                .stream().map(this::roleMap).toList());
    }

    @PostMapping("/roles")
    public ResponseEntity<?> saveRole(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);

        Long id = toLong(body.get("id"));
        VolunteerRole role = id != null ? roleRepo.findById(id).orElse(new VolunteerRole()) : new VolunteerRole();
        role.setAppClientId(cid);
        role.setRoleName(str(body, "roleName"));
        role.setMinistry(str(body, "ministry"));
        role.setDescription(str(body, "description"));
        role.setMaxCapacity(toInt(body.get("maxCapacity")));
        roleRepo.save(role);
        return ResponseEntity.ok(Map.of("success", true, "id", role.getId()));
    }

    @DeleteMapping("/roles/{id}")
    public ResponseEntity<?> deleteRole(@PathVariable Long id, HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        VolunteerRole role = roleRepo.findById(id).orElse(null);
        if (role == null || !cid.equals(role.getAppClientId()))
            return ResponseEntity.status(404).body(Map.of("error", "Not found"));
        role.setDeleteFlag(true);
        roleRepo.save(role);
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ════════════════════════════════════════════════════════
    // VOLUNTEER PROFILES  (admin)
    // ════════════════════════════════════════════════════════

    @GetMapping("/profiles")
    public ResponseEntity<?> listProfiles(HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        List<VolunteerProfile> profiles = profileRepo
                .findByAppClientIdAndDeleteFlagFalseOrderByFamilyMemberIdAsc(cid);

        // Build role-name lookup
        Map<Long, String> roleNames = roleRepo
                .findByAppClientIdAndDeleteFlagFalseOrderByMinistryAscRoleNameAsc(cid)
                .stream().collect(Collectors.toMap(VolunteerRole::getId, VolunteerRole::getRoleName));

        // Bulk-load all profile-role assignments in one query
        List<Long> profileIds = profiles.stream().map(VolunteerProfile::getId).toList();
        Map<Long, List<Map<String,Object>>> rolesPerProfile = buildRolesPerProfile(cid, profileIds, roleNames);

        return ResponseEntity.ok(profiles.stream()
                .map(p -> enrichProfile(p, cid, rolesPerProfile.getOrDefault(p.getId(), List.of())))
                .toList());
    }

    // ── Profile-Role endpoints ─────────────────────────────────────────────────

    /** List all roles currently assigned to a volunteer profile. */
    @GetMapping("/profiles/{profileId}/roles")
    public ResponseEntity<?> listProfileRoles(@PathVariable Long profileId, HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);

        VolunteerProfile profile = profileRepo.findById(profileId).orElse(null);
        if (profile == null || !cid.equals(profile.getAppClientId()))
            return ResponseEntity.status(404).body(Map.of("error", "Profile not found"));

        Map<Long, String> roleNames = roleRepo
                .findByAppClientIdAndDeleteFlagFalseOrderByMinistryAscRoleNameAsc(cid)
                .stream().collect(Collectors.toMap(VolunteerRole::getId, VolunteerRole::getRoleName));

        List<VolunteerProfileRole> links = profileRoleRepo
                .findByAppClientIdAndVolunteerProfileIdAndDeleteFlagFalse(cid, profileId);

        return ResponseEntity.ok(links.stream().map(l -> Map.of(
                "id", l.getId(),
                "roleId", l.getRoleId(),
                "roleName", roleNames.getOrDefault(l.getRoleId(), "")
        )).toList());
    }

    /** Assign a role to a volunteer profile (idempotent — no duplicate). */
    @PostMapping("/profiles/{profileId}/roles")
    public ResponseEntity<?> addProfileRole(@PathVariable Long profileId,
                                            @RequestBody Map<String, Object> body,
                                            HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);

        VolunteerProfile profile = profileRepo.findById(profileId).orElse(null);
        if (profile == null || !cid.equals(profile.getAppClientId()))
            return ResponseEntity.status(404).body(Map.of("error", "Profile not found"));

        Long roleId = toLong(body.get("roleId"));
        if (roleId == null)
            return ResponseEntity.badRequest().body(Map.of("error", "roleId is required"));

        VolunteerRole role = roleRepo.findById(roleId).orElse(null);
        if (role == null || !cid.equals(role.getAppClientId()))
            return ResponseEntity.status(404).body(Map.of("error", "Role not found"));

        // Idempotent — return existing if already assigned
        Optional<VolunteerProfileRole> existing = profileRoleRepo
                .findByAppClientIdAndVolunteerProfileIdAndRoleIdAndDeleteFlagFalse(cid, profileId, roleId);
        if (existing.isPresent())
            return ResponseEntity.ok(Map.of("success", true, "id", existing.get().getId(), "duplicate", true));

        VolunteerProfileRole link = new VolunteerProfileRole();
        link.setAppClientId(cid);
        link.setVolunteerProfileId(profileId);
        link.setRoleId(roleId);
        link.setAssignedAt(LocalDateTime.now());
        profileRoleRepo.save(link);
        return ResponseEntity.ok(Map.of("success", true, "id", link.getId()));
    }

    /** Remove a role assignment from a volunteer profile. */
    @DeleteMapping("/profiles/{profileId}/roles/{linkId}")
    public ResponseEntity<?> removeProfileRole(@PathVariable Long profileId,
                                               @PathVariable Long linkId,
                                               HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);

        VolunteerProfileRole link = profileRoleRepo.findById(linkId).orElse(null);
        if (link == null || !cid.equals(link.getAppClientId()) || !profileId.equals(link.getVolunteerProfileId()))
            return ResponseEntity.status(404).body(Map.of("error", "Role assignment not found"));

        link.setDeleteFlag(true);
        profileRoleRepo.save(link);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** Sync a volunteer's roles in bulk — replaces all existing roles with the provided list. */
    @PostMapping("/profiles/{profileId}/roles/sync")
    public ResponseEntity<?> syncProfileRoles(@PathVariable Long profileId,
                                              @RequestBody Map<String, Object> body,
                                              HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);

        VolunteerProfile profile = profileRepo.findById(profileId).orElse(null);
        if (profile == null || !cid.equals(profile.getAppClientId()))
            return ResponseEntity.status(404).body(Map.of("error", "Profile not found"));

        // Parse desired role IDs from body
        Object rawIds = body.get("roleIds");
        List<Long> desiredIds = new ArrayList<>();
        if (rawIds instanceof List<?> list) {
            for (Object item : list) {
                Long rid = toLong(item);
                if (rid != null) desiredIds.add(rid);
            }
        }

        // Current assignments
        List<VolunteerProfileRole> current = profileRoleRepo
                .findByAppClientIdAndVolunteerProfileIdAndDeleteFlagFalse(cid, profileId);
        Set<Long> currentRoleIds = current.stream().map(VolunteerProfileRole::getRoleId).collect(Collectors.toSet());

        // Remove roles not in desired list
        for (VolunteerProfileRole link : current) {
            if (!desiredIds.contains(link.getRoleId())) {
                link.setDeleteFlag(true);
                profileRoleRepo.save(link);
            }
        }

        // Add new roles
        for (Long rid : desiredIds) {
            if (!currentRoleIds.contains(rid)) {
                VolunteerRole role = roleRepo.findById(rid).orElse(null);
                if (role == null || !cid.equals(role.getAppClientId())) continue;
                VolunteerProfileRole link = new VolunteerProfileRole();
                link.setAppClientId(cid);
                link.setVolunteerProfileId(profileId);
                link.setRoleId(rid);
                link.setAssignedAt(LocalDateTime.now());
                profileRoleRepo.save(link);
            }
        }

        return ResponseEntity.ok(Map.of("success", true));
    }

    /** Admin: add an existing family member as a volunteer profile. */
    @PostMapping("/profiles/from-member")
    public ResponseEntity<?> addMemberAsVolunteer(@RequestBody Map<String, Object> body,
                                                   HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);

        Integer memberId = toInt(body.get("familyMemberId"));
        if (memberId == null)
            return ResponseEntity.badRequest().body(Map.of("error", "familyMemberId is required"));

        // Check member exists
        FamilyMember fm = memberRepo.findById(memberId).orElse(null);
        if (fm == null)
            return ResponseEntity.status(404).body(Map.of("error", "Member not found"));

        // Check if already a volunteer
        if (profileRepo.findByAppClientIdAndFamilyMemberIdAndDeleteFlagFalse(cid, memberId).isPresent())
            return ResponseEntity.ok(Map.of("success", true, "message", "Already a volunteer"));

        VolunteerProfile p = new VolunteerProfile();
        p.setAppClientId(cid);
        p.setFamilyMemberId(memberId);
        p.setSkills(str(body, "skills"));
        p.setAvailability(str(body, "availability"));
        p.setStatus("Active");
        p.setJoinedDate(LocalDate.now());
        profileRepo.save(p);
        return ResponseEntity.ok(Map.of("success", true, "id", p.getId()));
    }

    /** Admin: add a new volunteer manually (not necessarily an existing member). */
    @PostMapping("/profiles/manual")
    public ResponseEntity<?> addManualVolunteer(@RequestBody Map<String, Object> body,
                                                 HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);

        String firstName = str(body, "firstName");
        if (firstName == null || firstName.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "firstName is required"));

        // Create a minimal Family shell to satisfy the non-null FK on FamilyMember
        Family family = new Family();
        family.setAppClientId(cid);
        family.setInactive(false);
        family.setDeleteFlag(false);
        familyRepo.save(family);

        // Create a minimal FamilyMember record to represent this person
        FamilyMember fm = new FamilyMember();
        fm.setAppClientId(cid);
        fm.setFamily(family);
        fm.setFirstName(firstName);
        fm.setLastName(str(body, "lastName"));
        fm.setEmail(str(body, "email"));
        fm.setPhone(str(body, "phone"));
        fm.setDeleteFlag(false);
        memberRepo.save(fm);

        // Now create the volunteer profile linked to this member
        VolunteerProfile p = new VolunteerProfile();
        p.setAppClientId(cid);
        p.setFamilyMemberId(fm.getId());
        p.setSkills(str(body, "skills"));
        p.setAvailability(str(body, "availability"));
        p.setStatus("Active");
        p.setJoinedDate(LocalDate.now());
        profileRepo.save(p);

        return ResponseEntity.ok(Map.of("success", true, "id", p.getId(), "memberId", fm.getId()));
    }

    /** Admin: update skills, availability, status, notes on an existing volunteer profile. */
    @PutMapping("/profiles/{id}")
    public ResponseEntity<?> updateProfile(@PathVariable Long id,
                                           @RequestBody Map<String, Object> body,
                                           HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        VolunteerProfile p = profileRepo.findById(id).orElse(null);
        if (p == null || !cid.equals(p.getAppClientId()))
            return ResponseEntity.status(404).body(Map.of("error", "Profile not found"));
        if (body.containsKey("skills"))       p.setSkills(str(body, "skills"));
        if (body.containsKey("availability")) p.setAvailability(str(body, "availability"));
        if (body.containsKey("status")) {
            String s = str(body, "status");
            if (s != null && List.of("Active", "Inactive").contains(s)) p.setStatus(s);
        }
        if (body.containsKey("notes"))        p.setNotes(str(body, "notes"));
        profileRepo.save(p);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** Admin: update the status of a single volunteer assignment. */
    @PutMapping("/assignments/{id}/status")
    public ResponseEntity<?> updateAssignmentStatus(@PathVariable Long id,
                                                     @RequestBody Map<String, Object> body,
                                                     HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        VolunteerAssignment a = assignmentRepo.findById(id).orElse(null);
        if (a == null || !cid.equals(a.getAppClientId()))
            return ResponseEntity.status(404).body(Map.of("error", "Not found"));
        String status = str(body, "status");
        if (status == null || !List.of("pending","confirmed","declined","maybe").contains(status))
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid status"));
        a.setAssignmentStatus(status);
        assignmentRepo.save(a);
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ════════════════════════════════════════════════════════
    // ASSIGNMENTS  (admin)
    // ════════════════════════════════════════════════════════

    @GetMapping("/assignments")
    public ResponseEntity<?> listAssignments(@RequestParam(required = false) Integer eventId,
                                             @RequestParam(required = false) String eventLabel,
                                             HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);

        List<VolunteerAssignment> list;
        if (eventId != null) {
            list = assignmentRepo
                    .findByAppClientIdAndEventIdAndDeleteFlagFalseOrderByRoleIdAscFamilyMemberIdAsc(cid, eventId);
        } else if (eventLabel != null && !eventLabel.isBlank()) {
            list = assignmentRepo
                    .findByAppClientIdAndEventLabelAndDeleteFlagFalseOrderByFamilyMemberIdAsc(cid, eventLabel);
        } else {
            list = assignmentRepo
                    .findByAppClientIdAndDeleteFlagFalseOrderByEventDateDescCreatedAtDesc(cid);
        }

        // Build lookup maps for enrichment
        Map<Long, String> roleNames = roleRepo
                .findByAppClientIdAndDeleteFlagFalseOrderByMinistryAscRoleNameAsc(cid)
                .stream().collect(Collectors.toMap(VolunteerRole::getId, VolunteerRole::getRoleName));

        // Build profile lookup (memberId → profile) to attach volunteer's default roles
        Map<Integer, VolunteerProfile> profileByMember = profileRepo
                .findByAppClientIdAndDeleteFlagFalseOrderByFamilyMemberIdAsc(cid)
                .stream().collect(Collectors.toMap(VolunteerProfile::getFamilyMemberId, Function.identity(), (a1, b) -> a1));

        // Bulk-load profile roles
        List<Long> profileIds = profileByMember.values().stream().map(VolunteerProfile::getId).toList();
        Map<Long, List<Map<String,Object>>> rolesPerProfile = buildRolesPerProfile(cid, profileIds, roleNames);

        return ResponseEntity.ok(list.stream().map(a -> {
            Map<String,Object> m = enrichAssignment(a, roleNames, cid);
            // Attach volunteer's default roles (from their profile)
            VolunteerProfile vp = profileByMember.get(a.getFamilyMemberId());
            if (vp != null) {
                m.put("volunteerRoles", rolesPerProfile.getOrDefault(vp.getId(), List.of()));
            } else {
                m.put("volunteerRoles", List.of());
            }
            return m;
        }).toList());
    }

    @PostMapping("/assignments")
    public ResponseEntity<?> saveAssignment(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);

        Long id = toLong(body.get("id"));
        VolunteerAssignment a = id != null
                ? assignmentRepo.findById(id).orElse(new VolunteerAssignment())
                : new VolunteerAssignment();

        a.setAppClientId(cid);
        // Only overwrite familyMemberId when the caller supplies it — partial updates
        // (e.g. role-only updates) omit this field and must not null out the existing value.
        if (body.containsKey("familyMemberId")) {
            Integer fmId = toInt(body.get("familyMemberId"));
            if (fmId == null)
                return ResponseEntity.badRequest().body(Map.of("error", "familyMemberId is required"));
            a.setFamilyMemberId(fmId);
        }
        if (a.getFamilyMemberId() == null)
            return ResponseEntity.badRequest().body(Map.of("error", "familyMemberId is required"));
        if (body.containsKey("roleId")) a.setRoleId(toLong(body.get("roleId")));
        if (body.get("eventId") != null) a.setEventId(toInt(body.get("eventId")));
        if (body.containsKey("eventLabel")) a.setEventLabel(str(body, "eventLabel"));
        String dateStr = str(body, "eventDate");
        if (dateStr != null && !dateStr.isBlank()) a.setEventDate(LocalDate.parse(dateStr));
        if (body.containsKey("shiftTime")) a.setShiftTime(str(body, "shiftTime"));
        if (body.containsKey("notes")) a.setNotes(str(body, "notes"));
        if (a.getAssignmentStatus() == null || a.getAssignmentStatus().isBlank())
            a.setAssignmentStatus("pending");
        if (a.getCreatedAt() == null) a.setCreatedAt(LocalDateTime.now());

        assignmentRepo.save(a);
        return ResponseEntity.ok(Map.of("success", true, "id", a.getId()));
    }

    @DeleteMapping("/assignments/{id}")
    public ResponseEntity<?> deleteAssignment(@PathVariable Long id, HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        VolunteerAssignment a = assignmentRepo.findById(id).orElse(null);
        if (a == null || !cid.equals(a.getAppClientId()))
            return ResponseEntity.status(404).body(Map.of("error", "Not found"));
        a.setDeleteFlag(true);
        assignmentRepo.save(a);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** Notify all pending (unnotified) volunteers for a specific event. */
    @PostMapping("/assignments/notify")
    public ResponseEntity<?> notifyVolunteers(@RequestBody Map<String, Object> body,
                                              HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);

        Integer eventId = toInt(body.get("eventId"));
        boolean notifyAll = Boolean.TRUE.equals(body.get("notifyAll"));

        List<VolunteerAssignment> targets;
        if (eventId != null) {
            targets = assignmentRepo
                    .findByAppClientIdAndEventIdAndDeleteFlagFalseOrderByRoleIdAscFamilyMemberIdAsc(cid, eventId);
            if (!notifyAll) targets = targets.stream()
                    .filter(a -> !a.isNotificationSent()).toList();
        } else {
            targets = assignmentRepo.findByAppClientIdAndNotificationSentFalseAndDeleteFlagFalse(cid);
        }

        Map<Long, String> roleNames = roleRepo
                .findByAppClientIdAndDeleteFlagFalseOrderByMinistryAscRoleNameAsc(cid)
                .stream().collect(Collectors.toMap(VolunteerRole::getId, VolunteerRole::getRoleName));

        int sent = 0;
        for (VolunteerAssignment a : targets) {
            FamilyMember fm = memberRepo.findById(a.getFamilyMemberId()).orElse(null);
            if (fm == null) continue;
            String roleName  = a.getRoleId() != null ? roleNames.getOrDefault(a.getRoleId(), "Volunteer") : "Volunteer";
            String eventLabel = a.getEventLabel() != null ? a.getEventLabel() : "upcoming event";
            String dateLabel  = a.getEventDate() != null ? a.getEventDate().toString() : "";
            String shiftLabel = a.getShiftTime() != null ? a.getShiftTime() : "";

            // Email
            if (fm.getEmail() != null && !fm.getEmail().isBlank()) {
                String subject = "You're scheduled to volunteer – " + eventLabel;
                String html = "<p>Hi " + (fm.getFirstName() != null ? fm.getFirstName() : "there") + ",</p>"
                        + "<p>You have been assigned as a volunteer for:</p>"
                        + "<table style='border-collapse:collapse;font-family:sans-serif;'>"
                        + "<tr><td style='padding:4px 12px 4px 0;font-weight:700;'>Event:</td><td>" + eventLabel + "</td></tr>"
                        + (dateLabel.isBlank() ? "" : "<tr><td style='padding:4px 12px 4px 0;font-weight:700;'>Date:</td><td>" + dateLabel + "</td></tr>")
                        + "<tr><td style='padding:4px 12px 4px 0;font-weight:700;'>Role:</td><td>" + roleName + "</td></tr>"
                        + (shiftLabel.isBlank() ? "" : "<tr><td style='padding:4px 12px 4px 0;font-weight:700;'>Time:</td><td>" + shiftLabel + "</td></tr>")
                        + "</table>"
                        + "<p>Please log in to your member portal to confirm or decline this assignment.</p>"
                        + "<p>Thank you for serving!</p>";
                try {
                    emailService.sendGenericEmail(fm.getEmail(), subject, html, cid);
                } catch (Exception ignored) {}
            }

            // Push notification
            try {
                String userKey = fm.getEmail() != null ? fm.getEmail() : "";
                if (!userKey.isBlank()) {
                    pushService.logAndSendToUser(userKey, cid, "member",
                            "Volunteer Assignment",
                            "You're scheduled for " + roleName + " at " + eventLabel,
                            "/memberHome", "volunteer-assignment");
                }
            } catch (Exception ignored) {}

            a.setNotificationSent(true);
            assignmentRepo.save(a);
            sent++;
        }
        return ResponseEntity.ok(Map.of("success", true, "sent", sent));
    }

    /**
     * Send a compose-modal email directly from the server using the configured
     * SMTP / JavaMailSender — no external mail client required.
     *
     * Request body: { "to": ["email1","email2",...], "subject": "...", "body": "..." }
     */
    @PostMapping("/send-email")
    public ResponseEntity<?> sendEmail(@RequestBody Map<String, Object> body,
                                       HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);

        Object toRaw = body.get("to");
        List<String> toList = new ArrayList<>();
        if (toRaw instanceof List<?> l) {
            for (Object item : l) { if (item != null) toList.add(item.toString().trim()); }
        } else if (toRaw instanceof String s) {
            for (String part : s.split("[,\\n\\r]+")) { String t = part.trim(); if (!t.isEmpty()) toList.add(t); }
        }
        toList = toList.stream().filter(e -> e.contains("@")).distinct().collect(Collectors.toList());
        if (toList.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "No valid recipient addresses provided"));

        String subject = str(body, "subject");
        if (subject == null || subject.isBlank()) subject = "(no subject)";
        String htmlBody = str(body, "body");
        if (htmlBody == null) htmlBody = "";

        // Convert plain-text newlines to <br> if the body doesn't look like HTML
        if (!htmlBody.contains("<") ) {
            htmlBody = htmlBody.replace("\n", "<br>");
        }

        int sent = 0;
        List<String> failed = new ArrayList<>();
        for (String to : toList) {
            try {
                emailService.sendGenericEmail(to, subject, htmlBody, cid);
                sent++;
            } catch (Exception e) {
                failed.add(to);
            }
        }

        if (sent == 0) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to send email. Check SMTP settings."));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("sent", sent);
        if (!failed.isEmpty()) result.put("failed", failed);
        return ResponseEntity.ok(result);
    }

    /**
     * Send an SMS directly via Twilio — no device SMS app required.
     *
     * Request body: { "to": ["phone1","phone2",...], "body": "..." }
     */
    @PostMapping("/send-sms")
    public ResponseEntity<?> sendSms(@RequestBody Map<String, Object> body,
                                     HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));

        if (!smsService.isConfigured()) {
            return ResponseEntity.status(503).body(Map.of(
                "error", "SMS is not configured. Please add Twilio credentials (twilio.account-sid, " +
                         "twilio.auth-token, twilio.phone-number) to application.properties."));
        }

        Object toRaw = body.get("to");
        List<String> toList = new ArrayList<>();
        if (toRaw instanceof List<?> l) {
            for (Object item : l) { if (item != null) toList.add(item.toString().trim()); }
        } else if (toRaw instanceof String s) {
            for (String part : s.split("[,\\n\\r]+")) { String t = part.trim(); if (!t.isEmpty()) toList.add(t); }
        }
        toList = toList.stream().filter(p -> !p.isBlank()).distinct().collect(Collectors.toList());
        if (toList.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "No recipient phone numbers provided"));

        String message = str(body, "body");
        if (message == null || message.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Message body is required"));

        int sent = 0;
        List<String> failed = new ArrayList<>();
        for (String phone : toList) {
            boolean ok = smsService.send(phone, message);
            if (ok) sent++; else failed.add(phone);
        }

        if (sent == 0) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to send SMS. Check Twilio credentials and phone number format."));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("sent", sent);
        if (!failed.isEmpty()) result.put("failed", failed);
        return ResponseEntity.ok(result);
    }

    /** Mark volunteer attendance (admin check-in on event day). */
    @PostMapping("/attendance")
    public ResponseEntity<?> markAttendance(@RequestBody Map<String, Object> body,
                                            HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);

        Long assignmentId = toLong(body.get("assignmentId"));
        if (assignmentId == null)
            return ResponseEntity.badRequest().body(Map.of("error", "assignmentId required"));

        VolunteerAttendance att = attendanceRepo.findByAssignmentId(assignmentId)
                .orElse(new VolunteerAttendance());
        att.setAppClientId(cid);
        att.setAssignmentId(assignmentId);
        att.setCheckinTime(LocalDateTime.now());
        att.setMarkedBy(SessionUtil.getUsername(req));
        attendanceRepo.save(att);

        // Also update assignment status to confirmed
        assignmentRepo.findById(assignmentId).ifPresent(a -> {
            a.setAssignmentStatus("confirmed");
            assignmentRepo.save(a);
        });
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ════════════════════════════════════════════════════════
    // SUMMARY  (admin dashboard)
    // ════════════════════════════════════════════════════════

    @GetMapping("/summary")
    public ResponseEntity<?> summary(HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);

        long totalProfiles   = profileRepo.findByAppClientIdAndDeleteFlagFalseOrderByFamilyMemberIdAsc(cid).size();
        long totalRoles      = roleRepo.findByAppClientIdAndDeleteFlagFalseOrderByMinistryAscRoleNameAsc(cid).size();
        long totalAssignments = assignmentRepo.findByAppClientIdAndDeleteFlagFalseOrderByEventDateDescCreatedAtDesc(cid).size();
        long pendingCount    = assignmentRepo.findByAppClientIdAndDeleteFlagFalseOrderByEventDateDescCreatedAtDesc(cid)
                .stream().filter(a -> "pending".equals(a.getAssignmentStatus())).count();

        return ResponseEntity.ok(Map.of(
                "totalVolunteers", totalProfiles,
                "totalRoles", totalRoles,
                "totalAssignments", totalAssignments,
                "pendingConfirmations", pendingCount
        ));
    }

    // ════════════════════════════════════════════════════════
    // MEMBER-FACING ENDPOINTS  (/api/volunteers/my/*)
    // ════════════════════════════════════════════════════════

    /** Get own volunteer profile (or empty shell if none yet). */
    @GetMapping("/my/profile")
    public ResponseEntity<?> myProfile(HttpServletRequest req) {
        Integer memberId = getMemberId(req);
        if (memberId == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in"));
        String cid = SessionUtil.getAppClientId(req);
        VolunteerProfile p = profileRepo
                .findByAppClientIdAndFamilyMemberIdAndDeleteFlagFalse(cid, memberId)
                .orElse(null);
        if (p == null) return ResponseEntity.ok(Map.of("exists", false));

        Map<Long,String> roleNames = roleRepo
                .findByAppClientIdAndDeleteFlagFalseOrderByMinistryAscRoleNameAsc(cid)
                .stream().collect(Collectors.toMap(VolunteerRole::getId, VolunteerRole::getRoleName));
        List<Map<String,Object>> roles = buildRolesPerProfile(cid, List.of(p.getId()), roleNames)
                .getOrDefault(p.getId(), List.of());

        Map<String,Object> m = new LinkedHashMap<>(profileMap(p));
        m.put("roles", roles);
        return ResponseEntity.ok(m);
    }

    /** Create or update own volunteer profile. */
    @PostMapping("/my/profile")
    public ResponseEntity<?> saveMyProfile(@RequestBody Map<String, Object> body,
                                           HttpServletRequest req) {
        Integer memberId = getMemberId(req);
        if (memberId == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in"));
        String cid = SessionUtil.getAppClientId(req);

        VolunteerProfile p = profileRepo
                .findByAppClientIdAndFamilyMemberIdAndDeleteFlagFalse(cid, memberId)
                .orElse(new VolunteerProfile());
        p.setAppClientId(cid);
        p.setFamilyMemberId(memberId);
        p.setSkills(str(body, "skills"));
        p.setAvailability(str(body, "availability"));
        p.setNotes(str(body, "notes"));
        p.setStatus("Active");
        if (p.getJoinedDate() == null) p.setJoinedDate(LocalDate.now());
        profileRepo.save(p);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** Get own upcoming assignments. */
    @GetMapping("/my/assignments")
    public ResponseEntity<?> myAssignments(HttpServletRequest req) {
        Integer memberId = getMemberId(req);
        if (memberId == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in"));
        String cid = SessionUtil.getAppClientId(req);

        List<VolunteerAssignment> list = assignmentRepo
                .findUpcomingForMember(cid, memberId, LocalDate.now());

        Map<Long, String> roleNames = roleRepo
                .findByAppClientIdAndDeleteFlagFalseOrderByMinistryAscRoleNameAsc(cid)
                .stream().collect(Collectors.toMap(VolunteerRole::getId, VolunteerRole::getRoleName));

        return ResponseEntity.ok(list.stream().map(a -> enrichAssignment(a, roleNames, cid)).toList());
    }

    /** Get own full assignment history. */
    @GetMapping("/my/history")
    public ResponseEntity<?> myHistory(HttpServletRequest req) {
        Integer memberId = getMemberId(req);
        if (memberId == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in"));
        String cid = SessionUtil.getAppClientId(req);

        List<VolunteerAssignment> list = assignmentRepo
                .findByAppClientIdAndFamilyMemberIdAndDeleteFlagFalseOrderByEventDateDesc(cid, memberId);

        Map<Long, String> roleNames = roleRepo
                .findByAppClientIdAndDeleteFlagFalseOrderByMinistryAscRoleNameAsc(cid)
                .stream().collect(Collectors.toMap(VolunteerRole::getId, VolunteerRole::getRoleName));

        return ResponseEntity.ok(list.stream().map(a -> enrichAssignment(a, roleNames, cid)).toList());
    }

    /** Member responds to an assignment: confirm, decline, or maybe. */
    @PostMapping("/my/assignments/{id}/respond")
    public ResponseEntity<?> respond(@PathVariable Long id,
                                     @RequestBody Map<String, Object> body,
                                     HttpServletRequest req) {
        Integer memberId = getMemberId(req);
        if (memberId == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in"));
        String cid = SessionUtil.getAppClientId(req);

        VolunteerAssignment a = assignmentRepo.findById(id).orElse(null);
        if (a == null || !cid.equals(a.getAppClientId()) || !memberId.equals(a.getFamilyMemberId()))
            return ResponseEntity.status(404).body(Map.of("error", "Assignment not found"));

        String status = str(body, "status"); // confirmed | declined | maybe
        if (status == null || !List.of("confirmed", "declined", "maybe").contains(status))
            return ResponseEntity.badRequest().body(Map.of("error", "status must be confirmed, declined, or maybe"));

        a.setAssignmentStatus(status);
        a.setRespondedAt(LocalDateTime.now());
        assignmentRepo.save(a);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** List open roles for a specific event (for member signup view). */
    @GetMapping("/my/open-roles")
    public ResponseEntity<?> openRolesForEvent(@RequestParam Integer eventId,
                                               HttpServletRequest req) {
        Integer memberId = getMemberId(req);
        if (memberId == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in"));
        String cid = SessionUtil.getAppClientId(req);

        List<VolunteerRole> roles = roleRepo
                .findByAppClientIdAndDeleteFlagFalseOrderByMinistryAscRoleNameAsc(cid);

        // Count how many slots are filled per role for this event
        return ResponseEntity.ok(roles.stream().map(r -> {
            long filled = assignmentRepo.countActiveForEventRole(cid, eventId, r.getId());
            Map<String, Object> m = new LinkedHashMap<>(roleMap(r));
            m.put("filled", filled);
            m.put("spotsLeft", r.getMaxCapacity() != null ? Math.max(0, r.getMaxCapacity() - filled) : null);
            m.put("full", r.getMaxCapacity() != null && filled >= r.getMaxCapacity());
            return m;
        }).toList());
    }

    /** Member self-signs-up for a role on an event. */
    @PostMapping("/my/signup")
    public ResponseEntity<?> selfSignup(@RequestBody Map<String, Object> body,
                                        HttpServletRequest req) {
        Integer memberId = getMemberId(req);
        if (memberId == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in"));
        String cid = SessionUtil.getAppClientId(req);

        Integer eventId = toInt(body.get("eventId"));
        Long roleId     = toLong(body.get("roleId"));
        if (eventId == null || roleId == null)
            return ResponseEntity.badRequest().body(Map.of("error", "eventId and roleId are required"));

        // Check capacity
        VolunteerRole role = roleRepo.findById(roleId).orElse(null);
        if (role == null || !cid.equals(role.getAppClientId()))
            return ResponseEntity.status(404).body(Map.of("error", "Role not found"));
        if (role.getMaxCapacity() != null) {
            long filled = assignmentRepo.countActiveForEventRole(cid, eventId, roleId);
            if (filled >= role.getMaxCapacity())
                return ResponseEntity.badRequest().body(Map.of("error", "This role is already full"));
        }

        // Prevent duplicate signup
        List<VolunteerAssignment> existing = assignmentRepo
                .findByAppClientIdAndEventIdAndDeleteFlagFalseOrderByRoleIdAscFamilyMemberIdAsc(cid, eventId);
        boolean alreadySigned = existing.stream()
                .anyMatch(a -> memberId.equals(a.getFamilyMemberId()) && roleId.equals(a.getRoleId()));
        if (alreadySigned)
            return ResponseEntity.badRequest().body(Map.of("error", "You are already signed up for this role"));

        // Find event label
        String eventLabel = eventRepo.findById(eventId)
                .map(ChurchEvent::getEventName).orElse("Event #" + eventId);

        VolunteerAssignment a = new VolunteerAssignment();
        a.setAppClientId(cid);
        a.setEventId(eventId);
        a.setEventLabel(eventLabel);
        a.setRoleId(roleId);
        a.setFamilyMemberId(memberId);
        a.setAssignmentStatus("confirmed");
        a.setRespondedAt(LocalDateTime.now());
        a.setCreatedAt(LocalDateTime.now());
        assignmentRepo.save(a);
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════

    private Map<String, Object> roleMap(VolunteerRole r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId()); m.put("roleName", r.getRoleName());
        m.put("ministry", r.getMinistry()); m.put("description", r.getDescription());
        m.put("maxCapacity", r.getMaxCapacity());
        return m;
    }

    private Map<String, Object> profileMap(VolunteerProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId()); m.put("familyMemberId", p.getFamilyMemberId());
        m.put("skills", p.getSkills()); m.put("availability", p.getAvailability());
        m.put("status", p.getStatus()); m.put("joinedDate", p.getJoinedDate());
        m.put("notes", p.getNotes()); m.put("exists", true);
        return m;
    }

    private Map<String, Object> enrichProfile(VolunteerProfile p, String cid) {
        return enrichProfile(p, cid, List.of());
    }

    private Map<String, Object> enrichProfile(VolunteerProfile p, String cid,
                                               List<Map<String,Object>> roles) {
        Map<String, Object> m = new LinkedHashMap<>(profileMap(p));
        FamilyMember fm = memberRepo.findById(p.getFamilyMemberId()).orElse(null);
        if (fm != null) {
            m.put("firstName", fm.getFirstName()); m.put("lastName", fm.getLastName());
            m.put("email", fm.getEmail()); m.put("phone", fm.getPhone());
        }
        m.put("roles", roles);
        return m;
    }

    /**
     * Bulk-builds a map of profileId → list of {id, roleId, roleName} for the given profile IDs.
     */
    private Map<Long, List<Map<String,Object>>> buildRolesPerProfile(String cid,
                                                                      List<Long> profileIds,
                                                                      Map<Long,String> roleNames) {
        if (profileIds.isEmpty()) return Map.of();
        List<VolunteerProfileRole> allLinks = profileRoleRepo
                .findByAppClientIdAndVolunteerProfileIdInAndDeleteFlagFalse(cid, profileIds);
        Map<Long, List<Map<String,Object>>> result = new LinkedHashMap<>();
        for (VolunteerProfileRole link : allLinks) {
            result.computeIfAbsent(link.getVolunteerProfileId(), k -> new ArrayList<>())
                  .add(Map.of(
                      "id",       link.getId(),
                      "roleId",   link.getRoleId(),
                      "roleName", roleNames.getOrDefault(link.getRoleId(), "")
                  ));
        }
        return result;
    }

    private Map<String, Object> enrichAssignment(VolunteerAssignment a,
                                                  Map<Long, String> roleNames, String cid) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("eventId", a.getEventId()); m.put("eventLabel", a.getEventLabel());
        m.put("eventDate", a.getEventDate() != null ? a.getEventDate().toString() : null);
        m.put("shiftTime", a.getShiftTime());
        m.put("roleId", a.getRoleId());
        m.put("roleName", a.getRoleId() != null ? roleNames.getOrDefault(a.getRoleId(), "") : "");
        m.put("familyMemberId", a.getFamilyMemberId());
        m.put("assignmentStatus", a.getAssignmentStatus());
        m.put("notificationSent", a.isNotificationSent());
        m.put("notes", a.getNotes());
        m.put("createdAt", a.getCreatedAt() != null ? a.getCreatedAt().toString() : null);
        // Enrich with member name and contact info
        FamilyMember fm = memberRepo.findById(a.getFamilyMemberId()).orElse(null);
        if (fm != null) {
            String first = fm.getFirstName() != null ? fm.getFirstName() : "";
            String last  = fm.getLastName()  != null ? fm.getLastName()  : "";
            m.put("firstName",   first);
            m.put("lastName",    last);
            m.put("memberName",  (first + " " + last).trim());
            m.put("email",       fm.getEmail());
            m.put("phone",       fm.getPhone());
            m.put("memberEmail", fm.getEmail());
            m.put("memberPhone", fm.getPhone());
        }
        // Enrich with availability from volunteer profile
        if (a.getFamilyMemberId() != null) {
            profileRepo.findByAppClientIdAndFamilyMemberIdAndDeleteFlagFalse(cid, a.getFamilyMemberId())
                       .ifPresent(vp -> m.put("availability", vp.getAvailability()));
        }
        return m;
    }

    private Integer getMemberId(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return null;
        Object val = session.getAttribute("memberId");
        if (val instanceof Integer i) return i;
        if (val instanceof Number n) return n.intValue();
        return null;
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key); if (v == null) return null;
        String s = v.toString().trim(); return s.isBlank() ? null : s;
    }
    private Long toLong(Object v) {
        if (v == null) return null;
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return null; }
    }
    private Integer toInt(Object v) {
        if (v == null) return null;
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return null; }
    }
}
