package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.hibernate.UsernameRecoveryLog;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.UsernameRecoveryLogRepository;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.util.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Handles the "Forgot Username" recovery flow.
 *
 * <p>Security design:
 * <ul>
 *   <li>Always returns a generic success message — prevents username enumeration.</li>
 *   <li>Rate-limit: max 5 attempts per identifier or IP per hour. Returns HTTP 429 after that.</li>
 *   <li>Captcha required flag returned after 3 attempts (frontend shows hCaptcha).</li>
 *   <li>All attempts are audit-logged in {@code username_recovery_log}.</li>
 *   <li>Usernames are NEVER returned in the API response — only sent to the registered destination.</li>
 * </ul>
 *
 * <p>Account search covers three tiers:
 * <ol>
 *   <li>Staff accounts ({@code app_user}) — matched by email or phone.</li>
 *   <li>Member accounts ({@code family_member}) — matched by email or phone via memberRef.</li>
 *   <li>Church-level accounts — matched by email from {@code church_registration}.</li>
 * </ol>
 */
@RestController
public class ForgotUsernameController {

    private static final Logger LOG = LoggerFactory.getLogger(ForgotUsernameController.class);

    private static final int  CAPTCHA_THRESHOLD = 3;   // show captcha after this many attempts
    private static final int  BLOCK_THRESHOLD   = 5;   // block after this many attempts per hour
    private static final long WINDOW_HOURS      = 1L;

    private final LoginRepository              loginRepo;
    private final AppUserRepository            appUserRepo;
    private final FamilyMemberRepository       familyMemberRepo;
    private final ChurchRegistrationRepository churchRepo;
    private final UsernameRecoveryLogRepository recoveryLogRepo;
    private final EmailService                 emailService;

    private final com.churchgeniuspro.util.PublicFormGuard formGuard;

    public ForgotUsernameController(LoginRepository loginRepo,
                                    AppUserRepository appUserRepo,
                                    FamilyMemberRepository familyMemberRepo,
                                    ChurchRegistrationRepository churchRepo,
                                    UsernameRecoveryLogRepository recoveryLogRepo,
                                    EmailService emailService,
            com.churchgeniuspro.util.PublicFormGuard formGuard) {
        this.formGuard = formGuard;
        this.loginRepo       = loginRepo;
        this.appUserRepo     = appUserRepo;
        this.familyMemberRepo = familyMemberRepo;
        this.churchRepo      = churchRepo;
        this.recoveryLogRepo = recoveryLogRepo;
        this.emailService    = emailService;
    }

    // ── Generic success message ───────────────────────────────────────────
    private static final String GENERIC_MSG =
            "If an account exists with the provided information, the username details have been sent.";

    // ── Rate-limit check endpoint (GET) ──────────────────────────────────

    /**
     * Returns the current attempt count for the given identifier.
     * The frontend uses this to decide whether to show the captcha widget.
     */
    @GetMapping("/api/public/forgot-username/status")
    public ResponseEntity<Map<String, Object>> status(
            @RequestParam(required = false) String identifier,
            HttpServletRequest req) {
        String ip = clientIp(req);
        LocalDateTime since = LocalDateTime.now().minusHours(WINDOW_HOURS);
        // Only the caller's own (IP) history decides what the page shows. The identifier
        // count used to be included, which let anyone ask "has someone been trying to
        // recover this address?" (security audit P5). The submit endpoint still enforces
        // both dimensions; the page merely learns about a captcha one request later.
        long ipCount = recoveryLogRepo.countByIpSince(ip, since);
        long maxCount = ipCount;
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("attempts",        maxCount);
        res.put("requireCaptcha",  maxCount >= CAPTCHA_THRESHOLD);
        res.put("blocked",         maxCount >= BLOCK_THRESHOLD);
        return ResponseEntity.ok(res);
    }

    // ── Recovery submit endpoint (POST) ──────────────────────────────────

    @PostMapping("/api/public/forgot-username")
    public ResponseEntity<Map<String, Object>> recover(
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest req) {

        if (body == null) body = Collections.emptyMap();

        String rawIdentifier = str(body.get("identifier"));
        String captchaToken  = str(body.get("captchaToken"));

        // ── Basic validation ──────────────────────────────────────────────
        if (rawIdentifier == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Please provide an email address or phone number."));
        }

        String identifier = normalise(rawIdentifier);
        boolean isEmail   = identifier.contains("@");
        String  idType    = isEmail ? "email" : "phone";
        String  ip        = clientIp(req);

        // ── Rate-limiting ─────────────────────────────────────────────────
        LocalDateTime since = LocalDateTime.now().minusHours(WINDOW_HOURS);
        long identifierAttempts = recoveryLogRepo.countByIdentifierSince(identifier, since);
        long ipAttempts         = recoveryLogRepo.countByIpSince(ip, since);

        // Once the CAPTCHA threshold is reached the token is verified, not just requested.
        if ((identifierAttempts >= CAPTCHA_THRESHOLD || ipAttempts >= CAPTCHA_THRESHOLD)
                && formGuard.captchaEnabled()) {
            String captchaErr = formGuard.checkCaptcha(captchaToken, ip);
            if (captchaErr != null) {
                audit(identifier, idType, ip, false);
                return ResponseEntity.status(400).body(Map.of("error", captchaErr, "requireCaptcha", true));
            }
        }

        if (identifierAttempts >= BLOCK_THRESHOLD || ipAttempts >= BLOCK_THRESHOLD) {
            // Still log the blocked attempt
            audit(identifier, idType, ip, false);
            return ResponseEntity.status(429).body(Map.of(
                    "error", "Too many recovery attempts. Please try again later.",
                    "blocked", true));
        }

        // ── Account search ────────────────────────────────────────────────
        // Collect (username → role) pairs from all tiers.
        // Key = username, Value = display role label.
        // Grouped by the email ON THAT ACCOUNT. A phone number shared by a member of
        // church B and a staff member of church A must not put A's username in B's inbox.
        Map<String, Map<String, String>> byDeliveryEmail = new LinkedHashMap<>();

        if (isEmail) {
            Map<String, String> usernameRoleMap = new LinkedHashMap<>();
            collectByEmail(identifier, usernameRoleMap);
            if (!usernameRoleMap.isEmpty()) byDeliveryEmail.put(rawIdentifier.trim(), usernameRoleMap);
        } else {
            collectByPhone(identifier, byDeliveryEmail);
        }

        boolean matchFound = !byDeliveryEmail.isEmpty();

        // ── Audit log ─────────────────────────────────────────────────────
        audit(identifier, idType, ip, matchFound);

        // ── Send email if match found ─────────────────────────────────────
        if (matchFound) {
            byDeliveryEmail.forEach(this::sendRecoveryEmail);
            // Accounts with no email on file get nothing — usernames are never shown on-screen.
        }

        // ── Generic response (no enumeration) ────────────────────────────
        long nowAttempts = identifierAttempts + 1;
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("message",       GENERIC_MSG);
        res.put("requireCaptcha", nowAttempts >= CAPTCHA_THRESHOLD);
        res.put("blocked",        nowAttempts >= BLOCK_THRESHOLD);
        return ResponseEntity.ok(res);
    }

    // ── Email-based search ────────────────────────────────────────────────

    private void collectByEmail(String email, Map<String, String> out) {
        // Tier 1: staff (app_user)
        List<AppUser> staffUsers = appUserRepo.findActiveByEmail(email);
        for (AppUser u : staffUsers) {
            SignUp signup = loginRepo.findActiveSignupByUserId(u.getUserId()).orElse(null);
            if (signup != null) {
                out.putIfAbsent(signup.getUsername(), u.getRole());
            }
        }

        // Tier 2: members (family_member → signup via MBR token as clientId)
        List<FamilyMember> members = familyMemberRepo.findActiveByEmail(email);
        for (FamilyMember fm : members) {
            if (fm.getMemberRef() == null) continue;
            SignUp signup = loginRepo.findActiveSignupByMemberRef(fm.getMemberRef()).orElse(null);
            if (signup != null) {
                String role = fm.getRole() != null ? fm.getRole() : "Member";
                out.putIfAbsent(signup.getUsername(), role);
            }
        }

        // Tier 3: church-level accounts (church_registration → signup)
        List<ChurchRegistration> churches = churchRepo.findByEmailIgnoreCaseAndDeleteFlagFalse(email);
        for (ChurchRegistration cr : churches) {
            SignUp signup = loginRepo.findByClientId(cr.getClientId()).orElse(null);
            if (signup != null && Boolean.TRUE.equals(signup.getActive())) {
                out.putIfAbsent(signup.getUsername(), "Church Admin");
            }
        }
    }

    // ── Phone-based search ────────────────────────────────────────────────

    private void collectByPhone(String phone, Map<String, Map<String, String>> byEmail) {
        // Tier 1: staff (app_user) — delivered to that staff record's own email
        for (AppUser u : appUserRepo.findActiveByPhone(phone)) {
            if (u.getEmail() == null || u.getEmail().isBlank()) continue;
            SignUp signup = loginRepo.findActiveSignupByUserId(u.getUserId()).orElse(null);
            if (signup != null) {
                byEmail.computeIfAbsent(u.getEmail().trim(), k -> new LinkedHashMap<>())
                       .putIfAbsent(signup.getUsername(), u.getRole());
            }
        }

        // Tier 2: members (family_member) — delivered to that member's own email
        for (FamilyMember fm : familyMemberRepo.findActiveByPhoneAnyChurch(phone)) {
            if (fm.getMemberRef() == null || fm.getEmail() == null || fm.getEmail().isBlank()) continue;
            SignUp signup = loginRepo.findActiveSignupByMemberRef(fm.getMemberRef()).orElse(null);
            if (signup != null) {
                String role = fm.getRole() != null ? fm.getRole() : "Member";
                byEmail.computeIfAbsent(fm.getEmail().trim(), k -> new LinkedHashMap<>())
                       .putIfAbsent(signup.getUsername(), role);
            }
        }
    }

    // ── Email builder ─────────────────────────────────────────────────────

    private void sendRecoveryEmail(String toEmail, Map<String, String> usernameRoles) {
        StringBuilder rows = new StringBuilder();
        usernameRoles.forEach((username, role) ->
                rows.append("<li style='padding:4px 0;'><strong>")
                    .append(escHtml(username))
                    .append("</strong> &mdash; ")
                    .append(escHtml(role))
                    .append("</li>"));

        String html = "<div style='font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;"
                + "max-width:520px;margin:0 auto;padding:32px 24px;'>"
                + "<h2 style='color:#673147;margin:0 0 16px;'>Username Recovery</h2>"
                + "<p style='color:#333;margin:0 0 12px;'>Hello,</p>"
                + "<p style='color:#333;margin:0 0 16px;'>The following username(s) are associated with your account:</p>"
                + "<ul style='color:#333;padding-left:20px;margin:0 0 20px;'>"
                + rows
                + "</ul>"
                + "<p style='color:#333;margin:0 0 12px;'>You may use any of the above usernames to log in.</p>"
                + "<p style='color:#888;font-size:13px;margin:0 0 8px;'>If you did not request this recovery, please ignore this message.</p>"
                + "<p style='color:#888;font-size:13px;margin:0;'>Thank you,<br/>Church Genius Pro</p>"
                + "</div>";

        // Account recovery: delivered whatever the tenant's plan says.
        emailService.sendAccountEmail(toEmail, "Your Church Genius Pro Username(s)", html);
    }

    // ── Audit helper ──────────────────────────────────────────────────────

    private void audit(String identifier, String idType, String ip, boolean matchFound) {
        try {
            UsernameRecoveryLog log = new UsernameRecoveryLog();
            log.setIdentifier(identifier);
            log.setIdentifierType(idType);
            log.setIpAddress(ip);
            log.setMatchFound(matchFound);
            recoveryLogRepo.save(log);
        } catch (Exception e) {
            LOG.warn("[ForgotUsername] audit log failed", e);
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    private static String normalise(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase();
        if (!s.contains("@")) {
            // Strip common phone formatting
            s = s.replaceAll("[^0-9+]", "");
        }
        return s;
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Delegates to the shared resolver so this rate limiter keys on the same value as the
     * login one.
     *
     * <p>The previous inline version returned the raw first hop of {@code X-Forwarded-For},
     * which on Azure App Service carries the client's ephemeral port
     * ({@code 203.0.113.7:54321}). That made every request from one client look like a
     * different address, so the 5-per-hour recovery limit below never actually accumulated
     * in production. {@link ClientIpResolver#resolve} strips the port.
     */
    private static String clientIp(HttpServletRequest req) {
        return ClientIpResolver.resolve(req, true);
    }

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
