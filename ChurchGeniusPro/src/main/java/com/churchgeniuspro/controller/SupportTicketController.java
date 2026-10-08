package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.SupportTicket;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.service.SupportTicketService;
import com.churchgeniuspro.service.TestDataService;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ticketing (church side) and the AI Assistant page route, plus the Service
 * Admin ticket management API.
 *
 * <p>Access to the two features is one rule, {@link RoleGuard#requireFeature},
 * applied to the page routes AND every API here — hiding the menu item is not the
 * protection. Staff have both on by default, member-portal users only when the
 * box is ticked on /viewusers, Church logins always, the Kids Portal never.
 *
 * <p>A submitted ticket is read-only for the church: there is deliberately no
 * PUT/DELETE mapping under {@code /api/tickets}; only a Service Admin changes a
 * ticket, and only its status.
 */
@Controller
public class SupportTicketController {

    private static final Logger log = LoggerFactory.getLogger(SupportTicketController.class);

    private final SupportTicketService tickets;
    private final AppUserRepository appUserRepo;
    private final FamilyMemberRepository familyMemberRepo;
    private final ChurchRegistrationRepository churchRepo;

    public SupportTicketController(SupportTicketService tickets,
                                   AppUserRepository appUserRepo,
                                   FamilyMemberRepository familyMemberRepo,
                                   ChurchRegistrationRepository churchRepo) {
        this.tickets = tickets;
        this.appUserRepo = appUserRepo;
        this.familyMemberRepo = familyMemberRepo;
        this.churchRepo = churchRepo;
    }

    // ── Pages ─────────────────────────────────────────────────────────────

    @GetMapping("/tickets")
    public String ticketsPage(HttpServletRequest request) {
        String deny = RoleGuard.requireFeature(request, RoleGuard.PERM_TICKETING);
        if (deny != null) return deny;
        return "forward:/tickets.html";
    }

    @GetMapping("/ai-assistant")
    public String aiAssistantPage(HttpServletRequest request) {
        String deny = RoleGuard.requireFeature(request, RoleGuard.PERM_AI_ASSISTANT);
        if (deny != null) return deny;
        return "forward:/ai-assistant.html";
    }

    // ── Church-side API ───────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> deny(HttpServletRequest request) {
        String deny = RoleGuard.requireFeature(request, RoleGuard.PERM_TICKETING);
        if (deny == null) {
            if (RoleGuard.clientId(request) == null) return ResponseEntity.status(401).body(Map.of("error", "Not signed in."));
            return null;
        }
        boolean loggedOut = deny.startsWith("redirect:");
        return ResponseEntity.status(loggedOut ? 401 : 403)
                .body(Map.of("error", loggedOut ? "Not signed in." : "You do not have access to Ticketing."));
    }

    /** What the page needs before it renders: trial notice, church name, prefilled name/email. */
    @ResponseBody
    @GetMapping("/api/tickets/context")
    public ResponseEntity<Map<String, Object>> context(HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> d = deny(request);
        if (d != null) return d;
        String clientId = RoleGuard.clientId(request);
        HttpSession s = request.getSession(false);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientId", clientId);
        m.put("trial", TestDataService.isTrialTenant(clientId) || "Trial".equals(tickets.clientPackage(clientId)));
        m.put("clientPackage", tickets.clientPackage(clientId));
        m.put("churchName", churchRepo.findByClientIdAndDeleteFlagFalse(clientId)
                .map(ChurchRegistration::getChurchName).orElse(str(s, "churchName")));
        m.put("defaultName", ((nz(str(s, "firstName")) + " " + nz(str(s, "lastName"))).trim()));
        m.put("defaultEmail", defaultEmail(s, clientId));
        m.put("urgencies", SupportTicketService.URGENCIES);
        return ResponseEntity.ok(m);
    }

    @ResponseBody
    @GetMapping("/api/tickets")
    public ResponseEntity<?> list(HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> d = deny(request);
        if (d != null) return d;
        return ResponseEntity.ok(tickets.listForClient(RoleGuard.clientId(request)));
    }

    @ResponseBody
    @GetMapping("/api/tickets/{id}")
    public ResponseEntity<?> detail(@PathVariable Long id, HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> d = deny(request);
        if (d != null) return d;
        return tickets.detailForClient(RoleGuard.clientId(request), id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "Ticket not found.")));
    }

    @ResponseBody
    @PostMapping("/api/tickets")
    public ResponseEntity<?> submit(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> d = deny(request);
        if (d != null) return d;
        HttpSession s = request.getSession(false);
        String clientId = RoleGuard.clientId(request);
        SupportTicketService.NewTicket form = new SupportTicketService.NewTicket(
                str(body, "subject"), str(body, "name"), str(body, "email"), str(body, "description"), str(body, "urgency"));
        try {
            SupportTicketService.Submitted r = tickets.submit(clientId, submitter(s), str(s, "role"), form);
            SupportTicket t = r.ticket();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("id", t.getId());
            out.put("reference", t.getReference());
            out.put("status", t.getStatus());
            out.put("message", "Your support request has been received successfully. Your reference number is "
                    + t.getReference() + ". Our support team will contact you shortly.");
            out.put("supportEmailSent", r.supportEmailSent());
            out.put("confirmationEmailSent", r.confirmationEmailSent());
            if (r.emailProblem() != null) out.put("emailProblem", r.emailProblem());
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Service Admin API ─────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> requireServiceAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("serviceAdminId") == null) {
            return ResponseEntity.status(401).body(Map.of("status", "error", "message", "Service admin login required."));
        }
        return null;
    }

    @ResponseBody
    @GetMapping("/api/serviceadmin/tickets")
    public ResponseEntity<?> adminList(@RequestParam(required = false) String status, HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> d = requireServiceAdmin(request);
        if (d != null) return d;
        try {
            return ResponseEntity.ok(Map.of("status", "success", "tickets", tickets.listAll(status)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    @ResponseBody
    @GetMapping("/api/serviceadmin/tickets/{id}")
    public ResponseEntity<?> adminDetail(@PathVariable Long id, HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> d = requireServiceAdmin(request);
        if (d != null) return d;
        return tickets.detailForAdmin(id)
                .<ResponseEntity<?>>map(t -> ResponseEntity.ok(Map.of("status", "success", "ticket", t)))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("status", "error", "message", "Ticket not found.")));
    }

    /** The default email the admin can send for a given target status (shown before the change is made). */
    @ResponseBody
    @GetMapping("/api/serviceadmin/tickets/{id}/email-draft")
    public ResponseEntity<?> adminEmailDraft(@PathVariable Long id, @RequestParam String status, HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> d = requireServiceAdmin(request);
        if (d != null) return d;
        try {
            SupportTicket t = tickets.detailForAdmin(id).isPresent()
                    ? ticketEntity(id) : null;
            if (t == null) return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Ticket not found."));
            SupportTicket preview = new SupportTicket();
            preview.setReference(t.getReference()); preview.setSubject(t.getSubject());
            preview.setSubmitterName(t.getSubmitterName()); preview.setSubmitterEmail(t.getSubmitterEmail());
            preview.setStatus(SupportTicketService.normalizeStatus(status));
            return ResponseEntity.ok(Map.of("status", "success", "draft", tickets.defaultStatusEmail(preview)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * Open ⇄ Closed. Body: {@code {status, sendEmail (default true), subject?, body?}}.
     * The status change is saved first; a failed email is reported, not rolled back.
     */
    @ResponseBody
    @PostMapping("/api/serviceadmin/tickets/{id}/status")
    public ResponseEntity<?> adminSetStatus(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body,
                                            HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> d = requireServiceAdmin(request);
        if (d != null) return d;
        HttpSession s = request.getSession(false);
        Object who = s.getAttribute("serviceAdminUsername");
        String admin = who != null ? who.toString() : ("serviceAdminId:" + s.getAttribute("serviceAdminId"));
        try {
            SupportTicket t = tickets.setStatus(id, str(body, "status"), admin);
            Object se = body == null ? null : body.get("sendEmail");
            boolean sendEmail = se == null || Boolean.TRUE.equals(se) || "true".equalsIgnoreCase(String.valueOf(se));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "success");
            out.put("ticket", tickets.detailForAdmin(id).orElse(null));
            out.put("emailSent", false);
            if (sendEmail) {
                try {
                    tickets.sendStatusEmail(t, str(body, "subject"), str(body, "body"));
                    out.put("emailSent", true);
                    out.put("emailTo", t.getSubmitterEmail());
                } catch (Exception e) {
                    log.warn("Ticket {}: status email failed — {}", t.getReference(), e.toString());
                    out.put("emailError", "Status saved, but the email could not be sent: " + e.getMessage());
                }
            }
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private SupportTicket ticketEntity(Long id) { return tickets.find(id).orElse(null); }

    /** The login that submitted: staff username, church username, or the member's name. */
    private String submitter(HttpSession s) {
        String u = str(s, "username");
        if (u != null && !u.isBlank()) return u;
        Object memberId = s == null ? null : s.getAttribute("memberId");
        return memberId != null ? "member-portal:" + memberId : null;
    }

    private String defaultEmail(HttpSession s, String clientId) {
        if (s == null) return "";
        try {
            Object appUserId = s.getAttribute("appUserId");
            if (appUserId instanceof Integer id) {
                AppUser u = appUserRepo.findByIdAndClientId(id, clientId).orElse(null);
                if (u != null && u.getEmail() != null) return u.getEmail();
            }
            Object memberId = s.getAttribute("memberId");
            if (memberId instanceof Integer mid) {
                FamilyMember fm = familyMemberRepo.findByIdAndTenant(mid, clientId).orElse(null);
                if (fm != null && fm.getEmail() != null) return fm.getEmail();
            }
            if (Boolean.TRUE.equals(s.getAttribute("church")) || "true".equalsIgnoreCase(String.valueOf(s.getAttribute("church")))) {
                return churchRepo.findByClientIdAndDeleteFlagFalse(clientId).map(ChurchRegistration::getEmail).orElse("");
            }
        } catch (Exception e) {
            log.debug("ticket context: default email lookup failed — {}", e.toString());
        }
        return "";
    }

    private static String str(Map<String, Object> b, String k) {
        if (b == null) return null;
        Object v = b.get(k);
        return v == null ? null : v.toString();
    }
    private static String str(HttpSession s, String k) {
        if (s == null) return null;
        Object v = s.getAttribute(k);
        return v == null ? null : v.toString();
    }
    private static String nz(String s) { return s == null ? "" : s; }
}
