package com.churchgeniuspro.subscription;

import com.churchgeniuspro.service.ServiceClientService;
import com.churchgeniuspro.service.ServiceClientService.Period;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Subscription term from start/end dates — month-end aware")
class SubscriptionPeriodTest {

    private static Period p(String start, String end) {
        return ServiceClientService.periodBetween(LocalDate.parse(start), LocalDate.parse(end));
    }

    @Test @DisplayName("ordinary whole months and years")
    void ordinary() {
        assertThat(p("2026-10-15", "2026-11-15")).isEqualTo(new Period(1, "MONTHS"));
        assertThat(p("2026-01-10", "2026-07-10")).isEqualTo(new Period(6, "MONTHS"));
        assertThat(p("2026-03-01", "2027-03-01")).isEqualTo(new Period(1, "YEARS"));
        assertThat(p("2026-03-01", "2028-03-01")).isEqualTo(new Period(2, "YEARS"));
    }

    @Test @DisplayName("one month from the 31st/30th/29th clamps to the shorter month's last day")
    void monthEnd() {
        assertThat(p("2026-10-31", "2026-11-30")).isEqualTo(new Period(1, "MONTHS"));
        assertThat(p("2026-01-31", "2026-02-28")).isEqualTo(new Period(1, "MONTHS"));
        assertThat(p("2028-01-30", "2028-02-29")).isEqualTo(new Period(1, "MONTHS"));
        assertThat(p("2026-08-31", "2026-11-30")).isEqualTo(new Period(3, "MONTHS"));
        assertThat(p("2026-05-31", "2026-06-30")).isEqualTo(new Period(1, "MONTHS"));
    }

    @Test @DisplayName("Feb 29 -> Feb 28 next year is one year")
    void leapYear() {
        assertThat(p("2028-02-29", "2029-02-28")).isEqualTo(new Period(1, "YEARS"));
    }

    @Test @DisplayName("anything else is days; the term always reproduces the end date")
    void days() {
        assertThat(p("2026-01-15", "2026-02-20")).isEqualTo(new Period(36, "DAYS"));
        assertThat(p("2026-10-31", "2026-11-29")).isEqualTo(new Period(29, "DAYS"));
        assertThat(p("2026-01-30", "2026-02-28")).isEqualTo(new Period(1, "MONTHS"));
        assertThat(p("2026-01-30", "2026-02-27")).isEqualTo(new Period(28, "DAYS"));
        LocalDate s = LocalDate.of(2026, 1, 1);
        for (int i = 1; i <= 800; i++) {
            LocalDate e = s.plusDays(i);
            for (LocalDate st = s; st.isBefore(s.plusDays(400)); st = st.plusDays(37)) {
                if (!e.isAfter(st)) continue;
                Period q = ServiceClientService.periodBetween(st, e);
                LocalDate back = switch (q.unit()) {
                    case "YEARS" -> st.plusYears(q.amount());
                    case "MONTHS" -> st.plusMonths(q.amount());
                    default -> st.plusDays(q.amount());
                };
                assertThat(back).as(st + " -> " + e).isEqualTo(e);
            }
        }
    }
}
