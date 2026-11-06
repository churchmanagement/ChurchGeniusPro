package com.churchgeniuspro.payroll.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deletes payroll identifiers ChurchGeniusPro no longer keeps (user decisions 2026-11-06:
 * "Don't store SSN… You can delete existing SSN stored database data." and, for App Store
 * privacy, the bank account / routing last-4 the same way).
 *
 * <p>No entity maps these columns any more; this runner blanks whatever is still left in them,
 * on every start, once the application is up:
 * {@code payroll_employee.ssn_last4}, {@code payroll_employee.dd_account_last4},
 * {@code payroll_employee.dd_routing_last4} and {@code paystub.direct_deposit_account_last4}.
 * Idempotent: after the first run it updates nothing. Logs row COUNTS only — never a value,
 * employee or church. A column that does not exist (new installs, or after a later drop) is
 * skipped. The columns themselves stay until a separate decision drops them (statements in
 * {@code scripts/sql/migrate_production.sql}, commented).
 */
@Component
@Order(50)
public class PayrollSensitiveDataPurge implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PayrollSensitiveDataPurge.class);

    /** table → column, cleared in this order. */
    static final List<String[]> TARGETS = List.of(
            new String[]{"payroll_employee", "ssn_last4"},
            new String[]{"payroll_employee", "dd_account_last4"},
            new String[]{"payroll_employee", "dd_routing_last4"},
            new String[]{"paystub", "direct_deposit_account_last4"});

    static final String COLUMN_EXISTS =
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = ? AND column_name = ?";

    static String clearSql(String table, String column) {
        return "UPDATE " + table + " SET " + column + " = NULL WHERE " + column + " IS NOT NULL";
    }

    private final JdbcTemplate jdbc;

    public PayrollSensitiveDataPurge(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        purge();
    }

    /** @return column ("table.column") → rows cleared; -1 when the column does not exist or the clear failed. */
    public Map<String, Integer> purge() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String[] t : TARGETS) out.put(t[0] + "." + t[1], clear(t[0], t[1]));
        return out;
    }

    int clear(String table, String column) {
        try {
            Integer n = jdbc.queryForObject(COLUMN_EXISTS, Integer.class, table, column);
            if (n == null || n == 0) return -1;
            int cleared = jdbc.update(clearSql(table, column));
            if (cleared > 0) log.info("[Payroll] cleared {} from {} row(s) — no longer stored", table + "." + column, cleared);
            return cleared;
        } catch (RuntimeException e) {
            // never stops the application; logged by class name only, retried on the next start
            log.warn("[Payroll] clearing {}.{} did not run: {}", table, column, e.getClass().getSimpleName());
            return -1;
        }
    }
}
