package com.churchgeniuspro.util;

import java.util.List;

/**
 * Catalog of subscription-gated features: stable key, display label, and the
 * URL prefixes (pages + APIs) enforced by {@code SubscriptionFeatureFilter}.
 *
 * <p>Matching is longest-prefix-wins so specific features (e.g. Scan Check
 * under {@code /api/expense/check-scan}) take precedence over broader ones
 * (Accounting under {@code /api/expense}). Public, token-based pages (event
 * registration, donate, kids check-in kiosk, webhooks) are intentionally NOT
 * listed — enforcement applies to logged-in sessions; public entry points are
 * governed by their own token validity.
 *
 * <p>Features with an empty path list are UI/limit-level controls (hidden by
 * the frontend and enforced in their specific controllers/services).
 */
public final class SubscriptionFeatureCatalog {

    private SubscriptionFeatureCatalog() {}

    /** One gated feature. */
    public record Feature(String key, String label, String group, List<String> pathPrefixes) {}

    /** Paths that must never be gated even though they share a gated prefix. */
    public static final List<String> EXCLUDED_PREFIXES = List.of(
            "/api/plaid/webhook",     // Plaid webhook (signature-verified, no session)
            "/api/prayer/public",     // public prayer wall endpoints
            // Guess It's two public entry points: the big-screen display (identified
            // by a public-link token) and participants joining with a 6-character
            // group code. Both are token-governed and reachable without a session, so
            // they follow the rule above. They are NOT ungoverned: each checks the
            // church's Activity Corner feature itself — the display through
            // PublicLinkResolver, the group endpoints in GuessItGroupController —
            // because "a group can only exist if an admin created it while the
            // feature was on" stops being true the moment a church changes plan.
            "/api/guess-it/public",
            "/api/guess-it/group",
            // The donate page and its endpoints: reachable with a link token and no
            // session, and governed by that token plus the controller's own
            // Online Giving checks, which produce a message a donor can act on
            // rather than this filter's generic refusal.
            "/api/public/donate"
    );

    public static final List<Feature> FEATURES = List.of(
        new Feature("memberPortal",   "Member Portal",          "Portals",    List.of()),
        new Feature("kidsPortal",     "Kids Portal",            "Portals",    List.of()),

        new Feature("accounting",     "Accounting",             "Accounting", List.of(
                "/income", "/expense", "/accountingReports", "/income-report", "/expense-report",
                "/transactions-report", "/tax-report", "/financial-report", "/fund", "/source",
                "/purpose", "/transactiontype", "/donation-review",
                "/api/income", "/api/expense", "/api/reports", "/api/sources", "/api/donations")),
        new Feature("bankImport",     "Bank Import",            "Accounting", List.of(
                "/bank-import")),
        new Feature("bankSync",       "Bank Sync",              "Accounting", List.of(
                "/bankSync", "/bankSyncVerify", "/plaidReview", "/api/plaid")),
        new Feature("pledges",        "Pledges",                "Accounting", List.of(
                "/pledges", "/api/pledges")),
        new Feature("payroll",        "Payroll",                "Accounting", List.of(
                "/payroll", "/api/payroll")),
        // Online giving: the public /donate/{token} page stays reachable for link
        // validity, but new giving pages can't be published and payment intents
        // are refused (both checked in their controllers), so no new donations
        // can be accepted. Historical donations are always preserved.
        // Online giving: the public /donate/{token} page stays reachable for link
        // validity (its endpoints are excluded above), but the church's own Stripe
        // configuration screen is gated, so a plan without the feature cannot put
        // keys in place, publish a giving page, or accept a payment.
        new Feature("onlineGiving",   "Online Giving",          "Accounting", List.of(
                "/stripeIntegration", "/api/stripe-settings")),

        new Feature("eventCheckin",   "Event Check-ins",        "Check-ins",  List.of(
                "/event-checkin-admin", "/api/event-checkin")),
        new Feature("kidsCheckin",    "Kids Check-ins",         "Check-ins",  List.of(
                "/pickup-dashboard", "/api/kids-ministry/checkins", "/api/kids-ministry/checkin-cid")),

        new Feature("volunteers",     "Volunteer Scheduling",   "Ministry",   List.of(
                "/event-volunteers", "/volunteers")),
        new Feature("worship",        "Worship Planning",       "Ministry",   List.of(
                "/worshipPlanning", "/memberWorship", "/sundaySchool", "/api/worship")),
        new Feature("eventRegistration", "Event Registration",  "Ministry",   List.of(
                "/event", "/events", "/event-details", "/eventcalendar", "/api/events")),
        new Feature("attendance",     "Attendance",             "Ministry",   List.of(
                "/attendance", "/api/attendance")),
        new Feature("groups",         "Groups",                 "Ministry",   List.of(
                "/groups", "/api/groups")),
        new Feature("kidsMinistry",   "Kids Ministry",          "Ministry",   List.of(
                "/ministry", "/kidsMinistry", "/kidsMinistryPage", "/api/kids-ministry")),
        new Feature("prayer",         "Prayer Ministry",        "Ministry",   List.of(
                "/prayerRequest", "/viewPrayerRequest", "/api/prayer")),

        new Feature("privatePages",   "Private Page Access",    "Admin",      List.of(
                "/private-access-settings")),
        new Feature("composeEmail",   "Compose Emails",         "Communication", List.of(
                "/notifyEmail")),
        new Feature("reminders",      "Reminders",              "Communication", List.of(
                "/reminders", "/eventReminders", "/autoReminders", "/oneReminders", "/api/reminders")),
        new Feature("certificates",   "Certificates",           "More",       List.of(
                "/certificates", "/api/certificates")),
        new Feature("publicScreens",  "Public Screens",         "More",       List.of(
                "/publicScreens", "/api/public-screens")),
        new Feature("followUps",      "Follow-ups",             "More",       List.of(
                "/followups", "/api/followups")),
        new Feature("songbook",       "Song Book Access",       "More",       List.of(
                "/songbook", "/admin/songbook-access", "/api/songbook")),
        // Activity Corner — Guess It. The /guessIt page is listed even though it is
        // also used as a public big-screen display: SubscriptionFeatureFilter passes
        // anonymous requests through, so the display keeps working while a signed-in
        // user on a plan without the feature is refused.
        new Feature("activityCorner", "Activity Corner",        "More",       List.of(
                "/guessIt", "/api/guess-it")),
        new Feature("ntag",           "Customize NTag",         "Admin",      List.of(
                "/ntagAccess", "/api/ntag", "/api/ntag-landing")),

        new Feature("aiSearch",       "AI Type (Search)",       "AI",         List.of(
                "/api/ai-search")),
        new Feature("aiVoice",        "AI Voice",               "AI",         List.of(
                "/api/voice")),
        new Feature("aiConverse",     "AI Converse",            "AI",         List.of(
                "/api/ai-assist")),
        new Feature("scanCheck",      "Scan Check",             "AI",         List.of(
                "/api/expense/check-scan", "/api/expense/check-parse"))
    );

    /**
     * Returns the feature key gating {@code path}, or {@code null} when the
     * path is not subscription-gated. Longest matching prefix wins.
     */
    public static String keyForPath(String path) {
        if (path == null) return null;
        for (String ex : EXCLUDED_PREFIXES) {
            if (matches(path, ex)) return null;
        }
        String bestKey = null;
        int bestLen = -1;
        for (Feature f : FEATURES) {
            for (String prefix : f.pathPrefixes()) {
                if (matches(path, prefix) && prefix.length() > bestLen) {
                    bestKey = f.key();
                    bestLen = prefix.length();
                }
            }
        }
        return bestKey;
    }

    private static boolean matches(String path, String prefix) {
        return path.equals(prefix)
                || path.startsWith(prefix + "/")
                || path.equals(prefix + ".html");
    }
}
