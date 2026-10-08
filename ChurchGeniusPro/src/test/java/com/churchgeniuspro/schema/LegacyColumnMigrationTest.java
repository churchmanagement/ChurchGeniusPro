package com.churchgeniuspro.schema;

import com.churchgeniuspro.service.SchemaFixService;
import com.churchgeniuspro.service.SchemaFixService.LegacyColumn;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database audit P3 and P6: the script sections that drop the legacy columns and carry
 * the retired startup migrations. Both were executed against PostgreSQL 16 on a database
 * shaped like production (legacy columns present, one holding a value; income/expense
 * {@code method} and {@code family_member.signup_ref} present with one unmappable row
 * each) — the populated and unmappable columns were kept and reported, everything else
 * dropped; a second run after fixing those rows dropped the rest.
 */
@DisplayName("migrate_production.sql — legacy columns (P3) and retired startup migrations (P6)")
class LegacyColumnMigrationTest {

    private static String p3;
    private static String p6;

    @BeforeAll
    static void loadSections() throws IOException {
        p3 = MigrationScript.section("Database audit P3");
        p6 = MigrationScript.section("Database audit P6");
    }

    @Test
    @DisplayName("P3 drops exactly the legacy columns SchemaFixService drops that §P1 does not already cover")
    void p3CoversTheSameColumnsAsTheApplication() {
        for (LegacyColumn c : SchemaFixService.LEGACY_EMPTY_COLUMNS) {
            if (c.table().equals("membership_family")) {
                assertThat(p3).doesNotContain("'" + c.column() + "'");   // §P1 drops those seven
            } else {
                assertThat(p3).contains("('" + c.table() + "',");
                assertThat(p3).contains("'" + c.column() + "')");
            }
        }
        long entries = p3.lines().filter(l -> l.trim().startsWith("('")).count();
        assertThat(entries).isEqualTo(3);
    }

    @Test
    @DisplayName("P3 keeps a column that still holds a value, and treats '' and 0 as no value")
    void p3NeverDropsData() {
        assertThat(p3).contains("IF v_populated > 0 THEN");
        int keep = p3.indexOf("still holds a value");
        int drop = p3.indexOf("DROP COLUMN IF EXISTS");
        assertThat(keep).isPositive();
        assertThat(drop).isGreaterThan(keep);
        assertThat(p3).contains("v_pred := format('%I IS NOT NULL AND %I <> %L', r.col, r.col, '');");
        assertThat(p3).contains("v_pred := format('%I IS NOT NULL AND %I <> 0', r.col, r.col);");
    }

    @Test
    @DisplayName("P6 backfills every row — soft-deleted ones too — and drops method only when no row would lose it (audit M5)")
    void p6DropsMethodOnlyWhenNothingIsLost() {
        assertThat(p6).contains("FOR r IN SELECT * FROM (VALUES ('income'), ('expense')) AS v(tbl)");
        assertThat(p6).contains("t.method::integer = tt.id AND t.transaction_type_id IS NULL");
        assertThat(p6).doesNotContain("delete_flag = false");
        assertThat(p6).contains("WHERE method IS NOT NULL AND method <> %L AND transaction_type_id IS NULL");
        int guard = p6.indexOf("IF v_left > 0 THEN");
        int drop = p6.indexOf("DROP COLUMN method");
        assertThat(guard).isPositive();
        assertThat(drop).isGreaterThan(guard);
    }

    @Test
    @DisplayName("P6 drops signup_ref only once every row with a signup_ref has a member_ref")
    void p6DropsSignupRefOnlyWhenResolved() {
        assertThat(p6).contains("SET member_ref = s.client_id");
        assertThat(p6).contains("AND s.client_id LIKE 'MBR%'");
        assertThat(p6).contains("WHERE signup_ref IS NOT NULL AND member_ref IS NULL");
        int guard = p6.indexOf("IF v_left > 0 THEN", p6.indexOf("signup_ref"));
        int drop = p6.indexOf("DROP COLUMN signup_ref");
        assertThat(guard).isPositive();
        assertThat(drop).isGreaterThan(guard);
    }

    @Test
    @DisplayName("both sections are guarded for databases where the columns are already gone (production)")
    void bothNoOpWhenAlreadyDone() {
        assertThat(p3).contains("RAISE NOTICE 'P3: %.% already removed.'");
        assertThat(p6).contains("RAISE NOTICE 'P6: %.method already removed.'");
        assertThat(p6).contains("RAISE NOTICE 'P6: family_member.signup_ref already removed.'");
    }
}
