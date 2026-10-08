package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.ChurchEvent;
import com.churchgeniuspro.hibernate.ChurchLogo;
import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.EventRegistration;
import com.churchgeniuspro.model.ChurchEventBO;
import com.churchgeniuspro.model.EventRegistrationBO;
import com.churchgeniuspro.repository.ChurchLogoRepository;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.service.ChurchEventService;
import com.churchgeniuspro.util.EncryptionUtil;
import com.churchgeniuspro.util.PublicSendLimiter;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles the Event Setup page and all event REST API endpoints.
 *
 * <h3>Page routes</h3>
 * <ul>
 *   <li>{@code GET /event}                    → {@code event.html} (authenticated)</li>
 *   <li>{@code GET /event-register/{eventId}} → {@code event-register.html} (public)</li>
 * </ul>
 *
 * <h3>Authenticated API routes</h3>
 * <ul>
 *   <li>{@code GET    /api/events}                        → list all events for org</li>
 *   <li>{@code GET    /api/events/{id}}                   → single event with days</li>
 *   <li>{@code POST   /api/events}                        → create event</li>
 *   <li>{@code PUT    /api/events/{id}}                   → update event</li>
 *   <li>{@code DELETE /api/events/{id}}                   → soft-delete event</li>
 *   <li>{@code GET    /api/events/{id}/registrations}     → list registrations</li>
 *   <li>{@code DELETE /api/event-registrations/{id}}      → hard-delete a registration</li>
 * </ul>
 *
 * <h3>Public API routes (no session required)</h3>
 * <ul>
 *   <li>{@code GET  /api/event-register/{eventId}}               → event details</li>
 *   <li>{@code GET  /api/event-register/{eventId}/registrations} → list registrations</li>
 *   <li>{@code POST /api/event-register/{eventId}}               → submit registration</li>
 * </ul>
 */
@Controller
public class ChurchEventController {

    private final ChurchEventService          eventService;
    private final ChurchLogoRepository        logoRepo;
    private final ChurchRegistrationRepository churchRegRepo;
    private final PublicSendLimiter           sendLimiter;

    public ChurchEventController(ChurchEventService eventService,
                                 ChurchLogoRepository logoRepo,
                                 ChurchRegistrationRepository churchRegRepo,
                                 PublicSendLimiter sendLimiter) {
        this.eventService  = eventService;
        this.logoRepo      = logoRepo;
        this.churchRegRepo = churchRegRepo;
        this.sendLimiter   = sendLimiter;
    }

    /**
     * Every public RSVP (new or updated) e-mails a confirmation to whatever address the
     * body carries, so the two endpoints are bounded per network origin, per contact and
     * per event link before anything is saved or sent (security audit P2). The event
     * link stands in for the tenant: one token is one event of one church.
     */
    private ResponseEntity<Map<String, Object>> rsvpLimited(String token, EventRegistrationBO bo,
                                                            HttpServletRequest request) {
        String email = bo != null && bo.getEmail() != null && !bo.getEmail().isBlank() ? bo.getEmail() : null;
        String phone = bo != null && bo.getPhone() != null && !bo.getPhone().isBlank() ? bo.getPhone() : null;
        String limited = sendLimiter.check(PublicSendLimiter.EVENT_REGISTRATION, request,
                                           email != null ? email : phone, token);
        if (limited == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", limited);
        return ResponseEntity.status(429).body(m);
    }

    // ── Page routes ───────────────────────────────────────────────────────

    @GetMapping("/event")
    public String eventPage(HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrUser(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(request, "general.events.edit");
        if (deny != null) return deny;
        return "forward:/event.html";
    }

    @GetMapping("/event-volunteers")
    public String eventVolunteersPage(HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrUser(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(request, "general.events.emailsms");
        if (deny != null) return deny;
        return "forward:/event-volunteers.html";
    }

    // ── Authenticated API ─────────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/events")
    public ResponseEntity<List<Map<String, Object>>> getAll(HttpServletRequest request) {
        String appClientId = SessionUtil.getAppClientId(request);
        return ResponseEntity.ok(eventService.getAll(appClientId));
    }

    @ResponseBody
    @GetMapping("/api/events/{id}")
    public ResponseEntity<Map<String, Object>> getById(@PathVariable Integer id,
                                                       HttpServletRequest request) {
        // Read by several pages (/events, /event-details, /event-checkin-admin) whose
        // common guard is requireAuth; the tenant scope below is the real fence.
        String appClientId = authedTenant(request);
        if (appClientId == null) return unauthorized();
        try {
            return ResponseEntity.ok(eventService.getById(id, appClientId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @ResponseBody
    @PostMapping("/api/events")
    public ResponseEntity<Map<String, Object>> create(@RequestBody ChurchEventBO bo,
                                                      HttpServletRequest request) {
        // Same pair as the /event page: requireAdminOrUser + general.events.edit.
        String deny = RoleGuard.requireAdminOrUser(request);
        if (deny == null) deny = RoleGuard.requirePermission(request, "general.events.edit");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return unauthorized();
        String username    = SessionUtil.getUsername(request);
        try {
            ChurchEvent ev = eventService.create(bo, appClientId, username);
            return ResponseEntity.ok(encryptedEventResponse(ev));
        } catch (ChurchEventService.DuplicateEventCodeException e) {
            return badField("eventCode", e.getMessage());
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @ResponseBody
    @PutMapping("/api/events/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Integer id,
                                                      @RequestBody ChurchEventBO bo,
                                                      HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrUser(request);
        if (deny == null) deny = RoleGuard.requirePermission(request, "general.events.edit");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return unauthorized();
        String username = SessionUtil.getUsername(request);
        try {
            ChurchEvent ev = eventService.update(id, bo, username, appClientId);
            return ResponseEntity.ok(encryptedEventResponse(ev));
        } catch (ChurchEventService.DuplicateEventCodeException e) {
            return badField("eventCode", e.getMessage());
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @ResponseBody
    @DeleteMapping("/api/events/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Integer id,
                                                      HttpServletRequest request) {
        // Deleted from both /event (AdminOrUser) and /events (requireAuth: church,
        // accountant and member sessions included), so requireAuth is the common guard.
        String deny = RoleGuard.requireAuth(request);
        if (deny == null) deny = RoleGuard.requirePermission(request, "general.events.delete");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return unauthorized();
        try {
            eventService.delete(id, appClientId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @ResponseBody
    @GetMapping("/api/events/{id}/registrations")
    public ResponseEntity<List<Map<String, Object>>> getRegistrations(@PathVariable Integer id,
                                                                      HttpServletRequest request) {
        String appClientId = authedTenant(request);
        if (appClientId == null) return ResponseEntity.status(401).build();
        try {
            return ResponseEntity.ok(eventService.getRegistrations(id, appClientId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @ResponseBody
    @DeleteMapping("/api/event-registrations/{id}")
    public ResponseEntity<Map<String, Object>> deleteRegistration(@PathVariable Integer id, HttpServletRequest request) {
        if (RoleGuard.requireAdminOrUser(request) != null
                || RoleGuard.requirePermission(request, "general.events.edit") != null)
            return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return unauthorized();
        try {
            eventService.deleteRegistration(id, appClientId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    /**
     * Admin check-in: mark a pre-registered attendee as checked in by registration ID.
     * <p>Request: {@code POST /api/event-registrations/{id}/checkin}
     */
    @ResponseBody
    @PostMapping("/api/event-registrations/{id}/checkin")
    public ResponseEntity<Map<String, Object>> adminCheckIn(@PathVariable Integer id, HttpServletRequest request) {
        if (RoleGuard.requireAdminOrUser(request) != null
                || RoleGuard.requirePermission(request, "general.events.edit") != null)
            return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return unauthorized();
        try {
            EventRegistration reg = eventService.checkInById(id, appClientId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("success",     true);
            m.put("checkedIn",   reg.isCheckedIn());
            m.put("checkedInAt", reg.getCheckedInAt());
            return ResponseEntity.ok(m);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    /**
     * Admin undo check-in: reset checked-in status back to false.
     * <p>Request: {@code DELETE /api/event-registrations/{id}/checkin}
     */
    @ResponseBody
    @DeleteMapping("/api/event-registrations/{id}/checkin")
    public ResponseEntity<Map<String, Object>> adminUndoCheckIn(@PathVariable Integer id, HttpServletRequest request) {
        if (RoleGuard.requireAdminOrUser(request) != null
                || RoleGuard.requirePermission(request, "general.events.edit") != null)
            return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return unauthorized();
        try {
            EventRegistration reg = eventService.uncheckInById(id, appClientId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("success",   true);
            m.put("checkedIn", reg.isCheckedIn());
            return ResponseEntity.ok(m);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    /**
     * Admin walk-in registration + immediate check-in.
     * <p>Request: {@code POST /api/events/{id}/walkin}
     */
    @ResponseBody
    @PostMapping("/api/events/{id}/walkin")
    public ResponseEntity<Map<String, Object>> walkIn(@PathVariable Integer id,
                                                      @RequestBody EventRegistrationBO bo,
                                                      HttpServletRequest request) {
        if (RoleGuard.requireAdminOrUser(request) != null
                || RoleGuard.requirePermission(request, "general.events.edit") != null)
            return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return unauthorized();
        try {
            EventRegistration reg = eventService.adminWalkIn(id, bo, appClientId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("success",          true);
            m.put("id",               reg.getId());
            m.put("registrationCode", reg.getRegistrationCode());
            m.put("checkedIn",        reg.isCheckedIn());
            return ResponseEntity.ok(m);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    /**
     * Check-in summary counts for an event.
     * <p>Request: {@code GET /api/events/{id}/checkin-summary}
     */
    @ResponseBody
    @GetMapping("/api/events/{id}/checkin-summary")
    public ResponseEntity<Map<String, Object>> checkinSummary(@PathVariable Integer id,
                                                              HttpServletRequest request) {
        String appClientId = authedTenant(request);
        if (appClientId == null) return unauthorized();
        try {
            return ResponseEntity.ok(eventService.getCheckinSummary(id, appClientId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── Public API (no session required, uses AES-encrypted event token) ──

    @ResponseBody
    @GetMapping("/api/event-register/{token}")
    public ResponseEntity<Map<String, Object>> getPublicDetail(@PathVariable String token) {
        try {
            Map<String, Object> detail = eventService.getPublicDetail(token);
            // Override churchName with the full name from ChurchRegistration
            String appClientId = (String) detail.get("appClientId");
            if (appClientId != null && !appClientId.isBlank()) {
                churchRegRepo.findByClientIdAndDeleteFlagFalse(appClientId)
                        .map(ChurchRegistration::getChurchName)
                        .filter(n -> n != null && !n.isBlank())
                        .ifPresent(name -> detail.put("churchName", name));
            }
            // The tenant id is server-side business; the page never reads it (audit P14).
            detail.remove("appClientId");
            return ResponseEntity.ok(detail);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @ResponseBody
    @GetMapping("/api/event-register/{token}/registrations")
    public ResponseEntity<List<Map<String, Object>>> getPublicRegistrations(@PathVariable String token) {
        try {
            return ResponseEntity.ok(eventService.getRegistrationsByToken(token));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Public auto-fill lookup: given the event token and an email and/or phone the
     * visitor typed, returns an existing registrant for the same client (from
     * event_registration, else family_member) so the form can prefill itself.
     */
    @ResponseBody
    @GetMapping("/api/event-register/{token}/lookup")
    public ResponseEntity<Map<String, Object>> lookupRegistrant(
            @PathVariable String token,
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "phone", required = false) String phone,
            HttpServletRequest request) {
        // An e-mail/phone probe against the event's registrants (audit N7/P5): the page
        // needs one or two per visit; bounded per network origin so it cannot be
        // driven through a contact list.
        String limited = sendLimiter.check(PublicSendLimiter.EVENT_LOOKUP, request, null, null);
        if (limited != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", limited);
            return ResponseEntity.status(429).body(m);
        }
        try {
            return ResponseEntity.ok(eventService.lookupRegistrant(token, email, phone));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @ResponseBody
    @PostMapping("/api/event-register/{token}")
    public ResponseEntity<Map<String, Object>> submitRegistration(
            @PathVariable String token,
            @RequestBody EventRegistrationBO bo,
            HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> limited = rsvpLimited(token, bo, request);
        if (limited != null) return limited;
        try {
            EventRegistration reg = eventService.registerByToken(token, bo);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",               reg.getId());
            m.put("success",          true);
            m.put("registrationCode", reg.getRegistrationCode());
            return ResponseEntity.ok(m);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    /**
     * Self check-in endpoint (public, no session required).
     *
     * <p>Called when the registrant taps "I'm Here" on the public event page.
     * Idempotent — checking in again is a no-op.
     *
     * <p>Request: {@code POST /api/event-checkin/{registrationCode}}
     */
    @ResponseBody
    @PostMapping("/api/event-checkin/{registrationCode}")
    public ResponseEntity<Map<String, Object>> selfCheckIn(@PathVariable String registrationCode) {
        try {
            EventRegistration reg = eventService.checkIn(registrationCode);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("success",    true);
            m.put("checkedIn",  reg.isCheckedIn());
            m.put("checkedInAt", reg.getCheckedInAt());
            m.put("firstName",  reg.getFirstName());
            m.put("lastName",   reg.getLastName());
            return ResponseEntity.ok(m);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    /**
     * Update an existing RSVP for the event identified by {@code token}.
     *
     * <p>The registrant is identified by the {@code email} query parameter, which
     * must match a previously submitted registration for this event.
     *
     * <p>Request: {@code PUT /api/event-register/{token}?email=jane@example.com}
     * with the updated fields in the JSON body.
     */
    @ResponseBody
    @PutMapping("/api/event-register/{token}")
    public ResponseEntity<Map<String, Object>> updateRegistration(
            @PathVariable String token,
            @RequestParam String email,
            @RequestBody EventRegistrationBO bo,
            HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> limited = rsvpLimited(token, bo, request);
        if (limited != null) return limited;
        try {
            EventRegistration reg = eventService.updateRegistration(token, email, bo);
            return ResponseEntity.ok(Map.of("id", reg.getId(), "success", true));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    /**
     * Returns the church logo image for the organization that owns the event
     * identified by {@code token}.  No session required — public endpoint used
     * by the event registration page to display the church branding.
     *
     * <p>Returns 404 when no logo has been uploaded for that organization.
     */
    @ResponseBody
    @GetMapping("/api/event-register/{token}/logo")
    public ResponseEntity<byte[]> getPublicLogo(@PathVariable String token) {
        try {
            Integer eventId = eventService.decryptToken(token);
            // Look up the event to get appClientId
            com.churchgeniuspro.hibernate.ChurchEvent ev =
                    eventService.getEventEntityById(eventId);
            if (ev == null || ev.getAppClientId() == null) {
                return ResponseEntity.notFound().build();
            }
            java.util.Optional<ChurchLogo> opt = logoRepo.findByClientId(ev.getAppClientId());
            if (opt.isEmpty() || opt.get().getLogoData() == null) {
                return ResponseEntity.notFound().build();
            }
            ChurchLogo logo = opt.get();
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(
                            logo.getContentType() != null ? logo.getContentType() : "image/png"))
                    .body(logo.getLogoData());
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Returns the event's uploaded image bytes for the event identified by
     * {@code token}. Public endpoint used as the Open Graph image for link
     * previews (SMS / WhatsApp) and anywhere an absolute image URL is needed.
     * The image is stored as a base64 data URI; this decodes it to raw bytes.
     * Returns 404 when the event has no image.
     */
    @ResponseBody
    @GetMapping("/api/event-register/{token}/image")
    public ResponseEntity<byte[]> getPublicEventImage(@PathVariable String token) {
        try {
            Integer eventId = eventService.decryptToken(token);
            ChurchEvent ev = eventService.getEventEntityById(eventId);
            if (ev == null || !ev.isImagePresent()) {
                return ResponseEntity.notFound().build();
            }
            String data = eventService.loadImageData(eventId);   // database audit P7: from church_event_image
            if (data == null || data.isBlank()) {
                return ResponseEntity.notFound().build();
            }
            String contentType = "image/png";
            String base64;
            if (data.startsWith("data:")) {
                int comma = data.indexOf(',');
                if (comma < 0) return ResponseEntity.notFound().build();
                String meta = data.substring(5, comma);       // e.g. "image/png;base64"
                int semi = meta.indexOf(';');
                contentType = semi > 0 ? meta.substring(0, semi) : meta;
                base64 = data.substring(comma + 1);
            } else {
                base64 = data;
            }
            byte[] bytes = java.util.Base64.getDecoder().decode(base64);
            return ResponseEntity.ok()
                    .cacheControl(org.springframework.http.CacheControl
                            .maxAge(java.time.Duration.ofHours(6)).cachePublic())
                    .contentType(MediaType.parseMediaType(
                            contentType.isBlank() ? "image/png" : contentType))
                    .body(bytes);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
    }

    /** Session tenant for read handlers guarded by {@code requireAuth}; {@code null} when not logged in. */
    private static String authedTenant(HttpServletRequest request) {
        if (RoleGuard.requireAuth(request) != null) return null;
        return SessionUtil.getAppClientId(request);
    }

    /**
     * A rejection the page can attribute to one input. {@code errorField} lets
     * the form highlight the offending box instead of showing a banner that
     * leaves the person hunting for what went wrong.
     */
    private ResponseEntity<Map<String, Object>> badField(String field, String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg, "errorField", field));
    }

    private Map<String, Object> encryptedEventResponse(ChurchEvent ev) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id",      ev.getId());
        m.put("success", true);
        return m;
    }
}
