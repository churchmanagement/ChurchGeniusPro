package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.PolicyAcceptance;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.model.ChurchRegistrationBO;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.PolicyAcceptanceRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.service.ChurchRegistrationService;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.VerificationStore;
import com.churchgeniuspro.util.PasswordUtil;
import com.churchgeniuspro.util.PolicyVersions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Handles HTTP requests for the Church Registration feature.
 *
 * <h3>Combined Registration + Signup flow</h3>
 * <pre>
 *  GET  /churchregistration                    — page forward
 *  GET  /api/churchregistration/status         — check if already registered
 *  POST /api/churchregistration/initiate       — validate form + send OTP
 *  POST /api/churchregistration/verify         — verify OTP, save church + signup
 *  POST /api/churchregistration                — legacy save (kept for compat)
 * </pre>
 */
@Controller
public class ChurchRegistrationController {

    private final ChurchRegistrationService churchRegistrationService;
    private final LoginRepository           loginRepository;
    private final VerificationStore         verificationStore;
    private final EmailService              emailService;
    private final ServiceClientRepository   serviceClientRepository;
    private final PolicyAcceptanceRepository policyAcceptanceRepository;

    public ChurchRegistrationController(ChurchRegistrationService churchRegistrationService,
                                        LoginRepository           loginRepository,
                                        VerificationStore         verificationStore,
                                        EmailService              emailService,
                                        ServiceClientRepository   serviceClientRepository,
                                        PolicyAcceptanceRepository policyAcceptanceRepository) {
        this.churchRegistrationService = churchRegistrationService;
        this.loginRepository           = loginRepository;
        this.verificationStore         = verificationStore;
        this.emailService              = emailService;
        this.serviceClientRepository   = serviceClientRepository;
        this.policyAcceptanceRepository = policyAcceptanceRepository;
    }

    // ── Step 0 · Already-registered check ────────────────────────────────────

    /**
     * Returns {@code { "registered": true/false }} for a given decrypted clientId.
     * The frontend calls this after the service-client link is validated so it can
     * show the "already registered" screen before displaying the form.
     */
    @ResponseBody
    @GetMapping({"/api/churchregistration/status", "/public/churchregistration/status"})
    public ResponseEntity<Map<String, Object>> status(@RequestParam String clientId) {
        Map<String, Object> res = new HashMap<>();
        Optional<SignUp> existing = loginRepository.findByClientId(clientId);
        // Only treat as registered if the signup exists, is a church account,
        // AND has NOT been invalidated by a reapprove (deleted=false, active=true)
        boolean registered = existing.isPresent()
                && Boolean.TRUE.equals(existing.get().getChurch())
                && !Boolean.TRUE.equals(existing.get().getDeleted())
                && Boolean.TRUE.equals(existing.get().getActive());
        res.put("registered", registered);
        return ResponseEntity.ok(res);
    }

    // ── Step 0b · Prefill data from service_client ───────────────────────────

    /**
     * Returns service_client fields for the given decrypted clientId so the
     * registration form can be pre-populated with data already on file.
     */
    @ResponseBody
    @GetMapping({"/api/churchregistration/prefill", "/public/churchregistration/prefill"})
    public ResponseEntity<Map<String, Object>> prefill(@RequestParam String clientId) {
        Map<String, Object> res = new HashMap<>();
        ServiceClient sc = serviceClientRepository.findByClientId(clientId).orElse(null);
        if (sc == null) {
            res.put("found", false);
            return ResponseEntity.ok(res);
        }

        // Split name into firstName / lastName (split on first space)
        String fullName   = sc.getName() != null ? sc.getName().trim() : "";
        String firstName  = "";
        String lastName   = "";
        if (!fullName.isEmpty()) {
            int spaceIdx = fullName.indexOf(' ');
            if (spaceIdx > 0) {
                firstName = fullName.substring(0, spaceIdx).trim();
                lastName  = fullName.substring(spaceIdx + 1).trim();
            } else {
                firstName = fullName;
            }
        }

        res.put("found",       true);
        res.put("firstName",   firstName);
        res.put("lastName",    lastName);
        res.put("email",       sc.getEmail()       != null ? sc.getEmail()       : "");
        res.put("phone",       sc.getPhone()       != null ? sc.getPhone()       : "");
        res.put("churchName",  sc.getChurchName()  != null ? sc.getChurchName()  : "");
        res.put("address1",    sc.getAddressLine1() != null ? sc.getAddressLine1() : "");
        res.put("address2",    sc.getAddressLine2() != null ? sc.getAddressLine2() : "");
        res.put("city",        sc.getCity()        != null ? sc.getCity()        : "");
        res.put("state",       sc.getState()       != null ? sc.getState()       : "");
        res.put("country",     sc.getCountry()     != null ? sc.getCountry()     : "USA");
        res.put("pinCode",     sc.getPinCode()     != null ? sc.getPinCode()     : "");
        return ResponseEntity.ok(res);
    }

    // ── Step 1 · Validate form + send OTP ────────────────────────────────────

    /**
     * Validates the registration form (including credentials) and, if everything
     * is correct, emails a 6-digit OTP to the address entered in the form.
     *
     * <p>Expected body fields: {@code clientId, firstName, lastName, email,
     * username, password, confirmPassword, declarationCheck}
     */
    @ResponseBody
    @PostMapping("/api/churchregistration/initiate")
    public ResponseEntity<Map<String, Object>> initiate(@RequestBody Map<String, Object> body) {
        Map<String, Object> res = new HashMap<>();

        String clientId      = str(body, "clientId");
        String firstName     = str(body, "firstName");
        String lastName      = str(body, "lastName");
        String email         = str(body, "email");
        String username      = str(body, "username");
        String password      = str(body, "password");
        Object declaration   = body.get("declarationCheck");
        Object privacy       = body.get("privacyCheck");

        // Required-field checks
        if (isBlank(clientId) || isBlank(firstName) || isBlank(lastName)
                || isBlank(email) || isBlank(username) || isBlank(password)) {
            res.put("status",  "error");
            res.put("message", "Please fill in all required fields.");
            return ResponseEntity.status(400).body(res);
        }

        // Terms of Service / Acceptable Use must be accepted
        if (!isChecked(declaration)) {
            res.put("status",  "error");
            res.put("message", "You must accept the Terms of Service and Acceptable Use Policy to proceed.");
            return ResponseEntity.status(400).body(res);
        }

        // Privacy / Cookie policies must be acknowledged
        if (!isChecked(privacy)) {
            res.put("status",  "error");
            res.put("message", "You must acknowledge the Privacy Policy and Cookie Policy to proceed.");
            return ResponseEntity.status(400).body(res);
        }

        // Password length
        if (password.length() < 8) {
            res.put("status",  "error");
            res.put("message", "Password must be at least 8 characters.");
            return ResponseEntity.status(400).body(res);
        }

        // Username uniqueness
        if (loginRepository.existsByUsername(username.trim())) {
            res.put("status",  "error");
            res.put("field",   "username");
            res.put("message", "Username '" + username.trim() + "' is already taken.");
            return ResponseEntity.status(409).body(res);
        }

        // Guard against duplicate registration
        Optional<SignUp> existing = loginRepository.findByClientId(clientId);
        if (existing.isPresent() && Boolean.TRUE.equals(existing.get().getChurch())) {
            res.put("status",  "error");
            res.put("message", "This church has already been registered.");
            return ResponseEntity.status(409).body(res);
        }

        // Generate and email OTP
        try {
            String code = verificationStore.generateAndStore(clientId, "church", email.trim());
            ServiceClient sc = serviceClientRepository.findByClientId(clientId).orElse(null);
            String churchName = (sc != null && sc.getChurchName() != null && !sc.getChurchName().isBlank())
                    ? sc.getChurchName() : "Church Genius Pro";
            emailService.sendGenericEmail(
                    email.trim(),
                    "Your " + churchName + " Verification Code",
                    buildOtpEmailHtml(code, churchName));
        } catch (Exception e) {
            res.put("status",  "error");
            res.put("message", "Failed to send verification email. Please try again.");
            return ResponseEntity.status(500).body(res);
        }

        res.put("status",      "success");
        res.put("maskedEmail", maskEmail(email.trim()));
        return ResponseEntity.ok(res);
    }

    // ── Step 2 · Verify OTP + save all ───────────────────────────────────────

    /**
     * Verifies the 6-digit OTP, saves the {@code church_registration} record,
     * creates the {@code signup} record, and sends a confirmation email.
     *
     * <p>Expected body: all form fields from {@code initiate} plus {@code code}.
     */
    @ResponseBody
    @PostMapping("/api/churchregistration/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestBody Map<String, Object> body,
                                                      HttpServletRequest request) {
        Map<String, Object> res = new HashMap<>();

        String clientId  = str(body, "clientId");
        String username  = str(body, "username");
        String password  = str(body, "password");
        String code      = str(body, "code");

        if (isBlank(clientId) || isBlank(username) || isBlank(password) || isBlank(code)) {
            res.put("status",  "error");
            res.put("message", "Missing required fields.");
            return ResponseEntity.status(400).body(res);
        }

        // Policy acceptance is mandatory to register — re-enforce on the final save step
        // so the requirement cannot be bypassed by calling this endpoint directly.
        if (!isChecked(body.get("declarationCheck"))) {
            res.put("status",  "error");
            res.put("message", "You must accept the Terms of Service and Acceptable Use Policy to register.");
            return ResponseEntity.status(400).body(res);
        }
        if (!isChecked(body.get("privacyCheck"))) {
            res.put("status",  "error");
            res.put("message", "You must acknowledge the Privacy Policy and Cookie Policy to register.");
            return ResponseEntity.status(400).body(res);
        }

        // Verify OTP
        if (!verificationStore.validate(clientId, "church", code)) {
            res.put("status",  "error");
            res.put("message", "Invalid or expired verification code. Please request a new one.");
            return ResponseEntity.status(400).body(res);
        }

        // Re-check username (race-condition guard)
        if (loginRepository.existsByUsername(username.trim())) {
            res.put("status",  "error");
            res.put("message", "Username '" + username.trim() + "' is already taken.");
            return ResponseEntity.status(409).body(res);
        }

        // Application-wide password policy (length + upper/lower/digit/special)
        String pwPolicyError = com.churchgeniuspro.util.PasswordPolicy.validate(password);
        if (pwPolicyError != null) {
            res.put("status",  "error");
            res.put("message", pwPolicyError);
            return ResponseEntity.status(400).body(res);
        }

        // Build ChurchRegistrationBO from body
        ChurchRegistrationBO bo = new ChurchRegistrationBO();
        bo.setClientId(clientId);
        bo.setFirstName(str(body, "firstName"));
        bo.setLastName(str(body, "lastName"));
        bo.setChurchName(str(body, "churchName"));
        bo.setEmail(str(body, "email"));
        bo.setPhone(str(body, "phone"));
        bo.setAddress1(str(body, "address1"));
        bo.setAddress2(str(body, "address2"));
        bo.setCity(str(body, "city"));
        bo.setCountry(str(body, "country") != null ? str(body, "country") : "USA");
        bo.setPinCode(str(body, "pinCode"));
        bo.setEin(str(body, "ein"));
        bo.setDeclarationCheck(true);

        // state is sent as a numeric string
        Object stateObj = body.get("state");
        if (stateObj != null && !stateObj.toString().isBlank()) {
            try { bo.setState(Integer.parseInt(stateObj.toString())); }
            catch (NumberFormatException ignored) { /* leave null */ }
        }

        try {
            // Save church registration
            ChurchRegistration saved = churchRegistrationService.save(bo);

            // Save signup record
            Date   now    = new Date();
            SignUp signUp = new SignUp();
            signUp.setClientId(clientId);
            signUp.setUsername(username.trim());
            signUp.setPassword(PasswordUtil.encode(password));
            signUp.setActive(true);
            signUp.setDeleted(false);
            signUp.setLocked(false);
            signUp.setChurch(true);
            signUp.setChurchId(saved.getId());
            signUp.setCreated(now);
            signUp.setUpdated(now);
            loginRepository.save(signUp);

            // Record timestamped, versioned acceptance of the legal policies for audit.
            // Terms of Service + Acceptable Use are bound to the declaration checkbox;
            // Privacy + Cookie policies to the privacy checkbox.
            boolean termsOk   = isChecked(body.get("declarationCheck"));
            boolean privacyOk = isChecked(body.get("privacyCheck"));
            String ip = clientIp(request);
            String ua = request.getHeader("User-Agent");
            if (termsOk) {
                recordAcceptance(clientId, username.trim(), PolicyVersions.TERMS, ip, ua);
                recordAcceptance(clientId, username.trim(), PolicyVersions.ACCEPTABLE, ip, ua);
            }
            if (privacyOk) {
                recordAcceptance(clientId, username.trim(), PolicyVersions.PRIVACY, ip, ua);
                recordAcceptance(clientId, username.trim(), PolicyVersions.COOKIE, ip, ua);
            }

            // Clean up OTP
            verificationStore.remove(clientId, "church");

            // Send confirmation email
            churchRegistrationService.sendConfirmationEmail(
                    saved.getEmail(), saved.getFirstName(), saved.getChurchName());

            res.put("status",  "success");
            res.put("message", "Church registered successfully. You can now log in.");
            return ResponseEntity.ok(res);

        } catch (Exception e) {
            res.put("status",  "error");
            res.put("message", "Failed to complete registration: " + e.getMessage());
            return ResponseEntity.status(500).body(res);
        }
    }

    // ── Legacy save endpoint (kept for backward compatibility) ────────────────

    /**
     * Legacy endpoint — kept in case any existing integrations depend on it.
     * The primary path is now the {@code initiate} → {@code verify} flow.
     */
    @ResponseBody
    @PostMapping("/api/churchregistration")
    public ResponseEntity<Map<String, Object>> save(@RequestBody ChurchRegistrationBO bo) {
        Map<String, Object> response = new HashMap<>();
        try {
            ChurchRegistration saved = churchRegistrationService.save(bo);
            response.put("status",  "success");
            response.put("message", "Church registration saved successfully.");
            response.put("data",    saved);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("status",  "error");
            response.put("message", "Failed to save registration: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Safely extracts a trimmed String from a body map. */
    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** True if a checkbox value from the JSON body is truthy. */
    private static boolean isChecked(Object v) {
        return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
    }

    /** Best-effort client IP, honoring a single X-Forwarded-For hop. */
    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }

    /** Persists one immutable policy-acceptance audit row (best-effort; never blocks registration). */
    private void recordAcceptance(String clientId, String username, String policyType, String ip, String ua) {
        try {
            PolicyAcceptance pa = new PolicyAcceptance();
            pa.setClientId(clientId);
            pa.setUsername(username);
            pa.setPolicyType(policyType);
            pa.setPolicyVersion(PolicyVersions.versionFor(policyType));
            pa.setAccepted(Boolean.TRUE);
            pa.setAcceptedAt(new Date());
            pa.setIpAddress(ip);
            pa.setUserAgent(ua);
            pa.setSource("registration");
            policyAcceptanceRepository.save(pa);
        } catch (Exception ignore) {
            // Acceptance auditing must not fail the registration itself.
        }
    }

    /** Masks an email for display, e.g. {@code john@example.com} → {@code jo***@exa***.com}. */
    static String maskEmail(String email) {
        if (email == null || email.isEmpty()) return "your email";
        int atIdx = email.indexOf('@');
        if (atIdx < 0) return "your email";
        String user   = email.substring(0, atIdx);
        String domain = email.substring(atIdx + 1);
        String mUser  = user.length() <= 2 ? user.charAt(0) + "***" : user.substring(0, 2) + "***";
        String[] parts = domain.split("\\.");
        String mDomain = parts.length > 1
                ? parts[0].substring(0, Math.min(3, parts[0].length())) + "***@" + "***" + "." + parts[parts.length - 1]
                : domain;
        return mUser + "@" + mDomain;
    }
    /** Builds an HTML email body for the OTP verification email. */
    private String buildOtpEmailHtml(String code, String churchName) {
        String safeChurch = churchName != null
                ? churchName.replace("&", "&amp;").replace("<", "&lt;") : "";
        return "<!DOCTYPE html><html lang='en'><head>"
             + "<meta charset='UTF-8'/><meta name='viewport' content='width=device-width,initial-scale=1.0'/>"
             + "<title>Verification Code</title></head>"
             + "<body style='margin:0;padding:0;background-color:#f5f6fa;"
             +   "font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;'>"
             + "<table width='100%' cellpadding='0' cellspacing='0' style='background-color:#f5f6fa;padding:40px 20px;'>"
             + "<tr><td align='center'>"
             + "<table width='100%' cellpadding='0' cellspacing='0'"
             +   " style='max-width:520px;background:#ffffff;border-radius:16px;"
             +          "box-shadow:0 4px 24px rgba(0,0,0,0.08);overflow:hidden;'>"
             + "<tr><td style='background-color:#673147;padding:32px 40px;text-align:center;'>"
             + "<h1 style='color:#ffffff;font-size:22px;margin:0;'>" + safeChurch + "</h1></td></tr>"
             + "<tr><td style='padding:32px 40px;'>"
             + "<p style='font-size:16px;color:#333;'>Your verification code is:</p>"
             + "<p style='font-size:36px;font-weight:700;letter-spacing:8px;color:#673147;text-align:center;margin:24px 0;'>"
             + code + "</p>"
             + "<p style='font-size:14px;color:#666;'>This code expires in 10 minutes.</p>"
             + "<p style='font-size:14px;color:#999;margin-top:24px;'>If you did not request this, please ignore this email.</p>"
             + "</td></tr>"
             + "<tr><td style='background-color:#f5f6fa;padding:16px 40px;text-align:center;'>"
             + "<p style='font-size:12px;color:#999;margin:0;'>&copy; " + safeChurch + "</p>"
             + "</td></tr>"
             + "</table></td></tr></table>";
    }
}
