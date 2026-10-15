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
 * Applies V15 (Membership Requests: status / audit columns on {@code membership_family},
 * {@code membership_renewed_at} on {@code family}) after Hibernate's schema update on
 * every startup — same reason and shape as {@link DonationAccountingSchemaInitializer}:
 * on a fresh database Flyway runs before Hibernate creates the tables, so the file
 * no-ops then and is re-run here. Re-runnable: IF NOT EXISTS throughout and a guarded
 * backfill. PostgreSQL-only; on the H2 test profile Hibernate builds the columns.
 */
@Component
@DependsOn("entityManagerFactory")   // after Hibernate's own schema update, so the tables exist
public class MembershipRequestSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(MembershipRequestSchemaInitializer.class);

    /** Single source of truth — the Flyway migration itself. */
    static final String MIGRATION = "db/migration/V15__membership_request_status.sql";

    private final JdbcTemplate jdbc;

    public MembershipRequestSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void apply() {
        String sql;
        try {
            sql = new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            log.error("MembershipRequestSchemaInitializer: cannot read {}: {}", MIGRATION, ex.getMessage());
            return;
        }
        try {
            jdbc.execute(sql);
            log.info("MembershipRequestSchemaInitializer: membership request status schema verified (V15).");
        } catch (Exception ex) {
            log.warn("MembershipRequestSchemaInitializer: skipped ({}). Membership request statuses are unavailable "
                     + "until the V15 columns exist.", ex.getMessage());
        }
    }
}
