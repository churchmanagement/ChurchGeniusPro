package com.churchgeniuspro.schema;

import com.churchgeniuspro.service.SchemaFixService;
import com.churchgeniuspro.service.SchemaFixService.RateColumn;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database audit C1: the production companion to the SchemaFixService widening lives in
 * {@code migrate_production.sql}. This pins that the script widens exactly the columns the
 * application widens, to the same types, and repairs only the rounded seed. The section
 * was executed against PostgreSQL 16 (fresh, re-run, and with a hand-edited row plus
 * rounded state/deduction rates present) before shipping.
 */
@DisplayName("migrate_production.sql — payroll rate precision section (DB audit C1)")
class PayrollRateMigrationTest {

    private static String section;

    @BeforeAll
    static void loadSection() throws IOException {
        section = MigrationScript.section("Database audit C1");
    }

    @Test
    @DisplayName("widens every column SchemaFixService widens, to the same type — the two lists cannot drift apart")
    void widensTheSameColumnsAsTheApplication() {
        for (RateColumn rc : SchemaFixService.PAYROLL_RATE_COLUMNS) {
            assertThat(section)
                    .as("%s.%s → %s in the script", rc.table(), rc.column(), rc.type())
                    .containsPattern("\\('" + rc.table() + "',\\s+'" + rc.column() + "',\\s+'" + rc.type().replace("(", "\\(").replace(")", "\\)") + "'\\)");
        }
        // and nothing else
        long entries = section.lines().filter(l -> l.trim().startsWith("('payroll_")).count();
        assertThat(entries).isEqualTo(SchemaFixService.PAYROLL_RATE_COLUMNS.size());
    }

    @Test
    @DisplayName("widening is conditional on the current scale, so re-runs and already-wide columns are no-ops")
    void wideningIsConditional() {
        assertThat(section).contains("ELSIF v_scale >= 6 THEN");
        assertThat(section).contains("EXECUTE format('ALTER TABLE %I ALTER COLUMN %I TYPE %s', r.tbl, r.col, r.typ);");
    }

    @Test
    @DisplayName("repairs only the row that holds exactly the rounded seed, after the widening, and never touches any other row")
    void repairsOnlyTheRoundingShape() {
        int widen = section.indexOf("ALTER COLUMN %I TYPE %s");
        int repair = section.indexOf("UPDATE payroll_fica_rate");
        assertThat(widen).isPositive();
        assertThat(repair).isGreaterThan(widen);
        assertThat(section).contains("SET social_security_rate     = 0.062,");
        assertThat(section).contains("medicare_rate            = 0.0145,");
        assertThat(section).contains("additional_medicare_rate = 0.009");
        assertThat(section).contains("WHERE effective_year = 2026");
        assertThat(section).contains("AND social_security_rate     = 0.06");
        assertThat(section).contains("AND medicare_rate            = 0.01");
        assertThat(section).contains("AND additional_medicare_rate = 0.01;");
        // a still-narrow column means the UPDATE would be rounded again — it must bail out first
        assertThat(section).contains("IF v_scale IS NULL OR v_scale < 3 THEN");
    }

    @Test
    @DisplayName("reports a 2026 row that is wrong in any other way, and the state/deduction rates that must be re-entered")
    void reportsWhatItCannotRepair() {
        assertThat(section).contains("does not match the statutory 0.062 / 0.0145 / 0.009 / 184500 / 200000");
        assertThat(section).contains("re-enter their rates from source");
        assertThat(section).contains("WHERE percentage_based");
    }
}
