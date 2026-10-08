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
 * Applies V9 (one open subscription request per church: the partial unique index on
 * {@code subscription_request(client_id)}) after Hibernate's schema update on every
 * startup — same reason and shape as {@link TrialRequestSchemaInitializer}: on a
 * FRESH database Flyway runs before Hibernate creates the table, so V9 no-ops there.
 * Re-runnable; PostgreSQL-only (on H2 it is logged and skipped, and
 * {@code SubscriptionRequestService} still refuses a second open request).
 */
@Component
@DependsOn("entityManagerFactory")   // after Hibernate's own schema update, so subscription_request exists
public class SubscriptionRequestSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionRequestSchemaInitializer.class);

    /** Single source of truth — the Flyway migration itself. */
    static final String MIGRATION = "db/migration/V9__subscription_request_one_open.sql";

    private final JdbcTemplate jdbc;

    public SubscriptionRequestSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void apply() {
        String sql;
        try {
            sql = new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            log.error("SubscriptionRequestSchemaInitializer: cannot read {}: {}", MIGRATION, ex.getMessage());
            return;
        }
        try {
            jdbc.execute(sql);
            log.info("SubscriptionRequestSchemaInitializer: subscription_request one-open index verified (V9).");
        } catch (Exception ex) {
            log.warn("SubscriptionRequestSchemaInitializer: skipped ({}). Duplicate subscription requests are still "
                     + "refused by SubscriptionRequestService.", ex.getMessage());
        }
    }
}
