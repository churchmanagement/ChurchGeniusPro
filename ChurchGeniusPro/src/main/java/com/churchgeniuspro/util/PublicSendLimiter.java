package com.churchgeniuspro.util;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Rate limits for the PUBLIC endpoints that send something on an anonymous caller's
 * behalf — an SMS, a verification code, a confirmation e-mail, a notification to every
 * admin of a church (security audit P2).
 *
 * <p>{@link PublicFormGuard#checkRate} already throttles the Connect / Prayer / Trial
 * forms per IP. The endpoints covered here can be driven in a loop by anyone holding a
 * broadcast link, and one dimension is not enough for them:
 * <ul>
 *   <li><b>IP</b> — how many sends one network origin may trigger. Generous enough for
 *       a church's own Wi-Fi or a lobby kiosk (many people, one address), tight enough
 *       that a single host cannot relay in bulk.</li>
 *   <li><b>Target</b> — how many messages one phone number / e-mail / family may
 *       receive. This is what stops targeted bombing and the "every request re-issues
 *       the code" denial of the legitimate flow, and it holds even when the caller
 *       spreads requests across addresses or forges {@code X-Forwarded-For}.</li>
 *   <li><b>Tenant</b> — a daily ceiling per church (or per event link) on what a public
 *       form may send in that church's name: the bound on allowance exhaustion and
 *       sender-reputation damage no matter how distributed the caller is.</li>
 * </ul>
 * A request is allowed only when every rule of the policy has room; a refused request
 * is not recorded, so a blocked caller cannot extend its own block. Sliding windows,
 * in memory, single instance — the same terms as {@link PublicFormGuard}. Every
 * policy's numbers live here so they can be read (and tuned) in one place.
 */
@Component
public class PublicSendLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(PublicSendLimiter.class);

    public static final long MIN10 = 10 * 60_000L;
    public static final long HOUR  = 60 * 60_000L;
    public static final long DAY   = 24 * HOUR;

    public enum Scope { IP, TARGET, TENANT }

    /** At most {@code max} allowed sends per {@code windowMs} for one value of {@code scope}. */
    public record Rule(Scope scope, int max, long windowMs) {}

    /** The rules one endpoint applies. {@code form} keys the counters, so policies never share them. */
    public record Policy(String form, List<Rule> rules) {}

    private static Rule ip(int max, long window)     { return new Rule(Scope.IP, max, window); }
    private static Rule target(int max, long window) { return new Rule(Scope.TARGET, max, window); }
    private static Rule tenant(int max, long window) { return new Rule(Scope.TENANT, max, window); }

    // ── Policies ──────────────────────────────────────────────────────────

    /** Public SMS opt-in form: one text per submission, to a number the caller typed. */
    public static final Policy SMS_OPT_IN = new Policy("sms-opt-in", List.of(
            ip(10, MIN10), ip(30, HOUR), target(2, HOUR), target(5, DAY), tenant(200, DAY)));

    /** Public event RSVP (create and update): a confirmation e-mail to the address given. Keyed per event link. */
    public static final Policy EVENT_REGISTRATION = new Policy("event-registration", List.of(
            ip(30, MIN10), ip(120, HOUR), target(3, MIN10), target(12, DAY), tenant(1500, DAY)));

    /** Public membership form submission: an e-mail to every admin of the church. */
    public static final Policy MEMBERSHIP_SUBMIT = new Policy("membership-submit", List.of(
            ip(10, MIN10), ip(30, HOUR), tenant(200, DAY)));

    /** Membership-form lookup / resend: the verification code e-mailed to a family. Target = family. */
    public static final Policy MEMBERSHIP_OTP = new Policy("membership-otp", List.of(
            ip(5, MIN10), ip(15, HOUR), target(3, MIN10), target(10, DAY)));

    /** Member Signup lookup: a name+contact probe (no send, but an oracle) — network dimension only. */
    public static final Policy MEMBER_SIGNUP_LOOKUP = new Policy("member-signup-lookup", List.of(
            ip(20, MIN10), ip(60, HOUR)));

    /** Member Signup send-code: the code e-mailed to the member. Target = member. */
    public static final Policy MEMBER_SIGNUP_OTP = new Policy("member-signup-otp", List.of(
            ip(10, MIN10), ip(30, HOUR), target(3, MIN10), target(10, DAY), tenant(300, DAY)));

    /** Church registration initiate: the code e-mailed to the approved address. Target = the church. */
    public static final Policy CHURCH_REGISTRATION_OTP = new Policy("church-registration-otp", List.of(
            ip(5, MIN10), ip(15, HOUR), target(3, MIN10), target(10, DAY)));

    /** Invitation sign-up send-code: the code e-mailed to the invitee. Target = the invitation. */
    public static final Policy SIGNUP_OTP = new Policy("signup-otp", List.of(
            ip(5, MIN10), ip(15, HOUR), target(3, MIN10), target(10, DAY)));

    /** Trial/Demo test-email verification code (Admin Settings → Email Settings). */
    public static final Policy TRIAL_TEST_EMAIL_OTP = new Policy("trial-test-email-otp", List.of(
            ip(5, MIN10), ip(15, HOUR), target(3, MIN10), target(10, DAY), tenant(5, MIN10), tenant(20, DAY)));

    /** Website contact form: an e-mail to the vendor inbox. */
    public static final Policy WEB_CONTACT = new Policy("web-contact", List.of(
            ip(3, MIN10), ip(10, HOUR), target(3, MIN10), target(10, DAY)));

    // ── Policies for endpoints that do not send but expose or write (audit P3–P7) ──
    //
    // Sizing note: a congregation on the church Wi-Fi shares ONE public address, and
    // several of these pages are used by a whole room at once (a kiosk on a Sunday
    // morning, "give online now" during the offering, a youth group joining a game).
    // The per-origin numbers are therefore generous — they exist to stop scripted
    // bulk use by orders of magnitude, not to meter people.

    /** Kids check-in kiosk search: a household lookup by phone number. Tenant = the kiosk link. */
    public static final Policy KIDS_CHECKIN_SEARCH = new Policy("kids-checkin-search", List.of(
            ip(120, MIN10), ip(600, HOUR), tenant(5000, DAY)));

    /** Public donation page: a Stripe PaymentIntent per attempt (card-testing surface). Tenant = church. */
    public static final Policy DONATION_INTENT = new Policy("donation-intent", List.of(
            ip(60, MIN10), ip(200, HOUR), tenant(5000, DAY)));

    /** Public event page: the "my RSVP" lookup by e-mail/phone (an existence oracle). */
    public static final Policy EVENT_LOOKUP = new Policy("event-lookup", List.of(
            ip(60, MIN10), ip(200, HOUR)));

    /** Cookie-banner / pre-login policy acceptance rows. */
    public static final Policy POLICY_ACCEPTANCE = new Policy("policy-acceptance", List.of(
            ip(30, MIN10), ip(100, HOUR)));

    /** NTAG landing page analytics events (dropped, never refused, past the limit). Tenant = the landing link. */
    public static final Policy NTAG_TRACK = new Policy("ntag-track", List.of(
            ip(120, MIN10), ip(600, HOUR), tenant(5000, DAY)));

    /** Marketing-site visit rows (the page still renders when this refuses — only the row is skipped). */
    public static final Policy WEB_VISIT = new Policy("web-visit", List.of(
            ip(30, MIN10), ip(100, HOUR)));

    /** GuessIt group code checks (a 6-character code is guessable in bulk). */
    public static final Policy GUESSIT_VERIFY = new Policy("guessit-verify", List.of(
            ip(120, MIN10), ip(400, HOUR)));

    /** GuessIt group joins: one participant row each. Tenant = the group. */
    public static final Policy GUESSIT_JOIN = new Policy("guessit-join", List.of(
            ip(120, MIN10), ip(400, HOUR), tenant(2000, DAY)));

    // ── Messages ──────────────────────────────────────────────────────────

    public static final String IP_MSG =
            "Too many requests from your network. Please wait a while and try again.";
    public static final String TARGET_MSG =
            "A message was sent to that contact recently. Please wait before requesting another.";
    public static final String TENANT_MSG =
            "This form has reached its daily limit. Please contact the church office directly.";

    // ── State ─────────────────────────────────────────────────────────────

    /** form|scope|value → timestamps (ms) of allowed sends. */
    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    /**
     * Emergency switch: {@code public.send-limits.enabled=false} turns every policy
     * into a no-op (logged once per refusal-that-would-have-been at DEBUG).
     */
    @Value("${public.send-limits.enabled:true}")
    private boolean enabled = true;

    /** Same deployment decision as the login protection: trust X-Forwarded-For or not. */
    @Value("${security.login-protection.trust-forwarded-headers:true}")
    private boolean trustForwardedHeaders = true;

    private LongSupplier clock = System::currentTimeMillis;

    /** Test seam — the clock the windows are measured against. */
    public void setClock(LongSupplier clock) { this.clock = clock != null ? clock : System::currentTimeMillis; }

    /** Test seam — the emergency switch, without a Spring context. */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** Test seam — header trust, without a Spring context. */
    public void setTrustForwardedHeaders(boolean trust) { this.trustForwardedHeaders = trust; }

    // ── API ───────────────────────────────────────────────────────────────

    /** The caller's address under the application's forwarded-header policy. */
    public String clientIp(HttpServletRequest request) {
        return ClientIpResolver.resolve(request, trustForwardedHeaders);
    }

    /**
     * Checks {@code policy} for this caller/target/tenant and records the send when it
     * is allowed. Returns {@code null} when allowed, else the message to show. A
     * {@code null} target or tenant simply skips the rules of that scope.
     */
    public synchronized String check(Policy policy, String ip, String target, String tenant) {
        if (policy == null) return null;
        long now = clock.getAsLong();
        Map<Scope, String> values = Map.of(
                Scope.IP,     norm(ip),
                Scope.TARGET, norm(target),
                Scope.TENANT, norm(tenant));

        // Every rule must have room before anything is recorded.
        for (Rule r : policy.rules()) {
            String v = values.get(r.scope());
            if (v.isEmpty()) continue;
            Deque<Long> q = hits.get(key(policy, r.scope(), v));
            if (q == null) continue;
            long cutoff = now - r.windowMs();
            long inWindow = q.stream().filter(t -> t > cutoff).count();
            if (inWindow >= r.max()) {
                if (!enabled) {
                    LOG.debug("[PublicSendLimiter] disabled — would have refused {} for {} {}", policy.form(), r.scope(), v);
                    break;
                }
                LOG.info("[PublicSendLimiter] refused {} — {} limit ({} per {} min) reached", policy.form(), r.scope(),
                        r.max(), r.windowMs() / 60_000L);
                return switch (r.scope()) {
                    case IP     -> IP_MSG;
                    case TARGET -> TARGET_MSG;
                    case TENANT -> TENANT_MSG;
                };
            }
        }

        // Record the send under each scope, pruning what no rule of that scope can still see.
        for (Scope s : Scope.values()) {
            String v = values.get(s);
            if (v.isEmpty()) continue;
            long longest = policy.rules().stream().filter(r -> r.scope() == s).mapToLong(Rule::windowMs).max().orElse(0L);
            if (longest == 0L) continue;   // this policy has no rule for the scope
            Deque<Long> q = hits.computeIfAbsent(key(policy, s, v), k -> new ArrayDeque<>());
            while (!q.isEmpty() && now - q.peekFirst() > longest) q.pollFirst();
            q.addLast(now);
        }
        if (hits.size() > 50_000) hits.clear();   // safety valve
        return null;
    }

    /** Convenience: {@link #check} with the address taken from the request. */
    public String check(Policy policy, HttpServletRequest request, String target, String tenant) {
        return check(policy, clientIp(request), target, tenant);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static String key(Policy p, Scope s, String v) {
        return p.form() + "|" + s + "|" + v;
    }

    /** Case- and whitespace-insensitive key; phone numbers by their digits. */
    static String norm(String s) {
        if (s == null) return "";
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return "";
        if (t.indexOf('@') < 0) {
            String digits = t.replaceAll("\\D", "");
            if (digits.length() >= 7 && digits.length() >= t.length() / 2) return digits;   // looks like a phone number
        }
        return t.length() > 200 ? t.substring(0, 200) : t;
    }
}
