package com.churchgeniuspro.service;

import com.churchgeniuspro.model.TrialRegistrationBO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Self-service trial registration: everything a Service Admin used to do by hand
 * (Register New Client → Church Registration → Add User → Invite), driven from
 * one public form.
 *
 * <p>This class composes existing services and creates nothing itself. Every step
 * is the code path the manual flow already uses, which is what keeps a trial
 * tenant indistinguishable from a hand-built one:
 *
 * <ol>
 *   <li>{@link TrialTenantProvisioner} → {@link TestDataService#provisionTenant} —
 *       the client row, the church registration, the Church login, the SuperAdmin
 *       and the full sample data set. The same method Load Test Data calls.</li>
 *   <li>{@link DemoAccessService} — puts both logins on the Demo Role Access
 *       screen with an access window, and creates the tenant's delivery switches
 *       (SMS and email off by default).</li>
 *   <li>{@link AppUserService#sendEmail} — the Invite button's own method, so the
 *       signup link and the UserSignup page it opens are unchanged.</li>
 * </ol>
 *
 * <h2>No email verification, by design</h2>
 * Nothing here waits on a confirmation click. The address is only used to send the
 * invite, and a wrong one costs an unusable tenant rather than access: the
 * SuperAdmin cannot sign in until someone opens the emailed link and sets a
 * password through the existing UserSignup flow.
 */
@Service
public class TrialRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(TrialRegistrationService.class);

    /**
     * Historical trial length, kept as the fallback name used across the codebase.
     * The live value is {@link TrialPolicy#trialDays()}; see {@link #trialDaysOf}.
     */
    public static final int TRIAL_DAYS = TrialPolicy.FALLBACK_DAYS;

    /** The plan a trial starts on. Must exist and be active under Subscription Plans. */
    public static final String TRIAL_PLAN_CODE = "TRIAL";

    private final TrialTenantProvisioner provisioner;
    private final AppUserService appUserService;

    /**
     * Sends the new administrator the sign-ins for all three portals. Optional and
     * null-checked so this service's existing construction sites (and their tests)
     * are unchanged.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PortalWelcomeEmail portalWelcome;

    /** Test seam — supply the portal email without a Spring context. */
    public void setPortalWelcome(PortalWelcomeEmail p) { this.portalWelcome = p; }


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
     * The Register Client path an EMPTY-account trial reuses (Service Admin →
     * Register New Client with Subscription Type = Trial, then Approve). Optional and
     * setter-injected so existing construction sites are unchanged; without it an
     * empty-account request is refused as unavailable.
     */
    private ServiceClientService serviceClients;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setServiceClients(ServiceClientService s) { this.serviceClients = s; }

    /** Bank Sync switch, as the sample-data trial gets it (sandbox: the plan is TRIAL). */
    private com.churchgeniuspro.plaid.service.ServiceAdminPlaidService plaidSettings;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPlaidSettings(com.churchgeniuspro.plaid.service.ServiceAdminPlaidService p) { this.plaidSettings = p; }

    public TrialRegistrationService(TrialTenantProvisioner provisioner,
                                    AppUserService appUserService) {
        this.provisioner    = provisioner;
        this.appUserService = appUserService;
    }

    /**
     * Provisions a trial tenant and emails its SuperAdmin a signup link.
     *
     * @return clientId, church name, the SuperAdmin's username, and whether the
     *         invite email actually went out
     */
    /** The trial length for this registration: the link's stored length, else the configured default. */
    int trialDaysOf(TrialRegistrationBO bo) {
        Integer d = bo == null ? null : bo.getTrialDays();
        return (d == null || d < 1 || d > TrialRegistrationLinkService.MAX_TRIAL_DAYS) ? defaultTrialDays() : d;
    }

    public Map<String, Object> register(TrialRegistrationBO bo) {
        bo.validate();
        if (TrialRegistrationBO.EMPTY.equals(bo.accountTypeOrDefault())) {
            return registerEmpty(bo);
        }

        TrialTenantProvisioner.Provisioned p;
        try {
            p = provisioner.provision(bo, TRIAL_PLAN_CODE, trialDaysOf(bo));
        } catch (IllegalArgumentException e) {
            // resolvePlanCode throws this when no active plan carries the code, and
            // its message lists what is available. Name the setting that is missing
            // rather than surfacing a bare plan-code error to a member of the public.
            if (e.getMessage() != null && e.getMessage().contains("subscription plan")) {
                log.error("Trial registration blocked — no active '{}' plan configured: {}",
                          TRIAL_PLAN_CODE, e.getMessage());
                throw new IllegalStateException(
                        "Trial signup is not available right now. Please contact support.");
            }
            throw e;
        }

        // ── Invite: the same method the Invite button on /viewusers calls ──────
        // Outside the transaction and non-fatal. The tenant exists either way, and
        // a failed send is a resendable state, not a reason to discard a signup —
        // a Service Admin can press Invite on the same user.
        boolean invited = false;
        String  inviteError = null;
        try {
            appUserService.sendEmail(p.appUserId());
            invited = true;
        } catch (Exception e) {
            inviteError = e.getMessage();
            log.warn("Trial registration {}: tenant created but the invite email failed — {}",
                     p.clientId(), e.getMessage());
        }

        // ── The portal list ───────────────────────────────────────────────────
        // A second message, not a longer first one: the invitation carries a
        // single-use link the registrant must act on, and burying two more sets of
        // credentials in it invites them to act on the wrong thing. Sent after, and
        // just as non-fatally — the tenant exists regardless.
        boolean portalsEmailed = false;
        if (portalWelcome != null) {
            try {
                portalsEmailed = portalWelcome.send(
                        p.clientId(), p.churchName(), bo.getEmail().trim().toLowerCase(),
                        bo.getFirstName().trim(), String.valueOf(p.trialEndsOn()));
            } catch (Exception e) {
                log.warn("Trial registration {}: portal credentials email failed — {}",
                         p.clientId(), e.getMessage());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clientId",       p.clientId());
        out.put("churchName",     p.churchName());
        out.put("superAdminUser", p.username());
        out.put("email",          bo.getEmail().trim().toLowerCase());
        out.put("trialEndsOn",    String.valueOf(p.trialEndsOn()));
        out.put("invited",        invited);
        out.put("portalsEmailed", portalsEmailed);
        if (inviteError != null) out.put("inviteError", inviteError);

        out.put("accountType",    TrialRegistrationBO.SAMPLE);

        log.info("Trial registration complete: clientId={} church='{}' invited={}",
                 p.clientId(), p.churchName(), invited);
        return out;
    }

    /**
     * EMPTY-account trial: exactly what a Service Admin does with Register New Client
     * (Subscription Type = Trial) followed by Approve — a {@code CHR} client on the
     * TRIAL plan with no sample data, and the existing church-registration email so
     * the church sets its own username and password. Such a trial can later be
     * converted to a paid plan keeping all of its data; a sample-data ({@code TRIAL-})
     * trial cannot.
     *
     * <p>Dates: start = today (America/Chicago), period = the link's trial days.
     * Bank Sync is switched on as for a sample-data trial; the TRIAL plan confines it
     * to the Plaid sandbox, so no real bank can be connected during the trial.
     */
    Map<String, Object> registerEmpty(TrialRegistrationBO bo) {
        if (serviceClients == null) {
            throw new IllegalStateException("Trial signup is not available right now. Please contact support.");
        }
        int days = trialDaysOf(bo);
        com.churchgeniuspro.model.ServiceClientBO sc = new com.churchgeniuspro.model.ServiceClientBO();
        sc.setName(bo.getFirstName().trim() + " " + bo.getLastName().trim());
        sc.setChurchName(bo.getChurchName().trim());
        sc.setEmail(bo.getEmail().trim().toLowerCase());
        sc.setPhone(bo.getPhone());
        sc.setAddressLine1(bo.getAddressLine1());
        sc.setAddressLine2(bo.getAddressLine2());
        sc.setCity(bo.getCity());
        sc.setState(bo.getState());
        sc.setCountry(bo.getCountry());
        sc.setPinCode(bo.getPinCode());
        sc.setNote(bo.getNote());
        sc.setSubscriptionType(TRIAL_PLAN_CODE);
        sc.setActivePeriod(days);
        sc.setActivePeriodUnit("DAYS");
        sc.setStartDate(com.churchgeniuspro.util.AppClock.today().toString());
        sc.setPaymentStatus("NOT_REQUIRED");
        sc.setStatus("Active");

        com.churchgeniuspro.hibernate.ServiceClient saved;
        try {
            saved = serviceClients.save(sc, "trial-registration");
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().contains("does not exist or is inactive")) {
                log.error("Trial registration (empty) blocked — no active '{}' plan configured: {}",
                          TRIAL_PLAN_CODE, e.getMessage());
                throw new IllegalStateException("Trial signup is not available right now. Please contact support.");
            }
            throw e;
        }
        try {
            serviceClients.approve(saved.getId());   // church-registration link, as Approve sends it
        } catch (Exception e) {
            // Undo the half-made client so the released link can be used again cleanly.
            try { serviceClients.softDelete(saved.getId()); } catch (Exception ignored) { }
            log.error("Trial registration (empty) {}: approval failed — {}", saved.getClientId(), e.toString());
            throw new IllegalStateException("We could not complete your registration. Please try again or contact support.");
        }
        if (plaidSettings != null) {
            try {
                plaidSettings.updateSettings(saved.getClientId(), true, true, "TRIAL_REGISTRATION");
            } catch (Exception e) {
                log.warn("Trial client {}: could not enable Bank Sync — {}", saved.getClientId(), e.getMessage());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clientId",    saved.getClientId());
        out.put("churchName",  saved.getChurchName());
        out.put("email",       saved.getEmail());
        out.put("trialEndsOn", String.valueOf(saved.getEndDate()));
        out.put("invited",     true);       // the registration email is sent by approve()
        out.put("accountType", TrialRegistrationBO.EMPTY);
        log.info("Trial registration complete (empty account): clientId={} church='{}' endsOn={}",
                 saved.getClientId(), saved.getChurchName(), saved.getEndDate());
        return out;
    }
}
