package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.hibernate.SupportTicket;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.SupportTicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Ticketing: a church reports an issue to the ChurchGeniusPro support team.
 *
 * <p>Submitting saves the ticket first; the two emails (support inbox, submitter
 * confirmation) are sent afterwards and are best effort — a mail failure never
 * loses a ticket, and the outcome of each send is returned so the page can say so.
 * Both are platform mail, not congregation mail: they go through
 * {@link EmailService#sendComposed} with no tenant, so a Trial account's
 * congregation-mail block does not apply (the account is talking to support, not
 * to its members).
 *
 * <p>Tickets are read-only for the church after submission; only
 * {@link #setStatus} (Service Admin) changes one, and only its status.
 */
@Service
public class SupportTicketService {

    private static final Logger log = LoggerFactory.getLogger(SupportTicketService.class);

    /** Where every ticket is reported. */
    /** Default inbox; the live recipient is {@link PlatformSettingService#supportEmail()}. */
    public static final String SUPPORT_INBOX = PlatformSettingService.DEFAULT_SUPPORT_EMAIL;

    /** Configured Support Email (Service Admin → Platform Settings); default without it. */
    private PlatformSettingService platformSettings;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPlatformSettings(PlatformSettingService s) { this.platformSettings = s; }
    private String supportInbox() { return platformSettings != null ? platformSettings.supportEmail() : SUPPORT_INBOX; }
    public static final String SUPPORT_FROM_NAME = "ChurchGeniusPro Support";

    public static final List<String> URGENCIES = List.of("Low", "Medium", "High");
    public static final int SUBJECT_MAX = 200, NAME_MAX = 200, EMAIL_MAX = 320, DESCRIPTION_MAX = 10000;

    /** Reference alphabet: no 0/O or 1/I, so a number read over the phone is unambiguous. */
    private static final String REF_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RNG = new SecureRandom();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a");

    private final SupportTicketRepository repo;
    private final ChurchRegistrationRepository churchRepo;
    private final EmailService emailService;
    private final SubscriptionService subscriptionService;

    public SupportTicketService(SupportTicketRepository repo,
                                ChurchRegistrationRepository churchRepo,
                                EmailService emailService,
                                SubscriptionService subscriptionService) {
        this.repo = repo;
        this.churchRepo = churchRepo;
        this.emailService = emailService;
        this.subscriptionService = subscriptionService;
    }

    /** What the submit form sends. */
    public record NewTicket(String subject, String name, String email, String description, String urgency) {}

    /** What submitting produced: the saved ticket plus how each email went. */
    public record Submitted(SupportTicket ticket, boolean supportEmailSent, boolean confirmationEmailSent,
                            String emailProblem) {}

    // ── Validation ────────────────────────────────────────────────────────

    /** Returns the first validation problem, or {@code null} when the form is complete. */
    public static String validate(NewTicket t) {
        if (t == null) return "Please fill in the form.";
        if (blank(t.subject()))     return "Subject is required.";
        if (blank(t.name()))        return "Name is required.";
        if (blank(t.email()))       return "Email is required.";
        if (!t.email().trim().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")) return "Please enter a valid email address.";
        if (blank(t.description())) return "Error Description is required.";
        if (blank(t.urgency()))     return "Urgency is required.";
        if (!URGENCIES.contains(t.urgency().trim())) return "Urgency must be Low, Medium or High.";
        if (t.subject().trim().length() > SUBJECT_MAX)         return "Subject is too long (max " + SUBJECT_MAX + " characters).";
        if (t.name().trim().length() > NAME_MAX)               return "Name is too long (max " + NAME_MAX + " characters).";
        if (t.email().trim().length() > EMAIL_MAX)             return "Email is too long.";
        if (t.description().trim().length() > DESCRIPTION_MAX) return "Error Description is too long (max " + DESCRIPTION_MAX + " characters).";
        return null;
    }

    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }

    // ── Submit ────────────────────────────────────────────────────────────

    /**
     * Saves the ticket (in its own transaction) and then sends the two emails.
     *
     * @param username the signed-in login that submitted it (staff username, church
     *                 login or member portal login)
     * @param role     the session role
     */
    public Submitted submit(String clientId, String username, String role, NewTicket form) {
        String problem = validate(form);
        if (problem != null) throw new IllegalArgumentException(problem);
        if (clientId == null || clientId.isBlank()) throw new IllegalArgumentException("Not signed in.");

        SupportTicket t = save(clientId, username, role, form);

        boolean toSupport = false, toSubmitter = false;
        StringBuilder emailProblem = new StringBuilder();
        try {
            emailService.sendComposed(List.of(supportInbox()), null,
                    "[" + t.getReference() + "] " + t.getUrgency() + " — " + t.getSubject() + " — " + nz(t.getChurchName()),
                    supportEmailHtml(t), null, SUPPORT_FROM_NAME);
            toSupport = true;
        } catch (Exception e) {
            log.error("Ticket {}: support email failed — {}", t.getReference(), e.toString());
            emailProblem.append("The support team could not be emailed automatically; your ticket is saved and will still be seen. ");
        }
        try {
            emailService.sendComposed(List.of(t.getSubmitterEmail()), null,
                    "We received your support request " + t.getReference(),
                    confirmationEmailHtml(t), null, SUPPORT_FROM_NAME);
            toSubmitter = true;
        } catch (Exception e) {
            log.error("Ticket {}: confirmation email to {} failed — {}", t.getReference(), t.getSubmitterEmail(), e.toString());
            emailProblem.append("A confirmation email could not be sent to ").append(t.getSubmitterEmail()).append(".");
        }
        return new Submitted(t, toSupport, toSubmitter, emailProblem.length() == 0 ? null : emailProblem.toString().trim());
    }

    /** Persists the ticket (repository save is itself transactional; a self-call here would not be proxied). */
    SupportTicket save(String clientId, String username, String role, NewTicket form) {
        SupportTicket t = new SupportTicket();
        t.setReference(newReference());
        t.setClientId(clientId);
        t.setChurchName(churchRepo.findByClientIdAndDeleteFlagFalse(clientId)
                .map(ChurchRegistration::getChurchName).orElse(null));
        t.setSubject(form.subject().trim());
        t.setSubmitterName(form.name().trim());
        t.setSubmitterEmail(form.email().trim().toLowerCase());
        t.setDescription(form.description().trim());
        t.setUrgency(form.urgency().trim());
        t.setStatus(SupportTicket.STATUS_OPEN);
        t.setClientPackage(clientPackage(clientId));
        t.setSubmittedBy(username);
        t.setSubmittedRole(role);
        return repo.save(t);
    }

    /** {@code TKT-} + six characters from {@link #REF_ALPHABET}; retried on the rare collision. */
    public String newReference() {
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder("TKT-");
            for (int i = 0; i < 6; i++) sb.append(REF_ALPHABET.charAt(RNG.nextInt(REF_ALPHABET.length())));
            String ref = sb.toString();
            if (!repo.existsByReference(ref)) return ref;
        }
        throw new IllegalStateException("Could not allocate a ticket reference");
    }

    /** Trial / Free / Standard / Pro — the client's current package as the support team should see it. */
    public String clientPackage(String clientId) {
        if (TestDataService.isTrialTenant(clientId)) return "Trial";
        SubscriptionPlan plan = null;
        try { plan = subscriptionService.getPlan(clientId); } catch (Exception ignored) { /* fail open below */ }
        if (plan == null) return "Unknown";
        String code = plan.getPlanCode() == null ? "" : plan.getPlanCode().trim().toUpperCase();
        switch (code) {
            case "TRIAL":    return "Trial";
            case "FREE":     return "Free";
            case "STANDARD": return "Standard";
            case "PRO":      return "Pro";
            default:         return plan.getPlanName() != null && !plan.getPlanName().isBlank() ? plan.getPlanName() : code;
        }
    }

    // ── Read (church) ─────────────────────────────────────────────────────

    public List<Map<String, Object>> listForClient(String clientId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SupportTicket t : repo.findByClientIdOrderByCreatedAtDesc(clientId)) out.add(summary(t));
        return out;
    }

    public Optional<Map<String, Object>> detailForClient(String clientId, Long id) {
        return repo.findByIdAndClientId(id, clientId).map(this::detail);
    }

    // ── Service Admin ─────────────────────────────────────────────────────

    public List<Map<String, Object>> listAll(String status) {
        List<SupportTicket> rows = (status == null || status.isBlank() || "All".equalsIgnoreCase(status))
                ? repo.findAllByOrderByCreatedAtDesc()
                : repo.findByStatusOrderByCreatedAtDesc(normalizeStatus(status));
        List<Map<String, Object>> out = new ArrayList<>();
        for (SupportTicket t : rows) {
            Map<String, Object> m = summary(t);
            m.put("clientId", t.getClientId());
            m.put("churchName", t.getChurchName());
            m.put("clientPackage", t.getClientPackage());
            out.add(m);
        }
        return out;
    }

    public Optional<SupportTicket> find(Long id) { return repo.findById(id); }

    public Optional<Map<String, Object>> detailForAdmin(Long id) {
        return repo.findById(id).map(t -> {
            Map<String, Object> m = detail(t);
            m.put("clientId", t.getClientId());
            m.put("churchName", t.getChurchName());
            m.put("clientPackage", t.getClientPackage());
            m.put("submittedBy", t.getSubmittedBy());
            m.put("submittedRole", t.getSubmittedRole());
            return m;
        });
    }

    /** Open ⇄ Closed by a Service Admin. Returns the updated ticket. */
    @Transactional
    public SupportTicket setStatus(Long id, String status, String adminName) {
        String st = normalizeStatus(status);
        SupportTicket t = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + id));
        if (!st.equals(t.getStatus())) {
            t.setStatus(st);
            t.setStatusChangedAt(java.time.LocalDateTime.now());
            t.setStatusChangedBy(adminName);
            t = repo.save(t);
        }
        return t;
    }

    /** The default subject/body offered to the Service Admin after a status change. */
    public Map<String, String> defaultStatusEmail(SupportTicket t) {
        boolean closed = SupportTicket.STATUS_CLOSED.equals(t.getStatus());
        String subject = (closed ? "Your support request " : "Your support request ") + t.getReference()
                + (closed ? " has been resolved" : " has been reopened");
        String body = closed
                ? "Hello " + nz(t.getSubmitterName()) + ",\n\n"
                  + "Your support request " + t.getReference() + " (\"" + t.getSubject() + "\") has been marked as Closed.\n\n"
                  + "If the issue is not fully resolved, just reply to this email or submit a new ticket from the Ticketing page and we will take another look.\n\n"
                  + "Thank you,\nChurchGeniusPro Support"
                : "Hello " + nz(t.getSubmitterName()) + ",\n\n"
                  + "Your support request " + t.getReference() + " (\"" + t.getSubject() + "\") has been reopened and is being looked into again.\n\n"
                  + "We will contact you at this address with an update.\n\n"
                  + "Thank you,\nChurchGeniusPro Support";
        Map<String, String> m = new LinkedHashMap<>();
        m.put("to", t.getSubmitterEmail());
        m.put("subject", subject);
        m.put("body", body);
        return m;
    }

    /**
     * Sends the status-change email to the ticket's submitter. Throws when it
     * cannot be delivered so the Service Admin sees a real error.
     */
    public void sendStatusEmail(SupportTicket t, String subject, String plainBody) throws Exception {
        if (t.getSubmitterEmail() == null || t.getSubmitterEmail().isBlank()) {
            throw new IllegalArgumentException("This ticket has no submitter email address.");
        }
        String subj = (subject == null || subject.isBlank()) ? defaultStatusEmail(t).get("subject") : subject.trim();
        String body = (plainBody == null || plainBody.isBlank()) ? defaultStatusEmail(t).get("body") : plainBody;
        emailService.sendComposed(List.of(t.getSubmitterEmail()), null, subj, wrap(
                "<p style=\"white-space:pre-wrap;\">" + esc(body) + "</p>"
                + "<p style=\"color:#888;font-size:12px;\">Reference " + esc(t.getReference()) + " · Status: " + esc(t.getStatus()) + "</p>"),
                null, SUPPORT_FROM_NAME);
    }

    // ── Shapes ────────────────────────────────────────────────────────────

    private Map<String, Object> summary(SupportTicket t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("reference", t.getReference());
        m.put("subject", t.getSubject());
        m.put("name", t.getSubmitterName());
        m.put("email", t.getSubmitterEmail());
        m.put("shortDescription", shortDescription(t.getDescription()));
        m.put("urgency", t.getUrgency());
        m.put("status", t.getStatus());
        m.put("createdAt", t.getCreatedAt() != null ? t.getCreatedAt().toString() : null);
        return m;
    }

    private Map<String, Object> detail(SupportTicket t) {
        Map<String, Object> m = summary(t);
        m.put("description", t.getDescription());
        m.put("updatedAt", t.getUpdatedAt() != null ? t.getUpdatedAt().toString() : null);
        m.put("statusChangedAt", t.getStatusChangedAt() != null ? t.getStatusChangedAt().toString() : null);
        return m;
    }

    /** The first few words of the description, for list rows. */
    public static String shortDescription(String description) {
        if (description == null) return "";
        String one = description.trim().replaceAll("\\s+", " ");
        String[] words = one.split(" ");
        int n = Math.min(words.length, 8);
        String out = String.join(" ", Arrays.copyOf(words, n));
        if (out.length() > 70) out = out.substring(0, 70).trim();
        return (n < words.length || out.length() < one.length()) ? out + "…" : out;
    }

    public static String normalizeStatus(String s) {
        if (s == null) throw new IllegalArgumentException("Status is required.");
        String v = s.trim();
        if (v.equalsIgnoreCase(SupportTicket.STATUS_OPEN))   return SupportTicket.STATUS_OPEN;
        if (v.equalsIgnoreCase(SupportTicket.STATUS_CLOSED)) return SupportTicket.STATUS_CLOSED;
        throw new IllegalArgumentException("Status must be Open or Closed.");
    }

    // ── Email bodies ──────────────────────────────────────────────────────

    /** Everything the support team needs, as labelled rows. */
    public String supportEmailHtml(SupportTicket t) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h2 style=\"margin:0 0 12px;color:#673147;\">New support ticket ").append(esc(t.getReference())).append("</h2>");
        sb.append("<table cellpadding=\"6\" cellspacing=\"0\" style=\"border-collapse:collapse;font-size:14px;\">");
        row(sb, "Reference Number", t.getReference());
        row(sb, "Church Name", nz(t.getChurchName()));
        row(sb, "Subject", t.getSubject());
        row(sb, "Submitter Name", t.getSubmitterName());
        row(sb, "Submitter Email", t.getSubmitterEmail());
        row(sb, "Urgency", t.getUrgency());
        row(sb, "Current Client Package", nz(t.getClientPackage()));
        row(sb, "Logged-in Username", nz(t.getSubmittedBy()));
        row(sb, "Role", nz(t.getSubmittedRole()));
        row(sb, "Client ID", t.getClientId());
        row(sb, "Submitted", t.getCreatedAt() != null ? t.getCreatedAt().format(STAMP) : "");
        sb.append("</table>");
        sb.append("<h3 style=\"margin:18px 0 6px;color:#673147;\">Complete Error Description</h3>");
        sb.append("<div style=\"white-space:pre-wrap;font-size:14px;border:1px solid #e0d5db;border-radius:8px;padding:12px;background:#faf7f9;\">")
          .append(esc(t.getDescription())).append("</div>");
        return wrap(sb.toString());
    }

    String confirmationEmailHtml(SupportTicket t) {
        return wrap("<p>Hello " + esc(nz(t.getSubmitterName())) + ",</p>"
                + "<p>Thank you for contacting ChurchGeniusPro support. We have received your request and someone from our support team will contact you shortly.</p>"
                + "<p><strong>Reference number:</strong> " + esc(t.getReference()) + "<br>"
                + "<strong>Subject:</strong> " + esc(t.getSubject()) + "<br>"
                + "<strong>Urgency:</strong> " + esc(t.getUrgency()) + "</p>"
                + "<p>Please quote the reference number in any follow-up.</p>"
                + "<p>Thank you,<br>ChurchGeniusPro Support</p>");
    }

    private static void row(StringBuilder sb, String k, String v) {
        sb.append("<tr><td style=\"font-weight:700;color:#555;border-bottom:1px solid #eee;white-space:nowrap;\">").append(esc(k))
          .append("</td><td style=\"border-bottom:1px solid #eee;\">").append(esc(v)).append("</td></tr>");
    }

    private static String wrap(String inner) {
        return "<div style=\"font-family:-apple-system,Segoe UI,Roboto,sans-serif;color:#333;max-width:680px;\">" + inner + "</div>";
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
