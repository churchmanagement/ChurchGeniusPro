package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.Meeting;
import com.churchgeniuspro.hibernate.MeetingSkipDate;
import com.churchgeniuspro.repository.MeetingRepository;
import com.churchgeniuspro.repository.MeetingSkipDateRepository;
import com.churchgeniuspro.util.MeetingRecurrence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for per-occurrence deletion of recurring meetings:
 * recurrence matching, skip recording, restore, and delete-all-future.
 */
class MeetingOccurrenceServiceTest {

    private static final String CID = "CHR-1";

    private MeetingRepository meetingRepo;
    private MeetingSkipDateRepository skipRepo;
    private MeetingOccurrenceService svc;

    @BeforeEach
    void setUp() {
        meetingRepo = mock(MeetingRepository.class);
        skipRepo    = mock(MeetingSkipDateRepository.class);
        svc = new MeetingOccurrenceService(meetingRepo, skipRepo);
    }

    /** Weekly meeting every Tuesday starting Tue 2026-07-07, no end date. */
    private Meeting weeklyTuesday() {
        Meeting m = new Meeting();
        m.setId(10);
        m.setAppClientId(CID);
        m.setMeetingDate(LocalDate.of(2026, 7, 7));   // a Tuesday
        m.setOccurrence("Weekly");
        m.setWeekDays("2");                            // 0=Sun … 2=Tue
        m.setDeleteFlag(false);
        return m;
    }

    /* ── recurrence engine ──────────────────────────────────────────────── */

    @Test
    void weeklyOccursOnlyOnConfiguredDay() {
        Meeting m = weeklyTuesday();
        assertTrue(MeetingRecurrence.occursOn(m, LocalDate.of(2026, 7, 14)));  // Tue
        assertFalse(MeetingRecurrence.occursOn(m, LocalDate.of(2026, 7, 15))); // Wed
        assertFalse(MeetingRecurrence.occursOn(m, LocalDate.of(2026, 6, 30))); // before start
    }

    @Test
    void weeklyRespectsEndDate() {
        Meeting m = weeklyTuesday();
        m.setEndDate(LocalDate.of(2026, 7, 20));
        assertTrue(MeetingRecurrence.occursOn(m, LocalDate.of(2026, 7, 14)));
        assertFalse(MeetingRecurrence.occursOn(m, LocalDate.of(2026, 7, 21))); // after end
    }

    @Test
    void monthlyNthWeekday() {
        Meeting m = new Meeting();
        m.setId(11);
        m.setMeetingDate(LocalDate.of(2026, 1, 1));
        m.setOccurrence("Monthly");
        m.setMonthWeekOrdinal(1);   // 1st
        m.setMonthWeekDay(0);       // Sunday
        assertTrue(MeetingRecurrence.occursOn(m, LocalDate.of(2026, 8, 2)));   // 1st Sunday of Aug 2026
        assertFalse(MeetingRecurrence.occursOn(m, LocalDate.of(2026, 8, 9)));  // 2nd Sunday
    }

    @Test
    void oneTimeOccursOnlyOnItsDate() {
        Meeting m = new Meeting();
        m.setId(12);
        m.setMeetingDate(LocalDate.of(2026, 8, 1));
        m.setOccurrence("One-time");
        assertTrue(MeetingRecurrence.occursOn(m, LocalDate.of(2026, 8, 1)));
        assertFalse(MeetingRecurrence.occursOn(m, LocalDate.of(2026, 8, 2)));
    }

    /* ── listing ────────────────────────────────────────────────────────── */

    @Test
    void listMarksSkippedOccurrences() {
        Meeting m = weeklyTuesday();
        when(meetingRepo.findById(10)).thenReturn(Optional.of(m));
        LocalDate nextTue = LocalDate.now().plusDays(1);
        while (MeetingRecurrence.dowToNum(nextTue.getDayOfWeek()) != 2) nextTue = nextTue.plusDays(1);
        when(skipRepo.findByMeetingId(10))
                .thenReturn(List.of(new MeetingSkipDate(10, nextTue, CID)));

        Map<String, Object> out = svc.listOccurrences(10, CID, 5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> occ = (List<Map<String, Object>>) out.get("occurrences");
        assertFalse(occ.isEmpty());
        final String skippedDate = nextTue.toString();
        assertTrue(occ.stream().anyMatch(o ->
                skippedDate.equals(o.get("date")) && Boolean.TRUE.equals(o.get("skipped"))));
        // all listed dates are Tuesdays
        occ.forEach(o -> assertEquals(2, MeetingRecurrence.dowToNum(
                LocalDate.parse((String) o.get("date")).getDayOfWeek())));
    }

    /* ── delete single / multiple ───────────────────────────────────────── */

    @Test
    void deleteSingleOccurrenceRecordsSkip() {
        Meeting m = weeklyTuesday();
        when(meetingRepo.findById(10)).thenReturn(Optional.of(m));
        when(skipRepo.existsByMeetingIdAndSkipDate(anyInt(), any())).thenReturn(false);

        int n = svc.deleteOccurrences(10, CID, List.of(LocalDate.of(2026, 7, 28))); // a Tuesday
        assertEquals(1, n);

        ArgumentCaptor<MeetingSkipDate> cap = ArgumentCaptor.forClass(MeetingSkipDate.class);
        verify(skipRepo).save(cap.capture());
        assertEquals(LocalDate.of(2026, 7, 28), cap.getValue().getSkipDate());
        assertEquals(CID, cap.getValue().getAppClientId());
    }

    @Test
    void deleteMultipleSkipsNonOccurrencesAndDuplicates() {
        Meeting m = weeklyTuesday();
        when(meetingRepo.findById(10)).thenReturn(Optional.of(m));
        when(skipRepo.existsByMeetingIdAndSkipDate(eq(10), eq(LocalDate.of(2026, 8, 4))))
                .thenReturn(true);   // already skipped
        when(skipRepo.existsByMeetingIdAndSkipDate(eq(10), eq(LocalDate.of(2026, 8, 11))))
                .thenReturn(false);

        int n = svc.deleteOccurrences(10, CID, List.of(
                LocalDate.of(2026, 8, 4),    // Tue — already skipped → ignored
                LocalDate.of(2026, 8, 5),    // Wed — not an occurrence → ignored
                LocalDate.of(2026, 8, 11))); // Tue — new skip
        assertEquals(1, n);
        verify(skipRepo, times(1)).save(any());
    }

    @Test
    void deleteRejectsWhenNothingApplies() {
        Meeting m = weeklyTuesday();
        when(meetingRepo.findById(10)).thenReturn(Optional.of(m));
        assertThrows(IllegalArgumentException.class,
                () -> svc.deleteOccurrences(10, CID, List.of(LocalDate.of(2026, 8, 5)))); // Wed
    }

    @Test
    void wrongTenantIsRejected() {
        Meeting m = weeklyTuesday();
        when(meetingRepo.findById(10)).thenReturn(Optional.of(m));
        assertThrows(IllegalArgumentException.class,
                () -> svc.deleteOccurrences(10, "OTHER-CLIENT",
                        List.of(LocalDate.of(2026, 7, 28))));
    }

    /* ── restore ────────────────────────────────────────────────────────── */

    @Test
    void restoreDeletesSkipRows() {
        Meeting m = weeklyTuesday();
        when(meetingRepo.findById(10)).thenReturn(Optional.of(m));
        int n = svc.restoreOccurrences(10, CID, List.of(LocalDate.of(2026, 7, 28)));
        assertEquals(1, n);
        verify(skipRepo).deleteByMeetingIdAndSkipDateIn(eq(10), any());
    }

    /* ── delete this & all future ───────────────────────────────────────── */

    @Test
    void deleteFutureEndsSeriesDayBefore() {
        Meeting m = weeklyTuesday();
        when(meetingRepo.findById(10)).thenReturn(Optional.of(m));

        Map<String, Object> out = svc.deleteFromDate(10, CID, LocalDate.of(2026, 8, 4));
        assertEquals(false, out.get("seriesDeleted"));
        assertEquals("2026-08-03", out.get("endDate"));
        assertEquals(LocalDate.of(2026, 8, 3), m.getEndDate());
        assertFalse(m.isDeleteFlag());
        verify(skipRepo).deleteByMeetingIdAndSkipDateAfter(10, LocalDate.of(2026, 8, 3));
    }

    @Test
    void deleteFutureFromSeriesStartSoftDeletesWholeMeeting() {
        Meeting m = weeklyTuesday();
        when(meetingRepo.findById(10)).thenReturn(Optional.of(m));

        Map<String, Object> out = svc.deleteFromDate(10, CID, LocalDate.of(2026, 7, 7));
        assertEquals(true, out.get("seriesDeleted"));
        assertTrue(m.isDeleteFlag());
        verify(skipRepo).deleteByMeetingId(10);
    }
}
