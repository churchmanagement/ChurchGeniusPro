package com.churchgeniuspro.hibernate;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Tiny startup migration for the {@code km_checkin} table.
 *
 * <p>Two changes are needed beyond what Hibernate's {@code ddl-auto=update} will
 * do on its own:
 * <ol>
 *   <li>Drop the {@code NOT NULL} constraint on {@code child_id} — public
 *       /kidsCheckin guardian-only rows have no child reference. Hibernate
 *       happily adds new columns but never drops an existing NOT NULL.</li>
 *   <li>Defensively add the new {@code guardian_member_id} and
 *       {@code family_checkin_code} columns in case Hibernate's schema update
 *       missed them on a previous boot.</li>
 * </ol>
 *
 * <p>Each statement runs on a fresh JDBC connection from the DataSource, in
 * auto-commit mode — DDL needs to be committed individually so a single failure
 * doesn't roll back the others. Failures are logged at INFO so an idempotent
 * re-run (constraint already dropped, column already present) is visible but
 * not alarming.
 */
@Component
@DependsOn("entityManagerFactory")   // after Hibernate's own schema update, so the tables exist (database audit M5)
public class KmCheckinSchemaMigrator {

    private static final Logger log = LoggerFactory.getLogger(KmCheckinSchemaMigrator.class);

    private final DataSource dataSource;

    public KmCheckinSchemaMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void migrate() {
        runOnFreshConnection("ALTER TABLE km_checkin ADD COLUMN IF NOT EXISTS guardian_member_id INTEGER");
        runOnFreshConnection("ALTER TABLE km_checkin ADD COLUMN IF NOT EXISTS family_checkin_code VARCHAR(20)");
        // The NOT NULL drop is the load-bearing one for the public /kidsCheckin
        // flow. Run it last so the new columns exist even if this fails (it'll
        // only fail if the constraint was already dropped — harmless).
        runOnFreshConnection("ALTER TABLE km_checkin ALTER COLUMN child_id DROP NOT NULL");
    }

    private void runOnFreshConnection(String sql) {
        try (Connection conn = dataSource.getConnection();
             Statement  stmt = conn.createStatement()) {
            // Auto-commit so each DDL is its own committed unit, matching
            // PostgreSQL DDL semantics.
            conn.setAutoCommit(true);
            stmt.execute(sql);
            log.info("km_checkin migration applied: {}", sql);
        } catch (SQLException ex) {
            // "column already exists" / "column is already nullable" both throw
            // here — idempotent re-runs land in this branch and are expected.
            log.info("km_checkin migration step skipped ({}): {}", sql, ex.getMessage());
        }
    }
}
