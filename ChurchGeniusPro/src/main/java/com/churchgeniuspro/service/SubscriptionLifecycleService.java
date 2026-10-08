package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.SubscriptionChange;
import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.repository.SubscriptionChangeRepository;
import com.churchgeniuspro.repository.SubscriptionPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * The one place a client's subscription BILLING facts are decided, and the one
 * place every subscription change is recorded.
 *
 * <ul>
 *   <li><b>Price.</b> A client's {@code subscription_price} is copied from the plan's
 *       monthly or yearly list price when the plan or billing frequency changes, or is
 *       a negotiated price the Service Admin enters ({@code price_overridden}). A later
 *       change to the plan's list price never alters an existing client's price; the
 *       admin re-copies it explicitly ("reset to plan price").</li>
 *   <li><b>Plan validation.</b> A newly assigned plan must be an active Subscription
 *       Plan. Unknown codes used to be stored as typed, which the plan resolver then
 *       treats as unrestricted.</li>
 *   <li><b>History.</b> Every change of plan, price, frequency, dates or status writes
 *       an append-only {@link SubscriptionChange} row.</li>
 *   <li><b>Caches.</b> After a change, the per-client plan, account-status and
 *       messaging answers are dropped so the new subscription applies immediately.</li>
 * </ul>
 *
 * <p>Callers ({@code ServiceClientService}, {@code TestDataService}) take a
 * {@link #snapshot} before they change anything, then call {@link #applyPricing},
 * save, and {@link #recordAndRefresh}. This class never deletes or disables data:
 * a subscription change only changes what the client is allowed to do.
 */
@Service
public class SubscriptionLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionLifecycleService.class);

    public static final String MONTHLY = "MONTHLY";
    public static final String YEARLY  = "YEARLY";

    private final SubscriptionPlanRepository planRepo;
    private final SubscriptionChangeRepository changes;

    @Autowired(required = false) private SubscriptionService subscriptionService;
    @Autowired(required = false) private AccountStatusService accountStatus;
    @Autowired(required = false) private MessagingPolicy messagingPolicy;

    public SubscriptionLifecycleService(SubscriptionPlanRepository planRepo, SubscriptionChangeRepository changes) {
        this.planRepo = planRepo;
        this.changes = changes;
    }

    /** Test seams. */
    void setSubscriptionService(SubscriptionService s) { this.subscriptionService = s; }
    void setAccountStatus(AccountStatusService a) { this.accountStatus = a; }

    // ── Snapshot ─────────────────────────────────────────────────────────────

    /** The subscription facts of a client at one moment; null fields are simply unknown. */
    public record Snapshot(String plan, BigDecimal price, String frequency, Boolean overridden,
                           LocalDate start, LocalDate end, String status) {}

    public static Snapshot snapshot(ServiceClient c) {
        if (c == null) return null;
        return new Snapshot(c.getSubscriptionType(), c.getSubscriptionPrice(), c.getBillingFrequency(),
                c.getPriceOverridden(), c.getStartDate(), c.getEndDate(), c.getStatus());
    }

    // ── Plans and prices ─────────────────────────────────────────────────────

    /** MONTHLY or YEARLY from a form value; null/blank → null (= keep). */
    public static String normaliseFrequency(String f) {
        if (f == null || f.isBlank()) return null;
        String t = f.trim().toUpperCase();
        if (t.startsWith("YEAR") || t.equals("ANNUAL") || t.equals("ANNUALLY")) return YEARLY;
        if (t.startsWith("MONTH")) return MONTHLY;
        throw new IllegalArgumentException("Billing frequency must be Monthly or Yearly.");
    }

    /** "14.99", "$1,200" → 14.99 / 1200.00; blank → null; negative or unparsable → error. */
    public static BigDecimal parsePrice(String v) {
        if (v == null) return null;
        String t = v.trim().replace("$", "").replace(",", "");
        if (t.isEmpty()) return null;
        try {
            BigDecimal d = new BigDecimal(t).setScale(2, RoundingMode.HALF_UP);
            if (d.signum() < 0) throw new IllegalArgumentException("Price cannot be negative.");
            return d;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Price must be a number, e.g. 14.99.");
        }
    }

    /** The plan a subscription type resolves to (legacy LIMITED/FULL included), active or not. */
    public Optional<SubscriptionPlan> planFor(String subscriptionType) {
        String code = SubscriptionService.toPlanCode(subscriptionType);
        return code == null ? Optional.empty() : planRepo.findByPlanCodeIgnoreCase(code);
    }

    /** The plan's list price for a frequency; null when the plan has no yearly price. */
    public static BigDecimal listPrice(SubscriptionPlan p, String frequency) {
        if (p == null) return null;
        if (YEARLY.equals(frequency)) return p.getYearlyPrice();
        return p.getMonthlyPrice() != null ? p.getMonthlyPrice() : BigDecimal.ZERO;
    }

    /**
     * Refuses a NEWLY assigned plan that is not an active Subscription Plan. An
     * unchanged plan is never re-checked, so editing a client whose plan was later
     * deactivated still saves its other fields.
     */
    public void validatePlanChange(Snapshot before, ServiceClient c) {
        String now = SubscriptionService.toPlanCode(c.getSubscriptionType());
        String was = before == null ? null : SubscriptionService.toPlanCode(before.plan());
        if (now == null || now.equalsIgnoreCase(was)) return;
        checkConversionAllowed(c.getClientId(), now);
        SubscriptionPlan p = planFor(now).orElse(null);
        if (p == null || !p.isActive()) {
            throw new IllegalArgumentException("Subscription plan '" + c.getSubscriptionType()
                    + "' does not exist or is inactive. Choose a plan from Subscription Plans.");
        }
    }

    /**
     * Sets billing frequency and price on {@code c} (not saved).
     *
     * @param frequencyIn MONTHLY/YEARLY, or null to keep the client's current one
     * @param customPrice the negotiated price (used only when the price is overridden)
     * @param overriddenIn true = custom price, false = plan list price, null = keep as is
     * @param resetToPlan true = re-copy the plan's current list price
     */
    public void applyPricing(ServiceClient c, Snapshot before, String frequencyIn,
                             BigDecimal customPrice, Boolean overriddenIn, boolean resetToPlan) {
        String freq = normaliseFrequency(frequencyIn);
        if (freq == null) freq = c.getBillingFrequency();
        if (freq == null) freq = "YEARS".equalsIgnoreCase(c.getActivePeriodUnit()) ? YEARLY : MONTHLY;
        c.setBillingFrequency(freq);

        boolean wasOverridden = before != null && Boolean.TRUE.equals(before.overridden());
        boolean overridden = overriddenIn != null ? overriddenIn : wasOverridden;

        if (overridden) {
            BigDecimal price = customPrice != null ? customPrice : c.getSubscriptionPrice();
            if (price == null) throw new IllegalArgumentException("Enter the custom price for this client.");
            c.setSubscriptionPrice(price);
            c.setPriceOverridden(true);
            return;
        }
        boolean planChanged = before == null || !Objects.equals(
                SubscriptionService.toPlanCode(before.plan()), SubscriptionService.toPlanCode(c.getSubscriptionType()));
        boolean freqChanged = before == null || !freq.equals(before.frequency());
        if (planChanged || freqChanged || resetToPlan || wasOverridden || c.getSubscriptionPrice() == null) {
            SubscriptionPlan plan = planFor(c.getSubscriptionType()).orElse(null);
            if (plan == null) {                       // unknown legacy code: nothing to copy
                c.setSubscriptionPrice(null);
            } else {
                BigDecimal price = listPrice(plan, freq);
                if (price == null) {
                    throw new IllegalArgumentException("The " + plan.getPlanName() + " plan has no yearly price. "
                            + "Choose Monthly billing, enter a custom price, or set a Yearly Price on the plan.");
                }
                c.setSubscriptionPrice(price);
            }
        }
        c.setPriceOverridden(false);
    }

    /** Shown when a sample-data trial is asked to move to a paid plan. */
    public static final String SAMPLE_TRIAL_NOT_CONVERTIBLE =
            "This trial contains sample data and cannot be converted. "
          + "Register a new client for the requested plan.";

    /**
     * The conversion rule. A sample-data trial ({@code TRIAL-} id, created by the
     * self-service page with demo data) may only stay on the TRIAL plan — it can be
     * extended, never converted. An empty-account trial ({@code CHR} id, the Register
     * Client path) may move to any active plan and keeps all of its data. Internal
     * {@code DEMO-} accounts are out of scope and may use any plan, as before.
     *
     * @param newPlanCode the canonical plan code being assigned
     * @throws IllegalArgumentException for a sample-data trial leaving the TRIAL plan
     */
    public static void checkConversionAllowed(String clientId, String newPlanCode) {
        if (clientId == null || newPlanCode == null) return;
        boolean sampleDataTrial = clientId.startsWith(TestDataService.TRIAL_CLIENT_PREFIX);
        if (sampleDataTrial && !TrialPolicy.TRIAL_PLAN_CODE.equalsIgnoreCase(newPlanCode)) {
            throw new IllegalArgumentException(SAMPLE_TRIAL_NOT_CONVERTIBLE);
        }
    }

    // ── History + caches ─────────────────────────────────────────────────────

    /**
     * Writes a history row when anything changed between {@code before} and the
     * saved client, then drops the client's cached plan, status and messaging
     * answers. Never throws: a history write failing must not undo the change.
     */
    public void recordAndRefresh(Snapshot before, ServiceClient after, String actor, String reason) {
        if (after == null) return;
        Snapshot now = snapshot(after);
        boolean changed = before == null
                || !Objects.equals(before.plan(), now.plan())
                || !samePrice(before.price(), now.price())
                || !Objects.equals(before.frequency(), now.frequency())
                || !Objects.equals(before.start(), now.start())
                || !Objects.equals(before.end(), now.end())
                || !Objects.equals(before.status(), now.status());
        if (changed && after.getClientId() != null) {
            try {
                SubscriptionChange h = new SubscriptionChange();
                h.setClientId(after.getClientId());
                h.setChangedAt(LocalDateTime.now());
                h.setChangedBy(actor);
                h.setReason(before == null ? "CREATED" : reason);
                if (before != null) {
                    h.setFromPlan(before.plan());
                    h.setFromPrice(before.price());
                    h.setFromFrequency(before.frequency());
                    h.setFromStartDate(before.start());
                    h.setFromEndDate(before.end());
                    h.setFromStatus(before.status());
                }
                h.setToPlan(now.plan());
                h.setToPrice(now.price());
                h.setToFrequency(now.frequency());
                h.setToStartDate(now.start());
                h.setToEndDate(now.end());
                h.setToStatus(now.status());
                changes.save(h);
            } catch (Exception e) {
                log.error("Subscription history not recorded for {} — {}", after.getClientId(), e.toString());
            }
        }
        refresh(after.getClientId());
    }

    /** Drops every cached answer derived from this client's subscription. */
    public void refresh(String clientId) {
        if (clientId == null) return;
        if (subscriptionService != null) subscriptionService.evict(clientId);
        if (accountStatus != null) accountStatus.invalidateTenant(clientId);
        if (messagingPolicy != null) messagingPolicy.invalidate(clientId);
    }

    private static boolean samePrice(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : (b != null && a.compareTo(b) == 0);
    }
}
