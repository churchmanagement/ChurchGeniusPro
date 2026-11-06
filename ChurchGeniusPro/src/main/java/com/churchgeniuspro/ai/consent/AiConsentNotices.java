package com.churchgeniuspro.ai.consent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * App Store readiness D6 — the three AI data-sharing notices, worded exactly as the user
 * approved on 2026-11-06. The app shows the text the server sends, so this class is the one
 * source of the wording.
 *
 * <p>RULE: changing a title or paragraph REQUIRES raising that notice's version — that is what
 * makes everyone see the new text. {@code AiConsentNoticeTextTest} pins each version to the
 * SHA-256 of its text and fails when the text changes without a version bump.
 */
public final class AiConsentNotices {

    private AiConsentNotices() {}

    public enum Type { VOICE, ASSISTANT, SCAN }

    public static final String BUTTON_CANCEL = "Cancel";
    public static final String BUTTON_AGREE = "Agree & Continue";

    public record Notice(Type type, int version, String title, List<String> paragraphs) {
        public String text() { return String.join("\n", paragraphs); }
    }

    private static final Map<Type, Notice> CURRENT = new EnumMap<>(Type.class);
    static {
        CURRENT.put(Type.VOICE, new Notice(Type.VOICE, 1,
                "AI Voice Entry – Data Sharing Notice",
                List.of("To process your voice request, ChurchGeniusPro sends your audio recording to OpenAI for transcription and processing. "
                                + "It may also send a short list of your church's fund, expense category and payment method names to improve recognition.",
                        "Your recording may contain names, financial information or other personal details.",
                        "Do you want to continue?")));
        CURRENT.put(Type.ASSISTANT, new Notice(Type.ASSISTANT, 1,
                "AI Assistant – Data Sharing Notice",
                List.of("To answer your question or draft text, ChurchGeniusPro sends what you type, including your recent messages in a conversation, "
                                + "to OpenAI for processing. This may include names, financial information or other personal details.",
                        "Do you want to continue?")));
        CURRENT.put(Type.SCAN, new Notice(Type.SCAN, 1,
                "Document Scan – Data Sharing Notice",
                List.of("To read this document, ChurchGeniusPro sends the photo or file to OpenAI for processing. "
                                + "It may contain names, account numbers, financial information or other personal details.",
                        "Do you want to continue?")));
    }

    public static Notice current(Type type) { return CURRENT.get(type); }

    public static Type parse(String raw) {
        if (raw == null) return null;
        try { return Type.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) { return null; }
    }

    /** SHA-256 (hex) of the title + paragraphs — what the version pin test compares. */
    public static String fingerprint(Type type) {
        Notice n = current(type);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest((n.title() + "\n" + n.text()).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
