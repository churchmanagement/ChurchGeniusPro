package com.churchgeniuspro.config;

import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.repository.SubscriptionPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Seeds the three default subscription plans (Free / Standard / Pro) on
 * startup when they don't exist yet. Idempotent — existing plans (including
 * admin-edited limits/features) are never touched, so Service Admin changes
 * survive restarts. Additional plans can be created any time from the
 * Service Admin dashboard.
 *
 * <p>Feature JSON lists only DISABLED features — a missing key means enabled
 * (see {@code SubscriptionService}), so newly introduced features default on
 * for every plan.
 */
@Configuration
public class SubscriptionPlanSeeder {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionPlanSeeder.class);

    @Bean
    ApplicationRunner seedSubscriptionPlans(SubscriptionPlanRepository repo) {
        return args -> {
            // Trial: every Pro feature for trial_days days (end date auto-set at client
            // registration; expiry blocks all logins like any other plan). The
            // "N-day free trial · " prefix is rendered from trial_days, not stored.
            // No SMS or email figure here, deliberately: MessagingPolicy refuses BOTH
            // outright on a Trial subscription, so quoting an allowance described
            // something the product will not do.
            seed(repo, "TRIAL", "Trial Plan", 0,
                    "Pro Plan features for evaluation · Unlimited portals · "
                  + "Full AI integration · Advanced accounting · SMS and email sending are "
                  + "disabled on a trial; Bank Sync uses test banks only",
                    1000, null, 100, 100, null, null,
                    "{}");
            commercial(repo, "TRIAL", "0.00", null, 30);

            seed(repo, "FREE", "Free Plan", 1,
                    "Up to 50 people · 20 emails/month · 3 staff portals · "
                  + "Event registration & attendance · Groups, ministries & follow-ups",
                    50, 20, 50, 10, 3, 0,
                    // Disabled features on Free:
                    "{\"accounting\":false,\"bankImport\":false,\"bankSync\":false,"
                  + "\"pledges\":false,\"payroll\":false,\"worship\":false,"
                  + "\"eventCheckin\":false,\"kidsCheckin\":false,\"kidsMinistry\":false,"
                  + "\"kidsPortal\":false,\"volunteers\":false,\"privatePages\":false,"
                  + "\"ntag\":false,\"aiSearch\":false,\"aiVoice\":false,"
                  + "\"aiConverse\":false,\"scanCheck\":false}");
            commercial(repo, "FREE", "0.00", 0, null);

            seed(repo, "STANDARD", "Standard Plan", 2,
                    "Up to 100 people · 50 emails/month · 50 SMS/month · "
                  + "Staff, church & child portals · Standard accounting & online giving · "
                  + "Worship planning · Check-ins · Chat · Free migration · NTag login",
                    // Kids portals capped at 3 on Standard (was unlimited/null).
                    100, 50, 50, 20, 3, 3,
                    // AI is "Limited" on Standard: search on, voice/converse/scan off.
                    // No Payroll on Standard. Bank Sync is on, capped at 3 connected
                    // accounts by max_bank_accounts (commercial() below).
                    "{\"aiVoice\":false,\"aiConverse\":false,\"scanCheck\":false,"
                  + "\"payroll\":false}",
                    10);   // up to 10 church-added users (viewusers)
            commercial(repo, "STANDARD", "14.99", 3, null);

            seed(repo, "PRO", "Pro Plan", 3,
                    "Unlimited people & portals · Unlimited emails · Unlimited online giving · "
                  + "Advanced accounting · Full AI integration · 100 free SMS/month · "
                  + "Everything included in Standard Plan",
                    1000, null, 100, 100, null, null,
                    "{}");
            commercial(repo, "PRO", "34.99", null, null);
        };
    }

    /**
     * Price, Bank Sync account limit and (TRIAL only) trial length. Applied only
     * where the value is still null, so a plan an admin has already configured is
     * never overwritten on restart — the same rule Flyway V6 follows for databases
     * created before these columns existed. Null limit = unlimited, 0 = none.
     */
    private void commercial(SubscriptionPlanRepository repo, String code, String monthlyPrice,
                            Integer maxBankAccounts, Integer trialDays) {
        repo.findByPlanCodeIgnoreCase(code).ifPresent(p -> {
            boolean changed = false;
            if (p.getMonthlyPrice() == null) { p.setMonthlyPrice(new java.math.BigDecimal(monthlyPrice)); changed = true; }
            if (p.getMaxBankAccounts() == null && maxBankAccounts != null) { p.setMaxBankAccounts(maxBankAccounts); changed = true; }
            if (p.getTrialDays() == null && trialDays != null) { p.setTrialDays(trialDays); changed = true; }
            if (changed) repo.save(p);
        });
    }

    private void seed(SubscriptionPlanRepository repo, String code, String name, int sortOrder,
                      String description, Integer maxPeople, Integer maxEmails, Integer maxSms,
                      Integer maxGiving, Integer maxMemberPortals, Integer maxKidsPortals,
                      String featuresJson) {
        seed(repo, code, name, sortOrder, description, maxPeople, maxEmails, maxSms, maxGiving,
             maxMemberPortals, maxKidsPortals, featuresJson, null);
    }

    private void seed(SubscriptionPlanRepository repo, String code, String name, int sortOrder,
                      String description, Integer maxPeople, Integer maxEmails, Integer maxSms,
                      Integer maxGiving, Integer maxMemberPortals, Integer maxKidsPortals,
                      String featuresJson, Integer maxStaffUsers) {
        if (repo.existsByPlanCodeIgnoreCase(code)) return;
        SubscriptionPlan p = new SubscriptionPlan();
        p.setPlanCode(code);
        p.setPlanName(name);
        p.setSortOrder(sortOrder);
        p.setDescription(description);
        p.setMaxPeople(maxPeople);
        p.setMaxEmailsPerMonth(maxEmails);
        p.setMaxSmsPerMonth(maxSms);
        p.setMaxOnlineGivingPerMonth(maxGiving);
        p.setMaxMemberPortals(maxMemberPortals);
        p.setMaxKidsPortals(maxKidsPortals);
        p.setFeaturesJson(featuresJson);
        p.setMaxStaffUsers(maxStaffUsers);
        p.setActive(true);
        repo.save(p);
        log.info("Seeded default subscription plan '{}'", code);
    }
}
