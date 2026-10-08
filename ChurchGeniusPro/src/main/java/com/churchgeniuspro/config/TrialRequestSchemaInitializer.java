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
 * Applies V8 (one open Trial Request per email: the partial unique index on
 * {@code lower(email)}) after Hibernate's schema update on every startup.
 *
 * <p>Same reason and shape as {@link LedgerSummarySchemaInitializer}: on a FRESH
 * database Flyway runs before Hibernate creates {@code trial_request}, so V8 no-ops
 * and is recorded as applied. This runs the very same file once the table exists.
 * The file is re-runnable (IF NOT EXISTS checks), so on a migrated database it is a
 * cheap no-op. PostgreSQL-only: on the H2 test profile it fails and is logged as a
 * warning; {@code TrialRequestService} still refuses duplicates without the index.
 */
@Component
@DependsOn("entityManagerFactory")   // after Hibernate's own schema update, so trial_request exists
public class TrialRequestSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(TrialRequestSchemaInitializer.class);

    /** Single source of truth — the Flyway migration itself. */
    static final String MIGRATION = "db/migration/V8__trial_request_open_email_unique.sql";

    private final JdbcTemplate jdbc;

    public TrialRequestSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void apply() {
        String sql;
        try {
            sql = new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            log.error("TrialRequestSchemaInitializer: cannot read {}: {}", MIGRATION, ex.getMessage());
            return;
        }
        try {
            jdbc.execute(sql);
            log.info("TrialRequestSchemaInitializer: trial_request open-email index verified (V8).");
        } catch (Exception ex) {
            log.warn("TrialRequestSchemaInitializer: skipped ({}). Duplicate trial requests are still "
                     + "refused by TrialRequestService.", ex.getMessage());
        }
    }
}
