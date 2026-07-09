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
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

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
 *   <li>{@code GET /api/donations}     → JSON list of donations for the org</li>
 * </ul>
 */
@Controller
public class DonationController {

    private static final Logger log = LoggerFactory.getLogger(DonationController.class);

    private static final String STRIPE_API_BASE = "https://api.stripe.com/v1";

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final DonationRepository         donationRepo;
    private final StripeSettingsRepository   stripeRepo;
    private final PublicScreenLinkRepository linkRepo;
    private final ServiceClientRepository    clientRepo;
    private final ChurchLogoRepository       logoRepo;
    private final EmailService               emailService;
    private final RestTemplate               restTemplate = new RestTemplate();

    public DonationController(DonationRepository donationRepo,
                               StripeSettingsRepository stripeRepo,
                               PublicScreenLinkRepository linkRepo,
                               ServiceClientRepository clientRepo,
                               ChurchLogoRepository logoRepo,
                               EmailService emailService) {
        this.donationRepo = donationRepo;
        this.stripeRepo   = stripeRepo;
        this.linkRepo     = linkRepo;
        this.clientRepo   = clientRepo;
        this.logoRepo     = logoRepo;
        this.emailService = emailService;
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
        if (settings == null || isBlank(settings.getPublishableKey())) {
            // Distinguish the two real causes in the server log so this isn't opaque:
            //   • settingsPresent=false → no Stripe row for THIS org (configure Stripe,
            //     or this donate link belongs to a different org than the one you set up).
            //   • settingsPresent=true, keyBlank=true → row exists but no publishable key.
            log.warn("[Donate] config: online giving not configured. resolvedClientId={}, "
                    + "stripeSettingsPresent={}, publishableKeyBlank={}, totalStripeSettingsRows={}. "
                    + "Set the Publishable + Secret keys at /stripeIntegration for this organization.",
                    clientId, settings != null,
                    settings != null && isBlank(settings.getPublishableKey()), stripeRepo.count());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Online giving is not configured for this organization."));
        }
        log.info("[Donate] config OK for clientId={}.", clientId);
        String churchName = clientRepo.findByClientId(clientId)
                .map(ServiceClient::getChurchName)
                .filter(n -> n != null && !n.isBlank())
                .orElse(null);

        boolean hasLogo = logoRepo.findByClientId(clientId)
                .map(l -> l.getLogoData() != null && l.getLogoData().length > 0)
                .orElse(false);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("publishableKey", settings.getPublishableKey());
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
                                           @RequestBody Map<String, Object> body) {
        String clientId = resolveClientId(token);
        if (clientId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid or expired donation link."));
        }
        StripeSettings settings = stripeRepo.findByClientId(clientId).orElse(null);
        if (settings == null || isBlank(settings.getSecretKey())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Online giving is not configured for this organization."));
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

        String currency = body.getOrDefault("currency", "usd").toString().toLowerCase().trim();
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
        if (settings == null || isBlank(settings.getSecretKey())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Stripe not configured."));
        }

        String piId = str(body.get("paymentIntentId"));
        if (piId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "paymentIntentId is required."));
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
        BigDecimal amount = BigDecimal.valueOf(((Number) pi.getOrDefault("amount", 0)).longValue())
                .divide(BigDecimal.valueOf(100));
        String currency   = str(pi.getOrDefault("currency", "usd"));

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
        donation.setFirstName(str(body.get("firstName")));
        donation.setLastName(str(body.get("lastName")));
        donation.setEmail(str(body.get("email")));
        donation.setPhone(str(body.get("phone")));
        donation.setNote(str(body.get("note")));
        donation.setPaymentMethod(paymentMethod);
        donation.setAmount(amount);
        donation.setCurrency(currency != null ? currency.toUpperCase() : "USD");
        donation.setStripePaymentIntentId(piId);
        donation.setStripeChargeId(chargeId);
        donation.setStatus(status);

        donationRepo.save(donation);
        try { sendDonationThankyou(donation, clientId); } catch (Exception ignored) {}
        return ResponseEntity.ok(Map.of("success", true));
    }

    private void sendDonationThankyou(Donation donation, String clientId) {
        if (donation.getEmail() == null || donation.getEmail().isBlank()) return;

        ServiceClient sc = clientRepo.findByClientId(clientId).orElse(null);
        String churchName  = sc != null && sc.getChurchName() != null ? sc.getChurchName() : "Our Church";
        String churchEmail = sc != null && sc.getEmail() != null ? sc.getEmail() : "";
        String churchPhone = sc != null && sc.getPhone()  != null ? sc.getPhone()  : "";

        String donorFirst = donation.getFirstName() != null ? donation.getFirstName() : "Friend";
        String amountStr  = "$" + String.format("%.2f", donation.getAmount());

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
        deny = RoleGuard.requirePermission(request, "accounting.donation");
        if (deny != null) return deny;
        return "forward:/donationReview.html";
    }

    @ResponseBody
    @GetMapping("/api/donations")
    public ResponseEntity<?> getDonations(HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return ResponseEntity.status(403).build();

        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();

        List<Donation> donations = donationRepo.findByClientIdOrderByDonatedAtDesc(clientId);

        List<Map<String, Object>> result = new ArrayList<>();
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
            m.put("currency",              d.getCurrency());
            m.put("status",                d.getStatus());
            m.put("donatedAt",             d.getDonatedAt() != null ? d.getDonatedAt().toString() : null);
            m.put("stripePaymentIntentId", d.getStripePaymentIntentId());
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Stripe REST helpers (no SDK — uses Spring RestTemplate)
    // ══════════════════════════════════════════════════════════════════════════

    private Map<String, Object> stripeCreateIntent(String secretKey, long cents, String currency) {
        HttpHeaders headers = buildStripeHeaders(secretKey);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("amount",   String.valueOf(cents));
        params.add("currency", currency);
        params.add("automatic_payment_methods[enabled]", "true");

        try {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    STRIPE_API_BASE + "/payment_intents",
                    HttpMethod.POST,
                    new HttpEntity<>(params, headers),
                    MAP_TYPE);
            return resp.getBody() != null ? resp.getBody() : Map.of();
        } catch (HttpClientErrorException ex) {
            // Stripe returns 4xx with a JSON error body — parse it via a fresh exchange on the error response
            return Map.of("error", Map.of("message", ex.getStatusText()));
        }
    }

    private Map<String, Object> stripeRetrieveIntent(String secretKey, String piId) {
        HttpHeaders headers = buildStripeHeaders(secretKey);
        try {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    STRIPE_API_BASE + "/payment_intents/" + piId + "?expand[]=latest_charge",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    MAP_TYPE);
            return resp.getBody() != null ? resp.getBody() : Map.of();
        } catch (HttpClientErrorException ex) {
            return Map.of("error", Map.of("message", ex.getStatusText()));
        }
    }

    private HttpHeaders buildStripeHeaders(String secretKey) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer " + secretKey);
        return h;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Decrypts a public-link token, validates it via PublicScreenLink, and
     * returns the embedded clientId, or {@code null} if invalid/expired.
     */
    private String resolveClientId(String token) {
        if (token == null || token.isBlank()) return null;
        Optional<PublicScreenLink> opt = linkRepo.findByToken(token);
        if (opt.isEmpty() || opt.get().isRevoked()) return null;
        PublicScreenLink link = opt.get();
        if (link.getExpirationDate() != null && link.getExpirationDate().isBefore(LocalDate.now())) return null;
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
