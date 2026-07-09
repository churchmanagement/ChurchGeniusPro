package com.churchgeniuspro.util;

import java.util.*;

/**
 * Fuzzy answer grading utilities for Sunday School exams.
 *
 * <h3>Grading strategy (applied in submitExam regardless of questionType)</h3>
 * <ol>
 *   <li><b>Short-answer fast path</b> - if the correct answer is 5 words or fewer,
 *       use Levenshtein fuzzy matching. The student's answer just needs to contain
 *       one token (or phrase) that fuzzily matches the expected answer.
 *       Result: isCorrect = true/false, full or zero marks.</li>
 *   <li><b>Comprehensive keyword path</b> - for longer model answers, extract
 *       key words and key phrases, then measure keyword coverage.
 *       Coverage >= HIGH_CONFIDENCE_THRESHOLD (60%) -> auto-correct, full marks.
 *       Coverage >= PARTIAL_THRESHOLD (30%) -> suggested partial marks, teacher reviews.
 *       Otherwise -> suggestedMarks = 0, isCorrect = false.</li>
 * </ol>
 */
public final class AnswerGrader {

    private AnswerGrader() {}

    /** Coverage ratio at which a comprehensive answer is auto-approved as fully correct. */
    private static final double HIGH_CONFIDENCE_THRESHOLD = 0.60;

    /** Coverage ratio at which a partial suggested mark is set (teacher must still review). */
    private static final double PARTIAL_THRESHOLD = 0.30;

    /** Max word count in the correct answer to trigger the short-answer path. */
    private static final int SHORT_ANSWER_MAX_WORDS = 5;

    // Stop-words
    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "a","an","the","is","are","was","were","be","been","being",
            "have","has","had","do","does","did","will","would","could","should",
            "may","might","shall","can","need","dare","ought","used",
            "to","of","in","on","at","by","for","with","about","against",
            "between","through","during","before","after","above","below",
            "from","up","down","out","off","over","under","again","further",
            "then","once","and","but","or","nor","so","yet","both","either",
            "neither","not","only","own","same","than","too","very",
            "just","because","as","until","while","if","when","where",
            "how","what","which","who","whom","this","that","these","those",
            "i","me","my","myself","we","our","ours","ourselves",
            "you","your","yours","he","him","his","she","her","hers",
            "it","its","they","them","their","theirs"
    ));

    // Public API

    /**
     * Unified grading entry point - call this for every non-MCQ/TrueFalse question.
     * Internally chooses short-answer or comprehensive path based on the correct answer length.
     */
    public static GradeResult grade(String studentAnswer, String correctAnswer, int maxMarks) {
        if (studentAnswer == null || studentAnswer.isBlank()
                || correctAnswer == null || correctAnswer.isBlank()) {
            return new GradeResult(false, false, 0, Collections.emptyList());
        }

        String[] correctTokens = normalize(correctAnswer).split("\\s+");

        if (correctTokens.length <= SHORT_ANSWER_MAX_WORDS) {
            boolean correct = fuzzyMatchShort(studentAnswer, correctAnswer);
            return new GradeResult(
                    correct,
                    correct,
                    correct ? maxMarks : 0,
                    Collections.emptyList()
            );
        } else {
            return gradeComprehensive(studentAnswer, correctAnswer, maxMarks);
        }
    }

    /**
     * Fuzzy check for short answers (one or a few words).
     * Public so the controller can still call it directly for FillBlank/ShortAnswer types.
     *
     * Matching strategy for multi-word correct answers (e.g. "Augustus Caesar"):
     * 1. Exact normalised match.
     * 2. Whole-string Levenshtein similarity >= 75% (handles "augustine caesar").
     * 3. Token-level partial match: any student token (>= 4 chars) that
     *    - fuzzily matches any significant correct token at >= 65% similarity
     *      (handles "Caesar" -> "augustus caesar", "Augustine" -> "augustus"), or
     *    - is a substring of (or contains) a significant correct token
     *      (handles "Persian" inside "medopersian" after hyphen-stripping).
     */
    public static boolean fuzzyMatchShort(String studentAnswer, String correctAnswer) {
        if (studentAnswer == null || correctAnswer == null) return false;
        String s = normalize(studentAnswer);
        String c = normalize(correctAnswer);
        if (s.isEmpty() || c.isEmpty()) return false;
        if (s.equals(c)) return true;

        String[] sTokens = s.split("\\s+");
        String[] cTokens = c.split("\\s+");

        // Single-token correct answer: any student token that fuzzily matches counts
        if (cTokens.length == 1) {
            for (String tok : sTokens) {
                if (isFuzzyEqual(tok, cTokens[0])) return true;
            }
            return false;
        }

        // Multi-word: step 1 - whole-string similarity (strict 75% threshold)
        if (isFuzzyEqual(s, c)) return true;

        // Multi-word: step 2 - token-level partial match.
        // A student token (>= 4 chars) passes if it fuzzily equals OR is a substring of
        // any significant correct-answer token (>= 4 chars).
        // The looser 0.65 threshold is intentional: per-token comparison is already more
        // targeted than whole-string, so a slight relaxation is safe.
        for (String sTok : sTokens) {
            if (sTok.length() < 4) continue;
            for (String cTok : cTokens) {
                if (cTok.length() < 4) continue;
                if (isFuzzyEqualToken(sTok, cTok)) return true;
                // Substring containment handles hyphenated tokens normalised to one word,
                // e.g. "persian" inside "medopersian" (from "Medo-Persian Empire")
                if (sTok.contains(cTok) || cTok.contains(sTok)) return true;
            }
        }
        return false;
    }

    // Internal - comprehensive grading

    private static GradeResult gradeComprehensive(String studentAnswer, String modelAnswer, int maxMarks) {
        String sNorm = studentAnswer.toLowerCase().trim();

        List<String> keywords = extractKeywords(modelAnswer);
        if (keywords.isEmpty()) {
            return new GradeResult(null, false, 0, Collections.emptyList());
        }

        List<String> matched = new ArrayList<>();
        for (String kw : keywords) {
            if (containsFuzzy(sNorm, kw)) {
                matched.add(kw);
            }
        }

        double ratio = (double) matched.size() / keywords.size();
        int suggested = (int) Math.round(ratio * maxMarks);
        suggested = Math.max(0, Math.min(suggested, maxMarks));

        if (ratio >= HIGH_CONFIDENCE_THRESHOLD) {
            return new GradeResult(true, true, maxMarks, matched);
        } else if (ratio >= PARTIAL_THRESHOLD) {
            return new GradeResult(null, false, suggested, matched);
        } else {
            return new GradeResult(null, false, 0, matched);
        }
    }

    // Helpers

    private static String normalize(String s) {
        return s.trim().toLowerCase()
                .replaceAll("[^a-z0-9\\s]", "")
                .replaceAll("\\s+", " ");
    }

    /** Strict fuzzy equality (>= 75% similarity) used for whole-string comparisons. */
    private static boolean isFuzzyEqual(String a, String b) {
        if (a.equals(b)) return true;
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return true;
        int dist = levenshtein(a, b);
        double ratio = 1.0 - (double) dist / maxLen;
        return ratio >= 0.75;
    }

    /**
     * Looser fuzzy equality (>= 65% similarity) for per-token partial matching in
     * multi-word answers. Allows close variants like "Augustine" approx "Augustus".
     */
    private static boolean isFuzzyEqualToken(String a, String b) {
        if (a.equals(b)) return true;
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return true;
        int dist = levenshtein(a, b);
        double ratio = 1.0 - (double) dist / maxLen;
        return ratio >= 0.65;
    }

    private static int levenshtein(String a, String b) {
        int m = a.length(), n = b.length();
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 0; i <= m; i++) dp[i][0] = i;
        for (int j = 0; j <= n; j++) dp[0][j] = j;
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i-1][j] + 1, dp[i][j-1] + 1),
                                    dp[i-1][j-1] + cost);
            }
        }
        return dp[m][n];
    }

    /**
     * Extracts meaningful keywords and 2-word phrases from the model answer.
     * Single tokens must be non-stop and >= 3 chars; bigrams require both tokens non-stop.
     */
    static List<String> extractKeywords(String modelAnswer) {
        String lower = modelAnswer.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ").trim();
        String[] tokens = lower.split("\\s+");
        LinkedHashSet<String> result = new LinkedHashSet<>();

        // 2-gram phrases first (higher priority)
        for (int i = 0; i < tokens.length - 1; i++) {
            String t1 = tokens[i], t2 = tokens[i + 1];
            if (!STOP.contains(t1) && !STOP.contains(t2)
                    && t1.length() >= 3 && t2.length() >= 3) {
                result.add(t1 + " " + t2);
            }
        }

        // Single significant tokens (non-stop, length >= 3 to catch names like "God")
        for (String tok : tokens) {
            if (!STOP.contains(tok) && tok.length() >= 3) {
                result.add(tok);
            }
        }

        return new ArrayList<>(result);
    }

    private static boolean containsFuzzy(String studentNorm, String keyword) {
        if (studentNorm.contains(keyword)) return true;
        String[] kwTokens = keyword.split("\\s+");
        if (kwTokens.length == 1) {
            for (String tok : studentNorm.split("\\s+")) {
                if (isFuzzyEqual(tok, kwTokens[0])) return true;
            }
            return false;
        }
        String[] studentTokens = studentNorm.split("\\s+");
        for (int i = 0; i <= studentTokens.length - kwTokens.length; i++) {
            boolean allMatch = true;
            for (int j = 0; j < kwTokens.length; j++) {
                if (!isFuzzyEqual(studentTokens[i + j], kwTokens[j])) {
                    allMatch = false; break;
                }
            }
            if (allMatch) return true;
        }
        return false;
    }

    // Result type

    public static final class GradeResult {
        /** true = correct, false = incorrect, null = needs teacher review */
        public final Boolean isCorrect;
        /** true = this result was determined automatically with high confidence */
        public final boolean autoGraded;
        public final int suggestedMarks;
        public final List<String> matchedKeywords;

        GradeResult(Boolean isCorrect, boolean autoGraded, int suggestedMarks,
                    List<String> matchedKeywords) {
            this.isCorrect       = isCorrect;
            this.autoGraded      = autoGraded;
            this.suggestedMarks  = suggestedMarks;
            this.matchedKeywords = Collections.unmodifiableList(matchedKeywords);
        }
    }
}
