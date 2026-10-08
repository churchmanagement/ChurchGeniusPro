package com.churchgeniuspro.service;

import com.churchgeniuspro.controller.InvoiceController;
import com.churchgeniuspro.controller.ServiceAdminBillingController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.util.AppClock;
import com.churchgeniuspro.webfilter.AccountStatusFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


/**
 * Shared in-memory fakes for the billing unit tests (Phase 5 and Phase 6): repositories
 * that implement the conditional updates the way the JPQL does, a captured email
 * outbox, and helpers. Holds no tests of its own.
 */
abstract class BillingTestBase {

    final Map<Long, BillingInvoice> invRows = new LinkedHashMap<>();
    final Map<Long, BillingInvoiceLine> lineRows = new LinkedHashMap<>();
    final Map<Long, ClientCharge> chargeRows = new LinkedHashMap<>();
    final Map<String, ServiceClient> clientRows = new LinkedHashMap<>();
    final List<ReminderSentLog> sentLogRows = new ArrayList<>();
    final List<String[]> mails = new ArrayList<>();   // {to, subject, html, displayName}
    final AtomicLong ids = new AtomicLong(100);

    BillingInvoiceRepository invoices;
    BillingInvoiceLineRepository lines;
    ClientChargeRepository charges;
    ServiceClientRepository clients;
    SubscriptionRequestRepository requests;
    SubscriptionPlanRepository plans;
    EmailService email;
    PlatformSettingService settings;
    BillingService billing;
    boolean mailFails;

    ServiceClient paid;     // CHR-1 Standard monthly, negotiated price 40.00 (list 49.00)
    static final LocalDate TODAY = AppClock.today();

    static SubscriptionPlan plan(String code, String name, String monthly, String yearly) {
        SubscriptionPlan p = new SubscriptionPlan();
        p.setPlanCode(code); p.setPlanName(name); p.setActive(true);
        p.setMonthlyPrice(monthly == null ? null : new BigDecimal(monthly));
        p.setYearlyPrice(yearly == null ? null : new BigDecimal(yearly));
        return p;
    }

    ServiceClient client(int id, String cid, String plan, String price, LocalDate end) {
        ServiceClient c = new ServiceClient();
        c.setId(id); c.setClientId(cid); c.setChurchName("Church " + cid); c.setName("Pat Pastor");
        c.setEmail(cid.toLowerCase() + "@church.test"); c.setSubscriptionType(plan); c.setStatus("Active");
        c.setDeleteFlag(false); c.setPaymentStatus("PAID"); c.setBillingFrequency("MONTHLY");
        c.setSubscriptionPrice(price == null ? null : new BigDecimal(price));
        c.setStartDate(TODAY.minusMonths(1)); c.setEndDate(end);
        clientRows.put(cid, c);
        return c;
    }

    @BeforeEach
    void setUp() throws Exception {
        invoices = mock(BillingInvoiceRepository.class);
        lines = mock(BillingInvoiceLineRepository.class);
        charges = mock(ClientChargeRepository.class);
        clients = mock(ServiceClientRepository.class);
        requests = mock(SubscriptionRequestRepository.class);
        plans = mock(SubscriptionPlanRepository.class);
        email = mock(EmailService.class);
        settings = mock(PlatformSettingService.class);
        when(settings.supportEmail()).thenReturn("support@cgp.test");

        Map<String, SubscriptionPlan> planMap = Map.of(
                "STANDARD", plan("STANDARD", "Standard", "49.00", "490.00"),
                "PRO", plan("PRO", "Pro", "99.00", null),
                "TRIAL", plan("TRIAL", "Trial", "0", null));
        when(plans.findByPlanCodeIgnoreCase(anyString())).thenAnswer(i -> Optional.ofNullable(planMap.get(((String) i.getArgument(0)).toUpperCase())));

        // ── invoices ──
        org.mockito.stubbing.Answer<BillingInvoice> saveInv = i -> {
            BillingInvoice b = i.getArgument(0);
            if (b.getId() == null) {
                if (BillingInvoice.KIND_RENEWAL.equals(b.getKind()) && invRows.values().stream().anyMatch(o -> o != b
                        && BillingInvoice.KIND_RENEWAL.equals(o.getKind()) && o.getClientId().equals(b.getClientId())
                        && Objects.equals(o.getPeriodStart(), b.getPeriodStart()) && BillingService.OPEN_OR_PAID.contains(o.getStatus()))) {
                    throw new DataIntegrityViolationException("ux_billing_invoice_open_renewal");
                }
                b.setId(ids.incrementAndGet()); b.setVersion(0L);
                if (b.getSubtotal() == null) b.setSubtotal(BigDecimal.ZERO);
                if (b.getDiscount() == null) b.setDiscount(BigDecimal.ZERO);
                if (b.getTotal() == null) b.setTotal(BigDecimal.ZERO);
            } else {
                b.setVersion(b.getVersion() + 1);
            }
            invRows.put(b.getId(), b);
            return b;
        };
        when(invoices.save(any(BillingInvoice.class))).thenAnswer(saveInv);
        when(invoices.saveAndFlush(any(BillingInvoice.class))).thenAnswer(saveInv);
        when(invoices.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(invRows.get((Long) i.getArgument(0))));
        when(invoices.lockById(anyLong())).thenAnswer(i -> Optional.ofNullable(invRows.get((Long) i.getArgument(0))));
        when(invoices.findTop500ByOrderByIdDesc()).thenAnswer(i -> invRows.values().stream()
                .sorted(Comparator.comparing(BillingInvoice::getId).reversed()).toList());
        when(invoices.findByAccessTokenHash(anyString())).thenAnswer(i -> invRows.values().stream()
                .filter(b -> i.getArgument(0).equals(b.getAccessTokenHash())).findFirst());
        when(invoices.findByClientIdAndKindAndPeriodStartAndStatusIn(anyString(), anyString(), any(), anyCollection())).thenAnswer(i ->
                invRows.values().stream().filter(b -> b.getClientId().equals(i.getArgument(0)) && b.getKind().equals(i.getArgument(1))
                        && Objects.equals(b.getPeriodStart(), i.getArgument(2)) && ((Collection<?>) i.getArgument(3)).contains(b.getStatus())).toList());
        when(invoices.findByClientIdAndKindAndPeriodStart(anyString(), anyString(), any())).thenAnswer(i ->
                invRows.values().stream().filter(b -> b.getClientId().equals(i.getArgument(0)) && b.getKind().equals(i.getArgument(1))
                        && Objects.equals(b.getPeriodStart(), i.getArgument(2))).toList());
        when(invoices.findBySubscriptionRequestIdAndStatusIn(anyLong(), anyCollection())).thenAnswer(i ->
                invRows.values().stream().filter(b -> i.getArgument(0).equals(b.getSubscriptionRequestId())
                        && ((Collection<?>) i.getArgument(1)).contains(b.getStatus())).toList());
        when(invoices.markSent(anyLong(), anyLong(), any(), any(), any(), anyString(), any(), any(), any(), any())).thenAnswer(i -> {
            BillingInvoice b = invRows.get((Long) i.getArgument(0));
            if (b == null || !BillingInvoice.DRAFT.equals(b.getStatus()) || !b.getVersion().equals(i.getArgument(1))) return 0;
            b.setStatus(BillingInvoice.SENT); b.setVersion(b.getVersion() + 1);
            b.setSubtotal(i.getArgument(2)); b.setDiscount(i.getArgument(3)); b.setTotal(i.getArgument(4));
            b.setAccessTokenHash(i.getArgument(5)); b.setAccessTokenExpires(i.getArgument(6)); b.setIssueDate(i.getArgument(7));
            b.setSentAt(i.getArgument(8)); b.setSentBy(i.getArgument(9)); b.setSendCount(1);
            return 1;
        });
        when(invoices.revertSend(anyLong(), anyString())).thenAnswer(i -> {
            BillingInvoice b = invRows.get((Long) i.getArgument(0));
            if (b == null || !BillingInvoice.SENT.equals(b.getStatus()) || !i.getArgument(1).equals(b.getAccessTokenHash())) return 0;
            b.setStatus(BillingInvoice.DRAFT); b.setVersion(b.getVersion() + 1); b.setAccessTokenHash(null);
            b.setAccessTokenExpires(null); b.setIssueDate(null); b.setSentAt(null); b.setSentBy(null); b.setSendCount(null);
            return 1;
        });
        when(invoices.rotateToken(anyLong(), anyString(), anyString(), any(), anyInt(), any(), any())).thenAnswer(i -> {
            BillingInvoice b = invRows.get((Long) i.getArgument(0));
            if (b == null || !BillingInvoice.SENT.equals(b.getStatus()) || !i.getArgument(1).equals(b.getAccessTokenHash())) return 0;
            b.setAccessTokenHash(i.getArgument(2)); b.setAccessTokenExpires(i.getArgument(3)); b.setVersion(b.getVersion() + 1);
            b.setSendCount((b.getSendCount() == null ? 0 : b.getSendCount()) + (Integer) i.getArgument(4));
            b.setSentAt(i.getArgument(5)); b.setSentBy(i.getArgument(6));
            return 1;
        });
        when(invoices.markPaid(anyLong(), any(), anyString(), any(), any(), any())).thenAnswer(i -> {
            BillingInvoice b = invRows.get((Long) i.getArgument(0));
            if (b == null || !BillingInvoice.SENT.equals(b.getStatus())) return 0;
            b.setStatus(BillingInvoice.PAID); b.setVersion(b.getVersion() + 1); b.setPaidDate(i.getArgument(1));
            b.setPaymentMethod(i.getArgument(2)); b.setPaymentReference(i.getArgument(3)); b.setPaidRecordedBy(i.getArgument(4));
            return 1;
        });
        when(invoices.markVoid(anyLong(), any(), any(), any())).thenAnswer(i -> {
            BillingInvoice b = invRows.get((Long) i.getArgument(0));
            if (b == null || !BillingService.OPEN.contains(b.getStatus())) return 0;
            b.setStatus(BillingInvoice.VOID); b.setVersion(b.getVersion() + 1); b.setAccessTokenHash(null);
            b.setAccessTokenExpires(null); b.setVoidReason(i.getArgument(1)); b.setVoidedBy(i.getArgument(2));
            return 1;
        });

        // ── lines ──
        when(lines.save(any(BillingInvoiceLine.class))).thenAnswer(i -> {
            BillingInvoiceLine l = i.getArgument(0);
            if (l.getId() == null) l.setId(ids.incrementAndGet());
            lineRows.put(l.getId(), l);
            return l;
        });
        when(lines.findByInvoiceIdOrderBySortOrderAscIdAsc(anyLong())).thenAnswer(i -> lineRows.values().stream()
                .filter(l -> l.getInvoiceId().equals(i.getArgument(0)))
                .sorted(Comparator.comparing((BillingInvoiceLine l) -> l.getSortOrder()).thenComparing(BillingInvoiceLine::getId)).toList());
        doAnswer(i -> { for (Object o : (Iterable<?>) i.getArgument(0)) lineRows.remove(((BillingInvoiceLine) o).getId()); return null; })
                .when(lines).deleteAll(anyIterable());

        // ── charges ──
        when(charges.save(any(ClientCharge.class))).thenAnswer(i -> {
            ClientCharge c = i.getArgument(0);
            if (c.getId() == null) c.setId(ids.incrementAndGet());
            if (c.getCreatedAt() == null) c.setCreatedAt(LocalDateTime.now());
            chargeRows.put(c.getId(), c);
            return c;
        });
        when(charges.saveAndFlush(any(ClientCharge.class))).thenAnswer(i -> charges.save(i.getArgument(0)));
        when(charges.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(chargeRows.get((Long) i.getArgument(0))));
        when(charges.findByClientIdAndStatusAndInvoiceIdIsNullAndBillingPeriodLessThanEqualOrderByCreatedAtAscIdAsc(anyString(), anyString(), anyString()))
                .thenAnswer(i -> chargeRows.values().stream().filter(c -> c.getClientId().equals(i.getArgument(0))
                        && c.getStatus().equals(i.getArgument(1)) && c.getInvoiceId() == null
                        && c.getBillingPeriod().compareTo(i.getArgument(2)) <= 0).toList());
        // Conditional updates, exactly as the JPQL: the row decides, not the caller's copy.
        when(charges.claim(anyLong(), anyLong())).thenAnswer(i -> {
            ClientCharge c = chargeRows.get((Long) i.getArgument(0));
            if (c == null || c.getInvoiceId() != null || !ClientCharge.UNBILLED.equals(c.getStatus())) return 0;
            c.setInvoiceId(i.getArgument(1)); return 1;
        });
        when(charges.release(anyLong(), anyLong())).thenAnswer(i -> {
            ClientCharge c = chargeRows.get((Long) i.getArgument(0));
            if (c == null || !i.getArgument(1).equals(c.getInvoiceId()) || !ClientCharge.UNBILLED.equals(c.getStatus())) return 0;
            c.setInvoiceId(null); return 1;
        });
        when(charges.billForInvoice(anyLong(), any(), any())).thenAnswer(i -> bulk(i.getArgument(0), List.of(ClientCharge.UNBILLED),
                c -> c.setStatus(ClientCharge.BILLED)));
        when(charges.unbillForInvoice(anyLong(), any(), any())).thenAnswer(i -> bulk(i.getArgument(0), List.of(ClientCharge.BILLED),
                c -> c.setStatus(ClientCharge.UNBILLED)));
        when(charges.payForInvoice(anyLong(), any(), anyString(), any(), any())).thenAnswer(i -> bulk(i.getArgument(0),
                List.of(ClientCharge.UNBILLED, ClientCharge.BILLED), c -> { c.setStatus(ClientCharge.PAID);
                    c.setPaidDate(i.getArgument(1)); c.setPaymentMethod(i.getArgument(2)); }));
        when(charges.releaseForInvoice(anyLong(), any(), any())).thenAnswer(i -> bulk(i.getArgument(0),
                List.of(ClientCharge.UNBILLED, ClientCharge.BILLED), c -> { c.setStatus(ClientCharge.UNBILLED); c.setInvoiceId(null); }));
        when(charges.voidFree(anyLong(), any(), any())).thenAnswer(i -> {
            ClientCharge c = chargeRows.get((Long) i.getArgument(0));
            if (c == null || c.getInvoiceId() != null || !ClientCharge.UNBILLED.equals(c.getStatus())) return 0;
            c.setStatus(ClientCharge.VOID); return 1;
        });
        when(charges.payFree(anyLong(), any(), anyString(), any(), any())).thenAnswer(i -> {
            ClientCharge c = chargeRows.get((Long) i.getArgument(0));
            if (c == null || c.getInvoiceId() != null || !ClientCharge.UNBILLED.equals(c.getStatus())) return 0;
            c.setStatus(ClientCharge.PAID); c.setPaidDate(i.getArgument(1)); c.setPaymentMethod(i.getArgument(2)); return 1;
        });
        when(charges.findByClientIdAndStatusAndInvoiceIdIsNullOrderByCreatedAtAscIdAsc(anyString(), anyString())).thenAnswer(i ->
                chargeRows.values().stream().filter(c -> c.getClientId().equals(i.getArgument(0)) && c.getStatus().equals(i.getArgument(1))
                        && c.getInvoiceId() == null).toList());
        when(charges.findByInvoiceId(anyLong())).thenAnswer(i -> chargeRows.values().stream()
                .filter(c -> i.getArgument(0).equals(c.getInvoiceId())).toList());
        when(charges.findTop500ByOrderByCreatedAtDescIdDesc()).thenAnswer(i -> new ArrayList<>(chargeRows.values()));

        // ── clients ──
        when(clients.findByClientId(anyString())).thenAnswer(i -> Optional.ofNullable(clientRows.get((String) i.getArgument(0))));
        when(clients.findAllByDeleteFlagFalseOrderByIdDesc()).thenAnswer(i -> new ArrayList<>(clientRows.values()));
        when(clients.findByEndDateBetweenAndStatusAndDeleteFlagFalseOrderByEndDateAscIdAsc(any(), any(), anyString())).thenAnswer(i -> {
            LocalDate from = i.getArgument(0), to = i.getArgument(1);
            return clientRows.values().stream().filter(c -> c.getEndDate() != null && !c.getEndDate().isBefore(from)
                    && !c.getEndDate().isAfter(to) && i.getArgument(2).equals(c.getStatus()) && !Boolean.TRUE.equals(c.getDeleteFlag())).toList();
        });

        doAnswer(i -> {
            if (mailFails) throw new RuntimeException("SMTP down");
            List<String> to = i.getArgument(0);
            mails.add(new String[]{ to.get(0), i.getArgument(2), i.getArgument(3), i.getArgument(5) });
            return null;
        }).when(email).sendComposed(anyList(), any(), anyString(), anyString(), any(), anyString());

        SubscriptionLifecycleService lifecycle = new SubscriptionLifecycleService(plans, mock(SubscriptionChangeRepository.class));
        billing = new BillingService(invoices, lines, charges, clients, requests, lifecycle, email, settings);
        billing.setBaseUrl("https://app.test/");

        paid = client(1, "CHR-1", "STANDARD", "40.00", TODAY.plusDays(10));
        paid.setPriceOverridden(true);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    int bulk(Long invoiceId, List<String> from, java.util.function.Consumer<ClientCharge> change) {
        int n = 0;
        for (ClientCharge c : chargeRows.values()) {
            if (invoiceId.equals(c.getInvoiceId()) && from.contains(c.getStatus())) { change.accept(c); n++; }
        }
        return n;
    }

    ClientCharge chargeFor(String cid, String desc, String amount, String period) {
        return billing.createCharge(new BillingService.ChargeForm(cid, desc, amount, period, null), "admin");
    }

    /** A charge for the billing month of CHR-1's next billing date (so a renewal picks it up). */
    ClientCharge charge(String cid, String desc, String amount) {
        return billing.createCharge(new BillingService.ChargeForm(cid, desc, amount,
                java.time.YearMonth.from(TODAY.plusDays(10)).toString(), null), "admin");
    }

    static String tokenOf(String link) { return link.substring(link.indexOf("?t=") + 3); }

    List<BillingInvoiceLine> linesOf(Long invoiceId) { return lines.findByInvoiceIdOrderBySortOrderAscIdAsc(invoiceId); }

    BillingService.DraftForm form(BillingInvoice inv, String discount, List<BillingService.LineForm> ls) {
        return new BillingService.DraftForm(inv.getVersion(), inv.getBillToName(), inv.getBillToEmail(), inv.getDueDate(),
                null, discount, ls);
    }

    List<BillingService.LineForm> currentLines(Long invoiceId) {
        return linesOf(invoiceId).stream().map(l -> new BillingService.LineForm(l.getKind(), l.getDescription(),
                l.getAmount().toPlainString(), l.getChargeId())).collect(Collectors.toCollection(ArrayList::new));
    }

}
