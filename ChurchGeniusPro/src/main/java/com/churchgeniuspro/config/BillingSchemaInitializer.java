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
 * Applies the billing indexes after Hibernate's schema update on every startup — same
 * reason and shape as {@link SubscriptionRequestSchemaInitializer}: Flyway runs before
 * Hibernate creates or alters the tables, so on that first start these migrations no-op.
 * <ul>
 *   <li>V11 — one live renewal invoice per client per billing date:
 *       {@code billing_invoice(client_id, period_start)}.</li>
 *   <li>V12 — one invoice per Stripe PaymentIntent:
 *       {@code billing_invoice(stripe_payment_intent_id)} (Phase 6).</li>
 * </ul>
 * Re-runnable; PostgreSQL-only (on H2 it is logged and skipped, and BillingService /
 * BillingPaymentService still refuse duplicates).
 */
@Component
@DependsOn("entityManagerFactory")   // after Hibernate's own schema update, so billing_invoice and its columns exist
public class BillingSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(BillingSchemaInitializer.class);

    /** Single source of truth — the Flyway migrations themselves, in order. */
    static final List<String> MIGRATIONS = List.of(
            "db/migration/V11__billing_invoice_one_open_renewal.sql",
            "db/migration/V12__billing_invoice_payment_intent_unique.sql");

    private final JdbcTemplate jdbc;

    public BillingSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void apply() {
        for (String migration : MIGRATIONS) {
            String sql;
            try {
                sql = new ClassPathResource(migration).getContentAsString(StandardCharsets.UTF_8);
            } catch (Exception ex) {
                log.error("BillingSchemaInitializer: cannot read {}: {}", migration, ex.getMessage());
                continue;
            }
            try {
                jdbc.execute(sql);
                log.info("BillingSchemaInitializer: {} verified.", migration.substring(migration.lastIndexOf('/') + 1));
            } catch (Exception ex) {
                log.warn("BillingSchemaInitializer: skipped {} ({}). Duplicates are still refused by the billing services.",
                        migration.substring(migration.lastIndexOf('/') + 1), ex.getMessage());
            }
        }
    }
}
