package com.churchgeniuspro.service;

import com.churchgeniuspro.service.SnapshotRestorePlanner.Column;
import com.churchgeniuspro.service.SnapshotRestorePlanner.ForeignKey;
import com.churchgeniuspro.service.SnapshotRestorePlanner.Plan;
import com.churchgeniuspro.service.SnapshotRestorePlanner.Step;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database audit C2: the restore plan. The previous restore emptied {@code income},
 * {@code expense} and {@code meeting} — {@code TRUNCATE … CASCADE} on a parent restored
 * after its child — and reported SUCCESS. Every rule that prevents that is pinned here,
 * without a database; {@code BackupRestoreIT} runs the same shapes against PostgreSQL.
 */
@DisplayName("SnapshotRestorePlanner — order, columns and blockers of a snapshot restore (DB audit C2)")
class SnapshotRestorePlannerTest {

    /** The real foreign keys among the ten ledger/people/meeting tables (12 in production, these 10 among them). */
    static final List<ForeignKey> LEDGER_FKS = List.of(
            new ForeignKey("fk_sub_source_main", "sub_source", "main_source"),
            new ForeignKey("fk_income_member", "income", "family_member"),
            new ForeignKey("fk_income_sub_source", "income", "sub_source"),
            new ForeignKey("fk_income_txn_type", "income", "transaction_type"),
            new ForeignKey("fk_expense_main", "expense", "main_source"),
            new ForeignKey("fk_expense_purpose", "expense", "purpose"),
            new ForeignKey("fk_expense_txn_type", "expense", "transaction_type"),
            new ForeignKey("fk_family_member_family", "family_member", "family"),
            new ForeignKey("fk_meeting_type", "meeting", "meeting_type"));

    static final List<String> LEDGER_TABLES = List.of("main_source", "sub_source", "transaction_type", "purpose",
            "family", "family_member", "income", "expense", "meeting_type", "meeting");

    private static Column col(String name) { return new Column(name, true, false); }

    private static Column notNull(String name) { return new Column(name, false, false); }

    /** A table and its snapshot with identical columns. */
    private static void sameShape(Map<String, List<Column>> columns, String table, String snapshot, Column... cols) {
        columns.put(table, List.of(cols));
        columns.put(snapshot, List.of(cols));
    }

    private static Map<String, String> snapshots(String date, List<String> tables) {
        Map<String, String> m = new TreeMap<>();
        tables.forEach(t -> m.put(t, t + "_bkp_" + date));
        return m;
    }

    private static Map<String, List<Column>> ledgerColumns(String date) {
        Map<String, List<Column>> columns = new TreeMap<>();
        for (String t : LEDGER_TABLES) {
            sameShape(columns, t, t + "_bkp_" + date, notNull("id"), col("name"), col("app_client_id"));
        }
        return columns;
    }

    private static int indexOf(Plan plan, String table) {
        List<String> order = plan.tables();
        assertThat(order).as("%s is in the plan", table).contains(table);
        return order.indexOf(table);
    }

    @Nested
    @DisplayName("order")
    class Order {

        @Test
        @DisplayName("the C2 shape: all ten tables are restored, every parent before every child")
        void parentsBeforeChildren() {
            Plan plan = SnapshotRestorePlanner.plan(snapshots("20260101", LEDGER_TABLES),
                    ledgerColumns("20260101"), LEDGER_FKS, new TreeSet<>(LEDGER_TABLES));

            assertThat(plan.executable()).as("blockers: %s", plan.blockers()).isTrue();
            assertThat(plan.tables()).containsExactlyInAnyOrderElementsOf(LEDGER_TABLES);
            for (ForeignKey fk : LEDGER_FKS) {
                assertThat(indexOf(plan, fk.parentTable()))
                        .as("%s (parent) before %s (child)", fk.parentTable(), fk.childTable())
                        .isLessThan(indexOf(plan, fk.childTable()));
            }
            // the exact failure of the old code: catalogue order put main_source after income
            assertThat(indexOf(plan, "main_source")).isLessThan(indexOf(plan, "income"));
            assertThat(indexOf(plan, "meeting_type")).isLessThan(indexOf(plan, "meeting"));
            assertThat(indexOf(plan, "transaction_type")).isLessThan(indexOf(plan, "expense"));
        }

        @Test
        @DisplayName("tables with no dependency between them come out alphabetically, so the plan is deterministic")
        void alphabeticalAmongPeers() {
            List<String> tables = List.of("zeta", "alpha", "mid");
            Map<String, List<Column>> columns = new TreeMap<>();
            for (String t : tables) sameShape(columns, t, t + "_bkp_20260101", notNull("id"));

            Plan plan = SnapshotRestorePlanner.plan(snapshots("20260101", tables), columns, List.of(), Set.copyOf(tables));

            assertThat(plan.tables()).containsExactly("alpha", "mid", "zeta");
        }

        @Test
        @DisplayName("a self-referencing foreign key neither blocks nor reorders anything")
        void selfReferenceIsHarmless() {
            Map<String, List<Column>> columns = new TreeMap<>();
            sameShape(columns, "app_group", "app_group_bkp_20260101", notNull("id"), col("parent_id"));

            Plan plan = SnapshotRestorePlanner.plan(snapshots("20260101", List.of("app_group")), columns,
                    List.of(new ForeignKey("fk_group_parent", "app_group", "app_group")), Set.of("app_group"));

            assertThat(plan.executable()).isTrue();
            assertThat(plan.tables()).containsExactly("app_group");
        }

        @Test
        @DisplayName("circular foreign keys cannot be ordered and are a blocker, not a guess")
        void cycleIsABlocker() {
            List<String> tables = List.of("a", "b");
            Map<String, List<Column>> columns = new TreeMap<>();
            for (String t : tables) sameShape(columns, t, t + "_bkp_20260101", notNull("id"));

            Plan plan = SnapshotRestorePlanner.plan(snapshots("20260101", tables), columns,
                    List.of(new ForeignKey("fk_a_b", "a", "b"), new ForeignKey("fk_b_a", "b", "a")), Set.copyOf(tables));

            assertThat(plan.executable()).isFalse();
            assertThat(plan.blockers()).singleElement().asString().contains("circular").contains("a, b");
        }
    }

    @Nested
    @DisplayName("the CASCADE trap")
    class Cascade {

        @Test
        @DisplayName("a parent whose child has no snapshot for the date is a blocker — the old code would have emptied the child")
        void parentWithoutItsChildIsRefused() {
            // Only main_source was snapshotted; sub_source, income and expense reference it.
            Map<String, List<Column>> columns = new TreeMap<>();
            sameShape(columns, "main_source", "main_source_bkp_20260101", notNull("id"), col("name"));

            Plan plan = SnapshotRestorePlanner.plan(snapshots("20260101", List.of("main_source")), columns, LEDGER_FKS,
                    new TreeSet<>(LEDGER_TABLES));

            assertThat(plan.executable()).isFalse();
            assertThat(plan.blockers()).hasSize(2);
            assertThat(String.join("\n", plan.blockers()))
                    .contains("cannot empty main_source")
                    .contains("sub_source references it")
                    .contains("expense references it")
                    .contains("would also empty");
        }

        @Test
        @DisplayName("a child restored without its parents is fine — the parents are simply left as they are")
        void childWithoutParentIsAllowed() {
            Map<String, List<Column>> columns = new TreeMap<>();
            sameShape(columns, "income", "income_bkp_20260101", notNull("id"), col("sub_source_id"), col("member_id"));

            Plan plan = SnapshotRestorePlanner.plan(snapshots("20260101", List.of("income")), columns, LEDGER_FKS,
                    new TreeSet<>(LEDGER_TABLES));

            assertThat(plan.executable()).as(plan.blockers().toString()).isTrue();
            assertThat(plan.tables()).containsExactly("income");
            assertThat(String.join("\n", plan.warnings())).contains("no snapshot for this date and are left as they are");
        }

        @Test
        @DisplayName("…but a table in the middle of the chain (sub_source: child of main_source, parent of income) needs its child too")
        void middleOfChainNeedsItsChild() {
            Map<String, List<Column>> columns = new TreeMap<>();
            sameShape(columns, "sub_source", "sub_source_bkp_20260101", notNull("id"), col("main_source_id"));

            Plan plan = SnapshotRestorePlanner.plan(snapshots("20260101", List.of("sub_source")), columns, LEDGER_FKS,
                    new TreeSet<>(LEDGER_TABLES));

            assertThat(plan.executable()).isFalse();
            assertThat(plan.blockers()).singleElement().asString()
                    .contains("cannot empty sub_source: income references it");
        }
    }

    @Nested
    @DisplayName("columns by name (39 of production's 172 snapshots no longer match their table)")
    class Columns {

        @Test
        @DisplayName("only the columns the snapshot and the live table share are copied, in the live table's order")
        void intersectionInLiveOrder() {
            Map<String, List<Column>> columns = new TreeMap<>();
            columns.put("income", List.of(notNull("id"), col("amount"), col("import_ref"), col("app_client_id")));
            columns.put("income_bkp_20260729", List.of(notNull("id"), col("app_client_id"), col("amount"), col("method")));

            Plan plan = SnapshotRestorePlanner.plan(snapshots("20260729", List.of("income")), columns, List.of(), Set.of("income"));

            assertThat(plan.executable()).isTrue();
            Step step = plan.steps().get(0);
            assertThat(step.columns()).containsExactly("id", "amount", "app_client_id");
            assertThat(String.join("\n", plan.warnings()))
                    .contains("income: column(s) added since the snapshot are left at their default — import_ref")
                    .contains("income: column(s) dropped since the snapshot are not restored — method");
        }

        @Test
        @DisplayName("a live NOT NULL column with no default that the snapshot lacks cannot be filled — blocker")
        void notNullWithoutDefaultMissingFromSnapshotBlocks() {
            Map<String, List<Column>> columns = new TreeMap<>();
            columns.put("backup_config", List.of(notNull("id"), notNull("retention_months")));
            columns.put("backup_config_bkp_20260101", List.of(notNull("id")));

            Plan plan = SnapshotRestorePlanner.plan(snapshots("20260101", List.of("backup_config")), columns, List.of(),
                    Set.of("backup_config"));

            assertThat(plan.executable()).isFalse();
            assertThat(plan.blockers()).singleElement().asString()
                    .contains("backup_config.retention_months is NOT NULL with no default");
        }

        @Test
        @DisplayName("a NOT NULL column WITH a default that the snapshot lacks is only a note")
        void notNullWithDefaultIsAWarning() {
            Map<String, List<Column>> columns = new TreeMap<>();
            columns.put("family_member", List.of(notNull("id"), new Column("disable_alerts", false, true)));
            columns.put("family_member_bkp_20260101", List.of(notNull("id")));

            Plan plan = SnapshotRestorePlanner.plan(snapshots("20260101", List.of("family_member")), columns, List.of(),
                    Set.of("family_member"));

            assertThat(plan.executable()).isTrue();
            assertThat(plan.steps().get(0).columns()).containsExactly("id");
        }

        @Test
        @DisplayName("a generated column is never in the copy list — the database computes it")
        void generatedColumnsAreSkipped() {
            Map<String, List<Column>> columns = new TreeMap<>();
            columns.put("t", List.of(notNull("id"), new Column("total", false, false, true)));
            columns.put("t_bkp_20260101", List.of(notNull("id"), new Column("total", false, false, true)));

            Plan plan = SnapshotRestorePlanner.plan(snapshots("20260101", List.of("t")), columns, List.of(), Set.of("t"));

            assertThat(plan.executable()).isTrue();
            assertThat(plan.steps().get(0).columns()).containsExactly("id");
        }

        @Test
        @DisplayName("a snapshot of a table that no longer exists is skipped with a note, not a failure")
        void droppedTableIsSkipped() {
            Map<String, List<Column>> columns = new TreeMap<>();
            columns.put("credit_card_bkp_20260101", List.of(notNull("id")));
            sameShape(columns, "family", "family_bkp_20260101", notNull("id"));

            Plan plan = SnapshotRestorePlanner.plan(snapshots("20260101", List.of("credit_card", "family")), columns,
                    List.of(), Set.of("family"));

            assertThat(plan.executable()).isTrue();
            assertThat(plan.tables()).containsExactly("family");
            assertThat(String.join("\n", plan.warnings())).contains("credit_card: no longer exists");
        }
    }

    @Test
    @DisplayName("the plan reports which live tables the date has no snapshot for (the pre-August 43-table snapshots)")
    void untouchedTablesAreListed() {
        Map<String, List<Column>> columns = new TreeMap<>();
        sameShape(columns, "family", "family_bkp_20260729", notNull("id"));
        Set<String> live = new TreeSet<>(List.of("family", "payroll_run", "plaid_item", "spring_session", "backup_config"));

        Plan plan = SnapshotRestorePlanner.plan(snapshots("20260729", List.of("family")), columns, List.of(), live);

        List<String> notes = new ArrayList<>(plan.warnings());
        assertThat(notes).singleElement().asString()
                .startsWith("2 live table(s) have no snapshot for this date")
                .contains("payroll_run, plaid_item")
                .doesNotContain("spring_session")      // never restored anyway
                .doesNotContain("backup_config");
    }
}
