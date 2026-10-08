package com.churchgeniuspro.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which pages a church may publish, and whether THIS church may publish THEM.
 *
 * <p>Extracted from {@code PublicScreensController} so every place that mints a
 * public link — the Public Screens page, the NTAG landing buttons, reminder
 * emails, the kids check-in QR — applies exactly the same rule. A link that
 * exists only because some code path forgot to ask is how trial tenants ended up
 * with live Guess It boards.
 */
@Component
public class PublicPagePolicy {

    private static final Logger log = LoggerFactory.getLogger(PublicPagePolicy.class);

    // ── Page identities ────────────────────────────────────────────────────
    public static final String MEMBERSHIP_FORM_URL    = "/membershipForm";
    public static final String DONATION_PAGE_URL      = "/donate";
    public static final String MEMBER_SIGNUP_URL      = "/memberSignup";
    public static final String SMS_OPT_IN_URL         = "/smsOptIn";
    public static final String PUBLIC_PRAYER_URL      = "/viewPrayerRequest";   // withdrawn
    public static final String PUBLIC_CALENDAR_URL    = "/viewEventCalendar";
    public static final String GUESS_IT_URL           = "/guessIt";
    public static final String KIDS_CHECKIN_URL       = "/kidsCheckin";
    public static final String CONNECT_URL            = "/connect";
    public static final String PUBLIC_PRAYER_FORM_URL = "/publicPrayer";
    /** Midwest Region Meet RSVP — retired 2026-09-28; kept only so existing links are refused. */
    public static final String MID_REG_MEET_RSVP_URL  = "/midRegMeetRsvp";
    /** The tap-to-connect landing page written to NFC tags. Not offered in the dropdown. */
    public static final String NTAG_LANDING_URL       = "/ntagLanding";
    /** Public upcoming-events list linked from the NTAG landing page. */
    public static final String UPCOMING_EVENTS_URL    = "/upcomingEvents";
    /** Church-scoped pre-login link that lets a kiosk reach the network-gated login page. */
    public static final String PRIVATE_ACCESS_URL     = "/private-access";

    /**
     * Pages whose public content has been merged into one visitor experience: the
     * Upcoming Events list and the Event Calendar grid both live at
     * {@link #UPCOMING_EVENTS_URL} now, and {@link #PUBLIC_CALENDAR_URL} forwards
     * there. A token minted for any one of these (via Public Screens, or the NTAG
     * landing page's auto-generated button) is accepted by both — see
     * {@link PublicLinkResolver#resolveClientIdAnyOf}. This only widens which
     * token unlocks the merged page; each candidate still passes every resolve()
     * check (revocation, expiry, subscription policy) for its own page identity.
     */
    public static final List<String> UPCOMING_EVENTS_FAMILY =
            List.of(UPCOMING_EVENTS_URL, PUBLIC_CALENDAR_URL, NTAG_LANDING_URL);

    /** What the Public Screens dropdown offers. Financial/admin pages are never here. */
    public static final List<Map<String, String>> AVAILABLE_PAGES = List.of(
            page("Event Calendar",            PUBLIC_CALENDAR_URL),
            page("Membership Form",           MEMBERSHIP_FORM_URL),
            page("Donation Page",             DONATION_PAGE_URL),
            page("Member Signup",             MEMBER_SIGNUP_URL),
            page("SMS Opt-In Form",           SMS_OPT_IN_URL),
            page("Guess It",                  GUESS_IT_URL),
            page("Kids Check-In",             KIDS_CHECKIN_URL),
            page("Connect With Us",           CONNECT_URL),
            page("Prayer Request (Public)",   PUBLIC_PRAYER_FORM_URL)
    );

    private static final Set<String> EXCLUDED_PAGES = Set.of("/login", "/event-register");

    /**
     * Withdrawn for every account: generation is refused and existing links stop
     * resolving. {@code /viewPrayerRequest} (the internal list) is withdrawn while
     * {@code /publicPrayer} (the visitor form) is kept — different pages.
     */
    private static final Set<String> WITHDRAWN_PAGES = Set.of(
            "/event", "/groups", "/meetings", PUBLIC_PRAYER_URL, "/certificates",
            // Midwest Region Meet was retired: no new links, and existing RSVP links stop
            // resolving. The links' rows and the meet's data are kept, not deleted.
            MID_REG_MEET_RSVP_URL);

    /** Not offered to Trial or Demo tenants — real-event / live-activity pages. */
    private static final Set<String> TRIAL_DEMO_RESTRICTED_PAGES = Set.of(
            GUESS_IT_URL);

    /**
     * Every page a public link may point at. Anything else is refused.
     *
     * <p>An allow-list rather than a block-list, because {@code pageUrl} is caller
     * input that ends up in a {@code redirect:} on this application's own domain and
     * in a link a church hands to the public. Without it, {@code "//evil.example"}
     * passed every check and {@code /pub/{token}} emitted a protocol-relative
     * redirect off-site, and {@code "/midRegMeetRsvp?x"} slipped past the
     * trial/demo rule below by not being string-equal to the page it names.
     *
     * <p>Includes the pages that are never offered in the dropdown but are minted by
     * other code paths (the NFC landing page, its events list, the kiosk login link).
     */
    private static final Set<String> KNOWN_PAGES = Set.of(
            PUBLIC_CALENDAR_URL, MEMBERSHIP_FORM_URL, DONATION_PAGE_URL, MEMBER_SIGNUP_URL,
            SMS_OPT_IN_URL, GUESS_IT_URL, KIDS_CHECKIN_URL,
            CONNECT_URL, PUBLIC_PRAYER_FORM_URL, PUBLIC_PRAYER_URL,
            NTAG_LANDING_URL, UPCOMING_EVENTS_URL, PRIVATE_ACCESS_URL);

    private final SubscriptionService subscriptionService;
    private final MessagingPolicy messagingPolicy;

    public PublicPagePolicy(SubscriptionService subscriptionService, MessagingPolicy messagingPolicy) {
        this.subscriptionService = subscriptionService;
        this.messagingPolicy = messagingPolicy;
    }

    /** Null when the church may publish the page; otherwise the reason to show. */
    public String denialReason(String pageUrl, String appClientId) {
        if (pageUrl == null || pageUrl.isBlank()) return "Page URL is required.";
        if (EXCLUDED_PAGES.stream().anyMatch(pageUrl::startsWith)) {
            return "This page cannot be made public.";
        }
        if (WITHDRAWN_PAGES.stream().anyMatch(pageUrl::startsWith)) {
            return "This page is no longer available for public links.";
        }
        if (!KNOWN_PAGES.contains(pageUrl)) {
            // Deliberately after the two messages above, so a page that was withdrawn
            // still explains itself rather than reading as a typo.
            log.warn("Public link refused — '{}' is not a page that may be published (tenant={})",
                     pageUrl, appClientId);
            return "This page cannot be made public.";
        }
        if (TRIAL_DEMO_RESTRICTED_PAGES.contains(pageUrl) && isTrialOrDemoTenant(appClientId)) {
            return "This option is not available for trial or demo accounts.";
        }
        String featureKey = featureKeyForPage(pageUrl);
        if (featureKey != null && appClientId != null
                && !subscriptionService.isFeatureEnabled(appClientId, featureKey)) {
            return "This option is not included in your church's subscription plan. "
                 + "Please contact your administrator about upgrading.";
        }
        return null;
    }

    public boolean mayPublish(String pageUrl, String appClientId) {
        return denialReason(pageUrl, appClientId) == null;
    }

    /** Subscription feature key that gates a page, or null. Kept in step with SubscriptionFeatureCatalog. */
    static String featureKeyForPage(String pageUrl) {
        if (pageUrl == null) return null;
        if (pageUrl.startsWith(MEMBER_SIGNUP_URL))  return "memberPortal";
        if (pageUrl.startsWith(KIDS_CHECKIN_URL))   return "kidsCheckin";
        if (pageUrl.startsWith(DONATION_PAGE_URL))  return "onlineGiving";
        if (pageUrl.startsWith(GUESS_IT_URL))       return "activityCorner";
        return null;
    }

    private boolean isTrialOrDemoTenant(String appClientId) {
        if (appClientId == null || appClientId.isBlank()) return false;
        Boolean trialState;
        try {
            trialState = messagingPolicy.trialState(appClientId);
        } catch (Exception e) {
            log.warn("Public pages: subscription lookup failed for {} — treating as restricted. {}",
                     appClientId, e.getMessage());
            trialState = null;
        }
        // Fail CLOSED, unlike the feature overlay: publishing a page to the public
        // internet is not something to do on a guess.
        return EvaluationTenant.isEvaluationOrUnknown(appClientId, trialState);
    }

    private static Map<String, String> page(String label, String url) {
        Map<String, String> m = new java.util.LinkedHashMap<>();   // keeps label,url order in JSON
        m.put("label", label);
        m.put("url",   url);
        return java.util.Collections.unmodifiableMap(m);
    }
}
