package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.NtagCredential;
import com.churchgeniuspro.hibernate.NtagLoginChallenge;
import com.churchgeniuspro.hibernate.NtagLoginHistory;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.NtagCredentialRepository;
import com.churchgeniuspro.repository.NtagLoginChallengeRepository;
import com.churchgeniuspro.repository.NtagLoginHistoryRepository;
import com.churchgeniuspro.util.PasswordUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NTAG (NFC) three-factor temporary login: admin management of per-user tag
 * credentials, plus the scan → PIN → email-OTP login flow. PIN and OTP steps are
 * each independently configurable per credential.
 */
@Service
public class NtagService {

    private static final Logger LOG = LoggerFactory.getLogger(NtagService.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final int MAX_ATTEMPTS = 5;
    private static final int CHALLENGE_TTL_MIN = 10;
    private static final int OTP_TTL_MIN = 10;

    private final NtagCredentialRepository credRepo;
    private final NtagLoginChallengeRepository challengeRepo;
    private final NtagLoginHistoryRepository historyRepo;
    private final ChurchRegistrationRepository churchRepo;
    private final EmailService emailService;
    private final TemporaryAccessService tempAccessService;

    /** The church's own account status; optional so hand-built tests are unchanged. */
    private AccountStatusService accountStatus;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAccountStatus(AccountStatusService a) { this.accountStatus = a; }

    public NtagService(NtagCredentialRepository credRepo,
                       NtagLoginChallengeRepository challengeRepo,
                       NtagLoginHistoryRepository historyRepo,
                       ChurchRegistrationRepository churchRepo,
                       EmailService emailService,
                       TemporaryAccessService tempAccessService) {
        this.credRepo = credRepo;
        this.challengeRepo = challengeRepo;
        this.historyRepo = historyRepo;
        this.churchRepo = churchRepo;
        this.emailService = emailService;
        this.tempAccessService = tempAccessService;
    }

    /** User-facing login error. */
    public static class NtagLoginException extends RuntimeException {
        public NtagLoginException(String m) { super(m); }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Uppercase hex, separators stripped — so "04:A2:3B" and "04a23b" match. */
    public static String normalizeSerial(String s) {
        if (s == null) return null;
        String n = s.trim().toUpperCase().replaceAll("[^A-Z0-9]", "");
        return n.isEmpty() ? null : n;
    }

    private static String token() {
        byte[] b = new byte[24];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder(48);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }

    private static String sixDigit() { return String.format("%06d", RNG.nextInt(1_000_000)); }

    static String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "your email";
        String[] p = email.split("@", 2);
        String u = p[0];
        String um = u.length() <= 2 ? u.charAt(0) + "*" : u.charAt(0) + "***" + u.charAt(u.length() - 1);
        return um + "@" + p[1];
    }

    // ── Admin CRUD ─────────────────────────────────────────────────────────────

    @Transactional
    public NtagCredential create(String clientId, String createdBy, String serial, String holderName,
                                 String email, String phone, String userId, String role, String pin,
                                 boolean requirePin, boolean requireOtp, String permissions,
                                 LocalDateTime validFrom, LocalDateTime validUntil) {
        String norm = normalizeSerial(serial);
        if (norm == null) throw new IllegalArgumentException("A valid NTAG serial is required.");
        if (credRepo.existsByNtagSerialAndDeleteFlagFalse(norm))
            throw new IllegalArgumentException("That NTAG tag is already registered.");

        NtagCredential c = new NtagCredential();
        c.setClientId(clientId);
        c.setCreatedBy(createdBy);
        c.setNtagSerial(norm);
        c.setHolderName(holderName);
        c.setEmail(email);
        c.setPhone(phone);
        c.setUserId(userId);
        c.setRole(role == null || role.isBlank() ? "User" : role);
        c.setRequirePin(requirePin);
        c.setRequireOtp(requireOtp);
        c.setPermissions(permissions);
        c.setValidFrom(validFrom);
        c.setValidUntil(validUntil);
        c.setStatus("Active");
        if (requirePin) {
            if (pin == null || !pin.matches("\\d{6}")) throw new IllegalArgumentException("PIN must be 6 digits.");
            c.setPinHash(PasswordUtil.encode(pin));
        }
        if (requireOtp && (email == null || email.isBlank()))
            throw new IllegalArgumentException("An email address is required when email OTP is enabled.");
        return credRepo.save(c);
    }

    @Transactional
    public NtagCredential update(Long id, String clientId, String holderName, String email, String phone,
                                 String userId, String role, Boolean requirePin, Boolean requireOtp,
                                 String permissions, LocalDateTime validFrom, LocalDateTime validUntil) {
        NtagCredential c = mustFind(id, clientId);
        c.setHolderName(holderName);
        c.setEmail(email);
        c.setPhone(phone);
        c.setUserId(userId);
        if (role != null && !role.isBlank()) c.setRole(role);
        if (requirePin != null) c.setRequirePin(requirePin);
        if (requireOtp != null) c.setRequireOtp(requireOtp);
        c.setPermissions(permissions);
        c.setValidFrom(validFrom);
        c.setValidUntil(validUntil);
        if (c.isRequireOtp() && (email == null || email.isBlank()))
            throw new IllegalArgumentException("An email address is required when email OTP is enabled.");
        return credRepo.save(c);
    }

    @Transactional
    public void resetPin(Long id, String clientId, String pin) {
        if (pin == null || !pin.matches("\\d{6}")) throw new IllegalArgumentException("PIN must be 6 digits.");
        NtagCredential c = mustFind(id, clientId);
        c.setPinHash(PasswordUtil.encode(pin));
        c.setRequirePin(true);
        credRepo.save(c);
    }

    @Transactional
    public void setEnabled(Long id, String clientId, boolean enabled) {
        NtagCredential c = mustFind(id, clientId);
        c.setNtagEnabled(enabled);
        credRepo.save(c);
    }

    /** Immediate revoke — e.g. lost tag. Blocks all future logins. */
    @Transactional
    public void revoke(Long id, String clientId) {
        NtagCredential c = mustFind(id, clientId);
        c.setStatus("Revoked");
        c.setNtagEnabled(false);
        credRepo.save(c);
    }

    @Transactional
    public void delete(Long id, String clientId) {
        NtagCredential c = mustFind(id, clientId);
        c.setDeleteFlag(true);
        credRepo.save(c);
    }

    public List<NtagCredential> list(String clientId) {
        return credRepo.findByClientIdAndDeleteFlagFalseOrderByCreatedDateDesc(clientId);
    }

    private NtagCredential mustFind(Long id, String clientId) {
        return credRepo.findByIdAndClientIdAndDeleteFlagFalse(id, clientId)
                .orElseThrow(() -> new IllegalArgumentException("NTAG credential not found."));
    }

    public Map<String, Object> toMap(NtagCredential c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("ntagSerial", c.getNtagSerial());
        m.put("holderName", c.getHolderName());
        m.put("email", c.getEmail());
        m.put("phone", c.getPhone());
        m.put("userId", c.getUserId());
        m.put("role", c.getRole());
        m.put("ntagEnabled", c.isNtagEnabled());
        m.put("requirePin", c.isRequirePin());
        m.put("requireOtp", c.isRequireOtp());
        m.put("hasPin", c.getPinHash() != null);
        m.put("permissions", c.getPermissions());
        m.put("status", c.getStatus());
        m.put("validFrom", c.getValidFrom() != null ? c.getValidFrom().toString() : null);
        m.put("validUntil", c.getValidUntil() != null ? c.getValidUntil().toString() : null);
        m.put("createdDate", c.getCreatedDate() != null ? c.getCreatedDate().toString() : null);
        return m;
    }

    public List<Map<String, Object>> historyForClient(String clientId, int limit) {
        return toHistoryMaps(historyRepo.findByClientIdOrderByLoginTimeDesc(clientId, PageRequest.of(0, Math.max(1, Math.min(limit, 500)))));
    }

    /** Login history for one credential of this tenant; unknown / foreign id → IllegalArgumentException. */
    public List<Map<String, Object>> historyForCredential(Long credentialId, String clientId, int limit) {
        NtagCredential c = mustFind(credentialId, clientId);
        return toHistoryMaps(historyRepo.findByCredentialIdOrderByLoginTimeDesc(c.getId(), PageRequest.of(0, Math.max(1, Math.min(limit, 500)))));
    }

    private List<Map<String, Object>> toHistoryMaps(List<NtagLoginHistory> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (NtagLoginHistory h : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("time", h.getLoginTime() != null ? h.getLoginTime().toString() : null);
            m.put("holderName", h.getHolderName());
            m.put("serial", h.getSerial());
            m.put("ip", h.getIpAddress());
            m.put("status", h.getStatus());
            m.put("reason", h.getReason());
            out.add(m);
        }
        return out;
    }

    // ── Login flow ─────────────────────────────────────────────────────────────

    public static class StartResult {
        public String token, stage, holderName, maskedEmail;
        public boolean requirePin, requireOtp;
    }

    /** Step 1: a tag was scanned (or its serial typed). Validates the credential and opens a challenge. */
    @Transactional
    public StartResult start(String serial, String ip, String device) {
        String norm = normalizeSerial(serial);
        NtagCredential c = (norm == null) ? null : credRepo.findByNtagSerialAndDeleteFlagFalse(norm).orElse(null);
        if (c == null) { logHist(null, null, norm, ip, device, "NOT_FOUND", "tag not registered"); throw new NtagLoginException("This NTAG tag is not recognized."); }
        if (!c.isNtagEnabled() || "Revoked".equalsIgnoreCase(c.getStatus())) {
            logHist(c, ip, device, "DISABLED", "ntag login disabled or revoked");
            throw new NtagLoginException("NTAG login is disabled for this tag. Contact an administrator.");
        }
        LocalDateTime now = LocalDateTime.now();
        if ((c.getValidFrom() != null && now.isBefore(c.getValidFrom()))
                || (c.getValidUntil() != null && now.isAfter(c.getValidUntil()))) {
            logHist(c, ip, device, "EXPIRED", "outside validity window");
            throw new NtagLoginException("This NTAG tag is not valid at this time.");
        }
        // A tag is one of the church's logins: when the church's subscription or trial
        // has ended it cannot start a sign-in either.
        if (accountStatus != null && accountStatus.forTenant(c.getClientId()) != null) {
            logHist(c, ip, device, "SUBSCRIPTION_ENDED", "church subscription ended");
            throw new NtagLoginException("This church's subscription has ended. Please contact the church administrator.");
        }

        NtagLoginChallenge ch = new NtagLoginChallenge();
        ch.setToken(token());
        ch.setCredentialId(c.getId());
        ch.setClientId(c.getClientId());
        ch.setIpAddress(ip);
        ch.setExpiresAt(now.plusMinutes(CHALLENGE_TTL_MIN));
        ch.setCreatedAt(now);
        if (c.isRequirePin()) {
            ch.setStage("AWAIT_PIN");
        } else if (c.isRequireOtp()) {
            issueOtp(ch, c);
            ch.setStage("AWAIT_OTP");
        } else {
            ch.setStage("DONE");
        }
        challengeRepo.save(ch);

        StartResult r = new StartResult();
        r.token = ch.getToken();
        r.stage = ch.getStage();
        r.holderName = c.getHolderName();
        r.requirePin = c.isRequirePin();
        r.requireOtp = c.isRequireOtp();
        r.maskedEmail = maskEmail(c.getEmail());
        return r;
    }

    /** Step 2: verify the 6-digit PIN. Advances to OTP or DONE. */
    @Transactional
    public Map<String, Object> verifyPin(String tokenStr, String pin, String ip, String device) {
        NtagLoginChallenge ch = liveChallenge(tokenStr);
        if (!"AWAIT_PIN".equals(ch.getStage())) throw new NtagLoginException("Unexpected step. Please re-scan your tag.");
        NtagCredential c = credRepo.findById(ch.getCredentialId()).orElseThrow(() -> new NtagLoginException("Credential not found."));
        bumpAttempts(ch, c, ip, device);
        if (pin == null || c.getPinHash() == null || !PasswordUtil.matches(pin.trim(), c.getPinHash())) {
            challengeRepo.save(ch);
            logHist(c, ip, device, "FAILED_PIN", "incorrect PIN");
            throw new NtagLoginException("Incorrect PIN.");
        }
        ch.setAttempts(0);
        if (c.isRequireOtp()) { issueOtp(ch, c); ch.setStage("AWAIT_OTP"); }
        else ch.setStage("DONE");
        challengeRepo.save(ch);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", ch.getStage());
        m.put("requireOtp", c.isRequireOtp());
        m.put("maskedEmail", maskEmail(c.getEmail()));
        return m;
    }

    /** Step 3: verify the emailed OTP. Advances to DONE. */
    @Transactional
    public Map<String, Object> verifyOtp(String tokenStr, String otp, String ip, String device) {
        NtagLoginChallenge ch = liveChallenge(tokenStr);
        if (!"AWAIT_OTP".equals(ch.getStage())) throw new NtagLoginException("Unexpected step. Please re-scan your tag.");
        NtagCredential c = credRepo.findById(ch.getCredentialId()).orElseThrow(() -> new NtagLoginException("Credential not found."));
        if (ch.getOtpExpiresAt() == null || LocalDateTime.now().isAfter(ch.getOtpExpiresAt()))
            throw new NtagLoginException("Your code has expired. Please re-scan your tag.");
        bumpAttempts(ch, c, ip, device);
        if (otp == null || ch.getOtpHash() == null || !PasswordUtil.matches(otp.trim(), ch.getOtpHash())) {
            challengeRepo.save(ch);
            logHist(c, ip, device, "FAILED_OTP", "incorrect OTP");
            throw new NtagLoginException("Incorrect verification code.");
        }
        ch.setStage("DONE");
        challengeRepo.save(ch);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", "DONE");
        return m;
    }

    public static class SessionInfo {
        public String clientId, role, username, privileges, churchName, startRoute, holderName;
        public Long credentialId;
        public String permsCsv;
        public java.util.List<String> permittedRoutes;
    }

    /** Final step: consume a DONE challenge and return the session payload. */
    @Transactional
    public SessionInfo finalizeLogin(String tokenStr, String ip, String device) {
        NtagLoginChallenge ch = liveChallenge(tokenStr);
        if (!"DONE".equals(ch.getStage())) throw new NtagLoginException("Login is not complete yet.");
        NtagCredential c = credRepo.findById(ch.getCredentialId()).orElseThrow(() -> new NtagLoginException("Credential not found."));
        challengeRepo.delete(ch);

        SessionInfo s = new SessionInfo();
        s.clientId = c.getClientId();
        s.role = c.getRole() == null || c.getRole().isBlank() ? "User" : c.getRole();
        s.username = "ntag:" + c.getId();
        s.privileges = tempAccessService.buildPrivilegesJson(c.getPermissions());
        s.startRoute = tempAccessService.firstRoute(c.getPermissions());
        s.holderName = c.getHolderName();
        s.credentialId = c.getId();
        s.permsCsv = c.getPermissions() == null ? "" : c.getPermissions();
        s.permittedRoutes = tempAccessService.permittedRoutes(c.getPermissions());
        s.churchName = churchRepo.findByClientIdAndDeleteFlagFalse(c.getClientId())
                .map(ChurchRegistration::getChurchName).filter(n -> n != null && !n.isBlank()).orElse("Church");
        logHist(c, ip, device, "SUCCESS", "ntag 3-factor login");
        return s;
    }

    // ── internals ──

    private NtagLoginChallenge liveChallenge(String tokenStr) {
        NtagLoginChallenge ch = (tokenStr == null) ? null : challengeRepo.findByToken(tokenStr.trim()).orElse(null);
        if (ch == null) throw new NtagLoginException("Session not found. Please re-scan your tag.");
        if (LocalDateTime.now().isAfter(ch.getExpiresAt())) {
            challengeRepo.delete(ch);
            throw new NtagLoginException("Your login session expired. Please re-scan your tag.");
        }
        return ch;
    }

    private void bumpAttempts(NtagLoginChallenge ch, NtagCredential c, String ip, String device) {
        ch.setAttempts(ch.getAttempts() + 1);
        if (ch.getAttempts() > MAX_ATTEMPTS) {
            challengeRepo.delete(ch);
            logHist(c, ip, device, "BLOCKED", "too many attempts");
            throw new NtagLoginException("Too many attempts. Please re-scan your tag to start over.");
        }
    }

    private void issueOtp(NtagLoginChallenge ch, NtagCredential c) {
        String otp = sixDigit();
        ch.setOtpHash(PasswordUtil.encode(otp));
        ch.setOtpExpiresAt(LocalDateTime.now().plusMinutes(OTP_TTL_MIN));
        String subject = "Your NTAG login code";
        String html = "<div style=\"font-family:Arial,sans-serif;\">"
                + "<p>Hi " + safe(c.getHolderName()) + ",</p>"
                + "<p>Your one-time NTAG login code is:</p>"
                + "<p style=\"font-size:30px;font-weight:800;letter-spacing:6px;color:#673147;\">" + otp + "</p>"
                + "<p style=\"font-size:13px;color:#777;\">This code expires in " + OTP_TTL_MIN + " minutes. "
                + "If you did not try to log in, you can ignore this email.</p></div>";
        try { emailService.sendOrgEmail(c.getEmail(), subject, html, c.getClientId()); }
        catch (Exception e) { LOG.warn("[NTAG] OTP email send failed for {}: {}", c.getClientId(), e.toString()); }
    }

    private void logHist(NtagCredential c, String ip, String device, String status, String reason) {
        logHist(c, c == null ? null : c.getClientId(), c == null ? null : c.getNtagSerial(), ip, device, status, reason);
        // overload below carries explicit serial for the not-found case
    }

    private void logHist(NtagCredential c, String clientId, String serial, String ip, String device, String status, String reason) {
        try {
            NtagLoginHistory h = new NtagLoginHistory();
            h.setCredentialId(c == null ? null : c.getId());
            h.setClientId(clientId);
            h.setHolderName(c == null ? null : c.getHolderName());
            h.setSerial(serial);
            h.setIpAddress(ip);
            h.setDeviceInfo(device);
            h.setStatus(status);
            h.setReason(reason);
            h.setLoginTime(LocalDateTime.now());
            historyRepo.save(h);
        } catch (Exception e) { LOG.warn("[NTAG] history log failed: {}", e.toString()); }
    }

    private static String safe(String s) { return s == null ? "" : s.replace("<", "&lt;").replace(">", "&gt;"); }
}
