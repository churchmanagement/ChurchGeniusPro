package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.model.SignupBO;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.SignupService;
import com.churchgeniuspro.service.SubscriptionService;
import com.churchgeniuspro.service.VerificationStore;
import com.churchgeniuspro.util.PasswordUtil;
import com.churchgeniuspro.util.PublicSendLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles all sign-up flows.
 *
 * <h3>Church signup ({@code type=church})</h3>
 * <pre>
 *  GET  /signup?clientId=&lt;enc&gt;&amp;type=church  — page forward
 *  GET  /api/signup/validate               — decrypt + validate chain
 *  POST /api/signup/send-code              — validate creds, send 6-digit OTP
 *  POST /api/signup/verify                 — verify OTP, persist SignUp record
 * </pre>
 *
 * <h3>User signup ({@code type=user})</h3>
 * Same steps but the URL uses {@code userId=} and validation is against
 * the {@code app_user} table instead of {@code church_registration}.
 */
@Controller
public class SignupController {

    private final SignupService                 signupService;
    private final LoginRepository               loginRepository;
    private final AppUserRepository             appUserRepository;
    private final ChurchRegistrationRepository  churchRegistrationRepository;
    private final ServiceClientRepository       serviceClientRepository;
    private final VerificationStore             verificationStore;
    private final EmailService                  emailService;
    private final PublicSendLimiter             sendLimiter;

    public SignupController(SignupService                signupService,
                            LoginRepository               loginRepository,
                            AppUserRepository             appUserRepository,
                            ChurchRegistrationRepository  churchRegistrationRepository,
                            ServiceClientRepository       serviceClientRepository,
                            VerificationStore             verificationStore,
                            EmailService                  emailService,
                            PublicSendLimiter             sendLimiter) {
        this.sendLimiter                 = sendLimiter;
        this.signupService               = signupService;
        this.loginRepository             = loginRepository;
        this.appUserRepository           = appUserRepository;
        this.churchRegistrationRepository = churchRegistrationRepository;
        this.serviceClientRepository     = serviceClientRepository;
        this.verificationStore           = verificationStore;
        this.emailService                = emailService;
    }

    /**
     * Demo Role Access rows mark the logins that tenant provisioning created with a
     * generated username and password (every demo/trial staff login has one). Optional
     * so the controller still constructs without a Spring context.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.repository.DemoRoleAccessRepository demoRoleAccessRepository;

    /** Test seam. */
    public void setDemoRoleAccessRepository(com.churchgeniuspro.repository.DemoRoleAccessRepository r) {
        this.demoRoleAccessRepository = r;
    }

    // ── Page Route ────────────────────────────────────────────────────────────

    /** Forwards the browser to the static sign-up page; all query params are preserved. */
    @GetMapping("/signup")
    public String signupPage() {
        return "forward:/signup.html";
    }

    // ── Step 1 · Validate Invitation Link ─────────────────────────────────────

    /**
     * Decrypts the invitation token and performs the full validation chain.
     *
     * <p>Church: {@code GET /api/signup/validate?clientId=<enc>&type=church}
     * <p>User:   {@code GET /api/signup/validate?userId=<enc>&type=user}
     *
     * <p>Success response: {@code { status:"valid", decryptedClientId, maskedEmail,
     * name [, churchName] }}
     * <p>Failure response: {@code { status:"invalid", message }}
     */
    @ResponseBody
    @GetMapping("/api/signup/validate")
    public ResponseEntity<Map<String, Object>> validate(
            @RequestParam(required = false) String clientId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String type) {

        Map<String, Object> res = new HashMap<>();
        try {
            // The parameter is an opaque invitation handle: a staff invite token or a
            // church registration token. Its type comes from which table it is found
            // in — nothing is decrypted or inferred from a prefix.
            Invite inv = resolveInvite(clientId);
            if (inv == null) {
                res.put("status",  "invalid");
                res.put("message", "This invitation link is invalid, has been used, or has expired.");
                return ResponseEntity.status(400).body(res);
            }
            if ("church".equals(inv.type)) {
                return validateChurchLink(inv, res);
            } else {
                return validateUserLink(inv, res);
            }
        } catch (Exception e) {
            res.put("status",  "invalid");
            res.put("message", "Malformed or expired invitation link.");
            return ResponseEntity.status(400).body(res);
        }
    }

    // ── Step 2 · Send OTP ────────────────────────────────────────────────────

    /**
     * Validates the chosen username/password and emails a 6-digit OTP to the
     * address on record.
     *
     * <p>Body (JSON): {@code { clientId, type, username, password }}
     * — {@code clientId} is the <em>decrypted</em> value returned by
     *   {@code /api/signup/validate}.
     */
    @ResponseBody
    @PostMapping("/api/signup/send-code")
    public ResponseEntity<Map<String, Object>> sendCode(@RequestBody Map<String, String> body,
                                                        HttpServletRequest request) {
        Map<String, Object> res = new HashMap<>();

        Invite inv      = resolveInvite(body.get("clientId"));   // the handle validate() handed back
        String clientId = inv != null ? inv.clientId : null;
        String type     = inv != null ? inv.type : null;
        String username = body.get("username");
        String password = body.get("password");

        if (inv == null) {
            res.put("status",  "error");
            res.put("message", "This invitation link is invalid, has been used, or has expired.");
            return ResponseEntity.status(400).body(res);
        }
        if (isBlank(clientId) || isBlank(type) || isBlank(username) || isBlank(password)) {
            res.put("status",  "error");
            res.put("message", "Missing required fields.");
            return ResponseEntity.status(400).body(res);
        }

        // Username must be unique
        if (loginRepository.existsByUsername(username.trim())) {
            res.put("status",  "error");
            res.put("message", "Username '" + username.trim() + "' is already taken.");
            return ResponseEntity.status(409).body(res);
        }

        // Application-wide password policy (length + upper/lower/digit/special)
        String policyError = com.churchgeniuspro.util.PasswordPolicy.validate(password);
        if (policyError != null) {
            res.put("status",  "error");
            res.put("message", policyError);
            return ResponseEntity.status(400).body(res);
        }

        // The OTP goes to the address on record for the invitation — never one the caller supplies.
        String email = inv.email;
        if (email == null) {
            res.put("status",  "error");
            res.put("message", "Could not find the email address for this invitation.");
            return ResponseEntity.status(400).body(res);
        }

        // Generate, store and send OTP
        // Each call re-issues the code and e-mails the invitee again: bounded per
        // network origin and per invitation (security audit P2).
        String limited = sendLimiter.check(PublicSendLimiter.SIGNUP_OTP, request, inv.handle(), null);
        if (limited != null) {
            res.put("status",  "error");
            res.put("message", limited);
            return ResponseEntity.status(429).body(res);
        }

        String code = verificationStore.generateAndStore(clientId, type, email);
        String churchName = resolveChurchName(clientId, type);
        emailService.sendGenericEmail(
                email,
                "Your " + churchName + " Verification Code",
                buildOtpEmailHtml(code, churchName));

        res.put("status",      "success");
        res.put("message",     "Verification code sent.");
        res.put("maskedEmail", maskEmail(email));
        return ResponseEntity.ok(res);
    }

    // ── Step 3 · Verify OTP & Persist Signup ─────────────────────────────────

    /**
     * Verifies the 6-digit OTP and — on success — persists the {@link SignUp} record.
     *
     * <p>Body (JSON): {@code { clientId, type, username, password, code }}
     */
    @ResponseBody
    @PostMapping("/api/signup/verify")
    public ResponseEntity<Map<String, Object>> verifyAndSignup(@RequestBody Map<String, String> body) {
        Map<String, Object> res = new HashMap<>();

        Invite inv      = resolveInvite(body.get("clientId"));
        String clientId = inv != null ? inv.clientId : null;
        String type     = inv != null ? inv.type : null;
        String username = body.get("username");
        String password = body.get("password");
        String code     = body.get("code");

        if (inv == null) {
            res.put("status",  "error");
            res.put("message", "This invitation link is invalid, has been used, or has expired.");
            return ResponseEntity.status(400).body(res);
        }
        if (isBlank(clientId) || isBlank(type) || isBlank(username)
                || isBlank(password) || isBlank(code)) {
            res.put("status",  "error");
            res.put("message", "Missing required fields.");
            return ResponseEntity.status(400).body(res);
        }

        // Verify OTP (also checks expiry)
        if (!verificationStore.validate(clientId, type, code)) {
            res.put("status",  "error");
            res.put("message", "Invalid or expired verification code. Please request a new one.");
            return ResponseEntity.status(400).body(res);
        }

        // One login per invitation: a second signup row for the same id used to break
        // role-switching (Optional lookups) and, for a church, minted a second owner.
        //
        // Trial registration is the one flow that invites a user who ALREADY has a
        // login: provisioning creates the SuperAdmin together with a generated
        // username/password (tracked in demo_role_access), then emails this invitation
        // so the registrant can choose their own. That login is claimed here — its
        // credentials replaced, no second row — rather than refused. Only a login
        // provisioning created qualifies; anything else keeps the refusal below, and a
        // spent invitation never reaches this point (its token is cleared on use).
        SignUp provisioned = null;
        if ("user".equals(type)) {
            SignUp existing = loginRepository.findActiveSignupByUserId(clientId).orElse(null);
            if (existing != null && existing.getId() != null && demoRoleAccessRepository != null
                    && demoRoleAccessRepository.findBySignupId(existing.getId()).isPresent()) {
                provisioned = existing;
            }
        }
        if ((provisioned == null && loginRepository.findActiveSignupByUserId(clientId).isPresent())
                || ("church".equals(type) && loginRepository.findByClientId(clientId)
                        .filter(su -> Boolean.TRUE.equals(su.getChurch()) && Boolean.TRUE.equals(su.getActive())
                                   && !Boolean.TRUE.equals(su.getDeleted())).isPresent())) {
            res.put("status",  "error");
            res.put("message", "An account has already been created from this invitation.");
            return ResponseEntity.status(409).body(res);
        }

        // Race-condition guard: re-check username uniqueness (the login being claimed
        // may keep its own generated username)
        boolean keepsOwnUsername = provisioned != null && username.trim().equals(provisioned.getUsername());
        if (!keepsOwnUsername && loginRepository.existsByUsername(username.trim())) {
            res.put("status",  "error");
            res.put("message", "Username '" + username.trim() + "' is already taken.");
            return ResponseEntity.status(409).body(res);
        }

        // Password policy (server-authoritative; also checked at send-code)
        String pwPolicyError = com.churchgeniuspro.util.PasswordPolicy.validate(password);
        if (pwPolicyError != null) {
            res.put("status",  "error");
            res.put("message", pwPolicyError);
            return ResponseEntity.status(400).body(res);
        }

        if (provisioned != null) {
            // Claim the provisioned login: the registrant's own username and password
            // replace the generated ones, and the generated password is no longer shown.
            provisioned.setUsername(username.trim());
            provisioned.setPassword(PasswordUtil.encode(password));
            provisioned.setDemoPassword(null);
            provisioned.setActive(true);
            provisioned.setLocked(false);
            provisioned.setUpdated(new Date());
            loginRepository.save(provisioned);
            demoRoleAccessRepository.findBySignupId(provisioned.getId()).ifPresent(dra -> {
                dra.setUsername(username.trim());
                demoRoleAccessRepository.save(dra);
            });
            verificationStore.remove(clientId, type);
            inv.appUser.setInviteToken(null);        // the invite link is now spent
            appUserRepository.save(inv.appUser);
            res.put("status",  "success");
            res.put("message", "Account created successfully.");
            return ResponseEntity.ok(res);
        }

        // Persist the SignUp record
        Date   now    = new Date();
        SignUp signUp = new SignUp();
        signUp.setClientId(clientId);
        signUp.setUsername(username.trim());
        signUp.setPassword(PasswordUtil.encode(password));
        signUp.setActive(true);
        signUp.setDeleted(false);
        signUp.setLocked(false);
        signUp.setChurch("church".equalsIgnoreCase(type));
        signUp.setCreated(now);
        signUp.setUpdated(now);

        // For church signups: link church_registration ID
        if ("church".equalsIgnoreCase(type)) {
            churchRegistrationRepository
                    .findByClientIdAndDeleteFlagFalse(clientId)
                    .ifPresent(cr -> signUp.setChurchId(cr.getId()));
        }

        loginRepository.save(signUp);
        verificationStore.remove(clientId, type);   // clean up used code
        if (inv.appUser != null) {                  // the invite link is now spent
            inv.appUser.setInviteToken(null);
            appUserRepository.save(inv.appUser);
        }

        res.put("status",  "success");
        res.put("message", "Account created successfully.");
        return ResponseEntity.ok(res);
    }

    // The former legacy `POST /api/signup` ("kept for backward compatibility") was
    // removed: it created an ACTIVE login for any caller-supplied clientId with no
    // invitation token and no OTP, which — combined with the member-lookup on login —
    // let an anonymous caller obtain a member-portal session as any member of any
    // church. The supported flow is /validate → /send-code → /verify.

    // ── Private helpers ───────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> validateChurchLink(
            Invite inv, Map<String, Object> res) throws Exception {

        String decrypted = inv.clientId;

        // Check church_registration
        ChurchRegistration cr = churchRegistrationRepository
                .findByClientIdAndDeleteFlagFalse(decrypted)
                .orElse(null);
        if (cr == null) {
            res.put("status",  "invalid");
            res.put("message", "No church registration found for this link.");
            return ResponseEntity.status(400).body(res);
        }

        // Check service_client
        String scError = validateServiceClient(decrypted);
        if (scError != null) {
            res.put("status",  "invalid");
            res.put("message", scError);
            return ResponseEntity.status(400).body(res);
        }

        res.put("status",            "valid");
        res.put("type",              "church");
        res.put("decryptedClientId", inv.handle);   // the page carries the handle, not the id
        res.put("maskedEmail",       maskEmail(cr.getEmail()));
        res.put("name",              trim(cr.getFirstName()) + " " + trim(cr.getLastName()));
        res.put("churchName",        cr.getChurchName() != null ? cr.getChurchName() : "");
        return ResponseEntity.ok(res);
    }

    private ResponseEntity<Map<String, Object>> validateUserLink(
            Invite inv, Map<String, Object> res) throws Exception {

        String decrypted = inv.clientId;
        AppUser user = inv.appUser;

        // Check service_client using the organization ID (CGP-XXXXX) stored on the
        // app_user record — NOT the UUID userId which was decrypted from the URL.
        String scError = validateServiceClient(user.getClientId());
        if (scError != null) {
            res.put("status",  "invalid");
            res.put("message", scError);
            return ResponseEntity.status(400).body(res);
        }

        res.put("status",            "valid");
        res.put("type",              "user");
        res.put("decryptedClientId", inv.handle);   // the page carries the handle, not the id
        res.put("maskedEmail",       maskEmail(user.getEmail()));
        res.put("name",              trim(user.getFirstName()) + " " + trim(user.getLastName()));
        return ResponseEntity.ok(res);
    }

    /**
     * Returns {@code null} when the service client is valid, or an error message.
     * Conditions: status=Active, delete_flag=false, end_date > today.
     */
    private String validateServiceClient(String clientId) {
        ServiceClient sc = serviceClientRepository
                .findByClientIdAndStatusAndDeleteFlagFalse(clientId, "Active")
                .orElse(null);
        if (sc == null) {
            return "This invitation link is inactive or has been revoked.";
        }
        // Same rule as sign-in: access (and so an invitation) closes ON the end date.
        if (SubscriptionService.isExpired(sc.getEndDate(), com.churchgeniuspro.util.AppClock.today())) {
            return "This invitation link has expired. Please contact your administrator.";
        }
        return null;
    }

    /**
     * Infers the signup type from the prefix of a decrypted token.
     * <ul>
     *   <li>{@code CG...} — church (service_client clientId)</li>
     *   <li>{@code US...} (USR...) — app user (app_user userId)</li>
     * </ul>
     */
    /**
     * Resolves the church name for OTP email subjects.
     * For church type: looks up church_registration directly by clientId.
     * For user type: clientId is a userId — look up AppUser to get appClientId,
     *                then delegate to EmailService.
     */
    private String resolveChurchName(String clientId, String type) {
        if ("church".equalsIgnoreCase(type)) {
            return emailService.getChurchName(clientId);
        } else if ("user".equalsIgnoreCase(type)) {
            return appUserRepository.findByUserIdAndDeleteFlagFalse(clientId)
                    .map(u -> emailService.getChurchName(u.getClientId()))
                    .orElse("Church Genius Pro");
        }
        return "Church Genius Pro";
    }

    /** A resolved invitation handle: what it is for, and the record behind it. */
    private record Invite(String handle, String type, String clientId, String email, AppUser appUser) {}

    /**
     * Resolves the opaque handle in the invitation URL. Staff invites are
     * {@code app_user.invite_token}; church-owner invites are
     * {@code service_client.registration_token}. Unknown, spent and foreign values
     * all come back null — the handle is looked up, never decoded.
     */
    private Invite resolveInvite(String handle) {
        if (isBlank(handle)) return null;
        String h = handle.trim();
        AppUser user = appUserRepository.findByInviteTokenAndDeleteFlagFalse(h).orElse(null);
        if (user != null) {
            return new Invite(h, "user", user.getUserId(), user.getEmail(), user);
        }
        return serviceClientRepository.findByRegistrationTokenAndStatusAndDeleteFlagFalse(h, "Active")
                .map(sc -> churchRegistrationRepository.findByClientIdAndDeleteFlagFalse(sc.getClientId())
                        .map(cr -> new Invite(h, "church", sc.getClientId(), cr.getEmail(), null))
                        .orElse(null))
                .orElse(null);
    }

    // ── Static utilities ──────────────────────────────────────────────────────

    /**
     * Masks an email for display — e.g. {@code john.doe@example.com} →
     * {@code jo***@exa***.com}.
     */
    static String maskEmail(String email) {
        if (email == null || email.isEmpty()) return "your email";
        int atIdx = email.indexOf('@');
        if (atIdx < 0) return "your email";
        String   user  = email.substring(0, atIdx);
        String   domain = email.substring(atIdx + 1);
        String   mUser = user.length() <= 2
                ? user.charAt(0) + "***"
                : user.substring(0, 2) + "***";
        String[] parts = domain.split("\\.", 2);
        String   tld   = parts.length > 1 ? "." + parts[1] : "";
        String   host  = parts[0];
        String   mHost = host.length() <= 3 ? host : host.substring(0, 3) + "***";
        return mUser + "@" + mHost + tld;
    }

    /** Builds the HTML body for the 6-digit OTP email. */
    private static String buildOtpEmailHtml(String code, String churchName) {
        String safeChurch = churchName != null
                ? churchName.replace("&", "&amp;").replace("<", "&lt;") : "";
        return "<!DOCTYPE html><html lang='en'><head>"
             + "<meta charset='UTF-8'/>"
             + "<meta name='viewport' content='width=device-width,initial-scale=1.0'/>"
             + "</head>"
             + "<body style='margin:0;padding:0;background:#f5f6fa;"
             +   "font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;'>"
             + "<table width='100%' cellpadding='0' cellspacing='0' style='padding:40px 20px;'>"
             + "<tr><td align='center'>"
             + "<table style='max-width:480px;width:100%;background:#fff;border-radius:16px;"
             +   "box-shadow:0 4px 24px rgba(0,0,0,0.08);overflow:hidden;'>"
             // Header band
             + "<tr><td style='background:#673147;padding:28px 40px;text-align:center;'>"
             +   "<p style='margin:0;font-size:20px;font-weight:700;color:#fff;'>" + safeChurch + "</p>"
             +   "<p style='margin:6px 0 0;font-size:12px;color:rgba(255,255,255,0.75);'>"
             +     "Email Verification</p>"
             + "</td></tr>"
             // Body
             + "<tr><td style='padding:36px 40px;text-align:center;'>"
             +   "<p style='font-size:15px;color:#333;margin:0 0 24px;line-height:1.6;'>"
             +     "Use the code below to complete your sign-up.<br/>"
             +     "It expires in <strong>10 minutes</strong>.</p>"
             +   "<div style='display:inline-block;background:#f5eaef;border-radius:12px;"
             +              "padding:20px 44px;margin-bottom:24px;'>"
             +     "<p style='margin:0;font-size:40px;font-weight:800;letter-spacing:12px;"
             +              "color:#673147;font-family:monospace;'>" + code + "</p>"
             +   "</div>"
             +   "<p style='font-size:12px;color:#aaa;margin:0;'>"
             +     "If you did not request this, please ignore this email.</p>"
             + "</td></tr>"
             // Footer
             + "<tr><td style='background:#f8f9ff;padding:16px 40px;"
             +   "border-top:1px solid #e8eaf6;text-align:center;'>"
             +   "<p style='margin:0;font-size:11px;color:#bbb;'>"
             +     "Sent by <strong>" + safeChurch + "</strong></p>"
             + "</td></tr>"
             + "</table></td></tr></table></body></html>";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
