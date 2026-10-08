package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.PrayerNote;
import com.churchgeniuspro.hibernate.PrayerRequest;
import com.churchgeniuspro.hibernate.PrayerRequestVolunteer;
import com.churchgeniuspro.hibernate.PrayerSchedule;
import com.churchgeniuspro.hibernate.PrayerSection;
import com.churchgeniuspro.hibernate.PrayerVolunteer;
import com.churchgeniuspro.hibernate.PromiseVerse;
import com.churchgeniuspro.repository.ChurchLogoRepository;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.PrayerNoteRepository;
import com.churchgeniuspro.repository.PrayerRequestVolunteerRepository;
import com.churchgeniuspro.repository.PrayerVolunteerRepository;
import com.churchgeniuspro.repository.PromiseVerseRepository;
import com.churchgeniuspro.repository.PrayerRequestRepository;
import com.churchgeniuspro.repository.PrayerScheduleRepository;
import com.churchgeniuspro.repository.PrayerSectionRepository;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles the Prayer Request page and its REST API.
 *
 * <p>Page route:    GET  /prayerRequest
 * <p>Section API:   GET/POST/PUT/DELETE  /api/prayer/sections[/{id}]
 * <p>Request API:   GET/POST/PUT/DELETE  /api/prayer/requests[/{id}]
 * <p>Schedule API:  GET/PUT              /api/prayer/schedule
 * <p>Email:         POST /api/prayer/notify
 */
@Controller
public class PrayerRequestController {

    private static final Logger log = LoggerFactory.getLogger(PrayerRequestController.class);

    private final PrayerSectionRepository      sectionRepo;
    private final PrayerRequestRepository      requestRepo;
    private final PrayerScheduleRepository     scheduleRepo;
    private final FamilyMemberRepository       memberRepo;
    private final JavaMailSender               mailSender;
    private final EmailService                 emailService;
    private final ChurchRegistrationRepository churchRepo;
    private final ChurchLogoRepository         logoRepo;
    private final PromiseVerseRepository       verseRepo;
    private final PrayerVolunteerRepository         volunteerRepo;
    private final PrayerNoteRepository              noteRepo;
    private final PrayerRequestVolunteerRepository  reqVolRepo;
    private final com.churchgeniuspro.service.PublicLinkResolver publicLinkResolver;

    @Value("${app.mail.from:${spring.mail.username:noreply@churchgeniuspro.com}}")
    private String fromAddress;

    public PrayerRequestController(PrayerSectionRepository      sectionRepo,
                                   PrayerRequestRepository      requestRepo,
                                   PrayerScheduleRepository     scheduleRepo,
                                   FamilyMemberRepository       memberRepo,
                                   JavaMailSender               mailSender,
                                   EmailService                 emailService,
                                   ChurchRegistrationRepository churchRepo,
                                   ChurchLogoRepository         logoRepo,
                                   PromiseVerseRepository       verseRepo,
                                   PrayerVolunteerRepository         volunteerRepo,
                                   PrayerNoteRepository              noteRepo,
                                   PrayerRequestVolunteerRepository  reqVolRepo,
                                   com.churchgeniuspro.service.PublicLinkResolver publicLinkResolver) {
        this.sectionRepo  = sectionRepo;
        this.requestRepo  = requestRepo;
        this.scheduleRepo = scheduleRepo;
        this.memberRepo   = memberRepo;
        this.mailSender   = mailSender;
        this.emailService = emailService;
        this.churchRepo   = churchRepo;
        this.logoRepo     = logoRepo;
        this.verseRepo    = verseRepo;
        this.volunteerRepo= volunteerRepo;
        this.noteRepo     = noteRepo;
        this.reqVolRepo   = reqVolRepo;
        this.publicLinkResolver = publicLinkResolver;
    }

    // ── Page route ────────────────────────────────────────────────────────

    @GetMapping("/prayerRequest")
    public String page(HttpServletRequest req) {
        String deny = RoleGuard.requireAdminOrUser(req);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(req, "general.ministry.prayer");
        if (deny != null) return deny;
        return "forward:/prayerRequest.html";
    }

    // ── Global Prayer Schedule ────────────────────────────────────────────

    /**
     * Returns the org's global prayer reminder schedule.
     * Returns an empty schedule object (all nulls) if none has been saved yet.
     */
    @ResponseBody
    @GetMapping("/api/prayer/schedule")
    public ResponseEntity<?> getSchedule(HttpServletRequest req) {
        String deny = apiDeny(req);
        if (deny != null) return ResponseEntity.status(RoleGuard.REDIRECT_LOGIN.equals(deny) ? 401 : 403).body(Map.of("error", "Access denied"));
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        PrayerSchedule sched = scheduleRepo.findByClientId(clientId).orElse(new PrayerSchedule());
        return ResponseEntity.ok(scheduleToMap(sched));
    }

    /**
     * Creates or fully replaces the org's global prayer reminder schedule.
     */
    @ResponseBody
    @PutMapping("/api/prayer/schedule")
    public ResponseEntity<?> saveSchedule(@RequestBody Map<String, Object> body,
                                          HttpServletRequest req) {
        String deny = apiDeny(req);
        if (deny != null) return ResponseEntity.status(RoleGuard.REDIRECT_LOGIN.equals(deny) ? 401 : 403).body(Map.of("error", "Access denied"));
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));

        PrayerSchedule sched = scheduleRepo.findByClientId(clientId).orElse(new PrayerSchedule());
        sched.setClientId(clientId);
        applyScheduleToEntity(sched, body);
        scheduleRepo.save(sched);
        return ResponseEntity.ok(scheduleToMap(sched));
    }

    // ── Section CRUD ──────────────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/prayer/sections")
    public ResponseEntity<?> listSections(HttpServletRequest req) {
        String deny = apiDeny(req);
        if (deny != null) return ResponseEntity.status(RoleGuard.REDIRECT_LOGIN.equals(deny) ? 401 : 403).body(Map.of("error", "Access denied"));
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        List<PrayerSection> sections =
                sectionRepo.findByClientIdAndDeleteFlagFalseOrderByCreatedAtAsc(clientId);
        return ResponseEntity.ok(sections.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",   s.getId());
            m.put("name", s.getName());
            return m;
        }).collect(Collectors.toList()));
    }

    @ResponseBody
    @PostMapping("/api/prayer/sections")
    public ResponseEntity<?> createSection(@RequestBody Map<String, String> body,
                                           HttpServletRequest req) {
        String deny = apiDeny(req);
        if (deny != null) return ResponseEntity.status(RoleGuard.REDIRECT_LOGIN.equals(deny) ? 401 : 403).body(Map.of("error", "Access denied"));
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        String name = body.get("name");
        if (name == null || name.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Section name is required"));

        PrayerSection s = new PrayerSection();
        s.setName(name.trim());
        s.setClientId(clientId);
        sectionRepo.save(s);
        return ResponseEntity.ok(Map.of("id", s.getId(), "name", s.getName()));
    }

    @ResponseBody
    @PutMapping("/api/prayer/sections/{id}")
    public ResponseEntity<?> updateSection(@PathVariable Long id,
                                           @RequestBody Map<String, String> body,
                                           HttpServletRequest req) {
        String deny = apiDeny(req);
        if (deny != null) return ResponseEntity.status(RoleGuard.REDIRECT_LOGIN.equals(deny) ? 401 : 403).body(Map.of("error", "Access denied"));
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        PrayerSection s = sectionRepo.findById(id).orElse(null);
        if (s == null || s.isDeleteFlag() || !clientId.equals(s.getClientId()))
            return ResponseEntity.notFound().build();
        String name = body.get("name");
        if (name == null || name.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Section name is required"));
        s.setName(name.trim());
        sectionRepo.save(s);
        return ResponseEntity.ok(Map.of("id", s.getId(), "name", s.getName()));
    }

    @ResponseBody
    @DeleteMapping("/api/prayer/sections/{id}")
    public ResponseEntity<?> deleteSection(@PathVariable Long id, HttpServletRequest req) {
        String deny = apiDeny(req);
        if (deny != null) return ResponseEntity.status(RoleGuard.REDIRECT_LOGIN.equals(deny) ? 401 : 403).body(Map.of("error", "Access denied"));
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        PrayerSection s = sectionRepo.findById(id).orElse(null);
        if (s == null || s.isDeleteFlag() || !clientId.equals(s.getClientId()))
            return ResponseEntity.notFound().build();

        // Soft-delete child requests first
        List<PrayerRequest> children =
                requestRepo.findBySectionIdAndDeleteFlagFalseOrderByCreatedAtAsc(id);
        children.forEach(r -> r.setDeleteFlag(true));
        requestRepo.saveAll(children);

        s.setDeleteFlag(true);
        sectionRepo.save(s);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    // ── Prayer Request CRUD ───────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/prayer/requests")
    public ResponseEntity<?> listRequests(HttpServletRequest req) {
        String deny = apiDeny(req);
        if (deny != null) return ResponseEntity.status(RoleGuard.REDIRECT_LOGIN.equals(deny) ? 401 : 403).body(Map.of("error", "Access denied"));
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        List<PrayerRequest> requests =
                requestRepo.findByClientIdAndDeleteFlagFalseOrderByCreatedAtAsc(clientId);
        return ResponseEntity.ok(requests.stream().map(this::toMap).collect(Collectors.toList()));
    }

    @ResponseBody
    @PostMapping("/api/prayer/requests")
    public ResponseEntity<?> createRequest(@RequestBody Map<String, Object> body,
                                           HttpServletRequest req) {
        String deny = apiDeny(req);
        if (deny != null) return ResponseEntity.status(RoleGuard.REDIRECT_LOGIN.equals(deny) ? 401 : 403).body(Map.of("error", "Access denied"));
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));

        String title = str(body, "title");
        if (title.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Title is required"));

        Object rawSection = body.get("sectionId");
        if (rawSection == null)
            return ResponseEntity.badRequest().body(Map.of("error", "Section is required"));
        Long sectionId = Long.valueOf(rawSection.toString());

        PrayerSection section = sectionRepo.findById(sectionId).orElse(null);
        if (section == null || section.isDeleteFlag() || !clientId.equals(section.getClientId()))
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid section"));

        PrayerRequest r = new PrayerRequest();
        r.setSectionId(sectionId);
        r.setTitle(title.trim());
        r.setDescription(str(body, "description"));
        r.setRequesterName(str(body, "requesterName"));
        // Default to "New" per v1 spec; @PrePersist also enforces this.
        r.setStatus(str(body, "status").isBlank() ? "New" : str(body, "status"));
        Integer assigneeId = toIntOrNull(body.get("assignedVolunteerId"));
        if (assigneeId != null && memberRepo.findByIdAndTenant(assigneeId, clientId).isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid volunteer"));
        r.setAssignedVolunteerId(assigneeId);
        r.setClientId(clientId);
        requestRepo.save(r);
        return ResponseEntity.ok(toMap(r));
    }

    @ResponseBody
    @PutMapping("/api/prayer/requests/{id}")
    public ResponseEntity<?> updateRequest(@PathVariable Long id,
                                           @RequestBody Map<String, Object> body,
                                           HttpServletRequest req) {
        String deny = apiDeny(req);
        if (deny != null) return ResponseEntity.status(RoleGuard.REDIRECT_LOGIN.equals(deny) ? 401 : 403).body(Map.of("error", "Access denied"));
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        PrayerRequest r = requestRepo.findById(id).orElse(null);
        if (r == null || r.isDeleteFlag() || !clientId.equals(r.getClientId()))
            return ResponseEntity.notFound().build();

        String title = str(body, "title");
        if (title.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Title is required"));

        if (body.containsKey("sectionId")) {
            Long sectionId = Long.valueOf(body.get("sectionId").toString());
            PrayerSection section = sectionRepo.findById(sectionId).orElse(null);
            if (section == null || section.isDeleteFlag() || !clientId.equals(section.getClientId()))
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid section"));
            r.setSectionId(sectionId);
        }
        r.setTitle(title.trim());
        r.setDescription(str(body, "description"));
        r.setRequesterName(str(body, "requesterName"));
        if (!str(body, "status").isBlank()) {
            String newStatus = str(body, "status");
            // Stamp closedAt when transitioning into Closed; clear when leaving.
            if ("Closed".equals(newStatus) && !"Closed".equals(r.getStatus())) {
                r.setClosedAt(new Date());
            } else if (!"Closed".equals(newStatus)) {
                r.setClosedAt(null);
            }
            r.setStatus(newStatus);
        }
        if (body.containsKey("assignedVolunteerId")) {
            Integer assigneeId = toIntOrNull(body.get("assignedVolunteerId"));
            if (assigneeId != null && memberRepo.findByIdAndTenant(assigneeId, clientId).isEmpty())
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid volunteer"));
            r.setAssignedVolunteerId(assigneeId);
        }
        requestRepo.save(r);
        return ResponseEntity.ok(toMap(r));
    }

    @ResponseBody
    @DeleteMapping("/api/prayer/requests/{id}")
    public ResponseEntity<?> deleteRequest(@PathVariable Long id, HttpServletRequest req) {
        String deny = apiDeny(req);
        if (deny != null) return ResponseEntity.status(RoleGuard.REDIRECT_LOGIN.equals(deny) ? 401 : 403).body(Map.of("error", "Access denied"));
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        PrayerRequest r = requestRepo.findById(id).orElse(null);
        if (r == null || r.isDeleteFlag() || !clientId.equals(r.getClientId()))
            return ResponseEntity.notFound().build();
        r.setDeleteFlag(true);
        requestRepo.save(r);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    /**
     * The one authority on whether a tenant may send at all. This controller
     * builds its own MimeMessage instead of going through EmailService, so it
     * asks for itself rather than inheriting that service's guard.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.service.MessagingPolicy messagingPolicy;

    // ── Email notification ────────────────────────────────────────────────

    @ResponseBody
    @PostMapping("/api/prayer/notify")
    public ResponseEntity<?> notifyMembers(HttpServletRequest req) {
        String deny = apiDeny(req);
        if (deny != null) return ResponseEntity.status(RoleGuard.REDIRECT_LOGIN.equals(deny) ? 401 : 403).body(Map.of("error", "Access denied"));
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));

        // Collect emails from family members:
        // inactive=false, disable_alerts=false, delete_flag=false, app_client_id=clientId, email not null/blank
        List<String> emails = memberRepo.findEmailsForPrayerNotification(clientId)
                .stream()
                .distinct()
                .collect(Collectors.toList());

        if (emails.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "No member email addresses found."));

        if (messagingPolicy != null) {
            String why = messagingPolicy.emailBlockReason(clientId);
            if (why != null) return ResponseEntity.status(403).body(Map.of("error", why));
        }

        // Load sections and requests grouped by section
        List<PrayerSection> sections =
                sectionRepo.findByClientIdAndDeleteFlagFalseOrderByCreatedAtAsc(clientId);
        List<PrayerRequest> allRequests =
                requestRepo.findByClientIdAndDeleteFlagFalseOrderByCreatedAtAsc(clientId);

        Map<Long, List<PrayerRequest>> bySection = allRequests.stream()
                .collect(Collectors.groupingBy(PrayerRequest::getSectionId));

        String churchName = emailService.getChurchName(clientId);
        String html = buildEmailHtml(sections, bySection, churchName);

        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(fromAddress);
            helper.setBcc(emails.toArray(new String[0]));
            helper.setSubject("Church Prayer Requests");
            helper.setText(html, true);
            mailSender.send(msg);
            return ResponseEntity.ok(Map.of("status", "sent", "count", emails.size()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to send email: " + e.getMessage()));
        }
    }

    // ── Public Prayer Request page (no auth required) ─────────────────────

    /**
     * Serves the public prayer request viewer/submission page.
     * No authentication required — org is identified via the {@code cid} query param
     * (an AES-encrypted clientId, generated by the Public Screens link builder).
     */
    @GetMapping("/viewPrayerRequest")
    public String viewPrayerRequestPage() {
        return "forward:/viewPrayerRequest.html";
    }

    /**
     * Public API: returns the church name for the given encrypted clientId.
     * Used by the public prayer page to display church-specific branding.
     */
    @ResponseBody
    @GetMapping("/api/prayer/public-church-info")
    public ResponseEntity<?> publicChurchInfo(@RequestParam(name = "cid") String encryptedCid) {
        String clientId = resolvePublicCid(encryptedCid);
        if (clientId == null) return ResponseEntity.badRequest().body(Map.of("error", "Invalid cid"));
        String name = churchRepo.findByClientIdAndDeleteFlagFalse(clientId)
                .map(c -> c.getChurchName())
                .orElse("");
        return ResponseEntity.ok(Map.of("name", name != null ? name : ""));
    }

    /**
     * Public API: serves the church logo image for the given encrypted clientId.
     * Returns 404 if no logo is configured.
     */
    @GetMapping("/api/prayer/public-logo")
    public ResponseEntity<byte[]> publicLogo(@RequestParam(name = "cid") String encryptedCid) {
        String clientId = resolvePublicCid(encryptedCid);
        if (clientId == null) return ResponseEntity.notFound().build();
        return logoRepo.findByClientId(clientId)
                .filter(l -> l.getLogoData() != null && l.getLogoData().length > 0)
                .map(l -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(
                                l.getContentType() != null ? l.getContentType() : "image/png"))
                        .body(l.getLogoData()))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Public API: returns today's promise verse for the org (by day-of-year).
     * Falls back to a random verse if today's day has no entry.
     * Returns 204 No Content if the org has no verses configured.
     */
    @ResponseBody
    @GetMapping("/api/prayer/public-verse")
    public ResponseEntity<?> publicVerse(@RequestParam(name = "cid") String encryptedCid) {
        String clientId = resolvePublicCid(encryptedCid);
        if (clientId == null) return ResponseEntity.badRequest().body(Map.of("error", "Invalid cid"));

        int dayOfYear = java.time.LocalDate.now().getDayOfYear();
        PromiseVerse verse = verseRepo.findByClientIdAndDayNumber(clientId, dayOfYear)
                .orElseGet(() -> verseRepo.findRandomByClientId(clientId).orElse(null));

        if (verse == null) return ResponseEntity.noContent().build();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reference", verse.getReference() != null ? verse.getReference() : "");
        m.put("verseText", verse.getVerseText()  != null ? verse.getVerseText()  : "");
        return ResponseEntity.ok(m);
    }

    /**
     * Public API: list all active prayer requests for an org identified by encrypted clientId.
     * Returns sections and their requests grouped together.
     */
    @ResponseBody
    @GetMapping("/api/prayer/public-requests")
    public ResponseEntity<?> listPublicRequests(@RequestParam(name = "cid") String encryptedCid) {
        String clientId = resolvePublicCid(encryptedCid);
        if (clientId == null) return ResponseEntity.status(400).body(Map.of("error", "Invalid or missing cid"));

        List<PrayerSection> sections =
                sectionRepo.findByClientIdAndDeleteFlagFalseOrderByCreatedAtAsc(clientId);
        List<PrayerRequest> allRequests =
                requestRepo.findByClientIdAndDeleteFlagFalseOrderByCreatedAtAsc(clientId)
                        .stream()
                        .filter(r -> "Active".equals(r.getStatus()))
                        .collect(Collectors.toList());

        Map<Long, List<PrayerRequest>> bySection = allRequests.stream()
                .collect(Collectors.groupingBy(PrayerRequest::getSectionId,
                        LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> result = new ArrayList<>();
        for (PrayerSection s : sections) {
            Map<String, Object> sec = new LinkedHashMap<>();
            sec.put("id", s.getId());
            sec.put("name", s.getName());
            List<Map<String, Object>> reqs = bySection.getOrDefault(s.getId(), List.of())
                    .stream().map(r -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id",            r.getId());
                        m.put("title",         r.getTitle());
                        m.put("description",   r.getDescription() != null ? r.getDescription() : "");
                        m.put("requesterName", r.getRequesterName() != null ? r.getRequesterName() : "");
                        m.put("createdAt",     r.getCreatedAt() != null
                                ? new SimpleDateFormat("yyyy-MM-dd").format(r.getCreatedAt()) : "");
                        return m;
                    }).collect(Collectors.toList());
            sec.put("requests", reqs);
            result.add(sec);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Public API: submit a new prayer request on behalf of a public visitor.
     * Org identified via encrypted {@code cid} query param.
     */
    @ResponseBody
    @PostMapping("/api/prayer/public-submit")
    public ResponseEntity<?> publicSubmit(@RequestParam(name = "cid") String encryptedCid,
                                          @RequestBody Map<String, Object> body) {
        String clientId = resolvePublicCid(encryptedCid);
        if (clientId == null) return ResponseEntity.status(400).body(Map.of("error", "Invalid or missing cid"));

        String title = str(body, "title");
        if (title.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Title is required"));

        // Find or use the provided sectionId
        Long sectionId = null;
        Object rawSection = body.get("sectionId");
        if (rawSection != null) {
            try { sectionId = Long.valueOf(rawSection.toString()); } catch (NumberFormatException ignored) {}
        }
        if (sectionId == null) {
            // Default to first section for the org
            List<PrayerSection> sections = sectionRepo.findByClientIdAndDeleteFlagFalseOrderByCreatedAtAsc(clientId);
            if (sections.isEmpty())
                return ResponseEntity.badRequest().body(Map.of("error", "No sections configured for this church"));
            sectionId = sections.get(0).getId();
        } else {
            PrayerSection section = sectionRepo.findById(sectionId).orElse(null);
            if (section == null || section.isDeleteFlag() || !clientId.equals(section.getClientId()))
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid section"));
        }

        PrayerRequest r = new PrayerRequest();
        r.setSectionId(sectionId);
        r.setTitle(title.trim());
        r.setDescription(str(body, "description"));
        r.setRequesterName(str(body, "requesterName"));
        r.setStatus("New");
        r.setClientId(clientId);
        requestRepo.save(r);
        return ResponseEntity.ok(Map.of("status", "submitted", "id", r.getId()));
    }

    // ── Volunteers (prayer-team members) ──────────────────────────────────

    /** Lists the prayer-team volunteer roster, active rows first. */
    @ResponseBody
    @GetMapping("/api/prayer/volunteers")
    public ResponseEntity<?> listVolunteers(HttpServletRequest req) {
        String deny = apiDeny(req);
        if (deny != null) return ResponseEntity.status(RoleGuard.REDIRECT_LOGIN.equals(deny) ? 401 : 403).body(Map.of("error", "Access denied"));
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (PrayerVolunteer v : volunteerRepo.findByClient(clientId)) {
            out.add(toVolunteerMap(v, clientId));
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Adds a volunteer to the prayer team. Two shapes are supported:
     *   • Linked member  — {@code familyMemberId} points at a FamilyMember;
     *                      manual fields are ignored, name/contact come from
     *                      the linked row at display time.
     *   • Manual         — {@code familyMemberId} omitted; caller supplies
     *                      firstName/lastName/phone/email/notes/availability.
     *                      At least one of firstName or lastName is required.
     *
     * <p>Linked-member adds are idempotent — a re-add reactivates the existing
     * row instead of duplicating.
     */
    @ResponseBody
    @PostMapping("/api/prayer/volunteers")
    public ResponseEntity<?> addVolunteer(@RequestBody Map<String, Object> body,
                                          HttpServletRequest req) {
        String deny = apiDeny(req);
        if (deny != null) return ResponseEntity.status(RoleGuard.REDIRECT_LOGIN.equals(deny) ? 401 : 403).body(Map.of("error", "Access denied"));
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        Integer memberId = toIntOrNull(body.get("familyMemberId"));

        PrayerVolunteer v;
        if (memberId != null) {
            FamilyMember fm = memberRepo.findByIdAndTenant(memberId, clientId).orElse(null);
            if (fm == null) return ResponseEntity.badRequest().body(Map.of("error", "Member not found"));
            v = volunteerRepo.findByMember(clientId, memberId).orElseGet(() -> {
                PrayerVolunteer fresh = new PrayerVolunteer();
                fresh.setClientId(clientId);
                fresh.setFamilyMemberId(memberId);
                return fresh;
            });
        } else {
            // Manual entry — require at least a name.
            String first = str(body, "firstName");
            String last  = str(body, "lastName");
            if (first.isBlank() && last.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Manual volunteer needs at least a first or last name"));
            }
            v = new PrayerVolunteer();
            v.setClientId(clientId);
        }
        applyVolunteerBody(v, body);
        v.setDeleteFlag(false);
        try {
            return ResponseEntity.ok(toVolunteerMap(volunteerRepo.save(v), clientId));
        } catch (Exception ex) {
            // Surface DB constraint / schema errors to the UI so the operator
            // sees what went wrong instead of a bare 500.
            log.error("addVolunteer failed for clientId={}, memberId={}", clientId, memberId, ex);
            return ResponseEntity.status(500).body(Map.of(
                "error",  "Could not save volunteer",
                "detail", rootCauseMessage(ex)));
        }
    }

    /** Walks the cause chain so DataAccessExceptions surface the underlying SQL message. */
    private static String rootCauseMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur.getMessage();
    }

    @ResponseBody
    @PutMapping("/api/prayer/volunteers/{id}")
    public ResponseEntity<?> updateVolunteer(@PathVariable Integer id,
                                             @RequestBody Map<String, Object> body,
                                             HttpServletRequest req) {
        String deny = apiDeny(req);
        if (deny != null) return ResponseEntity.status(RoleGuard.REDIRECT_LOGIN.equals(deny) ? 401 : 403).body(Map.of("error", "Access denied"));
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        PrayerVolunteer v = volunteerRepo.findByIdAndClientId(id, clientId).orElse(null);
        if (v == null) return ResponseEntity.notFound().build();
        applyVolunteerBody(v, body);
        return ResponseEntity.ok(toVolunteerMap(volunteerRepo.save(v), clientId));
    }

    /** Applies whichever volunteer fields the body sets; safe for both add/update. */
    private void applyVolunteerBody(PrayerVolunteer v, Map<String, Object> body) {
        if (body.containsKey("role"))         v.setRole(str(body, "role"));
        if (body.containsKey("firstName"))    v.setFirstName(str(body, "firstName"));
        if (body.containsKey("lastName"))     v.setLastName(str(body, "lastName"));
        if (body.containsKey("phone"))        v.setPhone(str(body, "phone"));
        if (body.containsKey("email"))        v.setEmail(str(body, "email"));
        if (body.containsKey("notes"))        v.setNotes(str(body, "notes"));
        if (body.containsKey("availability")) v.setAvailability(str(body, "availability"));
        if (body.containsKey("active"))       v.setActive(Boolean.TRUE.equals(body.get("active")));
        else if (v.getId() == null)           v.setActive(true);   // default on create
    }

    /**
     * Soft-deletes a volunteer. Any prayer requests still assigned to them
     * keep the assignedVolunteerId reference; the UI shows "(unknown)" for
     * removed volunteers and prompts to reassign.
     */
    @ResponseBody
    @DeleteMapping("/api/prayer/volunteers/{id}")
    public ResponseEntity<?> deleteVolunteer(@PathVariable Integer id, HttpServletRequest req) {
        String deny = apiDeny(req);
        if (deny != null) return ResponseEntity.status(RoleGuard.REDIRECT_LOGIN.equals(deny) ? 401 : 403).body(Map.of("error", "Access denied"));
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        PrayerVolunteer v = volunteerRepo.findByIdAndClientId(id, clientId).orElse(null);
        if (v == null) return ResponseEntity.notFound().build();
        // Also drop every join row so the deleted volunteer disappears from
        // requests' Assigned Volunteers section without leaving orphan FKs.
        reqVolRepo.deleteAllForVolunteer(clientId, id);
        v.setDeleteFlag(true);
        v.setActive(false);
        volunteerRepo.save(v);
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── Multi-assign per request (M:N) ────────────────────────────────────

    /** List every volunteer currently assigned to a single request. */
    @ResponseBody
    @GetMapping("/api/prayer/requests/{id}/volunteers")
    public ResponseEntity<?> listRequestVolunteers(@PathVariable Long id, HttpServletRequest req) {
        String deny = apiDeny(req);
        if (deny != null) return ResponseEntity.status(RoleGuard.REDIRECT_LOGIN.equals(deny) ? 401 : 403).body(Map.of("error", "Access denied"));
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        PrayerRequest r = requestRepo.findById(id).orElse(null);
        if (r == null || r.isDeleteFlag() || !clientId.equals(r.getClientId()))
            return ResponseEntity.notFound().build();
        return ResponseEntity.ok(loadAssignedVolunteers(clientId, id));
    }

    /**
     * Add a volunteer to a request. Idempotent — if the join row already
     * exists, returns the existing row instead of erroring. Also keeps the
     * legacy {@code assignedVolunteerId} pointing at the most recent volunteer
     * so old reads still show "an" assignee, and flips status New → Assigned
     * the first time anyone is added.
     */
    @ResponseBody
    @PostMapping("/api/prayer/requests/{id}/volunteers")
    public ResponseEntity<?> addRequestVolunteer(@PathVariable Long id,
                                                 @RequestBody Map<String, Object> body,
                                                 HttpServletRequest req) {
        String deny = apiDeny(req);
        if (deny != null) return ResponseEntity.status(RoleGuard.REDIRECT_LOGIN.equals(deny) ? 401 : 403).body(Map.of("error", "Access denied"));
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        PrayerRequest r = requestRepo.findById(id).orElse(null);
        if (r == null || r.isDeleteFlag() || !clientId.equals(r.getClientId()))
            return ResponseEntity.notFound().build();
        Integer volId = toIntOrNull(body.get("prayerVolunteerId"));
        if (volId == null) return ResponseEntity.badRequest()
                .body(Map.of("error", "prayerVolunteerId is required"));
        PrayerVolunteer v = volunteerRepo.findByIdAndClientId(volId, clientId).orElse(null);
        if (v == null) return ResponseEntity.badRequest().body(Map.of("error", "Volunteer not found"));

        reqVolRepo.findByPrayerRequestIdAndPrayerVolunteerId(id, volId)
                .orElseGet(() -> {
                    PrayerRequestVolunteer link = new PrayerRequestVolunteer();
                    link.setClientId(clientId);
                    link.setPrayerRequestId(id);
                    link.setPrayerVolunteerId(volId);
                    return reqVolRepo.save(link);
                });

        // Mirror to legacy single-FK + advance status if it was still "New".
        r.setAssignedVolunteerId(v.getFamilyMemberId());
        if ("New".equals(r.getStatus()) || "Active".equals(r.getStatus())) {
            r.setStatus("Assigned");
        }
        requestRepo.save(r);
        return ResponseEntity.ok(Map.of(
                "request",   toMap(r),
                "volunteers", loadAssignedVolunteers(clientId, id)));
    }

    /** Unassign a single volunteer from a request. */
    @ResponseBody
    @DeleteMapping("/api/prayer/requests/{id}/volunteers/{volunteerId}")
    public ResponseEntity<?> removeRequestVolunteer(@PathVariable Long id,
                                                    @PathVariable Integer volunteerId,
                                                    HttpServletRequest req) {
        String deny = apiDeny(req);
        if (deny != null) return ResponseEntity.status(RoleGuard.REDIRECT_LOGIN.equals(deny) ? 401 : 403).body(Map.of("error", "Access denied"));
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        PrayerRequest r = requestRepo.findById(id).orElse(null);
        if (r == null || r.isDeleteFlag() || !clientId.equals(r.getClientId()))
            return ResponseEntity.notFound().build();
        reqVolRepo.deleteAssignment(clientId, id, volunteerId);
        // If the removed one matched the legacy single-FK, recompute from
        // whatever is still assigned (or null if no one is left).
        List<PrayerRequestVolunteer> remaining = reqVolRepo.findByRequest(clientId, id);
        if (remaining.isEmpty()) {
            r.setAssignedVolunteerId(null);
        } else {
            PrayerVolunteer next = volunteerRepo.findByIdAndClientId(
                    remaining.get(0).getPrayerVolunteerId(), clientId).orElse(null);
            r.setAssignedVolunteerId(next != null ? next.getFamilyMemberId() : null);
        }
        requestRepo.save(r);
        return ResponseEntity.ok(Map.of(
                "request",   toMap(r),
                "volunteers", loadAssignedVolunteers(clientId, id)));
    }

    /** Helper: hydrates the join rows into full volunteer details for the UI. */
    private List<Map<String, Object>> loadAssignedVolunteers(String clientId, Long requestId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PrayerRequestVolunteer link : reqVolRepo.findByRequest(clientId, requestId)) {
            PrayerVolunteer v = volunteerRepo
                    .findByIdAndClientId(link.getPrayerVolunteerId(), clientId).orElse(null);
            if (v == null) continue;
            Map<String, Object> m = toVolunteerMap(v, clientId);
            m.put("linkId", link.getId());
            out.add(m);
        }
        return out;
    }

    // ── Assignment shortcut ──────────────────────────────────────────────

    /**
     * Convenience endpoint used by the "Assign / Reassign" picker on each
     * request card. Equivalent to a PUT on the request with just the
     * assignedVolunteerId and (optionally) status flipped to "Assigned".
     */
    @ResponseBody
    @PostMapping("/api/prayer/requests/{id}/assign")
    public ResponseEntity<?> assignRequest(@PathVariable Long id,
                                           @RequestBody Map<String, Object> body,
                                           HttpServletRequest req) {
        String deny = apiDeny(req);
        if (deny != null) return ResponseEntity.status(RoleGuard.REDIRECT_LOGIN.equals(deny) ? 401 : 403).body(Map.of("error", "Access denied"));
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        PrayerRequest r = requestRepo.findById(id).orElse(null);
        if (r == null || r.isDeleteFlag() || !clientId.equals(r.getClientId()))
            return ResponseEntity.notFound().build();
        Integer volunteerId = toIntOrNull(body.get("assignedVolunteerId"));
        if (volunteerId != null && memberRepo.findByIdAndTenant(volunteerId, clientId).isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid volunteer"));
        r.setAssignedVolunteerId(volunteerId);
        // When attaching a volunteer, advance "New" → "Assigned"; leave other
        // statuses alone so the assignee swap doesn't reset progress.
        if (volunteerId != null && ("New".equals(r.getStatus()) || "Active".equals(r.getStatus()))) {
            r.setStatus("Assigned");
        }
        requestRepo.save(r);
        return ResponseEntity.ok(toMap(r));
    }

    /** Close (archive) a request. Hidden from the default Active list. */
    @ResponseBody
    @PostMapping("/api/prayer/requests/{id}/close")
    public ResponseEntity<?> closeRequest(@PathVariable Long id, HttpServletRequest req) {
        String deny = apiDeny(req);
        if (deny != null) return ResponseEntity.status(RoleGuard.REDIRECT_LOGIN.equals(deny) ? 401 : 403).body(Map.of("error", "Access denied"));
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        PrayerRequest r = requestRepo.findById(id).orElse(null);
        if (r == null || r.isDeleteFlag() || !clientId.equals(r.getClientId()))
            return ResponseEntity.notFound().build();
        r.setStatus("Closed");
        r.setClosedAt(new Date());
        requestRepo.save(r);
        return ResponseEntity.ok(toMap(r));
    }

    /** Re-open a closed request (status → New, closedAt cleared). */
    @ResponseBody
    @PostMapping("/api/prayer/requests/{id}/reopen")
    public ResponseEntity<?> reopenRequest(@PathVariable Long id, HttpServletRequest req) {
        String deny = apiDeny(req);
        if (deny != null) return ResponseEntity.status(RoleGuard.REDIRECT_LOGIN.equals(deny) ? 401 : 403).body(Map.of("error", "Access denied"));
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        PrayerRequest r = requestRepo.findById(id).orElse(null);
        if (r == null || r.isDeleteFlag() || !clientId.equals(r.getClientId()))
            return ResponseEntity.notFound().build();
        r.setStatus(r.getAssignedVolunteerId() != null ? "Assigned" : "New");
        r.setClosedAt(null);
        requestRepo.save(r);
        return ResponseEntity.ok(toMap(r));
    }

    // ── Notes (append-only thread per request) ────────────────────────────

    @ResponseBody
    @GetMapping("/api/prayer/requests/{id}/notes")
    public ResponseEntity<?> listNotes(@PathVariable Long id, HttpServletRequest req) {
        String deny = apiDeny(req);
        if (deny != null) return ResponseEntity.status(RoleGuard.REDIRECT_LOGIN.equals(deny) ? 401 : 403).body(Map.of("error", "Access denied"));
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        // Validate the request belongs to this tenant.
        PrayerRequest r = requestRepo.findById(id).orElse(null);
        if (r == null || r.isDeleteFlag() || !clientId.equals(r.getClientId()))
            return ResponseEntity.notFound().build();
        List<Map<String, Object>> out = new ArrayList<>();
        for (PrayerNote n : noteRepo.findByRequest(clientId, id)) {
            out.add(toNoteMap(n));
        }
        return ResponseEntity.ok(out);
    }

    @ResponseBody
    @PostMapping("/api/prayer/requests/{id}/notes")
    public ResponseEntity<?> addNote(@PathVariable Long id,
                                     @RequestBody Map<String, Object> body,
                                     HttpServletRequest req) {
        String deny = apiDeny(req);
        if (deny != null) return ResponseEntity.status(RoleGuard.REDIRECT_LOGIN.equals(deny) ? 401 : 403).body(Map.of("error", "Access denied"));
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        PrayerRequest r = requestRepo.findById(id).orElse(null);
        if (r == null || r.isDeleteFlag() || !clientId.equals(r.getClientId()))
            return ResponseEntity.notFound().build();
        String text = str(body, "body");
        if (text.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "Note body is required"));
        PrayerNote n = new PrayerNote();
        n.setClientId(clientId);
        n.setPrayerRequestId(id);
        n.setBody(text);
        n.setCreatedByUserId(currentUserToken(req));
        n.setCreatedByName(currentUserDisplayName(req));
        n.setDeleteFlag(false);
        return ResponseEntity.ok(toNoteMap(noteRepo.save(n)));
    }

    @ResponseBody
    @DeleteMapping("/api/prayer/notes/{id}")
    public ResponseEntity<?> deleteNote(@PathVariable Integer id, HttpServletRequest req) {
        String deny = apiDeny(req);
        if (deny != null) return ResponseEntity.status(RoleGuard.REDIRECT_LOGIN.equals(deny) ? 401 : 403).body(Map.of("error", "Access denied"));
        String clientId = resolveClientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        PrayerNote n = noteRepo.findByIdAndClientId(id, clientId).orElse(null);
        if (n == null) return ResponseEntity.notFound().build();
        n.setDeleteFlag(true);
        noteRepo.save(n);
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── Mappers for the new types ────────────────────────────────────────

    private Map<String, Object> toVolunteerMap(PrayerVolunteer v, String clientId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",             v.getId());
        m.put("familyMemberId", v.getFamilyMemberId());
        // For linked volunteers, derive name from FamilyMember so renames flow
        // through. For manual volunteers, use the stored name fields.
        String name;
        String phone;
        String email;
        if (v.getFamilyMemberId() != null) {
            name  = resolveAssigneeName(v.getFamilyMemberId(), clientId);
            FamilyMember fm = memberRepo.findByIdAndTenant(v.getFamilyMemberId(), clientId).orElse(null);
            phone = fm != null ? fm.getPhone() : null;
            email = fm != null ? fm.getEmail() : null;
        } else {
            String fn = v.getFirstName() != null ? v.getFirstName() : "";
            String ln = v.getLastName()  != null ? v.getLastName()  : "";
            name  = (fn + " " + ln).trim();
            if (name.isEmpty()) name = "(unnamed)";
            phone = v.getPhone();
            email = v.getEmail();
        }
        m.put("name",         name);
        m.put("role",         v.getRole());
        m.put("phone",        phone);
        m.put("email",        email);
        m.put("notes",        v.getNotes());
        m.put("availability", v.getAvailability());
        m.put("manual",       v.getFamilyMemberId() == null);
        m.put("active",       v.isActive());
        return m;
    }

    private Map<String, Object> toNoteMap(PrayerNote n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",        n.getId());
        m.put("body",      n.getBody());
        m.put("createdBy", n.getCreatedByName());
        m.put("createdAt", n.getCreatedDate() != null
                ? new SimpleDateFormat("yyyy-MM-dd HH:mm").format(n.getCreatedDate()) : "");
        return m;
    }

    /** USR<uuid> from the session, "system" fallback so notes always have an author. */
    private String currentUserToken(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s == null) return "system";
        Object uid = s.getAttribute("userId");
        return uid instanceof String str && !str.isBlank() ? str : "system";
    }

    /** Best-effort display name for a note's author. Falls back to "Staff". */
    private String currentUserDisplayName(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s == null) return "Staff";
        Object n = s.getAttribute("displayName");
        if (n instanceof String str && !str.isBlank()) return str;
        Object u = s.getAttribute("username");
        if (u instanceof String str && !str.isBlank()) return str;
        return "Staff";
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Same guard chain as the {@code /prayerRequest} page route, applied to the
     * session-backed API handlers. Returns {@code null} when allowed.
     */
    private static String apiDeny(HttpServletRequest req) {
        String deny = RoleGuard.requireAdminOrUser(req);
        if (deny != null) return deny;
        return RoleGuard.requirePermission(req, "general.prayer");
    }

    /** Resolves the organization clientId from the session regardless of login type. */
    private String resolveClientId(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return null;
        // Non-church users store the org clientId in appClientId
        Object appClientId = session.getAttribute("appClientId");
        if (appClientId instanceof String s && !s.isBlank()) return s;
        // Church users store it directly in clientId
        Object clientId = session.getAttribute("clientId");
        if (clientId instanceof String s && !s.isBlank()) return s;
        return null;
    }

    private Map<String, Object> toMap(PrayerRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",               r.getId());
        m.put("sectionId",        r.getSectionId());
        m.put("title",            r.getTitle());
        m.put("description",      r.getDescription()   != null ? r.getDescription()   : "");
        m.put("requesterName",    r.getRequesterName() != null ? r.getRequesterName() : "");
        // Normalise legacy "Active" → "New" for the UI so old rows display in
        // the New bucket without a schema migration. "Active" is still
        // accepted on write for backward compatibility.
        String rawStatus = r.getStatus();
        String status = "Active".equalsIgnoreCase(rawStatus) ? "New"
                : (rawStatus != null && !rawStatus.isBlank() ? rawStatus : "New");
        m.put("status",           status);
        m.put("createdAt",        r.getCreatedAt()     != null
                                  ? new SimpleDateFormat("yyyy-MM-dd").format(r.getCreatedAt()) : "");
        m.put("closedAt",         r.getClosedAt()      != null
                                  ? new SimpleDateFormat("yyyy-MM-dd").format(r.getClosedAt()) : null);
        // Legacy single-assignee (kept for back-compat with existing UI bits).
        m.put("assignedVolunteerId", r.getAssignedVolunteerId());
        m.put("assignedVolunteerName", resolveAssigneeName(r.getAssignedVolunteerId(), r.getClientId()));
        // New: full multi-assign list. Each entry is the same shape as a
        // /api/prayer/volunteers row, plus a linkId for the join row.
        List<Map<String, Object>> assignees = (r.getClientId() != null && r.getId() != null)
                ? loadAssignedVolunteers(r.getClientId(), r.getId())
                : new ArrayList<>();
        m.put("assignedVolunteers", assignees);
        m.put("assignedVolunteerCount", assignees.size());
        // Notes count drives the "💬 N" badge on each request card. Loaded
        // separately on demand by the Notes drawer endpoint.
        m.put("noteCount", r.getClientId() != null && r.getId() != null
                ? noteRepo.findByRequest(r.getClientId(), r.getId()).size() : 0);
        // schedule fields
        m.put("occurrence",       r.getOccurrence());
        m.put("weekDays",         stringToInts(r.getWeekDays()));
        m.put("monthMonths",      stringToInts(r.getMonthMonths()));
        m.put("monthDayOfMonth",  r.getMonthDayOfMonth());
        m.put("monthWeekOrdinal", r.getMonthWeekOrdinal());
        m.put("monthWeekDay",     r.getMonthWeekDay());
        return m;
    }

    /** Pretty name for the assigned volunteer chip. Returns null when
     *  unassigned, or "(unknown)" when the linked FamilyMember was removed. */
    private String resolveAssigneeName(Integer familyMemberId, String clientId) {
        if (familyMemberId == null || clientId == null) return null;
        FamilyMember fm = memberRepo.findByIdAndTenant(familyMemberId, clientId).orElse(null);
        if (fm == null) return "(unknown)";
        String n = ((fm.getFirstName() != null ? fm.getFirstName() : "") + " "
                  + (fm.getLastName()  != null ? fm.getLastName()  : "")).trim();
        return n.isEmpty() ? "(unnamed)" : n;
    }

    private String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v instanceof String s ? s.trim() : "";
    }

    /** Reads schedule fields from the JSON body and applies them to the entity. */
    private void applySchedule(PrayerRequest r, Map<String, Object> body) {
        String occ = str(body, "occurrence");
        r.setOccurrence(occ.isBlank() ? null : occ);

        if ("Weekly".equals(occ)) {
            r.setWeekDays(intsToString(body.get("weekDays")));
        } else {
            r.setWeekDays(null);
        }

        if ("Monthly".equals(occ)) {
            r.setMonthMonths(intsToString(body.get("monthMonths")));
            r.setMonthDayOfMonth(toIntOrNull(body.get("monthDayOfMonth")));
            r.setMonthWeekOrdinal(toIntOrNull(body.get("monthWeekOrdinal")));
            r.setMonthWeekDay(toIntOrNull(body.get("monthWeekDay")));
        } else {
            r.setMonthMonths(null);
            r.setMonthDayOfMonth(null);
            r.setMonthWeekOrdinal(null);
            r.setMonthWeekDay(null);
        }
    }

    /** Converts a JSON array (List&lt;Integer&gt; or int[]) to a comma-separated string. */
    @SuppressWarnings("unchecked")
    private String intsToString(Object val) {
        if (val == null) return null;
        if (val instanceof List<?> list) {
            if (list.isEmpty()) return null;
            return list.stream().map(Object::toString).collect(Collectors.joining(","));
        }
        return null;
    }

    /** Converts a comma-separated string to an int[], or null if blank. */
    private int[] stringToInts(String s) {
        if (s == null || s.isBlank()) return null;
        String[] parts = s.split(",");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { result[i] = Integer.parseInt(parts[i].trim()); }
            catch (NumberFormatException e) { result[i] = 0; }
        }
        return result;
    }

    private Integer toIntOrNull(Object val) {
        if (val == null) return null;
        try { return Integer.parseInt(val.toString()); }
        catch (NumberFormatException e) { return null; }
    }

    private String buildEmailHtml(List<PrayerSection> sections,
                                  Map<Long, List<PrayerRequest>> bySection,
                                  String churchName) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
            <div style="font-family:Arial,sans-serif;max-width:640px;margin:0 auto;background:#f5f6fa;padding:20px;">
              <div style="background:#5c6bc0;color:#fff;padding:24px 28px;border-radius:10px 10px 0 0;text-align:center;">
                <h1 style="margin:0;font-size:22px;">&#x1F64F; Prayer Requests</h1>
              </div>
              <div style="background:#fff;padding:28px;border-radius:0 0 10px 10px;box-shadow:0 2px 8px rgba(0,0,0,.06);">
            """);

        if (sections.isEmpty()) {
            sb.append("<p style='color:#666;'>No prayer requests at this time.</p>");
        } else {
            for (PrayerSection section : sections) {
                List<PrayerRequest> requests = bySection.getOrDefault(section.getId(), List.of());
                sb.append("<div style='margin-bottom:24px;'>");
                sb.append("<h2 style='color:#5c6bc0;font-size:16px;border-bottom:2px solid #e8eaf6;")
                  .append("padding-bottom:6px;margin-bottom:12px;'>")
                  .append(escHtml(section.getName()))
                  .append("</h2>");

                if (requests.isEmpty()) {
                    sb.append("<p style='color:#aaa;font-size:13px;'>No requests in this section.</p>");
                } else {
                    for (PrayerRequest r : requests) {
                        sb.append("<div style='background:#f8f9fe;border-left:4px solid #7986cb;")
                          .append("border-radius:6px;padding:12px 16px;margin-bottom:10px;'>");
                        sb.append("<div style='font-weight:700;color:#3f4568;font-size:14px;'>")
                          .append(escHtml(r.getTitle())).append("</div>");
                        if (!r.getDescription().isBlank()) {
                            sb.append("<div style='color:#555;font-size:13px;margin-top:6px;'>")
                              .append(escHtml(r.getDescription()).replace("\n", "<br>"))
                              .append("</div>");
                        }
                        if (r.getRequesterName() != null && !r.getRequesterName().isBlank()) {
                            sb.append("<div style='color:#9fa8c4;font-size:12px;margin-top:6px;'>")
                              .append("Requested by: ").append(escHtml(r.getRequesterName()))
                              .append("</div>");
                        }
                        String statusColor = "Answered".equals(r.getStatus()) ? "#43a047" : "#7986cb";
                        sb.append("<div style='margin-top:8px;'><span style='background:")
                          .append(statusColor)
                          .append(";color:#fff;font-size:11px;padding:2px 10px;border-radius:12px;'>")
                          .append(escHtml(r.getStatus())).append("</span></div>");
                        sb.append("</div>");
                    }
                }
                sb.append("</div>");
            }
        }

        sb.append("<p style=\"color:#aaa;font-size:12px;text-align:center;margin-top:24px;border-top:1px solid #eee;padding-top:16px;\">")
          .append("This email was sent by ").append(escHtml(churchName)).append(".")
          .append("</p></div></div>");
        return sb.toString();
    }

    /** Serialises a {@link PrayerSchedule} to a JSON-friendly map. */
    private Map<String, Object> scheduleToMap(PrayerSchedule s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("occurrence",       s.getOccurrence());
        m.put("weekDays",         stringToInts(s.getWeekDays()));
        m.put("monthMonths",      stringToInts(s.getMonthMonths()));
        m.put("monthDayOfMonth",  s.getMonthDayOfMonth());
        m.put("monthWeekOrdinal", s.getMonthWeekOrdinal());
        m.put("monthWeekDay",     s.getMonthWeekDay());
        m.put("sendHour",         s.getSendHour());
        return m;
    }

    /** Applies schedule fields from a JSON body map to a {@link PrayerSchedule} entity. */
    private void applyScheduleToEntity(PrayerSchedule s, Map<String, Object> body) {
        String occ = str(body, "occurrence");
        s.setOccurrence(occ.isBlank() ? null : occ);

        if ("Weekly".equals(occ)) {
            s.setWeekDays(intsToString(body.get("weekDays")));
        } else {
            s.setWeekDays(null);
        }

        if ("Monthly".equals(occ)) {
            s.setMonthMonths(intsToString(body.get("monthMonths")));
            s.setMonthDayOfMonth(toIntOrNull(body.get("monthDayOfMonth")));
            s.setMonthWeekOrdinal(toIntOrNull(body.get("monthWeekOrdinal")));
            s.setMonthWeekDay(toIntOrNull(body.get("monthWeekDay")));
        } else {
            s.setMonthMonths(null);
            s.setMonthDayOfMonth(null);
            s.setMonthWeekOrdinal(null);
            s.setMonthWeekDay(null);
        }

        // Send hour — stored regardless of occurrence type
        s.setSendHour(toIntOrNull(body.get("sendHour")));
    }

    /**
     * Resolves the tenant behind the {@code cid} query param on the public prayer
     * endpoints. Goes through {@link com.churchgeniuspro.service.PublicLinkResolver}
     * (page {@code /publicPrayer}) so a link the church has revoked or let expire
     * stops working, instead of accepting any ciphertext that decrypts.
     * Returns {@code null} when access is refused.
     */
    private String resolvePublicCid(String encryptedCid) {
        if (encryptedCid == null || encryptedCid.isBlank()) return null;
        String param;
        try {
            param = java.net.URLDecoder.decode(encryptedCid, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
        return publicLinkResolver.resolveClientId(param, "/publicPrayer");
    }

    private String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
