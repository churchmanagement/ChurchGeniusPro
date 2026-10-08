package com.churchgeniuspro.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ratchet against the cross-tenant IDOR class found by the September 2026 audit.
 *
 * <p>Every one of the ~40 HIGH findings had the same shape: a controller or service
 * loaded a row by bare id — {@code repo.findById(id)}, {@code deleteById},
 * {@code existsById}, {@code findAllById} — and acted on it without checking the
 * row's tenant. The fix pattern is a tenant-scoped finder
 * ({@code findByIdAndAppClientId…}) or an explicit {@code getClientId()} compare.
 *
 * <p>This test does not try to prove every remaining bare lookup is guarded (many
 * legitimately are — the id came from the session, or from a parent that was already
 * tenant-checked). It records how many bare lookups each file has TODAY and fails
 * when any file's count goes UP. To add one on purpose you must justify it here by
 * raising that file's number, which is exactly the review prompt this class of bug
 * needs. Lowering a number is always welcome.
 */
@DisplayName("Architecture — bare id-lookups must not increase")
class TenantScopedLookupRatchetTest {

    private static final Pattern BARE_LOOKUP = Pattern.compile(
            "\\b\\w*[Rr]epo\\w*\\.(findById|findByIdAndDeleteFlagFalse|findActiveById|findAllById"
          + "|deleteById|existsById|getReferenceById|findByIdWithMembers)\\s*\\(");

    /** Per-file ceiling, relative to src/main/java/com/churchgeniuspro. Generated 2026-09-10. */
    private static final Map<String, Integer> BASELINE = Map.ofEntries(
        entry("controller/AppUserController.java", 6),
        // userRepo.findById(appUserId): the id is the signed-in user's own, read from the
        // session (never from the request) — the same lookup login uses to load privileges.
        entry("service/PublicSubmissionNotificationService.java", 1),
        // userRepo.findById(appUserId) / memberRepo.findById(memberId): both ids come from
        // the signed-in user's OWN session; this only re-reads that user's saved
        // permission map (the same rows sign-in reads) to keep the session current.
        entry("service/PermissionRefresher.java", 2),
        // repo.findById(id) ×3 (find / detailForAdmin / setStatus): the SERVICE ADMIN paths,
        // which by design read tickets from every church — reachable only behind the
        // serviceAdminId session check. The church-side reads use findByIdAndClientId.
        entry("service/SupportTicketService.java", 3),
        // repo.findById(key) ×2 (get / set): platform_setting is keyed by a fixed setting
        // NAME (support_email, trial_request_token), not by an id from a request, and holds
        // platform-wide values — there is no tenant to scope by. Writes are Service Admin only.
        entry("service/PlatformSettingService.java", 2),
        // repo.findById(id) ×11 (approve / reject / disable / delete / backToPending, each read
        // before and after the atomic status transition) + linkRepo.findById(trialLinkId) ×1
        // (tenantOf — the request's own link): trial_request and trial_registration_link are
        // platform-level tables with no tenant — a request exists before any church account
        // does — and these paths are reached only from /api/serviceadmin/trial-requests
        // (Service Admin session required) or the per-request account-status check.
        entry("service/TrialRequestService.java", 12),
        // repo.findById(id) ×3 (requireOpenFor / decline / complete): subscription_request rows are
        // reached only from /api/serviceadmin/* (Service Admin session) and from the Service Admin
        // Convert action, which also checks that the request's client_id is the converted church.
        entry("service/SubscriptionRequestService.java", 3),
        entry("controller/EventRegisterPageController.java", 1),
        entry("controller/EventRegistrationReminderController.java", 1),
        entry("controller/EventVolunteerController.java", 3),
        entry("controller/GuessItController.java", 11),
        entry("controller/GuessItGroupController.java", 3),
        entry("controller/KidsMinistryController.java", 19),
        entry("controller/KmPickupPublicController.java", 4),
        entry("controller/LoginController.java", 6),
        entry("controller/MemberEventVolunteerController.java", 1),
        entry("controller/MembershipFormController.java", 34),
        entry("controller/MidRegMeetController.java", 1),
        entry("controller/PrayerRequestController.java", 15),
        entry("controller/PromiseVerseController.java", 2),
        entry("controller/PublicKidsCheckinController.java", 1),
        entry("controller/PublicScreensController.java", 1),
        entry("controller/ServiceAdminController.java", 1),
        entry("controller/SmsOptInController.java", 1),
        entry("controller/SubscriptionPlanAdminController.java", 1),
        entry("controller/SundaySchoolController.java", 69),
        entry("controller/VolunteerController.java", 12),
        entry("controller/WorshipPlanningController.java", 11),
        entry("payroll/controller/PayrollAdminController.java", 4),
        entry("payroll/controller/PayrollExportController.java", 2),
        entry("payroll/controller/PayrollPaystubController.java", 1),
        entry("payroll/controller/PayrollReportsController.java", 1),
        entry("payroll/controller/PayrollRunController.java", 1),
        entry("payroll/pdf/PaystubPdfService.java", 2),
        entry("payroll/service/PayrollReportingService.java", 1),
        entry("payroll/service/PayrollService.java", 3),
        entry("payroll/service/PayrollSetupService.java", 3),
        // employeeRepo.findById(id): the id comes from this service's own findAll() loop
        // (Service-Admin, all-tenant SSN key rotation), never from a request — same shape
        // as PlaidTokenRotationService below.
        entry("payroll/service/SsnRotationService.java", 1),
        entry("plaid/controller/PlaidEmailVerificationController.java", 2),
        entry("plaid/controller/PlaidItemDeleteController.java", 1),
        entry("plaid/controller/ServiceAdminPlaidController.java", 1),
        entry("plaid/service/BankSyncGateService.java", 2),
        entry("plaid/service/PlaidTokenRotationService.java", 1),
        entry("plaid/service/PlaidWebhookService.java", 1),
        entry("service/AppUserService.java", 6),
        entry("service/AttendanceService.java", 1),
        entry("service/BackupService.java", 1),
        entry("service/ChurchEventService.java", 5),
        entry("service/DemoAccessService.java", 5),
        entry("service/DemoReminderScheduler.java", 1),
        // Financial audit M9: 3 new lookups from the UPDATE/rollback-restore path
        // (incomeRepo/expenseRepo.findById(dedupeMatchId or targetId) and
        // memberRepo.findById(a before-image's memberId)) — all filtered by
        // tenant exactly like every other lookup already in this file
        // (.filter(x -> tenant.equals(x.getAppClientId())), or via
        // x.getFamily().getAppClientId() for a FamilyMember); the ratchet's
        // regex can't see the filter, only the findById.
        entry("service/EtlLoadService.java", 16),
        // Financial audit M9: subSourceById/purposeById/mainSourceById resolve
        // an ETL rollback's before-image FK ids back to live rows, each
        // filtered by tenant (repo.findById(id).filter(x ->
        // tenant.equals(x.getAppClientId()))) — same pattern as above, same
        // reason the regex still counts them.
        entry("service/EtlReferenceResolver.java", 3),
        entry("service/GuessItPlayService.java", 1),
        entry("service/KmPickupAlertService.java", 1),
        entry("service/MeetingOccurrenceService.java", 1),
        entry("service/MeetingService.java", 3),
        entry("service/NtagService.java", 3),
        entry("service/PrivateAccessService.java", 2),
        entry("service/ReminderSchedulerService.java", 8),
        entry("service/ReminderService.java", 1),
        // +1 (2026-10-05): extendTrial(id) — Service Admin → Registered Clients → Extend trial.
        // service_client IS the tenant table and is reached only from /api/serviceadmin/clients/*
        // (Service Admin session required), exactly like update/approve/reapprove/softDelete.
        // +1 (2026-10-05, Phase 3): convert(id) — Service Admin → Convert, same access as extendTrial.
        entry("service/ServiceClientService.java", 6),
        entry("service/TemporaryAccessService.java", 1),
        // requireDeletableRole loads a demo_role_access row by the id the Service
        // Admin screen showed, then checks the TENANT it belongs to is a trial one
        // before anything is deleted. There is no session tenant to scope by — a
        // service admin acts across tenants by definition — so the guard is that
        // check, not a scoped finder.
        entry("service/TrialDeletionService.java", 1),
        entry("service/TrialRegistrationLinkService.java", 1)
    );

    @Test
    void noFileGainsBareIdLookups() throws IOException {
        Path root = Paths.get("src/main/java/com/churchgeniuspro");
        assertThat(Files.isDirectory(root)).as("run from the project root").isTrue();

        Map<String, Integer> now = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (!rel.endsWith(".java")) continue;
                if (!(rel.contains("controller/") || rel.contains("service/") || rel.contains("plaid/")
                        || rel.contains("payroll/") || rel.contains("bankimport/"))) continue;
                Matcher m = BARE_LOOKUP.matcher(Files.readString(p));
                int n = 0;
                while (m.find()) n++;
                if (n > 0) now.put(rel, n);
            }
        }

        List<String> regressions = new ArrayList<>();
        now.forEach((file, n) -> {
            int allowed = BASELINE.getOrDefault(file, 0);
            if (n > allowed) regressions.add(file + ": " + n + " bare id-lookups (ceiling " + allowed + ")");
        });

        assertThat(regressions)
                .as("New bare id-lookups. Load the row with a tenant-scoped finder "
                  + "(findByIdAndAppClientId…) or compare getClientId() to the session tenant. "
                  + "If the id genuinely comes from the session or an already-checked parent, "
                  + "raise that file's ceiling in BASELINE with a comment saying why.")
                .isEmpty();
    }
}
