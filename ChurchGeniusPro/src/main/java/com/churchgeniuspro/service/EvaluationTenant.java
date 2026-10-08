package com.churchgeniuspro.service;

/**
 * The one definition of "this tenant is evaluating the product".
 *
 * <p>Four places used to decide this independently and disagreed: the feature
 * overlay checked the client-id prefix OR the Trial plan, the public-page rule did
 * the same but failed closed, Plaid looked only at the plan, and the demo access
 * windows looked only at the prefix. The gaps between them were real bugs — a demo
 * tenant on the Pro plan reached production Plaid, and a trial that upgraded stayed
 * restricted forever. This class is the rule; every gate asks it.
 *
 * <h2>The rule</h2>
 * <ul>
 *   <li><b>{@code DEMO-}</b> — always evaluation. Demo tenants are generated sample
 *       data with credentials a Service Admin can read in plain text; they are never
 *       a paying customer, whatever plan their row happens to carry
 *       ({@code loadSmallDemo} accepts any plan, commonly Pro).</li>
 *   <li><b>{@code TRIAL-}</b> — evaluation <em>while on the Trial plan</em>. A
 *       self-service trial is a real prospect: upgrading it is the whole point, and
 *       the client id keeps its prefix forever, so the prefix alone must not keep a
 *       paying church restricted. An unreadable subscription counts as evaluation,
 *       so uncertainty never widens their reach.</li>
 *   <li><b>anything else</b> — evaluation exactly when the subscription is Trial.
 *       Unknown stays unknown, and each caller decides what to do with that.</li>
 * </ul>
 *
 * <p>Static, and taking the already-resolved {@code trialState} rather than a
 * {@link MessagingPolicy} of its own: callers each hold one already, its answers are
 * cached for a minute, and keeping this class dependency-free means no new bean, no
 * constructor changes and no risk of a circular reference.
 */
public final class EvaluationTenant {

    private EvaluationTenant() {}

    /**
     * TRUE / FALSE / {@code null} when the subscription could not be read.
     *
     * @param clientId   the tenant
     * @param trialState {@link MessagingPolicy#trialState(String)} for that tenant —
     *                   {@code null} when it could not be determined
     */
    public static Boolean state(String clientId, Boolean trialState) {
        if (clientId == null || clientId.isBlank()) return Boolean.FALSE;
        if (TestDataService.isDemoTenant(clientId)) return Boolean.TRUE;
        if (TestDataService.isTrialTenant(clientId)) {
            // Only a subscription we can positively read as "not Trial" releases a
            // TRIAL- tenant; unknown keeps the restrictions.
            return Boolean.FALSE.equals(trialState) ? Boolean.FALSE : Boolean.TRUE;
        }
        return trialState;
    }

    /** True only when the tenant is definitely evaluating. Unknown → false (fail open). */
    public static boolean isEvaluation(String clientId, Boolean trialState) {
        return Boolean.TRUE.equals(state(clientId, trialState));
    }

    /** True unless the tenant is definitely NOT evaluating. Unknown → true (fail closed). */
    public static boolean isEvaluationOrUnknown(String clientId, Boolean trialState) {
        return !Boolean.FALSE.equals(state(clientId, trialState));
    }

    /**
     * True for a tenant this deployment generated — demo data or a self-service
     * trial signup — whatever plan it now carries.
     *
     * <p>Distinct from {@link #isEvaluation}: this is about where the tenant came
     * from (and so which admin tooling may touch it), not about what it may do
     * today. An upgraded {@code TRIAL-} church is still managed, but no longer an
     * evaluation account.
     */
    public static boolean isManaged(String clientId) {
        return TestDataService.isManagedTenant(clientId);
    }
}
