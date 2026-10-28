package com.churchgeniuspro.logging;

import com.churchgeniuspro.service.AuditTableRetentionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nightly audit-table retention (2026-10-28 logging audit, item 8) on an in-memory
 * PostgreSQL-mode H2: old rows go, young rows stay, a reminder-log row for an upcoming
 * event is kept however old, a 0-day rule keeps everything, a missing table never
 * stops the other tables, and batches continue until the table is clean.
 */
@DisplayName("AuditTableRetentionService — nightly purge of the audit / log tables")
class AuditTableRetentionServiceTest {

    JdbcTemplate jdbc;

    @BeforeEach
    void db() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:auditret;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE plaid_webhook_event (id INT PRIMARY KEY, payload TEXT, received_date TIMESTAMP)");
        jdbc.execute("CREATE TABLE help_audit_log (id INT PRIMARY KEY, query TEXT, created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE vision_usage_log (id INT PRIMARY KEY, created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE plaid_audit_log (id INT PRIMARY KEY, created_date TIMESTAMP)");
        jdbc.execute("CREATE TABLE payroll_audit_log (id INT PRIMARY KEY, created TIMESTAMP)");
        jdbc.execute("CREATE TABLE private_access_log (id INT PRIMARY KEY, created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE ntag_login_history (id INT PRIMARY KEY, login_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE church_event (id INT PRIMARY KEY, event_date DATE)");
        jdbc.execute("CREATE TABLE event_registration_reminder_log (id INT PRIMARY KEY, event_id INT, recipient VARCHAR(200), created_date TIMESTAMP)");
    }

    @AfterEach
    void drop() { jdbc.execute("DROP ALL OBJECTS"); }

    private AuditTableRetentionService service(int shortDays, int longDays) {
        return new AuditTableRetentionService(jdbc, true, shortDays, shortDays, shortDays, shortDays, longDays, longDays, longDays, longDays);
    }

    private static Timestamp daysAgo(int d) { return Timestamp.valueOf(LocalDateTime.now().minusDays(d)); }

    @Test
    @DisplayName("rows past their table's retention are removed, younger rows and 0-day tables are kept")
    void retentionPerTable() {
        jdbc.update("INSERT INTO plaid_webhook_event VALUES (1, '{\"item_id\":\"x\"}', ?)", daysAgo(100));
        jdbc.update("INSERT INTO plaid_webhook_event VALUES (2, '{}', ?)", daysAgo(10));
        jdbc.update("INSERT INTO help_audit_log VALUES (1, 'how much did Ada give', ?)", daysAgo(91));
        jdbc.update("INSERT INTO help_audit_log VALUES (2, 'q', ?)", daysAgo(89));
        jdbc.update("INSERT INTO plaid_audit_log VALUES (1, ?)", daysAgo(400));
        jdbc.update("INSERT INTO plaid_audit_log VALUES (2, ?)", daysAgo(200));   // < 365: kept
        jdbc.update("INSERT INTO payroll_audit_log VALUES (1, ?)", daysAgo(366));
        jdbc.update("INSERT INTO private_access_log VALUES (1, ?)", daysAgo(366));
        jdbc.update("INSERT INTO ntag_login_history VALUES (1, ?)", daysAgo(366));
        jdbc.update("INSERT INTO vision_usage_log VALUES (1, ?)", daysAgo(91));

        Map<String, Integer> out = service(90, 365).purgeNow();

        assertThat(out).containsEntry("plaid_webhook_event", 1).containsEntry("help_audit_log", 1)
                       .containsEntry("plaid_audit_log", 1).containsEntry("payroll_audit_log", 1)
                       .containsEntry("private_access_log", 1).containsEntry("ntag_login_history", 1)
                       .containsEntry("vision_usage_log", 1);
        assertThat(jdbc.queryForList("SELECT id FROM plaid_webhook_event", Integer.class)).containsExactly(2);
        assertThat(jdbc.queryForList("SELECT id FROM help_audit_log", Integer.class)).containsExactly(2);
        assertThat(jdbc.queryForList("SELECT id FROM plaid_audit_log", Integer.class)).containsExactly(2);

        // 0 days = that table is never touched
        jdbc.update("INSERT INTO plaid_webhook_event VALUES (3, '{}', ?)", daysAgo(1000));
        Map<String, Integer> off = new AuditTableRetentionService(jdbc, true, 0, 90, 90, 90, 365, 365, 365, 365).purgeNow();
        assertThat(off).containsEntry("plaid_webhook_event", 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plaid_webhook_event", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("reminder-log rows are kept while their event is still upcoming — they are the once-only invitation guard")
    void reminderLogKeepsUpcomingEvents() {
        jdbc.update("INSERT INTO church_event VALUES (1, ?)", LocalDate.now().plusDays(30));   // upcoming
        jdbc.update("INSERT INTO church_event VALUES (2, ?)", LocalDate.now().minusDays(5));   // over
        jdbc.update("INSERT INTO event_registration_reminder_log VALUES (1, 1, '+19135550100', ?)", daysAgo(200));  // old but event upcoming
        jdbc.update("INSERT INTO event_registration_reminder_log VALUES (2, 2, 'a@b.c', ?)", daysAgo(200));         // old, event over
        jdbc.update("INSERT INTO event_registration_reminder_log VALUES (3, 999, 'x', ?)", daysAgo(200));           // old, event deleted
        jdbc.update("INSERT INTO event_registration_reminder_log VALUES (4, 2, 'y', ?)", daysAgo(10));              // young

        Map<String, Integer> out = service(90, 365).purgeNow();

        assertThat(out).containsEntry("event_registration_reminder_log", 2);
        assertThat(jdbc.queryForList("SELECT id FROM event_registration_reminder_log ORDER BY id", Integer.class)).containsExactly(1, 4);
    }

    @Test
    @DisplayName("a missing table is skipped (-1) and the other tables are still purged; disabled job does nothing")
    void missingTableAndDisabled() {
        jdbc.execute("DROP TABLE vision_usage_log");
        jdbc.update("INSERT INTO help_audit_log VALUES (1, 'q', ?)", daysAgo(500));
        Map<String, Integer> out = service(90, 365).purgeNow();
        assertThat(out).containsEntry("vision_usage_log", -1).containsEntry("help_audit_log", 1);

        jdbc.update("INSERT INTO help_audit_log VALUES (2, 'q', ?)", daysAgo(500));
        AuditTableRetentionService disabled = new AuditTableRetentionService(jdbc, false, 90, 90, 90, 90, 365, 365, 365, 365);
        disabled.purgeExpiredRows();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM help_audit_log", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("more rows than one batch are removed in several statements")
    void batches() {
        jdbc.batchUpdate("INSERT INTO ntag_login_history VALUES (?, ?)",
                java.util.stream.IntStream.range(0, AuditTableRetentionService.BATCH * 2 + 7)
                        .mapToObj(i -> new Object[]{i, daysAgo(400)}).toList());
        assertThat(service(90, 365).purgeNow()).containsEntry("ntag_login_history", AuditTableRetentionService.BATCH * 2 + 7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ntag_login_history", Integer.class)).isZero();
        assertThat(service(90, 365).describe()).contains("ntag_login_history=365d", "plaid_webhook_event=90d");
    }
}
