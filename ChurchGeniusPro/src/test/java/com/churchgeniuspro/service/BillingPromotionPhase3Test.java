package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.BillingInvoice;
import com.churchgeniuspro.hibernate.BillingInvoiceLine;
import com.churchgeniuspro.hibernate.ClientPromotion;
import com.churchgeniuspro.hibernate.Promotion;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.SubscriptionRequest;
import com.churchgeniuspro.repository.ClientPromotionRepository;
import com.churchgeniuspro.repository.PromotionHistoryRepository;
import com.churchgeniuspro.repository.PromotionRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Promotions phase 3 — discounts on invoices. The subscription line stays at the
 * regular price; the promotion is part of the invoice discount. A client without
 * a discount gets exactly the invoice it got before.
 */
@DisplayName("Promotions phase 3 — invoices")
class BillingPromotionPhase3Test extends BillingTestBase {

    final Map<Long, ClientPromotion> promoRows = new LinkedHashMap<>();
    final Map<String, Promotion> dealRows = new LinkedHashMap<>();
    ClientPromotionService promotions;
    LocalDate due;

    @BeforeEach
    void wire() {
        ClientPromotionRepository assignments = mock(ClientPromotionRepository.class);
        when(assignments.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(promoRows.get((Long) i.getArgument(0))));
        when(assignments.findByClientIdAndStatusIn(anyString(), anyCollection())).thenAnswer(i -> promoRows.values().stream()
                .filter(a -> a.getClientId().equals(i.getArgument(0)) && ((Collection<?>) i.getArgument(1)).contains(a.getStatus())).toList());
        PromotionRepository deals = mock(PromotionRepository.class);
        when(deals.findByCodeAndBillingFrequency(anyString(), anyString())).thenAnswer(i ->
                Optional.ofNullable(dealRows.get(i.getArgument(0) + "/" + i.getArgument(1))));
        promotions = new ClientPromotionService(assignments, deals, mock(PromotionHistoryRepository.class), clients, plans);
        billing.setPromotions(promotions);
        due = paid.getEndDate();
    }

    /** CHR-1 pays 40.00 monthly; this gives it "THANKS2026: $25 fixed" around its next billing date. */
    ClientPromotion active(String type, String value, LocalDate start, LocalDate end) {
        ClientPromotion a = new ClientPromotion();
        a.setId(ids.incrementAndGet()); a.setClientId("CHR-1"); a.setKind(ClientPromotion.DEAL);
        a.setPromotionCode("THANKS2026"); a.setPromotionName("Thanksgiving"); a.setPlanCode("STANDARD");
        a.setBillingFrequency("MONTHLY"); a.setDiscountType(type); a.setDiscountValue(new BigDecimal(value));
        a.setStatus(ClientPromotion.ACTIVE); a.setStartDate(start); a.setEndDate(end); a.setAssignedAt(LocalDateTime.now());
        promoRows.put(a.getId(), a);
        return a;
    }

    ClientPromotion thanks() { return active("FIXED_PRICE", "25.00", due.minusMonths(1), due.plusYears(2).minusDays(1)); }

    @Test @DisplayName("A client without a discount gets the same invoice as before — with and without the promotion service")
    void noDiscountIdentical() {
        BillingInvoice inv = billing.createRenewalDraft("CHR-1", "a").invoice();
        assertThat(inv.getSubtotal()).isEqualByComparingTo("40.00");
        assertThat(inv.getDiscount()).isEqualByComparingTo("0");
        assertThat(inv.getTotal()).isEqualByComparingTo("40.00");
        assertThat(inv.getClientPromotionId()).isNull();
        assertThat(inv.getPromotionLabel()).isNull();
        assertThat(inv.getPromotionDiscount()).isNull();
        assertThat(inv.getRegularPrice()).isNull();
        String withService = billing.invoiceEmailHtml(inv, linesOf(inv.getId()), "https://x/y", null);
        assertThat(withService).doesNotContain("Promotion").doesNotContain("Promotional pricing");
        assertThat(billing.detail(inv.getId())).doesNotContainKey("promotion");
        assertThat(billing.upcoming().get(0)).doesNotContainKeys("regularPrice", "promotionLabel");
        assertThat(billing.renewalAmount(paid)).isEqualByComparingTo("40.00");

        billing.setPromotions(null);
        String without = billing.invoiceEmailHtml(inv, linesOf(inv.getId()), "https://x/y", null);
        assertThat(withService).isEqualTo(without);
        // an ordinary manual discount still reads "Discount"
        billing.updateDraft(inv.getId(), form(inv, "5.00", currentLines(inv.getId())), "a");
        assertThat(billing.invoiceEmailHtml(inv, linesOf(inv.getId()), "l", null)).contains(">Discount<").contains("−$5.00");
        assertThat(inv.getTotal()).isEqualByComparingTo("35.00");
    }

    @Test @DisplayName("Renewal inside the promotion: line at the regular price, promotion as discount, charges at full price")
    void renewalWithPromotion() {
        ClientPromotion a = thanks();
        charge("CHR-1", "Extra SMS", "10.00");
        BillingInvoice inv = billing.createRenewalDraft("CHR-1", "a").invoice();
        BillingInvoiceLine sub = linesOf(inv.getId()).stream().filter(l -> BillingInvoiceLine.SUBSCRIPTION.equals(l.getKind())).findFirst().orElseThrow();
        assertThat(sub.getAmount()).isEqualByComparingTo("40.00");
        assertThat(inv.getSubtotal()).isEqualByComparingTo("50.00");
        assertThat(inv.getDiscount()).isEqualByComparingTo("15.00");
        assertThat(inv.getTotal()).isEqualByComparingTo("35.00");
        assertThat(inv.getClientPromotionId()).isEqualTo(a.getId());
        assertThat(inv.getPromotionLabel()).isEqualTo("Promotion THANKS2026");
        assertThat(inv.getPromotionDiscount()).isEqualByComparingTo("15.00");
        assertThat(inv.getPromotionEndDate()).isEqualTo(a.getEndDate());
        assertThat(inv.getRegularPrice()).isEqualByComparingTo("40.00");

        String html = billing.invoiceEmailHtml(inv, linesOf(inv.getId()), "https://x/y", null);
        assertThat(html).contains("Promotion THANKS2026").contains("−$15.00").contains("Promotional pricing ends")
                .contains("the regular price of $40.00 per month applies").doesNotContain(">Discount<");

        BillingService.Sent sent = billing.send(inv.getId(), inv.getVersion(), "a");
        assertThat(sent.invoice().getTotal()).isEqualByComparingTo("35.00");
        Map<String, Object> pub = billing.publicView(tokenOf(sent.link()), null).orElseThrow();
        @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) pub.get("promotion");
        assertThat(p.get("label")).isEqualTo("Promotion THANKS2026");
        assertThat((BigDecimal) p.get("discount")).isEqualByComparingTo("15.00");
        assertThat((BigDecimal) p.get("otherDiscount")).isEqualByComparingTo("0");
        assertThat((String) p.get("note")).contains("Promotional pricing ends");
        assertThat((BigDecimal) pub.get("total")).isEqualByComparingTo("35.00");
        assertThat(mails.get(mails.size() - 1)[2]).contains("Promotion THANKS2026");

        Map<String, Object> up = billing.upcoming().get(0);
        assertThat((BigDecimal) up.get("price")).isEqualByComparingTo("25.00");
        assertThat((BigDecimal) up.get("regularPrice")).isEqualByComparingTo("40.00");
        assertThat(up.get("promotionLabel")).isEqualTo("Promotion THANKS2026");
        assertThat(billing.renewalAmount(paid)).isEqualByComparingTo("25.00");
    }

    @Test @DisplayName("Percent and amount-off; a manual discount is labelled 'Special discount'")
    void typesAndManual() {
        ClientPromotion a = active("PERCENT", "25", due.minusDays(1), due.plusMonths(3));
        BillingInvoice inv = billing.createRenewalDraft("CHR-1", "a").invoice();
        assertThat(inv.getDiscount()).isEqualByComparingTo("10.00");
        assertThat(inv.getTotal()).isEqualByComparingTo("30.00");
        invRows.clear();
        a.setDiscountType("AMOUNT_OFF"); a.setDiscountValue(new BigDecimal("12.50"));
        a.setKind(ClientPromotion.MANUAL); a.setPromotionCode(null);
        inv = billing.createRenewalDraft("CHR-1", "a").invoice();
        assertThat(inv.getTotal()).isEqualByComparingTo("27.50");
        assertThat(inv.getPromotionLabel()).isEqualTo("Special discount");
    }

    @Test @DisplayName("Outside the dates, another frequency, cancelled or waiting: the regular price")
    void outsideWindow() {
        ClientPromotion a = active("FIXED_PRICE", "25.00", due.plusDays(1), due.plusYears(1));
        assertThat(billing.createRenewalDraft("CHR-1", "a").invoice().getTotal()).isEqualByComparingTo("40.00");
        invRows.clear();
        a.setStartDate(due.minusYears(1)); a.setEndDate(due.minusDays(1));           // ended the day before the billing date
        assertThat(billing.createRenewalDraft("CHR-1", "a").invoice().getTotal()).isEqualByComparingTo("40.00");
        invRows.clear();
        a.setEndDate(due);                                                            // last day = billing date → still promotional
        assertThat(billing.createRenewalDraft("CHR-1", "a").invoice().getTotal()).isEqualByComparingTo("25.00");
        invRows.clear();
        a.setBillingFrequency("YEARLY");
        assertThat(billing.createRenewalDraft("CHR-1", "a").invoice().getTotal()).isEqualByComparingTo("40.00");
        invRows.clear();
        a.setBillingFrequency("MONTHLY"); a.setStatus(ClientPromotion.CANCELLED);
        assertThat(billing.createRenewalDraft("CHR-1", "a").invoice().getTotal()).isEqualByComparingTo("40.00");
        invRows.clear();
        a.setStatus(ClientPromotion.SCHEDULED); a.setStartDate(null); a.setEndDate(null);
        BillingInvoice inv = billing.createRenewalDraft("CHR-1", "a").invoice();
        assertThat(inv.getTotal()).isEqualByComparingTo("40.00");
        assertThat(inv.getClientPromotionId()).isNull();
    }

    @Test @DisplayName("A discount never raises a price, and a failed lookup falls back to the regular price")
    void neverRaises() {
        active("FIXED_PRICE", "55.00", due.minusDays(1), due.plusYears(1));
        BillingInvoice inv = billing.createRenewalDraft("CHR-1", "a").invoice();
        assertThat(inv.getTotal()).isEqualByComparingTo("40.00");
        assertThat(inv.getClientPromotionId()).isNull();

        invRows.clear();
        ClientPromotionService broken = mock(ClientPromotionService.class);
        when(broken.forInvoice(any(), any())).thenThrow(new IllegalStateException("db"));
        billing.setPromotions(broken);
        assertThat(billing.createRenewalDraft("CHR-1", "a").invoice().getTotal()).isEqualByComparingTo("40.00");
    }

    @Test @DisplayName("An invoice that already exists is never changed by a discount given later")
    void existingInvoicesUntouched() {
        BillingInvoice draft = billing.createRenewalDraft("CHR-1", "a").invoice();
        thanks();
        BillingService.Draft again = billing.createRenewalDraft("CHR-1", "a");
        assertThat(again.created()).isFalse();
        assertThat(again.invoice().getTotal()).isEqualByComparingTo("40.00");
        assertThat(again.invoice().getClientPromotionId()).isNull();
        BillingService.Sent sent = billing.send(draft.getId(), draft.getVersion(), "a");
        assertThat(sent.invoice().getTotal()).isEqualByComparingTo("40.00");
        assertThat(billing.publicView(tokenOf(sent.link()), null).orElseThrow()).doesNotContainKey("promotion");
    }

    @Test @DisplayName("Review: keep the promotion with another discount, remove it, or protect it from a changed line")
    void review() {
        thanks();
        BillingInvoice inv = billing.createRenewalDraft("CHR-1", "a").invoice();
        Long id = inv.getId();

        billing.updateDraft(id, keep(inv, "5.00", currentLines(id), "KEEP"), "a");
        assertThat(inv.getDiscount()).isEqualByComparingTo("20.00");
        assertThat(inv.getTotal()).isEqualByComparingTo("20.00");
        assertThat(inv.getPromotionDiscount()).isEqualByComparingTo("15.00");
        @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) billing.detail(id).get("promotion");
        assertThat((BigDecimal) p.get("otherDiscount")).isEqualByComparingTo("5.00");
        String html = billing.invoiceEmailHtml(inv, linesOf(id), "l", null);
        assertThat(html).contains("Promotion THANKS2026").contains("−$15.00").contains(">Discount<").contains("−$5.00");

        // other discount + promotion may not exceed the subtotal
        assertThatThrownBy(() -> billing.updateDraft(id, keep(inv, "26.00", currentLines(id), "KEEP"), "a"))
                .hasMessageContaining("Discount cannot be more than the subtotal");

        // the subscription line was changed: the promotion amount would be wrong
        List<BillingService.LineForm> changed = new ArrayList<>(List.of(new BillingService.LineForm("SUBSCRIPTION", "Standard", "30.00", null)));
        assertThatThrownBy(() -> billing.updateDraft(id, keep(inv, "0", changed, "KEEP"), "a"))
                .hasMessageContaining("Put the subscription line back");
        assertThat(inv.getPromotionDiscount()).isEqualByComparingTo("15.00");

        // older caller (no choice): the whole discount still covers the promotion → kept as typed
        billing.updateDraft(id, form(inv, "15.00", currentLines(id)), "a");
        assertThat(inv.getDiscount()).isEqualByComparingTo("15.00");
        assertThat(inv.getClientPromotionId()).isNotNull();
        // older caller lowers the discount below the promotion → it is an ordinary discount now
        billing.updateDraft(id, form(inv, "3.00", currentLines(id)), "a");
        assertThat(inv.getDiscount()).isEqualByComparingTo("3.00");
        assertThat(inv.getClientPromotionId()).isNull();
        assertThat(inv.getPromotionDiscount()).isNull();
    }

    @Test @DisplayName("Remove takes the promotion off this invoice only")
    void remove() {
        ClientPromotion a = thanks();
        BillingInvoice inv = billing.createRenewalDraft("CHR-1", "a").invoice();
        billing.updateDraft(inv.getId(), keep(inv, "0", currentLines(inv.getId()), "REMOVE"), "a");
        assertThat(inv.getDiscount()).isEqualByComparingTo("0");
        assertThat(inv.getTotal()).isEqualByComparingTo("40.00");
        assertThat(inv.getClientPromotionId()).isNull();
        assertThat(inv.getPromotionLabel()).isNull();
        assertThat(a.getStatus()).isEqualTo(ClientPromotion.ACTIVE);
        assertThat(billing.detail(inv.getId())).doesNotContainKey("promotion");
    }

    @Test @DisplayName("Send is refused when the discount was cancelled or its terms changed; an expired discount still honours its period")
    void sendGuard() {
        ClientPromotion a = thanks();
        BillingInvoice inv = billing.createRenewalDraft("CHR-1", "a").invoice();
        a.setStatus(ClientPromotion.CANCELLED);
        assertThatThrownBy(() -> billing.send(inv.getId(), inv.getVersion(), "a"))
                .hasMessageContaining("the discount was cancelled").hasMessageContaining("remove the promotion");
        assertThat(inv.getStatus()).isEqualTo(BillingInvoice.DRAFT);
        assertThat(mails).isEmpty();

        a.setStatus(ClientPromotion.ACTIVE); a.setDiscountValue(new BigDecimal("30.00"));
        assertThatThrownBy(() -> billing.send(inv.getId(), inv.getVersion(), "a")).hasMessageContaining("terms changed");

        a.setDiscountValue(new BigDecimal("25.00")); a.setStartDate(due.plusDays(3));
        assertThatThrownBy(() -> billing.send(inv.getId(), inv.getVersion(), "a")).hasMessageContaining("outside the discount's dates");

        a.setStartDate(due.minusMonths(1)); a.setStatus(ClientPromotion.EXPIRED);     // the period began inside the promotion
        assertThat(billing.send(inv.getId(), inv.getVersion(), "a").invoice().getTotal()).isEqualByComparingTo("25.00");

        // removing the promotion makes a cancelled one sendable
        invRows.clear(); lineRows.clear();
        a.setStatus(ClientPromotion.ACTIVE);
        BillingInvoice two = billing.createRenewalDraft("CHR-1", "a").invoice();
        a.setStatus(ClientPromotion.CANCELLED);
        billing.updateDraft(two.getId(), keep(two, "0", currentLines(two.getId()), "REMOVE"), "a");
        assertThat(billing.send(two.getId(), two.getVersion(), "a").invoice().getTotal()).isEqualByComparingTo("40.00");
    }

    @Test @DisplayName("A trial's waiting deal is on its Subscription Request invoice, for the matching frequency only")
    void requestInvoice() {
        ServiceClient trial = client(6, "CHR-6", "TRIAL", null, TODAY.plusDays(5));
        ClientPromotion w = new ClientPromotion();
        w.setId(ids.incrementAndGet()); w.setClientId("CHR-6"); w.setKind(ClientPromotion.DEAL); w.setPromotionCode("THANKS2026");
        w.setStatus(ClientPromotion.SCHEDULED); w.setAssignedAt(LocalDateTime.now());
        promoRows.put(w.getId(), w);
        Promotion monthly = new Promotion();
        monthly.setId(1L); monthly.setCode("THANKS2026"); monthly.setName("Thanksgiving"); monthly.setBillingFrequency("MONTHLY");
        monthly.setDiscountType("PERCENT"); monthly.setDiscountValue(new BigDecimal("50")); monthly.setDurationAmount(6);
        monthly.setDurationUnit("MONTHS"); monthly.setPlanCodes("STANDARD"); monthly.setActive(true);
        dealRows.put("THANKS2026/MONTHLY", monthly);

        SubscriptionRequest r = new SubscriptionRequest();
        r.setId(9L); r.setClientId("CHR-6"); r.setPlanCode("STANDARD"); r.setBillingFrequency("MONTHLY");
        r.setStatus(SubscriptionRequest.NEW); r.setRegisteredEmail("ann@church.test");
        when(requests.findById(9L)).thenReturn(Optional.of(r));
        BillingInvoice inv = billing.createRequestDraft(9L, "a").invoice();
        assertThat(inv.getSubtotal()).isEqualByComparingTo("49.00");
        assertThat(inv.getDiscount()).isEqualByComparingTo("24.50");
        assertThat(inv.getTotal()).isEqualByComparingTo("24.50");
        assertThat(inv.getPromotionEndDate()).isEqualTo(TODAY.plusMonths(6).minusDays(1));
        @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) billing.detail(inv.getId()).get("promotion");
        assertThat(p.get("tentative")).isEqualTo(true);
        assertThat(w.getStatus()).isEqualTo(ClientPromotion.SCHEDULED);     // nothing written: applying happens at Convert
        assertThat(w.getStartDate()).isNull();
        assertThat(billing.send(inv.getId(), inv.getVersion(), "a").invoice().getTotal()).isEqualByComparingTo("24.50");

        // a yearly request: there is no yearly THANKS2026 → list price, no promotion
        invRows.clear(); lineRows.clear();
        r.setBillingFrequency("YEARLY");
        BillingInvoice yearly = billing.createRequestDraft(9L, "a").invoice();
        assertThat(yearly.getTotal()).isEqualByComparingTo("490.00");
        assertThat(yearly.getClientPromotionId()).isNull();

        // the plan is not covered by the deal → list price
        invRows.clear(); lineRows.clear();
        r.setBillingFrequency("MONTHLY"); r.setPlanCode("PRO");
        assertThat(billing.createRequestDraft(9L, "a").invoice().getTotal()).isEqualByComparingTo("99.00");

        // cancelled while the draft waits → send refused
        invRows.clear(); lineRows.clear();
        r.setPlanCode("STANDARD");
        BillingInvoice again = billing.createRequestDraft(9L, "a").invoice();
        assertThat(again.getTotal()).isEqualByComparingTo("24.50");
        w.setStatus(ClientPromotion.CANCELLED);
        assertThatThrownBy(() -> billing.send(again.getId(), again.getVersion(), "a")).hasMessageContaining("cancelled");
        assertThat(trial.getSubscriptionType()).isEqualTo("TRIAL");
    }

    @Test @DisplayName("Pages, receipt and the production script know the promotion fields")
    void pagesAndScript() throws Exception {
        String inv = Files.readString(Paths.get("src/main/resources/static/invoice.html"));
        assertThat(inv).contains("id=\"promoRow\"").contains("id=\"promoNote\"").contains("promo.otherDiscount");
        String admin = Files.readString(Paths.get("src/main/resources/static/serviceadminhome.html"));
        assertThat(admin).contains("id=\"blInvPromoRow\"").contains("PROMO_REMOVED ? 'REMOVE' : 'KEEP'").contains("c.promotionLabel");
        String sql = Files.readString(Paths.get("scripts/sql/migrate_production.sql"));
        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS client_promotion_id BIGINT")
                .contains("ADD COLUMN IF NOT EXISTS promotion_discount NUMERIC(12,2)")
                .contains("ADD COLUMN IF NOT EXISTS regular_price NUMERIC(12,2)");
        String pay = Files.readString(Paths.get("src/main/java/com/churchgeniuspro/service/BillingPaymentService.java"));
        assertThat(pay).contains("BillingService.promotionNote(inv)");
        // the payment amount is still the invoice total — nothing in payment reads the promotion amount
        assertThat(pay).doesNotContain("getPromotionDiscount().subtract").doesNotContain("getRegularPrice()");
    }

    BillingService.DraftForm keep(BillingInvoice inv, String other, List<BillingService.LineForm> ls, String choice) {
        return new BillingService.DraftForm(inv.getVersion(), inv.getBillToName(), inv.getBillToEmail(), inv.getDueDate(),
                null, other, ls, choice);
    }
}
