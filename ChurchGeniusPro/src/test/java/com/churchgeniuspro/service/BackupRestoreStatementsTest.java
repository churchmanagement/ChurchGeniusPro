package com.churchgeniuspro.service;

import com.churchgeniuspro.service.SnapshotRestorePlanner.Plan;
import com.churchgeniuspro.service.SnapshotRestorePlanner.Step;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Database audit C2: the SQL a restore plan turns into. The statements are what stands
 * between the plan and the data, so their exact shape is pinned: one TRUNCATE of every
 * table with no CASCADE, INSERTs that name their columns, and a setval per id sequence.
 */
@DisplayName("BackupService.restoreStatements — the SQL of a planned restore (DB audit C2)")
class BackupRestoreStatementsTest {

    private static final Plan PLAN = new Plan(List.of(
            new Step("main_source", "main_source_bkp_20260101", List.of("id", "name")),
            new Step("sub_source", "sub_source_bkp_20260101", List.of("id", "main_source_id", "name")),
            new Step("income", "income_bkp_20260101", List.of("id", "sub_source_id", "amount"))),
            List.of(), List.of());

    @Test
    @DisplayName("every planned table is truncated in ONE statement, never with CASCADE")
    void singleTruncateWithoutCascade() {
        List<String> sql = BackupService.restoreStatements(PLAN, Map.of(), Map.of());

        assertThat(sql.get(0)).isEqualTo("TRUNCATE TABLE \"main_source\", \"sub_source\", \"income\"");
        assertThat(sql).noneMatch(s -> s.contains("CASCADE"));
        assertThat(sql.stream().filter(s -> s.startsWith("TRUNCATE")).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("inserts follow in plan order and name their columns on both sides — never SELECT *")
    void insertsNameColumnsInPlanOrder() {
        List<String> sql = BackupService.restoreStatements(PLAN, Map.of(), Map.of());

        assertThat(sql.get(1)).isEqualTo("INSERT INTO \"main_source\" (\"id\", \"name\") OVERRIDING SYSTEM VALUE "
                + "SELECT \"id\", \"name\" FROM \"main_source_bkp_20260101\"");
        assertThat(sql.get(2)).startsWith("INSERT INTO \"sub_source\" (\"id\", \"main_source_id\", \"name\")");
        assertThat(sql.get(3)).startsWith("INSERT INTO \"income\" (\"id\", \"sub_source_id\", \"amount\")");
        assertThat(sql).noneMatch(s -> s.contains("SELECT *"));
    }

    @Test
    @DisplayName("each restored table's id sequence — catalogue-owned or @SequenceGenerator — is set to MAX(id)+1")
    void sequencesAreResynced() {
        Map<String, Map<String, String>> owned = Map.of("income", Map.of("id", "income_id_seq"));
        Map<String, Map<String, String>> mapped = Map.of(
                "main_source", Map.of("id", "main_source_id_seq"),
                "plaid_transaction_staging", Map.of("id", "plaid_txn_staging_id_seq"));   // not in the plan

        List<String> sql = BackupService.restoreStatements(PLAN, owned, mapped);

        assertThat(sql).contains(
                "SELECT setval('\"main_source_id_seq\"', COALESCE((SELECT MAX(\"id\") FROM \"main_source\"), 0) + 1, false)",
                "SELECT setval('\"income_id_seq\"', COALESCE((SELECT MAX(\"id\") FROM \"income\"), 0) + 1, false)");
        assertThat(sql).noneMatch(s -> s.contains("plaid_txn_staging_id_seq"));
        // the setvals come after every insert
        int lastInsert = -1, firstSetval = Integer.MAX_VALUE;
        for (int i = 0; i < sql.size(); i++) {
            if (sql.get(i).startsWith("INSERT")) lastInsert = i;
            if (sql.get(i).startsWith("SELECT setval")) firstSetval = Math.min(firstSetval, i);
        }
        assertThat(firstSetval).isGreaterThan(lastInsert);
    }

    @Test
    @DisplayName("a catalogue name that is not a plain identifier is refused rather than interpolated")
    void oddIdentifiersAreRefused() {
        assertThatThrownBy(() -> BackupService.quote("income; DROP TABLE income"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BackupService.quote("in\"come")).isInstanceOf(IllegalArgumentException.class);
        assertThat(BackupService.quote("income_bkp_20260101")).isEqualTo("\"income_bkp_20260101\"");
    }
}
