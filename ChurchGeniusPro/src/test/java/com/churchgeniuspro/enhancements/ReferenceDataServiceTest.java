package com.churchgeniuspro.enhancements;

import com.churchgeniuspro.controller.ReferenceDataController;
import com.churchgeniuspro.service.ReferenceDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.churchgeniuspro.service.ReferenceDataService.*;

/**
 * 2026-11-16 (item 2) — Fund / Purpose / Transaction Type: soft delete, restore, permanent delete.
 * Real SQL on an in-memory PostgreSQL-mode database with two churches.
 */
@DisplayName("Reference data — soft delete, restore, permanent delete")
class ReferenceDataServiceTest {

    static final AtomicInteger DB = new AtomicInteger();
    static final String A = "CHR-A", B = "CHR-B";
    JdbcTemplate jdbc;
    ReferenceDataService svc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:refdata" + DB.incrementAndGet() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        for (String t : new String[] { "main_source:source_name", "purpose:purpose_name", "transaction_type:type_name" }) {
            String[] p = t.split(":");
            jdbc.execute("CREATE TABLE " + p[0] + " (id INT PRIMARY KEY, app_client_id VARCHAR(255), " + p[1]
                    + " VARCHAR(255) NOT NULL, delete_flag BOOLEAN NOT NULL DEFAULT false, deleted_at TIMESTAMP, deleted_by VARCHAR(100))");
        }
        jdbc.execute("CREATE TABLE sub_source (id INT PRIMARY KEY, app_client_id VARCHAR(255), source_name VARCHAR(255) NOT NULL, "
                + "main_source_id INT NOT NULL REFERENCES main_source(id), delete_flag BOOLEAN NOT NULL DEFAULT false, "
                + "deleted_at TIMESTAMP, deleted_by VARCHAR(100), deleted_with_parent BOOLEAN)");
        jdbc.execute("CREATE TABLE income (id INT PRIMARY KEY, app_client_id VARCHAR(255), sub_source_id INT NOT NULL REFERENCES sub_source(id), "
                + "transaction_type_id INT REFERENCES transaction_type(id), amount NUMERIC(12,2), delete_flag BOOLEAN NOT NULL DEFAULT false)");
        jdbc.execute("CREATE TABLE expense (id INT PRIMARY KEY, app_client_id VARCHAR(255), main_source_id INT NOT NULL REFERENCES main_source(id), "
                + "purpose_id INT NOT NULL REFERENCES purpose(id), transaction_type_id INT REFERENCES transaction_type(id), amount NUMERIC(12,2), delete_flag BOOLEAN NOT NULL DEFAULT false)");
        jdbc.execute("CREATE TABLE pledge_campaign (id INT PRIMARY KEY, client_id VARCHAR(255), sub_source_id INT)");
        jdbc.execute("CREATE TABLE donation (id INT PRIMARY KEY, client_id VARCHAR(255), sub_source_id INT)");
        jdbc.execute("CREATE TABLE donation_settings (id INT PRIMARY KEY, client_id VARCHAR(255), income_sub_source_id INT, income_transaction_type_id INT, "
                + "expense_purpose_id INT, expense_main_source_id INT, expense_transaction_type_id INT)");
        jdbc.execute("CREATE TABLE plaid_transaction_staging (id INT PRIMARY KEY, client_id VARCHAR(255), mapped_sub_source_id INT, mapped_purpose_id INT, "
                + "mapped_main_source_id INT, mapped_transaction_type_id INT)");
        jdbc.execute("CREATE TABLE import_link (id INT PRIMARY KEY, client_id VARCHAR(255), entity VARCHAR(40), target_id INT)");

        // Church A: "New Building Fund" (1) with subs Fund (11), Pledges (12), Old (13, deleted earlier on its own); "General" (2) with Tithe (21)
        jdbc.update("INSERT INTO main_source (id, app_client_id, source_name) VALUES (1, ?, 'New Building Fund'), (2, ?, 'General'), (9, ?, 'Other Church Fund')", A, A, B);
        jdbc.update("INSERT INTO sub_source (id, app_client_id, source_name, main_source_id) VALUES (11, ?, 'Fund', 1), (12, ?, 'Pledges', 1), (21, ?, 'Tithe', 2), (91, ?, 'Theirs', 9)", A, A, A, B);
        jdbc.update("INSERT INTO sub_source (id, app_client_id, source_name, main_source_id, delete_flag, deleted_with_parent) VALUES (13, ?, 'Old', 1, true, false)", A);
        jdbc.update("INSERT INTO purpose (id, app_client_id, purpose_name) VALUES (1, ?, 'Charity'), (2, ?, 'Unused Purpose'), (9, ?, 'Their Purpose')", A, A, B);
        jdbc.update("INSERT INTO transaction_type (id, app_client_id, type_name) VALUES (1, ?, 'Check'), (2, ?, 'Zelle'), (9, ?, 'Their Type')", A, A, B);
        jdbc.update("INSERT INTO income (id, app_client_id, sub_source_id, transaction_type_id, amount) VALUES (100, ?, 11, 1, 100.00), (101, ?, 11, 1, 50.00)", A, A);
        jdbc.update("INSERT INTO expense (id, app_client_id, main_source_id, purpose_id, transaction_type_id, amount) VALUES (200, ?, 2, 1, 1, 75.00)", A);
        svc = new ReferenceDataService(jdbc);
    }

    private boolean deleted(String table, int id) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT delete_flag FROM " + table + " WHERE id = ?", Boolean.class, id)); }
    private int rows(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private String ledger() {
        return jdbc.queryForList("SELECT id, sub_source_id, transaction_type_id, amount, delete_flag FROM income ORDER BY id").toString()
             + jdbc.queryForList("SELECT id, main_source_id, purpose_id, transaction_type_id, amount, delete_flag FROM expense ORDER BY id").toString();
    }

    @Test @DisplayName("soft-deleting a main category deletes its active sub-categories, stamps who and when, and touches no transaction")
    void softDeleteMain() {
        String before = ledger();
        int subs = svc.softDelete(MAIN, 1, A, "grace.pastor");
        assertThat(subs).isEqualTo(2);
        assertThat(deleted("main_source", 1)).isTrue();
        assertThat(deleted("sub_source", 11)).isTrue();
        assertThat(deleted("sub_source", 12)).isTrue();
        assertThat(jdbc.queryForObject("SELECT deleted_by FROM main_source WHERE id = 1", String.class)).isEqualTo("grace.pastor");
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM main_source WHERE id = 1", java.sql.Timestamp.class)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT deleted_with_parent FROM sub_source WHERE id = 11", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT deleted_with_parent FROM sub_source WHERE id = 13", Boolean.class)).as("deleted earlier on its own").isFalse();
        assertThat(deleted("main_source", 2)).isFalse();
        assertThat(deleted("sub_source", 21)).isFalse();
        assertThat(ledger()).as("income and expense rows are untouched").isEqualTo(before);

        // the Income Report filter still offers the deleted sub-categories, flagged
        List<Map<String, Object>> filter = svc.deletedSubCategories(A);
        assertThat(filter).extracting(m -> m.get("mainSourceName") + "/" + m.get("sourceName"))
                .containsExactly("New Building Fund/Fund", "New Building Fund/Old", "New Building Fund/Pledges");
        assertThat(filter).allSatisfy(m -> assertThat(m.get("deleted")).isEqualTo(true));
        assertThat(svc.deletedSubCategories(B)).isEmpty();
    }

    @Test @DisplayName("restoring a main category brings back the sub-categories deleted with it — not the one deleted earlier on its own")
    void restoreMain() {
        svc.softDelete(MAIN, 1, A, "u");
        Map<String, Object> out = svc.restore(MAIN, 1, A, "u");
        assertThat(out).containsEntry("name", "New Building Fund").containsEntry("restoredSubCategories", 2);
        assertThat(deleted("main_source", 1)).isFalse();
        assertThat(deleted("sub_source", 11)).isFalse();
        assertThat(deleted("sub_source", 12)).isFalse();
        assertThat(deleted("sub_source", 13)).as("stays deleted").isTrue();
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM main_source WHERE id = 1", java.sql.Timestamp.class)).isNull();
        assertThatThrownBy(() -> svc.restore(MAIN, 1, A, "u")).hasMessageContaining("is not deleted");
    }

    @Test @DisplayName("a main category deleted before this change (no record of what went with it) gets all its deleted sub-categories back")
    void restoreLegacyMain() {
        jdbc.update("UPDATE sub_source SET delete_flag = true WHERE main_source_id = 1");       // old-style cascade: no stamps
        jdbc.update("UPDATE sub_source SET deleted_with_parent = NULL WHERE id = 13");
        jdbc.update("UPDATE main_source SET delete_flag = true WHERE id = 1");
        assertThat(svc.restore(MAIN, 1, A, "u")).containsEntry("restoredSubCategories", 3);
    }

    @Test @DisplayName("restore refusals: sub-category under a deleted main category; a name now used by an active entry")
    void restoreRefusals() {
        svc.softDelete(MAIN, 1, A, "u");
        assertThatThrownBy(() -> svc.restore(SUB, 11, A, "u")).hasMessageContaining("Restore the main category first");
        jdbc.update("INSERT INTO main_source (id, app_client_id, source_name) VALUES (3, ?, 'new building fund')", A);
        assertThatThrownBy(() -> svc.restore(MAIN, 1, A, "u")).hasMessageContaining("already exists");
        assertThat(deleted("main_source", 1)).isTrue();

        svc.softDelete(PURPOSE, 2, A, "u");
        jdbc.update("INSERT INTO purpose (id, app_client_id, purpose_name) VALUES (3, ?, 'UNUSED PURPOSE')", A);
        assertThatThrownBy(() -> svc.restore(PURPOSE, 2, A, "u")).hasMessageContaining("already exists");
        // the same name in ANOTHER church is no obstacle
        svc.softDelete(TYPE, 2, A, "u");
        jdbc.update("INSERT INTO transaction_type (id, app_client_id, type_name) VALUES (8, ?, 'Zelle')", B);
        assertThat(svc.restore(TYPE, 2, A, "u")).containsEntry("name", "Zelle");
    }

    @Test @DisplayName("permanent delete is refused while anything refers to the entry — and nothing is changed")
    void permanentRefusedWhenUsed() {
        String before = ledger();
        assertThatThrownBy(() -> svc.permanentDelete(MAIN, 1, A, "u"))
                .isInstanceOfSatisfying(ReferenceDataService.InUseException.class, e -> {
                    assertThat(e.getMessage()).contains("cannot be permanently deleted").contains("kept exactly as they are");
                    assertThat(e.getUses()).anySatisfy(u -> { assertThat(u.get("label")).isEqualTo("income entries (in its sub-categories)"); assertThat(u.get("count")).isEqualTo(2L); });
                });
        assertThatThrownBy(() -> svc.permanentDelete(SUB, 11, A, "u")).isInstanceOf(ReferenceDataService.InUseException.class);
        assertThatThrownBy(() -> svc.permanentDelete(PURPOSE, 1, A, "u")).isInstanceOf(ReferenceDataService.InUseException.class);
        assertThatThrownBy(() -> svc.permanentDelete(TYPE, 1, A, "u")).isInstanceOf(ReferenceDataService.InUseException.class);
        assertThatThrownBy(() -> svc.permanentDelete(MAIN, 2, A, "u")).as("General pays an expense").isInstanceOf(ReferenceDataService.InUseException.class);
        // a DELETED income row is still history and still blocks
        jdbc.update("UPDATE income SET delete_flag = true");
        assertThatThrownBy(() -> svc.permanentDelete(SUB, 11, A, "u")).isInstanceOf(ReferenceDataService.InUseException.class);
        jdbc.update("UPDATE income SET delete_flag = false");
        // also after a soft delete
        svc.softDelete(MAIN, 1, A, "u");
        assertThatThrownBy(() -> svc.permanentDelete(MAIN, 1, A, "u")).isInstanceOf(ReferenceDataService.InUseException.class);
        assertThat(rows("main_source")).isEqualTo(3);
        assertThat(rows("sub_source")).isEqualTo(5);
        assertThat(ledger()).isEqualTo(before);
    }

    @Test @DisplayName("every kind of reference blocks: campaign, online donation, Donation Settings, Bank Sync, data import")
    void everyReferenceBlocks() {
        Object[][] cases = {
                { SUB, 12, "INSERT INTO pledge_campaign (id, client_id, sub_source_id) VALUES (1, 'CHR-A', 12)", "pledge campaigns" },
                { SUB, 12, "INSERT INTO donation (id, client_id, sub_source_id) VALUES (1, 'CHR-A', 12)", "online donations" },
                { SUB, 12, "INSERT INTO donation_settings (id, client_id, income_sub_source_id) VALUES (1, 'CHR-A', 12)", "Donation Settings (income fund)" },
                { SUB, 12, "INSERT INTO plaid_transaction_staging (id, client_id, mapped_sub_source_id) VALUES (1, 'CHR-A', 12)", "Bank Sync transactions" },
                { SUB, 12, "INSERT INTO import_link (id, client_id, entity, target_id) VALUES (1, 'CHR-A', 'ref:sub_source', 12)", "data import records" },
                { PURPOSE, 2, "INSERT INTO donation_settings (id, client_id, expense_purpose_id) VALUES (2, 'CHR-A', 2)", "Donation Settings (fee expense category)" },
                { TYPE, 2, "INSERT INTO donation_settings (id, client_id, expense_transaction_type_id) VALUES (3, 'CHR-A', 2)", "Donation Settings (payment method)" },
                { TYPE, 2, "INSERT INTO plaid_transaction_staging (id, client_id, mapped_transaction_type_id) VALUES (2, 'CHR-A', 2)", "Bank Sync transactions" } };
        for (Object[] c : cases) {
            jdbc.execute((String) c[2]);
            List<Map<String, Object>> uses = svc.usage((String) c[0], (Integer) c[1], A);
            assertThat(uses).as((String) c[3]).extracting(u -> u.get("label")).contains(c[3]);
            String table = ((String) c[2]).split(" ")[2];
            jdbc.execute("DELETE FROM " + table);
        }
        // a reference from ANOTHER church to the same number does not count
        jdbc.execute("INSERT INTO pledge_campaign (id, client_id, sub_source_id) VALUES (5, 'CHR-B', 12)");
        assertThat(svc.usage(SUB, 12, A)).isEmpty();
    }

    @Test @DisplayName("permanent delete of an unused entry removes it (and a main category's sub-categories) and nothing else")
    void permanentWhenUnused() {
        String before = ledger();
        assertThat(svc.usage(PURPOSE, 2, A)).isEmpty();
        assertThat(svc.permanentDelete(PURPOSE, 2, A, "u")).containsEntry("name", "Unused Purpose");
        assertThat(svc.permanentDelete(TYPE, 2, A, "u")).containsEntry("name", "Zelle");
        assertThat(svc.permanentDelete(SUB, 12, A, "u")).containsEntry("name", "Pledges");
        jdbc.update("INSERT INTO main_source (id, app_client_id, source_name) VALUES (4, ?, 'Empty Fund')", A);
        jdbc.update("INSERT INTO sub_source (id, app_client_id, source_name, main_source_id) VALUES (41, ?, 'One', 4), (42, ?, 'Two', 4)", A, A);
        assertThat(svc.permanentDelete(MAIN, 4, A, "u")).containsEntry("removedSubCategories", 2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sub_source WHERE main_source_id = 4", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM main_source WHERE id = 4", Integer.class)).isZero();
        assertThat(rows("purpose")).isEqualTo(2);
        assertThat(rows("transaction_type")).isEqualTo(2);
        assertThat(svc.deletedSubCategories(A)).extracting(m -> m.get("sourceName")).as("gone from the report filter").doesNotContain("Pledges");
        assertThat(ledger()).isEqualTo(before);
    }

    @Test @DisplayName("another church's entries behave like unknown ids for every operation")
    void tenantIsolation() {
        for (String k : new String[] { MAIN, SUB, PURPOSE, TYPE }) {
            int theirs = k.equals(SUB) ? 91 : 9;
            assertThatThrownBy(() -> svc.softDelete(k, theirs, A, "u")).hasMessageContaining("not found");
            assertThatThrownBy(() -> svc.restore(k, theirs, A, "u")).hasMessageContaining("not found");
            assertThatThrownBy(() -> svc.usage(k, theirs, A)).hasMessageContaining("not found");
            assertThatThrownBy(() -> svc.permanentDelete(k, theirs, A, "u")).hasMessageContaining("not found");
        }
        assertThat(rows("main_source") + rows("sub_source") + rows("purpose") + rows("transaction_type")).isEqualTo(3 + 5 + 3 + 3);
        assertThat(deleted("main_source", 9) || deleted("purpose", 9) || deleted("transaction_type", 9) || deleted("sub_source", 91)).isFalse();
        assertThatThrownBy(() -> svc.softDelete(MAIN, 1, null, "u")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.softDelete("nonsense", 1, A, "u")).hasMessageContaining("Unknown kind");
        assertThatThrownBy(() -> svc.deleted("nonsense", A)).hasMessageContaining("Unknown list");
    }

    @Test @DisplayName("the Deleted list groups a main category with its sub-categories and lists lone sub-categories apart")
    void deletedList() {
        svc.softDelete(MAIN, 1, A, "grace.pastor");
        svc.softDelete(SUB, 21, A, "grace.pastor");
        svc.softDelete(PURPOSE, 2, A, "grace.pastor");
        Map<String, Object> fund = svc.deleted("fund", A);
        @SuppressWarnings("unchecked") List<Map<String, Object>> mains = (List<Map<String, Object>>) fund.get("mainCategories");
        assertThat(mains).hasSize(1);
        assertThat(mains.get(0)).containsEntry("name", "New Building Fund").containsEntry("deletedBy", "grace.pastor");
        assertThat((List<?>) mains.get(0).get("subCategories")).hasSize(3);
        @SuppressWarnings("unchecked") List<Map<String, Object>> lone = (List<Map<String, Object>>) fund.get("subCategories");
        assertThat(lone).hasSize(1);
        assertThat(lone.get(0)).containsEntry("name", "Tithe").containsEntry("mainName", "General");
        assertThat((List<?>) svc.deleted("purpose", A).get("items")).hasSize(1);
        assertThat((List<?>) svc.deleted("transaction-type", A).get("items")).isEmpty();
        assertThat((List<?>) svc.deleted("fund", B).get("mainCategories")).isEmpty();
    }

    // ── API ──────────────────────────────────────────────────────────────

    static MockHttpServletRequest as(String role, String cid) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        if (role == null) return r;
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("appClientId", cid); s.setAttribute("clientId", cid); s.setAttribute("username", "grace.pastor"); s.setAttribute("role", role);
        r.setSession(s);
        return r;
    }

    @Test @DisplayName("API: roles, 409 with the reasons, and the church always comes from the session")
    void api() {
        ReferenceDataController c = new ReferenceDataController(svc);
        assertThat(c.usage(SUB, 11, as(null, null)).getStatusCode().value()).isEqualTo(401);
        for (String role : new String[] { "User", "Member" }) {
            assertThat(c.usage(SUB, 11, as(role, A)).getStatusCode().value()).as(role).isEqualTo(403);
            assertThat(c.permanent(PURPOSE, 2, as(role, A)).getStatusCode().value()).as(role).isEqualTo(403);
            assertThat(c.restore(PURPOSE, 2, as(role, A)).getStatusCode().value()).as(role).isEqualTo(403);
            assertThat(c.deleted("fund", as(role, A)).getStatusCode().value()).as(role).isEqualTo(403);
        }
        ResponseEntity<Map<String, Object>> use = c.usage(SUB, 11, as("Accountant", A));
        assertThat(use.getBody()).containsEntry("inUse", true).containsEntry("canPermanentlyDelete", false);
        ResponseEntity<Map<String, Object>> no = c.permanent(SUB, 11, as("SuperAdmin", A));
        assertThat(no.getStatusCode().value()).isEqualTo(409);
        assertThat(no.getBody()).containsEntry("code", "IN_USE").containsKey("uses");
        assertThat(c.permanent(PURPOSE, 2, as("Admin", A)).getStatusCode().value()).isEqualTo(200);
        // church B's admin cannot reach church A's rows
        assertThat(c.permanent(TYPE, 2, as("Admin", B)).getStatusCode().value()).isEqualTo(400);
        assertThat(c.usage(SUB, 11, as("Admin", B)).getStatusCode().value()).isEqualTo(400);
        assertThat(rows("transaction_type")).isEqualTo(3);
        assertThat(c.deleted("nope", as("Admin", A)).getStatusCode().value()).isEqualTo(400);
    }

    @Test @DisplayName("wiring: migration guarded and mirrored, pages use the two-choice dialog, lists and filters mark deleted entries")
    void wiring() throws Exception {
        String v25 = Files.readString(Path.of("src/main/resources/db/migration/V25__reference_data_delete_tracking.sql"));
        assertThat(v25).contains("to_regclass").contains("ADD COLUMN IF NOT EXISTS deleted_at").contains("deleted_with_parent");
        assertThat(v25.toUpperCase()).doesNotContain("DROP ").doesNotContain("DELETE FROM").doesNotContain("UPDATE ");
        assertThat(Files.readString(Path.of("scripts/sql/migrate_production.sql"))).contains("Reference data delete tracking").contains("deleted_with_parent");
        Path st = Path.of("src/main/resources/static");
        for (String page : new String[] { "fund.html", "purpose.html", "transactionType.html" }) {
            String html = Files.readString(st.resolve(page));
            assertThat(html).as(page).contains("/ref-delete.js").contains("CGP_refDelete.ask(").contains("CGP_refDelete.mountDeleted(");
            assertThat(html).as(page + " no longer uses the browser confirm box for deletes").doesNotContain("if (!confirm('Are you sure you want to delete");
        }
        String js = Files.readString(st.resolve("ref-delete.js"));
        assertThat(js).contains("Delete (recommended)").contains("Permanently delete").contains("/permanent").contains("/restore").doesNotContain("innerHTML");
        assertThat(Files.readString(st.resolve("income.html"))).contains("delTag(t.subSourceDeleted").contains("delTag(t.memberDeleted").contains("delTag(t.methodDeleted").contains("keepDeletedOption('fFund'");
        assertThat(Files.readString(st.resolve("expense.html"))).contains("delTag(e.purposeDeleted").contains("delTag(e.mainSourceDeleted");
        assertThat(Files.readString(st.resolve("income-report.html"))).contains("includeDeleted=true").contains("(Deleted)");
        assertThat(Files.readString(st.resolve("expense-report.html"))).contains("includeDeleted=true").contains("(Deleted)");
        // new transactions can never pick a deleted entry: the create path only knows the active finders
        String inc = Files.readString(Path.of("src/main/java/com/churchgeniuspro/service/IncomeService.java"));
        assertThat(inc).contains("subSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(id, appClientId)")
                .contains("transactionTypeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(id, appClientId)");
    }
}
