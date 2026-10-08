package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.model.TrialRegistrationBO;
import com.churchgeniuspro.plaid.service.ServiceAdminPlaidService;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.LoginRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The transactional half of trial registration: everything that must succeed or
 * fail together.
 *
 * <p>A separate bean from {@link TrialRegistrationService} for a mechanical
 * reason — {@code @Transactional} is applied by a proxy, so a method called from
 * another method of the same class would run with no transaction at all. Keeping
 * the boundary on its own bean is what makes it real.
 *
 * <p>A half-built tenant is worse than none: a client row with no church, or a
 * church with no login, shows up in Service Admin as a working account and fails
 * only at sign-in. The invite email is deliberately OUTSIDE this boundary, in the
 * caller — mail cannot be rolled back.
 */
@Service
public class TrialTenantProvisioner {

    private static final Logger log = LoggerFactory.getLogger(TrialTenantProvisioner.class);

    /** What the transaction produced, for the caller's non-transactional half. */
    public record Provisioned(String clientId, String churchName,
                              Integer appUserId, String username, LocalDate trialEndsOn) {}

    private final TestDataService   testData;
    private final DemoAccessService demoAccess;
    private final AppUserRepository appUserRepo;
    private final LoginRepository   loginRepo;
    private final ServiceAdminPlaidService plaidSettings;

    public TrialTenantProvisioner(TestDataService testData,
                                  DemoAccessService demoAccess,
                                  AppUserRepository appUserRepo,
                                  LoginRepository loginRepo,
                                  ServiceAdminPlaidService plaidSettings) {
        this.testData      = testData;
        this.demoAccess    = demoAccess;
        this.appUserRepo   = appUserRepo;
        this.loginRepo     = loginRepo;
        this.plaidSettings = plaidSettings;
    }

    @Transactional
    public Provisioned provision(TrialRegistrationBO bo, String planCode, int trialDays) {
        String clientId = TestDataService.TRIAL_CLIENT_PREFIX + System.currentTimeMillis();
        // Start = registration day, America/Chicago; access closes on the end date.
        LocalDate expiry = com.churchgeniuspro.util.AppClock.today().plusDays(trialDays);

        TestDataService.TenantSpec.Contact contact = new TestDataService.TenantSpec.Contact(
                bo.getFirstName().trim(), bo.getLastName().trim(),
                bo.getEmail().trim().toLowerCase(), bo.getPhone(),
                bo.getAddressLine1(), bo.getAddressLine2(), bo.getCity(), bo.getState(),
                bo.getCountry(), bo.getPinCode(), bo.getNote());

        // Church login + SuperAdmin, plus one Member Portal and one Kids Portal.
        // A trial is an evaluation of the whole product, and two of its three
        // audiences — the congregation and the children — could not be seen at all
        // without a login of their own. One of each rather than the demo tenant's
        // pair, so the credentials panel and the welcome email name exactly one
        // Member Portal and one Kids Portal.
        //
        // The portals need no separate data: provisionTenant seeds families,
        // members, Kids Ministry children and Sunday School classes with graded
        // exams, and keys each portal login to one of those people — so both open
        // on a populated screen rather than an empty shell.
        TestDataService.TenantSpec spec = new TestDataService.TenantSpec(
                clientId, bo.getChurchName().trim(), List.of("SuperAdmin"), 1, contact);

        Map<String, Object> created = testData.provisionTenant(spec, planCode, expiry);

        AppUser superAdmin = appUserRepo
                .findByClientIdAndDeleteFlagFalseOrderByLastNameAscFirstNameAsc(clientId)
                .stream()
                .filter(u -> "SuperAdmin".equalsIgnoreCase(u.getRole()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Trial provisioning did not create a SuperAdmin for " + clientId));

        // Replace the seeded sample identity with the registrant's own, so the
        // invite reaches them and the account reads as theirs. The username and
        // password the seeding generated are left alone — they are what Service
        // Admin displays, and the real password is set through UserSignup.
        superAdmin.setFirstName(bo.getFirstName().trim());
        superAdmin.setLastName(bo.getLastName().trim());
        superAdmin.setEmail(bo.getEmail().trim().toLowerCase());
        if (bo.getPhone() != null && !bo.getPhone().isBlank()) superAdmin.setPhone(bo.getPhone().trim());
        appUserRepo.save(superAdmin);

        // ── Demo Role Access ──────────────────────────────────────────────────
        // settingsFor creates the tenant's delivery switches, which default to SMS
        // off and email off. A church that signed itself up minutes ago must not be
        // able to message a congregation until a Service Admin allows it.
        demoAccess.settingsFor(clientId);

        String superAdminUsername = null;
        for (Map<String, Object> row : demoAccess.allDemoLogins()) {
            if (!clientId.equals(row.get("tenant"))) continue;
            Object rawId = row.get("signup_id");
            if (rawId == null) continue;
            int signupId = ((Number) rawId).intValue();
            String role     = String.valueOf(row.get("role_label"));
            String username = String.valueOf(row.get("username"));
            // A Church row carries no member name, matching how the Service Admin
            // table renders a demo tenant's church login.
            String memberName = TestDataService.ROLE_CHURCH.equals(role)
                    ? "" : String.valueOf(row.getOrDefault("member_name", "")).trim();
            demoAccess.record(signupId, clientId, username, role, memberName, expiry);
            if ("SuperAdmin".equalsIgnoreCase(role)) superAdminUsername = username;
        }

        if (superAdminUsername == null) {
            // Non-church signups key on app_user.user_id, not the tenant id.
            superAdminUsername = loginRepo.findAllByClientId(superAdmin.getUserId())
                    .stream().map(SignUp::getUsername).findFirst().orElse(null);
        }

        // ── Bank Sync ─────────────────────────────────────────────────────────
        // Plaid is off for a church until a Service Admin turns it on: PlaidGuard
        // reads church_plaid_setting and defaults a missing row to disabled. Trial
        // registration exists to remove exactly that manual step, and without this
        // a trial account reaches Bank Sync only to be told "Bank sync is not
        // enabled for your organization".
        //
        // Safe to enable unattended precisely because the tenant is on the TRIAL
        // plan: PlaidEnvironmentService confines it to the Plaid sandbox, so what
        // is being switched on is access to test banks, not real ones. Demo tenants
        // deliberately do NOT get this — loadSmallDemo accepts any plan, including
        // ones that route to production.
        //
        // Routed through ServiceAdminPlaidService rather than writing the row here,
        // so the change is audited the same way a Service Admin's would be.
        try {
            plaidSettings.updateSettings(clientId, true, true, "TRIAL_REGISTRATION");
        } catch (Exception e) {
            // Non-fatal: a Service Admin can still enable it by hand, and losing the
            // whole registration over a Bank Sync toggle would be a poor trade.
            log.warn("Trial tenant {}: could not enable Bank Sync — {}", clientId, e.getMessage());
        }

        log.info("Trial tenant provisioned: clientId={} church='{}' superAdmin={} endsOn={}",
                 clientId, spec.churchName(), superAdminUsername, expiry);

        return new Provisioned(clientId, String.valueOf(created.get("churchName")),
                               superAdmin.getId(), superAdminUsername, expiry);
    }
}
