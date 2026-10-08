package com.churchgeniuspro.plaid.service;

import com.churchgeniuspro.plaid.config.PlaidProperties;
import com.churchgeniuspro.plaid.entity.PlaidItem;
import com.churchgeniuspro.service.EvaluationTenant;
import com.churchgeniuspro.service.MessagingPolicy;
import com.churchgeniuspro.service.TestDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Decides which Plaid environment a tenant talks to.
 *
 * <p>Evaluation tenants are confined to the Plaid <b>sandbox</b> so an evaluating
 * church can exercise the whole Bank Sync flow against Plaid's test institutions
 * without ever reaching a real bank. "Evaluation" means a Trial subscription
 * <em>or</em> a demo/trial client id ({@code DEMO-}/{@code TRIAL-}, see
 * {@link #isSandboxTenant}). Every other subscription keeps this deployment's
 * configured environment, unchanged.
 *
 * <p>This is the only place that decision is made. {@link PlaidClient} takes the
 * environment as an argument and holds no default, so no call site can quietly
 * fall back to production by forgetting to ask.
 *
 * <h2>Two questions, deliberately separate</h2>
 * <ul>
 *   <li><b>Creating</b> a connection asks the tenant's plan: {@link #envForClient}.
 *       It fails CLOSED — an unreadable subscription refuses the operation rather
 *       than guessing, because guessing "not Trial" would put a Trial tenant in
 *       front of real banks, and guessing "Trial" would silently point a paying
 *       church's live connection at test data.</li>
 *   <li><b>Using</b> an existing connection asks the item: {@link #envForItem}.
 *       A Plaid access token belongs to the environment that issued it and is
 *       meaningless in the other, so the stored stamp — not today's plan — is the
 *       only correct answer. This also keeps the background sync working through
 *       a database blip, and keeps a tenant's existing sandbox items harmless
 *       after they upgrade.</li>
 * </ul>
 */
@Service
public class PlaidEnvironmentService {

    private static final Logger log = LoggerFactory.getLogger(PlaidEnvironmentService.class);

    private final PlaidProperties props;
    private final MessagingPolicy messagingPolicy;

    public PlaidEnvironmentService(PlaidProperties props, MessagingPolicy messagingPolicy) {
        this.props = props;
        this.messagingPolicy = messagingPolicy;
    }

    /**
     * The environment a new connection for this tenant must use.
     *
     * @throws IllegalStateException when the subscription cannot be read, or when
     *         the resolved environment has no credentials configured. Both are
     *         refusals, never a fallback to the other environment.
     */
    public String envForClient(String clientId) {
        Boolean sandbox = sandboxState(clientId);
        if (sandbox == null) {
            log.warn("Plaid: refusing to pick an environment for {} — subscription unreadable", clientId);
            throw new IllegalStateException(
                    "Could not confirm your subscription just now. Please try again in a moment.");
        }

        String env = sandbox ? PlaidProperties.SANDBOX
                             : PlaidProperties.normaliseEnv(props.getEnv());

        if (!props.isConfiguredFor(env)) {
            if (PlaidProperties.SANDBOX.equals(env)) {
                // Deliberately a refusal and not a silent promotion to production:
                // a missing sandbox secret must never widen an evaluation tenant's reach.
                log.error("Plaid: no sandbox secret configured (PLAID_SANDBOX_SECRET) — "
                        + "evaluation tenant {} cannot connect", clientId);
                throw new IllegalStateException(
                        "Bank Sync is not available for trial and demo accounts yet. Please contact support.");
            }
            throw new IllegalStateException(
                    "Plaid is not configured (missing PLAID_CLIENT_ID / PLAID_SECRET).");
        }
        return env;
    }

    /**
     * The environment an existing item's access token belongs to.
     *
     * <p>Falls back to this deployment's configured environment for a row written
     * before the column existed — which is what those rows were created against.
     */
    public String envForItem(PlaidItem item) {
        String env = stampedEnv(item);
        // A demo tenant never uses real bank data, whatever an item row says. A
        // DEMO- item can only be stamped production if it was linked before demo
        // tenants were confined to the sandbox (or by hand); either way it must not
        // be synced, read or refreshed against production. Refusing here covers
        // every consumer of an existing item — sync, webhook, delete, rotation —
        // without each having to remember. TRIAL- tenants are deliberately not
        // included: a trial that upgrades keeps its id and may then hold real items.
        if (item != null && PlaidProperties.PRODUCTION.equals(env)
                && TestDataService.isDemoTenant(item.getClientId())) {
            log.error("Plaid: refusing production item {} for demo tenant {}", item.getId(), item.getClientId());
            throw new IllegalStateException(
                    "Demo accounts cannot use a production bank connection. Please disconnect it.");
        }
        return env;
    }

    /**
     * The environment stamped on an item, for DISPLAY only — never for a Plaid call.
     *
     * <p>Unlike {@link #envForItem} this never refuses, so the connections page can
     * still list (and let the admin disconnect) a demo tenant's production item
     * instead of failing to load because of it.
     */
    public String stampedEnv(PlaidItem item) {
        String stamped = item == null ? null : item.getPlaidEnv();
        return (stamped != null && !stamped.isBlank())
                ? PlaidProperties.normaliseEnv(stamped)
                : PlaidProperties.normaliseEnv(props.getEnv());
    }

    /**
     * True when this tenant is confined to the sandbox. Never throws — for display only.
     *
     * <p>Sandbox tenants are the evaluation accounts: any {@code DEMO-} or
     * {@code TRIAL-} client id, plus any client on the Trial subscription. The
     * prefix is checked first and needs no database, so a demo tenant is confined
     * even when its plan row says Pro — {@code loadSmallDemo} creates demo tenants
     * on any plan, and a plan flag alone would have let a demo login reach real banks.
     */
    public boolean isSandboxTenant(String clientId) {
        return Boolean.TRUE.equals(sandboxState(clientId));
    }

    /**
     * Three-valued sandbox decision: TRUE / FALSE / {@code null} when the
     * subscription could not be read. The deterministic prefix half never yields
     * null, so a demo or trial-registered tenant is always classified; only a
     * {@code CHR-} client needs the subscription lookup and can be "unknown".
     */
    Boolean sandboxState(String clientId) {
        // One definition of "evaluating", shared with the feature overlay, the
        // public-page rule and the Bank Sync gate: DEMO- always, TRIAL- until it
        // upgrades, anything else exactly when its subscription is Trial. A TRIAL-
        // tenant whose subscription cannot be read stays in the sandbox.
        Boolean trialState;
        try {
            trialState = messagingPolicy.trialState(clientId);
        } catch (Exception e) {
            log.warn("Plaid: subscription lookup failed for {} — {}", clientId, e.getMessage());
            trialState = null;
        }
        return EvaluationTenant.state(clientId, trialState);
    }

    /** This deployment's configured environment, for non-Trial tenants. */
    public String defaultEnv() {
        return PlaidProperties.normaliseEnv(props.getEnv());
    }
}
