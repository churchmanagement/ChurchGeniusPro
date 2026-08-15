package com.churchgeniuspro.service;

import com.churchgeniuspro.common.States;
import com.churchgeniuspro.hibernate.AutoReminder;
import com.churchgeniuspro.util.EncryptionUtil;
import com.churchgeniuspro.hibernate.ChurchEvent;
import com.churchgeniuspro.hibernate.EventRegistration;
import com.churchgeniuspro.hibernate.EventReminder;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.Meeting;
import com.churchgeniuspro.hibernate.OneTimeReminder;
import com.churchgeniuspro.hibernate.PrayerRequest;
import com.churchgeniuspro.hibernate.PrayerSchedule;
import com.churchgeniuspro.hibernate.PrayerSection;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.AutoReminderRepository;
import com.churchgeniuspro.repository.FollowUpRepository;
import com.churchgeniuspro.repository.PrayerScheduleRepository;
import com.churchgeniuspro.repository.ChurchEventRepository;
import com.churchgeniuspro.repository.EventRegistrationRepository;
import com.churchgeniuspro.repository.EventReminderRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.FamilyRepository;
import com.churchgeniuspro.repository.GroupMemberRepository;
import com.churchgeniuspro.repository.MeetingRepository;
import com.churchgeniuspro.repository.OneTimeReminderRepository;
import com.churchgeniuspro.repository.PrayerRequestRepository;
import com.churchgeniuspro.repository.PrayerSectionRepository;
import com.churchgeniuspro.hibernate.WhatsAppSettings;
import com.churchgeniuspro.hibernate.PublicScreenLink;
import com.churchgeniuspro.repository.PublicScreenLinkRepository;
import com.churchgeniuspro.repository.WhatsAppSettingsRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Scheduled tasks that send reminder emails.
 *
 * <p>Reminder type IDs (from {@code auto_reminder_types}):
 * <ul>
 *   <li>1  — Meeting Reminder (3 h before)</li>
 *   <li>2  — Weekly Meeting Reminder (every Monday)</li>
 *   <li>3  — Birthday</li>
 *   <li>4  — Wedding Anniversary</li>
 *   <li>5  — Monthly Birthday &amp; Wedding</li>
 *   <li>6  — Monthly Statement</li>
 *   <li>7  — New Year</li>
 *   <li>8  — Christmas</li>
 *   <li>9  — US Independence Day</li>
 *   <li>10 — Thanksgiving</li>
 *   <li>11 — Veterans Day</li>
 *   <li>12 — Presidents Day</li>
 *   <li>13 — Memorial Day</li>
 *   <li>14 — Labor Day</li>
 * </ul>
 *
 * <p>Before sending any email the scheduler validates the client's subscription
 * via {@link AutoReminderRepository#findActiveClientIds()}.
 */
@Service
public class ReminderSchedulerService {

    // ── Type ID constants ──────────────────────────────────────────────────────
    private static final int TYPE_MEETING_REMINDER        = 1;
    private static final int TYPE_WEEKLY_MEETING_REMINDER = 2;
    private static final int TYPE_BIRTHDAY                = 3;
    private static final int TYPE_ANNIVERSARY             = 4;
    private static final int TYPE_MONTHLY_BD_ANNIVERSARY  = 5;
    private static final int TYPE_MONTHLY_STATEMENT       = 6;
    private static final int TYPE_NEW_YEAR                = 7;
    private static final int TYPE_CHRISTMAS               = 8;
    private static final int TYPE_INDEPENDENCE            = 9;
    private static final int TYPE_THANKSGIVING            = 10;
    private static final int TYPE_VETERANS                = 11;
    private static final int TYPE_PRESIDENTS              = 12;
    private static final int TYPE_MEMORIAL                = 13;
    private static final int TYPE_LABOR                   = 14;
    private static final int TYPE_PRAYER_REQUEST           = 15;
    private static final int TYPE_CELEBRANTS_MEMBERS       = 16;

    /** Scheduler-wide evaluation zone — must match the @Scheduled(zone=...) crons. */
    private static final java.time.ZoneId SCHED_ZONE = java.time.ZoneId.of("America/Chicago");

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(ReminderSchedulerService.class);

    @Value("${app.base-url}")
    private String baseUrl;

    private final AutoReminderRepository      autoReminderRepo;
    private final OneTimeReminderRepository   oneTimeReminderRepo;
    private final EventReminderRepository     eventReminderRepo;
    private final ChurchEventRepository       eventRepo;
    private final EventRegistrationRepository registrationRepo;
    private final MeetingRepository           meetingRepo;
    private final FamilyMemberRepository      memberRepo;
    private final GroupMemberRepository       groupMemberRepo;
    private final FamilyRepository            familyRepo;
    private final AppUserRepository           userRepo;
    private final EmailService                emailService;
    private final WhatsAppSenderService       whatsAppSender;
    private final WhatsAppSettingsRepository  whatsAppSettingsRepo;
    private final WebPushService              webPushService;
    private final PrayerRequestRepository     prayerRequestRepo;
    private final PrayerSectionRepository     prayerSectionRepo;
    private final PrayerScheduleRepository    prayerScheduleRepo;
    private final FollowUpRepository          followUpRepo;
    private final com.churchgeniuspro.repository.ReminderSentLogRepository sentLogRepo;
    private final PublicScreenLinkRepository publicScreenLinkRepo;
    private final com.churchgeniuspro.repository.MeetingSkipDateRepository meetingSkipRepo;

    public ReminderSchedulerService(AutoReminderRepository      autoReminderRepo,
                                    OneTimeReminderRepository   oneTimeReminderRepo,
                                    EventReminderRepository     eventReminderRepo,
                                    ChurchEventRepository       eventRepo,
                                    EventRegistrationRepository registrationRepo,
                                    MeetingRepository           meetingRepo,
                                    FamilyMemberRepository      memberRepo,
                                    GroupMemberRepository       groupMemberRepo,
                                    FamilyRepository            familyRepo,
                                    AppUserRepository           userRepo,
                                    EmailService                emailService,
                                    WhatsAppSenderService       whatsAppSender,
                                    WhatsAppSettingsRepository  whatsAppSettingsRepo,
                                    WebPushService              webPushService,
                                    PrayerRequestRepository     prayerRequestRepo,
                                    PrayerSectionRepository     prayerSectionRepo,
                                    PrayerScheduleRepository    prayerScheduleRepo,
                                    FollowUpRepository          followUpRepo,
                                    com.churchgeniuspro.repository.ReminderSentLogRepository sentLogRepo,
                                    PublicScreenLinkRepository  publicScreenLinkRepo,
                                    com.churchgeniuspro.repository.MeetingSkipDateRepository meetingSkipRepo) {
        this.autoReminderRepo    = autoReminderRepo;
        this.oneTimeReminderRepo = oneTimeReminderRepo;
        this.eventReminderRepo   = eventReminderRepo;
        this.eventRepo           = eventRepo;
        this.registrationRepo    = registrationRepo;
        this.meetingRepo         = meetingRepo;
        this.memberRepo          = memberRepo;
        this.groupMemberRepo     = groupMemberRepo;
        this.familyRepo          = familyRepo;
        this.userRepo            = userRepo;
        this.emailService        = emailService;
        this.whatsAppSender      = whatsAppSender;
        this.whatsAppSettingsRepo = whatsAppSettingsRepo;
        this.webPushService      = webPushService;
        this.prayerRequestRepo   = prayerRequestRepo;
        this.prayerSectionRepo   = prayerSectionRepo;
        this.prayerScheduleRepo  = prayerScheduleRepo;
        this.followUpRepo        = followUpRepo;
        this.sentLogRepo         = sentLogRepo;
        this.publicScreenLinkRepo = publicScreenLinkRepo;
        this.meetingSkipRepo     = meetingSkipRepo;
    }

    /**
     * True when the given single occurrence of a meeting was deleted by the
     * user ({@code meeting_skip_date}) — no reminder of ANY kind (email, SMS,
     * WhatsApp, push, weekly digest) may be sent for that date.
     */
    private boolean isOccurrenceSkipped(Meeting m, LocalDate date) {
        try {
            return meetingSkipRepo.existsByMeetingIdAndSkipDate(m.getId(), date);
        } catch (Exception e) {
            return false;   // fail open — better a reminder than a crash
        }
    }

    /**
     * First date in [weekStart, weekEnd] on which the meeting actually occurs
     * and is not skipped; {@code null} when every occurrence that week was
     * deleted (or none exists).
     */
    private LocalDate firstActiveOccurrenceInWeek(Meeting m, LocalDate weekStart, LocalDate weekEnd) {
        for (LocalDate d = weekStart; !d.isAfter(weekEnd); d = d.plusDays(1)) {
            if (meetingOccursToday(m, d) && !isOccurrenceSkipped(m, d)) return d;
        }
        return null;
    }

    // =========================================================================
    // Client validation — run once per scheduler invocation and cache for call
    // =========================================================================

    /** Fetches valid (active-subscription) org client IDs from the DB. */
    private Set<String> loadActiveClientIds() {
        return Set.copyOf(autoReminderRepo.findActiveClientIds());
    }

    /** Returns {@code true} if the given {@code appClientId} has an active subscription. */
    private boolean isActiveClient(String appClientId, Set<String> activeIds) {
        return appClientId != null && activeIds.contains(appClientId);
    }

    // =========================================================================
    // Birthday — daily at 07:50 (type 3)
    // =========================================================================

    @Scheduled(cron = "${scheduler.job.birthday-reminders}", zone = "America/Chicago")
    public void runBirthdayReminders() {
        Set<String> activeClients = loadActiveClientIds();
        LocalDate today = LocalDate.now();
        int month = today.getMonthValue();
        int day   = today.getDayOfMonth();

        for (AutoReminder reminder : autoReminderRepo.findByReminderTypeIdAndDisabledFalse(TYPE_BIRTHDAY)) {
            String clientId = reminder.getAppClientId();
            if (!isActiveClient(clientId, activeClients)) continue;

            List<String>       rcpList   = parseRecipients(reminder.getRecipients());
            // Use the no-email-filter query so that celebrants without an email are
            // still included — members/guests should be notified regardless of
            // whether the celebrant has an email address on file.
            List<FamilyMember> celebrants = memberRepo.findByBirthdayToday(month, day, clientId);

            for (FamilyMember celebrant : celebrants) {
                // \u2500\u2500 Dedup guard \u2014 skip if already sent today for this celebrant \u2500\u2500
                String bdRefKey = clientId + "_" + celebrant.getId();
                if (alreadySent(clientId, "BIRTHDAY", bdRefKey, today)) continue;
                if (!markSent(clientId, "BIRTHDAY", bdRefKey, today)) continue;

                String fullName    = fullNameWithNick(celebrant);
                String bdSubject   = "\uD83C\uDF82 Birthday Wishes for " + fullName;
                String celebrantMsg = "Happy Birthday, " + firstNameWithNick(celebrant) + "! \uD83C\uDF82";

                // Email/SMS the celebrant directly when "Celebrant" is selected, or
                // "Celebrants (Members Only)" is selected and the celebrant is a Member.
                boolean emailCelebrant = rcpList.contains("Celebrant")
                        || (rcpList.contains("CelebrantMembers") && isMemberType(celebrant));

                // ── Email channel ──────────────────────────────────────────────
                if (!Boolean.FALSE.equals(reminder.getSendEmail())) {
                    // Only email the celebrant directly if they have an email address.
                    if (emailCelebrant && hasEmail(celebrant)) {
                        String celebrantBody = hasTemplate(reminder.getEmailTemplate())
                                ? renderCelebrantEmail(reminder.getEmailTemplate(), celebrant.getFirstName(), celebrantMsg)
                                : buildBirthdayCelebrantBody(celebrant);
                        emailService.sendOrgEmail(
                                celebrant.getEmail(),
                                celebrantMsg,
                                celebrantBody,
                                clientId);
                    }
                    // Audience (Members + Guests + Visitors + selected Groups),
                    // de-duplicated by email so a recipient who belongs to more than
                    // one category/group receives the announcement only ONCE.
                    // The celebrant is excluded here — they receive the personalised
                    // celebrant email above instead of a duplicate audience copy.
                    String bdAudienceBody = buildBirthdayAudienceBody(celebrant);
                    String bdCelebrantEmail = hasEmail(celebrant)
                            ? celebrant.getEmail().trim().toLowerCase() : null;
                    for (String email : collectMembersAndGuests(clientId, rcpList)) {
                        if (bdCelebrantEmail != null && email.equals(bdCelebrantEmail)) continue;
                        emailService.sendOrgEmail(email, bdSubject, bdAudienceBody, clientId);
                    }
                }

                // ── WhatsApp / SMS channels (deduplicated per number) ──────────
                boolean doWa  = Boolean.TRUE.equals(reminder.getSendWhatsApp());
                boolean doSms = Boolean.TRUE.equals(reminder.getSendSms());
                whatsAppSender.sendToRecipientsMultiChannel(
                        reminder.getRecipients(), bdSubject, clientId, doWa, doSms);
                if (emailCelebrant && celebrant.getPhone() != null && !celebrant.getPhone().isBlank()) {
                    whatsAppSender.sendToPhoneMultiChannel(
                            celebrant.getPhone(), celebrantMsg, clientId, doWa, doSms);
                }

                // ── Push notification channel ──────────────────────────────────
                webPushService.logAndSendToOrg(clientId,
                        "🎂 Birthday: " + fullName(celebrant),
                        fullName(celebrant) + " has a birthday today — send your wishes!",
                        "/home", "cgp-birthday");
            }
        }
    }

    // =========================================================================
    // Wedding Anniversary — daily at 08:00 (type 4)
    // =========================================================================

    @Scheduled(cron = "${scheduler.job.anniversary-reminders}", zone = "America/Chicago")
    public void runAnniversaryReminders() {
        Set<String> activeClients = loadActiveClientIds();
        LocalDate today = LocalDate.now();
        int month = today.getMonthValue();
        int day   = today.getDayOfMonth();

        for (AutoReminder reminder : autoReminderRepo.findByReminderTypeIdAndDisabledFalse(TYPE_ANNIVERSARY)) {
            String       clientId = reminder.getAppClientId();
            if (!isActiveClient(clientId, activeClients)) continue;
            List<String> rcpList = parseRecipients(reminder.getRecipients());

            // Find any member (regardless of role) whose anniversary falls today for this org.
            // The anniversary may be stored on the Spouse, the Head of Household, or both.
            List<FamilyMember> membersToday =
                    memberRepo.findByAnniversaryToday(month, day, clientId);

            // De-duplicate by family — process each family at most once.
            Set<Integer> processedFamilies = new java.util.LinkedHashSet<>();
            for (FamilyMember anchor : membersToday) {
                if (anchor.getFamily() == null) continue;
                Integer familyId = anchor.getFamily().getId();
                if (!processedFamilies.add(familyId)) continue;   // already handled

                // ── Dedup guard — skip if already sent today for this family ──
                String anniRefKey = clientId + "_" + familyId;
                if (alreadySent(clientId, "ANNIVERSARY", anniRefKey, today)) continue;
                if (!markSent(clientId, "ANNIVERSARY", anniRefKey, today)) continue;

                // Load all active members of this family to identify the couple.
                List<FamilyMember> familyMembers = memberRepo.findActiveMembersByFamilyId(familyId);

                // Primary = "Head of Household" (or any role starting with "Head")
                FamilyMember primary = familyMembers.stream()
                        .filter(m -> m.getRole() != null &&
                                     m.getRole().toLowerCase().startsWith("head"))
                        .findFirst().orElse(null);

                // Spouse = "Spouse" or "Wife" (case-insensitive)
                FamilyMember spouse = familyMembers.stream()
                        .filter(m -> m.getRole() != null &&
                                     (m.getRole().equalsIgnoreCase("Spouse") ||
                                      m.getRole().equalsIgnoreCase("Wife")))
                        .findFirst().orElse(null);

                // Fall back: if no explicit primary was found, use the anchor member itself.
                if (primary == null) primary = anchor;

                // Build display name with nicknames: "Anson (Dany) and Christina (Chris)"
                String coupleNames;
                if (spouse != null) {
                    coupleNames = esc(firstNameWithNick(primary)) + " and "
                                + esc(firstNameWithNick(spouse));
                } else {
                    coupleNames = esc(firstNameWithNick(primary));
                }

                String subject = "\uD83D\uDC8D Happy Anniversary, " + coupleNames + "!";

                // Celebrant list: primary + spouse (whoever has an email)
                List<FamilyMember> couple = new ArrayList<>();
                couple.add(primary);
                if (spouse != null) couple.add(spouse);

                // Resolve the wedding year: prefer primary's anniversaryYear, fall back to spouse's
                Integer anniversaryYear = primary.getAnniversaryYear();
                if (anniversaryYear == null && spouse != null) {
                    anniversaryYear = spouse.getAnniversaryYear();
                }

                // ── Email channel ──────────────────────────────────────────────
                if (!Boolean.FALSE.equals(reminder.getSendEmail())) {
                    if (rcpList.contains("Celebrant") || rcpList.contains("CelebrantMembers")) {
                        for (FamilyMember m : couple) {
                            // Include this couple member when "Celebrant" is selected,
                            // or "Celebrants (Members Only)" is selected and they are a Member.
                            boolean includeM = rcpList.contains("Celebrant")
                                    || (rcpList.contains("CelebrantMembers") && isMemberType(m));
                            if (includeM && hasEmail(m)) {
                                String body = hasTemplate(reminder.getEmailTemplate())
                                        ? renderCelebrantEmail(reminder.getEmailTemplate(), m.getFirstName(), subject)
                                        : buildAnniversaryCelebrantBody(coupleNames, anniversaryYear);
                                emailService.sendOrgEmail(m.getEmail(), subject, body, clientId);
                            }
                        }
                    }
                    // Audience (Members + Guests + Visitors + selected Groups),
                    // de-duplicated by email so a recipient in more than one
                    // category/group receives the announcement only ONCE.  The
                    // couple is excluded here — they receive the personalised
                    // celebrant email above instead of a duplicate audience copy.
                    String anniAudienceBody = buildAnniversaryAudienceBody(coupleNames, anniversaryYear);
                    java.util.Set<String> coupleEmails = new java.util.HashSet<>();
                    for (FamilyMember m : couple) {
                        if (hasEmail(m)) coupleEmails.add(m.getEmail().trim().toLowerCase());
                    }
                    for (String email : collectMembersAndGuests(clientId, rcpList)) {
                        if (coupleEmails.contains(email)) continue;
                        emailService.sendOrgEmail(email, subject, anniAudienceBody, clientId);
                    }
                }

                // ── WhatsApp / SMS channels (deduplicated per number) ──────
                boolean doWaAnni  = Boolean.TRUE.equals(reminder.getSendWhatsApp());
                boolean doSmsAnni = Boolean.TRUE.equals(reminder.getSendSms());
                whatsAppSender.sendToRecipientsMultiChannel(
                        reminder.getRecipients(), subject, clientId, doWaAnni, doSmsAnni);
                if (rcpList.contains("Celebrant") || rcpList.contains("CelebrantMembers")) {
                    for (FamilyMember m : couple) {
                        boolean includeM = rcpList.contains("Celebrant")
                                || (rcpList.contains("CelebrantMembers") && isMemberType(m));
                        if (includeM && m.getPhone() != null && !m.getPhone().isBlank()) {
                            whatsAppSender.sendToPhoneMultiChannel(m.getPhone(),
                                    "\uD83D\uDC8D Happy Anniversary, " + coupleNames + "!",
                                    clientId, doWaAnni, doSmsAnni);
                        }
                    }
                }

                // ── Push notification channel ──────────────────────────────────────────────
                webPushService.logAndSendToOrg(clientId,
                        "💍 Anniversary: " + coupleNames,
                        coupleNames + " are celebrating their anniversary today!",
                        "/home", "cgp-anniversary");
            }
        }
    }

    // =========================================================================
    // Celebrants (Members Only) — daily (type 16)
    // Sends a personalised birthday or wedding-anniversary greeting ONLY to
    // celebrants whose memberType is "Member" — Guests and Visitors are excluded.
    // =========================================================================

    @Scheduled(cron = "${scheduler.job.celebrants-members-reminders}", zone = "America/Chicago")
    public void runCelebrantsMembersReminders() {
        Set<String> activeClients = loadActiveClientIds();
        LocalDate today = LocalDate.now();
        int month = today.getMonthValue();
        int day   = today.getDayOfMonth();

        for (AutoReminder reminder : autoReminderRepo.findByReminderTypeIdAndDisabledFalse(TYPE_CELEBRANTS_MEMBERS)) {
            String clientId = reminder.getAppClientId();
            if (!isActiveClient(clientId, activeClients)) continue;

            boolean doEmail = !Boolean.FALSE.equals(reminder.getSendEmail());
            boolean doWa    = Boolean.TRUE.equals(reminder.getSendWhatsApp());
            boolean doSms   = Boolean.TRUE.equals(reminder.getSendSms());

            // ── Birthdays — members only ──────────────────────────────────────
            for (FamilyMember celebrant : memberRepo.findByBirthdayToday(month, day, clientId)) {
                if (!isMemberType(celebrant)) continue;   // exclude Guests/Visitors

                String refKey = clientId + "_" + celebrant.getId();
                if (alreadySent(clientId, "CELEBRANT_MEMBER_BD", refKey, today)) continue;
                if (!markSent(clientId, "CELEBRANT_MEMBER_BD", refKey, today)) continue;

                String subject = "🎂 Happy Birthday, " + firstNameWithNick(celebrant) + "!";
                if (doEmail && hasEmail(celebrant)) {
                    emailService.sendOrgEmail(celebrant.getEmail(), subject,
                            buildBirthdayCelebrantBody(celebrant), clientId);
                }
                if ((doWa || doSms) && celebrant.getPhone() != null && !celebrant.getPhone().isBlank()) {
                    whatsAppSender.sendToPhoneMultiChannel(celebrant.getPhone(), subject, clientId, doWa, doSms);
                }
            }

            // ── Wedding anniversaries — members only ──────────────────────────
            for (FamilyMember celebrant : memberRepo.findByAnniversaryToday(month, day, clientId)) {
                if (!isMemberType(celebrant)) continue;   // exclude Guests/Visitors

                String refKey = clientId + "_" + celebrant.getId();
                if (alreadySent(clientId, "CELEBRANT_MEMBER_ANNI", refKey, today)) continue;
                if (!markSent(clientId, "CELEBRANT_MEMBER_ANNI", refKey, today)) continue;

                String subject = "💍 Happy Anniversary, " + firstNameWithNick(celebrant) + "!";
                if (doEmail && hasEmail(celebrant)) {
                    emailService.sendOrgEmail(celebrant.getEmail(), subject,
                            buildAnniversaryCelebrantBody(esc(firstNameWithNick(celebrant)), celebrant.getAnniversaryYear()),
                            clientId);
                }
                if ((doWa || doSms) && celebrant.getPhone() != null && !celebrant.getPhone().isBlank()) {
                    whatsAppSender.sendToPhoneMultiChannel(celebrant.getPhone(), subject, clientId, doWa, doSms);
                }
            }
        }
    }

    /** {@code true} when the family member's type is "Member" (excludes Guests/Visitors). */
    private boolean isMemberType(FamilyMember m) {
        return m.getMemberType() != null && m.getMemberType().trim().equalsIgnoreCase("Member");
    }

    // =========================================================================
    // Monthly: Birthdays & Anniversary summary + Monthly Statement — 1st of month at 07:50
    // =========================================================================

    @Scheduled(cron = "${scheduler.job.monthly-reminders}", zone = "America/Chicago")
    public void runMonthlyReminders() {
        runBirthdaysAndAnniversarySummary();
        runMonthlyStatementReminders();
    }

    private void runBirthdaysAndAnniversarySummary() {
        Set<String> activeClients = loadActiveClientIds();
        LocalDate today   = LocalDate.now();
        int       month   = today.getMonthValue();
        String    monthName = today.getMonth().getDisplayName(
                java.time.format.TextStyle.FULL, java.util.Locale.US);
        int       daysInMonth = today.getMonth().maxLength();

        for (AutoReminder reminder : autoReminderRepo.findByReminderTypeIdAndDisabledFalse(TYPE_MONTHLY_BD_ANNIVERSARY)) {
            String clientId = reminder.getAppClientId();
            if (!isActiveClient(clientId, activeClients)) continue;

            List<String> rcpList = parseRecipients(reminder.getRecipients());

            List<FamilyMember> birthdayMembers = new ArrayList<>();
            List<FamilyMember> anniversaryMembers = new ArrayList<>();
            // The monthly summary LISTS everyone celebrating this month (including the
            // 1st) — the celebrant's own email is irrelevant for being listed, so use
            // the non-email-filtered queries. (The WithEmail variants dropped anyone
            // without an email, e.g. a birthday on the 1st.)
            for (int d = 1; d <= daysInMonth; d++) {
                birthdayMembers.addAll(memberRepo.findByBirthdayToday(month, d, clientId));
                anniversaryMembers.addAll(memberRepo.findByAnniversaryToday(month, d, clientId));
            }

            String subject = "\uD83C\uDF89 " + monthName + " Birthdays & Anniversaries";
            String body    = buildMonthlySummaryBody(monthName, birthdayMembers, anniversaryMembers);

            if (!Boolean.FALSE.equals(reminder.getSendEmail())) {
                collectMembersAndGuests(clientId, rcpList)
                        .forEach(email -> emailService.sendOrgEmail(email, subject, body, clientId));
            }
            whatsAppSender.sendToRecipientsMultiChannel(
                    reminder.getRecipients(), subject, clientId,
                    Boolean.TRUE.equals(reminder.getSendWhatsApp()),
                    Boolean.TRUE.equals(reminder.getSendSms()));
        }
    }

    private void runMonthlyStatementReminders() {
        Set<String> activeClients = loadActiveClientIds();

        for (AutoReminder reminder : autoReminderRepo.findByReminderTypeIdAndDisabledFalse(TYPE_MONTHLY_STATEMENT)) {
            String clientId = reminder.getAppClientId();
            if (!isActiveClient(clientId, activeClients)) continue;

            userRepo.findByClientIdAndDeleteFlagFalseOrderByLastNameAscFirstNameAsc(clientId)
                    .stream()
                    .filter(u -> "Accountant".equalsIgnoreCase(u.getRole()) && u.isEnabled())
                    .forEach(u -> emailService.sendOrgEmail(
                            u.getEmail(),
                            "\uD83D\uDCC4 Monthly Statement Reminder",
                            buildMonthlyStatementBody(u.getFirstName()),
                            clientId));
        }
    }

    // =========================================================================
    // Fixed-date holiday reminders
    // =========================================================================

    /** New Year — 1 Jan at 00:50 (type 7) */
    @Scheduled(cron = "${scheduler.job.new-year-reminders}", zone = "America/Chicago")
    public void runNewYearReminders() {
        sendHolidayReminder(TYPE_NEW_YEAR, "\uD83C\uDF86 Happy New Year!", "\uD83C\uDF86 Happy New Year!");
    }

    /** Christmas — 25 Dec at 09:50 (type 8) */
    @Scheduled(cron = "${scheduler.job.christmas-reminders}", zone = "America/Chicago")
    public void runChristmasReminders() {
        sendHolidayReminder(TYPE_CHRISTMAS, "\uD83C\uDF84 Merry Christmas!", "\uD83C\uDF84 Merry Christmas!");
    }

    /** US Independence Day — 4 Jul at 09:50 (type 9) */
    @Scheduled(cron = "${scheduler.job.independence-day-reminders}", zone = "America/Chicago")
    public void runIndependenceDayReminders() {
        sendHolidayReminder(TYPE_INDEPENDENCE,
                "\uD83C\uDDFA\uD83C\uDDF8 Happy 4th of July!",
                "\uD83C\uDDFA\uD83C\uDDF8 Happy Independence Day!");
    }

    // =========================================================================
    // Hourly at :30 — Event, Meeting (type 1), One-time, dynamic holidays
    // =========================================================================

    @Scheduled(cron = "${scheduler.job.half-hourly-reminders}", zone = "America/Chicago")
    public void runHalfHourlyReminders() {
        LocalDate today = LocalDate.now();
        runEventReminders(today);
        runMeetingReminders(today);        // type 1
        runDynamicHolidayReminders(today);
        runOneTimeReminders(today);
        runPrayerRequestReminders(today);  // type 15
    }

    // =========================================================================
    // Prayer Request Reminders — daily check (type 15)
    // =========================================================================

    /**
     * Sends prayer request reminder emails using the org's <em>global</em> prayer schedule
     * (stored in {@code prayer_schedule}) rather than per-request schedule fields.
     *
     * <ul>
     *   <li><b>One-time / Daily</b> — fires every day the scheduler runs.</li>
     *   <li><b>Weekly</b>  — fires only on the configured days-of-week (0=Sun…6=Sat).</li>
     *   <li><b>Monthly</b> — fires on the configured months, and either a fixed day-of-month
     *       or a week-ordinal pattern (e.g. 2nd Monday).</li>
     * </ul>
     *
     * <p>All active, non-deleted prayer requests for the org are included in a single digest email.</p>
     */
    private void runPrayerRequestReminders(LocalDate ignoredToday) {
        // Evaluate the schedule in the scheduler's own zone (America/Chicago) so the
        // day-of-week / dedup checks agree with the @Scheduled(zone=...) and send-hour
        // checks. Using the JVM-default LocalDate.now() here was a bug: on a UTC server,
        // 6 PM Central Tuesday is already Wednesday in UTC, so the Weekly day-of-week
        // match would fail and nothing would send.
        LocalDate today = LocalDate.now(SCHED_ZONE);

        Set<String> activeClients = loadActiveClientIds();

        // Optional per-org reminder rows (type 15) only carry channel + recipient
        // OVERRIDES. The Prayer Request page never creates one, so the prayer digest
        // must NOT depend on its existence — it is keyed off prayer_schedule instead.
        Map<String, AutoReminder> reminderByClient = new java.util.HashMap<>();
        for (AutoReminder r : autoReminderRepo.findByReminderTypeIdAndDisabledFalse(TYPE_PRAYER_REQUEST)) {
            if (r.getAppClientId() != null) reminderByClient.putIfAbsent(r.getAppClientId(), r);
        }

        // Drive off the per-org schedule configured on /prayerRequest.
        for (PrayerSchedule schedule : prayerScheduleRepo.findAll()) {
            String clientId = schedule.getClientId();
            try {
                if (!isActiveClient(clientId, activeClients)) continue;
                if (schedule.getOccurrence() == null || schedule.getOccurrence().isBlank()) continue;

                // 1. Does today match the configured occurrence?
                if (!isPrayerScheduledToday(schedule, today)) continue;

                // 2. Send-hour gate (America/Chicago). The half-hourly cron ticks at HH:30,
                //    so a 6 PM setting fires during the 18:00 hour.
                if (schedule.getSendHour() != null) {
                    int currentHour = java.time.LocalTime.now(SCHED_ZONE).getHour();
                    if (currentHour != schedule.getSendHour()) continue;
                }

                // 3. Deduplication guard — skip if already sent today for this org.
                if (schedule.getLastSentDate() != null) {
                    LocalDate lastSent = schedule.getLastSentDate()
                            .toInstant().atZone(SCHED_ZONE).toLocalDate();
                    if (lastSent.equals(today)) continue;
                }

                // 4. Gather all active, non-deleted prayer requests for the digest.
                List<PrayerRequest> allRequests =
                        prayerRequestRepo.findByClientIdAndDeleteFlagFalseOrderByCreatedAtAsc(clientId)
                                .stream()
                                .filter(r -> "Active".equals(r.getStatus()))
                                .collect(java.util.stream.Collectors.toList());
                if (allRequests.isEmpty()) {
                    LOGGER.info("[PrayerReminder] {} scheduled today but has no active prayer requests — skipping.", clientId);
                    continue;
                }

                // 5. Section map for grouping.
                List<PrayerSection> allSections =
                        prayerSectionRepo.findByClientIdAndDeleteFlagFalseOrderByCreatedAtAsc(clientId);
                Map<Long, String> sectionNames = new java.util.LinkedHashMap<>();
                allSections.forEach(s -> sectionNames.put(s.getId(), s.getName()));

                String subject = "🙏 Prayer Requests";
                String html    = buildPrayerReminderEmail(allRequests, sectionNames,
                                                          emailService.getChurchName(clientId));

                // 6. Recipients — default to ALL MEMBERS. An admin reminder row may
                //    override the recipient set (e.g. add Guests/Visitors/Groups).
                AutoReminder reminder = reminderByClient.get(clientId);
                String recipientsCsv = (reminder != null && reminder.getRecipients() != null
                                        && !reminder.getRecipients().isBlank())
                                       ? reminder.getRecipients() : "Members";
                List<String> rcpList = parseRecipients(recipientsCsv);
                if (rcpList.isEmpty()) rcpList = parseRecipients("Members");

                boolean sendEmail = (reminder == null) || !Boolean.FALSE.equals(reminder.getSendEmail());
                int sent = 0, failed = 0;
                if (sendEmail) {
                    List<String> emails = collectMembersAndGuests(clientId, rcpList);
                    for (String email : emails) {
                        try { emailService.sendOrgEmail(email, subject, html, clientId); sent++; }
                        catch (Exception ex) {
                            failed++;
                            LOGGER.warn("[PrayerReminder] email failed to {} ({}): {}", email, clientId, ex.toString());
                        }
                    }
                    if (emails.isEmpty())
                        LOGGER.warn("[PrayerReminder] {} scheduled today but resolved 0 member recipients (check member emails / recipient settings).", clientId);
                }
                LOGGER.info("[PrayerReminder] {} digest on {}: {} sent, {} failed, {} active requests.",
                        clientId, today, sent, failed, allRequests.size());

                // 7. SMS / WhatsApp only when an admin reminder explicitly enables them.
                if (reminder != null
                        && (Boolean.TRUE.equals(reminder.getSendSms()) || Boolean.TRUE.equals(reminder.getSendWhatsApp()))) {
                    String smsMessage = buildPrayerSmsMessage(allRequests, clientId);
                    whatsAppSender.sendToRecipientsMultiChannel(
                            recipientsCsv, smsMessage, clientId,
                            Boolean.TRUE.equals(reminder.getSendWhatsApp()),
                            Boolean.TRUE.equals(reminder.getSendSms()));
                }

                // 8. Stamp last-sent date so subsequent half-hourly runs are skipped today.
                schedule.setLastSentDate(java.sql.Date.valueOf(today));
                prayerScheduleRepo.save(schedule);

            } catch (Exception ex) {
                LOGGER.error("[PrayerReminder] failed for client {}: {}", clientId, ex.toString(), ex);
            }
        }
    }

    /**
     * Returns {@code true} if the org's global {@link PrayerSchedule} fires on {@code today}.
     *
     * <ul>
     *   <li><b>One-time / Daily</b> — always {@code true}.</li>
     *   <li><b>Weekly</b>  — {@code true} when today's day-of-week is in {@code weekDays}
     *       (0=Sun … 6=Sat).  Empty list = fire every day.</li>
     *   <li><b>Monthly</b> — {@code true} when:
     *       <ol>
     *         <li>today's month is in {@code monthMonths} (empty = any month), AND</li>
     *         <li>either today matches the fixed {@code monthDayOfMonth}, OR
     *             today matches the week-ordinal+day-of-week pattern, OR
     *             neither day field is set (fires every day in matching months).</li>
     *       </ol>
     *   </li>
     * </ul>
     */
    private boolean isPrayerScheduledToday(PrayerSchedule s, LocalDate today) {
        String occ = s.getOccurrence();
        if (occ == null || occ.isBlank()) return false;

        switch (occ) {
            case "One-time":
            case "Daily":
                return true;

            case "Weekly": {
                int todayDow = today.getDayOfWeek().getValue() % 7; // Mon=1…Sun=7 → Sun=0
                int[] days = stringToIntsScheduler(s.getWeekDays());
                if (days == null || days.length == 0) return true; // no filter = every day
                for (int d : days) if (d == todayDow) return true;
                return false;
            }

            case "Monthly": {
                // 1. Month filter
                int[] months = stringToIntsScheduler(s.getMonthMonths());
                if (months != null && months.length > 0) {
                    boolean monthMatch = false;
                    for (int mo : months) if (mo == today.getMonthValue()) { monthMatch = true; break; }
                    if (!monthMatch) return false;
                }

                // 2. Fixed day-of-month
                if (s.getMonthDayOfMonth() != null && s.getMonthDayOfMonth() > 0) {
                    return today.getDayOfMonth() == s.getMonthDayOfMonth();
                }

                // 3. Week-ordinal pattern (e.g. "2nd Monday")
                if (s.getMonthWeekOrdinal() != null && s.getMonthWeekDay() != null) {
                    int targetDow     = s.getMonthWeekDay();     // 0=Sun … 6=Sat
                    int targetOrdinal = s.getMonthWeekOrdinal(); // 1–4=nth, 5=Last

                    int todayDow = today.getDayOfWeek().getValue() % 7;
                    if (todayDow != targetDow) return false;

                    if (targetOrdinal == 5) {
                        // "Last" — no same DOW exists later in the month
                        return today.plusWeeks(1).getMonthValue() != today.getMonthValue();
                    }
                    return (today.getDayOfMonth() - 1) / 7 + 1 == targetOrdinal;
                }

                // No day/week constraint — fire every day of the matching months
                return true;
            }

            default:
                return false;
        }
    }

    /** Comma-string → int[], or null. Used only inside the scheduler (no IOException). */
    private int[] stringToIntsScheduler(String s) {
        if (s == null || s.isBlank()) return null;
        String[] parts = s.split(",");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { result[i] = Integer.parseInt(parts[i].trim()); }
            catch (NumberFormatException e) { result[i] = 0; }
        }
        return result;
    }

    /**
     * Builds a short SMS message for prayer request reminders.
     * Includes a summary of the first few request titles and a link to the public prayer page.
     * Keeps the message concise to stay within SMS length limits.
     */
    private String buildPrayerSmsMessage(List<PrayerRequest> requests, String clientId) {
        String churchName = whatsAppSender.resolveChurchNameForSms(clientId);
        StringBuilder sb = new StringBuilder();
        sb.append("🙏 ").append(churchName).append(" — Prayer Requests\n");

        // Include up to 3 request titles as a preview
        int shown = 0;
        for (PrayerRequest r : requests) {
            if (shown >= 3) { sb.append("…and more.\n"); break; }
            sb.append("• ").append(r.getTitle()).append("\n");
            shown++;
        }

        // Append public link
        String publicLink = buildPublicPrayerLink(clientId);
        if (publicLink != null) {
            sb.append("View all: ").append(publicLink);
        }
        return sb.toString().trim();
    }

    /**
     * Builds the full public URL for the prayer request page for a given clientId.
     * Returns {@code null} if encryption fails.
     */
    private String buildPublicPrayerLink(String clientId) {
        try {
            String encryptedCid = EncryptionUtil.encrypt(clientId);
            return baseUrl + "/viewPrayerRequest?cid="
                    + java.net.URLEncoder.encode(encryptedCid, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return baseUrl + "/viewPrayerRequest";
        }
    }

    /** Builds the HTML digest email for prayer request reminders. */
    private String buildPrayerReminderEmail(List<PrayerRequest> requests,
                                            Map<Long, String> sectionNames,
                                            String churchName) {
        // Group by section
        Map<Long, List<PrayerRequest>> bySection = requests.stream()
                .collect(java.util.stream.Collectors.groupingBy(PrayerRequest::getSectionId,
                         java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()));

        StringBuilder content = new StringBuilder();
        for (Map.Entry<Long, List<PrayerRequest>> entry : bySection.entrySet()) {
            String secName = sectionNames.getOrDefault(entry.getKey(), "Prayer Requests");
            content.append("<h3 style='color:#5c6bc0;border-bottom:2px solid #e8eaf6;")
                   .append("padding-bottom:6px;margin:20px 0 10px;'>")
                   .append(esc(secName)).append("</h3>");
            for (PrayerRequest r : entry.getValue()) {
                content.append("<div style='background:#f8f9fe;border-left:4px solid #7986cb;")
                       .append("border-radius:6px;padding:12px 16px;margin-bottom:10px;'>");
                content.append("<div style='font-weight:700;color:#3f4568;'>")
                       .append(esc(r.getTitle())).append("</div>");
                if (r.getDescription() != null && !r.getDescription().isBlank()) {
                    content.append("<div style='color:#555;font-size:13px;margin-top:6px;'>")
                           .append(esc(r.getDescription()).replace("\n", "<br>")).append("</div>");
                }
                if (r.getRequesterName() != null && !r.getRequesterName().isBlank()) {
                    content.append("<div style='color:#9fa8c4;font-size:12px;margin-top:4px;'>")
                           .append("Requested by: ").append(esc(r.getRequesterName())).append("</div>");
                }
                content.append("</div>");
            }
        }

        content.append("<p style='color:#aaa;font-size:12px;text-align:center;margin-top:24px;'>")
               .append("This reminder was sent by ").append(esc(churchName)).append(".</p>");

        return wrapInTemplate("🙏 Prayer Requests", content.toString());
    }

    // =========================================================================
    // Weekly Meeting Reminder — every Monday at 07:50 (type 2)
    // =========================================================================

    @Scheduled(cron = "${scheduler.job.weekly-meeting-reminders}", zone = "America/Chicago")
    public void runWeeklyMeetingRemiznders() {
        Set<String> activeClients = loadActiveClientIds();
        LocalDate today    = LocalDate.now();                       // Monday
        LocalDate weekEnd  = today.plusDays(6);                     // Sunday

        for (AutoReminder reminder : autoReminderRepo.findByReminderTypeIdAndDisabledFalse(TYPE_WEEKLY_MEETING_REMINDER)) {
            String       clientId = reminder.getAppClientId();
            if (!isActiveClient(clientId, activeClients)) continue;

            List<String> rcpList = parseRecipients(reminder.getRecipients());

            // Gather all meetings for this client and filter to those falling in the week
            List<Meeting> weekMeetings = meetingRepo.findByAppClientIdAndDeleteFlagFalse(clientId)
                    .stream()
                    // falls in week AND at least one occurrence that week is not user-deleted
                    .filter(m -> meetingFallsInWeek(m, today, weekEnd)
                            && firstActiveOccurrenceInWeek(m, today, weekEnd) != null)
                    .sorted(Comparator.comparing(Meeting::getMeetingDate, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(m -> m.getStartTime() == null ? "" : m.getStartTime()))
                    .collect(Collectors.toList());

            if (weekMeetings.isEmpty()) continue;

            // \u2500\u2500 Dedup guard \u2014 weekly digest sent once per ISO-week per client \u2500\u2500
            String isoWeek = today.getYear() + "-W" + today.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            String weeklyRefKey = clientId + "_" + isoWeek;
            if (alreadySent(clientId, "WEEKLY_MEETING", weeklyRefKey, today)) continue;
            if (!markSent(clientId, "WEEKLY_MEETING", weeklyRefKey, today)) continue;

            String subject = "\uD83D\uDDD3\uFE0F Meetings This Week (" + today + " – " + weekEnd + ")";
            String body    = buildWeeklyMeetingBody(today, weekEnd, weekMeetings, clientId);

            if (!Boolean.FALSE.equals(reminder.getSendEmail())) {
                collectMembersAndGuests(clientId, rcpList)
                        .forEach(email -> emailService.sendOrgEmail(email, subject, body, clientId));
            }
            boolean doWaWeekly  = Boolean.TRUE.equals(reminder.getSendWhatsApp());
            boolean doSmsWeekly = Boolean.TRUE.equals(reminder.getSendSms());
            for (Meeting wm : weekMeetings) {
                // Use effective date so Daily shows today and Weekly shows its
                // occurrence date within this week — skipping user-deleted occurrences
                LocalDate effectiveDate = firstActiveOccurrenceInWeek(wm, today, weekEnd);
                if (effectiveDate == null) effectiveDate = resolveEffectiveDate(wm, today);
                whatsAppSender.sendToRecipientsMultiChannel(
                        reminder.getRecipients(), buildMeetingWhatsAppMessage(wm, effectiveDate, clientId),
                        clientId, doWaWeekly, doSmsWeekly);
            }
        }
    }

    /**
     * Returns {@code true} if the given meeting should appear in the weekly digest
     * covering {@code weekStart} through {@code weekEnd} (inclusive).
     *
     * <ul>
     *   <li>Once — meeting date is within the week.</li>
     *   <li>Daily — meeting started on or before week end, end date (if set) is on or after week start.</li>
     *   <li>Weekly — at least one day in {@code weekDays} falls within [weekStart, weekEnd] and the
     *       series is active during that period.  Falls back to the meeting date's own day-of-week
     *       when {@code weekDays} is empty.</li>
     *   <li>Monthly — at least one day in [weekStart, weekEnd] satisfies the monthly pattern
     *       (month filter + day-of-month or week-ordinal).</li>
     * </ul>
     */
    /**
     * True for any spelling of a non-recurring (one-time) occurrence. The meetings
     * form saves {@code "One-time"} (hyphen), but older/imported data may use
     * {@code "Once"} or {@code "One Time"} (space). All of these must be treated the
     * same, otherwise one-time meetings silently skip their reminder email while the
     * recurring types (Daily/Weekly/Monthly) still work.
     */
    private static boolean isOneTimeOccurrence(String occ) {
        if (occ == null) return true;
        String n = occ.trim().toLowerCase().replace('-', ' ').replace('_', ' ').replaceAll("\\s+", " ").trim();
        return n.isEmpty() || n.equals("once") || n.equals("one time") || n.equals("onetime") || n.equals("single");
    }

    private boolean meetingFallsInWeek(Meeting m, LocalDate weekStart, LocalDate weekEnd) {
        LocalDate mDate = m.getMeetingDate();
        if (mDate == null) return false;
        String occ = m.getOccurrence() == null ? "Once" : m.getOccurrence().trim();

        if (isOneTimeOccurrence(occ)) {
            return !mDate.isBefore(weekStart) && !mDate.isAfter(weekEnd);
        }

        LocalDate endDate = m.getEndDate();
        boolean started  = !mDate.isAfter(weekEnd);
        boolean notEnded = endDate == null || !endDate.isBefore(weekStart);

        if ("Daily".equalsIgnoreCase(occ)) {
            return started && notEnded;
        }

        if ("Weekly".equalsIgnoreCase(occ)) {
            if (!started || !notEnded) return false;
            int[] days = stringToIntsScheduler(m.getWeekDays());
            if (days == null || days.length == 0) {
                // Fall back to the meeting date's own day-of-week
                DayOfWeek meetingDow = mDate.getDayOfWeek();
                LocalDate occurrence = weekStart.with(TemporalAdjusters.nextOrSame(meetingDow));
                return !occurrence.isAfter(weekEnd);
            }
            // Check whether any configured weekDay falls within [weekStart, weekEnd]
            for (int d : days) {
                // Convert 0=Sun…6=Sat to DayOfWeek (Mon=1…Sun=7)
                DayOfWeek dow = DayOfWeek.of(d == 0 ? 7 : d);
                LocalDate occurrence = weekStart.with(TemporalAdjusters.nextOrSame(dow));
                if (!occurrence.isAfter(weekEnd)) return true;
            }
            return false;
        }

        if ("Monthly".equalsIgnoreCase(occ)) {
            if (!started || !notEnded) return false;
            // Check each day in the week to see if any satisfies the monthly pattern
            for (LocalDate d = weekStart; !d.isAfter(weekEnd); d = d.plusDays(1)) {
                if (meetingOccursToday(m, d)) return true;
            }
            return false;
        }

        return false;
    }

    // ── Meeting Reminders (type 1 — 3 h before) ──────────────────────────────

    private void runMeetingReminders(LocalDate today) {
    	
        Set<String> activeClients = loadActiveClientIds();
        LocalTime   now        = LocalTime.now();
        // Send exactly once: only when the meeting falls in the [now+2h, now+3h) window.
        // The job runs every hour at :30, so each hourly run covers a distinct 1-hour slice
        // and no meeting is matched by more than one consecutive run.
        LocalTime   lowerBound = now.plusHours(2);
        LocalTime   upperBound = now.plusHours(3);

        for (AutoReminder reminder : autoReminderRepo.findByReminderTypeIdAndDisabledFalse(TYPE_MEETING_REMINDER)) {
            String clientId = reminder.getAppClientId();
            if (!isActiveClient(clientId, activeClients)) continue;

            List<String> rcpList = parseRecipients(reminder.getRecipients());

            // Check all meetings for this client; meetingOccursToday handles
            // One Time / Daily / Weekly occurrence patterns
            for (Meeting meeting : meetingRepo.findByAppClientIdAndDeleteFlagFalse(clientId)) {
                if (!meetingOccursToday(meeting, today)) continue;
                // Occurrence deleted by user → no reminder of any kind today
                if (isOccurrenceSkipped(meeting, today)) continue;

                if (meeting.getStartTime() == null || meeting.getStartTime().isBlank()) continue;
                LocalTime meetingStart;
                try {
                    meetingStart = LocalTime.parse(meeting.getStartTime());
                } catch (Exception e) { continue; }

                // Window: [lowerBound, upperBound] — inclusive on both ends.
                // A meeting at exactly now+3h (e.g. 7:30 PM when run fires at 4:30 PM)
                // must be included, so we use !isAfter(upperBound) rather than isBefore(upperBound).
                if (meetingStart.isBefore(lowerBound) || meetingStart.isAfter(upperBound)) continue;

                // ── Dedup guard — send at most once per meeting per day ──
                String mtgRefKey = String.valueOf(meeting.getId());
                if (alreadySent(clientId, "MEETING", mtgRefKey, today)) continue;
                if (!markSent(clientId, "MEETING", mtgRefKey, today)) continue;

                String typeName = meeting.getMeetingType() != null
                        ? meeting.getMeetingType().getTypeName() : "Meeting";
                String subject  = "Reminder: " + typeName + " today at " + formatTime(meeting.getStartTime());
                String body     = buildMeetingReminderBody(meeting, typeName, reminder, today, clientId);

                if (!Boolean.FALSE.equals(reminder.getSendEmail())) {
                    collectMembersAndGuests(clientId, rcpList)
                            .forEach(email -> emailService.sendOrgEmail(email, subject, body, clientId));
                }

                // ── WhatsApp: use Content Template if SID configured, else plain text ──
                if (Boolean.TRUE.equals(reminder.getSendWhatsApp())) {
                    Optional<WhatsAppSettings> wsOpt = whatsAppSettingsRepo.findByClientId(clientId);
                    String contentSid = wsOpt.map(WhatsAppSettings::getMeetingReminderContentSid)
                                             .filter(s -> s != null && !s.isBlank())
                                             .orElse(null);
                    if (contentSid != null) {
                        Map<String, String> vars = buildMeetingTemplateVariables(meeting, clientId, today);
                        whatsAppSender.sendTemplateToRecipients(
                                reminder.getRecipients(), contentSid, vars, clientId);
                    } else {
                        whatsAppSender.sendToRecipientsMultiChannel(
                                reminder.getRecipients(), buildMeetingWhatsAppMessage(meeting, today, clientId),
                                clientId, true, false);
                    }
                }
                // SMS (plain text, no template)
                if (Boolean.TRUE.equals(reminder.getSendSms())) {
                    whatsAppSender.sendToRecipientsMultiChannel(
                            reminder.getRecipients(), buildMeetingWhatsAppMessage(meeting, today, clientId),
                            clientId, false, true);
                }

                // ── Push notification channel ────────────────────────────
                // Deep-link to the public event calendar when one is configured;
                // fall back to the internal meetings page for staff users.
                String pushCalLink = resolveCalendarLink(clientId);
                String pushPath = (pushCalLink != null) ? pushCalLink : "/meetings";
                webPushService.logAndSendToOrg(clientId,
                        "🏛️ Meeting Reminder: " + typeName,
                        subject,
                        pushPath, "cgp-meeting-reminder");
            }
        }
    }

    /**
     * Returns {@code true} if the meeting's occurrence pattern means it fires on {@code today}.
     *
     * <ul>
     *   <li>Once — only on its meeting date.</li>
     *   <li>Daily — every day from meeting date up to end date (or indefinitely).</li>
     *   <li>Weekly — fires on days listed in {@code weekDays} (0=Sun…6=Sat), from meeting date
     *       up to end date.  Falls back to meeting date's day-of-week if {@code weekDays} is empty.</li>
     *   <li>Monthly — fires on today's month if {@code monthMonths} is satisfied (or unset), AND
     *       either today matches {@code monthDayOfMonth} or the week-ordinal+weekDay pattern.</li>
     * </ul>
     */
    private boolean meetingOccursToday(Meeting m, LocalDate today) {
        LocalDate mDate = m.getMeetingDate();
        if (mDate == null) return false;
        String occ = m.getOccurrence() == null ? "Once" : m.getOccurrence().trim();
        if (isOneTimeOccurrence(occ)) {
        	return mDate.equals(today);
        }

        // Recurring: meeting series must have started on or before today
        if (mDate.isAfter(today)) return false;
        LocalDate endDate = m.getEndDate();
        if (endDate != null && endDate.isBefore(today)) return false;

        if ("Daily".equalsIgnoreCase(occ)) return true;

        if ("Weekly".equalsIgnoreCase(occ)) {
            int todayDow = today.getDayOfWeek().getValue() % 7; // Mon=1…Sun=7 → Sun=0
            int[] days = stringToIntsScheduler(m.getWeekDays());
            if (days == null || days.length == 0) {
                // Fall back to the meeting date's own day-of-week
                return mDate.getDayOfWeek() == today.getDayOfWeek();
            }
            for (int d : days) if (d == todayDow) return true;
            return false;
        }

        if ("Monthly".equalsIgnoreCase(occ)) {
            // 1. Month filter (monthMonths stores a comma-separated list of month numbers 1–12)
            int[] months = stringToIntsScheduler(m.getMonthMonths());
            if (months != null && months.length > 0) {
                boolean monthMatch = false;
                for (int mo : months) if (mo == today.getMonthValue()) { monthMatch = true; break; }
                if (!monthMatch) return false;
            }

            // 2. Fixed day-of-month
            if (m.getMonthDayOfMonth() != null && m.getMonthDayOfMonth() > 0) {
                return today.getDayOfMonth() == m.getMonthDayOfMonth();
            }

            // 3. Week-ordinal pattern (e.g. "2nd Monday")
            if (m.getMonthWeekOrdinal() != null && m.getMonthWeekDay() != null) {
                int targetDow     = m.getMonthWeekDay();     // 0=Sun … 6=Sat
                int targetOrdinal = m.getMonthWeekOrdinal(); // 1–4=nth, 5=Last

                int todayDow = today.getDayOfWeek().getValue() % 7;
                if (todayDow != targetDow) return false;

                if (targetOrdinal == 5) {
                    // "Last" — no same DOW exists later in the month
                    return today.plusWeeks(1).getMonthValue() != today.getMonthValue();
                }
                return (today.getDayOfMonth() - 1) / 7 + 1 == targetOrdinal;
            }

            // No day/week constraint — fire every day of the matching months
            return true;
        }

        return false;
    }

    // ── One-time Reminders ────────────────────────────────────────────────────

    private void runOneTimeReminders(LocalDate today) {
        Set<String> activeClients = loadActiveClientIds();
        for (OneTimeReminder reminder : oneTimeReminderRepo.findByEventDateAndDisabledFalse(today)) {
            String clientId = reminder.getAppClientId();
            if (!isActiveClient(clientId, activeClients)) continue;

            // ── Dedup guard — send at most once per one-time reminder per day ──
            String otRefKey = String.valueOf(reminder.getId());
            if (alreadySent(clientId, "ONE_TIME", otRefKey, today)) continue;
            if (!markSent(clientId, "ONE_TIME", otRefKey, today)) continue;

            List<String> rcpList = parseRecipients(reminder.getRecipients());

            // ── Email channel ──────────────────────────────────────────────
            if (!Boolean.FALSE.equals(reminder.getSendEmail())) {
                collectMembersAndGuests(clientId, rcpList)
                        .forEach(email -> emailService.sendOrgEmail(
                                email, reminder.getName(),
                                buildOneTimeReminderBody(reminder), clientId));
            }


            // ── WhatsApp / SMS channels (deduplicated per number) ──────
            whatsAppSender.sendToRecipientsMultiChannel(
                    reminder.getRecipients(), reminder.getName(), clientId,
                    Boolean.TRUE.equals(reminder.getSendWhatsApp()),
                    Boolean.TRUE.equals(reminder.getSendSms()));

            // ── Push notification channel ────────────────────────────────
            webPushService.logAndSendToOrg(clientId,
                    "🔔 Reminder: " + reminder.getName(),
                    reminder.getName(),
                    "/home", "cgp-one-time-reminder");
        }
    }

    // ── Event Reminders ───────────────────────────────────────────────────────

    /**
     * For each enabled {@link EventReminder}, uses its {@code appClientId} to find
     * all non-deleted events belonging to that church.  For each event, evaluates
     * the same-day / before-days / after-days triggers against today's date and
     * sends emails exclusively to registrants whose {@code attending} flag is {@code true}.
     */
    private void runEventReminders(LocalDate today) {
        Set<String> activeClients = loadActiveClientIds();

        for (EventReminder reminder : eventReminderRepo.findByDisabledFalse()) {
            String clientId = reminder.getAppClientId();
            if (!isActiveClient(clientId, activeClients)) continue;

            // Events to process: specific event if eventId is set, otherwise all for this church
            List<ChurchEvent> clientEvents;
            if (reminder.getEventId() != null) {
                clientEvents = eventRepo.findByIdAndDeleteFlagFalse(reminder.getEventId())
                        .map(List::of).orElse(List.of());
            } else {
                clientEvents = eventRepo.findByAppClientIdAndDeleteFlagFalseOrderByCreatedDateDesc(clientId);
            }
            

            for (ChurchEvent event : clientEvents) {
                LocalDate eventDate = event.getEventDate();
                if (eventDate == null) continue;

                // Recipients: those who RSVP'd "Yes" (attending=true) or "Maybe"
                // (attending=null), excluding anyone who declined with "No" (false).
                List<EventRegistration> attendees =
                        registrationRepo.findRemindableByEventId(event.getId());
                
                
                if (attendees.isEmpty()) continue;

                // Same-day trigger — include calendar invite
                if (Boolean.TRUE.equals(reminder.getSameDay()) && eventDate.equals(today)) {
                    String evtSameDayKey = event.getId() + "_SAME_DAY";
                    if (!alreadySent(clientId, "EVENT", evtSameDayKey, today) && markSent(clientId, "EVENT", evtSameDayKey, today)) {
                        byte[] ics = buildIcsContent(event);
                        String subject = "Reminder: " + eventDisplayName(event) + " is Today!";
                        String body    = hasTemplate(reminder.getSameDayTemplate())
                                ? renderEventTemplate(reminder.getSameDayTemplate(), event, 0)
                                : buildEventSameDayBody(event);
                        if (!Boolean.FALSE.equals(reminder.getSendEmail()))
                            sendToEventRegistrants(attendees, subject, body, clientId, ics);
                        sendEventRegistrantMessages(attendees, event, clientId, reminder);
                        webPushService.logAndSendToOrg(clientId,
                                "📅 Event Reminder: " + eventDisplayName(event),
                                subject,
                                "/event", "cgp-event-reminder");
                    }
                }

                // Before-days trigger — include calendar invite
                if (reminder.getBeforeDays() != null && reminder.getBeforeDays() > 0
                        && eventDate.minusDays(reminder.getBeforeDays()).equals(today)) {
                    String evtBeforeKey = event.getId() + "_BEFORE_" + reminder.getBeforeDays();
                    if (!alreadySent(clientId, "EVENT", evtBeforeKey, today) && markSent(clientId, "EVENT", evtBeforeKey, today)) {
                        byte[] ics = buildIcsContent(event);
                        String subject = "Reminder: " + eventDisplayName(event) + " in " + reminder.getBeforeDays() + " day(s)";
                        String body    = hasTemplate(reminder.getBeforeDaysTemplate())
                                ? renderEventTemplate(reminder.getBeforeDaysTemplate(), event, reminder.getBeforeDays())
                                : buildEventBeforeDaysBody(event, reminder.getBeforeDays());
                        if (!Boolean.FALSE.equals(reminder.getSendEmail()))
                            sendToEventRegistrants(attendees, subject, body, clientId, ics);
                        sendEventRegistrantMessages(attendees, event, clientId, reminder);
                        webPushService.logAndSendToOrg(clientId,
                                "📅 Event Reminder: " + eventDisplayName(event),
                                subject,
                                "/event", "cgp-event-reminder");
                    }
                }

                // After-days trigger — event already past, no calendar invite needed
                if (reminder.getAfterDays() != null && reminder.getAfterDays() > 0
                        && eventDate.plusDays(reminder.getAfterDays()).equals(today)) {
                    String evtAfterKey = event.getId() + "_AFTER_" + reminder.getAfterDays();
                    if (!alreadySent(clientId, "EVENT", evtAfterKey, today) && markSent(clientId, "EVENT", evtAfterKey, today)) {
                        String subject = "Hope you enjoyed " + eventDisplayName(event) + "!";
                        String body    = hasTemplate(reminder.getAfterDaysTemplate())
                                ? renderEventTemplate(reminder.getAfterDaysTemplate(), event, reminder.getAfterDays())
                                : buildEventAfterDaysBody(event);
                        if (!Boolean.FALSE.equals(reminder.getSendEmail()))
                            sendToEventRegistrants(attendees, subject, body, clientId, null);
                        sendEventRegistrantMessages(attendees, event, clientId, reminder);
                        webPushService.logAndSendToOrg(clientId,
                                "📅 Event Reminder: " + eventDisplayName(event),
                                subject,
                                "/event", "cgp-event-reminder");
                    }
                }
            }
        }
    }

    private void sendToEventRegistrants(List<EventRegistration> registrants, String subject,
                                         String body, String clientId, byte[] icsData) {
        for (EventRegistration reg : registrants) {
            if (reg.getEmail() != null && !reg.getEmail().isBlank()) {
                emailService.sendOrgEmail(reg.getEmail(), subject, body, clientId, icsData);
            }
        }
    }

    /**
     * Sends WhatsApp and/or SMS to event registrants whose phone numbers are non-null.
     * SMS messages include date/time, location, and a public view link for the event.
     */
    private void sendEventRegistrantMessages(List<EventRegistration> registrants,
                                              ChurchEvent event,
                                              String clientId,
                                              EventReminder reminder) {
        boolean doWhatsApp = Boolean.TRUE.equals(reminder.getSendWhatsApp());
        boolean doSms      = Boolean.TRUE.equals(reminder.getSendSms());
        if (!doWhatsApp && !doSms) return;

        String message = buildEventReminderSms(event, clientId);

        for (EventRegistration reg : registrants) {
            String phone = reg.getPhone();
            if (phone == null || phone.isBlank()) continue;
            // Use multi-channel helper to avoid sending both WhatsApp and SMS to the same number.
            whatsAppSender.sendToPhoneMultiChannel(phone, message, clientId, doWhatsApp, doSms);
        }
    }

    /**
     * Builds the plain-text SMS body for an event reminder.
     * Format: "Church Name: Reminder – Event Name. Date & Time. Location. Link. Reply STOP to opt out."
     */
    /**
     * Builds the bare SMS body for an event reminder — no church-name prefix and no
     * opt-out suffix, because {@link WhatsAppSenderService} adds both automatically
     * via {@code resolveMessage} / {@code resolveMessageNoSettings} before delivery.
     *
     * <p>Format (each item on its own line):
     * <pre>
     * Reminder – Musical Night
     * May 3, 2026  5:00 PM – 6:00 PM
     * 11902 Lowell Ave, OP, KS 66123
     * Details: http://...
     * </pre>
     */
    private String buildEventReminderSms(ChurchEvent event, String clientId) {
        StringBuilder sb = new StringBuilder("Reminder – ")
                .append(event.getEventName() != null ? decodeEntities(event.getEventName()) : "Upcoming Event");

        // Date & time on the next line
        if (event.getEventDate() != null) {
            sb.append("\n").append(formatDate(event.getEventDate()));
            if (event.getStartTime() != null && !event.getStartTime().isBlank()) {
                sb.append("  ").append(formatTime(event.getStartTime()));
                if (event.getEndTime() != null && !event.getEndTime().isBlank()
                        && !event.getEndTime().equals(event.getStartTime())) {
                    sb.append(" – ").append(formatTime(event.getEndTime()));
                }
            }
        }

        // Location on the next line
        String address = buildFullAddress(event);
        if (!address.isEmpty()) {
            sb.append("\n").append(address);
        }

        // Public view link on the next line
        try {
            String token = EncryptionUtil.encrypt(String.valueOf(event.getId()));
            sb.append("\nDetails: ").append(baseUrl)
              .append("/event-register/").append(token).append("?view=1");
        } catch (Exception ignored) {}

        return sb.toString();
    }

    // ── Dynamic Holiday Reminders ─────────────────────────────────────────────

    private void runDynamicHolidayReminders(LocalDate today) {
        // Thanksgiving = 4th Thursday of November (type 10)
        if (today.equals(LocalDate.of(today.getYear(), Month.NOVEMBER, 1)
                .with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY)))) {
            sendHolidayReminder(TYPE_THANKSGIVING, "\uD83E\uDD83 Happy Thanksgiving!", "\uD83E\uDD83 Happy Thanksgiving!");
        }
        // Veterans Day = November 11 (type 11)
        if (today.equals(LocalDate.of(today.getYear(), Month.NOVEMBER, 11))) {
            sendHolidayReminder(TYPE_VETERANS, "\uD83C\uDDFA\uD83C\uDDF8 Happy Veterans Day!", "\uD83C\uDDFA\uD83C\uDDF8 Happy Veterans Day!");
        }
        // Presidents' Day = 3rd Monday of February (type 12)
        if (today.equals(LocalDate.of(today.getYear(), Month.FEBRUARY, 1)
                .with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.MONDAY)))) {
            sendHolidayReminder(TYPE_PRESIDENTS, "\uD83C\uDDFA\uD83C\uDDF8 Happy Presidents' Day!", "\uD83C\uDDFA\uD83C\uDDF8 Happy Presidents' Day!");
        }
        // Memorial Day = last Monday of May (type 13)
        if (today.equals(LocalDate.of(today.getYear(), Month.MAY, 1)
                .with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY)))) {
            sendHolidayReminder(TYPE_MEMORIAL, "\uD83C\uDDFA\uD83C\uDDF8 Happy Memorial Day!", "\uD83C\uDDFA\uD83C\uDDF8 Happy Memorial Day!");
        }
        // Labor Day = 1st Monday of September (type 14)
        if (today.equals(LocalDate.of(today.getYear(), Month.SEPTEMBER, 1)
                .with(TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY)))) {
            sendHolidayReminder(TYPE_LABOR, "\uD83C\uDDFA\uD83C\uDDF8 Happy Labor Day!", "\uD83C\uDDFA\uD83C\uDDF8 Happy Labor Day!");
        }
    }

    // ── Holiday helper ────────────────────────────────────────────────────────

    private void sendHolidayReminder(int typeId, String subject, String heading) {
        Set<String> activeClients = loadActiveClientIds();
        LocalDate today = LocalDate.now();
        for (AutoReminder reminder : autoReminderRepo.findByReminderTypeIdAndDisabledFalse(typeId)) {
            String clientId = reminder.getAppClientId();
            if (!isActiveClient(clientId, activeClients)) continue;

            // ── Dedup guard — send once per holiday per year per client ──
            String holidayRefKey = clientId + "_" + today.getYear();
            if (alreadySent(clientId, "HOLIDAY_" + typeId, holidayRefKey, today)) continue;
            if (!markSent(clientId, "HOLIDAY_" + typeId, holidayRefKey, today)) continue;

            List<String> rcpList = parseRecipients(reminder.getRecipients());

            // The occasion fires on its own date, so "today" is the holiday date.
            String occasion = (reminder.getName() != null && !reminder.getName().isBlank())
                    ? reminder.getName() : heading;

            // Email — use the custom template when configured, else the default body.
            String body;
            if (hasTemplate(reminder.getEmailTemplate())) {
                String rendered = applyHolidayPlaceholders(reminder.getEmailTemplate(), occasion, today)
                        .replace("\r\n", "\n").replace("\n", "<br/>");
                body = wrapInTemplate(heading, rendered);
            } else {
                body = buildHolidayBody(heading, reminder.getNote());
            }

            // SMS/text — use the custom message when configured, else the default subject.
            String smsMessage = hasTemplate(reminder.getSmsTemplate())
                    ? applyHolidayPlaceholders(reminder.getSmsTemplate(), occasion, today)
                    : subject;

            if (!Boolean.FALSE.equals(reminder.getSendEmail())) {
                collectMembersAndGuests(clientId, rcpList)
                        .forEach(email -> emailService.sendOrgEmail(email, subject, body, clientId));
            }
            whatsAppSender.sendToRecipientsMultiChannel(
                    reminder.getRecipients(), smsMessage, clientId,
                    Boolean.TRUE.equals(reminder.getSendWhatsApp()),
                    Boolean.TRUE.equals(reminder.getSendSms()));
        }
    }

    /**
     * Substitutes the holiday placeholders {@code {date}} (the occasion's date)
     * and {@code {occasion}} (the occasion name) in a custom email/SMS template.
     */
    private String applyHolidayPlaceholders(String text, String occasion, LocalDate date) {
        if (text == null) return "";
        String dateStr = date != null ? formatDate(date) : "";
        return text.replace("{date}", dateStr)
                   .replace("{occasion}", occasion != null ? occasion : "");
    }

    /**
     * Renders a custom celebrant email template (Birthday / Wedding Anniversary),
     * replacing {@code {name}} with the celebrant's name, then wrapping the result
     * in the standard branded email shell. Author newlines become {@code <br/>}.
     */
    private String renderCelebrantEmail(String template, String name, String heading) {
        if (template == null) return "";
        String rendered = template
                .replace("{name}", esc(name == null ? "" : name))
                .replace("\r\n", "\n").replace("\n", "<br/>");
        return wrapInTemplate(heading, rendered);
    }

    // =========================================================================
    // Helpers — recipients and email addresses
    // =========================================================================

    private List<String> collectMembersAndGuests(String clientId, List<String> rcpList) {
        // Use a LinkedHashSet to deduplicate emails while preserving insertion order.
        // Without this, the same address can appear multiple times (e.g. a person
        // listed as both Member and Guest, or duplicate DB rows) and each copy would
        // produce a separate email send.
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        if (rcpList.contains("Members")) {
            memberRepo.findByMemberTypeWithEmailByAppUser("Member", clientId)
                    .forEach(m -> { if (hasEmail(m)) seen.add(m.getEmail().trim().toLowerCase()); });
        }
        if (rcpList.contains("Guests")) {
            memberRepo.findByMemberTypeWithEmailByAppUser("Guest", clientId)
                    .forEach(g -> { if (hasEmail(g)) seen.add(g.getEmail().trim().toLowerCase()); });
        }
        if (rcpList.contains("Visitors")) {
            memberRepo.findByMemberTypeWithEmailByAppUser("Visitor", clientId)
                    .forEach(v -> { if (hasEmail(v)) seen.add(v.getEmail().trim().toLowerCase()); });
        }
        // Group recipients — tokens of the form "Group:<id>".
        seen.addAll(collectGroupEmails(clientId, rcpList));
        return new ArrayList<>(seen);
    }

    /**
     * Resolves email addresses for any {@code Group:<id>} tokens in {@code rcpList}.
     * Each token references an {@code app_group} row; members are loaded via
     * {@link GroupMemberRepository#findByGroupActiveByAppUser} scoped to the tenant.
     * Returns deduplicated, lower-cased email addresses (group members without an
     * email are skipped).
     */
    private List<String> collectGroupEmails(String clientId, List<String> rcpList) {
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String token : rcpList) {
            if (token == null) continue;
            String t = token.trim();
            if (!t.startsWith("Group:")) continue;
            Integer groupId;
            try {
                groupId = Integer.valueOf(t.substring("Group:".length()).trim());
            } catch (NumberFormatException ex) {
                continue;   // malformed token — skip
            }
            groupMemberRepo.findByGroupActiveByAppUser(groupId, clientId).forEach(gm -> {
                String email = gm.getEmail();
                if (email != null && !email.isBlank()) seen.add(email.trim().toLowerCase());
            });
        }
        return new ArrayList<>(seen);
    }

    private List<String> parseRecipients(String recipientsStr) {
        if (recipientsStr == null || recipientsStr.isBlank()) return List.of();
        return Arrays.asList(recipientsStr.split(","));
    }

    private boolean hasEmail(FamilyMember m) {
        return m.getEmail() != null && !m.getEmail().isBlank();
    }

    // ── Sent-log helpers ──────────────────────────────────────────────────────

    /**
     * Returns {@code true} when a sent-log row already exists for this
     * (clientId, reminderType, referenceKey, today) combination — meaning the
     * reminder was already dispatched today and must be skipped.
     */
    private boolean alreadySent(String clientId, String reminderType, String referenceKey, LocalDate today) {
        return sentLogRepo.existsByAppClientIdAndReminderTypeAndReferenceKeyAndSentDate(
                clientId, reminderType, referenceKey, today);
    }

    /**
     * Writes a sent-log row to prevent this reminder from firing again today.
     * The unique DB constraint is the final safety net; this call is intentionally
     * made <em>before</em> sending so that a duplicate scheduler invocation that
     * races past the {@link #alreadySent} check still only sends once.
     *
     * <p>Runs in its own transaction so a constraint violation does NOT roll back
     * the surrounding scheduler transaction.
     *
     * @return {@code true} if the row was inserted (first send); {@code false} if
     *         a duplicate constraint violation was caught (already sent — skip).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private boolean markSent(String clientId, String reminderType, String referenceKey, LocalDate today) {
        try {
            com.churchgeniuspro.hibernate.ReminderSentLog log = new com.churchgeniuspro.hibernate.ReminderSentLog();
            log.setAppClientId(clientId);
            log.setReminderType(reminderType);
            log.setReferenceKey(referenceKey);
            log.setSentDate(today);
            sentLogRepo.saveAndFlush(log);
            return true;
        } catch (Exception e) {
            // Unique constraint violation — another thread already claimed this slot
            return false;
        }
    }

    private String fullName(FamilyMember m) {
        return ((m.getFirstName() != null ? m.getFirstName() : "") + " "
                + (m.getLastName() != null ? m.getLastName() : "")).trim();
    }

    /** "First Last (Nickname)" when a nickname (otherName) is on file, else "First Last". */
    private String fullNameWithNick(FamilyMember m) {
        String base = fullName(m);
        String nick = m.getOtherName();
        return (nick != null && !nick.isBlank()) ? base + " (" + nick.trim() + ")" : base;
    }

    /** "First (Nickname)" when a nickname is on file, else "First" — for couple/first-name displays. */
    private String firstNameWithNick(FamilyMember m) {
        String first = m.getFirstName() != null ? m.getFirstName().trim() : "";
        String nick = m.getOtherName();
        return (nick != null && !nick.isBlank()) ? first + " (" + nick.trim() + ")" : first;
    }

    private String formatTime(String t) {
        if (t == null || t.isBlank()) return "";
        try {
            String[] parts = t.split(":");
            int h = Integer.parseInt(parts[0]);
            String min = parts.length > 1 ? parts[1] : "00";
            return (h % 12 == 0 ? 12 : h % 12) + ":" + min + " " + (h >= 12 ? "PM" : "AM");
        } catch (Exception e) { return t; }
    }

    /** Formats a {@link LocalDate} as "May 3, 2026". */
    private String formatDate(java.time.LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern("MMMM d, yyyy"));
    }

    /**
     * Returns the effective display date for a recurring meeting relative to a reference date
     * (usually "today").
     *
     * <ul>
     *   <li><b>One Time / Once</b> — returns the stored {@code meetingDate}.</li>
     *   <li><b>Daily</b> — always returns {@code referenceDate} (today).</li>
     *   <li><b>Weekly</b> — returns the next occurrence of the meeting's day-of-week
     *       on or after {@code referenceDate}.  If the day has already passed this week,
     *       the date returned falls in the following week.</li>
     * </ul>
     *
     * Falls back to the stored {@code meetingDate} when the occurrence is unrecognised
     * or the meeting has no date.
     */
    private LocalDate resolveEffectiveDate(Meeting m, LocalDate referenceDate) {
        LocalDate mDate = m.getMeetingDate();
        if (mDate == null) return referenceDate;
        String occ = m.getOccurrence() == null ? "Once" : m.getOccurrence().trim();

        if ("Daily".equalsIgnoreCase(occ)) {
            return referenceDate;
        }
        if ("Weekly".equalsIgnoreCase(occ)) {
            DayOfWeek meetingDow = mDate.getDayOfWeek();
            return referenceDate.with(TemporalAdjusters.nextOrSame(meetingDow));
        }
        // One Time / Once — use stored date
        return mDate;
    }

    /**
     * Builds the Location cell content for the weekly meeting email.
     * Format: {@code "Name, Address1, Address2, City, STATE PinCode, Country"}
     * — all on one comma-separated line, matching the example
     * {@code "Anson Mathew, 16127 S Bradley Dr, Olathe, KS 66062"}.
     *
     * <p>Name is resolved from {@code locationMemberId} (member full name) or
     * {@code locationFamilyId} (family name), whichever is set.  The numeric
     * state code is converted to its two-letter abbreviation.
     * Returns {@code "—"} when no name or address fields are populated.
     */
    private String formatLocationCell(Meeting m) {
        // ── Resolve location name ─────────────────────────────────────────
        String name = "";
        if (m.getLocationMemberId() != null) {
            name = memberRepo.findById(m.getLocationMemberId())
                    .map(fm -> esc((fm.getFirstName() != null ? fm.getFirstName() : "")
                                   + " "
                                   + (fm.getLastName()  != null ? fm.getLastName()  : "")).trim())
                    .orElse("");
        } else if (m.getLocationFamilyId() != null) {
            name = familyRepo.findByIdWithMembers(m.getLocationFamilyId())
                    .map(f -> {
                        FamilyMember p = f.getMembers().stream()
                                .filter(fm -> !fm.isDeleteFlag())
                                .filter(fm -> "Head".equalsIgnoreCase(fm.getRole())
                                          || "Head of Household".equalsIgnoreCase(fm.getRole()))
                                .findFirst()
                                .orElse(f.getMembers().stream().filter(fm -> !fm.isDeleteFlag()).findFirst().orElse(null));
                        String ln = p != null && p.getLastName()  != null ? p.getLastName()  : "";
                        String fn = p != null && p.getFirstName() != null ? p.getFirstName() : "";
                        return esc(ln.isBlank() ? fn : ln + " Family");
                    })
                    .orElse("");
        }

        // ── Build address parts (all on one line) ─────────────────────────
        List<String> parts = new ArrayList<>();
        if (m.getAddress1() != null && !m.getAddress1().isBlank())
            parts.add(esc(m.getAddress1().trim()));
        if (m.getAddress2() != null && !m.getAddress2().isBlank())
            parts.add(esc(m.getAddress2().trim()));
        if (m.getCity() != null && !m.getCity().isBlank())
            parts.add(esc(m.getCity().trim()));

        // State: resolve numeric code → two-letter abbreviation
        String stateDisplay = "";
        if (m.getState() != null && !m.getState().isBlank()) {
            stateDisplay = m.getState().trim();
            try {
                String abbr = States.getCode(Integer.parseInt(stateDisplay));
                if (abbr != null) stateDisplay = abbr;
            } catch (NumberFormatException ignored) { /* use raw value */ }
        }
        // Combine state and pin code: "KS 66062"
        String statePin = stateDisplay;
        if (m.getPinCode() != null && !m.getPinCode().isBlank())
            statePin = (statePin.isEmpty() ? "" : statePin + " ") + esc(m.getPinCode().trim());
        if (!statePin.isEmpty()) parts.add(statePin);

        if (m.getCountry() != null && !m.getCountry().isBlank())
            parts.add(esc(m.getCountry().trim()));

        String address = String.join(", ", parts);

        // ── Combine name + address ────────────────────────────────────────
        if (!name.isEmpty() && !address.isEmpty()) return name + " <br> " + address;
        if (!name.isEmpty())    return name;
        if (!address.isEmpty()) return address;
        return "\u2014"; // em dash — no location data
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Decodes common HTML entities that may have been stored in user-entered text
     * (e.g. an event name saved as {@code "4 &amp; 14"}), so that plain-text
     * contexts (email subjects, SMS, ICS attachments) show the literal character
     * and HTML contexts don't double-escape it. Performs a single decode pass —
     * {@code &amp;} is decoded last so already-correct text is never corrupted.
     */
    private String decodeEntities(String s) {
        if (s == null || s.indexOf('&') < 0) return s;
        return s.replace("&lt;",   "<")
                .replace("&gt;",   ">")
                .replace("&quot;", "\"")
                .replace("&#34;",  "\"")
                .replace("&#39;",  "'")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ")
                .replace("&amp;",  "&")
                .replace("&#38;",  "&");
    }

    /** The event's display name with stored HTML entities decoded; falls back to "Event". */
    private String eventDisplayName(ChurchEvent event) {
        String name = event.getEventName();
        return (name != null && !name.isBlank()) ? decodeEntities(name.trim()) : "Event";
    }

    // =========================================================================
    // Email body builders
    // =========================================================================

    private String wrapInTemplate(String heading, String content) {
        return "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'/>"
             + "<style>"
             + "body{margin:0;padding:0;background:#f5f6fa;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;}"
             + ".wrap{max-width:520px;margin:40px auto;background:#fff;border-radius:16px;box-shadow:0 4px 24px rgba(0,0,0,.08);overflow:hidden;}"
             + ".body{padding:32px 40px;font-size:14px;color:#333;line-height:1.7;}"
             + "</style></head>"
             + "<body><div class='wrap'>"
             + "<div class='body'><h2 style='margin-top:0;color:#3f4568;'>" + esc(heading) + "</h2>"
             + content
             + "</div>"
             + "</div></body></html>";
    }

    private String buildBirthdayCelebrantBody(FamilyMember m) {
        int currentYear = LocalDate.now().getYear();
        // Build age phrase when birth year is recorded and yields a positive age
        String agePhrase = "";
        if (m.getBirthdayYear() != null && m.getBirthdayYear() > 0) {
            int age = currentYear - m.getBirthdayYear();
            if (age > 0) {
                agePhrase = "<p>What a milestone — today you turn <strong>" + ordinal(age) + "</strong>!</p>";
            }
        }
        return wrapInTemplate("Happy Birthday, " + esc(firstNameWithNick(m)) + "! \uD83C\uDF82",
                "<p>Wishing you a wonderful birthday filled with joy and blessings!</p>"
              + agePhrase
              + "<p>May this special day bring you happiness and may the year ahead be full of God\u2019s grace.</p>");
    }

    private String buildBirthdayAudienceBody(FamilyMember celebrant) {
        String name = esc(fullNameWithNick(celebrant));
        int currentYear = LocalDate.now().getYear();
        // Append age milestone when birth year is available
        String ageClause = "";
        if (celebrant.getBirthdayYear() != null && celebrant.getBirthdayYear() > 0) {
            int age = currentYear - celebrant.getBirthdayYear();
            if (age > 0) {
                ageClause = ", celebrating their <strong>" + ordinal(age) + " birthday</strong>";
            }
        }
        return wrapInTemplate("\uD83C\uDF82 Birthday Wishes for " + name,
                "<p>Today is <strong>" + name + "</strong>\u2019s birthday" + ageClause + "!</p>"
              + "<p>Please join us in wishing " + esc(celebrant.getFirstName()) + " a wonderful birthday filled with blessings.</p>");
    }

    /**
     * Anniversary email to the couple themselves.
     *
     * @param coupleNames    display names of the couple (already HTML-escaped)
     * @param anniversaryYear the year the couple married, or {@code null} if unknown
     */
    private String buildAnniversaryCelebrantBody(String coupleNames, Integer anniversaryYear) {
        Integer years = yearsMarried(anniversaryYear);
        String yearsPhrase = years != null
                ? "<p>Congratulations on reaching your <strong>" + ordinal(years)
                    + " wedding anniversary</strong>!</p>"
                : "";
        return wrapInTemplate("Happy Anniversary, " + coupleNames + "! \uD83D\uDC8D",
                "<p>Wishing you a joyful anniversary filled with love and blessings!</p>"
              + yearsPhrase
              + "<p>May your marriage continue to be a blessing to all around you.</p>");
    }

    /**
     * Anniversary email to members / guests.
     *
     * @param coupleNames    display names of the couple (already HTML-escaped)
     * @param anniversaryYear the year the couple married, or {@code null} if unknown
     */
    private String buildAnniversaryAudienceBody(String coupleNames, Integer anniversaryYear) {
        Integer years = yearsMarried(anniversaryYear);
        // With a valid year: "\u2026's 1st wedding anniversary!"; without: "\u2026's wedding anniversary!"
        String yearsClause = years != null
                ? " <strong>" + ordinal(years) + " wedding anniversary</strong>"
                : " wedding anniversary";
        return wrapInTemplate("\uD83D\uDC8D Anniversary Wishes for " + coupleNames,
                "<p>Today is <strong>" + coupleNames + "</strong>\u2019s" + yearsClause + "!</p>"
              + "<p>Please join us in celebrating this special occasion with love and prayers.</p>");
    }

    /**
     * Number of years married, derived from the stored wedding YEAR \u2014 or
     * {@code null} when the ordinal must be omitted from the email.
     *
     * <p>The stored value is only trusted when it is a plausible wedding year
     * (1900 \u2026 current year). This guards against bad data such as a duration
     * ("5") or a mistyped value, which previously produced nonsense like
     * "2021st wedding anniversary". A wedding earlier this year (0 years) also
     * yields {@code null} so no ordinal is shown.
     */
    private Integer yearsMarried(Integer anniversaryYear) {
        if (anniversaryYear == null) return null;
        int currentYear = LocalDate.now().getYear();
        if (anniversaryYear < 1900 || anniversaryYear > currentYear) return null;
        int years = currentYear - anniversaryYear;
        return years > 0 ? years : null;
    }

    /**
     * Returns the English ordinal string for a positive integer.
     * Examples: 1 → "1st", 2 → "2nd", 3 → "3rd", 11 → "11th", 21 → "21st".
     */
    private String ordinal(int n) {
        if (n <= 0) return String.valueOf(n);
        // 11th, 12th, 13th are exceptions to the normal suffix rule
        if (n % 100 >= 11 && n % 100 <= 13) return n + "th";
        switch (n % 10) {
            case 1:  return n + "st";
            case 2:  return n + "nd";
            case 3:  return n + "rd";
            default: return n + "th";
        }
    }

    private String buildMonthlySummaryBody(String monthName,
                                            List<FamilyMember> birthdays,
                                            List<FamilyMember> anniversaries) {
        StringBuilder sb = new StringBuilder();
        sb.append("<p>Here is the summary of birthdays and anniversaries for <strong>")
          .append(esc(monthName)).append("</strong>.</p>");

        sb.append("<h3 style='color:#5c6bc0;'>\uD83C\uDF82 Birthdays</h3>");
        if (birthdays.isEmpty()) {
            sb.append("<p>No birthdays this month.</p>");
        } else {
            sb.append("<ul>");
            for (FamilyMember m : birthdays) {
                sb.append("<li>").append(esc(fullNameWithNick(m)));
                if (m.getBirthdayMonth() != null && m.getBirthdayDay() != null) {
                    sb.append(" — ").append(monthName).append(" ").append(m.getBirthdayDay());
                }
                sb.append("</li>");
            }
            sb.append("</ul>");
        }

        sb.append("<h3 style='color:#5c6bc0;'>\uD83D\uDC8D Anniversaries</h3>");
        if (anniversaries.isEmpty()) {
            sb.append("<p>No anniversaries this month.</p>");
        } else {
            // Group members by family_id so couples appear as one combined entry
            Map<Integer, List<FamilyMember>> byFamily = new LinkedHashMap<>();
            for (FamilyMember m : anniversaries) {
                Integer fid = m.getFamily() != null ? m.getFamily().getId() : null;
                byFamily.computeIfAbsent(fid, k -> new ArrayList<>()).add(m);
            }
            sb.append("<ul>");
            for (Map.Entry<Integer, List<FamilyMember>> e : byFamily.entrySet()) {
                Integer fid = e.getKey();
                List<FamilyMember> queryMembers = e.getValue();

                // Load the full family so BOTH spouses appear even when the anniversary
                // (or the email) is only recorded on one partner.
                List<FamilyMember> familyMembers = (fid != null)
                        ? memberRepo.findActiveMembersByFamilyId(fid)
                        : queryMembers;
                if (familyMembers.isEmpty()) familyMembers = queryMembers;

                FamilyMember primary = familyMembers.stream()
                        .filter(m -> m.getRole() != null && m.getRole().toLowerCase().startsWith("head"))
                        .findFirst().orElse(familyMembers.get(0));
                FamilyMember spouse = familyMembers.stream()
                        .filter(m -> m.getRole() != null
                                && (m.getRole().equalsIgnoreCase("Spouse") || m.getRole().equalsIgnoreCase("Wife")))
                        .findFirst().orElse(null);

                String names = (spouse != null)
                        ? fullNameWithNick(primary) + " & " + fullNameWithNick(spouse)
                        : fullNameWithNick(primary);

                sb.append("<li>").append(esc(names));
                // The anniversary day is on the record returned by the date query.
                FamilyMember dateSource = queryMembers.get(0);
                if (dateSource.getAnniversaryMonth() != null && dateSource.getAnniversaryDay() != null) {
                    sb.append(" — ").append(monthName).append(" ").append(dateSource.getAnniversaryDay());
                }
                sb.append("</li>");
            }
            sb.append("</ul>");
        }
        return wrapInTemplate("\uD83C\uDF89 " + monthName + " Birthdays & Anniversaries", sb.toString());
    }

    private String buildMonthlyStatementBody(String firstName) {
        return wrapInTemplate("\uD83D\uDCC4 Monthly Statement Reminder",
                "<p>Hi " + esc(firstName) + ",</p>"
              + "<p>This is a reminder to generate and review the monthly financial statement.</p>"
              + "<p>Please log in to Church Genius to access the accounting reports.</p>");
    }

    private String buildHolidayBody(String heading, String note) {
        String noteHtml = (note != null && !note.isBlank()) ? "<p>" + esc(note) + "</p>" : "";
        return wrapInTemplate(heading,
                "<p>Warmest greetings on this special occasion from your church community!</p>"
              + noteHtml);
    }

    private String buildOneTimeReminderBody(com.churchgeniuspro.hibernate.OneTimeReminder reminder) {
        String noteHtml = (reminder.getNote() != null && !reminder.getNote().isBlank())
                ? "<p>" + esc(reminder.getNote()) + "</p>" : "";
        return wrapInTemplate(esc(reminder.getName()),
                "<h2 style='color:#3f4568;'>" + esc(reminder.getName()) + "</h2>"
              + noteHtml);
    }

    private String buildWeeklyMeetingBody(LocalDate weekStart, LocalDate weekEnd,
                                           List<Meeting> meetings, String clientId) {
        StringBuilder sb = new StringBuilder();
        sb.append("<p>Here are the meetings scheduled for the week of <strong>")
          .append(weekStart).append("</strong> to <strong>").append(weekEnd).append("</strong>.</p>");
        sb.append("<table style='width:100%;border-collapse:collapse;font-size:13px;margin-top:14px;'>");
        sb.append("<thead><tr>"
                + "<th style='text-align:left;padding:8px 12px;background:#e8eaf6;color:#3f4568;'>Date</th>"
                + "<th style='text-align:left;padding:8px 12px;background:#e8eaf6;color:#3f4568;'>Time</th>"
                + "<th style='text-align:left;padding:8px 12px;background:#e8eaf6;color:#3f4568;'>Meeting</th>"
                + "<th style='text-align:left;padding:8px 12px;background:#e8eaf6;color:#3f4568;'>Location</th>"
                + "<th style='text-align:left;padding:8px 12px;background:#e8eaf6;color:#3f4568;'>Notes</th>"
                + "</tr></thead><tbody>");
        boolean odd = true;
        for (Meeting m : meetings) {
            String bg = odd ? "#ffffff" : "#f8f9ff";
            odd = !odd;
            String typeName = m.getMeetingType() != null ? esc(m.getMeetingType().getTypeName()) : "Meeting";

            // Time: "9:00 AM" or "9:00 AM – 11:00 AM" when end time is present
            String startFmt = formatTime(m.getStartTime());
            String endFmt   = formatTime(m.getEndTime());
            String timeCell = (!startFmt.isEmpty() && !endFmt.isEmpty())
                    ? startFmt + " \u2013 " + endFmt   // en dash
                    : startFmt;

            String location = formatLocationCell(m);

            // Use effective date: Daily shows weekStart (Monday), Weekly shows its day this week
            LocalDate effectiveDate = resolveEffectiveDate(m, weekStart);

            String noteCell = (m.getNote() != null && !m.getNote().isBlank()) ? esc(m.getNote()) : "";
            sb.append("<tr style='background:").append(bg).append(";'>")
              .append("<td style='padding:8px 12px;border-bottom:1px solid #f0f0f0;white-space:nowrap;'>")
              .append(formatDate(effectiveDate)).append("</td>")
              .append("<td style='padding:8px 12px;border-bottom:1px solid #f0f0f0;white-space:nowrap;'>")
              .append(esc(timeCell)).append("</td>")
              .append("<td style='padding:8px 12px;border-bottom:1px solid #f0f0f0;'>")
              .append(typeName).append("</td>")
              .append("<td style='padding:8px 12px;border-bottom:1px solid #f0f0f0;font-size:12px;color:#555;'>")
              .append(location).append("</td>")
              .append("<td style='padding:8px 12px;border-bottom:1px solid #f0f0f0;font-size:12px;color:#555;'>")
              .append(noteCell).append("</td>")
              .append("</tr>");
        }
        sb.append("</tbody></table>");

        // \u2500\u2500 Event Calendar link \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
        String calendarLink = resolveCalendarLink(clientId);
        if (calendarLink != null) {
            sb.append("<div style='margin-top:24px;'>")
              .append("<a href='").append(calendarLink).append("' target='_blank' ")
              .append("style='display:inline-block;padding:10px 22px;background:#5c6bc0;")
              .append("color:#fff;font-size:13px;font-weight:600;border-radius:6px;")
              .append("text-decoration:none;letter-spacing:.3px;'>")
              .append("&#128197; View Calendar</a></div>");
        }

        return wrapInTemplate("\uD83D\uDDD3\uFE0F Meetings This Week", sb.toString());
    }

    /**
     * Returns the public Event Calendar URL for the given org, or {@code null} if the org
     * has not generated a public calendar link (or all links are revoked/expired).
     *
     * <p>Looks up the first active {@code /viewEventCalendar} entry in {@code public_screen_link}
     * and builds the URL the same way {@link com.churchgeniuspro.controller.PublicScreensController}
     * does: {@code {baseUrl}/viewEventCalendar?cid={AES-encrypted clientId}}.
     */
    private String resolveCalendarLink(String clientId) {
        if (clientId == null || clientId.isBlank()) return null;
        List<PublicScreenLink> links =
                publicScreenLinkRepo.findByAppClientIdAndPageUrlStartingWithAndRevokedFalse(
                        clientId, "/viewEventCalendar");
        if (links.isEmpty()) return null;
        // Pick the first non-expired entry (null expiration = never expires)
        PublicScreenLink link = links.stream()
                .filter(l -> l.getExpirationDate() == null
                          || !l.getExpirationDate().isBefore(java.time.LocalDate.now()))
                .findFirst()
                .orElse(null);
        if (link == null) return null;
        try {
            String encCid = EncryptionUtil.encrypt(clientId);
            return baseUrl + "/viewEventCalendar?cid="
                    + URLEncoder.encode(encCid, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String buildMeetingReminderBody(Meeting meeting, String typeName, AutoReminder reminder, LocalDate referenceDate, String clientId) {
        StringBuilder content = new StringBuilder();

        content.append("<p style='font-size:15px;color:#333;margin:0 0 20px;'>")
               .append("Please see the details of today's <strong>")
               .append(esc(typeName)).append("</strong> below:</p>");

        // ── Detail table ────────────────────────────────────────────────────
        content.append("<table style='font-size:14px;color:#444;border-collapse:collapse;width:100%;'>");

        // Date & Time — use effective date so Daily shows today, Weekly shows its next occurrence
        LocalDate effectiveDate = resolveEffectiveDate(meeting, referenceDate);
        String dateStr = formatDate(effectiveDate);
        String timeStr = (meeting.getStartTime() != null && !meeting.getStartTime().isBlank())
                         ? formatTime(meeting.getStartTime()) : "";
        if (!dateStr.isEmpty() || !timeStr.isEmpty()) {
            String dateTime = dateStr + (timeStr.isEmpty() ? "" : " @ " + timeStr);
            content.append("<tr><td style='padding:8px 14px 8px 0;font-weight:600;color:#5c6bc0;")
                   .append("vertical-align:top;white-space:nowrap;'>&#128197; Date &amp; Time:</td>")
                   .append("<td style='padding:8px 0;'>").append(esc(dateTime)).append("</td></tr>");
        }

        // Phone (from the location member, if one is selected)
        if (meeting.getLocationMemberId() != null) {
            String phone = memberRepo.findById(meeting.getLocationMemberId())
                    .filter(fm -> fm.getPhone() != null && !fm.getPhone().isBlank())
                    .map(fm -> fm.getPhone().trim())
                    .orElse("");
            if (!phone.isEmpty()) {
                content.append("<tr><td style='padding:8px 14px 8px 0;font-weight:600;color:#5c6bc0;")
                       .append("vertical-align:top;white-space:nowrap;'>&#128222; Phone:</td>")
                       .append("<td style='padding:8px 0;'>").append(esc(phone)).append("</td></tr>");
            }
        }

        // Location: "Name, 16127 S Bradley Dr, Olathe, KS 66062"
        String locationName = resolveMeetingLocationName(meeting);
        String fullAddress  = buildMeetingFullAddress(meeting);
        if (!locationName.isEmpty() || !fullAddress.isEmpty()) {
            String locationLine = locationName
                    + (!locationName.isEmpty() && !fullAddress.isEmpty() ? ", " : "")
                    + fullAddress;
            content.append("<tr><td style='padding:8px 14px 8px 0;font-weight:600;color:#5c6bc0;")
                   .append("vertical-align:top;white-space:nowrap;'>&#128205; Location:</td>")
                   .append("<td style='padding:8px 0;'>").append(esc(locationLine)).append("</td></tr>");
        }

        // ── Meeting Note ──────────────────────────────────────────────────────
        if (meeting.getNote() != null && !meeting.getNote().isBlank()) {
            content.append("<tr><td style='padding:8px 14px 8px 0;font-weight:600;color:#5c6bc0;")
                   .append("vertical-align:top;white-space:nowrap;'>&#128221; Notes:</td>")
                   .append("<td style='padding:8px 0;'>").append(esc(meeting.getNote())).append("</td></tr>");
        }

        content.append("</table>");

        // ── Location Map button ──────────────────────────────────────────────
        if (!fullAddress.isEmpty()) {
            try {
                String mapsUrl = "https://www.google.com/maps/dir/?api=1&destination="
                        + URLEncoder.encode(fullAddress, StandardCharsets.UTF_8.name());
                content.append("<div style='margin-top:20px;'>")
                       .append("<a href='").append(mapsUrl).append("' target='_blank' ")
                       .append("style='display:inline-block;padding:10px 22px;background:#34A853;")
                       .append("color:#fff;font-size:13px;font-weight:600;border-radius:6px;")
                       .append("text-decoration:none;letter-spacing:.3px;'>")
                       .append("&#128205; View Location Map</a></div>");
            } catch (Exception ignored) {}
        }

        // ── Event Calendar link ──────────────────────────────────────────────
        String calendarLink = resolveCalendarLink(clientId);
        if (calendarLink != null) {
            content.append("<div style='margin-top:20px;'>")
                   .append("<a href='").append(calendarLink).append("' target='_blank' ")
                   .append("style='display:inline-block;padding:10px 22px;background:#5c6bc0;")
                   .append("color:#fff;font-size:13px;font-weight:600;border-radius:6px;")
                   .append("text-decoration:none;letter-spacing:.3px;'>")
                   .append("&#128197; View Calendar</a></div>");
        }

        // ── Note from the auto-reminder (if any) ────────────────────────────
        if (reminder != null && reminder.getNote() != null && !reminder.getNote().isBlank()) {
            content.append("<div style='margin-top:20px;padding:12px 16px;background:#f8f9ff;")
                   .append("border-left:3px solid #5c6bc0;border-radius:4px;font-size:13px;color:#444;'>")
                   .append(esc(reminder.getNote())).append("</div>");
        }

        // ── Image/attachment from auto-reminder (base64 → inlined as CID) ───
        if (reminder != null && reminder.getImageData() != null && !reminder.getImageData().isBlank()) {
            content.append("<div style='margin-top:20px;'>")
                   .append("<img src='").append(reminder.getImageData())
                   .append("' alt='Attachment' style='max-width:100%;border-radius:8px;'/>")
                   .append("</div>");
        }

        // Build a clean card without the "Church Genius" purple header band.
        // The emoji is embedded as a literal character to avoid double-escaping
        // (wrapInTemplate calls esc() on the heading which would corrupt &# entities).
        String heading = "\uD83D\uDCCB Reminder: " + esc(typeName);
        return "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'/>"
             + "<style>"
             + "body{margin:0;padding:0;background:#f5f6fa;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;}"
             + ".wrap{max-width:520px;margin:40px auto;background:#fff;border-radius:16px;box-shadow:0 4px 24px rgba(0,0,0,.08);overflow:hidden;}"
             + ".body{padding:32px 40px;font-size:14px;color:#333;line-height:1.7;}"
             + "</style></head>"
             + "<body><div class='wrap'>"
             + "<div class='body'>"
             + "<h2 style='margin-top:0;color:#3f4568;'>" + heading + "</h2>"
             + content
             + "</div>"
             + "</div></body></html>";
    }

    /** Resolves the display name for a meeting's location (member name or family name). */
    private String resolveMeetingLocationName(Meeting meeting) {
        if (meeting.getLocationMemberId() != null) {
            return memberRepo.findById(meeting.getLocationMemberId())
                    .map(fm -> ((fm.getFirstName() != null ? fm.getFirstName() : "")
                               + " "
                               + (fm.getLastName()  != null ? fm.getLastName()  : "")).trim())
                    .orElse("");
        }
        if (meeting.getLocationFamilyId() != null) {
            return familyRepo.findByIdWithMembers(meeting.getLocationFamilyId())
                    .map(f -> {
                        FamilyMember p = f.getMembers().stream()
                                .filter(fm -> !fm.isDeleteFlag())
                                .filter(fm -> "Head".equalsIgnoreCase(fm.getRole())
                                          || "Head of Household".equalsIgnoreCase(fm.getRole()))
                                .findFirst()
                                .orElse(f.getMembers().stream().filter(fm -> !fm.isDeleteFlag()).findFirst().orElse(null));
                        String ln = p != null && p.getLastName()  != null ? p.getLastName()  : "";
                        String fn = p != null && p.getFirstName() != null ? p.getFirstName() : "";
                        return (ln.isBlank() ? fn : ln + " Family").trim();
                    })
                    .orElse("");
        }
        return "";
    }

    /** Builds a single comma-separated address line from meeting address fields (e.g. "16127 S Bradley Dr, Olathe, KS 66062"). */
    private String buildMeetingFullAddress(Meeting meeting) {
        List<String> parts = new ArrayList<>();
        if (meeting.getAddress1() != null && !meeting.getAddress1().isBlank())
            parts.add(meeting.getAddress1().trim());
        if (meeting.getAddress2() != null && !meeting.getAddress2().isBlank())
            parts.add(meeting.getAddress2().trim());
        if (meeting.getCity() != null && !meeting.getCity().isBlank())
            parts.add(meeting.getCity().trim());
        StringBuilder statePart = new StringBuilder();
        if (meeting.getState() != null && !meeting.getState().isBlank()) {
            String stateVal = meeting.getState().trim();
            try {
                String abbr = States.getCode(Integer.parseInt(stateVal));
                if (abbr != null) stateVal = abbr;
            } catch (NumberFormatException ignored) {}
            statePart.append(stateVal);
        }
        if (meeting.getPinCode() != null && !meeting.getPinCode().isBlank()) {
            if (statePart.length() > 0) statePart.append(" ");
            statePart.append(meeting.getPinCode().trim());
        }
        if (statePart.length() > 0) parts.add(statePart.toString());
        return String.join(", ", parts);
    }

    /**
     * Returns the phone number of the location contact for a meeting.
     * Checks the selected member first; falls back to the head of the selected family.
     */
    private String resolveMeetingPhone(Meeting meeting) {
        if (meeting.getLocationMemberId() != null) {
            return memberRepo.findById(meeting.getLocationMemberId())
                    .filter(fm -> fm.getPhone() != null && !fm.getPhone().isBlank())
                    .map(fm -> fm.getPhone().trim())
                    .orElse("");
        }
        if (meeting.getLocationFamilyId() != null) {
            return familyRepo.findByIdWithMembers(meeting.getLocationFamilyId())
                    .map(f -> {
                        FamilyMember head = f.getMembers().stream()
                                .filter(fm -> !fm.isDeleteFlag())
                                .filter(fm -> "Head".equalsIgnoreCase(fm.getRole())
                                          || "Head of Household".equalsIgnoreCase(fm.getRole()))
                                .findFirst()
                                .orElse(f.getMembers().stream()
                                        .filter(fm -> !fm.isDeleteFlag()).findFirst().orElse(null));
                        return (head != null && head.getPhone() != null && !head.getPhone().isBlank())
                                ? head.getPhone().trim() : "";
                    })
                    .orElse("");
        }
        return "";
    }

    /**
     * Builds the template variable map for a Twilio Content Template meeting reminder.
     *
     * <p>Variable mapping:
     * <ul>
     *   <li>{@code churchname}  — org display name from email_settings</li>
     *   <li>{@code name}        — recipient's first + last name (resolved per-send; use placeholder here)</li>
     *   <li>{@code dateandtime} — meeting date and time in 12-hour format (e.g. "April 21, 2026 at 7:30 PM")</li>
     *   <li>{@code location}    — host name + full address on two lines</li>
     *   <li>{@code direction}   — Google Maps directions link for the meeting address</li>
     * </ul>
     *
     * <p>Note: {@code name} is set to a generic placeholder here because the template
     * is sent via {@link WhatsAppSenderService#sendTemplateToRecipients} which resolves
     * phone numbers but not individual names.  If per-recipient personalisation is needed,
     * switch to a per-phone loop using {@link WhatsAppSenderService#sendTemplateToPhone}.
     */
    private Map<String, String> buildMeetingTemplateVariables(Meeting meeting, String clientId, LocalDate referenceDate) {
        // churchname — resolved via WhatsAppSenderService which already has EmailSettingsRepository
        String churchName = whatsAppSender.resolveChurchNameForSms(clientId);

        // dateandtime — use effective date so Daily shows today, Weekly shows next occurrence
        LocalDate effectiveDate = resolveEffectiveDate(meeting, referenceDate);
        String dateAndTime = effectiveDate.format(DateTimeFormatter.ofPattern("MMMM d, yyyy"));
        if (meeting.getStartTime() != null && !meeting.getStartTime().isBlank()) {
            dateAndTime += " at " + formatTime(meeting.getStartTime());
        }

        // location — "FirstName LastName\nAddress Line 1, Address Line 2\nCity, State Zip"
        String locationName = resolveMeetingLocationName(meeting);
        String address      = buildMeetingFullAddress(meeting);
        String location;
        if (!locationName.isEmpty() && !address.isEmpty()) {
            location = locationName + "\n" + address;
        } else if (!locationName.isEmpty()) {
            location = locationName;
        } else if (!address.isEmpty()) {
            location = address;
        } else {
            location = "—";
        }

        // direction — Google Maps link
        String direction = "—";
        if (!address.isEmpty()) {
            try {
                direction = "https://www.google.com/maps/dir/?api=1&destination="
                        + URLEncoder.encode(address, StandardCharsets.UTF_8.name());
            } catch (Exception ignored) {}
        }

        // calendar — public event calendar link (empty string when not configured)
        String calendarLink = resolveCalendarLink(clientId);

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("churchname",  churchName);
        vars.put("name",        "Valued Member");   // personalised per-recipient if needed later
        vars.put("dateandtime", dateAndTime);
        vars.put("location",    location);
        vars.put("direction",   direction);
        vars.put("calendar",    calendarLink != null ? calendarLink : "");
        return vars;
    }

    /**
     * Builds a plain-text WhatsApp / SMS message for a single meeting, including:
     * meeting type, date &amp; time, host name, address, phone, and a Google Maps link.
     */
    private String buildMeetingWhatsAppMessage(Meeting meeting, LocalDate referenceDate, String clientId) {
        String typeName = meeting.getMeetingType() != null
                ? meeting.getMeetingType().getTypeName() : "Meeting";
        // Use effective date: Daily → referenceDate, Weekly → next occurrence on/after referenceDate
        LocalDate effectiveDate = resolveEffectiveDate(meeting, referenceDate);
        String dateStr = effectiveDate.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"));
        String timeStr  = (meeting.getStartTime() != null && !meeting.getStartTime().isBlank())
                ? formatTime(meeting.getStartTime()) : "";

        String locationName = resolveMeetingLocationName(meeting);
        String phone        = resolveMeetingPhone(meeting);
        String address      = buildMeetingFullAddress(meeting);

        StringBuilder sb = new StringBuilder();
        sb.append("\uD83D\uDDD3\uFE0F Meeting Reminder: ").append(typeName).append("\n");
        if (!dateStr.isEmpty()) {
            sb.append("\uD83D\uDCC5 ").append(dateStr);
            if (!timeStr.isEmpty()) sb.append(" at ").append(timeStr);
            sb.append("\n");
        }

        if (!locationName.isEmpty() || !address.isEmpty() || !phone.isEmpty()) {
            sb.append("\n\uD83D\uDCCD Location:\n");
            if (!locationName.isEmpty()) sb.append(locationName).append("\n");
            if (!address.isEmpty())      sb.append(address).append("\n");
            if (!phone.isEmpty())        sb.append("\uD83D\uDCDE ").append(phone).append("\n");
        }

        if (!address.isEmpty()) {
            try {
                String mapsUrl = "https://www.google.com/maps/dir/?api=1&destination="
                        + URLEncoder.encode(address, StandardCharsets.UTF_8.name());
                sb.append("\n\uD83D\uDDFA\uFE0F Directions: ").append(mapsUrl);
            } catch (Exception ignored) {}
        }

        // \u2500\u2500 Event Calendar link \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
        String calendarLink = resolveCalendarLink(clientId);
        if (calendarLink != null) {
            sb.append("\n\n\uD83D\uDCC5 View Calendar: ").append(calendarLink);
        }

        return sb.toString();
    }

    // NOTE: wrapInTemplate() esc()-escapes the heading itself, so headings are
    // passed as PLAIN text (decoded, un-escaped) — passing esc()'d text here
    // would double-escape and render "&amp;" literally in the email.

    private String buildEventSameDayBody(ChurchEvent event) {
        String name = eventDisplayName(event);
        return wrapInTemplate("Reminder: " + name + " is Today!",
                "<p>Thank you for attending <strong>" + esc(name) + "</strong>. "
              + "The event will take place <strong>today</strong>. See you soon!</p>"
              + buildEventDetails(event, true));
    }

    private String buildEventBeforeDaysBody(ChurchEvent event, int beforeDays) {
        String name = eventDisplayName(event);
        return wrapInTemplate("Reminder: " + name + " in " + beforeDays + " day(s)",
                "<p>Thank you for attending <strong>" + esc(name) + "</strong>. "
              + "The event will take place in <strong>" + beforeDays + " day(s)</strong>.</p>"
              + buildEventDetails(event, false));
    }

    private String buildEventAfterDaysBody(ChurchEvent event) {
        String name = eventDisplayName(event);
        return wrapInTemplate("Hope you enjoyed " + name + "!",
                "<p>Thank you for attending <strong>" + esc(name) + "</strong>. "
              + "Hope you had a wonderful time!</p>");
    }

    /** {@code true} when a custom email template has been configured (non-blank). */
    private boolean hasTemplate(String template) {
        return template != null && !template.isBlank();
    }

    /**
     * Renders a custom event-email template by substituting the supported dynamic
     * placeholders with event-specific values, then wraps the result in the
     * standard branded email shell.
     *
     * <p>Supported placeholders: {@code {eventName}}, {@code {date}},
     * {@code {time}}, {@code {location}}, {@code {map}} (a link to the location on
     * Google Maps), {@code {calendar}} (an add-to-calendar link), and
     * {@code {beforeDays}} (the day count for the timing — the before/after day
     * number, or {@code 0} for same-day reminders).
     *
     * <p>Plain-text placeholder values are HTML-escaped; {@code {map}} and
     * {@code {calendar}} expand to HTML anchor links. Author newlines in the
     * template are preserved as {@code <br/>}.
     *
     * @param template the raw template body authored by the user
     * @param event    the event the reminder is for
     * @param dayCount day count substituted for {@code {beforeDays}}
     */
    private String renderEventTemplate(String template, ChurchEvent event, int dayCount) {
        String eventName = event.getEventName() != null ? decodeEntities(event.getEventName()) : "the event";

        String dateStr = event.getEventDate() != null ? formatDate(event.getEventDate()) : "";

        String timeStr = "";
        if (event.getStartTime() != null && !event.getStartTime().isBlank()) {
            timeStr = formatTime(event.getStartTime());
            if (event.getEndTime() != null && !event.getEndTime().isBlank()) {
                timeStr += " to " + formatTime(event.getEndTime());
            }
        }

        String location = buildFullAddress(event);

        String mapUrl = eventMapUrl(event);
        String mapHtml = mapUrl.isEmpty()
                ? esc(location)
                : "<a href='" + mapUrl + "' target='_blank'>"
                  + (location.isEmpty() ? "View location on map" : esc(location)) + "</a>";

        String calUrl = eventGoogleCalendarUrl(event);
        String calHtml = calUrl.isEmpty()
                ? ""
                : "<a href='" + calUrl + "' target='_blank'>Add to Calendar</a>";

        String rendered = template
                .replace("{eventName}", esc(eventName))
                .replace("{date}",      esc(dateStr))
                .replace("{time}",      esc(timeStr))
                .replace("{location}",  esc(location))
                .replace("{map}",       mapHtml)
                .replace("{calendar}",  calHtml)
                .replace("{beforeDays}", String.valueOf(dayCount));

        // Preserve author line breaks from the textarea.
        rendered = rendered.replace("\r\n", "\n").replace("\n", "<br/>");

        // Plain heading — wrapInTemplate esc()-escapes it itself.
        return wrapInTemplate(eventName, rendered);
    }

    /**
     * Returns a Google Maps directions URL for the event's address, or an empty
     * string when the event has no address.
     */
    private String eventMapUrl(ChurchEvent event) {
        String fullAddr = buildFullAddress(event);
        if (fullAddr.isEmpty()) return "";
        try {
            return "https://www.google.com/maps/dir/?api=1&destination="
                    + URLEncoder.encode(fullAddr, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Returns a Google Calendar "add event" URL for the event, or an empty string
     * when the event has no date.
     */
    private String eventGoogleCalendarUrl(ChurchEvent event) {
        if (event.getEventDate() == null) return "";
        try {
            LocalDate date    = event.getEventDate();
            String    dateStr = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String    dtStart, dtEnd;
            if (event.getStartTime() != null && !event.getStartTime().isBlank()) {
                dtStart = dateStr + "T" + event.getStartTime().replace(":", "") + "00";
                if (event.getEndTime() != null && !event.getEndTime().isBlank()) {
                    dtEnd = dateStr + "T" + event.getEndTime().replace(":", "") + "00";
                } else {
                    // No configured end time — mirror the start (no invented +1h).
                    dtEnd = dtStart;
                }
            } else {
                dtStart = dateStr;
                dtEnd   = date.plusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            }
            String name    = event.getEventName() != null ? decodeEntities(event.getEventName()) : "";
            String details = "You are registered for " + name;
            String enc     = StandardCharsets.UTF_8.name();
            return "https://calendar.google.com/calendar/render?action=TEMPLATE"
                    + "&text="     + URLEncoder.encode(name, enc)
                    + "&dates="    + dtStart + "/" + dtEnd
                    + "&details="  + URLEncoder.encode(details, enc)
                    + "&location=" + URLEncoder.encode(buildFullAddress(event), enc);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Builds the event details block (date, time, location, action buttons, image).
     *
     * @param isToday when {@code true} the time row reads "Today, 3:00 PM to 4:00 PM";
     *                when {@code false} it reads "3:00 PM to 4:00 PM".
     */
    private String buildEventDetails(ChurchEvent event, boolean isToday) {
        StringBuilder sb = new StringBuilder("<table style='font-size:13px;color:#555;margin-top:16px;'>");
        if (event.getEventDate() != null) {
            sb.append("<tr><td style='padding:4px 12px 4px 0;font-weight:600;'>Date:</td><td>")
              .append(esc(formatDate(event.getEventDate()))).append("</td></tr>");
        }
        if (event.getStartTime() != null && !event.getStartTime().isBlank()) {
            String time = formatTime(event.getStartTime());
            if (event.getEndTime() != null && !event.getEndTime().isBlank()) {
                time += " to " + formatTime(event.getEndTime());
            }
            if (isToday) time = "Today, " + time;
            sb.append("<tr><td style='padding:4px 12px 4px 0;font-weight:600;'>Time:</td><td>")
              .append(esc(time)).append("</td></tr>");
        }
        // Full address on one line: "16127 S Bradley Dr, Olathe, KS 66062"
        String fullAddress = buildFullAddress(event);
        if (!fullAddress.isEmpty()) {
            sb.append("<tr><td style='padding:4px 12px 4px 0;font-weight:600;'>Location:</td><td>")
              .append(esc(fullAddress)).append("</td></tr>");
        }
        sb.append("</table>");
        // Action buttons: Add to Calendar + Get Directions
        sb.append(buildCalendarLinksHtml(event));
        // Event image (rendered via CID to work in all email clients)
        if (event.getImageData() != null && !event.getImageData().isBlank()) {
            sb.append("<div style='margin-top:16px;'><img src='").append(event.getImageData())
              .append("' alt='Event' style='max-width:100%;border-radius:8px;'/></div>");
        }
        return sb.toString();
    }

    /**
     * Builds a single comma-separated address string from the event's address fields.
     * Format: "16127 S Bradley Dr, Olathe, KS 66062"
     */
    private String buildFullAddress(ChurchEvent event) {
        List<String> parts = new ArrayList<>();
        if (event.getAddress1() != null && !event.getAddress1().isBlank())
            parts.add(event.getAddress1().trim());
        if (event.getAddress2() != null && !event.getAddress2().isBlank())
            parts.add(event.getAddress2().trim());
        if (event.getCity() != null && !event.getCity().isBlank())
            parts.add(event.getCity().trim());
        StringBuilder statePart = new StringBuilder();
        if (event.getState() != null && !event.getState().isBlank()) {
            String stateVal = event.getState().trim();
            try {
                String abbr = States.getCode(Integer.parseInt(stateVal));
                if (abbr != null) stateVal = abbr;
            } catch (NumberFormatException ignored) { /* already an abbreviation */ }
            statePart.append(stateVal);
        }
        if (event.getPinCode() != null && !event.getPinCode().isBlank()) {
            if (statePart.length() > 0) statePart.append(" ");
            statePart.append(event.getPinCode().trim());
        }
        if (statePart.length() > 0) parts.add(statePart.toString());
        return String.join(", ", parts);
    }

    /**
     * Generates an HTML block containing Google Calendar and Outlook.com "Add to Calendar"
     * links, plus a note about the attached .ics file.  Returns an empty string when the
     * event has no date.
     */
    private String buildCalendarLinksHtml(ChurchEvent event) {
        if (event.getEventDate() == null) return "";
        try {
            LocalDate date    = event.getEventDate();
            String   dateStr  = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String   dtStart, dtEnd;

            if (event.getStartTime() != null && !event.getStartTime().isBlank()) {
                String startFmt = event.getStartTime().replace(":", "");
                dtStart = dateStr + "T" + startFmt + "00";
                if (event.getEndTime() != null && !event.getEndTime().isBlank()) {
                    dtEnd = dateStr + "T" + event.getEndTime().replace(":", "") + "00";
                } else {
                    // No configured end time — use the start time so the calendar
                    // entry never shows an end time the church didn't set.
                    dtEnd = dtStart;
                }
            } else {
                dtStart = dateStr;
                dtEnd   = date.plusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            }

            // Location string — buildFullAddress converts numeric state codes
            // to abbreviations (e.g. "16" → "KS").
            String loc = buildFullAddress(event);

            String name    = eventDisplayName(event);
            String details = "You are registered for " + name;
            String enc     = StandardCharsets.UTF_8.name();

            String googleLink = "https://calendar.google.com/calendar/render?action=TEMPLATE"
                    + "&text="     + URLEncoder.encode(name,    enc)
                    + "&dates="    + dtStart + "/" + dtEnd
                    + "&details="  + URLEncoder.encode(details, enc)
                    + "&location=" + URLEncoder.encode(loc, enc);

            // Outlook datetime: ISO format (2024-12-25T14:30:00)
            String outlookStart = date.toString()
                    + (event.getStartTime() != null && !event.getStartTime().isBlank()
                       ? "T" + event.getStartTime() + ":00" : "");
            String outlookEnd;
            if (event.getEndTime() != null && !event.getEndTime().isBlank()) {
                outlookEnd = date.toString() + "T" + event.getEndTime() + ":00";
            } else if (event.getStartTime() != null && !event.getStartTime().isBlank()) {
                // No configured end time — mirror the start time (no invented +1h).
                outlookEnd = outlookStart;
            } else {
                outlookEnd = date.plusDays(1).toString();
            }
            String outlookLink = "https://outlook.live.com/calendar/0/deeplink/compose"
                    + "?subject="  + URLEncoder.encode(name,         enc)
                    + "&startdt="  + URLEncoder.encode(outlookStart, enc)
                    + "&enddt="    + URLEncoder.encode(outlookEnd,   enc)
                    + "&body="     + URLEncoder.encode(details,      enc)
                    + "&location=" + URLEncoder.encode(loc, enc);

            // Shared inline style for the action buttons. display:inline-block +
            // margins (instead of table cells) lets the buttons WRAP on narrow
            // mobile screens so they stay inside the rounded content box.
            String btnStyle = "display:inline-block;padding:8px 18px;color:#ffffff;"
                    + "border-radius:6px;font-size:12px;font-weight:600;text-decoration:none;"
                    + "letter-spacing:.3px;margin:0 8px 8px 0;";

            // Get Directions link (only when an address is available)
            String directionsHtml = "";
            if (!loc.isEmpty()) {
                String mapsLink = "https://www.google.com/maps/dir/?api=1&destination="
                        + URLEncoder.encode(loc, enc);
                directionsHtml = "<a href='" + mapsLink + "' target='_blank'"
                        + " style='" + btnStyle + "background:#34A853;'>&#128205; Get Directions</a>";
            }

            return "<div style='margin-top:20px;padding:16px 20px;background:#f0f4ff;"
                    + "border-radius:8px;border:1px solid #c5cae9;'>"
                    + "<p style='margin:0 0 12px;font-size:13px;font-weight:700;color:#5c6bc0;'>"
                    + "&#128197; Add to Your Calendar</p>"
                    + "<div>"
                    + "<a href='" + googleLink + "' target='_blank'"
                    + " style='" + btnStyle + "background:#4285F4;'>&#128197; Google Calendar</a>"
                    + "<a href='" + outlookLink + "' target='_blank'"
                    + " style='" + btnStyle + "background:#0078D4;'>&#128197; Outlook Calendar</a>"
                    + directionsHtml
                    + "</div>"
                    + "<p style='margin:2px 0 0;font-size:11px;color:#888;line-height:1.5;'>"
                    + "An <strong>.ics</strong> calendar file is attached to this email — open it to add "
                    + "the event to Apple Calendar, Google Calendar desktop, or any other calendar app."
                    + "</p></div>";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Generates an ICS (iCalendar) file for the given event.
     * Returns {@code null} when the event has no date.
     */
    private byte[] buildIcsContent(ChurchEvent event) {
        if (event.getEventDate() == null) return null;
        LocalDate date    = event.getEventDate();
        String    dateStr = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String    dtStart, dtEnd;
        boolean   hasTime = event.getStartTime() != null && !event.getStartTime().isBlank();

        if (hasTime) {
            String startFmt = event.getStartTime().replace(":", "");
            dtStart = "DTSTART:" + dateStr + "T" + startFmt + "00";
            if (event.getEndTime() != null && !event.getEndTime().isBlank()) {
                dtEnd = "DTEND:" + dateStr + "T" + event.getEndTime().replace(":", "") + "00";
            } else {
                // No end time configured — omit DTEND entirely (RFC 5545: the event
                // then ends at DTSTART). Do NOT invent a one-hour end time: email
                // clients render the ICS as e.g. "Today • 6:00 PM – 7:00 PM", showing
                // an end time the church never configured.
                dtEnd = null;
            }
        } else {
            dtStart = "DTSTART;VALUE=DATE:" + dateStr;
            dtEnd   = "DTEND;VALUE=DATE:"   + date.plusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        }

        // Build location — reuse buildFullAddress so numeric state codes are
        // converted to abbreviations (e.g. "16" → "KS"), then append the country.
        String location = buildFullAddress(event);
        if (event.getCountry() != null && !event.getCountry().isBlank()) {
            location = location.isEmpty()
                    ? event.getCountry().trim()
                    : location + ", " + event.getCountry().trim();
        }

        String uid     = "cgp-event-" + event.getId() + "@churchgeniuspro";
        String summary = eventDisplayName(event)
                         .replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,");
        String desc    = "";
        if (event.getNote() != null && !event.getNote().isBlank()) {
            desc = decodeEntities(event.getNote().trim())
                        .replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                        .replace("\n", "\\n").replace("\r", "");
        }
        String locEsc  = location.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,");

        StringBuilder ics = new StringBuilder();
        ics.append("BEGIN:VCALENDAR\r\n");
        ics.append("VERSION:2.0\r\n");
        ics.append("PRODID:-//ChurchGeniusPro//EN\r\n");
        ics.append("CALSCALE:GREGORIAN\r\n");
        ics.append("METHOD:PUBLISH\r\n");
        ics.append("BEGIN:VEVENT\r\n");
        ics.append("UID:").append(uid).append("\r\n");
        ics.append(dtStart).append("\r\n");
        if (dtEnd != null) ics.append(dtEnd).append("\r\n");
        ics.append("SUMMARY:").append(summary).append("\r\n");
        if (!desc.isEmpty())     ics.append("DESCRIPTION:").append(desc).append("\r\n");
        if (!locEsc.isEmpty())   ics.append("LOCATION:").append(locEsc).append("\r\n");
        ics.append("STATUS:CONFIRMED\r\n");
        ics.append("END:VEVENT\r\n");
        ics.append("END:VCALENDAR\r\n");

        return ics.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // =========================================================================
    // Follow-Up — mark overdue PENDING items as MISSED — daily at 01:00
    // =========================================================================

    /**
     * Runs once per day at 01:00 CST.
     * Finds all PENDING follow-ups whose {@code due_date} is strictly before today
     * and marks them as {@code MISSED} across all orgs in a single UPDATE query.
     */
    @Scheduled(cron = "0 0 1 * * *", zone = "America/Chicago")
    public void markOverdueFollowUps() {
        java.util.Date today = java.sql.Date.valueOf(LocalDate.now());
        java.util.Date now   = new java.util.Date();
        int updated = followUpRepo.markOverdueMissed(today, now);
        if (updated > 0) {
            System.out.println("[FollowUp] Marked " + updated + " overdue follow-up(s) as MISSED.");
        }
    }
}
