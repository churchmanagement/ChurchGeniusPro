package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.PublicScreenLink;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.PublicScreenLinkRepository;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.VerificationStore;
import com.churchgeniuspro.util.PasswordUtil;
import com.churchgeniuspro.util.PublicSendLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles the public Member Signup flow — allows existing family members to
 * create their own login credentials without admin involvement.
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>{@code GET  /memberSignup?token=<pubToken>}        — page forward</li>
 *   <li>{@code GET  /api/member-signup/validate?token=...} — decrypt token → org info</li>
 *   <li>{@code POST /api/member-signup/lookup}             — last name + email/phone → find member</li>
 *   <li>{@code POST /api/member-signup/send-code}          — validate credentials + send OTP</li>
 *   <li>{@code POST /api/member-signup/verify}             — verify OTP → persist SignUp</li>
 * </ol>
 *
 * <h3>SignUp record structure</h3>
 * <ul>
 *   <li>{@code client_id} = matched member's {@code member_ref} (MBR...)</li>
 *   <li>{@code church}    = false</li>
 *   <li>{@code church_id} = signup.id of the family's Head of Household (if already registered)</li>
 * </ul>
 */
@Controller
public class MemberSignupController {

    private static final Logger log = LoggerFactory.getLogger(MemberSignupController.class);

    /** OTP type key used in VerificationStore. */
    private static final String OTP_TYPE = "member-signup";

    /** How long a lookup handle (step 2 → steps 3/4) stays valid. */
    private static final long HANDLE_TTL_MS = 30 * 60_000L;

    /**
     * Opaque one-time handle issued by {@link #lookup} in place of the member's
     * {@code memberRef}. The page (memberSignup.html) simply carries the value it
     * received back under the {@code memberRef} key, so the wire format is unchanged
     * while the real MBR reference — which is also the SignUp {@code client_id} —
     * never leaves the server. In-memory (single-instance deployment, same as
     * PublicFormGuard); a restart just makes the applicant start over.
     */
    private record LookupHandle(String memberRef, String appClientId, long issuedAt) {
        boolean expired() { return System.currentTimeMillis() - issuedAt > HANDLE_TTL_MS; }
    }
    private final ConcurrentHashMap<String, LookupHandle> handles = new ConcurrentHashMap<>();
    private final SecureRandom handleRandom = new SecureRandom();

    private final PublicScreenLinkRepository  linkRepo;
    private final FamilyMemberRepository      familyMemberRepository;
    private final LoginRepository             loginRepository;
    private final ChurchRegistrationRepository churchRegistrationRepository;
    private final VerificationStore           verificationStore;
    private final EmailService                emailService;
    private final com.churchgeniuspro.service.SubscriptionService subscriptionService;
    private final PublicSendLimiter           sendLimiter;

    public MemberSignupController(PublicScreenLinkRepository  linkRepo,
                                  FamilyMemberRepository      familyMemberRepository,
                                  LoginRepository             loginRepository,
                                  ChurchRegistrationRepository churchRegistrationRepository,
                                  VerificationStore           verificationStore,
                                  EmailService                emailService,
                                  com.churchgeniuspro.service.SubscriptionService subscriptionService,
                                  PublicSendLimiter           sendLimiter) {
        this.linkRepo                   = linkRepo;
        this.familyMemberRepository     = familyMemberRepository;
        this.loginRepository            = loginRepository;
        this.churchRegistrationRepository = churchRegistrationRepository;
        this.verificationStore          = verificationStore;
        this.emailService               = emailService;
        this.subscriptionService        = subscriptionService;
        this.sendLimiter                = sendLimiter;
    }

    // ── Page route ────────────────────────────────────────────────────────

    @GetMapping("/memberSignup")
    public String memberSignupPage() {
        return "forward:/memberSignup.html";
    }

    // ── Step 1 · Validate public token ────────────────────────────────────

    /**
     * Checks the public-screen token is a live Member Signup link and returns the
     * church name for display on the signup page. The {@code appClientId} field of
     * the response carries the link token itself (the page echoes it on every later
     * step); the tenant id is resolved server-side from that token each time.
     *
     * <p>Response (success): {@code { status:"valid", appClientId (=token), churchName }}
     * <p>Response (failure): {@code { status:"invalid", message }}
     */
    @ResponseBody
    @GetMapping("/api/member-signup/validate")
    public ResponseEntity<Map<String, Object>> validate(@RequestParam String token) {
        Map<String, Object> res = new HashMap<>();
        try {
            // Verify the token exists in the DB and is not revoked/expired
            Optional<PublicScreenLink> opt = linkRepo.findByToken(token);
            if (opt.isEmpty() || opt.get().isRevoked()) {
                res.put("status",  "invalid");
                res.put("message", "This signup link is invalid or has been revoked.");
                return ResponseEntity.status(400).body(res);
            }
            PublicScreenLink link = opt.get();
            if (link.getExpirationDate() != null && link.getExpirationDate().isBefore(LocalDate.now())) {
                res.put("status",  "invalid");
                res.put("message", "This signup link has expired. Please contact your administrator.");
                return ResponseEntity.status(400).body(res);
            }

            // The token must have been minted for the Member Signup page; a Membership
            // Form / Donation / SMS token for the same church must not open this flow.
            if (!PublicScreensController.MEMBER_SIGNUP_URL.equals(link.getPageUrl())) {
                res.put("status",  "invalid");
                res.put("message", "This signup link is invalid or has been revoked.");
                return ResponseEntity.status(400).body(res);
            }
            String appClientId = link.getAppClientId();

            // Resolve church name for display
            String churchName = "";
            ChurchRegistration cr = churchRegistrationRepository
                    .findByClientIdAndDeleteFlagFalse(appClientId)
                    .orElse(null);
            if (cr != null && cr.getChurchName() != null) {
                churchName = cr.getChurchName();
            }

            res.put("status",      "valid");
            // The page stores this under "appClientId" and echoes it on every later
            // step. It is the link token, not the tenant id: each step re-validates
            // it server-side, so the plaintext tenant id is neither exposed nor trusted.
            res.put("appClientId", token);
            res.put("churchName",  churchName);
            return ResponseEntity.ok(res);

        } catch (Exception e) {
            log.error("MemberSignup validate error: {}", e.getMessage(), e);
            res.put("status",  "invalid");
            res.put("message", "Invalid or malformed signup link.");
            return ResponseEntity.status(400).body(res);
        }
    }

    // ── Step 2 · Look up member by first name + last name + email/phone ──────

    /**
     * Finds a matching family member and returns the masked email address so
     * the user can confirm it before proceeding to create credentials.
     *
     * <p>Request body: {@code { appClientId, firstName, lastName, contact }}
     * where {@code contact} is the member's own email/phone, OR an adult family
     * member's email/phone (for child members who have no direct contact).
     *
     * <p>Lookup strategy:
     * <ol>
     *   <li>Try direct match: firstName + lastName + contact on the member's own record.</li>
     *   <li>If not found, try child match: firstName + lastName for a child, where any
     *       adult in the same family has the given contact.</li>
     * </ol>
     *
     * <p>Response (found):     {@code { status:"found", maskedEmail, memberRef }}
     * <p>Response (not found): {@code { status:"not_found", message }}
     */
    @ResponseBody
    @PostMapping("/api/member-signup/lookup")
    public ResponseEntity<Map<String, Object>> lookup(@RequestBody Map<String, String> body,
                                                      HttpServletRequest request) {
        Map<String, Object> res = new HashMap<>();

        String linkToken   = trim(body.get("appClientId"));   // link token issued by validate()
        String firstName   = trim(body.get("firstName"));
        String lastName    = trim(body.get("lastName"));
        String contact     = trim(body.get("contact"));   // email or phone

        if (linkToken.isEmpty() || firstName.isEmpty() || lastName.isEmpty() || contact.isEmpty()) {
            res.put("status",  "error");
            res.put("message", "All fields are required.");
            return ResponseEntity.status(400).body(res);
        }
        String appClientId = tenantFromLinkToken(linkToken);
        if (appClientId == null) return invalidLink(res);

        // A name + contact probe against the church's roster: bounded per network
        // origin so it cannot be used to confirm membership in bulk (security audit P2/P5).
        String limited = sendLimiter.check(PublicSendLimiter.MEMBER_SIGNUP_LOOKUP, request, null, appClientId);
        if (limited != null) return tooMany(res, limited);

        // Strategy 1: direct match — firstName + lastName + (own email or own phone)
        List<FamilyMember> matches = familyMemberRepository
                .findByLastNameAndContact(lastName, contact, appClientId)
                .stream()
                .filter(m -> firstName.equalsIgnoreCase(m.getFirstName() != null ? m.getFirstName().trim() : ""))
                .toList();

        FamilyMember matched = null;
        if (!matches.isEmpty()) {
            matched = matches.get(0);
        } else {
            // Strategy 2: child match — firstName + lastName for a child-role member whose
            // family contains an adult with matching contact
            List<FamilyMember> childMatches = familyMemberRepository
                    .findChildByNameAndFamilyContact(firstName, lastName, contact, appClientId);
            if (!childMatches.isEmpty()) {
                matched = childMatches.get(0);
            }
        }

        if (matched == null) {
            res.put("status",  "not_found");
            res.put("message", "No matching member found. Please check your name and email/phone.");
            return ResponseEntity.status(404).body(res);
        }

        // Check if this member already has a signup account
        if (matched.getMemberRef() != null) {
            Optional<SignUp> existing = loginRepository.findByClientId(matched.getMemberRef());
            if (existing.isPresent() && Boolean.TRUE.equals(existing.get().getActive())
                    && !Boolean.TRUE.equals(existing.get().getDeleted())) {
                res.put("status",  "already_registered");
                res.put("message", "This member already has an account. Please login or use 'Forgot Password'.");
                return ResponseEntity.status(409).body(res);
            }
        }

        // Resolve email to send OTP to — prefer the matched member's own email,
        // fall back to Head of Household's email within the same family
        String email = resolved(matched, appClientId);
        if (email == null || email.isEmpty()) {
            res.put("status",  "no_email");
            res.put("message", "No email address found for this member. Please contact your administrator.");
            return ResponseEntity.status(400).body(res);
        }

        // Ensure the member has a memberRef — generate and persist one if missing
        if (matched.getMemberRef() == null || matched.getMemberRef().isEmpty()) {
            matched.setMemberRef("MBR" + UUID.randomUUID().toString().replace("-", ""));
            familyMemberRepository.save(matched);
        }

        res.put("status",      "found");
        res.put("maskedEmail", maskEmail(email));
        // Opaque handle in place of the MBR reference (see LookupHandle).
        res.put("memberRef",   issueHandle(matched.getMemberRef(), appClientId));
        return ResponseEntity.ok(res);
    }

    // ── Step 3 · Send OTP ─────────────────────────────────────────────────

    /**
     * Validates that the chosen username is not taken, then sends a 6-digit OTP
     * to the member's email address.
     *
     * <p>Request body: {@code { appClientId, memberRef, username, password }}
     */
    @ResponseBody
    @PostMapping("/api/member-signup/send-code")
    public ResponseEntity<Map<String, Object>> sendCode(@RequestBody Map<String, String> body,
                                                        HttpServletRequest request) {
        Map<String, Object> res = new HashMap<>();

        String linkToken   = trim(body.get("appClientId"));   // link token issued by validate()
        String handleKey   = trim(body.get("memberRef"));     // handle issued by lookup()
        String username    = trim(body.get("username"));
        String password    = trim(body.get("password"));

        if (linkToken.isEmpty() || handleKey.isEmpty() || username.isEmpty() || password.isEmpty()) {
            res.put("status",  "error");
            res.put("message", "All fields are required.");
            return ResponseEntity.status(400).body(res);
        }
        String appClientId = tenantFromLinkToken(linkToken);
        if (appClientId == null) return invalidLink(res);
        String memberRef = resolveHandle(handleKey, appClientId);
        if (memberRef == null) return startOver(res);

        String policyError = com.churchgeniuspro.util.PasswordPolicy.validate(password);
        if (policyError != null) {
            res.put("status",  "error");
            res.put("message", policyError);
            return ResponseEntity.status(400).body(res);
        }

        // Check username uniqueness
        if (loginRepository.existsByUsername(username)) {
            res.put("status",  "error");
            res.put("message", "Username '" + username + "' is already taken. Please choose another.");
            return ResponseEntity.status(409).body(res);
        }

        // Find the member — must belong to the link's church
        FamilyMember fm = familyMemberRepository.findByMemberRef(memberRef)
                .filter(m -> belongsTo(m, appClientId)).orElse(null);
        if (fm == null) return startOver(res);

        // Resolve email
        String email = resolved(fm, appClientId);
        if (email == null || email.isEmpty()) {
            res.put("status",  "error");
            res.put("message", "No email address found. Please contact your administrator.");
            return ResponseEntity.status(400).body(res);
        }

        // Each call re-issues the code and e-mails the member again: bounded per network
        // origin, per member and per church (security audit P2).
        String limited = sendLimiter.check(PublicSendLimiter.MEMBER_SIGNUP_OTP, request, memberRef, appClientId);
        if (limited != null) return tooMany(res, limited);

        // Generate and send OTP
        String code = verificationStore.generateAndStore(memberRef, OTP_TYPE, email);
        try {
            String churchName = emailService.getChurchName(appClientId);
            String html = buildOtpEmail(fm.getFirstName(), code, churchName);
            // Registration mail: reaches the member signing up even on a Trial plan.
            emailService.sendAccountEmail(email,
                    "Your " + churchName + " Verification Code",
                    html,
                    appClientId);
            log.info("MemberSignup OTP sent to masked={} for memberRef={}", maskEmail(email), memberRef);
        } catch (Exception e) {
            log.error("MemberSignup OTP send failed for memberRef={}: {}", memberRef, e.getMessage(), e);
            verificationStore.remove(memberRef, OTP_TYPE);
            res.put("status",  "error");
            res.put("message", "Failed to send verification code. Please try again.");
            return ResponseEntity.status(500).body(res);
        }

        res.put("status",      "sent");
        res.put("maskedEmail", maskEmail(email));
        return ResponseEntity.ok(res);
    }

    // ── Step 4 · Verify OTP + create account ─────────────────────────────

    /**
     * Verifies the OTP, creates the {@link SignUp} record, links it back to
     * the {@link FamilyMember}, and redirects the caller to the login page.
     *
     * <p>Request body: {@code { appClientId, memberRef, username, password, code }}
     */
    @ResponseBody
    @PostMapping("/api/member-signup/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestBody Map<String, String> body) {
        Map<String, Object> res = new HashMap<>();

        String linkToken   = trim(body.get("appClientId"));   // link token issued by validate()
        String handleKey   = trim(body.get("memberRef"));     // handle issued by lookup()
        String username    = trim(body.get("username"));
        String password    = trim(body.get("password"));
        String code        = trim(body.get("code"));

        if (linkToken.isEmpty() || handleKey.isEmpty() || username.isEmpty()
                || password.isEmpty() || code.isEmpty()) {
            res.put("status",  "error");
            res.put("message", "Missing required fields.");
            return ResponseEntity.status(400).body(res);
        }
        String appClientId = tenantFromLinkToken(linkToken);
        if (appClientId == null) return invalidLink(res);
        String memberRef = resolveHandle(handleKey, appClientId);
        if (memberRef == null) return startOver(res);

        // Verify OTP
        if (!verificationStore.validate(memberRef, OTP_TYPE, code)) {
            res.put("status",  "error");
            res.put("message", "Invalid or expired verification code.");
            return ResponseEntity.status(400).body(res);
        }

        // Final username uniqueness check (race condition guard)
        if (loginRepository.existsByUsername(username)) {
            res.put("status",  "error");
            res.put("message", "Username '" + username + "' is already taken.");
            return ResponseEntity.status(409).body(res);
        }

        // Password policy (server-authoritative; also checked at send-code)
        String pwPolicyError = com.churchgeniuspro.util.PasswordPolicy.validate(password);
        if (pwPolicyError != null) {
            res.put("status",  "error");
            res.put("message", pwPolicyError);
            return ResponseEntity.status(400).body(res);
        }

        // Resolve the member — must belong to the link's church
        FamilyMember fm = familyMemberRepository.findByMemberRef(memberRef)
                .filter(m -> belongsTo(m, appClientId)).orElse(null);
        if (fm == null) return startOver(res);

        // A handle that already produced an account must not produce a second one.
        Optional<SignUp> existing = loginRepository.findByClientId(memberRef);
        if (existing.isPresent() && Boolean.TRUE.equals(existing.get().getActive())
                && !Boolean.TRUE.equals(existing.get().getDeleted())) {
            handles.remove(handleKey);
            res.put("status",  "already_registered");
            res.put("message", "This member already has an account. Please login or use 'Forgot Password'.");
            return ResponseEntity.status(409).body(res);
        }

        // Resolve church_id from Head of Household's existing signup (if any)
        // The Head's signup is found via their memberRef (= signup.clientId)
        Integer churchId = null;
        if (fm.getFamily() != null) {
            List<FamilyMember> heads = familyMemberRepository
                    .findHeadWithMemberRefByFamilyId(fm.getFamily().getId());
            if (!heads.isEmpty() && heads.get(0).getMemberRef() != null) {
                SignUp headSignup = loginRepository.findByClientId(heads.get(0).getMemberRef()).orElse(null);
                if (headSignup != null) churchId = headSignup.getId();
            }
        }

        // ── Subscription plan: Member Portal feature + portal-count limit ──
        try {
            String orgClientId = fm.getFamily() != null ? fm.getFamily().getAppClientId() : null;
            if (orgClientId != null) {
                if (!subscriptionService.isFeatureEnabled(orgClientId, "memberPortal")) {
                    res.put("status",  "error");
                    res.put("message", "Member portals are not included in this church's subscription plan. "
                            + "Please contact your church office.");
                    return ResponseEntity.status(403).body(res);
                }
                if (churchId != null) {
                    long portals = loginRepository.countByChurchIdAndChurchFalseAndDeletedFalse(churchId);
                    String limitMsg = subscriptionService.checkMemberPortalLimit(orgClientId, portals);
                    if (limitMsg != null) {
                        res.put("status",  "error");
                        res.put("message", limitMsg);
                        return ResponseEntity.status(403).body(res);
                    }
                }
            }
        } catch (Exception ignored) { /* fail-open on limit-check errors */ }

        // Persist SignUp record
        Date now = new Date();
        SignUp signUp = new SignUp();
        signUp.setClientId(memberRef);          // MBR<uuid> — uniquely identifies this member
        signUp.setUsername(username);
        signUp.setPassword(PasswordUtil.encode(password));
        signUp.setActive(true);
        signUp.setDeleted(false);
        signUp.setLocked(false);
        signUp.setChurch(false);
        signUp.setChurchId(churchId);
        signUp.setCreated(now);
        signUp.setUpdated(now);
        SignUp saved = loginRepository.save(signUp);

        // The signup is already linked via fm.memberRef == saved.clientId — no extra save needed

        verificationStore.remove(memberRef, OTP_TYPE);
        handles.remove(handleKey);   // one-time: the handle is spent

        log.info("MemberSignup complete: username={} memberRef={} signupId={}", username, memberRef, saved.getId());

        // Send welcome / account-creation confirmation email to the contact address
        try {
            String email = resolved(fm, appClientId);
            if (email != null && !email.isBlank()) {
                String churchName = emailService.getChurchName(appClientId);
                String html = buildWelcomeEmail(fm.getFirstName(), username, churchName);
                // Registration mail: reaches the member signing up even on a Trial plan.
                emailService.sendAccountEmail(email,
                        "Welcome to " + churchName + " – Account Created",
                        html,
                        appClientId);
            }
        } catch (Exception e) {
            // Non-fatal — account is already created; just log
            log.warn("MemberSignup welcome email failed for memberRef={}: {}", memberRef, e.getMessage());
        }

        res.put("status",  "success");
        res.put("message", "Account created successfully. Redirecting to login…");
        return ResponseEntity.ok(res);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Resolves the tenant behind the value the page carries as {@code appClientId}:
     * it must be a live {@link PublicScreenLink} token minted for the Member Signup
     * page. Returns {@code null} when the value is not such a token (including a
     * plaintext client id — that shape is no longer accepted).
     */
    private String tenantFromLinkToken(String token) {
        if (token == null || token.isBlank()) return null;
        PublicScreenLink link = linkRepo.findByToken(token).orElse(null);
        if (link == null || link.isRevoked()) return null;
        if (link.getExpirationDate() != null && link.getExpirationDate().isBefore(LocalDate.now())) return null;
        if (!PublicScreensController.MEMBER_SIGNUP_URL.equals(link.getPageUrl())) return null;
        String cid = link.getAppClientId();
        return cid == null || cid.isBlank() ? null : cid;
    }

    private String issueHandle(String memberRef, String appClientId) {
        byte[] b = new byte[24];
        handleRandom.nextBytes(b);
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        if (handles.size() > 10_000) handles.clear();   // safety valve
        handles.entrySet().removeIf(e -> e.getValue().expired());
        handles.put(key, new LookupHandle(memberRef, appClientId, System.currentTimeMillis()));
        return key;
    }

    /** The member ref behind a handle, only when it was issued for this tenant and is unexpired. */
    private String resolveHandle(String key, String appClientId) {
        LookupHandle h = handles.get(key);
        if (h == null) return null;
        if (h.expired()) { handles.remove(key); return null; }
        if (!h.appClientId().equals(appClientId)) return null;
        return h.memberRef();
    }

    /** Tenant via the family (authoritative), falling back to the member's own column. */
    private static boolean belongsTo(FamilyMember fm, String appClientId) {
        String t = fm.getFamily() != null && fm.getFamily().getAppClientId() != null
                ? fm.getFamily().getAppClientId() : fm.getAppClientId();
        return appClientId.equals(t);
    }

    /** 429 in this controller's {@code status}/{@code message} shape. */
    private static ResponseEntity<Map<String, Object>> tooMany(Map<String, Object> res, String message) {
        res.put("status",  "error");
        res.put("message", message);
        return ResponseEntity.status(429).body(res);
    }

    private static ResponseEntity<Map<String, Object>> invalidLink(Map<String, Object> res) {
        res.put("status",  "invalid");
        res.put("message", "This signup link is invalid or has expired. Please reload the page.");
        return ResponseEntity.status(400).body(res);
    }

    private static ResponseEntity<Map<String, Object>> startOver(Map<String, Object> res) {
        res.put("status",  "error");
        res.put("message", "Member not found. Please start over.");
        return ResponseEntity.status(404).body(res);
    }

    /**
     * Resolves the email address to use for OTP delivery.
     * Uses the matched member's own email if present;
     * otherwise falls back to the Head of Household's email in the same family.
     */
    private String resolved(FamilyMember fm, String appClientId) {
        if (fm.getEmail() != null && !fm.getEmail().isBlank()) return fm.getEmail();
        if (fm.getFamily() != null) {
            // Try Head of Household in the same family
            List<FamilyMember> heads = familyMemberRepository
                    .findActiveMembersByFamilyId(fm.getFamily().getId());
            return heads.stream()
                    .filter(m -> "Head".equalsIgnoreCase(m.getRole())
                              || "Head of Household".equalsIgnoreCase(m.getRole()))
                    .map(FamilyMember::getEmail)
                    .filter(e -> e != null && !e.isBlank())
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "****";
        String[] parts = email.split("@", 2);
        String local   = parts[0];
        String visible = local.length() > 2 ? local.substring(0, 2) : local.substring(0, 1);
        return visible + "****@" + parts[1];
    }

    private static String trim(String s) { return s != null ? s.trim() : ""; }

    private String buildWelcomeEmail(String firstName, String username, String churchName) {
        String name       = firstName != null && !firstName.isBlank() ? firstName : "there";
        String safeChurch = churchName != null ? churchName.replace("&", "&amp;").replace("<", "&lt;") : "";
        String safeUser   = username  != null ? username.replace("&", "&amp;").replace("<", "&lt;") : "";
        return "<!DOCTYPE html><html lang='en'><head>"
             + "<meta charset='UTF-8'/><meta name='viewport' content='width=device-width,initial-scale=1.0'/>"
             + "<title>Account Created</title></head>"
             + "<body style='margin:0;padding:0;background:#f5f6fa;"
             +   "font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;'>"
             + "<table width='100%' cellpadding='0' cellspacing='0' style='background:#f5f6fa;padding:40px 20px;'>"
             + "<tr><td align='center'>"
             + "<table width='100%' cellpadding='0' cellspacing='0'"
             +   " style='max-width:480px;background:#fff;border-radius:16px;"
             +          "box-shadow:0 4px 24px rgba(0,0,0,.08);overflow:hidden;'>"
             + "<tr><td style='background:#673147;padding:28px 36px;text-align:center;'>"
             +   "<p style='margin:0;font-size:20px;font-weight:700;color:#fff;'>" + safeChurch + "</p>"
             +   "<p style='margin:6px 0 0;font-size:12px;color:rgba(255,255,255,.7);'>Member Portal</p>"
             + "</td></tr>"
             + "<tr><td style='padding:32px 36px;'>"
             +   "<p style='margin:0 0 14px;font-size:15px;font-weight:600;color:#1a1a2e;'>Hi " + name + ",</p>"
             +   "<p style='margin:0 0 22px;font-size:14px;color:#555;line-height:1.7;'>"
             +     "Your member account has been set up successfully! "
             +     "You can now log in to the " + safeChurch + " member portal using your username below.</p>"
             +   "<div style='background:#f5eaef;border:1px solid #d4a3b4;border-radius:10px;"
             +               "padding:14px 20px;margin:0 0 24px;'>"
             +     "<p style='margin:0;font-size:13px;color:#888;'>Username</p>"
             +     "<p style='margin:4px 0 0;font-size:18px;font-weight:700;color:#673147;'>" + safeUser + "</p>"
             +   "</div>"
             +   "<p style='margin:0;font-size:12px;color:#aaa;'>"
             +     "If you did not create this account, please contact your church administrator immediately.</p>"
             + "</td></tr>"
             + "<tr><td style='background:#f8f9ff;padding:16px 36px;"
             +             "border-top:1px solid #e8eaf6;text-align:center;'>"
             +   "<p style='margin:0;font-size:12px;color:#aaa;'>"
             +     "Sent by <strong>" + safeChurch + "</strong></p>"
             + "</td></tr>"
             + "</table></td></tr></table></body></html>";
    }

    private String buildOtpEmail(String firstName, String code, String churchName) {
        String name       = firstName != null && !firstName.isBlank() ? firstName : "there";
        String safeChurch = churchName != null ? churchName.replace("&", "&amp;").replace("<", "&lt;") : "";
        return "<!DOCTYPE html><html lang='en'><head>"
             + "<meta charset='UTF-8'/><meta name='viewport' content='width=device-width,initial-scale=1.0'/>"
             + "<title>Verification Code</title></head>"
             + "<body style='margin:0;padding:0;background:#f5f6fa;"
             +   "font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;'>"
             + "<table width='100%' cellpadding='0' cellspacing='0' style='background:#f5f6fa;padding:40px 20px;'>"
             + "<tr><td align='center'>"
             + "<table width='100%' cellpadding='0' cellspacing='0'"
             +   " style='max-width:480px;background:#fff;border-radius:16px;"
             +          "box-shadow:0 4px 24px rgba(0,0,0,.08);overflow:hidden;'>"
             + "<tr><td style='background:#673147;padding:28px 36px;text-align:center;'>"
             +   "<p style='margin:0;font-size:20px;font-weight:700;color:#fff;'>" + safeChurch + "</p>"
             +   "<p style='margin:6px 0 0;font-size:12px;color:rgba(255,255,255,.7);'>Member Account Setup</p>"
             + "</td></tr>"
             + "<tr><td style='padding:32px 36px;'>"
             +   "<p style='margin:0 0 14px;font-size:15px;font-weight:600;color:#1a1a2e;'>Hi " + name + ",</p>"
             +   "<p style='margin:0 0 22px;font-size:14px;color:#555;line-height:1.7;'>"
             +     "Use the verification code below to complete your account setup. "
             +     "This code expires in <strong>10 minutes</strong>.</p>"
             +   "<div style='text-align:center;margin:0 0 28px;'>"
             +     "<div style='display:inline-block;background:#f5eaef;border:2px dashed #673147;"
             +                "border-radius:12px;padding:16px 36px;'>"
             +       "<span style='font-size:32px;font-weight:900;letter-spacing:8px;color:#673147;'>"
             +         code + "</span>"
             +     "</div>"
             +   "</div>"
             +   "<p style='margin:0;font-size:12px;color:#aaa;'>"
             +     "If you did not request this, please ignore this email.</p>"
             + "</td></tr>"
             + "<tr><td style='background:#f8f9ff;padding:16px 36px;"
             +             "border-top:1px solid #e8eaf6;text-align:center;'>"
             +   "<p style='margin:0;font-size:12px;color:#aaa;'>"
             +     "Sent by <strong>" + safeChurch + "</strong></p>"
             + "</td></tr>"
             + "</table></td></tr></table></body></html>";
    }
}
