package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.ImportRun;
import com.churchgeniuspro.hibernate.StagingRaw;
import com.churchgeniuspro.repository.StagingRawRepository;
import com.churchgeniuspro.util.CsvUtil;
import com.churchgeniuspro.util.SourcePreset;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * ETL — Extract. Turns an uploaded CSV, JSON, XLSX or XLS source into verbatim
 * {@code staging_raw} rows (one JSON object per source record), each stamped with
 * the run's tenant. Nothing is mapped or coerced here; the goal is a faithful,
 * traceable capture of exactly what was uploaded.
 *
 * <p>Re-extracting the same {@code sourceTable} replaces only that table's rows,
 * so an operator can re-upload a corrected file without disturbing other tables.
 *
 * <p>Migration Phase 1: Excel ({@code .xlsx} via XSSF, legacy {@code .xls} via HSSF
 * — CHMeetings exports BIFF), delimiter sniffing and charset fallback for CSV,
 * and the source's own identifier column: when the run's preset names one
 * (Breeze ID, Person Id, Individual ID…) and the header has it, its value becomes
 * {@code source_row_id} instead of the row ordinal, so related files can refer to
 * the same person and later phases can preserve the id on the live row.
 * Excel dates are captured as ISO {@code yyyy-MM-dd} (or ISO date-time), numbers
 * without a spurious {@code .0}, so the transforms see what the user saw.
 */
@Service
public class SourceExtractService {

    private static final Logger log = LoggerFactory.getLogger(SourceExtractService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter ISO_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final StagingRawRepository rawRepo;

    public SourceExtractService(StagingRawRepository rawRepo) {
        this.rawRepo = rawRepo;
    }

    public static class ExtractException extends RuntimeException {
        public ExtractException(String message) { super(message); }
    }

    /**
     * Result of an extract: how many rows were captured, the column order seen,
     * and which header (if any) supplied the source ids.
     */
    public record ExtractResult(String sourceTable, int rowCount, List<String> columns, String idColumn) {
        public ExtractResult(String sourceTable, int rowCount, List<String> columns) {
            this(sourceTable, rowCount, columns, null);
        }
    }

    // ───────────────────────── format detection ─────────────────────────

    /** csv | json | xlsx | xls — from the explicit format, else the name, else the bytes. */
    public static String detectFormat(String explicit, String fileName, byte[] bytes) {
        if (explicit != null && !explicit.isBlank()) {
            String f = explicit.trim().toLowerCase(Locale.ROOT);
            if (Set.of("csv", "json", "xlsx", "xls").contains(f)) return f;
        }
        String n = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (n.endsWith(".xlsx") || n.endsWith(".xlsm")) return "xlsx";
        if (n.endsWith(".xls")) return "xls";
        if (n.endsWith(".json")) return "json";
        if (n.endsWith(".csv") || n.endsWith(".txt") || n.endsWith(".tsv")) return "csv";
        if (bytes != null && bytes.length >= 8) {
            if (bytes[0] == 0x50 && bytes[1] == 0x4B) return "xlsx";                       // PK zip
            if ((bytes[0] & 0xFF) == 0xD0 && (bytes[1] & 0xFF) == 0xCF) return "xls";    // OLE2
            String head = new String(bytes, 0, Math.min(bytes.length, 64), StandardCharsets.UTF_8).trim();
            if (head.startsWith("[") || head.startsWith("{")) return "json";
        }
        return "csv";
    }

    /** Decode CSV bytes: UTF-8 (BOM stripped) unless it is not valid UTF-8, then Windows-1252. */
    public static String decodeText(byte[] bytes) {
        if (bytes == null) return "";
        int off = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) off = 3;
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE)
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF)
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes, off, bytes.length - off)).toString();
        } catch (Exception notUtf8) {
            return new String(bytes, off, bytes.length - off, Charset.forName("windows-1252"));
        }
    }

    /** Lower-case hex SHA-256 of the upload (file identity for the import history / repeat guard). */
    public static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(bytes == null ? new byte[0] : bytes)) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new ExtractException("SHA-256 unavailable: " + e.getMessage());
        }
    }

    // ───────────────────────── entry points ─────────────────────────

    /**
     * One door for every format. {@code targetHint} is the target table the id
     * columns should be looked up for (family / income / …); null = none.
     */
    @Transactional
    public ExtractResult extract(ImportRun run, String sourceTable, String format, byte[] bytes, String targetHint) {
        String f = format == null ? "csv" : format;
        return switch (f) {
            case "json" -> extractJson(run, sourceTable, decodeText(bytes), targetHint);
            case "xlsx", "xls" -> extractExcel(run, sourceTable, bytes, targetHint);
            default -> extractCsv(run, sourceTable, decodeText(bytes), targetHint);
        };
    }

    /** Extract CSV text (first non-blank row is the header). Legacy signature, no id column. */
    @Transactional
    public ExtractResult extractCsv(ImportRun run, String sourceTable, String csvText) {
        return extractCsv(run, sourceTable, csvText, null);
    }

    @Transactional
    public ExtractResult extractCsv(ImportRun run, String sourceTable, String csvText, String targetHint) {
        List<List<String>> rows = CsvUtil.parse(csvText, CsvUtil.sniffDelimiter(csvText));
        if (rows.isEmpty())
            throw new ExtractException("CSV is empty");

        // First non-blank row = header.
        int h = 0;
        while (h < rows.size() && CsvUtil.isBlankRow(rows.get(h))) h++;
        if (h >= rows.size())
            throw new ExtractException("CSV has no header row");

        List<String> header = dedupeHeader(rows.get(h));
        List<Map<String, Object>> records = new ArrayList<>();
        for (int i = h + 1; i < rows.size(); i++) {
            List<String> cells = rows.get(i);
            if (CsvUtil.isBlankRow(cells)) continue;
            Map<String, Object> obj = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                String val = c < cells.size() ? cells.get(c) : null;
                obj.put(header.get(c), val);
            }
            records.add(obj);
        }
        return store(run, sourceTable, header, records, targetHint, "CSV");
    }

    /** Extract a JSON array of objects (each object becomes one staged record). Legacy signature. */
    @Transactional
    public ExtractResult extractJson(ImportRun run, String sourceTable, String jsonText) {
        return extractJson(run, sourceTable, jsonText, null);
    }

    @Transactional
    public ExtractResult extractJson(ImportRun run, String sourceTable, String jsonText, String targetHint) {
        List<Map<String, Object>> records;
        try {
            records = MAPPER.readValue(jsonText, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new ExtractException("Expected a JSON array of objects: " + e.getMessage());
        }
        List<String> columns = new ArrayList<>();
        List<Map<String, Object>> clean = new ArrayList<>();
        for (Map<String, Object> rec : records) {
            if (rec == null) continue;
            for (String k : rec.keySet()) if (!columns.contains(k)) columns.add(k);
            clean.add(rec);
        }
        return store(run, sourceTable, columns, clean, targetHint, "JSON");
    }

    /**
     * Extract the first non-empty sheet of an Excel workbook (.xlsx or .xls). The
     * first non-blank row is the header. Cells are rendered the way Excel shows
     * them, except dates (ISO) and whole numbers (no ".0").
     */
    @Transactional
    public ExtractResult extractExcel(ImportRun run, String sourceTable, byte[] bytes, String targetHint) {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = null;
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet s = wb.getSheetAt(i);
                if (s.getPhysicalNumberOfRows() > 0) { sheet = s; break; }
            }
            if (sheet == null) throw new ExtractException("Workbook has no rows");

            DataFormatter fmt = new DataFormatter(Locale.US);
            FormulaEvaluator eval = wb.getCreationHelper().createFormulaEvaluator();
            int headerRow = -1;
            List<String> header = null;
            int lastCol = 0;
            for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < row.getLastCellNum(); c++) cells.add(cellText(row.getCell(c), fmt, eval));
                if (CsvUtil.isBlankRow(cells)) continue;
                headerRow = r; lastCol = row.getLastCellNum(); header = dedupeHeader(cells); break;
            }
            if (header == null) throw new ExtractException("Workbook has no header row");

            List<Map<String, Object>> records = new ArrayList<>();
            for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < Math.max(lastCol, row.getLastCellNum()); c++) cells.add(cellText(row.getCell(c), fmt, eval));
                if (CsvUtil.isBlankRow(cells)) continue;
                Map<String, Object> obj = new LinkedHashMap<>();
                for (int c = 0; c < header.size(); c++) obj.put(header.get(c), c < cells.size() ? cells.get(c) : null);
                records.add(obj);
            }
            return store(run, sourceTable, header, records, targetHint, "Excel/" + sheet.getSheetName());
        } catch (ExtractException e) {
            throw e;
        } catch (Exception e) {
            throw new ExtractException("Could not read the Excel workbook: " + e.getMessage());
        }
    }

    static String cellText(Cell cell, DataFormatter fmt, FormulaEvaluator eval) {
        if (cell == null) return null;
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            try { type = eval.evaluateFormulaCell(cell); } catch (Exception e) { return fmt.formatCellValue(cell); }
        }
        switch (type) {
            case BLANK: return null;
            case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    LocalDateTime dt = cell.getLocalDateTimeCellValue();
                    if (dt == null) return null;
                    return dt.toLocalTime().toSecondOfDay() == 0 ? dt.toLocalDate().format(ISO_DATE) : dt.format(ISO_DATETIME);
                }
                double d = cell.getNumericCellValue();
                if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
                return java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
            case STRING: return cell.getStringCellValue();
            case ERROR: return null;
            default: return fmt.formatCellValue(cell, eval);
        }
    }

    // ───────────────────────── store ─────────────────────────

    private ExtractResult store(ImportRun run, String sourceTable, List<String> header,
                                List<Map<String, Object>> records, String targetHint, String kind) {
        String table = normalizeTable(sourceTable);
        SourcePreset preset = SourcePreset.of(run.getSourcePreset());
        String idColumn = null;
        for (String t : (targetHint == null ? com.churchgeniuspro.util.EtlTargetSchema.tables() : List.of(targetHint))) {
            idColumn = preset.idColumnIn(t, header);
            if (idColumn != null) break;
        }
        if (idColumn == null && com.churchgeniuspro.util.EtlTargetSchema.isRecordTable(targetHint)) {
            // Phase 4: the admin said what the file holds, so that type's own id header
            // ("Event ID" in an events file — but NOT in a registrations file) is the row id.
            outer:
            for (String cand : com.churchgeniuspro.util.EtlTargetSchema.rowIdColumns(targetHint))
                for (String h : header) if (SourcePreset.norm(h).equals(cand)) { idColumn = h; break outer; }
        }
        if (idColumn == null && "family".equals(targetHint)) {
            // Phase 4: in a file the admin marked as PEOPLE, the person id is the row's own id
            // (it is only a link in giving / attendance / membership files).
            outer:
            for (String cand : List.of("personid", "individualid", "memberid", "peopleid", "contactid"))
                for (String h : header) if (SourcePreset.norm(h).equals(cand)) { idColumn = h; break outer; }
        }
        if (idColumn == null && !com.churchgeniuspro.util.EtlTargetSchema.isRecordTable(targetHint)) {
            // Any preset: a column literally named like a ROW id still beats the ordinal.
            // Person ids are deliberately not here: in a giving file they identify the
            // donor (a link), not the gift.
            for (String h : header) {
                String n = SourcePreset.norm(h);
                if (n.equals("id") || n.equals("recordid") || n.equals("externalid") || n.equals("rowid")
                        || n.equals("transactionid") || n.equals("paymentid") || n.equals("donationid")) { idColumn = h; break; }
            }
        }

        rawRepo.deleteByRunIdAndSourceTable(run.getId(), table);
        List<StagingRaw> batch = new ArrayList<>(records.size());
        int rowNum = 0;
        for (Map<String, Object> obj : records) {
            rowNum++;
            String sourceId = null;
            if (idColumn != null) {
                Object v = obj.get(idColumn);
                if (v != null && !String.valueOf(v).isBlank()) sourceId = String.valueOf(v).trim();
            }
            if (sourceId == null || sourceId.length() > 120) sourceId = String.valueOf(rowNum);
            batch.add(rawRow(run, table, sourceId, obj));
        }
        rawRepo.saveAll(batch);
        log.info("ETL run {} extracted {} {} rows into source table '{}' (id column: {})",
            run.getId(), rowNum, kind, table, idColumn == null ? "row number" : idColumn);
        return new ExtractResult(table, rowNum, header, idColumn);
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
