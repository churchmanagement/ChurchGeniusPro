package com.churchgeniuspro.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal, dependency-free CSV reader following RFC&nbsp;4180: quoted fields,
 * escaped quotes ({@code ""}), and commas/newlines embedded inside quotes.
 * Handles both {@code \n} and {@code \r\n} line endings. Used by the ETL extract
 * stage to turn an uploaded CSV into rows before they are stored verbatim as JSON.
 *
 * <p>Intentionally small and side-effect free so it can be unit-tested in
 * isolation. It does not infer types or trim values beyond what RFC 4180 requires.
 */
public final class CsvUtil {

    private CsvUtil() {}

    /** Parse the whole CSV text (comma-separated) into a list of rows, each a list of string cells. */
    public static List<List<String>> parse(String text) {
        return parse(text, ',');
    }

    /**
     * Migration Phase 1: pick the delimiter from the header line — the candidate
     * ({@code , ; TAB |}) that occurs most often outside quotes; comma on a tie or
     * when none occurs. Semicolon exports (European Excel) and tab exports were
     * previously read as a single column.
     */
    public static char sniffDelimiter(String text) {
        if (text == null || text.isEmpty()) return ',';
        int end = text.indexOf('\n'); if (end < 0) end = text.length();
        String line = text.substring(0, end);
        char[] cands = {',', ';', '\t', '|'};
        int[] counts = new int[cands.length];
        boolean q = false;
        for (char c : line.toCharArray()) {
            if (c == '"') { q = !q; continue; }
            if (q) continue;
            for (int i = 0; i < cands.length; i++) if (c == cands[i]) counts[i]++;
        }
        int best = 0;
        for (int i = 1; i < cands.length; i++) if (counts[i] > counts[best]) best = i;
        return counts[best] == 0 ? ',' : cands[best];
    }

    /** Parse with an explicit delimiter (see {@link #sniffDelimiter}). Quotes and newlines as RFC 4180. */
    public static List<List<String>> parse(String text, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null || text.isEmpty()) return rows;

        // Strip a UTF-8 BOM if present.
        if (text.charAt(0) == '\uFEFF') text = text.substring(1);

        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        int n = text.length();

        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {  // escaped quote
                        cell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cell.append(c);
                }
                continue;
            }

            if (c == delimiter) {
                row.add(cell.toString());
                cell.setLength(0);
                continue;
            }
            switch (c) {
                case '"':
                    inQuotes = true;
                    break;
                case '\r':
                    // swallow; the '\n' (or end) finishes the row
                    if (i + 1 < n && text.charAt(i + 1) == '\n') i++;
                    row.add(cell.toString());
                    cell.setLength(0);
                    rows.add(row);
                    row = new ArrayList<>();
                    break;
                case '\n':
                    row.add(cell.toString());
                    cell.setLength(0);
                    rows.add(row);
                    row = new ArrayList<>();
                    break;
                default:
                    cell.append(c);
            }
        }

        // Flush the final cell/row if the text didn't end with a newline.
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    /** True if a parsed row is entirely empty (e.g. a trailing blank line). */
    public static boolean isBlankRow(List<String> row) {
        if (row == null || row.isEmpty()) return true;
        for (String c : row) {
            if (c != null && !c.trim().isEmpty()) return false;
        }
        return true;
    }
}
