package com.churchgeniuspro.subscription;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ratchet for "one source of truth for the trial duration": every flow that creates
 * a trial or a trial link must ask {@code TrialPolicy} (or use the length stored on
 * the link) rather than carry its own number. A literal day/week/month count passed to
 * plusDays/plusWeeks/plusMonths in any of these files is how the old hard-coded
 * 30-day trials crept in, so its reappearance fails the build.
 */
@DisplayName("Trial duration — no hard-coded lengths in trial-creating code")
class TrialDurationSingleSourceTest {

    private static final String BASE = "src/main/java/com/churchgeniuspro/";
    private static final List<String> TRIAL_PATHS = List.of(
            "service/TrialRegistrationService.java",
            "service/TrialRegistrationLinkService.java",
            "service/TrialTenantProvisioner.java",
            "service/ServiceClientService.java",
            "service/DemoAccessService.java",
            "controller/TrialRegistrationController.java",
            "controller/ServiceAdminTrialLinkController.java");

    private static final Pattern LITERAL_PERIOD =
            Pattern.compile("plus(Days|Weeks|Months)\\(\\s*\\d+L?\\s*\\)");

    @Test
    void noLiteralTrialLengths() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (String rel : TRIAL_PATHS) {
            Path p = Paths.get(BASE + rel);
            assertThat(p).as("trial path moved? update this list").exists();
            List<String> lines = Files.readAllLines(p);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = LITERAL_PERIOD.matcher(lines.get(i));
                if (m.find()) offenders.add(rel + ":" + (i + 1) + "  " + lines.get(i).trim());
            }
        }
        assertThat(offenders)
                .as("use TrialPolicy.trialDays() / the link's stored trialDays instead of a literal")
                .isEmpty();
    }
}
