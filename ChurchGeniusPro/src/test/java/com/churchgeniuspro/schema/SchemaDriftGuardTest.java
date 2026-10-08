package com.churchgeniuspro.schema;

import com.churchgeniuspro.config.SchemaDriftGuard;
import com.churchgeniuspro.config.SchemaDriftGuard.DbColumn;
import com.churchgeniuspro.config.SchemaDriftGuard.Drift;
import com.churchgeniuspro.config.SchemaDriftGuard.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database audit P1: production carried {@code membership_family.family_name} as a
 * NOT NULL column no entity mapped, so every INSERT the application made was rejected
 * and the public membership form could not store a submission — and nothing at startup
 * said so. {@link SchemaDriftGuard} compares the live schema with the entity mapping on
 * every start and reports exactly that class of difference. These tests pin the
 * comparison itself; the guard was also run against a real PostgreSQL 16 carrying
 * production's drift (P1 relaxed by SchemaFixService, P2 and an unknown legacy column
 * reported, nothing else flagged across all 183 tables).
 */
@DisplayName("SchemaDriftGuard — live schema vs entity mapping (DB audit P1)")
class SchemaDriftGuardTest {

    private static Map<String, Map<String, Boolean>> mapped(String table, Object... colsAndNullable) {
        Map<String, Map<String, Boolean>> m = new TreeMap<>();
        Map<String, Boolean> cols = new TreeMap<>();
        for (int i = 0; i < colsAndNullable.length; i += 2) {
            cols.put((String) colsAndNullable[i], (Boolean) colsAndNullable[i + 1]);
        }
        m.put(table, cols);
        return m;
    }

    private static DbColumn col(String table, String column, boolean nullable, boolean hasDefault) {
        return new DbColumn(table, column, nullable, hasDefault);
    }

    @Nested
    @DisplayName("unmapped columns")
    class Unmapped {

        @Test
        @DisplayName("P1 shape: an unmapped NOT NULL column with no default is reported with the DROP NOT NULL remedy")
        void unmappedNotNullIsReported() {
            // Production's membership_family on 2026-09-16: the entity's eight columns plus
            // seven legacy ones, of which only family_name is NOT NULL.
            var mapped = mapped("membership_family",
                    "id", false, "inactive", false, "existing_family_id", true, "declaration_accepted", true,
                    "app_client_id", true, "signup_id", true, "delete_flag", false, "created_date", false);
            List<DbColumn> live = List.of(
                    col("membership_family", "id", false, false),
                    col("membership_family", "inactive", false, false),
                    col("membership_family", "existing_family_id", true, false),
                    col("membership_family", "declaration_accepted", true, false),
                    col("membership_family", "app_client_id", true, false),
                    col("membership_family", "signup_id", true, false),
                    col("membership_family", "delete_flag", false, false),
                    col("membership_family", "created_date", false, false),
                    col("membership_family", "family_name", false, false),      // the P1 landmine
                    col("membership_family", "family_address1", true, false),
                    col("membership_family", "family_address2", true, false),
                    col("membership_family", "family_city", true, false),
                    col("membership_family", "family_state", true, false),
                    col("membership_family", "family_country", true, false),
                    col("membership_family", "family_pin_code", true, false));

            List<Drift> drifts = SchemaDriftGuard.diff(mapped, live);

            assertThat(drifts).hasSize(1);
            Drift d = drifts.get(0);
            assertThat(d.kind()).isEqualTo(Kind.UNMAPPED_NOT_NULL);
            assertThat(d.table()).isEqualTo("membership_family");
            assertThat(d.column()).isEqualTo("family_name");
            assertThat(d.remedy()).contains("ALTER TABLE membership_family ALTER COLUMN family_name DROP NOT NULL;");
        }

        @Test
        @DisplayName("an unmapped column that is nullable, or NOT NULL with a default / identity, breaks nothing and is not reported")
        void unmappedHarmlessColumnsAreIgnored() {
            var mapped = mapped("subscription_plan", "id", false, "plan_code", false);
            List<DbColumn> live = List.of(
                    col("subscription_plan", "id", false, true),
                    col("subscription_plan", "plan_code", false, false),
                    col("subscription_plan", "extra_sms_count", false, true),   // NOT NULL DEFAULT 0 — fine
                    col("subscription_plan", "legacy_note", true, false));       // nullable — fine

            assertThat(SchemaDriftGuard.diff(mapped, live)).isEmpty();
        }
    }

    @Nested
    @DisplayName("mapped columns")
    class Mapped {

        @Test
        @DisplayName("a column the entity maps but the table lacks (a silently failed ddl-auto ALTER) is reported")
        void mappedColumnMissingIsReported() {
            var mapped = mapped("backup_config", "id", false, "interval_months", false, "retention_months", false);
            List<DbColumn> live = List.of(
                    col("backup_config", "id", false, false),
                    col("backup_config", "interval_months", false, false));

            List<Drift> drifts = SchemaDriftGuard.diff(mapped, live);

            assertThat(drifts).hasSize(1);
            assertThat(drifts.get(0).kind()).isEqualTo(Kind.MAPPED_COLUMN_MISSING);
            assertThat(drifts.get(0).column()).isEqualTo("retention_months");
            assertThat(drifts.get(0).remedy()).contains("@ColumnDefault");
        }

        @Test
        @DisplayName("P2 shape: the entity allows NULL but the database forbids it (no default) is reported")
        void nullabilityDriftIsReported() {
            var mapped = mapped("guess_it_participant", "id", false, "game_id", false, "member_id", true);
            List<DbColumn> live = List.of(
                    col("guess_it_participant", "id", false, false),
                    col("guess_it_participant", "game_id", false, false),
                    col("guess_it_participant", "member_id", false, false));   // NOT NULL in production

            List<Drift> drifts = SchemaDriftGuard.diff(mapped, live);

            assertThat(drifts).hasSize(1);
            assertThat(drifts.get(0).kind()).isEqualTo(Kind.NULLABILITY_DRIFT);
            assertThat(drifts.get(0).remedy())
                    .contains("ALTER TABLE guess_it_participant ALTER COLUMN member_id DROP NOT NULL;");
        }

        @Test
        @DisplayName("agreeing nullability, or a database default that fills the gap, is not drift")
        void agreeingOrDefaultedColumnsAreNotDrift() {
            var mapped = mapped("t", "a", false, "b", true, "c", true);
            List<DbColumn> live = List.of(
                    col("t", "a", false, false),   // both NOT NULL
                    col("t", "b", true, false),    // both nullable
                    col("t", "c", false, true));   // entity nullable, DB NOT NULL but DEFAULTed — inserts succeed

            assertThat(SchemaDriftGuard.diff(mapped, live)).isEmpty();
        }

        @Test
        @DisplayName("a mapped table that does not exist at all is reported once, not once per column")
        void missingTableIsReportedOnce() {
            var mapped = mapped("payroll_fica_rate", "id", false, "effective_year", false, "medicare_rate", true);

            List<Drift> drifts = SchemaDriftGuard.diff(mapped, List.of());

            assertThat(drifts).hasSize(1);
            assertThat(drifts.get(0).kind()).isEqualTo(Kind.TABLE_MISSING);
            assertThat(drifts.get(0).column()).isNull();
        }
    }

    @Test
    @DisplayName("tables no entity maps (Spring Session, backup snapshots) are outside the comparison")
    void unmappedTablesAreIgnored() {
        var mapped = mapped("family", "id", false);
        List<DbColumn> live = List.of(
                col("family", "id", false, false),
                col("spring_session", "primary_id", false, false),
                col("family_bkp_20260729", "id", false, false),
                col("family_bkp_20260729", "legacy_only", false, false));

        assertThat(SchemaDriftGuard.diff(mapped, live)).isEmpty();
    }

    @Test
    @DisplayName("identifiers are compared the way PostgreSQL stores them: lower-cased unless quoted, schema prefix dropped")
    void identifierNormalisation() {
        assertThat(SchemaDriftGuard.norm("Membership_Family")).isEqualTo("membership_family");
        assertThat(SchemaDriftGuard.norm("public.family_member")).isEqualTo("family_member");
        assertThat(SchemaDriftGuard.norm("\"MixedCase\"")).isEqualTo("MixedCase");
        assertThat(SchemaDriftGuard.norm(null)).isNull();
    }
}
