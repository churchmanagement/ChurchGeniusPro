package com.churchgeniuspro.integration;

import com.churchgeniuspro.hibernate.Group;
import com.churchgeniuspro.hibernate.GroupMember;
import com.churchgeniuspro.service.GroupMemberService;
import com.churchgeniuspro.service.GroupService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Finding 1 (Critical) — server-side tenant isolation for Group members, proven against
 * a real PostgreSQL database with two distinct tenants.
 *
 * <p>Every {@code GroupMemberService} entry point is tenant-scoped: a list/add validates
 * the parent group with {@code GroupService.findOrThrow(groupId, appClientId)}, and an
 * update/delete resolves the row with {@code findByIdAndAppClientIdAndDeleteFlagFalse}.
 * An id owned by another tenant therefore reads as "not found" — there is no cross-tenant
 * read, write, or existence oracle, and a {@code null} tenant (an unauthenticated caller)
 * is refused outright. This test seeds Tenant A and Tenant B and asserts that Tenant A
 * cannot read, add to, modify, or delete Tenant B's data, while Tenant B's own access
 * still works.
 *
 * <p>This exercises the security decision at the service layer directly (no UI, no
 * frontend), so it is independent of the HTTP path. Requires Docker; auto-skipped when
 * absent, runs on {@code verify} in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("Finding 1 — Group-member tenant isolation (two tenants, real DB)")
class GroupMemberTenantIsolationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String TENANT_A = "CGP-ITA";
    private static final String TENANT_B = "CGP-ITB";

    @Autowired GroupService       groupService;
    @Autowired GroupMemberService memberService;

    @Test
    @DisplayName("Tenant A cannot read, add, modify, or delete Tenant B's members; unauthenticated is refused")
    void tenantsAreIsolated() {
        // ── Seed two tenants, each with its own group + one member ───────────────
        Group gA = groupService.create("Choir A", TENANT_A);
        memberService.addMember(gA.getId(), "Alice", "Anderson", "alice@a.org", TENANT_A);

        Group gB = groupService.create("Choir B", TENANT_B);
        GroupMember mB = memberService.addMember(gB.getId(), "Bob", "Brown", "bob@b.org", TENANT_B);

        // ── Sanity: each tenant sees its own single member ───────────────────────
        assertThat(membersOf(gB.getId(), TENANT_B)).hasSize(1)
                .first().extracting(m -> m.get("firstName")).isEqualTo("Bob");

        // ── READ: Tenant A cannot list Tenant B's group ──────────────────────────
        assertThatThrownBy(() -> memberService.getMembers(gB.getId(), TENANT_A))
                .isInstanceOf(IllegalArgumentException.class);

        // ── CREATE: Tenant A cannot add into Tenant B's group ────────────────────
        assertThatThrownBy(() -> memberService.addMember(gB.getId(), "Mallory", "M", "m@a.org", TENANT_A))
                .isInstanceOf(IllegalArgumentException.class);

        // ── UPDATE: Tenant A cannot modify Tenant B's member ─────────────────────
        assertThatThrownBy(() -> memberService.updateMember(mB.getId(), "Hacked", "Name", "x@a.org", TENANT_A))
                .isInstanceOf(IllegalArgumentException.class);

        // ── DELETE: Tenant A cannot remove Tenant B's member ─────────────────────
        assertThatThrownBy(() -> memberService.removeMember(mB.getId(), TENANT_A))
                .isInstanceOf(IllegalArgumentException.class);

        // ── UNAUTHENTICATED: a null tenant is refused ────────────────────────────
        assertThatThrownBy(() -> memberService.getMembers(gB.getId(), null))
                .isInstanceOf(IllegalArgumentException.class);

        // ── Tenant B's data is intact and untouched after the failed attempts ────
        List<Map<String, Object>> bAfter = membersOf(gB.getId(), TENANT_B);
        assertThat(bAfter).as("Bob is still present and unchanged").hasSize(1)
                .first().satisfies(m -> {
                    assertThat(m.get("firstName")).isEqualTo("Bob");
                    assertThat(m.get("lastName")).isEqualTo("Brown");
                });

        // ── And Tenant B can still operate on its own member (no over-blocking) ───
        memberService.updateMember(mB.getId(), "Bobby", "Brown", "bob@b.org", TENANT_B);
        assertThat(membersOf(gB.getId(), TENANT_B))
                .first().extracting(m -> m.get("firstName")).isEqualTo("Bobby");
    }

    private List<Map<String, Object>> membersOf(Integer groupId, String tenant) {
        return memberService.getMembers(groupId, tenant);
    }
}
