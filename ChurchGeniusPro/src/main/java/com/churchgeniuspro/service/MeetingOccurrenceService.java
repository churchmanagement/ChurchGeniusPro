package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.Meeting;
import com.churchgeniuspro.hibernate.MeetingSkipDate;
import com.churchgeniuspro.repository.MeetingRepository;
import com.churchgeniuspro.repository.MeetingSkipDateRepository;
import com.churchgeniuspro.util.MeetingRecurrence;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Manages deleted (skipped) occurrences of recurring meetings.
 *
 * <p>Recurring meetings are one {@code meeting} row whose occurrences are
 * computed on the fly. "Deleting" an occurrence records a
 * {@link MeetingSkipDate} exception; the Event Calendar, ICS feed and the
 * reminder scheduler all exclude those dates, so the occurrence disappears
 * everywhere and no reminders (email / SMS / WhatsApp / push) are sent for
 * it, while the rest of the series continues unchanged.</p>
 */
@Service
public class MeetingOccurrenceService {

    /** How many days ahead the occurrence list looks for open-ended series. */
    private static final int HORIZON_DAYS = 370;

    private final MeetingRepository meetingRepo;
    private final MeetingSkipDateRepository skipRepo;

    public MeetingOccurrenceService(MeetingRepository meetingRepo,
                                    MeetingSkipDateRepository skipRepo) {
        this.meetingRepo = meetingRepo;
        this.skipRepo    = skipRepo;
    }

    /** Loads the meeting and verifies tenant ownership + not soft-deleted. */
    private Meeting requireMeeting(Integer id, String appClientId) {
        Meeting m = meetingRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Meeting not found: " + id));
        if (m.isDeleteFlag()) {
            throw new IllegalArgumentException("Meeting not found: " + id);
        }
        if (appClientId != null && m.getAppClientId() != null
                && !appClientId.equals(m.getAppClientId())) {
            throw new IllegalArgumentException("Meeting not found: " + id);
        }
        return m;
    }

    /**
     * Upcoming occurrences of the series (from today), each flagged with
     * {@code skipped}. Skipped dates already in the past are omitted.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> listOccurrences(Integer id, String appClientId, int limit) {
        Meeting m = requireMeeting(id, appClientId);
        LocalDate today = LocalDate.now();

        Set<LocalDate> skips = skipRepo.findByMeetingId(id).stream()
                .map(MeetingSkipDate::getSkipDate)
                .collect(Collectors.toSet());

        List<LocalDate> dates = MeetingRecurrence.listOccurrences(
                m, today, Math.max(1, limit), HORIZON_DAYS);

        List<Map<String, Object>> occ = new ArrayList<>();
        for (LocalDate d : dates) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("date", d.toString());
            o.put("skipped", skips.contains(d));
            occ.add(o);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("meetingId", m.getId());
        out.put("occurrence", m.getOccurrence());
        out.put("meetingDate", m.getMeetingDate() != null ? m.getMeetingDate().toString() : null);
        out.put("endDate", m.getEndDate() != null ? m.getEndDate().toString() : null);
        out.put("occurrences", occ);
        return out;
    }

    /**
     * Deletes (skips) the given occurrence dates. Only dates the series
     * actually occurs on are recorded; already-skipped dates are ignored.
     * Returns the number of newly skipped dates.
     */
    @Transactional
    public int deleteOccurrences(Integer id, String appClientId, List<LocalDate> dates) {
        Meeting m = requireMeeting(id, appClientId);
        if (dates == null || dates.isEmpty()) {
            throw new IllegalArgumentException("No dates provided.");
        }
        int added = 0;
        for (LocalDate d : dates) {
            if (d == null) continue;
            if (!MeetingRecurrence.occursOn(m, d)) continue;      // not an occurrence
            if (skipRepo.existsByMeetingIdAndSkipDate(id, d)) continue;
            skipRepo.save(new MeetingSkipDate(id, d, m.getAppClientId()));
            added++;
        }
        if (added == 0) {
            throw new IllegalArgumentException(
                    "None of the selected dates are active occurrences of this meeting.");
        }
        return added;
    }

    /** Restores previously deleted occurrence dates. Returns how many were restored. */
    @Transactional
    public int restoreOccurrences(Integer id, String appClientId, List<LocalDate> dates) {
        requireMeeting(id, appClientId);
        if (dates == null || dates.isEmpty()) {
            throw new IllegalArgumentException("No dates provided.");
        }
        skipRepo.deleteByMeetingIdAndSkipDateIn(id, dates);
        return dates.size();
    }

    /**
     * Deletes this occurrence and all future ones by ending the series the
     * day before {@code fromDate}. If that leaves no occurrences at all
     * (i.e. {@code fromDate} is on/before the series start), the whole
     * meeting is soft-deleted instead. Skip rows beyond the new end are
     * cleaned up. Returns {@code {"seriesDeleted": bool, "endDate": "..."}}.
     */
    @Transactional
    public Map<String, Object> deleteFromDate(Integer id, String appClientId, LocalDate fromDate) {
        Meeting m = requireMeeting(id, appClientId);
        if (fromDate == null) throw new IllegalArgumentException("fromDate is required.");

        String occ = m.getOccurrence() != null ? m.getOccurrence().trim() : "One-time";
        boolean recurring = !(occ.equalsIgnoreCase("One-time")
                || occ.equalsIgnoreCase("One Time") || occ.equalsIgnoreCase("Once"));

        if (!recurring || !fromDate.isAfter(m.getMeetingDate())) {
            // Nothing remains before fromDate — remove the whole series.
            m.setDeleteFlag(true);
            meetingRepo.save(m);
            skipRepo.deleteByMeetingId(id);
            return Map.of("seriesDeleted", true);
        }

        LocalDate newEnd = fromDate.minusDays(1);
        m.setEndDate(newEnd);
        meetingRepo.save(m);
        skipRepo.deleteByMeetingIdAndSkipDateAfter(id, newEnd);
        return Map.of("seriesDeleted", false, "endDate", newEnd.toString());
    }
}
