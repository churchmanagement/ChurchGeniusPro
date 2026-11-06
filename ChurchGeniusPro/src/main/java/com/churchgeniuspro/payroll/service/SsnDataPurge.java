package com.churchgeniuspro.payroll.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Deletes stored SSN data (user decision 2026-11-06: "Don't store SSN… You can delete existing
 * SSN stored database data."). ChurchGeniusPro no longer maps, accepts, prints or encrypts an
 * SSN; this runner clears any value still left in the old {@code payroll_employee.ssn_last4}
 * column, on every start, once the application is up. Idempotent: after the first run it
 * updates nothing. Logs a row COUNT only — never a value, employee or church.
 *
 * <p>The column itself is left in place (dropping it is a schema change and needs a separate
 * decision; the guarded block in {@code scripts/sql/migrate_production.sql} has the statement).
 * A database without the column (new installs, or after the drop) is skipped.
 */
@Component
@Order(50)
public class SsnDataPurge implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SsnDataPurge.class);

    static final String COLUMN_EXISTS =
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'payroll_employee' AND column_name = 'ssn_last4'";
    static final String CLEAR = "UPDATE payroll_employee SET ssn_last4 = NULL WHERE ssn_last4 IS NOT NULL";

    private final JdbcTemplate jdbc;

    public SsnDataPurge(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        purge();
    }

    /** @return rows cleared, 0 when nothing was left, -1 when the column does not exist or the purge failed. */
    public int purge() {
        try {
            Integer n = jdbc.queryForObject(COLUMN_EXISTS, Integer.class);
            if (n == null || n == 0) return -1;
            int cleared = jdbc.update(CLEAR);
            if (cleared > 0) log.info("[Payroll] cleared stored SSN data from {} employee row(s) — SSN is no longer stored", cleared);
            return cleared;
        } catch (RuntimeException e) {
            // never stops the application; logged by class name only, retried on the next start
            log.warn("[Payroll] SSN data purge did not run: {}", e.getClass().getSimpleName());
            return -1;
        }
    }
}
