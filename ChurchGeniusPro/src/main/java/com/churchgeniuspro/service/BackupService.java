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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
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
 * a {@code CREATE TABLE … AS SELECT * FROM …} statement. All copies of one run
 * are taken inside a single {@code REPEATABLE READ} transaction, so every
 * snapshot table shows the database as it was at one instant — a child row
 * can never reference a parent the snapshot has not got (database audit C2).
 *
 * <h3>How restores work</h3>
 * <p>{@link #restoreSnapshot} restores every table that has a snapshot for the
 * chosen date, as one transaction: parents before children in foreign-key
 * order, one {@code TRUNCATE} of all of them (never {@code CASCADE}), columns
 * copied by name, and every id sequence re-synced afterwards. The plan is
 * checked first ({@link SnapshotRestorePlanner}); if anything about it would
 * lose data or fail part-way, nothing is touched and the reasons are reported.
 *
 * <h3>Table groups</h3>
 * <ul>
 *   <li><b>ALL</b>    — every base table in the {@code public} schema, discovered at run
 *       time from {@code information_schema}. This used to be a hand-maintained list of 43
 *       table names, which silently stopped covering anything added after it was written —
 *       by the time it was replaced the schema had grown to roughly 155 tables, so about
 *       two thirds of the database was quietly not being backed up. A new feature must
 *       never again require someone to remember to edit this class.</li>
 *   <li><b>FAMILY</b> — family &amp; member tables (a deliberate, curated subset).</li>
 *   <li><b>INCOME</b> — income, expense, and transaction tables (likewise).</li>
 * </ul>
 *
 * <h3>Retention</h3>
 * Snapshots older than {@code BackupConfig.retentionMonths} are dropped automatically —
 * see {@link #purgeExpiredBackups}. <b>Every table's most recent backup is always kept</b>,
 * however old it is, so a short retention combined with a long backup interval can never
 * leave any table without a backup. The rule is applied per table rather than per snapshot,
 * and every retention that it saves is logged.
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

    /**
     * Tables never included in a backup, whatever the scope.
     *
     * <p>Each exclusion is deliberate:
     * <ul>
     *   <li><b>Backup tables themselves</b> (matched by {@link #BACKUP_TABLE_PATTERN}, not
     *       listed here) — without this, dynamic discovery would find
     *       {@code app_user_bkp_20260217} and snapshot it as
     *       {@code app_user_bkp_20260217_bkp_20260818}. Storage would double every run.</li>
     *   <li><b>spring_session / spring_session_attributes</b> — live login sessions. A
     *       snapshot is meaningless within minutes, and restoring one would resurrect
     *       expired sessions.</li>
     *   <li><b>backup_config / backup_log</b> — the backup system's own bookkeeping.
     *       Restoring these would silently roll back the very settings an administrator
     *       is using to perform the restore.</li>
     * </ul>
     */
    static final Set<String> EXCLUDED_TABLES = Set.of(
            "spring_session", "spring_session_attributes",
            "backup_config", "backup_log"
    );

    /** Identifies a snapshot table: {@code <source>_bkp_YYYYMMDD}. */
    static final java.util.regex.Pattern BACKUP_TABLE_PATTERN =
            java.util.regex.Pattern.compile("^(.+)_bkp_(\\d{8})$");

    private static final DateTimeFormatter SUFFIX_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final BackupConfigRepository backupConfigRepository;
    private final BackupLogRepository    backupLogRepository;
    private final EmailService           emailService;
    private final DataSource             dataSource;
    private final IdSequenceCatalog      sequenceCatalog;

    public BackupService(BackupConfigRepository backupConfigRepository,
                         BackupLogRepository    backupLogRepository,
                         EmailService           emailService,
                         DataSource             dataSource,
                         IdSequenceCatalog      sequenceCatalog) {
        this.backupConfigRepository = backupConfigRepository;
        this.backupLogRepository    = backupLogRepository;
        this.emailService           = emailService;
        this.dataSource             = dataSource;
        this.sequenceCatalog        = sequenceCatalog;
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
     * Saves (or updates) the backup configuration and recalculates {@code nextRunDate}
     * from the last run (see {@link #nextRunAfterSave}).
     */
    @Transactional
    public BackupConfig saveConfig(int intervalMonths, String tableScope, String notifyEmails) {
        return saveConfig(intervalMonths, tableScope, notifyEmails, getConfig().getRetentionMonths());
    }

    /**
     * Saves the backup configuration including the retention period, and recalculates
     * {@code nextRunDate} from the last run (see {@link #nextRunAfterSave}).
     *
     * @param retentionMonths months to keep a snapshot before dropping it; 0 = keep forever.
     *                        Negative values are clamped to 0 rather than rejected, so a bad
     *                        payload can never be interpreted as "delete everything".
     */
    @Transactional
    public BackupConfig saveConfig(int intervalMonths, String tableScope,
                                   String notifyEmails, int retentionMonths) {
        BackupConfig cfg = getConfig();
        cfg.setIntervalMonths(intervalMonths);
        cfg.setTableScope(tableScope);
        cfg.setNotifyEmails(notifyEmails);
        cfg.setRetentionMonths(Math.max(0, retentionMonths));
        cfg.setNextRunDate(nextRunAfterSave(LocalDate.now(), cfg.getLastRunDate(), intervalMonths));
        return backupConfigRepository.save(cfg);
    }

    /**
     * The next run date after the settings are saved.
     *
     * <p>Database audit P4 (Sept 2026): this used to be {@code today + interval}, so every
     * settings save pushed the next backup a whole interval into the future — production's
     * monthly backup of 2026-07-29 was due on 2026-08-29, a settings save on 2026-08-17 moved
     * it to 2026-09-17, and August had no backup. The schedule now keeps counting from the
     * last run: {@code lastRun + interval}, or today if that is already past (the daily
     * 02:00 check then runs it at its next tick). A backup that has never run is scheduled
     * one interval from today, as before; interval 0 disables the schedule.
     */
    static LocalDate nextRunAfterSave(LocalDate today, LocalDate lastRun, int intervalMonths) {
        if (intervalMonths <= 0) {
            return null;                                     // disabled
        }
        LocalDate anchor = lastRun != null ? lastRun : today;
        LocalDate next = anchor.plusMonths(intervalMonths);
        return next.isBefore(today) ? today : next;
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
     * Restores every table that has a snapshot for {@code dateSuffix} (YYYYMMDD) from that
     * snapshot, as a single transaction — either every table comes back exactly as the
     * snapshot holds it, or nothing changes at all.
     *
     * <p>Database audit C2 (Sept 2026): the previous version truncated each table with
     * {@code CASCADE} and reloaded it with {@code SELECT *}, in catalogue order. Because
     * real foreign keys exist on the ledger ({@code income → sub_source}, {@code expense →
     * main_source}, {@code meeting → meeting_type}, …), restoring a parent after its child
     * emptied the child again, and a restore that reported SUCCESS left {@code income},
     * {@code expense} and {@code meeting} with no rows. It also assumed the snapshot still
     * had the live table's columns and never re-synced a sequence. This version:
     * <ol>
     *   <li>reads the catalogue and builds a plan ({@link SnapshotRestorePlanner}) — which
     *       tables, parents first, copying which columns by name;</li>
     *   <li>refuses, changing nothing, if the plan has a blocker (a table whose truncation
     *       would empty a table with no snapshot; a NOT NULL column the snapshot cannot
     *       fill; circular foreign keys);</li>
     *   <li>truncates all planned tables in one statement, loads them in order, sets every
     *       id sequence to {@code MAX(id) + 1}, and commits — or rolls everything back on
     *       the first error.</li>
     * </ol>
     *
     * @return a result map with keys {@code status} (SUCCESS / FAILURE), {@code message},
     *         {@code restored}, {@code failed}, {@code skipped} and {@code warnings}
     */
    public Map<String, Object> restoreSnapshot(String dateSuffix) {
        // Validate format to prevent SQL injection
        if (dateSuffix == null || !dateSuffix.matches("[0-9]{8}")) {
            throw new IllegalArgumentException("Invalid date suffix: " + dateSuffix);
        }

        // Driven by what the snapshot actually contains rather than by a fixed table list,
        // so a restore covers exactly the tables that existed when the backup was taken —
        // including any added since this code was written.
        List<String> snapshotTables = findSnapshotsByDate().getOrDefault(dateSuffix, List.of());
        if (snapshotTables.isEmpty()) {
            return restoreResult("FAILURE", "No backup tables found for " + dateSuffix + ".", 0, 0, List.of(), List.of());
        }

        Map<String, String> snapshotByTable = new TreeMap<>();
        List<String> skipped = new ArrayList<>();
        for (String backupTable : snapshotTables) {
            var matcher = BACKUP_TABLE_PATTERN.matcher(backupTable);
            if (!matcher.matches()) continue;              // defensive: not a snapshot
            String table = matcher.group(1);
            if (EXCLUDED_TABLES.contains(table)) {         // never restore over backup config
                skipped.add(table);
                continue;
            }
            snapshotByTable.put(table, backupTable);
        }

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);                     // the whole restore is one transaction
            SnapshotRestorePlanner.Plan plan;
            Map<String, Map<String, String>> ownedSequences;
            try {
                Set<String> snapshotNames = new TreeSet<>(snapshotByTable.values());
                Set<String> baseTables = baseTables(conn);
                Set<String> wanted = new TreeSet<>(snapshotByTable.keySet());
                wanted.addAll(snapshotNames);
                plan = SnapshotRestorePlanner.plan(snapshotByTable, columnsOf(conn, wanted), foreignKeys(conn), baseTables);
                ownedSequences = ownedSequences(conn);
            } catch (SQLException ex) {
                conn.rollback();
                log.error("BackupService: could not read the catalogue to plan restore of {}", dateSuffix, ex);
                return restoreResult("FAILURE", "Restore of " + dateSuffix + " not started — could not read the "
                        + "database catalogue: " + firstLine(ex) + ". Nothing was changed.", 0, 0, skipped, List.of());
            }

            if (!plan.executable()) {
                conn.rollback();
                log.warn("BackupService: restore of {} refused — {}", dateSuffix, plan.blockers());
                return restoreResult("FAILURE", "Restore of " + dateSuffix + " refused — nothing was changed. "
                        + String.join(" | ", plan.blockers()), 0, plan.blockers().size(), skipped, plan.warnings());
            }
            if (plan.steps().isEmpty()) {
                conn.rollback();
                return restoreResult("FAILURE", "Restore of " + dateSuffix + " has nothing to restore — nothing was changed. "
                        + String.join(" | ", plan.warnings()), 0, 0, skipped, plan.warnings());
            }

            List<String> statements = restoreStatements(plan, ownedSequences, sequenceCatalog.byTable());
            String current = null;
            try (Statement stmt = conn.createStatement()) {
                for (String sql : statements) {
                    current = sql;
                    stmt.execute(sql);
                }
                conn.commit();
            } catch (Exception ex) {
                conn.rollback();
                log.error("BackupService: restore of {} rolled back at [{}]: {}", dateSuffix, current, firstLine(ex));
                return restoreResult("FAILURE", "Restore of " + dateSuffix + " failed and was rolled back — nothing "
                        + "was changed. " + firstLine(ex) + (current == null ? "" : " (while running: " + current + ")"),
                        0, plan.steps().size(), skipped, plan.warnings());
            }

            for (SnapshotRestorePlanner.Step step : plan.steps()) {
                log.info("BackupService: restored {} from {} ({} column(s))", step.table(), step.snapshot(), step.columns().size());
            }
            plan.warnings().forEach(w -> log.warn("BackupService: restore of {} — {}", dateSuffix, w));
            String message = plan.steps().size() + " table(s) restored from backup " + dateSuffix
                    + " in foreign-key order, as one transaction; id sequences re-synced.";
            if (!skipped.isEmpty()) message += " Skipped (never restored): " + String.join(", ", skipped) + ".";
            if (!plan.warnings().isEmpty()) message += " Notes: " + String.join(" | ", plan.warnings());
            return restoreResult("SUCCESS", message, plan.steps().size(), 0, skipped, plan.warnings());
        } catch (SQLException connEx) {
            log.error("BackupService: could not obtain DB connection for restore", connEx);
            return restoreResult("FAILURE", "DB connection failed: " + firstLine(connEx) + ". Nothing was changed.",
                    0, 0, skipped, List.of());
        }
    }

    /**
     * The SQL a plan executes, in order: one {@code TRUNCATE} of every planned table (no
     * {@code CASCADE} — the planner has already proved nothing outside the set references
     * them), one {@code INSERT … SELECT} per table naming its columns, parents first, then
     * one {@code setval} per id sequence of a restored table so the next insert does not
     * collide with a restored id. Package-private so the statements themselves are tested.
     *
     * @param ownedSequences  table → (column → sequence) for identity/serial columns, from
     *                        the catalogue
     * @param mappedSequences table → (column → sequence) for {@code @SequenceGenerator}
     *                        ids, from the entity mapping
     */
    static List<String> restoreStatements(SnapshotRestorePlanner.Plan plan,
                                          Map<String, Map<String, String>> ownedSequences,
                                          Map<String, Map<String, String>> mappedSequences) {
        List<String> sql = new ArrayList<>();
        List<String> tables = plan.tables();
        StringJoiner truncate = new StringJoiner(", ", "TRUNCATE TABLE ", "");
        tables.forEach(t -> truncate.add(quote(t)));
        sql.add(truncate.toString());
        for (SnapshotRestorePlanner.Step step : plan.steps()) {
            StringJoiner cols = new StringJoiner(", ");
            step.columns().forEach(c -> cols.add(quote(c)));
            // OVERRIDING SYSTEM VALUE lets a restored id land in a GENERATED ALWAYS identity
            // column too; it is accepted, and inert, when there is none.
            sql.add("INSERT INTO " + quote(step.table()) + " (" + cols + ") OVERRIDING SYSTEM VALUE SELECT "
                    + cols + " FROM " + quote(step.snapshot()));
        }
        for (String table : tables) {
            Map<String, String> sequences = new TreeMap<>();
            sequences.putAll(mappedSequences.getOrDefault(table, Map.of()));
            sequences.putAll(ownedSequences.getOrDefault(table, Map.of()));
            for (Map.Entry<String, String> e : sequences.entrySet()) {
                // is_called=false → the next nextval() returns exactly MAX(id)+1 (or 1 when empty).
                sql.add("SELECT setval('" + quote(e.getValue()) + "', COALESCE((SELECT MAX(" + quote(e.getKey())
                        + ") FROM " + quote(table) + "), 0) + 1, false)");
            }
        }
        return sql;
    }

    /** Quotes a catalogue identifier; anything that is not a plain identifier is refused outright. */
    static String quote(String identifier) {
        if (identifier == null || !identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Not a plain SQL identifier: " + identifier);
        }
        return "\"" + identifier + "\"";
    }

    private static Map<String, Object> restoreResult(String status, String message, int restored, int failed,
                                                     List<String> skipped, List<String> warnings) {
        Map<String, Object> result = new HashMap<>();
        result.put("status",   status);
        result.put("message",  message);
        result.put("restored", restored);
        result.put("failed",   failed);
        result.put("skipped",  skipped.size());
        result.put("warnings", warnings);
        return result;
    }

    private static String firstLine(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m.lines().findFirst().orElse(m);
    }

    // ── Catalogue reads for the restore plan ──────────────────────────────────

    /** Every base table in the current schema that is not a snapshot. */
    private Set<String> baseTables(Connection conn) throws SQLException {
        Set<String> tables = new TreeSet<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                     SELECT table_name
                       FROM information_schema.tables
                      WHERE table_schema = current_schema()
                        AND table_type   = 'BASE TABLE'
                        AND table_name !~ '_bkp_[0-9]{8}$'
                     """)) {
            while (rs.next()) tables.add(rs.getString(1));
        }
        return tables;
    }

    /** The columns of each named table, in ordinal order, as the catalogue describes them. */
    private Map<String, List<SnapshotRestorePlanner.Column>> columnsOf(Connection conn, Set<String> tables) throws SQLException {
        Map<String, List<SnapshotRestorePlanner.Column>> out = new TreeMap<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT table_name, column_name, is_nullable, column_default, is_identity, is_generated
                  FROM information_schema.columns
                 WHERE table_schema = current_schema() AND table_name = ANY (?)
                 ORDER BY table_name, ordinal_position
                """)) {
            ps.setArray(1, conn.createArrayOf("text", tables.toArray(new String[0])));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.computeIfAbsent(rs.getString("table_name"), k -> new ArrayList<>())
                       .add(new SnapshotRestorePlanner.Column(
                               rs.getString("column_name"),
                               "YES".equalsIgnoreCase(rs.getString("is_nullable")),
                               rs.getString("column_default") != null || "YES".equalsIgnoreCase(rs.getString("is_identity")),
                               "ALWAYS".equalsIgnoreCase(rs.getString("is_generated"))));
                }
            }
        }
        return out;
    }

    /** Every foreign key between tables of the current schema. */
    private List<SnapshotRestorePlanner.ForeignKey> foreignKeys(Connection conn) throws SQLException {
        List<SnapshotRestorePlanner.ForeignKey> out = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                     SELECT c.conname, ch.relname AS child_table, pa.relname AS parent_table
                       FROM pg_constraint c
                       JOIN pg_class ch     ON ch.oid = c.conrelid
                       JOIN pg_class pa     ON pa.oid = c.confrelid
                       JOIN pg_namespace n  ON n.oid  = ch.relnamespace
                      WHERE c.contype = 'f' AND n.nspname = current_schema()
                      ORDER BY 2, 3, 1
                     """)) {
            while (rs.next()) {
                out.add(new SnapshotRestorePlanner.ForeignKey(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
        }
        return out;
    }

    /** table → (column → sequence) for every serial / identity column the catalogue owns. */
    private Map<String, Map<String, String>> ownedSequences(Connection conn) throws SQLException {
        Map<String, Map<String, String>> out = new TreeMap<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                     SELECT t.relname AS table_name, a.attname AS column_name, s.relname AS sequence_name
                       FROM pg_depend d
                       JOIN pg_class s      ON s.oid = d.objid    AND s.relkind = 'S'
                       JOIN pg_class t      ON t.oid = d.refobjid
                       JOIN pg_attribute a  ON a.attrelid = t.oid AND a.attnum = d.refobjsubid
                       JOIN pg_namespace n  ON n.oid = t.relnamespace
                      WHERE d.classid = 'pg_class'::regclass AND d.refclassid = 'pg_class'::regclass
                        AND d.deptype IN ('a', 'i') AND n.nspname = current_schema()
                     """)) {
            while (rs.next()) {
                out.computeIfAbsent(rs.getString(1), k -> new TreeMap<>()).put(rs.getString(2), rs.getString(3));
            }
        }
        return out;
    }

    // ── Retention ─────────────────────────────────────────────────────────────

    /**
     * Drops snapshot tables older than the configured retention period.
     *
     * <p>With a retention of 6 months and today being 2026-08-18, the cutoff is 2026-02-18:
     * {@code app_user_bkp_20260217} is dropped, {@code app_user_bkp_20260218} is kept.
     * A retention of {@code 0} disables deletion entirely.
     *
     * <h4>The most recent snapshot is never dropped</h4>
     * Retention and backup interval are configured independently, and nothing stops someone
     * setting a 1-month retention with a 12-month interval. Read literally, that combination
     * deletes the only backup eleven months before the next one is taken, leaving the
     * database with no snapshot at all — the exact opposite of what a backup feature is for.
     * The newest snapshot is therefore always retained, however old it is, and the fact is
     * logged when it happens so it is visible rather than mysterious.
     *
     * <h4>Safety</h4>
     * Only tables matching {@code <source>_bkp_YYYYMMDD} are ever considered, the date must
     * parse as a real calendar date, and each name is re-validated against the pattern
     * immediately before the {@code DROP} — this method builds SQL by concatenation, so that
     * check is what stands between it and a table name from the catalogue it did not expect.
     *
     * @return the snapshot tables that were dropped
     */
    public List<String> purgeExpiredBackups(BackupConfig cfg) {
        int retentionMonths = cfg == null ? 0 : cfg.getRetentionMonths();
        if (retentionMonths <= 0) {
            log.debug("BackupService: retention disabled (retentionMonths={}), nothing purged", retentionMonths);
            return List.of();
        }

        Map<String, List<String>> byDate = findSnapshotsByDate();
        if (byDate.isEmpty()) return List.of();

        LocalDate cutoff = LocalDate.now().minusMonths(retentionMonths);
        List<String> expired = expiredSuffixes(byDate.keySet(), cutoff);

        // The last-remaining-copy rule, applied per source table rather than per snapshot
        // date. Keeping only the newest snapshot as a whole would still let a table lose its
        // final backup — a table present in an expiring snapshot but missing from newer ones
        // (added then removed, or skipped by a partial run) would be dropped with nothing
        // left. Protecting each table's own most recent copy makes the guarantee hold for
        // every table, which is what "consistently to all tables" requires.
        Map<String, String> newestCopyPerTable = newestCopyPerTable(byDate);

        List<String> dropped  = new ArrayList<>();
        List<String> failed   = new ArrayList<>();
        List<String> retained = new ArrayList<>();

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);   // each DROP stands alone; one failure must not block the rest
            try (Statement stmt = conn.createStatement()) {
                for (String suffix : expired) {
                    List<String> keptHere = new ArrayList<>();

                    for (String table : byDate.getOrDefault(suffix, List.of())) {
                        var matcher = BACKUP_TABLE_PATTERN.matcher(table);
                        if (!matcher.matches()) {
                            log.warn("BackupService: refusing to drop '{}' — not a snapshot table", table);
                            continue;
                        }
                        String source = matcher.group(1);

                        if (suffix.equals(newestCopyPerTable.get(source))) {
                            keptHere.add(source);
                            retained.add(table);
                            continue;
                        }

                        try {
                            stmt.execute("DROP TABLE IF EXISTS " + table);
                            dropped.add(table);
                        } catch (Exception ex) {
                            failed.add(table);
                            log.warn("BackupService: could not drop {}: {}", table, ex.getMessage());
                        }
                    }

                    // One line per expired snapshot rather than one per table: with ~155
                    // tables the per-table form would bury the message it exists to convey.
                    if (!keptHere.isEmpty()) {
                        log.info("BackupService: retention KEPT {} table(s) from expired snapshot {} — "
                               + "they are the most recent backup available for those tables, so deleting "
                               + "them would leave no backup at all (retention {} month(s), cutoff {}). "
                               + "Tables: {}",
                                keptHere.size(), suffix, retentionMonths, cutoff, summarise(keptHere));
                    }
                }
            }
        } catch (Exception connEx) {
            log.error("BackupService: could not obtain DB connection for retention purge", connEx);
            return List.of();
        }

        if (!dropped.isEmpty() || !retained.isEmpty()) {
            log.info("BackupService: retention purge complete — dropped {} snapshot table(s) older than {} "
                   + "({}-month retention), retained {} as last-remaining copies{}",
                    dropped.size(), cutoff, retentionMonths, retained.size(),
                    failed.isEmpty() ? "" : ", " + failed.size() + " could not be dropped");
        }
        return dropped;
    }

    /**
     * For each source table, the most recent snapshot suffix that holds a copy of it.
     *
     * <p>Pure function over the snapshot catalogue, so the last-remaining-copy rule can be
     * tested directly rather than inferred from what did or did not get deleted.
     *
     * @param snapshotsByDate date suffix → snapshot tables carrying it
     * @return source table name → newest suffix containing that table
     */
    static Map<String, String> newestCopyPerTable(Map<String, List<String>> snapshotsByDate) {
        Map<String, String> newest = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : snapshotsByDate.entrySet()) {
            String suffix = entry.getKey();
            for (String table : entry.getValue()) {
                var matcher = BACKUP_TABLE_PATTERN.matcher(table);
                if (!matcher.matches()) continue;
                String source = matcher.group(1);
                // YYYYMMDD sorts lexicographically in date order.
                newest.merge(source, suffix, (a, b) -> a.compareTo(b) >= 0 ? a : b);
            }
        }
        return newest;
    }

    /** Keeps a long table list readable in a single log line. */
    private static String summarise(List<String> names) {
        final int limit = 8;
        if (names.size() <= limit) return String.join(", ", names);
        return String.join(", ", names.subList(0, limit)) + " … (+" + (names.size() - limit) + " more)";
    }

    /**
     * Decides which snapshot date suffixes are older than the cutoff. Pure function — no
     * database, no clock — so the rule can be tested directly instead of inferred from
     * behaviour.
     *
     * <p>Worked example from the requirement: retention 6 months, today 2026-08-18, so
     * {@code cutoff = 2026-02-18}. {@code 20260217} is strictly before the cutoff and
     * expires; {@code 20260218} is not and is kept.
     *
     * <p>This answers only "is it old?". Whether an expired snapshot's tables may actually
     * be dropped is decided separately by {@link #newestCopyPerTable}, which protects each
     * table's last remaining copy.
     *
     * @param suffixes all known {@code YYYYMMDD} snapshot suffixes
     * @param cutoff   snapshots strictly before this date have expired
     * @return the expired suffixes, oldest first
     */
    static List<String> expiredSuffixes(Collection<String> suffixes, LocalDate cutoff) {
        List<String> expired = new ArrayList<>();
        for (String suffix : suffixes) {
            try {
                if (LocalDate.parse(suffix, SUFFIX_FORMAT).isBefore(cutoff)) {
                    expired.add(suffix);
                }
            } catch (Exception ignored) {
                // A table named like a snapshot but carrying an impossible date is left alone.
            }
        }
        Collections.sort(expired);   // lexicographic == chronological for YYYYMMDD
        return expired;
    }

    // ── Scheduler ─────────────────────────────────────────────────────────────

    /**
     * Runs once every day at 02:00 AM and executes the backup if today matches
     * (or has passed) the configured {@code nextRunDate}.
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void scheduledBackup() {
        BackupConfig cfg = getConfig();

        boolean backupDue = cfg.getIntervalMonths() > 0
                && cfg.getNextRunDate() != null
                && !LocalDate.now().isBefore(cfg.getNextRunDate());

        if (backupDue) {
            log.info("BackupService: scheduled backup triggered (nextRunDate={})", cfg.getNextRunDate());
            runBackup(cfg);   // runBackup purges as part of the same run
            return;
        }

        // Retention is enforced daily even when scheduled backups are disabled. Someone who
        // turns backups off still expects the snapshots already sitting in their database to
        // age out rather than accumulate for ever.
        purgeExpiredBackups(cfg);
    }

    // ── Core backup logic ─────────────────────────────────────────────────────

    /**
     * Immediately executes a backup for the given config, regardless of schedule.
     *
     * <p>All table copies of one run are taken in a single {@code REPEATABLE READ}
     * transaction, so every {@code <table>_bkp_<date>} shows the database as it was at
     * one instant: a child row copied at the end of the run cannot reference a parent
     * row that did not yet exist when the parent was copied at the start — which is
     * what would make a later restore fail on a foreign key (database audit C2). A
     * failure on one table still does not abort the others: each copy runs under its
     * own savepoint and only that table is rolled back.
     */
    public BackupLog runBackup(BackupConfig cfg) {
        String dateSuffix   = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        List<String> tables = resolveTables(cfg.getTableScope());
        List<String> backed = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);   // one instant for every table
            try (Statement stmt = conn.createStatement()) {
                for (String table : tables) {
                    String backupTable = table + "_bkp_" + dateSuffix;
                    Savepoint savepoint = conn.setSavepoint();
                    try {
                        stmt.execute("DROP TABLE IF EXISTS " + backupTable);
                        stmt.execute("CREATE TABLE " + backupTable + " AS SELECT * FROM " + table);
                        conn.releaseSavepoint(savepoint);
                        backed.add(backupTable);
                        log.info("BackupService: created {}", backupTable);
                    } catch (Exception ex) {
                        conn.rollback(savepoint);               // this table only; the run goes on
                        String shortMsg = ex.getMessage() == null ? "unknown error"
                                : ex.getMessage().lines().findFirst().orElse(ex.getMessage());
                        failed.add(table + " (" + shortMsg + ")");
                        log.warn("BackupService: failed to back up table {}: {}", table, shortMsg);
                    }
                }
            }
            try {
                conn.commit();
            } catch (Exception commitEx) {
                // Nothing was kept: every copy of this run is gone, not some of them.
                log.error("BackupService: could not commit backup {}", dateSuffix, commitEx);
                failed.add("commit failed, no snapshot kept (" + commitEx.getMessage() + ")");
                backed.clear();
            }
        } catch (Exception connEx) {
            log.error("BackupService: could not obtain DB connection", connEx);
            failed.add("DB connection failed: " + connEx.getMessage());
        }

        // Purge only AFTER a successful snapshot, so a failed backup can never be the run
        // that deletes the previous good one.
        List<String> purged = backed.isEmpty() ? List.of() : purgeExpiredBackups(cfg);

        // Build result
        String status  = failed.isEmpty() ? "SUCCESS" : (backed.isEmpty() ? "FAILURE" : "PARTIAL");
        String message = buildMessage(backed, failed, purged, cfg.getRetentionMonths());

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
            default       -> discoverAllTables();
        };
    }

    /**
     * Every base table in the {@code public} schema, minus snapshots and the exclusions in
     * {@link #EXCLUDED_TABLES}.
     *
     * <p>Discovered at run time rather than listed in code, so a table added next month is
     * backed up next month without anyone remembering this class exists.
     *
     * @return sorted table names; empty if the catalogue cannot be read, which callers treat
     *         as "back nothing up" rather than risking a partial-looking success
     */
    List<String> discoverAllTables() {
        List<String> tables = new ArrayList<>();
        String sql = """
                SELECT table_name
                  FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_type   = 'BASE TABLE'
                   AND table_name !~ '_bkp_[0-9]{8}$'
                 ORDER BY table_name
                """;
        try (Connection conn = dataSource.getConnection();
             Statement  stmt = conn.createStatement();
             java.sql.ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString("table_name");
                if (!EXCLUDED_TABLES.contains(name)) {
                    tables.add(name);
                }
            }
        } catch (Exception ex) {
            log.error("BackupService: could not enumerate tables to back up", ex);
            return List.of();
        }
        log.info("BackupService: discovered {} table(s) to back up", tables.size());
        return tables;
    }

    /**
     * All snapshot tables in the database, grouped by their {@code YYYYMMDD} suffix.
     *
     * @return date suffix → the snapshot tables carrying it, newest date first
     */
    Map<String, List<String>> findSnapshotsByDate() {
        Map<String, List<String>> byDate = new TreeMap<>(Comparator.reverseOrder());
        String sql = """
                SELECT table_name
                  FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_type   = 'BASE TABLE'
                   AND table_name ~ '_bkp_[0-9]{8}$'
                """;
        try (Connection conn = dataSource.getConnection();
             Statement  stmt = conn.createStatement();
             java.sql.ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString("table_name");
                var matcher = BACKUP_TABLE_PATTERN.matcher(name);
                if (matcher.matches()) {
                    byDate.computeIfAbsent(matcher.group(2), k -> new ArrayList<>()).add(name);
                }
            }
        } catch (Exception ex) {
            log.warn("BackupService: could not enumerate snapshots: {}", ex.getMessage());
        }
        return byDate;
    }

    private String buildMessage(List<String> backed, List<String> failed,
                                List<String> purged, int retentionMonths) {
        StringBuilder sb = new StringBuilder();
        sb.append(backed.size()).append(" table(s) backed up successfully.");
        if (!failed.isEmpty()) {
            sb.append(" FAILED: ").append(String.join("; ", failed)).append(".");
        }
        if (!purged.isEmpty()) {
            sb.append(" Retention: dropped ").append(purged.size())
              .append(" snapshot table(s) older than ").append(retentionMonths).append(" month(s).");
        }
        // The full table list runs to ~155 names now that discovery is dynamic, which would
        // dominate both the log row and the notification email. The count is the useful part.
        if (!backed.isEmpty()) {
            sb.append(" Snapshot suffix: ")
              .append(backed.get(0).substring(backed.get(0).lastIndexOf("_bkp_") + 5)).append(".");
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
