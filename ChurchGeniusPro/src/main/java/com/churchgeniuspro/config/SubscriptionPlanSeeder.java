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
            // Trial: every Pro feature for 30 days (end date auto-set at client
            // registration; expiry blocks all logins like any other plan).
            seed(repo, "TRIAL", "Trial Plan", 0,
                    "30-day free trial · All Pro Plan features · Unlimited emails & portals · "
                  + "100 SMS/month · Full AI integration · Advanced accounting",
                    1000, null, 100, 100, null, null,
                    "{}");

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

            seed(repo, "STANDARD", "Standard Plan", 2,
                    "Up to 100 people · 50 emails/month · 50 SMS/month · "
                  + "Staff, church & child portals · Standard accounting & online giving · "
                  + "Worship planning · Check-ins · Chat · Free migration · NTag login",
                    100, 50, 50, 20, 3, null,
                    // AI is "Limited" on Standard: search on, voice/converse/scan off.
                    "{\"aiVoice\":false,\"aiConverse\":false,\"scanCheck\":false}");

            seed(repo, "PRO", "Pro Plan", 3,
                    "Unlimited people & portals · Unlimited emails · Unlimited online giving · "
                  + "Advanced accounting · Full AI integration · 100 free SMS/month · "
                  + "Everything included in Standard Plan",
                    1000, null, 100, 100, null, null,
                    "{}");
        };
    }

    private void seed(SubscriptionPlanRepository repo, String code, String name, int sortOrder,
                      String description, Integer maxPeople, Integer maxEmails, Integer maxSms,
                      Integer maxGiving, Integer maxMemberPortals, Integer maxKidsPortals,
                      String featuresJson) {
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
        p.setActive(true);
        repo.save(p);
        log.info("Seeded default subscription plan '{}'", code);
    }
}
