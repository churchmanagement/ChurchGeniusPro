package com.churchgeniuspro.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ratchet: no NEW tenant-less {@code sendGenericEmail(to, subject, body)} calls.
 *
 * <p>The three-argument overload passes {@code clientId = null} into
 * {@code EmailService.doSend}, which means the Trial/Demo block, the recipient's
 * unsubscribe choice and the plan's monthly allowance are all skipped. That is
 * right for platform mail (OTP codes, registration, contact-form relay, the
 * expiry notice a Service Admin sends to a lapsing trial) and wrong for anything
 * a church sends to its people — which is how the Kids Ministry volunteer
 * broadcast came to bypass the trial rule (audit finding H3).
 *
 * <p>Every remaining call is pinned here with the reason it is platform mail. A
 * new one fails this test until it is either changed to {@code sendOrgEmail(…,
 * clientId)} or justified in {@link #BASELINE}.
 */
@DisplayName("Tenant-less email ratchet (H3)")
class TenantlessEmailRatchetTest {

    /** file → number of 3-arg sendGenericEmail calls that are known platform mail. */
    private static final Map<String, Integer> BASELINE = Map.ofEntries(
        // pre-login OTP / verification codes to the person completing the flow
        Map.entry("controller/MembershipFormController.java",   3),
        Map.entry("controller/TemporaryAccessController.java",  1),
        Map.entry("controller/SignupController.java",           1),
        Map.entry("controller/ChurchRegistrationController.java", 1),
        Map.entry("service/ChurchRegistrationService.java",     1),
        Map.entry("service/ServiceClientService.java",          2),
        // public contact form → the platform inbox
        Map.entry("controller/PublicWebController.java",        1),
        // a church's own backup archive, mailed to its own admin address
        Map.entry("service/BackupService.java",                 1),
        // platform notices ABOUT a tenant, not sent on its behalf
        Map.entry("service/DemoReminderScheduler.java",         1),
        Map.entry("service/SubscriptionExpiryNotifier.java",    1)
    );

    @Test
    @DisplayName("every tenant-less sendGenericEmail call is a known platform-mail site")
    void noNewTenantlessSends() throws IOException {
        Path root = Paths.get("src/main/java/com/churchgeniuspro");
        Map<String, Integer> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (rel.equals("service/EmailService.java")) continue;   // the definition itself
                int n = countThreeArgCalls(Files.readString(p, StandardCharsets.UTF_8));
                if (n > 0) found.put(rel, n);
            }
        }

        List<String> problems = new ArrayList<>();
        found.forEach((file, n) -> {
            int allowed = BASELINE.getOrDefault(file, 0);
            if (n > allowed) problems.add(file + ": " + n + " tenant-less sendGenericEmail call(s), baseline " + allowed);
        });
        assertThat(problems)
                .as("A church-context send must use sendOrgEmail(…, clientId) so the Trial/Demo block, "
                  + "unsubscribe list and monthly allowance apply. If this really is platform mail, "
                  + "add it to BASELINE with a comment saying why.")
                .isEmpty();
    }

    /** Counts {@code sendGenericEmail(} calls whose argument list has exactly three top-level arguments. */
    static int countThreeArgCalls(String src) {
        int count = 0, from = 0;
        while (true) {
            int i = src.indexOf("sendGenericEmail(", from);
            if (i < 0) return count;
            int open = i + "sendGenericEmail(".length();
            int depth = 1, commas = 0, j = open;
            boolean inStr = false;
            for (; j < src.length() && depth > 0; j++) {
                char c = src.charAt(j);
                if (inStr) { if (c == '\\') j++; else if (c == '"') inStr = false; continue; }
                if (c == '"') inStr = true;
                else if (c == '(') depth++;
                else if (c == ')') depth--;
                else if (c == ',' && depth == 1) commas++;
            }
            if (commas == 2) count++;
            from = j;
        }
    }
}
