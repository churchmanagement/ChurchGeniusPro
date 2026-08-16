package com.churchgeniuspro.util;

import com.churchgeniuspro.hibernate.Meeting;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared recurrence engine for {@link Meeting}: decides whether a meeting
 * series has an occurrence on a given date, and lists upcoming occurrence
 * dates. Mirrors the semantics used by the Event Calendar
 * ({@code EventCalendarController.buildEvents}) and the reminder scheduler
 * ({@code ReminderSchedulerService.meetingOccursToday}):
 *
 * <ul>
 *   <li>One-time — occurs only on {@code meetingDate}</li>
 *   <li>Daily — every day from {@code meetingDate} to {@code endDate}</li>
 *   <li>Weekly — days of week in {@code weekDays} CSV (0=Sun…6=Sat),
 *       falling back to the start date's day-of-week when unset</li>
 *   <li>Monthly — optional month filter ({@code monthMonths}), then either a
 *       fixed {@code monthDayOfMonth} or an ordinal weekday
 *       ({@code monthWeekOrdinal} 1–4, 5=Last + {@code monthWeekDay})</li>
 * </ul>
 */
public final class MeetingRecurrence {

    private MeetingRecurrence() { }

    /** True when the series (ignoring skip dates) has an occurrence on {@code date}. */
    public static boolean occursOn(Meeting m, LocalDate date) {
        if (m == null || m.getMeetingDate() == null || date == null) return false;

        String occ = m.getOccurrence() != null ? m.getOccurrence().trim() : "One-time";

        if (occ.equalsIgnoreCase("One-time") || occ.equalsIgnoreCase("One Time")
                || occ.equalsIgnoreCase("Once")) {
            return m.getMeetingDate().equals(date);
        }

        // Recurring: date must be inside [meetingDate, endDate]
        if (date.isBefore(m.getMeetingDate())) return false;
        if (m.getEndDate() != null && date.isAfter(m.getEndDate())) return false;

        if (occ.equalsIgnoreCase("Daily")) return true;

        if (occ.equalsIgnoreCase("Weekly")) {
            Set<Integer> dayNums = parseIntSet(m.getWeekDays());
            if (dayNums.isEmpty()) dayNums.add(dowToNum(m.getMeetingDate().getDayOfWeek()));
            return dayNums.contains(dowToNum(date.getDayOfWeek()));
        }

        if (occ.equalsIgnoreCase("Monthly")) {
            Set<Integer> allowedMonths = parseIntSet(m.getMonthMonths());
            if (!allowedMonths.isEmpty() && !allowedMonths.contains(date.getMonthValue())) {
                return false;
            }
            if (m.getMonthWeekOrdinal() != null && m.getMonthWeekDay() != null) {
                return isNthWeekday(date, m.getMonthWeekOrdinal(), m.getMonthWeekDay());
            }
            if (m.getMonthDayOfMonth() != null && m.getMonthDayOfMonth() > 0) {
                return date.getDayOfMonth() == m.getMonthDayOfMonth();
            }
            return false;
        }

        return false;
    }

    /**
     * Lists occurrence dates of the series from {@code from} (inclusive),
     * capped at {@code limit} dates and at {@code horizonDays} days of
     * look-ahead (for open-ended daily/weekly series).
     */
    public static List<LocalDate> listOccurrences(Meeting m, LocalDate from,
                                                  int limit, int horizonDays) {
        List<LocalDate> out = new ArrayList<>();
        if (m == null || m.getMeetingDate() == null || from == null || limit <= 0) return out;

        LocalDate start = from.isBefore(m.getMeetingDate()) ? m.getMeetingDate() : from;
        LocalDate hardEnd = from.plusDays(horizonDays);
        if (m.getEndDate() != null && m.getEndDate().isBefore(hardEnd)) hardEnd = m.getEndDate();

        for (LocalDate cur = start; !cur.isAfter(hardEnd) && out.size() < limit;
                cur = cur.plusDays(1)) {
            if (occursOn(m, cur)) out.add(cur);
        }
        return out;
    }

    /** Parses a comma-separated integer string ({@code "0,2,5"}) into a set. */
    public static Set<Integer> parseIntSet(String csv) {
        Set<Integer> set = new HashSet<>();
        if (csv == null || csv.isBlank()) return set;
        for (String part : csv.split(",")) {
            try { set.add(Integer.parseInt(part.trim())); } catch (NumberFormatException ignored) { }
        }
        return set;
    }

    /** {@link DayOfWeek} → 0=Sun…6=Sat numbering used by the meeting form. */
    public static int dowToNum(DayOfWeek dow) {
        return dow == DayOfWeek.SUNDAY ? 0 : dow.getValue();
    }

    /**
     * True if {@code date} is the Nth ({@code ordinal} 1–4, 5=Last) occurrence
     * of {@code weekDay} (0=Sun…6=Sat) within its month.
     */
    public static boolean isNthWeekday(LocalDate date, int ordinal, int weekDay) {
        if (dowToNum(date.getDayOfWeek()) != weekDay) return false;
        YearMonth ym = YearMonth.from(date);
        if (ordinal == 5) {
            LocalDate candidate = ym.atEndOfMonth();
            while (dowToNum(candidate.getDayOfWeek()) != weekDay) {
                candidate = candidate.minusDays(1);
            }
            return date.equals(candidate);
        }
        int count = 0;
        for (LocalDate d = ym.atDay(1); !d.isAfter(date); d = d.plusDays(1)) {
            if (dowToNum(d.getDayOfWeek()) == weekDay) count++;
        }
        return count == ordinal;
    }
}
