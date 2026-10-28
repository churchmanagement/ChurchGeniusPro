package com.churchgeniuspro.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nightly retention for the database audit / log tables that had none (2026-10-28 logging
 * audit, item 8). Until this existed these tables grew forever, were copied into every
 * backup snapshot, and held personal data — raw Plaid webhook payloads, AI Help questions,
 * reminder recipients' numbers and addresses, employee names with pay amounts, IPs.
 *
 * <p>Rules:
 * <ul>
 *   <li>Each table has its own retention (days); {@code 0} disables that table; the whole job
 *       can be switched off with {@code audit.retention.enabled=false}.</li>
 *   <li>{@code event_registration_reminder_log} is also the once-only guard for invitations
 *       and reminders, so a row is removed only when its event is already in the past (or the
 *       event no longer exists) — an old row for a still-upcoming event is kept whatever its
 *       age, so nobody is invited twice.</li>
 *   <li>Deletes run in batches of {@link #BATCH} rows per statement so a first run over years
 *       of data never holds a long lock; each table is isolated in its own try/catch and the
 *       job never throws.</li>
 *   <li>Only counts are logged — never a row.</li>
 * </ul>
 * The 30-day purges for {@code login_attempt_log} / {@code login_block}
 * ({@link LoginProtectionService}) and the Plaid staging rows ({@code PlaidScheduledService})
 * are unchanged and stay where they are. {@code reminder_sent_log} is deliberately NOT here:
 * it is a dedupe key store for every scheduler and holds no personal data.
 */
@Service
public class AuditTableRetentionService {

    private static final Logger LOG = LoggerFactory.getLogger(AuditTableRetentionService.class);
    public static final int BATCH = 5000;

    /** One table: name, timestamp column, retention days, optional extra WHERE (must reference the alias {@code t}). */
    record Rule(String table, String column, int days, String extraWhere) {}

    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final List<Rule> rules;

    public AuditTableRetentionService(
            JdbcTemplate jdbc,
            @Value("${audit.retention.enabled:true}") boolean enabled,
            @Value("${audit.retention.plaid-webhook-event-days:90}") int plaidWebhookDays,
            @Value("${audit.retention.help-audit-log-days:90}") int helpAuditDays,
            @Value("${audit.retention.event-registration-reminder-log-days:90}") int reminderLogDays,
            @Value("${audit.retention.vision-usage-log-days:90}") int visionUsageDays,
            @Value("${audit.retention.plaid-audit-log-days:365}") int plaidAuditDays,
            @Value("${audit.retention.payroll-audit-log-days:365}") int payrollAuditDays,
            @Value("${audit.retention.private-access-log-days:365}") int privateAccessDays,
            @Value("${audit.retention.ntag-login-history-days:365}") int ntagHistoryDays) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.rules = List.of(
            new Rule("plaid_webhook_event",             "received_date", plaidWebhookDays, null),
            new Rule("help_audit_log",                  "created_at",    helpAuditDays,    null),
            new Rule("event_registration_reminder_log", "created_date",  reminderLogDays,
                     // only once the event is over (or gone): the row is the once-only guard
                     "AND NOT EXISTS (SELECT 1 FROM church_event e WHERE e.id = t.event_id AND e.event_date >= CURRENT_DATE)"),
            new Rule("vision_usage_log",                "created_at",    visionUsageDays,  null),
            new Rule("plaid_audit_log",                 "created_date",  plaidAuditDays,   null),
            new Rule("payroll_audit_log",               "created",       payrollAuditDays, null),
            new Rule("private_access_log",              "created_at",    privateAccessDays, null),
            new Rule("ntag_login_history",              "login_time",    ntagHistoryDays,  null));
    }

    /** 03:50 daily — after the login-log (03:20), Plaid staging (03:30) and log-file (03:40) sweeps. */
    @Scheduled(cron = "${audit.retention.purge-cron:0 50 3 * * *}")
    public void purgeExpiredRows() {
        if (!enabled) { LOG.debug("[AuditRetention] disabled"); return; }
        Map<String, Integer> deleted = purgeNow();
        LOG.info("[AuditRetention] nightly purge done: {}", deleted);
    }

    /** Runs every rule once; returns rows deleted per table (-1 = that table failed; see the WARN). */
    public Map<String, Integer> purgeNow() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Rule r : rules) {
            if (r.days() <= 0) { out.put(r.table(), 0); continue; }
            try {
                out.put(r.table(), purgeTable(r));
            } catch (Exception e) {
                // A table that does not exist yet (fresh database, H2 tests) or any other failure
                // must never stop the other tables. Message only — no row data is in it.
                out.put(r.table(), -1);
                LOG.warn("[AuditRetention] {} skipped: {}", r.table(), e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        return out;
    }

    private int purgeTable(Rule r) {
        Timestamp cutoff = Timestamp.valueOf(LocalDateTime.now().minusDays(r.days()));
        String sql = "DELETE FROM " + r.table() + " WHERE id IN (SELECT t.id FROM " + r.table() + " t WHERE t."
                + r.column() + " < ? " + (r.extraWhere() == null ? "" : r.extraWhere() + " ") + "LIMIT " + BATCH + ")";
        int total = 0;
        for (int i = 0; i < 1000; i++) {                    // hard stop: 5M rows per table per night
            int n = jdbc.update(sql, cutoff);
            total += n;
            if (n < BATCH) break;
        }
        if (total > 0) LOG.info("[AuditRetention] {}: removed {} row(s) older than {} day(s)", r.table(), total, r.days());
        return total;
    }

    /** For tests and the Service Admin: the effective configuration. */
    public List<String> describe() {
        List<String> l = new ArrayList<>();
        for (Rule r : rules) l.add(r.table() + "=" + r.days() + "d");
        return l;
    }
}
