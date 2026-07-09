package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.ImportRun;
import com.churchgeniuspro.hibernate.StagingRaw;
import com.churchgeniuspro.repository.StagingRawRepository;
import com.churchgeniuspro.util.CsvUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ETL Phase 2 — Extract. Turns an uploaded CSV or JSON source into verbatim
 * {@code staging_raw} rows (one JSON object per source record), each stamped with
 * the run's tenant. Nothing is mapped or coerced here; the goal is a faithful,
 * traceable capture of exactly what was uploaded.
 *
 * <p>Re-extracting the same {@code sourceTable} replaces only that table's rows,
 * so an operator can re-upload a corrected file without disturbing other tables.
 */
@Service
public class SourceExtractService {

    private static final Logger log = LoggerFactory.getLogger(SourceExtractService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StagingRawRepository rawRepo;

    public SourceExtractService(StagingRawRepository rawRepo) {
        this.rawRepo = rawRepo;
    }

    public static class ExtractException extends RuntimeException {
        public ExtractException(String message) { super(message); }
    }

    /** Result of an extract: how many rows were captured and the column order seen. */
    public record ExtractResult(String sourceTable, int rowCount, List<String> columns) {}

    /** Extract CSV text (first non-blank row is the header). */
    @Transactional
    public ExtractResult extractCsv(ImportRun run, String sourceTable, String csvText) {
        String table = normalizeTable(sourceTable);
        List<List<String>> rows = CsvUtil.parse(csvText);
        if (rows.isEmpty())
            throw new ExtractException("CSV is empty");

        // First non-blank row = header.
        int h = 0;
        while (h < rows.size() && CsvUtil.isBlankRow(rows.get(h))) h++;
        if (h >= rows.size())
            throw new ExtractException("CSV has no header row");

        List<String> header = dedupeHeader(rows.get(h));
        rawRepo.deleteByRunIdAndSourceTable(run.getId(), table);

        List<StagingRaw> batch = new ArrayList<>();
        int rowNum = 0;
        for (int i = h + 1; i < rows.size(); i++) {
            List<String> cells = rows.get(i);
            if (CsvUtil.isBlankRow(cells)) continue;
            rowNum++;

            Map<String, Object> obj = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                String val = c < cells.size() ? cells.get(c) : null;
                obj.put(header.get(c), val);
            }
            batch.add(rawRow(run, table, String.valueOf(rowNum), obj));
        }
        rawRepo.saveAll(batch);
        log.info("ETL run {} extracted {} CSV rows into source table '{}'", run.getId(), rowNum, table);
        return new ExtractResult(table, rowNum, header);
    }

    /** Extract a JSON array of objects (each object becomes one staged record). */
    @Transactional
    public ExtractResult extractJson(ImportRun run, String sourceTable, String jsonText) {
        String table = normalizeTable(sourceTable);
        List<Map<String, Object>> records;
        try {
            records = MAPPER.readValue(jsonText, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new ExtractException("Expected a JSON array of objects: " + e.getMessage());
        }

        rawRepo.deleteByRunIdAndSourceTable(run.getId(), table);
        List<String> columns = new ArrayList<>();
        List<StagingRaw> batch = new ArrayList<>();
        int rowNum = 0;
        for (Map<String, Object> rec : records) {
            if (rec == null) continue;
            rowNum++;
            for (String k : rec.keySet()) if (!columns.contains(k)) columns.add(k);
            batch.add(rawRow(run, table, String.valueOf(rowNum), rec));
        }
        rawRepo.saveAll(batch);
        log.info("ETL run {} extracted {} JSON records into source table '{}'", run.getId(), rowNum, table);
        return new ExtractResult(table, rowNum, columns);
    }

    // ───────────────────────── helpers ─────────────────────────

    private StagingRaw rawRow(ImportRun run, String table, String sourceRowId, Map<String, Object> obj) {
        StagingRaw r = new StagingRaw();
        r.setRunId(run.getId());
        r.setClientId(run.getClientId());
        r.setSourceTable(table);
        r.setSourceRowId(sourceRowId);
        r.setPayload(toJson(obj));
        return r;
    }

    private String toJson(Map<String, Object> obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            throw new ExtractException("Failed to serialize source row: " + e.getMessage());
        }
    }

    private String normalizeTable(String sourceTable) {
        String t = sourceTable == null ? "" : sourceTable.trim();
        if (t.isEmpty()) t = "source";
        // Keep it tame: letters, digits, underscore.
        t = t.replaceAll("[^A-Za-z0-9_]+", "_").replaceAll("_+", "_");
        if (t.length() > 120) t = t.substring(0, 120);
        return t;
    }

    /** Ensure unique, non-blank header names ("" → col1; dup → name_2). */
    private List<String> dedupeHeader(List<String> raw) {
        List<String> out = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        int idx = 0;
        for (String h : raw) {
            idx++;
            String name = (h == null || h.trim().isEmpty()) ? "col" + idx : h.trim();
            if (seen.containsKey(name)) {
                int n = seen.get(name) + 1;
                seen.put(name, n);
                name = name + "_" + n;
            } else {
                seen.put(name, 1);
            }
            out.add(name);
        }
        return out;
    }
}
