package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.model.ServiceClientBO;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.service.WhatsAppSenderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ServiceClientService {

    private static final Logger log = LoggerFactory.getLogger(ServiceClientService.class);

    private final ServiceClientRepository repository;
    private final EmailService             emailService;
    private final WhatsAppSenderService    whatsAppSender;
    private final LoginRepository          loginRepository;

    /**
     * Messaging is blocked for Trial clients, and that answer is cached per
     * clientId. Any change to a client's Subscription Type must drop the cached
     * answer, or an upgrade out of Trial would stay blocked for up to a minute
     * (and a downgrade into Trial would keep sending).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MessagingPolicy messagingPolicy;

    @Value("${app.base-url}")
    private String baseUrl;

    /**
     * The one subscription-update path for trial and demo tenants
     * ({@code TRIAL-} / {@code DEMO-}). {@code @Lazy} because TestDataService is a
     * large bean with its own dependency graph; resolving it on first use keeps this
     * service's construction unchanged and rules out a cycle.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private TestDataService testDataService;

    /** Test seam — supply the subscription updater without a Spring context. */
    void setTestDataService(TestDataService t) { this.testDataService = t; }


    /**
     * Configured trial length (Service Admin → Subscription Plans → Trial plan).
     * Setter-injected so the existing constructor — and every test built on it —
     * keeps working; without a policy the historical {@value TrialPolicy#FALLBACK_DAYS}
     * days apply.
     */
    private TrialPolicy trialPolicy;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTrialPolicy(TrialPolicy p) { this.trialPolicy = p; }
    private int defaultTrialDays() { return trialPolicy != null ? trialPolicy.trialDays() : TrialPolicy.FALLBACK_DAYS; }

    /**
     * Plan validation, client price / billing frequency, subscription history and
     * cache refresh. Setter-injected so existing tests that build this service by
     * hand keep working; without it those steps are skipped, exactly as before.
     */
    private SubscriptionLifecycleService lifecycle;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLifecycle(SubscriptionLifecycleService l) { this.lifecycle = l; }

    public ServiceClientService(ServiceClientRepository repository,
                                EmailService             emailService,
                                WhatsAppSenderService    whatsAppSender,
                                LoginRepository          loginRepository) {
        this.repository      = repository;
        this.emailService    = emailService;
        this.whatsAppSender  = whatsAppSender;
        this.loginRepository = loginRepository;
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public List<ServiceClient> getAll() {
        return repository.findAllByDeleteFlagFalseOrderByIdDesc();
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    /**
     * Registers a new client.
     *
     * <p>No SMS/email flag is written here: whether a client may send is derived
     * from its Subscription Type by {@link MessagingPolicy}, so registering with
     * Type = TRIAL blocks messaging from the first request, and upgrading later
     * lifts the block with no back-fill. A stored copy would be one more thing
     * that can fall out of step with the plan it is supposed to reflect.
     */
    public ServiceClient save(ServiceClientBO bo) {
        return save(bo, null);
    }

    /** @param actor the Service Admin making the change, for the subscription history */
    public ServiceClient save(ServiceClientBO bo, String actor) {
        ServiceClient entity = new ServiceClient();
        mapBoToEntity(bo, entity);
        if (lifecycle != null) {
            lifecycle.validatePlanChange(null, entity);
            applyPricing(entity, null, bo);
        }
        ServiceClient saved = repository.save(entity);
        // Generate a unique token as the Client ID, prefixed with "CHR"
        saved.setClientId("CHR" + UUID.randomUUID().toString());
        saved = repository.save(saved);
        if (lifecycle != null) lifecycle.recordAndRefresh(null, saved, actor, "CREATED");
        invalidatePolicy(saved);
        return saved;
    }

    public ServiceClient update(Integer id, ServiceClientBO bo) {
        return update(id, bo, null);
    }

    /** @param actor the Service Admin making the change, for the subscription history */
    /**
     * Raised by Edit Client when the start date would change and the admin has not
     * confirmed it. Nothing is saved; the page asks and resends with the confirmation.
     */
    public static class StartDateConfirmationRequired extends RuntimeException {
        public StartDateConfirmationRequired(String message) { super(message); }
    }

    /**
     * Edit Client rules that apply before anything is written:
     * <ul>
     *   <li>A regular client on the Trial plan cannot be moved to another plan here;
     *       Trial → paid goes only through Convert (which sets the new subscription's
     *       start date and therefore a fresh usage period). Internal trial/demo tenants
     *       follow their own rules in the shared update.</li>
     *   <li>A changed start date needs explicit confirmation: it moves the end date (as
     *       applicable) and re-anchors the 30-day usage period, which may reset email,
     *       SMS and online-giving usage. An unchanged start date never asks.</li>
     * </ul>
     */
    static void checkEditRules(ServiceClient entity, ServiceClientBO bo) {
        String current   = SubscriptionService.toPlanCode(entity.getSubscriptionType());
        String requested = SubscriptionService.toPlanCode(bo.getSubscriptionType());
        if (!TestDataService.isManagedTenant(entity.getClientId())
                && TrialPolicy.TRIAL_PLAN_CODE.equalsIgnoreCase(current)
                && requested != null && !TrialPolicy.TRIAL_PLAN_CODE.equalsIgnoreCase(requested)) {
            throw new IllegalArgumentException(TRIAL_EDIT_REFUSED);
        }
        if (bo.getStartDate() != null && !bo.getStartDate().isBlank() && entity.getStartDate() != null
                && !Boolean.TRUE.equals(bo.getConfirmStartDateChange())) {
            LocalDate newStart;
            try { newStart = LocalDate.parse(bo.getStartDate().trim()); }
            catch (java.time.format.DateTimeParseException e) { return; }   // the save reports the bad date
            if (!newStart.equals(entity.getStartDate())) {
                throw new StartDateConfirmationRequired("You are changing the subscription start date from "
                        + entity.getStartDate() + " to " + newStart + ". This will:\n"
                        + "• change the subscription end date where it is calculated from the start date;\n"
                        + "• move (re-anchor) the 30-day usage period to the new start date;\n"
                        + "• possibly reset this client's email, SMS and online-giving usage for the new period.\n"
                        + "Do you want to save this change?");
            }
        }
    }

    /** Shown when Edit Client is used to move a Trial-plan client to a paid plan. */
    public static final String TRIAL_EDIT_REFUSED =
            "This client is on the Trial plan. To move it to a paid plan, use the Convert action — "
          + "Convert starts the new subscription (start date, billing and a fresh usage period) "
          + "on the same account. Edit cannot change a Trial client's plan.";

    public ServiceClient update(Integer id, ServiceClientBO bo, String actor) {
        ServiceClient entity = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Client not found: " + id));
        checkEditRules(entity, bo);
        if (TestDataService.isManagedTenant(entity.getClientId()) && testDataService != null) {
            return updateManagedTenant(entity, bo, actor);
        }
        SubscriptionLifecycleService.Snapshot before = SubscriptionLifecycleService.snapshot(entity);
        mapBoToEntity(bo, entity);
        if (lifecycle != null) {
            lifecycle.validatePlanChange(before, entity);
            applyPricing(entity, before, bo);
        }
        ServiceClient saved = repository.save(entity);
        if (lifecycle != null) lifecycle.recordAndRefresh(before, saved, actor, "EDIT");
        invalidatePolicy(saved);
        return saved;
    }

    /** Billing frequency + price from the form, through the one pricing rule. */
    private void applyPricing(ServiceClient entity, SubscriptionLifecycleService.Snapshot before, ServiceClientBO bo) {
        lifecycle.applyPricing(entity, before, bo.getBillingFrequency(),
                SubscriptionLifecycleService.parsePrice(bo.getSubscriptionPrice()),
                bo.getPriceOverridden(), Boolean.TRUE.equals(bo.getResetPriceToPlan()));
    }

    /**
     * Registered Clients → Edit for a trial/demo tenant.
     *
     * <p>Contact details, payment status, note, status and Extra SMS are saved from the
     * form as for any client. The SUBSCRIPTION — plan and end date — is not written
     * here: it goes through {@link TestDataService#updateDemoSubscription}, the same
     * update Test/Demo Data → Edit/Extend uses, so there is exactly one way a trial's
     * subscription changes. That path validates the plan against Subscription Plans and
     * stores its canonical code, extends every login's access window with the new end
     * date, reactivates an extended tenant, and clears the plan and messaging caches.
     *
     * <p>Only what actually changed is passed on. Re-saving the form untouched changes
     * nothing, and a blank or unrecognised Subscription Type is treated as "unchanged"
     * rather than written — a blank value would otherwise resolve to no plan at all,
     * which the plan resolver treats as unrestricted.
     */
    private ServiceClient updateManagedTenant(ServiceClient entity, ServiceClientBO bo, String actor) {
        SubscriptionLifecycleService.Snapshot before = SubscriptionLifecycleService.snapshot(entity);
        String    clientId   = entity.getClientId();
        String    prevType   = entity.getSubscriptionType();
        LocalDate prevStart  = entity.getStartDate();
        LocalDate prevEnd    = entity.getEndDate();
        Integer   prevPeriod = entity.getActivePeriod();
        String    prevUnit   = entity.getActivePeriodUnit();
        String    prevStatus = entity.getStatus();

        // What the form is asking for, worked out before anything is overwritten.
        LocalDate start = (bo.getStartDate() != null && !bo.getStartDate().isBlank())
                ? LocalDate.parse(bo.getStartDate()) : prevStart;
        String unit = (bo.getActivePeriodUnit() != null && !bo.getActivePeriodUnit().isBlank())
                ? bo.getActivePeriodUnit() : prevUnit;          // blank ≠ MONTHS for a day-based trial
        LocalDate requestedEnd = (bo.getActivePeriod() != null && bo.getActivePeriod() > 0 && start != null)
                ? endDateFor(start, bo.getActivePeriod(), unit) : null;
        String requestedPlan = SubscriptionService.toPlanCode(bo.getSubscriptionType());
        // Refuse converting a sample-data trial before anything on the row is written.
        if (requestedPlan != null && !requestedPlan.equalsIgnoreCase(SubscriptionService.toPlanCode(prevType))) {
            SubscriptionLifecycleService.checkConversionAllowed(clientId, requestedPlan);
        }

        // Non-subscription fields, exactly as for any other client...
        mapBoToEntity(bo, entity);
        // ...then put the subscription back: only the shared path below may change it.
        entity.setSubscriptionType(prevType);
        entity.setStartDate(start);
        entity.setEndDate(prevEnd);
        entity.setActivePeriod(prevPeriod);
        entity.setActivePeriodUnit(prevUnit);
        String formStatus = entity.getStatus();
        ServiceClient saved = repository.save(entity);

        boolean planChanged = requestedPlan != null
                && !requestedPlan.equalsIgnoreCase(SubscriptionService.toPlanCode(prevType));
        boolean endChanged  = requestedEnd != null && !requestedEnd.equals(prevEnd);

        if (planChanged || endChanged) {
            testDataService.updateDemoSubscription(clientId,
                    planChanged ? requestedPlan : null,
                    endChanged  ? requestedEnd  : null);
            saved = repository.findByClientId(clientId).orElse(saved);
            // The shared update recorded its own plan/end change; what is left to
            // record here is measured from the state it produced.
            before = SubscriptionLifecycleService.snapshot(saved);
            // Extending reactivates the tenant. Keep that when the form says Active (its
            // default), but an admin who chose Hold or Inactive in the same save means it.
            if (formStatus != null && !formStatus.equalsIgnoreCase("Active")
                    && !formStatus.equalsIgnoreCase(saved.getStatus())) {
                saved.setStatus(formStatus);
                saved = repository.save(saved);
            }
            log.info("Registered Clients edit for managed tenant {} applied via the shared subscription "
                   + "update — plan {} -> {}, end {} -> {}, status {} -> {}",
                     clientId, prevType, saved.getSubscriptionType(), prevEnd, saved.getEndDate(),
                     prevStatus, saved.getStatus());
        }
        if (lifecycle != null) {
            applyPricing(saved, before, bo);
            saved = repository.save(saved);
            lifecycle.recordAndRefresh(before, saved, actor, "EDIT");
        }
        invalidatePolicy(saved);
        return saved;
    }

    /**
     * Service Admin → Registered Clients → Extend trial. Moves a Trial-plan client's
     * end date later; the plan is never changed and no login flag is touched, so
     * access returns exactly as it was before the trial ended (logins someone
     * disabled on purpose stay disabled). Sample-data ({@code TRIAL-}) trials go
     * through the shared demo/trial update, which also moves every login's access
     * window; other clients keep their status.
     */
    public ServiceClient extendTrial(Integer id, LocalDate newEnd, String actor) {
        ServiceClient entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Client not found: " + id));
        if (!TrialPolicy.TRIAL_PLAN_CODE.equalsIgnoreCase(SubscriptionService.toPlanCode(entity.getSubscriptionType()))) {
            throw new IllegalArgumentException("Extend trial is only for clients on the Trial plan.");
        }
        if (newEnd == null || !newEnd.isAfter(com.churchgeniuspro.util.AppClock.today())) {
            throw new IllegalArgumentException("The new end date must be after today.");
        }
        if (entity.getEndDate() != null && !newEnd.isAfter(entity.getEndDate())) {
            throw new IllegalArgumentException("The new end date must be later than the current end date ("
                    + entity.getEndDate() + ").");
        }
        if (TestDataService.isManagedTenant(entity.getClientId()) && testDataService != null) {
            testDataService.updateDemoSubscription(entity.getClientId(), null, newEnd);
            ServiceClient saved = repository.findByClientId(entity.getClientId()).orElse(entity);
            if (lifecycle != null) lifecycle.refresh(saved.getClientId());
            invalidatePolicy(saved);
            return saved;
        }
        SubscriptionLifecycleService.Snapshot before = SubscriptionLifecycleService.snapshot(entity);
        entity.setEndDate(newEnd);
        if (entity.getStartDate() != null) {
            entity.setActivePeriod((int) Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(entity.getStartDate(), newEnd)));
            entity.setActivePeriodUnit("DAYS");
        }
        ServiceClient saved = repository.save(entity);
        if (lifecycle != null) lifecycle.recordAndRefresh(before, saved, actor, "EXTENSION");
        invalidatePolicy(saved);
        log.info("Trial extended for {} by {}: end {} -> {}", saved.getClientId(), actor, before.end(), newEnd);
        return saved;
    }

    // ── Convert an empty-account trial to a paid plan ─────────────────────────

    /** Optional: marks the church's subscription request completed on conversion. */
    private SubscriptionRequestService subscriptionRequests;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSubscriptionRequests(SubscriptionRequestService s) { this.subscriptionRequests = s; }

    public record ConvertCommand(String planCode, String billingFrequency, String customPrice,
                                 String startDate, String endDate, String paymentStatus,
                                 boolean sendConfirmation, Long requestId) {}

    public record ConvertResult(ServiceClient client, String planName, boolean emailSent, String emailError) {}

    /**
     * Service Admin → Convert: moves a Trial-plan client to a paid plan after payment
     * (spec section 9). Only the subscription fields change — client id, logins,
     * usernames, passwords and every record stay exactly as they are, so the church
     * keeps using the same account. Refused for a sample-data trial ({@code TRIAL-}).
     *
     * <p>Plan validation, price (plan list price for the frequency, or a custom price),
     * history (reason CONVERSION) and cache refresh all go through
     * {@link SubscriptionLifecycleService}. The confirmation email is account mail and
     * optional; if it fails the conversion stands and the admin is told.
     */
    public ConvertResult convert(Integer id, ConvertCommand cmd, String actor) {
        if (lifecycle == null) throw new IllegalStateException("Subscription changes are not available.");
        ServiceClient entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Client not found: " + id));
        if (!TrialPolicy.TRIAL_PLAN_CODE.equalsIgnoreCase(SubscriptionService.toPlanCode(entity.getSubscriptionType()))) {
            throw new IllegalArgumentException("Convert is for clients on the Trial plan. Use Edit to change a paid client's plan.");
        }
        String newPlan = SubscriptionService.toPlanCode(cmd.planCode());
        if (newPlan == null) throw new IllegalArgumentException("Choose the plan to convert to.");
        if (TrialPolicy.TRIAL_PLAN_CODE.equalsIgnoreCase(newPlan)) {
            throw new IllegalArgumentException("Choose a paid plan. To give more trial time, use Extend trial.");
        }
        SubscriptionLifecycleService.checkConversionAllowed(entity.getClientId(), newPlan);

        LocalDate today = com.churchgeniuspro.util.AppClock.today();
        LocalDate start = parseDate(cmd.startDate(), "start date", today);
        LocalDate end   = parseDate(cmd.endDate(), "end / next billing date", null);
        if (end == null) throw new IllegalArgumentException("Enter the end / next billing date.");
        if (!end.isAfter(start)) throw new IllegalArgumentException("The end / next billing date must be after the start date.");
        if (!end.isAfter(today)) throw new IllegalArgumentException("The end / next billing date must be after today.");
        String payment = cmd.paymentStatus() == null || cmd.paymentStatus().isBlank() ? "PAID" : cmd.paymentStatus().trim().toUpperCase();
        if (!java.util.Set.of("NOT_REQUIRED", "PENDING", "PAID").contains(payment)) {
            throw new IllegalArgumentException("Payment status must be Not Required, Pending or Paid.");
        }
        String freq = SubscriptionLifecycleService.normaliseFrequency(cmd.billingFrequency());
        if (freq == null) freq = SubscriptionLifecycleService.MONTHLY;
        java.math.BigDecimal customPrice = SubscriptionLifecycleService.parsePrice(cmd.customPrice());
        if (cmd.requestId() != null && subscriptionRequests != null) {
            subscriptionRequests.requireOpenFor(cmd.requestId(), entity.getClientId());
        }

        SubscriptionLifecycleService.Snapshot before = SubscriptionLifecycleService.snapshot(entity);
        entity.setSubscriptionType(newPlan);
        entity.setStartDate(start);
        entity.setEndDate(end);
        long months = java.time.temporal.ChronoUnit.MONTHS.between(start, end);
        long years  = java.time.temporal.ChronoUnit.YEARS.between(start, end);
        if (years > 0 && start.plusYears(years).equals(end)) {
            entity.setActivePeriod((int) years);  entity.setActivePeriodUnit("YEARS");
        } else if (months > 0 && start.plusMonths(months).equals(end)) {
            entity.setActivePeriod((int) months); entity.setActivePeriodUnit("MONTHS");
        } else {
            entity.setActivePeriod((int) java.time.temporal.ChronoUnit.DAYS.between(start, end));
            entity.setActivePeriodUnit("DAYS");
        }
        entity.setStatus("Active");
        entity.setPaymentStatus(payment);
        lifecycle.validatePlanChange(before, entity);
        lifecycle.applyPricing(entity, before, freq, customPrice, customPrice != null, false);
        ServiceClient saved = repository.save(entity);
        lifecycle.recordAndRefresh(before, saved, actor, "CONVERSION");
        invalidatePolicy(saved);

        if (cmd.requestId() != null && subscriptionRequests != null) {
            try { subscriptionRequests.complete(cmd.requestId(), saved.getClientId(), actor); }
            catch (Exception e) { log.warn("Conversion of {}: request {} not marked completed — {}", saved.getClientId(), cmd.requestId(), e.getMessage()); }
        }
        String planName = lifecycle.planFor(newPlan).map(com.churchgeniuspro.hibernate.SubscriptionPlan::getPlanName).orElse(newPlan);
        log.info("Trial converted: {} -> {} ({}, {}) by {}; end {}", saved.getClientId(), newPlan, freq,
                 saved.getSubscriptionPrice(), actor, saved.getEndDate());

        boolean sent = false;
        String err = null;
        if (cmd.sendConfirmation()) {
            if (saved.getEmail() == null || saved.getEmail().isBlank()) {
                err = "The subscription was converted, but this client has no email address, so no confirmation was sent.";
            } else {
                try {
                    emailService.sendAccountEmailOrThrow(saved.getEmail(),
                            "Your ChurchGeniusPro subscription: " + planName,
                            buildConversionEmail(saved, planName), null);
                    sent = true;
                } catch (Exception e) {
                    err = "The subscription was converted, but the confirmation email to " + saved.getEmail()
                        + " could not be sent.";
                    log.error("Conversion confirmation to {} failed — {}", saved.getEmail(), e.toString());
                }
            }
        }
        return new ConvertResult(saved, planName, sent, err);
    }

    private static LocalDate parseDate(String v, String label, LocalDate fallback) {
        if (v == null || v.isBlank()) return fallback;
        try { return LocalDate.parse(v.trim()); }
        catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Enter the " + label + " as YYYY-MM-DD.");
        }
    }

    String buildConversionEmail(ServiceClient c, String planName) {
        java.time.format.DateTimeFormatter day = java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy");
        String price = c.getSubscriptionPrice() == null ? "—"
                : "$" + c.getSubscriptionPrice().setScale(2, java.math.RoundingMode.HALF_UP)
                  + (SubscriptionLifecycleService.YEARLY.equals(c.getBillingFrequency()) ? " per year" : " per month");
        return "<div style='font-family:Segoe UI,Arial,sans-serif;font-size:15px;color:#2b2b2b;line-height:1.6;'>"
             + "<p>Dear " + escapeHtml(c.getName() != null ? c.getName() : c.getChurchName()) + ",</p>"
             + "<p>You can continue using the application with the <strong>" + escapeHtml(planName)
             + "</strong> subscription.</p>"
             + "<p>Your username, password and account are unchanged, and all of your data is still there.</p>"
             + "<table cellpadding='6' style='border-collapse:collapse;font-size:14px;'>"
             + "<tr><td style='font-weight:600;'>Church</td><td>" + escapeHtml(c.getChurchName()) + "</td></tr>"
             + "<tr><td style='font-weight:600;'>Plan</td><td>" + escapeHtml(planName) + "</td></tr>"
             + "<tr><td style='font-weight:600;'>Price</td><td>" + escapeHtml(price) + "</td></tr>"
             + "<tr><td style='font-weight:600;'>Billing</td><td>"
             + (SubscriptionLifecycleService.YEARLY.equals(c.getBillingFrequency()) ? "Yearly" : "Monthly") + "</td></tr>"
             + "<tr><td style='font-weight:600;'>Next billing date</td><td>"
             + (c.getEndDate() != null ? c.getEndDate().format(day) : "—") + "</td></tr>"
             + "</table>"
             + "<p>Thank you for choosing ChurchGeniusPro.<br/>— The ChurchGeniusPro team</p></div>";
    }

    /** End date for a period; DAYS is supported alongside MONTHS (default) and YEARS. */
    static LocalDate endDateFor(LocalDate start, int period, String unit) {
        if (unit != null && unit.equalsIgnoreCase("YEARS")) return start.plusYears(period);
        if (unit != null && unit.equalsIgnoreCase("DAYS"))  return start.plusDays(period);
        return start.plusMonths(period);
    }

    /** Drops the cached send-permission answer after a subscription change. */
    private void invalidatePolicy(ServiceClient c) {
        if (messagingPolicy != null && c != null) messagingPolicy.invalidate(c.getClientId());
    }

    public void softDelete(Integer id) {
        ServiceClient entity = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Client not found: " + id));
        entity.setDeleteFlag(true);
        repository.save(entity);
    }

    /**
     * Marks the client as approved, sets status to Active, and sends a
     * welcome email containing a church-registration link with an
     * encrypted Client ID.
     */
    public void approve(Integer id) throws Exception {
        ServiceClient entity = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Client not found: " + id));
        entity.setApproved(true);
        entity.setStatus("Active");
        entity.setRegistrationToken(PublicLinkResolver.newToken());   // a new link every approval
        repository.save(entity);

        // The URL parameter is still named clientId for the page's sake; the value is the token.
        String link = baseUrl + "/churchregistration.html?clientId=" + entity.getRegistrationToken();

        // ── Email ──────────────────────────────────────────────────────────
        String html = buildApprovalEmail(entity.getName(), link);
        emailService.sendGenericEmail(
                entity.getEmail(),
                "Your Registration Has Been Approved – Church Genius Pro",
                html);

        // ── WhatsApp ───────────────────────────────────────────────────────
        if (entity.getPhone() != null && !entity.getPhone().isBlank()) {
            String whatsAppMsg = buildApprovalWhatsAppMessage(entity.getName(), link);
            whatsAppSender.sendWhatsAppToPhone(entity.getPhone(), whatsAppMsg, entity.getClientId());
        }
    }

    /**
     * Re-approves an already-approved client.
     *
     * <ol>
     *   <li>Invalidates any existing church signup tied to this clientId
     *       (sets deleted=true, active=false) so the old registration link
     *       can no longer be used to log in.</li>
     *   <li>Generates a fresh encrypted registration link and sends it
     *       via email (and WhatsApp if a phone number is on file).</li>
     * </ol>
     */
    public void reapprove(Integer id) throws Exception {
        ServiceClient entity = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Client not found: " + id));

        // ── Invalidate existing church signup for this clientId ────────────
        loginRepository.findByClientId(entity.getClientId()).ifPresent(existing -> {
            if (Boolean.TRUE.equals(existing.getChurch())) {
                existing.setDeleted(true);
                existing.setActive(false);
                loginRepository.save(existing);
                log.info("Invalidated existing church signup id={} for clientId={}",
                        existing.getId(), entity.getClientId());
            }
        });

        // ── Ensure client remains approved and active ──────────────────────
        entity.setApproved(true);
        entity.setStatus("Active");
        entity.setRegistrationToken(PublicLinkResolver.newToken());   // a new link every approval
        repository.save(entity);

        // The URL parameter is still named clientId for the page's sake; the value is the token.
        String link = baseUrl + "/churchregistration.html?clientId=" + entity.getRegistrationToken();

        String html = buildReapprovalEmail(entity.getName(), link);
        emailService.sendGenericEmail(
                entity.getEmail(),
                "Your New Church Registration Link – Church Genius Pro",
                html);

        if (entity.getPhone() != null && !entity.getPhone().isBlank()) {
            String whatsAppMsg = buildApprovalWhatsAppMessage(entity.getName(), link);
            whatsAppSender.sendWhatsAppToPhone(entity.getPhone(), whatsAppMsg, entity.getClientId());
        }
    }

    /**
     * Decrypts {@code encryptedClientId} and returns the matching active
     * ServiceClient, or {@code null} if the ID is invalid / inactive.
     */
    public ServiceClient validateClientId(String registrationToken) {
        if (registrationToken == null || registrationToken.isBlank()) return null;
        return repository.findByRegistrationTokenAndStatusAndDeleteFlagFalse(registrationToken.trim(), "Active")
                .orElse(null);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void mapBoToEntity(ServiceClientBO bo, ServiceClient entity) {
        entity.setName(bo.getName());
        entity.setChurchName(bo.getChurchName());
        entity.setEmail(bo.getEmail());
        entity.setPhone(bo.getPhone());
        entity.setAddressLine1(bo.getAddressLine1());
        entity.setAddressLine2(bo.getAddressLine2());
        entity.setCity(bo.getCity());
        entity.setState(bo.getState());
        entity.setCountry(bo.getCountry() != null && !bo.getCountry().isBlank()
                ? bo.getCountry() : "USA");
        entity.setPinCode(bo.getPinCode());
        entity.setWebsiteUrl(bo.getWebsiteUrl());
        entity.setFacebookUrl(bo.getFacebookUrl());
        entity.setInstagramUrl(bo.getInstagramUrl());
        entity.setYoutubeUrl(bo.getYoutubeUrl());
        entity.setActivePeriod(bo.getActivePeriod());

        String unit = (bo.getActivePeriodUnit() != null && !bo.getActivePeriodUnit().isBlank())
                ? bo.getActivePeriodUnit() : "MONTHS";
        entity.setActivePeriodUnit(unit);

        LocalDate startDate = (bo.getStartDate() != null && !bo.getStartDate().isBlank())
                ? LocalDate.parse(bo.getStartDate())
                : com.churchgeniuspro.util.AppClock.today();          // subscription dates are Chicago dates
        entity.setStartDate(startDate);

        if (bo.getActivePeriod() != null && bo.getActivePeriod() > 0) {
            entity.setEndDate(endDateFor(startDate, bo.getActivePeriod(), unit));
        } else if ("TRIAL".equalsIgnoreCase(bo.getSubscriptionType())) {
            // Trial plan: the configured trial length (Subscription Plans → Trial)
            // from the start date when no explicit active period was entered.
            // Expiry blocks all logins for the client.
            entity.setEndDate(startDate.plusDays(defaultTrialDays()));
        }

        entity.setPaymentStatus(bo.getPaymentStatus() != null ? bo.getPaymentStatus() : "PENDING");
        // A blank Subscription Type (e.g. a plan code the form's dropdown did not list)
        // keeps the current one rather than being stored as "" — which resolves to no
        // plan and therefore no limits at all.
        entity.setSubscriptionType(bo.getSubscriptionType() != null && !bo.getSubscriptionType().isBlank()
                ? bo.getSubscriptionType()
                : (entity.getSubscriptionType() != null && !entity.getSubscriptionType().isBlank()
                        ? entity.getSubscriptionType() : "FREE"));
        // Per-client extra SMS credits (default 0 for new clients; keep existing on partial update)
        entity.setExtraSmsCount(bo.getExtraSmsCount() != null && bo.getExtraSmsCount() >= 0
                ? bo.getExtraSmsCount()
                : (entity.getExtraSmsCount() != null ? entity.getExtraSmsCount() : 0));
        entity.setNote(bo.getNote());
        entity.setStatus(bo.getStatus() != null && !bo.getStatus().isBlank()
                ? bo.getStatus() : "Active");
    }

    private String buildApprovalEmail(String name, String link) {
        String safeName = escapeHtml(name);
        return "<!DOCTYPE html><html lang='en'><head>"
             + "<meta charset='UTF-8'/><meta name='viewport' content='width=device-width,initial-scale=1.0'/>"
             + "<title>Registration Approved</title></head>"
             + "<body style='margin:0;padding:0;background-color:#f5f6fa;"
             +   "font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;'>"
             + "<table width='100%' cellpadding='0' cellspacing='0' style='background-color:#f5f6fa;padding:40px 20px;'>"
             + "<tr><td align='center'>"
             + "<table width='100%' cellpadding='0' cellspacing='0'"
             +   " style='max-width:520px;background:#ffffff;border-radius:16px;"
             +          "box-shadow:0 4px 24px rgba(0,0,0,0.08);overflow:hidden;'>"
             + "<tr><td style='background-color:#3a5a9b;padding:32px 40px;text-align:center;'>"
             + "<h1 style='color:#ffffff;font-size:22px;margin:0;'>Church Genius Pro</h1></td></tr>"
             + "<tr><td style='padding:32px 40px;'>"
             + "<p style='font-size:16px;color:#333;'>Dear " + safeName + ",</p>"
             + "<p style='font-size:15px;color:#333;'>Your church registration has been approved!</p>"
             + "<p style='font-size:15px;color:#333;'>Please use the link below to complete your setup:</p>"
             + "<p style='text-align:center;margin:24px 0;'>"
             + "<a href='" + link + "' style='background-color:#3a5a9b;color:#fff;padding:12px 28px;"
             +   "border-radius:8px;text-decoration:none;font-size:15px;'>Complete Registration</a></p>"
             + "<p style='font-size:13px;color:#999;'>Or copy this link: " + link + "</p>"
             + "</td></tr>"
             + "<tr><td style='background-color:#f5f6fa;padding:16px 40px;text-align:center;'>"
             + "<p style='font-size:12px;color:#999;margin:0;'>&copy; Church Genius Pro</p>"
             + "</td></tr>"
             + "</table></td></tr></table></body></html>";
    }

    private String buildReapprovalEmail(String name, String link) {
        String safeName = escapeHtml(name);
        return "<!DOCTYPE html><html lang='en'><head>"
             + "<meta charset='UTF-8'/><title>New Registration Link</title></head>"
             + "<body style='font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;"
             +   "background:#f5f6fa;margin:0;padding:40px 20px;'>"
             + "<table width='100%' cellpadding='0' cellspacing='0'><tr><td align='center'>"
             + "<table style='max-width:520px;background:#fff;border-radius:16px;"
             +   "box-shadow:0 4px 24px rgba(0,0,0,0.08);overflow:hidden;'>"
             + "<tr><td style='background:#3a5a9b;padding:32px 40px;text-align:center;'>"
             + "<h1 style='color:#fff;font-size:22px;margin:0;'>Church Genius Pro</h1></td></tr>"
             + "<tr><td style='padding:32px 40px;'>"
             + "<p style='font-size:16px;color:#333;'>Dear " + safeName + ",</p>"
             + "<p style='font-size:15px;color:#333;'>A new registration link has been generated for your church.</p>"
             + "<p style='text-align:center;margin:24px 0;'>"
             + "<a href='" + link + "' style='background:#3a5a9b;color:#fff;padding:12px 28px;"
             +   "border-radius:8px;text-decoration:none;font-size:15px;'>Register Now</a></p>"
             + "<p style='font-size:13px;color:#999;'>Or copy: " + link + "</p>"
             + "</td></tr></table></td></tr></table></body></html>";
    }

    private String buildApprovalWhatsAppMessage(String name, String link) {
        return "Hello " + name + ",\n\nYour Church Genius Pro registration has been approved!\n"
             + "Please use the following link to complete your setup:\n" + link
             + "\n\nThank you,\nChurch Genius Pro";
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
