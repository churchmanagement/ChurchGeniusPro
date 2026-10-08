package com.churchgeniuspro.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Backup retention and table-discovery rules.
 *
 * <p>Both of these have the same failure signature — they do the wrong thing silently and
 * you find out months later, either because two thirds of your tables were never in a
 * backup or because a snapshot you needed was deleted. So the rules are pinned directly
 * rather than inferred from end-to-end behaviour.
 */
class BackupRetentionTest {

    @Nested
    @DisplayName("Retention cutoff")
    class Cutoff {

        @Test
        @DisplayName("the worked example: 6-month retention on 2026-08-18 drops 20260217, keeps 20260218")
        void theRequirementsExample() {
            // Backup interval 1 month, retention 6 months, today 2026-08-18 → cutoff 2026-02-18.
            LocalDate cutoff = LocalDate.of(2026, 8, 18).minusMonths(6);
            assertEquals(LocalDate.of(2026, 2, 18), cutoff);

            List<String> expired = BackupService.expiredSuffixes(
                    Set.of("20260217", "20260218", "20260318", "20260818"), cutoff);

            assertEquals(List.of("20260217"), expired,
                    "20260217 is one day past the cutoff and must go; 20260218 is exactly on it and stays");
        }

        @Test
        @DisplayName("the boundary date itself is kept — 'older than' is strict")
        void boundaryIsInclusive() {
            LocalDate cutoff = LocalDate.of(2026, 2, 18);
            assertTrue(BackupService.expiredSuffixes(Set.of("20260218", "20260901"), cutoff).isEmpty());
        }

        @Test
        @DisplayName("expired snapshots come back oldest first")
        void orderedOldestFirst() {
            List<String> expired = BackupService.expiredSuffixes(
                    Set.of("20250101", "20240601", "20250301", "20260801"),
                    LocalDate.of(2026, 2, 18));
            assertEquals(List.of("20240601", "20250101", "20250301"), expired);
        }

        @Test
        @DisplayName("a month-length mismatch does not shift the cutoff (31 Aug minus 6 months)")
        void monthArithmeticIsCalendarCorrect() {
            // 31 August minus 6 months is 28 February, not 31 February.
            assertEquals(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 8, 31).minusMonths(6));
        }
    }

    @Nested
    @DisplayName("Safety rails — every table keeps its last backup")
    class Safety {

        @Test
        @DisplayName("the sole snapshot of a table is protected, however old it is")
        void lastRemainingCopyIsProtected() {
            // A 1-month retention with a 12-month backup interval. Read literally this
            // deletes the only backup and leaves nothing for eleven months.
            var byDate = Map.of("20250101", List.of("app_user_bkp_20250101", "family_bkp_20250101"));

            assertEquals(List.of("20250101"),
                    BackupService.expiredSuffixes(byDate.keySet(), LocalDate.of(2026, 8, 18)),
                    "it has genuinely expired...");

            var newest = BackupService.newestCopyPerTable(byDate);
            assertEquals("20250101", newest.get("app_user"), "...but it is still the only copy, so it stays");
            assertEquals("20250101", newest.get("family"));
        }

        @Test
        @DisplayName("with newer copies available, the old ones are droppable")
        void supersededCopiesAreDroppable() {
            var byDate = Map.of(
                    "20250101", List.of("app_user_bkp_20250101"),
                    "20260801", List.of("app_user_bkp_20260801"));

            var newest = BackupService.newestCopyPerTable(byDate);
            assertEquals("20260801", newest.get("app_user"),
                    "the 2025 copy is superseded and may be deleted");
        }

        @Test
        @DisplayName("a table missing from newer snapshots still keeps its own last copy")
        void protectionIsPerTableNotPerSnapshot() {
            // This is the case a per-SNAPSHOT rule gets wrong. 'legacy_table' was backed up
            // in January and does not appear in the August snapshot. Keeping only the newest
            // snapshot as a whole would drop January entirely and destroy the only backup
            // of legacy_table that exists.
            var byDate = Map.of(
                    "20250101", List.of("app_user_bkp_20250101", "legacy_table_bkp_20250101"),
                    "20260801", List.of("app_user_bkp_20260801"));

            var newest = BackupService.newestCopyPerTable(byDate);

            assertEquals("20260801", newest.get("app_user"),   "superseded — droppable");
            assertEquals("20250101", newest.get("legacy_table"),
                    "legacy_table has no newer backup, so its January copy must survive even "
                  + "though the rest of that snapshot is deleted");
        }

        @Test
        @DisplayName("the rule holds across many tables at once, not just the lucky ones")
        void appliesConsistentlyToAllTables() {
            var byDate = Map.of(
                    "20240101", List.of("a_bkp_20240101", "b_bkp_20240101", "c_bkp_20240101"),
                    "20250101", List.of("a_bkp_20250101", "b_bkp_20250101"),
                    "20260101", List.of("a_bkp_20260101"));

            var newest = BackupService.newestCopyPerTable(byDate);

            assertEquals("20260101", newest.get("a"));
            assertEquals("20250101", newest.get("b"), "b's newest copy is 2025, not 2026");
            assertEquals("20240101", newest.get("c"), "c only ever appears in 2024 — it must survive");
            assertEquals(3, newest.size(), "every table gets an answer; none is overlooked");
        }

        @Test
        @DisplayName("table names containing the source of another are not confused")
        void similarNamesAreNotConflated() {
            var byDate = Map.of(
                    "20250101", List.of("user_bkp_20250101", "app_user_bkp_20250101"),
                    "20260101", List.of("user_bkp_20260101"));

            var newest = BackupService.newestCopyPerTable(byDate);
            assertEquals("20260101", newest.get("user"));
            assertEquals("20250101", newest.get("app_user"),
                    "app_user must not inherit protection status from the unrelated 'user' table");
        }

        @Test
        @DisplayName("an impossible date in a table name is left strictly alone")
        void impossibleDatesAreIgnored() {
            List<String> expired = BackupService.expiredSuffixes(
                    Set.of("20261332", "00000000", "20250101"), LocalDate.of(2026, 8, 18));
            assertEquals(List.of("20250101"), expired,
                    "a name that looks like a snapshot but cannot be a date is not ours to drop");
        }

        @Test
        @DisplayName("no snapshots at all is not an error")
        void emptyInput() {
            assertTrue(BackupService.expiredSuffixes(Set.of(), LocalDate.now()).isEmpty());
            assertTrue(BackupService.newestCopyPerTable(Map.of()).isEmpty());
        }
    }

    @Nested
    @DisplayName("What counts as a snapshot table")
    class TableNaming {

        @Test
        @DisplayName("real snapshot names are recognised and split into source + date")
        void recognisesSnapshots() {
            var m = BackupService.BACKUP_TABLE_PATTERN.matcher("app_user_bkp_20260217");
            assertTrue(m.matches());
            assertEquals("app_user", m.group(1));
            assertEquals("20260217", m.group(2));

            assertTrue(BackupService.BACKUP_TABLE_PATTERN.matcher("login_attempt_log_bkp_20260818").matches());
        }

        @Test
        @DisplayName("ordinary tables are never mistaken for snapshots")
        void rejectsRealTables() {
            for (String name : List.of("app_user", "family_member", "backup_log",
                                       "app_user_bkp", "app_user_bkp_2026021",
                                       "app_user_bkp_202602179", "app_user_bkp_abcdefgh")) {
                assertFalse(BackupService.BACKUP_TABLE_PATTERN.matcher(name).matches(),
                        name + " must never be treated as a droppable snapshot");
            }
        }
    }

    @Nested
    @DisplayName("Backup scope")
    class Scope {

        @Test
        @DisplayName("the tables excluded from backup are excluded for a stated reason")
        void exclusionsAreDeliberate() {
            // Live session state — a snapshot is stale within minutes and restoring one
            // would resurrect expired logins.
            assertTrue(BackupService.EXCLUDED_TABLES.contains("spring_session"));
            assertTrue(BackupService.EXCLUDED_TABLES.contains("spring_session_attributes"));

            // The backup system's own bookkeeping: restoring these would roll back the
            // settings the administrator is using to perform the restore.
            assertTrue(BackupService.EXCLUDED_TABLES.contains("backup_config"));
            assertTrue(BackupService.EXCLUDED_TABLES.contains("backup_log"));
        }

        @Test
        @DisplayName("ordinary business tables are NOT excluded — that was the original bug")
        void businessTablesAreIncluded() {
            // The old hand-maintained list covered 43 tables and silently missed everything
            // added afterwards. Nothing here may ever be excluded by accident.
            for (String table : List.of("app_user", "family", "family_member", "income", "expense",
                                        "church_event", "payroll_employee", "plaid_item",
                                        "login_attempt_log", "song_book", "connect_submission",
                                        "public_page_visit", "subscription_plan")) {
                assertFalse(BackupService.EXCLUDED_TABLES.contains(table),
                        table + " must be included in an ALL-scope backup");
            }
        }

        @Test
        @DisplayName("the curated subsets are unchanged")
        void curatedSubsetsStillExist() {
            assertTrue(BackupService.FAMILY_TABLES.containsAll(List.of("family", "family_member")));
            assertTrue(BackupService.INCOME_TABLES.containsAll(List.of("income", "expense")));
        }
    }
}
