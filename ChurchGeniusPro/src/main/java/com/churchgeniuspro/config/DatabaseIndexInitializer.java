package com.churchgeniuspro.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/**
 * Creates performance-critical database indexes if they don't already exist.
 *
 * <p>All statements use {@code CREATE INDEX IF NOT EXISTS} so they are safe to
 * run on every startup — they are no-ops when the index already exists.
 *
 * <p>These indexes support the two main hot-path queries on the viewfamily page:
 * <ul>
 *   <li>{@code findFamilyListProjection} — filters on {@code family.app_client_id},
 *       {@code family.delete_flag}, and joins to {@code family_member.family_id}.</li>
 *   <li>{@code findPrimaryMemberPhotosByAppUser} — filters on {@code family_member.delete_flag}
 *       and {@code family.app_client_id}.</li>
 * </ul>
 *
 * <p>They also cover the failed-login rate limiter
 * ({@code com.churchgeniuspro.service.LoginProtectionService}), whose counting queries
 * run on every failed authentication attempt.
 */
@Component
@DependsOn("entityManagerFactory")   // after Hibernate's own schema update, so the tables exist (database audit M5)
public class DatabaseIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(DatabaseIndexInitializer.class);

    private final JdbcTemplate jdbc;

    public DatabaseIndexInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void createIndexes() {
        String[] ddl = {
            // family table
            "CREATE INDEX IF NOT EXISTS idx_family_app_client_id ON family(app_client_id)",
            "CREATE INDEX IF NOT EXISTS idx_family_delete_flag   ON family(delete_flag)",
            "CREATE INDEX IF NOT EXISTS idx_family_inactive       ON family(inactive)",

            // family_member table
            "CREATE INDEX IF NOT EXISTS idx_fm_family_id      ON family_member(family_id)",
            "CREATE INDEX IF NOT EXISTS idx_fm_app_client_id  ON family_member(app_client_id)",
            "CREATE INDEX IF NOT EXISTS idx_fm_delete_flag    ON family_member(delete_flag)",
            "CREATE INDEX IF NOT EXISTS idx_fm_role           ON family_member(lower(role))",

            // login_attempt_log — every failed login runs three counting queries against
            // this table, and it is the largest table the auth path touches. Without these
            // the rate limiter degrades into a sequential scan under exactly the load
            // (an active brute-force attack) where it must stay fast.
            "CREATE INDEX IF NOT EXISTS idx_lal_user_ip_time  ON login_attempt_log(username, ip_address, attempted_at)",
            "CREATE INDEX IF NOT EXISTS idx_lal_ip_time       ON login_attempt_log(ip_address, attempted_at)",
            "CREATE INDEX IF NOT EXISTS idx_lal_user_time     ON login_attempt_log(username, attempted_at)",
            "CREATE INDEX IF NOT EXISTS idx_lal_attempted_at  ON login_attempt_log(attempted_at)",

            // login_block — read once on every single login attempt (the hot path).
            "CREATE INDEX IF NOT EXISTS idx_lb_key_until      ON login_block(scope_key, blocked_until)",
            "CREATE INDEX IF NOT EXISTS idx_lb_username       ON login_block(username)",
            "CREATE INDEX IF NOT EXISTS idx_lb_blocked_until  ON login_block(blocked_until)",

            // Per-tenant security reporting: "show this church its sign-ins for last month".
            // Also in migrate_production.sql; created here too so the index cannot go missing
            // just because the manual migration step was skipped.
            "CREATE INDEX IF NOT EXISTS idx_lal_client_outcome_time ON login_attempt_log(client_id, outcome, attempted_at)",

            // Financial audit H8: enforces that an imported (Bank Import / Plaid) row's
            // import_ref can be used by at most one active income/expense row per church —
            // the real, unbypassable half of duplicate-import prevention (the application
            // layer's checks are the advisory, user-facing half). Partial — excludes
            // manual entries (import_ref IS NULL) and soft-deleted rows, so Postgres's
            // "NULL never equals NULL" rule isn't the only thing keeping manual entries
            // unaffected, and deleting a bad import frees its import_ref for re-import.
            // Also in migrate_production.sql; created here too for the same reason as above.
            "CREATE UNIQUE INDEX IF NOT EXISTS ux_income_import_ref  ON income  (app_client_id, import_ref) "
                + "WHERE delete_flag = false AND import_ref IS NOT NULL",
            "CREATE UNIQUE INDEX IF NOT EXISTS ux_expense_import_ref ON expense (app_client_id, import_ref) "
                + "WHERE delete_flag = false AND import_ref IS NOT NULL",

            // Ledger scalability, part A (Flyway V4): partial covering indexes that let
            // every dashboard aggregate and year-scoped report read one church's rows by
            // date, answered from the index alone. V4 is the primary home for these; they
            // are repeated here so a FRESH database (where Flyway ran V4 before Hibernate
            // had created income/expense, recording it as applied) still gets them on the
            // next startup. H2 (fast test profile) rejects INCLUDE, which the try/catch
            // below turns into a warning rather than a failed start.
            "CREATE INDEX IF NOT EXISTS idx_income_tenant_date  ON income  (app_client_id, income_date) "
                + "INCLUDE (amount, sub_source_id) WHERE delete_flag = false",
            "CREATE INDEX IF NOT EXISTS idx_expense_tenant_date ON expense (app_client_id, expense_date) "
                + "INCLUDE (amount, main_source_id, purpose_id) WHERE delete_flag = false",

            // Financial audit H3: the real, unbypassable half of the duplicate-paystub
            // guard (PayrollService#processEmployee's existsByRunIdAndEmployeeIdAndVoidedFalse
            // check is the advisory, fail-fast half) — at most one non-voided paystub per
            // (run, employee). run_id already identifies a single tenant's payroll_run row,
            // so no app_client_id column is needed for tenant isolation here, unlike the
            // import_ref indexes above. Partial on voided=false so voiding a whole run (every
            // stub in it) never blocks that run from being corrected and re-processed.
            // Also in migrate_production.sql; created here too so the index cannot go missing
            // just because the manual migration step was skipped.
            "CREATE UNIQUE INDEX IF NOT EXISTS ux_paystub_run_employee ON payroll_paystub (run_id, employee_id) "
                + "WHERE voided = false",
        };

        int created = 0;
        for (String sql : ddl) {
            try {
                jdbc.execute(sql);
                created++;
            } catch (Exception ex) {
                log.warn("Index DDL skipped ({}): {}", ex.getMessage(), sql);
            }
        }
        log.info("DatabaseIndexInitializer: {} index statement(s) executed.", created);
    }
}
