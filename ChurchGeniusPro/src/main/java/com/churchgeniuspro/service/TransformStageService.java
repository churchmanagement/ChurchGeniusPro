package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.util.EtlTargetSchema;
import com.churchgeniuspro.util.EtlTargetSchema.TargetColumn;
import com.churchgeniuspro.util.SourcePreset;
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
import java.util.Locale;

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
 *
 * <p>Migration Phase 1: transforms run with the run's date order; the virtual
 * targets {@code fullName} (split into first/last) and {@code birthDate} (split
 * into the three birthday integers) are resolved here; dictionary columns
 * (fund, method, category, gender, role, memberType) are translated through the
 * run's {@code import_value_map} (preset dictionary as a fallback) so "Tithes" and
 * "Tithe" become one fund before anything is loaded; and the household key
 * prefers the preset's family/person id columns.
 */
@Service
public class TransformStageService {

    private static final Logger log = LoggerFactory.getLogger(TransformStageService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Source columns we treat as a family grouping key when staging family rows. */
    private static final List<String> FAMILY_KEY_CANDIDATES =
        List.of("family_key", "family_id", "familyid", "household_id", "householdid",
                "member_id", "memberid", "breeze_id", "person_id", "individual_id", "id");

    private final MappingRuleRepository    ruleRepo;
    private final StagingRawRepository     rawRepo;
    private final StagingFamilyRepository  familyRepo;
    private final StagingIncomeRepository  incomeRepo;
    private final StagingExpenseRepository expenseRepo;
    private final ImportRunService         runService;
    private final ImportValueMapRepository valueMapRepo;
    private final ImportFileRepository     fileRepo;
    /** Phase 3: lets staging refuse to wipe the provenance of a table that is already approved or loaded (optional). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ImportBatchRepository batchRepo;
    public void setBatchRepo(ImportBatchRepository r) { this.batchRepo = r; }
    /** Phase 4: staging for the record data types (optional so older hand-built tests are unchanged). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private StagingRecordRepository recordRepo;
    public void setRecordRepo(StagingRecordRepository r) { this.recordRepo = r; }

    public TransformStageService(MappingRuleRepository ruleRepo,
                                 StagingRawRepository rawRepo,
                                 StagingFamilyRepository familyRepo,
                                 StagingIncomeRepository incomeRepo,
                                 StagingExpenseRepository expenseRepo,
                                 ImportRunService runService,
                                 ImportValueMapRepository valueMapRepo,
                                 ImportFileRepository fileRepo) {
        this.ruleRepo    = ruleRepo;
        this.rawRepo     = rawRepo;
        this.familyRepo  = familyRepo;
        this.incomeRepo  = incomeRepo;
        this.expenseRepo = expenseRepo;
        this.runService  = runService;
        this.valueMapRepo = valueMapRepo;
        this.fileRepo    = fileRepo;
    }

    /** Phase 2: did the uploaded file for this source table carry its own row ids (preset / literal id column)? */
    boolean hasSourceIds(Long runId, String sourceTable) {
        if (fileRepo == null || sourceTable == null) return false;
        for (ImportFile f : fileRepo.findByRunIdAndSourceTable(runId, sourceTable))
            if (f.getIdColumn() != null && !f.getIdColumn().isBlank()) return true;
        return false;
    }

    /** Phase 2: same question for the file behind a TARGET table (via its active mapping rules). */
    public boolean hasSourceIdsForTarget(Long runId, String targetTable) {
        List<MappingRule> rules = ruleRepo.findByRunIdAndTargetTable(runId, targetTable).stream()
            .filter(r -> r.getSourceColumn() != null && !r.getSourceColumn().isBlank())
            .filter(r -> "CONFIRMED".equals(r.getStatus()) || "AUTO".equals(r.getStatus()))
            .toList();
        if (rules.isEmpty()) return false;
        try { return hasSourceIds(runId, dominantSourceTable(rules)); } catch (StageException e) { return false; }
    }

    /**
     * Phase 2: the import reference written to {@code income/expense.import_ref} —
     * {@code ETL:<preset>:<source id>} when the file has real row ids, otherwise a
     * fingerprint of the row's financial identity, so a second import of the same
     * gift is recognised (Exact tier) and the H8 partial unique index can refuse it.
     */
    public static String importRef(SourcePreset preset, boolean hasIds, String sourceRowId, String... parts) {
        if (hasIds && sourceRowId != null && !sourceRowId.isBlank())
            return trim160("ETL:" + preset.name() + ":" + sourceRowId.trim());
        StringBuilder sb = new StringBuilder();
        for (String p : parts) sb.append(p == null ? "" : p.trim().toLowerCase(Locale.ROOT)).append('|');
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))) hex.append(String.format("%02x", b));
            return "ETL:" + preset.name() + ":fp:" + hex;
        } catch (Exception e) {
            return null;
        }
    }

    private static String trim160(String s) { return s.length() > 160 ? s.substring(0, 160) : s; }

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

        // Phase 3: a run may hold several tables at different stages. Re-staging replaces a
        // table's staging rows — which are the ONLY record of what a batch loaded — so a
        // table whose batch is approved, loaded or part-loaded is frozen until that batch
        // is rolled back.
        if (batchRepo != null)
            for (ImportBatch b : batchRepo.findByRunIdAndTargetTable(runId, targetTable))
                if ("APPROVED".equals(b.getStatus()) || "LOADED".equals(b.getStatus()) || "FAILED".equals(b.getStatus()))
                    throw new StageException("'" + targetTable + "' already has batch #" + b.getId() + " (" + b.getStatus()
                        + ") — roll it back before staging this table again");

        if (EtlTargetSchema.isRecordTable(targetTable))
            return stageRecords(runId, clientId, actor, targetTable, rules, sourceTable, raws);

        clearStaging(runId, targetTable);

        ImportRun run = runService.getRun(runId, clientId);
        Transforms.Context ctx = Transforms.Context.of(run.getDateOrder());
        SourcePreset preset = SourcePreset.of(run.getSourcePreset());
        Dictionaries dict = loadDictionaries(runId, preset);
        boolean directFirst = rules.stream().anyMatch(r -> "firstName".equals(r.getTargetColumn()));
        boolean directLast  = rules.stream().anyMatch(r -> "lastName".equals(r.getTargetColumn()));
        boolean hasIds = hasSourceIds(runId, sourceTable);

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
                TargetColumn col = EtlTargetSchema.column(targetTable, rule.getTargetColumn());
                try {
                    Object typed = Transforms.apply(rule.getTransform(), rawVal, ctx);
                    if (typed == null) continue;
                    if (col != null && col.virtual()) {
                        applyVirtual(bw, col.name(), typed, directFirst, directLast, issues);
                        continue;
                    }
                    if (col != null && col.dictionary() != null && typed instanceof String sv) {
                        typed = dict.translate(col.dictionary(), sv);
                    }
                    bw.setPropertyValue(rule.getTargetColumn(), typed);
                } catch (TransformException e) {
                    issues.add(issue(rule.getTargetColumn(), "ERROR", e.getMessage()));
                } catch (Exception e) {
                    issues.add(issue(rule.getTargetColumn(), "ERROR",
                        "Could not set " + rule.getTargetColumn() + ": " + e.getMessage()));
                }
            }

            if ("family".equals(targetTable)) {
                bw.setPropertyValue("familyKey", familyKey(src, raw.getSourceRowId()));
            } else if (entity instanceof StagingIncome si) {
                si.setImportRef(importRef(preset, hasIds, raw.getSourceRowId(),
                    String.valueOf(si.getIncomeDate()), si.getAmount() == null ? null : si.getAmount().toPlainString(),
                    si.getSourceName(), si.getSubSourceName(), si.getFamilyLink(), si.getGuestName(), si.getReferenceNo()));
            } else if (entity instanceof StagingExpense se) {
                se.setImportRef(importRef(preset, hasIds, raw.getSourceRowId(),
                    String.valueOf(se.getExpenseDate()), se.getAmount() == null ? null : se.getAmount().toPlainString(),
                    se.getCategory(), se.getPurposeName(), se.getPayee(), se.getReferenceNo()));
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

    // ───────────────────────── Phase 4: record data types ─────────────────────────

    /**
     * Stage a record data type (pledges, events, groups, kids …) into {@code staging_record}:
     * the same transforms, run date order and value dictionaries as the typed tables; the
     * typed values are stored as JSON (dates ISO, money as plain decimal strings). fullName
     * fills first/last name where no direct mapping does.
     */
    private StageResult stageRecords(Long runId, String clientId, String actor, String targetTable,
                                     List<MappingRule> rules, String sourceTable, List<StagingRaw> raws) {
        if (recordRepo == null) throw new StageException("Record staging is not available");
        recordRepo.deleteByRunIdAndTargetTable(runId, targetTable);
        ImportRun run = runService.getRun(runId, clientId);
        Transforms.Context ctx = Transforms.Context.of(run.getDateOrder());
        SourcePreset preset = SourcePreset.of(run.getSourcePreset());
        Dictionaries dict = loadDictionaries(runId, preset);
        boolean directFirst = rules.stream().anyMatch(r -> "firstName".equals(r.getTargetColumn()));
        boolean directLast  = rules.stream().anyMatch(r -> "lastName".equals(r.getTargetColumn()));

        int total = 0, withErrors = 0;
        for (StagingRaw raw : raws) {
            Map<String, Object> src = parse(raw.getPayload());
            Map<String, Object> values = new LinkedHashMap<>();
            List<Map<String, String>> issues = new ArrayList<>();
            for (MappingRule rule : rules) {
                Object cell = src.get(rule.getSourceColumn());
                String rawVal = cell == null ? null : String.valueOf(cell);
                TargetColumn col = EtlTargetSchema.column(targetTable, rule.getTargetColumn());
                try {
                    Object typed = Transforms.apply(rule.getTransform(), rawVal, ctx);
                    if (typed == null) continue;
                    if (col != null && col.virtual() && "fullName".equals(col.name())) {
                        String[] parts = Transforms.splitName(String.valueOf(typed));
                        if (!directFirst && parts[0] != null) values.putIfAbsent("firstName", parts[0]);
                        if (!directLast  && parts[1] != null) values.putIfAbsent("lastName", parts[1]);
                        values.put("fullName", String.valueOf(typed));
                        continue;
                    }
                    if (col != null && col.dictionary() != null && typed instanceof String sv) typed = dict.translate(col.dictionary(), sv);
                    values.put(rule.getTargetColumn(), jsonValue(typed));
                } catch (TransformException e) {
                    issues.add(issue(rule.getTargetColumn(), "ERROR", e.getMessage()));
                } catch (Exception e) {
                    issues.add(issue(rule.getTargetColumn(), "ERROR", "Could not set " + rule.getTargetColumn() + ": " + e.getMessage()));
                }
            }
            StagingRecord r = new StagingRecord();
            r.setRunId(runId); r.setClientId(clientId); r.setTargetTable(targetTable);
            r.setSourceRowId(raw.getSourceRowId()); r.setSourcePayload(raw.getPayload());
            r.setValuesJson(toJson(values));
            boolean hasError = issues.stream().anyMatch(i -> "ERROR".equals(i.get("level")));
            r.setRowStatus(hasError ? "ERROR" : "PENDING");
            r.setValidationMsgs(issues.isEmpty() ? null : toJson(issues));
            recordRepo.save(r);
            total++;
            if (hasError) withErrors++;
        }
        runService.recordAudit(runId, clientId, actor, "STAGE", Map.of(
            "targetTable", targetTable, "sourceTable", sourceTable, "total", total,
            "withTransformErrors", withErrors, "mappings", rules.size()));
        log.info("ETL run {} staged {} {} records ({} with transform errors) from '{}'", runId, total, targetTable, withErrors, sourceTable);
        return new StageResult(targetTable, sourceTable, total, withErrors);
    }

    /** JSON-safe form of a typed value: dates/times ISO, money as a plain decimal string (never a float). */
    static Object jsonValue(Object typed) {
        if (typed instanceof java.math.BigDecimal b) return b.toPlainString();
        if (typed instanceof java.time.temporal.Temporal t) return t.toString();
        return typed;
    }

    // ───────────────────────── Phase 1 helpers ─────────────────────────

    /**
     * {@code fullName} → first/last (only the parts no direct mapping already fills);
     * {@code birthDate} → birthdayMonth/Day/Year.
     */
    private void applyVirtual(BeanWrapper bw, String name, Object typed,
                              boolean directFirst, boolean directLast, List<Map<String, String>> issues) {
        switch (name) {
            case "fullName" -> {
                String[] parts = Transforms.splitName(String.valueOf(typed));
                if (!directFirst && parts[0] != null) bw.setPropertyValue("firstName", parts[0]);
                if (!directLast  && parts[1] != null) bw.setPropertyValue("lastName",  parts[1]);
            }
            case "birthDate" -> {
                if (typed instanceof java.time.LocalDate d) {
                    bw.setPropertyValue("birthdayMonth", d.getMonthValue());
                    bw.setPropertyValue("birthdayDay",   d.getDayOfMonth());
                    bw.setPropertyValue("birthdayYear",  d.getYear());
                } else {
                    issues.add(issue("birthDate", "ERROR", "Birth date did not parse as a date (use the parse_date transform)"));
                }
            }
            default -> issues.add(issue(name, "ERROR", "Unknown virtual column " + name));
        }
    }

    /** Run dictionaries (admin entries win; preset entries fill the gaps). */
    static final class Dictionaries {
        private final Map<String, Map<String, String>> byName = new HashMap<>();   // dict → lower(source) → target
        void put(String dict, String source, String target) {
            if (source == null || source.isBlank() || target == null || target.isBlank()) return;
            byName.computeIfAbsent(dict, k -> new HashMap<>()).putIfAbsent(source.trim().toLowerCase(Locale.ROOT), target.trim());
        }
        String translate(String dict, String value) {
            if (value == null) return null;
            Map<String, String> m = byName.get(dict);
            if (m == null) return value;
            String hit = m.get(value.trim().toLowerCase(Locale.ROOT));
            return hit == null ? value : hit;
        }
    }

    private Dictionaries loadDictionaries(Long runId, SourcePreset preset) {
        Dictionaries d = new Dictionaries();
        for (ImportValueMap v : valueMapRepo.findByRunIdOrderByDictionaryAscSourceValueAsc(runId))
            d.put(v.getDictionary(), v.getSourceValue(), v.getTargetValue());
        for (String name : EtlTargetSchema.DICTIONARIES)
            for (Map.Entry<String, String> e : preset.dictionary(name).entrySet()) d.put(name, e.getKey(), e.getValue());
        return d;
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
