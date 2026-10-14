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
 * Applies V13 (Manual and Automatic worship assignments own separate rows: the
 * per-workflow partial unique index, the {@code skipped} flag and
 * {@code worship_song.schedule_type}) after Hibernate's schema update on every
 * startup.
 *
 * <p>Same reason and shape as {@link TrialRequestSchemaInitializer}: on a FRESH
 * database Flyway runs before Hibernate creates the worship tables, so V13 no-ops
 * and is recorded as applied. This runs the very same file once the tables exist.
 * The file is re-runnable (IF NOT EXISTS checks), so on a migrated database it is a
 * cheap no-op. PostgreSQL-only: on the H2 test profile it fails and is logged as a
 * warning; the controller's type-aware upsert still keeps the workflows apart
 * without the index.
 */
@Component
@DependsOn("entityManagerFactory")   // after Hibernate's own schema update, so the tables exist
public class WorshipSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(WorshipSchemaInitializer.class);

    /** Single source of truth — the Flyway migration itself. */
    static final String MIGRATION = "db/migration/V13__worship_assignment_per_workflow_key.sql";

    private final JdbcTemplate jdbc;

    public WorshipSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void apply() {
        String sql;
        try {
            sql = new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            log.error("WorshipSchemaInitializer: cannot read {}: {}", MIGRATION, ex.getMessage());
            return;
        }
        try {
            jdbc.execute(sql);
            log.info("WorshipSchemaInitializer: worship_assignment per-workflow key verified (V13).");
        } catch (Exception ex) {
            log.warn("WorshipSchemaInitializer: skipped ({}). Manual/Automatic rows are still kept "
                     + "apart by the type-aware upsert in WorshipPlanningController.", ex.getMessage());
        }
    }
}
