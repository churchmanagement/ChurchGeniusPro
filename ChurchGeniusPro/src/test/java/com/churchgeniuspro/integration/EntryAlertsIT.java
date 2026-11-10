package com.churchgeniuspro.integration;

import com.churchgeniuspro.ai.action.AiActionService;
import com.churchgeniuspro.ai.guided.AiGuidedRecordService;
import com.churchgeniuspro.controller.ExpenseController;
import com.churchgeniuspro.controller.IncomeController;
import com.churchgeniuspro.entryalert.EntryAlertController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.IncomeService;
import com.churchgeniuspro.util.AppClock;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-09 — Income &amp; Expense alerts and the Genius Assistant saved confirmation on PostgreSQL, two churches:
 * alert settings stored per login / role / kind; the page endpoint and the conversation raise the SAME alerts from the
 * same row; an alerted entry is written only after confirmation and only once; the confirmation's previous / new fund
 * totals equal the ledger (active rows of this church only) and the dashboard's summary table.
 */
@EnabledIf("com.churchgeniuspro.integration.EntryAlertsIT#databaseAvailable")
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = { "app.base-url=http://localhost", "ai.actions.llm-fallback=false", "ai.guided.llm-fallback=false" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Income & Expense alerts + Genius Assistant confirmation on PostgreSQL (two churches)")
class EntryAlertsIT {

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

    static final String A = "CGP-ALERT-ITA-" + System.currentTimeMillis(), B = A.replace("ITA", "ITB");

    @Autowired AiGuidedRecordService guided;
    @Autowired AiActionService actions;
    @Autowired IncomeController incomeController;
    @Autowired ExpenseController expenseController;
    @Autowired EntryAlertController alertController;
    @Autowired IncomeService incomeService;
    @Autowired com.churchgeniuspro.service.ExpenseService expenseService;
    @Autowired JdbcTemplate jdbc;
    @Autowired FamilyRepository familyRepo; @Autowired FamilyMemberRepository memberRepo;
    @Autowired MainSourceRepository mainRepo; @Autowired SubSourceRepository subRepo; @Autowired PurposeRepository purposeRepo;
    @Autowired TransactionTypeRepository methodRepo;

    Integer annaA, titheA, generalA, utilA, cashA, titheB;

    @BeforeAll
    void seed() {
        for (String cid : List.of(A, B)) {
            Family f = new Family(); f.setAppClientId(cid); f = familyRepo.save(f);
            FamilyMember anna = new FamilyMember(); anna.setFamily(f); anna.setFirstName("Anna"); anna.setLastName("Varghese"); anna.setRole("Head of Household"); anna.setAppClientId(cid); anna = memberRepo.save(anna);
            MainSource ms = new MainSource(); ms.setSourceName("General Fund"); ms.setAppClientId(cid); ms = mainRepo.save(ms);
            SubSource tithe = new SubSource(); tithe.setSourceName("Tithe Fund"); tithe.setMainSource(ms); tithe.setAppClientId(cid); tithe = subRepo.save(tithe);
            Purpose util = new Purpose(); util.setPurposeName("Utilities"); util.setAppClientId(cid); util = purposeRepo.save(util);
            TransactionType cash = new TransactionType(); cash.setTypeName("Cash"); cash.setAppClientId(cid); cash = methodRepo.save(cash);
            // existing history: $5,000 in A's Tithe Fund (+ a deleted $999 that must not count); $70,000 in B
            if (cid.equals(A)) {
                annaA = anna.getId(); titheA = tithe.getId(); generalA = ms.getId(); utilA = util.getId(); cashA = cash.getId();
                incomeService.createIncome(anna.getId(), tithe.getId(), AppClock.today().minusDays(40), cash.getId(), null, new BigDecimal("3000.00"), null, null, false, cid, "seed");
                incomeService.createIncome(anna.getId(), tithe.getId(), AppClock.today().minusDays(10), cash.getId(), null, new BigDecimal("2000.00"), null, null, false, cid, "seed");
                Income gone = incomeService.createIncome(anna.getId(), tithe.getId(), AppClock.today().minusDays(5), cash.getId(), null, new BigDecimal("999.00"), null, null, false, cid, "seed");
                jdbc.update("UPDATE income SET delete_flag = true WHERE id = ?", gone.getId());
                expenseService.createExpense(util.getId(), ms.getId(), AppClock.today().minusDays(15), cash.getId(), null, new BigDecimal("1200.00"), null, false, cid, "seed");
            } else {
                titheB = tithe.getId();
                incomeService.createIncome(anna.getId(), tithe.getId(), AppClock.today().minusDays(3), cash.getId(), null, new BigDecimal("70000.00"), null, null, false, cid, "seed");
            }
        }
    }

    MockHttpServletRequest staff(String cid, String user, String role) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.getSession(true).setAttribute("username", user); r.getSession().setAttribute("role", role); r.getSession().setAttribute("appClientId", cid);
        r.getSession().setAttribute("clientId", "USR-" + cid);
        return r;
    }
    static MockHttpServletRequest same(MockHttpServletRequest r) { MockHttpServletRequest n = new MockHttpServletRequest(); n.setSession(r.getSession()); return n; }
    @SuppressWarnings("unchecked") static Map<String, Object> m(Object o) { return (Map<String, Object>) o; }
    static String usd(BigDecimal a) { return NumberFormat.getCurrencyInstance(Locale.US).format(a); }
    /** The Accountant dashboard's NET BALANCE card, computed exactly as AccountantDashboardController does. */
    BigDecimal dashboardNet(String cid) {
        BigDecimal inc = jdbc.queryForObject("SELECT COALESCE(SUM(total_amount),0) FROM ledger_month_summary WHERE app_client_id = ? AND kind = 'I'", BigDecimal.class, cid);
        BigDecimal exp = jdbc.queryForObject("SELECT COALESCE(SUM(s.total_amount),0) FROM ledger_month_summary s JOIN main_source ms ON ms.id = s.main_source_id "
            + "WHERE s.app_client_id = ? AND s.kind = 'E'", BigDecimal.class, cid);
        return inc.subtract(exp);
    }
    BigDecimal ledgerIncome(String cid) { return jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM income WHERE app_client_id = ? AND delete_flag = false", BigDecimal.class, cid); }
    BigDecimal ledgerExpense(String cid) { return jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM expense WHERE app_client_id = ? AND delete_flag = false", BigDecimal.class, cid); }
    long rows(String table, String cid) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE app_client_id = ? AND delete_flag = false", Long.class, cid); }

    @Test @Order(1) @DisplayName("saved confirmation: Previous / New Net Balance = the dashboard's NET BALANCE (all income − all expenses of this church)")
    void totals() {
        BigDecimal before = dashboardNet(A);
        assertThat(before).isEqualByComparingTo("3800.00");                       // 5,000 − 1,200 (deleted row and church B not counted)
        MockHttpServletRequest r = staff(A, "pat", "Accountant");
        guided.handle(r, A, "Add an income of $100 from Anna Varghese to the Tithe Fund by cash today");
        Map<String, Object> card = guided.handle(same(r), A, "Skip and review");
        assertThat(card.get("intent")).isEqualTo("guided.confirm");
        Map<String, Object> done = guided.handle(same(r), A, "yes");
        assertThat(done.get("intent")).isEqualTo("action.done");
        Map<String, Object> t = m(m(done.get("receipt")).get("totals"));
        BigDecimal after = dashboardNet(A);
        assertThat(after).isEqualByComparingTo("3900.00");
        assertThat(t).containsEntry("previous", usd(before)).containsEntry("added", "+$100.00").containsEntry("next", usd(after))
                     .containsEntry("previousLabel", "Previous Net Balance").containsEntry("nextLabel", "New Net Balance");
        // the ledger agrees with the summary table the dashboard reads
        assertThat(after).isEqualByComparingTo(ledgerIncome(A).subtract(ledgerExpense(A)));
        assertThat(dashboardNet(B)).isEqualByComparingTo("70000.00");
        // an expense lowers the Net Balance
        MockHttpServletRequest r2 = staff(A, "pat", "Accountant");
        guided.handle(r2, A, "Add an expense of $300.25 for Utilities from the General Fund by cash today");
        guided.handle(same(r2), A, "Skip and review");
        Map<String, Object> ex = guided.handle(same(r2), A, "yes");
        Map<String, Object> et = m(m(ex.get("receipt")).get("totals"));
        assertThat(et).containsEntry("previous", "$3,900.00").containsEntry("added", "\u2212$300.25").containsEntry("next", "$3,599.75");
        assertThat(dashboardNet(A)).isEqualByComparingTo("3599.75");
    }

    @Test @Order(2) @DisplayName("alerts: stored per login / role / kind; page endpoint and Genius Assistant raise the same alert from the same row; saved once after confirming")
    void alerts() throws Exception {
        MockHttpServletRequest pat = staff(A, "pat", "Accountant");
        ResponseEntity<Map<String, Object>> put = alertController.put("income", new LinkedHashMap<>(Map.of("maxEnabled", true, "maxAmount", "1000",
                "pastEnabled", true, "pastDays", "90")), pat);
        assertThat(put.getStatusCode().value()).isEqualTo(200);
        assertThat(alertController.put("income", new LinkedHashMap<>(Map.of("maxEnabled", true, "maxAmount", "-1")), pat).getStatusCode().value()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM entry_alert_setting WHERE app_client_id = ? AND username = 'pat' AND role = 'Accountant' AND kind = 'INCOME' "
            + "AND max_enabled AND max_amount = 1000.00 AND past_days = 90", Long.class, A)).isEqualTo(1);
        // the unique key refuses a second row for the same login + role + kind
        Assertions.assertThrows(Exception.class, () -> jdbc.update("INSERT INTO entry_alert_setting (app_client_id, username, role, kind, future_enabled, past_enabled, max_enabled, min_enabled) "
            + "VALUES (?, 'pat', 'Accountant', 'INCOME', false, false, false, false)", A));
        long before = rows("income", A);
        // page endpoint: 409, nothing written; confirmed → one row
        Map<String, Object> body = new LinkedHashMap<>(Map.of("subSourceId", titheA, "incomeDate", AppClock.today().minusDays(120).toString(),
                "transactionTypeId", cashA, "amount", "2500", "memberId", annaA));
        ResponseEntity<Map<String, Object>> res = incomeController.createIncome(body, pat);
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        @SuppressWarnings("unchecked") List<Map<String, Object>> pageAlerts = (List<Map<String, Object>>) res.getBody().get("alerts");
        assertThat(pageAlerts).extracting(a -> a.get("code")).containsExactly("OLD_DATE", "AMOUNT_HIGH");
        assertThat(rows("income", A)).isEqualTo(before);
        body.put("alertsConfirmed", true);
        assertThat(incomeController.createIncome(body, pat).getStatusCode().value()).isEqualTo(200);
        assertThat(rows("income", A)).isEqualTo(before + 1);
        // Genius Assistant: the same entry → the same alert messages
        MockHttpServletRequest r = staff(A, "pat", "Accountant");
        Map<String, Object> m = guided.handle(r, A, "Add an income of $2500 from Anna Varghese to the Tithe Fund by cash on "
            + AppClock.today().minusDays(120).format(java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy")));
        assertThat(m.get("asking")).isEqualTo("alerts");
        @SuppressWarnings("unchecked") List<Map<String, Object>> aiAlerts = (List<Map<String, Object>>) m.get("alerts");
        assertThat(aiAlerts).extracting(a -> a.get("message")).containsExactlyElementsOf(pageAlerts.stream().map(a -> a.get("message")).toList());
        assertThat(rows("income", A)).isEqualTo(before + 1);
        // another login of the church (no settings) and church B: no alerts
        assertThat(incomeController.createIncome(new LinkedHashMap<>(Map.of("subSourceId", titheA, "incomeDate", AppClock.today().toString(),
                "transactionTypeId", cashA, "amount", "2500", "guestName", "Anonymous")), staff(A, "lee", "Accountant")).getStatusCode().value()).isEqualTo(200);
        // expense alerts are their own settings
        assertThat(expenseController.create(new LinkedHashMap<>(Map.of("purposeId", utilA, "mainSourceId", generalA, "expenseDate", AppClock.today().minusDays(400).toString(),
                "transactionTypeId", cashA, "amount", "5000")), pat).getStatusCode().value()).isEqualTo(200);
        // migration block is safe to run twice
        String sql = Files.readString(Path.of("scripts/sql/migrate_production.sql"));
        String block = sql.substring(sql.indexOf("CREATE TABLE IF NOT EXISTS entry_alert_setting"));
        block = block.substring(0, block.indexOf(");") + 2);
        jdbc.execute(block); jdbc.execute(block);
    }
}
