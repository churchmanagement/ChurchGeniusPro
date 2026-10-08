package com.churchgeniuspro.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/**
 * Reads the Accountant dashboard's aggregates from {@code ledger_month_summary}
 * (Flyway V5) instead of the income/expense tables.
 *
 * <p>The summary holds one row per (church, month, kind, fund), kept in step with
 * the ledger by database triggers in the same transaction as every write, so these
 * figures equal what a SUM over the ledger rows would give — at a few thousand rows
 * per church instead of hundreds of thousands. Benchmark (800K income / 120K
 * expense rows): the dashboard's database time ≈0.5 s → ≈60 ms.
 *
 * <p>Row shapes match the repository aggregates they replace (see
 * {@code IncomeRepository} / {@code ExpenseRepository}), so the controller code
 * that builds the dashboard JSON is unchanged. Reports keep reading the ledger
 * rows: they need transaction-level detail, and the rows are the source of truth.
 *
 * <p>Income summary rows carry {@code sub_source_id} only (fund resolved through
 * {@code sub_source}, so re-parenting a sub-source never stales the summary);
 * expense rows carry {@code main_source_id} and {@code purpose_id}. Plain JDBC, no
 * JPA entity: Hibernate's {@code ddl-auto} must never manage this table.
 * Ledger scalability, part B.
 */
@Service
public class LedgerSummaryService {

    private static final RowMapper<Object[]> ROW = (rs, i) -> {
        int n = rs.getMetaData().getColumnCount();
        Object[] row = new Object[n];
        for (int c = 1; c <= n; c++) row[c - 1] = rs.getObject(c);
        return row;
    };

    private final JdbcTemplate jdbc;

    public LedgerSummaryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── All-time ──────────────────────────────────────────────────────────

    /** Grand total of active income; null when the church has none (caller treats as zero). */
    public BigDecimal totalIncome(String appClientId) {
        return jdbc.queryForObject(
                "SELECT SUM(total_amount) FROM ledger_month_summary WHERE app_client_id = ? AND kind = 'I'",
                BigDecimal.class, appClientId);
    }

    /** Rows of [sub_id, sub_name, main_id, main_name, total], by main then sub name. */
    public List<Object[]> incomeBySubCategory(String appClientId) {
        return jdbc.query(
                "SELECT ss.id, ss.source_name, ms.id, ms.source_name, SUM(s.total_amount) " +
                "FROM   ledger_month_summary s " +
                "JOIN   sub_source  ss ON ss.id = s.sub_source_id " +
                "JOIN   main_source ms ON ms.id = ss.main_source_id " +
                "WHERE  s.app_client_id = ? AND s.kind = 'I' " +
                "GROUP  BY ss.id, ss.source_name, ms.id, ms.source_name " +
                "ORDER  BY ms.source_name ASC, ss.source_name ASC",
                ROW, appClientId);
    }

    /** Rows of [main_id, main_name, total], by main name. */
    public List<Object[]> expenseByMainSource(String appClientId) {
        return jdbc.query(
                "SELECT ms.id, ms.source_name, SUM(s.total_amount) " +
                "FROM   ledger_month_summary s " +
                "JOIN   main_source ms ON ms.id = s.main_source_id " +
                "WHERE  s.app_client_id = ? AND s.kind = 'E' " +
                "GROUP  BY ms.id, ms.source_name " +
                "ORDER  BY ms.source_name ASC",
                ROW, appClientId);
    }

    /** Rows of [purpose_id, purpose_name, total], largest first. */
    public List<Object[]> expenseByPurpose(String appClientId) {
        return jdbc.query(
                "SELECT p.id, p.purpose_name, SUM(s.total_amount) AS total " +
                "FROM   ledger_month_summary s " +
                "JOIN   purpose p ON p.id = s.purpose_id " +
                "WHERE  s.app_client_id = ? AND s.kind = 'E' " +
                "GROUP  BY p.id, p.purpose_name " +
                "ORDER  BY total DESC",
                ROW, appClientId);
    }

    // ── One calendar year ─────────────────────────────────────────────────

    /** Rows of [purpose_id, purpose_name, total] for the year, largest first. */
    public List<Object[]> expenseByPurposeForYear(int year, String appClientId) {
        return jdbc.query(
                "SELECT p.id, p.purpose_name, SUM(s.total_amount) AS total " +
                "FROM   ledger_month_summary s " +
                "JOIN   purpose p ON p.id = s.purpose_id " +
                "WHERE  s.app_client_id = ? AND s.kind = 'E' AND s.period_month >= ? AND s.period_month < ? " +
                "GROUP  BY p.id, p.purpose_name " +
                "ORDER  BY total DESC",
                ROW, appClientId, yearStart(year), nextYearStart(year));
    }

    /** Rows of [month 1-12, total]. */
    public List<Object[]> incomeByMonthForYear(int year, String appClientId) {
        return jdbc.query(
                "SELECT EXTRACT(MONTH FROM s.period_month)::int AS m, SUM(s.total_amount) " +
                "FROM   ledger_month_summary s " +
                "WHERE  s.app_client_id = ? AND s.kind = 'I' AND s.period_month >= ? AND s.period_month < ? " +
                "GROUP  BY m ORDER BY m",
                ROW, appClientId, yearStart(year), nextYearStart(year));
    }

    /** Rows of [month 1-12, sub_id, sub_name, total]. */
    public List<Object[]> incomeBySubCategoryAndMonthForYear(int year, String appClientId) {
        return jdbc.query(
                "SELECT EXTRACT(MONTH FROM s.period_month)::int AS m, ss.id, ss.source_name, SUM(s.total_amount) " +
                "FROM   ledger_month_summary s " +
                "JOIN   sub_source ss ON ss.id = s.sub_source_id " +
                "WHERE  s.app_client_id = ? AND s.kind = 'I' AND s.period_month >= ? AND s.period_month < ? " +
                "GROUP  BY m, ss.id, ss.source_name " +
                "ORDER  BY m ASC, ss.source_name ASC",
                ROW, appClientId, yearStart(year), nextYearStart(year));
    }

    /** Rows of [month 1-12, main_id, main_name, total]. */
    public List<Object[]> expenseByMainSourceAndMonthForYear(int year, String appClientId) {
        return jdbc.query(
                "SELECT EXTRACT(MONTH FROM s.period_month)::int AS m, ms.id, ms.source_name, SUM(s.total_amount) " +
                "FROM   ledger_month_summary s " +
                "JOIN   main_source ms ON ms.id = s.main_source_id " +
                "WHERE  s.app_client_id = ? AND s.kind = 'E' AND s.period_month >= ? AND s.period_month < ? " +
                "GROUP  BY m, ms.id, ms.source_name " +
                "ORDER  BY m ASC, ms.source_name ASC",
                ROW, appClientId, yearStart(year), nextYearStart(year));
    }

    /** Rows of [month 1-12, purpose_id, purpose_name, total]. */
    public List<Object[]> expenseByPurposeAndMonthForYear(int year, String appClientId) {
        return jdbc.query(
                "SELECT EXTRACT(MONTH FROM s.period_month)::int AS m, p.id, p.purpose_name, SUM(s.total_amount) " +
                "FROM   ledger_month_summary s " +
                "JOIN   purpose p ON p.id = s.purpose_id " +
                "WHERE  s.app_client_id = ? AND s.kind = 'E' AND s.period_month >= ? AND s.period_month < ? " +
                "GROUP  BY m, p.id, p.purpose_name " +
                "ORDER  BY m ASC, p.purpose_name ASC",
                ROW, appClientId, yearStart(year), nextYearStart(year));
    }

    // ── Maintenance (SQL functions from V5) ───────────────────────────────

    /** Regenerates one church's summary rows from the ledger; returns rows written. */
    public int rebuild(String appClientId) {
        Integer n = jdbc.queryForObject("SELECT ledger_summary_rebuild(?)", Integer.class, appClientId);
        return n == null ? 0 : n;
    }

    /** One summary key whose stored figures differ from the ledger rows. */
    public record Mismatch(String appClientId, LocalDate periodMonth, String kind,
                           int mainSourceId, int subSourceId, int purposeId,
                           BigDecimal summaryAmount, BigDecimal actualAmount,
                           int summaryCount, int actualCount) {}

    /** Every summary key, across all churches, that disagrees with the ledger rows. */
    public List<Mismatch> reconcile() {
        return jdbc.query("SELECT * FROM ledger_summary_reconcile()", (rs, i) -> new Mismatch(
                rs.getString("app_client_id"), rs.getDate("period_month").toLocalDate(), rs.getString("kind"),
                rs.getInt("main_source_id"), rs.getInt("sub_source_id"), rs.getInt("purpose_id"),
                rs.getBigDecimal("summary_amount"), rs.getBigDecimal("actual_amount"),
                rs.getInt("summary_count"), rs.getInt("actual_count")));
    }

    private static Date yearStart(int year)     { return Date.valueOf(LocalDate.of(year, 1, 1)); }
    private static Date nextYearStart(int year) { return Date.valueOf(LocalDate.of(year + 1, 1, 1)); }
}
