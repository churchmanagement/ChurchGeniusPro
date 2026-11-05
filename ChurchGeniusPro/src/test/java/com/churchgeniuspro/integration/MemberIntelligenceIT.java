package com.churchgeniuspro.integration;

import com.churchgeniuspro.ai.filter.PeopleFilter;
import com.churchgeniuspro.ai.filter.PeopleFilter.*;
import com.churchgeniuspro.ai.filter.PeopleFilterService;
import com.churchgeniuspro.ai.intel.MemberIntelligenceService;
import com.churchgeniuspro.ai.intel.MinistryReportService;
import com.churchgeniuspro.ai.intel.MinistryReportService.Access;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.service.AiAccessPolicy.CrossAccess;
import com.churchgeniuspro.service.AuditTableRetentionService;
import com.churchgeniuspro.util.AppClock;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI Phase 2 on a real PostgreSQL with two seeded churches (Church-B names carry {@code ZZB}, and Church B holds
 * rows that point at Church-A people): the nightly snapshot gives every live member the expected signal, is
 * idempotent per day, never reads the other church; the Engagement predicate, Family 360 and the Ministry
 * Report agree with hand SQL; snapshot rows and report JSON hold no names or amounts; migration block and
 * retention purge behave.
 */
@EnabledIf("com.churchgeniuspro.integration.MemberIntelligenceIT#databaseAvailable")
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = { "app.base-url=http://localhost", "ai.intel.enabled=false" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("AI Phase 2 — engagement snapshots + ministry report on PostgreSQL (two churches)")
class MemberIntelligenceIT {

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

    static final String A = "CGP-MI-ITA-" + System.currentTimeMillis(), B = A.replace("ITA", "ITB"), MARK = "ZZB";

    @Autowired MemberIntelligenceService intel;
    @Autowired MinistryReportService reports;
    @Autowired PeopleFilterService filters;
    @Autowired MemberEngagementSnapshotRepository snapshots;
    @Autowired AuditTableRetentionService retention;
    @Autowired JdbcTemplate jdbc;
    @Autowired FamilyRepository familyRepo; @Autowired FamilyMemberRepository memberRepo;
    @Autowired AttendanceRecordRepository attendanceRepo; @Autowired AttendanceVisitorRepository visitorRepo;
    @Autowired GroupRepository groupRepo; @Autowired GroupMemberRepository groupMemberRepo;
    @Autowired VolunteerAssignmentRepository assignmentRepo; @Autowired IncomeRepository incomeRepo;
    @Autowired MainSourceRepository mainSourceRepo; @Autowired SubSourceRepository subSourceRepo;
    @Autowired FollowUpRepository followUpRepo;

    LocalDate today;
    final Map<String, Map<String, Integer>> ids = new HashMap<>();
    final Map<String, Integer> familyIds = new HashMap<>();
    static final AiAccess ALL = new AiAccess("Admin", "pat", false, false, false, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, Set.of());
    static final CrossAccess XALL = new CrossAccess(true, true);

    @BeforeAll
    void seed() {
        today = AppClock.today();
        seedChurch(A, "");
        seedChurch(B, MARK);
        // Church-B rows pointing at Church-A people: attendance and gifts for A's quiet member, a group link for A's Eve
        for (int d : new int[] { 1, 8, 15, 22 }) attend(B, ids.get(A).get("Dan"), today.minusDays(d));
        Group bg = group(B, MARK + " Choir");
        groupMember(bg, "Eve", "Stone", null, ids.get(A).get("Eve"));
    }

    void seedChurch(String cid, String tag) {
        Map<String, Integer> m = new HashMap<>(); ids.put(cid, m);
        Family f = new Family(); f.setAppClientId(cid); f = familyRepo.save(f); familyIds.put(cid, f.getId());
        FamilyMember ann  = member(f, tag + "Ann",  "Lee",   "ann" + tag + "@x.test", "Head of Household", false, false);   // ENGAGED
        FamilyMember bob  = member(f, tag + "Bob",  "Ray",   null,                    "Spouse",            false, false);   // AT_RISK (regular who stopped)
        FamilyMember cara = member(f, tag + "Cara", "Fox",   null,                    "Daughter",          false, false);   // NEW
        FamilyMember dan  = member(f, tag + "Dan",  "Fox",   null,                    "Son",               false, false);   // QUIET
        FamilyMember eve  = member(f, tag + "Eve",  "Stone", null,                    "Adult",             false, false);   // WATCH (group only, attended > 1 year ago)
        FamilyMember fay  = member(f, tag + "Fay",  "Hill",  null,                    "Adult",             false, false);   // AT_RISK (gave last year, not this)
        FamilyMember gus  = member(f, tag + "Gus",  "Moe",   null,                    "Adult",             false, false);   // WATCH (attendance stopped 50 days ago)
        FamilyMember hal  = member(f, tag + "Hal",  "Inact", null,                    "Adult",             true,  false);   // no row
        FamilyMember ivy  = member(f, tag + "Ivy",  "Gone",  null,                    "Adult",             false, true);    // no row
        for (FamilyMember x : List.of(ann, bob, cara, dan, eve, fay, gus, hal, ivy)) m.put(x.getFirstName().replace(tag, ""), x.getId());

        for (int d : new int[] { 2, 9, 16, 23, 30, 37, 44, 51, 58, 65, 72, 79, 86 }) attend(cid, ann.getId(), today.minusDays(d));
        attend(cid, ann.getId(), today.minusDays(2));                                        // same day twice → one attendance day
        attend(cid, ann.getId(), today.minusYears(3));
        for (int d : new int[] { 45, 52, 59, 66 }) attend(cid, bob.getId(), today.minusDays(d));
        attend(cid, bob.getId(), today.minusYears(2));
        for (int d : new int[] { 15, 8, 1 }) attend(cid, cara.getId(), today.minusDays(d));
        attend(cid, eve.getId(), today.minusDays(400));
        attend(cid, fay.getId(), today.minusDays(200));
        attend(cid, gus.getId(), today.minusDays(50)); attend(cid, gus.getId(), today.minusYears(2));   // not new
        attend(cid, hal.getId(), today.minusDays(1)); attend(cid, ivy.getId(), today.minusDays(1));
        AttendanceRecord gone = attend(cid, dan.getId(), today.minusDays(1)); gone.setDeleteFlag(true); attendanceRepo.save(gone);   // deleted → still QUIET
        AttendanceRecord absent = attend(cid, dan.getId(), today.minusDays(3)); absent.setStatus("ABSENT"); attendanceRepo.save(absent);

        Group youth = group(cid, tag + " Youth Group");
        groupMember(youth, eve.getFirstName(), "Stone", null, eve.getId());
        groupMember(youth, "Fam", "Fox", "family" + tag + "@x.test", null);     // nobody's unique email

        assign(cid, ann.getId(), today.minusDays(30), "confirmed");
        assign(cid, bob.getId(), today.minusDays(10), "declined");

        MainSource ms = new MainSource(); ms.setSourceName(tag + " Offerings"); ms.setAppClientId(cid); ms = mainSourceRepo.save(ms);
        SubSource general = new SubSource(); general.setSourceName(tag + " General"); general.setMainSource(ms); general.setAppClientId(cid); general = subSourceRepo.save(general);
        gift(cid, general, ann, today.minusDays(5), "100.00", false);
        gift(cid, general, ann, today.minusDays(40), "100.00", false);
        gift(cid, general, fay, today.minusDays(400), "250.00", false);
        gift(cid, general, fay, today.minusDays(500), "250.00", false);
        gift(cid, general, gus, today.minusDays(120), "20.00", false);
        gift(cid, general, dan, today.minusDays(10), "999.00", true);           // deleted gift → QUIET stays

        // a first-time visitor this month and a follow-up task
        AttendanceVisitor v = new AttendanceVisitor(); v.setClientId(cid); v.setName(tag + "Vic Visitor"); v.setFirstName(tag + "Vic"); v.setLastName("Visitor");
        v.setFirstVisitDate(today); v.setLastVisitDate(today); v.setVisitCount(1); v = visitorRepo.save(v);
        AttendanceRecord vr = new AttendanceRecord(); vr.setClientId(cid); vr.setVisitorId(v.getId()); vr.setPersonType("VISITOR"); vr.setAttendanceDate(today); vr.setStatus("PRESENT"); attendanceRepo.save(vr);
        FollowUp fu = new FollowUp(); fu.setClientId(cid); fu.setTitle(tag + " call Vic"); followUpRepo.save(fu);
    }

    Integer a(String n) { return ids.get(A).get(n); }

    @Test @Order(1) @DisplayName("snapshot: one row per live member with the expected signal, idempotent per day, force replaces, other church's rows never read")
    void snapshot() {
        int n = intel.snapshot(A, today, false);
        assertThat(n).isEqualTo(7);
        assertThat(intel.snapshot(A, today, false)).isEqualTo(-1);                 // same day → skipped
        assertThat(snapshots.countByAppClientIdAndSnapshotDate(A, today)).isEqualTo(7);
        assertThat(intel.snapshot(A, today, true)).isEqualTo(7);                   // force → replaced, still 7
        assertThat(snapshots.countByAppClientIdAndSnapshotDate(A, today)).isEqualTo(7);
        assertThat(intel.snapshot(B, today, false)).isEqualTo(7);

        Map<String, String> sig = new HashMap<>();
        for (MemberEngagementSnapshot s : snapshots.findByAppClientIdAndSnapshotDate(A, today)) sig.put(name(s.getMemberId()), s.getSignal());
        assertThat(sig).containsEntry("Ann", "ENGAGED").containsEntry("Bob", "AT_RISK").containsEntry("Cara", "NEW").containsEntry("Dan", "QUIET")
                       .containsEntry("Eve", "WATCH").containsEntry("Fay", "AT_RISK").containsEntry("Gus", "WATCH");
        assertThat(sig).doesNotContainKeys("Hal", "Ivy");

        // facts vs hand SQL
        MemberEngagementSnapshot ann = intel.latestFor(A, a("Ann"));
        assertThat(ann.getAttendance30()).isEqualTo(jdbc.queryForObject("SELECT COUNT(DISTINCT attendance_date) FROM attendance_record WHERE client_id = ? AND family_member_id = ? AND delete_flag = false AND attendance_date > ?", Integer.class, A, a("Ann"), today.minusDays(30)));
        assertThat(ann.getAttendance30()).isEqualTo(4);     // 2, 9, 16, 23 (day 30 is outside "> today-30"; the duplicate day counts once)
        assertThat(ann.getAttendance90()).isEqualTo(13);
        assertThat(ann.getAttendance365()).isEqualTo(13);
        assertThat(ann.getGifts90()).isEqualTo(2);
        assertThat(ann.isServing()).isTrue();
        assertThat(ann.getGroupsCount()).isZero();
        MemberEngagementSnapshot dan = intel.latestFor(A, a("Dan"));
        assertThat(dan.getAttendance365()).as("B's attendance of Dan, the deleted row and the ABSENT row never count").isZero();
        assertThat(dan.getGifts365()).as("deleted gift").isZero();
        MemberEngagementSnapshot eve = intel.latestFor(A, a("Eve"));
        assertThat(eve.getGroupsCount()).as("A's Youth Group only — B's Choir link is another church").isEqualTo(1);
        MemberEngagementSnapshot fay = intel.latestFor(A, a("Fay"));
        assertThat(fay.getGifts365()).isZero(); assertThat(fay.getGiftsPrior365()).isEqualTo(2);
        assertThat(MemberIntelligenceService.fromJson(fay.getReasonsJson())).anyMatch(r -> r.contains("2 gifts recorded in the previous 12 months"));

        // no PII in the table: reasons never carry a name or a dollar amount
        for (Map<String, Object> row : jdbc.queryForList("SELECT reasons_json FROM member_engagement_snapshot WHERE app_client_id = ?", A)) {
            String r = String.valueOf(row.get("reasons_json"));
            assertThat(r).doesNotContain("Ann").doesNotContain("Lee").doesNotContain("$").doesNotContain("100.00");
        }
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'member_engagement_snapshot'"))
                .extracting(c -> (String) c.get("column_name")).noneMatch(c -> c.contains("name") || c.contains("amount") || c.contains("email"));

        // the other church's snapshot describes its own people
        for (MemberEngagementSnapshot s : snapshots.findByAppClientIdAndSnapshotDate(B, today)) assertThat(ids.get(B).containsValue(s.getMemberId())).isTrue();
    }

    @SuppressWarnings("unchecked")
    @Test @Order(2) @DisplayName("Engagement predicate in people filters; Family 360 with and without finance; summary counts")
    void readers() {
        if (!snapshots.existsByAppClientIdAndSnapshotDate(A, today)) { intel.snapshot(A, today, false); intel.snapshot(B, today, false); }
        Map<String, Object> m = filters.run(PeopleFilter.of(new Engagement("AT_RISK")), ALL, XALL, A, today);
        assertThat(m.get("denied")).isEqualTo(false);
        assertThat((List<Integer>) m.get("memberIds")).containsExactlyInAnyOrder(a("Bob"), a("Fay"));
        assertThat((List<String>) m.get("columns")).contains("Last attended");
        assertThat((List<String>) m.get("definitions")).anyMatch(d -> d.startsWith("Engagement (At risk)"));
        m = filters.run(PeopleFilter.of(new Engagement("WATCH"), new InGroup(true, null)), ALL, XALL, A, today);
        assertThat((List<Integer>) m.get("memberIds")).containsExactly(a("Gus"));
        m = filters.run(PeopleFilter.of(new Engagement("NEW")), ALL, XALL, B, today);
        assertThat((List<Integer>) m.get("memberIds")).containsExactly(ids.get(B).get("Cara"));
        // Attendance is the gating module
        assertThat(filters.run(PeopleFilter.of(new Engagement("NEW")), ALL, new CrossAccess(false, true), A, today).get("denied")).isEqualTo(true);

        Map<String, Object> fam = intel.family(A, familyIds.get(A), true);
        List<Map<String, Object>> members = (List<Map<String, Object>>) fam.get("members");
        assertThat(members).hasSize(8);                                              // Hal (inactive) listed without a signal, Ivy (deleted) not listed
        Map<String, Map<String, Object>> byName = new HashMap<>();
        for (Map<String, Object> x : members) byName.put(((String) x.get("name")).split(" ")[0], x);
        assertThat(byName.get("Bob")).containsEntry("signal", "AT_RISK").containsKey("giving");
        assertThat(byName.get("Hal").get("signal")).isNull();
        assertThat((List<String>) byName.get("Fay").get("reasons")).anyMatch(MemberIntelligenceService::isGivingReason);
        Map<String, Object> noFin = intel.family(A, familyIds.get(A), false);
        for (Map<String, Object> x : (List<Map<String, Object>>) noFin.get("members")) {
            assertThat(x).doesNotContainKey("giving");
            if (x.get("reasons") != null) assertThat((List<String>) x.get("reasons")).noneMatch(MemberIntelligenceService::isGivingReason);
        }
        assertThat(intel.family(A, familyIds.get(B), true)).as("another church's family").isNull();

        Map<String, Object> sum = intel.summary(A, today);
        Map<String, Long> counts = (Map<String, Long>) sum.get("counts");
        assertThat(counts).containsEntry("ENGAGED", 1L).containsEntry("AT_RISK", 2L).containsEntry("WATCH", 2L).containsEntry("NEW", 1L).containsEntry("QUIET", 1L);
        assertThat((Map<String, Long>) intel.summary(A, today.minusDays(1)).get("counts")).containsEntry("ENGAGED", 0L);   // no snapshot before today
    }

    @SuppressWarnings("unchecked")
    @Test @Order(3) @DisplayName("Ministry Report: this month's figures equal hand SQL, finance only when allowed, engagement counts, no PII, other church separate")
    void report() {
        if (!snapshots.existsByAppClientIdAndSnapshotDate(A, today)) intel.snapshot(A, today, false);
        YearMonth ym = YearMonth.from(today);
        Map<String, Object> r = reports.monthly(A, ym.getYear(), ym.getMonthValue(), Access.all(), today);
        assertThat(r.get("partial")).isEqualTo(true);
        Map<String, Object> cur = (Map<String, Object>) r.get("current");
        Map<String, Object> att = (Map<String, Object>) cur.get("attendance");
        long checkins = jdbc.queryForObject("SELECT COUNT(*) FROM attendance_record WHERE client_id = ? AND delete_flag = false AND (status IS NULL OR status IN ('PRESENT','LATE')) AND attendance_date BETWEEN ? AND ?",
                Long.class, A, ym.atDay(1), ym.atEndOfMonth());
        assertThat(att.get("checkins")).isEqualTo(checkins);
        assertThat(((Number) att.get("visitors")).longValue()).isEqualTo(1L);
        Map<String, Object> vis = (Map<String, Object>) cur.get("visitors");
        assertThat(vis.get("firstTime")).isEqualTo(1L);
        Map<String, Object> fu = (Map<String, Object>) cur.get("followUps");
        assertThat(fu.get("created")).isEqualTo(1L); assertThat(fu.get("openAtEnd")).isEqualTo(1L);
        Map<String, Object> giving = (Map<String, Object>) cur.get("giving");
        BigDecimal total = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM income WHERE app_client_id = ? AND delete_flag = false AND income_date BETWEEN ? AND ?", BigDecimal.class, A, ym.atDay(1), ym.atEndOfMonth());
        assertThat(giving.get("total")).isEqualTo(String.format(Locale.US, "%,.2f", total));
        assertThat((List<Map<String, Object>>) giving.get("funds")).allMatch(f -> !((String) f.get("fund")).contains(MARK));
        Map<String, Object> groups = (Map<String, Object>) cur.get("groups");
        assertThat(groups.get("groups")).isEqualTo(1L);
        assertThat(groups.get("people")).as("Eve by link + the unmatched e-mail row").isEqualTo(2L);
        Map<String, Object> eng = (Map<String, Object>) r.get("engagement");
        assertThat(eng.get("snapshotDate")).isEqualTo(today.toString());
        assertThat(((Map<String, Long>) eng.get("counts"))).containsEntry("AT_RISK", 2L);
        assertThat((List<String>) r.get("narrative")).anyMatch(s -> s.contains("at risk"));
        String json = r.toString();
        assertThat(json).doesNotContain("Ann").doesNotContain("Lee").doesNotContain("Vic").doesNotContain("@x.test");

        // no finance → no giving block, no giving sentence or definition
        Map<String, Object> user = reports.monthly(A, ym.getYear(), ym.getMonthValue(), new Access(true, false, false, true, true), today);
        assertThat((Map<String, Object>) user.get("current")).doesNotContainKeys("giving", "groups");
        assertThat((List<String>) user.get("narrative")).noneMatch(s -> s.startsWith("Giving"));
        assertThat((List<String>) user.get("definitions")).noneMatch(s -> s.startsWith("Giving"));
        assertThat(user.toString()).doesNotContain("$");

        // the other church: its own numbers (B holds the extra attendance rows pointing at A's Dan)
        Map<String, Object> b = reports.monthly(B, ym.getYear(), ym.getMonthValue(), Access.all(), today);
        long bCheckins = jdbc.queryForObject("SELECT COUNT(*) FROM attendance_record WHERE client_id = ? AND delete_flag = false AND (status IS NULL OR status IN ('PRESENT','LATE')) AND attendance_date BETWEEN ? AND ?",
                Long.class, B, ym.atDay(1), ym.atEndOfMonth());
        assertThat(((Map<String, Object>) ((Map<String, Object>) b.get("current")).get("attendance")).get("checkins")).isEqualTo(bCheckins);
        assertThat(bCheckins).isNotEqualTo(checkins);

        // an empty month far back: zeros, comparisons omitted
        Map<String, Object> old = reports.monthly(A, 2010, 1, Access.all(), today);
        assertThat(((Map<String, Object>) ((Map<String, Object>) old.get("current")).get("attendance")).get("checkins")).isEqualTo(0L);
        assertThat((List<String>) old.get("narrative")).anyMatch(s -> s.startsWith("No attendance was recorded"));
    }

    @Test @Order(4) @DisplayName("migration block runs twice; retention purge removes only snapshot rows older than the window")
    void migrationAndRetention() throws Exception {
        String sql = Files.readString(Path.of("scripts/sql/migrate_production.sql"));
        int start = sql.indexOf("CREATE TABLE IF NOT EXISTS member_engagement_snapshot");
        assertThat(start).isPositive();
        String block = sql.substring(start);
        jdbc.execute(block); jdbc.execute(block);

        if (!snapshots.existsByAppClientIdAndSnapshotDate(A, today)) intel.snapshot(A, today, false);
        long live = jdbc.queryForObject("SELECT COUNT(*) FROM member_engagement_snapshot WHERE app_client_id = ?", Long.class, A);
        jdbc.update("INSERT INTO member_engagement_snapshot (app_client_id, member_id, snapshot_date, signal, created_at) VALUES (?, ?, ?, 'QUIET', ?)",
                A, a("Dan"), today.minusDays(800), java.sql.Timestamp.valueOf(today.minusDays(800).atStartOfDay()));
        Map<String, Integer> purged = retention.purgeNow();
        assertThat(purged.get("member_engagement_snapshot")).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_engagement_snapshot WHERE app_client_id = ?", Long.class, A)).isEqualTo(live);
        assertThat(retention.describe()).contains("member_engagement_snapshot=730d");
    }

    @AfterAll
    void cleanup() {
        for (String cid : List.of(A, B)) {
            jdbc.update("DELETE FROM member_engagement_snapshot WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM follow_up WHERE client_id = ?", cid);
            jdbc.update("DELETE FROM income WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM sub_source WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM main_source WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM volunteer_assignment WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM group_member WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM app_group WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM attendance_record WHERE client_id = ?", cid);
            jdbc.update("DELETE FROM attendance_visitor WHERE client_id = ?", cid);
            jdbc.update("DELETE FROM family_member WHERE family_id IN (SELECT id FROM family WHERE app_client_id = ?)", cid);
            jdbc.update("DELETE FROM family WHERE app_client_id = ?", cid);
        }
    }

    /* ─── helpers ─── */

    String name(Integer memberId) { for (var e : ids.get(A).entrySet()) if (e.getValue().equals(memberId)) return e.getKey(); return "?" + memberId; }

    FamilyMember member(Family f, String first, String last, String email, String role, boolean inactive, boolean deleted) {
        FamilyMember m = new FamilyMember(); m.setFamily(f); m.setFirstName(first); m.setLastName(last); m.setEmail(email);
        m.setRole(role); m.setInactive(inactive);
        m = memberRepo.save(m);
        if (deleted) { m.setDeleteFlag(true); m = memberRepo.save(m); }
        return m;
    }
    AttendanceRecord attend(String cid, Integer memberId, LocalDate d) {
        AttendanceRecord a = new AttendanceRecord(); a.setClientId(cid); a.setFamilyMemberId(memberId); a.setPersonType("MEMBER");
        a.setAttendanceDate(d); a.setStatus("PRESENT"); a.setServiceType("Sunday");
        return attendanceRepo.save(a);
    }
    Group group(String cid, String name) { Group g = new Group(); g.setAppClientId(cid); g.setGroupName(name); return groupRepo.save(g); }
    void groupMember(Group g, String first, String last, String email, Integer memberId) {
        GroupMember gm = new GroupMember(); gm.setGroup(g); gm.setAppClientId(g.getAppClientId()); gm.setFirstName(first); gm.setLastName(last);
        gm.setEmail(email); gm.setFamilyMemberId(memberId); groupMemberRepo.save(gm);
    }
    void assign(String cid, Integer memberId, LocalDate d, String status) {
        VolunteerAssignment va = new VolunteerAssignment(); va.setAppClientId(cid); va.setFamilyMemberId(memberId); va.setEventDate(d);
        va.setEventLabel("Sunday"); va.setAssignmentStatus(status); assignmentRepo.save(va);
    }
    void gift(String cid, SubSource ss, FamilyMember m, LocalDate d, String amount, boolean deleted) {
        Income i = new Income(); i.setAppClientId(cid); i.setSubSource(ss); i.setMember(m); i.setIncomeDate(d); i.setAmount(new BigDecimal(amount));
        i = incomeRepo.save(i);
        if (deleted) { i.setDeleteFlag(true); incomeRepo.save(i); }
    }
}
