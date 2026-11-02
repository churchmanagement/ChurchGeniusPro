package com.churchgeniuspro.integration;

import com.churchgeniuspro.hibernate.Family;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.SongAuditLog;
import com.churchgeniuspro.model.PageSlice;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.FamilyRepository;
import com.churchgeniuspro.repository.SongAuditLogRepository;
import com.churchgeniuspro.service.SongBookAccessService;
import com.churchgeniuspro.service.SongBookService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Song Book Access paging against real PostgreSQL with two churches: the members page
 * query (active rules, order, search, wildcard escaping, tenant isolation) and the
 * activity log keyset paging (newest first, equal timestamps, no gaps or repeats,
 * stable while new entries are logged).
 *
 * <p>Runs on {@code verify} with Docker (Testcontainers), or against {@code CGP_IT_JDBC_URL}; skipped otherwise.
 */
@EnabledIf("com.churchgeniuspro.integration.SongBookAccessPagingIT#databaseAvailable")
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = { "app.base-url=http://localhost" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Song Book Access — paging on PostgreSQL (two churches)")
class SongBookAccessPagingIT {

    static PostgreSQLContainer<?> POSTGRES;

    static boolean databaseAvailable() {
        if (System.getenv("CGP_IT_JDBC_URL") != null) return true;
        try { return DockerClientFactory.instance().isDockerAvailable(); } catch (Throwable t) { return false; }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        String url = System.getenv("CGP_IT_JDBC_URL");
        if (url != null) {
            r.add("spring.datasource.url", () -> url);
            r.add("spring.datasource.username", () -> System.getenv().getOrDefault("CGP_IT_JDBC_USER", "postgres"));
            r.add("spring.datasource.password", () -> System.getenv().getOrDefault("CGP_IT_JDBC_PASSWORD", ""));
            return;
        }
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
        POSTGRES.start();
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    static final String A = "CGP-SBP-ITA", B = "CGP-SBP-ITB";

    @Autowired SongBookAccessService access;
    @Autowired SongBookService songs;
    @Autowired FamilyRepository familyRepo;
    @Autowired FamilyMemberRepository memberRepo;
    @Autowired SongAuditLogRepository auditRepo;

    @BeforeAll
    void seed() {
        Family fa = family(A, false, false);
        // 23 active members in church A, inserted out of alphabetical order
        for (int i = 23; i >= 1; i--) member(fa, String.format("Ann%02d", i), i % 2 == 0 ? "Zane" : "Baker", "ann" + i + "@a.test", false, false);
        member(fa, "Percy", "Cent", "100%off@a.test", false, false);      // for wildcard escaping
        member(fa, "Gone", "Deleted", "gone@a.test", false, true);          // deleted member
        member(fa, "Quiet", "Inactive", "quiet@a.test", true, false);       // inactive member
        Family deletedFam = family(A, true, false);
        member(deletedFam, "Inherited", "Deleted", "inh@a.test", false, false);
        Family inactiveFam = family(A, false, true);
        member(inactiveFam, "Inherited", "Inactive", "ina@a.test", false, false);
        Family fb = family(B, false, false);
        member(fb, "Ann99", "Baker", "other@b.test", false, false);        // other church, matching name

        // activity: 25 entries in A, several sharing one timestamp; 3 in B
        Instant base = Instant.now().truncatedTo(ChronoUnit.MICROS).minus(1, ChronoUnit.HOURS);
        for (int i = 0; i < 25; i++) audit(A, i < 6 ? base : base.plusSeconds(i), "A-" + i);
        for (int i = 0; i < 3; i++) audit(B, base.plusSeconds(100 + i), "B-" + i);
    }

    Family family(String cid, boolean deleted, boolean inactive) {
        Family f = new Family(); f.setAppClientId(cid); f = familyRepo.save(f);
        if (deleted || inactive) { f.setDeleteFlag(deleted); f.setInactive(inactive); f = familyRepo.save(f); }
        return f;
    }

    FamilyMember member(Family f, String first, String last, String email, boolean inactive, boolean deleted) {
        FamilyMember m = new FamilyMember(); m.setFamily(f); m.setFirstName(first); m.setLastName(last); m.setEmail(email);
        m.setRole("Member"); m.setGender("Female"); m.setInactive(inactive);
        m = memberRepo.save(m);
        if (deleted) { m.setDeleteFlag(true); m = memberRepo.save(m); }
        return m;
    }

    void audit(String cid, Instant at, String detail) {
        SongAuditLog l = new SongAuditLog();
        l.setClientId(cid); l.setActor("it"); l.setRole("Admin"); l.setAction("ACCESS_GRANT"); l.setDetail(detail); l.setCreatedAt(at);
        auditRepo.save(l);
    }

    static String name(Map<String, Object> m) { return m.get("firstName") + " " + m.get("lastName"); }

    @Test
    @DisplayName("members: batches of 10 cover every active member once, in last/first name order, no other church")
    void membersInBatches() {
        List<Map<String, Object>> all = new ArrayList<>();
        int offset = 0, pages = 0;
        PageSlice<Map<String, Object>> p;
        do {
            p = access.memberPage(A, "", offset, 10);
            assertThat(p.rows().size()).isLessThanOrEqualTo(10);
            all.addAll(p.rows());
            offset += p.rows().size();
            pages++;
        } while (p.hasMore());

        assertThat(pages).isEqualTo(3);                       // 24 active → 10 + 10 + 4
        assertThat(all).hasSize(24);
        assertThat(access.memberCount(A, "")).isEqualTo(24);
        assertThat(all.stream().map(m -> m.get("memberId")).distinct().count()).isEqualTo(24);
        assertThat(all).extracting(SongBookAccessPagingIT::name)
                .doesNotContain("Gone Deleted", "Quiet Inactive", "Inherited Deleted", "Inherited Inactive", "Ann99 Baker");
        List<String> order = all.stream().map(m -> m.get("lastName") + "|" + m.get("firstName")).toList();
        assertThat(order).isSorted();
        assertThat(all).allSatisfy(m -> assertThat(m.get("level")).isIn("NONE", "VIEW", "FULL"));
    }

    @Test
    @DisplayName("members: search by full name, e-mail and nickname is server-side, case-insensitive and escaped")
    void search() {
        assertThat(access.memberPage(A, "ann05 baker", 0, 10).rows()).extracting(SongBookAccessPagingIT::name).containsExactly("Ann05 Baker");
        assertThat(access.memberCount(A, "ZANE")).isEqualTo(11);
        assertThat(access.memberPage(A, "ann1", 0, 10).rows()).hasSize(10);   // Ann10..Ann19
        assertThat(access.memberPage(A, "100%", 0, 10).rows()).extracting(SongBookAccessPagingIT::name).containsExactly("Percy Cent");
        assertThat(access.memberCount(A, "%")).isEqualTo(1);                  // literal %, not "everything"
        assertThat(access.memberCount(A, "_")).isZero();
        assertThat(access.memberCount(A, "other@b.test")).isZero();           // other church never matches
    }

    @Test
    @DisplayName("members: a granted level is returned for the member on the page")
    void levelOnPage() {
        Map<String, Object> first = access.memberPage(A, "ann07", 0, 10).rows().get(0);
        Long id = (Long) first.get("memberId");
        access.setLevel(A, id, "VIEW", "it");
        assertThat(access.memberPage(A, "ann07", 0, 10).rows().get(0).get("level")).isEqualTo("VIEW");
        assertThat(access.memberPage(B, "ann07", 0, 10).rows()).isEmpty();
    }

    @Test
    @DisplayName("activity: newest first, 10 at a time, equal timestamps neither skipped nor repeated, stable when new rows arrive")
    void activityKeyset() {
        List<SongAuditLog> seen = new ArrayList<>();
        PageSlice<SongAuditLog> p = songs.auditPage(A, null, null, 10);
        seen.addAll(p.rows());
        assertThat(p.hasMore()).isTrue();

        // a new entry is logged while the admin is reading (e.g. an access change)
        audit(A, Instant.now(), "A-new");

        while (p.hasMore()) {
            SongAuditLog last = seen.get(seen.size() - 1);
            p = songs.auditPage(A, last.getCreatedAt(), last.getId(), 10);
            seen.addAll(p.rows());
        }
        assertThat(seen).hasSize(25);                                     // the original 25, no gaps, no repeats
        assertThat(seen.stream().map(SongAuditLog::getId).distinct().count()).isEqualTo(25);
        assertThat(seen).extracting(SongAuditLog::getDetail).doesNotContain("A-new", "B-0", "B-1", "B-2");
        for (int i = 1; i < seen.size(); i++) {
            SongAuditLog a = seen.get(i - 1), b = seen.get(i);
            int c = b.getCreatedAt().compareTo(a.getCreatedAt());
            assertThat(c < 0 || (c == 0 && b.getId() < a.getId())).as("row %d older than row %d", i, i - 1).isTrue();
        }
        // a fresh first page now starts with the new entry
        assertThat(songs.auditPage(A, null, null, 10).rows().get(0).getDetail()).isEqualTo("A-new");
    }
}
