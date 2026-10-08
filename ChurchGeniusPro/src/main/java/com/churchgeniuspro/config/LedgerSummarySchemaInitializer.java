package com.churchgeniuspro.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Applies the ledger_month_summary schema (table, triggers, rebuild/reconcile
 * functions) after Hibernate's schema update on every startup.
 *
 * <p>Flyway V5 is the primary home for that schema, but on a FRESH database Flyway
 * runs before Hibernate has created {@code income}/{@code expense}, so V5 no-ops
 * (its triggers need those tables) and Flyway records it as applied. This
 * component runs the very same migration file again once the tables exist. The
 * file is written to be re-runnable: CREATE … IF NOT EXISTS / CREATE OR REPLACE /
 * DROP TRIGGER IF EXISTS, and it backfills only when the summary table is empty,
 * so on an already-migrated database this is a few cheap DDL statements and
 * nothing else. Ledger scalability, part B.
 *
 * <p>The SQL is PostgreSQL-only (DO blocks, triggers). On the H2 fast-test profile
 * it fails on the first statement; that is caught and logged as a warning so the
 * context still boots — the summary is simply absent there, as the H6/V4 indexes
 * already are.
 */
@Component
@DependsOn("entityManagerFactory")   // after Hibernate's own schema update, so income/expense exist
public class LedgerSummarySchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(LedgerSummarySchemaInitializer.class);

    /** Single source of truth — the Flyway migration itself. */
    static final String MIGRATION = "db/migration/V5__ledger_month_summary.sql";

    private final JdbcTemplate jdbc;

    public LedgerSummarySchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void apply() {
        String sql;
        try {
            sql = new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            log.error("LedgerSummarySchemaInitializer: cannot read {}: {}", MIGRATION, ex.getMessage());
            return;
        }
        try {
            jdbc.execute(sql);
            log.info("LedgerSummarySchemaInitializer: ledger_month_summary schema verified (V5).");
        } catch (Exception ex) {
            log.warn("LedgerSummarySchemaInitializer: skipped ({}). The Accountant dashboard needs "
                     + "ledger_month_summary — on PostgreSQL this must not happen.", ex.getMessage());
        }
    }
}
