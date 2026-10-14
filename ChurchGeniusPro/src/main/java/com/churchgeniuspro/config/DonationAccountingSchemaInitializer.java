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
 * Applies V14 (Donation Review accounting: the {@code donation_settings} table and
 * the fee / review / posting columns on {@code donation}) after Hibernate's schema
 * update on every startup.
 *
 * <p>Same reason and shape as {@link WorshipSchemaInitializer}: on a FRESH database
 * Flyway runs before Hibernate creates {@code donation}, so the column part of V14
 * no-ops and is recorded as applied. This runs the very same file once the table
 * exists. The file is re-runnable (IF NOT EXISTS throughout), so on a migrated
 * database it is a cheap no-op. PostgreSQL-only: on the H2 test profile the DO block
 * fails and is logged as a warning; there Hibernate's create-drop builds the columns
 * from the entity.
 */
@Component
@DependsOn("entityManagerFactory")   // after Hibernate's own schema update, so the tables exist
public class DonationAccountingSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(DonationAccountingSchemaInitializer.class);

    /** Single source of truth — the Flyway migration itself. */
    static final String MIGRATION = "db/migration/V14__donation_review_accounting.sql";

    private final JdbcTemplate jdbc;

    public DonationAccountingSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void apply() {
        String sql;
        try {
            sql = new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            log.error("DonationAccountingSchemaInitializer: cannot read {}: {}", MIGRATION, ex.getMessage());
            return;
        }
        try {
            jdbc.execute(sql);
            log.info("DonationAccountingSchemaInitializer: donation review accounting schema verified (V14).");
        } catch (Exception ex) {
            log.warn("DonationAccountingSchemaInitializer: skipped ({}). Donation posting still refuses to run "
                     + "until the V14 columns exist.", ex.getMessage());
        }
    }
}
