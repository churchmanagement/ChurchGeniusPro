package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.repository.SubscriptionPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The one source of truth for how long a trial lasts.
 *
 * <p>Reads {@code trial_days} from the TRIAL subscription plan (Service Admin →
 * Subscription Plans). Every flow that creates a trial — Trial Registration Links,
 * approved Trial Requests, Service Admin "Register New Client" on the Trial plan,
 * demo tenants and demo-login resets — asks this class instead of carrying its own
 * number, so changing the plan changes all of them at once.
 *
 * <p><b>New trials only.</b> The value is applied when a trial or a registration
 * link is <em>created</em>. A link stores the length it was issued with
 * ({@code trial_registration_link.trial_days}) and a tenant stores its own
 * {@code end_date}; neither is revisited when this setting changes later.
 *
 * <p><b>Immediate effect.</b> The lookup is cached for a short time and
 * {@link #refresh()} drops the cache; {@code SubscriptionPlanAdminController} calls
 * it on every plan save, so a changed duration is live for the next trial or link
 * without a restart.
 *
 * <p>Falls back to {@link #FALLBACK_DAYS} when there is no active TRIAL plan or its
 * {@code trial_days} is empty or out of range — the length trials had before the
 * setting existed, so nothing regresses on a database that has not been configured.
 */
@Service
public class TrialPolicy {

    private static final Logger log = LoggerFactory.getLogger(TrialPolicy.class);

    /** The TRIAL plan's code under Subscription Plans. */
    public static final String TRIAL_PLAN_CODE = "TRIAL";
    /** Used when no configured value exists: what trials always were. */
    public static final int FALLBACK_DAYS = 30;
    /** Bounds for any trial length, configured or requested per link. */
    public static final int MIN_DAYS = 1;
    public static final int MAX_DAYS = 365;

    private static final long CACHE_TTL_MS = 30_000;

    private final SubscriptionPlanRepository planRepo;
    private volatile int  cachedDays = FALLBACK_DAYS;
    private volatile long cachedUntil = 0;

    public TrialPolicy(SubscriptionPlanRepository planRepo) {
        this.planRepo = planRepo;
    }

    /** The configured trial length in days, always within {@link #MIN_DAYS}..{@link #MAX_DAYS}. */
    public int trialDays() {
        long now = System.currentTimeMillis();
        if (now < cachedUntil) return cachedDays;
        int days = FALLBACK_DAYS;
        try {
            Integer configured = planRepo.findByPlanCodeIgnoreCase(TRIAL_PLAN_CODE)
                    .filter(SubscriptionPlan::isActive)
                    .map(SubscriptionPlan::getTrialDays)
                    .orElse(null);
            if (inRange(configured)) days = configured;
            else if (configured != null) {
                log.warn("TRIAL plan trial_days={} is out of range {}..{}; using {} days",
                         configured, MIN_DAYS, MAX_DAYS, FALLBACK_DAYS);
            }
        } catch (Exception e) {
            log.warn("Could not read the TRIAL plan's trial_days ({}); using {} days", e.getMessage(), FALLBACK_DAYS);
        }
        cachedDays  = days;
        cachedUntil = now + CACHE_TTL_MS;
        return days;
    }

    /**
     * A length requested for one specific link or tenant: honoured when it is in
     * range, otherwise the configured default. This is how an admin may still issue
     * a longer or shorter link than the plan default.
     */
    public int resolve(Integer requested) {
        return inRange(requested) ? requested : trialDays();
    }

    /** Forget the cached value; the next call re-reads the plan. */
    public void refresh() {
        cachedUntil = 0;
    }

    public static boolean inRange(Integer days) {
        return days != null && days >= MIN_DAYS && days <= MAX_DAYS;
    }
}
