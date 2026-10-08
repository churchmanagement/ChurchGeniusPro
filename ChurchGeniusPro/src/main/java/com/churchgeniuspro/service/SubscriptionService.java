package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.hibernate.SubscriptionUsage;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.SubscriptionPlanRepository;
import com.churchgeniuspro.repository.SubscriptionUsageRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central resolver + enforcer for subscription plans.
 *
 * <p>Every church is a {@code ServiceClient}; its {@code subscriptionType}
 * links it to a {@link SubscriptionPlan} (legacy values FREE→FREE,
 * LIMITED→STANDARD, FULL→PRO; any other value is matched against
 * {@code plan_code} directly, so new plans work without schema changes).
 * All users sharing the clientId inherit the plan.
 *
 * <p><b>Fail-open philosophy</b> (matches RoleGuard): if the client or plan
 * cannot be resolved, or a feature key is absent from the plan's JSON, access
 * is allowed. Only an explicit {@code false} disables a feature; only an
 * explicit numeric limit constrains usage ({@code null} = unlimited).
 *
 * <p>Plan lookups are cached for 60 seconds per clientId to keep the
 * per-request filter cheap.
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);
    private static final long CACHE_TTL_MS = 60_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ServiceClientRepository    clientRepo;
    private final SubscriptionPlanRepository planRepo;
    private final SubscriptionUsageRepository usageRepo;

    /** clientId → (expiryEpochMs, resolved plan or null, features, per-client extra SMS). */
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private record CacheEntry(long expiresAt, SubscriptionPlan plan,
                              Map<String, Boolean> features, int extraSms, LocalDate startDate) {}

    public SubscriptionService(ServiceClientRepository clientRepo,
                               SubscriptionPlanRepository planRepo,
                               SubscriptionUsageRepository usageRepo) {
        this.clientRepo = clientRepo;
        this.planRepo   = planRepo;
        this.usageRepo  = usageRepo;
    }

    // ── Plan resolution ───────────────────────────────────────────────────

    /** Legacy ServiceClient.subscriptionType values → plan codes. */
    /**
     * Maps the value stored in {@code service_client.subscription_type} to a
     * {@code subscription_plan.plan_code}.
     *
     * <p>Public so callers that need to display or select a client's plan resolve it the
     * same way this service does, rather than re-implementing the legacy aliases and
     * drifting apart.
     */
    public static String toPlanCode(String subscriptionType) {
        if (subscriptionType == null || subscriptionType.isBlank()) return null;
        return switch (subscriptionType.trim().toUpperCase()) {
            case "FREE"    -> "FREE";
            case "LIMITED" -> "STANDARD";
            case "FULL"    -> "PRO";
            default        -> subscriptionType.trim().toUpperCase(); // direct plan code
        };
    }

    /**
     * Whether a subscription has run out, as of {@code today}.
     *
     * <p>This is the single definition of "expired" in the application, and it is written to
     * match the login queries — {@code countValidChurchLogin} and
     * {@code countValidNonChurchLogin} both require {@code sc.end_date > current_date}.
     * A subscription whose end date is <em>today</em> is therefore already expired: the
     * comparison is strict, not inclusive.
     *
     * <p>Having one method say so matters because the same boundary is decided in three
     * places — the SQL that refuses the login, the message that explains why, and the
     * Active/Expired badge on the admin screen. If those drift by a single day, an admin
     * sees "Active" next to an account that cannot sign in and has nothing to go on.
     *
     * <p>A {@code null} end date is treated as <em>not</em> expired: it means no expiry was
     * ever set, and the SQL comparison against NULL is likewise never true.
     */
    public static boolean isExpired(LocalDate endDate, LocalDate today) {
        return endDate != null && !endDate.isAfter(today);
    }

    // ── Account validity (active vs expired/inactive) ─────────────────────

    /**
     * Client ids of every church whose account is active right now —
     * {@code status = 'Active'}, not deleted, {@code end_date > today}.
     *
     * <p>The one definition background jobs use to decide whether a church still gets
     * work done on its behalf (push notifications, Bank Sync). It is the same predicate
     * that refuses sign-in and that the reminder schedulers already apply, so a church
     * nobody can sign in to is also a church nothing runs for. One query per job run,
     * not per recipient. Not cached: a renewal must take effect on the next run.
     */
    public java.util.Set<String> activeAccountClientIds() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String id : clientRepo.findActiveAccountClientIds()) if (id != null) out.add(id);
        return out;
    }

    /**
     * The same rule for one {@code service_client} row, for screens that already hold
     * it. Mirrors {@link ServiceClientRepository#findActiveAccountClientIds}: a NULL end
     * date is inactive here because {@code end_date > CURRENT_DATE} is never true for it.
     */
    public static boolean isAccountActive(ServiceClient sc, LocalDate today) {
        return sc != null
                && !Boolean.TRUE.equals(sc.getDeleteFlag())
                && "Active".equals(sc.getStatus())
                && sc.getEndDate() != null
                && !isExpired(sc.getEndDate(), today);
    }

    /** {@link #isAccountActive(ServiceClient, LocalDate)} for one church by client id; unknown → inactive. */
    public boolean isAccountActive(String clientId) {
        if (clientId == null || clientId.isBlank()) return false;
        try {
            return isAccountActive(clientRepo.findByClientId(clientId).orElse(null), com.churchgeniuspro.util.AppClock.today());
        } catch (Exception e) {
            log.warn("Active-account lookup failed for {} — {}", clientId, e.getMessage());
            return false;
        }
    }

    /** The plan for a church, or {@code null} when unresolvable (→ allow all). */
    public SubscriptionPlan getPlan(String clientId) {
        CacheEntry e = resolve(clientId);
        return e != null ? e.plan() : null;
    }

    private CacheEntry resolve(String clientId) {
        if (clientId == null || clientId.isBlank()) return null;
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(clientId);
        if (cached != null && cached.expiresAt() > now) return cached;

        SubscriptionPlan plan = null;
        Map<String, Boolean> features = Collections.emptyMap();
        int extraSms = 0;
        LocalDate startDate = null;
        try {
            ServiceClient sc = clientRepo.findByClientId(clientId).orElse(null);
            if (sc != null) startDate = sc.getStartDate();
            String planCode = sc != null ? toPlanCode(sc.getSubscriptionType()) : null;
            if (sc != null && sc.getExtraSmsCount() != null) {
                extraSms = Math.max(0, sc.getExtraSmsCount());
            }
            if (planCode != null) {
                plan = planRepo.findByPlanCodeIgnoreCase(planCode).filter(SubscriptionPlan::isActive).orElse(null);
                if (plan == null) {
                    // Not a detail for a debug log: this client has a plan NAME and no
                    // plan ROW, and the fail-open contract below then grants it every
                    // feature and every limit. Whoever deleted or deactivated that
                    // plan needs to see this.
                    log.error("Subscription plan '{}' is missing or inactive — client {} is running "
                            + "UNRESTRICTED (every feature enabled, no limits). Restore the plan row.",
                              planCode, clientId);
                }
            }
            if (plan != null && plan.getFeaturesJson() != null && !plan.getFeaturesJson().isBlank()) {
                features = MAPPER.readValue(plan.getFeaturesJson(), new TypeReference<Map<String, Boolean>>() {});
            }
        } catch (Exception ex) {
            log.warn("Subscription plan resolution failed for clientId={} — {}", clientId, ex.getMessage());
        }
        CacheEntry entry = new CacheEntry(now + CACHE_TTL_MS, plan, features, extraSms, startDate);
        cache.put(clientId, entry);
        return entry;
    }

    /** Drops all cached resolutions (called after a plan is edited). */
    public void clearCache() {
        cache.clear();
    }

    /** Drops one client's cached resolution (called after that client's subscription changes). */
    public void evict(String clientId) {
        if (clientId != null) cache.remove(clientId);
    }

    // ── Feature flags ─────────────────────────────────────────────────────

    /**
     * Features withheld from evaluation tenants whatever their plan says.
     *
     * <p>A plan flag alone cannot express this. A demo tenant is created by
     * {@code loadSmallDemo} on ANY plan — including Pro — so "disable it on the
     * Trial plan" misses demo tenants entirely, and misses a trial whose plan row
     * has not been configured yet.
     */
    static final java.util.Set<String> EVALUATION_DISABLED_FEATURES =
            java.util.Set.of(
                    "activityCorner",
                    // Real money. An evaluation tenant that entered its own Stripe
                    // keys could otherwise accept live donations from a published
                    // /donate link, which no part of "evaluating the product" needs.
                    "onlineGiving");

    /**
     * The demo/trial gate. Field-injected and null-checked so this service's
     * existing construction sites (and unit tests) are unaffected — the same
     * pattern {@code EmailService} uses.
     */
    @Autowired(required = false)
    private MessagingPolicy messagingPolicy;

    /** Test seam — supply the policy without a Spring context. */
    public void setMessagingPolicy(MessagingPolicy p) { this.messagingPolicy = p; }

    /**
     * True for an evaluation tenant: a demo/trial client id, or a Trial subscription.
     *
     * <p>The prefix check comes first and needs no database at all, so a tenant
     * created by demo loading or self-service trial registration is classified
     * correctly even before its plan row has been configured. Only a {@code CHR-}
     * client that has been put on the Trial plan needs the lookup.
     *
     * <p>Fails OPEN, matching this class's stated contract: an unreadable
     * subscription must not quietly withhold a feature from a paying church. The
     * deterministic half above is what makes that acceptable — the tenants this
     * is mainly protecting are identified by their id, not by a query.
     */
    public boolean isEvaluationTenant(String clientId) {
        if (clientId == null || clientId.isBlank()) return false;
        Boolean trialState = null;
        if (messagingPolicy != null) {
            try {
                trialState = messagingPolicy.trialState(clientId);
            } catch (Exception e) {
                log.warn("SubscriptionService: trial lookup failed for {} — {}", clientId, e.getMessage());
            }
        }
        return EvaluationTenant.isEvaluation(clientId, trialState);
    }

    /**
     * True unless the client's plan explicitly disables the feature, or the tenant
     * is an evaluation account and the feature is withheld from those.
     *
     * <p>Unknown client / plan / key → enabled (fail-open).
     *
     * <p>Both readers of feature state apply the evaluation overlay — this and
     * {@link #featureMap} — so the server-side gate
     * ({@code SubscriptionFeatureFilter}) and everything the front end hides from
     * {@code /api/subscription/features} agree without either having to know the
     * rule.
     */
    public boolean isFeatureEnabled(String clientId, String featureKey) {
        if (EVALUATION_DISABLED_FEATURES.contains(featureKey) && isEvaluationTenant(clientId)) {
            return false;
        }
        CacheEntry e = resolve(clientId);
        if (e == null || e.plan() == null) return true;
        Boolean v = e.features().get(featureKey);
        return v == null || v;
    }

    /** Effective feature map for the client (explicit values only; missing = enabled). */
    public Map<String, Boolean> featureMap(String clientId) {
        CacheEntry e = resolve(clientId);
        Map<String, Boolean> base = e != null ? e.features() : Collections.emptyMap();
        if (!isEvaluationTenant(clientId)) return base;
        // Overlay, not mutate: e.features() is the cached map shared by every
        // caller for this client, and writing into it would leak one tenant's
        // restriction into the cache.
        Map<String, Boolean> merged = new java.util.LinkedHashMap<>(base);
        for (String key : EVALUATION_DISABLED_FEATURES) merged.put(key, Boolean.FALSE);
        return merged;
    }

    // ── Usage counters (emails / SMS / online giving) ─────────────────────

    /** Length of a usage period: the "monthly" allowances reset every 30 days. */
    public static final int USAGE_PERIOD_DAYS = 30;

    /**
     * Start of the 30-day usage period containing {@code today}, counted from the
     * subscription start date ({@code anchor}): anchor + 30×N. A start date in the
     * future counts from that date; no start date falls back to the calendar month.
     */
    public static LocalDate periodStartFor(LocalDate anchor, LocalDate today) {
        if (anchor == null) return today.withDayOfMonth(1);
        if (!today.isAfter(anchor)) return anchor;
        long n = java.time.temporal.ChronoUnit.DAYS.between(anchor, today) / USAGE_PERIOD_DAYS;
        return anchor.plusDays(n * USAGE_PERIOD_DAYS);
    }

    /** Last day of the usage period starting at {@code periodStart}. */
    public static LocalDate periodEndFor(LocalDate periodStart, LocalDate anchor) {
        return anchor == null ? periodStart.plusMonths(1).minusDays(1) : periodStart.plusDays(USAGE_PERIOD_DAYS - 1);
    }

    /** Start of this client's current usage period (America/Chicago today). */
    LocalDate currentPeriod(String clientId) {
        CacheEntry e = resolve(clientId);
        return periodStartFor(e != null ? e.startDate() : null, com.churchgeniuspro.util.AppClock.today());
    }

    private SubscriptionUsage usageRow(String clientId) {
        LocalDate period = currentPeriod(clientId);
        return usageRepo.findByClientIdAndPeriodStart(clientId, period).orElseGet(() -> {
            SubscriptionUsage u = new SubscriptionUsage();
            u.setClientId(clientId);
            u.setPeriodStart(period);
            u.setUsageMonth(period.format(DateTimeFormatter.ofPattern("yyyy-MM")));
            try {
                return usageRepo.save(u);
            } catch (Exception dup) { // unique-constraint race: another thread created it
                return usageRepo.findByClientIdAndPeriodStart(clientId, period).orElse(u);
            }
        });
    }

    /** True when the client may send one more email this month. */
    public boolean canSendEmail(String clientId) {
        SubscriptionPlan plan = getPlan(clientId);
        if (plan == null || plan.getMaxEmailsPerMonth() == null) return true;
        return usageRow(clientId).getEmailsSent() < plan.getMaxEmailsPerMonth();
    }

    public void recordEmailSent(String clientId) {
        try { usageRow(clientId); usageRepo.addEmails(clientId, currentPeriod(clientId), 1); }
        catch (Exception ex) { log.warn("email usage increment failed for {} — {}", clientId, ex.getMessage()); }
    }

    /**
     * True when the client may send one more SMS this month. Effective limit =
     * plan's monthly SMS allowance + the CLIENT's own Extra SMS Count (granted
     * per registered client by the Service Admin).
     */
    public boolean canSendSms(String clientId) {
        CacheEntry e = resolve(clientId);
        SubscriptionPlan plan = e != null ? e.plan() : null;
        if (plan == null || plan.getMaxSmsPerMonth() == null) return true;
        int allowed = plan.getMaxSmsPerMonth() + (e != null ? e.extraSms() : 0);
        return usageRow(clientId).getSmsSent() < allowed;
    }

    public void recordSmsSent(String clientId) {
        try { usageRow(clientId); usageRepo.addSms(clientId, currentPeriod(clientId), 1); }
        catch (Exception ex) { log.warn("sms usage increment failed for {} — {}", clientId, ex.getMessage()); }
    }

    /** True when the client may accept one more online-giving transaction this month. */
    public boolean canAcceptOnlineGiving(String clientId) {
        SubscriptionPlan plan = getPlan(clientId);
        if (plan == null || plan.getMaxOnlineGivingPerMonth() == null) return true;
        return usageRow(clientId).getGivingCount() < plan.getMaxOnlineGivingPerMonth();
    }

    public void recordOnlineGiving(String clientId) {
        try { usageRow(clientId); usageRepo.addGiving(clientId, currentPeriod(clientId), 1); }
        catch (Exception ex) { log.warn("giving usage increment failed for {} — {}", clientId, ex.getMessage()); }
    }

    // ── Count-based limits (people / portals) ─────────────────────────────

    /**
     * Null when {@code currentCount + adding} fits the plan's people limit;
     * otherwise a user-facing message.
     */
    public String checkPeopleLimit(String clientId, long currentCount, int adding) {
        SubscriptionPlan plan = getPlan(clientId);
        if (plan == null || plan.getMaxPeople() == null) return null;
        if (currentCount + adding <= plan.getMaxPeople()) return null;
        // Existing members are always retained, viewable, and editable — the
        // limit only blocks NEW additions (never deletes or hides data).
        return "Your current subscription allows up to " + plan.getMaxPeople()
                + " members. You currently have " + currentCount
                + " members. Please upgrade your subscription before adding more members.";
    }

    /** Null when one more member portal fits the limit; otherwise a message. */
    public String checkMemberPortalLimit(String clientId, long currentCount) {
        SubscriptionPlan plan = getPlan(clientId);
        if (plan == null || plan.getMaxMemberPortals() == null) return null;
        if (currentCount < plan.getMaxMemberPortals()) return null;
        return "Your " + plan.getPlanName() + " subscription allows up to " + plan.getMaxMemberPortals()
                + " member portals. The limit has been reached — please upgrade your plan.";
    }

    /**
     * Null when the church may add one more staff user on /viewusers; otherwise a
     * message. {@code currentCount} is the church's non-deleted app_user rows.
     */
    public String checkStaffUserLimit(String clientId, long currentCount) {
        SubscriptionPlan plan = getPlan(clientId);
        if (plan == null || plan.getMaxStaffUsers() == null) return null;
        if (currentCount < plan.getMaxStaffUsers()) return null;
        return "Your " + plan.getPlanName() + " subscription allows up to " + plan.getMaxStaffUsers()
                + " users. The limit has been reached — please upgrade your plan to add more users.";
    }

    /**
     * Bank accounts this church may have connected through Bank Sync, counted as
     * individual accounts; null = unlimited, 0 = none. Null also when the plan
     * cannot be resolved (the usual fail-open rule).
     */
    public Integer bankAccountLimit(String clientId) {
        SubscriptionPlan plan = getPlan(clientId);
        return plan == null ? null : plan.getMaxBankAccounts();
    }

    /**
     * Null when connecting {@code adding} more bank accounts on top of
     * {@code currentCount} fits the plan; otherwise a user-facing message that
     * names the limit and the current count. Existing connections are never
     * removed by a downgrade — a church over its limit keeps every account and is
     * only refused new ones.
     */
    public String checkBankAccountLimit(String clientId, long currentCount, int adding) {
        SubscriptionPlan plan = getPlan(clientId);
        if (plan == null || plan.getMaxBankAccounts() == null) return null;
        int limit = plan.getMaxBankAccounts();
        if (currentCount + adding <= limit) return null;
        String planName = plan.getPlanName() != null ? plan.getPlanName() : "current";
        if (limit == 0) {
            return "Bank Sync is not included in your " + planName + " subscription. "
                 + "Please upgrade your plan to connect a bank account.";
        }
        String have = currentCount == 1 ? "1 connected account" : currentCount + " connected accounts";
        if (currentCount >= limit) {
            return "Your " + planName + " subscription allows up to " + limit + " connected bank account"
                 + (limit == 1 ? "" : "s") + ". You already have " + have
                 + ", so no more can be added. Disconnect an account or upgrade your plan to connect another.";
        }
        long room = limit - currentCount;
        return "Your " + planName + " subscription allows up to " + limit + " connected bank account"
             + (limit == 1 ? "" : "s") + ". You have " + have + " and this connection would add " + adding
             + ", which is more than the " + room + " remaining. Select at most " + room
             + " account" + (room == 1 ? "" : "s") + " in the bank login, or upgrade your plan.";
    }

    /** Null when one more kids portal fits the limit; otherwise a message. */
    public String checkKidsPortalLimit(String clientId, long currentCount) {
        SubscriptionPlan plan = getPlan(clientId);
        if (plan == null || plan.getMaxKidsPortals() == null) return null;
        if (currentCount < plan.getMaxKidsPortals()) return null;
        return "Your " + plan.getPlanName() + " subscription allows up to " + plan.getMaxKidsPortals()
                + " kids portals. The limit has been reached — please upgrade your plan.";
    }

    // ── Frontend payload ──────────────────────────────────────────────────

    /** Plan + features + limits + current usage, for /api/subscription/features. */
    /**
     * The trial facts shown to a Trial-plan church on first sign-in: length, start
     * and end dates and days remaining (America/Chicago dates). Access closes ON the
     * end date, so a 60-day trial starting October 5 ends December 4 and
     * {@code lastDay} is December 3. Null when the client is not on the Trial plan.
     */
    public Map<String, Object> trialInfo(String clientId) {
        if (clientId == null) return null;
        try {
            ServiceClient sc = clientRepo.findByClientId(clientId).orElse(null);
            if (sc == null || sc.getEndDate() == null
                    || !TrialPolicy.TRIAL_PLAN_CODE.equalsIgnoreCase(toPlanCode(sc.getSubscriptionType()))) {
                return null;
            }
            LocalDate today = com.churchgeniuspro.util.AppClock.today();
            Map<String, Object> t = new HashMap<>();
            if (sc.getStartDate() != null) {
                t.put("startDate", sc.getStartDate().toString());
                t.put("days", java.time.temporal.ChronoUnit.DAYS.between(sc.getStartDate(), sc.getEndDate()));
            }
            t.put("endDate", sc.getEndDate().toString());
            t.put("lastDay", sc.getEndDate().minusDays(1).toString());
            t.put("daysRemaining", Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(today, sc.getEndDate())));
            // Sample-data/demo tenants already get the blocking Trial Account agreement.
            t.put("managedTenant", TestDataService.isManagedTenant(clientId));
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    public Map<String, Object> describe(String clientId) {
        Map<String, Object> out = new HashMap<>();
        SubscriptionPlan plan = getPlan(clientId);
        Map<String, Object> trial = trialInfo(clientId);
        if (trial != null) out.put("trial", trial);
        out.put("planCode", plan != null ? plan.getPlanCode() : null);
        out.put("planName", plan != null ? plan.getPlanName() : null);
        out.put("features", featureMap(clientId));
        Map<String, Object> limits = new HashMap<>();
        CacheEntry entry = resolve(clientId);
        if (plan != null) {
            limits.put("maxPeople",                plan.getMaxPeople());
            limits.put("maxEmailsPerMonth",        plan.getMaxEmailsPerMonth());
            limits.put("maxSmsPerMonth",           plan.getMaxSmsPerMonth());
            limits.put("extraSmsCount",            entry != null ? entry.extraSms() : 0); // per-client grant
            limits.put("maxOnlineGivingPerMonth",  plan.getMaxOnlineGivingPerMonth());
            limits.put("maxMemberPortals",         plan.getMaxMemberPortals());
            limits.put("maxKidsPortals",           plan.getMaxKidsPortals());
            limits.put("maxStaffUsers",            plan.getMaxStaffUsers());
            limits.put("maxBankAccounts",          plan.getMaxBankAccounts());
            out.put("monthlyPrice", plan.getMonthlyPrice() != null ? plan.getMonthlyPrice() : java.math.BigDecimal.ZERO);
            out.put("yearlyPrice", plan.getYearlyPrice());
        }
        out.put("limits", limits);
        try {
            LocalDate period = currentPeriod(clientId);
            CacheEntry ce = resolve(clientId);
            SubscriptionUsage u = usageRepo.findByClientIdAndPeriodStart(clientId, period).orElse(null);
            Map<String, Object> usage = new HashMap<>();
            usage.put("month",       period.format(DateTimeFormatter.ofPattern("yyyy-MM")));   // kept for compatibility
            usage.put("periodStart", period.toString());
            usage.put("periodEnd",   periodEndFor(period, ce != null ? ce.startDate() : null).toString());
            usage.put("emailsSent",  u != null ? u.getEmailsSent() : 0);
            usage.put("smsSent",     u != null ? u.getSmsSent() : 0);
            usage.put("givingCount", u != null ? u.getGivingCount() : 0);
            out.put("usage", usage);
        } catch (Exception ignored) { }
        return out;
    }
}
