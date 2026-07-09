package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.BackupConfig;
import com.churchgeniuspro.hibernate.BackupLog;
import com.churchgeniuspro.repository.BackupConfigRepository;
import com.churchgeniuspro.repository.BackupLogRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Manages database backup configuration and execution.
 *
 * <h3>How backups work</h3>
 * <p>On the configured {@code nextRunDate} the daily scheduler copies each
 * source table into a new table named {@code <source>_bkp_<YYYYMMDD>} using
 * a {@code CREATE TABLE … AS SELECT * FROM …} statement.  PostgreSQL executes
 * this atomically and the snapshot is immediately queryable.
 *
 * <h3>Table groups</h3>
 * <ul>
 *   <li><b>ALL</b>    — every table listed in {@link #ALL_TABLES}.</li>
 *   <li><b>FAMILY</b> — family &amp; member tables.</li>
 *   <li><b>INCOME</b> — income, expense, and transaction tables.</li>
 * </ul>
 *
 * <p>After each run a {@link BackupLog} row is written and notification
 * emails are sent to the configured recipients via {@link EmailService}.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    // ── Table groups ──────────────────────────────────────────────────────────

    static final List<String> FAMILY_TABLES = List.of(
            "family", "family_member"
    );

    static final List<String> INCOME_TABLES = List.of(
            "income", "expense", "transaction_type", "main_source", "sub_source", "purpose"
    );

    static final List<String> ALL_TABLES;
    static {
        List<String> all = new ArrayList<>();
        all.addAll(FAMILY_TABLES);
        all.addAll(INCOME_TABLES);
        all.addAll(List.of(
                "app_user", "church_registration", "church_event",
                "event_volunteer", "event_volunteer_role",
                "meeting", "meeting_type",
                "app_group", "group_member",
                "km_child", "km_classroom", "km_checkin",
                "volunteer_profile", "volunteer_role", "km_volunteer_role_assignment",
                "worship_group", "worship_instrument", "worship_group_member",
                "worship_assignment", "worship_assignment_member", "worship_song",
                "ss_class", "ss_teacher", "ss_student", "ss_lesson",
                "ss_exam", "ss_question", "ss_submission", "ss_answer",
                "prayer_request", "follow_up",
                "mid_reg_meet", "mid_reg_meet_rsvp",
                "push_notification_log",
                "service_client"
        ));
        ALL_TABLES = Collections.unmodifiableList(all);
    }

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final BackupConfigRepository backupConfigRepository;
    private final BackupLogRepository    backupLogRepository;
    private final EmailService           emailService;
    private final DataSource             dataSource;

    public BackupService(BackupConfigRepository backupConfigRepository,
                         BackupLogRepository    backupLogRepository,
                         EmailService           emailService,
                         DataSource             dataSource) {
        this.backupConfigRepository = backupConfigRepository;
        this.backupLogRepository    = backupLogRepository;
        this.emailService           = emailService;
        this.dataSource             = dataSource;
    }

    // ── Config API ────────────────────────────────────────────────────────────

    /**
     * Returns the single backup config row, creating a default (disabled) one
     * if none exists yet.
     */
    public BackupConfig getConfig() {
        return backupConfigRepository.findById(1)
                .orElseGet(() -> {
                    BackupConfig cfg = new BackupConfig();
                    cfg.setIntervalMonths(0);
                    cfg.setTableScope("ALL");
                    return backupConfigRepository.save(cfg);
                });
    }

    /**
     * Saves (or updates) the backup configuration and recalculates
     * {@code nextRunDate} from today.
     */
    @Transactional
    public BackupConfig saveConfig(int intervalMonths, String tableScope, String notifyEmails) {
        BackupConfig cfg = getConfig();
        cfg.setIntervalMonths(intervalMonths);
        cfg.setTableScope(tableScope);
        cfg.setNotifyEmails(notifyEmails);

        // Recalculate next run date
        if (intervalMonths > 0) {
            cfg.setNextRunDate(LocalDate.now().plusMonths(intervalMonths));
        } else {
            cfg.setNextRunDate(null); // disabled
        }

        return backupConfigRepository.save(cfg);
    }

    /** Returns the 20 most-recent backup log entries. */
    public List<BackupLog> getRecentLogs() {
        return backupLogRepository.findRecent();
    }

    /**
     * Returns a sorted list of distinct backup date suffixes (YYYYMMDD) that
     * exist in the database, newest first. Scans the information_schema so no
     * hard-coded table list is needed.
     */
    public List<String> getAvailableSnapshots() {
        List<String> dates = new ArrayList<>();
        String sql = "SELECT DISTINCT regexp_replace(table_name, '^.+_bkp_', '') AS bkp_date " +
                     "FROM information_schema.tables " +
                     "WHERE table_schema = 'public' " +
                     "  AND table_name ~ '_bkp_[0-9]{8}$' " +
                     "ORDER BY bkp_date DESC";
        try (Connection conn = dataSource.getConnection();
             Statement  stmt = conn.createStatement();
             java.sql.ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                dates.add(rs.getString("bkp_date"));
            }
        } catch (Exception ex) {
            log.warn("BackupService.getAvailableSnapshots error: {}", ex.getMessage());
        }
        return dates;
    }

    /**
     * Restores all backup tables for the given {@code dateSuffix} (YYYYMMDD)
     * back into their source tables using TRUNCATE + INSERT INTO … SELECT *.
     * Only tables that actually have a snapshot for that date are restored.
     * Each restore runs in its own autocommit statement so one failure does not
     * block the others.
     *
     * @return a result map with keys {@code restored}, {@code failed}, {@code skipped}
     */
    public Map<String, Object> restoreSnapshot(String dateSuffix) {
        // Validate format to prevent SQL injection
        if (dateSuffix == null || !dateSuffix.matches("[0-9]{8}")) {
            throw new IllegalArgumentException("Invalid date suffix: " + dateSuffix);
        }

        List<String> restored = new ArrayList<>();
        List<String> failed   = new ArrayList<>();
        List<String> skipped  = new ArrayList<>();

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);   // use explicit transactions per table
            try (Statement stmt = conn.createStatement()) {
                for (String table : ALL_TABLES) {
                    String backupTable = table + "_bkp_" + dateSuffix;
                    // Check whether backup table exists
                    boolean exists;
                    try (java.sql.ResultSet rs = conn.getMetaData().getTables(
                            null, "public", backupTable, new String[]{"TABLE"})) {
                        exists = rs.next();
                    }
                    if (!exists) {
                        skipped.add(table);
                        continue;
                    }
                    try {
                        stmt.execute("TRUNCATE TABLE " + table + " CASCADE");
                        stmt.execute("INSERT INTO " + table + " SELECT * FROM " + backupTable);
                        conn.commit();
                        restored.add(table);
                        log.info("BackupService: restored {} from {}", table, backupTable);
                    } catch (Exception ex) {
                        conn.rollback();
                        String shortMsg = ex.getMessage() == null ? "unknown error"
                                : ex.getMessage().lines().findFirst().orElse(ex.getMessage());
                        failed.add(table + " (" + shortMsg + ")");
                        log.warn("BackupService: failed to restore {}: {}", table, shortMsg);
                        // Reset autoCommit state after rollback so next table starts clean
                        conn.setAutoCommit(false);
                    }
                }
            }
        } catch (Exception connEx) {
            log.error("BackupService: could not obtain DB connection for restore", connEx);
            failed.add("DB connection failed: " + connEx.getMessage());
        }

        String status  = failed.isEmpty() ? "SUCCESS" : (restored.isEmpty() ? "FAILURE" : "PARTIAL");
        String message = restored.size() + " table(s) restored from backup " + dateSuffix + ".";
        if (!failed.isEmpty())  message += " FAILED: " + String.join("; ", failed) + ".";
        if (!skipped.isEmpty()) message += " Skipped (no snapshot): " + String.join(", ", skipped) + ".";

        Map<String, Object> result = new HashMap<>();
        result.put("status",   status);
        result.put("message",  message);
        result.put("restored", restored.size());
        result.put("failed",   failed.size());
        result.put("skipped",  skipped.size());
        return result;
    }

    // ── Scheduler ─────────────────────────────────────────────────────────────

    /**
     * Runs once every day at 02:00 AM and executes the backup if today matches
     * (or has passed) the configured {@code nextRunDate}.
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void scheduledBackup() {
        BackupConfig cfg = getConfig();
        if (cfg.getIntervalMonths() <= 0 || cfg.getNextRunDate() == null) {
            return; // disabled
        }
        if (!LocalDate.now().isBefore(cfg.getNextRunDate())) {
            log.info("BackupService: scheduled backup triggered (nextRunDate={})", cfg.getNextRunDate());
            runBackup(cfg);
        }
    }

    // ── Core backup logic ─────────────────────────────────────────────────────

    /**
     * Immediately executes a backup for the given config, regardless of schedule.
     * Uses a plain JDBC connection with autocommit=true so that each table copy
     * runs in its own implicit transaction — a failure on one table does not
     * abort the others or the subsequent log/config save.
     */
    public BackupLog runBackup(BackupConfig cfg) {
        String dateSuffix   = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        List<String> tables = resolveTables(cfg.getTableScope());
        List<String> backed = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);          // each DDL statement is its own transaction
            try (Statement stmt = conn.createStatement()) {
                for (String table : tables) {
                    String backupTable = table + "_bkp_" + dateSuffix;
                    try {
                        stmt.execute("DROP TABLE IF EXISTS " + backupTable);
                        stmt.execute("CREATE TABLE " + backupTable + " AS SELECT * FROM " + table);
                        backed.add(backupTable);
                        log.info("BackupService: created {}", backupTable);
                    } catch (Exception ex) {
                        String shortMsg = ex.getMessage() == null ? "unknown error"
                                : ex.getMessage().lines().findFirst().orElse(ex.getMessage());
                        failed.add(table + " (" + shortMsg + ")");
                        log.warn("BackupService: failed to back up table {}: {}", table, shortMsg);
                    }
                }
            }
        } catch (Exception connEx) {
            log.error("BackupService: could not obtain DB connection", connEx);
            failed.add("DB connection failed: " + connEx.getMessage());
        }

        // Build result
        String status  = failed.isEmpty() ? "SUCCESS" : (backed.isEmpty() ? "FAILURE" : "PARTIAL");
        String message = buildMessage(backed, failed);

        // Persist log and advance schedule in a normal JPA transaction
        return persistResult(cfg, status, message);
    }

    @Transactional
    protected BackupLog persistResult(BackupConfig cfg, String status, String message) {
        BackupLog bkpLog = new BackupLog();
        bkpLog.setRunAt(LocalDateTime.now());
        bkpLog.setStatus(status);
        bkpLog.setTableScope(cfg.getTableScope());
        bkpLog.setMessage(message);

        cfg.setLastRunDate(LocalDate.now());
        if (cfg.getIntervalMonths() > 0) {
            cfg.setNextRunDate(LocalDate.now().plusMonths(cfg.getIntervalMonths()));
        }
        backupConfigRepository.save(cfg);

        sendNotifications(cfg, bkpLog, status, message);
        bkpLog.setNotifiedEmails(cfg.getNotifyEmails());

        return backupLogRepository.save(bkpLog);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<String> resolveTables(String scope) {
        return switch (scope == null ? "ALL" : scope.toUpperCase()) {
            case "FAMILY" -> FAMILY_TABLES;
            case "INCOME" -> INCOME_TABLES;
            default       -> ALL_TABLES;
        };
    }

    private String buildMessage(List<String> backed, List<String> failed) {
        StringBuilder sb = new StringBuilder();
        sb.append(backed.size()).append(" table(s) backed up successfully.");
        if (!backed.isEmpty()) {
            sb.append(" Tables: ").append(String.join(", ", backed)).append(".");
        }
        if (!failed.isEmpty()) {
            sb.append(" FAILED: ").append(String.join("; ", failed)).append(".");
        }
        return sb.toString();
    }

    private void sendNotifications(BackupConfig cfg, BackupLog bkpLog,
                                   String status, String message) {
        if (cfg.getNotifyEmails() == null || cfg.getNotifyEmails().isBlank()) return;

        String subject = "Church Genius Pro — Database Backup "
                + (status.equals("SUCCESS") ? "Completed ✅" : "Result ⚠️");

        String colour  = status.equals("SUCCESS") ? "#2e7d32" : "#c62828";
        String badge   = status.equals("SUCCESS") ? "✅ SUCCESS" : ("PARTIAL".equals(status) ? "⚠️ PARTIAL" : "❌ FAILURE");
        String htmlBody =
                "<div style='font-family:sans-serif;max-width:600px;margin:auto;'>" +
                "<div style='background:#673147;padding:18px 24px;border-radius:8px 8px 0 0;'>" +
                "<h2 style='color:#fff;margin:0;font-size:18px;'>Church Genius Pro</h2>" +
                "<p style='color:rgba(255,255,255,.75);margin:4px 0 0;font-size:13px;'>Database Backup Notification</p>" +
                "</div>" +
                "<div style='background:#fff;padding:24px;border:1px solid #eee;border-radius:0 0 8px 8px;'>" +
                "<p style='font-size:14px;color:#333;'>A database backup was completed on <strong>" +
                LocalDate.now() + "</strong>.</p>" +
                "<table style='width:100%;border-collapse:collapse;margin:16px 0;font-size:13px;'>" +
                "<tr><td style='padding:8px 12px;background:#f5f6fa;font-weight:700;color:#555;width:140px;'>Status</td>" +
                "<td style='padding:8px 12px;font-weight:700;color:" + colour + ";'>" + badge + "</td></tr>" +
                "<tr><td style='padding:8px 12px;background:#f5f6fa;font-weight:700;color:#555;'>Scope</td>" +
                "<td style='padding:8px 12px;'>" + (cfg.getTableScope() != null ? cfg.getTableScope() : "ALL") + "</td></tr>" +
                "<tr><td style='padding:8px 12px;background:#f5f6fa;font-weight:700;color:#555;'>Run Time</td>" +
                "<td style='padding:8px 12px;'>" + bkpLog.getRunAt() + "</td></tr>" +
                "</table>" +
                "<div style='background:#f9f9f9;border-left:4px solid " + colour + ";padding:12px 16px;" +
                "border-radius:4px;font-size:13px;color:#333;'>" + message + "</div>" +
                "<p style='font-size:12px;color:#aaa;margin-top:20px;'>This is an automated message from Church Genius Pro.</p>" +
                "</div></div>";

        for (String email : cfg.getNotifyEmails().split(",")) {
            String addr = email.trim();
            if (!addr.isEmpty()) {
                try {
                    emailService.sendGenericEmail(addr, subject, htmlBody);
                } catch (Exception ex) {
                    log.warn("BackupService: failed to notify {}: {}", addr, ex.getMessage());
                }
            }
        }
    }
}
