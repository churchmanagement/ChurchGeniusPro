package com.churchgeniuspro.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds and executes the ordered, strictly tenant-scoped set of deletes that
 * removes <em>every</em> row belonging to one demo/trial tenant — children
 * before parents — so that no {@code ON DELETE RESTRICT} foreign key (the H3/W3
 * keys) can block the purge and no other tenant is ever touched.
 *
 * <h2>Why this replaces the hand-maintained list</h2>
 * The previous purge deleted a hand-written list of ~31 tables. The September
 * audit (M7) found the schema has <b>164 tenant-owned tables</b>; the missing
 * children would hold their parents hostage once W3's RESTRICT foreign keys
 * exist. This planner instead derives the full set and the delete order from the
 * live catalog every run, so it cannot drift from the schema:
 * <ul>
 *   <li><b>Tenant set</b> — every table that has a {@code client_id} or
 *       {@code app_client_id} column (read from {@code information_schema}),
 *       plus the {@link #INDIRECT} tables that carry no tenant column and are
 *       reached only through a parent foreign key.</li>
 *   <li><b>Order</b> — a child-before-parent topological sort of the live
 *       foreign-key graph ({@code pg_constraint}) restricted to the tenant set,
 *       unioned with the indirect edges. Before W3 the graph is smaller and the
 *       order looser, which is harmless because nothing enforces it yet; after
 *       W3 the full graph gives the exact order RESTRICT requires.</li>
 *   <li><b>Scope</b> — each delete is scoped by the table's own tenant column,
 *       or, for an indirect table, by a sub-select through its parent (which is
 *       still present because children go first). {@code signup}'s overloaded
 *       key is handled with its three shapes.</li>
 * </ul>
 *
 * <h2>The safety invariant</h2>
 * Every statement this planner emits contains the {@code clientId} parameter —
 * directly or inside a sub-select that is itself scoped by it. A tenant table
 * that cannot be resolved to such a predicate makes {@link #build} <b>throw</b>;
 * there is never an unqualified delete, which is what keeps one tenant's
 * deletion from touching another's rows. Execution additionally runs each
 * statement inside a savepoint and, if a foreign-key violation is ever raised by
 * an unforeseen ordering gap, rolls that one statement back and retries it in a
 * later pass — completing fully or aborting with the stuck tables named, never
 * half-done and never silent.
 */
@Component
public class TenantPurgePlanner {

    private static final Logger log = LoggerFactory.getLogger(TenantPurgePlanner.class);

    /**
     * Tenant-owned tables that carry no {@code client_id}/{@code app_client_id}
     * column and are reachable only through a parent foreign key. Value =
     * {@code {childColumn, parentTable}}. The parent is a tenant table (directly
     * scoped, or itself indirect — resolution recurses). A test asserts this map
     * equals the set of no-tenant-column tables that the entity foreign-key graph
     * can reach a tenant table from, so a new such table cannot slip through
     * unclassified.
     */
    static final Map<String, String[]> INDIRECT = new LinkedHashMap<>();
    static {
        INDIRECT.put("worship_group_member",       new String[]{"instrument_id", "worship_instrument"});
        INDIRECT.put("worship_instrument",         new String[]{"group_id",      "worship_group"});
        INDIRECT.put("worship_assignment_member",  new String[]{"assignment_id", "worship_assignment"});
        INDIRECT.put("church_event_day",           new String[]{"event_id",      "church_event"});
        INDIRECT.put("demo_reminder_log",          new String[]{"role_access_id","demo_role_access"});
        INDIRECT.put("guess_it_group_participant", new String[]{"group_id",      "guess_it_group"});
        INDIRECT.put("guess_it_participant",       new String[]{"game_id",       "guess_it_game"});
        INDIRECT.put("mapping_rule",               new String[]{"run_id",        "import_run"});
        INDIRECT.put("member_preference",          new String[]{"member_id",     "family_member"});
        INDIRECT.put("user_permissions",           new String[]{"app_user_id",   "app_user"});
    }

    /**
     * Tenant tables with a nullable foreign key to themselves. The self-reference
     * is a RESTRICT key, so a single bulk delete could refuse mid-statement
     * (RESTRICT is checked immediately, not deferred). Null the column first, then
     * the delete has no self-reference to trip on. Value = the self-ref column.
     */
    static final Map<String, String> SELF_REF = new LinkedHashMap<>();
    static {
        SELF_REF.put("member_message", "parent_id");
        SELF_REF.put("payroll_run",    "adjusts_run_id");
    }

    /**
     * Tables that have a tenant column but must NOT be purged with the tenant.
     * {@code demo_deletion_audit} is the record OF the deletion — it has to
     * outlive the tenant it describes. {@code flyway_schema_history} is schema
     * bookkeeping, never tenant data.
     */
    static final Set<String> EXCLUDED = Set.of("demo_deletion_audit", "flyway_schema_history");

    /** Deleted last (nothing foreign-keys to them; they are the tenant roots). */
    static final List<String> ROOTS_LAST = List.of("church_registration", "service_client");

    /** One executable statement: SQL plus how many times {@code clientId} fills its {@code ?}s. */
    record Op(String sql, int params, boolean counts) {}

    /** All operations for one table, in execution order. */
    record Step(String table, List<Op> ops) {}

    /** The finished plan: an ordered list of per-table steps. */
    record Plan(List<Step> steps) {
        int tableCount() { return steps.size(); }
    }

    /* ══ Catalog introspection ═══════════════════════════════════════════════ */

    /** table → its tenant column ({@code client_id} preferred if both exist). */
    private Map<String, String> readTenantColumns(JdbcTemplate jdbc) {
        Map<String, String> cols = new LinkedHashMap<>();
        jdbc.query(
            "SELECT table_name, column_name FROM information_schema.columns " +
            "WHERE table_schema = 'public' AND column_name IN ('client_id','app_client_id') " +
            "ORDER BY table_name, column_name",
            rs -> {
                String t = rs.getString(1), c = rs.getString(2);
                // client_id wins over app_client_id when a table has both.
                cols.merge(t, c, (a, b) -> a.equals("client_id") || b.equals("client_id") ? "client_id" : a);
            });
        return cols;
    }

    /** child table → list of {@code {column, parentTable}} from live foreign keys. */
    private Map<String, List<String[]>> readForeignKeys(JdbcTemplate jdbc) {
        Map<String, List<String[]>> fks = new LinkedHashMap<>();
        jdbc.query(
            "SELECT c.relname AS child, att.attname AS col, p.relname AS parent " +
            "FROM pg_constraint con " +
            "JOIN pg_class c ON c.oid = con.conrelid " +
            "JOIN pg_class p ON p.oid = con.confrelid " +
            "JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public' " +
            "JOIN unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord) ON true " +
            "JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = k.attnum " +
            "WHERE con.contype = 'f'",
            (java.sql.ResultSet rs) -> {
                fks.computeIfAbsent(rs.getString("child"), k -> new ArrayList<>())
                   .add(new String[]{rs.getString("col"), rs.getString("parent")});
            });
        return fks;
    }

    /* ══ Plan construction ═══════════════════════════════════════════════════ */

    /**
     * Builds the ordered, fully-scoped purge plan for the current schema.
     *
     * @throws IllegalStateException if any tenant table cannot be tenant-scoped,
     *         or the tenant foreign-key graph contains a cycle.
     */
    Plan build(JdbcTemplate jdbc) {
        return assemble(readTenantColumns(jdbc), readForeignKeys(jdbc), readTables(jdbc));
    }

    /**
     * Pure plan assembly from catalog metadata — no database access, so it is
     * unit-testable with a synthetic schema. {@code tenantCol}: table → its tenant
     * column ({@code client_id}/{@code app_client_id}). {@code fks}: child → list of
     * {@code {column, parent}}. {@code existing}: every table that exists (gates the
     * indirect list).
     */
    Plan assemble(Map<String, String> tenantCol,
                  Map<String, List<String[]>> fks,
                  Set<String> existing) {
        // Tenant set: every table with a tenant column (minus the excluded), plus
        // the indirect tables (only those that actually exist in this database).
        Set<String> tenant = new LinkedHashSet<>();
        for (String t : tenantCol.keySet()) if (!EXCLUDED.contains(t)) tenant.add(t);
        for (String t : INDIRECT.keySet()) if (existing.contains(t)) tenant.add(t);

        // Edge set for ordering: live FK edges within the tenant set, the indirect
        // edges, and two synthetic edges forcing signup ahead of the tables its
        // overloaded key sub-selects read (app_user, family_member).
        Map<String, Set<String>> childToParents = new LinkedHashMap<>();
        for (var e : fks.entrySet()) {
            String child = e.getKey();
            if (!tenant.contains(child)) continue;
            for (String[] cp : e.getValue()) {
                String parent = cp[1];
                if (tenant.contains(parent) && !parent.equals(child)) {
                    childToParents.computeIfAbsent(child, k -> new LinkedHashSet<>()).add(parent);
                }
            }
        }
        for (var e : INDIRECT.entrySet()) {
            if (!tenant.contains(e.getKey())) continue;
            childToParents.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).add(e.getValue()[1]);
        }
        if (tenant.contains("signup")) {
            for (String p : List.of("app_user", "family_member"))
                if (tenant.contains(p))
                    childToParents.computeIfAbsent("signup", k -> new LinkedHashSet<>()).add(p);
        }

        List<String> order = childFirst(tenant, childToParents);

        // Pin the tenant-root tables to the very end (nothing FK-references them;
        // they are the last thing to go).
        order.removeAll(ROOTS_LAST);
        for (String r : ROOTS_LAST) if (tenant.contains(r)) order.add(r);

        // Turn each table into its scoped statement(s).
        List<Step> steps = new ArrayList<>();
        for (String t : order) {
            List<Op> ops = new ArrayList<>();
            if (t.equals("signup")) {
                ops.addAll(signupOps());
            } else {
                if (SELF_REF.containsKey(t)) {
                    String pred = scope(t, tenantCol);           // never null for a self-ref (both have a tenant col)
                    ops.add(new Op("UPDATE " + t + " SET " + SELF_REF.get(t) + " = NULL WHERE " + pred,
                                   countParams(pred), false));
                }
                String pred = scope(t, tenantCol);
                if (pred == null || !pred.contains("?")) {
                    throw new IllegalStateException(
                        "M7 tenant purge: refusing to build an unscoped delete for '" + t +
                        "'. Every tenant table must resolve to a client-scoped predicate.");
                }
                ops.add(new Op("DELETE FROM " + t + " WHERE " + pred, countParams(pred), true));
            }
            steps.add(new Step(t, ops));
        }
        return new Plan(steps);
    }

    /**
     * The scoped {@code WHERE} predicate for one table, or {@code null} if it is
     * not a tenant table. Directly by its tenant column; for an indirect table,
     * a sub-select through its parent (recursively).
     */
    private String scope(String table, Map<String, String> tenantCol) {
        String col = tenantCol.get(table);
        if (col != null) return col + " = ?";
        String[] via = INDIRECT.get(table);
        if (via == null) return null;
        String parentPred = scope(via[1], tenantCol);
        if (parentPred == null) return null;
        return via[0] + " IN (SELECT id FROM " + via[1] + " WHERE " + parentPred + ")";
    }

    /**
     * The three delete shapes for {@code signup}, whose {@code client_id} holds a
     * tenant id (church login), an {@code app_user.user_id} (staff login), or a
     * {@code family_member.member_ref} (member/child portal login). Runs before
     * {@code app_user}/{@code family_member} are deleted so the sub-selects
     * resolve.
     */
    private List<Op> signupOps() {
        return List.of(
            new Op("DELETE FROM signup WHERE client_id = ?", 1, true),
            new Op("DELETE FROM signup WHERE client_id IN " +
                   "(SELECT user_id FROM app_user WHERE client_id = ?)", 1, true),
            new Op("DELETE FROM signup WHERE client_id IN " +
                   "(SELECT member_ref FROM family_member WHERE app_client_id = ? AND member_ref IS NOT NULL)", 1, true));
    }

    private static int countParams(String sql) {
        int n = 0;
        for (int i = 0; i < sql.length(); i++) if (sql.charAt(i) == '?') n++;
        return n;
    }

    /** Every base table in the public schema — gates the indirect list. */
    private Set<String> readTables(JdbcTemplate jdbc) {
        return new LinkedHashSet<>(jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables " +
            "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'", String.class));
    }

    /** Child-before-parent order (reverse post-order DFS), with cycle detection. */
    private List<String> childFirst(Set<String> nodes, Map<String, Set<String>> childToParents) {
        List<String> post = new ArrayList<>();
        Map<String, Integer> color = new LinkedHashMap<>();   // 1 = in-progress, 2 = done
        for (String n : nodes) dfs(n, childToParents, color, post);
        Collections.reverse(post);
        return post;
    }

    private void dfs(String u, Map<String, Set<String>> childToParents,
                     Map<String, Integer> color, List<String> post) {
        Integer c = color.get(u);
        if (c != null && c == 2) return;
        if (c != null && c == 1) {
            throw new IllegalStateException(
                "M7 tenant purge: the tenant foreign-key graph has a cycle through '" + u +
                "'; a child-before-parent order does not exist. Resolve the cycle before deleting.");
        }
        color.put(u, 1);
        for (String parent : childToParents.getOrDefault(u, Set.of())) dfs(parent, childToParents, color, post);
        color.put(u, 2);
        post.add(u);
    }

    /* ══ Execution ═══════════════════════════════════════════════════════════ */

    /**
     * Runs the plan for one tenant and returns per-table deleted-row counts.
     * Must be called inside the caller's transaction. Each statement runs behind
     * a savepoint; a foreign-key violation defers that table to a later pass, so
     * an unforeseen ordering gap still completes rather than aborting the whole
     * purge — and if a table can never be freed, the purge aborts naming it.
     */
    Map<String, Integer> purge(JdbcTemplate jdbc, String clientId) {
        Plan plan = build(jdbc);
        Map<String, Integer> deleted = new LinkedHashMap<>();
        if (log.isDebugEnabled()) {
            for (Step s : plan.steps()) for (Op op : s.ops()) log.debug("M7 purge plan: {}", op.sql());
        }

        return jdbc.execute((Connection con) -> {
            List<Step> pending = new ArrayList<>(plan.steps());
            int pass = 0;
            while (!pending.isEmpty()) {
                pass++;
                List<Step> deferred = new ArrayList<>();
                boolean progress = false;
                for (Step step : pending) {
                    Savepoint sp = con.setSavepoint("m7");
                    try {
                        int rows = runStep(con, step, clientId);
                        con.releaseSavepoint(sp);
                        deleted.merge(step.table(), rows, Integer::sum);
                        progress = true;
                    } catch (java.sql.SQLException ex) {
                        con.rollback(sp);
                        if (isForeignKeyViolation(ex)) {
                            deferred.add(step);   // a child still references it; try again next pass
                        } else {
                            throw ex;             // anything else is a real error — abort the transaction
                        }
                    }
                }
                if (!deferred.isEmpty() && !progress) {
                    throw new IllegalStateException(
                        "M7 tenant purge for '" + clientId + "' stalled: " +
                        deferred.stream().map(Step::table).toList() +
                        " could not be freed of referencing rows. Aborting so nothing is left half-deleted.");
                }
                pending = deferred;
            }
            return deleted;
        });
    }

    private int runStep(Connection con, Step step, String clientId) throws java.sql.SQLException {
        int rows = 0;
        for (Op op : step.ops()) {
            try (var ps = con.prepareStatement(op.sql())) {
                for (int i = 1; i <= op.params(); i++) ps.setString(i, clientId);
                int n = ps.executeUpdate();
                if (op.counts()) rows += n;
            }
        }
        return rows;
    }

    private static boolean isForeignKeyViolation(java.sql.SQLException ex) {
        for (java.sql.SQLException e = ex; e != null; e = e.getNextException()) {
            if ("23503".equals(e.getSQLState())) return true;   // foreign_key_violation
        }
        String m = ex.getMessage();
        return m != null && m.toLowerCase().contains("foreign key");
    }

    /* ══ Introspection helpers for tests ═════════════════════════════════════ */

    /** The tenant tables the plan would delete, in order — for tests and audit. */
    List<String> plannedOrder(JdbcTemplate jdbc) {
        List<String> out = new ArrayList<>();
        for (Step s : build(jdbc).steps()) out.add(s.table());
        return out;
    }

    /**
     * Public, pure: the tenant tables this plan would delete, in child-before-parent
     * order, for a given catalogue snapshot. No database access, so callable from any
     * package and any database in a unit test.
     */
    public List<String> plannedTableOrder(Map<String, String> tenantCol,
                                          Map<String, List<String[]>> fks,
                                          Set<String> existing) {
        List<String> out = new ArrayList<>();
        for (Step s : assemble(tenantCol, fks, existing).steps()) out.add(s.table());
        return out;
    }

    /**
     * Public, pure: every SQL statement this plan would run, for a given catalogue
     * snapshot — for asserting that each one is client-scoped.
     */
    public List<String> plannedStatements(Map<String, String> tenantCol,
                                          Map<String, List<String[]>> fks,
                                          Set<String> existing) {
        List<String> out = new ArrayList<>();
        for (Step s : assemble(tenantCol, fks, existing).steps())
            for (Op op : s.ops()) out.add(op.sql());
        return out;
    }
}
