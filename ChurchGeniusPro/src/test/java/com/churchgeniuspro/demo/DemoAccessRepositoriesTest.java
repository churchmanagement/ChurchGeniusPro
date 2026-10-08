package com.churchgeniuspro.demo;

import com.churchgeniuspro.hibernate.DemoRoleAccess;
import com.churchgeniuspro.repository.DemoReminderLogRepository;
import com.churchgeniuspro.repository.DemoRoleAccessRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.parser.PartTree;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Guards the demo-access repositories against the failure that took the whole
 * application down on first boot: Spring Data parsed the derived finder
 * {@code existsByRoleAccessIdAndDaysBeforeAndForEndDate} as the property
 * {@code days} followed by its {@code Before} keyword (the less-than operator),
 * and refused to start the context.
 *
 * <p>Compiling cannot catch that — derivation happens at context startup — and
 * the project's full context test needs Docker, so it is skipped on most
 * machines. These tests drive Spring Data's own {@link PartTree} parser directly,
 * which is the very thing that threw, and need neither Docker nor a database.
 */
class DemoAccessRepositoriesTest {

    @Test
    @DisplayName("every derived finder name parses against its entity")
    void derivedFinderNamesParse() {
        for (Method m : DemoRoleAccessRepository.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Query.class)) continue;      // explicit JPQL, not derived
            String name = m.getName();
            assertThatCode(() -> new PartTree(name, DemoRoleAccess.class))
                    .as("derived finder '%s' must parse — a name that does not "
                      + "resolve stops the whole application from starting", name)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("the name that broke boot is still rejected, so the fix is not undone")
    void theOriginalBadNameStillFails() {
        // Documents WHY alreadySent() is @Query rather than a derived name.
        assertThatCode(() -> new PartTree(
                "existsByRoleAccessIdAndDaysBeforeAndForEndDate", com.churchgeniuspro.hibernate.DemoReminderLog.class))
                .as("if this ever stops throwing, the derived form is safe again")
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("queries that cannot be derived carry explicit JPQL")
    void keywordCollidingQueriesStayExplicit() throws Exception {
        Method alreadySent = DemoReminderLogRepository.class
                .getMethod("alreadySent", Long.class, Integer.class, LocalDate.class);
        assertThat(alreadySent.isAnnotationPresent(Query.class))
                .as("alreadySent must stay @Query — 'DaysBefore' cannot be derived")
                .isTrue();

        Method countdown = DemoRoleAccessRepository.class
                .getMethod("findCountdownCandidates", LocalDate.class);
        assertThat(countdown.isAnnotationPresent(Query.class)).isTrue();
    }

    @Test
    @DisplayName("status is derived from the dates, never stored")
    void statusIsDerived() {
        assertThat(window(LocalDate.now().plusDays(1)).getStatus()).isEqualTo("ACTIVE");
        assertThat(window(LocalDate.now()).getStatus()).isEqualTo("ACTIVE");     // last day still works
        assertThat(window(LocalDate.now().minusDays(1)).getStatus()).isEqualTo("EXPIRED");
        assertThat(window(null).getStatus()).isEqualTo("ACTIVE");                // no date, no limit

        DemoRoleAccess blocked = window(LocalDate.now().plusYears(1));
        blocked.setBlocked(true);
        assertThat(blocked.getStatus()).isEqualTo("BLOCKED");                    // block beats the date
        assertThat(blocked.isUsable()).isFalse();
    }

    private DemoRoleAccess window(LocalDate end) {
        DemoRoleAccess a = new DemoRoleAccess();
        a.setSignupId(1);
        a.setClientId("DEMO-1");
        a.setUsername("demo_user");
        a.setStartDate(LocalDate.now().minusDays(1));
        a.setEndDate(end);
        a.setBlocked(false);
        a.setCreatedAt(LocalDateTime.now());
        return a;
    }
}
