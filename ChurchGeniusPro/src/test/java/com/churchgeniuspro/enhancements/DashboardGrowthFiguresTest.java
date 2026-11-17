package com.churchgeniuspro.enhancements;

import com.churchgeniuspro.controller.AdminDashboardController;
import com.churchgeniuspro.hibernate.Family;
import com.churchgeniuspro.hibernate.FamilyMember;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 2026-11-16 (item 5) — Home dashboard: Members / Families figures beside Church Growth; the reorganised sections. */
@DisplayName("Home dashboard — Church Growth figures and reorganised sections")
class DashboardGrowthFiguresTest {

    private static Date day(int year, int month, int d) {
        return Date.from(LocalDate.of(year, month, d).atStartOfDay(ZoneId.systemDefault()).toInstant());
    }
    private static FamilyMember person(int id, String type, String gender, String role, Date created) {
        FamilyMember m = new FamilyMember();
        m.setId(id); m.setMemberType(type); m.setGender(gender); m.setRole(role); m.setCreatedDate(created);
        return m;
    }
    private static Family family(int id, Date created, FamilyMember... members) {
        Family f = new Family(); f.setId(id); f.setCreatedDate(created);
        f.setMembers(new ArrayList<>(List.of(members)));
        return f;
    }

    @Test @DisplayName("Members and Families figures are counted from the church's active people and families")
    @SuppressWarnings("unchecked")
    void figures() {
        FamilyMember dad   = person(1, "Member",  "Male",   "Head of Household", day(2024, 3, 1));
        FamilyMember mum   = person(2, "Member",  "Female", "Spouse",            day(2026, 2, 1));
        FamilyMember son   = person(3, "member",  "male",   "Son",               day(2026, 5, 1));   // legacy role = Child
        FamilyMember kid   = person(4, "Member",  null,     "Child",             day(2025, 12, 31));
        FamilyMember guest = person(5, "Guest",   "Female", "Head",              day(2026, 6, 1));
        FamilyMember vis   = person(6, "Visitor", "Male",   "Adult",             day(2026, 7, 1));   // no head → first member
        FamilyMember vis2  = person(7, "Member",  "Female", "Adult",             day(2026, 7, 1));
        List<FamilyMember> people = List.of(dad, mum, son, kid, guest, vis, vis2);
        List<Family> families = List.of(
                family(1, day(2024, 3, 1), mum, dad, son, kid),
                family(2, day(2026, 6, 1), guest),
                family(3, day(2026, 7, 1), vis, vis2),
                family(4, null));

        Map<String, Object> g = AdminDashboardController.growthFigures(people, families, 2026);
        assertThat(g).containsEntry("year", 2026);
        assertThat((Map<String, Object>) g.get("members"))
                .containsEntry("total", 5L).containsEntry("added", 3L).containsEntry("male", 2L).containsEntry("female", 2L)
                .containsEntry("adults", 3L).containsEntry("children", 2L);
        assertThat((Map<String, Object>) g.get("families"))
                .containsEntry("total", 4L).containsEntry("added", 2L).containsEntry("guests", 1L).containsEntry("visitors", 1L);
    }

    @Test @DisplayName("an empty church gives zeros, never an error")
    @SuppressWarnings("unchecked")
    void empty() {
        Map<String, Object> g = AdminDashboardController.growthFigures(List.of(), List.of(), 2026);
        assertThat((Map<String, Object>) g.get("members")).containsEntry("total", 0L).containsEntry("children", 0L);
        assertThat((Map<String, Object>) g.get("families")).containsEntry("total", 0L).containsEntry("visitors", 0L);
    }

    @Test @DisplayName("page: figures beside the chart, Member Distribution removed, events with the calendar, six At a glance cards")
    void page() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/static/home.html"));
        assertThat(html).contains("id=\"growthFiguresCard\"", "renderGrowthFigures(data);", "group('Members', [", "group('Families', [",
                "['Guests', f.guests", "['Visitors', f.visitors", ".growth-row.with-figures",
                "@media (max-width: 900px) { .growth-row.with-figures { grid-template-columns: 1fr; } }");
        assertThat(html).doesNotContain("Member Distribution", "distPanel", "renderDistribution",
                "label:'New Members'", "label:'Guests'", "label:'Families'", "label:'Visitors'", "label:'Members'");
        // Upcoming Events and the Calendar share one section; the growth section has no calendar any more
        int growth = html.indexOf("data-sid=\"s-admin-growth-calendar\"");
        int events = html.indexOf("data-sid=\"s-admin-events-distribution\"");
        int acct   = html.indexOf("data-sid=\"s-acct-banner\"");
        assertThat(html.substring(growth, events)).contains("id=\"growthChart\"").doesNotContain("id=\"miniCal\"");
        assertThat(html.substring(events, acct)).contains("id=\"upcomingList\"", "id=\"miniCal\"");
        // the figures are sent only with the statistics permission
        String ctl = Files.readString(Path.of("src/main/java/com/churchgeniuspro/controller/AdminDashboardController.java"));
        int allowed = ctl.indexOf("if (statsAllowed) {");
        assertThat(ctl.substring(allowed, ctl.indexOf("}", ctl.indexOf("res.put(\"growth\"")))).contains("res.put(\"growth\"");
    }
}
