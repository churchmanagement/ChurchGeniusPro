package com.churchgeniuspro.util;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Simple AES-128/ECB utility for encrypting / decrypting client IDs in URLs.
 * The key is fixed and application-internal — this is for obfuscation, not
 * high-security cryptography.
 */
public final class EncryptionUtil {

    /** 16 ASCII chars = 128-bit AES key. */
    private static final String SECRET_KEY = "ChurchGenius2024";
    private static final String ALGORITHM  = "AES/ECB/PKCS5Padding";

    private EncryptionUtil() {}

    public static String encrypt(String plaintext) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(
                SECRET_KEY.getBytes(StandardCharsets.UTF_8), "AES");
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec);
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        // URL-safe Base64 without padding
        return Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
    }

    public static String decrypt(String ciphertext) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(
                SECRET_KEY.getBytes(StandardCharsets.UTF_8), "AES");
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, keySpec);
        byte[] decoded   = Base64.getUrlDecoder().decode(ciphertext);
        byte[] decrypted = cipher.doFinal(decoded);
        return new String(decrypted, StandardCharsets.UTF_8);
    }
}
