package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.ImportRun;
import com.churchgeniuspro.hibernate.ImportValueMap;
import com.churchgeniuspro.hibernate.MappingRule;
import com.churchgeniuspro.hibernate.StagingRaw;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.util.EtlTargetSchema;
import com.churchgeniuspro.util.EtlTargetSchema.TargetColumn;
import com.churchgeniuspro.util.SourcePreset;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Migration Phase 1 — value dictionaries. For every dictionary-backed target
 * column with an active mapping (fund, method, category, gender, role, memberType)
 * this lists the distinct raw values in the uploaded file with their counts, the
 * current translation (admin entry, else the source preset's), and the church's
 * existing values to pick from (its funds, payment types, purposes; the app's own
 * gender / role / member-type vocabulary). The admin's choices are saved per run
 * in {@code import_value_map} and applied by {@link TransformStageService} at
 * stage time — so spellings are unified before load rather than new reference
 * rows appearing for each variant. Read-only against live data.
 */
@Service
public class EtlDictionaryService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_DISTINCT = 500;

    static final List<String> GENDERS      = List.of("Male", "Female");
    static final List<String> ROLES        = List.of("Head of Household", "Spouse", "Child", "Son", "Daughter",
                                                     "Father", "Mother", "Other");
    static final List<String> MEMBER_TYPES = List.of("Member", "Visitor", "Guest");

    private final MappingRuleRepository      ruleRepo;
    private final StagingRawRepository       rawRepo;
    private final ImportValueMapRepository   valueMapRepo;
    private final MainSourceRepository       mainSourceRepo;
    private final SubSourceRepository        subSourceRepo;
    private final PurposeRepository          purposeRepo;
    private final TransactionTypeRepository  txTypeRepo;
    private final ImportRunService           runService;

    public EtlDictionaryService(MappingRuleRepository ruleRepo, StagingRawRepository rawRepo,
                                ImportValueMapRepository valueMapRepo, MainSourceRepository mainSourceRepo,
                                SubSourceRepository subSourceRepo, PurposeRepository purposeRepo,
                                TransactionTypeRepository txTypeRepo, ImportRunService runService) {
        this.ruleRepo = ruleRepo; this.rawRepo = rawRepo; this.valueMapRepo = valueMapRepo;
        this.mainSourceRepo = mainSourceRepo; this.subSourceRepo = subSourceRepo; this.purposeRepo = purposeRepo;
        this.txTypeRepo = txTypeRepo; this.runService = runService;
    }

    public static class DictionaryException extends RuntimeException {
        public DictionaryException(String m) { super(m); }
    }

    /** One dictionary view: {dictionary, columns, options, values:[{source,count,target,fromPreset}]}. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Long runId, String clientId, String targetTable) {
        if (!EtlTargetSchema.isTable(targetTable))
            throw new DictionaryException("Unknown target table: " + targetTable);
        ImportRun run = runService.getRun(runId, clientId);
        SourcePreset preset = SourcePreset.of(run.getSourcePreset());

        // dictionary → active mapped source columns feeding it
        Map<String, List<MappingRule>> byDict = new LinkedHashMap<>();
        for (MappingRule r : ruleRepo.findByRunIdAndTargetTable(runId, targetTable)) {
            boolean active = ("CONFIRMED".equals(r.getStatus()) || "AUTO".equals(r.getStatus()))
                && r.getSourceColumn() != null && !r.getSourceColumn().isBlank();
            if (!active) continue;
            TargetColumn col = EtlTargetSchema.column(targetTable, r.getTargetColumn());
            if (col == null || col.dictionary() == null) continue;
            byDict.computeIfAbsent(col.dictionary(), k -> new ArrayList<>()).add(r);
        }
        if (byDict.isEmpty()) return List.of();

        // distinct raw values per dictionary (one pass over the source rows)
        Map<String, Map<String, Integer>> counts = new HashMap<>();   // dict → value → count
        Set<String> sourceTables = new LinkedHashSet<>();
        for (List<MappingRule> rs : byDict.values()) for (MappingRule r : rs) if (r.getSourceTable() != null) sourceTables.add(r.getSourceTable());
        for (String st : sourceTables) {
            for (StagingRaw raw : rawRepo.findByRunIdAndSourceTable(runId, st)) {
                Map<String, Object> src = parse(raw.getPayload());
                for (Map.Entry<String, List<MappingRule>> e : byDict.entrySet()) {
                    Map<String, Integer> c = counts.computeIfAbsent(e.getKey(), k -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
                    for (MappingRule r : e.getValue()) {
                        if (!st.equals(r.getSourceTable())) continue;
                        Object v = src.get(r.getSourceColumn());
                        if (v == null) continue;
                        String sv = String.valueOf(v).trim();
                        if (sv.isEmpty()) continue;
                        if (c.size() >= MAX_DISTINCT && !c.containsKey(sv)) continue;
                        c.merge(sv, 1, Integer::sum);
                    }
                }
            }
        }

        Map<String, Map<String, String>> saved = new HashMap<>();     // dict → lower(source) → target
        for (ImportValueMap v : valueMapRepo.findByRunIdOrderByDictionaryAscSourceValueAsc(runId))
            saved.computeIfAbsent(v.getDictionary(), k -> new HashMap<>())
                 .put(v.getSourceValue().trim().toLowerCase(Locale.ROOT), v.getTargetValue() == null ? "" : v.getTargetValue());

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<MappingRule>> e : byDict.entrySet()) {
            String dict = e.getKey();
            Map<String, String> presetDict = preset.dictionary(dict);
            Map<String, String> savedDict = saved.getOrDefault(dict, Map.of());
            List<Map<String, Object>> values = new ArrayList<>();
            for (Map.Entry<String, Integer> c : counts.getOrDefault(dict, Map.of()).entrySet()) {
                String lower = c.getKey().toLowerCase(Locale.ROOT);
                String target = savedDict.get(lower);
                boolean fromPreset = false;
                if (target == null && presetDict.containsKey(lower)) { target = presetDict.get(lower); fromPreset = true; }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("source", c.getKey());
                row.put("count", c.getValue());
                row.put("target", target == null ? "" : target);
                row.put("fromPreset", fromPreset);
                values.add(row);
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("dictionary", dict);
            d.put("columns", e.getValue().stream().map(MappingRule::getTargetColumn).toList());
            d.put("options", options(dict, clientId));
            d.put("values", values);
            d.put("truncated", counts.getOrDefault(dict, Map.of()).size() >= MAX_DISTINCT);
            out.add(d);
        }
        return out;
    }

    /** The church's existing values for a dictionary (what the admin picks from). */
    List<String> options(String dict, String tenant) {
        Set<String> opts = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        switch (dict) {
            case "fund" -> {
                for (var m : safe(mainSourceRepo.findActiveByAppUser(tenant))) if (m.getSourceName() != null) opts.add(m.getSourceName());
                for (var s : safe(subSourceRepo.findAllActiveByAppUser(tenant))) if (s.getSourceName() != null) opts.add(s.getSourceName());
            }
            case "method"     -> { for (var t : safe(txTypeRepo.findActiveByAppUser(tenant))) if (t.getTypeName() != null) opts.add(t.getTypeName()); }
            case "category"   -> { for (var p : safe(purposeRepo.findActiveByAppUser(tenant))) if (p.getPurposeName() != null) opts.add(p.getPurposeName()); }
            case "gender"     -> opts.addAll(GENDERS);
            case "role"       -> opts.addAll(ROLES);
            case "memberType" -> opts.addAll(MEMBER_TYPES);
            case "attendanceStatus" -> opts.addAll(AttendanceService.STATUSES);   // Phase 4: PRESENT / LATE / ABSENT / EXCUSED
            default -> { }
        }
        return new ArrayList<>(opts);
    }

    /**
     * Save the admin's entries for one dictionary: {source → target}; a blank target
     * removes the entry (the value then passes through unchanged). Replaces only the
     * entries named in {@code entries}.
     */
    @Transactional
    public int save(Long runId, String clientId, String actor, String dictionary, List<Map<String, String>> entries) {
        if (dictionary == null || !EtlTargetSchema.DICTIONARIES.contains(dictionary))
            throw new DictionaryException("Unknown dictionary: " + dictionary);
        runService.getRun(runId, clientId);   // tenant check
        int n = 0;
        for (Map<String, String> e : entries == null ? List.<Map<String, String>>of() : entries) {
            String source = e.get("source");
            if (source == null || source.isBlank() || source.length() > 255) continue;
            String target = e.get("target");
            Optional<ImportValueMap> existing = valueMapRepo.findByRunIdAndDictionaryAndSourceValueIgnoreCase(runId, dictionary, source.trim());
            if (target == null || target.isBlank()) {
                existing.ifPresent(valueMapRepo::delete);
                n++;
                continue;
            }
            if (target.length() > 255) throw new DictionaryException("Value too long: " + target);
            ImportValueMap v = existing.orElseGet(ImportValueMap::new);
            v.setRunId(runId);
            v.setClientId(clientId);
            v.setDictionary(dictionary);
            v.setSourceValue(source.trim());
            v.setTargetValue(target.trim());
            v.setDecidedBy(actor);
            valueMapRepo.save(v);
            n++;
        }
        runService.recordAudit(runId, clientId, actor, "DICTIONARY", Map.of("dictionary", dictionary, "entries", n));
        return n;
    }

    private static <T> List<T> safe(List<T> l) { return l == null ? List.of() : l; }

    private Map<String, Object> parse(String payload) {
        try {
            return MAPPER.readValue(payload, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
