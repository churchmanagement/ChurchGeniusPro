package com.churchgeniuspro.trial;

import com.churchgeniuspro.hibernate.DemoRoleAccess;
import com.churchgeniuspro.repository.DemoRoleAccessRepository;
import com.churchgeniuspro.repository.TrialRegistrationLinkRepository;
import com.churchgeniuspro.service.DemoAccessService;
import com.churchgeniuspro.service.TestDataService;
import com.churchgeniuspro.service.TrialDeletionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Deleting trial data, in two strengths, without reaching another tenant.
 *
 * <p>The dangerous part of this feature is not the deletion — it is the scoping.
 * Every statement has to name one client id, the Church login has to be
 * undeletable on its own, and a demo tenant or a real church must not be
 * reachable through a screen that says "Trial" on it. That is what these pin.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Trial data deletion")
class TrialDeletionTest {

    private static final String TRIAL  = TestDataService.TRIAL_CLIENT_PREFIX + "1788983547705";
    private static final String OTHER  = TestDataService.TRIAL_CLIENT_PREFIX + "1900000000000";
    private static final String DEMO   = TestDataService.DEMO_CLIENT_PREFIX  + "1757300000123";
    private static final String CHURCH = "CHR-real-church";

    @Mock TrialRegistrationLinkRepository linkRepo;
    @Mock DemoRoleAccessRepository        accessRepo;
    @Mock DemoAccessService               demoAccess;
    @Mock TestDataService                 testData;

    /**
     * Records every statement the service issues, so the scoping assertions below
     * can read the real SQL. A subclass rather than a mock: Mockito's varargs
     * matching is awkward here, and the SQL is exactly what these tests are about.
     */
    static final class RecordingJdbc extends JdbcTemplate {
        final List<String> sql = new ArrayList<>();
        final List<Object[]> args = new ArrayList<>();
        @Override public int update(String statement, Object... a) {
            sql.add(statement);
            args.add(a);
            return 1;
        }
    }

    private TrialDeletionService svc;
    private RecordingJdbc jdbc;
    private List<String> statements;
    private List<Object[]> args;

    @BeforeEach
    void setUp() {
        jdbc = new RecordingJdbc();
        statements = jdbc.sql;
        args = jdbc.args;
        svc = new TrialDeletionService(linkRepo, accessRepo, demoAccess, testData, jdbc);
    }

    private DemoRoleAccess role(String clientId, String label, Integer signupId, long id) {
        DemoRoleAccess w = new DemoRoleAccess();
        w.setId(id);
        w.setClientId(clientId);
        w.setRoleLabel(label);
        w.setSignupId(signupId);
        w.setEndDate(LocalDate.now().plusDays(30));
        w.setBlocked(false);
        when(accessRepo.findById(id)).thenReturn(Optional.of(w));
        return w;
    }

    /* ══ Registration links ══════════════════════════════════════════════ */

    @Nested
    @DisplayName("registration links")
    class Links {

        @Test
        @DisplayName("soft-deleting stamps the rows and reports what actually changed")
        void softDeleteReportsRealCount() {
            when(linkRepo.softDelete(anyCollection(), any(LocalDateTime.class), anyString())).thenReturn(2);

            TrialDeletionService.Outcome out = svc.softDeleteLinks(List.of(1, 2, 3), "admin");

            assertThat(out.operation()).isEqualTo("soft-delete");
            assertThat(out.affected()).as("the already-deleted third row is not counted").isEqualTo(2);
        }

        @Test
        @DisplayName("permanent deletion removes the rows")
        void permanentDelete() {
            when(linkRepo.permanentDelete(anyCollection())).thenReturn(1);
            assertThat(svc.permanentDeleteLinks(List.of(7), "admin").affected()).isEqualTo(1);
            verify(linkRepo).permanentDelete(List.of(7));
        }

        @Test
        @DisplayName("the all-forms go through the all-queries, never a list of every id")
        void allForms() {
            when(linkRepo.softDeleteAll(any(LocalDateTime.class), anyString())).thenReturn(5);
            when(linkRepo.permanentDeleteAll()).thenReturn(5);

            assertThat(svc.softDeleteAllLinks("admin").affected()).isEqualTo(5);
            assertThat(svc.permanentDeleteAllLinks("admin").affected()).isEqualTo(5);
            verify(linkRepo).softDeleteAll(any(LocalDateTime.class), anyString());
            verify(linkRepo).permanentDeleteAll();
        }

        @Test
        @DisplayName("restoring brings soft-deleted rows back")
        void restore() {
            when(linkRepo.restore(anyCollection())).thenReturn(1);
            assertThat(svc.restoreLinks(List.of(7), "admin").operation()).isEqualTo("restore");
        }

        @Test
        @DisplayName("an empty selection does nothing at all")
        void emptySelectionIsANoOp() {
            assertThat(svc.softDeleteLinks(List.of(), "admin").affected()).isZero();
            assertThat(svc.permanentDeleteLinks(null, "admin").affected()).isZero();
            verifyNoInteractions(linkRepo);
        }
    }

    /* ══ Accounts ════════════════════════════════════════════════════════ */

    @Nested
    @DisplayName("a trial account")
    class Account {

        @Test
        @DisplayName("soft delete sets the two flags the login queries already read")
        void softDeleteUsesExistingFlags() {
            TrialDeletionService.Outcome out = svc.softDeleteAccount(TRIAL, "admin");

            assertThat(out.counts()).containsOnlyKeys("service_client", "church_registration");
            assertThat(statements).allSatisfy(sql -> assertThat(sql).contains("WHERE client_id = ?"));
            assertThat(args).allSatisfy(a -> assertThat(a).containsExactly(TRIAL));
        }

        @Test
        @DisplayName("restore clears them again")
        void restoreClearsThem() {
            svc.restoreAccount(TRIAL, "admin");
            assertThat(statements).allSatisfy(sql -> assertThat(sql).contains("delete_flag = false"));
        }

        @Test
        @DisplayName("permanent deletion reuses the cascade demo deletion already had")
        void permanentDeleteDelegates() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total", 143);
            result.put("deleted", Map.of("signup", 4));
            when(testData.clearManagedTenant(TRIAL, "admin")).thenReturn(result);

            assertThat(svc.permanentDeleteAccount(TRIAL, "admin")).containsEntry("total", 143);
            // One ordered list of tables to keep correct, not two.
            verify(testData).clearManagedTenant(TRIAL, "admin");
        }

        @ParameterizedTest(name = "{0} is refused")
        @ValueSource(strings = { DEMO, CHURCH, "", "TRIALX-1" })
        @DisplayName("anything that is not a trial tenant is refused outright")
        void nonTrialTenantsRefused(String clientId) {
            assertThatThrownBy(() -> svc.softDeleteAccount(clientId, "admin"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a real church cannot be permanently deleted through this screen")
        void realChurchCannotBePermanentlyDeleted() {
            assertThatThrownBy(() -> svc.permanentDeleteAccount(CHURCH, "admin"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("trial accounts only");
            verifyNoInteractions(testData);
        }

        @Test
        @DisplayName("a demo tenant keeps its own Clear button and is refused here")
        void demoTenantRefusedHere() {
            assertThatThrownBy(() -> svc.permanentDeleteAccount(DEMO, "admin"))
                    .isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(testData);
        }
    }

    /* ══ Roles ═══════════════════════════════════════════════════════════ */

    @Nested
    @DisplayName("an individual role")
    class Role {

        @Test
        @DisplayName("SuperAdmin can be soft-deleted, and the window is blocked to match")
        void superAdminSoftDelete() {
            DemoRoleAccess w = role(TRIAL, "SuperAdmin", 42, 1L);

            TrialDeletionService.Outcome out = svc.softDeleteRole(1L, "admin");

            assertThat(out.counts()).containsKeys("signup", "app_user", "demo_role_access");
            assertThat(w.getBlocked()).as("the screen and the login agree").isTrue();
            assertThat(statements).anySatisfy(sql ->
                    assertThat(sql).contains("UPDATE signup SET deleted = true"));
        }

        @Test
        @DisplayName("...and permanently deleted, login rows only")
        void superAdminPermanentDelete() {
            role(TRIAL, "SuperAdmin", 42, 1L);

            svc.permanentDeleteRole(1L, "admin");

            assertThat(statements).anySatisfy(sql -> assertThat(sql).startsWith("DELETE FROM app_user"));
            assertThat(statements).anySatisfy(sql -> assertThat(sql).startsWith("DELETE FROM signup"));
            // The person the login signs in as is the church's data, not its access.
            assertThat(statements).noneSatisfy(sql -> assertThat(sql).contains("family_member"));
            verify(accessRepo).delete(any(DemoRoleAccess.class));
        }

        @Test
        @DisplayName("the app_user delete is scoped by BOTH the tenant and the signup")
        void roleDeleteIsDoublyScoped() {
            role(TRIAL, "SuperAdmin", 42, 1L);
            svc.permanentDeleteRole(1L, "admin");

            String appUserSql = statements.stream()
                    .filter(x -> x.startsWith("DELETE FROM app_user")).findFirst().orElseThrow();
            assertThat(appUserSql).contains("WHERE client_id = ?");
            assertThat(appUserSql).contains("user_id IN (SELECT client_id FROM signup WHERE id = ?)");
        }

        @ParameterizedTest(name = "{0} may be deleted on its own")
        @ValueSource(strings = { "SuperAdmin", "Admin", "Accountant", "User", "Member Portal", "Child Portal" })
        void otherRolesAreDeletable(String label) {
            assertThat(TrialDeletionService.isRoleDeletable(label)).isTrue();
        }

        /* ── the Church rule ─────────────────────────────────────────────── */

        @Test
        @DisplayName("the Church login cannot be deleted individually")
        void churchRoleIsRefused() {
            role(TRIAL, TestDataService.ROLE_CHURCH, 40, 9L);

            assertThatThrownBy(() -> svc.softDeleteRole(9L, "admin"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be deleted on its own");
            assertThatThrownBy(() -> svc.permanentDeleteRole(9L, "admin"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(statements).as("nothing was written").isEmpty();
        }

        @ParameterizedTest(name = "'{0}' is still recognised as the Church login")
        @ValueSource(strings = { "Church", "church", "CHURCH" })
        @DisplayName("the rule is not defeated by how the role is spelled")
        void churchSpellingsAllRefused(String label) {
            assertThat(TrialDeletionService.isRoleDeletable(label)).isFalse();
        }

        @Test
        @DisplayName("a role in a demo tenant is refused — this screen is for trials")
        void demoRoleRefused() {
            role(DEMO, "SuperAdmin", 42, 5L);
            assertThatThrownBy(() -> svc.softDeleteRole(5L, "admin"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a batch containing the Church login fails whole, writing nothing")
        void batchWithChurchFailsWhole() {
            role(TRIAL, TestDataService.ROLE_CHURCH, 40, 9L);
            role(TRIAL, "SuperAdmin", 42, 1L);

            assertThatThrownBy(() -> svc.permanentDeleteRoles(List.of(9L, 1L), "admin"))
                    .isInstanceOf(IllegalArgumentException.class);
            // 9L is refused first, so nothing ran. @Transactional would roll back a
            // partial batch anyway; this proves the refusal happens before any write.
            assertThat(statements).isEmpty();
        }

        @Test
        @DisplayName("several deletable roles go together")
        void batchOfDeletableRoles() {
            role(TRIAL, "SuperAdmin", 42, 1L);
            role(TRIAL, TestDataService.ROLE_MEMBER_PORTAL, 43, 2L);

            assertThat(svc.softDeleteRoles(List.of(1L, 2L), "admin").affected()).isPositive();
        }
    }

    /* ══ Tenant isolation ════════════════════════════════════════════════ */

    @Test
    @DisplayName("no statement this service issues can reach another tenant")
    void everyStatementIsScoped() {
        role(TRIAL, "SuperAdmin", 42, 1L);
        svc.softDeleteAccount(TRIAL, "admin");
        svc.softDeleteRole(1L, "admin");
        svc.permanentDeleteRole(1L, "admin");

        assertThat(statements).isNotEmpty();
        for (int i = 0; i < statements.size(); i++) {
            String sql = statements.get(i);
            // Every statement is either keyed by one client id or by one signup id;
            // there is no unqualified UPDATE or DELETE anywhere.
            assertThat(sql).as("statement must be scoped: %s", sql)
                    .containsAnyOf("client_id = ?", "WHERE id = ?");
            for (Object a : args.get(i)) {
                assertThat(String.valueOf(a))
                        .as("no statement may name another tenant")
                        .isNotEqualTo(OTHER).isNotEqualTo(DEMO).isNotEqualTo(CHURCH);
            }
        }
    }
}
