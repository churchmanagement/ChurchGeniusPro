package com.churchgeniuspro.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * App Store readiness D13 (Apple guideline 1.2, user-generated content): a small server-side
 * filter for text people write to OTHER people — Members Chat messages and replies, and prayer
 * requests sent from the public prayer page or the public prayer link.
 *
 * <p>It refuses text containing a listed offensive word (profanity, sexual terms, slurs) and the
 * caller tells the sender why. Matching is by WHOLE word after normalising case, accents,
 * common letter substitutions (0→o, 1→i, 3→e, 4→a, 5→s, @→a, $→s) and letters separated by
 * dots / dashes / spaces in short runs ("f.u.c.k"), so ordinary words that merely contain a
 * listed word ("Scunthorpe", "assistant", "class", "Dickens") pass. Words that also appear in
 * Scripture (KJV) or in medical prayer requests are deliberately NOT listed. It is a first line of
 * defence; reporting, blocking and staff review in Members Chat remain the moderation tools.
 * The refused text is never logged.
 */
public final class ContentFilter {

    private ContentFilter() {}

    public static final String CODE = "CONTENT_BLOCKED";
    public static final String MESSAGE =
            "This text contains language that isn't allowed in ChurchGeniusPro. Please reword it and try again.";

    /** Whole words refused. Lower case, letters only; plural / -ing forms listed where common. */
    static final Set<String> WORDS = Set.of(
            // profanity
            "fuck", "fucks", "fucked", "fucker", "fuckers", "fucking", "motherfucker", "motherfuckers", "fuk", "fck",
            "shit", "shits", "shitty", "bullshit", "horseshit", "shithead",
            "bitch", "bitches", "bitchy",
            "asshole", "assholes", "arsehole", "dickhead", "dickheads",
            "cunt", "cunts", "twat", "twats", "wanker", "wankers",
            "douchebag", "jackass", "dumbass", "goddamn", "goddamnit",
            // sexual
            "porn", "porno", "pornography", "blowjob", "handjob", "cumshot", "dildo", "milf",
            "slut", "sluts", "nudes", "sexting",
            "pussy", "pussies", "boobs", "titties",
            // slurs
            "nigger", "niggers", "nigga", "niggas", "faggot", "faggots",
            "tranny", "trannies", "retard", "retards", "spic", "spics",
            "kike", "kikes", "wetback", "wetbacks", "gook", "gooks", "raghead", "towelhead", "beaner", "beaners",
            "paki", "pakis",
            // threats / abuse
            "kys");

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    /** Runs of single letters split by . - _ * or spaces: "f.u.c.k", "s h i t". */
    private static final Pattern SPACED = Pattern.compile("\\b(?:[a-z][.\\-_* ]){2,}[a-z]\\b");
    private static final Pattern NON_LETTER = Pattern.compile("[^a-z]+");

    /** True when the text contains a refused word. Null / blank text is allowed. */
    public static boolean isBlocked(String text) {
        if (text == null || text.isBlank()) return false;
        String s = normalise(text);
        java.util.regex.Matcher m = SPACED.matcher(s);
        StringBuilder joined = new StringBuilder();
        int last = 0;
        while (m.find()) {
            joined.append(s, last, m.start()).append(m.group().replaceAll("[.\\-_* ]", ""));
            last = m.end();
        }
        joined.append(s.substring(last));
        for (String w : NON_LETTER.split(joined.toString())) {
            if (!w.isEmpty() && WORDS.contains(w)) return true;
            // stretched letters: "fuuuck", "shiiit"
            if (w.length() > 3) {
                String squeezed = w.replaceAll("(.)\\1{2,}", "$1");
                if (!squeezed.equals(w) && (WORDS.contains(squeezed) || WORDS.contains(squeezed.replaceAll("(.)\\1+", "$1")))) return true;
            }
        }
        return false;
    }

    /** True when any of the texts is blocked. */
    public static boolean anyBlocked(String... texts) {
        if (texts == null) return false;
        for (String t : texts) if (isBlocked(t)) return true;
        return false;
    }

    static String normalise(String text) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFKD);
        s = MARKS.matcher(s).replaceAll("").toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // a digit / symbol stands for a letter only when a letter follows it ("sh1t", "5hit");
            // trailing punctuation ("shit!") and plain numbers stay as they are
            boolean letterNear = i + 1 < s.length() && Character.isLetter(s.charAt(i + 1));
            switch (c) {
                case '0' -> b.append(letterNear ? 'o' : c);
                case '1', '!' -> b.append(letterNear ? 'i' : c);
                case '3' -> b.append(letterNear ? 'e' : c);
                case '4', '@' -> b.append(letterNear ? 'a' : c);
                case '5', '$' -> b.append(letterNear ? 's' : c);
                case '7' -> b.append(letterNear ? 't' : c);
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    /** The JSON body a controller returns for a refused text (HTTP 422). */
    public static java.util.Map<String, Object> refusal() {
        return java.util.Map.of("error", MESSAGE, "message", MESSAGE, "code", CODE);
    }
}
