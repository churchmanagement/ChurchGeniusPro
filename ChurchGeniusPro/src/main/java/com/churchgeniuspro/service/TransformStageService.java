package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.util.EtlTargetSchema;
import com.churchgeniuspro.util.Transforms;
import com.churchgeniuspro.util.Transforms.TransformException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * ETL Phase 4a — Transform + Stage. Applies the run's active mappings
 * ({@code AUTO} or operator-{@code CONFIRMED}, never {@code REJECTED}/
 * {@code NEEDS_REVIEW}) to the captured {@code staging_raw} rows, coercing each
 * source value through its whitelisted {@link Transforms transform} into the
 * target-shaped {@code staging_*} row. Per-field coercion failures are recorded
 * in {@code validation_msgs} (level ERROR) rather than aborting the row, so the
 * operator sees exactly what failed. Nothing live is touched.
 *
 * <p>Re-staging a target table replaces that table's prior staging rows for the
 * run, so the operator can fix a mapping and re-run.
 */
@Service
public class TransformStageService {

    private static final Logger log = LoggerFactory.getLogger(TransformStageService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Source columns we treat as a family grouping key when staging family rows. */
    private static final List<String> FAMILY_KEY_CANDIDATES =
        List.of("family_key", "family_id", "familyid", "household_id", "householdid",
                "member_id", "memberid", "id");

    private final MappingRuleRepository    ruleRepo;
    private final StagingRawRepository     rawRepo;
    private final StagingFamilyRepository  familyRepo;
    private final StagingIncomeRepository  incomeRepo;
    private final StagingExpenseRepository expenseRepo;
    private final ImportRunService         runService;

    public TransformStageService(MappingRuleRepository ruleRepo,
                                 StagingRawRepository rawRepo,
                                 StagingFamilyRepository familyRepo,
                                 StagingIncomeRepository incomeRepo,
                                 StagingExpenseRepository expenseRepo,
                                 ImportRunService runService) {
        this.ruleRepo    = ruleRepo;
        this.rawRepo     = rawRepo;
        this.familyRepo  = familyRepo;
        this.incomeRepo  = incomeRepo;
        this.expenseRepo = expenseRepo;
        this.runService  = runService;
    }

    public static class StageException extends RuntimeException {
        public StageException(String m) { super(m); }
    }

    public record StageResult(String targetTable, String sourceTable, int total, int withErrors) {}

    @Transactional
    public StageResult stage(Long runId, String clientId, String actor, String targetTable) {
        if (!EtlTargetSchema.isTable(targetTable))
            throw new StageException("Unknown target table: " + targetTable);

        // Active mappings only.
        List<MappingRule> rules = ruleRepo.findByRunIdAndTargetTable(runId, targetTable).stream()
            .filter(r -> r.getSourceColumn() != null && !r.getSourceColumn().isBlank())
            .filter(r -> "CONFIRMED".equals(r.getStatus()) || "AUTO".equals(r.getStatus()))
            .toList();
        if (rules.isEmpty())
            throw new StageException("No active mappings for '" + targetTable
                + "'. Suggest and confirm mappings first.");

        String sourceTable = dominantSourceTable(rules);
        List<StagingRaw> raws = rawRepo.findByRunIdAndSourceTable(runId, sourceTable);
        if (raws.isEmpty())
            throw new StageException("No extracted rows for source table '" + sourceTable + "'");

        clearStaging(runId, targetTable);

        int total = 0, withErrors = 0;
        for (StagingRaw raw : raws) {
            Map<String, Object> src = parse(raw.getPayload());
            Object entity = newEntity(targetTable);
            BeanWrapper bw = new BeanWrapperImpl(entity);
            bw.setPropertyValue("runId", runId);
            bw.setPropertyValue("clientId", clientId);
            bw.setPropertyValue("sourceRowId", raw.getSourceRowId());
            bw.setPropertyValue("sourcePayload", raw.getPayload());

            List<Map<String, String>> issues = new ArrayList<>();
            for (MappingRule rule : rules) {
                Object cell = src.get(rule.getSourceColumn());
                String rawVal = cell == null ? null : String.valueOf(cell);
                try {
                    Object typed = Transforms.apply(rule.getTransform(), rawVal);
                    if (typed != null) bw.setPropertyValue(rule.getTargetColumn(), typed);
                } catch (TransformException e) {
                    issues.add(issue(rule.getTargetColumn(), "ERROR", e.getMessage()));
                } catch (Exception e) {
                    issues.add(issue(rule.getTargetColumn(), "ERROR",
                        "Could not set " + rule.getTargetColumn() + ": " + e.getMessage()));
                }
            }

            if ("family".equals(targetTable)) {
                bw.setPropertyValue("familyKey", familyKey(src, raw.getSourceRowId()));
            }

            boolean hasError = issues.stream().anyMatch(i -> "ERROR".equals(i.get("level")));
            bw.setPropertyValue("rowStatus", hasError ? "ERROR" : "PENDING");
            bw.setPropertyValue("validationMsgs", issues.isEmpty() ? null : toJson(issues));

            save(targetTable, entity);
            total++;
            if (hasError) withErrors++;
        }

        runService.recordAudit(runId, clientId, actor, "STAGE", Map.of(
            "targetTable", targetTable,
            "sourceTable", sourceTable,
            "total", total,
            "withTransformErrors", withErrors,
            "mappings", rules.size()));
        log.info("ETL run {} staged {} {} rows ({} with transform errors) from '{}'",
            runId, total, targetTable, withErrors, sourceTable);
        return new StageResult(targetTable, sourceTable, total, withErrors);
    }

    // ───────────────────────── helpers ─────────────────────────

    private Object newEntity(String table) {
        return switch (table) {
            case "family"  -> new StagingFamily();
            case "income"  -> new StagingIncome();
            case "expense" -> new StagingExpense();
            default -> throw new StageException("Unknown target table: " + table);
        };
    }

    private void save(String table, Object entity) {
        switch (table) {
            case "family"  -> familyRepo.save((StagingFamily) entity);
            case "income"  -> incomeRepo.save((StagingIncome) entity);
            case "expense" -> expenseRepo.save((StagingExpense) entity);
            default -> throw new StageException("Unknown target table: " + table);
        }
    }

    private void clearStaging(Long runId, String table) {
        switch (table) {
            case "family"  -> familyRepo.deleteByRunId(runId);
            case "income"  -> incomeRepo.deleteByRunId(runId);
            case "expense" -> expenseRepo.deleteByRunId(runId);
            default -> throw new StageException("Unknown target table: " + table);
        }
    }

    /** Most common source table across the rules (mappings should share one). */
    private String dominantSourceTable(List<MappingRule> rules) {
        Map<String, Integer> counts = new HashMap<>();
        for (MappingRule r : rules) {
            if (r.getSourceTable() != null)
                counts.merge(r.getSourceTable(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElseThrow(() -> new StageException("Mappings have no source table"));
    }

    /** Stable family grouping key for child-row (income/expense) linking. */
    private String familyKey(Map<String, Object> src, String fallback) {
        Map<String, Object> lower = new HashMap<>();
        for (Map.Entry<String, Object> e : src.entrySet())
            lower.put(e.getKey().toLowerCase().replaceAll("[^a-z0-9]", ""), e.getValue());
        for (String cand : FAMILY_KEY_CANDIDATES) {
            Object v = lower.get(cand.replaceAll("[^a-z0-9]", ""));
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v).trim();
        }
        return fallback;
    }

    private Map<String, String> issue(String field, String level, String message) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("level", level);
        m.put("message", message);
        return m;
    }

    private Map<String, Object> parse(String payload) {
        try {
            return MAPPER.readValue(payload, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }
}
