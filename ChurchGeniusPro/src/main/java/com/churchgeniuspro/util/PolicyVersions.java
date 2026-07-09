package com.churchgeniuspro.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single source of truth for the current version and dates of each ChurchGeniusPro
 * legal/compliance document. The HTML policy pages display these same values, and
 * each {@code PolicyAcceptance} row stores the version that was in effect, so that
 * acceptances remain auditable across future policy revisions.
 *
 * <p>To publish a revised policy: bump the {@code version} (and update the
 * effective / last-updated dates) here AND in the corresponding HTML page.
 */
public final class PolicyVersions {

    private PolicyVersions() {}

    /** Canonical policy keys (also used as URL slugs / acceptance policy_type). */
    public static final String TERMS         = "terms";
    public static final String PRIVACY       = "privacy";
    public static final String COOKIE        = "cookie";
    public static final String ACCEPTABLE    = "acceptable-use";
    public static final String REFUND        = "refund";

    /** Current version string for every policy in this release. */
    public static final String CURRENT_VERSION = "1.0";

    /** Human-readable effective / last-updated date shown across all documents. */
    public static final String EFFECTIVE_DATE  = "June 21, 2026";
    public static final String LAST_UPDATED    = "June 21, 2026";

    private static final Map<String, String> VERSIONS = new LinkedHashMap<>();
    static {
        VERSIONS.put(TERMS,      CURRENT_VERSION);
        VERSIONS.put(PRIVACY,    CURRENT_VERSION);
        VERSIONS.put(COOKIE,     CURRENT_VERSION);
        VERSIONS.put(ACCEPTABLE, CURRENT_VERSION);
        VERSIONS.put(REFUND,     CURRENT_VERSION);
    }

    /** Returns the current version for a policy key, defaulting to CURRENT_VERSION. */
    public static String versionFor(String policyType) {
        if (policyType == null) return CURRENT_VERSION;
        return VERSIONS.getOrDefault(policyType.trim().toLowerCase(), CURRENT_VERSION);
    }

    /** True if the policy key is one we recognize. */
    public static boolean isKnown(String policyType) {
        return policyType != null && VERSIONS.containsKey(policyType.trim().toLowerCase());
    }
}
