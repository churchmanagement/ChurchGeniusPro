package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.MappingRule;
import com.churchgeniuspro.hibernate.SourceColumnProfile;
import com.churchgeniuspro.repository.MappingRuleRepository;
import com.churchgeniuspro.repository.SourceColumnProfileRepository;
import com.churchgeniuspro.util.EtlTargetSchema;
import com.churchgeniuspro.util.EtlTargetSchema.TargetColumn;
import com.churchgeniuspro.util.MappingMatcher;
import com.churchgeniuspro.util.MappingMatcher.SourceCol;
import com.churchgeniuspro.util.MappingMatcher.Suggestion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * ETL Phase 3 — Mapping. Combines a deterministic matcher (name + synonym + type)
 * with an OpenAI fallback that fills only the columns the matcher could not place.
 * The AI fallback can <b>suggest but never auto-approve</b>: every AI suggestion is
 * stored as {@code NEEDS_REVIEW} for an operator to confirm. Human decisions
 * ({@code CONFIRMED}/{@code REJECTED}) are preserved across re-suggests.
 *
 * <p>All suggestions are persisted as {@code mapping_rule} rows (one per target
 * column) so the review UI can show the full picture. No live data is touched.
 */
@Service
public class MappingService {

    private static final Logger log = LoggerFactory.getLogger(MappingService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** AI suggestions can never be marked AUTO; cap their stored confidence too. */
    private static final double AI_CONFIDENCE_CAP = 0.84;

    private final MappingRuleRepository         ruleRepo;
    private final SourceColumnProfileRepository profileRepo;
    private final OpenAiVoiceService            openAi;
    private final ImportRunService              runService;

    public MappingService(MappingRuleRepository ruleRepo,
                          SourceColumnProfileRepository profileRepo,
                          OpenAiVoiceService openAi,
                          ImportRunService runService) {
        this.ruleRepo    = ruleRepo;
        this.profileRepo = profileRepo;
        this.openAi      = openAi;
        this.runService  = runService;
    }

    public static class MappingException extends RuntimeException {
        public MappingException(String m) { super(m); }
    }

    // ───────────────────────── suggest ─────────────────────────

    /**
     * Build (or refresh) mapping suggestions for one target table from one
     * profiled source table. Preserves operator-confirmed/rejected rules.
     */
    @Transactional
    public List<MappingRule> suggest(Long runId, String clientId, String actor,
                                     String targetTable, String sourceTable) {
        if (!EtlTargetSchema.isTable(targetTable))
            throw new MappingException("Unknown target table: " + targetTable);

        List<SourceColumnProfile> profile =
            profileRepo.findByRunIdAndSourceTableOrderByOrdinalAsc(runId, sourceTable);
        if (profile.isEmpty())
            throw new MappingException("No profile for source table '" + sourceTable
                + "'. Run extract/profile first.");

        List<SourceCol> sources = profile.stream()
            .map(p -> new SourceCol(p.getColumnName(), p.getInferredType()))
            .toList();

        // 1) Deterministic pass.
        List<Suggestion> suggestions = MappingMatcher.match(targetTable, sources);

        // 2) AI fallback for whatever the matcher left unplaced.
        List<Suggestion> unmatched = suggestions.stream()
            .filter(s -> s.sourceColumn == null).toList();
        if (!unmatched.isEmpty() && openAi.isEnabled()) {
            try {
                applyAiFallback(targetTable, unmatched, profile, sources);
            } catch (Exception e) {
                log.warn("ETL run {} AI mapping fallback failed: {}", runId, e.getMessage());
            }
        }

        // 3) Persist, preserving human decisions.
        List<MappingRule> saved = new ArrayList<>();
        int auto = 0, review = 0, ai = 0;
        for (Suggestion s : suggestions) {
            MappingRule rule = ruleRepo
                .findByRunIdAndTargetTableAndTargetColumn(runId, targetTable, s.targetColumn)
                .orElseGet(MappingRule::new);

            if ("CONFIRMED".equals(rule.getStatus()) || "REJECTED".equals(rule.getStatus())) {
                saved.add(rule);   // operator already decided — leave untouched
                continue;
            }

            rule.setRunId(runId);
            rule.setClientId(clientId);
            rule.setTargetTable(targetTable);
            rule.setTargetColumn(s.targetColumn);
            rule.setSourceTable(sourceTable);
            rule.setSourceColumn(s.sourceColumn);
            rule.setTransform(s.transform);
            rule.setConfidence(BigDecimal.valueOf(s.confidence).setScale(3, RoundingMode.HALF_UP));
            rule.setStatus(s.status);
            rule.setRationale(s.rationale);
            saved.add(ruleRepo.save(rule));

            if ("AUTO".equals(s.status)) auto++; else review++;
            if (s.rationale != null && s.rationale.startsWith("AI:")) ai++;
        }

        runService.recordAudit(runId, clientId, actor, "MAP_SUGGEST", Map.of(
            "targetTable", targetTable,
            "sourceTable", sourceTable,
            "auto", auto,
            "needsReview", review,
            "aiAssisted", ai));
        log.info("ETL run {} mapped {} ({} auto, {} review, {} AI) {}→{}",
            runId, suggestions.size(), auto, review, ai, sourceTable, targetTable);
        return saved;
    }

    /** Ask the model to place still-unmapped target columns; suggestion-only. */
    private void applyAiFallback(String targetTable, List<Suggestion> unmatched,
                                 List<SourceColumnProfile> profile, List<SourceCol> sources) {
        Set<String> validSources = new HashSet<>();
        for (SourceCol s : sources) validSources.add(s.name());

        JsonNode out = openAi.chatJson(aiSystemPrompt(), aiUserPrompt(targetTable, unmatched, profile));
        if (out == null) return;
        JsonNode arr = out.path("mappings");
        if (!arr.isArray()) return;

        Map<String, Suggestion> byTarget = new HashMap<>();
        for (Suggestion s : unmatched) byTarget.put(s.targetColumn, s);
        Set<String> usedSources = new HashSet<>();   // don't let AI double-assign a source

        for (JsonNode m : arr) {
            String target = m.path("target").asText("");
            String source = m.path("source").asText("");
            Suggestion sug = byTarget.get(target);
            if (sug == null || sug.sourceColumn != null) continue;
            if (source.isBlank() || !validSources.contains(source) || usedSources.contains(source)) continue;

            double conf = Math.min(m.path("confidence").asDouble(0.6), AI_CONFIDENCE_CAP);
            if (conf < MappingMatcher.REVIEW_THRESHOLD) conf = MappingMatcher.REVIEW_THRESHOLD;
            String why = m.path("rationale").asText("semantic match");

            sug.sourceColumn = source;
            sug.confidence   = MappingMatcher.round3(conf);
            sug.status       = "NEEDS_REVIEW";   // AI never auto-approves
            sug.rationale    = "AI: '" + source + "' → " + target + " — " + why;
            usedSources.add(source);
        }
    }

    // ───────────────────────── review actions ─────────────────────────

    @Transactional(readOnly = true)
    public List<MappingRule> list(Long runId, String targetTable) {
        return (targetTable == null || targetTable.isBlank())
            ? ruleRepo.findByRunIdOrderByTargetTableAscTargetColumnAsc(runId)
            : ruleRepo.findByRunIdAndTargetTable(runId, targetTable);
    }

    /**
     * Edit and/or set the decision on one mapping rule. {@code decision} is one of
     * CONFIRMED | REJECTED | NEEDS_REVIEW. Editing the source/transform is allowed
     * together with the decision.
     */
    @Transactional
    public MappingRule updateRule(Long runId, String clientId, String actor,
                                  String targetTable, String targetColumn,
                                  String sourceTable, String sourceColumn,
                                  String transform, String decision) {
        if (!EtlTargetSchema.isTable(targetTable))
            throw new MappingException("Unknown target table: " + targetTable);
        boolean validTarget = EtlTargetSchema.columns(targetTable).stream()
            .map(TargetColumn::name).anyMatch(n -> n.equals(targetColumn));
        if (!validTarget)
            throw new MappingException("Unknown target column: " + targetTable + "." + targetColumn);

        String status = decision == null ? "NEEDS_REVIEW" : decision.trim().toUpperCase();
        if (!Set.of("CONFIRMED", "REJECTED", "NEEDS_REVIEW").contains(status))
            throw new MappingException("Invalid decision: " + decision);
        if ("CONFIRMED".equals(status) && (sourceColumn == null || sourceColumn.isBlank()))
            throw new MappingException("Cannot confirm a mapping with no source column");

        MappingRule rule = ruleRepo
            .findByRunIdAndTargetTableAndTargetColumn(runId, targetTable, targetColumn)
            .orElseGet(MappingRule::new);
        rule.setRunId(runId);
        rule.setClientId(clientId);
        rule.setTargetTable(targetTable);
        rule.setTargetColumn(targetColumn);
        if (sourceTable  != null) rule.setSourceTable(sourceTable);
        rule.setSourceColumn(sourceColumn == null || sourceColumn.isBlank() ? null : sourceColumn.trim());
        if (transform != null && !transform.isBlank()) rule.setTransform(transform.trim());
        rule.setStatus(status);
        rule.setDecidedBy(actor);
        rule.setRationale("Operator " + status.toLowerCase()
            + (rule.getSourceColumn() == null ? " (no source)" : " '" + rule.getSourceColumn() + "'"));
        MappingRule out = ruleRepo.save(rule);

        runService.recordAudit(runId, clientId, actor, "MAP_CONFIRM", Map.of(
            "targetTable", targetTable,
            "targetColumn", targetColumn,
            "sourceColumn", String.valueOf(rule.getSourceColumn()),
            "decision", status));
        return out;
    }

    // ───────────────────────── AI prompts ─────────────────────────

    private String aiSystemPrompt() {
        return "You map SOURCE database columns onto fixed TARGET columns for importing data into a "
             + "church-management system. You are a careful assistant: only propose a mapping when the "
             + "source column's NAME and SAMPLE VALUES clearly fit the target's meaning and type. It is "
             + "better to leave a target unmapped than to guess. Never invent a source column — choose only "
             + "from the provided source list. Reply ONLY as JSON: "
             + "{\"mappings\":[{\"target\":\"<targetColumn>\",\"source\":\"<sourceColumn>\",\"confidence\":0.0,"
             + "\"rationale\":\"<short why>\"}]}. Omit targets you are unsure about.";
    }

    private String aiUserPrompt(String targetTable, List<Suggestion> unmatched,
                                List<SourceColumnProfile> profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("TARGET TABLE: ").append(targetTable).append('\n');
        sb.append("UNMAPPED TARGET COLUMNS (name : type):\n");
        for (Suggestion s : unmatched) sb.append("  - ").append(s.targetColumn)
            .append(" : ").append(s.targetType).append('\n');
        sb.append("\nAVAILABLE SOURCE COLUMNS (name : inferredType : sampleValues):\n");
        for (SourceColumnProfile p : profile) {
            sb.append("  - ").append(p.getColumnName())
              .append(" : ").append(p.getInferredType())
              .append(" : ").append(p.getSampleValues() == null ? "[]" : p.getSampleValues())
              .append('\n');
        }
        return sb.toString();
    }
}
