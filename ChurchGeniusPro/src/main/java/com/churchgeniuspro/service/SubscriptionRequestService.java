package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.hibernate.SubscriptionRequest;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.SubscriptionPlanRepository;
import com.churchgeniuspro.repository.SubscriptionRequestRepository;
import com.churchgeniuspro.util.AppClock;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * "Request for Subscription" ({@code /subscriptionReq}): a church on the Trial plan
 * (or whose subscription has ended) asks to move to a plan.
 *
 * <ul>
 *   <li><b>Who may submit.</b> The holder of the church's request link (sent in the
 *       trial reminder emails), or a signed-in church owner / admin of that church.
 *       Either way the church is known before the form is shown, so its name comes
 *       from the account, never from the form.</li>
 *   <li><b>Registered Email</b> must match an email on the account: the client's
 *       registered email, the church registration email, or an active church user.</li>
 *   <li><b>One open request per church</b> (NEW / IN_PROGRESS) — service check plus
 *       the V9 partial unique index for simultaneous submits.</li>
 *   <li>The request is saved, then the configured Support Email is notified. A mail
 *       failure never loses the request; it is listed for the Service Admin.</li>
 *   <li>A sample-data trial ({@code TRIAL-}) cannot be converted, so its request is
 *       accepted and flagged "Register as New Client".</li>
 * </ul>
 */
@Service
public class SubscriptionRequestService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionRequestService.class);

    /** The request link stays valid this many days after the trial (or subscription) end date. */
    public static final int LINK_VALID_DAYS_AFTER_END = 60;

    static final List<String> OPEN = List.of(SubscriptionRequest.NEW, SubscriptionRequest.IN_PROGRESS);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a");
    private static final DateTimeFormatter DAY   = DateTimeFormatter.ofPattern("MMMM d, yyyy");

    private final SubscriptionRequestRepository repo;
    private final ServiceClientRepository clients;
    private final SubscriptionPlanRepository plans;
    private final ChurchRegistrationRepository churches;
    private final AppUserRepository users;
    private final EmailService email;
    private final PlatformSettingService settings;

    @Value("${app.base-url:}")
    private String baseUrl;

    public SubscriptionRequestService(SubscriptionRequestRepository repo, ServiceClientRepository clients,
                                      SubscriptionPlanRepository plans, ChurchRegistrationRepository churches,
                                      AppUserRepository users, EmailService email, PlatformSettingService settings) {
        this.repo = repo;
        this.clients = clients;
        this.plans = plans;
        this.churches = churches;
        this.users = users;
        this.email = email;
        this.settings = settings;
    }

    /** Test seam. */
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    // ── The request link ──────────────────────────────────────────────────

    /**
     * The church's "Request a subscription" link, issuing (or renewing an expired)
     * token as needed. The same link is reused by every reminder until it expires.
     */
    public String linkFor(ServiceClient sc) {
        LocalDate today = AppClock.today();
        if (sc.getSubscriptionRequestToken() == null || sc.getSubscriptionRequestTokenExpires() == null
                || sc.getSubscriptionRequestTokenExpires().isBefore(today)) {
            LocalDate base = (sc.getEndDate() != null && sc.getEndDate().isAfter(today)) ? sc.getEndDate() : today;
            sc.setSubscriptionRequestToken(PublicLinkResolver.newToken());
            sc.setSubscriptionRequestTokenExpires(base.plusDays(LINK_VALID_DAYS_AFTER_END));
            clients.save(sc);
        }
        return base() + "/subscriptionReq.html?t=" + sc.getSubscriptionRequestToken();
    }

    /** The church a request link belongs to, if the link is current. */
    public Optional<ServiceClient> clientForToken(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return clients.findBySubscriptionRequestTokenAndDeleteFlagFalse(token.trim())
                .filter(sc -> sc.getSubscriptionRequestTokenExpires() != null
                        && !sc.getSubscriptionRequestTokenExpires().isBefore(AppClock.today()));
    }

    /** The church of a signed-in church owner or admin (not members or regular users). */
    public Optional<ServiceClient> clientForSession(HttpServletRequest req) {
        if (req == null || req.getSession(false) == null || !SessionUtil.isAdminLike(req)) return Optional.empty();
        String cid = RoleGuard.clientId(req);
        if (cid == null || cid.isBlank()) return Optional.empty();
        return clients.findByClientId(cid).filter(sc -> !Boolean.TRUE.equals(sc.getDeleteFlag()));
    }

    // ── The page ──────────────────────────────────────────────────────────

    /** What the page shows: the church, its current plan, and the plans it may request. */
    public Map<String, Object> formInfo(ServiceClient sc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("churchName", sc.getChurchName());
        String current = SubscriptionService.toPlanCode(sc.getSubscriptionType());
        m.put("currentPlan", current);
        m.put("endDate", sc.getEndDate() != null ? sc.getEndDate().toString() : null);
        m.put("registerAsNewClient", isSampleTrial(sc));
        List<Map<String, Object>> list = new ArrayList<>();
        for (SubscriptionPlan p : requestablePlans()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("planCode", p.getPlanCode());
            pm.put("planName", p.getPlanName());
            pm.put("monthlyPrice", p.getMonthlyPrice() != null ? p.getMonthlyPrice() : BigDecimal.ZERO);
            pm.put("yearlyPrice", p.getYearlyPrice());
            list.add(pm);
        }
        m.put("plans", list);
        repo.findByClientIdAndStatusInOrderByCreatedAtDesc(sc.getClientId(), OPEN).stream().findFirst()
                .ifPresent(r -> m.put("openRequestSince", r.getCreatedAt().toLocalDate().toString()));
        return m;
    }

    /** Active plans a church can ask for, in Subscription Plans order (never TRIAL). */
    List<SubscriptionPlan> requestablePlans() {
        List<SubscriptionPlan> out = new ArrayList<>();
        for (SubscriptionPlan p : plans.findAllByOrderBySortOrderAscIdAsc()) {
            if (p.isActive() && !TrialPolicy.TRIAL_PLAN_CODE.equalsIgnoreCase(p.getPlanCode())) out.add(p);
        }
        return out;
    }

    static boolean isSampleTrial(ServiceClient sc) {
        return sc.getClientId() != null && sc.getClientId().startsWith(TestDataService.TRIAL_CLIENT_PREFIX);
    }

    // ── Submit ────────────────────────────────────────────────────────────

    public record Form(String firstName, String lastName, String registeredEmail, String phone,
                       String planCode, String billingFrequency, String note) {}

    /** A second open request for the same church. */
    public static class DuplicateRequestException extends RuntimeException {
        public DuplicateRequestException(String message) { super(message); }
    }

    /** Field rules; null when acceptable. Messages are for the requester. */
    public static String validate(Form f) {
        if (f == null) return "Please complete the form.";
        if (blank(f.firstName()))  return "First Name is required.";
        if (blank(f.lastName()))   return "Last Name is required.";
        if (blank(f.registeredEmail())) return "Registered Email is required.";
        if (!f.registeredEmail().trim().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) return "Please enter a valid email address.";
        if (blank(f.planCode()))   return "Please choose a plan.";
        if (f.firstName().trim().length() > 100 || f.lastName().trim().length() > 100) return "Names must be 100 characters or fewer.";
        if (!blank(f.phone()) && f.phone().trim().length() > 40) return "Phone must be 40 characters or fewer.";
        if (!blank(f.note()) && f.note().trim().length() > 1000) return "Note must be 1000 characters or fewer.";
        return null;
    }

    public SubscriptionRequest submit(ServiceClient sc, Form f, String ip) {
        String invalid = validate(f);
        if (invalid != null) throw new IllegalArgumentException(invalid);

        SubscriptionPlan plan = requestablePlans().stream()
                .filter(p -> p.getPlanCode().equalsIgnoreCase(f.planCode().trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Please choose one of the plans listed."));
        String freq = "YEARLY".equalsIgnoreCase(blank(f.billingFrequency()) ? "" : f.billingFrequency().trim())
                ? SubscriptionLifecycleService.YEARLY : SubscriptionLifecycleService.MONTHLY;
        if (SubscriptionLifecycleService.YEARLY.equals(freq) && plan.getYearlyPrice() == null) {
            throw new IllegalArgumentException("The " + plan.getPlanName() + " plan is available monthly only.");
        }
        String regEmail = f.registeredEmail().trim().toLowerCase();
        if (!emailsOnAccount(sc).contains(regEmail)) {
            throw new IllegalArgumentException("This email address does not match the email registered for "
                    + (sc.getChurchName() != null ? sc.getChurchName() : "this church")
                    + ". Please use the email address the account was registered with.");
        }
        refuseIfOpen(sc.getClientId());

        SubscriptionRequest r = new SubscriptionRequest();
        r.setClientId(sc.getClientId());
        r.setChurchName(sc.getChurchName());
        r.setFirstName(f.firstName().trim());
        r.setLastName(f.lastName().trim());
        r.setRegisteredEmail(regEmail);
        r.setPhone(blank(f.phone()) ? null : f.phone().trim());
        r.setPlanCode(plan.getPlanCode());
        r.setPlanName(plan.getPlanName());
        r.setBillingFrequency(freq);
        r.setNote(blank(f.note()) ? null : f.note().trim());
        r.setCurrentPlan(SubscriptionService.toPlanCode(sc.getSubscriptionType()));
        r.setRegisterAsNewClient(isSampleTrial(sc));
        r.setStatus(SubscriptionRequest.NEW);
        r.setRequestIp(ip);
        r.setCreatedAt(LocalDateTime.now());
        r.setSupportEmailSent(false);
        try {
            repo.save(r);
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            refuseIfOpen(sc.getClientId());
            throw new DuplicateRequestException(openMessage(null));
        }

        String to = settings.supportEmail();
        try {
            email.sendComposed(List.of(to), null,
                    "Subscription request — " + (r.getChurchName() != null ? r.getChurchName() : r.getClientId())
                            + " → " + r.getPlanName(),
                    supportEmailHtml(r, sc), null, "ChurchGeniusPro Subscription Requests");
            r.setSupportEmailSent(true);
            repo.save(r);
        } catch (Exception e) {
            log.error("Subscription request {}: Support Email notification to {} failed — {}", r.getId(), to, e.toString());
        }
        log.info("Subscription request {} saved for {} ({}): {} {} newClient={}", r.getId(), r.getClientId(),
                r.getChurchName(), r.getPlanCode(), r.getBillingFrequency(), r.getRegisterAsNewClient());
        return r;
    }

    private void refuseIfOpen(String clientId) {
        List<SubscriptionRequest> open = repo.findByClientIdAndStatusInOrderByCreatedAtDesc(clientId, OPEN);
        if (!open.isEmpty()) throw new DuplicateRequestException(openMessage(open.get(0)));
    }

    private static String openMessage(SubscriptionRequest open) {
        return "A subscription request for this church is already open"
             + (open != null && open.getCreatedAt() != null ? " (submitted " + open.getCreatedAt().format(DAY) + ")" : "")
             + ". We will contact you shortly.";
    }

    /** Every email the account is registered under, lower-cased. */
    Set<String> emailsOnAccount(ServiceClient sc) {
        Set<String> out = new HashSet<>();
        if (!blank(sc.getEmail())) out.add(sc.getEmail().trim().toLowerCase());
        try {
            churches.findByClientIdAndDeleteFlagFalse(sc.getClientId())
                    .ifPresent(c -> { if (!blank(c.getEmail())) out.add(c.getEmail().trim().toLowerCase()); });
        } catch (Exception ignored) { }
        try {
            for (AppUser u : users.findByClientIdAndDeleteFlagFalseOrderByLastNameAscFirstNameAsc(sc.getClientId())) {
                if (u.isEnabled() && !blank(u.getEmail())) out.add(u.getEmail().trim().toLowerCase());
            }
        } catch (Exception ignored) { }
        return out;
    }

    // ── Service Admin ─────────────────────────────────────────────────────

    public List<Map<String, Object>> listForAdmin() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SubscriptionRequest r : repo.findAllByOrderByCreatedAtDesc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("clientId", r.getClientId());
            m.put("churchName", r.getChurchName());
            m.put("firstName", r.getFirstName());
            m.put("lastName", r.getLastName());
            m.put("registeredEmail", r.getRegisteredEmail());
            m.put("phone", r.getPhone());
            m.put("planCode", r.getPlanCode());
            m.put("planName", r.getPlanName());
            m.put("billingFrequency", r.getBillingFrequency());
            m.put("note", r.getNote());
            m.put("currentPlan", r.getCurrentPlan());
            m.put("registerAsNewClient", Boolean.TRUE.equals(r.getRegisterAsNewClient()));
            m.put("status", r.getStatus());
            m.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
            m.put("supportEmailSent", r.getSupportEmailSent());
            m.put("decidedAt", r.getDecidedAt() != null ? r.getDecidedAt().toString() : null);
            m.put("decidedBy", r.getDecidedBy());
            m.put("declineReason", r.getDeclineReason());
            out.add(m);
        }
        return out;
    }

    /** Refuses unless the request exists, is open, and belongs to {@code clientId}. */
    public void requireOpenFor(Long id, String clientId) {
        SubscriptionRequest r = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Subscription request not found."));
        if (!Objects.equals(clientId, r.getClientId())) {
            throw new IllegalArgumentException("That subscription request belongs to a different church.");
        }
        if (!OPEN.contains(r.getStatus())) {
            throw new IllegalArgumentException("That subscription request is not open any more. Refresh the list.");
        }
    }

    /** NEW → IN_PROGRESS. */
    public void markInProgress(Long id, String actor) {
        if (repo.transition(id, List.of(SubscriptionRequest.NEW), SubscriptionRequest.IN_PROGRESS, actor, LocalDateTime.now()) != 1) {
            throw new IllegalArgumentException("Only a new request can be marked in progress. Refresh the list.");
        }
    }

    /** NEW / IN_PROGRESS → DECLINED. No email is sent to the church. */
    public void decline(Long id, String actor, String reason) {
        if (repo.transition(id, OPEN, SubscriptionRequest.DECLINED, actor, LocalDateTime.now()) != 1) {
            throw new IllegalArgumentException("This request is not open any more. Refresh the list.");
        }
        if (!blank(reason)) {
            repo.findById(id).ifPresent(r -> {
                String t = reason.trim();
                r.setDeclineReason(t.length() > 500 ? t.substring(0, 500) : t);
                repo.save(r);
            });
        }
    }

    /**
     * NEW / IN_PROGRESS → COMPLETED, after the Service Admin converted the church (or
     * registered it as a new client). The request must belong to {@code clientId}
     * when one is given.
     */
    public void complete(Long id, String clientId, String actor) {
        SubscriptionRequest r = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Subscription request not found."));
        if (clientId != null && !clientId.equals(r.getClientId())) {
            throw new IllegalArgumentException("That subscription request belongs to a different church.");
        }
        if (repo.transition(id, OPEN, SubscriptionRequest.COMPLETED, actor, LocalDateTime.now()) != 1) {
            throw new IllegalArgumentException("This request is not open any more. Refresh the list.");
        }
    }

    // ── Email ─────────────────────────────────────────────────────────────

    String supportEmailHtml(SubscriptionRequest r, ServiceClient sc) {
        String[][] rows = {
            {"Church Name", r.getChurchName()},
            {"Client ID", r.getClientId()},
            {"First Name", r.getFirstName()},
            {"Last Name", r.getLastName()},
            {"Registered Email", r.getRegisteredEmail()},
            {"Phone", or(r.getPhone())},
            {"Requested Plan", r.getPlanName() + " (" + r.getPlanCode() + ")"},
            {"Billing Frequency", SubscriptionLifecycleService.YEARLY.equals(r.getBillingFrequency()) ? "Yearly" : "Monthly"},
            {"Note", or(r.getNote())},
            {"Current Plan", or(r.getCurrentPlan())},
            {"Current End Date", sc.getEndDate() != null ? sc.getEndDate().format(DAY) : "—"},
            {"Request Date/Time", r.getCreatedAt() != null ? r.getCreatedAt().format(STAMP) : "—"},
            {"Handling", Boolean.TRUE.equals(r.getRegisterAsNewClient())
                    ? "Register as New Client — this is a sample-data trial and cannot be converted"
                    : "Convert this account (same username, password and data)"},
        };
        StringBuilder sb = new StringBuilder("<div style=\"font-family:Segoe UI,Arial,sans-serif;font-size:14px;color:#2b2b2b;\">"
                + "<p>A church has requested a subscription. Review it under "
                + "<strong>Service Admin → Subscription Requests</strong>.</p>"
                + "<table cellpadding=\"7\" style=\"border-collapse:collapse;\">");
        for (String[] row : rows) {
            sb.append("<tr><td style=\"border:1px solid #e3e3e8;background:#f7f7fa;font-weight:600;white-space:nowrap;vertical-align:top;\">")
              .append(esc(row[0])).append("</td><td style=\"border:1px solid #e3e3e8;white-space:pre-wrap;\">")
              .append(esc(row[1])).append("</td></tr>");
        }
        return sb.append("</table></div>").toString();
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private String base() { return baseUrl == null ? "" : baseUrl.replaceAll("/+$", ""); }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String or(String s) { return blank(s) ? "—" : s; }
    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
