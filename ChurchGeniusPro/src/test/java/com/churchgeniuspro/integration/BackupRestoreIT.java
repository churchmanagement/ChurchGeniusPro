package com.churchgeniuspro.integration;

import com.churchgeniuspro.service.BackupService;
import com.churchgeniuspro.service.IdSequenceCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database audit C2, against the real schema on a real PostgreSQL: the ten ledger, people
 * and meeting tables with their real foreign keys, snapshotted the way {@code runBackup}
 * snapshots them and restored through {@link BackupService#restoreSnapshot}.
 *
 * <p>The first test is the exact simulation that found the defect — with the old code it
 * ended with {@code income}, {@code expense} and {@code meeting} empty and a SUCCESS
 * status. Requires Docker; runs on {@code verify}.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BackupRestoreIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static final String DATE = "20260101";

    /** Parents first — the order a correct restore must discover for itself. */
    static final List<String> TABLES = List.of("main_source", "sub_source", "transaction_type", "purpose",
            "family", "family_member", "income", "expense", "meeting_type", "meeting");

    /** The INCOME backup scope, as BackupService defines it. */
    static final List<String> INCOME_SCOPE = List.of("income", "expense", "transaction_type", "main_source", "sub_source", "purpose");

    @Autowired BackupService backupService;
    @Autowired IdSequenceCatalog sequences;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanSlate() {
        for (String t : TABLES) jdbc.execute("DROP TABLE IF EXISTS " + t + "_bkp_" + DATE);
        jdbc.execute("ALTER TABLE income DROP COLUMN IF EXISTS drift_added");
        deleteAll();
    }

    private void deleteAll() {
        for (int i = TABLES.size() - 1; i >= 0; i--) jdbc.execute("DELETE FROM " + TABLES.get(i));
    }

    private void seedOneRowEach() {
        for (String t : TABLES) MinimalRows.insert(jdbc, t);
    }

    private void snapshot(List<String> tables) {
        for (String t : tables) jdbc.execute("CREATE TABLE " + t + "_bkp_" + DATE + " AS SELECT * FROM " + t);
    }

    private long count(String table) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return n == null ? -1 : n;
    }

    @Test
    @DisplayName("the C2 simulation: every table comes back — including income, expense and meeting")
    void everyTableComesBack() {
        seedOneRowEach();
        snapshot(TABLES);
        deleteAll();
        for (String t : TABLES) assertThat(count(t)).as("%s emptied before restore", t).isZero();

        Map<String, Object> result = backupService.restoreSnapshot(DATE);

        assertThat(result.get("status")).as(String.valueOf(result.get("message"))).isEqualTo("SUCCESS");
        assertThat(result.get("restored")).isEqualTo(TABLES.size());
        for (String t : TABLES) assertThat(count(t)).as("%s after restore", t).isEqualTo(1L);

        // and the next insert does not collide with a restored id: the sequence was re-synced
        String familySeq = sequences.byTable().get("family").get("id");
        assertThat(familySeq).isEqualTo("family_id_seq");
        Long next = jdbc.queryForObject("SELECT nextval('" + familySeq + "')", Long.class);
        assertThat(next).isEqualTo(2L);
    }

    @Test
    @DisplayName("a parent snapshotted without its children is refused, and nothing is touched")
    void parentWithoutChildrenIsRefused() {
        seedOneRowEach();
        snapshot(List.of("main_source"));

        Map<String, Object> result = backupService.restoreSnapshot(DATE);

        assertThat(result.get("status")).isEqualTo("FAILURE");
        assertThat(String.valueOf(result.get("message")))
                .contains("nothing was changed")
                .contains("cannot empty main_source")
                .contains("sub_source references it");
        for (String t : TABLES) assertThat(count(t)).as("%s untouched", t).isEqualTo(1L);
    }

    @Test
    @DisplayName("columns are copied by name, so a snapshot older than the schema still restores (39 of production's snapshots)")
    void schemaDriftBetweenSnapshotAndTableIsHandled() {
        seedOneRowEach();
        snapshot(TABLES);
        jdbc.execute("ALTER TABLE income ADD COLUMN drift_added text");                     // live gained a column
        jdbc.execute("ALTER TABLE income_bkp_" + DATE + " ADD COLUMN drift_dropped text");  // snapshot has one live lost
        deleteAll();

        Map<String, Object> result = backupService.restoreSnapshot(DATE);

        assertThat(result.get("status")).as(String.valueOf(result.get("message"))).isEqualTo("SUCCESS");
        assertThat(count("income")).isEqualTo(1L);
        assertThat(String.valueOf(result.get("message")))
                .contains("income: column(s) added since the snapshot are left at their default — drift_added")
                .contains("income: column(s) dropped since the snapshot are not restored — drift_dropped");
    }

    @Test
    @DisplayName("one failing insert rolls the whole restore back — no table is left truncated")
    void failureRollsEverythingBack() {
        seedOneRowEach();
        snapshot(TABLES);
        // Sabotage the income snapshot: a member that will not exist after the restore.
        jdbc.execute("UPDATE income_bkp_" + DATE + " SET member_id = 999");
        // And give the live database something the old code would have destroyed on the way.
        MinimalRows.insert(jdbc, "main_source", Map.of("id", "2"));
        assertThat(count("main_source")).isEqualTo(2L);

        Map<String, Object> result = backupService.restoreSnapshot(DATE);

        assertThat(result.get("status")).isEqualTo("FAILURE");
        assertThat(String.valueOf(result.get("message"))).contains("rolled back").contains("nothing was changed");
        assertThat(count("main_source")).as("main_source was not truncated").isEqualTo(2L);
        assertThat(count("income")).as("income was not truncated").isEqualTo(1L);
    }

    @Test
    @DisplayName("a backup run copies every table at one instant and the copies can be restored straight back")
    void runBackupThenRestoreRoundTrip() {
        seedOneRowEach();
        var cfg = backupService.getConfig();
        cfg.setTableScope("INCOME");
        var log = backupService.runBackup(cfg);
        assertThat(log.getStatus()).isEqualTo("SUCCESS");
        String today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        try {
            for (String t : INCOME_SCOPE) {
                assertThat(count(t + "_bkp_" + today)).as("%s snapshot", t).isEqualTo(1L);
            }
            jdbc.execute("DELETE FROM income");
            jdbc.execute("DELETE FROM expense");

            Map<String, Object> result = backupService.restoreSnapshot(today);

            assertThat(result.get("status")).as(String.valueOf(result.get("message"))).isEqualTo("SUCCESS");
            assertThat(count("income")).isEqualTo(1L);
            assertThat(count("expense")).isEqualTo(1L);
        } finally {
            for (String t : INCOME_SCOPE) jdbc.execute("DROP TABLE IF EXISTS " + t + "_bkp_" + today);
        }
    }
}
