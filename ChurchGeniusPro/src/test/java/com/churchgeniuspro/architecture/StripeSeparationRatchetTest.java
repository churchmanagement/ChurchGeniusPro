package com.churchgeniuspro.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two Stripe integrations that must never meet (Phase 6):
 * <ul>
 *   <li><b>Church donations</b> — each church's own Stripe keys in {@code stripe_settings}
 *       ({@code StripeSettings}, {@code StripeSettingsController}, {@code DonationController}).</li>
 *   <li><b>Platform billing</b> — the Service Admin's Stripe account, keys only in Azure
 *       settings ({@code PLATFORM_STRIPE_*}, {@code PlatformStripeService}).</li>
 * </ul>
 * The build fails if any source file references both sides, if the billing code touches
 * church Stripe settings, or if the donation code touches platform Stripe credentials.
 */
class StripeSeparationRatchetTest {

    static final List<String> CHURCH_STRIPE = List.of("StripeSettings", "stripeSettings", "stripe_settings", "StripeSettingsRepository");
    static final List<String> PLATFORM_STRIPE = List.of("PlatformStripeService", "PLATFORM_STRIPE", "platform.stripe", "BillingPaymentService");

    static final List<String> BILLING_FILES = List.of(
            "service/BillingService.java", "service/BillingPaymentService.java", "service/PlatformStripeService.java",
            "service/BillingReminderScheduler.java", "controller/InvoicePaymentController.java",
            "controller/ServiceAdminStripeController.java", "controller/ServiceAdminBillingController.java",
            "controller/InvoiceController.java");
    static final List<String> DONATION_FILES = List.of(
            "controller/DonationController.java", "controller/StripeSettingsController.java",
            "hibernate/StripeSettings.java", "repository/StripeSettingsRepository.java",
            "service/DonationIncomePostingService.java");

    static final Path ROOT = Paths.get("src/main/java/com/churchgeniuspro");

    @Test
    void billingNeverReadsChurchStripeSettings() throws IOException {
        for (String f : BILLING_FILES) {
            String src = Files.readString(ROOT.resolve(f));
            for (String t : CHURCH_STRIPE) assertThat(src).as(f + " must not reference " + t).doesNotContain(t);
        }
    }

    @Test
    void donationsNeverReadPlatformStripeCredentials() throws IOException {
        for (String f : DONATION_FILES) {
            String src = Files.readString(ROOT.resolve(f));
            for (String t : PLATFORM_STRIPE) assertThat(src).as(f + " must not reference " + t).doesNotContain(t);
        }
        String page = Files.readString(Paths.get("src/main/resources/static/stripeIntegration.html"));
        for (String t : PLATFORM_STRIPE) assertThat(page).as("stripeIntegration.html must not reference " + t).doesNotContain(t);
    }

    @Test
    void noSourceFileTouchesBothIntegrations() throws IOException {
        List<String> both = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (!p.toString().endsWith(".java")) continue;
                String src = Files.readString(p);
                boolean church = CHURCH_STRIPE.stream().anyMatch(src::contains);
                boolean platform = PLATFORM_STRIPE.stream().anyMatch(src::contains);
                if (church && platform) both.add(ROOT.relativize(p).toString());
            }
        }
        assertThat(both).as("files referencing both the church and the platform Stripe integration").isEmpty();
    }

    @Test
    void platformKeysComeOnlyFromAzureSettingsNotTheDatabase() throws IOException {
        String props = Files.readString(Paths.get("src/main/resources/application.properties"));
        assertThat(props).contains("platform.stripe.secret-key=${PLATFORM_STRIPE_SECRET_KEY:}")
                .contains("platform.stripe.webhook-secret=${PLATFORM_STRIPE_WEBHOOK_SECRET:}")
                .contains("platform.stripe.publishable-key=${PLATFORM_STRIPE_PUBLISHABLE_KEY:}");
        String svc = Files.readString(ROOT.resolve("service/PlatformStripeService.java"));
        assertThat(svc).doesNotContain("Repository").doesNotContain("PlatformSettingService").doesNotContain("JdbcTemplate");
    }
}
