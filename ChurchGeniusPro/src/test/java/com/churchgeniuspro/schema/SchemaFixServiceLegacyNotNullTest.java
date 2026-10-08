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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Database audit P1: {@code membership_family.family_name} is a legacy NOT NULL column no
 * entity maps, so every INSERT the application makes is rejected. ddl-auto cannot relax
 * it; {@link SchemaFixService} now does, at startup, exactly like the other legacy
 * constraints it already removes — and only when the column is still NOT NULL.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SchemaFixService — relaxes the legacy NOT NULL on membership_family.family_name (DB audit P1)")
class SchemaFixServiceLegacyNotNullTest {

    private static final String NULLABILITY_SQL_FRAGMENT = "SELECT is_nullable FROM information_schema.columns";
    private static final String RELAX_SQL = "ALTER TABLE membership_family ALTER COLUMN family_name DROP NOT NULL";

    @Mock JdbcTemplate jdbc;

    private void columnReports(String isNullable) {
        when(jdbc.queryForList(contains(NULLABILITY_SQL_FRAGMENT), eq(String.class),
                eq("membership_family"), eq("family_name")))
                .thenReturn(isNullable == null ? List.of() : List.of(isNullable));
    }

    @Test
    @DisplayName("a still-NOT NULL column is relaxed with exactly one ALTER")
    void relaxesWhenNotNull() {
        columnReports("NO");

        new SchemaFixService(jdbc).relaxLegacyNotNull("membership_family", "family_name");

        verify(jdbc).execute(RELAX_SQL);
    }

    @Test
    @DisplayName("an already-nullable column is left alone (repeated restarts are no-ops)")
    void noOpWhenAlreadyNullable() {
        columnReports("YES");

        new SchemaFixService(jdbc).relaxLegacyNotNull("membership_family", "family_name");

        verify(jdbc, never()).execute(any(String.class));
    }

    @Test
    @DisplayName("a column that has already been dropped (migrate_production.sql ran first) is left alone")
    void noOpWhenColumnGone() {
        columnReports(null);

        new SchemaFixService(jdbc).relaxLegacyNotNull("membership_family", "family_name");

        verify(jdbc, never()).execute(any(String.class));
    }

    @Test
    @DisplayName("a database error is logged, never propagated — startup must not depend on it")
    void failureNeverBlocksStartup() {
        columnReports("NO");
        org.mockito.Mockito.doThrow(new RuntimeException("permission denied for table membership_family"))
                .when(jdbc).execute(RELAX_SQL);

        assertThatCode(() -> new SchemaFixService(jdbc).relaxLegacyNotNull("membership_family", "family_name"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the startup entry point actually reaches the new step (it is wired into dropLegacyConstraints)")
    void startupEntryPointRelaxesTheColumn() {
        columnReports("NO");
        // Every other legacy step reads a Boolean/Integer it treats as "nothing to do" when null,
        // so with a default mock they all skip and only the P1 step performs an ALTER.

        new SchemaFixService(jdbc).dropLegacyConstraints();

        verify(jdbc).execute(RELAX_SQL);
    }
}
