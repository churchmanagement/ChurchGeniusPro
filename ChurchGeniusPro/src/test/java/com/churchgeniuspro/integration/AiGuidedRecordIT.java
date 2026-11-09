package com.churchgeniuspro.integration;

import com.churchgeniuspro.ai.action.AiActionService;
import com.churchgeniuspro.ai.guided.AiGuidedRecordService;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.AiMeetingCreateService;
import com.churchgeniuspro.util.AppClock;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-08 — the guided AI Search conversations on PostgreSQL with two churches: income and expense are
 * written ONCE through the real services after the review card, only in the signed-in church, with church B's
 * same-named funds / members invisible to A; permission is the Income / Expense page rule; the meeting flow still
 * creates its meeting.
 */
@EnabledIf("com.churchgeniuspro.integration.AiGuidedRecordIT#databaseAvailable")
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = { "app.base-url=http://localhost", "ai.actions.llm-fallback=false", "ai.guided.llm-fallback=false" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("AI Search guided income / expense / meeting on PostgreSQL (two churches)")
class AiGuidedRecordIT {

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
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine"); POSTGRES.start();
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl); r.add("spring.datasource.username", POSTGRES::getUsername); r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    static final String A = "CGP-GUIDE-ITA-" + System.currentTimeMillis(), B = A.replace("ITA", "ITB");

    @Autowired AiGuidedRecordService guided;
    @Autowired AiActionService actions;
    @Autowired AiMeetingCreateService meetings;
    @Autowired com.churchgeniuspro.ai.guided.AiGuidedSettingService settings;
    @Autowired com.churchgeniuspro.ai.guided.AiManageRedirectService manage;
    @Autowired JdbcTemplate jdbc;
    @Autowired FamilyRepository familyRepo; @Autowired FamilyMemberRepository memberRepo;
    @Autowired MainSourceRepository mainRepo; @Autowired SubSourceRepository subRepo; @Autowired PurposeRepository purposeRepo;
    @Autowired TransactionTypeRepository methodRepo; @Autowired MeetingTypeRepository typeRepo;

    Integer annaA, annaB, titheA, titheB, generalA, utilA, checkA, cashA;

    @BeforeAll
    void seed() {
        for (String cid : List.of(A, B)) {
            Family f = new Family(); f.setAppClientId(cid); f = familyRepo.save(f);
            FamilyMember anna = new FamilyMember(); anna.setFamily(f); anna.setFirstName("Anna"); anna.setLastName("Varghese"); anna.setRole("Head of Household"); anna.setAppClientId(cid); anna = memberRepo.save(anna);
            MainSource ms = new MainSource(); ms.setSourceName("General Fund"); ms.setAppClientId(cid); ms = mainRepo.save(ms);
            SubSource tithe = new SubSource(); tithe.setSourceName("Tithe"); tithe.setMainSource(ms); tithe.setAppClientId(cid); tithe = subRepo.save(tithe);
            Purpose util = new Purpose(); util.setPurposeName("Utilities"); util.setAppClientId(cid); util = purposeRepo.save(util);
            TransactionType check = new TransactionType(); check.setTypeName("Check"); check.setAppClientId(cid); check = methodRepo.save(check);
            TransactionType cash = new TransactionType(); cash.setTypeName("Cash"); cash.setAppClientId(cid); cash = methodRepo.save(cash);
            MeetingType mt = new MeetingType(); mt.setAppClientId(cid); mt.setTypeName("Prayer Meeting"); typeRepo.save(mt);
            if (cid.equals(A)) { annaA = anna.getId(); titheA = tithe.getId(); generalA = ms.getId(); utilA = util.getId(); checkA = check.getId(); cashA = cash.getId(); }
            else { annaB = anna.getId(); titheB = tithe.getId(); }
        }
        // only church B has a "Mission Trip" fund and a member "Zed Brown"
        MainSource msB = mainRepo.findActiveByAppUser(B).get(0);
        SubSource mission = new SubSource(); mission.setSourceName("Mission Trip"); mission.setMainSource(msB); mission.setAppClientId(B); subRepo.save(mission);
        Family fb = familyRepo.findAll().stream().filter(x -> B.equals(x.getAppClientId())).findFirst().get();
        FamilyMember zed = new FamilyMember(); zed.setFamily(fb); zed.setFirstName("Zed"); zed.setLastName("Brown"); zed.setRole("Adult"); zed.setAppClientId(B); memberRepo.save(zed);
    }

    MockHttpServletRequest staff(String cid, String role, String privs) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.getSession(true).setAttribute("username", "pat-" + cid); r.getSession().setAttribute("role", role); r.getSession().setAttribute("appClientId", cid);
        r.getSession().setAttribute("clientId", "USR-" + cid);
        if (privs != null) r.getSession().setAttribute("privileges", privs);
        return r;
    }
    static MockHttpServletRequest same(MockHttpServletRequest r) { MockHttpServletRequest n = new MockHttpServletRequest(); n.setSession(r.getSession()); return n; }
    @SuppressWarnings("unchecked") static String token(Map<String, Object> m) { return (String) ((Map<String, Object>) m.get("confirm")).get("token"); }
    long incomes(String cid) { return jdbc.queryForObject("SELECT COUNT(*) FROM income WHERE app_client_id = ? AND delete_flag = false", Long.class, cid); }
    long expenses(String cid) { return jdbc.queryForObject("SELECT COUNT(*) FROM expense WHERE app_client_id = ? AND delete_flag = false", Long.class, cid); }

    @Test @DisplayName("income: step by step, one row in A through IncomeService after Save; a second Save writes nothing; B untouched")
    void income() {
        LocalDate today = AppClock.today();
        MockHttpServletRequest r = staff(A, "Accountant", null);
        assertThat(guided.handle(r, A, "add an income").get("asking")).isEqualTo("contributor");
        assertThat(guided.handle(same(r), A, "Anna Varghese").get("asking")).isEqualTo("amount");
        assertThat(guided.handle(same(r), A, "$125.40").get("asking")).isEqualTo("fund");
        assertThat(guided.handle(same(r), A, "Tithe").get("asking")).isEqualTo("date");
        assertThat(guided.handle(same(r), A, "today").get("asking")).isEqualTo("method");
        assertThat(guided.handle(same(r), A, "check").get("asking")).isEqualTo("optional");
        assertThat(guided.handle(same(r), A, "Add a reference number").get("asking")).isEqualTo("refNo");
        assertThat(guided.handle(same(r), A, "1045").get("asking")).isEqualTo("review");
        Map<String, Object> card = guided.handle(same(r), A, "ready");
        assertThat(card.get("intent")).isEqualTo("guided.confirm");
        assertThat(incomes(A)).as("nothing before Save").isZero();
        Map<String, Object> done = actions.confirm(same(r), A, token(card));
        assertThat(done.get("intent")).isEqualTo("action.done");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT member_id, sub_source_id, transaction_type_id, amount, ref_no, income_date, created_by, guest_name FROM income WHERE app_client_id = ?", A);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(((Number) row.get("member_id")).intValue()).isEqualTo(annaA);
        assertThat(((Number) row.get("sub_source_id")).intValue()).isEqualTo(titheA);
        assertThat(((Number) row.get("transaction_type_id")).intValue()).isEqualTo(checkA);
        assertThat(new BigDecimal(String.valueOf(row.get("amount")))).isEqualByComparingTo("125.40");
        assertThat(row.get("ref_no")).isEqualTo("1045");
        assertThat(String.valueOf(row.get("income_date"))).isEqualTo(today.toString());
        assertThat(row.get("created_by")).isEqualTo("pat-" + A);
        assertThat(actions.confirm(same(r), A, token(card)).get("intent")).isEqualTo("action.expired");
        assertThat(guided.handle(same(r), A, "yes")).isNull();
        assertThat(incomes(A)).isEqualTo(1);
        assertThat(incomes(B)).isZero();
        // the proposal row is GUIDED and EXECUTED, with ids only
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_action_proposal WHERE app_client_id = ? AND tool = 'create_income' AND source = 'GUIDED' AND status = 'EXECUTED'", Long.class, A)).isEqualTo(1);
        // the same entry again → duplicate question
        Map<String, Object> again = guided.handle(staff(A, "Accountant", null), A, "add an income of $125.40 from Anna Varghese to Tithe by check today ref 1045");
        assertThat(String.valueOf(again.get("answer"))).contains("already recorded");
    }

    @Test @DisplayName("church scoping: B's fund and member names are unknown to A; a guest name is kept as written")
    void scoping() {
        MockHttpServletRequest r = staff(A, "SuperAdmin", null);
        Map<String, Object> m = guided.handle(r, A, "add an income of $10 from Zed Brown to Mission Trip by cash today ref 7");
        assertThat(m.get("asking")).as("Zed Brown is only in church B").isEqualTo("contributor");
        assertThat(String.valueOf(m.get("answer"))).contains("couldn't find a member called “Zed Brown”");
        m = guided.handle(same(r), A, "Yes, record as a guest");
        assertThat(m.get("asking")).as("Mission Trip is only in church B").isEqualTo("fund");
        assertThat(String.valueOf(m.get("answer"))).contains("Tithe").doesNotContain("Mission Trip");
        m = guided.handle(same(r), A, "Tithe");
        assertThat(m.get("asking")).isEqualTo("review");
        m = guided.handle(same(r), A, "ready");
        Map<String, Object> done = actions.confirm(same(r), A, token(m));
        assertThat(done.get("intent")).isEqualTo("action.done");
        Map<String, Object> row = jdbc.queryForMap("SELECT member_id, guest_name, sub_source_id FROM income WHERE app_client_id = ? AND guest_name IS NOT NULL", A);
        assertThat(row.get("member_id")).isNull();
        assertThat(row.get("guest_name")).isEqualTo("Zed Brown");
        assertThat(((Number) row.get("sub_source_id")).intValue()).isEqualTo(titheA);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM income WHERE sub_source_id = ? AND app_client_id <> ?", Long.class, titheB, B)).isZero();
    }

    @Test @DisplayName("expense: one sentence → optional question → card → spoken yes writes one row; Admin without the Expense permission is refused")
    void expense() {
        MockHttpServletRequest r = staff(A, "Accountant", null);
        Map<String, Object> m = guided.handle(r, A, "Record an expense of $89.99 for Utilities from General Fund by cash yesterday");
        assertThat(m.get("asking")).isEqualTo("optional");
        m = guided.handle(same(r), A, "Skip and review");
        assertThat(m.get("intent")).isEqualTo("guided.confirm");
        long before = expenses(A);
        assertThat(guided.handle(same(r), A, "yes").get("intent")).isEqualTo("action.done");
        assertThat(expenses(A)).isEqualTo(before + 1);
        Map<String, Object> row = jdbc.queryForMap("SELECT purpose_id, main_source_id, transaction_type_id, amount FROM expense WHERE app_client_id = ? ORDER BY id DESC LIMIT 1", A);
        assertThat(((Number) row.get("purpose_id")).intValue()).isEqualTo(utilA);
        assertThat(((Number) row.get("main_source_id")).intValue()).isEqualTo(generalA);
        assertThat(((Number) row.get("transaction_type_id")).intValue()).isEqualTo(cashA);
        assertThat(expenses(B)).isZero();
        // Admin's role defaults carry no Accounting permission → refused, nothing looked up or written
        assertThat(guided.handle(staff(A, "Admin", null), A, "add an expense").get("intent")).isEqualTo("guided.denied");
        assertThat(guided.handle(staff(A, "User", null), A, "add an income").get("intent")).isEqualTo("guided.denied");
    }

    @Test @DisplayName("meeting flow on the same route still creates its meeting (review question, card, yes)")
    void meetingStillWorks() {
        MockHttpServletRequest r = staff(A, "Admin", "{\"general\":true,\"general.meetings\":true,\"general.meetings.edit\":true}");
        LocalDate day = AppClock.today().plusDays(9);
        Map<String, Object> m = meetings.handle(r, A, "Create a Prayer Meeting on " + day);
        assertThat(m.get("asking")).isEqualTo("location");
        m = meetings.handle(same(r), A, "no location");
        m = meetings.handle(same(r), A, "7:30");
        assertThat(m.get("choices")).isEqualTo(List.of("7:30 AM", "7:30 PM"));
        m = meetings.handle(same(r), A, "7:30 PM");
        assertThat(m.get("asking")).isEqualTo("review");
        m = meetings.handle(same(r), A, "ready");
        assertThat(m.get("intent")).isEqualTo("meeting.confirm");
        assertThat(meetings.handle(same(r), A, "yes").get("intent")).isEqualTo("meeting.created");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM meeting WHERE app_client_id = ? AND meeting_date = ? AND start_time = '19:30'", Long.class, A, java.sql.Date.valueOf(day))).isEqualTo(1);
    }

    @Test @DisplayName("Account Settings: transaction type, purpose, main fund and sub-fund written once in A through the pages' services; B's main fund unknown to A")
    void settingsEntries() {
        MockHttpServletRequest r = staff(A, "Accountant", null);
        Map<String, Object> m = settings.handle(r, A, "Create a new transaction type called Special Offering");
        assertThat(m.get("intent")).isEqualTo("guided.confirm");
        assertThat(settings.handle(same(r), A, "yes").get("intent")).isEqualTo("action.done");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction_type WHERE app_client_id = ? AND type_name = 'Special Offering' AND delete_flag = false", Long.class, A)).isEqualTo(1);
        assertThat(settings.handle(staff(A, "Accountant", null), A, "add a payment method called special offering").get("answer").toString()).contains("already exists");

        MockHttpServletRequest r2 = staff(A, "SuperAdmin", null);
        settings.handle(r2, A, "add a new purpose");
        m = settings.handle(same(r2), A, "Youth Camp");
        actions.confirm(same(r2), A, token(m));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purpose WHERE app_client_id = ? AND purpose_name = 'Youth Camp'", Long.class, A)).isEqualTo(1);

        MockHttpServletRequest r3 = staff(A, "Accountant", null);
        m = settings.handle(r3, A, "Add a new fund called Building Fund");
        assertThat(m.get("asking")).isEqualTo("level");
        m = settings.handle(same(r3), A, "Main fund");
        assertThat(settings.handle(same(r3), A, "save").get("intent")).isEqualTo("action.done");
        Integer building = jdbc.queryForObject("SELECT id FROM main_source WHERE app_client_id = ? AND source_name = 'Building Fund' AND delete_flag = false", Integer.class, A);

        MockHttpServletRequest r4 = staff(A, "Accountant", null);
        m = settings.handle(r4, A, "add a sub-fund Roof Repair under Building Fund, not tax-deductible");
        assertThat(m.get("intent")).isEqualTo("guided.confirm");
        actions.confirm(same(r4), A, token(m));
        Map<String, Object> sub = jdbc.queryForMap("SELECT main_source_id, app_client_id, tax_deductible FROM sub_source WHERE source_name = 'Roof Repair' AND app_client_id = ?", A);
        assertThat(((Number) sub.get("main_source_id")).intValue()).isEqualTo(building);
        assertThat(sub.get("app_client_id")).isEqualTo(A);
        assertThat(sub.get("tax_deductible")).isEqualTo(false);
        // B's only-there main fund is not offered to A
        MockHttpServletRequest r5 = staff(A, "Accountant", null);
        mainRepo.save(newMain(B, "Mission Base"));
        m = settings.handle(r5, A, "add a sub-fund Wells under Mission Base");
        assertThat(m.get("asking")).isEqualTo("parent");
        assertThat(String.valueOf(m.get("answer"))).doesNotContain("Mission Base");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction_type WHERE app_client_id = ? AND type_name = 'Special Offering'", Long.class, B)).isZero();
        // Admin with the role defaults (no Accounting) is refused
        assertThat(settings.handle(staff(A, "Admin", null), A, "add a new fund called X").get("intent")).isEqualTo("guided.denied");
        // edit / delete through AI Search: nothing changes, the answer links to the page; the church's real names are recognised
        String before = jdbc.queryForObject("SELECT COUNT(*) || '/' || COALESCE(SUM(LENGTH(type_name)),0) FROM transaction_type WHERE app_client_id = ? AND delete_flag = false", String.class, A);
        Map<String, Object> d = manage.handle(staff(A, "Accountant", null), A, "delete Special Offering", false);
        assertThat(d.get("intent")).isEqualTo("manage.redirect");
        assertThat(d.get("record")).isEqualTo("Special Offering");
        assertThat(((Map<?, ?>) d.get("link")).get("url")).isEqualTo("/transactiontype");
        assertThat(((Map<?, ?>) manage.handle(staff(A, "Accountant", null), A, "rename Roof Repair to Roof Fund", false).get("link")).get("url")).isEqualTo("/fund");
        assertThat(manage.handle(staff(B, "Accountant", null), B, "delete Roof Repair", false)).as("A's name means nothing in B").isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) || '/' || COALESCE(SUM(LENGTH(type_name)),0) FROM transaction_type WHERE app_client_id = ? AND delete_flag = false", String.class, A)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sub_source WHERE source_name = 'Roof Repair' AND app_client_id = ? AND delete_flag = false", Long.class, A)).isEqualTo(1);
    }

    static MainSource newMain(String cid, String name) { MainSource ms = new MainSource(); ms.setSourceName(name); ms.setAppClientId(cid); return ms; }
}
