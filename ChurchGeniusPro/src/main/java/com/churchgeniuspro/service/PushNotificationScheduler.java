package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.PushSubscription;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.MemberMessageRepository;
import com.churchgeniuspro.repository.PushSubscriptionRepository;
import com.churchgeniuspro.repository.ChurchEventRepository;
import com.churchgeniuspro.repository.MeetingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Scheduled service that fires Web Push notifications when the user's browser
 * may be in the background.
 *
 * <h3>Schedule</h3>
 * <ul>
 *   <li>Every minute   — unread message badge for members</li>
 *   <li>Daily at 07:00 — upcoming events / meetings / birthdays / anniversaries for all subscribers</li>
 * </ul>
 *
 * <p>All jobs are no-ops when push is not configured
 * ({@link WebPushService#isEnabled()} returns {@code false}).
 *
 * <p>All sends use the {@code logAndSend*} variants of {@link WebPushService} so
 * every notification is recorded in {@code push_notification_log} and counted
 * in the user's badge.
 */
@Service
public class PushNotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationScheduler.class);

    private final WebPushService             pushService;
    private final PushSubscriptionRepository pushSubRepo;
    private final MemberMessageRepository    messageRepo;
    private final FamilyMemberRepository     familyMemberRepo;
    private final ChurchEventRepository      eventRepo;
    private final MeetingRepository          meetingRepo;

    public PushNotificationScheduler(WebPushService pushService,
                                     PushSubscriptionRepository pushSubRepo,
                                     MemberMessageRepository messageRepo,
                                     FamilyMemberRepository familyMemberRepo,
                                     ChurchEventRepository eventRepo,
                                     MeetingRepository meetingRepo) {
        this.pushService      = pushService;
        this.pushSubRepo      = pushSubRepo;
        this.messageRepo      = messageRepo;
        this.familyMemberRepo = familyMemberRepo;
        this.eventRepo        = eventRepo;
        this.meetingRepo      = meetingRepo;
    }

    // ── Every minute: unread-message notifications for members ───────────────

    /**
     * Checks every minute whether any member subscriber has unread messages
     * and fires a push notification if so.  Uses tag "cgp-unread-messages" so
     * only one notification is shown in the notification centre even if multiple
     * checks fire before the user looks.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void notifyUnreadMessages() {
        if (!pushService.isEnabled()) return;

        List<PushSubscription> allMemberSubs = pushSubRepo.findByUserTypeAndActiveTrue("member");

        // Group by userKey — one notification per user regardless of how many devices
        Map<String, List<PushSubscription>> byUser = allMemberSubs.stream()
                .collect(Collectors.groupingBy(PushSubscription::getUserKey));

        for (Map.Entry<String, List<PushSubscription>> entry : byUser.entrySet()) {
            String userKey = entry.getKey();
            Optional<FamilyMember> fm = familyMemberRepo.findByMemberRef(userKey);
            if (fm.isEmpty()) continue;

            long unread = messageRepo.countUnread(fm.get().getId());
            if (unread <= 0) continue;

            String body = unread == 1
                    ? "You have 1 unread message."
                    : "You have " + unread + " unread messages.";

            String appClientId = entry.getValue().get(0).getAppClientId();

            pushService.logAndSendToUser(
                    userKey, appClientId, "member",
                    "📩 New Messages",
                    body,
                    "/memberHome",
                    "cgp-unread-messages");
        }
    }

    // ── Daily at 07:00: events, meetings, birthdays, anniversaries ────────────

    /**
     * Fires targeted daily morning pushes to all subscribers:
     * <ul>
     *   <li>Birthdays today / tomorrow — broadcast to org</li>
     *   <li>Anniversaries today / tomorrow — broadcast to org</li>
     *   <li>Meetings today — push to org</li>
     *   <li>Events within next 7 days — push to org</li>
     * </ul>
     *
     * <p>Uses distinct notification tags per type so OS notification centres
     * collapse duplicates correctly and users see one card per category.
     */
    @Scheduled(cron = "0 0 7 * * *")
    public void notifyDailyDigest() {
        if (!pushService.isEnabled()) return;

        LocalDate today    = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);
        LocalDate in7      = today.plusDays(7);

        // Collect distinct orgs that have at least one active subscriber
        Set<String> orgIds = pushSubRepo.findAll().stream()
                .filter(PushSubscription::isActive)
                .map(PushSubscription::getAppClientId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        for (String appClientId : orgIds) {

            // ── Birthdays today / tomorrow ─────────────────────────────────
            List<FamilyMember> birthdayToday    = membersWithBirthday(appClientId, today);
            List<FamilyMember> birthdayTomorrow = membersWithBirthday(appClientId, tomorrow);

            if (!birthdayToday.isEmpty()) {
                String names = joinNames(birthdayToday);
                pushService.logAndSendToOrg(appClientId,
                        "🎂 Birthday Today!",
                        names + (birthdayToday.size() == 1 ? " has" : " have")
                                + " a birthday today — send your wishes!",
                        "/home", "cgp-birthday");
            }
            if (!birthdayTomorrow.isEmpty()) {
                String names = joinNames(birthdayTomorrow);
                pushService.logAndSendToOrg(appClientId,
                        "🎂 Birthday Tomorrow",
                        names + (birthdayTomorrow.size() == 1 ? "'s" : "'")
                                + " birthday is tomorrow.",
                        "/home", "cgp-birthday-tomorrow");
            }

            // ── Anniversaries today / tomorrow ────────────────────────────
            List<FamilyMember> annivToday    = membersWithAnniversary(appClientId, today);
            List<FamilyMember> annivTomorrow = membersWithAnniversary(appClientId, tomorrow);

            if (!annivToday.isEmpty()) {
                String names = joinNames(annivToday);
                pushService.logAndSendToOrg(appClientId,
                        "💍 Anniversary Today!",
                        names + (annivToday.size() == 1 ? " is" : " are")
                                + " celebrating an anniversary today!",
                        "/home", "cgp-anniversary");
            }
            if (!annivTomorrow.isEmpty()) {
                String names = joinNames(annivTomorrow);
                pushService.logAndSendToOrg(appClientId,
                        "💍 Anniversary Tomorrow",
                        names + (annivTomorrow.size() == 1 ? "'s" : "'")
                                + " anniversary is tomorrow.",
                        "/home", "cgp-anniversary-tomorrow");
            }

            // ── Meetings today ─────────────────────────────────────────────
            List<String> meetingLines = meetingRepo
                    .findByAppClientIdAndDeleteFlagFalse(appClientId)
                    .stream()
                    .filter(m -> m.getMeetingDate() != null && m.getMeetingDate().equals(today))
                    .map(m -> {
                        String type = m.getMeetingType() != null
                                    ? m.getMeetingType().getTypeName() : "Meeting";
                        String time = m.getStartTime() != null ? " at " + m.getStartTime() : "";
                        return type + time;
                    })
                    .collect(Collectors.toList());

            if (!meetingLines.isEmpty()) {
                String body = meetingLines.size() == 1
                        ? meetingLines.get(0) + " is scheduled for today."
                        : meetingLines.size() + " meetings scheduled for today.";
                pushService.logAndSendToOrg(appClientId,
                        "🏛 Meeting Today",
                        body,
                        "/meetings", "cgp-meeting-today");
            }

            // ── Events within the next 7 days ──────────────────────────────
            List<String> eventLines = eventRepo
                    .findByAppClientIdAndDeleteFlagFalseOrderByCreatedDateDesc(appClientId)
                    .stream()
                    .filter(e -> e.getEventDate() != null
                              && !e.getEventDate().isBefore(today)
                              && !e.getEventDate().isAfter(in7))
                    .map(e -> e.getEventName() + " — " + formatDate(e.getEventDate(), today))
                    .collect(Collectors.toList());

            if (!eventLines.isEmpty()) {
                String body = eventLines.size() == 1
                        ? "📅 " + eventLines.get(0)
                        : "📅 " + eventLines.size() + " upcoming events this week.";
                pushService.logAndSendToOrg(appClientId,
                        "📅 Upcoming Events",
                        body,
                        "/event", "cgp-upcoming-events");
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<FamilyMember> membersWithBirthday(String appClientId, LocalDate date) {
        return familyMemberRepo.findAllWithFamilyByAppUser(appClientId).stream()
                .filter(fm -> fm.getBirthdayMonth() != null && fm.getBirthdayDay() != null
                           && fm.getBirthdayMonth() == date.getMonthValue()
                           && fm.getBirthdayDay()   == date.getDayOfMonth()
                           && !fm.isDeleteFlag())
                .collect(Collectors.toList());
    }

    private List<FamilyMember> membersWithAnniversary(String appClientId, LocalDate date) {
        return familyMemberRepo.findAllWithFamilyByAppUser(appClientId).stream()
                .filter(fm -> fm.getAnniversaryMonth() != null && fm.getAnniversaryDay() != null
                           && fm.getAnniversaryMonth() == date.getMonthValue()
                           && fm.getAnniversaryDay()   == date.getDayOfMonth()
                           && !fm.isDeleteFlag())
                .collect(Collectors.toList());
    }

    private String joinNames(List<FamilyMember> members) {
        List<String> names = members.stream()
                .map(fm -> trim(fm.getFirstName()) + " " + trim(fm.getLastName()))
                .distinct()
                .collect(Collectors.toList());
        if (names.size() == 1) return names.get(0);
        if (names.size() == 2) return names.get(0) + " and " + names.get(1);
        return names.get(0) + ", " + names.get(1) + " and " + (names.size() - 2) + " others";
    }

    private String formatDate(LocalDate d, LocalDate today) {
        if (d == null) return "?";
        if (d.equals(today))             return "today";
        if (d.equals(today.plusDays(1))) return "tomorrow";
        return d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
               + " " + d.getDayOfMonth();
    }

    private String trim(String s) { return s != null ? s.trim() : ""; }
}
