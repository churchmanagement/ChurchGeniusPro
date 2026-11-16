package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.ClientPromotion;
import com.churchgeniuspro.hibernate.PromotionHistory;
import com.churchgeniuspro.hibernate.ReminderSentLog;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.repository.ClientPromotionRepository;
import com.churchgeniuspro.repository.PromotionHistoryRepository;
import com.churchgeniuspro.repository.PromotionRepository;
import com.churchgeniuspro.repository.ReminderSentLogRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.SubscriptionPlanRepository;
import com.churchgeniuspro.util.AppClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Promotions phase 4 — the daily pass: activate, expire (with the history entry), 30 / 10 / 1-day notices. */
@DisplayName("Promotions phase 4 — expiry and notices")
class PromotionExpiryPhase4Test {

    static final LocalDate TODAY = AppClock.today();

    final Map<Long, ClientPromotion> rows = new LinkedHashMap<>();
    final Map<String, ServiceClient> clientRows = new LinkedHashMap<>();
    final List<PromotionHistory> historyRows = new ArrayList<>();
    final Set<String> claims = new HashSet<>();
    final List<Object[]> mails = new ArrayList<>();   // {to, bcc, subject, html}
    boolean mailFails;
    long ids = 0;

    ClientPromotionService promotions;
    PromotionExpiryScheduler job;
    ClientPromotionRepository assignments;

    @BeforeEach
    void setUp() throws Exception {
        assignments = mock(ClientPromotionRepository.class);
        when(assignments.findByStatusIn(anyCollection())).thenAnswer(i -> rows.values().stream()
                .filter(a -> ((Collection<?>) i.getArgument(0)).contains(a.getStatus())).toList());
        when(assignments.findByClientIdAndStatusIn(anyString(), anyCollection())).thenAnswer(i -> rows.values().stream()
                .filter(a -> a.getClientId().equals(i.getArgument(0)) && ((Collection<?>) i.getArgument(1)).contains(a.getStatus())).toList());
        when(assignments.findByStatusAndStartDateLessThanEqual(anyString(), any())).thenAnswer(i -> rows.values().stream()
                .filter(a -> a.getStatus().equals(i.getArgument(0)) && a.getStartDate() != null
                        && !a.getStartDate().isAfter(i.getArgument(1))).toList());
        when(assignments.save(any(ClientPromotion.class))).thenAnswer(i -> i.getArgument(0));
        PromotionHistoryRepository history = mock(PromotionHistoryRepository.class);
        when(history.save(any(PromotionHistory.class))).thenAnswer(i -> { historyRows.add(i.getArgument(0)); return i.getArgument(0); });
        ServiceClientRepository clients = mock(ServiceClientRepository.class);
        when(clients.findByClientId(anyString())).thenAnswer(i -> Optional.ofNullable(clientRows.get((String) i.getArgument(0))));
        SubscriptionPlanRepository plans = mock(SubscriptionPlanRepository.class);
        SubscriptionPlan pro = new SubscriptionPlan();
        pro.setPlanCode("PRO"); pro.setPlanName("Pro"); pro.setActive(true); pro.setMonthlyPrice(new BigDecimal("40.00"));
        pro.setYearlyPrice(new BigDecimal("400.00"));
        when(plans.findByPlanCodeIgnoreCase(anyString())).thenReturn(Optional.of(pro));
        promotions = new ClientPromotionService(assignments, mock(PromotionRepository.class), history, clients, plans);

        ReminderSentLogRepository sentLog = mock(ReminderSentLogRepository.class);
        when(sentLog.existsByAppClientIdAndReminderTypeAndReferenceKeyAndSentDate(anyString(), anyString(), anyString(), any()))
                .thenAnswer(i -> claims.contains(i.getArgument(0) + "|" + i.getArgument(1) + "|" + i.getArgument(2) + "|" + i.getArgument(3)));
        when(sentLog.saveAndFlush(any(ReminderSentLog.class))).thenAnswer(i -> {
            ReminderSentLog r = i.getArgument(0);
            claims.add(r.getAppClientId() + "|" + r.getReminderType() + "|" + r.getReferenceKey() + "|" + r.getSentDate());
            return r;
        });
        doAnswer(i -> {
            ReminderSentLog r = i.getArgument(0);
            claims.remove(r.getAppClientId() + "|" + r.getReminderType() + "|" + r.getReferenceKey() + "|" + r.getSentDate());
            return null;
        }).when(sentLog).delete(any(ReminderSentLog.class));
        EmailService email = mock(EmailService.class);
        doAnswer(i -> {
            if (mailFails) throw new RuntimeException("SMTP down");
            mails.add(new Object[]{ ((List<?>) i.getArgument(0)).get(0), i.getArgument(1), i.getArgument(2), i.getArgument(3) });
            return null;
        }).when(email).sendComposed(anyList(), any(), anyString(), anyString(), any(), anyString());
        PlatformSettingService settings = mock(PlatformSettingService.class);
        when(settings.supportEmail()).thenReturn("support@cgp.test");
        job = new PromotionExpiryScheduler(promotions, clients, settings, sentLog, email);
    }

    ServiceClient client(String cid) {
        ServiceClient c = new ServiceClient();
        c.setId(clientRows.size() + 1); c.setClientId(cid); c.setChurchName("Grace <Church>"); c.setEmail(cid.toLowerCase() + "@church.test");
        c.setSubscriptionType("PRO"); c.setStatus("Active"); c.setDeleteFlag(false); c.setBillingFrequency("MONTHLY");
        c.setSubscriptionPrice(new BigDecimal("40.00")); c.setEndDate(TODAY.plusDays(5));
        clientRows.put(cid, c);
        return c;
    }

    ClientPromotion discount(String cid, String status, LocalDate start, LocalDate end) {
        ClientPromotion a = new ClientPromotion();
        a.setId(++ids); a.setClientId(cid); a.setKind(ClientPromotion.DEAL); a.setPromotionCode("THANKS2026");
        a.setPromotionName("Thanksgiving"); a.setPlanCode("PRO"); a.setBillingFrequency("MONTHLY");
        a.setDiscountType("FIXED_PRICE"); a.setDiscountValue(new BigDecimal("25.00")); a.setStatus(status);
        a.setStartDate(start); a.setEndDate(end); a.setRegularPrice(new BigDecimal("40.00"));
        a.setPromotionalPrice(new BigDecimal("25.00")); a.setNotes("Founding church"); a.setAssignedAt(LocalDateTime.now());
        rows.put(a.getId(), a);
        return a;
    }

    @Test @DisplayName("The day after the last day: Expired, restored-on date, one history entry with every required detail")
    void expires() {
        client("CHR-1");
        ClientPromotion ended = discount("CHR-1", ClientPromotion.ACTIVE, TODAY.minusYears(2), TODAY.minusDays(1));
        client("CHR-2");
        ClientPromotion lastDay = discount("CHR-2", ClientPromotion.ACTIVE, TODAY.minusMonths(6), TODAY);
        client("CHR-3");
        ClientPromotion cancelled = discount("CHR-3", ClientPromotion.CANCELLED, TODAY.minusYears(1), TODAY.minusDays(9));
        client("CHR-4");
        ClientPromotion waiting = discount("CHR-4", ClientPromotion.SCHEDULED, null, null);

        PromotionExpiryScheduler.Result r = job.runFor(TODAY);
        assertThat(r.expired()).isEqualTo(1);
        assertThat(ended.getStatus()).isEqualTo(ClientPromotion.EXPIRED);
        assertThat(ended.getRestoredOn()).isEqualTo(TODAY);
        assertThat(lastDay.getStatus()).isEqualTo(ClientPromotion.ACTIVE);          // still promotional on its last day
        assertThat(cancelled.getStatus()).isEqualTo(ClientPromotion.CANCELLED);
        assertThat(waiting.getStatus()).isEqualTo(ClientPromotion.SCHEDULED);
        assertThat(rows).hasSize(4);                                                // nothing is ever deleted
        verify(assignments, never()).delete(any());

        assertThat(historyRows).hasSize(1);
        PromotionHistory h = historyRows.get(0);
        assertThat(h.getEvent()).isEqualTo(PromotionHistory.EXPIRED);
        assertThat(h.getActor()).isEqualTo("system");
        assertThat(h.getClientId()).isEqualTo("CHR-1");
        assertThat(h.getPromotionCode()).isEqualTo("THANKS2026");
        assertThat(h.getNotes()).startsWith("Promotion expired – Regular subscription pricing restored").contains("Founding church");
        assertThat(h.getAfterJson()).contains("\"status\":\"EXPIRED\"").contains("\"promotionCode\":\"THANKS2026\"")
                .contains("\"promotionalPrice\":\"25.00\"").contains("\"regularPrice\":\"40.00\"").contains("\"regularPriceNow\":\"40.00\"")
                .contains("\"planCode\":\"PRO\"").contains("\"billingFrequency\":\"MONTHLY\"")
                .contains("\"expiredOn\":\"" + TODAY.minusDays(1) + "\"").contains("\"regularPricingFrom\":\"" + TODAY + "\"");
        assertThat(h.getBeforeJson()).contains("\"status\":\"ACTIVE\"");

        // a second run the same day (or the next) writes nothing more
        job.runFor(TODAY); job.runFor(TODAY.plusDays(1));
        assertThat(historyRows.stream().filter(x -> x.getClientId().equals("CHR-1")).count()).isEqualTo(1);
        // the client's own price was never touched
        assertThat(clientRows.get("CHR-1").getSubscriptionPrice()).isEqualByComparingTo("40.00");
        // and the price rule gives the regular price again
        assertThat(promotions.quote(clientRows.get("CHR-1"), TODAY).discounted()).isFalse();
    }

    @Test @DisplayName("A scheduled discount whose start date arrived becomes Active in the same pass")
    void activates() {
        client("CHR-1");
        ClientPromotion a = discount("CHR-1", ClientPromotion.SCHEDULED, TODAY, TODAY.plusYears(1).minusDays(1));
        assertThat(job.runFor(TODAY).activated()).isEqualTo(1);
        assertThat(a.getStatus()).isEqualTo(ClientPromotion.ACTIVE);
        assertThat(historyRows.get(0).getEvent()).isEqualTo(PromotionHistory.ACTIVATED);
    }

    @Test @DisplayName("Notices 30, 10 and 1 day(s) before the end — once each, to the client with a copy to Support")
    void notices() {
        client("CHR-1");
        ClientPromotion a = discount("CHR-1", ClientPromotion.ACTIVE, TODAY.minusMonths(11), TODAY.plusDays(30));
        assertThat(job.runFor(TODAY).noticesSent()).isEqualTo(1);
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0)[0]).isEqualTo("chr-1@church.test");
        assertThat(String.valueOf(mails.get(0)[1])).isEqualTo("[support@cgp.test]");
        assertThat((String) mails.get(0)[2]).contains("ends in 30 days");
        String html = (String) mails.get(0)[3];
        assertThat(html).contains("Promotion THANKS2026").contains("$25.00 per month").contains("$40.00 per month")
                .contains("Grace &lt;Church&gt;").doesNotContain("<Church>").contains("You do not need to do anything");
        // the same day again: nothing
        assertThat(job.runFor(TODAY).noticesSent()).isZero();
        assertThat(mails).hasSize(1);
        // other days: nothing until 10 and 1
        assertThat(job.runFor(TODAY.plusDays(5)).noticesSent()).isZero();
        assertThat(job.runFor(TODAY.plusDays(20)).noticesSent()).isEqualTo(1);
        assertThat((String) mails.get(1)[2]).contains("ends in 10 days");
        assertThat(job.runFor(TODAY.plusDays(29)).noticesSent()).isEqualTo(1);
        assertThat((String) mails.get(2)[2]).contains("ends tomorrow");
        assertThat(job.runFor(TODAY.plusDays(30)).noticesSent()).isZero();           // the last day itself: no notice
        assertThat(a.getStatus()).isEqualTo(ClientPromotion.ACTIVE);
        assertThat(job.runFor(TODAY.plusDays(31)).expired()).isEqualTo(1);
        assertThat(mails).hasSize(3);
    }

    @Test @DisplayName("No 30-day notice for a discount of one month or less; 10 and 1 still go out")
    void oneMonthDeal() {
        client("CHR-1");
        LocalDate start = TODAY.minusDays(1);
        ClientPromotion a = discount("CHR-1", ClientPromotion.ACTIVE, start, start.plusMonths(1).minusDays(1));
        assertThat(PromotionExpiryScheduler.longerThanOneMonth(a)).isFalse();
        for (LocalDate d = TODAY; !d.isAfter(a.getEndDate()); d = d.plusDays(1)) job.runFor(d);
        assertThat(mails).hasSize(2);
        assertThat((String) mails.get(0)[2]).contains("in 10 days");
        assertThat((String) mails.get(1)[2]).contains("tomorrow");
        ClientPromotion three = discount("CHR-1", ClientPromotion.CANCELLED, start, start.plusMonths(3).minusDays(1));
        assertThat(PromotionExpiryScheduler.longerThanOneMonth(three)).isTrue();
    }

    @Test @DisplayName("No notice for a cancelled discount, an inactive or deleted client, or when notices are switched off")
    void noNotice() {
        client("CHR-1");
        discount("CHR-1", ClientPromotion.CANCELLED, TODAY.minusMonths(5), TODAY.plusDays(10));
        ServiceClient gone = client("CHR-2"); gone.setDeleteFlag(true);
        discount("CHR-2", ClientPromotion.ACTIVE, TODAY.minusMonths(5), TODAY.plusDays(10));
        ServiceClient off = client("CHR-3"); off.setStatus("Inactive");
        discount("CHR-3", ClientPromotion.ACTIVE, TODAY.minusMonths(5), TODAY.plusDays(10));
        ServiceClient yearly = client("CHR-4"); yearly.setBillingFrequency("YEARLY");   // discount is monthly: no promotional price in force
        discount("CHR-4", ClientPromotion.ACTIVE, TODAY.minusMonths(5), TODAY.plusDays(10));
        assertThat(job.runFor(TODAY).noticesSent()).isZero();
        assertThat(mails).isEmpty();

        client("CHR-5");
        discount("CHR-5", ClientPromotion.ACTIVE, TODAY.minusMonths(5), TODAY.plusDays(10));
        job.setNoticesEnabled(false);
        assertThat(job.runFor(TODAY).noticesSent()).isZero();
        job.setNoticesEnabled(true);
        assertThat(job.runFor(TODAY).noticesSent()).isEqualTo(1);
    }

    @Test @DisplayName("A failed email releases its claim, so the next run sends it; one failure never stops the others")
    void failures() {
        client("CHR-1");
        discount("CHR-1", ClientPromotion.ACTIVE, TODAY.minusMonths(5), TODAY.plusDays(1));
        mailFails = true;
        assertThat(job.runFor(TODAY).noticesSent()).isZero();
        assertThat(claims).isEmpty();
        mailFails = false;
        assertThat(job.runFor(TODAY).noticesSent()).isEqualTo(1);

        // expiry: a discount whose save fails does not stop the next one
        client("CHR-2"); client("CHR-3");
        ClientPromotion bad = discount("CHR-2", ClientPromotion.ACTIVE, TODAY.minusYears(1), TODAY.minusDays(2));
        ClientPromotion good = discount("CHR-3", ClientPromotion.ACTIVE, TODAY.minusYears(1), TODAY.minusDays(2));
        when(assignments.save(bad)).thenThrow(new IllegalStateException("db"));
        assertThat(job.runFor(TODAY).expired()).isEqualTo(1);
        assertThat(good.getStatus()).isEqualTo(ClientPromotion.EXPIRED);
    }

    @Test @DisplayName("The job is scheduled in Central time and documented")
    void wiring() throws Exception {
        String src = Files.readString(Paths.get("src/main/java/com/churchgeniuspro/service/PromotionExpiryScheduler.java"));
        assertThat(src).contains("zone = \"America/Chicago\"").contains("scheduler.job.promotion-expiry");
        assertThat(Files.readString(Paths.get("src/main/resources/scheduler.properties"))).contains("scheduler.job.promotion-expiry=");
        assertThat(src).doesNotContain(".delete(a").doesNotContain("setSubscriptionPrice");
    }
}
