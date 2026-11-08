package com.churchgeniuspro.util;

import java.text.Normalizer;
import java.util.*;

/**
 * Text rules for Guess It games written in more than one language
 * (e.g. "Moses | മോശെ").
 *
 * <p>The correct answer a host types does not have to be a character-for-character
 * copy of an option: "Moses – മോശെ", "moses" or "മോശെ" all identify the option
 * "Moses | മോശെ". {@link #canonicalAnswer} returns that option's exact text, which
 * is what gets stored — answer checking during play stays an exact comparison.
 */
public final class GuessItText {

    private GuessItText() {}

    /** Leading list markers people paste in: "* ", "- ", "• ", "1. ", "2) ". */
    private static final String BULLET = "^\\s*(?:[*•·▪◦‣-]|\\d{1,2}[.)])\\s+";

    /** Separators between translations: | / – — and a hyphen with spaces round it. */
    private static final String SEPARATOR = "\\s*[|/–—]\\s*|\\s+-\\s+";

    /** An option or answer with its list marker removed and spaces tidied. */
    public static String clean(String s) {
        if (s == null) return "";
        return s.replaceFirst(BULLET, "").trim().replaceAll("\\s+", " ");
    }

    /** The language parts of an option, normalised for comparison. */
    static List<String> parts(String s) {
        List<String> out = new ArrayList<>();
        for (String p : clean(s).split(SEPARATOR)) {
            String n = norm(p);
            if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    private static String norm(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC).trim()
                .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * The option the host meant, or {@code null} when the answer names no option
     * or names more than one.
     */
    public static String canonicalAnswer(List<String> options, String answer) {
        if (options == null || answer == null || answer.isBlank()) return null;
        for (String o : options) if (o.equals(answer)) return o;

        List<String> a = parts(answer);
        if (a.isEmpty()) return null;
        for (String o : options) if (parts(o).equals(a)) return o;

        String found = null;
        for (String o : options) {
            if (!Collections.disjoint(parts(o), a)) {
                if (found != null && !found.equals(o)) return null;   // ambiguous
                found = o;
            }
        }
        return found;
    }
}
