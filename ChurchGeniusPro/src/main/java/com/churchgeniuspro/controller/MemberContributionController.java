package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.Donation;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.StripeSettings;
import com.churchgeniuspro.hibernate.SubSource;
import com.churchgeniuspro.repository.DonationRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.StripeSettingsRepository;
import com.churchgeniuspro.repository.SubSourceRepository;
import com.churchgeniuspro.service.ChurchStripeGateway;
import com.churchgeniuspro.service.DonationIncomePostingService;
import com.churchgeniuspro.service.SubscriptionService;
import com.churchgeniuspro.util.PublicSendLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Member Portal → My Profile → Contributions → Give / Contribute (Phase D).
 *
 * <p>A contribution is a real Stripe payment through the church's own connected
 * account, made with exactly the integration the public donation page uses
 * ({@link ChurchStripeGateway}): PaymentIntent → Stripe.js confirm → server-side
 * verification → {@code donation} row → posted to Income. Nothing is recorded
 * until Stripe reports the intent {@code succeeded}; a failed or cancelled payment
 * records nothing; the save is idempotent on the PaymentIntent id, so a retry or a
 * refresh cannot create a duplicate.
 *
 * <p>What makes it a member contribution rather than an anonymous donation: the
 * intent carries the member and purpose as Stripe metadata, and the save step
 * accepts an intent only when that metadata names the signed-in member of the
 * signed-in church — one member cannot record another's payment, and a purpose
 * must belong to the member's church.
 *
 * <p>Church-side only: this never touches the platform billing integration.
 */
@RestController
public class MemberContributionController {

    private static final Logger log = LoggerFactory.getLogger(MemberContributionController.class);
    static final String NOT_CONFIGURED_MSG = "Online giving is not set up for your church yet. Please contact the church office.";
    static final String METADATA_PURPOSE = "cgp_member_contribution";

    private final DonationRepository donationRepo;
    private final StripeSettingsRepository stripeRepo;
    private final FamilyMemberRepository memberRepo;
    private final SubSourceRepository subSourceRepo;
    private final SubscriptionService subscriptionService;
    private final DonationIncomePostingService donationIncomePoster;
    private final ChurchStripeGateway stripe;
    private final PublicSendLimiter sendLimiter;

    /** The donation page's thank-you email, reused; optional (unit tests). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private DonationController donationController;
    public void setDonationController(DonationController c) { this.donationController = c; }

    public MemberContributionController(DonationRepository donationRepo,
                                        StripeSettingsRepository stripeRepo,
                                        FamilyMemberRepository memberRepo,
                                        SubSourceRepository subSourceRepo,
                                        SubscriptionService subscriptionService,
                                        DonationIncomePostingService donationIncomePoster,
                                        ChurchStripeGateway stripe,
                                        PublicSendLimiter sendLimiter) {
        this.donationRepo = donationRepo; this.stripeRepo = stripeRepo; this.memberRepo = memberRepo;
        this.subSourceRepo = subSourceRepo; this.subscriptionService = subscriptionService;
        this.donationIncomePoster = donationIncomePoster; this.stripe = stripe; this.sendLimiter = sendLimiter;
    }

    /** The signed-in member of the signed-in church, or null. */
    private record Who(Integer memberId, String clientId, FamilyMember member) {}

    private Who who(HttpServletRequest request) {
        HttpSession s = request.getSession(false);
        if (s == null) return null;
        boolean isMember = "Member".equals(s.getAttribute("role")) && s.getAttribute("memberId") != null;
        if (!isMember) return null;
        Integer memberId = s.getAttribute("memberId") instanceof Number n ? n.intValue() : null;
        Object cid = s.getAttribute("appClientId");
        String clientId = cid instanceof String str && !str.isBlank() ? str : null;
        if (memberId == null || clientId == null) return null;
        FamilyMember fm = memberRepo.findByIdAndTenant(memberId, clientId).orElse(null);
        return fm == null ? null : new Who(memberId, clientId, fm);
    }

    /** Whether the church can take a card payment, and the key Stripe.js needs. */
    @GetMapping("/api/member/give/config")
    public ResponseEntity<?> config(HttpServletRequest request) {
        Who w = who(request);
        if (w == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        StripeSettings settings = stripeRepo.findByClientId(w.clientId()).orElse(null);
        boolean enabled = stripe.configured(settings) && subscriptionService.isFeatureEnabled(w.clientId(), "onlineGiving");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("onlineGivingEnabled", enabled);
        if (enabled) out.put("publishableKey", settings.getPublishableKey());
        else out.put("message", NOT_CONFIGURED_MSG);
        return ResponseEntity.ok(out);
    }

    /** Body: {@code { amount, subSourceId, note? }} → {@code { clientSecret, paymentIntentId }}. */
    @PostMapping("/api/member/give/intent")
    public ResponseEntity<?> createIntent(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        Who w = who(request);
        if (w == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        if (body == null) return ResponseEntity.badRequest().body(Map.of("error", "Missing request."));
        String clientId = w.clientId();

        // Same bound as the donation page: PaymentIntents are the card-testing surface.
        String limited = sendLimiter.check(PublicSendLimiter.DONATION_INTENT, request, null, clientId);
        if (limited != null) return ResponseEntity.status(429).body(Map.of("error", limited));

        StripeSettings settings = stripeRepo.findByClientId(clientId).orElse(null);
        if (!stripe.configured(settings)) return ResponseEntity.badRequest().body(Map.of("error", NOT_CONFIGURED_MSG));
        if (!subscriptionService.isFeatureEnabled(clientId, "onlineGiving")) {
            return ResponseEntity.status(403).body(Map.of("error", NOT_CONFIGURED_MSG));
        }
        if (!subscriptionService.canAcceptOnlineGiving(clientId)) {
            return ResponseEntity.status(403).body(Map.of("error",
                    "Your church has reached its monthly online giving limit for its current subscription plan. Please contact the church office."));
        }

        Integer subSourceId = body.get("subSourceId") instanceof Number n ? n.intValue() : null;
        if (subSourceId == null) return ResponseEntity.badRequest().body(Map.of("error", "Purpose is required."));
        SubSource purpose = subSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(subSourceId, clientId).orElse(null);
        if (purpose == null) return ResponseEntity.badRequest().body(Map.of("error", "Invalid purpose."));

        BigDecimal amount;
        try {
            amount = new BigDecimal(String.valueOf(body.get("amount"))).setScale(2, java.math.RoundingMode.HALF_UP);
            if (amount.compareTo(BigDecimal.valueOf(0.50)) < 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "Minimum contribution amount is $0.50."));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "A valid amount greater than zero is required."));
        }
        long cents = amount.movePointRight(2).longValueExact();

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("purpose",       METADATA_PURPOSE);
        metadata.put("client_id",     clientId);
        metadata.put("member_id",     String.valueOf(w.memberId()));
        metadata.put("sub_source_id", String.valueOf(subSourceId));
        try {
            Map<String, Object> pi = stripe.createIntent(settings.getSecretKey(), cents, "usd", metadata);
            if (pi.containsKey("error")) return ResponseEntity.badRequest().body(Map.of("error", ChurchStripeGateway.errorMessage(pi)));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("clientSecret",    pi.get("client_secret"));
            out.put("paymentIntentId", pi.get("id"));
            out.put("amount",          amount);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            log.warn("Member contribution: could not create PaymentIntent for client {} — {}", clientId, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Could not initiate payment. Please try again."));
        }
    }

    /** Body: {@code { paymentIntentId, note? }}. Verifies with Stripe, then records the contribution once. */
    @PostMapping("/api/member/give/save")
    public ResponseEntity<?> save(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        Who w = who(request);
        if (w == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        if (body == null) return ResponseEntity.badRequest().body(Map.of("error", "Missing request."));
        String clientId = w.clientId();

        StripeSettings settings = stripeRepo.findByClientId(clientId).orElse(null);
        if (!stripe.configured(settings)) return ResponseEntity.badRequest().body(Map.of("error", NOT_CONFIGURED_MSG));
        if (!subscriptionService.isFeatureEnabled(clientId, "onlineGiving")) {
            return ResponseEntity.status(403).body(Map.of("error", NOT_CONFIGURED_MSG));
        }

        String piId = str(body.get("paymentIntentId"));
        if (piId == null || !ChurchStripeGateway.PAYMENT_INTENT_ID.matcher(piId).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid paymentIntentId."));
        }
        // Idempotent: a refresh or retry after success records nothing more.
        Donation existing = donationRepo.findByStripePaymentIntentId(piId).orElse(null);
        if (existing != null) {
            if (!clientId.equals(existing.getClientId())) return ResponseEntity.status(404).body(Map.of("error", "Payment not found."));
            return ResponseEntity.ok(Map.of("success", true, "message", "Contribution already recorded.", "donationId", existing.getId()));
        }

        Map<String, Object> pi;
        try {
            pi = stripe.retrieveIntent(settings.getSecretKey(), piId);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Could not verify payment. Please contact the church office."));
        }
        if (pi.containsKey("error")) return ResponseEntity.badRequest().body(Map.of("error", ChurchStripeGateway.errorMessage(pi)));

        // The intent must be this member's, for this church, made through this flow.
        Map<?, ?> md = pi.get("metadata") instanceof Map<?, ?> m ? m : Map.of();
        if (!METADATA_PURPOSE.equals(str(md.get("purpose")))
                || !clientId.equals(str(md.get("client_id")))
                || !String.valueOf(w.memberId()).equals(str(md.get("member_id")))) {
            log.warn("Member contribution: PaymentIntent {} does not belong to member {} of {} — refused", piId, w.memberId(), clientId);
            return ResponseEntity.status(404).body(Map.of("error", "Payment not found."));
        }
        String status = str(pi.get("status"));
        if (!"succeeded".equals(status)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Payment has not completed (status: " + status + "). Nothing was recorded."));
        }
        String currency = str(pi.getOrDefault("currency", "usd"));
        if (!"usd".equals(currency)) {
            return ResponseEntity.badRequest().body(Map.of("error", "This payment was not made in US dollars and could not be recorded."));
        }
        // Amount and purpose come from Stripe (authoritative), never from the browser.
        BigDecimal amount = BigDecimal.valueOf(((Number) pi.getOrDefault("amount", 0)).longValue()).movePointLeft(2);
        Integer subSourceId = null;
        try { subSourceId = Integer.valueOf(str(md.get("sub_source_id"))); } catch (Exception ignored) { /* posted to Online Giving */ }
        if (subSourceId != null && subSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(subSourceId, clientId).isEmpty()) subSourceId = null;
        ChurchStripeGateway.ChargeInfo charge = ChurchStripeGateway.chargeInfo(pi);

        FamilyMember fm = w.member();
        Donation d = new Donation();
        d.setClientId(clientId);
        d.setSource(Donation.SOURCE_MEMBER_PORTAL);
        d.setMemberId(w.memberId());
        d.setSubSourceId(subSourceId);
        d.setFirstName(clip(fm.getFirstName(), 100));
        d.setLastName(clip(fm.getLastName(), 100));
        d.setEmail(clip(fm.getEmail(), 200));
        d.setPhone(clip(fm.getPhone(), 50));
        d.setNote(clip(str(body.get("note")), 1000));
        d.setPaymentMethod(charge.paymentMethod());
        d.setAmount(amount);
        d.setIntendedAmount(amount);
        d.setFeeCovered(BigDecimal.ZERO);
        d.setCurrency("USD");
        d.setStripePaymentIntentId(piId);
        d.setStripeChargeId(charge.chargeId());
        d.setStatus(status);
        try {
            donationRepo.save(d);
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            // Two saves of the same intent at once: the unique PaymentIntent id keeps one.
            return ResponseEntity.ok(Map.of("success", true, "message", "Contribution already recorded."));
        }
        subscriptionService.recordOnlineGiving(clientId);
        donationIncomePoster.postToIncome(d);        // best-effort, never throws
        try { if (donationController != null) donationController.sendThankyou(d, clientId); } catch (Exception ignored) {}
        log.info("Member contribution recorded: client={} member={} amount={} intent={}", clientId, w.memberId(), amount, piId);
        return ResponseEntity.ok(Map.of("success", true, "donationId", d.getId(), "amount", amount, "paymentMethod", charge.paymentMethod()));
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }
    private static String clip(String s, int max) { return s == null ? null : (s.length() <= max ? s : s.substring(0, max)); }
}
