package com.churchgeniuspro.service;

import com.churchgeniuspro.util.AppClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Event retention (approved 2026-11-02): an event is ARCHIVED (soft-deleted) once its last
 * day is more than 5 years in the past, and PERMANENTLY DELETED with its event-specific
 * records once its last day is more than 10 years in the past.
 *
 * <h3>Date rule</h3>
 * The last day is the latest {@code church_event_day} date of a "Multiple Days" event,
 * otherwise {@code event_date} ({@link EventListService#END_DATE}). "More than N years"
 * = last day &lt; today − N years, with today in church time (America/Chicago) and
 * {@link LocalDate#minusYears} for the cutoff — so an event that ended on Feb 29 2020 is
 * archived on Mar 1 2025. Events with no date are never touched.
 *
 * <h3>Archive (5 years)</h3>
 * One conditional UPDATE: live events past the cutoff get {@code delete_flag = true},
 * {@code deleted_at}, {@code delete_reason = 'RETENTION'}. Nothing else changes — every
 * registration, volunteer record and reminder row stays. Events a person already deleted
 * are never touched; events a Service Admin restored ({@code delete_reason = 'RESTORED'})
 * are not archived again. Safe to run any number of times.
 *
 * <h3>Permanent delete (10 years)</h3>
 * Every event past the 10-year cutoff, whether archived first or not (a deleted or restored
 * event is included — the rule is the event's age). Each event is deleted in its OWN
 * transaction, children first in foreign-key order, after re-checking the cutoff inside
 * that transaction; at most {@code max-per-run} events per night. Removed: the event, its
 * days, image, registrations, reminders, invitation contacts and their log, event
 * volunteers and their roles, volunteer assignments for the event and their attendance,
 * and the ETL import links that point at the event or its registrations. Never removed:
 * members, families, volunteer profiles, roles, templates or anything shared. Only the
 * event id, church and per-table counts are logged.
 *
 * <h3>Switches</h3>
 * {@code events.retention.enabled} (archive) and {@code events.retention.hard-delete.enabled}
 * (permanent delete) both default to FALSE: nothing runs until they are set. The Service
 * Admin dry run ({@link #dryRun}) shows exactly what each rule would affect.
 */
@Service
public class EventRetentionService {

    private static final Logger log = LoggerFactory.getLogger(EventRetentionService.class);

    public static final String REASON_RETENTION = "RETENTION";
    public static final String REASON_RESTORED  = "RESTORED";

    private static final String END = EventListService.END_DATE;

    /** Event-specific child tables, deleted in this order before the event itself. */
    static final String[][] CHILD_DELETES = {
        { "volunteer_attendance",                "DELETE FROM volunteer_attendance WHERE assignment_id IN (SELECT id FROM volunteer_assignment WHERE event_id = :id)" },
        { "volunteer_assignment",                "DELETE FROM volunteer_assignment WHERE event_id = :id" },
        { "event_volunteer_role",                "DELETE FROM event_volunteer_role WHERE event_volunteer_id IN (SELECT id FROM event_volunteer WHERE event_id = :id)" },
        { "event_volunteer",                     "DELETE FROM event_volunteer WHERE event_id = :id" },
        { "event_registration_reminder_log",     "DELETE FROM event_registration_reminder_log WHERE event_id = :id "
                                               + "OR contact_id IN (SELECT id FROM event_registration_reminder_contact WHERE event_id = :id)" },
        { "event_registration_reminder_contact", "DELETE FROM event_registration_reminder_contact WHERE event_id = :id" },
        { "event_reminder",                      "DELETE FROM event_reminder WHERE event_id = :id" },
        { "import_link",                         "DELETE FROM import_link WHERE client_id = :client AND ("
                                               + "(entity = 'event' AND target_id = :id) OR "
                                               + "(entity = 'event_registration' AND target_id IN (SELECT id FROM event_registration WHERE event_id = :id)))" },
        { "event_registration",                  "DELETE FROM event_registration WHERE event_id = :id" },
        { "church_event_day",                    "DELETE FROM church_event_day WHERE event_id = :id" },
        { "church_event_image",                  "DELETE FROM church_event_image WHERE event_id = :id" },
    };

    /** Counting twins of {@link #CHILD_DELETES} for the dry run (same row sets). */
    static final String[][] CHILD_COUNTS = {
        { "volunteer_attendance",                "SELECT COUNT(*) FROM volunteer_attendance WHERE assignment_id IN (SELECT id FROM volunteer_assignment WHERE event_id IN (:ids))" },
        { "volunteer_assignment",                "SELECT COUNT(*) FROM volunteer_assignment WHERE event_id IN (:ids)" },
        { "event_volunteer_role",                "SELECT COUNT(*) FROM event_volunteer_role WHERE event_volunteer_id IN (SELECT id FROM event_volunteer WHERE event_id IN (:ids))" },
        { "event_volunteer",                     "SELECT COUNT(*) FROM event_volunteer WHERE event_id IN (:ids)" },
        { "event_registration_reminder_log",     "SELECT COUNT(*) FROM event_registration_reminder_log WHERE event_id IN (:ids) "
                                               + "OR contact_id IN (SELECT id FROM event_registration_reminder_contact WHERE event_id IN (:ids))" },
        { "event_registration_reminder_contact", "SELECT COUNT(*) FROM event_registration_reminder_contact WHERE event_id IN (:ids)" },
        { "event_reminder",                      "SELECT COUNT(*) FROM event_reminder WHERE event_id IN (:ids)" },
        { "import_link",                         "SELECT COUNT(*) FROM import_link WHERE (entity = 'event' AND target_id IN (:ids)) "
                                               + "OR (entity = 'event_registration' AND target_id IN (SELECT id FROM event_registration WHERE event_id IN (:ids)))" },
        { "event_registration",                  "SELECT COUNT(*) FROM event_registration WHERE event_id IN (:ids)" },
        { "church_event_day",                    "SELECT COUNT(*) FROM church_event_day WHERE event_id IN (:ids)" },
        { "church_event_image",                  "SELECT COUNT(*) FROM church_event_image WHERE event_id IN (:ids)" },
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final boolean hardDeleteEnabled;
    private final int archiveYears;
    private final int deleteYears;
    private final int maxPerRun;

    public EventRetentionService(NamedParameterJdbcTemplate jdbc,
                                 PlatformTransactionManager txManager,
                                 @Value("${events.retention.enabled:false}") boolean enabled,
                                 @Value("${events.retention.hard-delete.enabled:false}") boolean hardDeleteEnabled,
                                 @Value("${events.retention.archive-years:5}") int archiveYears,
                                 @Value("${events.retention.delete-years:10}") int deleteYears,
                                 @Value("${events.retention.hard-delete.max-per-run:200}") int maxPerRun) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.enabled = enabled;
        this.hardDeleteEnabled = hardDeleteEnabled;
        this.archiveYears = Math.max(1, archiveYears);
        this.deleteYears = Math.max(this.archiveYears + 1, deleteYears);
        this.maxPerRun = Math.max(1, Math.min(maxPerRun, 5000));
    }

    public boolean isEnabled()           { return enabled; }
    public boolean isHardDeleteEnabled() { return hardDeleteEnabled; }

    public LocalDate archiveCutoff(LocalDate today) { return today.minusYears(archiveYears); }
    public LocalDate deleteCutoff(LocalDate today)  { return today.minusYears(deleteYears); }

    /** Nightly at 04:10 church time (after the audit-table purge). Does nothing while switched off. */
    @Scheduled(cron = "${events.retention.cron:0 10 4 * * *}", zone = "America/Chicago")
    public void nightly() {
        if (!enabled) return;
        try {
            int archived = archive(AppClock.today());
            log.info("[EventRetention] archived {} event(s) older than {} years", archived, archiveYears);
        } catch (RuntimeException e) {
            log.warn("[EventRetention] archive step failed: {}", e.getClass().getSimpleName());
        }
        if (!hardDeleteEnabled) return;
        try {
            Map<String, Object> r = purge(AppClock.today(), maxPerRun);
            log.info("[EventRetention] permanently deleted {} event(s) older than {} years ({} failed)",
                    r.get("deleted"), deleteYears, r.get("failed"));
        } catch (RuntimeException e) {
            log.warn("[EventRetention] delete step failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Archives every live event past the 5-year cutoff. Returns how many were archived. */
    public int archive(LocalDate today) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("cutoff", archiveCutoff(today))
                .addValue("now", LocalDateTime.now(AppClock.ZONE))
                .addValue("reason", REASON_RETENTION)
                .addValue("restored", REASON_RESTORED);
        return jdbc.update(
                "UPDATE church_event e SET delete_flag = true, deleted_at = :now, delete_reason = :reason "
              + "WHERE e.delete_flag = false AND COALESCE(e.delete_reason, '') <> :restored "
              + "AND " + END + " IS NOT NULL AND " + END + " < :cutoff", p);
    }

    /**
     * Permanently deletes up to {@code limit} events past the 10-year cutoff, oldest first,
     * one transaction per event. Returns {deleted, failed, rows:{table:count}}.
     */
    public Map<String, Object> purge(LocalDate today, int limit) {
        LocalDate cutoff = deleteCutoff(today);
        List<Map<String, Object>> due = jdbc.queryForList(
                "SELECT e.id, e.app_client_id FROM church_event e "
              + "WHERE " + END + " IS NOT NULL AND " + END + " < :cutoff "
              + "ORDER BY " + END + " ASC, e.id ASC LIMIT :lim",
                new MapSqlParameterSource().addValue("cutoff", cutoff).addValue("lim", Math.max(1, limit)));
        int deleted = 0, failed = 0;
        Map<String, Long> totals = new LinkedHashMap<>();
        for (Map<String, Object> row : due) {
            Integer id = ((Number) row.get("id")).intValue();
            String client = (String) row.get("app_client_id");
            try {
                Map<String, Integer> counts = tx.execute(status -> deleteOne(id, client, cutoff));
                if (counts == null) continue;                    // no longer due (date changed)
                deleted++;
                counts.forEach((t, n) -> totals.merge(t, (long) n, Long::sum));
                log.info("[EventRetention] deleted event {} (church {}) rows={}", id, client, counts);
            } catch (RuntimeException e) {
                failed++;
                log.warn("[EventRetention] could not delete event {} (church {}): {}", id, client, e.getClass().getSimpleName());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", deleted);
        out.put("failed", failed);
        out.put("rows", totals);
        return out;
    }

    /** One event, inside the caller's transaction. Null when the event is no longer due. */
    private Map<String, Integer> deleteOne(Integer id, String client, LocalDate cutoff) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("id", id).addValue("client", client == null ? "" : client).addValue("cutoff", cutoff);
        // Re-check inside the transaction (and lock the row) so an event whose date was just
        // edited, or that another run already removed, is left alone.
        List<Integer> still = jdbc.queryForList(
                "SELECT e.id FROM church_event e WHERE e.id = :id AND " + END + " IS NOT NULL AND " + END + " < :cutoff FOR UPDATE",
                p, Integer.class);
        if (still.isEmpty()) return null;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String[] d : CHILD_DELETES) counts.put(d[0], jdbc.update(d[1], p));
        counts.put("church_event", jdbc.update("DELETE FROM church_event WHERE id = :id", p));
        return counts;
    }

    /**
     * What the rules would do today, without changing anything: per church the number of
     * events to archive and to delete permanently, per table the related rows a permanent
     * delete would remove, and up to {@code listLimit} candidate events (id, church, name,
     * last day, current state, action).
     */
    public Map<String, Object> dryRun(LocalDate today, int listLimit) {
        LocalDate ac = archiveCutoff(today), dc = deleteCutoff(today);
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("ac", ac).addValue("dc", dc).addValue("restored", REASON_RESTORED)
                .addValue("lim", Math.max(1, Math.min(listLimit, 5000)));

        List<Map<String, Object>> perChurch = jdbc.queryForList(
                "SELECT e.app_client_id AS church, "
              + "COUNT(*) FILTER (WHERE e.delete_flag = false AND COALESCE(e.delete_reason,'') <> :restored AND " + END + " < :ac) AS to_archive, "
              + "COUNT(*) FILTER (WHERE " + END + " < :dc) AS to_delete "
              + "FROM church_event e WHERE " + END + " IS NOT NULL AND " + END + " < :ac "
              + "GROUP BY e.app_client_id ORDER BY e.app_client_id", p);

        List<Integer> deleteIds = jdbc.queryForList(
                "SELECT e.id FROM church_event e WHERE " + END + " IS NOT NULL AND " + END + " < :dc", p, Integer.class);
        Map<String, Long> related = new LinkedHashMap<>();
        if (!deleteIds.isEmpty()) {
            MapSqlParameterSource ids = new MapSqlParameterSource().addValue("ids", deleteIds);
            for (String[] c : CHILD_COUNTS) {
                Long n = jdbc.queryForObject(c[1], ids, Long.class);
                related.put(c[0], n == null ? 0L : n);
            }
        }
        related.put("church_event", (long) deleteIds.size());

        List<Map<String, Object>> events = new ArrayList<>();
        jdbc.query(
                "SELECT e.id, e.app_client_id, e.event_name, " + END + " AS end_date, e.delete_flag, e.delete_reason "
              + "FROM church_event e WHERE " + END + " IS NOT NULL AND " + END + " < :ac "
              + "AND (e.delete_flag = false OR " + END + " < :dc) "
              + "ORDER BY " + END + " ASC, e.id ASC LIMIT :lim", p, rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    LocalDate end = rs.getDate("end_date").toLocalDate();
                    m.put("id", rs.getInt("id"));
                    m.put("church", rs.getString("app_client_id"));
                    m.put("name", rs.getString("event_name"));
                    m.put("lastDay", end.toString());
                    boolean del = rs.getBoolean("delete_flag");
                    String reason = rs.getString("delete_reason");
                    m.put("state", del ? (reason == null ? "DELETED" : "DELETED (" + reason + ")") : (REASON_RESTORED.equals(reason) ? "RESTORED" : "LIVE"));
                    m.put("action", end.isBefore(dc) ? "DELETE PERMANENTLY" : "ARCHIVE");
                    events.add(m);
                });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("today", today.toString());
        out.put("timeZone", AppClock.ZONE.getId());
        out.put("archiveCutoff", ac.toString());
        out.put("deleteCutoff", dc.toString());
        out.put("archiveEnabled", enabled);
        out.put("hardDeleteEnabled", hardDeleteEnabled);
        out.put("maxPerRun", maxPerRun);
        out.put("churches", perChurch);
        out.put("permanentDeleteRows", related);
        out.put("events", events);
        return out;
    }

    /**
     * Brings back an event the RETENTION job archived. It is marked RESTORED so the nightly
     * archive does not take it again (the 10-year permanent delete still applies).
     * Returns false when the event does not exist or was not archived by retention.
     */
    public boolean restore(Integer id) {
        return jdbc.update(
                "UPDATE church_event SET delete_flag = false, deleted_at = NULL, delete_reason = :restored "
              + "WHERE id = :id AND delete_flag = true AND delete_reason = :retention",
                new MapSqlParameterSource().addValue("id", id)
                        .addValue("restored", REASON_RESTORED).addValue("retention", REASON_RETENTION)) == 1;
    }
}
