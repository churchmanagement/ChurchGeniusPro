package com.churchgeniuspro.etl;

import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.EtlLinkService;
import com.churchgeniuspro.service.EtlLiveLookup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Migration Phase 4 — groups + memberships and Kids Ministry children + check-ins. */
@DisplayName("ETL Phase 4 — groups, memberships, Kids Ministry")
class EtlPhase4GroupsKidsTest extends EtlPhase4Support {

    private Group liveGroup(int id, String name) { Group g = new Group(); g.setId(id); g.setAppClientId(TENANT); g.setGroupName(name); return liveRow(g); }

    @Test
    @DisplayName("groups: same name (case / spacing) → STRONG; punctuation-only difference → POSSIBLE")
    void groupTiers() {
        when(live.groups(TENANT)).thenReturn(List.of(liveGroup(1, "Young Adults"), liveGroup(2, "Men's Breakfast")));
        StagingRecord strong = staged("group", "G1", v("groupName", "  young   ADULTS "));
        StagingRecord possible = staged("group", "G2", v("groupName", "Mens Breakfast"));
        StagingRecord fresh = staged("group", "G3", v("groupName", "Choir"));
        svc.validate(RUN, TENANT, "actor", "group");
        assertThat(strong.getMatchTier()).isEqualTo("STRONG"); assertThat(strong.getDedupeMatchId()).isEqualTo(1L);
        assertThat(possible.getMatchTier()).isEqualTo("POSSIBLE"); assertThat(possible.getDedupeMatchId()).isEqualTo(2L);
        assertThat(fresh.getMatchTier()).isEqualTo("NONE"); assertThat(fresh.getDedupeAction()).isEqualTo("INSERT");
    }

    @Test
    @DisplayName("memberships: group by id (staged) / by live name / created when missing (INFO); group + member → STRONG; names required unless the person resolves")
    void memberships() {
        when(live.groups(TENANT)).thenReturn(List.of(liveGroup(1, "Prayer Team")));
        when(live.groupMembers(TENANT)).thenReturn(List.<Object[]>of(new Object[]{1, 40, "Grace", "Okafor", null, 77}));
        member(40, "Grace", "Okafor", "grace@example.org", null);
        StagingRecord g = staged("group", "G-1", v("groupName", "Young Adults"));
        StagingRecord byId = staged("group_member", "M1", v("groupLink", "G-1", "personLink", "40"));
        StagingRecord dup = staged("group_member", "M2", v("groupName", "prayer team", "personLink", "40"));
        StagingRecord created = staged("group_member", "M3", v("groupName", "Worship Choir", "firstName", "Lena", "lastName", "Walker"));
        StagingRecord noName = staged("group_member", "M4", v("groupName", "Worship Choir", "personLink", "999"));
        svc.validate(RUN, TENANT, "actor", "group_member");
        assertThat(links(byId)).containsEntry("parent", "s" + g.getId()).containsEntry("person", "m40"); assertThat(byId.getRowStatus()).isEqualTo("VALID");
        assertThat(dup.getMatchTier()).isEqualTo("STRONG"); assertThat(dup.getDedupeMatchId()).isEqualTo(77L);
        assertThat(links(created)).containsEntry("parent", "new:Worship Choir"); assertThat(msgText(created)).contains("INFO:Group 'Worship Choir' does not exist yet and will be created");
        assertThat(noName.getRowStatus()).isEqualTo("ERROR"); assertThat(msgText(noName)).contains("needs a first and last name");
    }

    @Test
    @DisplayName("load: a missing group is created ONCE for all its members and recorded as a reference row; members get family_member_id and names from the member")
    void loadMemberships() {
        member(40, "Grace", "Okafor", "grace@example.org", null);
        staged("group_member", "M1", v("groupName", "Worship Choir", "personLink", "40"));
        staged("group_member", "M2", v("groupName", "worship choir", "firstName", "Lena", "lastName", "Walker", "email", "lena@example.org"));
        svc.validate(RUN, TENANT, "actor", "group_member");
        assertThat(load("group_member", 90)).containsExactly(2, 0, 0, 0);
        assertThat(saved.stream().filter(Group.class::isInstance)).hasSize(1);
        Group choir = savedOf(Group.class, 0);
        assertThat(choir.getGroupName()).isEqualTo("Worship Choir"); assertThat(choir.getAppClientId()).isEqualTo(TENANT);
        verify(links).record(TENANT, "GENERIC", ImportLink.REF_GROUP, "Worship Choir", choir.getId().longValue(), RUN, 90L);
        GroupMember a = savedOf(GroupMember.class, 0), b = savedOf(GroupMember.class, 1);
        assertThat(a.getGroup()).isSameAs(choir); assertThat(a.getFamilyMemberId()).isEqualTo(40);
        assertThat(a.getFirstName()).isEqualTo("Grace"); assertThat(a.getEmail()).isEqualTo("grace@example.org");
        assertThat(b.getFamilyMemberId()).isNull(); assertThat(b.getLastName()).isEqualTo("Walker");
    }

    @Test
    @DisplayName("children: first + last + birth date → STRONG; + guardian → POSSIBLE; future birth date → ERROR; parent linked, classroom created once, parent name/phone filled from the member")
    void children() {
        KmChild had = new KmChild(); had.setId(300L); had.setFirstName("Ada"); had.setLastName("Okafor"); had.setDob(LocalDate.of(2018, 6, 10)); had.setParentName("Grace Okafor");
        when(live.children(TENANT)).thenReturn(List.of(had));
        member(40, "Grace", "Okafor", "grace@example.org", "9135550401");
        StagingRecord strong = staged("km_child", "K1", v("firstName", "Ada", "lastName", "Okafor", "dob", "2018-06-10"));
        StagingRecord possible = staged("km_child", "K2", v("firstName", "ADA", "lastName", "okafor", "parentLink", "40"));
        StagingRecord future = staged("km_child", "K3", v("firstName", "Zed", "lastName", "Z", "dob", LocalDate.now().plusDays(3).toString()));
        StagingRecord a = staged("km_child", "K4", v("firstName", "Leo", "lastName", "Okafor", "parentLink", "40", "classroom", "Preschool"));
        StagingRecord b = staged("km_child", "K5", v("firstName", "Mia", "lastName", "Okafor", "parentLink", "40", "classroom", "preschool"));
        svc.validate(RUN, TENANT, "actor", "km_child");
        assertThat(strong.getMatchTier()).isEqualTo("STRONG"); assertThat(strong.getMatchDetail()).isEqualTo("first + last name + birth date");
        assertThat(possible.getMatchTier()).isEqualTo("POSSIBLE"); assertThat(possible.getMatchDetail()).isEqualTo("first + last name + guardian");
        assertThat(future.getRowStatus()).isEqualTo("ERROR");
        assertThat(links(a)).containsEntry("classroom", "new:Preschool");

        possible.setRowStatus("WARN"); possible.setDedupeAction("INSERT");
        strong.setDedupeAction("SKIP");
        assertThat(load("km_child", 95)).containsExactly(3, 0, 1, 0);
        assertThat(saved.stream().filter(KmClassroom.class::isInstance)).hasSize(1);     // "Preschool" / "preschool" → one room
        KmChild leo = saved.stream().filter(KmChild.class::isInstance).map(KmChild.class::cast).filter(k -> "Leo".equals(k.getFirstName())).findFirst().orElseThrow();
        assertThat(leo.getFamilyMemberId()).isEqualTo(40); assertThat(leo.getParentName()).isEqualTo("Grace Okafor");
        assertThat(leo.getParentPhone()).isEqualTo("9135550401"); assertThat(leo.getParentEmail()).isEqualTo("grace@example.org");
        assertThat(leo.getClassroomId()).isEqualTo(savedOf(KmClassroom.class, 0).getId());
        assertThat(strong.getRowStatus()).isEqualTo("SKIPPED");
    }

    @Test
    @DisplayName("check-ins: child by id (staged) or name + birth date (live); unknown child → ERROR; check-out before check-in → ERROR; load always closes the check-in and uses the child's classroom")
    void checkins() {
        KmChild live1 = new KmChild(); live1.setId(300L); live1.setClientId(TENANT); live1.setFirstName("Ada"); live1.setLastName("Okafor"); live1.setDob(LocalDate.of(2018, 6, 10)); live1.setClassroomId(12L);
        liveRow(live1);
        when(live.children(TENANT)).thenReturn(List.of(live1));
        KmCheckin had = new KmCheckin(); had.setId(600L); had.setChildId(300L); had.setCheckinTime(LocalDateTime.of(2026, 3, 1, 9, 45, 30));
        when(live.checkins(eq(TENANT), any(), any())).thenReturn(List.of(had));
        StagingRecord kid = staged("km_child", "K-2", v("firstName", "Leo", "lastName", "Okafor"));
        StagingRecord dup = staged("km_checkin", "CK1", v("childFirstName", "ada", "childLastName", "Okafor", "childDob", "2018-06-10", "checkinTime", "2026-03-01T09:45"));
        StagingRecord open = staged("km_checkin", "CK2", v("childFirstName", "Ada", "childLastName", "Okafor", "checkinTime", "2026-03-08T09:50"));
        StagingRecord unknown = staged("km_checkin", "CK3", v("childLink", "K-404", "checkinTime", "2026-03-01T09:00"));
        StagingRecord backwards = staged("km_checkin", "CK4", v("childLink", "K-2", "checkinTime", "2026-03-01T10:00", "checkoutTime", "2026-03-01T09:00"));
        svc.validate(RUN, TENANT, "actor", "km_checkin");
        assertThat(dup.getMatchTier()).isEqualTo("STRONG"); assertThat(dup.getDedupeMatchId()).isEqualTo(600L);
        assertThat(links(open)).containsEntry("parent", "l300"); assertThat(msgText(open)).contains("recorded as checked out at the check-in time");
        assertThat(unknown.getRowStatus()).isEqualTo("ERROR"); assertThat(msgText(unknown)).contains("child id 'K-404' was not found");
        assertThat(links(backwards)).containsEntry("parent", "s" + kid.getId()); assertThat(backwards.getRowStatus()).isEqualTo("ERROR");

        assertThat(load("km_checkin", 96)).containsExactly(1, 0, 1, 0);
        KmCheckin c = savedOf(KmCheckin.class, 0);
        assertThat(c.getChildId()).isEqualTo(300L); assertThat(c.getClassroomId()).isEqualTo(12L);
        assertThat(c.getCheckoutTime()).isEqualTo(c.getCheckinTime()); assertThat(c.getCheckoutMethod()).isEqualTo("import");
    }

    @Test
    @DisplayName("rollback: check-ins are removed (no soft delete); an inserted child with check-ins added outside the import is refused; an UPDATE is restored from its before-image")
    void rollback() {
        StagingRecord ck = staged("km_checkin", "CK1", v()); ck.setBatchId(96L); ck.setRowStatus("LOADED"); ck.setTargetId(600L);
        KmCheckin row = new KmCheckin(); row.setId(600L); liveRow(row);
        when(live.remove("km_checkin", 600L, TENANT)).thenReturn(true);
        svc.rollback(batch(96, "km_checkin", "LOADED"), TENANT);
        verify(live).remove("km_checkin", 600L, TENANT);

        KmChild ins = new KmChild(); ins.setId(301L); liveRow(ins);
        KmChild upd = new KmChild(); upd.setId(302L); upd.setGrade("5th"); upd.setFirstName("Mia"); liveRow(upd);
        StagingRecord a = staged("km_child", "K1", v()); a.setBatchId(95L); a.setRowStatus("LOADED"); a.setTargetId(301L);
        StagingRecord b = staged("km_child", "K2", v()); b.setBatchId(95L); b.setRowStatus("LOADED"); b.setTargetId(302L);
        b.setBeforeImage("{\"firstName\":\"Mia\",\"grade\":\"4th\",\"dob\":\"2016-02-14\",\"classroomId\":12,\"familyMemberId\":null}");
        when(live.dependents("km_child", 301L, TENANT)).thenReturn(3L);
        assertThatThrownBy(() -> svc.rollback(batch(95, "km_child", "LOADED"), TENANT)).hasMessageContaining("#301 (3)");
        assertThat(upd.getGrade()).isEqualTo("5th");
        when(live.dependents("km_child", 301L, TENANT)).thenReturn(0L);
        svc.rollback(batch(95, "km_child", "LOADED"), TENANT);
        assertThat(ins.isDeleteFlag()).isTrue();
        assertThat(upd.getGrade()).isEqualTo("4th"); assertThat(upd.getDob()).isEqualTo(LocalDate.of(2016, 2, 14)); assertThat(upd.getClassroomId()).isEqualTo(12L);
        assertThat(upd.isDeleteFlag()).isFalse();
    }

    @Test
    @DisplayName("EtlLinkService: a group / classroom an import created is soft-deleted on rollback only when nothing uses it; a fund still used by a live campaign is kept")
    void referenceCleanup() {
        ImportLinkRepository linkRepo = mock(ImportLinkRepository.class);
        SubSourceRepository subs = mock(SubSourceRepository.class);
        IncomeRepository income = mock(IncomeRepository.class);
        EtlLinkService ls = new EtlLinkService(linkRepo, subs, mock(MainSourceRepository.class), mock(PurposeRepository.class),
            mock(TransactionTypeRepository.class), income, mock(ExpenseRepository.class));
        EtlLiveLookup lk = mock(EtlLiveLookup.class);
        ls.setLookup(lk);
        ImportLink gFree = link(ImportLink.REF_GROUP, 1L), gUsed = link(ImportLink.REF_GROUP, 2L), room = link(ImportLink.REF_KM_CLASSROOM, 3L), fund = link(ImportLink.REF_SUB_SOURCE, 4L);
        when(linkRepo.findByBatchId(90L)).thenReturn(List.of(gFree, gUsed, room, fund));
        Group free = new Group(); free.setId(1); Group used = new Group(); used.setId(2); KmClassroom r = new KmClassroom(); r.setId(3L);
        doReturn(Optional.of(free)).when(lk).one("group", 1L, TENANT);
        doReturn(Optional.of(used)).when(lk).one("group", 2L, TENANT);
        doReturn(Optional.of(r)).when(lk).one("km_classroom", 3L, TENANT);
        when(lk.dependents("group", 2L, TENANT)).thenReturn(4L);
        SubSource s = new SubSource(); s.setId(4); s.setAppClientId(TENANT); s.setDeleteFlag(false);
        when(subs.findById(4)).thenReturn(Optional.of(s));
        when(lk.dependents("sub_source", 4L, TENANT)).thenReturn(1L);                  // a live campaign points at the fund
        int[] res = ls.rollbackBatch(TENANT, 90L);
        assertThat(res).containsExactly(4, 2, 2);
        assertThat(free.isDeleteFlag()).isTrue(); assertThat(used.isDeleteFlag()).isFalse(); assertThat(r.isDeleteFlag()).isTrue(); assertThat(s.isDeleteFlag()).isFalse();
    }

    private static ImportLink link(String entity, Long target) {
        ImportLink l = new ImportLink(); l.setClientId(TENANT); l.setEntity(entity); l.setSourceKey("k" + target); l.setTargetId(target); return l;
    }
}
