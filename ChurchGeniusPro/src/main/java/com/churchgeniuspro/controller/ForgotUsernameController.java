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
import jakarta.servlet.http.HttpServletRequest;
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

    private static final int  CAPTCHA_THRESHOLD = 3;   // show captcha after this many attempts
    private static final int  BLOCK_THRESHOLD   = 5;   // block after this many attempts per hour
    private static final long WINDOW_HOURS      = 1L;

    private final LoginRepository              loginRepo;
    private final AppUserRepository            appUserRepo;
    private final FamilyMemberRepository       familyMemberRepo;
    private final ChurchRegistrationRepository churchRepo;
    private final UsernameRecoveryLogRepository recoveryLogRepo;
    private final EmailService                 emailService;

    public ForgotUsernameController(LoginRepository loginRepo,
                                    AppUserRepository appUserRepo,
                                    FamilyMemberRepository familyMemberRepo,
                                    ChurchRegistrationRepository churchRepo,
                                    UsernameRecoveryLogRepository recoveryLogRepo,
                                    EmailService emailService) {
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
        long identifierCount = 0;
        if (identifier != null && !identifier.isBlank()) {
            identifierCount = recoveryLogRepo.countByIdentifierSince(normalise(identifier), since);
        }
        long ipCount = recoveryLogRepo.countByIpSince(ip, since);
        long maxCount = Math.max(identifierCount, ipCount);
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
        String captchaToken  = str(body.get("captchaToken")); // reserved for future hCaptcha verify

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
        Map<String, String> usernameRoleMap = new LinkedHashMap<>();

        if (isEmail) {
            collectByEmail(identifier, usernameRoleMap);
        } else {
            collectByPhone(identifier, usernameRoleMap);
        }

        boolean matchFound = !usernameRoleMap.isEmpty();

        // ── Audit log ─────────────────────────────────────────────────────
        audit(identifier, idType, ip, matchFound);

        // ── Send email if match found ─────────────────────────────────────
        if (matchFound) {
            // Resolve the delivery address
            String deliveryEmail = isEmail ? rawIdentifier.trim() : resolveEmailFromPhone(identifier);
            if (deliveryEmail != null && !deliveryEmail.isBlank()) {
                sendRecoveryEmail(deliveryEmail, usernameRoleMap);
            }
            // If phone and no email found, a partial mask could be shown — but per requirements
            // we NEVER expose usernames on-screen, so we just return the generic message.
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

    private void collectByPhone(String phone, Map<String, String> out) {
        // Tier 1: staff (app_user)
        List<AppUser> staffUsers = appUserRepo.findActiveByPhone(phone);
        for (AppUser u : staffUsers) {
            SignUp signup = loginRepo.findActiveSignupByUserId(u.getUserId()).orElse(null);
            if (signup != null) {
                out.putIfAbsent(signup.getUsername(), u.getRole());
            }
        }

        // Tier 2: members (family_member)
        List<FamilyMember> members = familyMemberRepo.findActiveByPhoneAnyChurch(phone);
        for (FamilyMember fm : members) {
            if (fm.getMemberRef() == null) continue;
            SignUp signup = loginRepo.findActiveSignupByMemberRef(fm.getMemberRef()).orElse(null);
            if (signup != null) {
                String role = fm.getRole() != null ? fm.getRole() : "Member";
                out.putIfAbsent(signup.getUsername(), role);
            }
        }
    }

    // ── Email fallback for phone-based recovery ───────────────────────────

    /** Finds the first email address on record for the given phone number (for sending recovery). */
    private String resolveEmailFromPhone(String phone) {
        // Try staff first
        List<AppUser> staff = appUserRepo.findActiveByPhone(phone);
        for (AppUser u : staff) {
            if (u.getEmail() != null && !u.getEmail().isBlank()) return u.getEmail();
        }
        // Then members
        List<FamilyMember> members = familyMemberRepo.findActiveByPhoneAnyChurch(phone);
        for (FamilyMember fm : members) {
            if (fm.getEmail() != null && !fm.getEmail().isBlank()) return fm.getEmail();
        }
        return null;
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

        emailService.sendGenericEmail(toEmail, "Your Church Genius Pro Username(s)", html);
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
            System.err.println("[ForgotUsername] Audit log failed: " + e.getMessage());
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

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
