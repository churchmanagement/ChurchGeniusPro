package com.churchgeniuspro.schema;

import com.churchgeniuspro.service.SchemaFixService;
import com.churchgeniuspro.service.SchemaFixService.LegacyColumn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Database audit P3: ten columns no entity maps any more are still in production
 * ({@code ddl-auto} never drops a column). {@link SchemaFixService} drops each at startup
 * — but only when no row holds a value in it; a column with data is kept and reported,
 * because deciding about that data is a person's job, not a startup hook's.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SchemaFixService — drops empty legacy columns (DB audit P3)")
class SchemaFixServiceLegacyColumnsTest {

    private static final String TYPE_QUERY_FRAGMENT = "SELECT data_type FROM information_schema.columns";

    @Mock JdbcTemplate jdbc;

    private void columnIs(String table, String column, String dataType, long populatedRows) {
        when(jdbc.queryForList(contains(TYPE_QUERY_FRAGMENT), eq(String.class), eq(table), eq(column)))
                .thenReturn(dataType == null ? List.of() : List.of(dataType));
        when(jdbc.queryForObject(contains("SELECT count(*) FROM " + table + " WHERE " + column), eq(Long.class)))
                .thenReturn(populatedRows);
    }

    @Test
    @DisplayName("an empty legacy column is dropped")
    void emptyColumnIsDropped() {
        columnIs("family", "member_renewal_date", "date", 0);

        boolean dropped = new SchemaFixService(jdbc).dropLegacyColumnIfEmpty("family", "member_renewal_date");

        assertThat(dropped).isTrue();
        verify(jdbc).execute("ALTER TABLE family DROP COLUMN IF EXISTS member_renewal_date");
    }

    @Test
    @DisplayName("a legacy column that still holds a value in any row is kept — reported, never emptied")
    void populatedColumnIsKept() {
        columnIs("family_member", "renewal_date", "date", 3);

        boolean dropped = new SchemaFixService(jdbc).dropLegacyColumnIfEmpty("family_member", "renewal_date");

        assertThat(dropped).isFalse();
        verify(jdbc, never()).execute(any(String.class));
    }

    @Test
    @DisplayName("an already-dropped column is a no-op (repeated restarts, databases the script migrated first)")
    void absentColumnIsNoOp() {
        columnIs("subscription_plan", "extra_sms_count", null, 0);

        assertThat(new SchemaFixService(jdbc).dropLegacyColumnIfEmpty("subscription_plan", "extra_sms_count")).isFalse();
        verify(jdbc, never()).execute(any(String.class));
        verify(jdbc, never()).queryForObject(any(String.class), eq(Long.class));
    }

    @Test
    @DisplayName("'no value' depends on the type: NULL or '' for text, NULL or 0 for numbers (an unmapped column with DEFAULT 0 fills with zeros), NULL otherwise")
    void populatedPredicateByType() {
        assertThat(SchemaFixService.populatedPredicate("family_city", "character varying"))
                .isEqualTo("family_city IS NOT NULL AND family_city <> ''");
        assertThat(SchemaFixService.populatedPredicate("notes", "text")).isEqualTo("notes IS NOT NULL AND notes <> ''");
        assertThat(SchemaFixService.populatedPredicate("extra_sms_count", "integer"))
                .isEqualTo("extra_sms_count IS NOT NULL AND extra_sms_count <> 0");
        assertThat(SchemaFixService.populatedPredicate("amount", "numeric")).isEqualTo("amount IS NOT NULL AND amount <> 0");
        assertThat(SchemaFixService.populatedPredicate("renewal_date", "date")).isEqualTo("renewal_date IS NOT NULL");
        assertThat(SchemaFixService.populatedPredicate("flag", "boolean")).isEqualTo("flag IS NOT NULL");
    }

    @Test
    @DisplayName("the list is exactly production's ten legacy columns (P1's family_name, six more membership_family columns, and three others)")
    void theTenColumns() {
        assertThat(SchemaFixService.LEGACY_EMPTY_COLUMNS).containsExactlyInAnyOrder(
                new LegacyColumn("membership_family", "family_name"),
                new LegacyColumn("membership_family", "family_address1"),
                new LegacyColumn("membership_family", "family_address2"),
                new LegacyColumn("membership_family", "family_city"),
                new LegacyColumn("membership_family", "family_state"),
                new LegacyColumn("membership_family", "family_country"),
                new LegacyColumn("membership_family", "family_pin_code"),
                new LegacyColumn("family", "member_renewal_date"),
                new LegacyColumn("family_member", "renewal_date"),
                new LegacyColumn("subscription_plan", "extra_sms_count"));
    }

    @Test
    @DisplayName("the startup entry point looks at every one of them, after relaxing family_name's NOT NULL")
    void startupEntryPointCoversTheList() {
        for (LegacyColumn c : SchemaFixService.LEGACY_EMPTY_COLUMNS) {
            columnIs(c.table(), c.column(), c.column().equals("extra_sms_count") ? "integer"
                    : c.column().endsWith("date") ? "date" : "character varying", 0);
        }

        new SchemaFixService(jdbc).dropLegacyConstraints();

        for (LegacyColumn c : SchemaFixService.LEGACY_EMPTY_COLUMNS) {
            verify(jdbc).execute("ALTER TABLE " + c.table() + " DROP COLUMN IF EXISTS " + c.column());
        }
    }

    @Test
    @DisplayName("a catalogue or DDL failure is logged, never propagated")
    void failureNeverBlocksStartup() {
        columnIs("family", "member_renewal_date", "date", 0);
        org.mockito.Mockito.doThrow(new RuntimeException("must be owner of table family"))
                .when(jdbc).execute(any(String.class));

        assertThatCode(() -> new SchemaFixService(jdbc).dropLegacyColumnIfEmpty("family", "member_renewal_date"))
                .doesNotThrowAnyException();
    }
}
