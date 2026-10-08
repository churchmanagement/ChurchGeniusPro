package com.churchgeniuspro.schema;

import com.churchgeniuspro.payroll.config.Federal2026TaxData;
import com.churchgeniuspro.payroll.config.FicaConfig;
import com.churchgeniuspro.payroll.entity.DeductionDefinition;
import com.churchgeniuspro.payroll.entity.EmployeeDeduction;
import com.churchgeniuspro.payroll.entity.FederalTaxBracket;
import com.churchgeniuspro.payroll.entity.FicaRate;
import com.churchgeniuspro.payroll.entity.StateTaxBracket;
import com.churchgeniuspro.payroll.entity.StateTaxConfig;
import com.churchgeniuspro.service.SchemaFixService;
import com.churchgeniuspro.service.SchemaFixService.RateColumn;
import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Database audit C1: every payroll rate column was created as {@code numeric(38,2)} —
 * Hibernate's default for a {@code BigDecimal} with no declared precision — so the 2026
 * FICA seed {@code 0.062 / 0.0145 / 0.009} was stored as {@code 0.06 / 0.01 / 0.01}
 * (confirmed in production 16 Sep 2026) and every paystub withheld at those rates.
 *
 * <p>Three things are pinned here: the entities declare a precision on every rate column;
 * {@link SchemaFixService} widens the columns of an existing database to exactly those
 * types and repairs the rounded seed; and it does so only in the right order and only for
 * the rounding shape. {@code SchemaFixServiceIT} runs the same against PostgreSQL.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Payroll rate precision (DB audit C1)")
class PayrollRatePrecisionTest {

    private static final String SCALE_QUERY_FRAGMENT = "SELECT numeric_scale FROM information_schema.columns";
    private static final String EXISTS_QUERY_FRAGMENT = "SELECT EXISTS";
    private static final String REPAIR_UPDATE_FRAGMENT = "UPDATE payroll_fica_rate";

    @Mock JdbcTemplate jdbc;

    // ── entities ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("entities")
    class Entities {

        /** entity class + field → the table.column it maps to (Spring's default naming: camelCase → snake_case). */
        private static final Map<String, Class<?>> ENTITY_BY_TABLE = Map.of(
                "payroll_fica_rate", FicaRate.class,
                "payroll_federal_bracket", FederalTaxBracket.class,
                "payroll_state_bracket", StateTaxBracket.class,
                "payroll_state_tax_config", StateTaxConfig.class,
                "payroll_employee_deduction", EmployeeDeduction.class,
                "payroll_deduction_definition", DeductionDefinition.class);

        private static String snake(String camel) {
            return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        }

        @Test
        @DisplayName("every column SchemaFixService widens is declared with exactly that precision on its entity")
        void widenedColumnsMatchTheEntityAnnotations() throws Exception {
            for (RateColumn rc : SchemaFixService.PAYROLL_RATE_COLUMNS) {
                Class<?> entity = ENTITY_BY_TABLE.get(rc.table());
                assertThat(entity).as("entity for %s", rc.table()).isNotNull();
                Field field = null;
                for (Field f : entity.getDeclaredFields()) {
                    if (snake(f.getName()).equals(rc.column())) field = f;
                }
                assertThat(field).as("%s.%s maps a field of %s", rc.table(), rc.column(), entity.getSimpleName()).isNotNull();
                assertThat(field.getType()).isEqualTo(BigDecimal.class);
                Column column = field.getAnnotation(Column.class);
                assertThat(column).as("@Column on %s.%s", entity.getSimpleName(), field.getName()).isNotNull();
                assertThat(column.precision()).as("precision of %s.%s", rc.table(), rc.column()).isEqualTo(rc.precision());
                assertThat(column.scale()).as("scale of %s.%s", rc.table(), rc.column()).isEqualTo(rc.scale());
            }
        }

        @Test
        @DisplayName("no rate-bearing BigDecimal field is left without a precision (the C1 defect's origin)")
        void noRateFieldWithoutPrecision() {
            // The rate fields: name ends in "rate" or "Rate", or is one of the two amount-or-rate columns.
            for (Map.Entry<String, Class<?>> e : ENTITY_BY_TABLE.entrySet()) {
                for (Field f : e.getValue().getDeclaredFields()) {
                    if (f.getType() != BigDecimal.class) continue;
                    String name = f.getName();
                    boolean rateBearing = name.toLowerCase(Locale.ROOT).endsWith("rate")
                            || name.equals("amountOrRate") || name.equals("defaultAmount");
                    if (!rateBearing) continue;
                    Column c = f.getAnnotation(Column.class);
                    assertThat(c).as("@Column on %s.%s", e.getValue().getSimpleName(), name).isNotNull();
                    assertThat(c.scale()).as("scale of %s.%s", e.getValue().getSimpleName(), name).isGreaterThanOrEqualTo(6);
                    assertThat(SchemaFixService.PAYROLL_RATE_COLUMNS)
                            .as("%s is in the widening list", snake(name))
                            .anyMatch(rc -> rc.table().equals(e.getKey()) && rc.column().equals(snake(name)));
                }
            }
        }

        @Test
        @DisplayName("the declared types can hold every statutory 2026 rate without loss")
        void typesHoldTheStatutoryRates() {
            FicaConfig f = Federal2026TaxData.fica();
            for (BigDecimal rate : List.of(f.getSocialSecurityRate(), f.getMedicareRate(), f.getAdditionalMedicareRate())) {
                assertThat(rate.scale()).isLessThanOrEqualTo(6);
                assertThat(rate.setScale(6).compareTo(rate)).isZero();
                // and what the old column did to it
                assertThat(rate.setScale(2, java.math.RoundingMode.HALF_UP).compareTo(rate)).isNotZero();
            }
        }
    }

    // ── SchemaFixService: widening ───────────────────────────────────────────

    private void scaleReports(String table, String column, Integer scale) {
        when(jdbc.queryForList(contains(SCALE_QUERY_FRAGMENT), eq(Integer.class), eq(table), eq(column)))
                .thenReturn(scale == null ? List.of() : List.of(scale));
    }

    private void allColumnsReportScale(int scale) {
        for (RateColumn rc : SchemaFixService.PAYROLL_RATE_COLUMNS) scaleReports(rc.table(), rc.column(), scale);
    }

    @Nested
    @DisplayName("widening")
    class Widening {

        @Test
        @DisplayName("production's shape: every rate column at scale 2 is altered to the entity's type")
        void widensEveryColumnAtScaleTwo() {
            allColumnsReportScale(2);

            boolean wide = new SchemaFixService(jdbc).widenPayrollRateColumns();

            assertThat(wide).isTrue();
            verify(jdbc).execute("ALTER TABLE payroll_fica_rate ALTER COLUMN social_security_rate TYPE numeric(9,6)");
            verify(jdbc).execute("ALTER TABLE payroll_fica_rate ALTER COLUMN medicare_rate TYPE numeric(9,6)");
            verify(jdbc).execute("ALTER TABLE payroll_fica_rate ALTER COLUMN additional_medicare_rate TYPE numeric(9,6)");
            verify(jdbc).execute("ALTER TABLE payroll_federal_bracket ALTER COLUMN rate TYPE numeric(9,6)");
            verify(jdbc).execute("ALTER TABLE payroll_state_bracket ALTER COLUMN rate TYPE numeric(9,6)");
            verify(jdbc).execute("ALTER TABLE payroll_state_tax_config ALTER COLUMN flat_rate TYPE numeric(9,6)");
            verify(jdbc).execute("ALTER TABLE payroll_state_tax_config ALTER COLUMN local_tax_rate TYPE numeric(9,6)");
            verify(jdbc).execute("ALTER TABLE payroll_employee_deduction ALTER COLUMN amount_or_rate TYPE numeric(15,6)");
            verify(jdbc).execute("ALTER TABLE payroll_deduction_definition ALTER COLUMN default_amount TYPE numeric(15,6)");
        }

        @Test
        @DisplayName("already-wide columns and columns that do not exist yet are left alone (repeated restarts, fresh databases)")
        void noOpWhenWideOrAbsent() {
            allColumnsReportScale(6);
            scaleReports("payroll_state_bracket", "rate", null);        // table not created yet

            boolean wide = new SchemaFixService(jdbc).widenPayrollRateColumns();

            assertThat(wide).isTrue();
            verify(jdbc, never()).execute(any(String.class));
        }

        @Test
        @DisplayName("a failed ALTER is logged, reported as 'not wide', and never propagated")
        void failureIsReportedNotThrown() {
            allColumnsReportScale(2);
            org.mockito.Mockito.doThrow(new RuntimeException("must be owner of table payroll_fica_rate"))
                    .when(jdbc).execute(contains("payroll_fica_rate"));

            SchemaFixService svc = new SchemaFixService(jdbc);
            assertThatCode(svc::widenPayrollRateColumns).doesNotThrowAnyException();
            assertThat(svc.widenPayrollRateColumns()).isFalse();
        }
    }

    // ── SchemaFixService: repairing the rounded seed ─────────────────────────

    private void ficaTableExists(boolean exists) {
        when(jdbc.queryForObject(contains(EXISTS_QUERY_FRAGMENT), eq(Boolean.class), eq("payroll_fica_rate"),
                eq("social_security_rate"))).thenReturn(exists);
    }

    @Nested
    @DisplayName("repairing the rounded seed")
    class Repair {

        @Test
        @DisplayName("the UPDATE targets only the 2026 row that holds exactly the rounded seed, and writes the statutory rates")
        void repairsExactlyTheRoundingShape() {
            ficaTableExists(true);
            FicaConfig f = Federal2026TaxData.fica();

            new SchemaFixService(jdbc).repairRoundedFicaSeed();

            verify(jdbc).update(
                    contains("SET social_security_rate = ?, medicare_rate = ?, additional_medicare_rate = ? "
                            + "WHERE effective_year = ? AND social_security_rate = ? AND medicare_rate = ? "
                            + "AND additional_medicare_rate = ?"),
                    eq(f.getSocialSecurityRate()), eq(f.getMedicareRate()), eq(f.getAdditionalMedicareRate()),   // SET …
                    eq(Federal2026TaxData.YEAR),                                                                    // WHERE …
                    eq(new BigDecimal("0.06")), eq(new BigDecimal("0.01")), eq(new BigDecimal("0.01")));
        }

        @Test
        @DisplayName("no FICA table yet (seeded on the first payroll run, with the right precision) — nothing to do")
        void noOpWithoutTheTable() {
            ficaTableExists(false);

            new SchemaFixService(jdbc).repairRoundedFicaSeed();

            verify(jdbc, never()).update(contains(REPAIR_UPDATE_FRAGMENT), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("the startup sequence widens first and repairs only if every column is wide — an UPDATE into numeric(38,2) would be rounded again")
        void repairOnlyAfterSuccessfulWidening() {
            allColumnsReportScale(2);
            ficaTableExists(true);
            org.mockito.Mockito.doThrow(new RuntimeException("permission denied"))
                    .when(jdbc).execute(contains("ALTER COLUMN medicare_rate"));

            new SchemaFixService(jdbc).dropLegacyConstraints();

            verify(jdbc, never()).update(contains(REPAIR_UPDATE_FRAGMENT), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("…and repairs when the widening succeeded")
        void repairsAfterWidening() {
            allColumnsReportScale(2);
            ficaTableExists(true);
            new SchemaFixService(jdbc).dropLegacyConstraints();

            verify(jdbc).update(contains(REPAIR_UPDATE_FRAGMENT), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("what numeric(38,2) made of the seed is computed, not typed: 0.062→0.06, 0.0145→0.01, 0.009→0.01")
        void roundingShapeIsDerived() {
            assertThat(SchemaFixService.roundedByOldColumn(new BigDecimal("0.062"))).isEqualByComparingTo("0.06");
            assertThat(SchemaFixService.roundedByOldColumn(new BigDecimal("0.0145"))).isEqualByComparingTo("0.01");
            assertThat(SchemaFixService.roundedByOldColumn(new BigDecimal("0.009"))).isEqualByComparingTo("0.01");
            assertThat(SchemaFixService.roundedByOldColumn(new BigDecimal("0.0307"))).isEqualByComparingTo("0.03");
            assertThat(SchemaFixService.roundedByOldColumn(new BigDecimal("0.035"))).isEqualByComparingTo("0.04");
        }
    }

    @Test
    @DisplayName("the startup verification never throws, whatever the row looks like")
    void verificationNeverThrows() {
        ficaTableExists(true);
        when(jdbc.queryForList(contains("FROM payroll_fica_rate WHERE effective_year = ?"), anyInt()))
                .thenReturn(List.of(Map.of("social_security_rate", new BigDecimal("0.06"),
                        "medicare_rate", new BigDecimal("0.01"), "additional_medicare_rate", new BigDecimal("0.01"),
                        "social_security_wage_base", new BigDecimal("184500.00"),
                        "additional_medicare_threshold", new BigDecimal("200000.00"))));

        assertThatCode(() -> new SchemaFixService(jdbc).verifyFicaSeed()).doesNotThrowAnyException();
    }
}
