package com.churchgeniuspro.etl;

import com.churchgeniuspro.hibernate.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Migration Phase 4 — events, event registrations and attendance. */
@DisplayName("ETL Phase 4 — events, registrations, attendance")
class EtlPhase4EventsTest extends EtlPhase4Support {

    private ChurchEvent liveEvent(int id, String name, LocalDate d, String start) {
        ChurchEvent e = new ChurchEvent(); e.setId(id); e.setAppClientId(TENANT); e.setEventName(name); e.setEventDate(d); e.setStartTime(start); return liveRow(e);
    }

    @Test
    @DisplayName("events: name + date + start time → STRONG; name + date → POSSIBLE; negative capacity → ERROR; end before start → WARN")
    void eventTiers() {
        when(live.events(TENANT)).thenReturn(List.of(liveEvent(1, "Spring Picnic", LocalDate.of(2026, 4, 18), "11:00")));
        StagingRecord strong = staged("event", "E1", v("eventName", "spring  picnic!", "eventDate", "2026-04-18", "startTime", "11:00"));
        StagingRecord possible = staged("event", "E2", v("eventName", "Spring Picnic", "eventDate", "2026-04-18", "startTime", "15:00", "endTime", "14:00"));
        StagingRecord bad = staged("event", "E3", v("eventName", "Training", "eventDate", "2026-05-02", "maxCapacity", -1));
        svc.validate(RUN, TENANT, "actor", "event");
        assertThat(strong.getMatchTier()).isEqualTo("STRONG"); assertThat(strong.getDedupeMatchId()).isEqualTo(1L);
        assertThat(possible.getMatchTier()).isEqualTo("POSSIBLE"); assertThat(possible.getRowStatus()).isEqualTo("REVIEW");
        assertThat(msgText(possible)).contains("End time is before the start time").contains("merge");
        assertThat(bad.getRowStatus()).isEqualTo("ERROR"); assertThat(msgText(bad)).contains("Capacity cannot be negative");
    }

    @Test
    @DisplayName("registrations: event by id (staged) or by name; two live events with that name and no date → ERROR; e-mail / full name → STRONG; last name → POSSIBLE; a name or e-mail is required")
    void registrations() {
        when(live.events(TENANT)).thenReturn(List.of(liveEvent(1, "Retreat", LocalDate.of(2026, 6, 1), null), liveEvent(2, "Retreat", LocalDate.of(2026, 9, 1), null)));
        EventRegistration onFile = new EventRegistration(); onFile.setId(50); onFile.setEventId(1); onFile.setFirstName("Lena"); onFile.setLastName("Walker"); onFile.setEmail("lena@example.org");
        when(live.registrations(TENANT)).thenReturn(List.of(onFile));
        StagingRecord ev = staged("event", "E-9", v("eventName", "Picnic", "eventDate", "2026-04-18"));
        StagingRecord byId = staged("event_registration", "R1", v("eventLink", "E-9", "firstName", "Ann", "lastName", "Lee"));
        StagingRecord ambiguous = staged("event_registration", "R2", v("eventName", "Retreat", "firstName", "Ann", "lastName", "Lee"));
        StagingRecord byEmail = staged("event_registration", "R3", v("eventName", "Retreat", "eventDate", "2026-06-01", "email", "LENA@example.org"));
        StagingRecord lastOnly = staged("event_registration", "R4", v("eventName", "Retreat", "eventDate", "2026-06-01", "firstName", "Tom", "lastName", "Walker"));
        StagingRecord nobody = staged("event_registration", "R5", v("eventLink", "E-9", "adults", -2));
        svc.validate(RUN, TENANT, "actor", "event_registration");
        assertThat(links(byId)).containsEntry("parent", "s" + ev.getId()); assertThat(byId.getRowStatus()).isEqualTo("VALID");
        assertThat(ambiguous.getRowStatus()).isEqualTo("ERROR"); assertThat(msgText(ambiguous)).contains("is ambiguous: 2 existing records — add an event date column");
        assertThat(links(byEmail)).containsEntry("parent", "l1");
        assertThat(byEmail.getMatchTier()).isEqualTo("STRONG"); assertThat(byEmail.getMatchDetail()).isEqualTo("event + e-mail"); assertThat(byEmail.getDedupeMatchId()).isEqualTo(50L);
        assertThat(lastOnly.getMatchTier()).isEqualTo("POSSIBLE"); assertThat(lastOnly.getMatchDetail()).isEqualTo("event + last name");
        assertThat(nobody.getRowStatus()).isEqualTo("ERROR"); assertThat(msgText(nobody)).contains("needs a name or an e-mail").contains("adults cannot be negative");
    }

    @Test
    @DisplayName("load: event_code stays blank and type defaults to One Day; a registration fills names from the member and is marked checked in at the event's start despite @PrePersist")
    void loadEventAndRegistration() {
        staged("event", "E-1", v("eventName", "Spring Picnic", "eventDate", "2026-04-18", "startTime", "11:00", "endTime", "14:00", "maxCapacity", 150));
        svc.validate(RUN, TENANT, "actor", "event");
        assertThat(load("event", 80)).containsExactly(1, 0, 0, 0);
        ChurchEvent e = savedOf(ChurchEvent.class, 0);
        assertThat(e.getEventCode()).isNull(); assertThat(e.getEventType()).isEqualTo("One Day"); assertThat(e.getMaxCapacity()).isEqualTo(150);
        assertThat(e.getCreatedBy()).isEqualTo("actor");

        member(40, "Grace", "Okafor", "grace@example.org", "9135550401");
        person("701", "Grace", "Okafor", 40L);
        staged("event_registration", "R-1", v("eventLink", "E-1", "personLink", "701", "adults", 2, "checkedIn", true));
        svc.validate(RUN, TENANT, "actor", "event_registration");
        assertThat(load("event_registration", 81)).containsExactly(1, 0, 0, 0);
        EventRegistration r = savedOf(EventRegistration.class, 0);
        assertThat(r.getEventId()).isEqualTo(e.getId()); assertThat(r.getClientId()).isEqualTo(TENANT);
        assertThat(r.getFirstName()).isEqualTo("Grace"); assertThat(r.getEmail()).isEqualTo("grace@example.org"); assertThat(r.getPhone()).isEqualTo("9135550401");
        assertThat(r.isCheckedIn()).isTrue(); assertThat(r.getAttending()).isTrue();
        assertThat(r.getCheckedInAt()).isEqualTo(java.util.Date.from(LocalDateTime.of(2026, 4, 18, 11, 0).atZone(java.time.ZoneId.systemDefault()).toInstant()));
        verify(live, times(2)).save(r);                                                  // insert, then the check-in fix-up after @PrePersist
    }

    @Test
    @DisplayName("attendance: member by id or by unique exact name (INFO); otherwise a named visitor (WARN); status words normalised; unknown status → WARN + PRESENT; a check-in without check-out is closed")
    void attendance() {
        member(40, "Grace", "Okafor", null, null);
        member(41, "Sam", "Lee", null, null); member(42, "Sam", "Lee", null, null);
        StagingRecord byId = staged("attendance", "A1", v("personLink", "40", "attendanceDate", "2026-03-01", "serviceType", "Sunday", "status", "yes",
            "checkInTime", "2026-03-01T09:55"));
        StagingRecord byName = staged("attendance", "A2", v("firstName", "grace", "lastName", "OKAFOR", "attendanceDate", "2026-03-08", "status", "Late"));
        StagingRecord twoSams = staged("attendance", "A3", v("firstName", "Sam", "lastName", "Lee", "attendanceDate", "2026-03-01", "status", "maybe"));
        StagingRecord nobody = staged("attendance", "A4", v("attendanceDate", "2026-03-01"));
        svc.validate(RUN, TENANT, "actor", "attendance");
        assertThat(links(byId)).containsEntry("person", "m40").containsEntry("status", "PRESENT").containsEntry("personName", "Grace Okafor");
        assertThat(links(byName)).containsEntry("person", "m40").containsEntry("status", "LATE"); assertThat(msgText(byName)).contains("INFO:Linked by name to member #40");
        assertThat(twoSams.getRowStatus()).isEqualTo("WARN"); assertThat(links(twoSams)).doesNotContainKey("person").containsEntry("status", "PRESENT");
        assertThat(msgText(twoSams)).contains("recorded by name ('Sam Lee') as a visitor").contains("Unknown attendance status 'maybe'");
        assertThat(nobody.getRowStatus()).isEqualTo("ERROR");

        assertThat(load("attendance", 82)).containsExactly(3, 0, 0, 0);
        AttendanceRecord a = savedOf(AttendanceRecord.class, 0), v = savedOf(AttendanceRecord.class, 2);
        assertThat(a.getPersonType()).isEqualTo("MEMBER"); assertThat(a.getFamilyMemberId()).isEqualTo(40); assertThat(a.getCheckInMethod()).isEqualTo("import");
        assertThat(a.getCheckOutTime()).isEqualTo(a.getCheckInTime()).isEqualTo(LocalDateTime.of(2026, 3, 1, 9, 55));
        assertThat(v.getPersonType()).isEqualTo("VISITOR"); assertThat(v.getVisitorId()).isNull(); assertThat(v.getPersonName()).isEqualTo("Sam Lee");
    }

    @Test
    @DisplayName("attendance tiers: same member + date + service → STRONG; + date only → POSSIBLE; a name-only row finds the member's existing record by name")
    void attendanceTiers() {
        member(40, "Grace", "Okafor", null, null);
        AttendanceRecord had = new AttendanceRecord(); had.setId(900L); had.setFamilyMemberId(40); had.setPersonName("Grace Okafor");
        had.setAttendanceDate(LocalDate.of(2026, 3, 1)); had.setServiceType("Sunday Worship");
        when(live.attendance(eq(TENANT), any(), any())).thenReturn(List.of(had));
        StagingRecord strong = staged("attendance", "A1", v("personLink", "40", "attendanceDate", "2026-03-01", "serviceType", "sunday  worship"));
        StagingRecord possible = staged("attendance", "A2", v("personLink", "40", "attendanceDate", "2026-03-01", "serviceType", "Bible Study"));
        StagingRecord named = staged("attendance", "A3", v("fullName", "Grace Okafor", "attendanceDate", "2026-03-01", "serviceType", "Sunday Worship"));
        svc.validate(RUN, TENANT, "actor", "attendance");
        assertThat(strong.getMatchTier()).isEqualTo("STRONG"); assertThat(strong.getDedupeMatchId()).isEqualTo(900L);
        assertThat(possible.getMatchTier()).isEqualTo("POSSIBLE");
        assertThat(named.getMatchTier()).isEqualTo("STRONG");
    }

    @Test
    @DisplayName("rollback: registrations (no soft delete) are removed, but only the rows this import created; an event the church has since registered people for is refused")
    void rollback() {
        StagingRecord r = staged("event_registration", "R1", v()); r.setBatchId(81L); r.setRowStatus("LOADED"); r.setTargetId(55L);
        EventRegistration created = new EventRegistration(); created.setId(55); liveRow(created);
        when(live.remove("event_registration", 55L, TENANT)).thenReturn(true);
        assertThat(svc.rollback(batch(81, "event_registration", "LOADED"), TENANT)).isEqualTo(1);
        verify(live).remove("event_registration", 55L, TENANT);
        verify(live, times(1)).remove(anyString(), anyLong(), anyString());

        ChurchEvent ev = liveEvent(9, "Picnic", LocalDate.of(2026, 4, 18), null);
        StagingRecord e = staged("event", "E1", v()); e.setBatchId(80L); e.setRowStatus("LOADED"); e.setTargetId(9L);
        when(live.dependents("event", 9L, TENANT)).thenReturn(1L);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> svc.rollback(batch(80, "event", "LOADED"), TENANT))
            .hasMessageContaining("events created by this import now have records added outside it: #9 (1)");
        assertThat(ev.isDeleteFlag()).isFalse(); assertThat(e.getRowStatus()).isEqualTo("LOADED");
    }
}
