package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.ChurchLogo;
import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.Donation;
import com.churchgeniuspro.hibernate.MidRegMeet;
import com.churchgeniuspro.hibernate.MidRegMeetRsvp;
import com.churchgeniuspro.hibernate.StripeSettings;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.ChurchLogoRepository;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.DonationRepository;
import com.churchgeniuspro.repository.MidRegMeetRepository;
import com.churchgeniuspro.repository.MidRegMeetRsvpRepository;
import com.churchgeniuspro.repository.StripeSettingsRepository;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.SmsService;
import com.churchgeniuspro.util.EncryptionUtil;
import com.churchgeniuspro.util.PhoneUtil;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Handles the Midwest Region Meet admin page and its public RSVP page.
 *
 * <h3>Page routes</h3>
 * <ul>
 *   <li>{@code GET /midRegMeet}     → {@code midRegMeet.html}  (Admin + SuperAdmin)</li>
 *   <li>{@code GET /midRegMeetRsvp} → {@code midRegMeetRsvp.html} (public, no auth)</li>
 * </ul>
 *
 * <h3>Admin API routes (session required)</h3>
 * <ul>
 *   <li>{@code GET    /api/mid-reg-meet/config}              → get event config</li>
 *   <li>{@code POST   /api/mid-reg-meet/config}              → save event config</li>
 *   <li>{@code GET    /api/mid-reg-meet/public-link}         → encrypted public RSVP URL</li>
 *   <li>{@code POST   /api/mid-reg-meet/tshirt-image}        → upload t-shirt image</li>
 *   <li>{@code GET    /api/mid-reg-meet/tshirt-image-preview}→ serve t-shirt image (admin, session auth)</li>
 *   <li>{@code GET    /api/mid-reg-meet/participants}        → list all RSVPs</li>
 *   <li>{@code DELETE /api/mid-reg-meet/rsvp/{id}}           → delete an RSVP</li>
 * </ul>
 *
 * <h3>Public API routes (no session required)</h3>
 * <ul>
 *   <li>{@code GET  /api/mid-reg-meet/public/config}              → event config (clientId encrypted)</li>
 *   <li>{@code GET  /api/mid-reg-meet/public/tshirt-image}        → t-shirt image (clientId encrypted)</li>
 *   <li>{@code POST /api/mid-reg-meet/public/rsvp}                → submit RSVP (clientId encrypted)</li>
 *   <li>{@code GET  /api/mid-reg-meet/public/rsvp/edit/{token}}   → fetch RSVP by edit token</li>
 *   <li>{@code PUT  /api/mid-reg-meet/public/rsvp/edit/{token}}   → update RSVP by edit token</li>
 * </ul>
 *
 * <p><strong>Client ID encryption:</strong> All public endpoints accept an encrypted
 * {@code clientId} parameter (AES-128/ECB via {@link EncryptionUtil}).  The admin page
 * exposes the encrypted value via {@code GET /api/mid-reg-meet/public-link} so it is
 * never exposed in plain text in the shareable URL.
 */
@Controller
public class MidRegMeetController {

    /** SVG placeholder returned when no t-shirt image has been uploaded. */
    private static final String DEFAULT_TSHIRT_SVG =
        "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 120 120' width='120' height='120'>" +
        "<rect width='120' height='120' rx='10' fill='#f5f0f4'/>" +
        "<text x='60' y='72' text-anchor='middle' font-size='52'>👕</text>" +
        "</svg>";

    private static final String STRIPE_API_BASE = "https://api.stripe.com/v1";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final MidRegMeetRepository         meetRepo;
    private final MidRegMeetRsvpRepository     rsvpRepo;
    private final AppUserRepository            userRepo;
    private final ChurchLogoRepository         logoRepo;
    private final ChurchRegistrationRepository churchRepo;
    private final DonationRepository           donationRepo;
    private final StripeSettingsRepository     stripeRepo;
    private final EmailService                 emailService;
    private final SmsService                   smsService;
    private final RestTemplate                 restTemplate = new RestTemplate();

    @Value("${app.base-url}")
    private String baseUrl;

    public MidRegMeetController(MidRegMeetRepository meetRepo,
                                MidRegMeetRsvpRepository rsvpRepo,
                                AppUserRepository userRepo,
                                ChurchLogoRepository logoRepo,
                                ChurchRegistrationRepository churchRepo,
                                DonationRepository donationRepo,
                                StripeSettingsRepository stripeRepo,
                                EmailService emailService,
                                SmsService smsService) {
        this.meetRepo     = meetRepo;
        this.rsvpRepo     = rsvpRepo;
        this.userRepo     = userRepo;
        this.logoRepo     = logoRepo;
        this.churchRepo   = churchRepo;
        this.donationRepo = donationRepo;
        this.stripeRepo   = stripeRepo;
        this.emailService = emailService;
        this.smsService   = smsService;
    }

    // ── Page routes ───────────────────────────────────────────────────────────

    @GetMapping("/midRegMeet")
    public String adminPage(HttpServletRequest request) {
        // Members are allowed to view the Midwest Meet page (nav visibility is
        // controlled by MEMBER_NAV_PERM in the member portal sidebar).
        String deny = RoleGuard.requireAdminOrUser(request);
        return deny != null ? deny : "forward:/midRegMeet.html";
    }

    @GetMapping("/midRegMeetRsvp")
    public String publicRsvpPage() {
        return "forward:/midRegMeetRsvp.html";
    }

    // ── Admin: get config ─────────────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/mid-reg-meet/config")
    public ResponseEntity<?> getConfig(HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        MidRegMeet meet = meetRepo.findByClientId(clientId).orElse(new MidRegMeet());
        return ResponseEntity.ok(toConfigMap(meet, false));
    }

    // ── Admin: save config ────────────────────────────────────────────────────

    @ResponseBody
    @PostMapping("/api/mid-reg-meet/config")
    public ResponseEntity<?> saveConfig(@RequestBody Map<String, Object> body,
                                        HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();

        MidRegMeet meet = meetRepo.findByClientId(clientId).orElse(new MidRegMeet());
        meet.setClientId(clientId);
        applyConfigBody(meet, body);
        meetRepo.save(meet);
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── Admin: encrypted public link ──────────────────────────────────────────

    /**
     * Returns the shareable public RSVP URL with the clientId AES-encrypted.
     * The encrypted token is URL-safe Base64 without padding.
     */
    @ResponseBody
    @GetMapping("/api/mid-reg-meet/public-link")
    public ResponseEntity<?> publicLink(HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        try {
            String encrypted = EncryptionUtil.encrypt(clientId);
            String url = baseUrl + "/midRegMeetRsvp?cid=" + URLEncoder.encode(encrypted, StandardCharsets.UTF_8);
            return ResponseEntity.ok(Map.of("url", url));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Admin: upload t-shirt image ───────────────────────────────────────────

    @ResponseBody
    @PostMapping(value = "/api/mid-reg-meet/tshirt-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadTshirtImage(@RequestParam("file") MultipartFile file,
                                               HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        if (file.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "No file selected."));
        String ct = file.getContentType();
        if (ct == null || !ct.startsWith("image/"))
            return ResponseEntity.badRequest().body(Map.of("error", "Only image files are allowed."));
        try {
            MidRegMeet meet = meetRepo.findByClientId(clientId).orElse(new MidRegMeet());
            meet.setClientId(clientId);
            meet.setTshirtImage(file.getBytes());
            meet.setTshirtImageContentType(ct);
            meetRepo.save(meet);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Admin: serve t-shirt image (for admin page preview, session required) ──

    @GetMapping("/api/mid-reg-meet/tshirt-image-preview")
    public ResponseEntity<byte[]> tshirtImagePreview(HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        return meetRepo.findByClientId(clientId)
                .filter(m -> m.getTshirtImage() != null && m.getTshirtImage().length > 0)
                .map(m -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(m.getTshirtImageContentType()))
                        .body(m.getTshirtImage()))
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Admin: list participants ──────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/mid-reg-meet/participants")
    public ResponseEntity<?> listParticipants(HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        List<MidRegMeetRsvp> rsvps = rsvpRepo.findByClientIdOrderByCreatedAtDesc(clientId);

        // Event donations (saved with a "Midwest Region Meet" note), totalled per
        // donor so each participant row can show their donation details.
        Map<String, java.math.BigDecimal> donTotals = new HashMap<>();
        Map<String, Integer> donCounts = new HashMap<>();
        try {
            for (com.churchgeniuspro.hibernate.Donation d : donationRepo.findByClientIdOrderByDonatedAtDesc(clientId)) {
                if (d.getNote() == null || !d.getNote().startsWith("Midwest Region Meet")) continue;
                if (d.getAmount() == null) continue;
                if (d.getStatus() != null && !"succeeded".equalsIgnoreCase(d.getStatus())) continue;
                String key = donorKey(d.getEmail(), d.getFirstName(), d.getLastName());
                if (key.isEmpty()) continue;
                donTotals.merge(key, d.getAmount(), java.math.BigDecimal::add);
                donCounts.merge(key, 1, Integer::sum);
            }
        } catch (Exception ignored) { }

        List<Map<String, Object>> result = new ArrayList<>();
        for (MidRegMeetRsvp r : rsvps) {
            Map<String, Object> m = toRsvpMap(r);
            String key = donorKey(r.getEmail(), r.getFirstName(), r.getLastName());
            java.math.BigDecimal total = donTotals.get(key);
            m.put("donationAmount", total == null ? null : total);
            m.put("donationCount", donCounts.getOrDefault(key, 0));
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    /** Matches an RSVP to its donations: by email when present, else by full name. */
    private static String donorKey(String email, String first, String last) {
        if (email != null && !email.isBlank()) return email.trim().toLowerCase();
        String name = ((first == null ? "" : first.trim()) + " " + (last == null ? "" : last.trim())).trim().toLowerCase();
        return name;
    }

    // ── Admin: delete RSVP ────────────────────────────────────────────────────

    @ResponseBody
    @DeleteMapping("/api/mid-reg-meet/rsvp/{id}")
    public ResponseEntity<?> deleteRsvp(@PathVariable Long id, HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        return rsvpRepo.findById(id).map(r -> {
            if (!r.getClientId().equals(clientId)) return ResponseEntity.status(403).<Object>build();
            rsvpRepo.delete(r);
            return ResponseEntity.ok((Object) Map.of("success", true));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Public: get event config (?cid= encrypted or ?clientId= plain) ────────

    @ResponseBody
    @GetMapping("/api/mid-reg-meet/public/config")
    public ResponseEntity<?> publicConfig(
            @RequestParam(required = false) String cid,
            @RequestParam(required = false) String clientId) {
        String resolved = resolveClientId(cid, clientId);
        if (resolved == null) return ResponseEntity.badRequest().body(Map.of("error", "Missing clientId or cid"));
        return meetRepo.findByClientId(resolved)
                .map(m -> ResponseEntity.ok(toConfigMap(m, true)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Public: t-shirt image (?cid= encrypted or ?clientId= plain) ──────────

    @GetMapping("/api/mid-reg-meet/public/tshirt-image")
    public ResponseEntity<byte[]> publicTshirtImage(
            @RequestParam(required = false) String cid,
            @RequestParam(required = false) String clientId) {
        String resolved = resolveClientId(cid, clientId);
        if (resolved != null) {
            Optional<MidRegMeet> opt = meetRepo.findByClientId(resolved);
            if (opt.isPresent() && opt.get().getTshirtImage() != null && opt.get().getTshirtImage().length > 0) {
                MidRegMeet m = opt.get();
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(m.getTshirtImageContentType()))
                        .body(m.getTshirtImage());
            }
        }
        // Return a default SVG placeholder instead of 404
        byte[] svg = DEFAULT_TSHIRT_SVG.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("image/svg+xml"))
                .body(svg);
    }

    // ── Admin: upload flyer image ─────────────────────────────────────────────

    @ResponseBody
    @PostMapping(value = "/api/mid-reg-meet/flyer-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadFlyerImage(@RequestParam("file") MultipartFile file,
                                              HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        if (file.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "No file selected."));
        String ct = file.getContentType();
        if (ct == null || !ct.startsWith("image/"))
            return ResponseEntity.badRequest().body(Map.of("error", "Only image files are allowed."));
        try {
            MidRegMeet meet = meetRepo.findByClientId(clientId).orElse(new MidRegMeet());
            meet.setClientId(clientId);
            meet.setFlyerImage(file.getBytes());
            meet.setFlyerImageContentType(ct);
            meetRepo.save(meet);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Admin: serve flyer image (for admin page preview, session required) ───

    @GetMapping("/api/mid-reg-meet/flyer-image-preview")
    public ResponseEntity<byte[]> flyerImagePreview(HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        return meetRepo.findByClientId(clientId)
                .filter(m -> m.getFlyerImage() != null && m.getFlyerImage().length > 0)
                .map(m -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(m.getFlyerImageContentType()))
                        .body(m.getFlyerImage()))
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Public: flyer image (?cid= encrypted or ?clientId= plain) ────────────

    @GetMapping("/api/mid-reg-meet/public/flyer-image")
    public ResponseEntity<byte[]> publicFlyerImage(
            @RequestParam(required = false) String cid,
            @RequestParam(required = false) String clientId) {
        String resolved = resolveClientId(cid, clientId);
        if (resolved == null) return ResponseEntity.notFound().build();
        return meetRepo.findByClientId(resolved)
                .filter(m -> m.getFlyerImage() != null && m.getFlyerImage().length > 0)
                .map(m -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(m.getFlyerImageContentType()))
                        .body(m.getFlyerImage()))
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Public: church name (?cid= encrypted or ?clientId= plain) ────────────

    @ResponseBody
    @GetMapping("/api/mid-reg-meet/public/church-info")
    public ResponseEntity<?> publicChurchInfo(
            @RequestParam(required = false) String cid,
            @RequestParam(required = false) String clientId) {
        String resolved = resolveClientId(cid, clientId);
        if (resolved == null) return ResponseEntity.badRequest().body(Map.of("error", "Missing clientId or cid"));
        String name = churchRepo.findByClientIdAndDeleteFlagFalse(resolved)
                .map(c -> c.getChurchName())
                .orElse("");
        return ResponseEntity.ok(Map.of("name", name != null ? name : ""));
    }

    // ── Public: church logo (?cid= encrypted or ?clientId= plain) ────────────

    @GetMapping("/api/mid-reg-meet/public/logo")
    public ResponseEntity<byte[]> publicLogo(
            @RequestParam(required = false) String cid,
            @RequestParam(required = false) String clientId) {
        String resolved = resolveClientId(cid, clientId);
        if (resolved == null) return ResponseEntity.notFound().build();
        return logoRepo.findByClientId(resolved)
                .filter(l -> l.getLogoData() != null && l.getLogoData().length > 0)
                .map(l -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(
                                l.getContentType() != null ? l.getContentType() : "image/png"))
                        .body(l.getLogoData()))
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Public: Stripe config for donation section ────────────────────────────

    /**
     * Returns the Stripe publishable key and transaction-fee flag for the public RSVP page.
     * Returns 404 (not an error) when Stripe is not configured — page hides donation section.
     */
    @ResponseBody
    @GetMapping("/api/mid-reg-meet/public/stripe-config")
    public ResponseEntity<?> publicStripeConfig(
            @RequestParam(required = false) String cid,
            @RequestParam(required = false) String clientId) {
        String resolved = resolveClientId(cid, clientId);
        if (resolved == null) return ResponseEntity.notFound().build();
        StripeSettings settings = stripeRepo.findByClientId(resolved).orElse(null);
        if (settings == null || settings.getPublishableKey() == null || settings.getPublishableKey().isBlank()) {
            return ResponseEntity.notFound().build();
        }
        boolean fee = meetRepo.findByClientId(resolved)
                .map(m -> m.isIncludeTransactionFee()).orElse(false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("publishableKey",       settings.getPublishableKey());
        out.put("includeTransactionFee", fee);
        return ResponseEntity.ok(out);
    }

    // ── Public: create Stripe PaymentIntent for event donation ───────────────

    @ResponseBody
    @PostMapping("/api/mid-reg-meet/public/donate/intent")
    public ResponseEntity<?> midRegDonateIntent(
            @RequestParam(required = false) String cid,
            @RequestParam(required = false) String clientId,
            @RequestBody Map<String, Object> body) {
        String resolved = resolveClientId(cid, clientId);
        if (resolved == null) return ResponseEntity.badRequest().body(Map.of("error", "Invalid link."));
        StripeSettings settings = stripeRepo.findByClientId(resolved).orElse(null);
        if (settings == null || settings.getSecretKey() == null || settings.getSecretKey().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Online giving is not configured."));
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(String.valueOf(body.get("amount")));
            if (amount.compareTo(BigDecimal.valueOf(0.50)) < 0)
                return ResponseEntity.badRequest().body(Map.of("error", "Minimum donation is $0.50."));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid amount."));
        }
        long cents = amount.multiply(BigDecimal.valueOf(100)).longValue();
        try {
            Map<String, Object> pi = stripeCreateIntent(settings.getSecretKey(), cents, "usd");
            if (pi.containsKey("error"))
                return ResponseEntity.badRequest().body(Map.of("error", stripeErrorMsg(pi)));
            return ResponseEntity.ok(Map.of(
                    "clientSecret",    pi.get("client_secret"),
                    "paymentIntentId", pi.get("id")));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Could not initiate payment. Please try again."));
        }
    }

    // ── Public: save donation after Stripe confirmation ───────────────────────

    @ResponseBody
    @PostMapping("/api/mid-reg-meet/public/donate/save")
    public ResponseEntity<?> midRegDonateSave(
            @RequestParam(required = false) String cid,
            @RequestParam(required = false) String clientId,
            @RequestBody Map<String, Object> body) {
        String resolved = resolveClientId(cid, clientId);
        if (resolved == null) return ResponseEntity.badRequest().body(Map.of("error", "Invalid link."));
        StripeSettings settings = stripeRepo.findByClientId(resolved).orElse(null);
        if (settings == null || settings.getSecretKey() == null || settings.getSecretKey().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Stripe not configured."));
        }
        String piId = strObj(body.get("paymentIntentId"));
        if (piId == null) return ResponseEntity.badRequest().body(Map.of("error", "paymentIntentId required."));
        if (donationRepo.findByStripePaymentIntentId(piId).isPresent())
            return ResponseEntity.ok(Map.of("success", true, "message", "Already recorded."));

        Map<String, Object> pi;
        try { pi = stripeRetrieveIntent(settings.getSecretKey(), piId); }
        catch (Exception e) { return ResponseEntity.status(500).body(Map.of("error", "Could not verify payment.")); }
        if (pi.containsKey("error")) return ResponseEntity.badRequest().body(Map.of("error", stripeErrorMsg(pi)));
        if (!"succeeded".equals(strObj(pi.get("status"))))
            return ResponseEntity.badRequest().body(Map.of("error", "Payment not completed."));

        BigDecimal amount = BigDecimal.valueOf(((Number) pi.getOrDefault("amount", 0)).longValue())
                .divide(BigDecimal.valueOf(100));
        String currency = strObj(pi.getOrDefault("currency", "usd"));

        String chargeId = null; String paymentMethod = "Card";
        try {
            Object lc = pi.get("latest_charge");
            if (lc instanceof Map<?,?> charge) {
                chargeId = strObj(charge.get("id"));
                Object pmd = charge.get("payment_method_details");
                if (pmd instanceof Map<?,?> details) {
                    String type = strObj(details.get("type"));
                    if ("card".equals(type) && details.get("card") instanceof Map<?,?> card) {
                        String brand = strObj(card.get("brand"));
                        Object last4 = card.get("last4");
                        if (brand != null) brand = Character.toUpperCase(brand.charAt(0)) + brand.substring(1);
                        paymentMethod = (brand != null ? brand : "Card") + (last4 != null ? " •••• " + last4 : "");
                    } else if ("us_bank_account".equals(type)) {
                        paymentMethod = "Bank Transfer";
                    } else if (type != null) {
                        String t = type.replace("_", " ");
                        paymentMethod = Character.toUpperCase(t.charAt(0)) + t.substring(1);
                    }
                }
            }
        } catch (Exception ignored) {}

        Donation donation = new Donation();
        donation.setClientId(resolved);
        donation.setFirstName(strObj(body.get("firstName")));
        donation.setLastName(strObj(body.get("lastName")));
        donation.setEmail(strObj(body.get("email")));
        donation.setPhone(strObj(body.get("phone")));
        donation.setNote("Midwest Region Meet" + (body.get("note") != null && !body.get("note").toString().isBlank()
                ? " — " + body.get("note").toString().trim() : ""));
        donation.setPaymentMethod(paymentMethod);
        donation.setAmount(amount);
        donation.setCurrency(currency != null ? currency.toUpperCase() : "USD");
        donation.setStripePaymentIntentId(piId);
        donation.setStripeChargeId(chargeId);
        donation.setStatus("succeeded");
        donationRepo.save(donation);
        try { sendDonationThankyou(donation, resolved); } catch (Exception ignored) {}
        return ResponseEntity.ok(Map.of("success", true));
    }

    private void sendDonationThankyou(Donation donation, String clientId) {
        if (donation.getEmail() == null || donation.getEmail().isBlank()) return;

        // Gather church / event info
        MidRegMeet meet = meetRepo.findByClientId(clientId).orElse(new MidRegMeet());
        ChurchRegistration church = churchRepo.findByClientIdAndDeleteFlagFalse(clientId).orElse(null);

        String churchName  = church != null && church.getChurchName() != null ? church.getChurchName() : "Our Church";
        String churchEmail = church != null && church.getEmail() != null ? church.getEmail() : "";
        String churchPhone = church != null && church.getPhone()  != null ? church.getPhone()  : "";
        String senderName  = meet.getSenderName()  != null ? meet.getSenderName()  : churchName;
        String senderEmail = meet.getSenderEmail() != null ? meet.getSenderEmail() : churchEmail;
        String senderPhone = meet.getSenderPhone() != null ? meet.getSenderPhone() : churchPhone;
        String eventName   = meet.getEventName()   != null ? meet.getEventName()   : "Midwest Region Meet";

        String donorFirst  = donation.getFirstName() != null ? donation.getFirstName() : "Friend";
        String donorFull   = (donorFirst + " " + (donation.getLastName() != null ? donation.getLastName() : "")).trim();

        // Format amount
        String amountStr = "$" + String.format("%.2f", donation.getAmount());

        // Format date
        String donationDate = "";
        if (donation.getDonatedAt() != null) {
            java.time.LocalDate ld = donation.getDonatedAt().toLocalDate();
            donationDate = ld.getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
                    + " " + ld.getDayOfMonth() + ", " + ld.getYear();
        }

        String subject = "Thank You for Your Donation — " + senderName;

        String html =
            "<!DOCTYPE html><html><head><meta charset='UTF-8'/>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'/></head>"
            + "<body style='margin:0;padding:0;background:#f5f5f5;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;'>"
            + "<table width='100%' cellpadding='0' cellspacing='0' style='background:#f5f5f5;padding:24px 16px;'><tr><td align='center'>"
            + "<table width='600' cellpadding='0' cellspacing='0' style='max-width:600px;width:100%;background:#fff;"
            + "border-radius:14px;overflow:hidden;box-shadow:0 4px 20px rgba(0,0,0,.08);'>"

            // Header
            + "<tr><td style='background:linear-gradient(135deg,#673147 0%,#4f2538 100%);padding:32px 36px;text-align:center;'>"
            + "<div style='font-size:13px;font-weight:600;color:rgba(255,255,255,.75);letter-spacing:1px;"
            + "text-transform:uppercase;margin-bottom:6px;'>" + esc(senderName) + "</div>"
            + "<div style='font-size:26px;font-weight:800;color:#fff;line-height:1.2;'>🙏 Thank You!</div>"
            + "<div style='margin-top:10px;display:inline-block;padding:5px 16px;"
            + "background:rgba(255,255,255,.18);border:1px solid rgba(255,255,255,.35);"
            + "border-radius:20px;font-size:13px;color:#fff;font-weight:600;'>Donation Received ✅</div>"
            + "</td></tr>"

            // Greeting
            + "<tr><td style='padding:28px 36px 0;'>"
            + "<p style='font-size:17px;font-weight:600;color:#333;margin:0 0 10px;'>Dear " + esc(donorFirst) + ",</p>"
            + "<p style='font-size:14px;color:#555;line-height:1.8;margin:0;'>"
            + "Thank you for your generous donation and support to <strong>" + esc(senderName) + "</strong>. "
            + "Your contribution helps us continue our ministry, outreach, and community services. "
            + "We are deeply grateful for your kindness and generosity."
            + "</p></td></tr>"

            // Donation summary card
            + "<tr><td style='padding:24px 36px 0;'>"
            + "<div style='background:#fdf5f7;border-radius:12px;padding:22px 24px;border:1px solid #e8d5de;'>"
            + "<div style='font-size:13px;font-weight:700;text-transform:uppercase;letter-spacing:.6px;"
            + "color:#673147;margin-bottom:16px;'>💳 Donation Summary</div>"
            + "<table width='100%' cellpadding='0' cellspacing='0'>"

            // Amount row — large highlighted
            + "<tr><td style='padding:6px 0;'>"
            + "<div style='font-size:13px;font-weight:600;color:#888;margin-bottom:4px;'>Donation Amount</div>"
            + "<div style='font-size:32px;font-weight:800;color:#673147;'>" + amountStr + "</div>"
            + "</td></tr>"

            + "<tr><td style='padding:8px 0 0;border-top:1px solid #e8d5de;margin-top:12px;'>"
            + "<table width='100%' cellpadding='0' cellspacing='0'>"
            + (donationDate.isBlank() ? "" :
               "<tr><td style='padding:5px 0;font-size:13px;font-weight:600;color:#888;width:140px;'>Donation Date</td>"
               + "<td style='padding:5px 0;font-size:13px;color:#333;font-weight:600;'>" + esc(donationDate) + "</td></tr>")
            + (donation.getPaymentMethod() != null && !donation.getPaymentMethod().isBlank() ?
               "<tr><td style='padding:5px 0;font-size:13px;font-weight:600;color:#888;'>Payment Method</td>"
               + "<td style='padding:5px 0;font-size:13px;color:#333;'>" + esc(donation.getPaymentMethod()) + "</td></tr>" : "")
            + (eventName != null && !eventName.isBlank() ?
               "<tr><td style='padding:5px 0;font-size:13px;font-weight:600;color:#888;'>Designated To</td>"
               + "<td style='padding:5px 0;font-size:13px;color:#333;'>" + esc(eventName) + "</td></tr>" : "")
            + "</table></td></tr>"
            + "</table></div></td></tr>"

            // 501(c)(3) notice
            + "<tr><td style='padding:20px 36px 0;'>"
            + "<div style='background:#fff8e8;border:1px solid #f59e0b;border-radius:10px;padding:16px 20px;'>"
            + "<table width='100%' cellpadding='0' cellspacing='0'><tr>"
            + "<td width='36' valign='top' style='font-size:22px;padding-right:12px;'>📋</td>"
            + "<td valign='top'>"
            + "<div style='font-size:13px;font-weight:700;color:#92400e;margin-bottom:5px;'>"
            + "501(c)(3) Tax-Exempt Organization</div>"
            + "<div style='font-size:13px;color:#78350f;line-height:1.7;'>"
            + "<strong>" + esc(senderName) + "</strong> is a registered 501(c)(3) nonprofit organization. "
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
            + "<strong style='font-style:normal;color:#673147;'>" + esc(senderName) + "</strong>"
            + (senderEmail.isBlank() ? "" : "<br/><a href='mailto:" + esc(senderEmail) + "' style='color:#673147;"
               + "text-decoration:none;'>" + esc(senderEmail) + "</a>")
            + (senderPhone.isBlank() ? "" : "<br/>" + esc(senderPhone))
            + "</div></div></td></tr>"

            // Closing
            + "<tr><td style='padding:24px 36px;text-align:center;'>"
            + "<p style='font-size:14px;color:#555;line-height:1.8;margin:0 0 6px;'>"
            + "Thank you again for supporting our mission and ministry.</p>"
            + "<p style='font-size:15px;font-weight:600;color:#673147;margin:0;'>God bless you, 🙏</p>"
            + "<p style='font-size:14px;color:#777;margin:4px 0 0;'>" + esc(senderName) + " Team</p>"
            + "</td></tr>"

            // Footer
            + "<tr><td style='background:#fdf5f7;padding:16px 36px;text-align:center;border-top:1px solid #f0e8ec;'>"
            + "<div style='font-size:11px;color:#aaa;'>This receipt was sent by " + esc(senderName)
            + " via Church Genius Pro. Please keep this email for your records.</div>"
            + "</td></tr>"

            + "</table></td></tr></table>"
            + "</body></html>";

        emailService.sendOrgEmail(donation.getEmail(), subject, html, clientId);
    }

    // ── Public: submit RSVP (body: "cid" encrypted or "clientId" plain) ──────

    @ResponseBody
    @PostMapping("/api/mid-reg-meet/public/rsvp")
    public ResponseEntity<?> submitRsvp(@RequestBody Map<String, Object> body) {
        String cid      = (String) body.get("cid");
        String rawId    = (String) body.get("clientId");
        String clientId = resolveClientId(cid, rawId);
        if (clientId == null) return ResponseEntity.badRequest().body(Map.of("error", "Missing clientId or cid"));

        MidRegMeetRsvp rsvp = new MidRegMeetRsvp();
        fillRsvpFromBody(rsvp, body, clientId);
        rsvpRepo.save(rsvp);

        // Build the encrypted cid for the edit link (encrypt if only plain clientId was supplied)
        String encryptedCid = cid;
        if ((encryptedCid == null || encryptedCid.isBlank()) && clientId != null) {
            try { encryptedCid = EncryptionUtil.encrypt(clientId); } catch (Exception ignored) {}
        }
        try { sendRsvpConfirmation(rsvp, clientId, encryptedCid); } catch (Exception ignored) {}
        try { notifyAdmins(rsvp, clientId); } catch (Exception ignored) {}

        return ResponseEntity.ok(Map.of("success", true, "editToken", rsvp.getEditToken()));
    }

    // ── Public: fetch RSVP by edit token ─────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/mid-reg-meet/public/rsvp/edit/{token}")
    public ResponseEntity<?> getRsvpByToken(@PathVariable String token) {
        return rsvpRepo.findByEditToken(token)
                .map(r -> ResponseEntity.ok(toRsvpMap(r)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Public: update RSVP by edit token ────────────────────────────────────

    @ResponseBody
    @PutMapping("/api/mid-reg-meet/public/rsvp/edit/{token}")
    public ResponseEntity<?> updateRsvpByToken(@PathVariable String token,
                                               @RequestBody Map<String, Object> body) {
        return rsvpRepo.findByEditToken(token).map(rsvp -> {
            fillRsvpFromBody(rsvp, body, rsvp.getClientId());
            rsvpRepo.save(rsvp);
            return ResponseEntity.ok((Object) Map.of("success", true));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolves the plaintext clientId from a public request.
     * Accepts either:
     * <ul>
     *   <li>{@code cid} — AES-encrypted, URL-safe Base64 (preferred, generated by Public Screens)</li>
     *   <li>{@code clientId} — plain text (direct / admin test access)</li>
     * </ul>
     * Returns {@code null} if both are absent or decryption fails.
     */
    private String resolveClientId(String cid, String clientId) {
        if (cid != null && !cid.isBlank()) {
            try { return EncryptionUtil.decrypt(cid); } catch (Exception ignored) {}
        }
        if (clientId != null && !clientId.isBlank()) return clientId;
        return null;
    }

    /** Convenience overload: only cid (no plain fallback). */
    private String decryptClientId(String cid) {
        return resolveClientId(cid, null);
    }

    private void applyConfigBody(MidRegMeet meet, Map<String, Object> b) {
        if (b.containsKey("eventName"))    meet.setEventName((String) b.get("eventName"));
        if (b.containsKey("eventAddress")) meet.setEventAddress((String) b.get("eventAddress"));
        if (b.containsKey("eventDays"))    meet.setEventDays(toJson(b.get("eventDays")));
        if (b.containsKey("registrationEndDate") && b.get("registrationEndDate") != null) {
            try {
                meet.setRegistrationEndDate(
                        new java.text.SimpleDateFormat("yyyy-MM-dd").parse((String) b.get("registrationEndDate")));
            } catch (Exception ignored) {}
        } else if (b.containsKey("registrationEndDate")) {
            meet.setRegistrationEndDate(null);
        }
        if (b.containsKey("senderName"))  meet.setSenderName((String) b.get("senderName"));
        if (b.containsKey("senderEmail")) meet.setSenderEmail((String) b.get("senderEmail"));
        if (b.containsKey("senderPhone")) meet.setSenderPhone((String) b.get("senderPhone"));
        if (b.containsKey("note"))        meet.setNote((String) b.get("note"));
        if (b.containsKey("includeTransactionFee")) {
            Object v = b.get("includeTransactionFee");
            meet.setIncludeTransactionFee(Boolean.TRUE.equals(v) || "true".equals(String.valueOf(v)));
        }
    }

    private void fillRsvpFromBody(MidRegMeetRsvp rsvp, Map<String, Object> b, String clientId) {
        rsvp.setClientId(clientId);
        if (b.containsKey("firstName"))           rsvp.setFirstName((String) b.get("firstName"));
        if (b.containsKey("lastName"))            rsvp.setLastName((String) b.get("lastName"));
        if (b.containsKey("church"))              rsvp.setChurch((String) b.get("church"));
        if (b.containsKey("email"))               rsvp.setEmail((String) b.get("email"));
        if (b.containsKey("phone"))               rsvp.setPhone((String) b.get("phone"));
        if (b.containsKey("adults"))              rsvp.setAdults(toInt(b.get("adults")));
        if (b.containsKey("children"))            rsvp.setChildren(toInt(b.get("children")));
        if (b.containsKey("rsvpSunday"))          rsvp.setRsvpSunday((String) b.get("rsvpSunday"));
        if (b.containsKey("rsvpSaturdayMission")) rsvp.setRsvpSaturdayMission((String) b.get("rsvpSaturdayMission"));
        if (b.containsKey("rsvpSaturdayMusic"))   rsvp.setRsvpSaturdayMusic((String) b.get("rsvpSaturdayMusic"));
        if (b.containsKey("musicOptions"))        rsvp.setMusicOptions(toJson(b.get("musicOptions")));
        if (b.containsKey("tshirtSizes"))         rsvp.setTshirtSizes(toJson(b.get("tshirtSizes")));
        if (b.containsKey("note"))                rsvp.setNote((String) b.get("note"));
    }

    private void sendRsvpConfirmation(MidRegMeetRsvp rsvp, String clientId, String cid) {
        String editLink = baseUrl + "/midRegMeetRsvp?cid=" + URLEncoder.encode(cid, StandardCharsets.UTF_8)
                + "&editToken=" + rsvp.getEditToken();

        // Fetch event config and church info for a rich email
        MidRegMeet meet = meetRepo.findByClientId(clientId).orElse(new MidRegMeet());
        ChurchRegistration church =
                churchRepo.findByClientIdAndDeleteFlagFalse(clientId).orElse(null);

        String churchName  = church != null && church.getChurchName() != null ? church.getChurchName() : "Our Church";
        String churchEmail = church != null && church.getEmail() != null ? church.getEmail() : "";
        String churchPhone = church != null && church.getPhone()  != null ? church.getPhone()  : "";
        String senderName  = meet.getSenderName()  != null ? meet.getSenderName()  : churchName;
        String senderEmail = meet.getSenderEmail() != null ? meet.getSenderEmail() : churchEmail;
        String senderPhone = meet.getSenderPhone() != null ? meet.getSenderPhone() : churchPhone;
        String eventName   = meet.getEventName()    != null ? meet.getEventName()   : "Midwest Region Meet";
        String eventAddr   = meet.getEventAddress() != null ? meet.getEventAddress() : "";

        // Parse event days JSON into RSVP rows
        String[] rsvpValues = {
            rsvp.getRsvpSunday(),
            rsvp.getRsvpSaturdayMission(),
            rsvp.getRsvpSaturdayMusic()
        };
        List<Map<String, Object>> days = new ArrayList<>();
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            days = mapper.readValue(meet.getEventDays() != null ? meet.getEventDays() : "[]",
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception ignored) {}

        // Build RSVP rows HTML
        StringBuilder rsvpRows = new StringBuilder();
        for (int i = 0; i < days.size() && i < rsvpValues.length; i++) {
            Map<String, Object> day = days.get(i);
            String dayName  = day.getOrDefault("dayName",  "").toString();
            String date     = day.getOrDefault("date",     "").toString();
            String start    = day.getOrDefault("startTime","").toString();
            String end      = day.getOrDefault("endTime",  "").toString();
            String response = rsvpValues[i] != null ? rsvpValues[i] : "—";
            String formattedDate = formatEmailDate(date, start, end);
            String emoji = "Yes".equalsIgnoreCase(response) ? "✅"
                         : "No".equalsIgnoreCase(response)  ? "❌"
                         : "Maybe".equalsIgnoreCase(response) ? "🤔" : "—";
            String badgeColor = "Yes".equalsIgnoreCase(response) ? "#2e7d32;background:#e8f5e9"
                              : "No".equalsIgnoreCase(response)  ? "#c62828;background:#ffebee"
                              : "#e65100;background:#fff3e0";
            rsvpRows.append("<tr>")
                .append("<td style='padding:12px 16px;border-bottom:1px solid #f0f0f0;font-weight:600;color:#333;'>")
                .append(esc(dayName.isBlank() ? "Day " + (i+1) : dayName)).append("</td>")
                .append("<td style='padding:12px 16px;border-bottom:1px solid #f0f0f0;color:#555;font-size:13px;'>")
                .append(esc(formattedDate)).append("</td>")
                .append("<td style='padding:12px 16px;border-bottom:1px solid #f0f0f0;'>")
                .append("<span style='display:inline-block;padding:4px 12px;border-radius:20px;font-size:13px;font-weight:700;color:")
                .append(badgeColor).append(";'>").append(emoji).append(" ").append(esc(response)).append("</span>")
                .append("</td></tr>");
        }

        // T-shirt section
        StringBuilder tshirtSection = new StringBuilder();
        if (rsvp.getTshirtSizes() != null && !rsvp.getTshirtSizes().isBlank()
                && !rsvp.getTshirtSizes().equals("{}") && !rsvp.getTshirtSizes().equals("null")) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Integer> sizes = mapper.readValue(rsvp.getTshirtSizes(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Integer>>() {});
                boolean hasAny = sizes.values().stream().anyMatch(v -> v != null && v > 0);
                if (hasAny) {
                    tshirtSection.append("<div style='margin:24px 0;padding:20px 24px;")
                        .append("background:#f8f4f6;border-left:4px solid #673147;border-radius:0 10px 10px 0;'>")
                        .append("<div style='font-size:15px;font-weight:700;color:#673147;margin-bottom:10px;'>")
                        .append("👕 T-Shirt for Mission Work</div>")
                        .append("<p style='margin:0 0 10px;color:#555;font-size:14px;'>")
                        .append("We have received your T-shirt request for Mission Work. Here are your selections:</p>")
                        .append("<table style='border-collapse:collapse;width:100%;max-width:320px;'>")
                        .append("<tr style='background:#673147;color:#fff;'>")
                        .append("<th style='padding:8px 16px;text-align:left;font-size:13px;border-radius:6px 0 0 0;'>Size</th>")
                        .append("<th style='padding:8px 16px;text-align:center;font-size:13px;border-radius:0 6px 0 0;'>Qty</th></tr>");
                    String[] sizeOrder = {"XS","S","M","L","XL"};
                    for (String sz : sizeOrder) {
                        Integer qty = sizes.get(sz);
                        if (qty != null && qty > 0) {
                            tshirtSection.append("<tr>")
                                .append("<td style='padding:8px 16px;border-bottom:1px solid #e8d5de;font-weight:600;'>").append(sz).append("</td>")
                                .append("<td style='padding:8px 16px;border-bottom:1px solid #e8d5de;text-align:center;'>").append(qty).append("</td>")
                                .append("</tr>");
                        }
                    }
                    tshirtSection.append("</table></div>");
                }
            } catch (Exception ignored) {}
        }

        // Flyer image as base64 (inline, so no external URL needed)
        String flyerImgHtml = "";
        if (meet.getFlyerImage() != null && meet.getFlyerImage().length > 0) {
            String b64 = java.util.Base64.getEncoder().encodeToString(meet.getFlyerImage());
            flyerImgHtml = "<div style='text-align:center;margin:24px 0;'>"
                + "<img src='data:image/jpeg;base64," + b64 + "' alt='Event Flyer' "
                + "style='max-width:100%;max-height:400px;border-radius:12px;box-shadow:0 4px 16px rgba(0,0,0,.15);'/>"
                + "</div>";
        }

        // Full HTML email
        String html =
            "<!DOCTYPE html><html><head><meta charset='UTF-8'/>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'/></head>"
            + "<body style='margin:0;padding:0;background:#f5f5f5;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;'>"
            + "<table width='100%' cellpadding='0' cellspacing='0' style='background:#f5f5f5;padding:24px 16px;'><tr><td align='center'>"
            + "<table width='600' cellpadding='0' cellspacing='0' style='max-width:600px;width:100%;background:#fff;border-radius:14px;overflow:hidden;box-shadow:0 4px 20px rgba(0,0,0,.08);'>"

            // Header banner
            + "<tr><td style='background:linear-gradient(135deg,#673147 0%,#4f2538 100%);padding:32px 36px;text-align:center;'>"
            + "<div style='font-size:13px;font-weight:600;color:rgba(255,255,255,.75);letter-spacing:1px;text-transform:uppercase;margin-bottom:6px;'>"
            + esc(senderName) + "</div>"
            + "<div style='font-size:26px;font-weight:800;color:#fff;line-height:1.2;'>" + esc(eventName) + "</div>"
            + "<div style='margin-top:10px;display:inline-block;padding:5px 16px;background:rgba(255,255,255,.18);"
            + "border:1px solid rgba(255,255,255,.35);border-radius:20px;font-size:13px;color:#fff;font-weight:600;'>"
            + "✅ RSVP Confirmed</div>"
            + "</td></tr>"

            // Flyer image (if available)
            + (flyerImgHtml.isBlank() ? "" : "<tr><td style='padding:24px 36px 0;'>" + flyerImgHtml + "</td></tr>")

            // Welcome message
            + "<tr><td style='padding:28px 36px 0;'>"
            + "<p style='font-size:17px;font-weight:600;color:#333;margin:0 0 8px;'>Hi " + esc(rsvp.getFirstName()) + " 👋</p>"
            + "<p style='font-size:14px;color:#555;line-height:1.7;margin:0;'>"
            + "Thank you for registering! We're excited to have you at the <strong>" + esc(eventName) + "</strong>. "
            + "Your RSVP has been received and is confirmed. Please find your registration details below."
            + "</p></td></tr>"

            // Event details card
            + "<tr><td style='padding:20px 36px 0;'>"
            + "<div style='background:#fdf5f7;border-radius:10px;padding:18px 20px;border:1px solid #e8d5de;'>"
            + "<div style='font-size:13px;font-weight:700;text-transform:uppercase;letter-spacing:.6px;color:#673147;margin-bottom:12px;'>📍 Event Details</div>"
            + "<table width='100%' cellpadding='0' cellspacing='0'>"
            + "<tr><td style='padding:4px 0;font-size:13px;font-weight:600;color:#888;width:90px;'>Event</td>"
            + "<td style='padding:4px 0;font-size:14px;color:#333;font-weight:600;'>" + esc(eventName) + "</td></tr>"
            + (eventAddr.isBlank() ? "" :
               "<tr><td style='padding:4px 0;font-size:13px;font-weight:600;color:#888;vertical-align:top;'>Venue</td>"
               + "<td style='padding:4px 0;font-size:14px;color:#333;'>" + esc(eventAddr) + "</td></tr>")
            + "</table></div></td></tr>"

            // RSVP summary table
            + "<tr><td style='padding:20px 36px 0;'>"
            + "<div style='font-size:13px;font-weight:700;text-transform:uppercase;letter-spacing:.6px;color:#673147;margin-bottom:12px;'>📋 Your RSVP Summary</div>"
            + "<table width='100%' cellpadding='0' cellspacing='0' style='border:1px solid #e8d5de;border-radius:10px;overflow:hidden;'>"
            + "<tr style='background:#673147;'>"
            + "<th style='padding:11px 16px;text-align:left;font-size:12px;color:#fff;font-weight:600;letter-spacing:.4px;'>Event / Session</th>"
            + "<th style='padding:11px 16px;text-align:left;font-size:12px;color:#fff;font-weight:600;letter-spacing:.4px;'>Date &amp; Time</th>"
            + "<th style='padding:11px 16px;text-align:left;font-size:12px;color:#fff;font-weight:600;letter-spacing:.4px;'>Response</th>"
            + "</tr>"
            + rsvpRows
            + "</table></td></tr>"

            // T-shirt section (if applicable)
            + (tshirtSection.length() == 0 ? "" : "<tr><td style='padding:4px 36px 0;'>" + tshirtSection + "</td></tr>")

            // Edit link
            + "<tr><td style='padding:24px 36px;text-align:center;'>"
            + "<a href='" + editLink + "' style='display:inline-block;padding:12px 28px;background:#673147;"
            + "color:#fff;border-radius:8px;font-size:14px;font-weight:700;text-decoration:none;'>✏️ Update My RSVP</a>"
            + "</td></tr>"

            // Contact section
            + "<tr><td style='padding:0 36px 28px;border-top:1px solid #f0f0f0;'>"
            + "<div style='padding-top:20px;'>"
            + "<div style='font-size:12px;font-weight:700;text-transform:uppercase;letter-spacing:.6px;color:#888;margin-bottom:8px;'>For Questions, Contact:</div>"
            + "<div style='font-size:14px;color:#555;font-style:italic;line-height:1.8;'>"
            + esc(senderName)
            + (senderEmail.isBlank() ? "" : "<br/><a href='mailto:" + esc(senderEmail) + "' style='color:#673147;text-decoration:none;'>" + esc(senderEmail) + "</a>")
            + (senderPhone.isBlank() ? "" : "<br/>" + esc(senderPhone))
            + "</div></div></td></tr>"

            // Footer
            + "<tr><td style='background:#fdf5f7;padding:16px 36px;text-align:center;'>"
            + "<div style='font-size:11px;color:#aaa;'>This email was sent by " + esc(senderName)
            + " via Church Genius Pro. You are receiving this because you submitted an RSVP.</div>"
            + "</td></tr>"

            + "</table></td></tr></table>"
            + "</body></html>";

        if (rsvp.getEmail() != null && !rsvp.getEmail().isBlank()) {
            String subject = "Your RSVP Confirmation — " + eventName;
            emailService.sendOrgEmail(rsvp.getEmail(), subject, html, clientId);
        }

        if (rsvp.getPhone() != null && !rsvp.getPhone().isBlank()) {
            smsService.send(rsvp.getPhone(),
                    "Hi " + rsvp.getFirstName() + "! Your " + eventName + " RSVP is confirmed. "
                    + "Edit: " + editLink + " Reply STOP to opt out.");
        }
    }

    /** Format a date+time range like "Saturday, September 26, 2026 @ 9:00 AM – 12:00 PM" */
    private String formatEmailDate(String date, String startTime, String endTime) {
        if (date == null || date.isBlank()) return "";
        try {
            java.time.LocalDate d = java.time.LocalDate.parse(date);
            String dayOfWeek = d.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH);
            String month     = d.getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH);
            String formatted = dayOfWeek + ", " + month + " " + d.getDayOfMonth() + ", " + d.getYear();
            if (startTime != null && !startTime.isBlank()) {
                formatted += " @ " + fmt12h(startTime);
                if (endTime != null && !endTime.isBlank()) {
                    formatted += " – " + fmt12h(endTime);
                }
            }
            return formatted;
        } catch (Exception e) {
            return date + (startTime != null && !startTime.isBlank() ? " @ " + startTime : "");
        }
    }

    private String fmt12h(String time24) {
        try {
            java.time.LocalTime t = java.time.LocalTime.parse(time24);
            return t.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH));
        } catch (Exception e) { return time24; }
    }

    private void notifyAdmins(MidRegMeetRsvp rsvp, String clientId) {
        List<AppUser> admins = userRepo.findAdminsByClientId(clientId);
        String subject = "New RSVP: " + esc(rsvp.getFirstName()) + " " + esc(rsvp.getLastName())
                + " — Midwest Region Meet";
        String html = "<p>A new RSVP was submitted.</p><ul>"
                + "<li><b>Name:</b> " + esc(rsvp.getFirstName()) + " " + esc(rsvp.getLastName()) + "</li>"
                + "<li><b>Church:</b> " + esc(rsvp.getChurch()) + "</li>"
                + "<li><b>Email:</b> " + esc(rsvp.getEmail()) + "</li>"
                + "<li><b>Phone:</b> " + esc(PhoneUtil.format(rsvp.getPhone())) + "</li>"
                + "<li><b>Adults:</b> " + rsvp.getAdults() + " | <b>Children:</b> " + rsvp.getChildren() + "</li>"
                + "<li><b>Sunday:</b> " + esc(rsvp.getRsvpSunday()) + "</li>"
                + "<li><b>Saturday Mission:</b> " + esc(rsvp.getRsvpSaturdayMission()) + "</li>"
                + "<li><b>Saturday Music:</b> " + esc(rsvp.getRsvpSaturdayMusic()) + "</li>"
                + "</ul><p><a href=\"" + baseUrl + "/midRegMeet\">View all participants →</a></p>";
        for (AppUser admin : admins) {
            if (admin.getEmail() != null && !admin.getEmail().isBlank()) {
                emailService.sendOrgEmail(admin.getEmail(), subject, html, clientId);
            }
        }
    }

    private Map<String, Object> toConfigMap(MidRegMeet m, boolean publicView) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("eventName",    m.getEventName());
        map.put("eventAddress", m.getEventAddress());
        map.put("eventDays",    m.getEventDays());
        map.put("note",         m.getNote());
        map.put("hasTshirtImage",          m.getTshirtImage() != null && m.getTshirtImage().length > 0);
        map.put("hasFlyerImage",           m.getFlyerImage()  != null && m.getFlyerImage().length  > 0);
        map.put("includeTransactionFee",   m.isIncludeTransactionFee());
        if (m.getRegistrationEndDate() != null) {
            map.put("registrationEndDate",
                    new java.text.SimpleDateFormat("yyyy-MM-dd").format(m.getRegistrationEndDate()));
        } else {
            map.put("registrationEndDate", null);
        }
        if (!publicView) {
            map.put("senderName",  m.getSenderName());
            map.put("senderEmail", m.getSenderEmail());
            map.put("senderPhone", m.getSenderPhone());
        }
        return map;
    }

    private Map<String, Object> toRsvpMap(MidRegMeetRsvp r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                  r.getId());
        m.put("firstName",           r.getFirstName());
        m.put("lastName",            r.getLastName());
        m.put("church",              r.getChurch());
        m.put("email",               r.getEmail());
        m.put("phone",               r.getPhone());
        m.put("adults",              r.getAdults());
        m.put("children",            r.getChildren());
        m.put("rsvpSunday",          r.getRsvpSunday());
        m.put("rsvpSaturdayMission", r.getRsvpSaturdayMission());
        m.put("rsvpSaturdayMusic",   r.getRsvpSaturdayMusic());
        m.put("musicOptions",        r.getMusicOptions());
        m.put("tshirtSizes",         r.getTshirtSizes());
        m.put("note",                r.getNote());
        m.put("editToken",           r.getEditToken());
        m.put("createdAt",           r.getCreatedAt());
        return m;
    }

    // ── Stripe REST helpers ───────────────────────────────────────────────────

    private Map<String, Object> stripeCreateIntent(String secretKey, long cents, String currency) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + secretKey);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("amount",   String.valueOf(cents));
        params.add("currency", currency);
        params.add("automatic_payment_methods[enabled]", "true");
        try {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    STRIPE_API_BASE + "/payment_intents", HttpMethod.POST,
                    new HttpEntity<>(params, headers), MAP_TYPE);
            return resp.getBody() != null ? resp.getBody() : Map.of();
        } catch (HttpClientErrorException ex) {
            return Map.of("error", Map.of("message", ex.getStatusText()));
        }
    }

    private Map<String, Object> stripeRetrieveIntent(String secretKey, String piId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + secretKey);
        try {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    STRIPE_API_BASE + "/payment_intents/" + piId + "?expand[]=latest_charge",
                    HttpMethod.GET, new HttpEntity<>(headers), MAP_TYPE);
            return resp.getBody() != null ? resp.getBody() : Map.of();
        } catch (HttpClientErrorException ex) {
            return Map.of("error", Map.of("message", ex.getStatusText()));
        }
    }

    private static String stripeErrorMsg(Map<String, Object> pi) {
        Object err = pi.get("error");
        if (err instanceof Map<?, ?> e && e.get("message") != null) return e.get("message").toString();
        return "A payment error occurred.";
    }

    private static String strObj(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private Integer toInt(Object v) {
        if (v == null) return null;
        if (v instanceof Integer) return (Integer) v;
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private String toJson(Object v) {
        if (v == null) return null;
        if (v instanceof String) return (String) v;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(v);
        } catch (Exception e) { return v.toString(); }
    }
}
