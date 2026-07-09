package com.churchgeniuspro.hibernate;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Startup migration for the {@code prayer_volunteer} table.
 *
 * <p>Required because Hibernate's {@code ddl-auto=update} only ever <em>adds</em>
 * columns and constraints — it never relaxes existing ones. The original
 * table was created with {@code family_member_id NOT NULL}; the v2 design
 * makes that column nullable so manually-added (non-member) volunteers can
 * be persisted. Without this migration, every manual-add 500s with a
 * not-null violation.
 *
 * <p>The {@code ADD COLUMN IF NOT EXISTS} statements defensively materialise
 * the new optional fields too, since some boot orderings have shown Hibernate
 * skipping its own DDL when the entity is touched lazily.
 *
 * <p>Each statement runs on a fresh JDBC connection in auto-commit so a
 * single failure (e.g. constraint already dropped on re-run) doesn't take
 * the others down.
 */
@Component
public class PrayerVolunteerSchemaMigrator {

    private static final Logger log = LoggerFactory.getLogger(PrayerVolunteerSchemaMigrator.class);

    private final DataSource dataSource;

    public PrayerVolunteerSchemaMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void migrate() {
        // 1. Make sure the table exists at all — Hibernate normally handles this,
        //    but the explicit guard keeps the IF EXISTS dance below safe even on
        //    a fresh schema where the table was just created seconds ago.
        runOnFreshConnection(
            "CREATE TABLE IF NOT EXISTS prayer_volunteer (" +
            "  id BIGINT PRIMARY KEY, " +
            "  client_id VARCHAR(100) NOT NULL, " +
            "  family_member_id INTEGER, " +
            "  role VARCHAR(100), " +
            "  active BOOLEAN NOT NULL DEFAULT true, " +
            "  delete_flag BOOLEAN NOT NULL DEFAULT false, " +
            "  created_date TIMESTAMP" +
            ")");

        // 2. Drop the legacy NOT NULL on family_member_id so manual volunteers can be inserted.
        runOnFreshConnection("ALTER TABLE prayer_volunteer ALTER COLUMN family_member_id DROP NOT NULL");

        // 3. Add the v2 manual-volunteer columns (idempotent).
        runOnFreshConnection("ALTER TABLE prayer_volunteer ADD COLUMN IF NOT EXISTS first_name   VARCHAR(100)");
        runOnFreshConnection("ALTER TABLE prayer_volunteer ADD COLUMN IF NOT EXISTS last_name    VARCHAR(100)");
        runOnFreshConnection("ALTER TABLE prayer_volunteer ADD COLUMN IF NOT EXISTS phone        VARCHAR(50)");
        runOnFreshConnection("ALTER TABLE prayer_volunteer ADD COLUMN IF NOT EXISTS email        VARCHAR(200)");
        runOnFreshConnection("ALTER TABLE prayer_volunteer ADD COLUMN IF NOT EXISTS notes        TEXT");
        runOnFreshConnection("ALTER TABLE prayer_volunteer ADD COLUMN IF NOT EXISTS availability VARCHAR(200)");
    }

    private void runOnFreshConnection(String sql) {
        try (Connection conn = dataSource.getConnection();
             Statement  stmt = conn.createStatement()) {
            conn.setAutoCommit(true);
            stmt.execute(sql);
            log.info("prayer_volunteer migration applied: {}", sql);
        } catch (SQLException ex) {
            // Idempotent re-runs land here — column already exists, constraint
            // already nullable, etc. Logged at INFO so it's visible but not noisy.
            log.info("prayer_volunteer migration step skipped ({}): {}", sql, ex.getMessage());
        }
    }
}
