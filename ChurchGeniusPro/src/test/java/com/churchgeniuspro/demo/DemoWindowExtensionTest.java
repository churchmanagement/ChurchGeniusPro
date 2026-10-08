package com.churchgeniuspro.demo;

import com.churchgeniuspro.hibernate.DemoRoleAccess;
import com.churchgeniuspro.repository.DemoClientSettingsRepository;
import com.churchgeniuspro.repository.DemoRoleAccessRepository;
import com.churchgeniuspro.service.DemoAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * M5: extending a demo/trial subscription extends the logins that belong to it.
 *
 * <p>A demo or trial tenant is governed by two dates that were never connected:
 * {@code service_client.end_date}, and each login's own {@code demo_role_access}
 * window. The Service Admin screen extended the first. Sign-in checks the second
 * first — so after a paid extension every login was still refused with "your
 * demo/trial access has expired", and the only way out was to edit each role's date
 * by hand. A trial that converted to a paying customer could not get back in.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Demo window extension (M5)")
class DemoWindowExtensionTest {

    private static final String TENANT = "TRIAL-1757300000456";

    @Mock DemoRoleAccessRepository     accessRepo;
    @Mock DemoClientSettingsRepository settingsRepo;
    @Mock JdbcTemplate                 jdbc;

    private DemoAccessService service;
    private final List<DemoRoleAccess> windows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new DemoAccessService(accessRepo, settingsRepo, jdbc);
        when(accessRepo.findByClientId(TENANT)).thenReturn(windows);
        when(accessRepo.save(any(DemoRoleAccess.class))).thenAnswer(i -> i.getArgument(0));
    }

    private DemoRoleAccess window(String username, LocalDate end, boolean blocked) {
        DemoRoleAccess w = new DemoRoleAccess();
        w.setClientId(TENANT);
        w.setUsername(username);
        w.setEndDate(end);
        w.setBlocked(blocked);
        windows.add(w);
        return w;
    }

    @Test
    @DisplayName("every expired login is moved out to the tenant's new end date")
    void expiredWindowsMoveForward() {
        DemoRoleAccess church = window("demo.church", LocalDate.now().minusDays(3), false);
        DemoRoleAccess pastor = window("demo.pastor", LocalDate.now().minusDays(3), false);
        assertThat(church.isUsable()).isFalse();

        LocalDate newEnd = LocalDate.now().plusDays(30);
        assertThat(service.extendWindows(TENANT, newEnd)).isEqualTo(2);

        assertThat(church.getEndDate()).isEqualTo(newEnd);
        assertThat(pastor.getEndDate()).isEqualTo(newEnd);
        assertThat(church.isUsable()).isTrue();
    }

    @Test
    @DisplayName("a window an admin set FURTHER out is left alone")
    void laterWindowsAreNotPulledBack() {
        LocalDate further = LocalDate.now().plusDays(90);
        DemoRoleAccess special = window("demo.pastor", further, false);
        window("demo.church", LocalDate.now().minusDays(1), false);

        assertThat(service.extendWindows(TENANT, LocalDate.now().plusDays(30))).isEqualTo(1);
        assertThat(special.getEndDate()).isEqualTo(further);
    }

    @Test
    @DisplayName("a login a Service Admin blocked stays blocked")
    void blockedStaysBlocked() {
        DemoRoleAccess blocked = window("demo.removed", LocalDate.now().minusDays(1), true);

        service.extendWindows(TENANT, LocalDate.now().plusDays(30));

        assertThat(blocked.getEndDate()).isEqualTo(LocalDate.now().plusDays(30));
        assertThat(blocked.getStatus()).isEqualTo("BLOCKED");
        assertThat(blocked.isUsable()).isFalse();
    }

    @Test
    @DisplayName("a window with no end date is given the tenant's")
    void openEndedWindowTakesTheDate() {
        DemoRoleAccess open = window("demo.church", null, false);
        LocalDate newEnd = LocalDate.now().plusDays(30);

        assertThat(service.extendWindows(TENANT, newEnd)).isEqualTo(1);
        assertThat(open.getEndDate()).isEqualTo(newEnd);
    }

    @Test
    @DisplayName("nothing to do, nothing done")
    void noWindowsOrNoDate() {
        assertThat(service.extendWindows(TENANT, LocalDate.now().plusDays(30))).isZero();
        window("demo.church", LocalDate.now().minusDays(1), false);
        assertThat(service.extendWindows(TENANT, null)).isZero();
        assertThat(service.extendWindows(null, LocalDate.now())).isZero();
    }
}
