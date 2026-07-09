package com.churchgeniuspro.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Drops legacy database constraints that Hibernate's {@code ddl-auto=update}
 * cannot remove automatically.
 *
 * <p>Runs once at application startup (after the datasource is available but
 * before the rest of the application processes requests).  All SQL statements
 * use {@code IF EXISTS} / conditional logic so that repeated restarts are safe.
 *
 * <p>Constraints managed here:
 * <ul>
 *   <li>{@code uk1j9d9a06i600gd43uu3km82jw} — the auto-generated single-column
 *       UNIQUE constraint on {@code app_user.email} created by the original
 *       {@code @Column(unique=true)} annotation.  Replaced by the composite
 *       constraint {@code uq_app_user_email_role_client} on
 *       (email, role, client_id).</li>
 *   <li>{@code uq_app_user_email_role} — an intermediate composite constraint
 *       on (email, role) that was added in a transitional commit and is now
 *       superseded by the three-column constraint above.</li>
 * </ul>
 */
@Component
public class SchemaFixService {

    private static final Logger log = LoggerFactory.getLogger(SchemaFixService.class);

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

        // Migrate legacy income data then remove the obsolete column.
        // Order matters: backfill reads from method, so it must run first.
        backfillIncomeTransactionTypeId();
        dropIncomeMethodColumn();

        // Same migration for the expense table.
        backfillExpenseTransactionTypeId();
        dropExpenseMethodColumn();

        // Backfill family_member.member_ref from signup.client_id via the legacy
        // signup_ref integer FK (still present in DB even though removed from the entity).
        // Then drop the now-redundant signup_ref column.
        backfillMemberRefFromSignupRef();
        dropSignupRefColumn();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

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

    /**
     * For income rows where {@code transaction_type_id} is still NULL but the
     * legacy {@code method} VARCHAR column holds a numeric string (the old PK
     * of transaction_type), copies that value into the FK column.
     * Skipped entirely once the {@code method} column has been dropped.
     * Safe to re-run — only touches rows where {@code transaction_type_id IS NULL}.
     */
    private void backfillIncomeTransactionTypeId() {
        try {
            boolean methodExists = columnExists("income", "method");
            boolean fkExists     = columnExists("income", "transaction_type_id");

            if (!methodExists || !fkExists) {
                log.info("SchemaFixService: skipping income backfill (method={}, transaction_type_id={}).",
                         methodExists, fkExists);
                return;
            }

            // income.method stored the transaction_type PK as a numeric string (e.g. "5").
            // Cast it to integer and join directly against transaction_type.id.
            int updated = jdbc.update(
                "UPDATE income i " +
                "SET    transaction_type_id = tt.id " +
                "FROM   transaction_type tt " +
                "WHERE  i.method ~ '^[0-9]+$' " +
                "  AND  i.method::integer = tt.id " +
                "  AND  i.transaction_type_id IS NULL " +
                "  AND  i.delete_flag = false " +
                "  AND  tt.delete_flag = false");

            if (updated > 0) {
                log.info("SchemaFixService: backfilled transaction_type_id on {} income row(s).", updated);
            }
        } catch (Exception e) {
            log.warn("SchemaFixService: could not backfill income.transaction_type_id — {}", e.getMessage());
        }
    }

    /**
     * Drops the legacy {@code income.method} VARCHAR column now that all data
     * has been migrated to {@code transaction_type_id}.
     * Checks for column existence first so repeated restarts are safe no-ops.
     */
    private void dropIncomeMethodColumn() {
        try {
            if (!columnExists("income", "method")) {
                return;  // already dropped — nothing to do
            }
            jdbc.execute("ALTER TABLE income DROP COLUMN method");
            log.info("SchemaFixService: legacy income.method column dropped.");
        } catch (Exception e) {
            log.warn("SchemaFixService: could not drop income.method column — {}", e.getMessage());
        }
    }

    /**
     * Same backfill logic as income: copies the numeric-string method PK into
     * {@code expense.transaction_type_id}.  Skipped once the method column is gone.
     */
    private void backfillExpenseTransactionTypeId() {
        try {
            boolean methodExists = columnExists("expense", "method");
            boolean fkExists     = columnExists("expense", "transaction_type_id");

            if (!methodExists || !fkExists) {
                log.info("SchemaFixService: skipping expense backfill (method={}, transaction_type_id={}).",
                         methodExists, fkExists);
                return;
            }

            int updated = jdbc.update(
                "UPDATE expense e " +
                "SET    transaction_type_id = tt.id " +
                "FROM   transaction_type tt " +
                "WHERE  e.method ~ '^[0-9]+$' " +
                "  AND  e.method::integer = tt.id " +
                "  AND  e.transaction_type_id IS NULL " +
                "  AND  e.delete_flag = false " +
                "  AND  tt.delete_flag = false");

            if (updated > 0) {
                log.info("SchemaFixService: backfilled transaction_type_id on {} expense row(s).", updated);
            }
        } catch (Exception e) {
            log.warn("SchemaFixService: could not backfill expense.transaction_type_id — {}", e.getMessage());
        }
    }

    /** Drops the legacy {@code expense.method} VARCHAR column after backfill. */
    private void dropExpenseMethodColumn() {
        try {
            if (!columnExists("expense", "method")) {
                return;
            }
            jdbc.execute("ALTER TABLE expense DROP COLUMN method");
            log.info("SchemaFixService: legacy expense.method column dropped.");
        } catch (Exception e) {
            log.warn("SchemaFixService: could not drop expense.method column — {}", e.getMessage());
        }
    }

    /**
     * Backfills {@code family_member.member_ref} for rows that have a
     * {@code signup_ref} integer FK but no {@code member_ref} value yet.
     *
     * <p>Uses the {@code signup_ref → signup.id → signup.client_id} chain.
     * Only runs when both {@code signup_ref} and {@code member_ref} columns exist.
     * Safe to re-run — only touches rows where {@code member_ref IS NULL}.
     */
    /**
     * Backfills {@code family_member.member_ref} for rows that have a
     * {@code signup_ref} integer FK but no {@code member_ref} value yet.
     * Uses the signup_ref -> signup.id -> signup.client_id chain.
     * Only runs when both columns exist. Safe to re-run.
     */
    private void backfillMemberRefFromSignupRef() {
        try {
            if (!columnExists("family_member", "signup_ref")
                    || !columnExists("family_member", "member_ref")) {
                log.info("SchemaFixService: skipping member_ref backfill (column absent).");
                return;
            }
            int updated = jdbc.update(
                "UPDATE family_member fm " +
                "SET    member_ref = s.client_id " +
                "FROM   signup s " +
                "WHERE  fm.signup_ref = s.id " +
                "  AND  fm.member_ref IS NULL " +
                "  AND  s.client_id IS NOT NULL " +
                "  AND  s.client_id LIKE 'MBR%'");
            if (updated > 0) {
                log.info("SchemaFixService: backfilled member_ref on {} family_member row(s).", updated);
            } else {
                log.info("SchemaFixService: member_ref backfill — nothing to update.");
            }
        } catch (Exception e) {
            log.warn("SchemaFixService: could not backfill member_ref — {}", e.getMessage());
        }
    }

    /**
     * Drops the legacy {@code family_member.signup_ref} integer column once
     * all data has been migrated to {@code member_ref}.
     * Checks column existence first so repeated restarts are safe no-ops.
     */
    private void dropSignupRefColumn() {
        try {
            if (!columnExists("family_member", "signup_ref")) {
                return;
            }
            // Drop any constraint referencing signup_ref first, using quote_ident to
            // avoid embedding double-quote characters inside Java string literals.
            jdbc.execute(
                "DO $$ DECLARE r RECORD; " +
                "BEGIN " +
                "  FOR r IN " +
                "    SELECT c.conname FROM pg_constraint c " +
                "    JOIN pg_class t ON c.conrelid = t.oid " +
                "    JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(c.conkey) " +
                "    WHERE t.relname = 'family_member' AND a.attname = 'signup_ref' " +
                "  LOOP " +
                "    EXECUTE 'ALTER TABLE family_member DROP CONSTRAINT IF EXISTS ' " +
                "            || quote_ident(r.conname); " +
                "  END LOOP; " +
                "END $$;"
            );
            jdbc.execute("ALTER TABLE family_member DROP COLUMN signup_ref");
            log.info("SchemaFixService: legacy family_member.signup_ref column dropped.");
        } catch (Exception e) {
            log.warn("SchemaFixService: could not drop family_member.signup_ref — {}", e.getMessage());
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
