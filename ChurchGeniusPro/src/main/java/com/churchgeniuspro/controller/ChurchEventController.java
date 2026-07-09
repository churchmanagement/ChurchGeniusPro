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

    public ChurchEventController(ChurchEventService eventService,
                                 ChurchLogoRepository logoRepo,
                                 ChurchRegistrationRepository churchRegRepo) {
        this.eventService  = eventService;
        this.logoRepo      = logoRepo;
        this.churchRegRepo = churchRegRepo;
    }

    // ── Page routes ───────────────────────────────────────────────────────

    @GetMapping("/event")
    public String eventPage(HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrUser(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePermission(request, "general.events.edit");
        if (deny != null) return deny;
        return "forward:/event.html";
    }

    @GetMapping("/event-volunteers")
    public String eventVolunteersPage(HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrUser(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePermission(request, "general.events.emailsms");
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
    public ResponseEntity<Map<String, Object>> getById(@PathVariable Integer id) {
        try {
            return ResponseEntity.ok(eventService.getById(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @ResponseBody
    @PostMapping("/api/events")
    public ResponseEntity<Map<String, Object>> create(@RequestBody ChurchEventBO bo,
                                                      HttpServletRequest request) {
        String deny = RoleGuard.requirePermission(request, "general.events.edit");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        String username    = SessionUtil.getUsername(request);
        try {
            ChurchEvent ev = eventService.create(bo, appClientId, username);
            return ResponseEntity.ok(encryptedEventResponse(ev));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @ResponseBody
    @PutMapping("/api/events/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Integer id,
                                                      @RequestBody ChurchEventBO bo,
                                                      HttpServletRequest request) {
        String deny = RoleGuard.requirePermission(request, "general.events.edit");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String username = SessionUtil.getUsername(request);
        try {
            ChurchEvent ev = eventService.update(id, bo, username);
            return ResponseEntity.ok(encryptedEventResponse(ev));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @ResponseBody
    @DeleteMapping("/api/events/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Integer id,
                                                      HttpServletRequest request) {
        String deny = RoleGuard.requirePermission(request, "general.events.delete");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        try {
            eventService.delete(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @ResponseBody
    @GetMapping("/api/events/{id}/registrations")
    public ResponseEntity<List<Map<String, Object>>> getRegistrations(@PathVariable Integer id) {
        return ResponseEntity.ok(eventService.getRegistrations(id));
    }

    @ResponseBody
    @DeleteMapping("/api/event-registrations/{id}")
    public ResponseEntity<Map<String, Object>> deleteRegistration(@PathVariable Integer id, HttpServletRequest request) {
        if (RoleGuard.requireAdminOrUser(request) != null
                || RoleGuard.requirePermission(request, "general.events.edit") != null)
            return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        try {
            eventService.deleteRegistration(id);
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
        try {
            EventRegistration reg = eventService.checkInById(id);
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
        try {
            EventRegistration reg = eventService.uncheckInById(id);
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
        try {
            EventRegistration reg = eventService.adminWalkIn(id, bo);
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
    public ResponseEntity<Map<String, Object>> checkinSummary(@PathVariable Integer id) {
        return ResponseEntity.ok(eventService.getCheckinSummary(id));
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
            @RequestParam(value = "phone", required = false) String phone) {
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
            @RequestBody EventRegistrationBO bo) {
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
            @RequestBody EventRegistrationBO bo) {
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
            if (ev == null || ev.getImageData() == null || ev.getImageData().isBlank()) {
                return ResponseEntity.notFound().build();
            }
            String data = ev.getImageData();
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

    private Map<String, Object> encryptedEventResponse(ChurchEvent ev) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id",      ev.getId());
        m.put("success", true);
        return m;
    }
}
