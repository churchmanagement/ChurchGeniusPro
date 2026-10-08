package com.churchgeniuspro.payroll.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Authenticated encryption for payroll SSN last-4 digits (encrypted at rest,
 * never stored as plain text). Same construction as {@code PlaidTokenCipher}:
 * AES-256-GCM, random 96-bit IV per value, stored as
 * {@code base64(IV || ciphertext||tag)}.
 *
 * <p>Key: base64 AES key from {@code PAYROLL_SSN_ENC_KEY}
 * ({@code payroll.ssn-enc-key}). When unset, a key is derived from an
 * application constant so development works out of the box — a startup WARNING
 * tells operators to set a real key in production
 * ({@code openssl rand -base64 32}).
 *
 * <h3>Key rotation (security audit 2026-10-07, Phase 5)</h3>
 * <p>{@code PAYROLL_SSN_ENC_KEY_PREVIOUS} ({@code payroll.ssn-enc-key-previous}) is
 * set only while rotating: either the previous base64 key, or the word
 * {@code derived} meaning the built-in development key above. {@link #decrypt}
 * tries the current key first and the previous key second, so every stored value
 * stays readable throughout; {@link SsnRotationService} then re-encrypts the rows
 * onto the current key and the previous key is removed. {@link #classify} reports
 * which key (if any) a stored value is under, so the rotation never has to guess.
 *
 * <p>{@link #decrypt} transparently passes through legacy PLAINTEXT values
 * (exactly 4 digits) recorded before encryption existed; they are re-encrypted
 * the next time the employee record is saved, or by the rotation.
 */
@Component
public class SsnCrypto {

    private static final Logger log = LoggerFactory.getLogger(SsnCrypto.class);
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    /** Sentinel for {@code PAYROLL_SSN_ENC_KEY_PREVIOUS}: "the built-in development key". */
    public static final String PREVIOUS_DERIVED = "derived";

    /** How a stored {@code ssn_last4} value relates to the configured keys. */
    public enum State {
        /** null or blank — nothing stored. */
        BLANK,
        /** exactly 4 digits, recorded before encryption existed. */
        LEGACY_PLAINTEXT,
        /** decrypts with the current key — nothing to do. */
        CURRENT_KEY,
        /** decrypts only with the previous key — needs re-encrypting. */
        PREVIOUS_KEY,
        /** decrypts with neither configured key. */
        UNREADABLE
    }

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;
    private final SecretKeySpec previousKey;   // null unless a rotation is in progress
    private final boolean keyFromConfiguration;

    public SsnCrypto(@Value("${payroll.ssn-enc-key:}") String configuredKey) {
        this(configuredKey, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SsnCrypto(@Value("${payroll.ssn-enc-key:}") String configuredKey,
                     @Value("${payroll.ssn-enc-key-previous:}") String previousConfiguredKey) {
        boolean configured = configuredKey != null && !configuredKey.isBlank();
        this.keyFromConfiguration = configured;
        this.key = new SecretKeySpec(configured ? parseKey(configuredKey, "PAYROLL_SSN_ENC_KEY") : derivedKeyBytes(true), "AES");
        byte[] previous = previousKeyBytes(previousConfiguredKey);
        if (previous != null && !configured) {
            throw new IllegalStateException(
                    "PAYROLL_SSN_ENC_KEY_PREVIOUS is set but PAYROLL_SSN_ENC_KEY is not — a rotation needs a new key to rotate onto.");
        }
        this.previousKey = previous == null ? null : new SecretKeySpec(previous, "AES");
        if (this.previousKey != null) {
            log.warn("PAYROLL_SSN_ENC_KEY_PREVIOUS is set — payroll SSN key rotation in progress. "
                    + "Run the Service Admin rotation, then remove the variable once it reports pending=0.");
        }
    }

    /** Base64 AES key of 16/24/32 bytes; the exception names the variable, never the value. */
    private static byte[] parseKey(String configuredKey, String variable) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(configuredKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(variable + " is not valid base64.");
        }
        if (bytes.length != 16 && bytes.length != 24 && bytes.length != 32) {
            throw new IllegalStateException(variable + " must decode to 16, 24 or 32 bytes (got "
                    + bytes.length + "). Generate one with: openssl rand -base64 32");
        }
        return bytes;
    }

    private static byte[] previousKeyBytes(String previousConfiguredKey) {
        if (previousConfiguredKey == null || previousConfiguredKey.isBlank()) return null;
        if (PREVIOUS_DERIVED.equalsIgnoreCase(previousConfiguredKey.trim())) return derivedKeyBytes(false);
        return parseKey(previousConfiguredKey, "PAYROLL_SSN_ENC_KEY_PREVIOUS");
    }

    /** The built-in development key. {@code warn} = it is being used as the CURRENT key. */
    private static byte[] derivedKeyBytes(boolean warn) {
        if (warn) {
            log.warn("PAYROLL_SSN_ENC_KEY is not set — using a derived development key for payroll SSN "
                    + "encryption. Set PAYROLL_SSN_ENC_KEY (openssl rand -base64 32) in production.");
        }
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest("ChurchGeniusPro:payroll-ssn:v1".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** True when the current key came from configuration (not the derived development key). */
    public boolean isConfigured() { return keyFromConfiguration; }

    /** True while {@code PAYROLL_SSN_ENC_KEY_PREVIOUS} is set. */
    public boolean rotationInProgress() { return previousKey != null; }

    /** Encrypts a last-4 value with the current key; null/blank passes through unchanged. */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return plaintext;
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("SSN encryption failed", e);   // never logs the value
        }
    }

    /**
     * Decrypts a stored value for authorized display: current key first, previous
     * key second (rotation only). Legacy plaintext rows (exactly 4 digits) pass
     * through unchanged; undecryptable values return {@code null} rather than
     * leaking ciphertext.
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) return stored;
        if (isLegacyPlaintext(stored)) return stored;
        String value = decryptWith(key, stored);
        if (value == null && previousKey != null) value = decryptWith(previousKey, stored);
        if (value == null) log.warn("Payroll SSN last-4 value could not be decrypted with the configured key(s)");
        return value;
    }

    /** Which key a stored value is under. Never throws; never logs the value. */
    public State classify(String stored) {
        if (stored == null || stored.isBlank()) return State.BLANK;
        if (isLegacyPlaintext(stored)) return State.LEGACY_PLAINTEXT;
        if (decryptWith(key, stored) != null) return State.CURRENT_KEY;
        if (previousKey != null && decryptWith(previousKey, stored) != null) return State.PREVIOUS_KEY;
        return State.UNREADABLE;
    }

    /** True when the stored value should be re-encrypted onto the current key. */
    public boolean needsRotation(String stored) {
        State s = classify(stored);
        return s == State.LEGACY_PLAINTEXT || s == State.PREVIOUS_KEY;
    }

    /**
     * Re-encrypts a stored value onto the current key. The plaintext exists only in
     * a local variable here. Throws when the value cannot be read with any key.
     */
    String reEncrypt(String stored) {
        String value = decrypt(stored);
        if (value == null || value.isBlank()) throw new IllegalStateException("value is not readable with the configured key(s)");
        return encrypt(value);
    }

    private static boolean isLegacyPlaintext(String stored) {
        return stored.matches("\\d{4}");
    }

    /** Null on any failure (bad base64, wrong key, tampered tag); never logs the value. */
    private static String decryptWith(SecretKeySpec k, String stored) {
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            if (all.length <= IV_LENGTH) return null;
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(all, 0, iv, 0, IV_LENGTH);
            byte[] ct = new byte[all.length - IV_LENGTH];
            System.arraycopy(all, IV_LENGTH, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
