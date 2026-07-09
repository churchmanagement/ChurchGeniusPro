package com.churchgeniuspro.util;

/**
 * Centralised member-name formatting.
 *
 * <p>When a member has a nickname, their name is shown as
 * {@code "First Last (Nickname)"} everywhere in the app — except on legal
 * documents (tax reports, certificates) and official PDF forms, which must use
 * the legal name only and therefore should NOT call this helper.
 */
public final class MemberNameUtil {

    private MemberNameUtil() { }

    /** Legal name only: {@code "First Last"} (trimmed, single-spaced). */
    public static String legalName(String firstName, String lastName) {
        String f = firstName == null ? "" : firstName.trim();
        String l = lastName  == null ? "" : lastName.trim();
        return (f + " " + l).trim().replaceAll("\\s+", " ");
    }

    /**
     * Display name with optional nickname:
     * {@code "First Last (Nickname)"} when a nickname is present,
     * otherwise just {@code "First Last"}.
     */
    public static String display(String firstName, String lastName, String nickname) {
        String base = legalName(firstName, lastName);
        if (nickname != null && !nickname.trim().isEmpty()) {
            return base.isEmpty() ? "(" + nickname.trim() + ")"
                                  : base + " (" + nickname.trim() + ")";
        }
        return base;
    }
}
