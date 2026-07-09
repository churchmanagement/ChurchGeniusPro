package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.VerificationCode;
import com.churchgeniuspro.repository.VerificationCodeRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Random;

/**
 * Persistent store for 6-digit email verification (OTP) codes.
 *
 * <p>Codes are written to the {@code verification_code} table so they survive
 * server restarts.  Each entry is keyed by {@code clientId + type} and expires
 * after 10 minutes.  Expired rows are purged automatically every 5 minutes.
 */
@Component
public class VerificationStore {

    private static final int  OTP_BOUND      = 1_000_000; // 000000–999999
    private static final long EXPIRY_MINUTES = 10;

    private final VerificationCodeRepository repo;
    private final Random random = new Random();

    public VerificationStore(VerificationCodeRepository repo) {
        this.repo = repo;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Generates a 6-digit OTP, persists it with a 10-minute expiry, and
     * returns the plain-text code so the caller can email it.
     */
    public String generateAndStore(String clientId, String type, String email) {
        String code = String.format("%06d", random.nextInt(OTP_BOUND));

        // Upsert: delete existing entry for this clientId+type then insert fresh
        repo.deleteByClientIdAndType(clientId, type);

        VerificationCode vc = new VerificationCode();
        vc.setClientId(clientId);
        vc.setType(type);
        vc.setCode(code);
        vc.setEmail(email);
        vc.setExpiresAt(LocalDateTime.now().plusMinutes(EXPIRY_MINUTES));
        repo.save(vc);

        return code;
    }

    /**
     * Returns {@code true} when the supplied code matches the stored code AND
     * the entry has not yet expired.
     */
    public boolean validate(String clientId, String type, String code) {
        Optional<VerificationCode> opt = repo.findByClientIdAndType(clientId, type);
        if (opt.isEmpty()) return false;
        VerificationCode vc = opt.get();
        if (LocalDateTime.now().isAfter(vc.getExpiresAt())) {
            repo.deleteByClientIdAndType(clientId, type);
            return false;
        }
        return vc.getCode().equals(code);
    }

    /**
     * Returns the email address stored alongside the code, or {@code null}
     * if no unexpired entry exists.
     */
    public String getEmail(String clientId, String type) {
        Optional<VerificationCode> opt = repo.findByClientIdAndType(clientId, type);
        if (opt.isEmpty()) return null;
        VerificationCode vc = opt.get();
        if (LocalDateTime.now().isAfter(vc.getExpiresAt())) {
            repo.deleteByClientIdAndType(clientId, type);
            return null;
        }
        return vc.getEmail();
    }

    /** Removes the entry after a successful verification. */
    public void remove(String clientId, String type) {
        repo.deleteByClientIdAndType(clientId, type);
    }

    /** Purges expired rows every 5 minutes to keep the table small. */
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void purgeExpired() {
        repo.deleteExpiredBefore(LocalDateTime.now());
    }
}
