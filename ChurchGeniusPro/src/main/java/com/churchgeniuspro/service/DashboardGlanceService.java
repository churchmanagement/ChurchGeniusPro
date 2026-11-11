package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.AttendanceRecord;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.FollowUp;
import com.churchgeniuspro.hibernate.Meeting;
import com.churchgeniuspro.hibernate.MeetingSkipDate;
import com.churchgeniuspro.repository.AttendanceRecordRepository;
import com.churchgeniuspro.repository.ChurchEventRepository;
import com.churchgeniuspro.repository.FollowUpRepository;
import com.churchgeniuspro.repository.GroupRepository;
import com.churchgeniuspro.repository.MeetingRepository;
import com.churchgeniuspro.repository.MeetingSkipDateRepository;
import com.churchgeniuspro.util.MeetingRecurrence;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The compact "at a glance" statistics of the Home dashboard (2026-11-10): Groups, Attendance %, Events,
 * Visitors, and Birthdays / Anniversaries / Meetings for this month, this week and today.
 *
 * <p>COUNTS ONLY — no names, no dates of birth, no ages. Every figure reuses a rule the app already has:
 * <ul>
 *   <li>Groups — the Groups page's list ({@code GroupRepository.findActiveByAppUser}).</li>
 *   <li>Attendance % — people of the directory checked in (Present or Late, the Attendance dashboard's rule)
 *       at least once in the last 30 days, out of all active people of the directory.</li>
 *   <li>Events — upcoming events, the assistant's own query (multi-day events by any of their days).</li>
 *   <li>Visitors — exactly the number on the Follow-ups page's Visitors tab: open (PENDING or MISSED)
 *       follow-ups linked to a visitor.</li>
 *   <li>Birthdays — active people whose birthday month/day falls in the window (29 Feb counts on 28 Feb in a
 *       non-leap year). Anniversaries — the same on the anniversary month/day, ONCE per family and date,
 *       so a couple who both carry the date is one anniversary.</li>
 *   <li>Meetings — occurrences by {@link MeetingRecurrence} (the Event Calendar's engine), skip dates removed.</li>
 * </ul>
 * Windows: "today" = the church day ({@code AppClock}); "this week" = Monday–Sunday containing today (the
 * Attendance dashboard's week); "this month" = the calendar month. The caller has already decided WHO may
 * see these ({@code AdminDashboardController}); every lookup is scoped to the church passed in.
 */
@Service
public class DashboardGlanceService {

    private final GroupRepository            groupRepo;
    private final ChurchEventRepository      eventRepo;
    private final FollowUpRepository         followUpRepo;
    private final AttendanceRecordRepository attendanceRepo;
    private final MeetingRepository          meetingRepo;
    private final MeetingSkipDateRepository  skipRepo;

    public DashboardGlanceService(GroupRepository groupRepo, ChurchEventRepository eventRepo,
                                  FollowUpRepository followUpRepo, AttendanceRecordRepository attendanceRepo,
                                  MeetingRepository meetingRepo, MeetingSkipDateRepository skipRepo) {
        this.groupRepo = groupRepo; this.eventRepo = eventRepo; this.followUpRepo = followUpRepo;
        this.attendanceRepo = attendanceRepo; this.meetingRepo = meetingRepo; this.skipRepo = skipRepo;
    }

    /** How far ahead "upcoming events" looks. */
    static final int EVENT_HORIZON_DAYS = 365;
    /** The attendance percentage's window, in days (today included). */
    static final int ATTENDANCE_DAYS = 30;

    /**
     * @param appClientId the session's church — never null or blank (the caller refuses such a session)
     * @param people      the church's active people (the list the dashboard's Members card is counted from)
     */
    public Map<String, Object> glance(String appClientId, List<FamilyMember> people, LocalDate today) {
        if (appClientId == null || appClientId.isBlank()) throw new IllegalArgumentException("church required");
        LocalDate weekStart = today.with(DayOfWeek.MONDAY), weekEnd = weekStart.plusDays(6);
        LocalDate monthStart = today.withDayOfMonth(1), monthEnd = today.withDayOfMonth(today.lengthOfMonth());

        Map<String, Object> g = new LinkedHashMap<>();
        g.put("groups", groupRepo.findActiveByAppUser(appClientId).size());

        // ── Attendance % (last 30 days) ──
        LocalDate from = today.minusDays(ATTENDANCE_DAYS - 1L);
        Set<Integer> directory = people.stream().map(FamilyMember::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Integer> attended = new HashSet<>();
        for (AttendanceRecord a : attendanceRepo.findByClientIdAndAttendanceDateBetweenAndDeleteFlagFalse(appClientId, from, today)) {
            if (!("PRESENT".equals(a.getStatus()) || "LATE".equals(a.getStatus()))) continue;
            if (a.getFamilyMemberId() != null && directory.contains(a.getFamilyMemberId())) attended.add(a.getFamilyMemberId());
        }
        Map<String, Object> att = new LinkedHashMap<>();
        att.put("percent", directory.isEmpty() ? 0 : (int) Math.round(100.0 * attended.size() / directory.size()));
        att.put("attended", attended.size());
        att.put("people", directory.size());
        att.put("days", ATTENDANCE_DAYS);
        g.put("attendance", att);

        // ── Events (upcoming) ──
        g.put("events", eventRepo.aiUpcomingEvents(appClientId, java.sql.Date.valueOf(today),
                java.sql.Date.valueOf(today.plusDays(EVENT_HORIZON_DAYS)), 1000).size());

        // ── Visitors (the Follow-ups page's Visitors tab: open visitor follow-ups) ──
        long visitors = 0;
        for (FollowUp f : followUpRepo.findByClientIdAndLinkedTypeAndDeleteFlagFalseOrderByCreatedAtDesc(appClientId, "VISITOR"))
            if ("PENDING".equals(f.getStatus()) || "MISSED".equals(f.getStatus())) visitors++;
        g.put("visitors", visitors);

        // ── Birthdays / Anniversaries ──
        List<int[]> birthdays = new ArrayList<>();
        Set<String> seenAnniv = new HashSet<>();
        List<int[]> anniversaries = new ArrayList<>();
        for (FamilyMember m : people) {
            if (valid(m.getBirthdayMonth(), m.getBirthdayDay())) birthdays.add(new int[] {m.getBirthdayMonth(), m.getBirthdayDay()});
            if (valid(m.getAnniversaryMonth(), m.getAnniversaryDay())) {
                Object fam = m.getFamily() != null && m.getFamily().getId() != null ? m.getFamily().getId() : "m" + m.getId();
                if (seenAnniv.add(fam + "@" + m.getAnniversaryMonth() + "-" + m.getAnniversaryDay()))
                    anniversaries.add(new int[] {m.getAnniversaryMonth(), m.getAnniversaryDay()});
            }
        }
        g.put("birthdays",     windows(birthdays, today, weekStart, weekEnd, monthStart, monthEnd));
        g.put("anniversaries", windows(anniversaries, today, weekStart, weekEnd, monthStart, monthEnd));

        // ── Meetings (occurrences, skip dates removed) ──
        List<Meeting> meetings = meetingRepo.findAllActiveByAppUserOrderByDateAsc(appClientId);
        Set<String> skipped = new HashSet<>();
        List<Integer> ids = meetings.stream().map(Meeting::getId).filter(Objects::nonNull).collect(Collectors.toList());
        if (!ids.isEmpty()) for (MeetingSkipDate s : skipRepo.findByMeetingIdIn(ids)) skipped.add(s.getMeetingId() + "@" + s.getSkipDate());
        LocalDate first = monthStart.isBefore(weekStart) ? monthStart : weekStart, last = monthEnd.isAfter(weekEnd) ? monthEnd : weekEnd;
        int mMonth = 0, mWeek = 0, mToday = 0;
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            int n = 0;
            for (Meeting m : meetings) if (MeetingRecurrence.occursOn(m, d) && !skipped.contains(m.getId() + "@" + d)) n++;
            if (!d.isBefore(monthStart) && !d.isAfter(monthEnd)) mMonth += n;
            if (!d.isBefore(weekStart) && !d.isAfter(weekEnd)) mWeek += n;
            if (d.equals(today)) mToday += n;
        }
        g.put("meetings", trio(mMonth, mWeek, mToday));

        Map<String, Object> w = new LinkedHashMap<>();
        w.put("today", today.toString());
        w.put("weekFrom", weekStart.toString());   w.put("weekTo", weekEnd.toString());
        w.put("monthFrom", monthStart.toString()); w.put("monthTo", monthEnd.toString());
        g.put("windows", w);
        return g;
    }

    private static boolean valid(Integer month, Integer day) {
        return month != null && day != null && month >= 1 && month <= 12 && day >= 1 && day <= 31;
    }

    /** Month / week / today counts of yearly month-day dates. */
    private static Map<String, Object> windows(List<int[]> dates, LocalDate today, LocalDate weekStart, LocalDate weekEnd,
                                               LocalDate monthStart, LocalDate monthEnd) {
        int month = 0, week = 0, day = 0;
        for (int[] md : dates) {
            if (md[0] == today.getMonthValue() && fallsOn(md, today.withDayOfMonth(1), monthEnd) ) month++;
            boolean inWeek = false;
            for (LocalDate d = weekStart; !d.isAfter(weekEnd); d = d.plusDays(1)) if (isOn(md, d)) { inWeek = true; break; }
            if (inWeek) week++;
            if (isOn(md, today)) day++;
        }
        return trio(month, week, day);
    }

    private static boolean fallsOn(int[] md, LocalDate from, LocalDate to) {
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) if (isOn(md, d)) return true;
        return false;
    }

    /** True when a yearly month/day lands on {@code d}; 29 Feb is observed on 28 Feb in a non-leap year. */
    static boolean isOn(int[] md, LocalDate d) {
        if (md[0] != d.getMonthValue()) return false;
        if (md[1] == d.getDayOfMonth()) return true;
        return md[0] == 2 && md[1] == 29 && !d.isLeapYear() && d.getDayOfMonth() == 28;
    }

    private static Map<String, Object> trio(int month, int week, int today) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("month", month); t.put("week", week); t.put("today", today);
        return t;
    }
}
