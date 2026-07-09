package com.churchgeniuspro.plaid.service;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.plaid.entity.BankSyncTrustedDevice;
import com.churchgeniuspro.plaid.entity.BankSyncVerification;
import com.churchgeniuspro.plaid.repository.BankSyncTrustedDeviceRepository;
import com.churchgeniuspro.plaid.repository.BankSyncVerificationRepository;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * Step-up email-verification gate for the Bank Sync page. Users must verify a
 * one-time code emailed to their registered address (or a 30-day trusted-device
 * token) before the page or its APIs can be accessed.
 *
 * <p>Codes are securely generated, stored only in encrypted form, decrypted and
 * compared during verification, single-use, and expire after 15 minutes. Every
 * action is written to the Plaid audit log.
 */
@Service
public class BankSyncGateService {

    private static final Logger log = LoggerFactory.getLogger(BankSyncGateService.class);

    private static final int  CODE_TTL_MINUTES   = 15;
    private static final int  DEVICE_TTL_DAYS     = 30;
    private static final String COOKIE_NAME        = "cgp_bsync_trust";
    private static final String SESSION_VERIFIED   = "bankSyncVerifiedUserId";

    private final BankSyncVerificationRepository verificationRepo;
    private final BankSyncTrustedDeviceRepository deviceRepo;
    private final PlaidTokenCipher cipher;
    private final EmailService emailService;
    private final AppUserRepository appUserRepository;
    private final PlaidAuditService audit;
    private final SecureRandom random = new SecureRandom();

    public BankSyncGateService(BankSyncVerificationRepository verificationRepo,
                               BankSyncTrustedDeviceRepository deviceRepo,
                               PlaidTokenCipher cipher,
                               EmailService emailService,
                               AppUserRepository appUserRepository,
                               PlaidAuditService audit) {
        this.verificationRepo = verificationRepo;
        this.deviceRepo = deviceRepo;
        this.cipher = cipher;
        this.emailService = emailService;
        this.appUserRepository = appUserRepository;
        this.audit = audit;
    }

    /** Outcome of a verify attempt. */
    public record VerifyOutcome(boolean ok, String message) {}

    // ── Access check (used by the page gate AND the API guard) ───────────────

    /** True when this request/session has passed verification or is a trusted device. */
    public boolean isVerified(HttpServletRequest req) {
        Integer uid = appUserId(req);
        if (uid == null) return false;

        HttpSession s = req.getSession(false);
        if (s != null && uid.equals(s.getAttribute(SESSION_VERIFIED))) return true;

        // Trusted-device cookie → validate against a non-expired DB record.
        String cookieVal = readCookie(req, COOKIE_NAME);
        if (cookieVal != null) {
            BankSyncTrustedDevice d = deviceRepo
                    .findByAppUserIdAndTokenHash(uid, sha256Hex(cookieVal)).orElse(null);
            if (d != null && d.getExpiresAt() != null && d.getExpiresAt().isAfter(Instant.now())) {
                if (s != null) s.setAttribute(SESSION_VERIFIED, uid);  // cache for the session
                return true;
            }
        }
        return false;
    }

    // ── Send / resend ────────────────────────────────────────────────────────

    /** Generate a fresh code and email it to the current user. */
    public boolean sendCode(HttpServletRequest req, boolean isResend) {
        Integer uid = appUserId(req);
        String clientId = SessionUtil.getAppClientId(req);
        if (uid == null) return false;
        AppUser user = appUserRepository.findById(uid).orElse(null);
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) return false;

        String code = newCode();
        store(uid, clientId, code, false, null);

        String html = "<p>Your Bank Sync verification code is:</p>"
                + "<p style=\"font-size:24px;font-weight:700;letter-spacing:4px;\">" + code + "</p>"
                + "<p>This code expires in " + CODE_TTL_MINUTES + " minutes and can be used once. "
                + "If you did not request access to Bank Sync, you can ignore this email.</p>";
        try {
            emailService.sendOrgEmail(user.getEmail(), "Your Bank Sync verification code", html, clientId);
        } catch (Exception e) {
            log.warn("Bank Sync verification email failed for user {}: {}", uid, e.getMessage());
            return false;
        }
        audit.record(clientId, SessionUtil.getUsername(req),
                isResend ? "GATE_CODE_RESENT" : "GATE_CODE_SENT", "user:" + uid,
                "Verification code emailed");
        return true;
    }

    /**
     * Church-role admin generates a temporary one-time code for another user who
     * cannot receive email. Returns the plaintext code to show the admin. Fully audited.
     */
    public String generateTempCode(HttpServletRequest adminReq, Integer targetUserId) {
        String clientId = SessionUtil.getAppClientId(adminReq);
        AppUser target = appUserRepository.findById(targetUserId).orElse(null);
        if (target == null) throw new IllegalArgumentException("User not found.");
        // Same tenant only.
        if (clientId != null && target.getClientId() != null && !clientId.equals(target.getClientId())) {
            throw new IllegalArgumentException("User belongs to a different organization.");
        }
        String admin = SessionUtil.getUsername(adminReq);
        String code = newCode();
        store(targetUserId, target.getClientId() != null ? target.getClientId() : clientId, code, true, admin);
        audit.record(clientId, admin, "GATE_TEMP_CODE", "user:" + targetUserId,
                "Temporary Bank Sync code generated by " + admin + " for user " + targetUserId);
        return code;
    }

    // ── Verify ───────────────────────────────────────────────────────────────

    public VerifyOutcome verify(HttpServletRequest req, HttpServletResponse res,
                                String code, boolean rememberDevice) {
        Integer uid = appUserId(req);
        String clientId = SessionUtil.getAppClientId(req);
        String actor = SessionUtil.getUsername(req);
        if (uid == null) return new VerifyOutcome(false, "Not authenticated.");
        if (code == null || code.isBlank()) return new VerifyOutcome(false, "Enter the code.");
        code = code.trim();

        // Match against any unused, non-expired code for this user.
        BankSyncVerification matched = null;
        for (BankSyncVerification v : verificationRepo.findByAppUserIdAndUsed(uid, false)) {
            if (v.getExpiresAt() == null || !v.getExpiresAt().isAfter(Instant.now())) continue;
            String plain;
            try { plain = cipher.decrypt(v.getTokenEnc()); }
            catch (Exception e) { continue; }
            if (constantTimeEquals(plain, code)) { matched = v; break; }
        }

        if (matched == null) {
            audit.record(clientId, actor, "GATE_FAILED", "user:" + uid,
                    "Invalid or expired verification code");
            return new VerifyOutcome(false, "Invalid or expired code. Please try again or resend.");
        }

        // Consume the code (one-time) and mark this session verified.
        matched.setUsed(true);
        matched.setUsedDate(new Date());
        verificationRepo.save(matched);
        HttpSession s = req.getSession(true);
        s.setAttribute(SESSION_VERIFIED, uid);

        if (rememberDevice) {
            issueTrustedDevice(uid, clientId, res);
            audit.record(clientId, actor, "GATE_TRUSTED_DEVICE", "user:" + uid,
                    "Device remembered for " + DEVICE_TTL_DAYS + " days");
        }
        audit.record(clientId, actor, "GATE_VERIFIED", "user:" + uid,
                matched.isTemp() ? "Verified with temporary code" : "Email verification successful");
        return new VerifyOutcome(true, "Verified.");
    }

    // ── internals ────────────────────────────────────────────────────────────

    private void store(Integer uid, String clientId, String code, boolean isTemp, String generatedBy) {
        // Invalidate any prior unused codes so only the newest is usable.
        List<BankSyncVerification> prior = verificationRepo.findByAppUserIdAndUsed(uid, false);
        for (BankSyncVerification p : prior) { p.setUsed(true); p.setUsedDate(new Date()); }
        verificationRepo.saveAll(prior);

        BankSyncVerification v = new BankSyncVerification();
        v.setAppUserId(uid);
        v.setClientId(clientId);
        v.setTokenEnc(cipher.encrypt(code));
        v.setExpiresAt(Instant.now().plus(Duration.ofMinutes(CODE_TTL_MINUTES)));
        v.setUsed(false);
        v.setTemp(isTemp);
        v.setGeneratedBy(generatedBy);
        verificationRepo.save(v);
    }

    private void issueTrustedDevice(Integer uid, String clientId, HttpServletResponse res) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        BankSyncTrustedDevice d = new BankSyncTrustedDevice();
        d.setAppUserId(uid);
        d.setClientId(clientId);
        d.setTokenHash(sha256Hex(token));
        d.setExpiresAt(Instant.now().plus(Duration.ofDays(DEVICE_TTL_DAYS)));
        deviceRepo.save(d);

        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, token)
                .httpOnly(true).secure(true).sameSite("Lax").path("/")
                .maxAge(Duration.ofDays(DEVICE_TTL_DAYS)).build();
        res.addHeader("Set-Cookie", cookie.toString());
    }

    private Integer appUserId(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        Object v = s != null ? s.getAttribute("appUserId") : null;
        return v instanceof Integer i ? i : null;
    }

    private String newCode() {
        return String.format("%06d", random.nextInt(1_000_000));
    }

    private String readCookie(HttpServletRequest req, String name) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) if (name.equals(c.getName())) return c.getValue();
        return null;
    }

    private String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("hash failed", e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
