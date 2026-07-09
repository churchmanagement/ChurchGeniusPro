package com.churchgeniuspro.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Central BCrypt password utility.
 *
 * <p>A single shared {@link BCryptPasswordEncoder} instance (strength 10) is
 * used for all password hashing and verification in the application.
 *
 * <p>Usage:
 * <pre>
 *   // Hash before saving
 *   String hashed = PasswordUtil.encode(rawPassword);
 *   signUp.setPassword(hashed);
 *
 *   // Verify on login
 *   if (!PasswordUtil.matches(submitted, stored)) { ... deny ... }
 *
 *   // Detect un-migrated plaintext rows
 *   if (!PasswordUtil.isBCrypt(stored)) { ... re-hash ... }
 * </pre>
 */
public final class PasswordUtil {

    private PasswordUtil() {}

    /** Shared encoder — BCryptPasswordEncoder is thread-safe. */
    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder(10);

    /**
     * Hash {@code rawPassword} with BCrypt (strength 10).
     * Always call this before persisting a password.
     */
    public static String encode(String rawPassword) {
        return ENCODER.encode(rawPassword);
    }

    /**
     * Returns {@code true} if {@code rawPassword} matches the BCrypt hash
     * {@code encodedPassword}.
     *
     * <p>Also returns {@code true} when both values are equal as plain strings
     * (legacy plaintext rows that haven't been migrated yet). The login code
     * should migrate such rows immediately after a successful match.
     */
    public static boolean matches(String rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) return false;
        // Fast path: BCrypt hash present → use proper matching
        if (isBCrypt(encodedPassword)) {
            return ENCODER.matches(rawPassword, encodedPassword);
        }
        // Legacy plaintext row — fall back to direct equality so existing users
        // can still log in. The caller must re-hash and save after this succeeds.
        return rawPassword.equals(encodedPassword);
    }

    /**
     * Returns {@code true} when the stored value looks like a BCrypt hash
     * (starts with {@code $2a$}, {@code $2b$}, or {@code $2y$}).
     * Used to detect un-migrated plaintext rows.
     */
    public static boolean isBCrypt(String stored) {
        return stored != null && (stored.startsWith("$2a$")
                || stored.startsWith("$2b$")
                || stored.startsWith("$2y$"));
    }
}
