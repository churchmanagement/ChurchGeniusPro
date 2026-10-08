package com.churchgeniuspro.schema;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database audit P1: the production companion to the SchemaFixService change lives in
 * {@code migrate_production.sql}. There is no SQL runner in this build, so this pins the
 * shape of that section — the statement that unblocks the public membership form, the
 * guard that keeps legacy data from being dropped, and its idempotency guards — the way
 * the static-page tests pin JavaScript. The section itself was executed three times
 * against PostgreSQL 16 (fresh, re-run, and with legacy data present) before shipping.
 */
@DisplayName("migrate_production.sql — membership_family legacy columns section (DB audit P1)")
class MembershipFamilyMigrationTest {

    private static String section;

    @BeforeAll
    static void loadSection() throws IOException {
        section = MigrationScript.section("Database audit P1");
    }

    @Test
    @DisplayName("relaxes the NOT NULL on family_name inside a DO block, only when it is still NOT NULL")
    void relaxesFamilyNameNotNullConditionally() {
        assertThat(section).contains("ALTER TABLE membership_family ALTER COLUMN family_name DROP NOT NULL;");
        assertThat(section).contains("IF v_nullable = 'NO' THEN");
        assertThat(section).contains("ELSIF v_nullable = 'YES' THEN");
        assertThat(section).contains("DO $$");
    }

    @Test
    @DisplayName("drops all seven legacy name/address columns, each with IF EXISTS")
    void dropsTheSevenLegacyColumnsIdempotently() {
        for (String col : new String[]{"family_name", "family_address1", "family_address2",
                                       "family_city", "family_state", "family_country", "family_pin_code"}) {
            assertThat(section).contains("DROP COLUMN IF EXISTS " + col);
        }
    }

    @Test
    @DisplayName("never drops a column that still holds data — the populated-row guard precedes the DROP")
    void keepsColumnsThatStillHoldData() {
        int guard = section.indexOf("IF v_populated > 0 THEN");
        int drop = section.indexOf("DROP COLUMN IF EXISTS family_name");
        assertThat(guard).isPositive();
        assertThat(drop).isGreaterThan(guard);
        // the emptiness test is built from the catalogue so a half-migrated table is handled
        assertThat(section).contains("string_agg(format('(%I IS NOT NULL AND %I <> %L)'");
    }

    @Test
    @DisplayName("is guarded against databases that predate the table entirely")
    void guardedWhenTableAbsent() {
        assertThat(section).contains("IF to_regclass('public.membership_family') IS NULL THEN");
    }
}
