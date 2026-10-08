package com.churchgeniuspro.plaid.service;

import com.churchgeniuspro.plaid.config.PlaidProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Authenticated encryption for Plaid access tokens (encrypted at rest).
 *
 * <p>Uses AES-256-GCM with a random 12-byte IV per value; the stored form is
 * {@code base64(IV || ciphertext||tag)}. The 32-byte key is supplied as a
 * base64 string via {@code PLAID_TOKEN_ENC_KEY} — it is NOT hardcoded, and this
 * class deliberately does not reuse the legacy {@code EncryptionUtil}
 * (AES-ECB, fixed key), which is only suitable for obfuscation.
 *
 * <p>Fails closed: if no valid key is configured, encrypt/decrypt throw rather
 * than silently storing tokens in a weakly-protected form.
 *
 * <h2>Key rotation</h2>
 * Encryption always uses the current key. Decryption tries the current key and
 * then, if configured, {@code PLAID_TOKEN_ENC_KEY_PREVIOUS} — so the moment a new
 * key is deployed alongside the old one, every stored token still opens and the
 * application keeps running. {@link PlaidTokenRotationService} then re-encrypts
 * the stored rows onto the current key, after which the previous key can be
 * removed. Without that overlap, changing the key would orphan every stored bank
 * token and force every church to reconnect.
 */
@Component
public class PlaidTokenCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;      // 96-bit nonce (GCM standard)
    private static final int TAG_LENGTH_BITS = 128;

    private final PlaidProperties props;
    private final SecureRandom random = new SecureRandom();

    public PlaidTokenCipher(PlaidProperties props) {
        this.props = props;
    }

    /** True when a usable 16/24/32-byte AES key is configured. */
    public boolean isConfigured() {
        try {
            int len = keyBytes().length;
            return len == 16 || len == 24 || len == 32;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        SecretKeySpec key = keySpec();   // validates the key; throws a clear reason if bad
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
            // Never include the plaintext token in the message.
            throw new IllegalStateException("Plaid token encryption failed", e);
        }
    }

    /**
     * Decrypts with the current key, falling back to the previous key during a
     * rotation. GCM is authenticated, so a wrong key fails rather than returning
     * plausible rubbish — trying one and then the other is safe.
     */
    public String decrypt(String stored) {
        if (stored == null) return null;
        try {
            return decryptWith(keySpec(), stored);
        } catch (Exception primaryFailure) {
            SecretKeySpec previous = previousKeySpec();
            if (previous == null) {
                throw new IllegalStateException("Plaid token decryption failed", primaryFailure);
            }
            try {
                return decryptWith(previous, stored);
            } catch (Exception e) {
                // Neither key opens it. Report the CURRENT key's failure: during a
                // rotation the previous key failing too means the value predates
                // both, which is the more useful thing to chase.
                throw new IllegalStateException("Plaid token decryption failed", primaryFailure);
            }
        }
    }

    /**
     * Whether this stored value still needs re-encrypting onto the current key.
     *
     * <p>Never throws: an unreadable value answers {@code false}, because it is not
     * something a rotation pass can fix and must not stop it. Blank values (the
     * stub a soft-deleted item carries) answer {@code false} too.
     */
    public boolean needsRotation(String stored) {
        if (stored == null || stored.isBlank()) return false;
        try {
            decryptWith(keySpec(), stored);
            return false;                       // already on the current key
        } catch (Exception currentFailed) {
            SecretKeySpec previous = previousKeySpec();
            if (previous == null) return false; // no rotation in progress
            try {
                decryptWith(previous, stored);
                return true;                    // opens with the old key: rewrite it
            } catch (Exception e) {
                return false;                   // opens with neither; not ours to fix
            }
        }
    }

    /** True when a previous key is configured, i.e. a rotation is in progress. */
    public boolean rotationInProgress() {
        return previousKeySpec() != null;
    }

    private String decryptWith(SecretKeySpec key, String stored) throws Exception {
        byte[] all = Base64.getDecoder().decode(stored);
        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(all, 0, iv, 0, IV_LENGTH);
        byte[] ct = new byte[all.length - IV_LENGTH];
        System.arraycopy(all, IV_LENGTH, ct, 0, ct.length);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
    }

    private SecretKeySpec keySpec() {
        return new SecretKeySpec(keyBytes(), "AES");
    }

    /** The previous key, or null when none is configured or it is unusable. */
    private SecretKeySpec previousKeySpec() {
        String k = props.getTokenEncKeyPrevious();
        if (k == null || k.isBlank()) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(k.trim());
            if (bytes.length != 16 && bytes.length != 24 && bytes.length != 32) return null;
            return new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Decodes and validates the configured key. Throws with a specific, non-secret
     * reason so misconfiguration is obvious in the logs.
     */
    private byte[] keyBytes() {
        String k = props.getTokenEncKey();
        if (k == null || k.isBlank()) {
            throw new IllegalStateException(
                    "PLAID_TOKEN_ENC_KEY is not set — set it to a base64-encoded 32-byte AES key "
                    + "(e.g. `openssl rand -base64 32`).");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(k.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "PLAID_TOKEN_ENC_KEY is not valid base64. Generate one with `openssl rand -base64 32`.");
        }
        if (bytes.length != 16 && bytes.length != 24 && bytes.length != 32) {
            throw new IllegalStateException(
                    "PLAID_TOKEN_ENC_KEY must decode to 16, 24, or 32 bytes (AES-128/192/256); got "
                    + bytes.length + " bytes. Generate a 32-byte key with `openssl rand -base64 32`.");
        }
        return bytes;
    }
}
