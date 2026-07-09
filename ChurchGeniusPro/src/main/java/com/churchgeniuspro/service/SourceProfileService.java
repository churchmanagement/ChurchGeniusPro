package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.SourceColumnProfile;
import com.churchgeniuspro.hibernate.StagingRaw;
import com.churchgeniuspro.repository.SourceColumnProfileRepository;
import com.churchgeniuspro.repository.StagingRawRepository;
import com.churchgeniuspro.util.TypeInference;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * ETL Phase 2 — Profile. Reads the verbatim {@code staging_raw} rows for one
 * source table and computes a per-column profile: inferred type (dominant cell
 * type), null rate, distinct/total counts, value-length range, and a small JSON
 * sample of values (see the ETL design doc, §3 stage 2). Results feed the mapping
 * phase. Read-only against staging; writes only profile rows.
 */
@Service
public class SourceProfileService {

    private static final Logger log = LoggerFactory.getLogger(SourceProfileService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int SAMPLE_LIMIT = 5;
    private static final int DISTINCT_CAP = 10_000;   // bound memory on huge columns

    private final StagingRawRepository          rawRepo;
    private final SourceColumnProfileRepository profileRepo;

    public SourceProfileService(StagingRawRepository rawRepo,
                                SourceColumnProfileRepository profileRepo) {
        this.rawRepo     = rawRepo;
        this.profileRepo = profileRepo;
    }

    /** Mutable per-column accumulator. */
    private static final class ColStat {
        int ordinal;
        int total, nonNull;
        String type;                  // reconciled type so far
        Integer minLen, maxLen;
        final LinkedHashSet<String> distinct = new LinkedHashSet<>();
        boolean distinctCapped;
        final List<String> samples = new ArrayList<>();
    }

    /**
     * Profile one source table for a run. Replaces any prior profile for that
     * table. Returns the persisted profile rows in column order.
     */
    @Transactional
    public List<SourceColumnProfile> profile(Long runId, String clientId, String sourceTable) {
        List<StagingRaw> rows = rawRepo.findByRunIdAndSourceTable(runId, sourceTable);
        if (rows.isEmpty())
            throw new IllegalStateException("No extracted rows for source table '" + sourceTable + "'");

        // Preserve first-seen column order across all rows.
        Map<String, ColStat> cols = new LinkedHashMap<>();

        for (StagingRaw raw : rows) {
            Map<String, Object> obj = parse(raw.getPayload());
            for (Map.Entry<String, Object> e : obj.entrySet()) {
                ColStat cs = cols.computeIfAbsent(e.getKey(), k -> {
                    ColStat c = new ColStat();
                    c.ordinal = cols.size();
                    return c;
                });
                accumulate(cs, e.getValue());
            }
        }

        profileRepo.deleteByRunIdAndSourceTable(runId, sourceTable);
        List<SourceColumnProfile> out = new ArrayList<>();
        for (Map.Entry<String, ColStat> e : cols.entrySet()) {
            out.add(profileRepo.save(toEntity(runId, clientId, sourceTable, e.getKey(), e.getValue())));
        }
        log.info("ETL run {} profiled {} columns of source table '{}' ({} rows)",
            runId, out.size(), sourceTable, rows.size());
        return out;
    }

    /** Profile every extracted source table under a run. */
    @Transactional
    public Map<String, List<SourceColumnProfile>> profileAll(Long runId, String clientId) {
        Map<String, List<SourceColumnProfile>> result = new LinkedHashMap<>();
        for (String table : rawRepo.findDistinctSourceTables(runId)) {
            result.put(table, profile(runId, clientId, table));
        }
        return result;
    }

    // ───────────────────────── internals ─────────────────────────

    private void accumulate(ColStat cs, Object value) {
        cs.total++;
        String s = value == null ? null : String.valueOf(value);
        String type = TypeInference.classify(s);
        if (TypeInference.EMPTY.equals(type)) {
            return;  // null/blank contributes only to total + null rate
        }
        cs.nonNull++;
        cs.type = TypeInference.reconcile(cs.type, type);

        int len = s.length();
        cs.minLen = cs.minLen == null ? len : Math.min(cs.minLen, len);
        cs.maxLen = cs.maxLen == null ? len : Math.max(cs.maxLen, len);

        if (!cs.distinctCapped) {
            if (cs.distinct.size() < DISTINCT_CAP) cs.distinct.add(s);
            else cs.distinctCapped = true;
        }
        if (cs.samples.size() < SAMPLE_LIMIT && !cs.samples.contains(s)) cs.samples.add(s);
    }

    private SourceColumnProfile toEntity(Long runId, String clientId, String table,
                                         String column, ColStat cs) {
        SourceColumnProfile p = new SourceColumnProfile();
        p.setRunId(runId);
        p.setClientId(clientId);
        p.setSourceTable(table);
        p.setColumnName(column);
        p.setOrdinal(cs.ordinal);
        p.setInferredType(cs.type == null ? TypeInference.EMPTY : cs.type);
        p.setTotalCount(cs.total);
        p.setNonNullCount(cs.nonNull);
        p.setNullCount(cs.total - cs.nonNull);
        p.setDistinctCount(cs.distinctCapped ? DISTINCT_CAP : cs.distinct.size());
        p.setNullRate(cs.total == 0 ? BigDecimal.ZERO
            : BigDecimal.valueOf(cs.total - cs.nonNull)
                .divide(BigDecimal.valueOf(cs.total), 4, RoundingMode.HALF_UP));
        p.setMinLength(cs.minLen);
        p.setMaxLength(cs.maxLen);
        p.setSampleValues(toJson(cs.samples));
        return p;
    }

    private Map<String, Object> parse(String payload) {
        try {
            return MAPPER.readValue(payload, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private String toJson(List<String> samples) {
        try {
            return MAPPER.writeValueAsString(samples);
        } catch (Exception e) {
            return "[]";
        }
    }
}
