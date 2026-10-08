package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.PublicScreenLink;
import com.churchgeniuspro.hibernate.SmsOptIn;
import com.churchgeniuspro.hibernate.StripeSettings;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.PublicScreenLinkRepository;
import com.churchgeniuspro.repository.SmsOptInRepository;
import com.churchgeniuspro.repository.StripeSettingsRepository;
import com.churchgeniuspro.service.SmsService;
import com.churchgeniuspro.util.PublicSendLimiter;
import com.churchgeniuspro.util.SessionUtil;
import com.churchgeniuspro.util.PhoneNumbers;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@Controller
public class SmsOptInController {

    private static final Logger log = LoggerFactory.getLogger(SmsOptInController.class);

    private final SmsOptInRepository            smsOptInRepo;
    private final ChurchRegistrationRepository  churchRepo;
    private final FamilyMemberRepository        familyMemberRepo;
    private final PublicScreenLinkRepository    linkRepo;
    private final StripeSettingsRepository      stripeRepo;
    private final SmsService                    smsService;

    @Value("${app.base-url:http://localhost:8080}")
    private String appBaseUrl;

    /** Twilio auth token — the HMAC key for X-Twilio-Signature. Blank = SMS not configured. */
    @Value("${twilio.auth-token:}")
    private String twilioAuthToken;

    /** Twilio account SID; an inbound request from any other account is refused. */
    @Value("${twilio.account-sid:}")
    private String twilioAccountSid;

    private final com.churchgeniuspro.service.PublicLinkResolver links;
    private final PublicSendLimiter sendLimiter;

    public SmsOptInController(SmsOptInRepository smsOptInRepo,
                               ChurchRegistrationRepository churchRepo,
                               FamilyMemberRepository familyMemberRepo,
                               PublicScreenLinkRepository linkRepo,
                               StripeSettingsRepository stripeRepo,
                               SmsService smsService,
            com.churchgeniuspro.service.PublicLinkResolver links,
            PublicSendLimiter sendLimiter) {
        this.links = links;
        this.sendLimiter = sendLimiter;
        this.smsOptInRepo     = smsOptInRepo;
        this.churchRepo       = churchRepo;
        this.familyMemberRepo = familyMemberRepo;
        this.linkRepo         = linkRepo;
        this.stripeRepo       = stripeRepo;
        this.smsService       = smsService;
    }

    // ── Page route (public) ───────────────────────────────────────────────────

    @GetMapping("/smsOptIn")
    public String smsOptInPage() {
        return "forward:/smsOptIn.html";
    }

    // ── Resolve church name from token (public) ───────────────────────────────

    @ResponseBody
    @GetMapping("/api/public/sms-opt-in/church-name")
    public ResponseEntity<Map<String, Object>> getChurchName(@RequestParam String token) {
        Map<String, Object> res = new HashMap<>();
        try {
            String appClientId = decryptToken(token);
            if (appClientId == null) {
                res.put("found", false);
                return ResponseEntity.ok(res);
            }
            ChurchRegistration church = churchRepo
                    .findByClientIdAndDeleteFlagFalse(appClientId).orElse(null);
            res.put("found",      church != null);
            res.put("churchName", church != null ? church.getChurchName() : "Our Church");
            // The tenant id stayed server-side from here on; the page only needs the name (audit P14).
        } catch (Exception e) {
            res.put("found",      false);
            res.put("churchName", "Our Church");
        }
        return ResponseEntity.ok(res);
    }

    // ── Submit opt-in form (public) ───────────────────────────────────────────

    @ResponseBody
    @PostMapping("/api/public/sms-opt-in")
    public ResponseEntity<Map<String, Object>> submitOptIn(@RequestBody Map<String, Object> body,
                                                           HttpServletRequest request) {
        Map<String, Object> res = new HashMap<>();

        String token      = str(body, "token");
        String firstName  = str(body, "firstName");
        String lastName   = str(body, "lastName");
        String rawPhone   = str(body, "phoneNumber");
        Object consent    = body.get("consent");

        if (isBlank(token) || isBlank(firstName) || isBlank(lastName) || isBlank(rawPhone)) {
            res.put("status",  "error");
            res.put("message", "All fields are required.");
            return ResponseEntity.badRequest().body(res);
        }

        if (!Boolean.TRUE.equals(consent) && !"true".equalsIgnoreCase(String.valueOf(consent))) {
            res.put("status",  "error");
            res.put("message", "You must agree to receive SMS messages to continue.");
            return ResponseEntity.badRequest().body(res);
        }

        String appClientId = decryptToken(token);
        if (appClientId == null) {
            res.put("status",  "error");
            res.put("message", "Invalid or expired link.");
            return ResponseEntity.badRequest().body(res);
        }

        String phone = normalizePhone(rawPhone);
        if (phone == null) {
            res.put("status",  "error");
            res.put("message", "Please enter a valid US phone number (10 digits).");
            return ResponseEntity.badRequest().body(res);
        }

        String churchName = "Our Church";
        ChurchRegistration church = churchRepo
                .findByClientIdAndDeleteFlagFalse(appClientId).orElse(null);
        if (church != null && church.getChurchName() != null) {
            churchName = church.getChurchName();
        }

        SmsOptIn record = smsOptInRepo
                .findByPhoneNumberAndAppClientId(phone, appClientId)
                .orElseGet(SmsOptIn::new);

        // A number that is already a confirmed subscriber has nothing to gain from a
        // re-submission — and used to lose from it: the form reset confirmed=false and
        // rewrote the name, so anyone who knew a subscriber's number could silently
        // drop them off the confirmed list (security audit P2). Leave the record alone
        // and send nothing; the wording is the one a caller gets whenever no text goes
        // out, so it does not reveal who is subscribed.
        if (record.getId() != null && record.isConfirmed() && record.isConsent()) {
            res.put("status",  "success");
            res.put("smsSent", false);
            res.put("message", "Thank you! Your opt-in has been recorded. You will receive a confirmation shortly.");
            return ResponseEntity.ok(res);
        }

        // One text per submission to a number the caller typed: bounded per network
        // origin, per number and per church before anything is written or sent.
        String limited = sendLimiter.check(PublicSendLimiter.SMS_OPT_IN, request, phone, appClientId);
        if (limited != null) {
            res.put("status",  "error");
            res.put("message", limited);
            return ResponseEntity.status(429).body(res);
        }

        record.setAppClientId(appClientId);
        record.setFirstName(firstName.trim());
        record.setLastName(lastName.trim());
        record.setPhoneNumber(phone);
        record.setConsent(true);
        record.setConfirmed(false);
        record.setOptedInAt(LocalDateTime.now());
        record.setOptedOutAt(null);
        smsOptInRepo.save(record);

        // The tenant is known here, so the opt-in text is subject to the same
        // Trial / demo block as everything else this church sends.
        boolean smsSent = smsService.sendConfirmationRequest(phone, churchName, appClientId);

        res.put("status",  "success");
        res.put("smsSent", smsSent);
        res.put("message", smsSent
                ? "Thank you! A confirmation text has been sent to your phone. Please reply YES to confirm."
                : "Thank you! Your opt-in has been recorded. You will receive a confirmation shortly.");
        return ResponseEntity.ok(res);
    }

    // ── Twilio inbound webhook ────────────────────────────────────────────────

    @ResponseBody
    @PostMapping(value = "/webhook/sms",
                 consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
                 produces = MediaType.TEXT_XML_VALUE)
    public String handleInboundSms(@RequestParam(value = "From",       required = false) String from,
                                   @RequestParam(value = "Body",       required = false) String body,
                                   @RequestParam(value = "AccountSid", required = false) String accountSid,
                                   HttpServletRequest request) {

        // Authenticate the caller before anything else. Without this, any HTTP client
        // could POST From=<victim>&Body=STOP (or YES) and rewrite consent for that
        // number in every church, or use GIVE as a phone→church oracle.
        if (!isGenuineTwilioRequest(request, accountSid)) {
            return "<Response/>";
        }

        if (from == null || body == null) return "<Response/>";

        String trimmed = body.trim();
        String keyword  = trimmed.toUpperCase();
        List<SmsOptIn> records = smsOptInRepo.findByPhoneNumber(from);

        // GIVE keyword: anyone can text GIVE (or GIVE <amount>) to get a donation link
        if (keyword.equals("GIVE") || keyword.startsWith("GIVE ")) {
            return handleGiveKeyword(from, trimmed, records);
        }

        if (records.isEmpty()) {
            log.debug("SmsWebhook: no opt-in record for {}", from);
            return "<Response/>";
        }

        for (SmsOptIn record : records) {
            switch (keyword) {
                case "YES" -> {
                    record.setConfirmed(true);
                    record.setConsent(true);
                    record.setConfirmedAt(LocalDateTime.now());
                    smsOptInRepo.save(record);
                    log.info("SmsWebhook: {} confirmed opt-in for church {}", from, record.getAppClientId());
                }
                case "STOP", "STOPALL", "UNSUBSCRIBE", "CANCEL", "END", "QUIT" -> {
                    record.setConsent(false);
                    record.setConfirmed(false);
                    record.setOptedOutAt(LocalDateTime.now());
                    smsOptInRepo.save(record);
                    log.info("SmsWebhook: {} opted out from church {}", from, record.getAppClientId());
                }
                case "START", "UNSTOP" -> {
                    record.setConsent(true);
                    record.setConfirmed(true);
                    record.setOptedInAt(LocalDateTime.now());
                    record.setOptedOutAt(null);
                    smsOptInRepo.save(record);
                    log.info("SmsWebhook: {} re-opted in for church {}", from, record.getAppClientId());
                }
                default -> log.debug("SmsWebhook: unrecognized reply '{}' from {}", body.trim(), from);
            }
        }
        return "<Response/>";
    }

    // ── GIVE keyword handler ──────────────────────────────────────────────────

    /**
     * Handles the GIVE keyword from inbound SMS.
     * Accepted formats: GIVE, GIVE 50, GIVE $50, GIVE 50.00
     *
     * Church lookup is a three-tier chain:
     *   1. sms_opt_in record  — sender has previously opted in via the church link
     *   2. family_member table — sender's phone matches a member record (covers most members)
     *   3. Single-church fallback — only used when this deployment has exactly one church
     */
    private String handleGiveKeyword(String from, String originalBody, List<SmsOptIn> records) {
        // Parse optional amount from "GIVE 50" or "GIVE $50.00"
        String[] parts = originalBody.split("\\s+", 2);
        Double amount  = null;
        if (parts.length == 2) {
            try {
                String raw = parts[1].replaceAll("[^0-9.]", "");
                double v = Double.parseDouble(raw);
                if (v >= 0.50) amount = v;
            } catch (NumberFormatException ignored) { /* treat as no amount */ }
        }

        // Tier 1: sms_opt_in record
        String appClientId = null;
        String churchName  = null;
        if (!records.isEmpty()) {
            appClientId = records.get(0).getAppClientId();
            ChurchRegistration church = churchRepo
                    .findByClientIdAndDeleteFlagFalse(appClientId).orElse(null);
            if (church != null) churchName = church.getChurchName();
        }

        // Tier 2: family_member table — covers members who never went through the SMS opt-in flow.
        // Twilio sends E.164 (+1XXXXXXXXXX); phones in the DB are often stored as 10 digits.
        if (appClientId == null) {
            String digits10 = from.replaceAll("[^0-9]", "");
            if (digits10.length() == 11 && digits10.startsWith("1")) digits10 = digits10.substring(1);

            List<FamilyMember> members = familyMemberRepo.findActiveByPhoneAnyChurch(from);
            if (members.isEmpty()) members = familyMemberRepo.findActiveByPhoneAnyChurch(digits10);

            if (!members.isEmpty() && members.get(0).getFamily() != null) {
                appClientId = members.get(0).getFamily().getAppClientId();
                ChurchRegistration church = churchRepo
                        .findByClientIdAndDeleteFlagFalse(appClientId).orElse(null);
                if (church != null) churchName = church.getChurchName();
                log.info("SmsGive: matched {} to church {} via family_member", from, appClientId);
            }
        }

        // Tier 3: single-church fallback
        if (appClientId == null) {
            List<ChurchRegistration> allChurches = churchRepo.findAllByDeleteFlagFalse();
            if (allChurches.size() == 1) {
                ChurchRegistration church = allChurches.get(0);
                appClientId = church.getClientId();
                churchName  = church.getChurchName();
                log.info("SmsGive: unknown sender {}, single-church fallback to {}", from, appClientId);
            }
        }

        if (appClientId == null) {
            return buildTwiml("To give online, please contact your church for a donation link.");
        }

        // Check Stripe is configured for this church
        StripeSettings stripe = stripeRepo.findByClientId(appClientId).orElse(null);
        if (stripe == null || isBlank(stripe.getPublishableKey())) {
            log.warn("SmsGive: Stripe not configured for church {}", appClientId);
            String label = churchName != null ? churchName : "your church";
            return buildTwiml("Online giving is not set up yet. Please contact " + label + " for more info.");
        }

        // Find active donation PublicScreenLink for this church
        List<PublicScreenLink> links = linkRepo
                .findByAppClientIdAndPageUrlStartingWithAndRevokedFalse(appClientId, "/donate");

        String donationToken = null;
        for (PublicScreenLink link : links) {
            if (link.getExpirationDate() == null ||
                !link.getExpirationDate().isBefore(java.time.LocalDate.now())) {
                donationToken = link.getToken();
                break;
            }
        }

        if (donationToken == null) {
            log.warn("SmsGive: no active donation link for church {}", appClientId);
            String label = churchName != null ? churchName : "your church";
            return buildTwiml("Online giving is available, but the link hasn't been set up yet. Please contact " + label + ".");
        }

        // Build the give URL — amount passed as URL fragment so it never hits the server
        String baseUrl = appBaseUrl.replaceAll("/$", "");
        String url     = baseUrl + "/donate/" + donationToken;
        if (amount != null) url += "#give=" + String.format("%.2f", amount);

        String amountPart = amount != null ? " $" + String.format("%.2f", amount) : "";
        String church     = churchName != null ? churchName : "your church";
        String msg = "Give" + amountPart + " to " + church + ":\n" + url + "\nReply STOP to opt out.";

        log.info("SmsGive: sending donation link to {} for church {} amount={}", from, appClientId, amount);
        return buildTwiml(msg);
    }

    /** Wraps a message in TwiML so Twilio sends it as an SMS reply. */
    private static String buildTwiml(String message) {
        String safe = message
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
        return "<Response><Message>" + safe + "</Message></Response>";
    }

    // ── Admin: list opt-in records (authenticated) ────────────────────────────

    @ResponseBody
    @GetMapping("/api/sms-opt-in")
    public ResponseEntity<List<Map<String, Object>>> listOptIns(HttpServletRequest request) {
        String appClientId = staffClientId(request);
        if (appClientId == null) return ResponseEntity.status(403).build();
        List<SmsOptIn> records = smsOptInRepo.findByAppClientIdOrderByCreatedAtDesc(appClientId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (SmsOptIn r : records) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",          r.getId());
            m.put("firstName",   r.getFirstName());
            m.put("lastName",    r.getLastName());
            m.put("phoneNumber", r.getPhoneNumber());
            m.put("consent",     r.isConsent());
            m.put("confirmed",   r.isConfirmed());
            m.put("optedInAt",   r.getOptedInAt()   != null ? r.getOptedInAt().toString()   : null);
            m.put("confirmedAt", r.getConfirmedAt() != null ? r.getConfirmedAt().toString() : null);
            m.put("optedOutAt",  r.getOptedOutAt()  != null ? r.getOptedOutAt().toString()  : null);
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    @ResponseBody
    @DeleteMapping("/api/sms-opt-in/{id}")
    public ResponseEntity<Map<String, Object>> deleteOptIn(@PathVariable Integer id,
                                                            HttpServletRequest request) {
        Map<String, Object> res = new LinkedHashMap<>();
        String appClientId = staffClientId(request);
        if (appClientId == null) { res.put("error", "Access denied."); return ResponseEntity.status(403).body(res); }
        SmsOptIn record = smsOptInRepo.findById(id).orElse(null);
        if (record == null || !appClientId.equals(record.getAppClientId())) {
            res.put("error", "Record not found.");
            return ResponseEntity.status(404).body(res);
        }
        smsOptInRepo.delete(record);
        res.put("success", true);
        return ResponseEntity.ok(res);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String decryptToken(String token) {
        // The link token names one PublicScreenLink row; nothing is decrypted.
        return links.resolveClientId(token, com.churchgeniuspro.service.PublicPagePolicy.SMS_OPT_IN_URL);
    }

    /**
     * Delegates to {@link PhoneNumbers#toE164}. The behaviour is the same for every number
     * this previously accepted, and it additionally handles international numbers and
     * rejects ten-digit strings that cannot be dialled (a leading 0 or 1 on the area code
     * or exchange), which used to be stored as opt-in records no message could reach.
     */
    static String normalizePhone(String raw) {
        return PhoneNumbers.toE164(raw);
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ── Auth helpers ─────────────────────────────────────────────────────────

    /**
     * Staff caller's tenant for the admin listing, or null. This path used to sit on
     * AuthFilter's whitelist (the comment said "public SMS opt-in form", but the form
     * is under /api/public/sms-opt-in) and resolved the tenant from any session —
     * including the publicView session a QR-code visitor gets.
     */
    private static String staffClientId(HttpServletRequest request) {
        if (com.churchgeniuspro.util.RoleGuard.requireAdminOrUser(request) != null) return null;
        String cid = com.churchgeniuspro.util.RoleGuard.clientId(request);
        return cid == null || cid.isBlank() ? null : cid;
    }

    /**
     * Validates {@code X-Twilio-Signature} (HMAC-SHA1 over the public URL plus the
     * sorted POST parameters, keyed with the auth token) and the account SID.
     *
     * <p>The URL Twilio signed is the one configured in the Twilio console — the
     * public one. Behind Azure's proxy {@code request.getRequestURL()} may report an
     * internal scheme/host, so the canonical candidate is built from
     * {@code app.base-url}; the raw request URL is tried second for local setups.
     * Fails closed when SMS is not configured.
     */
    private boolean isGenuineTwilioRequest(HttpServletRequest request, String accountSid) {
        if (twilioAuthToken == null || twilioAuthToken.isBlank()) {
            log.warn("SmsWebhook: refused — twilio.auth-token not configured");
            return false;
        }
        if (twilioAccountSid != null && !twilioAccountSid.isBlank()
                && (accountSid == null || !twilioAccountSid.equals(accountSid))) {
            log.warn("SmsWebhook: refused — AccountSid mismatch");
            return false;
        }
        String signature = request.getHeader("X-Twilio-Signature");
        if (signature == null || signature.isBlank()) {
            log.warn("SmsWebhook: refused — missing X-Twilio-Signature");
            return false;
        }

        Map<String, String> params = new HashMap<>();
        request.getParameterMap().forEach((k, v) -> params.put(k, v != null && v.length > 0 ? v[0] : ""));

        String query = request.getQueryString();
        String suffix = request.getRequestURI() + (query != null && !query.isBlank() ? "?" + query : "");
        String canonical = appBaseUrl.replaceAll("/$", "") + suffix;
        String raw = request.getRequestURL().toString() + (query != null && !query.isBlank() ? "?" + query : "");

        com.twilio.security.RequestValidator validator = new com.twilio.security.RequestValidator(twilioAuthToken);
        boolean ok = validator.validate(canonical, params, signature)
                  || (!raw.equals(canonical) && validator.validate(raw, params, signature));
        if (!ok) log.warn("SmsWebhook: refused — signature did not verify for {}", canonical);
        return ok;
    }
}
