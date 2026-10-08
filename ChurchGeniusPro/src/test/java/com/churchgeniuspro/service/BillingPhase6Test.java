package com.churchgeniuspro.service;

import com.churchgeniuspro.controller.InvoicePaymentController;
import com.churchgeniuspro.controller.ServiceAdminStripeController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.logging.SensitiveDataMasker;
import com.churchgeniuspro.repository.BillingPaymentEventRepository;
import com.churchgeniuspro.util.AppClock;
import com.churchgeniuspro.util.PublicFormGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 6: card payment of platform invoices through the Service Admin's own Stripe
 * account. Stripe is the in-memory {@link FakeStripeHttp}; nothing reaches Stripe.
 */
@DisplayName("Phase 6 — Service Admin Stripe (card payments)")
class BillingPhase6Test extends BillingTestBase {

    final List<BillingPaymentEvent> eventRows = new ArrayList<>();
    final List<Map<String, Object>> outbox = new ArrayList<>();   // to, bcc, subject, html
    final AtomicLong eventIds = new AtomicLong(5000);
    BillingPaymentEventRepository events;
    FakeStripeHttp fake;
    PlatformStripeService stripe;
    BillingPaymentService payments;
    BillingInvoice inv;
    String token;
    ClientCharge charge;

    @BeforeEach
    void phase6() throws Exception {
        events = mock(BillingPaymentEventRepository.class);
        org.mockito.stubbing.Answer<BillingPaymentEvent> saveEv = i -> {
            BillingPaymentEvent e = i.getArgument(0);
            if (e.getStripeEventId() != null && eventRows.stream().anyMatch(o -> o != e && e.getStripeEventId().equals(o.getStripeEventId()))) {
                throw new DataIntegrityViolationException("uq_billing_payment_event_stripe_event");
            }
            if (e.getId() == null) { e.setId(eventIds.incrementAndGet()); eventRows.add(e); }
            return e;
        };
        when(events.saveAndFlush(any(BillingPaymentEvent.class))).thenAnswer(saveEv);
        when(events.save(any(BillingPaymentEvent.class))).thenAnswer(saveEv);
        when(events.existsByStripeEventId(anyString())).thenAnswer(i -> eventRows.stream().anyMatch(e -> i.getArgument(0).equals(e.getStripeEventId())));
        when(events.findByInvoiceIdOrderByCreatedAtDescIdDesc(anyLong())).thenAnswer(i -> eventRows.stream()
                .filter(e -> i.getArgument(0).equals(e.getInvoiceId())).toList());

        when(invoices.attachFirstPaymentIntent(anyLong(), anyString())).thenAnswer(i -> {
            BillingInvoice b = invRows.get((Long) i.getArgument(0));
            if (b == null || !BillingInvoice.SENT.equals(b.getStatus()) || b.getStripePaymentIntentId() != null) return 0;
            b.setStripePaymentIntentId(i.getArgument(1)); return 1;
        });
        when(invoices.replacePaymentIntent(anyLong(), anyString(), anyString())).thenAnswer(i -> {
            BillingInvoice b = invRows.get((Long) i.getArgument(0));
            if (b == null || !BillingInvoice.SENT.equals(b.getStatus()) || !i.getArgument(1).equals(b.getStripePaymentIntentId())) return 0;
            b.setStripePaymentIntentId(i.getArgument(2)); return 1;
        });
        when(invoices.markReceiptSent(anyLong(), any())).thenAnswer(i -> {
            invRows.get((Long) i.getArgument(0)).setReceiptSentAt(i.getArgument(1)); return 1;
        });
        doAnswer(i -> {
            if (mailFails) throw new RuntimeException("SMTP down");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("to", i.getArgument(0)); m.put("bcc", i.getArgument(1)); m.put("subject", i.getArgument(2)); m.put("html", i.getArgument(3));
            outbox.add(m);
            List<String> to = i.getArgument(0);
            mails.add(new String[]{ to.get(0), i.getArgument(2), i.getArgument(3), i.getArgument(5) });
            return null;
        }).when(email).sendComposed(anyList(), any(), anyString(), anyString(), any(), anyString());

        fake = new FakeStripeHttp();
        stripe = new PlatformStripeService();
        stripe.setHttp(fake);
        stripe.setKeys(FakeStripeHttp.SECRET, FakeStripeHttp.PUBLISH, FakeStripeHttp.WEBHOOK);
        when(clients.setBillingStripeCustomerId(anyString(), anyString())).thenAnswer(i -> {
            ServiceClient c = clientRows.get((String) i.getArgument(0));
            if (c == null || c.getBillingStripeCustomerId() != null) return 0;
            c.setBillingStripeCustomerId(i.getArgument(1)); return 1;
        });
        payments = new BillingPaymentService(billing, invoices, charges, events, stripe, email, settings, clients);

        charge = charge("CHR-1", "Setup", "20");
        inv = billing.createRenewalDraft("CHR-1", "admin").invoice();   // 40.00 + 20.00 = 60.00
        token = tokenOf(billing.send(inv.getId(), null, "admin").link());
        outbox.clear(); mails.clear();
    }

    BillingInvoice now() { return invRows.get(inv.getId()); }
    long receipts() { return outbox.stream().filter(m -> String.valueOf(m.get("subject")).startsWith("Receipt for invoice")).count(); }
    long alerts() { return outbox.stream().filter(m -> String.valueOf(m.get("subject")).startsWith("Billing alert")).count(); }
    String startAndSucceed() {
        payments.startPayment(token, null);
        String pi = now().getStripePaymentIntentId();
        fake.succeed(pi);
        return pi;
    }
    String[] webhookFor(String eventId, String pi, long t) throws Exception {
        return FakeStripeHttp.webhook(eventId, fake.intents.get(pi), t, FakeStripeHttp.WEBHOOK, false);
    }
    static long nowSec() { return Instant.now().getEpochSecond(); }

    // ══ Keys, status, masking ═════════════════════════════════════════════

    @Nested @DisplayName("Keys from Azure settings: status, masking, read-only test")
    class Keys {
        @Test @DisplayName("status shows masked hints and mode only — never a full key")
        void statusMasked() throws Exception {
            Map<String, Object> st = stripe.status();
            assertThat(st).containsEntry("configured", true).containsEntry("mode", "test").containsEntry("webhookConfigured", true);
            assertThat(st.get("secretKeyHint")).isEqualTo("sk_test_…K1l2");
            assertThat(st.get("publishableKeyHint")).isEqualTo("pk_test_…P9o8");
            assertThat(st.get("webhookSecretHint")).isEqualTo("whsec_…6Bc7");
            String json = new ObjectMapper().writeValueAsString(st);
            assertThat(json).doesNotContain(FakeStripeHttp.SECRET).doesNotContain(FakeStripeHttp.PUBLISH).doesNotContain(FakeStripeHttp.WEBHOOK);
        }

        @Test @DisplayName("missing, malformed and mixed test/live keys make card payment unavailable")
        void problems() {
            stripe.setKeys(null, null, null);
            assertThat(stripe.isConfigured()).isFalse();
            assertThat(stripe.publishableKey()).isNull();
            assertThat(stripe.problems()).hasSize(2);
            stripe.setKeys("pk_test_abcdefgh1234", FakeStripeHttp.PUBLISH, null);
            assertThat(stripe.problems()).anyMatch(p -> p.contains("not a Stripe secret key"));
            stripe.setKeys("sk_live_abcdefgh12345678", FakeStripeHttp.PUBLISH, null);
            assertThat(stripe.problems()).anyMatch(p -> p.contains("different modes"));
            assertThat(stripe.webhookConfigured()).isFalse();
            assertThat(payments.payConfig(token, null)).containsEntry("available", false).doesNotContainKey("publishableKey");
            assertThatThrownBy(() -> payments.startPayment(token, null)).isInstanceOf(IllegalStateException.class);
            assertThat(fake.calls).isEmpty();
        }

        @Test @DisplayName("Test connection makes exactly one read-only call (GET /balance) and returns no balances")
        void testConnectionReadOnly() {
            Map<String, Object> r = stripe.testConnection();
            assertThat(r).containsEntry("ok", true).containsEntry("livemode", false);
            assertThat(r.toString()).doesNotContain("12345");
            assertThat(fake.calls).containsExactly("GET /balance");
        }

        @Test @DisplayName("a Stripe error that echoes a key is masked before it is shown or logged")
        void errorsMasked() {
            String other = "sk_test_" + "Q9w8E7r6T5y4U3i2O1p0Aa11";
            stripe.setKeys(other, FakeStripeHttp.PUBLISH, FakeStripeHttp.WEBHOOK);   // fake answers "Invalid API Key provided: <key>"
            Map<String, Object> r = stripe.testConnection();
            assertThat(r).containsEntry("ok", false);
            assertThat(String.valueOf(r.get("message"))).doesNotContain(other).doesNotContain("Q9w8E7r6");
            assertThatThrownBy(() -> stripe.retrieveIntent("pi_Abcdefgh12345678"))
                    .isInstanceOf(PlatformStripeService.StripeException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(other));
        }

        @Test @DisplayName("the log masker redacts every Stripe credential shape, including PaymentIntent client secrets")
        void masker() {
            String line = "sk=" + FakeStripeHttp.SECRET + " rk_live_ABCDEFGHijkl1234 pk=" + FakeStripeHttp.PUBLISH + " wh="
                    + FakeStripeHttp.WEBHOOK + " cs=pi_3AbCdEfGh1234567_secret_ZyXwVuTs98765432 id=pi_3AbCdEfGh1234567";
            String masked = SensitiveDataMasker.mask(line);
            assertThat(masked).doesNotContain(FakeStripeHttp.SECRET).doesNotContain("rk_live_ABCDEFGH").doesNotContain(FakeStripeHttp.PUBLISH)
                    .doesNotContain(FakeStripeHttp.WEBHOOK).doesNotContain("_secret_ZyXw")
                    .contains("id=pi_3AbCdEfGh1234567");   // a plain PaymentIntent id is not a secret
        }

        @Test @DisplayName("objects that carry a client secret never print it")
        void toStrings() {
            PlatformStripeService.Intent i = new PlatformStripeService.Intent("pi_x", "requires_payment_method", 100, 0, "usd",
                    Map.of(), false, "pi_x_secret_SHOULDNOTPRINT");
            assertThat(i.toString()).doesNotContain("SHOULDNOTPRINT");
            assertThat(new BillingPaymentService.Started("pi_x_secret_SHOULDNOTPRINT", "pk", 100).toString()).doesNotContain("SHOULDNOTPRINT");
        }
    }

    // ══ Starting a payment ═══════════════════════════════════════════════

    @Nested @DisplayName("PaymentIntent: amount, currency, invoice; payment methods managed in the Stripe Dashboard")
    class Start {
        @Test @DisplayName("one USD PaymentIntent for exactly the Amount Due, with the invoice in its metadata and an idempotency key — and NO payment_method_types (Stripe API 2026-09-30 refuses it)")
        void create() {
            BillingPaymentService.Started s = payments.startPayment(token, null);
            assertThat(s.amountCents()).isEqualTo(6000);
            assertThat(s.publishableKey()).isEqualTo(FakeStripeHttp.PUBLISH);
            assertThat(s.clientSecret()).startsWith(now().getStripePaymentIntentId() + "_secret_");
            Map<String, String> form = fake.createForms.get(0);
            assertThat(form).containsEntry("amount", "6000").containsEntry("currency", "usd")
                    .containsEntry("metadata[invoice_id]", String.valueOf(inv.getId())).containsEntry("metadata[purpose]", PlatformStripeService.PURPOSE);
            assertThat(form.keySet()).noneMatch(k -> k.contains("payment_method_types")).noneMatch(k -> k.contains("automatic_payment_methods"))
                    .noneMatch(k -> k.contains("excluded_payment_method_types"));
            assertThat(fake.idempotencyKeys.get(0)).matches("cgp-inv-" + inv.getId() + "-6000-first-[0-9a-f]{8}");
        }

        @Test @DisplayName("the idempotency key tracks the request body: same body ⇒ same key; a changed body (new code, excluded method) ⇒ a fresh key, never a 24-hour lockout")
        void idempotencyKeyTracksParameters() {
            payments.startPayment(token, null);
            String k1 = fake.idempotencyKeys.get(0);
            now().setStripePaymentIntentId(null);                 // the same create again (what a concurrent caller sends)
            payments.startPayment(token, null);
            assertThat(fake.idempotencyKeys.get(1)).isEqualTo(k1);
            assertThat(fake.intents).hasSize(1);                  // Stripe returned the same intent
            now().setStripePaymentIntentId(null);
            stripe.setExcludedMethods("affirm");                  // different body
            payments.startPayment(token, null);
            assertThat(fake.idempotencyKeys.get(2)).isNotEqualTo(k1).startsWith("cgp-inv-" + inv.getId() + "-6000-first-");
            assertThat(fake.intents).hasSize(2);
            // Stripe's idempotency_error is named in the (masked) message
            Map<String, String> f = new LinkedHashMap<>(); f.put("amount", "1"); f.put("currency", "usd");
            assertThat(PlatformStripeService.paramsHash(f)).hasSize(8).isEqualTo(PlatformStripeService.paramsHash(new LinkedHashMap<>(f)));
        }

        @Test @DisplayName("a Stripe Customer is created once per client (bank transfer needs one), stored on the client row and reused")
        void customer() {
            payments.startPayment(token, null);
            assertThat(fake.customerForms).hasSize(1);
            assertThat(fake.customerForms.get(0)).containsEntry("email", "chr-1@church.test").containsEntry("metadata[client_id]", "CHR-1")
                    .containsEntry("metadata[purpose]", PlatformStripeService.PURPOSE);
            String cus = clientRows.get("CHR-1").getBillingStripeCustomerId();
            assertThat(cus).startsWith("cus_");
            assertThat(fake.createForms.get(0)).containsEntry("customer", cus);
            // Second invoice for the same client: no new customer.
            BillingInvoice m = billing.createManualDraft("CHR-1", "a").invoice();
            billing.updateDraft(m.getId(), new BillingService.DraftForm(m.getVersion(), null, "pay@church.test", TODAY.plusDays(5), null, null,
                    List.of(new BillingService.LineForm("CUSTOM", "Training", "100", null))), "a");
            payments.startPayment(tokenOf(billing.send(m.getId(), null, "a").link()), null);
            assertThat(fake.customerForms).hasSize(1);
            assertThat(fake.createForms.get(1)).containsEntry("customer", cus);
            verify(clients, never()).save(any());
        }

        @Test @DisplayName("Stripe refusing the Customer (restricted key without Customers write) does not block the payment: intent created without one")
        void customerRefused() {
            fake.customerError = "This API key does not have the required permissions: rk_test_SHOULDNOTAPPEAR12345";
            BillingPaymentService.Started s = payments.startPayment(token, null);
            assertThat(s.amountCents()).isEqualTo(6000);
            assertThat(fake.createForms.get(0)).doesNotContainKey("customer");
            assertThat(clientRows.get("CHR-1").getBillingStripeCustomerId()).isNull();
        }

        @Test @DisplayName("PLATFORM_STRIPE_EXCLUDED_METHODS keeps Dashboard-enabled methods off platform invoices (indexed array params)")
        void excluded() {
            stripe.setExcludedMethods(" Affirm, klarna ,affirm,,bad type!");
            assertThat(stripe.excludedMethods()).containsExactly("affirm", "klarna");
            payments.startPayment(token, null);
            assertThat(fake.createForms.get(0)).containsEntry("excluded_payment_method_types[0]", "affirm")
                    .containsEntry("excluded_payment_method_types[1]", "klarna");
            assertThat(stripe.status().get("excludedMethods")).isEqualTo(List.of("affirm", "klarna"));
        }

        @Test @DisplayName("a stored PaymentIntent that no longer exists in Stripe (created under other keys) is replaced, not fatal")
        void staleIntentReplaced() {
            payments.startPayment(token, null);
            String stale = now().getStripePaymentIntentId();
            fake.intents.remove(stale);                      // Stripe answers 404 "No such payment_intent"
            BillingPaymentService.Started s = payments.startPayment(token, null);
            assertThat(now().getStripePaymentIntentId()).isNotEqualTo(stale);
            assertThat(s.clientSecret()).startsWith(now().getStripePaymentIntentId() + "_secret_");
            assertThat(fake.idempotencyKeys).anyMatch(k -> k.startsWith("cgp-inv-" + inv.getId() + "-6000-" + stale + "-"));
            // confirm on a stale intent tells the payer to start again rather than erroring
            now().setStripePaymentIntentId(stale);
            assertThat(payments.confirm(token, null)).containsEntry("state", "FAILED");
        }

        @Test @DisplayName("a second start reuses the open PaymentIntent; a discounted invoice charges the discounted total")
        void reuse() {
            payments.startPayment(token, null);
            payments.startPayment(token, null);
            assertThat(fake.createForms).hasSize(1);
            // A discounted invoice: the PaymentIntent equals Amount Due, never the subtotal.
            BillingInvoice m = billing.createManualDraft("CHR-1", "a").invoice();
            billing.updateDraft(m.getId(), new BillingService.DraftForm(m.getVersion(), null, "pay@church.test", TODAY.plusDays(5), null, "12.50",
                    List.of(new BillingService.LineForm("CUSTOM", "Training", "100", null))), "a");
            String t2 = tokenOf(billing.send(m.getId(), null, "a").link());
            assertThat(payments.startPayment(t2, null).amountCents()).isEqualTo(8750);
        }

        @Test @DisplayName("a cancelled PaymentIntent is replaced (new idempotency key), conditionally")
        void replaceCancelled() {
            payments.startPayment(token, null);
            String first = now().getStripePaymentIntentId();
            fake.setStatus(first, "canceled");
            payments.startPayment(token, null);
            assertThat(now().getStripePaymentIntentId()).isNotEqualTo(first);
            assertThat(fake.idempotencyKeys).anyMatch(k -> k.startsWith("cgp-inv-" + inv.getId() + "-6000-" + first + "-"));
        }

        @Test @DisplayName("concurrent starts end on one PaymentIntent (same idempotency key ⇒ same intent)")
        void concurrentStart() {
            // Caller 2 read the invoice before caller 1 attached its PaymentIntent.
            payments.startPayment(token, null);
            String attached = now().getStripePaymentIntentId();
            now().setStripePaymentIntentId(null);             // what caller 2 saw
            when(invoices.attachFirstPaymentIntent(anyLong(), anyString())).thenAnswer(i -> { now().setStripePaymentIntentId(attached); return 0; });
            BillingPaymentService.Started s2 = payments.startPayment(token, null);
            assertThat(s2.clientSecret()).startsWith(attached + "_secret_");
            assertThat(fake.intents).hasSize(1);
        }

        @Test @DisplayName("refused: bad token or another church's session (404-style), paid invoice, below Stripe's 50-cent minimum")
        void refusals() {
            assertThatThrownBy(() -> payments.startPayment("nope", null)).isInstanceOf(BillingPaymentService.NotFound.class);
            assertThatThrownBy(() -> payments.startPayment(token, "CHR-OTHER")).isInstanceOf(BillingPaymentService.NotFound.class);
            assertThatThrownBy(() -> payments.payConfig("nope", null)).isInstanceOf(BillingPaymentService.NotFound.class);
            BillingInvoice small = billing.createManualDraft("CHR-1", "a").invoice();
            billing.updateDraft(small.getId(), new BillingService.DraftForm(small.getVersion(), null, "a@b.co", TODAY, null, "0",
                    List.of(new BillingService.LineForm("CUSTOM", "Tiny", "0.49", null))), "a");
            String ts = tokenOf(billing.send(small.getId(), null, "a").link());
            assertThat(payments.payConfig(ts, null)).containsEntry("available", false);
            billing.markPaid(inv.getId(), TODAY, "CHECK", null, "a");
            assertThat(payments.payConfig(token, null)).containsEntry("available", false);
            assertThatThrownBy(() -> payments.startPayment(token, null)).hasMessageContaining("already paid");
        }
    }

    // ══ Recording ════════════════════════════════════════════════════════

    @Nested @DisplayName("Recording a card payment")
    class Record {
        @Test @DisplayName("confirm: re-reads Stripe, marks the invoice and its charges paid, one receipt to the billing email with a Support copy")
        void confirmPaid() {
            String pi = startAndSucceed();
            assertThat(payments.confirm(token, null)).containsEntry("state", "PAID");
            assertThat(now().getStatus()).isEqualTo(BillingInvoice.PAID);
            assertThat(now().getPaymentMethod()).isEqualTo("CARD");
            assertThat(now().getPaymentReference()).isEqualTo(pi);
            assertThat(now().getPaidDate()).isEqualTo(AppClock.today());
            assertThat(charge.getStatus()).isEqualTo(ClientCharge.PAID);
            assertThat(receipts()).isEqualTo(1);
            Map<String, Object> r = outbox.stream().filter(m -> String.valueOf(m.get("subject")).startsWith("Receipt")).findFirst().orElseThrow();
            assertThat(r.get("to")).isEqualTo(List.of("chr-1@church.test"));
            assertThat(r.get("bcc")).isEqualTo(List.of("support@cgp.test"));
            assertThat(String.valueOf(r.get("subject"))).doesNotContain("$");
            assertThat(String.valueOf(r.get("html"))).contains("$60.00").contains("Card").doesNotContain("_secret_");
            assertThat(now().getReceiptSentAt()).isNotNull();
            assertThat(eventRows).extracting(BillingPaymentEvent::getOutcome).containsExactly(BillingPaymentEvent.RECORDED);
            assertThat(paid.getEndDate()).isEqualTo(TODAY.plusDays(10));   // payment never renews the subscription
            verify(clients, never()).save(any());
        }

        @Test @DisplayName("a declined / unfinished card changes nothing")
        void notSucceeded() {
            payments.startPayment(token, null);
            assertThat(payments.confirm(token, null)).containsEntry("state", "FAILED");
            fake.setStatus(now().getStripePaymentIntentId(), "processing");
            assertThat(payments.confirm(token, null)).containsEntry("state", "PROCESSING");
            assertThat(now().getStatus()).isEqualTo(BillingInvoice.SENT);
            assertThat(charge.getStatus()).isEqualTo(ClientCharge.BILLED);
            assertThat(receipts()).isZero();
        }

        @Test @DisplayName("confirm then webhook, and webhook then confirm: recorded once, one receipt, no alert")
        void confirmWebhookRace() throws Exception {
            String pi = startAndSucceed();
            payments.confirm(token, null);
            String[] w = webhookFor("evt_A1", pi, nowSec());
            assertThat(payments.handleWebhook(w[0], w[1])).isEqualTo(BillingPaymentEvent.ALREADY_RECORDED);
            assertThat(receipts()).isEqualTo(1);
            assertThat(alerts()).isZero();

            // The other order, on a second invoice.
            BillingInvoice m = billing.createManualDraft("CHR-1", "a").invoice();
            billing.updateDraft(m.getId(), new BillingService.DraftForm(m.getVersion(), null, "pay@church.test", TODAY, null, "0",
                    List.of(new BillingService.LineForm("CUSTOM", "Training", "75", null))), "a");
            String t2 = tokenOf(billing.send(m.getId(), null, "a").link());
            payments.startPayment(t2, null);
            String pi2 = invRows.get(m.getId()).getStripePaymentIntentId();
            fake.succeed(pi2);
            String[] w2 = webhookFor("evt_B1", pi2, nowSec());
            assertThat(payments.handleWebhook(w2[0], w2[1])).isEqualTo(BillingPaymentEvent.RECORDED);
            assertThat(payments.confirm(t2, null)).containsEntry("state", "PAID");
            assertThat(receipts()).isEqualTo(2);
            assertThat(alerts()).isZero();
            assertThat(eventRows).extracting(BillingPaymentEvent::getOutcome).containsExactly(
                    BillingPaymentEvent.RECORDED, BillingPaymentEvent.ALREADY_RECORDED, BillingPaymentEvent.RECORDED);
        }

        @Test @DisplayName("amount, currency or invoice mismatch: NOT applied, Support alerted")
        void mismatches() throws Exception {
            String pi = startAndSucceed();
            ObjectNode o = fake.intents.get(pi);
            o.put("amount", 5999).put("amount_received", 5999);
            String[] w = webhookFor("evt_M1", pi, nowSec());
            assertThat(payments.handleWebhook(w[0], w[1])).isEqualTo(BillingPaymentEvent.ALERT_MISMATCH);
            o.put("amount", 6000).put("amount_received", 6000).put("currency", "eur");
            w = webhookFor("evt_M2", pi, nowSec());
            assertThat(payments.handleWebhook(w[0], w[1])).isEqualTo(BillingPaymentEvent.ALERT_MISMATCH);
            o.put("currency", "usd");
            ((ObjectNode) o.get("metadata")).put("invoice_id", "999999");
            w = webhookFor("evt_M3", pi, nowSec());
            assertThat(payments.handleWebhook(w[0], w[1])).isEqualTo(BillingPaymentEvent.ALERT_MISMATCH);
            assertThat(now().getStatus()).isEqualTo(BillingInvoice.SENT);
            assertThat(alerts()).isEqualTo(3);
            assertThat(receipts()).isZero();
        }

        @Test @DisplayName("money for an invoice already paid another way: invoice unchanged, Support alerted (possible double payment)")
        void alreadyPaid() throws Exception {
            String pi = startAndSucceed();
            billing.markPaid(inv.getId(), TODAY, "CHECK", "1001", "admin");
            String[] w = webhookFor("evt_P1", pi, nowSec());
            assertThat(payments.handleWebhook(w[0], w[1])).isEqualTo(BillingPaymentEvent.ALERT_ALREADY_PAID);
            assertThat(now().getPaymentMethod()).isEqualTo("CHECK");
            assertThat(now().getPaymentReference()).isEqualTo("1001");
            assertThat(alerts()).isEqualTo(1);
            assertThat(String.valueOf(outbox.get(0).get("html"))).contains(pi).contains("refund").doesNotContain("_secret_");
        }

        @Test @DisplayName("void cancels the open PaymentIntent; money that still arrives for a void invoice is NOT applied and alerts Support")
        void voided() throws Exception {
            payments.startPayment(token, null);
            String pi = now().getStripePaymentIntentId();
            billing.voidInvoice(inv.getId(), "changed", "admin");
            payments.afterVoid(inv.getId());
            assertThat(fake.calls).contains("POST /payment_intents/" + pi + "/cancel");
            assertThat(fake.intents.get(pi).path("status").asText()).isEqualTo("canceled");

            // A payment that succeeded just before the void, reported after it.
            fake.setStatus(pi, "requires_payment_method");
            fake.succeed(pi);
            String[] w = webhookFor("evt_V1", pi, nowSec());
            assertThat(payments.handleWebhook(w[0], w[1])).isEqualTo(BillingPaymentEvent.ALERT_VOID);
            assertThat(now().getStatus()).isEqualTo(BillingInvoice.VOID);
            assertThat(alerts()).isEqualTo(1);
            assertThatThrownBy(() -> payments.startPayment(token, null)).isInstanceOf(BillingPaymentService.NotFound.class);
        }
    }

    // ══ Webhook security ═════════════════════════════════════════════════

    @Nested @DisplayName("Other payment methods: ACH debit, bank transfer, and what the payer sees")
    class Methods {
        @Test @DisplayName("the method actually used is recorded (ACH debit), on the invoice, its charges and the receipt")
        void achRecorded() {
            payments.startPayment(token, null);
            String pi = now().getStripePaymentIntentId();
            fake.succeed(pi, "us_bank_account");
            assertThat(payments.confirm(token, null)).containsEntry("state", "PAID");
            assertThat(now().getPaymentMethod()).isEqualTo("ACH_DEBIT");
            assertThat(charge.getPaymentMethod()).isEqualTo("ACH_DEBIT");
            assertThat(outbox.get(0).get("html").toString()).contains("Bank account (ACH debit)").doesNotContain(">Card<");
            assertThat(BillingPaymentService.methodCode("customer_balance")).isEqualTo("BANK_TRANSFER");
            assertThat(BillingPaymentService.methodCode("link")).isEqualTo("LINK");
            assertThat(BillingPaymentService.methodCode("some_new_type")).isEqualTo("STRIPE_SOME_NEW_TYPE");
            assertThat(BillingPaymentService.methodLabel("STRIPE_SOME_NEW_TYPE")).isEqualTo("some new type");
            assertThat(BillingPaymentService.methodLabel("BANK_TRANSFER")).isEqualTo("Bank transfer");
        }

        @Test @DisplayName("a webhook carries only the payment method id: the method is re-read from Stripe (bank transfer → BANK_TRANSFER)")
        void webhookMethodReRead() throws Exception {
            payments.startPayment(token, null);
            String pi = now().getStripePaymentIntentId();
            fake.succeed(pi, "customer_balance");
            fake.retrievePaths.clear();
            String[] w = webhookFor("evt_M1", pi, nowSec());
            assertThat(w[0]).doesNotContain("\"type\":\"customer_balance\"");   // as Stripe sends it
            assertThat(payments.handleWebhook(w[0], w[1])).isEqualTo(BillingPaymentEvent.RECORDED);
            assertThat(now().getPaymentMethod()).isEqualTo("BANK_TRANSFER");
            assertThat(fake.retrievePaths).anyMatch(p -> p.contains("expand[]=payment_method"));
            assertThat(outbox.get(0).get("html").toString()).contains("Bank transfer");
        }

        @Test @DisplayName("ACH debit clearing: confirm says PROCESSING, the page's config says so too, invoice stays SENT until the webhook")
        void achProcessing() throws Exception {
            payments.startPayment(token, null);
            String pi = now().getStripePaymentIntentId();
            fake.processing(pi);
            assertThat(payments.confirm(token, null)).containsEntry("state", "PROCESSING");
            assertThat(payments.payConfig(token, null)).containsEntry("pending", "PROCESSING").doesNotContainKey("instructionsUrl");
            assertThat(now().getStatus()).isEqualTo(BillingInvoice.SENT);
            assertThat(payments.startPayment(token, null).clientSecret()).startsWith(pi + "_secret_");   // same intent reused
            fake.succeed(pi, "us_bank_account");
            String[] w = webhookFor("evt_A1", pi, nowSec());
            assertThat(payments.handleWebhook(w[0], w[1])).isEqualTo(BillingPaymentEvent.RECORDED);
            assertThat(now().getStatus()).isEqualTo(BillingInvoice.PAID);
            assertThat(now().getPaymentMethod()).isEqualTo("ACH_DEBIT");
            assertThat(receipts()).isEqualTo(1);
        }

        @Test @DisplayName("bank transfer awaited: confirm and config return AWAITING_TRANSFER with Stripe's hosted instructions link")
        void bankTransferAwaited() {
            payments.startPayment(token, null);
            String pi = now().getStripePaymentIntentId();
            fake.awaitBankTransfer(pi, "https://payments.stripe.com/bank_transfer/instructions/test_abc");
            Map<String, Object> c = payments.confirm(token, null);
            assertThat(c).containsEntry("state", "AWAITING_TRANSFER").containsEntry("instructionsUrl", "https://payments.stripe.com/bank_transfer/instructions/test_abc");
            assertThat(payments.payConfig(token, null)).containsEntry("pending", "AWAITING_TRANSFER").containsKey("instructionsUrl");
            assertThat(now().getStatus()).isEqualTo(BillingInvoice.SENT);
            assertThat(receipts()).isZero();
        }

        @Test @DisplayName("a failed attempt: confirm reports Stripe's (masked) reason; the payment_failed webhook writes an audit row, leaves the invoice SENT and alerts Support")
        void failed() throws Exception {
            payments.startPayment(token, null);
            String pi = now().getStripePaymentIntentId();
            fake.fail(pi, "Your card was declined. key sk_test_SHOULDNOTLEAK0000000000");
            Map<String, Object> c = payments.confirm(token, null);
            assertThat(c).containsEntry("state", "FAILED");
            assertThat(String.valueOf(c.get("message"))).contains("Your card was declined").doesNotContain("SHOULDNOTLEAK");
            String[] w = FakeStripeHttp.webhook("evt_F1", "payment_intent.payment_failed", fake.intents.get(pi), nowSec(), FakeStripeHttp.WEBHOOK, false);
            assertThat(payments.handleWebhook(w[0], w[1])).isEqualTo(BillingPaymentEvent.PAYMENT_FAILED);
            assertThat(now().getStatus()).isEqualTo(BillingInvoice.SENT);
            assertThat(charge.getStatus()).isEqualTo(ClientCharge.BILLED);
            assertThat(eventRows).extracting(BillingPaymentEvent::getOutcome).containsExactly(BillingPaymentEvent.PAYMENT_FAILED);
            assertThat(eventRows.get(0).getDetail()).contains("declined").doesNotContain("SHOULDNOTLEAK");
            assertThat(alerts()).isEqualTo(1);
            assertThat(outbox.get(0).get("html").toString()).contains("attempt failed").contains(inv.getInvoiceNumber()).doesNotContain("SHOULDNOTLEAK");
            // redelivered → ignored, no second alert
            assertThat(payments.handleWebhook(w[0], w[1])).isEqualTo("duplicate");
            assertThat(alerts()).isEqualTo(1);
            assertThat(receipts()).isZero();
        }

        @Test @DisplayName("a short bank transfer (partially_funded): audit row with the remainder, invoice SENT, Support alerted; money is never applied")
        void partiallyFunded() throws Exception {
            payments.startPayment(token, null);
            String pi = now().getStripePaymentIntentId();
            fake.awaitBankTransfer(pi, "https://payments.stripe.com/x");
            fake.intents.get(pi).put("amount_received", 4000);
            String[] w = FakeStripeHttp.webhook("evt_P1", "payment_intent.partially_funded", fake.intents.get(pi), nowSec(), FakeStripeHttp.WEBHOOK, false);
            assertThat(payments.handleWebhook(w[0], w[1])).isEqualTo(BillingPaymentEvent.PARTIALLY_FUNDED);
            assertThat(now().getStatus()).isEqualTo(BillingInvoice.SENT);
            assertThat(eventRows.get(0).getDetail()).contains("Received 4000 of 6000").contains("remaining 2000");
            assertThat(eventRows.get(0).getAmountCents()).isEqualTo(4000);
            assertThat(alerts()).isEqualTo(1);
            assertThat(outbox.get(0).get("html").toString()).contains("LESS than the invoice total").contains("remaining amount");
            assertThat(payments.paymentEvents(inv.getId())).singleElement().satisfies(m -> assertThat(m).containsEntry("outcome", BillingPaymentEvent.PARTIALLY_FUNDED));
        }

        @Test @DisplayName("payment_failed / partially_funded for another purpose or the other mode are ignored")
        void problemsIgnored() throws Exception {
            payments.startPayment(token, null);
            String pi = now().getStripePaymentIntentId();
            ObjectNode other = fake.intents.get(pi).deepCopy();
            other.putObject("metadata").put("purpose", "church_donation");
            String[] w = FakeStripeHttp.webhook("evt_X1", "payment_intent.payment_failed", other, nowSec(), FakeStripeHttp.WEBHOOK, false);
            assertThat(payments.handleWebhook(w[0], w[1])).isEqualTo(BillingPaymentEvent.IGNORED);
            String[] live = FakeStripeHttp.webhook("evt_X2", "payment_intent.partially_funded", fake.intents.get(pi), nowSec(), FakeStripeHttp.WEBHOOK, true);
            assertThat(payments.handleWebhook(live[0], live[1])).isEqualTo(BillingPaymentEvent.IGNORED);
            assertThat(eventRows).isEmpty();
            assertThat(alerts()).isZero();
        }
    }

    @Nested @DisplayName("Webhook signature, replay and duplicates")
    class Webhook {
        @Test @DisplayName("tampered body, wrong secret, missing/malformed header, stale and future timestamps are all refused; nothing applied")
        void signatures() throws Exception {
            String pi = startAndSucceed();
            String[] ok = webhookFor("evt_S1", pi, nowSec());
            List<String[]> bad = List.of(
                    new String[]{ ok[0].replace("\"usd\"", "\"USD\""), ok[1] },                                            // tampered
                    FakeStripeHttp.webhook("evt_S1", fake.intents.get(pi), nowSec(), "whsec_wrongSECRET123456", false),   // wrong secret
                    new String[]{ ok[0], null }, new String[]{ ok[0], "" }, new String[]{ ok[0], "v1=abc" },                // missing / malformed
                    webhookFor("evt_S1", pi, nowSec() - PlatformStripeService.WEBHOOK_TOLERANCE_SECONDS - 1),             // replay of an old delivery
                    webhookFor("evt_S1", pi, nowSec() + PlatformStripeService.WEBHOOK_TOLERANCE_SECONDS + 1));            // from the future
            for (String[] b : bad) {
                assertThatThrownBy(() -> payments.handleWebhook(b[0], b[1])).isInstanceOf(PlatformStripeService.WebhookSignatureException.class);
            }
            assertThat(now().getStatus()).isEqualTo(BillingInvoice.SENT);
            assertThat(eventRows).isEmpty();
            assertThat(payments.handleWebhook(ok[0], ok[1])).isEqualTo(BillingPaymentEvent.RECORDED);
        }

        @Test @DisplayName("a redelivered event (same event id) is recognised and ignored — no second receipt")
        void duplicate() throws Exception {
            String pi = startAndSucceed();
            String[] w = webhookFor("evt_D1", pi, nowSec());
            assertThat(payments.handleWebhook(w[0], w[1])).isEqualTo(BillingPaymentEvent.RECORDED);
            String[] again = webhookFor("evt_D1", pi, nowSec());   // Stripe re-signs a retry with a new timestamp
            assertThat(payments.handleWebhook(again[0], again[1])).isEqualTo("duplicate");
            assertThat(receipts()).isEqualTo(1);
            assertThat(eventRows).hasSize(1);
        }

        @Test @DisplayName("events of the other mode, other types and other purposes are ignored")
        void ignored() throws Exception {
            String pi = startAndSucceed();
            String[] live = FakeStripeHttp.webhook("evt_I1", fake.intents.get(pi), nowSec(), FakeStripeHttp.WEBHOOK, true);
            assertThat(payments.handleWebhook(live[0], live[1])).isEqualTo(BillingPaymentEvent.IGNORED);
            ObjectNode other = fake.intents.get(pi).deepCopy();
            ((ObjectNode) other.get("metadata")).put("purpose", "donation");
            String[] donation = FakeStripeHttp.webhook("evt_I2", other, nowSec(), FakeStripeHttp.WEBHOOK, false);
            assertThat(payments.handleWebhook(donation[0], donation[1])).isEqualTo(BillingPaymentEvent.IGNORED);
            assertThat(now().getStatus()).isEqualTo(BillingInvoice.SENT);
        }

        @Test @DisplayName("controller: 400 'invalid' for a bad signature, 200 for a good one; responses carry no data")
        void controller() throws Exception {
            InvoicePaymentController c = new InvoicePaymentController(payments, mock(PublicFormGuard.class));
            String pi = startAndSucceed();
            String[] w = webhookFor("evt_C1", pi, nowSec());
            ResponseEntity<String> bad = c.webhook(w[0], "t=1,v1=00");
            assertThat(bad.getStatusCode().value()).isEqualTo(400);
            assertThat(bad.getBody()).isEqualTo("invalid");
            ResponseEntity<String> good = c.webhook(w[0], w[1]);
            assertThat(good.getStatusCode().value()).isEqualTo(200);
            assertThat(good.getBody()).isEqualTo("ok:" + BillingPaymentEvent.RECORDED);
        }
    }

    // ══ Receipts, endpoints, separation ══════════════════════════════════

    @Test @DisplayName("manual mark paid: a receipt is sent only when asked, to the billing email with a Support copy")
    void manualReceipt() throws Exception {
        assertThatThrownBy(() -> payments.sendReceipt(inv.getId())).hasMessageContaining("Only a paid invoice");
        billing.markPaid(inv.getId(), TODAY, "BANK_TRANSFER", "ACH-77", "admin");
        assertThat(receipts()).isZero();                       // not asked
        payments.sendReceipt(inv.getId());                     // the "send receipt" box
        assertThat(receipts()).isEqualTo(1);
        assertThat(String.valueOf(outbox.get(0).get("html"))).contains("Bank transfer").contains("$60.00");
        assertThat(outbox.get(0).get("bcc")).isEqualTo(List.of("support@cgp.test"));
    }

    @Test @DisplayName("public payment endpoints: generic 404 for bad tokens, no-store, and never a secret key in any response")
    void endpoints() throws Exception {
        PublicFormGuard guard = mock(PublicFormGuard.class);
        InvoicePaymentController c = new InvoicePaymentController(payments, guard);
        MockHttpServletRequest req = new MockHttpServletRequest();
        assertThat(c.config("nope", req).getStatusCode().value()).isEqualTo(404);
        assertThat(c.start(Map.of("t", "nope"), req).getStatusCode().value()).isEqualTo(404);
        assertThat(c.confirm(Map.of("t", "nope"), req).getStatusCode().value()).isEqualTo(404);
        ResponseEntity<Map<String, Object>> cfg = c.config(token, req);
        ResponseEntity<Map<String, Object>> st = c.start(Map.of("t", token), req);
        assertThat(st.getHeaders().getCacheControl()).contains("no-store");
        String all = new ObjectMapper().writeValueAsString(List.of(cfg.getBody(), st.getBody()));
        assertThat(all).doesNotContain(FakeStripeHttp.SECRET).doesNotContain(FakeStripeHttp.WEBHOOK).doesNotContain("sk_test_").doesNotContain("whsec_");
        when(guard.checkRate(any(), eq("invoice-pay"))).thenReturn("Too many submissions. Please try again later.");
        assertThat(c.start(Map.of("t", token), req).getStatusCode().value()).isEqualTo(429);
    }

    @Test @DisplayName("Service Admin Stripe endpoints: admin only; status never contains a key")
    void adminStripe() throws Exception {
        ServiceAdminStripeController c = new ServiceAdminStripeController(stripe, payments);
        MockHttpServletRequest anon = new MockHttpServletRequest();
        assertThat(c.status(anon).getStatusCode().value()).isEqualTo(401);
        assertThat(c.test(anon).getStatusCode().value()).isEqualTo(401);
        assertThat(c.events(1L, anon).getStatusCode().value()).isEqualTo(401);
        MockHttpServletRequest admin = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession(); s.setAttribute("role", "ServiceAdmin"); admin.setSession(s);
        String body = new ObjectMapper().writeValueAsString(c.status(admin).getBody());
        assertThat(body).doesNotContain(FakeStripeHttp.SECRET).doesNotContain(FakeStripeHttp.PUBLISH).doesNotContain(FakeStripeHttp.WEBHOOK)
                .contains("sk_test_…").contains("/api/billing/stripe-webhook");
        assertThat(fake.calls).isEmpty();                       // viewing the status calls nothing
    }

    @Test @DisplayName("billing reminders are untouched by Phase 6 (still off by default)")
    void remindersStillOff() {
        verify(settings, never()).set(anyString(), any(), any());
    }
}
