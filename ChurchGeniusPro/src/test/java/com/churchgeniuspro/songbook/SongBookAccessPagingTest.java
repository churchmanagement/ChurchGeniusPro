package com.churchgeniuspro.songbook;

import com.churchgeniuspro.controller.SongBookAdminController;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.SongAuditLog;
import com.churchgeniuspro.hibernate.SongBookAccess;
import com.churchgeniuspro.model.PageSlice;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.SongBookAccessRepository;
import com.churchgeniuspro.service.SongBookAccessService;
import com.churchgeniuspro.service.SongBookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Song Book Access: members and activity are served 10 at a time, tenant-scoped, admin-only. */
@DisplayName("Song Book Access — server-side paging")
class SongBookAccessPagingTest {

    static final String CID = "CHR-SBA";

    FamilyMemberRepository memberRepo;
    SongBookAccessRepository accessRepo;
    SongBookService songService;
    SongBookAccessService access;
    SongBookAdminController controller;

    @BeforeEach
    void setUp() {
        memberRepo = mock(FamilyMemberRepository.class);
        accessRepo = mock(SongBookAccessRepository.class);
        songService = mock(SongBookService.class);
        access = new SongBookAccessService(accessRepo, memberRepo);
        controller = new SongBookAdminController(access, songService);
    }

    static FamilyMember member(int id, String first, String last) {
        FamilyMember m = new FamilyMember();
        m.setId(id); m.setFirstName(first); m.setLastName(last);
        m.setEmail(first.toLowerCase() + "@x.test"); m.setRole("Member");
        return m;
    }

    static List<FamilyMember> members(int fromId, int count) {
        List<FamilyMember> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(member(fromId + i, "P" + (fromId + i), "Last"));
        return out;
    }

    static MockHttpServletRequest session(String role, String clientId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "admin@x.test");
        s.setAttribute("role", role);
        if (clientId != null) s.setAttribute("appClientId", clientId);
        req.setSession(s);
        return req;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(ResponseEntity<?> r) { return (List<Map<String, Object>>) r.getBody(); }

    @Test
    @DisplayName("first page: asks the database for 11 rows from offset 0, returns 10 + hasMore + total")
    void firstPage() {
        when(memberRepo.findSongBookAccessPage(eq(CID), eq(""), any(Pageable.class))).thenReturn(members(1, 11));
        when(memberRepo.countSongBookAccessMembers(CID, "")).thenReturn(37L);

        ResponseEntity<?> r = controller.members(0, 10, "", session("Admin", CID));

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(rows(r)).hasSize(10);
        assertThat(r.getHeaders().getFirst("X-Has-More")).isEqualTo("true");
        assertThat(r.getHeaders().getFirst("X-Total-Count")).isEqualTo("37");
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(memberRepo).findSongBookAccessPage(eq(CID), eq(""), page.capture());
        assertThat(page.getValue().getOffset()).isZero();
        assertThat(page.getValue().getPageSize()).isEqualTo(11);
        // never loads the whole church any more
        verify(accessRepo, never()).findByClientId(anyString());
    }

    @Test
    @DisplayName("next page: offset 10, no count query, last page reports hasMore=false")
    void nextPage() {
        when(memberRepo.findSongBookAccessPage(eq(CID), eq(""), any(Pageable.class))).thenReturn(members(11, 4));

        ResponseEntity<?> r = controller.members(10, 10, "", session("Admin", CID));

        assertThat(rows(r)).hasSize(4);
        assertThat(r.getHeaders().getFirst("X-Has-More")).isEqualTo("false");
        assertThat(r.getHeaders().getFirst("X-Total-Count")).isNull();
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(memberRepo).findSongBookAccessPage(eq(CID), eq(""), page.capture());
        assertThat(page.getValue().getOffset()).isEqualTo(10);
        verify(memberRepo, never()).countSongBookAccessMembers(anyString(), anyString());
    }

    @Test
    @DisplayName("single short first page: total comes from the rows, no count query")
    void shortFirstPage() {
        when(memberRepo.findSongBookAccessPage(eq(CID), eq(""), any(Pageable.class))).thenReturn(members(1, 3));
        ResponseEntity<?> r = controller.members(0, 10, "", session("SuperAdmin", CID));
        assertThat(r.getHeaders().getFirst("X-Total-Count")).isEqualTo("3");
        assertThat(r.getHeaders().getFirst("X-Has-More")).isEqualTo("false");
        verify(memberRepo, never()).countSongBookAccessMembers(anyString(), anyString());
    }

    @Test
    @DisplayName("access levels are read only for the members on the page; missing grant = NONE")
    @SuppressWarnings("unchecked")
    void levelsForPageOnly() {
        when(memberRepo.findSongBookAccessPage(eq(CID), eq(""), any(Pageable.class))).thenReturn(members(1, 3));
        SongBookAccess full = new SongBookAccess(); full.setMemberId(2L); full.setAccessLevel("FULL");
        when(accessRepo.findByClientIdAndMemberIdIn(eq(CID), anyCollection())).thenReturn(List.of(full));

        PageSlice<Map<String, Object>> page = access.memberPage(CID, "", 0, 10);

        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(accessRepo).findByClientIdAndMemberIdIn(eq(CID), ids.capture());
        assertThat(ids.getValue()).containsExactly(1L, 2L, 3L);
        assertThat(page.rows()).extracting(m -> m.get("level")).containsExactly("NONE", "FULL", "NONE");
        assertThat(page.rows().get(0)).containsKeys("memberId", "firstName", "lastName", "email", "role", "level");
    }

    @Test
    @DisplayName("search is a lower-cased LIKE pattern with wildcards escaped; size is clamped")
    void searchPatternAndClamp() {
        when(memberRepo.findSongBookAccessPage(anyString(), anyString(), any(Pageable.class))).thenReturn(List.of());
        access.memberPage(CID, "  Jo_n 100%! ", -5, 5000);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(memberRepo).findSongBookAccessPage(eq(CID), eq("%jo!_n 100!%!!%"), page.capture());
        assertThat(page.getValue().getOffset()).isZero();
        assertThat(page.getValue().getPageSize()).isEqualTo(SongBookAccessService.MAX_PAGE_SIZE + 1);
    }

    @Test
    @DisplayName("non-admin and member sessions are refused; no church in session → 401, nothing queried")
    void accessControl() {
        assertThat(controller.members(0, 10, "", session("User", CID)).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.members(0, 10, "", session("Member", CID)).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.audit(10, null, null, session("Accountant", CID)).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.members(0, 10, "", session("Admin", null)).getStatusCode().value()).isEqualTo(401);
        assertThat(controller.audit(10, null, null, session("Admin", null)).getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(memberRepo, songService);
    }

    static SongAuditLog log(long id, Instant at) {
        SongAuditLog l = new SongAuditLog();
        l.setId(id); l.setClientId(CID); l.setAction("ACCESS_GRANT"); l.setActor("a"); l.setCreatedAt(at);
        return l;
    }

    @Test
    @DisplayName("activity: newest page without cursor, then older page with the last row's (createdAt, id)")
    void auditPaging() {
        Instant t = Instant.parse("2026-11-01T15:04:05.123456Z");
        when(songService.auditPage(CID, null, null, 10))
                .thenReturn(new PageSlice<>(List.of(log(30, t), log(29, t)), true));
        ResponseEntity<?> first = controller.audit(10, null, null, session("Admin", CID));
        assertThat(first.getHeaders().getFirst("X-Has-More")).isEqualTo("true");
        assertThat(rows(first)).extracting(m -> m.get("id")).containsExactly(30L, 29L);
        assertThat(rows(first).get(0).get("createdAt")).isEqualTo(t.toString());

        when(songService.auditPage(CID, t, 29L, 10)).thenReturn(new PageSlice<>(List.of(log(28, t)), false));
        ResponseEntity<?> older = controller.audit(10, t.toString(), 29L, session("Admin", CID));
        assertThat(older.getHeaders().getFirst("X-Has-More")).isEqualTo("false");
        assertThat(rows(older)).extracting(m -> m.get("id")).containsExactly(28L);
    }

    @Test
    @DisplayName("activity: malformed cursor → 400, nothing queried")
    void badCursor() {
        assertThat(controller.audit(10, "yesterday", 5L, session("Admin", CID)).getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(songService);
    }

    @Test
    @DisplayName("SongBookService.auditPage over-fetches one row and uses the keyset query only with a full cursor")
    void auditServiceWindow() {
        com.churchgeniuspro.repository.SongAuditLogRepository auditRepo =
                mock(com.churchgeniuspro.repository.SongAuditLogRepository.class);
        SongBookService svc = new SongBookService(null, null, null, null, auditRepo, null, null, null);
        Instant t = Instant.now();
        List<SongAuditLog> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) eleven.add(log(100 - i, t));
        when(auditRepo.findNewestPage(eq(CID), any(Pageable.class))).thenReturn(eleven);

        PageSlice<SongAuditLog> p = svc.auditPage(CID, null, null, 10);
        assertThat(p.rows()).hasSize(10);
        assertThat(p.hasMore()).isTrue();
        ArgumentCaptor<Pageable> w = ArgumentCaptor.forClass(Pageable.class);
        verify(auditRepo).findNewestPage(eq(CID), w.capture());
        assertThat(w.getValue().getPageSize()).isEqualTo(11);

        when(auditRepo.findPageBefore(eq(CID), eq(t), eq(91L), any(Pageable.class))).thenReturn(List.of(log(90, t)));
        PageSlice<SongAuditLog> older = svc.auditPage(CID, t, 91L, 10);
        assertThat(older.hasMore()).isFalse();
        assertThat(older.rows()).hasSize(1);
        // half a cursor is treated as "newest"
        svc.auditPage(CID, t, null, 10);
        verify(auditRepo, times(2)).findNewestPage(eq(CID), any(Pageable.class));
    }
}
