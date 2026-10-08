package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.TrialTestEmail;
import com.churchgeniuspro.repository.TrialTestEmailRepository;
import com.churchgeniuspro.util.EmailMask;
import com.churchgeniuspro.util.PublicSendLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Phase B — the verified test address a Trial/Demo tenant's congregation mail is
 * redirected to, and the code flow that verifies it.
 *
 * <ul>
 *   <li><b>Eligible</b> tenants are exactly those whose congregation mail
 *       {@link MessagingPolicy} blocks (Trial plan, or a demo/trial tenant whose
 *       sending a Service Admin has not opened). A paying church is never eligible,
 *       never shown the feature, and never redirected.</li>
 *   <li>Only a successful code verification sets {@code verifiedEmail}. A change
 *       request parks the new address as pending; the old verified address keeps
 *       receiving test mail until the new one is confirmed, then is replaced.</li>
 *   <li>The code is six digits from {@link SecureRandom}, stored as a SHA-256 hash,
 *       valid for {@value #CODE_MINUTES} minutes, {@value #MAX_ATTEMPTS} attempts,
 *       single-use, and never logged. Requests go through {@link PublicSendLimiter}.</li>
 *   <li>The code email is <em>account mail</em> — it must reach the address being
 *       verified — and is the one message this feature sends to a real inbox.</li>
 * </ul>
 */
@Service
public class TrialTestEmailService {

    private static final Logger log = LoggerFactory.getLogger(TrialTestEmailService.class);

    public static final int CODE_MINUTES = 15;
    public static final int MAX_ATTEMPTS = 5;
    public static final String NOTICE =
            "This is a Demo/Trial test email from ChurchGeniusPro. The actual email sent to a real recipient may be different.";

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");
    private static final long CACHE_MS = 15_000L;

    private final TrialTestEmailRepository repo;
    private final MessagingPolicy policy;
    private final EmailService email;
    private final PublicSendLimiter limiter;
    private final SecureRandom random = new SecureRandom();
    private Supplier<LocalDateTime> clock = LocalDateTime::now;

    private record Cached(long expiresAt, String address) {}
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public TrialTestEmailService(TrialTestEmailRepository repo,
                                 MessagingPolicy policy,
                                 @Lazy EmailService email,
                                 PublicSendLimiter limiter) {
        this.repo = repo; this.policy = policy; this.email = email; this.limiter = limiter;
    }

    /** Test seam. */
    public void setClock(Supplier<LocalDateTime> clock) { this.clock = clock; }

    /** True only for a tenant whose congregation mail is blocked — the tenants this feature exists for. */
    public boolean eligible(String clientId) {
        return clientId != null && !clientId.isBlank() && policy != null && policy.emailBlockReason(clientId) != null;
    }

    /**
     * The verified test address for {@code clientId}, or null. Cached briefly because a
     * bulk send asks once per recipient. Never returns a pending (unverified) address.
     */
    public String verifiedAddress(String clientId) {
        if (clientId == null || clientId.isBlank()) return null;
        long now = System.currentTimeMillis();
        Cached c = cache.get(clientId);
        if (c != null && c.expiresAt() > now) return c.address();
        String address = null;
        try {
            address = repo.findByClientId(clientId).map(TrialTestEmail::getVerifiedEmail)
                    .filter(a -> a != null && !a.isBlank()).orElse(null);
        } catch (Exception e) {
            log.warn("Trial test email lookup failed for {} — {}", clientId, e.getMessage());
        }
        cache.put(clientId, new Cached(now + CACHE_MS, address));
        return address;
    }

    /** What the Email Settings page shows. Addresses are returned to the tenant's own admin, unmasked. */
    public Map<String, Object> status(String clientId) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean elig = eligible(clientId);
        out.put("eligible", elig);
        if (!elig) return out;
        TrialTestEmail row = repo.findByClientId(clientId).orElse(null);
        LocalDateTime now = clock.get();
        out.put("verifiedEmail", row != null ? row.getVerifiedEmail() : null);
        out.put("verifiedAt",    row != null && row.getVerifiedAt() != null ? row.getVerifiedAt().toString() : null);
        boolean pending = row != null && row.getPendingEmail() != null && row.getPendingCodeHash() != null
                && row.getPendingExpiresAt() != null && row.getPendingExpiresAt().isAfter(now);
        out.put("pendingEmail",     pending ? row.getPendingEmail() : null);
        out.put("pendingExpiresAt", pending ? row.getPendingExpiresAt().toString() : null);
        out.put("codeMinutes", CODE_MINUTES);
        return out;
    }

    /**
     * Starts verifying {@code address}: stores its hashed code and emails the code to
     * that address. The currently verified address is untouched.
     *
     * @throws IllegalArgumentException for an ineligible tenant, a bad address or a rate limit
     * @throws Exception when the code email could not be sent (nothing is stored then)
     */
    public Map<String, Object> requestCode(String clientId, String address, String ip, String requestedBy) throws Exception {
        if (!eligible(clientId)) throw new IllegalArgumentException("Test emails are only available on Trial/Demo accounts.");
        String addr = address == null ? "" : address.trim().toLowerCase();
        if (addr.isEmpty() || addr.length() > 320 || !EMAIL.matcher(addr).matches()) {
            throw new IllegalArgumentException("Please enter a valid email address.");
        }
        String limited = limiter.check(PublicSendLimiter.TRIAL_TEST_EMAIL_OTP, ip, addr, clientId);
        if (limited != null) throw new IllegalArgumentException(limited);

        String code = String.format("%06d", random.nextInt(1_000_000));
        LocalDateTime now = clock.get();
        TrialTestEmail row = repo.findByClientId(clientId).orElseGet(TrialTestEmail::new);
        row.setClientId(clientId);                              // never from the request
        row.setPendingEmail(addr);
        row.setPendingCodeHash(hash(clientId, code));
        row.setPendingExpiresAt(now.plusMinutes(CODE_MINUTES));
        row.setPendingRequestedAt(now);
        row.setPendingAttempts(0);
        row.setUpdatedAt(now);
        row.setUpdatedBy(requestedBy);

        // Account mail: the one message that must reach a real inbox — the address
        // being verified. Sent before the row is stored so a failed send stores nothing.
        email.sendAccountEmailOrThrow(addr, "Your ChurchGeniusPro test email verification code",
                codeEmailHtml(code, email.getChurchName(clientId)), clientId);
        repo.save(row);
        log.info("Trial test email: verification code sent for tenant {} to {}", clientId, EmailMask.mask(addr));
        return status(clientId);
    }

    /**
     * Confirms the pending address with {@code code}. On success it becomes the one
     * verified address and the code is discarded; a wrong code counts an attempt.
     *
     * @throws IllegalArgumentException with the reason on any failure
     */
    public Map<String, Object> verify(String clientId, String code, String verifiedBy) {
        if (!eligible(clientId)) throw new IllegalArgumentException("Test emails are only available on Trial/Demo accounts.");
        String c = code == null ? "" : code.trim();
        TrialTestEmail row = repo.findByClientId(clientId).orElse(null);
        LocalDateTime now = clock.get();
        if (row == null || row.getPendingEmail() == null || row.getPendingCodeHash() == null) {
            throw new IllegalArgumentException("No verification is in progress. Enter an address and request a code first.");
        }
        if (row.getPendingExpiresAt() == null || !row.getPendingExpiresAt().isAfter(now)) {
            clearPending(row, now);
            repo.save(row);
            throw new IllegalArgumentException("That code has expired. Please request a new one.");
        }
        if (row.getPendingAttempts() >= MAX_ATTEMPTS) {
            clearPending(row, now);
            repo.save(row);
            throw new IllegalArgumentException("Too many incorrect attempts. Please request a new code.");
        }
        if (!c.matches("\\d{6}") || !MessageDigest.isEqual(
                hash(clientId, c).getBytes(StandardCharsets.UTF_8),
                row.getPendingCodeHash().getBytes(StandardCharsets.UTF_8))) {
            row.setPendingAttempts(row.getPendingAttempts() + 1);
            repo.save(row);
            throw new IllegalArgumentException("That code is not correct. Please check the email and try again.");
        }
        row.setVerifiedEmail(row.getPendingEmail());
        row.setVerifiedAt(now);
        row.setVerifiedBy(verifiedBy);
        clearPending(row, now);
        row.setUpdatedBy(verifiedBy);
        repo.save(row);
        cache.remove(clientId);
        log.info("Trial test email: tenant {} verified {}", clientId, EmailMask.mask(row.getVerifiedEmail()));
        return status(clientId);
    }

    private static void clearPending(TrialTestEmail row, LocalDateTime now) {
        row.setPendingEmail(null);
        row.setPendingCodeHash(null);
        row.setPendingExpiresAt(null);
        row.setPendingAttempts(0);
        row.setUpdatedAt(now);
    }

    static String hash(String clientId, String code) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((clientId + ":" + code).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * The notice placed at the very top of a redirected message. The original
     * content follows it unchanged. The real recipient is shown masked.
     */
    public static String notice(String originalTo, int recipientCount) {
        String who = recipientCount > 1
                ? recipientCount + " recipients (first: " + HtmlUtils.htmlEscape(EmailMask.mask(originalTo)) + ")"
                : HtmlUtils.htmlEscape(EmailMask.mask(originalTo));
        return "<div class=\"cgp-trial-notice\" style=\"margin:0 0 16px;padding:12px 14px;border:2px solid #f59e0b;"
             + "border-radius:8px;background:#fffbeb;color:#78350f;font-family:Arial,sans-serif;font-size:13px;line-height:1.5;\">"
             + "<strong>⚠️ " + NOTICE + "</strong><br>"
             + "<span style=\"color:#92400e;\">This copy was addressed to: " + who + "</span></div>";
    }

    /** Inserts the notice at the top of {@code html}: just inside {@code <body>} when there is one, else first. */
    public static String withNotice(String html, String originalTo, int recipientCount) {
        String body = html == null ? "" : html;
        String n = notice(originalTo, recipientCount);
        java.util.regex.Matcher m = Pattern.compile("(?i)<body[^>]*>").matcher(body);
        if (m.find()) return body.substring(0, m.end()) + n + body.substring(m.end());
        return n + body;
    }

    private static String codeEmailHtml(String code, String churchName) {
        String safe = HtmlUtils.htmlEscape(churchName == null ? "Church Genius Pro" : churchName);
        return "<table width='100%' cellpadding='0' cellspacing='0' style='background:#f5f6fa;padding:24px 0;font-family:Arial,sans-serif;'>"
             + "<tr><td align='center'><table width='100%' cellpadding='0' cellspacing='0' style='max-width:520px;background:#fff;border-radius:16px;overflow:hidden;'>"
             + "<tr><td style='background:#673147;padding:28px 40px;text-align:center;'><h1 style='color:#fff;font-size:20px;margin:0;'>" + safe + "</h1></td></tr>"
             + "<tr><td style='padding:32px 40px;'>"
             + "<p style='font-size:15px;color:#333;'>Use this code to confirm the address that will receive your Trial/Demo <strong>test emails</strong>:</p>"
             + "<p style='font-size:36px;font-weight:700;letter-spacing:8px;color:#673147;text-align:center;margin:24px 0;'>" + code + "</p>"
             + "<p style='font-size:14px;color:#666;'>This code expires in " + CODE_MINUTES + " minutes.</p>"
             + "<p style='font-size:13px;color:#999;margin-top:24px;'>If you did not request this, you can ignore this email.</p>"
             + "</td></tr></table></td></tr></table>";
    }
}
