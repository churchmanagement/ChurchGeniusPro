package com.churchgeniuspro.integration;

import com.churchgeniuspro.config.SchemaDriftGuard;
import com.churchgeniuspro.payroll.config.FicaConfig;
import com.churchgeniuspro.payroll.service.TaxConfigService;
import com.churchgeniuspro.service.SchemaFixService;
import com.churchgeniuspro.service.SchemaFixService.LegacyColumn;
import com.churchgeniuspro.service.SchemaFixService.RateColumn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The startup repairs of {@link SchemaFixService} against a real PostgreSQL carrying
 * production's shape (16 Sep 2026): the legacy global Plaid unique keys next to the
 * per-tenant ones (financial audit M7 / database audit H7), and the payroll rate columns
 * as {@code numeric(38,2)} with the rounded 2026 FICA seed (database audit C1). Requires
 * Docker; runs on {@code verify}.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SchemaFixServiceIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired SchemaFixService schemaFix;
    @Autowired SchemaDriftGuard driftGuard;
    @Autowired TaxConfigService taxConfig;
    @Autowired JdbcTemplate jdbc;

    private List<String> uniqueIndexes(String table) {
        return jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = current_schema() AND tablename = ? "
                + "AND indexdef LIKE 'CREATE UNIQUE INDEX%' ORDER BY 1", String.class, table);
    }

    private String columnType(String table, String column) {
        return jdbc.queryForObject("SELECT data_type || '(' || numeric_precision || ',' || numeric_scale || ')' "
                + "FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                String.class, table, column);
    }

    @Test
    @DisplayName("Plaid: the legacy global keys go, the per-tenant keys stay, and two tenants can share an item_id")
    void plaidGlobalKeysAreDropped() {
        jdbc.execute("ALTER TABLE plaid_item ADD CONSTRAINT uq_plaid_item_item_id UNIQUE (item_id)");
        jdbc.execute("ALTER TABLE plaid_account ADD CONSTRAINT uq_plaid_account_account_id UNIQUE (account_id)");
        jdbc.execute("CREATE UNIQUE INDEX uq_plaid_txn_txn_id ON plaid_transaction_staging (plaid_transaction_id)");
        assertThat(uniqueIndexes("plaid_item")).contains("uq_plaid_item_item_id", "uq_plaid_item_client_item_id");

        schemaFix.dropLegacyConstraints();

        assertThat(uniqueIndexes("plaid_item")).containsExactly("plaid_item_pkey", "uq_plaid_item_client_item_id");
        assertThat(uniqueIndexes("plaid_account")).containsExactly("plaid_account_pkey", "uq_plaid_account_client_account");
        assertThat(uniqueIndexes("plaid_transaction_staging"))
                .containsExactly("plaid_transaction_staging_pkey", "uq_plaid_txn_client_txn_id");

        // The M7 behaviour the constraint was blocking: the same sandbox item_id under two tenants…
        jdbc.execute("DELETE FROM plaid_item");
        MinimalRows.insert(jdbc, "plaid_item", Map.of("id", "1", "client_id", "'TRIAL-A'", "item_id", "'sandbox-item'"));
        MinimalRows.insert(jdbc, "plaid_item", Map.of("id", "2", "client_id", "'TRIAL-B'", "item_id", "'sandbox-item'"));
        // …while the same item_id under ONE tenant is still refused.
        assertThatThrownBy(() -> MinimalRows.insert(jdbc, "plaid_item",
                Map.of("id", "3", "client_id", "'TRIAL-A'", "item_id", "'sandbox-item'")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_plaid_item_client_item_id");
        jdbc.execute("DELETE FROM plaid_item");

        // and a second start has nothing left to do
        schemaFix.dropLegacyConstraints();
        assertThat(uniqueIndexes("plaid_item")).containsExactly("plaid_item_pkey", "uq_plaid_item_client_item_id");
    }

    @Test
    @DisplayName("C1: a fresh schema from the entities already has the rate precision")
    void freshSchemaHasThePrecision() {
        // Whatever the other test did, the columns end up wide again; this pins the entity DDL.
        for (RateColumn rc : SchemaFixService.PAYROLL_RATE_COLUMNS) {
            assertThat(columnType(rc.table(), rc.column())).as("%s.%s", rc.table(), rc.column())
                    .isEqualTo(rc.type());
        }
    }

    @Test
    @DisplayName("C1: production's shape — numeric(38,2) rounds the seed to 0.06/0.01/0.01; startup widens the columns, repairs the row, payroll computes 6.2 %")
    void ratesAreWidenedAndTheSeedRepaired() {
        jdbc.execute("DELETE FROM payroll_fica_rate");
        for (RateColumn rc : SchemaFixService.PAYROLL_RATE_COLUMNS) {
            jdbc.execute("ALTER TABLE " + rc.table() + " ALTER COLUMN " + rc.column() + " TYPE numeric(38,2)");
        }
        jdbc.update("INSERT INTO payroll_fica_rate (effective_year, social_security_rate, social_security_wage_base, "
                + "medicare_rate, additional_medicare_rate, additional_medicare_threshold) VALUES (2026, 0.062, 184500, 0.0145, 0.009, 200000)");
        try {
            // The defect, reproduced: PostgreSQL rounded on insert without a word.
            Map<String, Object> stored = jdbc.queryForMap("SELECT social_security_rate, medicare_rate, additional_medicare_rate "
                    + "FROM payroll_fica_rate WHERE effective_year = 2026");
            assertThat((BigDecimal) stored.get("social_security_rate")).isEqualByComparingTo("0.06");
            assertThat((BigDecimal) stored.get("medicare_rate")).isEqualByComparingTo("0.01");
            assertThat((BigDecimal) stored.get("additional_medicare_rate")).isEqualByComparingTo("0.01");
            // …and the guard now refuses to compute payroll from that row.
            assertThatThrownBy(() -> taxConfig.loadFica(2026)).hasMessageContaining("Social Security rate is 0.06 instead of 0.062");

            schemaFix.dropLegacyConstraints();

            for (RateColumn rc : SchemaFixService.PAYROLL_RATE_COLUMNS) {
                assertThat(columnType(rc.table(), rc.column())).as("%s.%s", rc.table(), rc.column()).isEqualTo(rc.type());
            }
            FicaConfig fica = taxConfig.loadFica(2026);
            assertThat(fica.getSocialSecurityRate()).isEqualByComparingTo("0.062");
            assertThat(fica.getMedicareRate()).isEqualByComparingTo("0.0145");
            assertThat(fica.getAdditionalMedicareRate()).isEqualByComparingTo("0.009");
            // What a $1,000 FICA wage withholds now versus what production withheld.
            assertThat(fica.socialSecurity(new BigDecimal("1000"), BigDecimal.ZERO)).isEqualByComparingTo("62.00");
            assertThat(fica.medicare(new BigDecimal("1000"))).isEqualByComparingTo("14.50");

            // A second start finds everything in order and changes nothing.
            schemaFix.dropLegacyConstraints();
            assertThat(taxConfig.loadFica(2026).getSocialSecurityRate()).isEqualByComparingTo("0.062");
        } finally {
            jdbc.execute("DELETE FROM payroll_fica_rate");
        }
    }

    @Test
    @DisplayName("P3: none of the legacy columns SchemaFixService drops is mapped by any entity")
    void legacyColumnsAreUnmapped() {
        Map<String, Map<String, Boolean>> mapped = driftGuard.mappedColumns();
        for (LegacyColumn c : SchemaFixService.LEGACY_EMPTY_COLUMNS) {
            assertThat(mapped).as("table %s is an entity table", c.table()).containsKey(c.table());
            assertThat(mapped.get(c.table())).as("%s.%s must not be mapped", c.table(), c.column())
                    .doesNotContainKey(c.column());
        }
    }

    @Test
    @DisplayName("P3: production's legacy columns — nine empty ones go at startup, the one holding a value stays")
    void emptyLegacyColumnsAreDroppedPopulatedOnesKept() {
        jdbc.execute("ALTER TABLE membership_family ADD COLUMN family_name varchar(255) NOT NULL");   // the P1 shape
        for (String c : List.of("family_address1", "family_address2", "family_city", "family_state", "family_country", "family_pin_code")) {
            jdbc.execute("ALTER TABLE membership_family ADD COLUMN " + c + " varchar(255)");
        }
        jdbc.execute("ALTER TABLE family ADD COLUMN member_renewal_date date");
        jdbc.execute("ALTER TABLE family_member ADD COLUMN renewal_date date");
        jdbc.execute("ALTER TABLE subscription_plan ADD COLUMN extra_sms_count integer NOT NULL DEFAULT 0");
        MinimalRows.insert(jdbc, "family", Map.of("id", "999"));
        MinimalRows.insert(jdbc, "family_member", Map.of("id", "999", "family_id", "999", "renewal_date", "'2025-01-01'"));   // data → kept
        try {
            schemaFix.dropLegacyConstraints();

            List<String> left = jdbc.queryForList("SELECT table_name || '.' || column_name FROM information_schema.columns "
                    + "WHERE table_schema = current_schema() AND ((table_name = 'membership_family' AND column_name LIKE 'family_%') "
                    + "OR (table_name, column_name) IN (('family', 'member_renewal_date'), ('family_member', 'renewal_date'), "
                    + "('subscription_plan', 'extra_sms_count'))) ORDER BY 1", String.class);
            assertThat(left).containsExactly("family_member.renewal_date");
            assertThat(jdbc.queryForObject("SELECT renewal_date FROM family_member WHERE id = 999", java.sql.Date.class))
                    .as("the kept column still holds its value").isEqualTo(java.sql.Date.valueOf("2025-01-01"));

            // a second start changes nothing more
            schemaFix.dropLegacyConstraints();
            assertThat(left).containsExactly("family_member.renewal_date");
        } finally {
            jdbc.execute("DELETE FROM family_member WHERE id = 999");
            jdbc.execute("DELETE FROM family WHERE id = 999");
            jdbc.execute("ALTER TABLE family_member DROP COLUMN IF EXISTS renewal_date");
        }
    }
}
