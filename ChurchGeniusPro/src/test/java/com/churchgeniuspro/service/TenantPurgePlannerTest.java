package com.churchgeniuspro.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Database audit M7: the demo/trial purge deletes <em>every</em> tenant-owned table,
 * children before parents, each statement strictly scoped to one client — so that
 * W3's {@code ON DELETE RESTRICT} foreign keys cannot block it and no other tenant is
 * touched.
 *
 * <p>These are pure tests of {@link TenantPurgePlanner#assemble}, driven by a synthetic
 * catalogue that reproduces every awkward shape in the real schema: a table scoped by
 * {@code client_id} and one by {@code app_client_id}, a two-level indirect chain
 * ({@code worship_group_member → worship_instrument → worship_group}), a self-referential
 * table, {@code signup}'s overloaded key, and the excluded audit table. The full
 * execution against a real PostgreSQL with the 137 W3 foreign keys is proven by
 * {@code TenantPurgeIT}.
 */
class TenantPurgePlannerTest {

    private final TenantPurgePlanner planner = new TenantPurgePlanner();

    /** A synthetic tenant schema exercising each awkward shape. */
    private static Map<String, String> tenantCols() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("service_client", "client_id");
        m.put("church_registration", "client_id");
        m.put("app_user", "client_id");
        m.put("family_member", "app_client_id");
        m.put("signup", "client_id");
        m.put("demo_role_access", "client_id");
        m.put("church_event", "app_client_id");
        m.put("member_message", "app_client_id");   // self-referential
        m.put("income", "app_client_id");
        m.put("worship_group", "client_id");
        m.put("worship_assignment", "client_id");
        m.put("guess_it_group", "client_id");
        m.put("guess_it_game", "client_id");
        m.put("import_run", "client_id");
        m.put("demo_deletion_audit", "client_id");  // EXCLUDED from the purge
        return m;
    }

    /** The indirect (no tenant column) tables, present in the schema. */
    private static Set<String> withIndirect(Set<String> direct) {
        Set<String> all = new java.util.LinkedHashSet<>(direct);
        all.addAll(TenantPurgePlanner.INDIRECT.keySet());
        return all;
    }

    private static Map<String, List<String[]>> fks() {
        Map<String, List<String[]>> f = new LinkedHashMap<>();
        f.put("demo_role_access", List.<String[]>of(new String[]{"signup_id", "signup"}));
        f.put("income", List.<String[]>of(new String[]{"member_id", "family_member"}));
        f.put("member_message", List.<String[]>of(new String[]{"recipient_member_id", "family_member"}));
        return f;
    }

    private TenantPurgePlanner.Plan plan() {
        Map<String, String> cols = tenantCols();
        return planner.assemble(cols, fks(), withIndirect(cols.keySet()));
    }

    private static Map<String, Integer> index(List<String> order) {
        Map<String, Integer> idx = new LinkedHashMap<>();
        for (int i = 0; i < order.size(); i++) idx.put(order.get(i), i);
        return idx;
    }

    @Test
    @DisplayName("covers every tenant table, adds the indirect ones, and excludes the audit table")
    void coverage() {
        Map<String, String> cols = tenantCols();
        List<String> order = planner.plannedTableOrder(cols, fks(), withIndirect(cols.keySet()));
        // 15 tenant-column tables − demo_deletion_audit + 10 indirect = 24
        assertThat(order).hasSize(24);
        assertThat(order).doesNotContain("demo_deletion_audit");
        assertThat(order).contains("worship_group_member", "worship_instrument", "church_event_day",
                "user_permissions", "member_preference", "mapping_rule", "guess_it_participant");
    }

    @Test
    @DisplayName("deletes children before parents for every foreign key and the indirect chain")
    void childBeforeParent() {
        Map<String, Integer> idx = index(planner.plannedTableOrder(tenantCols(), fks(), withIndirect(tenantCols().keySet())));
        // explicit foreign keys
        assertThat(idx.get("demo_role_access")).isLessThan(idx.get("signup"));
        assertThat(idx.get("income")).isLessThan(idx.get("family_member"));
        assertThat(idx.get("member_message")).isLessThan(idx.get("family_member"));
        // the two-level indirect chain
        assertThat(idx.get("worship_group_member")).isLessThan(idx.get("worship_instrument"));
        assertThat(idx.get("worship_instrument")).isLessThan(idx.get("worship_group"));
        // other indirect tables before their parent
        assertThat(idx.get("church_event_day")).isLessThan(idx.get("church_event"));
        assertThat(idx.get("user_permissions")).isLessThan(idx.get("app_user"));
        assertThat(idx.get("member_preference")).isLessThan(idx.get("family_member"));
        // signup is forced ahead of the tables its overloaded key reads
        assertThat(idx.get("signup")).isLessThan(idx.get("app_user"));
        assertThat(idx.get("signup")).isLessThan(idx.get("family_member"));
    }

    @Test
    @DisplayName("the tenant-root tables are deleted last")
    void rootsLast() {
        List<String> order = planner.plannedTableOrder(tenantCols(), fks(), withIndirect(tenantCols().keySet()));
        assertThat(order.get(order.size() - 1)).isEqualTo("service_client");
        assertThat(order.get(order.size() - 2)).isEqualTo("church_registration");
    }

    @Test
    @DisplayName("every emitted statement is client-scoped — there is never an unqualified delete")
    void everyStatementScoped() {
        List<String> stmts = planner.plannedStatements(tenantCols(), fks(), withIndirect(tenantCols().keySet()));
        assertThat(stmts).isNotEmpty();
        assertThat(stmts).allSatisfy(s -> assertThat(s).as("scoped: %s", s).contains("?"));
    }

    @Test
    @DisplayName("an indirect table is scoped by a nested sub-select through its parent chain")
    void indirectNesting() {
        String sql = stepOps("worship_group_member").get(0).sql();
        assertThat(sql).isEqualTo(
            "DELETE FROM worship_group_member WHERE instrument_id IN " +
            "(SELECT id FROM worship_instrument WHERE group_id IN " +
            "(SELECT id FROM worship_group WHERE client_id = ?))");
    }

    @Test
    @DisplayName("signup is deleted in its three overloaded shapes")
    void signupThreeShapes() {
        List<TenantPurgePlanner.Op> ops = stepOps("signup");
        assertThat(ops).hasSize(3);
        assertThat(ops).allSatisfy(op -> assertThat(op.sql()).startsWith("DELETE FROM signup WHERE client_id"));
        assertThat(ops.get(1).sql()).contains("SELECT user_id FROM app_user");
        assertThat(ops.get(2).sql()).contains("SELECT member_ref FROM family_member");
    }

    @Test
    @DisplayName("a self-referential table has its self-reference nulled before the delete")
    void selfReferenceNulledFirst() {
        List<TenantPurgePlanner.Op> ops = stepOps("member_message");
        assertThat(ops).hasSize(2);
        assertThat(ops.get(0).sql()).isEqualTo("UPDATE member_message SET parent_id = NULL WHERE app_client_id = ?");
        assertThat(ops.get(1).sql()).isEqualTo("DELETE FROM member_message WHERE app_client_id = ?");
    }

    @Test
    @DisplayName("aborts rather than emit an unscoped delete when a table cannot be tenant-scoped")
    void refusesUnscoped() {
        // mapping_rule is indirect via import_run; drop import_run's tenant column and
        // its existence so the chain cannot resolve to a client parameter.
        Map<String, String> cols = tenantCols();
        cols.remove("import_run");
        assertThatThrownBy(() -> planner.assemble(cols, Map.of(), Set.of("mapping_rule")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unscoped delete")
                .hasMessageContaining("mapping_rule");
    }

    @Test
    @DisplayName("detects a cycle in the tenant foreign-key graph instead of looping forever")
    void detectsCycle() {
        Map<String, String> cols = new LinkedHashMap<>();
        cols.put("a", "client_id");
        cols.put("b", "client_id");
        Map<String, List<String[]>> cyclic = new LinkedHashMap<>();
        cyclic.put("a", List.<String[]>of(new String[]{"b_id", "b"}));
        cyclic.put("b", List.<String[]>of(new String[]{"a_id", "a"}));
        assertThatThrownBy(() -> planner.assemble(cols, cyclic, Set.of("a", "b")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle");
    }

    private List<TenantPurgePlanner.Op> stepOps(String table) {
        for (TenantPurgePlanner.Step s : plan().steps()) if (s.table().equals(table)) return s.ops();
        throw new AssertionError("no step for " + table);
    }
}
