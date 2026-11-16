package com.churchgeniuspro.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Applies the promotion index after Hibernate's schema update on every startup — same
 * reason and shape as {@link BillingSchemaInitializer}: Flyway runs before Hibernate
 * creates the table, so on that first start the migration no-ops.
 * <ul>
 *   <li>V24 — one SCHEDULED or ACTIVE discount per client:
 *       {@code client_promotion(client_id)}.</li>
 * </ul>
 * Re-runnable; PostgreSQL-only (on H2 it is logged and skipped, and
 * ClientPromotionService still refuses a second live discount).
 */
@Component
@DependsOn("entityManagerFactory")   // after Hibernate's own schema update, so client_promotion exists
public class PromotionSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(PromotionSchemaInitializer.class);

    /** Single source of truth — the Flyway migrations themselves, in order. */
    static final List<String> MIGRATIONS = List.of(
            "db/migration/V24__client_promotion_one_live.sql");

    private final JdbcTemplate jdbc;

    public PromotionSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void apply() {
        for (String migration : MIGRATIONS) {
            String sql;
            try {
                sql = new ClassPathResource(migration).getContentAsString(StandardCharsets.UTF_8);
            } catch (Exception ex) {
                log.error("PromotionSchemaInitializer: cannot read {}: {}", migration, ex.getMessage());
                continue;
            }
            try {
                jdbc.execute(sql);
                log.info("PromotionSchemaInitializer: {} verified.", migration.substring(migration.lastIndexOf('/') + 1));
            } catch (Exception ex) {
                log.warn("PromotionSchemaInitializer: skipped {} ({}). A second live discount is still refused by ClientPromotionService.",
                        migration.substring(migration.lastIndexOf('/') + 1), ex.getMessage());
            }
        }
    }
}
