package com.churchgeniuspro.integration;

import com.churchgeniuspro.ai.filter.PeopleFilter;
import com.churchgeniuspro.ai.filter.PeopleFilter.*;
import com.churchgeniuspro.ai.filter.PeopleFilterService;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.service.AiAccessPolicy.CrossAccess;
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
 * AI Phase 1 — composable people filters on a real PostgreSQL with two seeded churches of the same
 * shape (Church-B names carry {@code ZZB}). Every filter combination's people are compared with
 * hand-written SQL; no Church-B row ever appears for Church A, including B rows that point at A ids.
 */
@EnabledIf("com.churchgeniuspro.integration.PeopleFilterIT#databaseAvailable")
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = { "app.base-url=http://localhost" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("AI Phase 1 — people filters on PostgreSQL (two churches)")
class PeopleFilterIT {

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

    static final String A = "CGP-PF-ITA-" + System.currentTimeMillis(), B = A.replace("ITA", "ITB"), MARK = "ZZB";

    @Autowired PeopleFilterService svc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FamilyRepository familyRepo; @Autowired FamilyMemberRepository memberRepo;
    @Autowired AttendanceRecordRepository attendanceRepo; @Autowired GroupRepository groupRepo; @Autowired GroupMemberRepository groupMemberRepo;
    @Autowired VolunteerAssignmentRepository assignmentRepo; @Autowired IncomeRepository incomeRepo;
    @Autowired MainSourceRepository mainSourceRepo; @Autowired SubSourceRepository subSourceRepo;
    @Autowired PledgeCampaignRepository campaignRepo; @Autowired PledgeMemberRepository pledgeRepo;
    @Autowired ChurchEventRepository eventRepo; @Autowired EventRegistrationRepository registrationRepo;

    LocalDate today;
    final Map<String, Map<String, Integer>> ids = new HashMap<>();
    static final AiAccess ALL = new AiAccess("Admin", "pat", false, false, false, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, Set.of());
    static final CrossAccess XALL = new CrossAccess(true, true);

    @BeforeAll
    void seed() {
        today = AppClock.today();
        seedChurch(A, "");
        seedChurch(B, MARK);
        // Church-B rows pointing at Church-A people: a group link, attendance, a gift and an assignment.
        Group bg = group(B, MARK + " Choir");
        GroupMember leak = new GroupMember(); leak.setGroup(bg); leak.setAppClientId(B); leak.setFirstName("Eve"); leak.setLastName("Stone");
        leak.setFamilyMemberId(ids.get(A).get("Eve")); groupMemberRepo.save(leak);
        for (int d : new int[] { 2, 9, 16, 23 }) attend(B, ids.get(A).get("Gus"), today.minusDays(d));
        assign(B, ids.get(A).get("Gus"), today.minusDays(3), "confirmed");
    }

    void seedChurch(String cid, String tag) {
        Map<String, Integer> m = new HashMap<>(); ids.put(cid, m);
        Family f = new Family(); f.setAppClientId(cid); f = familyRepo.save(f);
        FamilyMember ann  = member(f, tag + "Ann",  "Lee",   "ann" + tag + "@x.test",    "Head of Household", "Female", 1990, 3, 15, null, false, false);
        FamilyMember bob  = member(f, tag + "Bob",  "Ray",   "bob" + tag + "@x.test",    "Spouse",            "Male",   1988, 7, 2,  null, false, false);
        FamilyMember cara = member(f, tag + "Cara", "Fox",   "family" + tag + "@x.test", "Daughter",          "Female", 2011, 3, 9,  null, false, false);   // 15
        FamilyMember dan  = member(f, tag + "Dan",  "Fox",   "family" + tag + "@x.test", "Son",               "Male",   2014, 1, 1,  null, false, false);   // 12, shares email
        FamilyMember eve  = member(f, tag + "Eve",  "Stone", null,                       "Adult",             "Female", 1958, 3, 1,  null, false, false);   // 68
        FamilyMember fay  = member(f, tag + "Fay",  "Hill",  null,                       "Adult",             "Female", null, null, null, "Visitor", false, false);
        FamilyMember gus  = member(f, tag + "Gus",  "Moe",   "gus" + tag + "@x.test",    "Adult",             "Male",   1975, 11, 4, "Guest", false, false);
        FamilyMember hal  = member(f, tag + "Hal",  "Inact", null,                       "Adult",             "Male",   1970, 5, 5,  null, true, false);
        FamilyMember ivy  = member(f, tag + "Ivy",  "Gone",  null,                       "Adult",             "Female", 1980, 6, 6,  null, false, true);
        for (FamilyMember x : List.of(ann, bob, cara, dan, eve, fay, gus, hal, ivy)) m.put(x.getFirstName().replace(tag, ""), x.getId());

        for (int d : new int[] { 1, 8, 15, 22 }) attend(cid, ann.getId(), today.minusDays(d));
        for (int d : new int[] { 2, 9 }) attend(cid, bob.getId(), today.minusDays(d));
        for (int d : new int[] { 3, 10, 17 }) attend(cid, eve.getId(), today.minusDays(d));
        for (int d : new int[] { 70, 77 }) attend(cid, fay.getId(), today.minusDays(d));
        attend(cid, hal.getId(), today.minusDays(1)); attend(cid, ivy.getId(), today.minusDays(1));
        AttendanceRecord gone = attend(cid, gus.getId(), today.minusDays(1)); gone.setDeleteFlag(true); attendanceRepo.save(gone);

        Group youth = group(cid, tag + " Youth Group");
        groupMember(youth, ann.getFirstName(), "Lee", null, ann.getId());                  // linked
        groupMember(youth, "Robert", "Ray", "BOB" + tag.toLowerCase() + "@X.test ", null);    // unique email
        groupMember(youth, "Fam", "Fox", "family" + tag + "@x.test", null);               // shared family email → nobody

        assign(cid, ann.getId(), today.minusDays(30), "confirmed");
        assign(cid, bob.getId(), today.minusDays(10), "declined");
        assign(cid, eve.getId(), today.plusDays(5), "pending");

        MainSource ms = new MainSource(); ms.setSourceName(tag + " Offerings"); ms.setAppClientId(cid); ms = mainSourceRepo.save(ms);
        SubSource general = new SubSource(); general.setSourceName(tag + " General"); general.setMainSource(ms); general.setAppClientId(cid); general = subSourceRepo.save(general);
        SubSource building = new SubSource(); building.setSourceName(tag + " Building Fund"); building.setMainSource(ms); building.setAppClientId(cid); building = subSourceRepo.save(building);
        LocalDate lastYear = LocalDate.of(today.getYear() - 1, 6, 1);
        gift(cid, general, ann, lastYear, "100.00", false); gift(cid, building, ann, today.withDayOfYear(1).plusDays(10), "50.00", false);
        gift(cid, general, bob, lastYear, "75.00", false);
        gift(cid, general, eve, today.withDayOfYear(1).plusDays(3), "40.00", true);          // deleted gift

        PledgeCampaign pc = new PledgeCampaign(); pc.setClientId(cid); pc.setName(tag + " Roof"); pc.setSubSourceId(building.getId()); pc.setStatus("ACTIVE"); pc = campaignRepo.save(pc);
        PledgeMember pm = new PledgeMember(); pm.setClientId(cid); pm.setCampaignId(pc.getId()); pm.setFamilyMemberId(eve.getId()); pm.setFamilyId(f.getId()); pm.setPledgeAmount(new BigDecimal("500")); pledgeRepo.save(pm);

        ChurchEvent ev = new ChurchEvent(); ev.setAppClientId(cid); ev.setEventName(tag + " Spring Picnic"); ev.setEventType("One Day"); ev.setEventDate(today.plusDays(10)); ev = eventRepo.save(ev);
        EventRegistration er = new EventRegistration(); er.setEventId(ev.getId()); er.setClientId(cid); er.setFirstName("G"); er.setLastName("M"); er.setEmail("GUS" + tag + "@x.test"); registrationRepo.save(er);
        EventRegistration er2 = new EventRegistration(); er2.setEventId(ev.getId()); er2.setClientId(cid); er2.setFirstName("F"); er2.setLastName("F"); er2.setEmail("family" + tag + "@x.test"); registrationRepo.save(er2);
    }

    @SuppressWarnings("unchecked")
    List<Integer> run(PeopleFilter f) {
        Map<String, Object> m = svc.run(f, ALL, XALL, A, today);
        assertThat(m.get("denied")).as(String.valueOf(m.get("answer"))).isEqualTo(false);
        List<Integer> out = (List<Integer>) m.get("memberIds");
        assertThat(out).noneMatch(id -> ids.get(B).containsValue(id));
        return out;
    }
    Set<Integer> who(String... names) { Set<Integer> s = new HashSet<>(); for (String n : names) s.add(ids.get(A).get(n)); return s; }
    Set<Integer> sql(String where, Object... args) {
        List<Object> a = new ArrayList<>(); a.add(A); a.addAll(List.of(args));
        return new HashSet<>(jdbc.queryForList("SELECT fm.id FROM family_member fm JOIN family f ON f.id = fm.family_id WHERE f.app_client_id = ? " + where, Integer.class, a.toArray()));
    }

    @Test @DisplayName("age, gender, role, type, status, birth month, contact — each equals hand SQL, never the other church")
    void simpleFacts() {
        assertThat(run(PeopleFilter.of(new AgeBand(18, 40)))).containsExactlyInAnyOrderElementsOf(who("Ann", "Bob"));
        assertThat(run(PeopleFilter.of(new AgeBand(null, 17)))).containsExactlyInAnyOrderElementsOf(who("Cara", "Dan"));
        assertThat(run(PeopleFilter.of(new AgeBand(65, null)))).containsExactlyInAnyOrderElementsOf(who("Eve"));      // Hal inactive, Ivy deleted
        assertThat(run(PeopleFilter.of(new Gender("Male")))).containsExactlyInAnyOrderElementsOf(who("Bob", "Dan", "Gus"));
        assertThat(run(PeopleFilter.of(new Role("Child")))).containsExactlyInAnyOrderElementsOf(who("Cara", "Dan"));
        assertThat(run(PeopleFilter.of(new Role("Head")))).containsExactlyInAnyOrderElementsOf(who("Ann"));
        assertThat(run(PeopleFilter.of(new Role("Adult")))).containsExactlyInAnyOrderElementsOf(who("Eve", "Fay", "Gus"));
        assertThat(run(PeopleFilter.of(new PeopleFilter.MemberType("NonMember")))).containsExactlyInAnyOrderElementsOf(who("Fay", "Gus"));
        assertThat(run(PeopleFilter.of(new PeopleFilter.MemberType("Visitor")))).containsExactlyInAnyOrderElementsOf(who("Fay"));
        assertThat(run(PeopleFilter.of(new Status("inactive")))).containsExactlyInAnyOrderElementsOf(who("Hal"));
        assertThat(run(PeopleFilter.of(new Status("deleted")))).containsExactlyInAnyOrderElementsOf(who("Ivy"));
        assertThat(run(PeopleFilter.of(new BirthMonth(3)))).containsExactlyInAnyOrderElementsOf(who("Ann", "Cara", "Eve"));
        assertThat(run(PeopleFilter.of(new HasContact(true, "email")))).containsExactlyInAnyOrderElementsOf(who("Eve", "Fay"));
        assertThat(run(PeopleFilter.of(new Gender("Female"), new HasContact(false, "email")))).containsExactlyInAnyOrderElementsOf(who("Ann", "Cara"));
        assertThat(run(PeopleFilter.of(new AgeBand(18, 40)))).containsExactlyInAnyOrderElementsOf(
                sql("AND fm.delete_flag = false AND f.delete_flag = false AND fm.inactive = false AND f.inactive = false AND fm.birthday_year IS NOT NULL "
                  + "AND EXTRACT(YEAR FROM age(?::date, make_date(fm.birthday_year, fm.birthday_month, LEAST(fm.birthday_day, 28)))) BETWEEN 18 AND 40", today));
    }

    @Test @DisplayName("attendance, giving (with fund), groups (link / unique email / shared email), serving, pledges, events, combinations")
    void modules() {
        assertThat(run(PeopleFilter.of(new Attended(false, 3, today.minusWeeks(12).plusDays(1), today, "")))).containsExactlyInAnyOrderElementsOf(who("Ann", "Eve"));
        assertThat(run(PeopleFilter.of(new Attended(false, 1, today.minusWeeks(12).plusDays(1), today, "")))).containsExactlyInAnyOrderElementsOf(who("Ann", "Bob", "Eve", "Fay"));
        assertThat(run(PeopleFilter.of(new Attended(true, 0, today.minusWeeks(4).plusDays(1), today, "")))).containsExactlyInAnyOrderElementsOf(who("Cara", "Dan", "Fay", "Gus"));
        assertThat(run(PeopleFilter.of(new Gave(false, today.withDayOfYear(1), today, "", null)))).containsExactlyInAnyOrderElementsOf(who("Ann"));   // Eve's gift deleted
        assertThat(run(PeopleFilter.of(new Gave(false, LocalDate.of(today.getYear() - 1, 1, 1), LocalDate.of(today.getYear() - 1, 12, 31), "", null))))
                .containsExactlyInAnyOrderElementsOf(who("Ann", "Bob"));
        assertThat(run(PeopleFilter.of(new Gave(false, today.withDayOfYear(1), today, "", " Building Fund")))).containsExactlyInAnyOrderElementsOf(who("Ann"));
        assertThat(run(PeopleFilter.of(new Gave(false, today.withDayOfYear(1), today, "", " General")))).isEmpty();
        assertThat(run(PeopleFilter.of(new Gave(true, today.withDayOfYear(1), today, "", null)))).containsExactlyInAnyOrderElementsOf(who("Bob", "Cara", "Dan", "Eve", "Fay", "Gus"));
        assertThat(run(PeopleFilter.of(new InGroup(false, null)))).as("Ann by link, Bob by unique email; Cara/Dan share an email").containsExactlyInAnyOrderElementsOf(who("Ann", "Bob"));
        assertThat(run(PeopleFilter.of(new InGroup(false, " Youth Group")))).containsExactlyInAnyOrderElementsOf(who("Ann", "Bob"));
        assertThat(run(PeopleFilter.of(new InGroup(true, null)))).containsExactlyInAnyOrderElementsOf(who("Cara", "Dan", "Eve", "Fay", "Gus"));
        assertThat(run(PeopleFilter.of(new Serving(false, today.minusMonths(6))))).as("Bob declined, Eve upcoming counts").containsExactlyInAnyOrderElementsOf(who("Ann", "Eve"));
        assertThat(run(PeopleFilter.of(new InCampaign(false, null)))).containsExactlyInAnyOrderElementsOf(who("Eve"));
        assertThat(run(PeopleFilter.of(new InCampaign(false, " Roof")))).containsExactlyInAnyOrderElementsOf(who("Eve"));
        assertThat(run(PeopleFilter.of(new RegisteredForEvent(false, " Spring Picnic")))).as("Gus by unique email; the shared family email confirms nobody")
                .containsExactlyInAnyOrderElementsOf(who("Gus"));
        // combinations
        assertThat(run(PeopleFilter.of(new Gender("Female"), new Attended(false, 2, today.minusDays(59), today, ""), new Gave(true, today.withDayOfYear(1), today, "", null))))
                .containsExactlyInAnyOrderElementsOf(who("Eve"));
        assertThat(run(PeopleFilter.of(new AgeBand(18, null), new Serving(false, today.minusMonths(6)), new InGroup(true, null)))).containsExactlyInAnyOrderElementsOf(who("Eve"));
        assertThat(run(PeopleFilter.of(new Attended(false, 3, today.minusWeeks(12).plusDays(1), today, ""), new InGroup(true, null)))).containsExactlyInAnyOrderElementsOf(who("Eve"));
        // the other church gets its own people for the same filter
        @SuppressWarnings("unchecked")
        List<Integer> b = (List<Integer>) svc.run(PeopleFilter.of(new Gender("Male")), ALL, XALL, B, today).get("memberIds");
        assertThat(b).containsExactlyInAnyOrder(ids.get(B).get("Bob"), ids.get(B).get("Dan"), ids.get(B).get("Gus"));
    }

    @SuppressWarnings("unchecked")
    @Test @DisplayName("answer shape: columns follow the predicates, chips carry the filter, totals and cap, definitions, link")
    void shape() {
        Map<String, Object> m = svc.run(PeopleFilter.of(new Gender("Female"), new Attended(false, 1, today.minusWeeks(12).plusDays(1), today, "in the last 12 weeks"),
                new Gave(false, today.withDayOfYear(1), today, "this year", null)), ALL, XALL, A, today);
        assertThat(m.get("intent")).isEqualTo(PeopleFilterService.INTENT);
        assertThat((List<String>) m.get("columns")).containsExactly("Name", "Role", "Last attended", "Gave");
        List<List<String>> rows = (List<List<String>>) m.get("rows");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get(0)).isEqualTo("Ann Lee");
        assertThat(rows.get(0).get(1)).isEqualTo("Head of Household");
        assertThat(rows.get(0).get(3)).isEqualTo("$50.00");
        assertThat(m.get("total")).isEqualTo(1L);
        Map<String, Object> filter = (Map<String, Object>) m.get("filter");
        assertThat((List<String>) filter.get("labels")).containsExactly("Women", "Attended in the last 12 weeks", "Gave this year");
        assertThat(PeopleFilter.fromJson((List<?>) filter.get("predicates")).predicates()).hasSize(3);
        assertThat((List<String>) m.get("sources")).containsExactly("Members", "Attendance", "Income");
        assertThat(String.valueOf(m.get("answer"))).startsWith("1 person matches: Women · Attended in the last 12 weeks · Gave this year.");
        assertThat((List<String>) m.get("definitions")).anyMatch(d -> d.contains("combined with AND"));

        // cap: 2 rows max
        PeopleFilterService target = org.springframework.test.util.AopTestUtils.getTargetObject(svc);   // @Transactional proxy
        int keep = target.maxRows; target.maxRows = 2;
        try {
            Map<String, Object> capped = svc.run(PeopleFilter.of(new Gender("Female")), ALL, XALL, A, today);
            assertThat(capped.get("truncated")).isEqualTo(true);
            assertThat(capped.get("total")).isEqualTo(4L);
            assertThat((List<?>) capped.get("rows")).hasSize(2);
        } finally { target.maxRows = keep; }
    }

    @Test @DisplayName("permission gating refuses the whole question and names every missing module; too-wide windows ask to narrow")
    void gating() {
        AiAccess noFinance = new AiAccess("User", "u", false, false, false, true, false, false, false, true, true, false, true, true, false, true, true, false, true, true, Set.of());
        Map<String, Object> m = svc.run(PeopleFilter.of(new Gender("Female"), new Gave(false, today.withDayOfYear(1), today, "", null), new InGroup(true, null)), noFinance, new CrossAccess(true, false), A, today);
        assertThat(m.get("denied")).isEqualTo(true);
        assertThat(m.get("intent")).isEqualTo(PeopleFilterService.INTENT_DENIED);
        assertThat((List<String>) m.get("missing")).containsExactly("Income", "Groups");
        assertThat(m).doesNotContainKey("rows");
        Map<String, Object> wide = svc.run(PeopleFilter.of(new Attended(false, 1, today.minusYears(3), today, "3 years")), ALL, XALL, A, today);
        assertThat(wide.get("intent")).isEqualTo("narrow");
        Map<String, Object> kids = svc.run(PeopleFilter.of(new Gender("Female")), AiAccess.none("Member"), XALL, A, today);
        assertThat(kids.get("denied")).isEqualTo(true);
    }

    @AfterAll
    void cleanup() {
        for (String cid : List.of(A, B)) {
            jdbc.update("DELETE FROM event_registration WHERE client_id = ?", cid);
            jdbc.update("DELETE FROM church_event WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM pledge_member WHERE client_id = ?", cid);
            jdbc.update("DELETE FROM pledge_campaign WHERE client_id = ?", cid);
            jdbc.update("DELETE FROM income WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM sub_source WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM main_source WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM volunteer_assignment WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM group_member WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM app_group WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM attendance_record WHERE client_id = ?", cid);
            jdbc.update("DELETE FROM family_member WHERE family_id IN (SELECT id FROM family WHERE app_client_id = ?)", cid);
            jdbc.update("DELETE FROM family WHERE app_client_id = ?", cid);
        }
    }

    /* ─── helpers ─── */

    FamilyMember member(Family f, String first, String last, String email, String role, String gender, Integer y, Integer mo, Integer d, String type, boolean inactive, boolean deleted) {
        FamilyMember m = new FamilyMember(); m.setFamily(f); m.setFirstName(first); m.setLastName(last); m.setEmail(email);
        m.setRole(role); m.setGender(gender); m.setBirthdayYear(y); m.setBirthdayMonth(mo); m.setBirthdayDay(d); m.setMemberType(type); m.setInactive(inactive);
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
