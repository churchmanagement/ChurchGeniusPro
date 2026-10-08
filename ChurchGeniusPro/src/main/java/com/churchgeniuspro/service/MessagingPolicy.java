package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.repository.ServiceClientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The single authority on whether a tenant may send SMS or email.
 *
 * <p>Two rules live here, so every dispatch path asks one question instead of
 * each remembering a different subset:
 *
 * <ul>
 *   <li><b>Trial subscriptions never send.</b> A church registered with
 *       Subscription Type {@code TRIAL} is evaluating the product; letting it
 *       text and email real congregants on someone else's Twilio account and
 *       mail reputation is not part of that evaluation. The rule is derived
 *       from {@code service_client.subscription_type} rather than copied into a
 *       flag at registration, so it applies the moment a client is created as a
 *       trial, and lifts by itself the moment the plan is upgraded — no
 *       back-fill, and no stored copy that can drift out of step.</li>
 *   <li><b>Demo/test tenants stay blocked unless explicitly opened</b>, which is
 *       {@link DemoAccessService#sendingAllowed}. Kept behind this one façade so
 *       callers need not know both mechanisms exist.</li>
 * </ul>
 *
 * <p><b>A blank clientId is allowed.</b> Some sends genuinely have no tenant:
 * pre-login OTP and password-reset mail, and Service Admin broadcasts typed by
 * hand on the Service Admin screen. Those are the platform's own messages, not a
 * church's, so there is no subscription to consult. Every path that <em>does</em>
 * know its tenant is expected to pass it.
 *
 * <p>Lookups are cached briefly, because bulk sends ask this question once per
 * recipient and the answer changes only when an admin edits the client.
 */
@Service
public class MessagingPolicy {

    private static final Logger log = LoggerFactory.getLogger(MessagingPolicy.class);

    /** Subscription Type that blocks outbound messaging, as stored on ServiceClient. */
    public static final String TRIAL = "TRIAL";

    private static final long CACHE_TTL_MS = 60_000;

    public static final String TRIAL_SMS_MSG =
            "SMS sending is not available on a Trial subscription.";
    public static final String TRIAL_EMAIL_MSG =
            "Email sending is not available on a Trial subscription.";
    public static final String DEMO_SMS_MSG =
            "SMS sending is disabled for this demo/test account.";
    public static final String DEMO_EMAIL_MSG =
            "Email sending is disabled for this demo/test account.";

    private final ServiceClientRepository clientRepo;

    /** Optional so the demo feature can be absent without breaking messaging. */
    @Autowired(required = false)
    private DemoAccessService demoAccess;

    private record Cached(long expiresAt, boolean trial) {}
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    public MessagingPolicy(ServiceClientRepository clientRepo) {
        this.clientRepo = clientRepo;
    }

    /** Test seam — lets a unit test supply the demo gate without a Spring context. */
    public void setDemoAccess(DemoAccessService demoAccess) { this.demoAccess = demoAccess; }

    /**
     * True when this client is on a Trial subscription.
     *
     * <p>Fails OPEN: an unreadable subscription answers "not Trial", so a database
     * blip cannot silence a paying church's reminders. The Trial messaging block is
     * a product rule, not a security boundary. Callers for whom "unknown" is NOT
     * safely equivalent to "not Trial" — anything routing a tenant to a Plaid
     * environment, say — must use {@link #trialState} and handle the null.
     */
    public boolean isTrial(String clientId) {
        return Boolean.TRUE.equals(trialState(clientId));
    }

    /**
     * Trial status as three values: TRUE, FALSE, or {@code null} when the
     * subscription could not be read.
     *
     * <p>Exists so a caller can tell "definitely not Trial" apart from "we do not
     * know", which {@link #isTrial} deliberately collapses. Keeping both on this
     * class means there is still exactly one definition of what Trial means; only
     * the handling of uncertainty differs by caller.
     */
    public Boolean trialState(String clientId) {
        if (clientId == null || clientId.isBlank()) return Boolean.FALSE;
        long now = System.currentTimeMillis();
        Cached c = cache.get(clientId);
        if (c != null && c.expiresAt() > now) return c.trial();
        boolean trial;
        try {
            ServiceClient sc = clientRepo.findByClientId(clientId).orElse(null);
            trial = sc != null && sc.getSubscriptionType() != null
                    && TRIAL.equalsIgnoreCase(sc.getSubscriptionType().trim());
        } catch (Exception e) {
            log.warn("MessagingPolicy: subscription lookup failed for {} — {}", clientId, e.getMessage());
            return null;                     // unknown — never cached
        }
        cache.put(clientId, new Cached(now + CACHE_TTL_MS, trial));
        return trial;
    }

    /** Drops the cached answer for a client — call after changing its subscription. */
    public void invalidate(String clientId) {
        if (clientId != null) cache.remove(clientId);
    }

    /** Drops every cached answer. */
    public void invalidateAll() { cache.clear(); }

    /** @return null when the client may send SMS, otherwise the reason it may not. */
    public String smsBlockReason(String clientId) {
        if (clientId == null || clientId.isBlank()) return null;
        if (isTrial(clientId)) return TRIAL_SMS_MSG;
        if (demoAccess != null && !demoAccess.sendingAllowed(clientId, true)) return DEMO_SMS_MSG;
        return null;
    }

    /** @return null when the client may send email, otherwise the reason it may not. */
    public String emailBlockReason(String clientId) {
        if (clientId == null || clientId.isBlank()) return null;
        if (isTrial(clientId)) return TRIAL_EMAIL_MSG;
        if (demoAccess != null && !demoAccess.sendingAllowed(clientId, false)) return DEMO_EMAIL_MSG;
        return null;
    }

    public boolean smsAllowed(String clientId)   { return smsBlockReason(clientId) == null; }
    public boolean emailAllowed(String clientId) { return emailBlockReason(clientId) == null; }
}
