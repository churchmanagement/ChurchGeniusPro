package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.PasswordResetToken;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.PasswordResetTokenRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.LoginProtectionService;
import com.churchgeniuspro.service.PasswordResetService;
import com.churchgeniuspro.util.EmailMask;
import com.churchgeniuspro.util.PasswordUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Date;
import java.util.Map;

/**
 * Handles the two-step forgot-password flow:
 * <ol>
 *   <li>User submits username + email → token generated, reset email sent.</li>
 *   <li>User follows reset link → new password saved, token marked used.</li>
 * </ol>
 *
 * <p>Token creation is delegated to {@link PasswordResetService} so that
 * {@code @Transactional} is applied via Spring's AOP proxy (self-invocation
 * inside a {@code @Controller} bypasses the proxy and silently ignores the
 * annotation).
 *
 * <p>Email resolution rules (all require active=true on the signup row):
 * <ul>
 *   <li>church=true  → email from {@code church_registration} (deleteFlag=false)</li>
 *   <li>church=false, clientId starts with "USR" → email from {@code app_user}
 *       (deleteFlag=false, enabled=true)</li>
 *   <li>church=false, clientId starts with "MBR", role != Child → email from
 *       {@code family_member} (deleteFlag=false, inactive=false)</li>
 *   <li>church=false, clientId starts with "MBR", role = Child → email must match
 *       the Head of Household in the same family; reset link is sent to that HoH email</li>
 * </ul>
 * In all cases the associated {@code service_client} must have
 * status='Active', deleteFlag=false, endDate >= today.
 */
@Controller
public class ForgotPasswordController {

    private static final Logger log = LoggerFactory.getLogger(ForgotPasswordController.class);

    private final LoginRepository              loginRepo;
    private final AppUserRepository            appUserRepo;
    private final ChurchRegistrationRepository churchRepo;
    private final FamilyMemberRepository       familyMemberRepo;
    private final ServiceClientRepository      serviceClientRepo;
    private final PasswordResetTokenRepository tokenRepo;
    private final EmailService                 emailService;
    private final PasswordResetService         passwordResetService;
    private final LoginProtectionService       loginProtection;

    @Value("${app.base-url}")
    private String baseUrl;

    public ForgotPasswordController(LoginRepository loginRepo,
                                    AppUserRepository appUserRepo,
                                    ChurchRegistrationRepository churchRepo,
                                    FamilyMemberRepository familyMemberRepo,
                                    ServiceClientRepository serviceClientRepo,
                                    PasswordResetTokenRepository tokenRepo,
                                    EmailService emailService,
                                    PasswordResetService passwordResetService,
                                    LoginProtectionService loginProtection) {
        this.loginProtection      = loginProtection;
        this.loginRepo            = loginRepo;
        this.appUserRepo          = appUserRepo;
        this.churchRepo           = churchRepo;
        this.familyMemberRepo     = familyMemberRepo;
        this.serviceClientRepo    = serviceClientRepo;
        this.tokenRepo            = tokenRepo;
        this.emailService         = emailService;
        this.passwordResetService = passwordResetService;
    }

    // ── Pages ─────────────────────────────────────────────────────────────────

    @GetMapping("/forgotPassword")
    public String forgotPage() {
        return "forward:/forgotPassword.html";
    }

    @GetMapping("/resetPassword")
    public String resetPage() {
        return "forward:/resetPassword.html";
    }

    // ── API ───────────────────────────────────────────────────────────────────

    /**
     * Step 1: Validate username + email, generate token, send reset email.
     * Always returns HTTP 200 with a generic message to prevent
     * username / email enumeration attacks.
     *
     * <p>Two abuse controls apply here, neither of which can lock a real user out:
     * a host already serving an IP-scope login block cannot pivot to the reset flow,
     * and a per-account cooldown in {@link PasswordResetService} suppresses repeat
     * emails (the previously sent link stays valid).
     */
    @ResponseBody
    @PostMapping("/api/forgot-password")
    public ResponseEntity<Map<String, String>> requestReset(@RequestBody Map<String, String> body,
                                                            HttpServletRequest request) {
        String username = trim(body.get("username"));
        String email    = trim(body.get("email"));

        // Refuse the reset flow to a host that is already blocked for login abuse.
        // Keyed on IP only — never on the username, because blocking by username here
        // would hand an attacker a way to deny the real owner their recovery path.
        LoginProtectionService.GuardResult guard = loginProtection.check(request, null);
        if (guard.blocked()) {
            loginProtection.recordBlockedAttempt(request, username, "/api/forgot-password");
            return ResponseEntity.status(429)
                    .header("Retry-After", String.valueOf(guard.retryAfterSeconds()))
                    .body(Map.of("error", LoginProtectionService.BLOCKED_MESSAGE));
        }

        if (username == null || email == null) {
            return bad("Username and email are required.");
        }

        try {
            // findActiveByUsername handles rows where deleted IS NULL (not just false)
            SignUp user = loginRepo.findActiveByUsername(username).orElse(null);
            log.debug("[ForgotPassword] lookup found={}", user != null);

            if (user != null && Boolean.TRUE.equals(user.getActive())) {
                log.debug("[ForgotPassword] clientId={} church={} active={} deleted={}",
                        user.getClientId(), user.getChurch(), user.getActive(), user.getDeleted());

                // For Child members the "expected" email is the HoH email, and
                // the reset link is sent to that HoH address (not the child's own).
                String actualEmail     = resolveEmail(user);
                String sendToEmail     = resolveSendToEmail(user, actualEmail);
                String serviceClientId = resolveServiceClientId(user);

                log.debug("[ForgotPassword] resolvedEmail={} sendTo={} provided={} serviceClientId={}",
                        EmailMask.mask(actualEmail), EmailMask.mask(sendToEmail), EmailMask.mask(email), serviceClientId);

                boolean emailMatches = actualEmail != null && actualEmail.equalsIgnoreCase(email);
                boolean subscriptionOk = serviceClientId != null
                        && serviceClientRepo.isActiveSubscription(serviceClientId, LocalDate.now());

                log.debug("[ForgotPassword] emailMatches={} subscriptionOk={}", emailMatches, subscriptionOk);

                if (emailMatches && subscriptionOk) {
                    // Delegate to service so @Transactional is applied via Spring proxy.
                    // Returns null when a link was already emailed inside the cooldown —
                    // that earlier link is still valid, so we simply send nothing.
                    String token = passwordResetService.createToken(username, sendToEmail);
                    if (token != null) {
                        String resetLink = baseUrl + "/resetPassword?token=" + token;
                        String churchName = resolveChurchName(user);
                        log.info("[ForgotPassword] sending reset email to {}", EmailMask.mask(sendToEmail));
                        // Email is sent AFTER the transaction commits (token is already in DB)
                        // Account recovery: delivered whatever the tenant's plan says.
                        emailService.sendAccountEmail(
                                sendToEmail,
                                "Password Reset — " + churchName,
                                buildResetEmail(username, resetLink, churchName));
                        log.info("[ForgotPassword] reset email sent");
                    } else {
                        log.info("[ForgotPassword] suppressed duplicate reset email (cooldown active)");
                    }
                }
            }
        } catch (Exception ex) {
            // Log but do not expose — always return the same generic message
            log.error("[ForgotPassword] error processing reset request", ex);
        }

        // Generic response regardless of whether the username/email matched
        return ResponseEntity.ok(Map.of("message",
                "If an account with that username and email exists, a reset link has been sent."));
    }

    /**
     * Step 2: Validate token, update the password.
     */
    @ResponseBody
    @Transactional
    @PostMapping("/api/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@RequestBody Map<String, String> body) {
        String token       = trim(body.get("token"));
        String newPassword = body.get("newPassword");

        if (token == null || newPassword == null || newPassword.isBlank()) {
            return bad("Token and new password are required.");
        }
        String pwPolicyError = com.churchgeniuspro.util.PasswordPolicy.validate(newPassword);
        if (pwPolicyError != null) {
            return bad(pwPolicyError);
        }

        PasswordResetToken prt = tokenRepo.findByToken(token).orElse(null);
        if (prt == null || prt.isUsed()) {
            return bad("This reset link is invalid or has already been used.");
        }
        if (prt.getExpiryTime().before(new Date())) {
            return bad("This reset link has expired. Please request a new one.");
        }

        // Update password — also handle NULL deleted here for consistency
        SignUp user = loginRepo.findActiveByUsername(prt.getUsername()).orElse(null);
        if (user == null) {
            return bad("Account not found.");
        }
        user.setPassword(PasswordUtil.encode(newPassword));
        user.setUpdated(new Date());
        loginRepo.save(user);

        // Mark token as used so it cannot be replayed
        prt.setUsed(true);
        tokenRepo.save(prt);

        // Completing a reset proves control of the account's mailbox, so it clears this
        // account's failure counters and releases any temporary block attached to the
        // username. This is what keeps every block genuinely self-recoverable: a user
        // caught behind one is never waiting on an administrator. Deliberately does not
        // touch IP-scope blocks — those belong to the host, not to the account.
        loginProtection.clearAccountCounters(prt.getUsername(), user.getClientId(), "PASSWORD_RESET");

        return ResponseEntity.ok(Map.of("message", "Password reset successfully. You can now log in."));
    }

    /**
     * Validate that a reset token is still valid.
     * Called by the reset page on load to show an error before the user submits.
     */
    @ResponseBody
    @GetMapping("/api/reset-password/validate")
    public ResponseEntity<Map<String, Object>> validateToken(@RequestParam String token) {
        PasswordResetToken prt = tokenRepo.findByToken(token).orElse(null);
        if (prt == null || prt.isUsed() || prt.getExpiryTime().before(new Date())) {
            return ResponseEntity.ok(Map.of("valid", false,
                    "message", "This reset link is invalid or has expired."));
        }
        // The reset form does not need the login name; a token holder learning it is a free lookup.
        return ResponseEntity.ok(Map.of("valid", true));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolves the email address that the user must supply on the forgot-password form.
     *
     * <ul>
     *   <li>church=true  → {@code church_registration} (deleteFlag=false)</li>
     *   <li>church=false, clientId starts with "USR" → {@code app_user}
     *       (deleteFlag=false, enabled=true)</li>
     *   <li>church=false, clientId starts with "MBR", role != Child → member's own email</li>
     *   <li>church=false, clientId starts with "MBR", role = Child  → Head of Household
     *       email for the same family (user must enter the HoH email to pass validation)</li>
     * </ul>
     */
    private String resolveEmail(SignUp user) {
        String clientId = user.getClientId();
        if (clientId == null) return null;

        if (Boolean.TRUE.equals(user.getChurch())) {
            // Church account: email in church_registration
            return churchRepo.findByClientIdAndDeleteFlagFalse(clientId)
                             .map(ChurchRegistration::getEmail)
                             .orElse(null);

        } else {
            // Non-church: distinguish by clientId prefix
            if (clientId.startsWith("USR")) {
                return appUserRepo.findByUserIdAndDeleteFlagFalse(clientId)
                                  .filter(AppUser::isEnabled)
                                  .map(AppUser::getEmail)
                                  .orElse(null);

            } else if (clientId.startsWith("MBR")) {
                // findByMemberRef already filters deleteFlag=false AND inactive=false
                FamilyMember member = familyMemberRepo.findByMemberRef(clientId).orElse(null);
                if (member == null) return null;

                // Child accounts: the expected email is the Head of Household's email
                if ("Child".equalsIgnoreCase(member.getRole()) && member.getFamily() != null) {
                    return resolveHohEmail(member.getFamily().getId());
                }

                return member.getEmail();
            }
        }
        return null;
    }

    /**
     * Returns the email address the reset link should actually be sent to.
     * For non-Child members this is the same as {@code resolvedEmail}.
     * For Child members the link is sent to the Head of Household email
     * (which is also what {@link #resolveEmail} returns, so they are the same value here —
     * kept as a separate method for clarity and future flexibility).
     */
    private String resolveSendToEmail(SignUp user, String resolvedEmail) {
        // Currently the HoH email is already returned by resolveEmail for Child accounts,
        // so resolvedEmail IS the send-to address in all cases.
        return resolvedEmail;
    }

    /**
     * Finds the email address of the active Head of Household in the given family.
     * Uses the same role strings recognised elsewhere in the codebase:
     * "Head" or "Head of Household" (case-insensitive).
     */
    private String resolveHohEmail(Integer familyId) {
        if (familyId == null) return null;
        return familyMemberRepo.findActiveMembersByFamilyId(familyId)
                .stream()
                .filter(m -> {
                    String r = m.getRole();
                    return r != null && (r.equalsIgnoreCase("Head")
                            || r.equalsIgnoreCase("Head of Household"));
                })
                .map(FamilyMember::getEmail)
                .filter(e -> e != null && !e.isBlank())
                .findFirst()
                .orElse(null);
    }

    /**
     * Resolves the service_client clientId so we can validate the subscription.
     *
     * <ul>
     *   <li>church=true  → signup.clientId IS the service_client clientId</li>
     *   <li>church=false, USR → app_user.clientId IS the service_client clientId</li>
     *   <li>church=false, MBR → family_member.appClientId IS the service_client clientId</li>
     * </ul>
     */
    private String resolveServiceClientId(SignUp user) {
        String clientId = user.getClientId();
        if (clientId == null) return null;

        if (Boolean.TRUE.equals(user.getChurch())) {
            return clientId; // signup.client_id = service_client.client_id for churches

        } else {
            if (clientId.startsWith("USR")) {
                return appUserRepo.findByUserIdAndDeleteFlagFalse(clientId)
                                  .filter(AppUser::isEnabled)
                                  .map(AppUser::getClientId)
                                  .orElse(null);

            } else if (clientId.startsWith("MBR")) {
                return familyMemberRepo.findByMemberRef(clientId)
                                       .map(FamilyMember::getAppClientId)
                                       .orElse(null);
            }
        }
        return null;
    }

    /**
     * Resolves the church name for display in the reset email.
     */
    private String resolveChurchName(SignUp user) {
        String clientId = user.getClientId();
        if (clientId == null) return "Church Genius Pro";

        if (Boolean.TRUE.equals(user.getChurch())) {
            return churchRepo.findByClientIdAndDeleteFlagFalse(clientId)
                    .map(ChurchRegistration::getChurchName)
                    .filter(n -> n != null && !n.isBlank())
                    .orElse("Church Genius Pro");
        } else {
            // For USR / MBR accounts, derive the org clientId then look up church name
            String orgClientId = resolveServiceClientId(user);
            if (orgClientId != null) {
                return emailService.getChurchName(orgClientId);
            }
        }
        return "Church Genius Pro";
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static ResponseEntity<Map<String, String>> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    private String buildResetEmail(String username, String resetLink, String churchName) {
        String safeChurch = churchName != null
                ? churchName.replace("&", "&amp;").replace("<", "&lt;") : "";
        return "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'/></head>"
             + "<body style='margin:0;padding:0;background:#f5f6fa;"
             +       "font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;'>"
             + "<table width='100%' cellpadding='0' cellspacing='0' style='background:#f5f6fa;padding:40px 20px;'>"
             + "<tr><td align='center'>"
             + "<table width='100%' cellpadding='0' cellspacing='0'"
             +       "style='max-width:520px;background:#fff;border-radius:16px;"
             +              "box-shadow:0 4px 24px rgba(0,0,0,.08);overflow:hidden;'>"
             + "<tr><td style='background:#673147;padding:32px 40px;text-align:center;'>"
             +   "<p style='margin:0;font-size:22px;font-weight:700;color:#fff;'>" + safeChurch + "</p>"
             +   "<p style='margin:8px 0 0;font-size:13px;color:rgba(255,255,255,.75);'>Password Reset</p>"
             + "</td></tr>"
             + "<tr><td style='padding:36px 40px;'>"
             +   "<p style='margin:0 0 16px;font-size:16px;color:#1a1a2e;font-weight:600;'>"
             +       "Hi <strong>" + username + "</strong>,</p>"
             +   "<p style='margin:0 0 16px;font-size:14px;color:#555;line-height:1.7;'>"
             +       "We received a request to reset your password. "
             +       "Click the button below to set a new password. "
             +       "This link will expire in <strong>10 minutes</strong>.</p>"
             + "<table cellpadding='0' cellspacing='0' style='margin:0 auto 28px;'>"
             + "<tr><td style='background:#673147;border-radius:8px;'>"
             +   "<a href='" + resetLink + "'"
             +      "style='display:inline-block;padding:14px 36px;font-size:15px;"
             +             "font-weight:600;color:#fff;text-decoration:none;'>Reset My Password</a>"
             + "</td></tr></table>"
             + "<p style='margin:0 0 8px;font-size:12px;color:#aaa;'>"
             +   "If you didn't request this, please ignore this email — your password won't change.</p>"
             + "<p style='margin:0;font-size:12px;word-break:break-all;'>"
             +   "<a href='" + resetLink + "' style='color:#3a7bd5;'>" + resetLink + "</a></p>"
             + "</td></tr>"
             + "<tr><td style='background:#f8f9ff;padding:20px 40px;border-top:1px solid #e8eaf6;text-align:center;'>"
             +   "<p style='margin:0;font-size:12px;color:#aaa;'>Sent by " + safeChurch + "</p>"
             + "</td></tr>"
             + "</table></td></tr></table></body></html>";
    }
}
