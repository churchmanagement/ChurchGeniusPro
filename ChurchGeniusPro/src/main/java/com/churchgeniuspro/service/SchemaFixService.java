package com.churchgeniuspro.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Repairs, at startup, the things Hibernate's {@code ddl-auto=update} leaves behind or
 * cannot do: legacy constraints and columns, a NOT NULL that blocks every insert, a
 * column type that rounds its values away.
 *
 * <p>Runs once at application startup, <em>after</em> Hibernate has finished its own
 * schema update ({@code @DependsOn("entityManagerFactory")}), so every table the entities
 * map exists by the time this looks at the catalogue — on a fresh database there is then
 * simply nothing legacy to find. Every step is conditional on the catalogue and safe to
 * repeat; a failure is logged and never stops the application (the drift it leaves behind
 * is reported by {@link com.churchgeniuspro.config.SchemaDriftGuard}). The historical
 * data migrations that used to live here ({@code income.method} and {@code expense.method}
 * → {@code transaction_type_id}, {@code family_member.signup_ref} → {@code member_ref})
 * have run in production (verified 16 Sep 2026) and were retired to
 * {@code migrate_production.sql} §"Database audit P6", where they can still be applied to
 * an older database — with the guard that a column is only dropped once every row has
 * been migrated (database audit M5).
 *
 * <p>Managed here:
 * <ul>
 *   <li>{@code uk1j9d9a06i600gd43uu3km82jw} — the auto-generated single-column
 *       UNIQUE constraint on {@code app_user.email} created by the original
 *       {@code @Column(unique=true)} annotation.  Replaced by the composite
 *       constraint {@code uq_app_user_email_role_client} on
 *       (email, role, client_id).</li>
 *   <li>{@code uq_app_user_email_role} — an intermediate composite constraint
 *       on (email, role) that was added in a transitional commit and is now
 *       superseded by the three-column constraint above.</li>
 *   <li>{@code membership_family.family_name NOT NULL} — a legacy column no entity
 *       maps (database audit P1, Sept 2026); its NOT NULL rejected every INSERT the
 *       public membership form made. Relaxed here first, then dropped with the other
 *       legacy columns below once confirmed empty.</li>
 *   <li>Ten legacy columns no entity maps (database audit P3): the seven
 *       {@code membership_family.family_*} name/address columns,
 *       {@code family.member_renewal_date}, {@code family_member.renewal_date} and
 *       {@code subscription_plan.extra_sms_count} (moved to {@code service_client}).
 *       Each is dropped only if no row holds a value in it; otherwise it is kept and
 *       reported.</li>
 *   <li>The pre-M7 <b>global</b> unique keys on {@code plaid_item.item_id},
 *       {@code plaid_account.account_id} and
 *       {@code plaid_transaction_staging.plaid_transaction_id} (financial audit M7 /
 *       database audit H7, Sept 2026). The entities now declare per-tenant
 *       {@code (client_id, <id>)} keys, which Hibernate created — but Hibernate only ever
 *       drops and recreates the unique keys it currently knows about, so the old global
 *       ones stayed behind, still enforced, and the M7 code fix could not take effect for
 *       two tenants sharing one Plaid sandbox institution.</li>
 *   <li>The payroll <b>rate</b> columns (database audit C1, Sept 2026): created by
 *       {@code ddl-auto} as {@code numeric(38,2)} — Hibernate's default for an unqualified
 *       {@code BigDecimal} — so the 2026 FICA seed {@code 0.062 / 0.0145 / 0.009} was
 *       stored as {@code 0.06 / 0.01 / 0.01} and every paystub withheld at those rates.
 *       The entities now declare their precision; {@code ddl-auto} never alters an
 *       existing column's type, so the columns are widened here and the FICA row is
 *       repaired where it still carries the rounded seed.</li>
 * </ul>
 *
 * <p>Anything of this shape that is <em>not</em> handled here is reported at startup by
 * {@link com.churchgeniuspro.config.SchemaDriftGuard}.
 */
@Component
@DependsOn("entityManagerFactory")   // after Hibernate's own schema update (database audit M5)
public class SchemaFixService {

    private static final Logger log = LoggerFactory.getLogger(SchemaFixService.class);

    /** A payroll column that holds a rate (or a rate-or-amount) and the type it must have. */
    public record RateColumn(String table, String column, int precision, int scale) {
        public String type() { return "numeric(" + precision + "," + scale + ")"; }
    }

    /**
     * Every payroll column that stores a rate, with the type its entity now declares
     * ({@code @Column(precision, scale)}). Pure rates get {@code numeric(9,6)}; the two
     * columns that hold either a flat amount or a rate get {@code numeric(15,6)}.
     * Pinned against the entity annotations by {@code PayrollRatePrecisionTest}.
     */
    public static final java.util.List<RateColumn> PAYROLL_RATE_COLUMNS = java.util.List.of(
            new RateColumn("payroll_fica_rate",            "social_security_rate",     9,  6),
            new RateColumn("payroll_fica_rate",            "medicare_rate",            9,  6),
            new RateColumn("payroll_fica_rate",            "additional_medicare_rate", 9,  6),
            new RateColumn("payroll_federal_bracket",      "rate",                     9,  6),
            new RateColumn("payroll_state_bracket",        "rate",                     9,  6),
            new RateColumn("payroll_state_tax_config",     "flat_rate",                9,  6),
            new RateColumn("payroll_state_tax_config",     "local_tax_rate",           9,  6),
            new RateColumn("payroll_employee_deduction",   "amount_or_rate",           15, 6),
            new RateColumn("payroll_deduction_definition", "default_amount",           15, 6));

    /** A legacy column no entity maps any more, dropped at startup once it holds no data. */
    public record LegacyColumn(String table, String column) {}

    /**
     * Columns left behind by entity refactors (database audit P3, verified empty in
     * production 16 Sep 2026). {@code ddl-auto} never drops a column; these are dropped
     * here — each only when every row is NULL, empty or zero in it — and by
     * {@code migrate_production.sql} §P1/§P3. Pinned against the entity mapping by
     * {@code SchemaFixServiceIT}: none of them is mapped.
     */
    public static final java.util.List<LegacyColumn> LEGACY_EMPTY_COLUMNS = java.util.List.of(
            new LegacyColumn("membership_family", "family_name"),
            new LegacyColumn("membership_family", "family_address1"),
            new LegacyColumn("membership_family", "family_address2"),
            new LegacyColumn("membership_family", "family_city"),
            new LegacyColumn("membership_family", "family_state"),
            new LegacyColumn("membership_family", "family_country"),
            new LegacyColumn("membership_family", "family_pin_code"),
            new LegacyColumn("family",            "member_renewal_date"),
            new LegacyColumn("family_member",     "renewal_date"),
            new LegacyColumn("subscription_plan", "extra_sms_count"));

    private final JdbcTemplate jdbc;

    public SchemaFixService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void dropLegacyConstraints() {
        // Drop every single-column UNIQUE constraint on app_user.email
        // (covers any auto-generated name, not just the known one).
        dropSingleColumnEmailUnique();

        // Drop the intermediate (email, role) constraint if it still exists.
        dropNamedConstraintIfExists("app_user", "uq_app_user_email_role");

        // Database audit P1: membership_family.family_name is a legacy NOT NULL column
        // that no entity maps any more (the family's name and address moved to the
        // Head membership_family_member row). Hibernate's INSERT therefore never
        // supplies it, PostgreSQL rejects every row, and the public membership form
        // has been unable to store a single submission. ddl-auto can neither drop a
        // column nor relax its NOT NULL, so it is relaxed here first — the form works
        // from this moment even if the column has to stay because it holds data.
        relaxLegacyNotNull("membership_family", "family_name");

        // Database audit P3: the legacy columns themselves. Dropped only when empty;
        // a column that still holds a value is kept and reported, never emptied.
        for (LegacyColumn legacy : LEGACY_EMPTY_COLUMNS) {
            dropLegacyColumnIfEmpty(legacy.table(), legacy.column());
        }

        // Financial audit M7 / database audit H7: the pre-M7 GLOBAL unique keys on the
        // three Plaid external-id columns. The entities now declare (client_id, <id>) keys
        // and Hibernate created those, but it never removes a unique key it no longer
        // maps, so the old ones stayed behind — still enforced, so the M7 code fix could
        // not take effect. Removed here whatever their name and whether each is a
        // constraint or a bare unique index (mirrored in migrate_production.sql §M7).
        dropSingleColumnUniqueKeys("plaid_item", "item_id");
        dropSingleColumnUniqueKeys("plaid_account", "account_id");
        dropSingleColumnUniqueKeys("plaid_transaction_staging", "plaid_transaction_id");

        // Database audit C1: the payroll rate columns were created as numeric(38,2), which
        // rounded the 2026 FICA seed to 0.06 / 0.01 / 0.01. Widen them to what the entities
        // now declare (ddl-auto never alters an existing column's type), then repair the
        // FICA row where it still carries exactly the rounded seed. Order matters: an
        // UPDATE before the ALTER would be rounded to two places again.
        boolean rateColumnsWide = widenPayrollRateColumns();
        if (rateColumnsWide) {
            repairRoundedFicaSeed();
        }
        verifyFicaSeed();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Drops every non-partial UNIQUE key on {@code table} that covers exactly the one
     * column {@code column} — the primary key excepted — whether it is a constraint
     * ({@code ALTER TABLE … DROP CONSTRAINT}) or a bare unique index ({@code DROP INDEX}),
     * and whatever it is called. Composite keys that include the column are untouched:
     * they are the per-tenant keys that replace the global one. No-op once nothing is
     * left, so repeated restarts are safe; a failure is logged and never blocks startup.
     */
    public void dropSingleColumnUniqueKeys(String table, String column) {
        try {
            java.util.List<java.util.Map<String, Object>> keys = jdbc.queryForList(
                "SELECT i.relname AS index_name, c.conname AS constraint_name " +
                "FROM pg_index x " +
                "JOIN pg_class t ON t.oid = x.indrelid " +
                "JOIN pg_namespace n ON n.oid = t.relnamespace " +
                "JOIN pg_class i ON i.oid = x.indexrelid " +
                "JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = x.indkey[0] " +
                "LEFT JOIN pg_constraint c ON c.conindid = i.oid AND c.contype IN ('u', 'p') " +
                "WHERE n.nspname = current_schema() AND t.relname = ? " +
                "  AND x.indisunique AND NOT x.indisprimary " +
                "  AND x.indnatts = 1 AND x.indpred IS NULL " +
                "  AND a.attname = ? " +
                "ORDER BY i.relname", table, column);
            for (java.util.Map<String, Object> key : keys) {
                String constraint = (String) key.get("constraint_name");
                String index = (String) key.get("index_name");
                if (constraint != null) {
                    jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS \"" + constraint + "\"");
                    log.info("SchemaFixService: legacy global unique constraint {} on {}.{} dropped "
                             + "(uniqueness is per tenant now).", constraint, table, column);
                } else {
                    jdbc.execute("DROP INDEX IF EXISTS \"" + index + "\"");
                    log.info("SchemaFixService: legacy global unique index {} on {}.{} dropped "
                             + "(uniqueness is per tenant now).", index, table, column);
                }
            }
        } catch (Exception e) {
            log.warn("SchemaFixService: could not drop single-column unique keys on {}.{} — {}",
                     table, column, e.getMessage());
        }
    }

    /**
     * Widens every column in {@link #PAYROLL_RATE_COLUMNS} whose scale is still below the
     * entity's, so that rates such as {@code 0.0145} survive the round trip. A column that
     * does not exist yet (the table is created by Hibernate from the entity, with the right
     * type) or is already wide enough is left alone, so repeated restarts are safe.
     *
     * @return {@code true} when every rate column that exists now has the declared scale
     *         (so a value with more than two decimals can be stored), {@code false} if any
     *         widening failed
     */
    public boolean widenPayrollRateColumns() {
        boolean allWide = true;
        for (RateColumn rc : PAYROLL_RATE_COLUMNS) {
            try {
                java.util.List<Integer> scales = jdbc.queryForList(
                    "SELECT numeric_scale FROM information_schema.columns " +
                    "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ? " +
                    "  AND data_type = 'numeric'",
                    Integer.class, rc.table(), rc.column());
                if (scales.isEmpty()) {
                    continue;                                   // not created yet — the entity will
                }
                Integer scale = scales.get(0);
                if (scale != null && scale >= rc.scale()) {
                    continue;                                   // already widened on an earlier start
                }
                jdbc.execute("ALTER TABLE " + rc.table() + " ALTER COLUMN " + rc.column()
                             + " TYPE " + rc.type());
                log.info("SchemaFixService: {}.{} widened from numeric scale {} to {} (rates were being "
                         + "rounded to two decimals).", rc.table(), rc.column(), scale, rc.type());
            } catch (Exception e) {
                allWide = false;
                log.warn("SchemaFixService: could not widen {}.{} to {} — {}",
                         rc.table(), rc.column(), rc.type(), e.getMessage());
            }
        }
        return allWide;
    }

    /**
     * Restores the statutory 2026 FICA rates on the {@code payroll_fica_rate} row that
     * still holds exactly what {@code numeric(38,2)} made of the seed — {@code 0.062 →
     * 0.06}, {@code 0.0145 → 0.01}, {@code 0.009 → 0.01}. Only that precise shape is
     * touched: a row that differs in any other way is not the rounding defect and is left
     * for {@link #verifyFicaSeed()} to report. No-op once repaired. Must run after
     * {@link #widenPayrollRateColumns()}, or the UPDATE would be rounded again.
     */
    public void repairRoundedFicaSeed() {
        try {
            if (!columnExists("payroll_fica_rate", "social_security_rate")) {
                return;                                         // table not created yet
            }
            com.churchgeniuspro.payroll.config.FicaConfig f = com.churchgeniuspro.payroll.config.Federal2026TaxData.fica();
            int repaired = jdbc.update(
                "UPDATE payroll_fica_rate " +
                "SET social_security_rate = ?, medicare_rate = ?, additional_medicare_rate = ? " +
                "WHERE effective_year = ? " +
                "AND social_security_rate = ? AND medicare_rate = ? AND additional_medicare_rate = ?",
                f.getSocialSecurityRate(), f.getMedicareRate(), f.getAdditionalMedicareRate(),
                com.churchgeniuspro.payroll.config.Federal2026TaxData.YEAR,
                roundedByOldColumn(f.getSocialSecurityRate()),
                roundedByOldColumn(f.getMedicareRate()),
                roundedByOldColumn(f.getAdditionalMedicareRate()));
            if (repaired > 0) {
                log.warn("SchemaFixService: payroll_fica_rate {} carried the rounded seed ({} / {} / {}); "
                         + "restored to the statutory {} / {} / {}. Paystubs computed before this start used "
                         + "the rounded rates — review them (database audit C1).",
                         com.churchgeniuspro.payroll.config.Federal2026TaxData.YEAR,
                         roundedByOldColumn(f.getSocialSecurityRate()), roundedByOldColumn(f.getMedicareRate()),
                         roundedByOldColumn(f.getAdditionalMedicareRate()),
                         f.getSocialSecurityRate(), f.getMedicareRate(), f.getAdditionalMedicareRate());
            }
        } catch (Exception e) {
            log.warn("SchemaFixService: could not repair the rounded FICA seed — {}", e.getMessage());
        }
    }

    /**
     * Reports, at ERROR, a stored 2026 FICA row that differs from
     * {@code Federal2026TaxData.fica()} — the statutory values the seed writes. Diagnostics
     * only: {@code TaxConfigService.loadFica} refuses to compute payroll from such a row,
     * so the mismatch cannot silently reach a paystub again.
     */
    public void verifyFicaSeed() {
        try {
            if (!columnExists("payroll_fica_rate", "social_security_rate")) {
                return;
            }
            com.churchgeniuspro.payroll.config.FicaConfig f = com.churchgeniuspro.payroll.config.Federal2026TaxData.fica();
            java.util.List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                "SELECT social_security_rate, medicare_rate, additional_medicare_rate, " +
                "       social_security_wage_base, additional_medicare_threshold " +
                "FROM payroll_fica_rate WHERE effective_year = ?",
                com.churchgeniuspro.payroll.config.Federal2026TaxData.YEAR);
            if (rows.isEmpty()) {
                return;                                         // seeded on the first payroll run
            }
            java.util.Map<String, Object> r = rows.get(0);
            java.util.List<String> wrong = new java.util.ArrayList<>();
            compare(wrong, "social_security_rate",         r.get("social_security_rate"),         f.getSocialSecurityRate());
            compare(wrong, "medicare_rate",                r.get("medicare_rate"),                f.getMedicareRate());
            compare(wrong, "additional_medicare_rate",     r.get("additional_medicare_rate"),     f.getAdditionalMedicareRate());
            compare(wrong, "social_security_wage_base",    r.get("social_security_wage_base"),    f.getSocialSecurityWageBase());
            compare(wrong, "additional_medicare_threshold", r.get("additional_medicare_threshold"), f.getAdditionalMedicareThreshold());
            if (wrong.isEmpty()) {
                log.info("SchemaFixService: payroll_fica_rate {} matches the statutory values.",
                         com.churchgeniuspro.payroll.config.Federal2026TaxData.YEAR);
            } else {
                log.error("SchemaFixService: payroll_fica_rate {} differs from the statutory values — payroll "
                          + "will refuse to run until it is corrected (see migrate_production.sql, "
                          + "'Database audit C1'): {}",
                          com.churchgeniuspro.payroll.config.Federal2026TaxData.YEAR, String.join("; ", wrong));
            }
        } catch (Exception e) {
            log.warn("SchemaFixService: could not verify the FICA seed — {}", e.getMessage());
        }
    }

    private static void compare(java.util.List<String> wrong, String column, Object stored, java.math.BigDecimal expected) {
        java.math.BigDecimal actual = stored instanceof java.math.BigDecimal bd ? bd
                : stored == null ? null : new java.math.BigDecimal(stored.toString());
        if (actual == null || actual.compareTo(expected) != 0) {
            wrong.add(column + " is " + (actual == null ? "NULL" : actual.stripTrailingZeros().toPlainString())
                      + ", statutory " + expected.toPlainString());
        }
    }

    /** What a {@code numeric(38,2)} column made of a seeded rate: rounded half-up to two places. */
    public static java.math.BigDecimal roundedByOldColumn(java.math.BigDecimal rate) {
        return rate.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Drops the NOT NULL constraint from a legacy column that is still present in
     * the database but no longer mapped by any entity, so that Hibernate's INSERTs
     * (which never mention the column) stop failing on it. No-op when the column is
     * absent or already nullable, so repeated restarts are safe; a failure is logged
     * and never blocks startup — the SchemaDriftGuard will keep reporting the column
     * until it is fixed.
     */
    public void relaxLegacyNotNull(String table, String column) {
        try {
            java.util.List<String> nullable = jdbc.queryForList(
                "SELECT is_nullable FROM information_schema.columns " +
                "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                String.class, table, column);
            if (nullable.isEmpty()) {
                return;                         // column already dropped — nothing to do
            }
            if ("YES".equalsIgnoreCase(nullable.get(0))) {
                return;                         // already relaxed on an earlier start
            }
            jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN " + column + " DROP NOT NULL");
            log.info("SchemaFixService: NOT NULL dropped from legacy column {}.{} (unmapped by any entity).",
                     table, column);
        } catch (Exception e) {
            log.warn("SchemaFixService: could not relax NOT NULL on {}.{} — {}", table, column, e.getMessage());
        }
    }

    /**
     * Drops a legacy column no entity maps, provided no row holds a value in it — NULL,
     * the empty string and zero all count as "no value", which is what an unmapped
     * column with a default accumulates. A column that still carries data is left in
     * place and reported at WARN so the decision stays with a person; an absent column
     * is a no-op, so repeated restarts are safe. A failure is logged, never propagated.
     *
     * @return {@code true} if the column was dropped by this call
     */
    public boolean dropLegacyColumnIfEmpty(String table, String column) {
        try {
            java.util.List<String> types = jdbc.queryForList(
                "SELECT data_type FROM information_schema.columns " +
                "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                String.class, table, column);
            if (types.isEmpty()) {
                return false;                                   // already dropped
            }
            Long populated = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + populatedPredicate(column, types.get(0)), Long.class);
            if (populated != null && populated > 0) {
                log.warn("SchemaFixService: legacy column {}.{} still holds a value in {} row(s) — kept; no entity "
                         + "maps it, so decide what to do with that data before dropping it by hand.",
                         table, column, populated);
                return false;
            }
            jdbc.execute("ALTER TABLE " + table + " DROP COLUMN IF EXISTS " + column);
            log.info("SchemaFixService: legacy column {}.{} dropped (unmapped by any entity, held no data).",
                     table, column);
            return true;
        } catch (Exception e) {
            log.warn("SchemaFixService: could not drop legacy column {}.{} — {}", table, column, e.getMessage());
            return false;
        }
    }

    /** The rows in which a legacy column of the given catalogue type still carries a value. */
    public static String populatedPredicate(String column, String dataType) {
        String t = dataType == null ? "" : dataType.toLowerCase(java.util.Locale.ROOT);
        if (t.startsWith("character") || t.equals("text")) {
            return column + " IS NOT NULL AND " + column + " <> ''";
        }
        if (t.equals("integer") || t.equals("bigint") || t.equals("smallint") || t.equals("numeric")
                || t.equals("double precision") || t.equals("real")) {
            return column + " IS NOT NULL AND " + column + " <> 0";
        }
        return column + " IS NOT NULL";
    }

    /**
     * Finds and drops any UNIQUE constraint on the {@code app_user} table that
     * covers ONLY the {@code email} column.  Uses the PostgreSQL system catalogue
     * so it works regardless of the auto-generated constraint name.
     */
    private void dropSingleColumnEmailUnique() {
        try {
            String sql =
                "DO $$ " +
                "DECLARE r RECORD; " +
                "BEGIN " +
                "  FOR r IN " +
                "    SELECT c.conname " +
                "    FROM pg_constraint c " +
                "    JOIN pg_class t ON c.conrelid = t.oid " +
                "    WHERE t.relname = 'app_user' " +
                "      AND c.contype = 'u' " +
                "      AND array_length(c.conkey, 1) = 1 " +
                "      AND EXISTS ( " +
                "            SELECT 1 FROM pg_attribute a " +
                "            WHERE a.attrelid = t.oid " +
                "              AND a.attnum = c.conkey[1] " +
                "              AND a.attname = 'email' " +
                "          ) " +
                "  LOOP " +
                "    EXECUTE 'ALTER TABLE app_user DROP CONSTRAINT IF EXISTS \"' || r.conname || '\"'; " +
                "    RAISE NOTICE 'Dropped legacy email-unique constraint: %', r.conname; " +
                "  END LOOP; " +
                "END $$;";
            jdbc.execute(sql);
            log.info("SchemaFixService: legacy single-column email unique constraints removed (if any).");
        } catch (Exception e) {
            log.warn("SchemaFixService: could not drop single-column email constraint — {}", e.getMessage());
        }
    }

    /** Returns true when {@code column} is present on {@code table}. */
    private boolean columnExists(String table, String column) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (" +
            "  SELECT 1 FROM information_schema.columns " +
            "  WHERE table_name = ? AND column_name = ?" +
            ")", Boolean.class, table, column));
    }

    /** Drops a constraint by explicit name, silently if it does not exist. */
    private void dropNamedConstraintIfExists(String table, String constraintName) {
        try {
            jdbc.execute(
                "ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS \"" + constraintName + "\"");
            log.info("SchemaFixService: constraint '{}' on '{}' dropped (if it existed).",
                     constraintName, table);
        } catch (Exception e) {
            log.warn("SchemaFixService: could not drop constraint '{}' — {}", constraintName, e.getMessage());
        }
    }
}
