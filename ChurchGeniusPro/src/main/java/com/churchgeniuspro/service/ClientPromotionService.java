package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.ClientPromotion;
import com.churchgeniuspro.hibernate.Promotion;
import com.churchgeniuspro.hibernate.PromotionHistory;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.model.ServiceClientBO;
import com.churchgeniuspro.repository.ClientPromotionRepository;
import com.churchgeniuspro.repository.PromotionHistoryRepository;
import com.churchgeniuspro.repository.PromotionRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.SubscriptionPlanRepository;
import com.churchgeniuspro.util.AppClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Promotions phase 2 — giving a customer a discount, and its whole life:
 * Scheduled → Active → Cancelled (Expired arrives with phase 4).
 *
 * <p>Only the Service Admin does any of this; customers never see or enter a code.
 * Two kinds, kept separate: a <b>promotional deal</b> (by its code) and an independent
 * <b>manual discount</b> ("Manual Discount – No Promotion Code", reason required).
 *
 * <p>Rules (approved 2026-11-14):
 * <ul>
 *   <li>A trial holds its discount as SCHEDULED with no dates. It becomes ACTIVE only
 *       when the Service Admin explicitly applies it at Convert; the start date is the
 *       paid start date. For a deal the trial stores the CODE and the monthly or yearly
 *       deal matching the chosen billing frequency is used at Convert.</li>
 *   <li>A paying client's discount starts at its next billing date. Nothing already
 *       invoiced is touched.</li>
 *   <li>Monthly deals only for monthly billing, yearly only for yearly; the deal must
 *       cover the plan. A manual discount is the one path outside those rules.</li>
 *   <li>Terms adjusted for one customer may never run longer than the deal.</li>
 *   <li>The discount must lower the customer's regular price, which may be a negotiated
 *       price. That price stays on {@code service_client} untouched.</li>
 *   <li>One SCHEDULED or ACTIVE discount per client. Free plans, sample-data trials
 *       (TRIAL-) and demo accounts (DEMO-) are never eligible.</li>
 *   <li>The end date is fixed when the discount starts. It can be shortened, never
 *       extended, and never moves on renewal, plan change or reactivation.</li>
 *   <li>Every cancellation needs a reason. Every action writes an append-only history
 *       row naming the Service Admin.</li>
 * </ul>
 *
 * <p><b>No invoice changes in this phase.</b> {@link #quote} is the one price rule;
 * billing starts calling it in phase 3.
 */
@Service
public class ClientPromotionService {

    private static final Logger log = LoggerFactory.getLogger(ClientPromotionService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final List<String> LIVE = List.of(ClientPromotion.SCHEDULED, ClientPromotion.ACTIVE);
    static final int MAX_MANUAL_MONTHS = 60;          // a manual discount has no deal to cap it: 5 years
    static final int NOTES_MAX = 2000;
    public static final String SYSTEM = "system";

    public static final String KEEP   = "KEEP";
    public static final String CANCEL = "CANCEL";
    public static final String APPLY        = "APPLY";
    public static final String DO_NOT_APPLY = "DO_NOT_APPLY";

    /** Edit Client changed the plan or billing frequency of a client with a live discount: the Service Admin must decide. */
    public static class DecisionRequired extends RuntimeException {
        private final boolean eligible;
        public DecisionRequired(String message, boolean eligible) { super(message); this.eligible = eligible; }
        /** True when the discount may continue on the new plan and frequency. */
        public boolean eligible() { return eligible; }
    }

    /** The same deal is being given to a customer who already had it: ask the Service Admin to confirm. */
    public static class RepeatConfirmationRequired extends RuntimeException {
        public RepeatConfirmationRequired(String message) { super(message); }
    }

    /** Discount terms as the page sends them. Either a duration or an end date. */
    public record Terms(String discountType, String discountValue, Integer durationAmount,
                        String durationUnit, String endDate) {
        boolean blank() {
            return isBlank(discountType) && isBlank(discountValue) && durationAmount == null
                    && isBlank(durationUnit) && isBlank(endDate);
        }
    }

    /** Giving a discount: {@code kind} DEAL (with {@code promotionCode}; {@code terms} = optional adjustment) or MANUAL. */
    public record AssignForm(String kind, String promotionCode, Terms terms, String notes, boolean confirmRepeat) {}

    /** The Service Admin's choice at Convert for a trial's scheduled discount. */
    public record ConversionChoice(String decision, String reason, Terms adjust, String notes) {}

    /** A validated, not-yet-written application of a trial's discount at conversion. */
    public record ConversionPlan(Long assignmentId, boolean apply, String reason, Promotion deal,
                                 String planCode, String frequency, LocalDate start, LocalDate end,
                                 String discountType, BigDecimal discountValue, Integer durationAmount,
                                 String durationUnit, boolean adjusted, String notes) {}

    /** What a client pays for a billing period: the regular price, the price to charge, and the discount behind it (or null). */
    public record Quote(BigDecimal regularPrice, BigDecimal price, ClientPromotion discount) {
        public boolean discounted() { return discount != null; }
    }

    private final ClientPromotionRepository assignments;
    private final PromotionRepository deals;
    private final PromotionHistoryRepository history;
    private final ServiceClientRepository clients;
    private final SubscriptionPlanRepository plans;

    public ClientPromotionService(ClientPromotionRepository assignments, PromotionRepository deals,
                                  PromotionHistoryRepository history, ServiceClientRepository clients,
                                  SubscriptionPlanRepository plans) {
        this.assignments = assignments;
        this.deals = deals;
        this.history = history;
        this.clients = clients;
        this.plans = plans;
    }

    // ── read ───────────────────────────────────────────────────────────────

    /** Everything the Discount dialog shows for one client. */
    @Transactional
    public Map<String, Object> view(Integer clientNumericId) {
        ServiceClient sc = client(clientNumericId);
        activateDue(AppClock.today());
        Map<String, Object> out = new LinkedHashMap<>();
        Situation s = situation(sc);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", sc.getId());
        c.put("clientId", sc.getClientId());
        c.put("churchName", sc.getChurchName());
        c.put("planCode", s.planCode);
        c.put("planName", s.plan == null ? s.planCode : s.plan.getPlanName());
        c.put("trial", s.trial);
        c.put("billingFrequency", s.trial ? null : s.frequency);
        c.put("regularPrice", s.trial ? null : s.regular);
        c.put("negotiatedPrice", Boolean.TRUE.equals(sc.getPriceOverridden()));
        c.put("nextBillingDate", s.trial ? null : str(nextBillingDate(sc)));
        c.put("eligible", s.refusal == null);
        c.put("ineligibleReason", s.refusal);
        out.put("client", c);

        ClientPromotion live = live(sc.getClientId()).orElse(null);
        out.put("current", live == null ? null : toMap(live));
        List<Map<String, Object>> past = new ArrayList<>();
        for (ClientPromotion a : assignments.findByClientIdOrderByIdDesc(sc.getClientId())) {
            if (live == null || !Objects.equals(a.getId(), live.getId())) past.add(toMap(a));
        }
        out.put("past", past);

        List<Map<String, Object>> hist = new ArrayList<>();
        for (PromotionHistory h : history.findByClientIdOrderByOccurredAtDescIdDesc(sc.getClientId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("event", h.getEvent());
            m.put("actor", h.getActor());
            m.put("occurredAt", str(h.getOccurredAt()));
            m.put("promotionCode", h.getPromotionCode());
            m.put("notes", h.getNotes());
            m.put("before", parse(h.getBeforeJson()));
            m.put("after", parse(h.getAfterJson()));
            hist.add(m);
        }
        out.put("history", hist);
        out.put("deals", s.refusal != null ? List.of() : assignableDeals(s));
        return out;
    }

    /** Client id → its live discount, for the badge in the client list. */
    @Transactional
    public Map<String, Map<String, Object>> summary() {
        activateDue(AppClock.today());
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (ClientPromotion a : assignments.findByStatusIn(LIVE)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", a.getStatus());
            m.put("kind", a.getKind());
            m.put("label", ClientPromotion.MANUAL.equals(a.getKind()) ? "Manual" : a.getPromotionCode());
            m.put("startDate", str(a.getStartDate()));
            m.put("endDate", str(a.getEndDate()));
            out.put(a.getClientId(), m);
        }
        return out;
    }

    // ── assign ─────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> assign(Integer clientNumericId, AssignForm f, String actor) {
        ServiceClient sc = client(clientNumericId);
        if (f == null) throw new IllegalArgumentException("Choose a promotional deal or a manual discount.");
        Situation s = situation(sc);
        if (s.refusal != null) throw new IllegalArgumentException(s.refusal);
        if (live(sc.getClientId()).isPresent()) {
            throw new IllegalArgumentException("This client already has a discount. Cancel it before giving another one.");
        }
        String kind = f.kind() == null ? "" : f.kind().trim().toUpperCase();
        String notes = notes(f.notes());
        LocalDate today = AppClock.today();

        ClientPromotion a = new ClientPromotion();
        a.setClientId(sc.getClientId());
        a.setStatus(ClientPromotion.SCHEDULED);

        if (ClientPromotion.DEAL.equals(kind)) {
            String code = f.promotionCode() == null ? "" : f.promotionCode().trim().toUpperCase();
            if (code.isEmpty()) throw new IllegalArgumentException("Choose the promotional deal.");
            a.setKind(ClientPromotion.DEAL);
            a.setPromotionCode(code);
            if (!f.confirmRepeat() && assignments.findByClientIdOrderByIdDesc(sc.getClientId()).stream()
                    .anyMatch(p -> code.equals(p.getPromotionCode()))) {
                throw new RepeatConfirmationRequired("This customer has already had the " + code
                        + " deal. Do you want to give it to them again?");
            }
            boolean adjust = f.terms() != null && !f.terms().blank();
            if (s.trial) {
                // The paid plan and frequency are not known yet: keep the code, pick the deal at Convert.
                List<Promotion> variants = deals.findByCode(code).stream().filter(d -> assignable(d, today)).toList();
                if (variants.isEmpty()) {
                    throw new IllegalArgumentException("There is no active deal with code " + code + " that can be assigned today.");
                }
                if (adjust) {
                    throw new IllegalArgumentException("Adjust the terms when you convert the trial — the plan and "
                            + "billing frequency are chosen then.");
                }
                a.setPromotionName(variants.get(0).getName());
            } else {
                Promotion deal = deals.findByCodeAndBillingFrequency(code, s.frequency)
                        .orElseThrow(() -> new IllegalArgumentException("There is no " + word(s.frequency) + " deal with code "
                                + code + ". This client is billed " + word(s.frequency) + "; a "
                                + word(other(s.frequency)) + " deal cannot be used."));
                if (!assignable(deal, today)) {
                    throw new IllegalArgumentException("The " + code + " deal is not active or cannot be assigned today.");
                }
                requireCovers(deal, s.planCode, s.plan);
                LocalDate start = nextBillingDate(sc);
                Granted g = adjust ? adjusted(deal, f.terms(), start, notes) : standard(deal, start);
                requireLower(g.type, g.value, s.regular);
                fillDeal(a, deal);
                fillDated(a, s.planCode, s.frequency, start, g, s.regular);
            }
        } else if (ClientPromotion.MANUAL.equals(kind)) {
            if (notes == null) {
                throw new IllegalArgumentException("Enter the reason for this manual discount in Reason / Notes.");
            }
            a.setKind(ClientPromotion.MANUAL);
            a.setPromotionName(ClientPromotion.MANUAL_LABEL);
            a.setManuallyAdjusted(true);
            if (f.terms() == null || f.terms().blank()) throw new IllegalArgumentException("Enter the discount and how long it lasts.");
            if (s.trial) {
                if (!isBlank(f.terms().endDate())) {
                    throw new IllegalArgumentException("For a trial, enter a duration. The dates are set when the trial is converted.");
                }
                Granted g = manual(f.terms(), null);
                a.setDiscountType(g.type);
                a.setDiscountValue(g.value);
                a.setDurationAmount(g.amount);
                a.setDurationUnit(g.unit);
            } else {
                LocalDate start = nextBillingDate(sc);
                Granted g = manual(f.terms(), start);
                requireLower(g.type, g.value, s.regular);
                fillDated(a, s.planCode, s.frequency, start, g, s.regular);
            }
        } else {
            throw new IllegalArgumentException("Choose a promotional deal or a manual discount.");
        }

        a.setNotes(notes);
        a.setAssignedBy(actor(actor));
        a.setAssignedAt(LocalDateTime.now());
        ClientPromotion saved = assignments.save(a);
        record(saved, PromotionHistory.ASSIGNED, actor, null, snapshot(saved), notes);
        activateIfDue(saved, today);
        log.info("Discount assigned: client {} {} {} by {}", sc.getClientId(), saved.getKind(), saved.getPromotionCode(), actor(actor));
        return toMap(saved);
    }

    // ── change terms ───────────────────────────────────────────────────────

    /** Changes the live discount's terms. A note is required. The end date can be shortened, never extended. */
    @Transactional
    public Map<String, Object> modify(Integer clientNumericId, Terms terms, String notesIn, String actor) {
        ServiceClient sc = client(clientNumericId);
        ClientPromotion a = live(sc.getClientId())
                .orElseThrow(() -> new IllegalArgumentException("This client has no discount to change."));
        String notes = notes(notesIn);
        if (notes == null) throw new IllegalArgumentException("Enter a note explaining the change.");
        if (terms == null || terms.blank()) throw new IllegalArgumentException("Enter the new terms.");
        Map<String, Object> before = snapshot(a);

        if (a.getStartDate() == null) {
            // A trial's waiting discount.
            if (ClientPromotion.DEAL.equals(a.getKind())) {
                throw new IllegalArgumentException("Adjust this deal's terms when you convert the trial — the plan and "
                        + "billing frequency are chosen then.");
            }
            if (!isBlank(terms.endDate())) {
                throw new IllegalArgumentException("For a trial, enter a duration. The dates are set when the trial is converted.");
            }
            Granted g = manual(terms, null);
            a.setDiscountType(g.type);
            a.setDiscountValue(g.value);
            a.setDurationAmount(g.amount);
            a.setDurationUnit(g.unit);
        } else {
            Situation s = situation(sc);
            BigDecimal regular = s.regular != null ? s.regular : a.getRegularPrice();
            // The original limit: the deal's own length, or five years for a manual discount.
            LocalDate limit = ClientPromotion.DEAL.equals(a.getKind()) && a.getDealDurationAmount() != null
                    ? endFor(a.getStartDate(), a.getDealDurationAmount(), a.getDealDurationUnit())
                    : a.getStartDate().plusMonths(MAX_MANUAL_MONTHS).minusDays(1);
            Granted g = terms(terms, a.getStartDate(), limit,
                    ClientPromotion.DEAL.equals(a.getKind()) ? "the deal's duration" : "5 years");
            if (g.end.isAfter(a.getEndDate())) {
                throw new IllegalArgumentException("A discount's end date can be shortened but not extended. It ends on "
                        + a.getEndDate() + ".");
            }
            if (g.end.isBefore(AppClock.today()) && ClientPromotion.ACTIVE.equals(a.getStatus())) {
                throw new IllegalArgumentException("The end date cannot be in the past. To stop the discount now, cancel it.");
            }
            requireLower(g.type, g.value, regular);
            a.setDiscountType(g.type);
            a.setDiscountValue(g.value);
            a.setDurationAmount(g.amount);
            a.setDurationUnit(g.unit);
            a.setEndDate(g.end);
            a.setPromotionalPrice(PromotionService.promotionalPrice(g.type, g.value, regular));
        }
        a.setManuallyAdjusted(true);
        a.setNotes(notes);
        a.setUpdatedAt(LocalDateTime.now());
        a.setUpdatedBy(actor(actor));
        Map<String, Object> after = snapshot(a);
        ClientPromotion saved = assignments.save(a);
        record(saved, PromotionHistory.TERMS_CHANGED, actor, before, after, notes);
        return toMap(saved);
    }

    // ── cancel ─────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> cancel(Integer clientNumericId, String reason, String actor) {
        ServiceClient sc = client(clientNumericId);
        ClientPromotion a = live(sc.getClientId())
                .orElseThrow(() -> new IllegalArgumentException("This client has no discount to cancel."));
        return toMap(cancel(a, reason, actor, PromotionHistory.CANCELLED));
    }

    private ClientPromotion cancel(ClientPromotion a, String reasonIn, String actor, String event) {
        String reason = reasonIn == null ? "" : reasonIn.trim();
        if (reason.isEmpty()) throw new IllegalArgumentException("Enter the reason for cancelling this discount.");
        if (reason.length() > 500) throw new IllegalArgumentException("The reason can be at most 500 characters.");
        Map<String, Object> before = snapshot(a);
        a.setStatus(ClientPromotion.CANCELLED);
        a.setCancelledBy(actor(actor));
        a.setCancelledAt(LocalDateTime.now());
        a.setCancelReason(reason);
        a.setUpdatedAt(a.getCancelledAt());
        a.setUpdatedBy(actor(actor));
        ClientPromotion saved = assignments.save(a);
        record(saved, event, actor, before, snapshot(saved), reason);
        log.info("Discount cancelled: client {} {} by {}", saved.getClientId(), saved.getPromotionCode(), actor(actor));
        return saved;
    }

    /** A deleted client's live discount is cancelled, with a history entry. Never throws. */
    @Transactional
    public void cancelForDeletedClient(Integer clientNumericId, String actor) {
        try {
            ServiceClient sc = clients.findById(clientNumericId).orElse(null);
            if (sc == null || sc.getClientId() == null) return;
            live(sc.getClientId()).ifPresent(a -> cancel(a, "Client deleted", actor, PromotionHistory.CANCELLED));
        } catch (Exception e) {
            log.warn("Could not cancel the discount of deleted client {}: {}", clientNumericId, e.getClass().getSimpleName());
        }
    }

    // ── trial → paid conversion ────────────────────────────────────────────

    /**
     * Before Convert: when the trial has a waiting discount the Service Admin must have
     * chosen Apply or Do not apply. Validates everything and writes nothing; returns
     * null when the trial has no waiting discount.
     */
    @Transactional(readOnly = true)
    public ConversionPlan prepareConversion(Integer clientNumericId, ConversionChoice choice, String newPlanIn,
                                            String frequencyIn, String customPriceIn, String startDateIn) {
        ServiceClient sc = clients.findById(clientNumericId).orElse(null);
        if (sc == null || sc.getClientId() == null) return null;       // Convert reports the missing client
        ClientPromotion a = live(sc.getClientId()).filter(x -> x.getStartDate() == null).orElse(null);
        if (a == null) return null;
        String decision = choice == null || choice.decision() == null ? "" : choice.decision().trim().toUpperCase();
        String what = ClientPromotion.DEAL.equals(a.getKind()) ? "the " + a.getPromotionCode() + " promotional deal" : "a manual discount";
        if (DO_NOT_APPLY.equals(decision)) {
            String reason = choice.reason() == null ? "" : choice.reason().trim();
            if (reason.isEmpty()) throw new IllegalArgumentException("Enter the reason for not applying the discount.");
            if (reason.length() > 500) throw new IllegalArgumentException("The reason can be at most 500 characters.");
            return new ConversionPlan(a.getId(), false, reason, null, null, null, null, null, null, null, null, null, false, null);
        }
        if (!APPLY.equals(decision)) {
            throw new IllegalArgumentException("This trial has " + what + " waiting. Choose Apply or Do not apply before converting.");
        }

        String planCode = SubscriptionService.toPlanCode(newPlanIn);
        SubscriptionPlan plan = planCode == null ? null : plans.findByPlanCodeIgnoreCase(planCode).orElse(null);
        if (plan == null || !PromotionService.isPaidPlan(plan)) {
            throw new IllegalArgumentException("A discount can only be applied to an active paid plan.");
        }
        String freq = SubscriptionLifecycleService.normaliseFrequency(frequencyIn);
        if (freq == null) freq = SubscriptionLifecycleService.MONTHLY;
        LocalDate start = isBlank(startDateIn) ? AppClock.today() : date(startDateIn, "start date");
        BigDecimal custom = SubscriptionLifecycleService.parsePrice(customPriceIn);
        BigDecimal regular = custom != null ? custom : SubscriptionLifecycleService.listPrice(plan, freq);
        if (regular == null || regular.signum() <= 0) {
            throw new IllegalArgumentException("The " + plan.getPlanName() + " plan has no " + word(freq)
                    + " price, so a discount cannot be applied.");
        }
        regular = regular.setScale(2, RoundingMode.HALF_UP);
        boolean adjust = choice.adjust() != null && !choice.adjust().blank();
        String notes = notes(choice.notes());

        Promotion deal = null;
        Granted g;
        if (ClientPromotion.DEAL.equals(a.getKind())) {
            final String f = freq;
            deal = deals.findByCodeAndBillingFrequency(a.getPromotionCode(), freq)
                    .orElseThrow(() -> new IllegalArgumentException("There is no " + word(f) + " deal with code "
                            + a.getPromotionCode() + ", so it cannot be applied to " + word(f) + " billing. Choose "
                            + word(other(f)) + " billing, or choose Do not apply."));
            requireCovers(deal, plan.getPlanCode(), plan);
            g = adjust ? adjusted(deal, choice.adjust(), start, notes) : standard(deal, start);
        } else {
            Terms t = adjust ? choice.adjust()
                    : new Terms(a.getDiscountType(), a.getDiscountValue().toPlainString(), a.getDurationAmount(), a.getDurationUnit(), null);
            if (adjust && notes == null) throw new IllegalArgumentException("Enter a note explaining the changed terms.");
            g = manual(t, start);
        }
        requireLower(g.type, g.value, regular);
        return new ConversionPlan(a.getId(), true, null, deal, plan.getPlanCode(), freq, start, g.end,
                g.type, g.value, g.amount, g.unit, adjust, notes);
    }

    /** After a successful Convert: writes what {@link #prepareConversion} validated. */
    @Transactional
    public Map<String, Object> completeConversion(ConversionPlan plan, ServiceClient converted, String actor) {
        if (plan == null) return null;
        ClientPromotion a = assignments.findById(plan.assignmentId())
                .orElseThrow(() -> new IllegalArgumentException("The discount was not found."));
        if (!ClientPromotion.SCHEDULED.equals(a.getStatus())) return toMap(a);
        if (!plan.apply()) return toMap(cancel(a, plan.reason(), actor, PromotionHistory.NOT_APPLIED));

        Map<String, Object> before = snapshot(a);
        if (plan.deal() != null) fillDeal(a, plan.deal());
        BigDecimal regular = converted != null && converted.getSubscriptionPrice() != null
                ? converted.getSubscriptionPrice().setScale(2, RoundingMode.HALF_UP) : null;
        a.setPlanCode(plan.planCode());
        a.setBillingFrequency(plan.frequency());
        a.setStartDate(plan.start());
        a.setEndDate(plan.end());
        a.setDiscountType(plan.discountType());
        a.setDiscountValue(plan.discountValue());
        a.setDurationAmount(plan.durationAmount());
        a.setDurationUnit(plan.durationUnit());
        if (plan.adjusted()) a.setManuallyAdjusted(true);
        if (plan.notes() != null) a.setNotes(plan.notes());
        a.setRegularPrice(regular);
        a.setPromotionalPrice(PromotionService.promotionalPrice(plan.discountType(), plan.discountValue(), regular));
        a.setStatus(ClientPromotion.ACTIVE);
        a.setAppliedBy(actor(actor));
        a.setAppliedAt(LocalDateTime.now());
        a.setUpdatedAt(a.getAppliedAt());
        a.setUpdatedBy(actor(actor));
        ClientPromotion saved = assignments.save(a);
        record(saved, PromotionHistory.APPLIED, actor, before, snapshot(saved), saved.getNotes());
        log.info("Discount applied at conversion: client {} {} {} – {} by {}", saved.getClientId(),
                saved.getPromotionCode(), saved.getStartDate(), saved.getEndDate(), actor(actor));
        return toMap(saved);
    }

    // ── Edit Client: plan or billing frequency change ──────────────────────

    /**
     * Before Edit Client saves: when the client holds a dated discount and the plan or
     * billing frequency is changing, the Service Admin must decide. Throws
     * {@link DecisionRequired} until {@code promotionDecision} is KEEP (only when the
     * discount is still eligible) or CANCEL (with a reason). Writes nothing.
     */
    @Transactional(readOnly = true)
    public void checkEdit(Integer clientNumericId, ServiceClientBO bo) {
        Change ch = change(clientNumericId, bo);
        if (ch == null) return;
        String decision = bo.getPromotionDecision() == null ? "" : bo.getPromotionDecision().trim().toUpperCase();
        if (CANCEL.equals(decision)) {
            if (isBlank(bo.getPromotionCancelReason())) {
                throw new IllegalArgumentException("Enter the reason for cancelling the discount.");
            }
            return;
        }
        if (KEEP.equals(decision)) {
            if (ch.problem != null) throw new IllegalArgumentException(ch.problem + " Cancel the discount to save this change.");
            return;
        }
        String label = ClientPromotion.DEAL.equals(ch.a.getKind()) ? "the " + ch.a.getPromotionCode() + " promotional deal"
                : "a manual discount";
        throw new DecisionRequired("This client has " + label + " (through " + ch.a.getEndDate() + "), and you are changing "
                + ch.what + ". " + (ch.problem == null
                    ? "The discount can continue on the new " + ch.what + ". Keep it, or cancel it? Its end date does not change."
                    : ch.problem + " The discount must be cancelled before this change can be saved."),
                ch.problem == null);
    }

    /** After Edit Client saved: carries out the decision {@link #checkEdit} accepted. */
    @Transactional
    public void afterEdit(Integer clientNumericId, ServiceClientBO bo, ServiceClient saved, String actor) {
        if (bo == null || isBlank(bo.getPromotionDecision()) || saved == null) return;
        ClientPromotion a = live(saved.getClientId()).filter(x -> x.getStartDate() != null).orElse(null);
        if (a == null) return;
        String decision = bo.getPromotionDecision().trim().toUpperCase();
        if (CANCEL.equals(decision)) {
            cancel(a, bo.getPromotionCancelReason(), actor, PromotionHistory.CANCELLED);
        } else if (KEEP.equals(decision)) {
            Situation s = situation(saved);
            if (Objects.equals(a.getPlanCode(), s.planCode) && Objects.equals(a.getBillingFrequency(), s.frequency)) return;
            Map<String, Object> before = snapshot(a);
            a.setPlanCode(s.planCode);
            a.setBillingFrequency(s.frequency);
            a.setRegularPrice(s.regular);
            a.setPromotionalPrice(PromotionService.promotionalPrice(a.getDiscountType(), a.getDiscountValue(), s.regular));
            a.setUpdatedAt(LocalDateTime.now());
            a.setUpdatedBy(actor(actor));
            ClientPromotion kept = assignments.save(a);
            record(kept, PromotionHistory.KEPT_AFTER_CHANGE, actor, before, snapshot(kept), kept.getNotes());
        }
    }

    private record Change(ClientPromotion a, String what, String problem) {}

    /** The plan / frequency change Edit Client is about to make to a client with a dated discount, or null. */
    private Change change(Integer clientNumericId, ServiceClientBO bo) {
        if (bo == null) return null;
        ServiceClient sc = clients.findById(clientNumericId).orElse(null);
        if (sc == null || sc.getClientId() == null) return null;
        ClientPromotion a = live(sc.getClientId()).filter(x -> x.getStartDate() != null).orElse(null);
        if (a == null) return null;
        String curPlan = SubscriptionService.toPlanCode(sc.getSubscriptionType());
        String curFreq = BillingService.frequencyOf(sc);
        String newPlan = SubscriptionService.toPlanCode(bo.getSubscriptionType());
        if (newPlan == null) newPlan = curPlan;
        String newFreq;
        try { newFreq = SubscriptionLifecycleService.normaliseFrequency(bo.getBillingFrequency()); }
        catch (IllegalArgumentException e) { return null; }            // the save reports the bad value
        if (newFreq == null) newFreq = curFreq;
        boolean planChanged = !Objects.equals(curPlan, newPlan);
        boolean freqChanged = !Objects.equals(curFreq, newFreq);
        if (!planChanged && !freqChanged) return null;
        String what = planChanged && freqChanged ? "plan and billing frequency" : planChanged ? "plan" : "billing frequency";

        SubscriptionPlan plan = newPlan == null ? null : plans.findByPlanCodeIgnoreCase(newPlan).orElse(null);
        String problem = null;
        if (plan == null || !PromotionService.isPaidPlan(plan)) {
            problem = "A discount cannot continue on a plan that is not an active paid plan.";
        } else if (ClientPromotion.DEAL.equals(a.getKind()) && freqChanged) {
            problem = "The " + a.getPromotionCode() + " deal is a " + word(a.getBillingFrequency())
                    + " deal and cannot continue on " + word(newFreq) + " billing.";
        } else if (ClientPromotion.DEAL.equals(a.getKind()) && planChanged && !dealCovers(a, plan.getPlanCode())) {
            problem = "The " + a.getPromotionCode() + " deal does not cover the " + plan.getPlanName() + " plan.";
        } else {
            BigDecimal custom = null;
            try {
                if (Boolean.TRUE.equals(bo.getPriceOverridden())) custom = SubscriptionLifecycleService.parsePrice(bo.getSubscriptionPrice());
            } catch (IllegalArgumentException ignored) { }
            BigDecimal regular = custom != null ? custom : SubscriptionLifecycleService.listPrice(plan, newFreq);
            BigDecimal price = regular == null ? null : PromotionService.promotionalPrice(a.getDiscountType(), a.getDiscountValue(), regular);
            if (regular == null || regular.signum() <= 0 || price == null || price.compareTo(regular) >= 0) {
                problem = "On the new " + what + " this discount would no longer lower the price.";
            }
        }
        return new Change(a, what, problem);
    }

    private boolean dealCovers(ClientPromotion a, String planCode) {
        Promotion deal = a.getPromotionId() == null ? null : deals.findById(a.getPromotionId()).orElse(null);
        return deal != null && covers(deal, planCode);
    }

    // ── activation of a paying client's scheduled discount ─────────────────

    /** Scheduled discounts whose start date has arrived become Active. Idempotent; never throws. */
    @Transactional
    public int activateDue(LocalDate today) {
        int n = 0;
        try {
            for (ClientPromotion a : assignments.findByStatusAndStartDateLessThanEqual(ClientPromotion.SCHEDULED, today)) {
                if (activateIfDue(a, today)) n++;
            }
        } catch (Exception e) {
            log.warn("Promotion activation pass failed: {}", e.getClass().getSimpleName());
        }
        return n;
    }

    private boolean activateIfDue(ClientPromotion a, LocalDate today) {
        if (!ClientPromotion.SCHEDULED.equals(a.getStatus()) || a.getStartDate() == null || a.getStartDate().isAfter(today)) return false;
        Map<String, Object> before = snapshot(a);
        a.setStatus(ClientPromotion.ACTIVE);
        a.setAppliedBy(SYSTEM);
        a.setAppliedAt(LocalDateTime.now());
        a.setUpdatedAt(a.getAppliedAt());
        a.setUpdatedBy(SYSTEM);
        ClientPromotion saved = assignments.save(a);
        record(saved, PromotionHistory.ACTIVATED, SYSTEM, before, snapshot(saved), saved.getNotes());
        return true;
    }

    // ── expiry (phase 4) ───────────────────────────────────────────────────

    /** The note every automatic expiry entry carries. */
    public static final String EXPIRED_NOTE = "Promotion expired – Regular subscription pricing restored";

    /**
     * Active discounts whose last day has passed become Expired, each with one history
     * entry (code, promotional price, regular price, dates, plan, frequency, notes).
     * Nothing else changes: the client's regular price was never overwritten, and
     * invoices already follow the discount's dates. Never deleted. Idempotent; one
     * discount failing does not stop the others.
     */
    private org.springframework.transaction.support.TransactionTemplate tx;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTransactionManager(org.springframework.transaction.PlatformTransactionManager tm) {
        this.tx = tm == null ? null : new org.springframework.transaction.support.TransactionTemplate(tm);
    }

    public int expireDue(LocalDate today) {
        int n = 0;
        List<ClientPromotion> due;
        try {
            due = assignments.findByStatusIn(List.of(ClientPromotion.ACTIVE));
        } catch (Exception e) {
            log.warn("Promotion expiry pass failed: {}", e.getClass().getSimpleName());
            return 0;
        }
        for (ClientPromotion a : due) {
            if (a.getEndDate() == null || !a.getEndDate().isBefore(today)) continue;
            try {
                if (tx == null) expire(a);
                else tx.executeWithoutResult(status -> expire(a));     // status + history entry together, one discount at a time
                n++;
            } catch (Exception e) {
                log.warn("Promotion expiry failed for discount {} of {}: {}", a.getId(), a.getClientId(), e.getClass().getSimpleName());
            }
        }
        return n;
    }

    private void expire(ClientPromotion a) {
        Map<String, Object> before = snapshot(a);
        ServiceClient sc = clients.findByClientId(a.getClientId()).orElse(null);
        BigDecimal regularNow = sc == null ? null : BillingService.effectivePrice(sc, planOf(sc), BillingService.frequencyOf(sc));
        a.setStatus(ClientPromotion.EXPIRED);
        a.setRestoredOn(a.getEndDate().plusDays(1));
        a.setUpdatedAt(LocalDateTime.now());
        a.setUpdatedBy(SYSTEM);
        ClientPromotion saved = assignments.save(a);
        Map<String, Object> after = snapshot(saved);
        after.put("expiredOn", str(saved.getEndDate()));
        after.put("regularPricingFrom", str(saved.getRestoredOn()));
        after.put("regularPriceNow", plain(regularNow != null ? regularNow : saved.getRegularPrice()));
        record(saved, PromotionHistory.EXPIRED, SYSTEM, before, after,
                EXPIRED_NOTE + (isBlank(saved.getNotes()) ? "" : " — " + saved.getNotes()));
        log.info("Discount expired: client {} {} ended {} — regular pricing from {}", saved.getClientId(),
                ClientPromotion.MANUAL.equals(saved.getKind()) ? "manual discount" : saved.getPromotionCode(),
                saved.getEndDate(), saved.getRestoredOn());
    }

    /** The client's live discount that has dates (applied / scheduled for a paying client), or null. */
    @Transactional(readOnly = true)
    public ClientPromotion liveDated(String clientId) {
        return clientId == null ? null : live(clientId).filter(x -> x.getStartDate() != null && x.getEndDate() != null).orElse(null);
    }

    /** Active discounts whose last day is {@code endDate} — for the before-expiry notices. */
    @Transactional(readOnly = true)
    public List<ClientPromotion> activeEndingOn(LocalDate endDate) {
        List<ClientPromotion> out = new ArrayList<>();
        for (ClientPromotion a : assignments.findByStatusIn(List.of(ClientPromotion.ACTIVE))) {
            if (endDate != null && endDate.equals(a.getEndDate())) out.add(a);
        }
        return out;
    }

    // ── the price rule ─────────────────────────────────────────────────────

    /**
     * What this client pays for the billing period that STARTS on {@code periodStart}.
     * The discounted price applies when the client has a dated, live discount for its
     * current billing frequency whose start ≤ periodStart ≤ end; otherwise the regular
     * price. Whole billing periods only — nothing is prorated. A client with no
     * discount gets exactly {@link BillingService#effectivePrice}.
     *
     * <p>Billing reads it through {@link #forInvoice} (phase 3).
     */
    @Transactional(readOnly = true)
    public Quote quote(ServiceClient sc, LocalDate periodStart) {
        if (sc == null) return new Quote(null, null, null);
        String freq = BillingService.frequencyOf(sc);
        SubscriptionPlan plan = planOf(sc);
        BigDecimal regular = BillingService.effectivePrice(sc, plan, freq);
        if (sc.getClientId() == null || periodStart == null) return new Quote(regular, regular, null);
        ClientPromotion a = live(sc.getClientId()).orElse(null);
        if (a == null || a.getStartDate() == null || a.getEndDate() == null
                || periodStart.isBefore(a.getStartDate()) || periodStart.isAfter(a.getEndDate())
                || !Objects.equals(a.getBillingFrequency(), freq)
                || a.getDiscountType() == null || a.getDiscountValue() == null) {
            return new Quote(regular, regular, null);
        }
        BigDecimal price = PromotionService.promotionalPrice(a.getDiscountType(), a.getDiscountValue(), regular);
        if (price == null || price.compareTo(regular) >= 0) return new Quote(regular, regular, null);   // never raises a price
        return new Quote(regular, price, a);
    }

    // ── invoices (phase 3) ─────────────────────────────────────────────────

    /**
     * The promotional part of one invoice: the subscription line stays at
     * {@code regularPrice}; {@code discount} is taken off the invoice. {@code tentative}
     * = a trial's waiting discount, shown on its Subscription Request invoice before Convert.
     */
    public record InvoicePromotion(Long id, String label, BigDecimal regularPrice, BigDecimal price,
                                   BigDecimal discount, LocalDate endDate, boolean tentative) {}

    /** Customer-facing words for a discount on an invoice. */
    static String invoiceLabel(ClientPromotion a) {
        return ClientPromotion.DEAL.equals(a.getKind()) && !isBlank(a.getPromotionCode())
                ? "Promotion " + a.getPromotionCode() : "Special discount";
    }

    /** The promotion on a renewal invoice whose period starts on {@code periodStart}; null when none applies. */
    @Transactional(readOnly = true)
    public InvoicePromotion forInvoice(ServiceClient sc, LocalDate periodStart) {
        Quote q = quote(sc, periodStart);
        if (!q.discounted()) return null;
        return new InvoicePromotion(q.discount().getId(), invoiceLabel(q.discount()), q.regularPrice(), q.price(),
                q.regularPrice().subtract(q.price()).setScale(2, RoundingMode.HALF_UP), q.discount().getEndDate(), false);
    }

    /**
     * A trial's WAITING discount as it would be applied at Convert to this plan,
     * frequency and regular price starting on {@code start} — for the Subscription
     * Request invoice, so the first payment already carries the promotion. Writes
     * nothing; null when there is no waiting discount or it would not be eligible.
     */
    @Transactional(readOnly = true)
    public InvoicePromotion forRequest(ServiceClient sc, SubscriptionPlan plan, String freq, BigDecimal regularIn, LocalDate start) {
        try {
            if (sc == null || sc.getClientId() == null || plan == null || regularIn == null || start == null) return null;
            if (!PromotionService.isPaidPlan(plan) || regularIn.signum() <= 0) return null;
            ClientPromotion a = live(sc.getClientId()).filter(x -> x.getStartDate() == null).orElse(null);
            if (a == null) return null;
            BigDecimal regular = regularIn.setScale(2, RoundingMode.HALF_UP);
            Granted g;
            if (ClientPromotion.DEAL.equals(a.getKind())) {
                Promotion deal = deals.findByCodeAndBillingFrequency(a.getPromotionCode(), freq).orElse(null);
                if (deal == null || !covers(deal, plan.getPlanCode())) return null;
                g = standard(deal, start);
            } else {
                if (a.getDiscountType() == null || a.getDiscountValue() == null) return null;
                g = manual(new Terms(a.getDiscountType(), a.getDiscountValue().toPlainString(),
                        a.getDurationAmount(), a.getDurationUnit(), null), start);
            }
            BigDecimal price = PromotionService.promotionalPrice(g.type, g.value, regular);
            if (price == null || price.compareTo(regular) >= 0) return null;
            return new InvoicePromotion(a.getId(), invoiceLabel(a), regular, price,
                    regular.subtract(price).setScale(2, RoundingMode.HALF_UP), g.end, true);
        } catch (IllegalArgumentException notEligible) {
            return null;
        }
    }

    /**
     * Checked when a draft invoice that carries a promotion is about to be sent: null
     * when the promotion on it is still what the customer's discount gives; otherwise
     * the reason (cancelled, terms changed, outside its dates). Never throws.
     */
    @Transactional(readOnly = true)
    public String invoiceProblem(Long clientPromotionId, ServiceClient sc, String planCode, String freq,
                                 LocalDate periodStart, BigDecimal regular, BigDecimal promotionDiscount) {
        try {
            ClientPromotion a = clientPromotionId == null ? null : assignments.findById(clientPromotionId).orElse(null);
            if (a == null) return "the discount no longer exists";
            if (ClientPromotion.CANCELLED.equals(a.getStatus())) return "the discount was cancelled";
            if (regular == null || promotionDiscount == null) return "the invoice's promotion details are incomplete";
            BigDecimal expected;
            if (a.getStartDate() != null) {
                if (periodStart == null || periodStart.isBefore(a.getStartDate())
                        || (a.getEndDate() != null && periodStart.isAfter(a.getEndDate()))) {
                    return "the invoice's billing period is outside the discount's dates";
                }
                if (freq != null && a.getBillingFrequency() != null && !freq.equals(a.getBillingFrequency())) {
                    return "the discount is for " + word(a.getBillingFrequency()) + " billing";
                }
                BigDecimal price = PromotionService.promotionalPrice(a.getDiscountType(), a.getDiscountValue(), regular);
                expected = price == null ? null : regular.subtract(price).setScale(2, RoundingMode.HALF_UP);
            } else {
                SubscriptionPlan plan = planCode == null ? null : plans.findByPlanCodeIgnoreCase(planCode).orElse(null);
                InvoicePromotion p = forRequest(sc, plan, freq, regular, periodStart == null ? AppClock.today() : periodStart);
                expected = p == null ? null : p.discount();
            }
            if (expected == null || expected.signum() <= 0) return "the discount no longer applies to this invoice";
            if (expected.compareTo(promotionDiscount) != 0) return "the discount's terms changed after this draft was created";
            return null;
        } catch (Exception e) {
            log.warn("Promotion check for an invoice failed: {}", e.getClass().getSimpleName());
            return "the discount could not be checked";
        }
    }

    // ── terms ──────────────────────────────────────────────────────────────

    /** Validated terms: type, value, the duration as entered (may be null with an end date) and the end date (null without a start). */
    private record Granted(String type, BigDecimal value, Integer amount, String unit, LocalDate end) {}

    private static Granted standard(Promotion deal, LocalDate start) {
        return new Granted(deal.getDiscountType(), deal.getDiscountValue(), deal.getDurationAmount(), deal.getDurationUnit(),
                endFor(start, deal.getDurationAmount(), deal.getDurationUnit()));
    }

    /** Terms adjusted for one customer: any type / value, but never longer than the deal. A note is required. */
    private static Granted adjusted(Promotion deal, Terms t, LocalDate start, String notes) {
        if (notes == null) throw new IllegalArgumentException("Enter a note explaining why this customer's terms differ from the deal.");
        Terms filled = new Terms(isBlank(t.discountType()) ? deal.getDiscountType() : t.discountType(),
                isBlank(t.discountValue()) ? deal.getDiscountValue().toPlainString() : t.discountValue(),
                t.durationAmount() == null && isBlank(t.endDate()) ? deal.getDurationAmount() : t.durationAmount(),
                t.durationAmount() == null && isBlank(t.endDate()) ? deal.getDurationUnit() : t.durationUnit(),
                t.endDate());
        return terms(filled, start, endFor(start, deal.getDurationAmount(), deal.getDurationUnit()), "the deal's duration ("
                + durationText(deal.getDurationAmount(), deal.getDurationUnit()) + ")");
    }

    private static Granted manual(Terms t, LocalDate start) {
        return terms(t, start, start == null ? null : start.plusMonths(MAX_MANUAL_MONTHS).minusDays(1), "5 years");
    }

    /**
     * @param start null for a trial's waiting discount (then only a duration is accepted and no end date is worked out)
     * @param limit the latest end date allowed, or null with a null start (then the duration is capped at 5 years)
     */
    private static Granted terms(Terms t, LocalDate start, LocalDate limit, String limitWords) {
        String type = t.discountType() == null ? "" : t.discountType().trim().toUpperCase();
        if (!PromotionService.TYPES.contains(type)) {
            throw new IllegalArgumentException("Choose the discount type: percentage off, dollar amount off, or fixed promotional price.");
        }
        BigDecimal value = PromotionService.discountValue(type, t.discountValue());
        Integer amount = t.durationAmount();
        String unit = t.durationUnit() == null ? "" : t.durationUnit().trim().toUpperCase();
        LocalDate end;
        if (!isBlank(t.endDate())) {
            if (start == null) throw new IllegalArgumentException("Enter a duration.");
            end = date(t.endDate(), "expiration date");
            if (end.isBefore(start)) {
                throw new IllegalArgumentException("The expiration date cannot be before the discount starts (" + start + ").");
            }
            amount = null;
            unit = null;
        } else {
            boolean months = Promotion.MONTHS.equals(unit) && amount != null && amount >= 1 && amount <= MAX_MANUAL_MONTHS;
            boolean years  = Promotion.YEARS.equals(unit) && amount != null && amount >= 1 && amount <= MAX_MANUAL_MONTHS / 12;
            if (!months && !years) {
                throw new IllegalArgumentException("Enter how long the discount lasts: 1 to 60 months or 1 to 5 years, or an expiration date.");
            }
            end = start == null ? null : endFor(start, amount, unit);
        }
        if (end != null && limit != null && end.isAfter(limit)) {
            throw new IllegalArgumentException("This discount cannot run longer than " + limitWords + ". The latest end date is " + limit + ".");
        }
        return new Granted(type, value, amount, unit, end);
    }

    static LocalDate endFor(LocalDate start, Integer amount, String unit) {
        if (start == null || amount == null) return null;
        return (Promotion.YEARS.equals(unit) ? start.plusYears(amount) : start.plusMonths(amount)).minusDays(1);
    }

    private static void requireLower(String type, BigDecimal value, BigDecimal regular) {
        if (regular == null || regular.signum() <= 0) {
            throw new IllegalArgumentException("This client has no regular price to discount.");
        }
        if (Promotion.AMOUNT_OFF.equals(type) && value.compareTo(regular) >= 0) {
            throw new IllegalArgumentException("$" + value + " off is not less than the client's regular price of $" + regular + ".");
        }
        BigDecimal price = PromotionService.promotionalPrice(type, value, regular);
        if (price.compareTo(regular) >= 0) {
            throw new IllegalArgumentException("This discount would not lower the client's regular price of $" + regular + ".");
        }
    }

    private static void requireCovers(Promotion deal, String planCode, SubscriptionPlan plan) {
        if (!covers(deal, planCode)) {
            throw new IllegalArgumentException("The " + deal.getCode() + " deal does not cover the "
                    + (plan == null ? planCode : plan.getPlanName()) + " plan.");
        }
    }

    private static boolean covers(Promotion deal, String planCode) {
        return planCode != null && PromotionService.planList(deal).stream().anyMatch(c -> c.equalsIgnoreCase(planCode));
    }

    private static boolean assignable(Promotion d, LocalDate today) {
        return d.isActive() && (d.getAssignFrom() == null || !today.isBefore(d.getAssignFrom()))
                && (d.getAssignUntil() == null || !today.isAfter(d.getAssignUntil()));
    }

    private static void fillDeal(ClientPromotion a, Promotion deal) {
        a.setPromotionId(deal.getId());
        a.setPromotionCode(deal.getCode());
        a.setPromotionName(deal.getName());
        a.setDealDiscountType(deal.getDiscountType());
        a.setDealDiscountValue(deal.getDiscountValue());
        a.setDealDurationAmount(deal.getDurationAmount());
        a.setDealDurationUnit(deal.getDurationUnit());
    }

    private static void fillDated(ClientPromotion a, String planCode, String freq, LocalDate start, Granted g, BigDecimal regular) {
        a.setPlanCode(planCode);
        a.setBillingFrequency(freq);
        a.setStartDate(start);
        a.setEndDate(g.end);
        a.setDiscountType(g.type);
        a.setDiscountValue(g.value);
        a.setDurationAmount(g.amount);
        a.setDurationUnit(g.unit);
        a.setRegularPrice(regular);
        a.setPromotionalPrice(PromotionService.promotionalPrice(g.type, g.value, regular));
        if (ClientPromotion.DEAL.equals(a.getKind())) {
            a.setManuallyAdjusted(!Objects.equals(g.type, a.getDealDiscountType())
                    || g.value.compareTo(a.getDealDiscountValue()) != 0
                    || !Objects.equals(g.end, endFor(start, a.getDealDurationAmount(), a.getDealDurationUnit())));
        }
    }

    // ── the client's situation ─────────────────────────────────────────────

    private static final class Situation {
        String planCode; SubscriptionPlan plan; boolean trial; String frequency; BigDecimal regular; String refusal;
    }

    private Situation situation(ServiceClient sc) {
        Situation s = new Situation();
        s.planCode = SubscriptionService.toPlanCode(sc.getSubscriptionType());
        s.plan = planOf(sc);
        s.trial = TrialPolicy.TRIAL_PLAN_CODE.equalsIgnoreCase(s.planCode);
        s.frequency = BillingService.frequencyOf(sc);
        s.regular = s.trial ? null : BillingService.effectivePrice(sc, s.plan, s.frequency);
        if (Boolean.TRUE.equals(sc.getDeleteFlag())) {
            s.refusal = "This client has been deleted.";
        } else if (TestDataService.isManagedTenant(sc.getClientId())) {
            s.refusal = "Sample-data trials and demo accounts cannot have a discount. A sample-data trial is registered "
                      + "as a new client when it subscribes; give the discount to that client.";
        } else if (s.planCode == null || "FREE".equalsIgnoreCase(s.planCode)) {
            s.refusal = "Free subscriptions are not eligible for a discount.";
        } else if (!s.trial && (s.plan == null || !PromotionService.isPaidPlan(s.plan))) {
            s.refusal = "This client is not on an active paid plan.";
        } else if (!s.trial && (s.regular == null || s.regular.signum() <= 0)) {
            s.refusal = "This client has no regular price to discount.";
        }
        return s;
    }

    private SubscriptionPlan planOf(ServiceClient sc) {
        String code = sc == null ? null : SubscriptionService.toPlanCode(sc.getSubscriptionType());
        return code == null ? null : plans.findByPlanCodeIgnoreCase(code).orElse(null);
    }

    /** A paying client's discount starts with its next billing period: the end date, or today when that has passed. */
    static LocalDate nextBillingDate(ServiceClient sc) {
        LocalDate today = AppClock.today();
        return sc.getEndDate() != null && sc.getEndDate().isAfter(today) ? sc.getEndDate() : today;
    }

    /** Deals the Service Admin may pick for this client today. */
    private List<Map<String, Object>> assignableDeals(Situation s) {
        LocalDate today = AppClock.today();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Promotion d : deals.findAllByOrderByActiveDescCodeAscBillingFrequencyAsc()) {
            if (!assignable(d, today)) continue;
            if (!s.trial && (!Objects.equals(d.getBillingFrequency(), s.frequency) || !covers(d, s.planCode))) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("code", d.getCode());
            m.put("name", d.getName());
            m.put("billingFrequency", d.getBillingFrequency());
            m.put("discountType", d.getDiscountType());
            m.put("discountValue", d.getDiscountValue());
            m.put("durationAmount", d.getDurationAmount());
            m.put("durationUnit", d.getDurationUnit());
            m.put("planCodes", PromotionService.planList(d));
            if (!s.trial) {
                BigDecimal price = PromotionService.promotionalPrice(d.getDiscountType(), d.getDiscountValue(), s.regular);
                m.put("promotionalPrice", price);
                m.put("lowersPrice", price != null && s.regular != null && price.compareTo(s.regular) < 0);
            }
            out.add(m);
        }
        return out;
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private ServiceClient client(Integer id) {
        if (id == null) throw new IllegalArgumentException("Client not found.");
        ServiceClient sc = clients.findById(id).orElseThrow(() -> new IllegalArgumentException("Client not found."));
        if (sc.getClientId() == null) throw new IllegalArgumentException("Client not found.");
        return sc;
    }

    private Optional<ClientPromotion> live(String clientId) {
        return assignments.findByClientIdAndStatusIn(clientId, LIVE).stream().findFirst();
    }

    private static String notes(String v) {
        if (v == null || v.isBlank()) return null;
        String t = v.trim();
        if (t.length() > NOTES_MAX) throw new IllegalArgumentException("Notes can be at most " + NOTES_MAX + " characters.");
        return t;
    }

    private static LocalDate date(String v, String label) {
        try { return LocalDate.parse(v.trim()); }
        catch (DateTimeParseException e) { throw new IllegalArgumentException("Enter the " + label + " as YYYY-MM-DD."); }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String actor(String a) { return isBlank(a) ? "ServiceAdmin" : a; }
    private static String word(String freq) { return SubscriptionLifecycleService.YEARLY.equals(freq) ? "yearly" : "monthly"; }
    private static String other(String freq) {
        return SubscriptionLifecycleService.YEARLY.equals(freq) ? SubscriptionLifecycleService.MONTHLY : SubscriptionLifecycleService.YEARLY;
    }
    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static String plain(BigDecimal d) { return d == null ? null : d.toPlainString(); }

    static String durationText(Integer amount, String unit) {
        if (amount == null) return null;
        return amount + " " + (Promotion.YEARS.equals(unit) ? "year" : "month") + (amount == 1 ? "" : "s");
    }

    /** The stored facts of a discount — what history keeps before and after a change. */
    private static Map<String, Object> snapshot(ClientPromotion a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", a.getKind());
        m.put("label", ClientPromotion.MANUAL.equals(a.getKind()) ? ClientPromotion.MANUAL_LABEL : a.getPromotionCode());
        m.put("promotionCode", a.getPromotionCode());
        m.put("promotionName", a.getPromotionName());
        m.put("status", a.getStatus());
        m.put("planCode", a.getPlanCode());
        m.put("billingFrequency", a.getBillingFrequency());
        m.put("discountType", a.getDiscountType());
        m.put("discountValue", plain(a.getDiscountValue()));
        m.put("duration", durationText(a.getDurationAmount(), a.getDurationUnit()));
        m.put("dealDiscountType", a.getDealDiscountType());
        m.put("dealDiscountValue", plain(a.getDealDiscountValue()));
        m.put("dealDuration", durationText(a.getDealDurationAmount(), a.getDealDurationUnit()));
        m.put("manuallyAdjusted", a.isManuallyAdjusted());
        m.put("startDate", str(a.getStartDate()));
        m.put("endDate", str(a.getEndDate()));
        m.put("regularPrice", plain(a.getRegularPrice()));
        m.put("promotionalPrice", plain(a.getPromotionalPrice()));
        m.put("notes", a.getNotes());
        m.put("cancelReason", a.getCancelReason());
        return m;
    }

    private static Map<String, Object> toMap(ClientPromotion a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.putAll(snapshot(a));
        m.put("durationAmount", a.getDurationAmount());
        m.put("durationUnit", a.getDurationUnit());
        m.put("assignedBy", a.getAssignedBy());
        m.put("assignedAt", str(a.getAssignedAt()));
        m.put("appliedBy", a.getAppliedBy());
        m.put("appliedAt", str(a.getAppliedAt()));
        m.put("cancelledBy", a.getCancelledBy());
        m.put("cancelledAt", str(a.getCancelledAt()));
        m.put("restoredOn", str(a.getRestoredOn()));
        return m;
    }

    private void record(ClientPromotion a, String event, String actor, Map<String, Object> before,
                        Map<String, Object> after, String notes) {
        PromotionHistory h = new PromotionHistory();
        h.setPromotionId(a.getPromotionId());
        h.setPromotionCode(a.getPromotionCode());
        h.setClientPromotionId(a.getId());
        h.setClientId(a.getClientId());
        h.setEvent(event);
        h.setActor(actor(actor));
        h.setOccurredAt(LocalDateTime.now());
        h.setNotes(notes);
        h.setBeforeJson(json(before));
        h.setAfterJson(json(after));
        history.save(h);
    }

    private static String json(Map<String, Object> m) {
        if (m == null) return null;
        try { return JSON.writeValueAsString(m); }
        catch (Exception e) { throw new IllegalStateException("Could not record the promotion history."); }
    }

    private static Object parse(String json) {
        if (json == null || json.isBlank()) return null;
        try { return JSON.readValue(json, Map.class); }
        catch (Exception e) { return null; }
    }
}
