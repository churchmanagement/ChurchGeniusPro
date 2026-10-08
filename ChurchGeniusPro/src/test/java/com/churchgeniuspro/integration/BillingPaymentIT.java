package com.churchgeniuspro.integration;

import com.churchgeniuspro.hibernate.BillingInvoice;
import com.churchgeniuspro.hibernate.BillingPaymentEvent;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.repository.BillingInvoiceRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.service.*;
import com.churchgeniuspro.util.AppClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.reset;

/**
 * Phase 6 card payments against real PostgreSQL, with Stripe replaced by an in-memory fake:
 * V12 (one invoice per PaymentIntent), webhook de-duplication by event id, and the browser
 * confirm racing the webhook in truly concurrent transactions — the payment is applied
 * once, the charges are paid once, and exactly one receipt goes out. Requires Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
                properties = "app.base-url=http://localhost")   // the "it" profile does not set it
class BillingPaymentIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired BillingService billing;
    @Autowired BillingPaymentService payments;
    @Autowired PlatformStripeService stripe;
    @Autowired BillingInvoiceRepository invoices;
    @Autowired ServiceClientRepository clients;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean EmailService email;
    FakeStripeHttp fake;

    @BeforeEach
    void wire() throws Exception {
        reset(email);
        doNothing().when(email).sendComposed(anyList(), any(), anyString(), anyString(), any(), anyString());
        fake = new FakeStripeHttp();
        stripe.setHttp(fake);
        stripe.setKeys(FakeStripeHttp.SECRET, FakeStripeHttp.PUBLISH, FakeStripeHttp.WEBHOOK);
    }

    record Sent(Long invoiceId, String number, String token, Long chargeId) {}

    Sent sentInvoice() {
        String cid = "IT-PAY-" + UUID.randomUUID().toString().substring(0, 8);
        ServiceClient c = new ServiceClient();
        c.setClientId(cid); c.setChurchName("Church " + cid); c.setName("Pat"); c.setEmail(cid.toLowerCase() + "@church.test");
        c.setSubscriptionType("STANDARD"); c.setStatus("Active"); c.setDeleteFlag(false); c.setApproved(true);
        c.setPaymentStatus("PAID"); c.setBillingFrequency("MONTHLY"); c.setSubscriptionPrice(new BigDecimal("40.00"));
        c.setStartDate(AppClock.today().minusMonths(1)); c.setEndDate(AppClock.today().plusDays(10)); c.setCreatedDate(LocalDateTime.now());
        clients.saveAndFlush(c);
        Long chargeId = billing.createCharge(new BillingService.ChargeForm(cid, "Setup", "20",
                YearMonth.from(AppClock.today().plusDays(10)).toString(), null), "it").getId();
        BillingInvoice inv = billing.createRenewalDraft(cid, "it").invoice();
        String link = billing.send(inv.getId(), null, "it").link();
        return new Sent(inv.getId(), inv.getInvoiceNumber(), link.substring(link.indexOf("?t=") + 3), chargeId);
    }

    long receiptsFor(String number) {
        return Mockito.mockingDetails(email).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("sendComposed"))
                .filter(i -> String.valueOf((Object) i.getArgument(2)).equals("Receipt for invoice " + number + " — ChurchGeniusPro")).count();
    }

    @Test
    void v12IndexExistsAndAPaymentIntentBelongsToOneInvoice() {
        String def = jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'ux_billing_invoice_payment_intent'", String.class);
        assertThat(def).contains("(stripe_payment_intent_id)").contains("IS NOT NULL");
        Sent a = sentInvoice(), b = sentInvoice();
        payments.startPayment(a.token(), null);
        String pi = invoices.findById(a.invoiceId()).orElseThrow().getStripePaymentIntentId();
        assertThatThrownBy(() -> jdbc.update("UPDATE billing_invoice SET stripe_payment_intent_id = ? WHERE id = ?", pi, b.invoiceId()))
                .isInstanceOf(DuplicateKeyException.class);
        jdbc.update("UPDATE billing_invoice SET stripe_payment_intent_id = NULL WHERE id = ?", b.invoiceId());   // many NULLs are fine
    }

    @Test
    void confirmAndWebhookAtTheSameMomentApplyThePaymentOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (int round = 0; round < 15; round++) {
            Sent s = sentInvoice();
            payments.startPayment(s.token(), null);
            String pi = invoices.findById(s.invoiceId()).orElseThrow().getStripePaymentIntentId();
            String[] w = FakeStripeHttp.webhook("evt_race_" + round + "_" + s.invoiceId(), fake.succeed(pi),
                    Instant.now().getEpochSecond(), FakeStripeHttp.WEBHOOK, false);
            CyclicBarrier go = new CyclicBarrier(2);
            Future<Object> confirm = pool.submit(() -> { go.await(); return payments.confirm(s.token(), null).get("state"); });
            Future<String> hook = pool.submit(() -> { go.await(); return payments.handleWebhook(w[0], w[1]); });
            assertThat(confirm.get(20, TimeUnit.SECONDS)).isEqualTo("PAID");
            String hookOutcome = hook.get(20, TimeUnit.SECONDS);
            List<String> outcomes = jdbc.queryForList("SELECT outcome FROM billing_payment_event WHERE invoice_id = ? ORDER BY id", String.class, s.invoiceId());
            assertThat(outcomes).as("round " + round).containsExactlyInAnyOrder(BillingPaymentEvent.RECORDED, BillingPaymentEvent.ALREADY_RECORDED);
            assertThat(hookOutcome).isIn(BillingPaymentEvent.RECORDED, BillingPaymentEvent.ALREADY_RECORDED);
            assertThat(jdbc.queryForObject("SELECT status || '/' || payment_method || '/' || payment_reference FROM billing_invoice WHERE id = ?",
                    String.class, s.invoiceId())).isEqualTo("PAID/CARD/" + pi);
            assertThat(jdbc.queryForObject("SELECT status FROM client_charge WHERE id = ?", String.class, s.chargeId())).isEqualTo("PAID");
            assertThat(receiptsFor(s.number())).as("round " + round + " receipts").isEqualTo(1);
        }
        pool.shutdown();
    }

    @Test
    void theSameWebhookDeliveredTwiceAtOnceIsAppliedOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Sent s = sentInvoice();
        payments.startPayment(s.token(), null);
        String pi = invoices.findById(s.invoiceId()).orElseThrow().getStripePaymentIntentId();
        String eventId = "evt_dup_" + s.invoiceId();
        String[] w = FakeStripeHttp.webhook(eventId, fake.succeed(pi), Instant.now().getEpochSecond(), FakeStripeHttp.WEBHOOK, false);
        CyclicBarrier go = new CyclicBarrier(2);
        Future<String> a = pool.submit(() -> { go.await(); return payments.handleWebhook(w[0], w[1]); });
        Future<String> b = pool.submit(() -> { go.await(); return payments.handleWebhook(w[0], w[1]); });
        List<String> results = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(results).containsExactlyInAnyOrder(BillingPaymentEvent.RECORDED, "duplicate");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_payment_event WHERE stripe_event_id = ?", Integer.class, eventId)).isEqualTo(1);
        assertThat(receiptsFor(s.number())).isEqualTo(1);
    }

    @Test
    void moneyForAVoidedInvoiceIsNotApplied() throws Exception {
        Sent s = sentInvoice();
        payments.startPayment(s.token(), null);
        String pi = invoices.findById(s.invoiceId()).orElseThrow().getStripePaymentIntentId();
        billing.voidInvoice(s.invoiceId(), "changed", "it");
        payments.afterVoid(s.invoiceId());
        assertThat(fake.intents.get(pi).path("status").asText()).isEqualTo("canceled");
        fake.setStatus(pi, "requires_payment_method");
        String[] w = FakeStripeHttp.webhook("evt_void_" + s.invoiceId(), fake.succeed(pi), Instant.now().getEpochSecond(), FakeStripeHttp.WEBHOOK, false);
        assertThat(payments.handleWebhook(w[0], w[1])).isEqualTo(BillingPaymentEvent.ALERT_VOID);
        assertThat(jdbc.queryForObject("SELECT status FROM billing_invoice WHERE id = ?", String.class, s.invoiceId())).isEqualTo("VOID");
        assertThat(jdbc.queryForObject("SELECT status || '/' || COALESCE(CAST(invoice_id AS varchar),'null') FROM client_charge WHERE id = ?",
                String.class, s.chargeId())).isEqualTo("UNBILLED/null");
    }
}
