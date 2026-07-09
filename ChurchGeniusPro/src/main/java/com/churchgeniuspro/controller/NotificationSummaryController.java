package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.MemberMessageRepository;
import com.churchgeniuspro.repository.ChurchEventRepository;
import com.churchgeniuspro.repository.MeetingRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Returns a lightweight notification summary that the frontend polls every
 * minute to update badge counts — no push required for foreground tabs.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET /api/notifications/summary} — for member portal sessions</li>
 *   <li>{@code GET /api/notifications/staff-summary} — for staff (app user) sessions</li>
 * </ul>
 *
 * <p>Both return:
 * <pre>
 * {
 *   "unreadMessages": 3,
 *   "upcomingEvents": 1,
 *   "upcomingMeetings": 2,
 *   "upcomingBirthdays": 1,
 *   "total": 7
 * }
 * </pre>
 */
@RestController
public class NotificationSummaryController {

    private final MemberMessageRepository  messageRepo;
    private final FamilyMemberRepository   familyMemberRepo;
    private final ChurchEventRepository    eventRepo;
    private final MeetingRepository        meetingRepo;

    public NotificationSummaryController(MemberMessageRepository messageRepo,
                                         FamilyMemberRepository familyMemberRepo,
                                         ChurchEventRepository eventRepo,
                                         MeetingRepository meetingRepo) {
        this.messageRepo     = messageRepo;
        this.familyMemberRepo = familyMemberRepo;
        this.eventRepo       = eventRepo;
        this.meetingRepo     = meetingRepo;
    }

    // ── Member portal summary ─────────────────────────────────────────────────

    /**
     * Notification summary for a logged-in member.
     * Counts:  unread messages, events in next 7 days,
     *          meetings in next 7 days, birthdays today or tomorrow.
     */
    @GetMapping("/api/notifications/summary")
    public ResponseEntity<?> memberSummary(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !"Member".equals(session.getAttribute("role"))) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        Integer memberId   = resolveSessionMemberId(session);
        String appClientId = resolveAppClientId(session);

        long unreadMessages  = memberId != null ? messageRepo.countUnread(memberId) : 0;
        long upcomingEvents  = countUpcomingEvents(appClientId, 7);
        long upcomingMeetings = countUpcomingMeetings(appClientId, 7);
        long upcomingBirthdays = countBirthdaysInDays(appClientId, 2);

        return ResponseEntity.ok(buildSummary(
            unreadMessages, upcomingEvents, upcomingMeetings, upcomingBirthdays));
    }

    // ── Staff (app user) summary ──────────────────────────────────────────────

    /**
     * Notification summary for a logged-in staff user (Admin, Accountant, etc.).
     * Counts: events in next 7 days, meetings in next 7 days,
     *         birthdays today or tomorrow.  No unread-message count for staff.
     */
    @GetMapping("/api/notifications/staff-summary")
    public ResponseEntity<?> staffSummary(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("clientId") == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        // Must be a staff (non-member, non-church) session
        Boolean isChurch = (Boolean) session.getAttribute("church");
        String role      = (String) session.getAttribute("role");
        if (Boolean.TRUE.equals(isChurch) || "Member".equals(role)) {
            return ResponseEntity.status(403).body(Map.of("error", "Staff only"));
        }

        String appClientId = resolveAppClientId(session);
        long upcomingEvents    = countUpcomingEvents(appClientId, 7);
        long upcomingMeetings  = countUpcomingMeetings(appClientId, 7);
        long upcomingBirthdays = countBirthdaysInDays(appClientId, 2);

        return ResponseEntity.ok(buildSummary(0, upcomingEvents, upcomingMeetings, upcomingBirthdays));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> buildSummary(long msgs, long events, long meetings, long birthdays) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("unreadMessages",    msgs);
        m.put("upcomingEvents",    events);
        m.put("upcomingMeetings",  meetings);
        m.put("upcomingBirthdays", birthdays);
        m.put("total",             msgs + events + meetings + birthdays);
        return m;
    }

    private long countUpcomingEvents(String appClientId, int days) {
        if (appClientId == null) return 0;
        LocalDate today = LocalDate.now();
        LocalDate limit = today.plusDays(days);
        try {
            return eventRepo
                .findByAppClientIdAndDeleteFlagFalseOrderByCreatedDateDesc(appClientId)
                .stream()
                .filter(e -> e.getEventDate() != null
                          && !e.getEventDate().isBefore(today)
                          && !e.getEventDate().isAfter(limit))
                .count();
        } catch (Exception ex) { return 0; }
    }

    private long countUpcomingMeetings(String appClientId, int days) {
        if (appClientId == null) return 0;
        LocalDate today = LocalDate.now();
        LocalDate limit = today.plusDays(days);
        try {
            return meetingRepo
                .findByAppClientIdAndDeleteFlagFalse(appClientId)
                .stream()
                .filter(m -> m.getMeetingDate() != null
                          && !m.getMeetingDate().isBefore(today)
                          && !m.getMeetingDate().isAfter(limit))
                .count();
        } catch (Exception ex) { return 0; }
    }

    private long countBirthdaysInDays(String appClientId, int days) {
        if (appClientId == null) return 0;
        LocalDate today = LocalDate.now();
        try {
            List<FamilyMember> members =
                familyMemberRepo.findAllWithFamilyByAppUser(appClientId);
            long count = 0;
            for (FamilyMember fm : members) {
                if (fm.getBirthdayMonth() == null || fm.getBirthdayDay() == null) continue;
                for (int i = 0; i < days; i++) {
                    LocalDate d = today.plusDays(i);
                    if (fm.getBirthdayMonth() == d.getMonthValue()
                            && fm.getBirthdayDay() == d.getDayOfMonth()) {
                        count++;
                        break;
                    }
                }
            }
            return count;
        } catch (Exception ex) { return 0; }
    }

    private Integer resolveSessionMemberId(HttpSession session) {
        Object v = session.getAttribute("memberId");
        if (v instanceof Number n) return n.intValue();
        return null;
    }

    private String resolveAppClientId(HttpSession session) {
        Object v = session.getAttribute("appClientId");
        if (v != null) return String.valueOf(v);
        Object c = session.getAttribute("clientId");
        return c != null ? String.valueOf(c) : null;
    }
}
