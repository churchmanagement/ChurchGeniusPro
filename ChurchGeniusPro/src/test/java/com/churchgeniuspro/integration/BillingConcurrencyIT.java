package com.churchgeniuspro.integration;

import com.churchgeniuspro.hibernate.BillingInvoice;
import com.churchgeniuspro.hibernate.BillingInvoiceLine;
import com.churchgeniuspro.hibernate.ClientCharge;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.repository.BillingInvoiceLineRepository;
import com.churchgeniuspro.repository.BillingInvoiceRepository;
import com.churchgeniuspro.repository.ClientChargeRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.service.BillingService;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.util.AppClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * Platform billing against real PostgreSQL: the guarantees that only a database can
 * give. Concurrent drafts cannot share a charge (conditional claim under the row lock);
 * the send transaction rolls back as one; a failed email is compensated in one
 * transaction; V11 (re-applied by BillingSchemaInitializer after Hibernate) allows one
 * live renewal per client per billing date, whatever the due date, and lets manual and
 * subscription-request invoices share it. Requires Docker; runs on `verify`.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
                properties = "app.base-url=http://localhost")   // the "it" profile does not set it
class BillingConcurrencyIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired BillingService billing;
    @Autowired ClientChargeRepository charges;
    @Autowired BillingInvoiceRepository invoices;
    @Autowired BillingInvoiceLineRepository lines;
    @Autowired ServiceClientRepository clients;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @MockitoBean EmailService email;

    static final LocalDate TODAY = AppClock.today();

    @BeforeEach
    void mailWorks() throws Exception {
        reset(email);
        doNothing().when(email).sendComposed(anyList(), any(), anyString(), anyString(), any(), anyString());
    }

    String newClient() {
        String cid = "IT-BILL-" + UUID.randomUUID().toString().substring(0, 8);
        ServiceClient c = new ServiceClient();
        c.setClientId(cid); c.setChurchName("Church " + cid); c.setName("Pat Pastor"); c.setEmail(cid.toLowerCase() + "@church.test");
        c.setSubscriptionType("STANDARD"); c.setStatus("Active"); c.setDeleteFlag(false); c.setApproved(true);
        c.setPaymentStatus("PAID"); c.setBillingFrequency("MONTHLY"); c.setSubscriptionPrice(new BigDecimal("40.00"));
        c.setStartDate(TODAY.minusMonths(1)); c.setEndDate(TODAY.plusDays(10)); c.setCreatedDate(LocalDateTime.now());
        clients.saveAndFlush(c);
        return cid;
    }

    ClientCharge charge(String cid, String amount) {
        return billing.createCharge(new BillingService.ChargeForm(cid, "Setup", amount,
                YearMonth.from(TODAY.plusDays(10)).toString(), null), "it");
    }

    List<Long> invoicesHolding(Long chargeId) {
        return jdbc.queryForList("SELECT DISTINCT l.invoice_id FROM billing_invoice_line l JOIN billing_invoice i ON i.id = l.invoice_id "
                + "WHERE l.charge_id = ? AND i.status <> 'VOID'", Long.class, chargeId);
    }

    // ── B ──────────────────────────────────────────────────────────────────

    @Test
    void secondClaimOfOneChargeWaitsForTheFirstAndThenLoses() throws Exception {
        String cid = newClient();
        Long chargeId = charge(cid, "20").getId();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        CountDownLatch firstClaimed = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Integer> first = pool.submit(() -> tx.execute(s -> {
            int n = charges.claim(chargeId, 9_000_001L);
            firstClaimed.countDown();
            sleep(700);                       // hold the row lock while the second claim arrives
            return n;
        }));
        firstClaimed.await();
        long t0 = System.nanoTime();
        Future<Integer> second = pool.submit(() -> tx.execute(s -> charges.claim(chargeId, 9_000_002L)));
        int a = first.get(10, TimeUnit.SECONDS), b = second.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(a).isEqualTo(1);
        assertThat(b).isZero();
        assertThat((System.nanoTime() - t0) / 1_000_000).as("second claim waited for the first").isGreaterThan(300);
        assertThat(charges.findById(chargeId).orElseThrow().getInvoiceId()).isEqualTo(9_000_001L);
    }

    @Test
    void adminAndReminderJobCreatingDraftsAtTheSameMomentNeverShareACharge() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (int round = 0; round < 20; round++) {
            String cid = newClient();
            Long chargeId = charge(cid, "20").getId();
            CyclicBarrier go = new CyclicBarrier(2);
            Future<Long> manual = pool.submit(() -> { go.await(); return billing.createManualDraft(cid, "admin").invoice().getId(); });
            Future<Long> renewal = pool.submit(() -> { go.await(); return billing.createRenewalDraft(cid, "system (billing reminders)").invoice().getId(); });
            Long m = manual.get(20, TimeUnit.SECONDS), r = renewal.get(20, TimeUnit.SECONDS);
            List<Long> holders = invoicesHolding(chargeId);
            assertThat(holders).as("round " + round).hasSize(1);
            assertThat(charges.findById(chargeId).orElseThrow().getInvoiceId()).isEqualTo(holders.get(0)).isIn(m, r);
        }
        pool.shutdown();
    }

    @Test
    void twoSimultaneousRenewalRequestsGiveOneInvoice() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (int round = 0; round < 10; round++) {
            String cid = newClient();
            CyclicBarrier go = new CyclicBarrier(2);
            Future<BillingService.Draft> a = pool.submit(() -> { go.await(); return billing.createRenewalDraft(cid, "admin"); });
            Future<BillingService.Draft> b = pool.submit(() -> { go.await(); return billing.createRenewalDraft(cid, "job"); });
            BillingService.Draft da = a.get(20, TimeUnit.SECONDS), db = b.get(20, TimeUnit.SECONDS);
            assertThat(da.invoice().getId()).isEqualTo(db.invoice().getId());
            assertThat(da.created() ^ db.created()).as("exactly one created").isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_invoice WHERE client_id = ?", Integer.class, cid)).isEqualTo(1);
        }
        pool.shutdown();
    }

    // ── A ──────────────────────────────────────────────────────────────────

    @Test
    void sendIsOneTransactionAndAFailedEmailIsUndoneInOne() throws Exception {
        String cid = newClient();
        Long chargeId = charge(cid, "20").getId();
        BillingInvoice inv = billing.createRenewalDraft(cid, "it").invoice();

        // A charge reserved by the invoice with no line: the billed count cannot match →
        // the whole send transaction rolls back, including DRAFT → SENT.
        jdbc.update("INSERT INTO client_charge (client_id, description, amount, billing_period, status, invoice_id, version, created_at) "
                + "VALUES (?, 'stray', 1.00, ?, 'UNBILLED', ?, 0, now())", cid, YearMonth.from(TODAY).toString(), inv.getId());
        assertThatThrownBy(() -> billing.send(inv.getId(), null, "it")).hasMessageContaining("charges changed");
        assertThat(status(inv.getId())).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT access_token_hash FROM billing_invoice WHERE id = ?", String.class, inv.getId())).isNull();
        assertThat(jdbc.queryForList("SELECT status FROM client_charge WHERE invoice_id = ?", String.class, inv.getId())).containsOnly("UNBILLED");
        jdbc.update("DELETE FROM client_charge WHERE description = 'stray' AND invoice_id = ?", inv.getId());

        // Email fails → one compensating transaction: DRAFT again, charge UNBILLED and still reserved.
        doThrow(new RuntimeException("SMTP down")).when(email).sendComposed(anyList(), any(), anyString(), anyString(), any(), anyString());
        assertThatThrownBy(() -> billing.send(inv.getId(), null, "it")).hasMessageContaining("still a draft");
        assertThat(status(inv.getId())).isEqualTo("DRAFT");
        assertThat(chargeRow(chargeId)).isEqualTo("UNBILLED/" + inv.getId());

        // Email works → SENT and BILLED.
        reset(email);
        doNothing().when(email).sendComposed(anyList(), any(), anyString(), anyString(), any(), anyString());
        String link = billing.send(inv.getId(), null, "it").link();
        assertThat(status(inv.getId())).isEqualTo("SENT");
        assertThat(chargeRow(chargeId)).isEqualTo("BILLED/" + inv.getId());
        assertThat(jdbc.queryForObject("SELECT access_token_expires FROM billing_invoice WHERE id = ?", LocalDate.class, inv.getId()))
                .isEqualTo(inv.getDueDate().plusDays(60));

        // Void → link dead, charge free again (once).
        billing.voidInvoice(inv.getId(), null, "it");
        assertThat(billing.publicView(link.substring(link.indexOf("?t=") + 3), null)).isEmpty();
        assertThat(chargeRow(chargeId)).isEqualTo("UNBILLED/null");
        assertThatThrownBy(() -> billing.voidInvoice(inv.getId(), null, "it")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markPaidPaysTheInvoiceAndItsChargesTogether() {
        String cid = newClient();
        Long chargeId = charge(cid, "20").getId();
        BillingInvoice inv = billing.createRenewalDraft(cid, "it").invoice();
        billing.send(inv.getId(), null, "it");
        billing.markPaid(inv.getId(), TODAY, "CHECK", "1001", "it");
        assertThat(status(inv.getId())).isEqualTo("PAID");
        assertThat(chargeRow(chargeId)).isEqualTo("PAID/" + inv.getId());
        assertThat(clients.findByClientId(cid).orElseThrow().getEndDate()).isEqualTo(TODAY.plusDays(10));   // not renewed
    }

    // ── C / V11 ────────────────────────────────────────────────────────────

    @Test
    void renewalIsIdentifiedByBillingDateAndV11EnforcesIt() {
        String indexDef = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'ux_billing_invoice_open_renewal'", String.class);
        assertThat(indexDef).contains("(client_id, period_start)").contains("RENEWAL");

        String cid = newClient();
        BillingInvoice inv = billing.createRenewalDraft(cid, "admin").invoice();
        LocalDate billingDate = inv.getPeriodStart();
        List<BillingService.LineForm> ls = new ArrayList<>();
        for (BillingInvoiceLine l : lines.findByInvoiceIdOrderBySortOrderAscIdAsc(inv.getId())) {
            ls.add(new BillingService.LineForm(l.getKind(), l.getDescription(), l.getAmount().toPlainString(), l.getChargeId()));
        }
        BillingInvoice cur = invoices.findById(inv.getId()).orElseThrow();
        billing.updateDraft(inv.getId(), new BillingService.DraftForm(cur.getVersion(), null, cur.getBillToEmail(),
                billingDate.plusDays(14), null, "0", ls), "admin");                                     // due date changed
        BillingService.Draft again = billing.createRenewalDraft(cid, "admin");
        assertThat(again.created()).isFalse();
        assertThat(again.invoice().getId()).isEqualTo(inv.getId());

        // Database: a second live renewal for the same billing date is refused whatever its due date…
        assertThatThrownBy(() -> insertInvoice(cid, "RENEWAL", "DRAFT", billingDate, billingDate.plusDays(30)))
                .isInstanceOf(DuplicateKeyException.class);
        // …manual and subscription-request invoices may share the date, and a VOID renewal does not count.
        insertInvoice(cid, "MANUAL", "SENT", billingDate, billingDate);
        insertInvoice(cid, "REQUEST", "DRAFT", billingDate, billingDate);
        insertInvoice(cid, "RENEWAL", "VOID", billingDate, billingDate);
        insertInvoice(cid, "RENEWAL", "DRAFT", billingDate.plusMonths(1), billingDate);   // another billing date
    }

    @Test
    void renewalTakesOnlyChargesOfItsBillingMonthOrEarlier() {
        String cid = newClient();
        YearMonth month = YearMonth.from(TODAY.plusDays(10));
        Long same = billing.createCharge(new BillingService.ChargeForm(cid, "This month", "10", month.toString(), null), "it").getId();
        Long earlier = billing.createCharge(new BillingService.ChargeForm(cid, "Earlier", "5", month.minusMonths(2).toString(), null), "it").getId();
        Long later = billing.createCharge(new BillingService.ChargeForm(cid, "Later", "99", month.plusMonths(1).toString(), null), "it").getId();
        BillingInvoice inv = billing.createRenewalDraft(cid, "it").invoice();
        assertThat(lines.findByInvoiceIdOrderBySortOrderAscIdAsc(inv.getId())).extracting(BillingInvoiceLine::getChargeId)
                .containsExactlyInAnyOrder(null, same, earlier);
        assertThat(charges.findById(later).orElseThrow().getInvoiceId()).isNull();
        assertThat(invoices.findById(inv.getId()).orElseThrow().getTotal()).isEqualByComparingTo("55.00");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void insertInvoice(String cid, String kind, String status, LocalDate periodStart, LocalDate due) {
        jdbc.update("INSERT INTO billing_invoice (client_id, kind, status, period_start, due_date, subtotal, discount, total, version, created_at) "
                + "VALUES (?, ?, ?, ?, ?, 0, 0, 0, 0, now())", cid, kind, status, periodStart, due);
    }

    private String status(Long id) {
        return jdbc.queryForObject("SELECT status FROM billing_invoice WHERE id = ?", String.class, id);
    }

    private String chargeRow(Long id) {
        return jdbc.queryForObject("SELECT status || '/' || COALESCE(CAST(invoice_id AS varchar), 'null') FROM client_charge WHERE id = ?",
                String.class, id);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
