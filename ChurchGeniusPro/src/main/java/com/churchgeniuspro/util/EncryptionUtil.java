package com.churchgeniuspro.util;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * AES-128/ECB utility for encrypting / decrypting client IDs in URLs.
 *
 * <p><b>Security note.</b> This is deterministic, unauthenticated encryption with
 * one key shared by every feature — a value minted for one purpose is valid for
 * all of them, and anyone holding the key can mint a value for any tenant. The
 * key used to be a string literal in this file (and therefore in every shipped
 * JAR). It now comes from the {@code CGP_CID_KEY} environment variable; the
 * literal remains only as a fallback so existing links keep working until the
 * key is rotated deliberately, and its use is logged as an error at startup.
 *
 * <p>Rotating the key invalidates every public link, QR code and {@code cid}
 * currently in circulation. The longer-term replacement is per-purpose random
 * tokens stored in the database (as {@code PublicScreenLink} already is).
 */
public final class EncryptionUtil {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EncryptionUtil.class);

    /** Legacy 16-ASCII-char (128-bit) key — fallback only; see class note. */
    private static final String LEGACY_KEY = "ChurchGenius2024";
    private static final String ALGORITHM  = "AES/ECB/PKCS5Padding";

    /** Resolved once: {@code CGP_CID_KEY} (must be exactly 16 bytes), else the legacy literal. */
    private static final String SECRET_KEY = resolveKey();

    private static String resolveKey() {
        String env = System.getenv("CGP_CID_KEY");
        if (env == null || env.isBlank()) {
            log.error("CGP_CID_KEY is not set — public-link tokens are using the legacy built-in key. "
                    + "Set a 16-character CGP_CID_KEY and rotate at a planned time (all existing links will change).");
            return LEGACY_KEY;
        }
        if (env.getBytes(StandardCharsets.UTF_8).length != 16) {
            log.error("CGP_CID_KEY must be exactly 16 bytes (AES-128); falling back to the legacy key.");
            return LEGACY_KEY;
        }
        log.info("Public-link token key loaded from CGP_CID_KEY.");
        return env;
    }

    private EncryptionUtil() {}

    /**
     * True when the key came from {@code CGP_CID_KEY}. Referencing this at startup
     * forces the class to initialise, so the "loaded" / "not set" line above appears
     * in the boot log instead of whenever a public form is first opened.
     */
    public static boolean isKeyFromEnvironment() {
        return !LEGACY_KEY.equals(SECRET_KEY);
    }

    /**
     * URL-safe HMAC-SHA256 of {@code data} under the same env-sourced key — for
     * signing a link's parameters so they cannot be altered (unsubscribe footer).
     */
    public static String sign(String data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    /** Constant-time check of {@link #sign}. */
    public static boolean verify(String data, String signature) {
        if (data == null || signature == null) return false;
        return java.security.MessageDigest.isEqual(
                sign(data).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }

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
