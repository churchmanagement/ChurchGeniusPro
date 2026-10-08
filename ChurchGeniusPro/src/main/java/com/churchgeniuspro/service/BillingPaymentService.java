package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.BillingInvoice;
import com.churchgeniuspro.hibernate.BillingPaymentEvent;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.logging.SensitiveDataMasker;
import com.churchgeniuspro.repository.BillingInvoiceRepository;
import com.churchgeniuspro.repository.BillingPaymentEventRepository;
import com.churchgeniuspro.repository.ClientChargeRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.util.AppClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Online payment of platform invoices through the Service Admin's own Stripe account (Phase 6).
 *
 * <ul>
 *   <li><b>Start</b> — for a SENT invoice opened by its link: one PaymentIntent for exactly
 *       the invoice's Amount Due (read from the database; the browser never sends an
 *       amount), offering the payment methods enabled in the Stripe Dashboard, reused while
 *       open. Concurrent starts get the same intent. A Stripe Customer is created for the
 *       client on first use so bank transfer can be offered; if that fails the intent is
 *       still created (card and other methods keep working). A stored intent that no longer
 *       exists in Stripe (other mode / other account) is replaced, not fatal.</li>
 *   <li><b>Asynchronous methods</b> (ACH Direct Debit, bank transfer) settle days later: the
 *       browser sees PROCESSING / AWAITING_TRANSFER and the invoice stays SENT until the
 *       signed {@code payment_intent.succeeded} webhook records it. {@code payment_failed}
 *       and {@code partially_funded} leave the invoice unchanged and alert the Support Email.</li>
 *   <li><b>Record</b> — the browser confirm path and the signed webhook both end in
 *       {@link #recordCardPayment}: the PaymentIntent must have succeeded and match the
 *       invoice (metadata), the amount (exact cents) and the currency (usd). One
 *       transaction moves SENT → PAID with the same conditional update as a manual mark
 *       paid, marks the invoice's charges paid and writes the audit row, so a payment is
 *       never applied twice. A webhook event id is stored once (unique) in that same
 *       transaction, so a redelivered event is ignored.</li>
 *   <li>Money for an invoice that is already paid, voided, or does not match is NEVER
 *       applied: the invoice stays as it is and the Support Email is alerted so the payment
 *       can be refunded in the Stripe dashboard (refunds are not built in).</li>
 *   <li>Payment never changes the client's subscription — renewal stays a Service Admin action.</li>
 *   <li>Receipts: card payments → the invoice's billing email, copied to the Support Email;
 *       manual mark paid → the same, only when the Service Admin ticks "send receipt".</li>
 * </ul>
 */
@Service
public class BillingPaymentService {

    private static final Logger log = LoggerFactory.getLogger(BillingPaymentService.class);
    public static final String ACTOR = "stripe (online payment)";
    /** Stripe's minimum USD charge. */
    public static final long MIN_CENTS = 50;
    private static final Set<String> REUSABLE = Set.of("requires_payment_method", "requires_confirmation", "requires_action", "processing");
    private static final Set<String> CANCELLABLE = Set.of("requires_payment_method", "requires_confirmation", "requires_action");
    private static final DateTimeFormatter LONG_DAY = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);

    private final BillingService billing;
    private final BillingInvoiceRepository invoices;
    private final ClientChargeRepository charges;
    private final BillingPaymentEventRepository events;
    private final PlatformStripeService stripe;
    private final EmailService email;
    private final PlatformSettingService settings;
    private final ServiceClientRepository clients;

    private org.springframework.transaction.support.TransactionTemplate tx;
    @org.springframework.beans.factory.annotation.Autowired
    public void setTransactionManager(org.springframework.transaction.PlatformTransactionManager tm) {
        this.tx = new org.springframework.transaction.support.TransactionTemplate(tm);
    }
    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return tx == null ? work.get() : tx.execute(s -> work.get());
    }

    public BillingPaymentService(BillingService billing, BillingInvoiceRepository invoices, ClientChargeRepository charges,
                                 BillingPaymentEventRepository events, PlatformStripeService stripe,
                                 EmailService email, PlatformSettingService settings, ServiceClientRepository clients) {
        this.billing = billing;
        this.invoices = invoices;
        this.charges = charges;
        this.events = events;
        this.stripe = stripe;
        this.email = email;
        this.settings = settings;
        this.clients = clients;
    }

    /** Any refusal on the public payment endpoints that must look like "link not valid". */
    public static class NotFound extends RuntimeException {
        public NotFound() { super("not found"); }
    }

    static long cents(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    // ══ Invoice page ═════════════════════════════════════════════════════

    /** Whether the invoice page may offer "Pay with card", and the publishable key Stripe.js needs. */
    public Map<String, Object> payConfig(String token, String sessionClientId) {
        BillingInvoice inv = billing.invoiceForToken(token, sessionClientId).orElseThrow(NotFound::new);
        Map<String, Object> m = new LinkedHashMap<>();
        String why = unavailableReason(inv);
        m.put("available", why == null);
        if (why == null) {
            m.put("publishableKey", stripe.publishableKey());
            pendingState(inv).ifPresent(p -> { m.put("pending", p.state()); if (p.url() != null) m.put("instructionsUrl", p.url()); });
        } else {
            m.put("reason", why);
        }
        return m;
    }

    record Pending(String state, String url) {}

    /** A payment already under way for the invoice (ACH processing / bank transfer awaited), if any. Never throws. */
    private Optional<Pending> pendingState(BillingInvoice inv) {
        if (inv.getStripePaymentIntentId() == null) return Optional.empty();
        try {
            PlatformStripeService.Intent pi = stripe.retrieveIntent(inv.getStripePaymentIntentId());
            if ("processing".equals(pi.status())) return Optional.of(new Pending("PROCESSING", null));
            if ("requires_action".equals(pi.status()) && pi.instructionsUrl() != null) return Optional.of(new Pending("AWAITING_TRANSFER", pi.instructionsUrl()));
        } catch (Exception e) {
            log.warn("Billing: could not read PaymentIntent for invoice {} — {}", inv.getInvoiceNumber(), SensitiveDataMasker.mask(String.valueOf(e.getMessage())));
        }
        return Optional.empty();
    }

    private String unavailableReason(BillingInvoice inv) {
        if (!BillingInvoice.SENT.equals(inv.getStatus())) return "This invoice is already paid.";
        if (!stripe.isConfigured()) return "Card payment is not available for this invoice. Please contact us to arrange payment.";
        if (inv.getTotal() == null || cents(inv.getTotal()) < MIN_CENTS) return "This amount cannot be paid by card. Please contact us.";
        return null;
    }

    /** What the browser needs to show the card form. The client secret is never logged. */
    public record Started(String clientSecret, String publishableKey, long amountCents) {
        @Override public String toString() { return "Started[" + amountCents + "]"; }
    }

    /**
     * Returns the invoice's open PaymentIntent, creating (or replacing a cancelled) one for
     * exactly the invoice total. A PaymentIntent that turns out to have succeeded already
     * is recorded and reported as paid.
     */
    public Started startPayment(String token, String sessionClientId) {
        BillingInvoice inv = billing.invoiceForToken(token, sessionClientId).orElseThrow(NotFound::new);
        String why = unavailableReason(inv);
        if (why != null) throw new IllegalStateException(why);
        long amount = cents(inv.getTotal());
        String current = inv.getStripePaymentIntentId();
        if (current != null) {
            PlatformStripeService.Intent pi = null;
            try {
                pi = stripe.retrieveIntent(current);
            } catch (PlatformStripeService.StripeNotFoundException gone) {
                // Created under other keys (test vs live, another account): unusable — replace it.
                log.warn("Billing: PaymentIntent {} on invoice {} no longer exists in Stripe — replacing it", current, inv.getInvoiceNumber());
            }
            if (pi != null) {
                if ("succeeded".equals(pi.status())) {
                    recordCardPayment(inv.getId(), pi, BillingPaymentEvent.SOURCE_CONFIRM, null, null);
                    throw new IllegalStateException("This invoice has already been paid. Thank you!");
                }
                if (REUSABLE.contains(pi.status()) && matches(pi, inv.getId(), amount)) {
                    return new Started(pi.clientSecret(), stripe.publishableKey(), amount);
                }
                // Cancelled or not matching — replaced below. Cancel a stray open one.
                if (CANCELLABLE.contains(pi.status())) cancelQuietly(current);
            }
        }
        // Same invoice + amount + predecessor ⇒ same key ⇒ Stripe returns the same intent to concurrent callers.
        String idem = "cgp-inv-" + inv.getId() + "-" + amount + "-" + (current == null ? "first" : current);
        PlatformStripeService.Intent created = stripe.createIntent(inv.getId(), inv.getInvoiceNumber(), amount, customerFor(inv), idem);
        int n = current == null ? invoices.attachFirstPaymentIntent(inv.getId(), created.id())
                                : invoices.replacePaymentIntent(inv.getId(), current, created.id());
        if (n != 1) {
            BillingInvoice now = invoices.findById(inv.getId()).orElseThrow(NotFound::new);
            if (BillingInvoice.SENT.equals(now.getStatus()) && created.id().equals(now.getStripePaymentIntentId())) {
                return new Started(created.clientSecret(), stripe.publishableKey(), amount);
            }
            if (BillingInvoice.SENT.equals(now.getStatus()) && now.getStripePaymentIntentId() != null) {
                cancelQuietly(created.id());
                PlatformStripeService.Intent theirs = stripe.retrieveIntent(now.getStripePaymentIntentId());
                if (REUSABLE.contains(theirs.status()) && matches(theirs, now.getId(), amount)) {
                    return new Started(theirs.clientSecret(), stripe.publishableKey(), amount);
                }
            }
            cancelQuietly(created.id());
            throw new IllegalStateException("This invoice can no longer be paid by card. Please refresh the page.");
        }
        log.info("Billing: PaymentIntent {} attached to invoice {} ({} cents)", created.id(), inv.getInvoiceNumber(), amount);
        return new Started(created.clientSecret(), stripe.publishableKey(), amount);
    }

    /**
     * The client's Stripe Customer in the billing account, created on first use (bank
     * transfer needs one). Null — and the payment still goes ahead with the other methods —
     * when the client row is missing or Stripe refuses the create (e.g. a restricted key
     * without Customers write); the refusal is logged, masked.
     */
    String customerFor(BillingInvoice inv) {
        if (inv.getClientId() == null) return null;
        try {
            ServiceClient sc = clients.findByClientId(inv.getClientId()).orElse(null);
            if (sc == null) return null;
            if (sc.getBillingStripeCustomerId() != null) return sc.getBillingStripeCustomerId();
            String name = sc.getChurchName() != null && !sc.getChurchName().isBlank() ? sc.getChurchName() : inv.getChurchName();
            String cus = stripe.createCustomer(name, inv.getBillToEmail(), inv.getClientId());
            if (clients.setBillingStripeCustomerId(inv.getClientId(), cus) == 1) {
                log.info("Billing: Stripe Customer {} created for client {}", cus, inv.getClientId());
                return cus;
            }
            // Another request created one first: the stored id is the one every intent must use.
            return clients.findByClientId(inv.getClientId()).map(ServiceClient::getBillingStripeCustomerId).orElse(cus);
        } catch (Exception e) {
            log.warn("Billing: no Stripe Customer for client {} (bank transfer not offered) — {}", inv.getClientId(),
                    SensitiveDataMasker.mask(String.valueOf(e.getMessage())));
            return null;
        }
    }

    /**
     * After the card form completes (or the 3-D Secure redirect returns): re-reads the
     * invoice's PaymentIntent from Stripe — never trusting the browser — and records it.
     * Returns {@code state}: PAID, PROCESSING (ACH debit under way), AWAITING_TRANSFER (bank
     * transfer instructions issued; {@code instructionsUrl}), FAILED, PENDING, or ATTENTION.
     */
    public Map<String, Object> confirm(String token, String sessionClientId) {
        BillingInvoice inv = billing.invoiceForToken(token, sessionClientId).orElseThrow(NotFound::new);
        Map<String, Object> m = new LinkedHashMap<>();
        if (BillingInvoice.PAID.equals(inv.getStatus())) { m.put("state", "PAID"); return m; }
        if (inv.getStripePaymentIntentId() == null) { m.put("state", "PENDING"); return m; }
        PlatformStripeService.Intent pi;
        try {
            pi = stripe.retrieveIntent(inv.getStripePaymentIntentId());
        } catch (PlatformStripeService.StripeNotFoundException gone) {
            m.put("state", "FAILED"); m.put("message", "The payment could not be found. Please start the payment again.");
            return m;
        }
        switch (pi.status() == null ? "" : pi.status()) {
            case "succeeded" -> {
                Result r = recordCardPayment(inv.getId(), pi, BillingPaymentEvent.SOURCE_CONFIRM, null, null);
                boolean ok = BillingPaymentEvent.RECORDED.equals(r.outcome()) || BillingPaymentEvent.ALREADY_RECORDED.equals(r.outcome());
                m.put("state", ok ? "PAID" : "ATTENTION");
                if (!ok) m.put("message", "Your payment was received, but it could not be applied to this invoice automatically. "
                        + "We have been notified and will contact you.");
            }
            case "processing" -> m.put("state", "PROCESSING");
            case "requires_action" -> {
                if (pi.instructionsUrl() != null) { m.put("state", "AWAITING_TRANSFER"); m.put("instructionsUrl", pi.instructionsUrl()); }
                else m.put("state", "PENDING");
            }
            case "requires_payment_method" -> {
                m.put("state", "FAILED");
                m.put("message", pi.lastErrorMessage() != null ? "The payment did not go through: " + pi.lastErrorMessage()
                        : "You have not been charged. Please try again or choose another payment method.");
            }
            default -> m.put("state", "PENDING");
        }
        return m;
    }

    // ══ Webhook ══════════════════════════════════════════════════════════

    /**
     * Applies a signed Stripe webhook. Throws {@link PlatformStripeService.WebhookSignatureException}
     * for a bad, missing, stale or replayed signature (the controller answers 400 and
     * applies nothing). Returns the outcome for logging; "duplicate" for a redelivered event.
     */
    public String handleWebhook(String payload, String signatureHeader) {
        PlatformStripeService.WebhookEvent ev = stripe.verifyWebhook(payload, signatureHeader, Instant.now().getEpochSecond());
        if (ev.livemode() != stripe.isLive()) {
            log.warn("Billing webhook {} ignored: {} event but the platform key is {} mode", ev.id(),
                    ev.livemode() ? "live" : "test", stripe.isLive() ? "live" : "test");
            return BillingPaymentEvent.IGNORED;
        }
        String type = ev.type() == null ? "" : ev.type();
        if (!type.equals("payment_intent.succeeded") && !type.equals("payment_intent.payment_failed")
                && !type.equals("payment_intent.partially_funded")) return BillingPaymentEvent.IGNORED;
        PlatformStripeService.Intent pi = PlatformStripeService.toIntent(ev.object());
        if (!PlatformStripeService.PURPOSE.equals(pi.metadata().get("purpose"))) return BillingPaymentEvent.IGNORED;
        Long invoiceId = null;
        try { invoiceId = Long.valueOf(pi.metadata().get("invoice_id")); } catch (Exception ignored) { }
        try {
            if (type.equals("payment_intent.succeeded")) {
                return recordCardPayment(invoiceId, pi, BillingPaymentEvent.SOURCE_WEBHOOK, ev.id(), ev.type()).outcome();
            }
            return recordProblem(invoiceId, pi, ev.id(), ev.type(),
                    type.equals("payment_intent.payment_failed") ? BillingPaymentEvent.PAYMENT_FAILED : BillingPaymentEvent.PARTIALLY_FUNDED);
        } catch (DataIntegrityViolationException dup) {
            if (ev.id() != null && events.existsByStripeEventId(ev.id())) {
                log.info("Billing webhook {} already processed — ignored", ev.id());
                return "duplicate";
            }
            throw dup;
        }
    }

    /**
     * A failed attempt or a short bank transfer: audit row (event id unique ⇒ redeliveries
     * ignored), invoice untouched, Support Email told what to do. Never applies money.
     */
    String recordProblem(Long invoiceId, PlatformStripeService.Intent pi, String eventId, String eventType, String outcome) {
        BillingInvoice[] seen = { null };
        String detail = BillingPaymentEvent.PARTIALLY_FUNDED.equals(outcome)
                ? "Received " + pi.amountReceived() + " of " + pi.amount() + " " + pi.currency() + " so far; remaining "
                  + Math.max(0, pi.amount() - pi.amountReceived()) + "."
                : (pi.lastErrorMessage() != null ? pi.lastErrorMessage() : "Stripe reported the payment attempt failed.");
        inTransaction(() -> {
            BillingPaymentEvent ev = new BillingPaymentEvent();
            ev.setStripeEventId(eventId);
            ev.setEventType(eventType);
            ev.setSource(BillingPaymentEvent.SOURCE_WEBHOOK);
            ev.setPaymentIntentId(pi.id());
            ev.setInvoiceId(invoiceId);
            ev.setAmountCents(pi.amountReceived());
            ev.setCurrency(pi.currency());
            ev.setOutcome(outcome);
            ev.setDetail(detail.length() > 500 ? detail.substring(0, 500) : detail);
            ev.setCreatedAt(LocalDateTime.now());
            BillingInvoice inv = invoiceId == null ? null : invoices.findById(invoiceId).orElse(null);
            if (inv != null) ev.setClientId(inv.getClientId());
            seen[0] = inv;
            events.saveAndFlush(ev);
            return outcome;
        });
        log.info("Billing: {} for PaymentIntent {} on invoice {} — invoice unchanged", outcome, pi.id(),
                seen[0] != null ? seen[0].getInvoiceNumber() : "?");
        alertSupport(outcome, pi, seen[0], detail);
        return outcome;
    }

    /** Stripe payment method type → the invoice's payment_method code (also used by manual mark paid). */
    static String methodCode(String stripeType) {
        if (stripeType == null) return "CARD";
        return switch (stripeType) {
            case "card", "card_present" -> "CARD";
            case "us_bank_account", "acss_debit", "sepa_debit", "bacs_debit" -> "ACH_DEBIT";
            case "customer_balance" -> "BANK_TRANSFER";
            case "link" -> "LINK";
            case "cashapp" -> "CASH_APP";
            case "amazon_pay" -> "AMAZON_PAY";
            case "paypal" -> "PAYPAL";
            default -> "STRIPE_" + stripeType.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
        };
    }

    /** Human label for a payment_method code (receipts, Service Admin). */
    public static String methodLabel(String code) {
        if (code == null) return "Other";
        return switch (code) {
            case "CARD" -> "Card";
            case "CHECK" -> "Check";
            case "BANK_TRANSFER" -> "Bank transfer";
            case "ACH_DEBIT" -> "Bank account (ACH debit)";
            case "LINK" -> "Link";
            case "CASH_APP" -> "Cash App Pay";
            case "AMAZON_PAY" -> "Amazon Pay";
            case "PAYPAL" -> "PayPal";
            case "CASH" -> "Cash";
            default -> code.startsWith("STRIPE_") ? code.substring(7).replace('_', ' ').toLowerCase(Locale.ROOT) : "Other";
        };
    }

    /** The method the payer used; a webhook object carries only the method id, so it is re-read from Stripe. */
    private String methodUsed(PlatformStripeService.Intent pi) {
        String type = pi.paymentMethodType();
        if (type == null && stripe.isConfigured()) {
            try { type = stripe.retrieveIntent(pi.id()).paymentMethodType(); } catch (Exception e) {
                log.warn("Billing: could not read the payment method of {} — {}", pi.id(), SensitiveDataMasker.mask(String.valueOf(e.getMessage())));
            }
        }
        return methodCode(type);
    }

    // ══ Recording a successful payment ═══════════════════════════════════

    public record Result(String outcome, String invoiceNumber) {}

    private static boolean matches(PlatformStripeService.Intent pi, Long invoiceId, long amountCents) {
        return pi != null && pi.amount() == amountCents && "usd".equalsIgnoreCase(pi.currency())
                && PlatformStripeService.PURPOSE.equals(pi.metadata().get("purpose"))
                && String.valueOf(invoiceId).equals(pi.metadata().get("invoice_id"));
    }

    /**
     * One transaction: audit row (with the webhook event id, unique), the conditional
     * SENT → PAID and the invoice's charges → PAID. Receipt or Support alert after commit.
     */
    public Result recordCardPayment(Long invoiceId, PlatformStripeService.Intent pi, String source, String eventId, String eventType) {
        String[] detail = { null };
        BillingInvoice[] seen = { null };
        String method = "succeeded".equals(pi.status()) ? methodUsed(pi) : "CARD";   // read before the transaction: a Stripe call
        String outcome = inTransaction(() -> {
            BillingPaymentEvent ev = new BillingPaymentEvent();
            ev.setStripeEventId(eventId);
            ev.setEventType(eventType);
            ev.setSource(source);
            ev.setPaymentIntentId(pi.id());
            ev.setInvoiceId(invoiceId);
            ev.setAmountCents(pi.amountReceived() > 0 ? pi.amountReceived() : pi.amount());
            ev.setCurrency(pi.currency());
            ev.setOutcome(BillingPaymentEvent.IGNORED);
            ev.setCreatedAt(LocalDateTime.now());
            events.saveAndFlush(ev);   // a redelivered webhook fails here (unique event id) → rolled back, ignored

            BillingInvoice inv = invoiceId == null ? null : invoices.findById(invoiceId).orElse(null);
            String o;
            if (!"succeeded".equals(pi.status())) {
                o = BillingPaymentEvent.NOT_SUCCEEDED;
            } else if (inv == null) {
                o = BillingPaymentEvent.ALERT_MISMATCH;
                detail[0] = "No invoice matches this payment.";
            } else {
                ev.setClientId(inv.getClientId());
                long expected = cents(inv.getTotal());
                if (!matches(pi, inv.getId(), expected) || pi.amountReceived() != expected) {
                    o = BillingPaymentEvent.ALERT_MISMATCH;
                    detail[0] = "Expected " + expected + " usd for invoice " + inv.getId() + "; payment was "
                            + pi.amountReceived() + " " + pi.currency() + " for invoice " + pi.metadata().get("invoice_id") + ".";
                } else if (BillingInvoice.SENT.equals(inv.getStatus())
                        && invoices.markPaid(inv.getId(), AppClock.today(), method, pi.id(), ACTOR, LocalDateTime.now()) == 1) {
                    charges.payForInvoice(inv.getId(), AppClock.today(), method, LocalDateTime.now(), ACTOR);
                    o = BillingPaymentEvent.RECORDED;
                } else {
                    BillingInvoice now = invoices.findById(inv.getId()).orElse(inv);   // fresh after the conditional update
                    inv = now;
                    if (BillingInvoice.PAID.equals(now.getStatus()) && pi.id().equals(now.getPaymentReference())) {
                        o = BillingPaymentEvent.ALREADY_RECORDED;
                    } else if (BillingInvoice.PAID.equals(now.getStatus())) {
                        o = BillingPaymentEvent.ALERT_ALREADY_PAID;
                        detail[0] = "Invoice was already paid (" + now.getPaymentMethod() + ", " + now.getPaidDate() + ").";
                    } else if (BillingInvoice.VOID.equals(now.getStatus())) {
                        o = BillingPaymentEvent.ALERT_VOID;
                        detail[0] = "Invoice is void.";
                    } else {
                        o = BillingPaymentEvent.ALERT_MISMATCH;
                        detail[0] = "Invoice is " + now.getStatus() + ".";
                    }
                }
                seen[0] = inv;
            }
            ev.setOutcome(o);
            ev.setDetail(detail[0]);
            events.save(ev);
            return o;
        });
        BillingInvoice inv = seen[0];
        String number = inv != null ? inv.getInvoiceNumber() : null;
        log.info("Billing: payment {} ({}) via {} for invoice {} → {}", pi.id(), method, source, number, outcome);
        if (BillingPaymentEvent.RECORDED.equals(outcome)) {
            try {
                sendReceipt(inv.getId());
            } catch (Exception e) {
                log.error("Billing: receipt for {} could not be sent — {}", number, SensitiveDataMasker.mask(String.valueOf(e.getMessage())));
            }
        } else if (outcome.startsWith("ALERT_")) {
            alertSupport(outcome, pi, inv, detail[0]);
        }
        return new Result(outcome, number);
    }

    // ══ Void ═════════════════════════════════════════════════════════════

    /** After a void: cancels the invoice's open PaymentIntent so it can no longer be paid. Best effort. */
    public void afterVoid(Long invoiceId) {
        BillingInvoice inv = invoices.findById(invoiceId).orElse(null);
        if (inv == null || inv.getStripePaymentIntentId() == null || !stripe.isConfigured()) return;
        try {
            PlatformStripeService.Intent pi = stripe.retrieveIntent(inv.getStripePaymentIntentId());
            if (CANCELLABLE.contains(pi.status())) {
                stripe.cancelIntent(pi.id());
                log.info("Billing: PaymentIntent {} cancelled after invoice {} was voided", pi.id(), inv.getInvoiceNumber());
            }
        } catch (Exception e) {
            log.warn("Billing: could not cancel PaymentIntent for voided invoice {} — {}", inv.getInvoiceNumber(),
                    SensitiveDataMasker.mask(String.valueOf(e.getMessage())));
        }
    }

    private void cancelQuietly(String piId) {
        try { stripe.cancelIntent(piId); } catch (Exception e) {
            log.warn("Billing: could not cancel PaymentIntent {} — {}", piId, SensitiveDataMasker.mask(String.valueOf(e.getMessage())));
        }
    }

    // ══ Emails ═══════════════════════════════════════════════════════════

    /** Payment receipt to the invoice's billing email, copied (BCC) to the Support Email. */
    public void sendReceipt(Long invoiceId) throws Exception {
        BillingInvoice inv = invoices.findById(invoiceId).orElseThrow(() -> new IllegalArgumentException("Invoice not found."));
        if (!BillingInvoice.PAID.equals(inv.getStatus())) throw new IllegalArgumentException("Only a paid invoice has a receipt.");
        if (inv.getBillToEmail() == null || inv.getBillToEmail().isBlank()) throw new IllegalArgumentException("The invoice has no billing email.");
        String support = settings.supportEmail();
        List<String> bcc = support.equalsIgnoreCase(inv.getBillToEmail().trim()) ? null : List.of(support);
        email.sendComposed(List.of(inv.getBillToEmail().trim()), bcc,
                "Receipt for invoice " + inv.getInvoiceNumber() + " — ChurchGeniusPro", receiptHtml(inv), null, "ChurchGeniusPro Billing");
        invoices.markReceiptSent(inv.getId(), LocalDateTime.now());
    }

    String receiptHtml(BillingInvoice inv) {
        String method = methodLabel(inv.getPaymentMethod());
        String paid = inv.getPaidDate() != null ? inv.getPaidDate().format(LONG_DAY) : "—";
        return "<!DOCTYPE html><html><body style='margin:0;padding:0;background:#f5f6fa;font-family:-apple-system,Segoe UI,Roboto,sans-serif;'>"
             + "<table width='100%' cellpadding='0' cellspacing='0' style='padding:36px 16px;'><tr><td align='center'>"
             + "<table width='100%' cellpadding='0' cellspacing='0' style='max-width:520px;background:#fff;border-radius:14px;overflow:hidden;"
             + "box-shadow:0 4px 20px rgba(0,0,0,.08);'><tr><td style='background:#673147;padding:24px 36px;text-align:center;'>"
             + "<p style='margin:0;font-size:19px;font-weight:700;color:#fff;'>ChurchGeniusPro</p>"
             + "<p style='margin:6px 0 0;font-size:12px;color:rgba(255,255,255,.75);'>Payment receipt</p></td></tr>"
             + "<tr><td style='padding:30px 36px;'>"
             + "<p style='margin:0 0 14px;font-size:15px;color:#1a1a2e;font-weight:600;'>Hello "
             + BillingService.esc(inv.getChurchName() != null ? inv.getChurchName() : "there") + ",</p>"
             + "<p style='margin:0 0 16px;font-size:14px;color:#555;line-height:1.7;'>Thank you — we received your payment.</p>"
             + "<table style='font-size:13.5px;color:#444;margin:0 0 16px;'>"
             + row("Invoice", BillingService.esc(inv.getInvoiceNumber()))
             + row("Amount paid", BillingService.money(inv.getTotal()))
             + row("Paid on", paid)
             + row("Payment method", method)
             + "</table><p style='margin:0;font-size:12px;color:#888;line-height:1.6;'>Questions? Contact "
             + BillingService.esc(settings.supportEmail()) + ".</p></td></tr></table></td></tr></table></body></html>";
    }

    private static String row(String k, String v) {
        return "<tr><td style='padding:3px 14px 3px 0;font-weight:600;'>" + k + ":</td><td>" + v + "</td></tr>";
    }

    /** Tells the Support Email that an online payment needs a person. No secrets. */
    private void alertSupport(String outcome, PlatformStripeService.Intent pi, BillingInvoice inv, String detail) {
        String what = switch (outcome) {
            case BillingPaymentEvent.ALERT_ALREADY_PAID -> "a payment arrived for an invoice that was already paid (possible double payment)";
            case BillingPaymentEvent.ALERT_VOID -> "a payment arrived for a voided invoice";
            case BillingPaymentEvent.PAYMENT_FAILED -> "an online payment attempt failed (the invoice is still unpaid)";
            case BillingPaymentEvent.PARTIALLY_FUNDED -> "a bank transfer arrived for LESS than the invoice total (the invoice is still unpaid)";
            default -> "a payment did not match its invoice and was NOT applied";
        };
        String action = switch (outcome) {
            case BillingPaymentEvent.PAYMENT_FAILED -> "No money was received. The church can try again from the invoice link.";
            case BillingPaymentEvent.PARTIALLY_FUNDED -> "Ask the church to transfer the remaining amount, or settle it in the Stripe dashboard.";
            default -> "If the money should be returned, refund it in the Stripe dashboard.";
        };
        String html = "<div style=\"font-family:Segoe UI,Arial,sans-serif;font-size:14px;color:#2b2b2b;\">"
                + "<p><strong>Billing alert:</strong> " + what + ". The invoice was not changed. " + action
                + "</p><table cellpadding=\"6\" style=\"border-collapse:collapse;font-size:13px;\">"
                + "<tr><td><b>Invoice</b></td><td>" + BillingService.esc(inv != null ? inv.getInvoiceNumber() + " (" + inv.getStatus() + ")" : "not found") + "</td></tr>"
                + "<tr><td><b>Church</b></td><td>" + BillingService.esc(inv != null ? inv.getChurchName() : "—") + "</td></tr>"
                + "<tr><td><b>PaymentIntent</b></td><td>" + BillingService.esc(pi.id()) + "</td></tr>"
                + "<tr><td><b>Amount</b></td><td>" + BillingService.money(BigDecimal.valueOf(pi.amountReceived(), 2)) + " "
                + BillingService.esc(pi.currency()) + "</td></tr>"
                + "<tr><td><b>Detail</b></td><td>" + BillingService.esc(detail) + "</td></tr></table></div>";
        try {
            email.sendComposed(List.of(settings.supportEmail()), null,
                    "Billing alert: online payment needs attention" + (inv != null ? " — " + inv.getInvoiceNumber() : ""),
                    html, null, "ChurchGeniusPro Billing");
        } catch (Exception e) {
            log.error("Billing: Support alert for PaymentIntent {} could not be sent — {}", pi.id(),
                    SensitiveDataMasker.mask(String.valueOf(e.getMessage())));
        }
    }

    // ══ Service Admin ════════════════════════════════════════════════════

    public List<Map<String, Object>> paymentEvents(Long invoiceId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (BillingPaymentEvent e : events.findByInvoiceIdOrderByCreatedAtDescIdDesc(invoiceId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
            m.put("source", e.getSource());
            m.put("paymentIntentId", e.getPaymentIntentId());
            m.put("amountCents", e.getAmountCents());
            m.put("currency", e.getCurrency());
            m.put("outcome", e.getOutcome());
            m.put("detail", e.getDetail());
            out.add(m);
        }
        return out;
    }
}
