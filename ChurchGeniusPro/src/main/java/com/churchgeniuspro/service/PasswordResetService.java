package com.churchgeniuspro.service;

import com.churchgeniuspro.config.LoginProtectionProperties;
import com.churchgeniuspro.hibernate.PasswordResetToken;
import com.churchgeniuspro.repository.PasswordResetTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;

/**
 * Manages password-reset tokens.
 *
 * <p>Extracted into its own {@code @Service} so that {@code @Transactional}
 * is applied through Spring's AOP proxy rather than via self-invocation
 * (calling {@code this.method()} inside the same bean bypasses the proxy
 * and silently ignores the annotation).
 *
 * <p>The token-creation step (delete old + insert new) is wrapped in a
 * single transaction so both operations succeed or fail together.
 * Email sending is intentionally performed <em>outside</em> this class
 * (after the transaction commits) so that a transient SMTP error never
 * rolls back the token that was already stored.
 *
 * <h3>Token properties</h3>
 * <ul>
 *   <li><b>Unpredictable</b> — 256 bits from {@link SecureRandom}, base64url-encoded.</li>
 *   <li><b>Time-limited</b> — valid for {@value #TOKEN_VALIDITY_MINUTES} minutes.</li>
 *   <li><b>Single-use</b> — marked {@code used} by the reset endpoint and never accepted again.</li>
 *   <li><b>Superseding</b> — issuing a new token deletes every earlier one for that account,
 *       so a token stolen from an old email stops working as soon as a fresh one is requested.</li>
 *   <li><b>Rate-limited</b> — a per-account cooldown stops someone who knows a username from
 *       flooding that person's inbox with reset emails.</li>
 * </ul>
 */
@Service
public class PasswordResetService {

    /** Reset tokens expire after 10 minutes. */
    static final int TOKEN_VALIDITY_MINUTES = 10;
    private static final long TOKEN_VALIDITY_MS = TOKEN_VALIDITY_MINUTES * 60L * 1000L;

    /** 32 bytes = 256 bits of entropy — far beyond guessing range for a 10-minute window. */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PasswordResetTokenRepository tokenRepo;
    private final LoginProtectionProperties    props;

    public PasswordResetService(PasswordResetTokenRepository tokenRepo,
                                LoginProtectionProperties props) {
        this.tokenRepo = tokenRepo;
        this.props     = props;
    }

    /**
     * Atomically replaces any existing token for {@code username} with a freshly
     * generated one.
     *
     * @param username the login username
     * @param email    the email address attached to the account (lowercased for storage)
     * @return the new token string for the caller to embed in the reset URL, or
     *         {@code null} when a token was issued within the cooldown window and no new
     *         email should be sent. Callers must treat {@code null} as "quietly do
     *         nothing" and still return the same generic response to the client.
     */
    @Transactional
    public String createToken(String username, String email) {
        Duration cooldown = props.getPasswordResetRequestCooldown();
        if (cooldown != null && !cooldown.isZero() && !cooldown.isNegative()) {
            PasswordResetToken recent = tokenRepo.findFirstByUsernameOrderByIdDesc(username).orElse(null);
            if (recent != null && !recent.isUsed() && recent.getCreatedAt() != null) {
                long ageMs = System.currentTimeMillis() - recent.getCreatedAt().getTime();
                if (ageMs < cooldown.toMillis()) {
                    // A valid link is already in that inbox — do not send a second one.
                    return null;
                }
            }
        }

        // Remove any previous (potentially expired) tokens for this username
        tokenRepo.deleteByUsername(username);

        PasswordResetToken prt = new PasswordResetToken();
        prt.setUsername(username);
        prt.setEmail(email.toLowerCase());
        prt.setToken(generateToken());
        prt.setCreatedAt(new Date());
        prt.setExpiryTime(new Date(System.currentTimeMillis() + TOKEN_VALIDITY_MS));
        tokenRepo.save(prt);

        return prt.getToken();
    }

    /**
     * 256 bits of cryptographically secure randomness, URL-safe and unpadded so it can
     * be dropped straight into the reset link.
     */
    private static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
