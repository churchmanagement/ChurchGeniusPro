package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.DemoRoleAccess;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.repository.DemoRoleAccessRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether an already-signed-in account is still allowed to be here.
 *
 * <p>Expiry, suspension and demo blocks were decided once, at sign-in, and never
 * asked again. A session therefore outlived the answer: for up to the idle timeout
 * normally, until the 30-day cookie ran out for a member who had enabled "Don't log
 * out automatically" (which sets the session to never expire), and indefinitely
 * through remember-me. The practical effect was that an admin pressing <b>Block</b>
 * on a demo login, or a subscription lapsing overnight, changed nothing for anyone
 * already signed in.
 *
 * <p>Answers are cached for a minute per tenant and per login, so the per-request
 * filter in front of this costs a map lookup almost always, and a change takes
 * effect within the minute.
 *
 * <p>Every path fails OPEN. This runs on every API call of every signed-in user;
 * a database blip must cost nobody their session. The authoritative refusals are
 * still the ones at sign-in.
 */
@Service
public class AccountStatusService {

    private static final Logger log = LoggerFactory.getLogger(AccountStatusService.class);

    private static final long CACHE_TTL_MS = 60_000;

    /** Shown when the tenant's own subscription has ended. */
    public static final String EXPIRED_CODE = "SUBSCRIPTION_EXPIRED";
    /** Shown when this particular demo/trial login has expired or been blocked. */
    public static final String DEMO_ENDED_CODE = "DEMO_ACCESS_ENDED";
    /** Phase C: the tenant's trial request is Pending, Disabled or Deleted. The session is ended. */
    public static final String TRIAL_STATUS_CODE = "TRIAL_STATUS";

    private final ServiceClientRepository clientRepo;
    private final DemoRoleAccessRepository accessRepo;

    private record Cached(long expiresAt, String reason, String code) {}
    private final ConcurrentHashMap<String, Cached> tenantCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Cached> loginCache  = new ConcurrentHashMap<>();

    public AccountStatusService(ServiceClientRepository clientRepo, DemoRoleAccessRepository accessRepo) {
        this.clientRepo = clientRepo;
        this.accessRepo = accessRepo;
    }

    /** Why this session must stop, or {@code null} when it may continue. */
    public record Block(String message, String code) {}

    /**
     * The tenant-level answer: has this church's subscription ended, been suspended
     * or been removed since the session started?
     *
     * <p>Deliberately the same rule the login queries apply
     * ({@code delete_flag = false AND status = 'Active' AND end_date > current_date}),
     * so a session is cut off exactly when a fresh sign-in would be refused —
     * never sooner.
     */
    public Block forTenant(String clientId) {
        if (clientId == null || clientId.isBlank()) return null;
        Cached c = tenantCache.get(clientId);
        long now = System.currentTimeMillis();
        if (c == null || c.expiresAt() <= now) {
            c = new Cached(now + CACHE_TTL_MS, null, null);
            try {
                ServiceClient sc = clientRepo.findByClientId(clientId).orElse(null);
                if (sc != null) {
                    if (Boolean.TRUE.equals(sc.getDeleteFlag())) {
                        c = new Cached(now + CACHE_TTL_MS,
                                "This account is no longer active. Please contact your administrator.",
                                EXPIRED_CODE);
                    } else if (sc.getStatus() != null && !"Active".equalsIgnoreCase(sc.getStatus().trim())) {
                        String st = sc.getStatus().trim();
                        // Phase C: a trial account a Service Admin set to Pending / Disabled /
                        // Deleted is refused on every request, and the session is ended.
                        if (com.churchgeniuspro.hibernate.TrialRequest.TENANT_PENDING.equalsIgnoreCase(st)
                                || com.churchgeniuspro.hibernate.TrialRequest.TENANT_DISABLED.equalsIgnoreCase(st)
                                || com.churchgeniuspro.hibernate.TrialRequest.TENANT_DELETED.equalsIgnoreCase(st)) {
                            String why = com.churchgeniuspro.hibernate.TrialRequest.TENANT_PENDING.equalsIgnoreCase(st)
                                    ? "This trial account is pending approval"
                                    : com.churchgeniuspro.hibernate.TrialRequest.TENANT_DISABLED.equalsIgnoreCase(st)
                                        ? "This trial account has been disabled" : "This trial account has been removed";
                            c = new Cached(now + CACHE_TTL_MS,
                                    why + ". Access is not available. Please contact support@churchgeniuspro.com.",
                                    TRIAL_STATUS_CODE);
                        } else {
                            c = new Cached(now + CACHE_TTL_MS,
                                    "This account is not active. Please contact your administrator.",
                                    EXPIRED_CODE);
                        }
                    } else if (SubscriptionService.isExpired(sc.getEndDate(), com.churchgeniuspro.util.AppClock.today())) {
                        c = new Cached(now + CACHE_TTL_MS,
                                "This subscription expired on " + sc.getEndDate()
                              + " and access has ended. Please contact your administrator to renew.",
                                EXPIRED_CODE);
                    }
                }
            } catch (Exception e) {
                log.warn("Account status: tenant lookup failed for {} — allowing. {}", clientId, e.getMessage());
                return null;                       // not cached: retry on the next request
            }
            tenantCache.put(clientId, c);
        }
        return c.reason() == null ? null : new Block(c.reason(), c.code());
    }

    /**
     * The per-login answer for demo/trial accounts: has THIS login's access window
     * ended, or has a Service Admin blocked it?
     *
     * <p>By username rather than signup id, because remember-me and account
     * switching build sessions without recording the id — the same reason
     * {@code DemoTrialAgreementFilter} looks it up this way.
     *
     * @param clientId the session's tenant, used only to skip the lookup entirely
     *                 for tenants that cannot have a window
     */
    public Block forLogin(String clientId, String username) {
        if (username == null || username.isBlank()) return null;
        // Windows exist only for tenants this deployment provisioned, so a real
        // church never costs a query here — a prefix comparison answers it.
        if (!EvaluationTenant.isManaged(clientId)) return null;
        Cached c = loginCache.get(username);
        long now = System.currentTimeMillis();
        if (c == null || c.expiresAt() <= now) {
            c = new Cached(now + CACHE_TTL_MS, null, null);
            try {
                Optional<DemoRoleAccess> w = accessRepo.findByUsername(username);
                if (w.isPresent() && !w.get().isUsable()) {
                    c = new Cached(now + CACHE_TTL_MS,
                            "Your demo/trial access has ended. Please contact "
                          + "support@churchgeniuspro.com if you would like to continue "
                          + "using the application.",
                            DEMO_ENDED_CODE);
                }
            } catch (Exception e) {
                log.warn("Account status: demo window lookup failed for {} — allowing. {}",
                         username, e.getMessage());
                return null;
            }
            loginCache.put(username, c);
        }
        return c.reason() == null ? null : new Block(c.reason(), c.code());
    }

    /** Drops a cached answer — call after changing a subscription or a demo window. */
    public void invalidateTenant(String clientId) {
        if (clientId != null) tenantCache.remove(clientId);
    }

    /** Drops a cached answer for one login. */
    public void invalidateLogin(String username) {
        if (username != null) loginCache.remove(username);
    }

    /** Drops every cached answer. */
    public void invalidateAll() {
        tenantCache.clear();
        loginCache.clear();
    }
}
