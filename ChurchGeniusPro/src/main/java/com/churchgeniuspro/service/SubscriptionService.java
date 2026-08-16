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
                              Map<String, Boolean> features, int extraSms) {}

    public SubscriptionService(ServiceClientRepository clientRepo,
                               SubscriptionPlanRepository planRepo,
                               SubscriptionUsageRepository usageRepo) {
        this.clientRepo = clientRepo;
        this.planRepo   = planRepo;
        this.usageRepo  = usageRepo;
    }

    // ── Plan resolution ───────────────────────────────────────────────────

    /** Legacy ServiceClient.subscriptionType values → plan codes. */
    private static String toPlanCode(String subscriptionType) {
        if (subscriptionType == null || subscriptionType.isBlank()) return null;
        return switch (subscriptionType.trim().toUpperCase()) {
            case "FREE"    -> "FREE";
            case "LIMITED" -> "STANDARD";
            case "FULL"    -> "PRO";
            default        -> subscriptionType.trim().toUpperCase(); // direct plan code
        };
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
        try {
            ServiceClient sc = clientRepo.findByClientId(clientId).orElse(null);
            String planCode = sc != null ? toPlanCode(sc.getSubscriptionType()) : null;
            if (sc != null && sc.getExtraSmsCount() != null) {
                extraSms = Math.max(0, sc.getExtraSmsCount());
            }
            if (planCode != null) {
                plan = planRepo.findByPlanCodeIgnoreCase(planCode).filter(SubscriptionPlan::isActive).orElse(null);
            }
            if (plan != null && plan.getFeaturesJson() != null && !plan.getFeaturesJson().isBlank()) {
                features = MAPPER.readValue(plan.getFeaturesJson(), new TypeReference<Map<String, Boolean>>() {});
            }
        } catch (Exception ex) {
            log.warn("Subscription plan resolution failed for clientId={} — {}", clientId, ex.getMessage());
        }
        CacheEntry entry = new CacheEntry(now + CACHE_TTL_MS, plan, features, extraSms);
        cache.put(clientId, entry);
        return entry;
    }

    /** Drops all cached resolutions (called after a plan is edited). */
    public void clearCache() {
        cache.clear();
    }

    // ── Feature flags ─────────────────────────────────────────────────────

    /**
     * True unless the client's plan explicitly disables the feature.
     * Unknown client / plan / key → enabled (fail-open).
     */
    public boolean isFeatureEnabled(String clientId, String featureKey) {
        CacheEntry e = resolve(clientId);
        if (e == null || e.plan() == null) return true;
        Boolean v = e.features().get(featureKey);
        return v == null || v;
    }

    /** Effective feature map for the client (explicit values only; missing = enabled). */
    public Map<String, Boolean> featureMap(String clientId) {
        CacheEntry e = resolve(clientId);
        return e != null ? e.features() : Collections.emptyMap();
    }

    // ── Usage counters (emails / SMS / online giving) ─────────────────────

    private static String currentMonth() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    private SubscriptionUsage usageRow(String clientId) {
        String month = currentMonth();
        return usageRepo.findByClientIdAndUsageMonth(clientId, month).orElseGet(() -> {
            SubscriptionUsage u = new SubscriptionUsage();
            u.setClientId(clientId);
            u.setUsageMonth(month);
            try {
                return usageRepo.save(u);
            } catch (Exception dup) { // unique-constraint race: another thread created it
                return usageRepo.findByClientIdAndUsageMonth(clientId, month).orElse(u);
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
        try { usageRow(clientId); usageRepo.addEmails(clientId, currentMonth(), 1); }
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
        try { usageRow(clientId); usageRepo.addSms(clientId, currentMonth(), 1); }
        catch (Exception ex) { log.warn("sms usage increment failed for {} — {}", clientId, ex.getMessage()); }
    }

    /** True when the client may accept one more online-giving transaction this month. */
    public boolean canAcceptOnlineGiving(String clientId) {
        SubscriptionPlan plan = getPlan(clientId);
        if (plan == null || plan.getMaxOnlineGivingPerMonth() == null) return true;
        return usageRow(clientId).getGivingCount() < plan.getMaxOnlineGivingPerMonth();
    }

    public void recordOnlineGiving(String clientId) {
        try { usageRow(clientId); usageRepo.addGiving(clientId, currentMonth(), 1); }
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
    public Map<String, Object> describe(String clientId) {
        Map<String, Object> out = new HashMap<>();
        SubscriptionPlan plan = getPlan(clientId);
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
        }
        out.put("limits", limits);
        try {
            SubscriptionUsage u = usageRepo
                    .findByClientIdAndUsageMonth(clientId, currentMonth()).orElse(null);
            Map<String, Object> usage = new HashMap<>();
            usage.put("month",       currentMonth());
            usage.put("emailsSent",  u != null ? u.getEmailsSent() : 0);
            usage.put("smsSent",     u != null ? u.getSmsSent() : 0);
            usage.put("givingCount", u != null ? u.getGivingCount() : 0);
            out.put("usage", usage);
        } catch (Exception ignored) { }
        return out;
    }
}
