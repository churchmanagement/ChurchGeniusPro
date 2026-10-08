package com.churchgeniuspro.schema;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database audit H1/H6: V2 is generated (from the model schema and repository usage) and must stay
 * safe to run before Hibernate on a fresh database. This pins that shape so a hand-edit cannot break
 * it: every index is created inside the to_regclass-guarded loop, and the counts do not drift.
 */
@DisplayName("V2__h6_tenant_and_join_indexes.sql — shape of the H6 index migration (DB audit H1/H6)")
class FlywayIndexMigrationTest {

    private static String sql;

    @BeforeAll
    static void load() throws IOException {
        sql = Files.readString(Path.of("src/main/resources/db/migration/V2__h6_tenant_and_join_indexes.sql"));
    }

    @Test
    @DisplayName("34 tenant + 20 join = 54 index rows, and the migration creates indexes only through the guarded EXECUTE")
    void everyIndexIsGuarded() {
        Matcher tenant = Pattern.compile("'(idx_\\w+_tenant)'").matcher(sql);
        int tenantCount = 0; while (tenant.find()) tenantCount++;
        assertThat(tenantCount).as("tenant-leading indexes").isEqualTo(34);

        long total = sql.lines().filter(l -> l.trim().startsWith("('idx_")).count();
        assertThat(total).as("total index rows").isEqualTo(54);

        // the only DDL is the single guarded EXECUTE — no bare CREATE INDEX that would fail pre-Hibernate
        assertThat(sql).contains("IF to_regclass('public.' || r.tbl) IS NULL THEN");
        assertThat(sql).contains("EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON %I (%s)', r.idx, r.tbl, r.cols);");
        assertThat(sql).doesNotContainPattern("(?m)^\\s*CREATE INDEX ");   // no un-guarded statement
    }

    @Test
    @DisplayName("the core ledger tables income and expense get a (tenant, delete_flag) composite")
    void coreLedgerTablesAreCovered() {
        assertThat(sql).contains("('idx_income_tenant', 'income', 'app_client_id, delete_flag')");
        assertThat(sql).contains("('idx_expense_tenant', 'expense', 'app_client_id, delete_flag')");
    }
}
