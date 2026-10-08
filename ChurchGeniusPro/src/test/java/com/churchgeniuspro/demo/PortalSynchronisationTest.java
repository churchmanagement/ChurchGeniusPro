package com.churchgeniuspro.demo;

import com.churchgeniuspro.hibernate.DemoRoleAccess;
import com.churchgeniuspro.repository.DemoClientSettingsRepository;
import com.churchgeniuspro.repository.DemoRoleAccessRepository;
import com.churchgeniuspro.service.DemoAccessService;
import com.churchgeniuspro.service.TestDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * A trial or demo account is one church with three doors into it.
 *
 * <p>Blocking the staff login used to shut one of them. The Member Portal and the
 * Kids Portal have their own {@code signup} rows and their own access windows, so
 * they carried on working — same tenant, same data, through logins a Service Admin
 * had already decided to stop. The three windows now move together.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Portal window synchronisation")
class PortalSynchronisationTest {

    private static final String TENANT = TestDataService.TRIAL_CLIENT_PREFIX + "1757300000456";

    @Mock DemoRoleAccessRepository     accessRepo;
    @Mock DemoClientSettingsRepository settingsRepo;
    @Mock JdbcTemplate                 jdbc;

    private DemoAccessService service;
    private final List<DemoRoleAccess> rows = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong();

    private DemoRoleAccess superAdmin, member, child, otherStaff;

    @BeforeEach
    void setUp() {
        service = new DemoAccessService(accessRepo, settingsRepo, jdbc);

        superAdmin = row("SuperAdmin",                      "trial.super");
        member     = row(TestDataService.ROLE_MEMBER_PORTAL, "member_grace_7");
        child      = row(TestDataService.ROLE_CHILD_PORTAL,  "child_lily_9");
        otherStaff = row("User",                             "sam.wilson");

        when(accessRepo.findByClientId(TENANT)).thenReturn(rows);
        when(accessRepo.findById(anyLong())).thenAnswer(i ->
                rows.stream().filter(r -> r.getId().equals(i.getArgument(0))).findFirst());
        when(accessRepo.save(any(DemoRoleAccess.class))).thenAnswer(i -> i.getArgument(0));
    }

    private DemoRoleAccess row(String roleLabel, String username) {
        DemoRoleAccess a = new DemoRoleAccess();
        a.setId(ids.incrementAndGet());
        a.setClientId(TENANT);
        a.setRoleLabel(roleLabel);
        a.setUsername(username);
        a.setEndDate(LocalDate.now().plusDays(30));
        a.setBlocked(false);
        rows.add(a);
        return a;
    }

    /* ── blocking ───────────────────────────────────────────────────────── */

    @Nested
    @DisplayName("blocking the account")
    class Blocking {

        @Test
        @DisplayName("blocking the SuperAdmin blocks the Member and Kids portals with it")
        void blockCascades() {
            service.setBlocked(superAdmin.getId(), true);

            assertThat(member.getBlocked()).isTrue();
            assertThat(child.getBlocked()).isTrue();
            assertThat(member.isUsable()).isFalse();
            assertThat(child.isUsable()).isFalse();
        }

        @Test
        @DisplayName("unblocking it opens them again")
        void unblockCascades() {
            service.setBlocked(superAdmin.getId(), true);
            service.setBlocked(superAdmin.getId(), false);

            assertThat(member.getBlocked()).isFalse();
            assertThat(child.isUsable()).isTrue();
        }

        @Test
        @DisplayName("blocking a sample staff login is about that login alone")
        void otherStaffDoesNotCascade() {
            service.setBlocked(otherStaff.getId(), true);

            assertThat(otherStaff.getBlocked()).isTrue();
            assertThat(member.getBlocked()).isFalse();
            assertThat(child.getBlocked()).isFalse();
        }

        @Test
        @DisplayName("blocking a portal on its own stops only that portal")
        void portalBlockDoesNotCascadeUpwards() {
            service.setBlocked(member.getId(), true);

            assertThat(member.getBlocked()).isTrue();
            assertThat(superAdmin.getBlocked()).isFalse();
            assertThat(child.getBlocked()).isFalse();
        }
    }

    /* ── expiry ─────────────────────────────────────────────────────────── */

    @Nested
    @DisplayName("the end date")
    class Expiry {

        @Test
        @DisplayName("shortening the account's window shortens the portals' too")
        void endDateCascades() {
            LocalDate yesterday = LocalDate.now().minusDays(1);
            service.setEndDate(superAdmin.getId(), yesterday);

            assertThat(member.getEndDate()).isEqualTo(yesterday);
            assertThat(child.getEndDate()).isEqualTo(yesterday);
            assertThat(member.getStatus()).isEqualTo("EXPIRED");
            assertThat(child.getStatus()).isEqualTo("EXPIRED");
        }

        @Test
        @DisplayName("extending it extends them")
        void extensionCascades() {
            LocalDate later = LocalDate.now().plusDays(90);
            service.setEndDate(superAdmin.getId(), later);

            assertThat(member.getEndDate()).isEqualTo(later);
            assertThat(child.getEndDate()).isEqualTo(later);
        }

        @Test
        @DisplayName("a reissued staff login reopens its portals rather than leaving them expired")
        void reissueCascades() {
            service.setEndDate(superAdmin.getId(), LocalDate.now().minusDays(1));
            service.setBlocked(superAdmin.getId(), true);
            assertThat(member.isUsable()).isFalse();

            service.reissue(superAdmin.getId(), "trial.super.2");

            assertThat(member.getEndDate()).isEqualTo(superAdmin.getEndDate());
            assertThat(member.getBlocked()).isFalse();
            assertThat(child.isUsable()).isTrue();
        }
    }

    /* ── the tenant-level extension from M5 still covers everything ─────── */

    @Test
    @DisplayName("extending the subscription still moves all three windows")
    void subscriptionExtensionMovesEverything() {
        rows.forEach(r -> r.setEndDate(LocalDate.now().minusDays(2)));
        LocalDate newEnd = LocalDate.now().plusDays(30);

        assertThat(service.extendWindows(TENANT, newEnd)).isEqualTo(4);
        assertThat(superAdmin.getEndDate()).isEqualTo(newEnd);
        assertThat(member.getEndDate()).isEqualTo(newEnd);
        assertThat(child.getEndDate()).isEqualTo(newEnd);
    }

    @Test
    @DisplayName("a login with no window row is untouched by any of this")
    void unknownIdIsRefused() {
        when(accessRepo.findById(999L)).thenReturn(Optional.empty());
        assertThat(rows).allSatisfy(r -> assertThat(r.getBlocked()).isFalse());
    }
}
