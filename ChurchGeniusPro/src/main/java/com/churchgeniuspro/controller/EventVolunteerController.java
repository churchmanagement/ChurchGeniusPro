package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.ChurchEvent;
import com.churchgeniuspro.hibernate.EventVolunteer;
import com.churchgeniuspro.hibernate.EventVolunteerRole;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.VolunteerRole;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.SmsService;
import com.churchgeniuspro.service.WebPushService;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST API for event-specific volunteer management.
 *
 * All endpoints require Admin / SuperAdmin.
 * Base path: /api/events/{eventId}/volunteers
 */
@RestController
@RequestMapping("/api/events/{eventId}/volunteers")
public class EventVolunteerController {

    private final EventVolunteerRepository     evRepo;
    private final EventVolunteerRoleRepository evRoleRepo;
    private final FamilyMemberRepository       memberRepo;
    private final VolunteerRoleRepository      roleRepo;
    private final ChurchEventRepository        churchEventRepo;
    private final AppUserRepository            appUserRepo;
    private final EmailService                 emailService;
    private final SmsService                   smsService;
    private final WebPushService               webPushService;

    public EventVolunteerController(EventVolunteerRepository evRepo,
                                    EventVolunteerRoleRepository evRoleRepo,
                                    FamilyMemberRepository memberRepo,
                                    VolunteerRoleRepository roleRepo,
                                    ChurchEventRepository churchEventRepo,
                                    AppUserRepository appUserRepo,
                                    EmailService emailService,
                                    SmsService smsService,
                                    WebPushService webPushService) {
        this.evRepo          = evRepo;
        this.evRoleRepo      = evRoleRepo;
        this.memberRepo      = memberRepo;
        this.roleRepo        = roleRepo;
        this.churchEventRepo = churchEventRepo;
        this.appUserRepo     = appUserRepo;
        this.emailService    = emailService;
        this.smsService      = smsService;
        this.webPushService  = webPushService;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString().trim();
    }

    private Integer toInt(Object v) {
        if (v == null) return null;
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return null; }
    }

    /** Build a rich DTO for a single EventVolunteer including its roles. */
    private Map<String, Object> toDto(EventVolunteer ev, List<EventVolunteerRole> roles) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",             ev.getId());
        m.put("eventId",        ev.getEventId());
        m.put("familyMemberId", ev.getFamilyMemberId());
        m.put("firstName",      ev.getFirstName() != null ? ev.getFirstName() : "");
        m.put("lastName",       ev.getLastName()  != null ? ev.getLastName()  : "");
        m.put("email",          ev.getEmail()     != null ? ev.getEmail()     : "");
        m.put("phone",          ev.getPhone()     != null ? ev.getPhone()     : "");
        m.put("isManual",       ev.isManual());
        m.put("status",         ev.getStatus()    != null ? ev.getStatus()    : "pending");
        m.put("notes",          ev.getNotes()     != null ? ev.getNotes()     : "");
        m.put("roles",          roles.stream().map(r -> {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("id",       r.getId());
            rm.put("roleName", r.getRoleName());
            return rm;
        }).toList());
        return m;
    }

    // ── GET /api/events/{eventId}/volunteers ─────────────────────────────────

    @GetMapping
    public ResponseEntity<?> list(@PathVariable Integer eventId,
                                  HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);

        List<EventVolunteer> volunteers = evRepo
                .findByAppClientIdAndEventIdAndDeleteFlagFalseOrderByCreatedAtAsc(cid, eventId);

        List<Long> ids = volunteers.stream().map(EventVolunteer::getId).toList();
        Map<Long, List<EventVolunteerRole>> rolesMap = ids.isEmpty()
                ? Map.of()
                : evRoleRepo.findByAppClientIdAndEventVolunteerIdInAndDeleteFlagFalseOrderByIdAsc(cid, ids)
                            .stream().collect(Collectors.groupingBy(EventVolunteerRole::getEventVolunteerId));

        List<Map<String, Object>> result = volunteers.stream()
                .map(ev -> toDto(ev, rolesMap.getOrDefault(ev.getId(), List.of())))
                .toList();

        return ResponseEntity.ok(result);
    }

    // ── POST /api/events/{eventId}/volunteers ────────────────────────────────
    //    Create a new volunteer for this event.
    //    Body: { familyMemberId?, firstName, lastName, email, phone, isManual, status, notes, roles:[{roleName}] }

    @PostMapping
    public ResponseEntity<?> create(@PathVariable Integer eventId,
                                    @RequestBody Map<String, Object> body,
                                    HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        if (cid == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        // The path's eventId must be one of this church's events.
        if (churchEventRepo.findByIdAndAppClientIdAndDeleteFlagFalse(eventId, cid).isEmpty())
            return ResponseEntity.status(404).body(Map.of("error", "Event not found"));

        EventVolunteer ev = new EventVolunteer();
        ev.setAppClientId(cid);
        ev.setEventId(eventId);
        ev.setCreatedAt(LocalDateTime.now());

        Integer fmId = toInt(body.get("familyMemberId"));
        boolean manual = Boolean.TRUE.equals(body.get("isManual")) || fmId == null;
        ev.setManual(manual);

        // A linked member must belong to this church; a foreign id is never stored.
        FamilyMember fm = null;
        if (fmId != null) {
            fm = memberRepo.findByIdAndTenant(fmId, cid).orElse(null);
            if (fm == null) return ResponseEntity.status(404).body(Map.of("error", "Member not found"));
        }
        ev.setFamilyMemberId(fmId);

        // If linked to a member, pull latest contact info from DB
        if (fm != null && !manual) {
            ev.setFirstName(fm.getFirstName());
            ev.setLastName(fm.getLastName());
            ev.setEmail(fm.getEmail());
            ev.setPhone(fm.getPhone());
        }
        // Caller-supplied overrides (or manual entry)
        if (str(body, "firstName") != null) ev.setFirstName(str(body, "firstName"));
        if (str(body, "lastName")  != null) ev.setLastName(str(body,  "lastName"));
        if (str(body, "email")     != null) ev.setEmail(str(body,     "email"));
        if (str(body, "phone")     != null) ev.setPhone(str(body,     "phone"));

        String status = str(body, "status");
        ev.setStatus(status != null && List.of("pending","confirmed","declined","maybe").contains(status)
                ? status : "pending");
        ev.setNotes(str(body, "notes"));

        evRepo.save(ev);

        // Save roles
        List<Map<String, Object>> roleDtos = saveRoles(ev, body, cid);

        Map<String, Object> dto = toDto(ev, evRoleRepo
                .findByAppClientIdAndEventVolunteerIdAndDeleteFlagFalseOrderByIdAsc(cid, ev.getId()));
        return ResponseEntity.ok(Map.of("success", true, "volunteer", dto));
    }

    // ── PUT /api/events/{eventId}/volunteers/{id} ─────────────────────────────

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Integer eventId,
                                    @PathVariable Long id,
                                    @RequestBody Map<String, Object> body,
                                    HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);

        EventVolunteer ev = evRepo.findById(id).orElse(null);
        if (ev == null || !cid.equals(ev.getAppClientId()) || !eventId.equals(ev.getEventId()))
            return ResponseEntity.status(404).body(Map.of("error", "Not found"));

        if (body.containsKey("firstName")) ev.setFirstName(str(body, "firstName"));
        if (body.containsKey("lastName"))  ev.setLastName(str(body, "lastName"));
        if (body.containsKey("email"))     ev.setEmail(str(body, "email"));
        if (body.containsKey("phone"))     ev.setPhone(str(body, "phone"));
        if (body.containsKey("notes"))     ev.setNotes(str(body, "notes"));
        if (body.containsKey("status")) {
            String status = str(body, "status");
            if (List.of("pending","confirmed","declined","maybe").contains(status))
                ev.setStatus(status);
        }
        evRepo.save(ev);

        // Replace roles if provided
        if (body.containsKey("roles")) {
            // soft-delete all existing roles for this volunteer
            List<EventVolunteerRole> existing = evRoleRepo
                    .findByAppClientIdAndEventVolunteerIdAndDeleteFlagFalseOrderByIdAsc(cid, ev.getId());
            existing.forEach(r -> r.setDeleteFlag(true));
            evRoleRepo.saveAll(existing);
            // save new roles
            saveRoles(ev, body, cid);
        }

        Map<String, Object> dto = toDto(ev, evRoleRepo
                .findByAppClientIdAndEventVolunteerIdAndDeleteFlagFalseOrderByIdAsc(cid, ev.getId()));
        return ResponseEntity.ok(Map.of("success", true, "volunteer", dto));
    }

    // ── DELETE /api/events/{eventId}/volunteers/{id} ──────────────────────────

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Integer eventId,
                                    @PathVariable Long id,
                                    HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);

        EventVolunteer ev = evRepo.findById(id).orElse(null);
        if (ev == null || !cid.equals(ev.getAppClientId()) || !eventId.equals(ev.getEventId()))
            return ResponseEntity.status(404).body(Map.of("error", "Not found"));

        ev.setDeleteFlag(true);
        evRepo.save(ev);

        // soft-delete roles too
        List<EventVolunteerRole> roles = evRoleRepo
                .findByAppClientIdAndEventVolunteerIdAndDeleteFlagFalseOrderByIdAsc(cid, ev.getId());
        roles.forEach(r -> r.setDeleteFlag(true));
        evRoleRepo.saveAll(roles);

        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── POST /api/events/{eventId}/volunteers/send-email ─────────────────────

    @PostMapping("/send-email")
    public ResponseEntity<?> sendEmail(@PathVariable Integer eventId,
                                       @RequestBody Map<String, Object> body,
                                       HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);

        @SuppressWarnings("unchecked")
        List<String> to = (List<String>) body.getOrDefault("to", List.of());
        String subject  = str(body, "subject");
        String msgBody  = str(body, "body");
        if (subject == null) subject = "Volunteer Assignment";
        if (msgBody  == null) msgBody = "";

        // A Trial/demo tenant's congregation mail is dropped inside EmailService; ask
        // first so the reply says "blocked" rather than counting dropped mail as sent.
        EmailService.Delivery d = emailService.delivery(cid);
        int sent = 0, blocked = 0;
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        // Phase B: a Trial/Demo tenant with a verified test address gets ONE test
        // email for this action; the volunteers are simulated, never emailed.
        try (com.churchgeniuspro.util.EmailActionScope scope =
                     com.churchgeniuspro.util.EmailActionScope.begin("volunteer-email:" + eventId)) {
            for (String email : to) {
                if (email == null || email.isBlank()) continue;
                if (d.blocked()) { blocked++; continue; }
                try {
                    String html = "<p>" + msgBody.replace("\n", "<br>") + "</p>";
                    emailService.sendGenericEmail(email, subject, html, cid);
                    if (!d.test()) sent++;
                } catch (Exception e) {
                    // log and continue
                }
            }
            if (d.test()) {
                out.put("testEmailsSent", scope.testEmailsSent());
                out.put("simulated",      scope.simulated());
                out.put("testEmail",      d.testEmail());
            }
        }
        out.put("success", true); out.put("sent", sent); out.put("blocked", blocked);
        if (d.blocked()) out.put("blockReason", d.reason());
        return ResponseEntity.ok(out);
    }

    // ── POST /api/events/{eventId}/volunteers/send-sms ───────────────────────

    @PostMapping("/send-sms")
    public ResponseEntity<?> sendSms(@PathVariable Integer eventId,
                                     @RequestBody Map<String, Object> body,
                                     HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));

        if (!smsService.isConfigured())
            return ResponseEntity.ok(Map.of("success", false, "error", "SMS not configured"));

        @SuppressWarnings("unchecked")
        List<String> to = (List<String>) body.getOrDefault("to", List.of());
        String message  = str(body, "body");
        if (message == null) message = "";

        int sent = 0;
        for (String phone : to) {
            if (phone == null || phone.isBlank()) continue;
            try {
                boolean ok = smsService.sendForClient(
                        com.churchgeniuspro.util.SessionUtil.getAppClientId(req), phone, message).sent();
                if (ok) sent++;
            } catch (Exception e) {
                // log and continue
            }
        }
        return ResponseEntity.ok(Map.of("success", true, "sent", sent));
    }

    // ── GET /api/events/{eventId}/volunteers/roles ───────────────────────────
    //    Returns the org's configured volunteer roles (for the role picker dropdown)

    @GetMapping("/roles")
    public ResponseEntity<?> listOrgRoles(@PathVariable Integer eventId,
                                          HttpServletRequest req) {
        String deny = RoleGuard.requireAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);

        List<Map<String, Object>> roles = roleRepo
                .findByAppClientIdAndDeleteFlagFalseOrderByMinistryAscRoleNameAsc(cid)
                .stream().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",       r.getId());
                    m.put("roleName", r.getRoleName());
                    m.put("ministry", r.getMinistry() != null ? r.getMinistry() : "");
                    return m;
                }).toList();

        return ResponseEntity.ok(roles);
    }

    // ── PUT /api/events/{eventId}/volunteers/{id}/member-respond ─────────────
    //    Member updates their own volunteer status (confirmed/declined/maybe).
    //    Sends email to event creator + push notification to the member.

    @PutMapping("/{id}/member-respond")
    public ResponseEntity<?> memberRespond(@PathVariable Integer eventId,
                                           @PathVariable Long id,
                                           @RequestBody Map<String, Object> body,
                                           HttpServletRequest req) {
        Integer memberId = getMemberId(req);
        if (memberId == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in"));
        String cid = SessionUtil.getAppClientId(req);

        EventVolunteer ev = evRepo.findById(id).orElse(null);
        if (ev == null || !cid.equals(ev.getAppClientId()) || !eventId.equals(ev.getEventId()))
            return ResponseEntity.status(404).body(Map.of("error", "Not found"));

        // Validate this volunteer record belongs to the logged-in member
        if (!memberId.equals(ev.getFamilyMemberId()))
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));

        String status = str(body, "status");
        if (status == null || !List.of("confirmed", "declined", "maybe").contains(status))
            return ResponseEntity.badRequest().body(Map.of("error", "status must be confirmed, declined, or maybe"));

        ev.setStatus(status);
        evRepo.save(ev);

        // Look up the event for name/details
        ChurchEvent churchEvent = churchEventRepo.findByIdAndAppClientIdAndDeleteFlagFalse(eventId, cid).orElse(null);
        String eventName = churchEvent != null ? churchEvent.getEventName() : "an event";

        // Look up volunteer name
        String volName = ((ev.getFirstName() != null ? ev.getFirstName() : "") + " "
                        + (ev.getLastName()  != null ? ev.getLastName()  : "")).trim();
        if (volName.isEmpty()) volName = "A volunteer";

        String statusLabel = "confirmed".equals(status) ? "accepted ✅"
                           : "declined".equals(status)  ? "declined ❌"
                           : "responded Maybe 🤔";

        // ── 1. Email notification to event creator ────────────────────────────
        if (churchEvent != null && churchEvent.getCreatedBy() != null
                && !churchEvent.getCreatedBy().isBlank()) {
            try {
                Optional<AppUser> creatorOpt = appUserRepo
                        .findFirstByEmailIgnoreCaseAndDeleteFlagFalse(churchEvent.getCreatedBy());
                String creatorEmail = creatorOpt.map(AppUser::getEmail)
                                                .filter(e -> e != null && !e.isBlank())
                                                .orElse(churchEvent.getCreatedBy()); // fallback: username IS the email

                String subject = volName + " has " + statusLabel + " — " + eventName;
                String html = buildVolunteerResponseEmail(volName, eventName, status, creatorOpt
                        .map(u -> u.getFirstName() != null ? u.getFirstName() : "").orElse(""));
                emailService.sendGenericEmail(creatorEmail, subject, html, cid);
            } catch (Exception e) {
                // log but don't fail the request
            }
        }

        // ── 2. Push notification to the member themselves ─────────────────────
        String memberUserKey = "MBR-" + memberId;
        String pushTitle = "Volunteer Assignment — " + eventName;
        String pushBody  = "You have " + statusLabel + " your volunteer assignment.";
        webPushService.logAndSendToUser(memberUserKey, cid, "member",
                pushTitle, pushBody, "/memberHome", "cgp-vol-respond");

        return ResponseEntity.ok(Map.of("success", true, "status", status));
    }

    /** Get the member ID from session — null if not a member session. */
    private Integer getMemberId(HttpServletRequest req) {
        Object val = req.getSession(false) != null
                ? req.getSession(false).getAttribute("memberId") : null;
        if (val == null) return null;
        try { return Integer.parseInt(val.toString()); } catch (Exception e) { return null; }
    }

    /** Build a clean HTML email notifying the event creator of the volunteer's status change. */
    private String buildVolunteerResponseEmail(String volName, String eventName,
                                               String status, String creatorFirst) {
        String emoji      = "confirmed".equals(status) ? "✅" : "declined".equals(status) ? "❌" : "🤔";
        String label      = "confirmed".equals(status) ? "Accepted" : "declined".equals(status) ? "Declined" : "Maybe";
        String safeVol    = esc(volName);
        String safeEvent  = esc(eventName);
        String safeHi     = creatorFirst != null && !creatorFirst.isBlank()
                            ? "Hi <strong>" + esc(creatorFirst) + "</strong>," : "Hello,";
        String color      = "confirmed".equals(status) ? "#2e7d32" : "declined".equals(status) ? "#c62828" : "#e65100";

        return "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'/></head>"
             + "<body style='margin:0;padding:0;background:#f0f2f5;"
             +   "font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,Helvetica,Arial,sans-serif;'>"
             + "<table width='100%' cellpadding='0' cellspacing='0'"
             +   " style='background:#f0f2f5;padding:32px 16px;'><tr><td align='center'>"
             + "<table width='100%' cellpadding='0' cellspacing='0'"
             +   " style='max-width:560px;background:#ffffff;border-radius:16px;"
             +   "overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,0.08);'>"
             // Header
             + "<tr><td style='background:linear-gradient(135deg,#673147 0%,#8d3f5f 100%);"
             +   "padding:32px 32px 24px;text-align:center;'>"
             + "<div style='font-size:40px;line-height:1;margin-bottom:12px;'>" + emoji + "</div>"
             + "<h1 style='color:#fff;font-size:20px;font-weight:700;margin:0 0 8px;'>"
             +   "Volunteer Status Update</h1>"
             + "<p style='color:rgba(255,255,255,0.8);font-size:13px;margin:0;'>" + safeEvent + "</p>"
             + "</td></tr>"
             // Body
             + "<tr><td style='padding:28px 32px 24px;'>"
             + "<p style='font-size:15px;color:#1a1a2e;margin:0 0 16px;'>" + safeHi + "</p>"
             + "<p style='font-size:14px;color:#555;margin:0 0 20px;line-height:1.6;'>"
             +   "<strong style='color:#1a1a2e;'>" + safeVol + "</strong> has responded to their volunteer"
             +   " assignment for <strong style='color:#673147;'>" + safeEvent + "</strong>.</p>"
             + "<div style='background:#f8f9fb;border-radius:10px;border:1px solid #e8eaf0;"
             +   "padding:16px 20px;text-align:center;margin-bottom:20px;'>"
             + "<span style='font-size:13px;font-weight:700;text-transform:uppercase;"
             +   "letter-spacing:0.5px;color:" + color + ";'>" + label + "</span>"
             + "</div>"
             + "<p style='font-size:13px;color:#888;margin:0;line-height:1.6;'>"
             +   "You can view and manage all volunteers on the Events page.</p>"
             + "</td></tr>"
             // Footer
             + "<tr><td style='background:#f8f9fb;border-top:1px solid #eeeff2;"
             +   "padding:16px 32px;text-align:center;'>"
             + "<p style='margin:0;font-size:11px;color:#aaa;'>"
             +   "This is an automated message &mdash; please do not reply.</p>"
             + "</td></tr>"
             + "</table></td></tr></table></body></html>";
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
    }

    // ── private helpers ───────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> saveRoles(EventVolunteer ev,
                                                Map<String, Object> body,
                                                String cid) {
        Object rolesRaw = body.get("roles");
        if (!(rolesRaw instanceof List)) return List.of();

        List<Object> rolesList = (List<Object>) rolesRaw;
        List<EventVolunteerRole> saved = new ArrayList<>();
        for (Object item : rolesList) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> rm = (Map<String, Object>) item;
            String rName = rm.get("roleName") != null ? rm.get("roleName").toString().trim() : null;
            if (rName == null || rName.isBlank()) continue;

            EventVolunteerRole evr = new EventVolunteerRole();
            evr.setAppClientId(cid);
            evr.setEventVolunteerId(ev.getId());
            evr.setRoleName(rName);
            evr.setCreatedAt(LocalDateTime.now());
            evRoleRepo.save(evr);
            saved.add(evr);
        }
        return saved.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",       r.getId());
            m.put("roleName", r.getRoleName());
            return m;
        }).toList();
    }
}
