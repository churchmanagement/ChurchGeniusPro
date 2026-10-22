package com.churchgeniuspro.integration;

import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.service.AiMeetingCreateService;
import com.churchgeniuspro.service.AiStructuredAnswerService;
import com.churchgeniuspro.util.AppClock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI Search Phase G — strict clientId isolation of every AI data path, proven against a real
 * PostgreSQL with two seeded tenants. Tenant A asks every structured question with full rights;
 * nothing seeded for Tenant B (every name/title carries the marker {@code ZZB}) may appear, counts
 * equal Tenant A's rows only, and every {@code ai*} repository method called with the other
 * tenant's ids returns nothing. Also: the AI meeting creation writes into the caller's tenant only
 * and cannot see the other tenant's meeting categories.
 *
 * <p>Runs on {@code verify} with Docker (Testcontainers Postgres), or against a database named by
 * {@code CGP_IT_JDBC_URL} / {@code CGP_IT_JDBC_USER} / {@code CGP_IT_JDBC_PASSWORD}; skipped otherwise.
 */
@EnabledIf("com.churchgeniuspro.integration.AiSearchTenantIsolationIT#databaseAvailable")
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
                properties = { "app.base-url=http://localhost" })   // the "it" profile does not set it
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("AI Search — tenant isolation of every data path (two tenants, real DB)")
class AiSearchTenantIsolationIT {

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

    static final String A = "CGP-AI-ITA", B = "CGP-AI-ITB", MARK = "ZZB";

    @Autowired AiStructuredAnswerService structured;
    @Autowired AiMeetingCreateService meetingCreate;
    @Autowired IncomeRepository incomeRepo; @Autowired ExpenseRepository expenseRepo;
    @Autowired MainSourceRepository mainSourceRepo; @Autowired SubSourceRepository subSourceRepo; @Autowired PurposeRepository purposeRepo;
    @Autowired FamilyRepository familyRepo; @Autowired FamilyMemberRepository memberRepo;
    @Autowired PrayerRequestRepository prayerRepo; @Autowired FollowUpRepository followUpRepo;
    @Autowired VolunteerAssignmentRepository assignmentRepo; @Autowired VolunteerRoleRepository roleRepo;
    @Autowired ChurchEventRepository eventRepo; @Autowired KmChildRepository kmChildRepo; @Autowired KmClassroomRepository kmClassroomRepo;
    @Autowired PledgeCampaignRepository campaignRepo; @Autowired PledgeMemberRepository pledgeRepo;
    @Autowired MeetingRepository meetingRepo; @Autowired MeetingTypeRepository meetingTypeRepo;
    @Autowired PublicScreenLinkRepository publicLinkRepo;

    LocalDate today;
    Integer bMemberId, bRoleIdInt, bTypeId, bCampaignId, bClassroomInt; Long bRoleId, bClassroomId;
    Integer aTypeId, aMemberId; Integer aCampaignId;

    @BeforeAll
    void seed() {
        today = AppClock.today();
        seedTenant(A, "Alpha");
        seedTenant(B, MARK);
    }

    /** The same shape for both tenants; every Tenant-B string carries MARK. */
    void seedTenant(String cid, String tag) {
        MainSource ms = new MainSource(); ms.setSourceName(tag + " Offerings"); ms.setAppClientId(cid); ms = mainSourceRepo.save(ms);
        SubSource ss = new SubSource(); ss.setSourceName(tag + " General Fund"); ss.setMainSource(ms); ss.setAppClientId(cid); ss = subSourceRepo.save(ss);
        Purpose p = new Purpose(); p.setPurposeName(tag + " Utilities"); p.setAppClientId(cid); p = purposeRepo.save(p);

        Family f = new Family(); f.setAppClientId(cid); f = familyRepo.save(f);
        FamilyMember head = member(f, tag + "Head", tag + "Family", "Head", "Male");
        FamilyMember kid  = member(f, tag + "Kid", tag + "Family", "Son", "Male");
        FamilyMember mom  = member(f, tag + "Mom", tag + "Family", "Spouse", "Female");
        if (cid.equals(B)) bMemberId = head.getId(); else aMemberId = head.getId();

        Income i1 = new Income(); i1.setAppClientId(cid); i1.setSubSource(ss); i1.setMember(head); i1.setIncomeDate(today); i1.setAmount(new BigDecimal("100.00")); i1.setImportRef("pi_" + tag); incomeRepo.save(i1);
        Income i2 = new Income(); i2.setAppClientId(cid); i2.setSubSource(ss); i2.setGuestName(tag + " Guest"); i2.setIncomeDate(today.minusDays(1)); i2.setAmount(new BigDecimal("50.00")); incomeRepo.save(i2);
        Expense e1 = new Expense(); e1.setAppClientId(cid); e1.setPurpose(p); e1.setMainSource(ms); e1.setExpenseDate(today); e1.setAmount(new BigDecimal("30.00")); e1.setNote(tag + " power bill"); expenseRepo.save(e1);
        Expense e2 = new Expense(); e2.setAppClientId(cid); e2.setPurpose(p); e2.setMainSource(ms); e2.setExpenseDate(today); e2.setAmount(new BigDecimal("3.00")); e2.setImportRef("pi_" + tag + ":stripe-fee"); expenseRepo.save(e2);

        PrayerRequest pr = new PrayerRequest(); pr.setClientId(cid); pr.setSectionId(1L); pr.setTitle(tag + " healing"); pr.setStatus("Pending"); prayerRepo.save(pr);
        FollowUp fu = new FollowUp(); fu.setClientId(cid); fu.setTitle(tag + " visit"); fu.setStatus("PENDING"); fu.setAssignedTo("pat"); fu.setDeleteFlag(false); followUpRepo.save(fu);

        VolunteerRole role = new VolunteerRole(); role.setAppClientId(cid); role.setRoleName(tag + " Usher"); role = roleRepo.save(role);
        VolunteerAssignment va = new VolunteerAssignment(); va.setAppClientId(cid); va.setRoleId(role.getId()); va.setFamilyMemberId(head.getId());
        va.setEventLabel(tag + " Sunday"); va.setEventDate(today.plusDays(3)); va.setAssignmentStatus("accepted"); assignmentRepo.save(va);
        if (cid.equals(B)) bRoleId = role.getId();

        ChurchEvent ev = new ChurchEvent(); ev.setAppClientId(cid); ev.setEventName(tag + " Picnic"); ev.setEventDate(today.plusDays(5)); eventRepo.save(ev);

        KmClassroom room = new KmClassroom(); room.setClientId(cid); room.setClassName(tag + " Lambs"); room = kmClassroomRepo.save(room);
        KmChild ch = new KmChild(); ch.setClientId(cid); ch.setFirstName(tag + "Child"); ch.setLastName(tag + "Km"); ch.setClassroomId(room.getId()); kmChildRepo.save(ch);
        if (cid.equals(B)) bClassroomId = room.getId();

        PledgeCampaign c = new PledgeCampaign(); c.setClientId(cid); c.setName(tag + " Roof"); c.setSubSourceId(ss.getId()); c.setStatus("Active"); c = campaignRepo.save(c);
        PledgeMember pm = new PledgeMember(); pm.setClientId(cid); pm.setCampaignId(c.getId()); pm.setFamilyMemberId(head.getId()); pm.setPledgeAmount(new BigDecimal("500.00")); pledgeRepo.save(pm);
        PledgeMember pg = new PledgeMember(); pg.setClientId(cid); pg.setCampaignId(c.getId()); pg.setGuestName(tag + " Guest"); pg.setPledgeAmount(new BigDecimal("200.00")); pg.setAmountCollected(new BigDecimal("20.00")); pledgeRepo.save(pg);
        if (cid.equals(B)) bCampaignId = c.getId(); else aCampaignId = c.getId();

        MeetingType mt = new MeetingType(); mt.setTypeName(tag + " Study"); mt.setAppClientId(cid); mt = meetingTypeRepo.save(mt);
        Meeting m = new Meeting(); m.setAppClientId(cid); m.setMeetingType(mt); m.setMeetingDate(today.plusDays(2)); m.setStartTime("19:00"); m.setOccurrence("One-time"); meetingRepo.save(m);
        if (cid.equals(B)) bTypeId = mt.getId(); else aTypeId = mt.getId();
        PublicScreenLink pl = new PublicScreenLink(); pl.setAppClientId(cid); pl.setPageLabel(tag + " Connect"); pl.setPageUrl("/connect");
        pl.setToken(tag + "TOKEN" + System.nanoTime()); pl.setRevoked(false); publicLinkRepo.save(pl);
    }

    FamilyMember member(Family f, String first, String last, String role, String gender) {
        FamilyMember m = new FamilyMember(); m.setFamily(f); m.setFirstName(first); m.setLastName(last); m.setRole(role); m.setGender(gender);
        return memberRepo.save(m);
    }

    static AiAccess full(String username) {
        return new AiAccess("Admin", username, false, false, false, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, Set.of("/meetings"));
    }

    String answerA(String q) {
        Map<String, Object> m = structured.route(q, full("pat"), A, today);
        assertThat(m).as(q).isNotNull();
        assertThat(m.get("denied")).as(q).isNotEqualTo(true);
        return String.valueOf(m);
    }

    @Test @DisplayName("every structured intent answered for Tenant A contains nothing of Tenant B")
    void structuredAnswersAreTenantScoped() {
        for (String q : List.of("what was the income this year", "who are the contributors this year", "list the expenses this year",
                                "total expenses this year", "transactions this year", "income report for this year", "expense report for this year",
                                "financial report for this year", "how many members do we have", "children in family records",
                                "kids ministry children", "pending prayer requests", "open follow-ups", "upcoming volunteer assignments",
                                "upcoming events", "campaign totals", "what meetings are coming up")) {
            String s = answerA(q);
            assertThat(s).as(q).doesNotContain(MARK);
        }
        // Counts are Tenant A's rows only (each tenant seeded identically).
        assertThat(answerA("what was the income this year")).contains("$150.00").contains("2 transactions").contains("$100.00");
        assertThat(answerA("total expenses this year")).contains("$33.00").contains("2 entries").contains("$3.00");
        assertThat(answerA("how many members do we have")).contains("3 active members").contains("2 male").contains("1 female");
        assertThat(answerA("pending prayer requests")).contains("Alpha healing");
        assertThat(answerA("campaign totals")).contains("Alpha Roof").contains("$700.00");
        assertThat(answerA("upcoming volunteer assignments")).contains("Alpha Usher").contains("AlphaHead");
        assertThat(answerA("kids ministry children")).contains("AlphaChild").contains("Alpha Lambs");
        assertThat(answerA("children in family records")).contains("AlphaKid");
    }

    @Test @DisplayName("public links: only the caller's church's links, as named links to the real public address")
    @SuppressWarnings("unchecked")
    void publicLinksAreTenantScoped() {
        Map<String, Object> r = structured.route("show the public links", full("pat"), A, today);
        List<Map<String, String>> links = (List<Map<String, String>>) r.get("publicLinks");
        assertThat(links).extracting(l -> l.get("label")).containsExactly("Alpha Connect");
        assertThat(links.get(0).get("url")).startsWith("/connect?c=AlphaTOKEN");
        assertThat(String.valueOf(r)).doesNotContain(MARK);
    }

    @Test @DisplayName("a non-admin sees only follow-ups assigned to them — within their own tenant")
    void followUpsAssigneeScoped() {
        AiAccess mine = new AiAccess("User", "pat", false, false, false, true, true, true, true, true, true, true, true, true, false, true, true, true, true, true, Set.of());
        String s = String.valueOf(structured.route("open follow-ups", mine, A, today));
        assertThat(s).contains("Alpha visit").doesNotContain(MARK);
        AiAccess other = new AiAccess("User", "someone-else", false, false, false, true, true, true, true, true, true, true, true, true, false, true, true, true, true, true, Set.of());
        assertThat(String.valueOf(structured.route("open follow-ups", other, A, today))).doesNotContain("Alpha visit").doesNotContain(MARK);
    }

    @Test @DisplayName("every ai* repository method is tenant-scoped: Tenant B's ids queried as Tenant A return nothing")
    void repositoriesRefuseForeignIds() {
        Date from = Date.valueOf(today.minusDays(30)), to = Date.valueOf(today.plusDays(30));
        assertThat(((Number) incomeRepo.aiIncomeTotals(A, from, to).get(0)[1]).longValue()).isEqualTo(2);
        assertThat(incomeRepo.aiIncomeRows(A, from, to, 50)).allSatisfy(r -> assertThat(String.valueOf(r[1]) + r[3]).doesNotContain(MARK));
        assertThat(expenseRepo.aiExpenseRows(A, from, to, 50)).hasSize(2);
        assertThat(((Number) expenseRepo.aiExpenseTotals(A, from, to).get(0)[1]).longValue()).isEqualTo(2);
        assertThat(memberRepo.aiMemberNames(A, List.of(bMemberId))).as("B's member id asked as A").isEmpty();
        assertThat(memberRepo.aiChildRoleMemberNames(A, 50)).hasSize(1);
        assertThat(memberRepo.aiCountActiveMembersByGender(A).stream().mapToLong(r -> ((Number) r[1]).longValue()).sum()).isEqualTo(3);
        assertThat(prayerRepo.aiCountOpen(A)).isEqualTo(1);
        assertThat(prayerRepo.aiFindOpen(A, PageRequest.of(0, 20))).allSatisfy(p -> assertThat(p.getTitle()).doesNotContain(MARK));
        assertThat(followUpRepo.aiCountByStatuses(A, List.of("PENDING", "MISSED"))).isEqualTo(1);
        assertThat(followUpRepo.aiCountByAssigneeAndStatuses(A, "pat", List.of("PENDING", "MISSED"))).isEqualTo(1);
        assertThat(followUpRepo.aiFindByAssigneeAndStatuses(A, "pat", List.of("PENDING", "MISSED"), PageRequest.of(0, 20))).allSatisfy(f -> assertThat(f.getTitle()).doesNotContain(MARK));
        assertThat(assignmentRepo.aiCountUpcomingActive(A, today)).isEqualTo(1);
        assertThat(assignmentRepo.aiFindUpcomingActive(A, today, PageRequest.of(0, 20))).allSatisfy(v -> assertThat(v.getEventLabel()).doesNotContain(MARK));
        assertThat(roleRepo.findByAppClientIdAndIdIn(A, List.of(bRoleId))).as("B's role id asked as A").isEmpty();
        assertThat(eventRepo.aiUpcomingEvents(A, Date.valueOf(today), Date.valueOf(today.plusDays(90)), 50)).hasSize(1);
        assertThat(kmChildRepo.countByClientIdAndDeleteFlagFalseAndInactiveFalse(A)).isEqualTo(1);
        assertThat(kmClassroomRepo.findByClientIdAndIdIn(A, List.of(bClassroomId))).as("B's classroom id asked as A").isEmpty();
        assertThat(pledgeRepo.aiCampaignPledgeTotals(A, bCampaignId).get(0)[1]).as("B's campaign asked as A").satisfies(n -> assertThat(((Number) n).longValue()).isZero());
        assertThat(((Number) pledgeRepo.aiCampaignPledgeTotals(A, aCampaignId).get(0)[1]).longValue()).isEqualTo(2);
        assertThat(incomeRepo.aiCampaignMemberCollectedOpenEnded(A, bCampaignId, subSourceRepo.findAll().stream().filter(s -> B.equals(s.getAppClientId())).findFirst().orElseThrow().getId(), today.minusYears(1))).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(meetingRepo.aiCountSameSlot(A, bTypeId, today.plusDays(2), "19:00")).as("B's meeting type/slot asked as A").isZero();
        assertThat(meetingRepo.aiCountSameSlot(A, aTypeId, today.plusDays(2), "19:00")).isEqualTo(1);
        assertThat(meetingTypeRepo.findByAppClientIdAndDeleteFlagFalseOrderByTypeNameAsc(A)).extracting(MeetingType::getTypeName).containsExactly("Alpha Study");
    }

    @Test @DisplayName("AI meeting creation: the other tenant's category is invisible and the row is written into the caller's tenant")
    void meetingCreationIsTenantScoped() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "pat"); s.setAttribute("role", "Admin"); s.setAttribute("appClientId", A); s.setAttribute("clientId", A);
        MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s);
        // Tenant B's category name is not a category for Tenant A → asked, never created.
        Map<String, Object> ask = meetingCreate.handle(r, A, "Create a " + MARK + " Study meeting on " + today.plusDays(10) + " at 7pm");
        assertThat(ask.get("intent")).isEqualTo("meeting.ask");
        assertThat(String.valueOf(ask.get("answer"))).contains("Alpha Study").doesNotContain(MARK);
        // Tenant B's member named as the host is not found for Tenant A (no address of theirs is used).
        Map<String, Object> loc = meetingCreate.handle(r, A, "Create an Alpha Study meeting on " + today.plusDays(10) + " at 7pm at " + MARK + "Head " + MARK + "Family's house");
        assertThat(loc.get("asking")).isEqualTo("location");
        assertThat(String.valueOf(loc)).doesNotContain("home (");
        // Tenant A's own member's home → confirm → created under A only, with A's member as the location.
        Map<String, Object> c = meetingCreate.handle(r, A, "Create an Alpha Study meeting on " + today.plusDays(10) + " at 7pm at AlphaHead AlphaFamily's house");
        assertThat(c.get("intent")).isEqualTo("meeting.confirm");
        @SuppressWarnings("unchecked") String token = String.valueOf(((Map<String, Object>) c.get("confirm")).get("token"));
        MockHttpServletRequest r2 = new MockHttpServletRequest(); r2.setSession(s);
        Map<String, Object> created = meetingCreate.confirm(r2, A, token);
        assertThat(created.get("intent")).isEqualTo("meeting.created");
        Integer newId = (Integer) created.get("meetingId");
        assertThat(meetingRepo.findByIdAndAppClientIdAndDeleteFlagFalse(newId, A)).get()
                .satisfies(m -> assertThat(m.getLocationMemberId()).isEqualTo(aMemberId));
        assertThat(meetingRepo.findByIdAndAppClientIdAndDeleteFlagFalse(newId, B)).isEmpty();
        @SuppressWarnings("unchecked") Map<String, String> link = (Map<String, String>) created.get("link");
        assertThat(link).containsEntry("url", "/meetings?meetingId=" + newId);
        assertThat(meetingRepo.aiCountSameSlot(A, aTypeId, today.plusDays(10), "19:00")).isEqualTo(1);
        assertThat(meetingRepo.aiCountSameSlot(B, bTypeId, today.plusDays(10), "19:00")).isZero();
        assertThat(meetingRepo.findByAppClientIdAndDeleteFlagFalse(B)).hasSize(1);
    }
}
