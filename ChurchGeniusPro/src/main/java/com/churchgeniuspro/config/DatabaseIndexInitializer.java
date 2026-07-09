package com.churchgeniuspro.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
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
 */
@Component
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
