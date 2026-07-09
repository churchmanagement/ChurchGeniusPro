package com.churchgeniuspro.util;

import com.churchgeniuspro.util.EtlTargetSchema.TargetColumn;

import java.util.*;

/**
 * Deterministic column matcher for the ETL mapping stage (Phase 3).
 *
 * <p>Given the profiled source columns and a target table, it scores every
 * (target column, source column) pair on NAME similarity (exact / synonym /
 * token-overlap / substring) modulated by TYPE compatibility, then greedily
 * assigns each source column to at most one target. Output is a per-target
 * suggestion with a confidence in [0,1], a human rationale, and a status of
 * {@code AUTO} (high confidence) or {@code NEEDS_REVIEW}.
 *
 * <p>Pure and static so it is unit-testable and can be ported to JS for parity.
 * The AI fallback (in the service layer) only fills targets this matcher left
 * without a source — it never overrides a deterministic match.
 */
public final class MappingMatcher {

    private MappingMatcher() {}

    public static final double AUTO_THRESHOLD   = 0.85;
    public static final double REVIEW_THRESHOLD = 0.55;

    /** A profiled source column (subset of SourceColumnProfile the matcher needs). */
    public record SourceCol(String name, String type) {}

    /** One mapping suggestion for a target column. */
    public static final class Suggestion {
        public final String targetColumn;
        public final String targetType;
        public String sourceColumn;     // null when unmatched
        public double confidence;       // 0..1
        public String status;           // AUTO | NEEDS_REVIEW
        public String transform;
        public String rationale;

        Suggestion(String targetColumn, String targetType) {
            this.targetColumn = targetColumn;
            this.targetType   = targetType;
            this.confidence   = 0d;
            this.status       = "NEEDS_REVIEW";
            this.transform    = EtlTargetSchema.suggestedTransform(targetType);
            this.rationale    = "No confident source column found";
        }
    }

    /**
     * Produce one Suggestion per target column of {@code table}. Deterministic
     * matches are filled; the rest are returned unmatched (confidence 0) for the
     * AI fallback / operator.
     */
    public static List<Suggestion> match(String table, List<SourceCol> sources) {
        List<TargetColumn> targets = EtlTargetSchema.columns(table);
        Map<String, Suggestion> byTarget = new LinkedHashMap<>();
        for (TargetColumn t : targets) byTarget.put(t.name(), new Suggestion(t.name(), t.type()));

        // Score all viable pairs.
        List<Pair> pairs = new ArrayList<>();
        for (TargetColumn t : targets) {
            for (SourceCol s : sources) {
                double nameScore = nameScore(t, s.name());
                if (nameScore <= 0) continue;
                double typeCompat = typeCompatibility(t.type(), s.type());
                double combined = round3(nameScore * (0.7 + 0.3 * typeCompat));
                if (combined < REVIEW_THRESHOLD) continue;
                pairs.add(new Pair(t, s, combined, nameScore, typeCompat));
            }
        }

        // Greedy: highest-confidence pairs win; each source used once, each target once.
        pairs.sort((a, b) -> Double.compare(b.score, a.score));
        Set<String> usedSources = new HashSet<>();
        Set<String> filledTargets = new HashSet<>();
        for (Pair p : pairs) {
            if (filledTargets.contains(p.target.name())) continue;
            if (usedSources.contains(p.source.name())) continue;
            Suggestion sug = byTarget.get(p.target.name());
            sug.sourceColumn = p.source.name();
            sug.confidence   = p.score;
            sug.status       = p.score >= AUTO_THRESHOLD ? "AUTO" : "NEEDS_REVIEW";
            sug.rationale    = rationale(p);
            filledTargets.add(p.target.name());
            usedSources.add(p.source.name());
        }
        return new ArrayList<>(byTarget.values());
    }

    private record Pair(TargetColumn target, SourceCol source, double score,
                        double nameScore, double typeCompat) {}

    // ───────────────────────── name scoring ─────────────────────────

    /** 0..1 name-similarity between a target column (+synonyms) and a source name. */
    static double nameScore(TargetColumn target, String sourceName) {
        String src = normalize(sourceName);
        if (src.isEmpty()) return 0;
        Set<String> srcTok = rawTokens(sourceName);

        String canon = normalize(target.name());
        double best = 0;

        List<String> candidates = new ArrayList<>();
        candidates.add(target.name());
        candidates.addAll(target.synonyms());

        for (String candRaw : candidates) {
            String cand = normalize(candRaw);
            if (cand.isEmpty()) continue;
            boolean isCanon = cand.equals(canon);

            if (src.equals(cand)) {                       // exact (canonical or synonym)
                best = Math.max(best, isCanon ? 1.0 : 0.92);
                continue;
            }
            // containment (e.g. "memberfirstname" contains "firstname")
            if (src.contains(cand) || cand.contains(src)) {
                double cover = (double) Math.min(src.length(), cand.length())
                             / Math.max(src.length(), cand.length());
                best = Math.max(best, 0.6 + 0.32 * cover);
            }
            // token Jaccard on raw tokens (handles "member first name" vs "first_name")
            double j = jaccard(srcTok, rawTokens(candRaw));
            if (j > 0) best = Math.max(best, 0.5 + 0.4 * j);
        }
        return round3(Math.min(best, 1.0));
    }

    static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    /** Tokenize a raw column name on separators + camelCase for overlap scoring. */
    static Set<String> rawTokens(String raw) {
        if (raw == null) return Set.of();
        String spaced = raw
            .replaceAll("([a-z0-9])([A-Z])", "$1 $2")   // camelCase → camel Case
            .replaceAll("[^A-Za-z0-9]+", " ")
            .trim().toLowerCase();
        Set<String> out = new LinkedHashSet<>();
        for (String t : spaced.split("\\s+")) if (!t.isBlank()) out.add(t);
        return out;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(a); inter.retainAll(b);
        Set<String> union = new HashSet<>(a); union.addAll(b);
        return (double) inter.size() / union.size();
    }

    // ───────────────────────── type compatibility ─────────────────────────

    /** 0..1 how well a profiled source type fits a semantic target type. */
    static double typeCompatibility(String targetType, String sourceType) {
        String t = targetType == null ? "STRING" : targetType;
        String s = sourceType == null ? "STRING" : sourceType;
        if (s.equals("EMPTY") || s.equals("MIXED")) return 0.6;  // unknown — don't over-penalize
        if (t.equals(s)) return 1.0;
        return switch (t) {
            case "STRING"  -> 1.0;                                   // anything stringifies
            case "DECIMAL" -> s.equals("INTEGER") ? 1.0 : 0.4;
            case "INTEGER" -> s.equals("DECIMAL") ? 0.7 : 0.4;
            case "DATE"    -> s.equals("STRING") ? 0.6 : 0.3;
            case "EMAIL"   -> s.equals("STRING") ? 0.6 : 0.3;
            case "PHONE"   -> s.equals("INTEGER") ? 0.7 : (s.equals("STRING") ? 0.6 : 0.3);
            case "BOOLEAN" -> s.equals("INTEGER") ? 0.6 : (s.equals("STRING") ? 0.4 : 0.3);
            default        -> 0.5;
        };
    }

    // ───────────────────────── helpers ─────────────────────────

    private static String rationale(Pair p) {
        String name = p.nameScore >= 0.999 ? "exact name match"
                    : p.nameScore >= 0.9   ? "synonym match"
                    : p.nameScore >= 0.7   ? "name contains target term"
                    :                        "partial name overlap";
        String type = p.typeCompat >= 0.999 ? "type matches"
                    : p.typeCompat >= 0.6   ? "type compatible"
                    :                          "type differs (transform may be needed)";
        return "'" + p.source.name() + "' → " + p.target.name()
             + " (" + name + ", " + type + ", confidence "
             + String.format(java.util.Locale.US, "%.2f", p.score) + ")";
    }

    public static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
