package com.churchgeniuspro.plaid.service;

import com.churchgeniuspro.plaid.config.PlaidProperties;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PlaidTokenCipher} — AES-256-GCM round-trip and fail-closed
 * behavior when no key is configured.
 */
class PlaidTokenCipherTest {

    private PlaidTokenCipher cipherWithKey() {
        PlaidProperties props = new PlaidProperties();
        // 32-byte (AES-256) key, base64-encoded.
        props.setTokenEncKey(Base64.getEncoder().encodeToString(new byte[32]));
        return new PlaidTokenCipher(props);
    }

    @Test
    void encryptDecryptRoundTrip() {
        PlaidTokenCipher cipher = cipherWithKey();
        String token = "access-sandbox-abc123-secret-token";

        String enc = cipher.encrypt(token);

        assertNotNull(enc);
        assertNotEquals(token, enc, "ciphertext must not equal plaintext");
        assertEquals(token, cipher.decrypt(enc), "decrypt must recover the original token");
    }

    @Test
    void encryptionIsNonDeterministic() {
        PlaidTokenCipher cipher = cipherWithKey();
        String token = "same-token";
        // Random IV per call → two ciphertexts differ, both decrypt correctly.
        assertNotEquals(cipher.encrypt(token), cipher.encrypt(token));
    }

    @Test
    void reportsConfiguredWhenKeyPresent() {
        assertTrue(cipherWithKey().isConfigured());
    }

    @Test
    void failsClosedWithoutKey() {
        PlaidTokenCipher cipher = new PlaidTokenCipher(new PlaidProperties()); // blank key
        assertFalse(cipher.isConfigured());
        assertThrows(IllegalStateException.class, () -> cipher.encrypt("x"));
    }
}
