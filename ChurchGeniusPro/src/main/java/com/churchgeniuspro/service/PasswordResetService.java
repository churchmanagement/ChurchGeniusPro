package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.PasswordResetToken;
import com.churchgeniuspro.repository.PasswordResetTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.UUID;

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
 */
@Service
public class PasswordResetService {

    /** Reset tokens expire after 10 minutes. */
    private static final long TOKEN_VALIDITY_MS = 10L * 60 * 1000;

    private final PasswordResetTokenRepository tokenRepo;

    public PasswordResetService(PasswordResetTokenRepository tokenRepo) {
        this.tokenRepo = tokenRepo;
    }

    /**
     * Atomically replaces any existing token for {@code username} with a
     * freshly generated one valid for 10 minutes.
     *
     * @param username the login username
     * @param email    the email address attached to the account (lowercased for storage)
     * @return the new token string — caller should embed it in the reset URL
     */
    @Transactional
    public String createToken(String username, String email) {
        // Remove any previous (potentially expired) tokens for this username
        tokenRepo.deleteByUsername(username);

        PasswordResetToken prt = new PasswordResetToken();
        prt.setUsername(username);
        prt.setEmail(email.toLowerCase());
        prt.setToken(UUID.randomUUID().toString());
        prt.setExpiryTime(new Date(System.currentTimeMillis() + TOKEN_VALIDITY_MS));
        tokenRepo.save(prt);

        return prt.getToken();
    }
}
