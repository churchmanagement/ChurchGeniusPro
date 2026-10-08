package com.churchgeniuspro.schema;

import com.churchgeniuspro.service.SchemaFixService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Financial audit M7 / database audit H7: production still carried the pre-M7 GLOBAL
 * unique keys on the three Plaid external-id columns next to the new per-tenant ones
 * (verified 16 Sep 2026), because Hibernate never drops a unique key it no longer maps
 * and the script's DROP lines were never run. {@link SchemaFixService} now removes every
 * single-column unique key on those columns at startup — constraint or bare index,
 * whatever its name — and leaves the composite per-tenant keys alone.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SchemaFixService — drops the legacy global Plaid unique keys (financial audit M7 / DB audit H7)")
class SchemaFixServicePlaidUniqueKeysTest {

    private static final String CATALOGUE_QUERY_FRAGMENT = "x.indisunique AND NOT x.indisprimary";

    @Mock JdbcTemplate jdbc;

    private static Map<String, Object> key(String index, String constraint) {
        Map<String, Object> m = new HashMap<>();
        m.put("index_name", index);
        m.put("constraint_name", constraint);
        return m;
    }

    private void catalogueReports(String table, String column, List<Map<String, Object>> keys) {
        when(jdbc.queryForList(contains(CATALOGUE_QUERY_FRAGMENT), eq(table), eq(column))).thenReturn(keys);
    }

    @Test
    @DisplayName("production's shape: a constraint-backed global key is dropped with DROP CONSTRAINT")
    void constraintBackedKeyIsDropped() {
        catalogueReports("plaid_item", "item_id", List.of(key("uq_plaid_item_item_id", "uq_plaid_item_item_id")));

        new SchemaFixService(jdbc).dropSingleColumnUniqueKeys("plaid_item", "item_id");

        verify(jdbc).execute("ALTER TABLE plaid_item DROP CONSTRAINT IF EXISTS \"uq_plaid_item_item_id\"");
    }

    @Test
    @DisplayName("a bare unique index (no constraint behind it) is dropped with DROP INDEX")
    void bareIndexIsDropped() {
        catalogueReports("plaid_transaction_staging", "plaid_transaction_id",
                List.of(key("uq_plaid_txn_txn_id", null)));

        new SchemaFixService(jdbc).dropSingleColumnUniqueKeys("plaid_transaction_staging", "plaid_transaction_id");

        verify(jdbc).execute("DROP INDEX IF EXISTS \"uq_plaid_txn_txn_id\"");
        verify(jdbc, never()).execute(contains("DROP CONSTRAINT"));
    }

    @Test
    @DisplayName("nothing left to drop (already repaired, or a fresh database) — no statement at all")
    void noOpWhenGone() {
        catalogueReports("plaid_account", "account_id", List.of());

        new SchemaFixService(jdbc).dropSingleColumnUniqueKeys("plaid_account", "account_id");

        verify(jdbc, never()).execute(any(String.class));
    }

    @Test
    @DisplayName("a catalogue or DDL failure is logged, never propagated — startup must not depend on it")
    void failureNeverBlocksStartup() {
        catalogueReports("plaid_item", "item_id", List.of(key("uq_plaid_item_item_id", "uq_plaid_item_item_id")));
        org.mockito.Mockito.doThrow(new RuntimeException("must be owner of table plaid_item"))
                .when(jdbc).execute(any(String.class));

        assertThatCode(() -> new SchemaFixService(jdbc).dropSingleColumnUniqueKeys("plaid_item", "item_id"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the startup entry point reaches all three Plaid tables")
    void startupEntryPointCoversAllThreeTables() {
        catalogueReports("plaid_item", "item_id", List.of(key("uq_plaid_item_item_id", "uq_plaid_item_item_id")));
        catalogueReports("plaid_account", "account_id", List.of(key("uq_plaid_account_account_id", "uq_plaid_account_account_id")));
        catalogueReports("plaid_transaction_staging", "plaid_transaction_id", List.of(key("uq_plaid_txn_txn_id", null)));

        new SchemaFixService(jdbc).dropLegacyConstraints();

        verify(jdbc).execute("ALTER TABLE plaid_item DROP CONSTRAINT IF EXISTS \"uq_plaid_item_item_id\"");
        verify(jdbc).execute("ALTER TABLE plaid_account DROP CONSTRAINT IF EXISTS \"uq_plaid_account_account_id\"");
        verify(jdbc).execute("DROP INDEX IF EXISTS \"uq_plaid_txn_txn_id\"");
    }
}
