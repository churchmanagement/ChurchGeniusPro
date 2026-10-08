package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.util.AppClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Platform billing (Phase 5): additional charges, invoices reviewed and sent by the
 * Service Admin, the secure invoice link, and the data behind billing reminders.
 * No card payments here — payments are recorded with "Mark paid" (Stripe is Phase 6).
 *
 * <ul>
 *   <li><b>Charges</b> are created per client and billing period. A new draft invoice
 *       picks up the client's unbilled charges; a draft reserves them so they cannot
 *       appear on two invoices.</li>
 *   <li><b>Drafts</b> are built from the client's own price (never the current list
 *       price, unless the client has no price of its own), then reviewed: lines,
 *       discount, due date and billing email can all be changed before sending.</li>
 *   <li><b>Send</b> is one transaction that locks the invoice, moves it DRAFT → SENT
 *       (lines and totals frozen, link token minted — only its SHA-256 hash is stored)
 *       and marks its reserved charges BILLED. Only after that commits is the email
 *       sent. If the email fails, one compensating transaction puts the invoice back
 *       to DRAFT and its charges back to UNBILLED.</li>
 *   <li><b>Renewal invoices</b> are identified by their billing date
 *       ({@code period_start}, the client's end date when created, never editable) —
 *       not by the due date, which can be changed in review. One live renewal per
 *       client per billing date (service check + V11).</li>
 *   <li><b>Charges</b> are reserved with a conditional update, so two invoices created
 *       at the same moment can never both hold one charge.</li>
 *   <li><b>Re-send</b> replaces the token, so earlier links stop working;
 *       <b>Void</b> revokes it and releases the invoice's charges.</li>
 *   <li><b>Mark paid</b> records the payment and marks the invoice's charges paid. It
 *       does not change the client's subscription dates or payment status: renewing
 *       the subscription stays an explicit Service Admin action on the client.</li>
 * </ul>
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    /** The invoice link stays valid through this many days after the due date. */
    public static final int LINK_VALID_DAYS_AFTER_DUE = 60;
    /** Billing → Upcoming lists clients whose next billing date is within this many days. */
    public static final int UPCOMING_DAYS = 10;
    static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000.00");
    public static final List<String> PAYMENT_METHODS = List.of("CHECK", "BANK_TRANSFER", "CASH", "CARD", "OTHER");
    static final List<String> OPEN_OR_PAID = List.of(BillingInvoice.DRAFT, BillingInvoice.SENT, BillingInvoice.PAID);
    static final List<String> OPEN = List.of(BillingInvoice.DRAFT, BillingInvoice.SENT);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);
    private static final DateTimeFormatter LONG_DAY = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BillingInvoiceRepository invoices;
    private final BillingInvoiceLineRepository lines;
    private final ClientChargeRepository charges;
    private final ServiceClientRepository clients;
    private final SubscriptionRequestRepository requests;
    private final SubscriptionLifecycleService lifecycle;
    private final EmailService email;
    private final PlatformSettingService settings;

    @Value("${app.base-url:}")
    private String baseUrl;

    public BillingService(BillingInvoiceRepository invoices, BillingInvoiceLineRepository lines,
                          ClientChargeRepository charges, ServiceClientRepository clients,
                          SubscriptionRequestRepository requests, SubscriptionLifecycleService lifecycle,
                          EmailService email, PlatformSettingService settings) {
        this.invoices = invoices;
        this.lines = lines;
        this.charges = charges;
        this.clients = clients;
        this.requests = requests;
        this.lifecycle = lifecycle;
        this.email = email;
        this.settings = settings;
    }

    /** Test seam. */
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    /**
     * Programmatic transactions for the steps that must be all-or-nothing but cannot
     * be a single {@code @Transactional} method: the send transaction and its
     * compensation (the email goes out BETWEEN them), and the renewal-draft insert
     * (losing V11's race must roll back only that insert so the winner can be read).
     * Required in the application; unit tests construct the service without Spring and
     * run the same steps without a transaction.
     */
    private org.springframework.transaction.support.TransactionTemplate tx;
    @org.springframework.beans.factory.annotation.Autowired
    public void setTransactionManager(org.springframework.transaction.PlatformTransactionManager tm) {
        this.tx = new org.springframework.transaction.support.TransactionTemplate(tm);
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return tx == null ? work.get() : tx.execute(status -> work.get());
    }

    // ══ Additional charges ═══════════════════════════════════════════════

    public record ChargeForm(String clientId, String description, String amount, String billingPeriod, String note) {}

    public ClientCharge createCharge(ChargeForm f, String actor) {
        if (f == null || blank(f.clientId())) throw new IllegalArgumentException("Choose the client to charge.");
        ServiceClient sc = activeClient(f.clientId().trim());
        ClientCharge c = new ClientCharge();
        c.setClientId(sc.getClientId());
        applyChargeFields(c, f);
        c.setStatus(ClientCharge.UNBILLED);
        c.setCreatedAt(LocalDateTime.now());
        c.setCreatedBy(actor);
        charges.save(c);
        log.info("Billing: charge {} created for {} ({} {}) by {}", c.getId(), c.getClientId(),
                c.getAmount(), c.getBillingPeriod(), actor);
        return c;
    }

    /**
     * Edits an unbilled charge; when it sits on a draft invoice, that draft's line
     * follows. The draft is locked first (a send of it waits, or has already won and
     * this edit is refused), and the charge is saved with its version, so an edit made
     * from a copy read before another invoice claimed it is refused.
     */
    @Transactional
    public ClientCharge updateCharge(Long id, ChargeForm f, String actor) {
        ClientCharge c = charge(id);
        if (!ClientCharge.UNBILLED.equals(c.getStatus())) {
            throw new IllegalArgumentException("Only an unbilled charge can be edited (this one is "
                    + c.getStatus().toLowerCase() + ").");
        }
        BillingInvoice inv = null;
        if (c.getInvoiceId() != null) {
            inv = invoices.lockById(c.getInvoiceId()).orElseThrow(() -> new IllegalArgumentException("Invoice not found."));
            if (!BillingInvoice.DRAFT.equals(inv.getStatus())) {
                throw new IllegalArgumentException("This charge is on invoice " + inv.getInvoiceNumber() + ", which was already sent.");
            }
        }
        applyChargeFields(c, f);
        c.setUpdatedAt(LocalDateTime.now());
        c.setUpdatedBy(actor);
        try {
            charges.saveAndFlush(c);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            throw chargeChanged();
        }
        if (inv != null) {
            List<BillingInvoiceLine> ls = lines.findByInvoiceIdOrderBySortOrderAscIdAsc(inv.getId());
            for (BillingInvoiceLine l : ls) {
                if (Objects.equals(l.getChargeId(), c.getId())) {
                    l.setAmount(c.getAmount());
                    l.setDescription(chargeLineText(c));
                    lines.save(l);
                }
            }
            recomputeTotals(inv, ls);
            inv.setUpdatedAt(LocalDateTime.now());
            inv.setUpdatedBy(actor);
            invoices.save(inv);
        }
        return c;
    }

    public void voidCharge(Long id, String actor) {
        ClientCharge c = charge(id);
        requireFreeUnbilled(c, "voided");
        if (charges.voidFree(c.getId(), LocalDateTime.now(), actor) != 1) throw chargeChanged();
    }

    /** A charge paid on its own, outside any invoice. Charges on a sent invoice are paid with that invoice. */
    public void markChargePaid(Long id, LocalDate paidDate, String method, String actor) {
        ClientCharge c = charge(id);
        requireFreeUnbilled(c, "marked paid on its own");
        String m = paymentMethod(method);
        if (charges.payFree(c.getId(), paidDate != null ? paidDate : AppClock.today(), m, LocalDateTime.now(), actor) != 1) {
            throw chargeChanged();
        }
    }

    private static IllegalArgumentException chargeChanged() {
        return new IllegalArgumentException("This charge was changed at the same moment (for example, added to an invoice). "
                + "Refresh the list and try again.");
    }

    private void requireFreeUnbilled(ClientCharge c, String verb) {
        if (!ClientCharge.UNBILLED.equals(c.getStatus())) {
            throw new IllegalArgumentException("Only an unbilled charge can be " + verb + " (this one is "
                    + c.getStatus().toLowerCase() + ").");
        }
        if (c.getInvoiceId() != null) {
            String no = invoices.findById(c.getInvoiceId()).map(BillingInvoice::getInvoiceNumber).orElse("a draft invoice");
            throw new IllegalArgumentException("This charge is on draft invoice " + no
                    + ". Remove it from that invoice first.");
        }
    }

    private void applyChargeFields(ClientCharge c, ChargeForm f) {
        if (f == null) throw new IllegalArgumentException("Please complete the charge.");
        if (blank(f.description())) throw new IllegalArgumentException("Description is required.");
        if (f.description().trim().length() > 200) throw new IllegalArgumentException("Description must be 200 characters or fewer.");
        BigDecimal amt = amount(f.amount(), "Amount");
        if (amt.signum() <= 0) throw new IllegalArgumentException("Amount must be more than $0.00.");
        String period = blank(f.billingPeriod()) ? AppClock.today().toString().substring(0, 7) : f.billingPeriod().trim();
        if (!period.matches("^\\d{4}-(0[1-9]|1[0-2])$")) throw new IllegalArgumentException("Billing period must be a month, e.g. 2026-11.");
        if (!blank(f.note()) && f.note().trim().length() > 500) throw new IllegalArgumentException("Note must be 500 characters or fewer.");
        c.setDescription(f.description().trim());
        c.setAmount(amt);
        c.setBillingPeriod(period);
        c.setNote(blank(f.note()) ? null : f.note().trim());
    }

    // ══ Creating drafts ══════════════════════════════════════════════════

    /** The draft (or the already existing invoice) and whether it was just created. */
    public record Draft(BillingInvoice invoice, boolean created) {}

    /**
     * The renewal invoice for the client's next billing date (its end date): the
     * existing one if there is one (draft, sent or paid), otherwise a new draft with
     * the subscription at the client's own price plus its unbilled charges.
     */
    public Draft createRenewalDraft(String clientId, String actor) {
        ServiceClient sc = activeClient(clientId);
        if (!isPaidPlanClient(sc)) {
            throw new IllegalArgumentException("Renewal invoices are for clients on a paid plan. For a trial, use "
                    + "Create invoice on its Subscription Request; for other amounts, create a manual invoice.");
        }
        if (sc.getEndDate() == null) throw new IllegalArgumentException("This client has no end date (next billing date).");
        LocalDate due = sc.getEndDate();   // billing date = the period start; also the initial due date
        Optional<BillingInvoice> existing = renewalFor(sc.getClientId(), due);
        if (existing.isPresent()) return new Draft(existing.get(), false);

        String freq = frequencyOf(sc);
        Optional<SubscriptionPlan> plan = lifecycle.planFor(sc.getSubscriptionType());
        BigDecimal price = effectivePrice(sc, plan.orElse(null), freq);
        LocalDate end = periodEnd(due, freq);

        BillingInvoice inv = newInvoice(sc, BillingInvoice.KIND_RENEWAL, due, actor);
        inv.setPlanCode(SubscriptionService.toPlanCode(sc.getSubscriptionType()));
        inv.setBillingFrequency(freq);
        inv.setPeriodStart(due);
        inv.setPeriodEnd(end);
        List<BillingInvoiceLine> ls = new ArrayList<>();
        ls.add(line(BillingInvoiceLine.SUBSCRIPTION,
                subscriptionText(plan.map(SubscriptionPlan::getPlanName).orElse(inv.getPlanCode()), freq, due, end), price, null));
        try {
            return new Draft(inTransaction(() -> persistDraft(inv, ls, YearMonth.from(due), actor)), true);
        } catch (DataIntegrityViolationException race) {
            // The reminder job (or another admin) created it at the same moment — V11's index
            // on (client_id, period_start) refused the second insert.
            return renewalFor(sc.getClientId(), due).map(i -> new Draft(i, false)).orElseThrow(() -> race);
        }
    }

    /** A draft for a Subscription Request: the requested plan at its list price for the requested frequency. */
    @Transactional
    public Draft createRequestDraft(Long requestId, String actor) {
        SubscriptionRequest r = requests.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Subscription request not found."));
        if (!SubscriptionRequestService.OPEN.contains(r.getStatus())) {
            throw new IllegalArgumentException("This subscription request is not open any more. Refresh the list.");
        }
        if (Boolean.TRUE.equals(r.getRegisterAsNewClient())) {
            throw new IllegalArgumentException("This request comes from a sample-data trial. Register the new client "
                    + "first, then create the invoice for that client from Billing.");
        }
        List<BillingInvoice> open = invoices.findBySubscriptionRequestIdAndStatusIn(r.getId(), OPEN_OR_PAID);
        if (!open.isEmpty()) return new Draft(open.get(0), false);

        ServiceClient sc = activeClient(r.getClientId());
        SubscriptionPlan plan = lifecycle.planFor(r.getPlanCode())
                .orElseThrow(() -> new IllegalArgumentException("The requested plan " + r.getPlanCode() + " no longer exists."));
        String freq = SubscriptionLifecycleService.YEARLY.equals(r.getBillingFrequency())
                ? SubscriptionLifecycleService.YEARLY : SubscriptionLifecycleService.MONTHLY;
        BigDecimal price = SubscriptionLifecycleService.listPrice(plan, freq);
        if (price == null) price = BigDecimal.ZERO;
        LocalDate start = AppClock.today();
        LocalDate end = periodEnd(start, freq);

        BillingInvoice inv = newInvoice(sc, BillingInvoice.KIND_REQUEST, start, actor);
        inv.setSubscriptionRequestId(r.getId());
        inv.setPlanCode(plan.getPlanCode());
        inv.setBillingFrequency(freq);
        inv.setPeriodStart(start);
        inv.setPeriodEnd(end);
        String who = ((r.getFirstName() == null ? "" : r.getFirstName()) + " " + (r.getLastName() == null ? "" : r.getLastName())).trim();
        if (!who.isEmpty()) inv.setBillToName(who);
        if (!blank(r.getRegisteredEmail())) inv.setBillToEmail(r.getRegisteredEmail());
        List<BillingInvoiceLine> ls = new ArrayList<>();
        ls.add(line(BillingInvoiceLine.SUBSCRIPTION, subscriptionText(plan.getPlanName(), freq, start, end), price, null));
        return new Draft(persistDraft(inv, ls, YearMonth.from(start), actor), true);
    }

    /** A draft with the client's free unbilled charges of any month (custom lines can be added in review). */
    @Transactional
    public Draft createManualDraft(String clientId, String actor) {
        ServiceClient sc = activeClient(clientId);
        BillingInvoice inv = newInvoice(sc, BillingInvoice.KIND_MANUAL, AppClock.today(), actor);
        return new Draft(persistDraft(inv, new ArrayList<>(), null, actor), true);
    }

    private BillingInvoice newInvoice(ServiceClient sc, String kind, LocalDate due, String actor) {
        BillingInvoice inv = new BillingInvoice();
        inv.setClientId(sc.getClientId());
        inv.setKind(kind);
        inv.setStatus(BillingInvoice.DRAFT);
        inv.setChurchName(sc.getChurchName());
        inv.setBillToName(blank(sc.getName()) ? sc.getChurchName() : sc.getName());
        inv.setBillToEmail(sc.getEmail());
        inv.setDueDate(due);
        inv.setCreatedAt(LocalDateTime.now());
        inv.setCreatedBy(actor);
        return inv;
    }

    /**
     * Saves the draft, numbers it, then reserves the client's free unbilled charges —
     * those of billing month {@code maxMonth} or earlier, or of any month when null —
     * each with a conditional claim. A charge another invoice claimed first (even one
     * that was free when it was listed a moment ago) is simply not included.
     */
    private BillingInvoice persistDraft(BillingInvoice inv, List<BillingInvoiceLine> ls, YearMonth maxMonth, String actor) {
        recomputeTotals(inv, ls);
        invoices.saveAndFlush(inv);
        Long invoiceId = inv.getId();
        List<ClientCharge> candidates = maxMonth == null
                ? charges.findByClientIdAndStatusAndInvoiceIdIsNullOrderByCreatedAtAscIdAsc(inv.getClientId(), ClientCharge.UNBILLED)
                : charges.findByClientIdAndStatusAndInvoiceIdIsNullAndBillingPeriodLessThanEqualOrderByCreatedAtAscIdAsc(
                        inv.getClientId(), ClientCharge.UNBILLED, maxMonth.toString());
        int claimed = 0;
        for (ClientCharge c : candidates) {
            if (charges.claim(c.getId(), invoiceId) != 1) {
                log.info("Billing: charge {} was claimed by another invoice first — not added to invoice {}", c.getId(), invoiceId);
                continue;
            }
            ClientCharge now = charges.findById(c.getId()).orElse(c);   // fresh amount / description
            ls.add(line(BillingInvoiceLine.CHARGE, chargeLineText(now), now.getAmount(), now.getId()));
            claimed++;
        }
        int i = 0;
        for (BillingInvoiceLine l : ls) {
            l.setInvoiceId(invoiceId);
            l.setClientId(inv.getClientId());
            l.setSortOrder(i++);
            lines.save(l);
        }
        BillingInvoice fresh = claimed > 0 ? invoices.findById(invoiceId).orElse(inv) : inv;
        fresh.setInvoiceNumber(String.format("INV-%d-%05d", AppClock.today().getYear(), invoiceId));
        recomputeTotals(fresh, ls);
        inv = invoices.save(fresh);
        log.info("Billing: draft {} ({}) created for {} by {} — {} line(s), total {}", inv.getInvoiceNumber(),
                inv.getKind(), inv.getClientId(), actor, ls.size(), inv.getTotal());
        return inv;
    }

    // ══ Review (draft edits) ═════════════════════════════════════════════

    public record LineForm(String kind, String description, String amount, Long chargeId) {}

    public record DraftForm(Long version, String billToName, String billToEmail, LocalDate dueDate, String note,
                            String discount, List<LineForm> lines) {}

    /**
     * Replaces a draft's editable fields and lines. A charge line bills its charge at
     * the charge's amount (edit the charge to change it); a charge removed from the
     * lines is released, and any of the client's free unbilled charges may be added.
     */
    @Transactional
    public BillingInvoice updateDraft(Long id, DraftForm f, String actor) {
        if (id == null) throw new IllegalArgumentException("Invoice not found.");
        BillingInvoice inv = invoices.lockById(id).orElseThrow(() -> new IllegalArgumentException("Invoice not found."));
        if (!BillingInvoice.DRAFT.equals(inv.getStatus())) {
            throw new IllegalArgumentException("Only a draft can be edited. This invoice is " + inv.getStatus().toLowerCase() + ".");
        }
        if (f == null) throw new IllegalArgumentException("Nothing to save.");
        if (f.version() != null && !f.version().equals(inv.getVersion())) throw stale();
        if (f.lines() == null || f.lines().size() > 50) throw new IllegalArgumentException("An invoice has 1 to 50 lines.");

        String to = blank(f.billToEmail()) ? null : f.billToEmail().trim();
        if (to == null || to.length() > 320 || !to.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new IllegalArgumentException("Enter a valid billing email address.");
        }
        if (!blank(f.billToName()) && f.billToName().trim().length() > 200) throw new IllegalArgumentException("Bill-to name must be 200 characters or fewer.");
        if (f.dueDate() == null) throw new IllegalArgumentException("Due date is required.");
        if (linkExpiry(f.dueDate()).isBefore(AppClock.today())) {
            throw new IllegalArgumentException("That due date is more than " + LINK_VALID_DAYS_AFTER_DUE
                    + " days ago, so the invoice link would already be expired. Choose a later due date.");
        }
        if (!blank(f.note()) && f.note().trim().length() > 2000) throw new IllegalArgumentException("Note must be 2000 characters or fewer.");

        Map<Long, ClientCharge> reserved = new HashMap<>();
        for (ClientCharge c : charges.findByInvoiceId(inv.getId())) reserved.put(c.getId(), c);

        List<BillingInvoiceLine> fresh = new ArrayList<>();
        Set<Long> keptCharges = new HashSet<>();
        for (LineForm lf : f.lines()) {
            if (lf == null) continue;
            String kind = blank(lf.kind()) ? BillingInvoiceLine.CUSTOM : lf.kind().trim().toUpperCase();
            if (lf.chargeId() != null || BillingInvoiceLine.CHARGE.equals(kind)) {
                if (lf.chargeId() == null) throw new IllegalArgumentException("A charge line must name its charge.");
                ClientCharge c = reserved.get(lf.chargeId());
                if (c == null) {
                    c = charges.findById(lf.chargeId()).orElse(null);
                    if (c == null || !inv.getClientId().equals(c.getClientId()) || !ClientCharge.UNBILLED.equals(c.getStatus())
                            || c.getInvoiceId() != null) {
                        throw new IllegalArgumentException("A charge on this invoice is no longer available. Reopen the invoice.");
                    }
                }
                if (!keptCharges.add(c.getId())) throw new IllegalArgumentException("A charge can appear only once on an invoice.");
                String text = blank(lf.description()) ? chargeLineText(c) : lineText(lf.description());
                fresh.add(line(BillingInvoiceLine.CHARGE, text, c.getAmount(), c.getId()));
                reserved.put(c.getId(), c);
            } else {
                if (!BillingInvoiceLine.SUBSCRIPTION.equals(kind)) kind = BillingInvoiceLine.CUSTOM;
                if (blank(lf.description())) throw new IllegalArgumentException("Every line needs a description.");
                BigDecimal amt = amount(lf.amount(), "Line amount");
                fresh.add(line(kind, lineText(lf.description()), amt, null));
            }
        }
        if (fresh.isEmpty()) throw new IllegalArgumentException("An invoice needs at least one line.");

        BigDecimal subtotal = sum(fresh);
        BigDecimal discount = blank(f.discount()) ? BigDecimal.ZERO : amount(f.discount(), "Discount");
        if (discount.compareTo(subtotal) > 0) {
            throw new IllegalArgumentException("Discount cannot be more than the subtotal (" + money(subtotal) + ").");
        }

        // Lines: replace.
        List<BillingInvoiceLine> old = lines.findByInvoiceIdOrderBySortOrderAscIdAsc(inv.getId());
        lines.deleteAll(old);
        int i = 0;
        for (BillingInvoiceLine l : fresh) {
            l.setInvoiceId(inv.getId());
            l.setClientId(inv.getClientId());
            l.setSortOrder(i++);
            lines.save(l);
        }
        // Charges: claim the added ones (conditionally — another invoice may have taken one
        // a moment ago), release the removed ones.
        for (ClientCharge c : reserved.values()) {
            boolean keep = keptCharges.contains(c.getId());
            boolean onThis = inv.getId().equals(c.getInvoiceId());
            if (keep && !onThis && charges.claim(c.getId(), inv.getId()) != 1) {
                throw new IllegalArgumentException("A charge you added was just put on another invoice. Reopen the invoice.");
            }
            if (!keep && onThis) charges.release(c.getId(), inv.getId());
        }

        inv.setBillToName(blank(f.billToName()) ? null : f.billToName().trim());
        inv.setBillToEmail(to);
        inv.setDueDate(f.dueDate());
        inv.setNote(blank(f.note()) ? null : f.note().trim());
        inv.setSubtotal(subtotal);
        inv.setDiscount(discount.setScale(2, RoundingMode.HALF_UP));
        inv.setTotal(subtotal.subtract(discount).setScale(2, RoundingMode.HALF_UP));
        inv.setUpdatedAt(LocalDateTime.now());
        inv.setUpdatedBy(actor);
        try {
            return invoices.saveAndFlush(inv);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            throw stale();
        }
    }

    // ══ Send / re-send ═══════════════════════════════════════════════════

    /** Result of a send: the secure link (shown once to the Service Admin for copying). */
    public record Sent(BillingInvoice invoice, String link) {}

    /**
     * Freezes the draft and emails its link. Refused if the draft changed since
     * {@code version} was read (pass null to skip that check, as the reminder job does).
     * If the email fails the invoice is back to DRAFT and an {@link IllegalStateException} says why.
     */
    public Sent send(Long id, Long version, String actor) {
        return send(id, version, actor, null);
    }

    /** What the send transaction committed, for the email step. */
    private record Prepared(BillingInvoice invoice, List<BillingInvoiceLine> lines, String token, String hash) {}

    Sent send(Long id, Long version, String actor, String reminderNote) {
        String token = newToken();
        String hash = hash(token);
        LocalDateTime now = LocalDateTime.now();

        // 1. One transaction: lock the invoice, DRAFT → SENT (totals frozen, link minted),
        //    its reserved charges → BILLED. Any refusal or mismatch rolls back all of it.
        Prepared p = inTransaction(() -> {
            if (id == null) throw new IllegalArgumentException("Invoice not found.");
            BillingInvoice inv = invoices.lockById(id).orElseThrow(() -> new IllegalArgumentException("Invoice not found."));
            if (!BillingInvoice.DRAFT.equals(inv.getStatus())) {
                throw new IllegalArgumentException("This invoice was already " + inv.getStatus().toLowerCase() + ". Refresh the list.");
            }
            if (version != null && !version.equals(inv.getVersion())) throw stale();
            List<BillingInvoiceLine> ls = lines.findByInvoiceIdOrderBySortOrderAscIdAsc(inv.getId());
            if (ls.isEmpty()) throw new IllegalArgumentException("Add at least one line before sending.");
            if (blank(inv.getBillToEmail())) throw new IllegalArgumentException("Add a billing email before sending.");
            if (inv.getDueDate() == null) throw new IllegalArgumentException("Add a due date before sending.");
            LocalDate expires = linkExpiry(inv.getDueDate());
            if (expires.isBefore(AppClock.today())) {
                throw new IllegalArgumentException("The due date is more than " + LINK_VALID_DAYS_AFTER_DUE
                        + " days ago, so the link would already be expired. Change the due date before sending.");
            }
            BigDecimal subtotal = sum(ls);
            BigDecimal discount = inv.getDiscount() == null ? BigDecimal.ZERO : inv.getDiscount();
            if (discount.compareTo(subtotal) > 0) throw new IllegalArgumentException("Discount is more than the subtotal. Edit the invoice.");
            BigDecimal total = subtotal.subtract(discount).setScale(2, RoundingMode.HALF_UP);

            // Every charge line must bill a charge this draft still holds, unbilled.
            int chargeLines = 0;
            for (BillingInvoiceLine l : ls) {
                if (l.getChargeId() == null) continue;
                chargeLines++;
                ClientCharge c = charges.findById(l.getChargeId()).orElse(null);
                if (c == null || !inv.getId().equals(c.getInvoiceId()) || !ClientCharge.UNBILLED.equals(c.getStatus())) {
                    throw new IllegalArgumentException("A charge on this invoice is no longer available. Reopen the invoice.");
                }
            }
            Long seen = inv.getVersion();
            if (invoices.markSent(inv.getId(), seen, subtotal, discount, total, hash, expires, AppClock.today(), now, actor) != 1) {
                throw stale();
            }
            int billed = charges.billForInvoice(inv.getId(), now, actor);
            if (billed != chargeLines) {
                // A charge reserved by this invoice without a line (or the reverse): never send
                // a total that differs from what is billed. Rolls back the DRAFT → SENT too.
                throw new IllegalArgumentException("The invoice's charges changed while it was being sent. "
                        + "Reopen the invoice and try again.");
            }
            return new Prepared(invoice(id), ls, token, hash);
        });

        // 2. The email, only after the transaction committed.
        String link = link(p.token());
        try {
            deliver(p.invoice(), p.lines(), link, reminderNote);
        } catch (Exception e) {
            log.error("Billing: invoice {} email to {} failed — {}", p.invoice().getInvoiceNumber(), p.invoice().getBillToEmail(), e.toString());
            // 3. One compensating transaction: SENT → DRAFT and its charges BILLED → UNBILLED.
            //    Conditional on the link this send minted, so an invoice voided or paid in the
            //    meantime is left as it is.
            Integer undone;
            try {
                undone = inTransaction(() -> {
                    int n = invoices.revertSend(id, p.hash());
                    if (n == 1) charges.unbillForInvoice(id, LocalDateTime.now(), actor);
                    return n;
                });
            } catch (RuntimeException undoFailed) {
                log.error("Billing: invoice {} could not be returned to draft after the failed email — {}",
                        p.invoice().getInvoiceNumber(), undoFailed.toString());
                throw new IllegalStateException("The invoice email could not be sent (" + e.getMessage()
                        + "), and the invoice could not be returned to draft, so it is marked Sent with its charges billed. "
                        + "Use Re-send to email it, or Void it.");
            }
            if (undone != 1) {
                throw new IllegalStateException("The invoice email could not be sent (" + e.getMessage()
                        + "). The invoice was changed by someone else in the meantime; refresh the list.");
            }
            throw new IllegalStateException("The invoice email could not be sent (" + e.getMessage()
                    + "). The invoice is still a draft and its charges are unbilled; nothing was sent.");
        }
        log.info("Billing: invoice {} sent to {} by {} — total {}", p.invoice().getInvoiceNumber(),
                p.invoice().getBillToEmail(), actor, p.invoice().getTotal());
        return new Sent(p.invoice(), link);
    }

    /** Emails a sent invoice again with a NEW link; the previous link stops working. */
    public Sent resend(Long id, String actor) {
        return resend(id, actor, null);
    }

    Sent resend(Long id, String actor, String reminderNote) {
        BillingInvoice inv = invoice(id);
        if (!BillingInvoice.SENT.equals(inv.getStatus())) {
            throw new IllegalArgumentException("Only a sent, unpaid invoice can be re-sent. This invoice is "
                    + inv.getStatus().toLowerCase() + ".");
        }
        if (linkExpiry(inv.getDueDate()).isBefore(AppClock.today())) {
            throw new IllegalArgumentException("This invoice's link expired " + LINK_VALID_DAYS_AFTER_DUE
                    + " days after its due date, so it cannot be re-sent. Void it and create a new invoice.");
        }
        String oldHash = inv.getAccessTokenHash();
        LocalDate oldExpires = inv.getAccessTokenExpires();
        LocalDateTime oldAt = inv.getSentAt();
        String oldBy = inv.getSentBy();
        String token = newToken();
        String hash = hash(token);
        if (invoices.rotateToken(inv.getId(), oldHash, hash, linkExpiry(inv.getDueDate()), 1, LocalDateTime.now(), actor) != 1) {
            throw stale();
        }
        BillingInvoice now = invoice(id);
        String link = link(token);
        try {
            deliver(now, lines.findByInvoiceIdOrderBySortOrderAscIdAsc(inv.getId()), link, reminderNote);
        } catch (Exception e) {
            invoices.rotateToken(inv.getId(), hash, oldHash, oldExpires, -1, oldAt, oldBy);
            log.error("Billing: re-send of {} to {} failed — previous link kept. {}", inv.getInvoiceNumber(), inv.getBillToEmail(), e.toString());
            throw new IllegalStateException("The invoice email could not be sent (" + e.getMessage()
                    + "). The previous link still works.");
        }
        log.info("Billing: invoice {} re-sent to {} by {}", inv.getInvoiceNumber(), inv.getBillToEmail(), actor);
        return new Sent(now, link);
    }

    // ══ Mark paid / void ═════════════════════════════════════════════════

    @Transactional
    public void markPaid(Long id, LocalDate paidDate, String method, String reference, String actor) {
        BillingInvoice inv = invoice(id);
        String m = paymentMethod(method);
        String ref = blank(reference) ? null : reference.trim();
        if (ref != null && ref.length() > 120) throw new IllegalArgumentException("Reference must be 120 characters or fewer.");
        LocalDate paid = paidDate != null ? paidDate : AppClock.today();
        if (paid.isAfter(AppClock.today())) throw new IllegalArgumentException("Paid date cannot be in the future.");
        if (invoices.markPaid(inv.getId(), paid, m, ref, actor, LocalDateTime.now()) != 1) {
            throw new IllegalArgumentException("Only a sent, unpaid invoice can be marked paid. Refresh the list.");
        }
        // Same transaction as SENT → PAID.
        charges.payForInvoice(inv.getId(), paid, m, LocalDateTime.now(), actor);
        log.info("Billing: invoice {} marked paid ({} {}) by {}", inv.getInvoiceNumber(), m, paid, actor);
    }

    /** Voids a draft or a sent invoice: the link stops working and its charges are unbilled again. */
    @Transactional
    public void voidInvoice(Long id, String reason, String actor) {
        BillingInvoice inv = invoice(id);
        String why = blank(reason) ? null : (reason.trim().length() > 500 ? reason.trim().substring(0, 500) : reason.trim());
        if (invoices.markVoid(inv.getId(), why, actor, LocalDateTime.now()) != 1) {
            throw new IllegalArgumentException("Only a draft or a sent, unpaid invoice can be voided. Refresh the list.");
        }
        // Same transaction as → VOID; runs once, because only one void can succeed.
        charges.releaseForInvoice(inv.getId(), LocalDateTime.now(), actor);
        log.info("Billing: invoice {} voided by {}{}", inv.getInvoiceNumber(), actor, why == null ? "" : " — " + why);
    }

    // ══ Secure invoice page ══════════════════════════════════════════════

    /**
     * The invoice a link token opens, for the public invoice page — or empty for any
     * token that is unknown, superseded, expired, for a draft or void invoice, or for a
     * different church than the one signed in ({@code sessionClientId}, null when
     * nobody is). Every refusal is the same empty result, so the page cannot be used
     * to probe tokens. No ids or client ids are returned.
     */
    public Optional<Map<String, Object>> publicView(String token, String sessionClientId) {
        Optional<BillingInvoice> found = invoiceForToken(token, sessionClientId);
        if (found.isEmpty()) return Optional.empty();
        BillingInvoice inv = found.get();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("invoiceNumber", inv.getInvoiceNumber());
        m.put("churchName", inv.getChurchName());
        m.put("billToName", inv.getBillToName());
        m.put("issueDate", str(inv.getIssueDate()));
        m.put("dueDate", str(inv.getDueDate()));
        m.put("periodStart", str(inv.getPeriodStart()));
        m.put("periodEnd", str(inv.getPeriodEnd()));
        List<Map<String, Object>> ls = new ArrayList<>();
        for (BillingInvoiceLine l : lines.findByInvoiceIdOrderBySortOrderAscIdAsc(inv.getId())) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("description", l.getDescription());
            lm.put("amount", l.getAmount());
            ls.add(lm);
        }
        m.put("lines", ls);
        m.put("subtotal", inv.getSubtotal());
        m.put("discount", inv.getDiscount());
        m.put("total", inv.getTotal());
        m.put("note", inv.getNote());
        boolean paid = BillingInvoice.PAID.equals(inv.getStatus());
        m.put("state", paid ? "PAID" : (inv.getDueDate() != null && inv.getDueDate().isBefore(AppClock.today()) ? "OVERDUE" : "DUE"));
        if (paid) m.put("paidDate", str(inv.getPaidDate()));
        m.put("supportEmail", settings.supportEmail());
        return Optional.of(m);
    }

    /**
     * The invoice a link token opens — SENT or PAID, link not expired, and not another
     * church's (when a church session is present). Empty for every refusal alike. Shared
     * by the invoice page and card payment (Phase 6), so both apply exactly the same rules.
     */
    public Optional<BillingInvoice> invoiceForToken(String token, String sessionClientId) {
        if (token == null || token.isBlank() || token.length() > 200) return Optional.empty();
        Optional<BillingInvoice> found = invoices.findByAccessTokenHash(hash(token.trim()));
        if (found.isEmpty()) return Optional.empty();
        BillingInvoice inv = found.get();
        if (!BillingInvoice.SENT.equals(inv.getStatus()) && !BillingInvoice.PAID.equals(inv.getStatus())) return Optional.empty();
        if (inv.getAccessTokenExpires() == null || inv.getAccessTokenExpires().isBefore(AppClock.today())) return Optional.empty();
        if (sessionClientId != null && !sessionClientId.isBlank() && !sessionClientId.equals(inv.getClientId())) return Optional.empty();
        return Optional.of(inv);
    }

    // ══ Service Admin views ══════════════════════════════════════════════

    public List<Map<String, Object>> listInvoices() {
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDate today = AppClock.today();
        for (BillingInvoice i : invoices.findTop500ByOrderByIdDesc()) out.add(summary(i, today));
        return out;
    }

    public Map<String, Object> detail(Long id) {
        BillingInvoice inv = invoice(id);
        Map<String, Object> m = summary(inv, AppClock.today());
        m.put("billToName", inv.getBillToName());
        m.put("note", inv.getNote());
        m.put("subtotal", inv.getSubtotal());
        m.put("discount", inv.getDiscount());
        m.put("periodStart", str(inv.getPeriodStart()));
        m.put("periodEnd", str(inv.getPeriodEnd()));
        m.put("planCode", inv.getPlanCode());
        m.put("billingFrequency", inv.getBillingFrequency());
        m.put("paymentReference", inv.getPaymentReference());
        m.put("paidRecordedBy", inv.getPaidRecordedBy());
        m.put("voidReason", inv.getVoidReason());
        m.put("voidedBy", inv.getVoidedBy());
        m.put("createdBy", inv.getCreatedBy());
        m.put("linkExpires", str(inv.getAccessTokenExpires()));
        m.put("stripePaymentIntentId", inv.getStripePaymentIntentId());
        m.put("receiptSentAt", inv.getReceiptSentAt() != null ? inv.getReceiptSentAt().toString() : null);
        List<Map<String, Object>> ls = new ArrayList<>();
        for (BillingInvoiceLine l : lines.findByInvoiceIdOrderBySortOrderAscIdAsc(inv.getId())) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("kind", l.getKind());
            lm.put("description", l.getDescription());
            lm.put("amount", l.getAmount());
            lm.put("chargeId", l.getChargeId());
            ls.add(lm);
        }
        m.put("lines", ls);
        List<Map<String, Object>> avail = new ArrayList<>();
        if (BillingInvoice.DRAFT.equals(inv.getStatus())) {
            for (ClientCharge c : charges.findByClientIdAndStatusAndInvoiceIdIsNullOrderByCreatedAtAscIdAsc(inv.getClientId(), ClientCharge.UNBILLED)) {
                avail.add(chargeMap(c, null));
            }
        }
        m.put("availableCharges", avail);
        return m;
    }

    private Map<String, Object> summary(BillingInvoice i, LocalDate today) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("version", i.getVersion());
        m.put("invoiceNumber", i.getInvoiceNumber());
        m.put("clientId", i.getClientId());
        m.put("churchName", i.getChurchName());
        m.put("kind", i.getKind());
        m.put("subscriptionRequestId", i.getSubscriptionRequestId());
        m.put("status", i.getStatus());
        m.put("overdue", BillingInvoice.SENT.equals(i.getStatus()) && i.getDueDate() != null && i.getDueDate().isBefore(today));
        m.put("billToEmail", i.getBillToEmail());
        m.put("issueDate", str(i.getIssueDate()));
        m.put("dueDate", str(i.getDueDate()));
        m.put("billingDate", BillingInvoice.KIND_RENEWAL.equals(i.getKind()) ? str(i.getPeriodStart()) : null);
        m.put("total", i.getTotal());
        m.put("sentAt", i.getSentAt() != null ? i.getSentAt().toString() : null);
        m.put("sentBy", i.getSentBy());
        m.put("sendCount", i.getSendCount());
        m.put("paidDate", str(i.getPaidDate()));
        m.put("paymentMethod", i.getPaymentMethod());
        m.put("createdAt", i.getCreatedAt() != null ? i.getCreatedAt().toString() : null);
        return m;
    }

    public List<Map<String, Object>> listCharges() {
        Map<String, String> names = churchNames();
        Map<Long, String> numbers = new HashMap<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (ClientCharge c : charges.findTop500ByOrderByCreatedAtDescIdDesc()) {
            String no = null;
            if (c.getInvoiceId() != null) {
                no = numbers.computeIfAbsent(c.getInvoiceId(),
                        k -> invoices.findById(k).map(BillingInvoice::getInvoiceNumber).orElse(null));
            }
            Map<String, Object> m = chargeMap(c, no);
            m.put("churchName", names.get(c.getClientId()));
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> chargeMap(ClientCharge c, String invoiceNumber) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("clientId", c.getClientId());
        m.put("description", c.getDescription());
        m.put("amount", c.getAmount());
        m.put("billingPeriod", c.getBillingPeriod());
        m.put("status", c.getStatus());
        m.put("invoiceId", c.getInvoiceId());
        m.put("invoiceNumber", invoiceNumber);
        m.put("paidDate", str(c.getPaidDate()));
        m.put("paymentMethod", c.getPaymentMethod());
        m.put("note", c.getNote());
        m.put("createdAt", c.getCreatedAt() != null ? c.getCreatedAt().toString() : null);
        m.put("createdBy", c.getCreatedBy());
        return m;
    }

    /** Paid-plan clients whose next billing date (end date) is today or within {@link #UPCOMING_DAYS} days. */
    public List<Map<String, Object>> upcoming() {
        LocalDate today = AppClock.today();
        List<Map<String, Object>> out = new ArrayList<>();
        for (ServiceClient sc : billableDueBetween(today, today.plusDays(UPCOMING_DAYS))) {
            String freq = frequencyOf(sc);
            Optional<SubscriptionPlan> plan = lifecycle.planFor(sc.getSubscriptionType());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", sc.getId());
            m.put("clientId", sc.getClientId());
            m.put("churchName", sc.getChurchName());
            m.put("plan", plan.map(SubscriptionPlan::getPlanName).orElse(sc.getSubscriptionType()));
            m.put("billingFrequency", freq);
            m.put("price", effectivePrice(sc, plan.orElse(null), freq));
            m.put("priceOverridden", Boolean.TRUE.equals(sc.getPriceOverridden()));
            m.put("dueDate", str(sc.getEndDate()));
            m.put("daysLeft", ChronoUnit.DAYS.between(today, sc.getEndDate()));
            m.put("email", sc.getEmail());
            renewalFor(sc.getClientId(), sc.getEndDate()).ifPresent(i -> {
                m.put("invoiceId", i.getId());
                m.put("invoiceNumber", i.getInvoiceNumber());
                m.put("invoiceStatus", i.getStatus());
                m.put("invoiceTotal", i.getTotal());
            });
            out.add(m);
        }
        return out;
    }

    // ══ Used by the reminder job ═════════════════════════════════════════

    /** Active paid-plan clients (not demo / sample trials, billing not "Not Required") due in the range. */
    List<ServiceClient> billableDueBetween(LocalDate from, LocalDate to) {
        List<ServiceClient> out = new ArrayList<>();
        for (ServiceClient sc : clients.findByEndDateBetweenAndStatusAndDeleteFlagFalseOrderByEndDateAscIdAsc(from, to, "Active")) {
            if (isPaidPlanClient(sc)) out.add(sc);
        }
        return out;
    }

    /**
     * The client's live (draft, sent or paid) renewal invoice for a BILLING DATE — its
     * {@code period_start}, never the editable due date.
     */
    Optional<BillingInvoice> renewalFor(String clientId, LocalDate billingDate) {
        return invoices.findByClientIdAndKindAndPeriodStartAndStatusIn(clientId, BillingInvoice.KIND_RENEWAL, billingDate, OPEN_OR_PAID)
                .stream().findFirst();
    }

    /** True when any renewal invoice — void included — exists for that billing date. */
    boolean anyRenewalFor(String clientId, LocalDate billingDate) {
        return !invoices.findByClientIdAndKindAndPeriodStart(clientId, BillingInvoice.KIND_RENEWAL, billingDate).isEmpty();
    }

    /** The amount a renewal would bill: the client's own price, else the plan's list price, else 0. */
    BigDecimal renewalAmount(ServiceClient sc) {
        String freq = frequencyOf(sc);
        return effectivePrice(sc, lifecycle.planFor(sc.getSubscriptionType()).orElse(null), freq);
    }

    static boolean isPaidPlanClient(ServiceClient sc) {
        if (sc == null || Boolean.TRUE.equals(sc.getDeleteFlag())) return false;
        String cid = sc.getClientId();
        if (cid == null || cid.startsWith(TestDataService.DEMO_CLIENT_PREFIX) || cid.startsWith(TestDataService.TRIAL_CLIENT_PREFIX)) return false;
        String code = SubscriptionService.toPlanCode(sc.getSubscriptionType());
        if (code == null || TrialPolicy.TRIAL_PLAN_CODE.equalsIgnoreCase(code) || "FREE".equals(code)) return false;
        return !"NOT_REQUIRED".equalsIgnoreCase(sc.getPaymentStatus());
    }

    // ══ Email ════════════════════════════════════════════════════════════

    private void deliver(BillingInvoice inv, List<BillingInvoiceLine> ls, String link, String reminderNote) throws Exception {
        String church = inv.getChurchName() != null ? inv.getChurchName() : inv.getClientId();
        String subject = (reminderNote != null ? "Reminder: invoice " : "Invoice ") + inv.getInvoiceNumber()
                + " from ChurchGeniusPro — " + church;
        email.sendComposed(List.of(inv.getBillToEmail()), null, subject, invoiceEmailHtml(inv, ls, link, reminderNote),
                null, "ChurchGeniusPro Billing");
    }

    String invoiceEmailHtml(BillingInvoice inv, List<BillingInvoiceLine> ls, String link, String reminderNote) {
        String church = esc(inv.getChurchName() != null ? inv.getChurchName() : "your church");
        String due = inv.getDueDate() != null ? inv.getDueDate().format(LONG_DAY) : "—";
        StringBuilder rows = new StringBuilder();
        for (BillingInvoiceLine l : ls) {
            rows.append("<tr><td style='padding:6px 0;color:#444;'>").append(esc(l.getDescription()))
                .append("</td><td style='padding:6px 0;text-align:right;color:#444;white-space:nowrap;'>")
                .append(money(l.getAmount())).append("</td></tr>");
        }
        if (inv.getDiscount() != null && inv.getDiscount().signum() > 0) {
            rows.append("<tr><td style='padding:6px 0;color:#256b38;'>Discount</td><td style='padding:6px 0;text-align:right;color:#256b38;'>−")
                .append(money(inv.getDiscount())).append("</td></tr>");
        }
        String safeLink = esc(link);
        return "<!DOCTYPE html><html><body style='margin:0;padding:0;background:#f5f6fa;font-family:-apple-system,Segoe UI,Roboto,sans-serif;'>"
             + "<table width='100%' cellpadding='0' cellspacing='0' style='padding:36px 16px;'><tr><td align='center'>"
             + "<table width='100%' cellpadding='0' cellspacing='0' style='max-width:540px;background:#fff;border-radius:14px;"
             + "overflow:hidden;box-shadow:0 4px 20px rgba(0,0,0,.08);'>"
             + "<tr><td style='background:#673147;padding:24px 36px;text-align:center;'>"
             + "<p style='margin:0;font-size:19px;font-weight:700;color:#fff;'>ChurchGeniusPro</p>"
             + "<p style='margin:6px 0 0;font-size:12px;color:rgba(255,255,255,.75);'>Invoice " + esc(inv.getInvoiceNumber()) + "</p></td></tr>"
             + "<tr><td style='padding:30px 36px;'>"
             + "<p style='margin:0 0 14px;font-size:15px;color:#1a1a2e;font-weight:600;'>Hello " + church + ",</p>"
             + (reminderNote != null ? "<p style='margin:0 0 14px;font-size:14px;color:#7a5b16;background:#fff8e1;border:1px solid #ffe0a3;"
                     + "border-radius:8px;padding:10px 12px;'>" + esc(reminderNote) + "</p>" : "")
             + "<p style='margin:0 0 16px;font-size:14px;color:#555;line-height:1.7;'>Here is your ChurchGeniusPro invoice. "
             + "The amount due is <strong>" + money(inv.getTotal()) + "</strong>, due on <strong>" + due + "</strong>.</p>"
             + "<table width='100%' style='font-size:13.5px;border-top:1px solid #eee;border-bottom:1px solid #eee;margin:0 0 18px;'>"
             + rows + "<tr><td style='padding:8px 0;font-weight:700;color:#1a1a2e;border-top:1px solid #eee;'>Amount due</td>"
             + "<td style='padding:8px 0;text-align:right;font-weight:700;color:#1a1a2e;border-top:1px solid #eee;'>" + money(inv.getTotal())
             + "</td></tr></table>"
             + "<p style='margin:0 0 20px;text-align:center;'><a href='" + safeLink + "' style='background:#673147;color:#fff;"
             + "padding:12px 26px;border-radius:8px;text-decoration:none;font-weight:600;font-size:14px;'>View invoice</a></p>"
             + "<p style='margin:0 0 10px;font-size:12px;color:#888;line-height:1.6;'>Or copy this link: " + safeLink + "</p>"
             + "<p style='margin:0;font-size:12px;color:#888;line-height:1.6;'>Questions about this invoice? Contact "
             + esc(settings.supportEmail()) + ".</p>"
             + "</td></tr></table></td></tr></table></body></html>";
    }

    // ══ helpers ══════════════════════════════════════════════════════════

    private ServiceClient activeClient(String clientId) {
        if (blank(clientId)) throw new IllegalArgumentException("Choose a client.");
        return clients.findByClientId(clientId.trim())
                .filter(sc -> !Boolean.TRUE.equals(sc.getDeleteFlag()))
                .orElseThrow(() -> new IllegalArgumentException("Client not found."));
    }

    // invoice(id) / charge(id) / requests.findById: Service Admin paths only (every caller is
    // ServiceAdminBillingController or the billing reminder job). Billing records are
    // platform records about a client, read across all clients by design; the only
    // church-facing read (publicView) looks up by token hash and checks the session's client.
    private BillingInvoice invoice(Long id) {
        if (id == null) throw new IllegalArgumentException("Invoice not found.");
        return invoices.findById(id).orElseThrow(() -> new IllegalArgumentException("Invoice not found."));
    }

    private ClientCharge charge(Long id) {
        if (id == null) throw new IllegalArgumentException("Charge not found.");
        return charges.findById(id).orElseThrow(() -> new IllegalArgumentException("Charge not found."));
    }

    private Map<String, String> churchNames() {
        Map<String, String> m = new HashMap<>();
        try {
            for (ServiceClient sc : clients.findAllByDeleteFlagFalseOrderByIdDesc()) {
                if (sc.getClientId() != null) m.put(sc.getClientId(), sc.getChurchName());
            }
        } catch (Exception ignored) { }
        return m;
    }

    private static IllegalArgumentException stale() {
        return new IllegalArgumentException("This invoice was changed by someone else (or already sent). Reopen it and try again.");
    }

    static String frequencyOf(ServiceClient sc) {
        return SubscriptionLifecycleService.YEARLY.equalsIgnoreCase(sc.getBillingFrequency())
                ? SubscriptionLifecycleService.YEARLY : SubscriptionLifecycleService.MONTHLY;
    }

    static BigDecimal effectivePrice(ServiceClient sc, SubscriptionPlan plan, String freq) {
        if (sc.getSubscriptionPrice() != null) return sc.getSubscriptionPrice().setScale(2, RoundingMode.HALF_UP);
        BigDecimal list = SubscriptionLifecycleService.listPrice(plan, freq);
        return list == null ? BigDecimal.ZERO.setScale(2) : list.setScale(2, RoundingMode.HALF_UP);
    }

    static LocalDate periodEnd(LocalDate start, String freq) {
        return (SubscriptionLifecycleService.YEARLY.equals(freq) ? start.plusYears(1) : start.plusMonths(1)).minusDays(1);
    }

    private static String subscriptionText(String planName, String freq, LocalDate start, LocalDate end) {
        return lineText((planName == null ? "Subscription" : planName) + " plan — "
                + (SubscriptionLifecycleService.YEARLY.equals(freq) ? "Yearly" : "Monthly") + " subscription ("
                + start.format(DAY) + " – " + end.format(DAY) + ")");
    }

    private static String chargeLineText(ClientCharge c) {
        return lineText(c.getDescription() + " (" + c.getBillingPeriod() + ")");
    }

    private static String lineText(String s) {
        String t = s.trim();
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    private static BillingInvoiceLine line(String kind, String description, BigDecimal amount, Long chargeId) {
        BillingInvoiceLine l = new BillingInvoiceLine();
        l.setKind(kind);
        l.setDescription(description);
        l.setAmount(amount == null ? BigDecimal.ZERO.setScale(2) : amount.setScale(2, RoundingMode.HALF_UP));
        l.setChargeId(chargeId);
        return l;
    }

    private static BigDecimal sum(List<BillingInvoiceLine> ls) {
        BigDecimal s = BigDecimal.ZERO;
        for (BillingInvoiceLine l : ls) s = s.add(l.getAmount() == null ? BigDecimal.ZERO : l.getAmount());
        return s.setScale(2, RoundingMode.HALF_UP);
    }

    private static void recomputeTotals(BillingInvoice inv, List<BillingInvoiceLine> ls) {
        BigDecimal subtotal = sum(ls);
        BigDecimal discount = inv.getDiscount() == null ? BigDecimal.ZERO : inv.getDiscount();
        if (discount.compareTo(subtotal) > 0) discount = subtotal;
        inv.setSubtotal(subtotal);
        inv.setDiscount(discount.setScale(2, RoundingMode.HALF_UP));
        inv.setTotal(subtotal.subtract(discount).setScale(2, RoundingMode.HALF_UP));
    }

    /** "$1,200.50" / "1200.5" → 1200.50; must be 0 … 1,000,000. */
    static BigDecimal amount(String v, String field) {
        BigDecimal d;
        try {
            d = SubscriptionLifecycleService.parsePrice(v);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " must be a number of $0.00 or more, e.g. 25.00.");
        }
        if (d == null) throw new IllegalArgumentException(field + " is required.");
        if (d.compareTo(MAX_AMOUNT) > 0) throw new IllegalArgumentException(field + " is too large.");
        return d;
    }

    static String paymentMethod(String m) {
        String t = blank(m) ? "" : m.trim().toUpperCase();
        if (!PAYMENT_METHODS.contains(t)) throw new IllegalArgumentException("Choose a payment method: check, bank transfer, cash, card or other.");
        return t;
    }

    /** The link works through this date (America/Chicago): exactly 60 days after the due date, however late it is sent. */
    static LocalDate linkExpiry(LocalDate due) {
        return due.plusDays(LINK_VALID_DAYS_AFTER_DUE);
    }

    private String link(String token) {
        String b = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        return b + "/invoice.html?t=" + token;
    }

    static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** SHA-256 of the token, hex — the only form stored. */
    static String hash(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String money(BigDecimal v) {
        return NumberFormat.getCurrencyInstance(Locale.US).format(v == null ? BigDecimal.ZERO : v);
    }

    private static String str(LocalDate d) { return d == null ? null : d.toString(); }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
