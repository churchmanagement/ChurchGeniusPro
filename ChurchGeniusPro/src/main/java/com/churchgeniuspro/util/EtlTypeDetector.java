package com.churchgeniuspro.util;

import java.util.*;

/**
 * ETL Migration Phase 5 — guesses which data type a file holds from its headers alone, so
 * the wizard's Files step can label each dropped file ("this looks like Pledges") before
 * the admin maps it. It is a suggestion; the admin can change it.
 *
 * <p>Score per type = 1 point per target column the matcher would place automatically
 * (preset aliases count as automatic), ½ per review-level match, +3 when the type's own
 * id header is present ("Pledge ID", "Registration ID" — the generic "ID" does not count),
 * −4 when a required column cannot be placed at all. The best type is reported with a
 * confidence: HIGH when it leads the next by 2 points or more, otherwise LOW.
 */
public final class EtlTypeDetector {

    private EtlTypeDetector() {}

    public record Score(String table, double score, int auto, int review, boolean requiredCovered, boolean ownId) {}

    public record Result(String table, String confidence, List<Score> ranking) {}

    public static Result detect(List<String> headers, SourcePreset preset) {
        SourcePreset p = preset == null ? SourcePreset.GENERIC : preset;
        List<MappingMatcher.SourceCol> sources = headers.stream().map(h -> new MappingMatcher.SourceCol(h, "STRING")).toList();
        Set<String> norm = new HashSet<>();
        for (String h : headers) norm.add(SourcePreset.norm(h));
        List<Score> scores = new ArrayList<>();
        for (String table : EtlTargetSchema.tables()) {
            Set<String> placed = new HashSet<>();
            int auto = 0, review = 0;
            for (EtlTargetSchema.TargetColumn c : EtlTargetSchema.columns(table))
                for (String h : headers) if (p.matches(table, c.name(), h)) { placed.add(c.name()); auto++; break; }
            for (MappingMatcher.Suggestion s : MappingMatcher.match(table, sources, placed)) {
                if (s.sourceColumn == null) continue;
                placed.add(s.targetColumn);
                if ("AUTO".equals(s.status)) auto++; else review++;
            }
            boolean required = true;
            for (EtlTargetSchema.TargetColumn c : EtlTargetSchema.required(table)) if (!placed.contains(c.name())) required = false;
            if ("family".equals(table) && !(placed.contains("firstName") || placed.contains("lastName") || placed.contains("fullName"))) required = false;
            boolean ownId = false;
            for (String id : rowIds(table, p)) if (!"id".equals(id) && norm.contains(id)) ownId = true;
            double score = auto + 0.5 * review + (ownId ? 3 : 0) + (required ? 0 : -4);
            scores.add(new Score(table, score, auto, review, required, ownId));
        }
        scores.sort((a, b) -> Double.compare(b.score(), a.score()));
        Score best = scores.get(0);
        if (best.score() <= 0) return new Result(null, "NONE", scores.subList(0, Math.min(3, scores.size())));
        double margin = scores.size() > 1 ? best.score() - scores.get(1).score() : best.score();
        return new Result(best.table(), margin >= 2 ? "HIGH" : "LOW", scores.subList(0, Math.min(3, scores.size())));
    }

    /** The type's own id headers (normalised): record types from the schema, people/money from the preset. */
    private static List<String> rowIds(String table, SourcePreset p) {
        if (EtlTargetSchema.isRecordTable(table)) return EtlTargetSchema.rowIdColumns(table);
        List<String> out = new ArrayList<>();
        for (String h : p.idColumns(table)) out.add(SourcePreset.norm(h));
        if ("family".equals(table)) out.addAll(List.of("personid", "individualid", "memberid"));
        if ("income".equals(table)) out.addAll(List.of("transactionid", "paymentid", "donationid", "giftid"));
        if ("expense".equals(table)) out.addAll(List.of("expenseid", "billid", "checkid"));
        return out;
    }
}
