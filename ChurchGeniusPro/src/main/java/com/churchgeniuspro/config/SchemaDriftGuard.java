package com.churchgeniuspro.config;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.SelectableConsumer;
import org.hibernate.metamodel.mapping.SelectableMapping;
import org.hibernate.persister.entity.EntityPersister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Compares the live PostgreSQL schema with what the JPA entities actually map, once per
 * startup, and reports every difference that {@code ddl-auto=update} cannot fix and that
 * makes an entity unusable — loudly, at ERROR, with the statement that repairs it.
 *
 * <p>Database audit (Sept 2026) findings P1, P2 and CLAUDE.md 2026-08-27 are all one
 * shape: the application started, looked healthy, and one table silently could not be
 * written or read.
 * <ul>
 *   <li>{@code membership_family.family_name} — a column no entity maps any more, still
 *       {@code NOT NULL} with no default. Hibernate's INSERT never mentions it, so the
 *       public membership form could not store a single submission. ({@code UNMAPPED_NOT_NULL})</li>
 *   <li>{@code guess_it_participant.member_id} — mapped as nullable, still {@code NOT NULL}
 *       in production because the migration relaxing it was never run; public players
 *       cannot join. ({@code NULLABILITY_DRIFT})</li>
 *   <li>{@code backup_config.retention_months} — a {@code nullable=false} column whose
 *       {@code ALTER TABLE … ADD COLUMN} failed on a populated table; Hibernate logged a
 *       WARN and carried on, and every read of the entity then failed.
 *       ({@code MAPPED_COLUMN_MISSING})</li>
 * </ul>
 *
 * <p>The guard never modifies anything and never prevents startup: an environment that
 * has drifted is still better up than down, and the fixes are ALTERs an operator runs
 * (or {@code SchemaFixService} applies for the known cases). It runs as an
 * {@link ApplicationRunner}, i.e. after Hibernate's own schema update has finished, and
 * only against PostgreSQL — the H2 test profile creates its schema from the entities,
 * so there is nothing to compare there. Disable with {@code schema.drift-guard.enabled=false}.
 */
@Component
public class SchemaDriftGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaDriftGuard.class);

    /** One column as PostgreSQL's {@code information_schema.columns} describes it. */
    public record DbColumn(String table, String column, boolean nullable, boolean hasDefault) {}

    /** Kinds of drift the guard reports, in order of how directly they break the application. */
    public enum Kind {
        /** Table the entity maps does not exist at all. */
        TABLE_MISSING,
        /** Entity maps a column the table does not have — every read/write of the entity fails. */
        MAPPED_COLUMN_MISSING,
        /** Column no entity maps is NOT NULL with no default — every INSERT into the table fails. */
        UNMAPPED_NOT_NULL,
        /** Entity treats the column as nullable but the database forbids NULL — inserts of a null value fail. */
        NULLABILITY_DRIFT
    }

    public record Drift(Kind kind, String table, String column, String remedy) {
        @Override public String toString() {
            return kind + " " + table + (column == null ? "" : "." + column) + " — " + remedy;
        }
    }

    private final EntityManagerFactory emf;
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public SchemaDriftGuard(EntityManagerFactory emf, JdbcTemplate jdbc,
                            @Value("${schema.drift-guard.enabled:true}") boolean enabled) {
        this.emf = emf;
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("SchemaDriftGuard: disabled (schema.drift-guard.enabled=false).");
            return;
        }
        try {
            if (!isPostgres()) {
                log.info("SchemaDriftGuard: not PostgreSQL — skipped.");
                return;
            }
            Map<String, Map<String, Boolean>> mapped = mappedColumns();
            List<DbColumn> live = liveColumns(mapped.keySet());
            List<Drift> drifts = diff(mapped, live);
            report(drifts, mapped.size());
        } catch (Exception e) {
            // Diagnostics must never take the application down.
            log.warn("SchemaDriftGuard: could not compare schema with entity model — {}", e.getMessage());
        }
    }

    // ── the comparison (pure, unit-tested) ──────────────────────────────────

    /**
     * Compares the entity mapping with the live schema.
     *
     * @param mapped table → (column → nullable-in-the-mapping), every column every entity maps
     * @param live   every column of every one of those tables as the database reports them
     */
    public static List<Drift> diff(Map<String, Map<String, Boolean>> mapped, List<DbColumn> live) {
        Map<String, Map<String, DbColumn>> byTable = new TreeMap<>();
        for (DbColumn c : live) {
            byTable.computeIfAbsent(c.table(), k -> new TreeMap<>()).put(c.column(), c);
        }
        List<Drift> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Boolean>> e : mapped.entrySet()) {
            String table = e.getKey();
            Map<String, DbColumn> cols = byTable.get(table);
            if (cols == null) {
                out.add(new Drift(Kind.TABLE_MISSING, table, null,
                        "the entity's table does not exist; check the startup log for a failed "
                        + "'create table' (ddl-auto=update should have created it)"));
                continue;
            }
            for (Map.Entry<String, Boolean> m : e.getValue().entrySet()) {
                String column = m.getKey();
                boolean mappedNullable = m.getValue();
                DbColumn db = cols.get(column);
                if (db == null) {
                    out.add(new Drift(Kind.MAPPED_COLUMN_MISSING, table, column,
                            "the entity maps this column but the table lacks it — every read/write of the "
                            + "entity fails; a NOT NULL column added by ddl-auto to a populated table needs "
                            + "@ColumnDefault, or run: ALTER TABLE " + table + " ADD COLUMN " + column + " …"));
                } else if (mappedNullable && !db.nullable() && !db.hasDefault()) {
                    out.add(new Drift(Kind.NULLABILITY_DRIFT, table, column,
                            "the entity allows NULL but the database forbids it — run: ALTER TABLE "
                            + table + " ALTER COLUMN " + column + " DROP NOT NULL;"));
                }
            }
            for (DbColumn db : cols.values()) {
                if (!e.getValue().containsKey(db.column()) && !db.nullable() && !db.hasDefault()) {
                    out.add(new Drift(Kind.UNMAPPED_NOT_NULL, table, db.column(),
                            "no entity maps this column, so INSERTs never supply it and PostgreSQL rejects "
                            + "every row — run: ALTER TABLE " + table + " ALTER COLUMN " + db.column()
                            + " DROP NOT NULL; (then drop the column once its data is confirmed unneeded)"));
                }
            }
        }
        return out;
    }

    // ── model side ──────────────────────────────────────────────────────────

    /** Every column every entity maps, by table: column → nullable in the mapping. */
    public Map<String, Map<String, Boolean>> mappedColumns() {
        Map<String, Map<String, Boolean>> mapped = new TreeMap<>();
        SessionFactoryImplementor sf = emf.unwrap(SessionFactoryImplementor.class);
        sf.getMappingMetamodel().forEachEntityDescriptor(persister -> collect(persister, mapped));
        return mapped;
    }

    private static void collect(EntityPersister persister, Map<String, Map<String, Boolean>> mapped) {
        String entityTable = norm(persister.getMappedTableDetails().getTableName());
        mapped.computeIfAbsent(entityTable, k -> new TreeMap<>());
        SelectableConsumer add = (index, selectable) -> record(selectable, mapped);
        persister.getIdentifierMapping().forEachSelectable(add);
        if (persister.getVersionMapping() != null) {
            persister.getVersionMapping().forEachSelectable(add);
        }
        if (persister.getDiscriminatorMapping() != null && !persister.getDiscriminatorMapping().isFormula()) {
            persister.getDiscriminatorMapping().forEachSelectable(add);
        }
        if (persister.getSoftDeleteMapping() != null) {
            persister.getSoftDeleteMapping().forEachSelectable(add);
        }
        persister.forEachAttributeMapping(attribute -> attribute.forEachSelectable(add));
    }

    private static void record(SelectableMapping selectable, Map<String, Map<String, Boolean>> mapped) {
        if (selectable.isFormula()) return;
        String table = norm(selectable.getContainingTableExpression());
        String column = norm(selectable.getSelectionExpression());
        if (table == null || column == null) return;
        // A column mapped twice (e.g. an insertable=false read-only duplicate) counts as
        // nullable only if every mapping of it allows NULL.
        mapped.computeIfAbsent(table, k -> new TreeMap<>())
              .merge(column, selectable.isNullable(), Boolean::logicalAnd);
    }

    /** PostgreSQL folds unquoted identifiers to lower case; do the same, and strip any quoting. */
    public static String norm(String identifier) {
        if (identifier == null) return null;
        String s = identifier.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        } else {
            s = s.toLowerCase(Locale.ROOT);
        }
        int dot = s.lastIndexOf('.');           // schema-qualified name → bare name
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    // ── database side ───────────────────────────────────────────────────────

    private boolean isPostgres() throws Exception {
        DataSource ds = jdbc.getDataSource();
        if (ds == null) return false;
        try (Connection c = ds.getConnection()) {
            String product = c.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase(Locale.ROOT).contains("postgresql");
        }
    }

    /** All columns of the given tables in the current schema, as {@code information_schema} reports them. */
    List<DbColumn> liveColumns(Set<String> tables) {
        String[] names = tables.toArray(new String[0]);
        return jdbc.query(
                "SELECT table_name, column_name, is_nullable, column_default, is_identity "
              + "FROM information_schema.columns "
              + "WHERE table_schema = current_schema() AND table_name = ANY (?) "
              + "ORDER BY table_name, ordinal_position",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", names)),
                (rs, i) -> new DbColumn(
                        rs.getString("table_name"),
                        rs.getString("column_name"),
                        "YES".equalsIgnoreCase(rs.getString("is_nullable")),
                        rs.getString("column_default") != null || "YES".equalsIgnoreCase(rs.getString("is_identity"))));
    }

    // ── reporting ───────────────────────────────────────────────────────────

    private static void report(List<Drift> drifts, int tables) {
        if (drifts.isEmpty()) {
            log.info("SchemaDriftGuard: {} entity tables match the database — no drift.", tables);
            return;
        }
        log.error("SchemaDriftGuard: {} schema difference(s) that ddl-auto cannot repair — the affected "
                + "entities are unusable until each is fixed by hand (see migrate_production.sql):", drifts.size());
        for (Drift d : drifts) {
            log.error("SchemaDriftGuard:   {}", d);
        }
    }
}
