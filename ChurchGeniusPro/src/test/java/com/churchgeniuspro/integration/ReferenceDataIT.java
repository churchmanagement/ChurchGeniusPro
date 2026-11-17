package com.churchgeniuspro.integration;

import com.churchgeniuspro.controller.IncomeController;
import com.churchgeniuspro.controller.PurposeController;
import com.churchgeniuspro.controller.ReferenceDataController;
import com.churchgeniuspro.controller.SourceController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.ExpenseService;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 2026-11-16 (item 2) on PostgreSQL with the real pages' controllers and services, two churches — the user's
 * example: main category "New Building Fund" / sub-category "Fund" with income recorded, then soft-deleted.
 */
@EnabledIf("com.churchgeniuspro.integration.ReferenceDataIT#databaseAvailable")
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = { "app.base-url=http://localhost" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Fund / Purpose / Transaction Type delete, restore, permanent delete on PostgreSQL (two churches)")
class ReferenceDataIT {

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

    static final String A = "CGP-REF-ITA-" + System.currentTimeMillis(), B = A.replace("ITA", "ITB");

    @Autowired SourceController sources;
    @Autowired PurposeController purposes;
    @Autowired IncomeController incomes;
    @Autowired ReferenceDataController ref;
    @Autowired IncomeService incomeService;
    @Autowired ExpenseService expenseService;
    @Autowired IncomeRepository incomeRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired FamilyRepository familyRepo; @Autowired FamilyMemberRepository memberRepo;
    @Autowired MainSourceRepository mainRepo; @Autowired SubSourceRepository subRepo; @Autowired PurposeRepository purposeRepo;
    @Autowired TransactionTypeRepository methodRepo;

    Integer james, building, fund, general, tithe, charity, unusedPurpose, check, buildingB, fundB, i1, i2;

    MainSource main(String cid, String name) { MainSource m = new MainSource(); m.setSourceName(name); m.setAppClientId(cid); return mainRepo.save(m); }
    SubSource sub(String cid, MainSource m, String name) { SubSource s = new SubSource(); s.setSourceName(name); s.setMainSource(m); s.setAppClientId(cid); return subRepo.save(s); }

    @BeforeAll
    void seed() {
        for (String cid : List.of(A, B)) {
            Family f = new Family(); f.setAppClientId(cid); f = familyRepo.save(f);
            FamilyMember m = new FamilyMember(); m.setFamily(f); m.setFirstName("James"); m.setLastName("Gomez"); m.setRole("Head of Household"); m.setAppClientId(cid); m = memberRepo.save(m);
            MainSource nb = main(cid, "New Building Fund"); SubSource fu = sub(cid, nb, "Fund");
            MainSource ge = main(cid, "General"); SubSource ti = sub(cid, ge, "Tithe");
            Purpose ch = new Purpose(); ch.setPurposeName("Charity"); ch.setAppClientId(cid); ch = purposeRepo.save(ch);
            Purpose un = new Purpose(); un.setPurposeName("Unused"); un.setAppClientId(cid); un = purposeRepo.save(un);
            TransactionType ck = new TransactionType(); ck.setTypeName("Check"); ck.setAppClientId(cid); ck = methodRepo.save(ck);
            Income a = incomeService.createIncome(m.getId(), fu.getId(), AppClock.today(), ck.getId(), null, new BigDecimal("100.00"), null, null, false, cid, "seed");
            Income b = incomeService.createIncome(m.getId(), fu.getId(), AppClock.today().minusDays(3), ck.getId(), null, new BigDecimal("250.00"), null, null, false, cid, "seed");
            expenseService.createExpense(ch.getId(), ge.getId(), AppClock.today(), ck.getId(), null, new BigDecimal("40.00"), null, false, cid, "seed");
            if (cid.equals(A)) { james = m.getId(); building = nb.getId(); fund = fu.getId(); general = ge.getId(); tithe = ti.getId();
                charity = ch.getId(); unusedPurpose = un.getId(); check = ck.getId(); i1 = a.getId(); i2 = b.getId(); }
            else { buildingB = nb.getId(); fundB = fu.getId(); }
        }
    }

    MockHttpServletRequest staff(String cid) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.getSession(true).setAttribute("username", "grace.pastor"); r.getSession().setAttribute("role", "SuperAdmin");
        r.getSession().setAttribute("appClientId", cid); r.getSession().setAttribute("clientId", "USR-" + cid);
        return r;
    }
    String ledger(String cid) {
        return jdbc.queryForList("SELECT id, member_id, sub_source_id, transaction_type_id, amount, income_date, delete_flag FROM income WHERE app_client_id = ? ORDER BY id", cid).toString()
             + jdbc.queryForList("SELECT id, main_source_id, purpose_id, transaction_type_id, amount, expense_date, delete_flag FROM expense WHERE app_client_id = ? ORDER BY id", cid).toString()
             + jdbc.queryForList("SELECT kind, main_source_id, sub_source_id, purpose_id, total_amount, txn_count FROM ledger_month_summary WHERE app_client_id = ? ORDER BY 1,2,3,4", cid).toString();
    }
    Map<String, Object> row(Integer incomeId) {
        return incomeService.getRecentIncomes(A, 0, 50).rows().stream().filter(m -> incomeId.equals(m.get("id"))).findFirst().orElseThrow();
    }

    @Test @Order(1) @DisplayName("the Flyway columns exist; the production migration block is safe to run twice")
    void schema() throws Exception {
        for (String t : List.of("main_source", "sub_source", "purpose", "transaction_type")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_name = ? AND column_name IN ('deleted_at','deleted_by')", Integer.class, t)).as(t).isEqualTo(2);
        }
        String v25 = Files.readString(Path.of("src/main/resources/db/migration/V25__reference_data_delete_tracking.sql"));
        jdbc.execute(v25); jdbc.execute(v25);
    }

    @Test @Order(2) @DisplayName("soft delete: transactions untouched and still named, tagged Deleted; new entries refused; report filter keeps it marked")
    void softDelete() {
        String before = ledger(A), beforeB = ledger(B);
        assertThat(sources.deleteMainSource(building, staff(A)).getStatusCode().value()).isEqualTo(200);

        assertThat(ledger(A)).as("no income, expense or summary row changed").isEqualTo(before);
        assertThat(ledger(B)).isEqualTo(beforeB);
        assertThat(jdbc.queryForObject("SELECT delete_flag FROM sub_source WHERE id = ?", Boolean.class, fundB)).as("church B's same-named fund").isFalse();

        Map<String, Object> r = row(i1);
        assertThat(r).containsEntry("mainSourceName", "New Building Fund").containsEntry("subSourceName", "Fund")
                .containsEntry("subSourceDeleted", true).containsEntry("methodDeleted", false).containsEntry("memberDeleted", false);

        // entry forms: the deleted sub-category is not offered; report filter: offered, flagged
        List<Map<String, Object>> forForms = incomes.getSubSources(staff(A)).getBody();
        assertThat(forForms).extracting(m -> m.get("id")).doesNotContain(fund).contains(tithe);
        MockHttpServletRequest rq = staff(A); rq.setParameter("includeDeleted", "true");
        List<Map<String, Object>> forReports = incomes.getSubSources(rq).getBody();
        Map<String, Object> del = forReports.stream().filter(m -> fund.equals(m.get("id"))).findFirst().orElseThrow();
        assertThat(del).containsEntry("deleted", true).containsEntry("mainSourceName", "New Building Fund").containsEntry("sourceName", "Fund");
        assertThat(forReports).extracting(m -> m.get("id")).doesNotContain(fundB);

        // the Income Report still finds the old transactions by the deleted sub-category
        assertThat(incomeRepo.reportIncomeTransactionsFiltered(java.sql.Date.valueOf(AppClock.today().minusDays(30)), java.sql.Date.valueOf(AppClock.today()), A, null, fund)).hasSize(2);

        // a NEW transaction cannot use it
        assertThatThrownBy(() -> incomeService.createIncome(james, fund, AppClock.today(), check, null, new BigDecimal("5.00"), null, null, false, A, "u"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM income WHERE app_client_id = ?", Integer.class, A)).isEqualTo(2);
    }

    @Test @Order(3) @DisplayName("an existing entry may keep its deleted fund while a note is corrected, or be moved to an active fund — never silently")
    void editKeepsOrMoves() {
        incomeService.updateIncome(i1, james, fund, AppClock.today(), check, "1042", new BigDecimal("100.00"), "corrected note", null, false, A, "grace.pastor");
        Map<String, Object> kept = row(i1);
        assertThat(kept).containsEntry("subSourceId", fund).containsEntry("subSourceDeleted", true).containsEntry("note", "corrected note").containsEntry("refNo", "1042");
        assertThat(((BigDecimal) kept.get("amount"))).isEqualByComparingTo("100.00");

        // i2 → moving to ANOTHER deleted reference is refused; to an active fund works
        Integer otherDeleted = jdbc.queryForObject("SELECT id FROM sub_source WHERE app_client_id = ? AND delete_flag = true AND id <> ? LIMIT 1", Integer.class, A, -1);
        assertThat(otherDeleted).isEqualTo(fund);
        incomeService.updateIncome(i2, james, tithe, AppClock.today().minusDays(3), check, null, new BigDecimal("250.00"), null, null, false, A, "grace.pastor");
        assertThat(row(i2)).containsEntry("subSourceId", tithe).containsEntry("subSourceDeleted", false);
        assertThatThrownBy(() -> incomeService.updateIncome(i2, james, fund, AppClock.today().minusDays(3), check, null, new BigDecimal("250.00"), null, null, false, A, "u"))
                .as("back to the deleted fund is a new choice → refused").isInstanceOf(IllegalArgumentException.class);
        assertThat(row(i2)).containsEntry("subSourceId", tithe);
    }

    @Test @Order(4) @DisplayName("permanent delete refused with the reasons while income refers to it; church B cannot touch church A")
    void permanentRefused() {
        String before = ledger(A);
        ResponseEntity<Map<String, Object>> no = ref.permanent("main-source", building, staff(A));
        assertThat(no.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(no.getBody().get("uses"))).contains("income entries (in its sub-categories)");
        assertThat(ref.permanent("main-source", building, staff(B)).getStatusCode().value()).isEqualTo(400);
        assertThat(ref.restore("main-source", building, staff(B)).getStatusCode().value()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM main_source WHERE id = ?", Integer.class, building)).isEqualTo(1);
        assertThat(ledger(A)).isEqualTo(before);
    }

    @Test @Order(5) @DisplayName("restore: the main category and the sub-category deleted with it are usable again")
    void restore() {
        ResponseEntity<Map<String, Object>> ok = ref.restore("main-source", building, staff(A));
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody()).containsEntry("restoredSubCategories", 1);
        assertThat(row(i1)).containsEntry("subSourceDeleted", false);
        assertThat(incomes.getSubSources(staff(A)).getBody()).extracting(m -> m.get("id")).contains(fund);
        Income again = incomeService.createIncome(james, fund, AppClock.today(), check, null, new BigDecimal("5.00"), null, null, false, A, "u");
        assertThat(again.getId()).isNotNull();
    }

    @Test @Order(6) @DisplayName("purposes: soft delete tags the expense; an unused purpose can be permanently deleted, a used one cannot")
    void purposes() {
        String before = ledger(A);
        assertThat(purposes.delete(charity, staff(A)).getStatusCode().value()).isEqualTo(200);
        Map<String, Object> e = expenseService.getRecentExpenses(A, 0, 10).rows().get(0);
        assertThat(e).containsEntry("purposeName", "Charity").containsEntry("purposeDeleted", true).containsEntry("mainSourceDeleted", false);
        assertThat(ref.permanent("purpose", charity, staff(A)).getStatusCode().value()).isEqualTo(409);
        assertThat(ref.permanent("purpose", unusedPurpose, staff(A)).getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purpose WHERE id = ?", Integer.class, unusedPurpose)).isZero();
        @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) ref.deleted("purpose", staff(A)).getBody().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("name", "Charity").containsEntry("deletedBy", "grace.pastor");
        assertThat(ref.restore("purpose", charity, staff(A)).getStatusCode().value()).isEqualTo(200);
        assertThat(ledger(A)).isEqualTo(before);
    }
}
