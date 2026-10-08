package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.ChurchLogo;
import com.churchgeniuspro.hibernate.Donation;
import com.churchgeniuspro.hibernate.PublicScreenLink;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.StripeSettings;
import com.churchgeniuspro.repository.ChurchLogoRepository;
import com.churchgeniuspro.repository.DonationRepository;
import com.churchgeniuspro.repository.PublicScreenLinkRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.StripeSettingsRepository;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.util.PublicSendLimiter;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Handles the public donation page (Stripe payment) and the Donation page.
 *
 * <h3>Public routes (no session required — token validated via PublicScreenLink)</h3>
 * <ul>
 *   <li>{@code GET  /donate/{token}}               → serves donation.html</li>
 *   <li>{@code GET  /api/public/donate/config}      → publishable key for Stripe.js</li>
 *   <li>{@code POST /api/public/donate/intent}      → creates Stripe PaymentIntent</li>
 *   <li>{@code POST /api/public/donate/save}        → verifies &amp; persists donation</li>
 * </ul>
 *
 * <h3>Authenticated routes (Accountant / SuperAdmin only)</h3>
 * <ul>
 *   <li>{@code GET /donation-review}   → serves donationReview.html</li>
 *   <li>{@code GET /api/donations}     → {@code {rows: [...], totals: [{currency,total,count}, ...]}}
 *       for the org — {@code totals} is the server-computed, exact-BigDecimal figure
 *       the review page's headline total reconciles against (financial audit M11)</li>
 * </ul>
 */
@Controller
public class DonationController {

    private static final Logger log = LoggerFactory.getLogger(DonationController.class);

    /** In-app notification for staff with the right permissions. Optional: absent in unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.service.PublicSubmissionNotificationService submissionNotifications;

    /** Test seam. */
    public void setSubmissionNotifications(com.churchgeniuspro.service.PublicSubmissionNotificationService s) {
        this.submissionNotifications = s;
    }



    /**
     * Donor-facing message used when the church has not finished Stripe setup.
     * Shown only when a donation is actually submitted — the donation page itself
     * still renders normally so visitors see the church's real giving page.
     */
    static final String NOT_CONFIGURED_MSG =
            "Online giving is not configured for this church account.";



    private final DonationRepository         donationRepo;
    private final StripeSettingsRepository   stripeRepo;
    private final PublicScreenLinkRepository linkRepo;
    private final ServiceClientRepository    clientRepo;
    private final ChurchLogoRepository       logoRepo;
    private final EmailService               emailService;
    private final com.churchgeniuspro.service.SubscriptionService subscriptionService;
    private final com.churchgeniuspro.service.DonationIncomePostingService donationIncomePoster;
    private final PublicSendLimiter          sendLimiter;
    /**
     * Phase D: the Stripe REST calls below are the church-side integration, shared
     * with the Member Portal's Give / Contribute. Optional so the constructor used
     * by tests is unchanged; without Spring a default gateway is used.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.service.ChurchStripeGateway stripeGateway;
    public void setStripeGateway(com.churchgeniuspro.service.ChurchStripeGateway g) { this.stripeGateway = g; }
    private com.churchgeniuspro.service.ChurchStripeGateway gateway() {
        if (stripeGateway == null) stripeGateway = new com.churchgeniuspro.service.ChurchStripeGateway();
        return stripeGateway;
    }

    /** A Stripe PaymentIntent id, and nothing that could steer the URL it is put into. */
    static final java.util.regex.Pattern PAYMENT_INTENT_ID = java.util.regex.Pattern.compile("^pi_[A-Za-z0-9]{1,64}$");

    public DonationController(DonationRepository donationRepo,
                               StripeSettingsRepository stripeRepo,
                               PublicScreenLinkRepository linkRepo,
                               ServiceClientRepository clientRepo,
                               ChurchLogoRepository logoRepo,
                               EmailService emailService,
                               com.churchgeniuspro.service.SubscriptionService subscriptionService,
                               com.churchgeniuspro.service.DonationIncomePostingService donationIncomePoster,
                               PublicSendLimiter sendLimiter) {
        this.sendLimiter  = sendLimiter;
        this.donationRepo = donationRepo;
        this.stripeRepo   = stripeRepo;
        this.linkRepo     = linkRepo;
        this.clientRepo   = clientRepo;
        this.logoRepo     = logoRepo;
        this.emailService = emailService;
        this.subscriptionService  = subscriptionService;
        this.donationIncomePoster = donationIncomePoster;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Public page
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Serves the public donation page. The path token is validated against
     * PublicScreenLink (same mechanism as other public screens).
     */
    @GetMapping("/donate/{token}")
    public String donatePage(@PathVariable String token) {
        Optional<PublicScreenLink> opt = linkRepo.findByToken(token);
        if (opt.isEmpty()) return "redirect:/login";
        PublicScreenLink link = opt.get();
        if (link.isRevoked()) return "redirect:/login";
        if (link.getExpirationDate() != null && link.getExpirationDate().isBefore(LocalDate.now())) {
            return "redirect:/login";
        }
        return "forward:/donation.html";
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Public API — config
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Returns the Stripe publishable key needed by Stripe.js on the donation page.
     * The secret key is never sent to the client.
     */
    @ResponseBody
    @GetMapping("/api/public/donate/config")
    public ResponseEntity<?> config(@RequestParam("cid") String token) {
        String clientId = resolveClientId(token);
        if (clientId == null || clientId.isBlank()) {
            // A null clientId means the link is revoked/expired/unknown; a blank one
            // means the link was generated without a valid session (no appClientId).
            log.warn("[Donate] config: link did not resolve to a valid organization "
                    + "(clientId={}, tokenPrefix={}). The donation link may be revoked, expired, "
                    + "or was generated while not properly signed in — regenerate it from Public Screens.",
                    clientId == null ? "null" : "<blank>", tokenPrefix(token));
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid or expired donation link."));
        }
        StripeSettings settings = stripeRepo.findByClientId(clientId).orElse(null);
        boolean configured = onlineGivingConfigured(settings);
        if (!configured) {
            // The link is valid, so the page still renders — the donor sees the normal
            // giving page and is only told at submit time (see createIntent). Distinguish
            // the real causes in the server log so this isn't opaque:
            //   • settingsPresent=false → no Stripe row for THIS org (configure Stripe,
            //     or this donate link belongs to a different org than the one you set up).
            //   • settingsPresent=true → row exists but a key is missing.
            log.warn("[Donate] config: online giving not configured — serving the page in "
                    + "preview mode. resolvedClientId={}, stripeSettingsPresent={}, "
                    + "publishableKeyBlank={}, secretKeyBlank={}, totalStripeSettingsRows={}. "
                    + "Set the Publishable + Secret keys at /stripeIntegration for this organization.",
                    clientId, settings != null,
                    settings == null || isBlank(settings.getPublishableKey()),
                    settings == null || isBlank(settings.getSecretKey()), stripeRepo.count());
        } else {
            log.info("[Donate] config OK for clientId={}.", clientId);
        }
        String churchName = clientRepo.findByClientId(clientId)
                .map(ServiceClient::getChurchName)
                .filter(n -> n != null && !n.isBlank())
                .orElse(null);

        boolean hasLogo = logoRepo.findByClientId(clientId)
                .map(l -> l.getLogoData() != null && l.getLogoData().length > 0)
                .orElse(false);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("onlineGivingEnabled", configured);
        if (configured) {
            out.put("publishableKey", settings.getPublishableKey());
        } else {
            // No key is sent when giving is unconfigured — the page renders a read-only
            // placeholder where the card field would be and blocks on submit instead.
            out.put("message", NOT_CONFIGURED_MSG);
        }
        if (churchName != null) out.put("churchName", churchName);
        if (hasLogo) out.put("logoUrl", "/api/public/donate/logo?cid=" + token);
        return ResponseEntity.ok(out);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Public API — church logo
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Serves the church logo image for the donation page (no session required).
     * Returns 404 when no logo exists for the organization.
     */
    @GetMapping("/api/public/donate/logo")
    public ResponseEntity<byte[]> publicLogo(@RequestParam("cid") String token) {
        String clientId = resolveClientId(token);
        if (clientId == null) return ResponseEntity.notFound().build();

        ChurchLogo logo = logoRepo.findByClientId(clientId).orElse(null);
        if (logo == null || logo.getLogoData() == null || logo.getLogoData().length == 0) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        logo.getContentType() != null ? logo.getContentType() : "image/png"))
                .body(logo.getLogoData());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Public API — create PaymentIntent
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Creates a Stripe PaymentIntent and returns the {@code clientSecret} that
     * Stripe.js needs to confirm the payment on the browser side.
     *
     * <p>Expected request body: {@code { "amount": 25.00, "currency": "usd" }}
     */
    @ResponseBody
    @PostMapping("/api/public/donate/intent")
    public ResponseEntity<?> createIntent(@RequestParam("cid") String token,
                                           @RequestBody Map<String, Object> body,
                                           HttpServletRequest request) {
        String clientId = resolveClientId(token);
        if (clientId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid or expired donation link."));
        }
        // Every call creates a PaymentIntent against the church's Stripe account — the
        // classic card-testing surface. Bounded per network origin and per church per
        // day before Stripe is touched (security audit P4).
        String limited = sendLimiter.check(PublicSendLimiter.DONATION_INTENT, request, null, clientId);
        if (limited != null) {
            return ResponseEntity.status(429).body(Map.of("error", limited));
        }
        StripeSettings settings = stripeRepo.findByClientId(clientId).orElse(null);
        if (!onlineGivingConfigured(settings)) {
            // Authoritative check. The page renders normally for unconfigured orgs, so
            // this is the point where the restriction is actually enforced — posting
            // here directly (or re-enabling the button in devtools) hits the same guard.
            log.warn("[Donate] intent rejected — online giving not configured for clientId={}.",
                    clientId);
            return ResponseEntity.badRequest().body(Map.of("error", NOT_CONFIGURED_MSG));
        }

        // Subscription plan: Online Giving feature + monthly allowance. Both are
        // enforced BEFORE creating the PaymentIntent so a donor is never charged
        // when the church's plan doesn't allow it. Historical donations remain.
        if (!subscriptionService.isFeatureEnabled(clientId, "onlineGiving")) {
            return ResponseEntity.status(403).body(Map.of("error",
                    "Online giving is not included in this church's current subscription plan. "
                  + "Please contact the church office to give another way."));
        }
        if (!subscriptionService.canAcceptOnlineGiving(clientId)) {
            return ResponseEntity.status(403).body(Map.of("error",
                    "This church has reached its monthly online giving limit for its current "
                  + "subscription plan. Please contact the church office to give another way."));
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(String.valueOf(body.get("amount")));
            if (amount.compareTo(BigDecimal.valueOf(0.50)) < 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "Minimum donation amount is $0.50."));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid amount."));
        }

        // Financial audit M2: `currency` used to be taken from the request body with no
        // check. The donation page never lets a donor pick one — it always sends "usd" —
        // so this only ever mattered for a request built outside the page, and every
        // dollar amount downstream (this multiply, tax statements, reports) assumes
        // two-decimal USD. Reject rather than accept and mismeasure the charge.
        Object currencyRaw = body.get("currency");
        String currency    = (currencyRaw != null ? currencyRaw.toString() : "usd").toLowerCase().trim();
        if (!"usd".equals(currency)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only USD donations are supported."));
        }
        long   cents    = amount.multiply(BigDecimal.valueOf(100)).longValue();

        try {
            Map<String, Object> pi = stripeCreateIntent(settings.getSecretKey(), cents, currency);
            if (pi.containsKey("error")) {
                return ResponseEntity.badRequest().body(Map.of("error", stripeErrorMsg(pi)));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("clientSecret",    pi.get("client_secret"));
            out.put("paymentIntentId", pi.get("id"));
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Could not initiate payment. Please try again."));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Public API — save donation
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Verifies the Stripe PaymentIntent server-side, then persists the donation.
     * Idempotent — calling again with the same {@code paymentIntentId} is a no-op.
     *
     * <p>Expected request body:
     * <pre>
     * {
     *   "paymentIntentId": "pi_xxx",
     *   "firstName": "Jane",
     *   "lastName":  "Doe",     // optional
     *   "email":     "j@d.com"  // optional
     * }
     * </pre>
     */
    @ResponseBody
    @PostMapping("/api/public/donate/save")
    public ResponseEntity<?> saveDonation(@RequestParam("cid") String token,
                                           @RequestBody Map<String, Object> body) {
        String clientId = resolveClientId(token);
        if (clientId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid or expired donation link."));
        }
        StripeSettings settings = stripeRepo.findByClientId(clientId).orElse(null);
        if (!onlineGivingConfigured(settings)) {
            return ResponseEntity.badRequest().body(Map.of("error", NOT_CONFIGURED_MSG));
        }
        // Re-checked here and not only at the intent: the plan can change, or the
        // church can be moved onto an evaluation account, while a donor has the
        // payment sheet open. Recording a donation this church is no longer
        // permitted to accept would put money in a ledger the product says is shut.
        if (!subscriptionService.isFeatureEnabled(clientId, "onlineGiving")) {
            return ResponseEntity.status(403).body(Map.of("error", NOT_CONFIGURED_MSG));
        }

        String piId = str(body.get("paymentIntentId"));
        if (piId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "paymentIntentId is required."));
        }
        // The id is placed in the URL of an authenticated call to Stripe: accept only
        // the shape Stripe issues, never a path (security audit P4).
        if (!PAYMENT_INTENT_ID.matcher(piId).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid paymentIntentId."));
        }

        // Idempotency — skip if already saved
        if (donationRepo.findByStripePaymentIntentId(piId).isPresent()) {
            return ResponseEntity.ok(Map.of("success", true, "message", "Donation already recorded."));
        }

        // Verify payment with Stripe
        Map<String, Object> pi;
        try {
            pi = stripeRetrieveIntent(settings.getSecretKey(), piId);
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Could not verify payment. Please contact support."));
        }

        if (pi.containsKey("error")) {
            return ResponseEntity.badRequest().body(Map.of("error", stripeErrorMsg(pi)));
        }

        String status = str(pi.get("status"));
        if (!"succeeded".equals(status)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Payment has not completed (status: " + status + ")."));
        }

        // Amount and currency come from Stripe (authoritative)
        String currency = str(pi.getOrDefault("currency", "usd"));
        // Financial audit M2: the divide-by-100 below assumes a two-decimal currency —
        // correct for USD, wrong for a zero-decimal currency like JPY or a three-decimal
        // one like KWD. /intent now only ever creates USD PaymentIntents, so this should
        // be unreachable — but the charge has already happened by this point, so a
        // currency this app didn't intend to charge in is refused rather than mismeasured.
        if (!"usd".equals(currency)) {
            log.warn("[Donate] save rejected — PaymentIntent {} is in currency '{}', not usd. clientId={}",
                    piId, currency, clientId);
            return ResponseEntity.badRequest().body(Map.of("error",
                    "This payment was not made in US dollars and could not be recorded. Please contact support."));
        }
        BigDecimal amount = BigDecimal.valueOf(((Number) pi.getOrDefault("amount", 0)).longValue())
                .divide(BigDecimal.valueOf(100));

        // Extract charge + payment method details
        String chargeId      = null;
        String paymentMethod = "Card";
        try {
            Object latestCharge = pi.get("latest_charge");
            if (latestCharge instanceof Map<?, ?> charge) {
                chargeId = str(charge.get("id"));
                Object pmd = charge.get("payment_method_details");
                if (pmd instanceof Map<?, ?> details) {
                    String type = str(details.get("type"));
                    if ("card".equals(type) && details.get("card") instanceof Map<?, ?> card) {
                        String brand = str(card.get("brand"));
                        Object last4 = card.get("last4");
                        if (brand != null) brand = capitalize(brand);
                        paymentMethod = (brand != null ? brand : "Card")
                                + (last4 != null ? " \u2022\u2022\u2022\u2022 " + last4 : "");
                    } else if ("us_bank_account".equals(type)) {
                        paymentMethod = "Bank Transfer";
                    } else if (type != null) {
                        paymentMethod = capitalize(type.replace("_", " "));
                    }
                }
            }
        } catch (Exception ignored) { /* keep default */ }

        Donation donation = new Donation();
        donation.setClientId(clientId);
        donation.setFirstName(clip(str(body.get("firstName")), 100));
        donation.setLastName(clip(str(body.get("lastName")), 100));
        donation.setEmail(clip(str(body.get("email")), 200));
        donation.setPhone(clip(str(body.get("phone")), 50));
        donation.setNote(clip(str(body.get("note")), 1000));
        donation.setPaymentMethod(paymentMethod);
        donation.setAmount(amount);
        // Financial audit M3: this donation page has no fee-cover option, so the
        // whole charge is always the intended gift — set explicitly (rather than
        // left null) so every new row, from either giving path, has this
        // populated the same way; only a donation recorded before this existed
        // relies on the getIntendedAmountOrCharge() fallback.
        donation.setIntendedAmount(amount);
        donation.setFeeCovered(BigDecimal.ZERO);
        donation.setCurrency(currency != null ? currency.toUpperCase() : "USD");
        donation.setStripePaymentIntentId(piId);
        donation.setStripeChargeId(chargeId);
        donation.setStatus(status);

        donationRepo.save(donation);
        subscriptionService.recordOnlineGiving(clientId);
        // Financial audit H9: so this gift appears on the church's Year-End Tax
        // Report, the donor's own giving statement, and every other income
        // report — none of which reads the donation table. Best-effort and
        // never throws; a posting failure never affects the donor's confirmation.
        donationIncomePoster.postToIncome(donation);
        try { sendDonationThankyou(donation, clientId); } catch (Exception ignored) {}
        if (submissionNotifications != null) {
            String donor = ((donation.getFirstName() == null ? "" : donation.getFirstName()) + " "
                          + (donation.getLastName()  == null ? "" : donation.getLastName())).trim();
            String amt = "$" + String.format("%.2f", donation.getIntendedAmountOrCharge());
            submissionNotifications.record(clientId,
                    com.churchgeniuspro.service.PublicSubmissionNotificationService.Type.DONATION,
                    "New online donation",
                    amt + " from " + (donor.isEmpty() ? "an anonymous donor" : donor)
                        + (status != null && !"succeeded".equalsIgnoreCase(status) ? " (status: " + status + ")" : "") + ".",
                    donation.getId() == null ? null : donation.getId().longValue());
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** Phase D: the Member Portal sends the same thank-you for a member contribution. */
    public void sendThankyou(Donation donation, String clientId) { sendDonationThankyou(donation, clientId); }

    private void sendDonationThankyou(Donation donation, String clientId) {
        if (donation.getEmail() == null || donation.getEmail().isBlank()) return;

        ServiceClient sc = clientRepo.findByClientId(clientId).orElse(null);
        String churchName  = sc != null && sc.getChurchName() != null ? sc.getChurchName() : "Our Church";
        String churchEmail = sc != null && sc.getEmail() != null ? sc.getEmail() : "";
        String churchPhone = sc != null && sc.getPhone()  != null ? sc.getPhone()  : "";

        String donorFirst = donation.getFirstName() != null ? donation.getFirstName() : "Friend";
        String amountStr  = "$" + String.format("%.2f", donation.getIntendedAmountOrCharge());

        String donationDate = "";
        if (donation.getDonatedAt() != null) {
            java.time.LocalDate ld = donation.getDonatedAt().toLocalDate();
            donationDate = ld.getMonth()
                    .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
                    + " " + ld.getDayOfMonth() + ", " + ld.getYear();
        }

        String subject = "Thank You for Your Donation — " + churchName;

        String html =
            "<!DOCTYPE html><html><head><meta charset='UTF-8'/>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'/></head>"
            + "<body style='margin:0;padding:0;background:#f5f5f5;"
            + "font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;'>"
            + "<table width='100%' cellpadding='0' cellspacing='0'"
            + " style='background:#f5f5f5;padding:24px 16px;'><tr><td align='center'>"
            + "<table width='600' cellpadding='0' cellspacing='0'"
            + " style='max-width:600px;width:100%;background:#fff;border-radius:14px;"
            + "overflow:hidden;box-shadow:0 4px 20px rgba(0,0,0,.08);'>"

            // Header
            + "<tr><td style='background:linear-gradient(135deg,#673147 0%,#4f2538 100%);"
            + "padding:32px 36px;text-align:center;'>"
            + "<div style='font-size:13px;font-weight:600;color:rgba(255,255,255,.75);"
            + "letter-spacing:1px;text-transform:uppercase;margin-bottom:6px;'>" + esc(churchName) + "</div>"
            + "<div style='font-size:26px;font-weight:800;color:#fff;line-height:1.2;'>🙏 Thank You!</div>"
            + "<div style='margin-top:10px;display:inline-block;padding:5px 16px;"
            + "background:rgba(255,255,255,.18);border:1px solid rgba(255,255,255,.35);"
            + "border-radius:20px;font-size:13px;color:#fff;font-weight:600;'>Donation Received ✅</div>"
            + "</td></tr>"

            // Greeting
            + "<tr><td style='padding:28px 36px 0;'>"
            + "<p style='font-size:17px;font-weight:600;color:#333;margin:0 0 10px;'>Dear "
            + esc(donorFirst) + ",</p>"
            + "<p style='font-size:14px;color:#555;line-height:1.8;margin:0;'>"
            + "Thank you for your generous donation and support to <strong>" + esc(churchName) + "</strong>. "
            + "Your contribution helps us continue our ministry, outreach, and community services. "
            + "We are deeply grateful for your kindness and generosity."
            + "</p></td></tr>"

            // Donation summary card
            + "<tr><td style='padding:24px 36px 0;'>"
            + "<div style='background:#fdf5f7;border-radius:12px;padding:22px 24px;"
            + "border:1px solid #e8d5de;'>"
            + "<div style='font-size:13px;font-weight:700;text-transform:uppercase;"
            + "letter-spacing:.6px;color:#673147;margin-bottom:16px;'>💳 Donation Summary</div>"
            + "<table width='100%' cellpadding='0' cellspacing='0'>"
            + "<tr><td style='padding:6px 0;'>"
            + "<div style='font-size:13px;font-weight:600;color:#888;margin-bottom:4px;'>Donation Amount</div>"
            + "<div style='font-size:32px;font-weight:800;color:#673147;'>" + amountStr + "</div>"
            + "</td></tr>"
            + "<tr><td style='padding:8px 0 0;border-top:1px solid #e8d5de;'>"
            + "<table width='100%' cellpadding='0' cellspacing='0'>"
            + (donationDate.isBlank() ? "" :
               "<tr><td style='padding:5px 0;font-size:13px;font-weight:600;color:#888;width:140px;'>"
               + "Donation Date</td><td style='padding:5px 0;font-size:13px;color:#333;font-weight:600;'>"
               + esc(donationDate) + "</td></tr>")
            + (donation.getPaymentMethod() != null && !donation.getPaymentMethod().isBlank() ?
               "<tr><td style='padding:5px 0;font-size:13px;font-weight:600;color:#888;'>"
               + "Payment Method</td><td style='padding:5px 0;font-size:13px;color:#333;'>"
               + esc(donation.getPaymentMethod()) + "</td></tr>" : "")
            + (donation.getNote() != null && !donation.getNote().isBlank() ?
               "<tr><td style='padding:5px 0;font-size:13px;font-weight:600;color:#888;vertical-align:top;'>"
               + "Note</td><td style='padding:5px 0;font-size:13px;color:#333;'>"
               + esc(donation.getNote()) + "</td></tr>" : "")
            + "</table></td></tr>"
            + "</table></div></td></tr>"

            // 501(c)(3) notice
            + "<tr><td style='padding:20px 36px 0;'>"
            + "<div style='background:#fff8e8;border:1px solid #f59e0b;border-radius:10px;"
            + "padding:16px 20px;'>"
            + "<table width='100%' cellpadding='0' cellspacing='0'><tr>"
            + "<td width='36' valign='top' style='font-size:22px;padding-right:12px;'>📋</td>"
            + "<td valign='top'>"
            + "<div style='font-size:13px;font-weight:700;color:#92400e;margin-bottom:5px;'>"
            + "501(c)(3) Tax-Exempt Organization</div>"
            + "<div style='font-size:13px;color:#78350f;line-height:1.7;'>"
            + "<strong>" + esc(churchName) + "</strong> is a registered 501(c)(3) nonprofit organization. "
            + "Your donation may be tax-deductible to the extent permitted by law. "
            + "Please retain this email as your donation receipt for tax purposes. "
            + "No goods or services were provided in exchange for this contribution."
            + "</div></td></tr></table></div></td></tr>"

            // Contact section
            + "<tr><td style='padding:20px 36px 0;'>"
            + "<div style='background:#f8f8f8;border-radius:10px;padding:18px 20px;'>"
            + "<div style='font-size:13px;font-weight:700;text-transform:uppercase;letter-spacing:.6px;"
            + "color:#555;margin-bottom:10px;'>For Questions or Donation Inquiries, Contact:</div>"
            + "<div style='font-size:14px;color:#444;font-style:italic;line-height:2;'>"
            + "<strong style='font-style:normal;color:#673147;'>" + esc(churchName) + "</strong>"
            + (churchEmail.isBlank() ? "" : "<br/><a href='mailto:" + esc(churchEmail)
               + "' style='color:#673147;text-decoration:none;'>" + esc(churchEmail) + "</a>")
            + (churchPhone.isBlank() ? "" : "<br/>" + esc(churchPhone))
            + "</div></div></td></tr>"

            // Closing
            + "<tr><td style='padding:24px 36px;text-align:center;'>"
            + "<p style='font-size:14px;color:#555;line-height:1.8;margin:0 0 6px;'>"
            + "Thank you again for supporting our mission and ministry.</p>"
            + "<p style='font-size:15px;font-weight:600;color:#673147;margin:0;'>God bless you, 🙏</p>"
            + "<p style='font-size:14px;color:#777;margin:4px 0 0;'>" + esc(churchName) + " Team</p>"
            + "</td></tr>"

            // Footer
            + "<tr><td style='background:#fdf5f7;padding:16px 36px;text-align:center;"
            + "border-top:1px solid #f0e8ec;'>"
            + "<div style='font-size:11px;color:#aaa;'>This receipt was sent by " + esc(churchName)
            + " via Church Genius Pro. Please keep this email for your records.</div>"
            + "</td></tr>"

            + "</table></td></tr></table></body></html>";

        emailService.sendOrgEmail(donation.getEmail(), subject, html, clientId);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Authenticated — review page
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/donation-review")
    public String donationReviewPage(HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(request, "accounting.donation");
        if (deny != null) return deny;
        return "forward:/donationReview.html";
    }

    /** Phase D: resolves the purpose name for a member contribution row. Optional (unit tests). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.repository.SubSourceRepository subSourceRepo;
    public void setSubSourceRepo(com.churchgeniuspro.repository.SubSourceRepository r) { this.subSourceRepo = r; }

    /**
     * @param source Phase D: {@code member} lists Member Portal contributions; anything
     *               else (the default) lists donations made through the donation page.
     *               The two are kept apart so the Donation Review page can show them
     *               on separate tabs; totals are over the listed rows only.
     */
    /** The donation page's list, as before. */
    public ResponseEntity<?> getDonations(HttpServletRequest request) { return getDonations(null, request); }

    @ResponseBody
    @GetMapping("/api/donations")
    public ResponseEntity<?> getDonations(@RequestParam(value = "source", required = false) String source,
                                          HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return ResponseEntity.status(403).build();

        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();

        boolean memberTab = "member".equalsIgnoreCase(source);
        List<Donation> donations = donationRepo.findByClientIdOrderByDonatedAtDesc(clientId).stream()
                .filter(d -> d.isMemberContribution() == memberTab)
                .toList();
        Map<Integer, String> purposeNames = new java.util.HashMap<>();

        List<Map<String, Object>> result = new ArrayList<>();
        // Financial audit M11: totalsByCurrency/countByCurrency are accumulated from
        // this exact same list, in the exact same loop, as the rows sent to the
        // client — so the totals below can never drift from what the client is
        // actually looking at. BigDecimal addition of two scale-2 values is exact
        // (no double/float involved anywhere on this path), unlike the client's old
        // `reduce((s,d)=>s+parseFloat(d.amount))`, which could accumulate visible
        // cent-level error over a large list.
        //
        // Donation Review: `totals` covers PENDING donations only (review status not
        // Completed); Completed ones are summed separately into `completedTotals` for
        // the page's secondary line. Every row is still returned. Review status is a
        // page-level bookkeeping flag only — nothing here touches Stripe or Income.
        Map<String, BigDecimal> totalsByCurrency = new LinkedHashMap<>();
        Map<String, Integer>    countByCurrency  = new LinkedHashMap<>();
        Map<String, BigDecimal> completedByCurrency      = new LinkedHashMap<>();
        Map<String, Integer>    completedCountByCurrency = new LinkedHashMap<>();
        for (Donation d : donations) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",                    d.getId());
            m.put("firstName",             d.getFirstName());
            m.put("lastName",              d.getLastName());
            m.put("email",                 d.getEmail());
            m.put("phone",                 d.getPhone());
            m.put("note",                  d.getNote());
            m.put("paymentMethod",         d.getPaymentMethod());
            m.put("amount",                d.getAmount());
            m.put("intendedAmount",        d.getIntendedAmountOrCharge());
            m.put("feeCovered",            d.getFeeCovered() != null ? d.getFeeCovered() : BigDecimal.ZERO);
            m.put("currency",              d.getCurrency());
            m.put("status",                d.getStatus());
            m.put("donatedAt",             d.getDonatedAt() != null ? d.getDonatedAt().toString() : null);
            m.put("stripePaymentIntentId", d.getStripePaymentIntentId());
            m.put("reviewStatus",          d.isReviewCompleted() ? Donation.REVIEW_COMPLETED : "PENDING");
            if (memberTab) {
                m.put("memberId", d.getMemberId());
                m.put("purpose",  d.getSubSourceId() == null ? null : purposeNames.computeIfAbsent(d.getSubSourceId(), id ->
                        subSourceRepo == null ? null : subSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(id, clientId)
                                .map(ss -> ss.getSourceName()).orElse(null)));
            }
            m.put("reviewCompletedAt",     d.getReviewCompletedAt() != null ? d.getReviewCompletedAt().toString() : null);
            result.add(m);

            // Same "missing currency means USD" normalization the review page itself
            // applies (see the M2 fix in donationReview.html) — kept identical here so
            // a row can never end up counted under a different currency bucket than
            // the one the client displays it under.
            String cur = (d.getCurrency() == null || d.getCurrency().isBlank()) ? "USD" : d.getCurrency();
            BigDecimal amt = d.getAmount() != null ? d.getAmount() : BigDecimal.ZERO;
            if (d.isReviewCompleted()) {
                completedByCurrency.merge(cur, amt, BigDecimal::add);
                completedCountByCurrency.merge(cur, 1, Integer::sum);
            } else {
                totalsByCurrency.merge(cur, amt, BigDecimal::add);
                countByCurrency.merge(cur, 1, Integer::sum);
            }
        }

        List<Map<String, Object>> totals = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : totalsByCurrency.entrySet()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("currency", e.getKey());
            t.put("total",    e.getValue());
            t.put("count",    countByCurrency.get(e.getKey()));
            totals.add(t);
        }

        List<Map<String, Object>> completedTotals = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : completedByCurrency.entrySet()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("currency", e.getKey());
            t.put("total",    e.getValue());
            t.put("count",    completedCountByCurrency.get(e.getKey()));
            completedTotals.add(t);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows",   result);
        out.put("totals", totals);
        out.put("completedTotals", completedTotals);
        return ResponseEntity.ok(out);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Stripe REST helpers (no SDK — uses Spring RestTemplate)
    // ══════════════════════════════════════════════════════════════════════════

    // Phase D: the calls themselves live in ChurchStripeGateway (unchanged in substance).
    private Map<String, Object> stripeCreateIntent(String secretKey, long cents, String currency) {
        return gateway().createIntent(secretKey, cents, currency);
    }

    private Map<String, Object> stripeRetrieveIntent(String secretKey, String piId) {
        return gateway().retrieveIntent(secretKey, piId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Decrypts a public-link token, validates it via PublicScreenLink, and
     * returns the embedded clientId, or {@code null} if invalid/expired.
     */
    /**
     * True when the church can actually take a card payment. Both keys are required:
     * the publishable key for Stripe.js in the browser and the secret key for the
     * server-side PaymentIntent, so a half-finished setup counts as not configured.
     */
    private boolean onlineGivingConfigured(StripeSettings s) {
        return s != null && !isBlank(s.getPublishableKey()) && !isBlank(s.getSecretKey());
    }

    private String resolveClientId(String token) {
        if (token == null || token.isBlank()) return null;
        Optional<PublicScreenLink> opt = linkRepo.findByToken(token);
        if (opt.isEmpty() || opt.get().isRevoked()) return null;
        PublicScreenLink link = opt.get();
        if (link.getExpirationDate() != null && link.getExpirationDate().isBefore(LocalDate.now())) return null;
        // Only a Donation link. Revoking the donation link must not be undone by the
        // church's other public links (events board, membership form…) still being live.
        if (link.getPageUrl() == null || !link.getPageUrl().startsWith("/donate")) return null;
        return link.getAppClientId();
    }

    private static String stripeErrorMsg(Map<String, Object> pi) {
        Object err = pi.get("error");
        if (err instanceof Map<?, ?> e) {
            Object msg = e.get("message");
            if (msg != null) return msg.toString();
        }
        return "A payment error occurred.";
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Donor-typed text, cut to the column's worth of characters. */
    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    /** First few characters of a token for safe correlation in logs (never the full token). */
    private static String tokenPrefix(String t) {
        if (t == null || t.isEmpty()) return "<empty>";
        return t.substring(0, Math.min(t.length(), 8)) + "…";
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
