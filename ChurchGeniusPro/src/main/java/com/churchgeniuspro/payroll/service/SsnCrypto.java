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
 * <p>{@link #decrypt} transparently passes through legacy PLAINTEXT values
 * (exactly 4 digits) recorded before encryption existed; they are re-encrypted
 * the next time the employee record is saved.
 */
@Component
public class SsnCrypto {

    private static final Logger log = LoggerFactory.getLogger(SsnCrypto.class);
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public SsnCrypto(@Value("${payroll.ssn-enc-key:}") String configuredKey) {
        this.key = new SecretKeySpec(resolveKeyBytes(configuredKey), "AES");
    }

    private static byte[] resolveKeyBytes(String configuredKey) {
        if (configuredKey != null && !configuredKey.isBlank()) {
            byte[] bytes = Base64.getDecoder().decode(configuredKey.trim());
            if (bytes.length != 16 && bytes.length != 24 && bytes.length != 32) {
                throw new IllegalStateException(
                        "PAYROLL_SSN_ENC_KEY must decode to 16/24/32 bytes; got " + bytes.length
                        + ". Generate one with `openssl rand -base64 32`.");
            }
            return bytes;
        }
        log.warn("PAYROLL_SSN_ENC_KEY is not set — using a derived development key for SSN "
                + "encryption. Set a real base64 32-byte key in production (`openssl rand -base64 32`).");
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest("ChurchGeniusPro:payroll-ssn:v1".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Encrypts a last-4 value; null/blank passes through unchanged. */
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
     * Decrypts a stored value for authorized display. Legacy plaintext rows
     * (exactly 4 digits, from before encryption) pass through unchanged;
     * undecryptable values return {@code null} rather than leaking ciphertext.
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) return stored;
        if (stored.matches("\\d{4}")) return stored;   // legacy plaintext row
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(all, 0, iv, 0, IV_LENGTH);
            byte[] ct = new byte[all.length - IV_LENGTH];
            System.arraycopy(all, IV_LENGTH, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("SSN decryption failed for a stored value (wrong key?) — returning null");
            return null;
        }
    }
}
