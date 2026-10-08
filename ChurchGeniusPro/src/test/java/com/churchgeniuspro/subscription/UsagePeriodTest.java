package com.churchgeniuspro.subscription;

import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.hibernate.SubscriptionUsage;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.SubscriptionPlanRepository;
import com.churchgeniuspro.repository.SubscriptionUsageRepository;
import com.churchgeniuspro.service.SubscriptionService;
import com.churchgeniuspro.util.AppClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 4 (spec section 11): the monthly allowances reset every 30 days from the
 * subscription start date, in America/Chicago dates, and history is never deleted.
 */
@DisplayName("Usage periods — allowances reset every 30 days from the start date")
class UsagePeriodTest {

    final Map<String, SubscriptionUsage> rows = new HashMap<>();   // key: client|periodStart
    ServiceClient sc;
    SubscriptionPlan standard, pro;
    SubscriptionService service;
    SubscriptionUsageRepository usage;

    @BeforeEach
    void setUp() {
        standard = new SubscriptionPlan(); standard.setPlanCode("STANDARD"); standard.setActive(true);
        standard.setMaxEmailsPerMonth(2); standard.setMaxSmsPerMonth(2); standard.setMaxOnlineGivingPerMonth(1);
        pro = new SubscriptionPlan(); pro.setPlanCode("PRO"); pro.setActive(true);
        pro.setMaxEmailsPerMonth(5); pro.setMaxSmsPerMonth(5);
        SubscriptionPlanRepository plans = mock(SubscriptionPlanRepository.class);
        when(plans.findByPlanCodeIgnoreCase("STANDARD")).thenReturn(Optional.of(standard));
        when(plans.findByPlanCodeIgnoreCase("PRO")).thenReturn(Optional.of(pro));

        sc = new ServiceClient(); sc.setClientId("CHR-1"); sc.setSubscriptionType("STANDARD");
        sc.setStartDate(AppClock.today().minusDays(10));
        ServiceClientRepository clients = mock(ServiceClientRepository.class);
        when(clients.findByClientId("CHR-1")).thenReturn(Optional.of(sc));

        usage = mock(SubscriptionUsageRepository.class);
        when(usage.findByClientIdAndPeriodStart(anyString(), any())).thenAnswer(i ->
                Optional.ofNullable(rows.get(i.getArgument(0) + "|" + i.getArgument(1))));
        when(usage.save(any(SubscriptionUsage.class))).thenAnswer(i -> {
            SubscriptionUsage u = i.getArgument(0);
            rows.put(u.getClientId() + "|" + u.getPeriodStart(), u);
            return u;
        });
        when(usage.addEmails(anyString(), any(), anyInt())).thenAnswer(i -> bump(i.getArgument(0), i.getArgument(1), "e"));
        when(usage.addSms(anyString(), any(), anyInt())).thenAnswer(i -> bump(i.getArgument(0), i.getArgument(1), "s"));
        when(usage.addGiving(anyString(), any(), anyInt())).thenAnswer(i -> bump(i.getArgument(0), i.getArgument(1), "g"));
        service = new SubscriptionService(clients, plans, usage);
    }

    private int bump(String client, LocalDate period, String what) {
        SubscriptionUsage u = rows.get(client + "|" + period);
        if (u == null) return 0;
        switch (what) {
            case "e" -> u.setEmailsSent(u.getEmailsSent() + 1);
            case "s" -> u.setSmsSent(u.getSmsSent() + 1);
            default  -> u.setGivingCount(u.getGivingCount() + 1);
        }
        return 1;
    }

    // ── The arithmetic ──────────────────────────────────────────────────────

    @Test @DisplayName("start Oct 5: days 0–29 are period 1 (Oct 5–Nov 3); day 30 (Nov 4) starts period 2")
    void periodBoundaries() {
        LocalDate start = LocalDate.of(2026, 10, 5);
        assertThat(SubscriptionService.periodStartFor(start, start)).isEqualTo(start);
        assertThat(SubscriptionService.periodStartFor(start, LocalDate.of(2026, 11, 3))).isEqualTo(start);           // day 29
        assertThat(SubscriptionService.periodStartFor(start, LocalDate.of(2026, 11, 4))).isEqualTo(LocalDate.of(2026, 11, 4)); // day 30
        assertThat(SubscriptionService.periodEndFor(LocalDate.of(2026, 11, 4), start)).isEqualTo(LocalDate.of(2026, 12, 3));
        assertThat(SubscriptionService.periodStartFor(start, LocalDate.of(2027, 10, 5))).isEqualTo(start.plusDays(360)); // a yearly client: still every 30 days
    }

    @Test @DisplayName("a start date in the future counts from that date; no start date falls back to the calendar month")
    void edgeAnchors() {
        LocalDate start = LocalDate.of(2026, 12, 1);
        assertThat(SubscriptionService.periodStartFor(start, LocalDate.of(2026, 11, 20))).isEqualTo(start);
        assertThat(SubscriptionService.periodStartFor(null, LocalDate.of(2026, 11, 20))).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(SubscriptionService.periodEndFor(LocalDate.of(2026, 11, 1), null)).isEqualTo(LocalDate.of(2026, 11, 30));
    }

    // ── Counting and limits ─────────────────────────────────────────────────

    @Test @DisplayName("sends are counted in the current period and refused at the plan limit")
    void countsWithinPeriod() {
        assertThat(service.canSendEmail("CHR-1")).isTrue();
        service.recordEmailSent("CHR-1");
        service.recordEmailSent("CHR-1");
        assertThat(service.canSendEmail("CHR-1")).isFalse();                  // 2 of 2
        assertThat(service.canSendSms("CHR-1")).isTrue();                     // separate counter
        service.recordOnlineGiving("CHR-1");
        assertThat(service.canAcceptOnlineGiving("CHR-1")).isFalse();         // 1 of 1
        SubscriptionUsage row = rows.get("CHR-1|" + sc.getStartDate());
        assertThat(row.getEmailsSent()).isEqualTo(2);
        assertThat(row.getPeriodStart()).isEqualTo(sc.getStartDate());
        assertThat(row.getUsageMonth()).isEqualTo(sc.getStartDate().toString().substring(0, 7));
    }

    @Test @DisplayName("after 30 days a fresh period starts; the old period's row is kept as history")
    void newPeriodResetsKeepsHistory() {
        LocalDate start = AppClock.today().minusDays(30);                     // today is day 30
        sc.setStartDate(start);
        service.evict("CHR-1");
        SubscriptionUsage first = new SubscriptionUsage();
        first.setClientId("CHR-1"); first.setPeriodStart(start); first.setEmailsSent(2);
        rows.put("CHR-1|" + start, first);                                    // period 1 used up
        assertThat(service.canSendEmail("CHR-1")).isTrue();                   // period 2: fresh allowance
        service.recordEmailSent("CHR-1");
        assertThat(rows.get("CHR-1|" + AppClock.today()).getEmailsSent()).isEqualTo(1);
        assertThat(rows.get("CHR-1|" + start).getEmailsSent()).isEqualTo(2); // history kept
    }

    @Test @DisplayName("an upgrade mid-period keeps the count; the new plan's limit applies at once")
    void upgradeKeepsCount() {
        service.recordEmailSent("CHR-1"); service.recordEmailSent("CHR-1");
        assertThat(service.canSendEmail("CHR-1")).isFalse();                  // 2 of 2 on Standard
        sc.setSubscriptionType("PRO");
        service.evict("CHR-1");
        assertThat(service.canSendEmail("CHR-1")).isTrue();                   // 2 of 5 on Pro
        assertThat(rows.get("CHR-1|" + sc.getStartDate()).getEmailsSent()).isEqualTo(2);
    }

    @Test @DisplayName("a downgrade mid-period keeps the count: already over the lower limit means wait for the next period")
    void downgradeKeepsCount() {
        sc.setSubscriptionType("PRO");
        for (int i = 0; i < 4; i++) service.recordEmailSent("CHR-1");
        sc.setSubscriptionType("STANDARD");
        service.evict("CHR-1");
        assertThat(service.canSendEmail("CHR-1")).isFalse();                  // 4 used, limit 2
        assertThat(rows.get("CHR-1|" + sc.getStartDate()).getEmailsSent()).isEqualTo(4);   // nothing deleted
    }

    @Test @DisplayName("conversion (new start date) starts a fresh period at 0")
    void conversionStartsFreshPeriod() {
        service.recordEmailSent("CHR-1"); service.recordEmailSent("CHR-1");
        assertThat(service.canSendEmail("CHR-1")).isFalse();
        sc.setStartDate(AppClock.today());                                    // Convert sets the start date
        service.evict("CHR-1");
        assertThat(service.canSendEmail("CHR-1")).isTrue();
    }

    @Test @DisplayName("describe() reports the current period's dates and counts")
    @SuppressWarnings("unchecked")
    void describeReportsPeriod() {
        service.recordSmsSent("CHR-1");
        Map<String, Object> d = service.describe("CHR-1");
        Map<String, Object> u = (Map<String, Object>) d.get("usage");
        assertThat(u).containsEntry("periodStart", sc.getStartDate().toString())
                     .containsEntry("periodEnd", sc.getStartDate().plusDays(29).toString())
                     .containsEntry("smsSent", 1)
                     .containsKey("month");
    }
}
