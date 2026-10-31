package com.churchgeniuspro.integration;

import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.service.AiAccessPolicy.CrossAccess;
import com.churchgeniuspro.service.AiCrossReferenceService;
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
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI Search Phase 1 — the cross-referenced queries against a real PostgreSQL with two seeded churches
 * of the same shape (every Church-B name carries {@code ZZB}). Checks each question's exact people,
 * the group-matching rules (member link / unique email confirm; a shared family email or a same name
 * does not), Follow-ups ownership, the active-member rule, and that no row, count or link of the other
 * church ever leaks in — including Church-B rows that point at Church-A member ids.
 *
 * <p>Runs on {@code verify} with Docker (Testcontainers), or against {@code CGP_IT_JDBC_URL}; skipped otherwise.
 */
@EnabledIf("com.churchgeniuspro.integration.AiCrossReferenceIT#databaseAvailable")
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = { "app.base-url=http://localhost" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("AI Search Phase 1 — cross-referenced queries on PostgreSQL (two churches)")
class AiCrossReferenceIT {

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

    static final String A = "CGP-XR-ITA", B = "CGP-XR-ITB", MARK = "ZZB";

    @Autowired AiCrossReferenceService svc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FamilyRepository familyRepo; @Autowired FamilyMemberRepository memberRepo;
    @Autowired AttendanceRecordRepository attendanceRepo; @Autowired GroupRepository groupRepo; @Autowired GroupMemberRepository groupMemberRepo;
    @Autowired VolunteerAssignmentRepository assignmentRepo; @Autowired IncomeRepository incomeRepo;
    @Autowired MainSourceRepository mainSourceRepo; @Autowired SubSourceRepository subSourceRepo;
    @Autowired ConnectSubmissionRepository connectRepo; @Autowired FollowUpRepository followUpRepo;

    LocalDate today;
    final Map<String, Map<String, Integer>> ids = new HashMap<>();

    @BeforeAll
    void seed() {
        today = AppClock.today();
        seedChurch(A, "");
        seedChurch(B, MARK);
        // Church-B rows that point at Church-A people: a group row linked to A's Eve, and attendance
        // recorded under B for A's Kim. Neither may change any Church-A answer.
        Group bg = group(B, MARK + " Choir", false);
        GroupMember leak = new GroupMember(); leak.setGroup(bg); leak.setAppClientId(B); leak.setFirstName("Eve"); leak.setLastName("Stone");
        leak.setFamilyMemberId(ids.get(A).get("Eve")); groupMemberRepo.save(leak);
        for (int d : new int[] { 2, 9, 16, 23 }) attend(B, ids.get(A).get("Kim"), today.minusDays(d), "PRESENT", "North");
    }

    void seedChurch(String cid, String tag) {
        Map<String, Integer> m = new HashMap<>(); ids.put(cid, m);
        Family f = new Family(); f.setAppClientId(cid); f = familyRepo.save(f);
        LocalDate easter = easterBefore(today);
        // name → (email)
        FamilyMember ann = member(f, tag + "Ann", "Lee", "ann" + tag + "@x.test", false, false);
        FamilyMember bob = member(f, tag + "Bob", "Ray", "bob" + tag + "@x.test", false, false);
        FamilyMember cara = member(f, tag + "Cara", "Fox", "family" + tag + "@x.test", false, false);
        FamilyMember dan = member(f, tag + "Dan", "Fox", "family" + tag + "@x.test", false, false);      // shares Cara's email
        FamilyMember eve = member(f, tag + "Eve", "Stone", null, false, false);
        FamilyMember fay = member(f, tag + "Fay", "Hill", null, false, false);
        FamilyMember gus = member(f, tag + "Gus", "Moe", null, false, false);
        FamilyMember hal = member(f, tag + "Hal", "Inact", null, true, false);                           // inactive
        FamilyMember ivy = member(f, tag + "Ivy", "Gone", null, false, true);                            // deleted
        FamilyMember jay = member(f, tag + "Jay", "Absent", null, false, false);
        FamilyMember kim = member(f, tag + "Kim", "Elsewhere", null, false, false);
        for (FamilyMember x : List.of(ann, bob, cara, dan, eve, fay, gus, hal, ivy, jay, kim)) m.put(x.getFirstName().replace(tag, ""), x.getId());

        for (int d : new int[] { 1, 8, 15, 22 }) attend(cid, ann.getId(), today.minusDays(d), "PRESENT", "North");
        attend(cid, ann.getId(), easter, "PRESENT", "North");
        for (int d : new int[] { 2, 9, 16 }) attend(cid, bob.getId(), today.minusDays(d), "LATE", "South");
        for (int d : new int[] { 3, 10, 17 }) attend(cid, cara.getId(), today.minusDays(d), "PRESENT", "South");
        for (int d : new int[] { 4, 11, 18 }) attend(cid, eve.getId(), today.minusDays(d), "PRESENT", null);
        for (int d : new int[] { 35, 42, 49 }) attend(cid, fay.getId(), today.minusDays(d), "PRESENT", "North");
        attend(cid, gus.getId(), easter, "PRESENT", "North");
        for (int d : new int[] { 1, 8, 15 }) { attend(cid, hal.getId(), today.minusDays(d), "PRESENT", "North"); attend(cid, ivy.getId(), today.minusDays(d), "PRESENT", "North"); }
        for (int d : new int[] { 1, 8, 15 }) attend(cid, jay.getId(), today.minusDays(d), "ABSENT", "North");
        AttendanceRecord gone = attend(cid, jay.getId(), today.minusDays(29), "PRESENT", "North"); gone.setDeleteFlag(true); attendanceRepo.save(gone);

        Group youth = group(cid, tag + " Youth", false);
        Group old = group(cid, tag + " Old Group", true);
        groupMember(youth, ann.getFirstName(), "Lee", null, ann.getId());                // linked
        groupMember(youth, "Robert", "Ray", "BOB" + tag.toLowerCase() + "@X.test ", null);  // unique email (case/space-insensitive)
        groupMember(youth, "Fam", "Fox", "family" + tag + "@x.test", null);             // shared family email → confirms nobody
        groupMember(youth, eve.getFirstName(), "Stone", null, null);                     // same name only → unverified
        groupMember(old, fay.getFirstName(), "Hill", null, fay.getId());                 // deleted group → ignored

        assign(cid, ann.getId(), today.minusDays(30), "confirmed");
        assign(cid, bob.getId(), today.minusDays(10), "declined");
        assign(cid, eve.getId(), today.plusDays(5), "pending");

        MainSource ms = new MainSource(); ms.setSourceName(tag + " Offerings"); ms.setAppClientId(cid); ms = mainSourceRepo.save(ms);
        SubSource ss = new SubSource(); ss.setSourceName(tag + " General"); ss.setMainSource(ms); ss.setAppClientId(cid); ss = subSourceRepo.save(ss);
        LocalDate lastYear = today.withDayOfYear(1).minusMonths(6);
        gift(cid, ss, ann, lastYear, "100.00", false); gift(cid, ss, ann, today.withDayOfYear(1), "50.00", false);
        gift(cid, ss, bob, lastYear, "75.00", false); gift(cid, ss, bob, lastYear.plusDays(7), "25.00", false);
        gift(cid, ss, cara, lastYear, "40.00", true);                                    // deleted gift
        Income guest = new Income(); guest.setAppClientId(cid); guest.setSubSource(ss); guest.setGuestName(tag + " Guest"); guest.setIncomeDate(lastYear); guest.setAmount(new BigDecimal("10.00")); incomeRepo.save(guest);

        Long fu1 = followUp(cid, "PENDING", "pat", 20);
        Long fu2 = followUp(cid, "COMPLETED", "pat", 5);
        Long fu5 = followUp(cid, "PENDING", "sam", 2);
        connect(cid, tag + "Vic", "One", "New", fu1);
        connect(cid, tag + "Val", "Two", "Assigned", fu2);
        connect(cid, tag + "Ved", "Three", "Contacted", null);
        connect(cid, tag + "Vin", "Four", "New", null);
        connect(cid, tag + "Vas", "Five", "InProgress", fu5);
    }

    /* ───────────── questions ───────────── */

    static AiAccess admin() { return new AiAccess("Admin", "pat", false, false, false, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, Set.of()); }
    static AiAccess user()  { return new AiAccess("User", "pat", false, false, false, false, false, false, false, true, true, true, true, true, false, true, true, false, true, true, Set.of()); }
    static final CrossAccess BOTH = new CrossAccess(true, true);

    Map<String, Object> ask(String q, AiAccess a) { return svc.route(q, a, BOTH, A, today); }

    @SuppressWarnings("unchecked")
    static List<String> firstCol(Map<String, Object> m) {
        List<String> out = new ArrayList<>();
        for (List<String> r : (List<List<String>>) m.get("rows")) out.add(r.get(0));
        return out;
    }

    void noLeak(Map<String, Object> m) { assertThat(m.toString()).doesNotContain(MARK); }

    @Test @DisplayName("not in any group: shared family email and same-name rows don't confirm; deleted groups and the other church's rows are ignored")
    void notInGroup() {
        Map<String, Object> m = ask("regular attenders not in any group", admin());
        assertThat(firstCol(m)).containsExactly("Cara Fox", "Fay Hill", "Eve Stone");   // ordered by last name
        @SuppressWarnings("unchecked") List<List<String>> rows = (List<List<String>>) m.get("rows");
        assertThat(rows.get(2).get(3)).isEqualTo("Youth");                              // Eve's unverified match (trimmed)
        assertThat(rows.get(0).get(3)).isEmpty();                                        // Cara: no same-name row
        assertThat(m.get("total")).isEqualTo(3L);
        assertThat(m.get("summary")).isEqualTo(Map.of("Not in a group", "3", "Unverified name matches", "1"));
        noLeak(m);
    }

    @Test @DisplayName("lapsed regulars: attended 3+ days in the 12 weeks before the last 4, none since")
    void lapsed() {
        Map<String, Object> m = ask("regulars we havent seen", admin());
        assertThat(firstCol(m)).containsExactly("Fay Hill");
        assertThat(m.get("total")).isEqualTo(1L);
        noLeak(m);
    }

    @Test @DisplayName("Easter: came on Easter Sunday and not since")
    void easter() {
        Map<String, Object> m = ask("who came on easter but hasnt attended since", admin());
        assertThat(firstCol(m)).containsExactly("Gus Moe");
        noLeak(m);
    }

    @Test @DisplayName("not serving: declined assignments don't count; upcoming and recent ones do")
    void notServing() {
        Map<String, Object> m = ask("regular attenders not serving by campus", admin());
        assertThat(firstCol(m)).containsExactlyInAnyOrder("Bob Ray", "Cara Fox", "Fay Hill");
        assertThat(m.get("columns")).asList().contains("Campus");
        noLeak(m);
    }

    @Test @DisplayName("lapsed givers: linked, live gifts only; amount is the earlier period's gross")
    void lapsedGivers() {
        Map<String, Object> m = ask("members who contribution last year but not this year", admin());
        @SuppressWarnings("unchecked") List<List<String>> rows = (List<List<String>>) m.get("rows");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get(0)).isEqualTo("Bob Ray");
        assertThat(rows.get(0).get(2)).isEqualTo("$100.00");
        assertThat(rows.get(0).get(3)).isEqualTo("2");
        noLeak(m);
    }

    @Test @DisplayName("visitors: Follow-ups ownership — admins see all open, a User only tasks assigned to them")
    void visitors() {
        Map<String, Object> m = ask("visitors with no follow up", admin());
        assertThat(firstCol(m)).containsExactlyInAnyOrder("Vic One", "Vin Four", "Vas Five");
        assertThat(m.toString()).contains("Open 20 days ⚠").contains("No follow-up task");
        noLeak(m);
        Map<String, Object> u = ask("visitors with no follow up", user());
        assertThat(firstCol(u)).containsExactly("Vic One");
        noLeak(u);
    }

    @Test @DisplayName("Church B sees only its own people, with the same counts")
    void otherChurch() {
        Map<String, Object> m = svc.route("regular attenders not in any group", admin(), BOTH, B, today);
        assertThat(firstCol(m)).allMatch(n -> n.startsWith(MARK));
        assertThat(m.get("total")).isEqualTo(3L);
    }

    /* ───────────── seed helpers ───────────── */

    static LocalDate easterBefore(LocalDate d) {
        for (int y = d.getYear(); ; y--) { LocalDate e = easter(y); if (!e.isAfter(d)) return e; }
    }
    static LocalDate easter(int y) {
        int a = y % 19, b = y / 100, c = y % 100, d = b / 4, e = b % 4, f = (b + 8) / 25, g = (b - f + 1) / 3,
            h = (19 * a + b - d - g + 15) % 30, i = c / 4, k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7, m = (a + 11 * h + 22 * l) / 451;
        return LocalDate.of(y, (h + l - 7 * m + 114) / 31, ((h + l - 7 * m + 114) % 31) + 1);
    }

    FamilyMember member(Family f, String first, String last, String email, boolean inactive, boolean deleted) {
        FamilyMember m = new FamilyMember(); m.setFamily(f); m.setFirstName(first); m.setLastName(last); m.setEmail(email);
        m.setRole("Member"); m.setGender("Female"); m.setInactive(inactive);
        m = memberRepo.save(m);
        if (deleted) { m.setDeleteFlag(true); m = memberRepo.save(m); }     // @PrePersist resets the flag on insert
        return m;
    }
    AttendanceRecord attend(String cid, Integer memberId, LocalDate d, String status, String campus) {
        AttendanceRecord a = new AttendanceRecord(); a.setClientId(cid); a.setFamilyMemberId(memberId); a.setPersonType("MEMBER");
        a.setAttendanceDate(d); a.setStatus(status); a.setCampus(campus); a.setServiceType("Sunday");
        return attendanceRepo.save(a);
    }
    Group group(String cid, String name, boolean deleted) {
        Group g = new Group(); g.setAppClientId(cid); g.setGroupName(name); g = groupRepo.save(g);
        if (deleted) { g.setDeleteFlag(true); g = groupRepo.save(g); }
        return g;
    }
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
    Long followUp(String cid, String status, String assignee, int daysAgo) {
        FollowUp fu = new FollowUp(); fu.setClientId(cid); fu.setTitle("Welcome"); fu.setStatus(status); fu.setAssignedTo(assignee);
        fu.setLinkedType("CONNECT"); fu.setDeleteFlag(false);
        fu = followUpRepo.save(fu);
        jdbc.update("UPDATE follow_up SET created_at = ? WHERE id = ?", java.sql.Timestamp.valueOf(today.minusDays(daysAgo).atTime(10, 0)), fu.getId());
        return fu.getId();
    }
    void connect(String cid, String first, String last, String status, Long fuId) {
        ConnectSubmission s = new ConnectSubmission(); s.setClientId(cid); s.setFirstName(first); s.setLastName(last); s.setFollowUpId(fuId);
        s = connectRepo.save(s);
        jdbc.update("UPDATE connect_submission SET status = ?, created_at = ? WHERE id = ?", status, java.sql.Timestamp.valueOf(today.minusDays(3).atTime(9, 0)), s.getId());
    }
}
