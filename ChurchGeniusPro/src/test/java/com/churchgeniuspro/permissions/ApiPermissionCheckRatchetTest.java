package com.churchgeniuspro.permissions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architecture ratchet — permissions govern PAGES, MENUS and BUTTONS, not data APIs.
 *
 * <p>Decision of 2026-09-29: no {@code /api/**} endpoint gains a permission check.
 * The write-side checks that already exist stay because they exist; this test
 * records how many {@code requirePermission} / {@code requireMemberPermission}
 * calls each controller file has and fails when a file gains one. Page routes
 * (those that {@code forward:}) are expected to move to {@code requirePagePermission},
 * which is not counted, so converting a page route lowers a file's count — that is
 * fine and the baseline can then be lowered too.
 *
 * <p>To add a check deliberately (e.g. a new endpoint approved as an exception),
 * raise that file's ceiling here with a comment saying why.
 */
@DisplayName("Architecture — data APIs must not gain permission checks")
class ApiPermissionCheckRatchetTest {

    // requireFeature (2026-10-01) is the opt-in check for Ticketing and the AI
    // Assistant. It is counted here too, so adding it to any other API is caught.
    private static final Pattern CHECK = Pattern.compile(
            "\\bRoleGuard\\.(requirePermission|requireMemberPermission|requireFeature)\\s*\\(");

    /** Counts as of 2026-09-29 (Phase 1). A file absent here has a ceiling of 0. */
    // Ratcheted DOWN in Phase 2: every page route now uses requirePagePermission, so the
    // remaining counts are API/data checks (plus the two member.worship page checks in
    // HomeController that were deliberately left on requireMemberPermission).
    private static final Map<String, Integer> BASELINE = new TreeMap<>(Map.ofEntries(
        Map.entry("bankimport/BankImportController.java", 4),
        Map.entry("controller/AttendanceController.java", 1),
        Map.entry("controller/CheckScanController.java", 5),
        Map.entry("controller/ChurchEventController.java", 7),
        Map.entry("controller/EventCalendarController.java", 1),
        Map.entry("controller/EventEmailTemplateController.java", 1),
        Map.entry("controller/EventRegistrationReminderController.java", 3),
        Map.entry("controller/ExpenseCheckController.java", 4),
        Map.entry("controller/ExpenseController.java", 4),
        Map.entry("controller/FamilyController.java", 11),
        Map.entry("controller/FavoritesController.java", 1),
        Map.entry("controller/FilesController.java", 2),
        Map.entry("controller/GroupController.java", 3),
        Map.entry("controller/HomeController.java", 2),
        Map.entry("controller/IncomeController.java", 4),
        Map.entry("controller/MeetingController.java", 4),
        Map.entry("controller/MeetingTemplateController.java", 1),
        Map.entry("controller/MeetingTypeController.java", 1),
        Map.entry("controller/MembershipFormController.java", 4),
        Map.entry("controller/MembershipPrintController.java", 1),
        Map.entry("controller/NotifyEmailController.java", 2),
        Map.entry("controller/PledgeController.java", 6),
        Map.entry("controller/PrayerRequestController.java", 1),
        Map.entry("controller/PublicScreensController.java", 1),
        Map.entry("controller/ReminderApiController.java", 1),
        Map.entry("controller/SundaySchoolController.java", 1),
        Map.entry("controller/UnsubscribeController.java", 3),
        Map.entry("controller/WorshipPlanningController.java", 2),
        Map.entry("service/AiDataSearchService.java", 2),
        // ── Approved exception (user request, 2026-10-01): Ticketing and the AI Assistant
        // are to be enforced "on the backend/API as well, so hiding the menu item is not
        // the only protection". These are the only requireFeature call sites:
        Map.entry("controller/SupportTicketController.java", 3),   // /tickets page, /ai-assistant page, deny() for every /api/tickets/* call
        Map.entry("controller/AiSearchController.java", 1),        // POST /api/ai-search
        // ── Approved exception (user decision, 2026-10-06): POST /api/donations/review-status
        // changes the review status of financial donation records, so in addition to the
        // Accountant/Admin role and own-church scoping it requires the accounting.donation
        // permission — an Accountant/Admin with Donation switched off cannot call it
        // directly. This one write endpoint only; GET /api/donations and every other
        // donation API stay role-only per the 2026-09-29 rule (DonationController: 0).
        Map.entry("controller/DonationReviewController.java", 1),
        Map.entry("controller/AiDataSearchController.java", 1),    // POST /api/ai-search/data
        Map.entry("controller/VoiceCommandController.java", 1)     // POST /api/ai-assist
    ));

    @Test
    @DisplayName("no controller gains a requirePermission / requireMemberPermission call")
    void noFileGainsPermissionChecks() throws IOException {
        Path root = Paths.get("src/main/java/com/churchgeniuspro");
        Map<String, Integer> counts = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith("util/RoleGuard")) continue;          // the definitions
                Matcher m = CHECK.matcher(Files.readString(p));
                int n = 0; while (m.find()) n++;
                if (n > 0) counts.put(rel, n);
            }
        }
        List<String> over = new ArrayList<>();
        counts.forEach((file, n) -> {
            int allowed = BASELINE.getOrDefault(file, 0);
            if (n > allowed) over.add(file + ": " + n + " permission checks (ceiling " + allowed + ")");
        });
        assertThat(over)
            .as("Permissions control pages, menus and buttons — not data APIs. A page route should use "
              + "RoleGuard.requirePagePermission; an API endpoint should not gain a permission check. "
              + "If a new check is a deliberate, approved exception, raise that file's ceiling in BASELINE "
              + "with a comment saying why.")
            .isEmpty();
    }
}
