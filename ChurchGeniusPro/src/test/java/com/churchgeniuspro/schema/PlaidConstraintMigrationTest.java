package com.churchgeniuspro.schema;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Financial audit M7 / database audit H7: the script's six plain Plaid ALTERs were never
 * run against production and could not be re-run anywhere the current build had started
 * (no {@code ADD CONSTRAINT IF NOT EXISTS} in PostgreSQL). They are now one idempotent
 * block; this pins its shape. The block was executed against PostgreSQL 16 in three
 * states — legacy keys as constraints and as a bare index next to the per-tenant keys, a
 * database that never ran the current build (per-tenant keys absent), and already
 * migrated — before shipping.
 */
@DisplayName("migrate_production.sql — Plaid per-tenant unique keys block (financial audit M7 / DB audit H7)")
class PlaidConstraintMigrationTest {

    private static String section;

    @BeforeAll
    static void loadSection() throws IOException {
        section = MigrationScript.section("Database audit H7");
    }

    @Test
    @DisplayName("no bare ADD CONSTRAINT remains — the per-tenant key is added only when absent")
    void addIsGuarded() {
        assertThat(section).doesNotContain("\nALTER TABLE plaid_item ADD CONSTRAINT");
        assertThat(section).doesNotContain("\nALTER TABLE plaid_account ADD CONSTRAINT");
        assertThat(section).doesNotContain("\nALTER TABLE plaid_transaction_staging ADD CONSTRAINT");
        assertThat(section).contains("IF EXISTS (SELECT 1 FROM pg_constraint");
        assertThat(section).contains("EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I UNIQUE (%s)'");
    }

    @Test
    @DisplayName("every single-column unique key on the external id is dropped, constraint or bare index, whatever its name")
    void legacyKeysAreDroppedByShapeNotByName() {
        assertThat(section).contains("x.indisunique AND NOT x.indisprimary");
        assertThat(section).contains("x.indnatts = 1 AND x.indpred IS NULL");
        assertThat(section).contains("EXECUTE format('ALTER TABLE %I DROP CONSTRAINT IF EXISTS %I'");
        assertThat(section).contains("EXECUTE format('DROP INDEX IF EXISTS %I'");
    }

    @Test
    @DisplayName("covers exactly the three tables and columns, with the per-tenant key names the entities declare")
    void coversTheThreeTables() {
        assertThat(section).contains("('plaid_item',                'item_id',              'uq_plaid_item_client_item_id',    'client_id, item_id')");
        assertThat(section).contains("('plaid_account',             'account_id',           'uq_plaid_account_client_account', 'client_id, account_id')");
        assertThat(section).contains("('plaid_transaction_staging', 'plaid_transaction_id', 'uq_plaid_txn_client_txn_id',      'client_id, plaid_transaction_id')");
    }

    @Test
    @DisplayName("guarded against databases that predate the Plaid tables")
    void guardedWhenTableAbsent() {
        assertThat(section).contains("IF to_regclass('public.' || r.tbl) IS NULL THEN");
    }
}
